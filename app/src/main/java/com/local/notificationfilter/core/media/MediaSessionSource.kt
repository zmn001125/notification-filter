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
    }

    /** 卸载（幂等）：解绑监听与全部会话回调，并清空快照。 */
    fun detach() {
        if (!attached) return
        attached = false
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
     * 把一份 metadata 摊平成 [MediaSnapshot] 并写入 Holder。
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
        runCatching {
            val metadata = controller.metadata
            val allStrings = buildList {
                metadata?.keySet()?.forEach { key ->
                    val text = runCatching { metadata.getString(key) }.getOrNull()
                    if (!text.isNullOrBlank()) add(key to text)
                }
            }
            MediaSessionHolder.update(
                MediaSnapshot(
                    packageName = controller.packageName,
                    title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE),
                    displayTitle = metadata?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE),
                    displaySubtitle = metadata?.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE),
                    displayDescription = metadata?.getString(MediaMetadata.METADATA_KEY_DISPLAY_DESCRIPTION),
                    artist = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST),
                    album = metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM),
                    albumArtist = metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST),
                    genre = metadata?.getString(MediaMetadata.METADATA_KEY_GENRE),
                    composer = metadata?.getString(MediaMetadata.METADATA_KEY_COMPOSER),
                    writer = metadata?.getString(MediaMetadata.METADATA_KEY_WRITER),
                    year = metadata?.getString(MediaMetadata.METADATA_KEY_YEAR),
                    durationMs = metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION),
                    playing = controller.playbackState?.state == PlaybackState.STATE_PLAYING,
                    extras = allStrings,
                    updatedAtMs = System.currentTimeMillis(),
                ),
            )
        }.onFailure { NfLog.w(this, "读取媒体 metadata 失败: ${it.message}") }
    }
}
