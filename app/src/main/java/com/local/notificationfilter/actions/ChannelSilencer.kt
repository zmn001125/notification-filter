package com.local.notificationfilter.actions

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.net.Uri
import android.os.Process
import android.os.UserHandle
import android.service.notification.NotificationListenerService
import com.local.notificationfilter.data.prefs.ChannelOriginal
import com.local.notificationfilter.data.prefs.ChannelSilenceStore
import com.local.notificationfilter.util.NfLog

/**
 * 渠道静音器（V10 任务 B）：「静音消除」的落地实现。
 *
 * ## 平台事实（决定了本类的语义）
 * `NotificationListenerService.onNotificationPosted` 是在通知**已经发布、声音/振动已经发出
 * 之后**才回调的，因此**无法**拦住"这一条通知"的发声。唯一可持久生效的手段是把该通知所属的
 * `NotificationChannel` 改成静音（`setSound(null, null)` + `enableVibration(false)`）——
 * 这会**影响该渠道之后的所有通知**，因此本类的语义是：
 * **「静音消除」= 把该通知所属渠道静音 + 消除这条通知**（用户已在需求中确认接受）。
 *
 * ## 读与写的分工（关键实现细节）
 * - **读**：跨 App 读渠道只有一条公开路径 —— `NotificationListenerService
 *   .getNotificationChannels(pkg, user)`（NLS 专用，API 26+）。本类优先走它，因此能拿到
 *   源 App 渠道的**真实**原始设置（重要性 / 铃声 / 振动）并留底。
 * - **写**：`NotificationManager.createNotificationChannel(...)` 由调用方包名决定作用域
 *   （框架按 `opPackageName` 落到本 App 的渠道表）。也就是说：**对第三方 App 的渠道做静音
 *   覆盖，公开 API 并不提供**（隐藏 SystemApi `createNotificationChannels(pkg, ...)` 需要
 *   通知助理角色 / 系统权限，个人侧载拿不到）。因此这里按需求实现「尽力覆盖」，
 *   全程 `runCatching` 兜底，失败只记日志——**绝不因为系统限制让通知处理崩掉**。
 *
 * 结论（诚实记录）：静音**能否真正生效取决于系统是否放行**；无论是否生效，
 * 「消除」这一半总是照常执行，用户至少不会看到这条通知。若真机上仍然响铃，
 * 需要另行评估「通知助理角色 / 免打扰」方案（已在本任务报告中列为本轮已知限制）。
 *
 * ## 线程模型
 * 所有方法都是同步的短调用（渠道读写是本地 Binder），由管线在自己的单线程 scope 内调用；
 * [listener] 由 `NfNotificationListener` 在连接 / 拆除时 [attach] / [detach]。
 */
