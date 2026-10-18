package com.local.notificationfilter.core.media

import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import com.local.notificationfilter.service.NfNotificationListener
import com.local.notificationfilter.util.NfLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 媒体会话采集源（V10.8）。
 *
 * ## 为什么需要它
 * 媒体信息**不在通知里**，而在 `MediaSession` 的 metadata 里。
 * 不同播放器往哪个键塞什么内容差别很大（`METADATA_KEY_TITLE` 多数放曲目标题，
 * 也有放当前播放进度或自定义文案的），且播放器的通知通道可能一条都不发。
 * 只监听通知，永远拿不到这类数据。
 *
 * ## 权限
 * 本 App 已是**已启用的通知监听者**，因此
 * `MediaSessionManager.getActiveSessions(自身 ComponentName)` 可直接用，
 * 不需要 `MEDIA_CONTENT_CONTROL`（系统权限，侧载拿不到）。
 *
 * ## 开销（符合项目「无轮询、无 wake lock」红线）
 * - 唯一事件源是系统的会话增减回调 + 每个会话的 `onMetadataChanged`，**纯事件驱动**；
 * - 每次回调只做「读 metadata → 拼一个不可变对象 → 写 StateFlow」，无 IO；
 * - [MediaSessionHolder.enabled] 为 false 时（没有任何规则用到 `media.*` 字段），
 *   回调进来会**立刻返回**，不构造对象、不写 StateFlow —— 这是「按需采集」的落点。
 */
class MediaSessionSource(context: Context) {

    private val appContext: Context = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private val bindings = HashMap<MediaController, MediaController.Callback>()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var refreshJob: Job? = null

    /**
     * 媒体内容**真的有了新值**时的回调（V11.6）。
     *
     * 用途：让上层把「流水里这条通知的字段」和规则看到的内容跟上最新媒体信息，
     * 而不是等到播放器下一次重发通知。**只在内容变化时触发**（同值不触发），
     * 上层据此做节流，不会因为每秒采样而刷库。
     */
    var onMediaChanged: ((packageName: String) -> Unit)? = null

    @Volatile
    private var attached = false