class ChannelSilencer(
    context: Context,
    private val store: ChannelSilenceStore,
) {

    private val appContext: Context = context.applicationContext

    @Volatile
    private var listener: NotificationListenerService? = null

    /** 已经尝试过静音的 `(pkg, channelId)`（同一渠道不重复建/覆盖，避免反复写系统）。 */
    private val mutedKeys = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    fun attach(service: NotificationListenerService) {
        listener = service
    }

    fun detach() {
        listener = null
    }

    /**
     * 静音 `(packageName, channelId)` 指向的渠道，并在静音**之前**留底原始设置。
     *
     * @param packageName 通知来源包名（本 App 自身会被直接拒绝：防递归）
     * @param user 通知所属用户（`StatusBarNotification.getUser()`，多用户 / 分身场景）；
     *   null 时回落当前进程用户
     * @param channelId 通知渠道 id（`Notification.getChannelId()`；可空 = 老式无渠道通知）
     * @return true = 已发起静音覆盖并留底；false = 拿不到渠道 / 系统拒绝 / 本 App 自身（已记日志）
     */
    fun mute(packageName: String, user: UserHandle?, channelId: String?): Boolean {
        if (packageName == appContext.packageName) {
            // 防递归：本 App 重发的通知不参与静音（否则会把自己的渠道改哑）
            NfLog.d(this, "静音消除跳过自身通知 pkg=$packageName")
            return false
        }
        val id = channelId?.takeIf { it.isNotBlank() } ?: run {
            NfLog.w(this, "静音消除跳过：通知无渠道 id pkg=$packageName")
            return false
        }
        val found = findChannel(packageName, user, id) ?: run {
            NfLog.w(this, "静音消除跳过：读不到渠道 pkg=$packageName channel=$id")
            return false
        }
        val source = found.channel

        // —— 静音前留底（只在首次写，之后不覆盖，见 ChannelSilenceStore.remember）——
        val recorded = store.remember(
            ChannelOriginal(
                packageName = packageName,
                channelId = id,
                channelName = source.name?.toString().orEmpty(),
                importance = source.importance,
                soundUri = source.sound?.toString(),
                shouldVibrate = source.shouldVibrate(),
                savedAt = System.currentTimeMillis(),
            ),
        )
        if (recorded) {
            NfLog.i(
                this,
                "已记录渠道原始设置 pkg=$packageName channel=$id " +
                    "importance=${source.importance} sound=${source.sound} " +
                    "vibrate=${source.shouldVibrate()}",
            )
        }

        val key = "$packageName\u0000$id"
        if (!mutedKeys.add(key)) {
            // 本进程已覆盖过同一渠道：不重复写系统（幂等，省一次 createNotificationChannel）
            return true
        }
        return applyMute(found, packageName, user ?: Process.myUserHandle(), id)
    }

    /**
     * 恢复某个渠道到静音前的原始设置（能力已备，本轮不接 UI）。
     *
     * 语义：有留底才恢复（没有留底说明这个渠道不是我们静音的，不能瞎改）；
     * 恢复成功后删掉留底，下次静音会重新留底一次。
     *
     * @return true = 已按原值重建渠道并清掉留底
     */
    fun restore(packageName: String, channelId: String): Boolean {
        val original = store.original(packageName, channelId) ?: run {
            NfLog.w(this, "恢复渠道跳过：没有原始记录 pkg=$packageName channel=$channelId")
            return false
        }
        val manager = notificationManager() ?: return false
        return runCatching {
            val restored = NotificationChannel(
                channelId,
                original.channelName.ifBlank { channelId },
                // IMPORTANCE_UNSPECIFIED(0) 不是合法的可建渠道重要性，收敛到
                // IMPORTANCE_MIN(1)..IMPORTANCE_HIGH(4)
                original.importance.coerceIn(
                    NotificationManager.IMPORTANCE_MIN,
                    NotificationManager.IMPORTANCE_HIGH,
                ),
            )
            // 铃声：有 Uri 还原 Uri，没有则保持无声（如实还原「原本就静音」）
            val uri = original.soundUri?.takeIf { it.isNotBlank() }?.let { Uri.parse(it) }
            if (uri != null) {
                restored.setSound(
                    uri,
                    AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                        .build(),
                )
            } else {
                restored.setSound(null, null)
            }
            restored.enableVibration(original.shouldVibrate)
            manager.createNotificationChannel(restored)
            store.forget(packageName, channelId)
            mutedKeys.remove("$packageName\u0000$channelId")
            NfLog.i(
                this,
                "已恢复渠道 pkg=$packageName channel=$channelId " +
                    "importance=${original.importance} sound=${original.soundUri} " +
                    "vibrate=${original.shouldVibrate}",
            )
            true
        }.onFailure {
            NfLog.e(this, "恢复渠道失败 pkg=$packageName channel=$channelId: ${it.message}")
        }.getOrDefault(false)
    }

    /** 恢复全部留有原始记录的渠道；返回成功条数。 */
    fun restoreAll(): Int {
        var ok = 0
        store.originals().forEach { original ->
            if (restore(original.packageName, original.channelId)) ok++
        }
        if (ok > 0) NfLog.i(this, "已恢复 $ok 个渠道")
        return ok
    }

    // ==================== 内部实现 ====================

    /**
     * 读到的渠道 + 它是否是**源 App 的**渠道（而不是本包同 id 的渠道）。
     *
     * 这个布尔值决定了静音后能不能做「真的生效了吗」的自检，也决定了失效时能不能安全地
     * 删掉我们刚建的镜像渠道（删本包渠道是允许的，删别人的不行）。
     */
    private data class FoundChannel(val channel: NotificationChannel, val fromSource: Boolean)

    /**
     * 读渠道：优先「源 App 的渠道表」（NLS 专用 API，能拿到别人的渠道），
     * 失败再回落本包（覆盖「渠道恰好属于本 App」以及 NLS 未连接时的兜底）。
     *
     * [user] 用 `StatusBarNotification.getUser()` 拿到的 UserHandle 透传——
     * 不能用 `UserHandle.of(userId)`：那不在公开 SDK 里（编译期就解析不到）。
     */
    private fun findChannel(packageName: String, user: UserHandle?, channelId: String): FoundChannel? {
        val handle = user ?: Process.myUserHandle()
        readSourceChannel(packageName, handle, channelId)?.let {
            return FoundChannel(it, fromSource = true)
        }
        val own = runCatching { notificationManager()?.getNotificationChannel(channelId) }.getOrNull()
        return own?.let { FoundChannel(it, fromSource = false) }
    }

    /** 通过 NLS 读**源 App** 的某个渠道（拿不到 / 非 NLS 场景 → null）。 */
    private fun readSourceChannel(
        packageName: String,
        handle: UserHandle,
        channelId: String,
    ): NotificationChannel? {
        val service = listener ?: return null
        return runCatching {
            // API 26+：NLS 读他人渠道的唯一公开路径；多用户 / 分身靠 UserHandle 区分
            service.getNotificationChannels(packageName, handle)?.firstOrNull { it.id == channelId }
        }.onFailure {
            NfLog.w(this, "读取源 App 渠道失败 pkg=$packageName: ${it.message}")
        }.getOrNull()
    }

    /**
     * 执行静音覆盖（尽力而为）+ **生效自检**。
     *
     * 流程：
     * 1. 用源渠道的名称 / 描述 / 分组造一个「静音版」渠道并 `createNotificationChannel`；
     * 2. 若是源 App 的渠道 → 用 NLS **回读**一次，确认它真的变成「无声 + 不振动」；
     * 3. 没变成 → 说明系统按调用方包名落了这次覆盖（公开 API 的已知限制）：**删掉我们刚建的
     *    镜像渠道**（避免污染本 App 的通知类别列表），并记一条明确的告警日志；
     *    此时「消除」这个动作照常生效，只是「静音」这半没有落地。
     *
     * 为什么值得多读一次：这是**唯一**能如实告诉真机「静音到底有没有用」的手段，
     * 也避免用户以为开了开关、其实一直没静音。成本只有一次本地 Binder 读。
     */
    private fun applyMute(
        found: FoundChannel,
        packageName: String,
        handle: UserHandle,
        channelId: String,
    ): Boolean {
        val manager = notificationManager() ?: run {
            NfLog.w(this, "静音消除跳过：拿不到 NotificationManager pkg=$packageName")
            return false
        }
        val source = found.channel
        return runCatching {
            val muted = NotificationChannel(
                channelId,
                source.name?.toString()?.ifBlank { channelId } ?: channelId,
                source.importance.coerceIn(
                    NotificationManager.IMPORTANCE_MIN,
                    NotificationManager.IMPORTANCE_HIGH,
                ),
            ).apply {
                description = source.description
                group = source.group
                setSound(null, null)
                enableVibration(false)
                enableLights(false)
            }
            manager.createNotificationChannel(muted)

            // 渠道本来就属于本 App（NLS 未连接等）：覆盖的就是自己，直接算成功
            if (!found.fromSource) {
                NfLog.i(this, "静音消除：已把本包渠道设为静音 pkg=$packageName channel=$channelId")
                return true
            }

            val after = readSourceChannel(packageName, handle, channelId)
            val applied = after != null && after.sound == null && !after.shouldVibrate()
            if (applied) {
                NfLog.i(
                    this,
                    "静音消除：源渠道已静音 pkg=$packageName channel=$channelId " +
                        "（Android 限制：无法只静音单条通知，本渠道后续通知一并静音）",
                )
            } else {
                // 系统只允许改本包渠道 → 撤回镜像渠道，避免本 App 通知类别里堆垃圾
                runCatching { manager.deleteNotificationChannel(channelId) }
                NfLog.w(
                    this,
                    "静音消除未生效 pkg=$packageName channel=$channelId：" +
                        "系统未允许修改其它应用的渠道（公开 API 只能作用于本包，已撤回镜像渠道）。" +
                        "消除动作仍照常执行。",
                )
            }
            applied
        }.onFailure {
            // 系统不允许改他人渠道时会在这里失败：只记日志，不影响消除动作
            NfLog.e(this, "静音渠道失败 pkg=$packageName channel=$channelId: ${it.message}")
        }.getOrDefault(false)
    }

    private fun notificationManager(): NotificationManager? = runCatching {
        appContext.getSystemService(NotificationManager::class.java)
    }.getOrNull()
}