    private val sessionsChangedListener =
        MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
            bindAll(controllers.orEmpty())
        }

    /** 挂载（幂等）。先注册会话变化监听，再立即读一次当前会话。 */
    fun attach() {
        if (attached) return
        attached = true
        runCatching {
            val manager = appContext.getSystemService(Context.MEDIA_SESSION_SERVICE)
                as MediaSessionManager
            val component = ComponentName(appContext, NfNotificationListener::class.java)
            manager.addOnActiveSessionsChangedListener(sessionsChangedListener, component, handler)
            bindAll(manager.getActiveSessions(component))
            NfLog.i(this, "媒体会话采集已挂载（${bindings.size} 个会话）")
        }.onFailure { NfLog.w(this, "媒体会话采集挂载失败: ${it.message}") }
    }

    /**
     * V13.1 耗电修复：**按需启停兜底轮询**（原实现在 `attach()` 里无条件启动，
     * 于是即使 [MediaSessionHolder.enabled] 为 false——没有任何规则引用 `media.*`——
     * 也有一个协程每 15 秒醒来一次，一天 5760 次无意义唤醒；前台服务不让进程进入 Doze，
     * 这些唤醒全部按满速执行，是「什么都没开也耗电」的直接来源）。
     *
     * 现在只有 [MediaSessionHolder.enabled] 为 true 时才存在这个循环；
     * 规则改回不引用 `media.*` 时 [stopRefresher] 立刻取消，回到零唤醒。
     */
    fun setPollingEnabled(enabled: Boolean) {
        if (enabled) startRefresher() else stopRefresher()
    }

    /**
     * 立即重新读取所有已绑定会话的 metadata（V10.9）。
     *
     * 为什么必须有它：采集默认是关的，`attach()` 时 `publish()` 全部提前返回，
     * Holder 一直是空的。等用户打开字段面板把 enabled 置 true 时，
     * **如果歌曲正暂停、或下一次 metadata 变化还没来，就永远不会再有回调** →
     * 面板上只得一片空白，看起来像"没抓到"。这里主动读一次，立刻有值。
     */
    fun refreshNow() {
        val manager = runCatching {
            appContext.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
        }.getOrNull() ?: return
        runCatching {
            val component = ComponentName(appContext, NfNotificationListener::class.java)
            val current = manager.getActiveSessions(component)
            if (current.isNotEmpty()) bindAll(current)
            // 已绑定的也重读一次（可能不在 current 里但仍在册）
            bindings.keys.toList().forEach { publish(it) }
        }.onFailure { NfLog.w(this, "刷新媒体会话失败: ${it.message}") }
        // V11.6：回调拿不到时也能立刻取到最新值
        refreshFromSessions()
    }

    /**
     * **V11.6：兜底实时刷新。**
     *
     * ## 为什么需要它（真机实测结论）
     * 网易云这类把歌词塞进 `METADATA_KEY_TITLE` 的播放器，`MediaSession` 里的 metadata
     * 确实在逐句变化，但 `MediaController.Callback.onMetadataChanged` **并不保证送到**
     * （实测：只有播放/暂停这类状态切换、或重新取会话时才拿得到新歌词，
     * 于是「媒体标题」看起来很久才变一次）。
     * 而 `MediaController.getMetadata()` 读的是**该 controller 创建时的缓存**，
     * 不重新取会话就永远是旧值——这就是字段一直不更新的根因。
     *
     * 所以这里按需重新 `getActiveSessions()`：
     * 每次都会拿到**新的 controller**（构造时从 session 读一次当前 metadata），
     * 于是能绕开失效的回调，拿到最新的歌词/标题。
     *
     * ## 开销（项目「无轮询」红线的唯一例外，边界写死）
     * - V13.1：`enabled == false`（没有任何规则引用 `media.*`）→ **循环根本不存在**
     *   （由 [setPollingEnabled] 启停，见那里的说明）；
     * - 有会话在播放 → [POLL_PLAYING_MS]（V13.1 起 1 秒一次；歌词换句粒度 2~5 秒，
     *   原来 500ms 有一半是白跑的唤醒）；
     * - 没在播放 → [POLL_IDLE_MS]（V13.1 起 60 秒一次，只为兜底发现「刚开始播放」；
     *   正常路径靠 `onPlaybackStateChanged` 回调，这里只是兜底，不需要 15 秒）。
     *   暂停/停止后不会继续高频唤醒。
     */
    fun startRefresher() {
        if (refreshJob?.isActive == true) return
        refreshJob = scope.launch {
            while (isActive) {
                val playing = MediaSessionHolder.enabled &&
                    MediaSessionHolder.byPackage.value.values.any { it.playing }
                delay(if (playing) POLL_PLAYING_MS else POLL_IDLE_MS)
                if (!MediaSessionHolder.enabled) continue
                refreshFromSessions()
            }
        }
    }

    /** 停掉兜底轮询（[setPollingEnabled] 的另一半）。取消后回到零唤醒。 */
    fun stopRefresher() {
        refreshJob?.cancel()
        refreshJob = null
    }

    /**
     * 重新取一次活动会话并逐个读 metadata（新 controller = 新缓存，能绕过失效回调）。
     *
     * ## 读取与写入分离（V12 性能修补）
     * 读 metadata 是 binder 调用，播放中每 500ms 就要读一轮，绝不能放在主线程；
     * 因此这里在**协程线程**把每个新 controller 逐个读成不可变快照，
     * 再**一次性**回主线程批量写 Holder（原来是每个 controller 各读各写一遍，
     * 每 500ms 都有一批 binder 读取跑在主线程上）。
     *
     * ## 为什么不给新 controller 注册回调
     * 轮询路径**不**在拿到的这些新 controller 上 `registerCallback`
     * （注册只发生在 [bindAll]），所以会话侧不会累积回调；
     * 新 controller 读完即被 GC，这是「拿最新 metadata」的必要代价。
     * 边界由 [MediaSessionHolder.enabled] 门控 + 500ms / 15s 两档间隔写死，不会失控。
     */
    private fun refreshFromSessions() {
        if (!MediaSessionHolder.enabled) return
        // 取会话是 binder 调用 → 放在协程线程，别在主线程做
        val controllers = runCatching {
            val manager = appContext.getSystemService(Context.MEDIA_SESSION_SERVICE)
                as MediaSessionManager
            val component = ComponentName(appContext, NfNotificationListener::class.java)
            manager.getActiveSessions(component)
        }.onFailure { NfLog.w(this, "实时刷新媒体会话失败: ${it.message}") }
            .getOrDefault(emptyList())
        if (controllers.isEmpty()) return
        // 读 metadata 同样是 binder 调用：播放中每 500ms 一轮，放在主线程会持续抢帧，
        // 所以这一步留在协程线程逐个读成快照，单个 controller 失败只丢它自己。
        val snapshots = controllers.mapNotNull { controller ->
            runCatching { readSnapshot(controller) }
                .onFailure { NfLog.w(this, "读取媒体 metadata 失败: ${it.message}") }
                .getOrNull()
        }
        // 写 Holder **统一回主线程**：回调路径（onMetadataChanged）本来就跑在主线程，
        // 这样所有写入串行，不会和轮询线程产生并发丢更新。
        handler.post {
            if (!MediaSessionHolder.enabled) return@post
            snapshots.forEach { snapshot -> applySnapshot(snapshot) }
        }
    }

    /** 卸载（幂等）：解绑监听与全部会话回调，并清空快照。 */
    fun detach() {
        if (!attached) return
        attached = false
        refreshJob?.cancel()
        refreshJob = null
        runCatching {
            val manager = appContext.getSystemService(Context.MEDIA_SESSION_SERVICE)
                as MediaSessionManager
            manager.removeOnActiveSessionsChangedListener(sessionsChangedListener)
        }.onFailure { NfLog.w(this, "移除媒体会话监听失败: ${it.message}") }
        bindings.forEach { (controller, callback) ->
            runCatching { controller.unregisterCallback(callback) }
        }
        bindings.clear()
        MediaSessionHolder.reset()
        NfLog.i(this, "媒体会话采集已卸载")
    }

    private fun bindAll(controllers: List<MediaController>) {
        val alive = controllers.toHashSet()
        bindings.keys.filter { it !in alive }.forEach { stale ->
            bindings.remove(stale)?.let { cb -> runCatching { stale.unregisterCallback(cb) } }
        }
        controllers.forEach { controller ->
            if (bindings.containsKey(controller)) return@forEach
            val callback = object : MediaController.Callback() {
                override fun onMetadataChanged(metadata: MediaMetadata?) = publish(controller)

                override fun onPlaybackStateChanged(state: PlaybackState?) = publish(controller)

                override fun onSessionDestroyed() {
                    bindings.remove(controller)?.let { cb ->
                        runCatching { controller.unregisterCallback(cb) }
                    }
                    MediaSessionHolder.remove(controller.packageName)
                }
            }
            runCatching {
                controller.registerCallback(callback, handler)
                bindings[controller] = callback
                // 立即读一次：播放器可能早就在播了，不等下一次 metadata 变化
                publish(controller)
            }.onFailure {
                NfLog.w(this, "绑定媒体会话失败 pkg=${controller.packageName}: ${it.message}")
            }
        }
    }

    /**
     * 读一份 metadata 摊平成 [MediaSnapshot] 并写入 Holder（**回调路径入口**）。
     *
     * 调用方全部是主线程上的即时读取：`bindAll` 对新会话的立即读、
     * `onMetadataChanged` / `onPlaybackStateChanged` 回调（注册时带了主线程 handler）、
     * 以及 `refreshNow()` 对已绑定会话的重读。轮询路径不走这里——
     * 那边的 binder 读取已下沉到协程线程（见 [refreshFromSessions]）。
     *
     * 「所有字段」的口径：
     * - 标准字段逐个显式读取（title / displayTitle / artist / album / …），
     *   这样规则里可以直接看到语义化的键名；
     * - **其余所有键**（含播放器自定义键）
     *   原样进 [MediaSnapshot.extras]，规则侧用 `media.<原键名>` 访问。
     */
    private fun publish(controller: MediaController) {
        // 没有任何规则用到 media.* 时直接返回：不构造对象、不写 StateFlow
        if (!MediaSessionHolder.enabled) return
        // 读可能抛异常（binder 断开等）：失败只丢这一次，不影响其他会话
        val snapshot = runCatching { readSnapshot(controller) }
            .onFailure { NfLog.w(this, "读取媒体 metadata 失败: ${it.message}") }
            .getOrNull() ?: return
        applySnapshot(snapshot)
    }

    /**
     * 把一份 metadata 摊平成 [MediaSnapshot]（**只读不写，可抛异常，调用方负责兜底**）。
     *
     * ## 为什么与写入拆开（V12 性能修补）
     * metadata 的每个键都是 binder 调用，播放中每 500ms 就要读一轮，
     * 这类读取不能放主线程（会持续抢帧）；而 Holder 的写入必须留在主线程串行，
     * 避免与回调路径（onMetadataChanged 等）竞争丢更新。
     * 于是「读」与「写」拆成两个函数：读可以在协程线程，写只在主线程。
     *
     * 「所有字段」的口径：
     * - 标准字段逐个显式读取（title / displayTitle / artist / album / …），
     *   这样规则里可以直接看到语义化的键名；
     * - **其余所有键**（含播放器自定义键）
     *   原样进 [MediaSnapshot.extras]，规则侧用 `media.<原键名>` 访问。
     */
    private fun readSnapshot(controller: MediaController): MediaSnapshot {
        val metadata = controller.metadata
        // V11.6：**每个键单独兜底**。此前显式那十几个 getString 是裸调用，
        // 只要有一个键实际存的是非 String 类型（`METADATA_KEY_YEAR` 等被部分播放器
        // 存成 Long 很常见），整份快照就会抛异常、被调用方的 runCatching 吞掉 →
        // 表现成「媒体字段很久不更新」，而不是只丢那一个字段。
        fun str(key: String): String? = runCatching { metadata?.getString(key) }.getOrNull()
        val allStrings = buildList {
            metadata?.keySet()?.forEach { key ->
                val text = runCatching { metadata.getString(key) }.getOrNull()
                if (!text.isNullOrBlank()) add(key to text)
            }
        }
        return MediaSnapshot(
            packageName = controller.packageName,
            title = str(MediaMetadata.METADATA_KEY_TITLE),
            displayTitle = str(MediaMetadata.METADATA_KEY_DISPLAY_TITLE),
            displaySubtitle = str(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE),
            displayDescription = str(MediaMetadata.METADATA_KEY_DISPLAY_DESCRIPTION),
            artist = str(MediaMetadata.METADATA_KEY_ARTIST),
            album = str(MediaMetadata.METADATA_KEY_ALBUM),
            albumArtist = str(MediaMetadata.METADATA_KEY_ALBUM_ARTIST),
            genre = str(MediaMetadata.METADATA_KEY_GENRE),
            composer = str(MediaMetadata.METADATA_KEY_COMPOSER),
            writer = str(MediaMetadata.METADATA_KEY_WRITER),
            year = str(MediaMetadata.METADATA_KEY_YEAR),
            durationMs = runCatching {
                metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION)
            }.getOrNull(),
            playing = controller.playbackState?.state == PlaybackState.STATE_PLAYING,
            extras = allStrings,
            updatedAtMs = System.currentTimeMillis(),
        )
    }

    /**
     * 把快照写入 Holder 并在内容真的变了时通知上层。**只允许在主线程调用。**
     *
     * 写入保持主线程串行：回调路径本来就在主线程，轮询路径也统一 post 回主线程后再写，
     * 两条路径对 Holder 的写入不会交叉。
     */
    private fun applySnapshot(snapshot: MediaSnapshot) {
        if (!MediaSessionHolder.enabled) return
        val changed = MediaSessionHolder.update(snapshot)
        // 只有内容真的变了才通知上层（上层据此刷新流水行，同值不重复写库）
        if (changed) onMediaChanged?.invoke(snapshot.packageName)
    }

    private companion object {
        /**
         * 播放中：每秒取一次（歌词换句粒度 2~5 秒，1 秒足够跟手）。
         *
         * V13.1 由 500ms 放宽到 1000ms：一次 tick 是 `getActiveSessions` +
         * 每个会话 2 次 binder，播放时每 500ms 一轮纯属过度采样，砍一半唤醒。
         */
        const val POLL_PLAYING_MS = 1_000L

        /**
         * 未播放：60 秒取一次，只为兜底发现「刚开始播放」。
         *
         * V13.1 由 15s 放宽到 60s：正常路径有 `onPlaybackStateChanged` 回调，
         * 这里只是回调丢失时的兜底，15 秒的唤醒密度没有意义。
         */
        const val POLL_IDLE_MS = 60_000L
    }
}
