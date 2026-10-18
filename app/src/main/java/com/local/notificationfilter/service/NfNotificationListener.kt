package com.local.notificationfilter.service

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.local.notificationfilter.NfApplication
import com.local.notificationfilter.core.media.MediaSessionHolder
import com.local.notificationfilter.core.media.MediaSessionSource
import com.local.notificationfilter.core.model.Rule
import com.local.notificationfilter.util.NfLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 通知监听服务（P0-1）：系统在授予通知使用权后自动绑定。
 *
 * HyperOS3 保活（架构 §1.3(1)）：
 * - onListenerConnected 按「常驻通知开关」决定是否前台化（specialUse 类型）：
 *   开 → startForeground(1001) 发布常驻「过滤中/已暂停」通知（失败降级为普通 ongoing 通知）；
 *   关（默认）→ 不 startForeground，服务以纯后台形态运行（V2 需求⑤，决策 D7）；
 * - 重启后监听权仍在时系统会自动重新绑定，BootReceiver 负责重排延时任务与常驻通知。
 *
 * 连接状态（V2 需求⑥）：onListenerConnected / onListenerDisconnected / onDestroy
 * 推送 [ListenerStatusHolder]，作为 UI 三态展示的唯一事实来源。
 */
class NfNotificationListener : NotificationListenerService() {

    /** 当前管线；断连 / 销毁时由 [teardown] 置空，[onNotificationPosted] 以 null 判断就绪。 */
    @Volatile
    private var pipeline: NotificationPipeline? = null

    /**
     * V10.8：媒体会话采集源（媒体字段的唯一来源）。
     *
     * 挂载成本很低（系统回调驱动、无轮询），但**是否真正读取数据**由
     * [MediaSessionHolder.enabled] 决定：没有任何规则引用 `media.*` 字段时，
     * 回调进来立刻返回，不构造对象、不写 StateFlow —— 这就是「按需采集」。
     */
    private var mediaSource: MediaSessionSource? = null

    /**
     * 服务作用域：[teardown] 会把它 cancel（防止断连后残留协程），
     * 因此重连时必须由 [ensureServiceScope] 重建，否则 launch 到已取消的 scope 会静默不执行。
     */
    private var serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    companion object {
        /**
         * 当前监听服务实例（系统只绑定一个，架构 §5.4）。
         * 首页「常驻通知开关」通过它调用 [applyOngoingVisibility] 即时生效；
         * @Volatile 保证 UI 线程与服务线程间的可见性。
         */
        @Volatile
        var instance: NfNotificationListener? = null
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        NfLog.i(this, "监听服务已连接")
        ListenerStatusHolder.setConnected()
        // 断连→重连（权限变更 / 系统 UI 重启 / 省电策略抖动）后旧 scope 已被 teardown 取消
        ensureServiceScope()

        val container = (application as NfApplication).container
        ActiveNotificationsProvider.attach(this)
        DeviceStateProvider.attach(this)
        // V4：叠加层控制器持有 applicationContext（弹幕 / 悬浮窗需要）
        OverlayController.attach(this)
        // V7 需求 7：条件编排器挂载（订阅 3 个事件源 + 分钟 ticker，全部按需启停）。
        // 与 DeviceStateProvider 同点：只在这里 attach，teardown 里 detach。
        container.overlayConditionArbiter.attach()

        // V10.8：媒体会话采集（按需启用，见 refreshMediaCollection）
        // V11.6：内容真变了就回调管线去刷新流水里的 media.*（播放器的元数据变化
        // 不一定伴随重发通知，只等通知会拿不到实时歌词）。
        mediaSource = MediaSessionSource(this).also { src ->
            src.onMediaChanged = { pkg -> pipeline?.onMediaChanged(pkg) }
            src.attach()
        }
        refreshMediaCollection()

        val pipe = NotificationPipeline(container, this)
        container.actionExecutor.attach(this)
        pipeline = pipe

        // V7：按数据库里的最新规则编译一次条件索引（规则集可能在服务断开期间被改过）
        serviceScope.launch {
            runCatching { container.overlayConditionArbiter.compileFromDb() }
                .onFailure { NfLog.w(this@NfNotificationListener, "条件索引编译失败: ${it.message}") }
        }

        // 常驻通知开关：关（默认）→ 纯后台形态运行；开 → 前台化并刷新文案（V2 需求⑤）
        serviceScope.launch {
            val showOngoing = runCatching {
                container.settingsRepository.isShowOngoing()
            }.getOrDefault(false)
            if (showOngoing) {
                promoteToForeground()
                // 用真实总开关状态刷新常驻通知文案
                runCatching { pipe.refreshOngoingNotification() }
            } else {
                NfLog.i(this@NfNotificationListener, "常驻通知开关为关闭，服务以纯后台形态运行")
            }
        }
    }

    override fun onListenerDisconnected() {
        NfLog.i(this, "监听服务断开")
        ListenerStatusHolder.setDisconnected()
        // P1-9：必须先拆干净旧管线，否则旧 CoroutineScope / Mutex 不会 cancel，
        // 重连后旧管线仍在处理已排队的通知，与新管线重复写流水、重复执行动作。
        teardown()
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        pipeline?.process(sbn) ?: NfLog.w(this, "管线未就绪，丢弃 key=${sbn.key}")
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        pipeline?.onRemoved(sbn)
    }

    /**
     * V10 任务 A：规则保存成功后调用 —— 对**通知栏里已经存在的**通知立即重跑一遍。
     *
     * 为什么不自己起协程：真正的批量处理跑在 [pipeline] 自己的单线程 scope 里（与
     * `onNotificationPosted` 串行，天然满足同 key 顺序性），本函数只做一次投递。
     * 也**特意不用** [serviceScope]：`teardown()` 会把它 cancel，而这里不需要任何 UI 侧时序，
     * 用管线的 scope 最稳。
     *
     * 断连场景（[pipeline] 已被置空）：静默跳过、只记一条日志 ——
     * 用户此时保存规则不应当看到任何崩溃或报错弹窗。
     *
     * @param reason 触发原因（日志用）
     */
    fun reprocessActiveAfterRuleChange(rule: Rule, reason: String = "规则保存") {
        val pipe = pipeline
        if (pipe == null) {
            NfLog.w(this, "监听未就绪，跳过存量通知补处理（$reason）")
            return
        }
        // V10.3：把"刚保存的这一条规则"透传下去——补处理只执行它，不跑其他规则
        pipe.reprocessActiveAfterRuleChange(rule, reason)
    }

    /**
     * 重新计算「是否需要采集媒体字段」并开关采集（V10.8）。
     *
     * 触发点：监听连接时、规则保存后。口径 = **是否有任何启用中的规则引用了 `media.*` 字段**。
     * 没有就完全不采（`MediaSessionHolder.enabled = false`），满足「零开销」要求。
     */
    fun refreshMediaCollection() {
        ensureServiceScope()
        serviceScope.launch {
            val needed = runCatching {
                (application as NfApplication).container.ruleRepository.getAll().any { rule ->
                    rule.enabled && rule.extrasFields.any { it.startsWith(MEDIA_FIELD_PREFIX) }
                }
            }.getOrDefault(false)
            val wasEnabled = MediaSessionHolder.enabled
            MediaSessionHolder.setEnabled(needed)
            // V13.1 耗电修复：兜底轮询随开关启停。没有规则引用 media.* 时循环根本不存在
            // （此前 attach() 无条件启动，一天 5760 次空转唤醒）。
            mediaSource?.setPollingEnabled(needed)
            NfLog.i(
                this@NfNotificationListener,
                "媒体字段采集：${if (needed) "已启用" else "未启用（没有规则引用 $MEDIA_FIELD_PREFIX）"}",
            )
            // V11.6：刚由关变开时立刻取一次，不用等兜底轮询的下一拍
            if (needed && !wasEnabled) mediaSource?.refreshNow()
        }
    }

    /**
     * 立即读一次媒体会话（V10.9）。
     *
     * 供「匹配字段」面板打开时调用：先把 `MediaSessionHolder.enabled` 置 true，
     * 再调本方法，用户就能立刻看到当前媒体标题/歌手，而不是等下一次 metadata 变化才出值。
     */
    fun refreshMediaNow() {
        mediaSource?.refreshNow()
    }

    override fun onDestroy() {
        NfLog.i(this, "监听服务销毁")
        ListenerStatusHolder.setDisconnected()
        instance = null
        teardown()
        super.onDestroy()
    }

    // ==================== 生命周期清理（P1-9） ====================

    /**
     * 拆除管线与所有 attach 过的单例：断连（[onListenerDisconnected]）与销毁（[onDestroy]）共用。
     *
     * **幂等**：可重复调用（各 detach 自身幂等、[OverlayController.dismissAll] 内部按主线程消息
     * 清空集合），连续调用两次不会崩。
     */
    private fun teardown() {
        pipeline?.shutdown()
        pipeline = null
        (application as? NfApplication)?.container?.actionExecutor?.detach()
        ActiveNotificationsProvider.detach()
        DeviceStateProvider.detach()
        // V10.8：先摘采集源，再让它清空 Holder（detach 内部已 reset）
        mediaSource?.detach()
        mediaSource = null
        // V7 需求 7：取消条件编排器的全部订阅（含分钟 ticker），并撤下条件卡片
        (application as? NfApplication)?.container?.overlayConditionArbiter?.detach()
        // V4：收掉所有叠加层（进程被回收时窗口由系统一并销毁）
        OverlayController.dismissAll()
        serviceScope.cancel()
    }

    /** 保证 [serviceScope] 可用：被 [teardown] 取消后重建一个（重连路径）。 */
    private fun ensureServiceScope() {
        if (serviceScope.isActive) return
        serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    }

    // ==================== 前台化 / 常驻通知显隐（V2 需求⑤） ====================

    private fun promoteToForeground() {
        val statusNotification = OngoingStatusNotification(this)
        runCatching {
            startForeground(
                OngoingStatusNotification.NOTIFICATION_ID,
                statusNotification.buildNotification(filtering = true),
            )
            NfLog.i(this, "已提升为前台服务")
        }.onFailure {
            NfLog.w(this, "前台化失败，降级为常驻通知: ${it.message}")
            statusNotification.show(filtering = true)
        }
    }

    /**
     * 切换常驻通知显隐（首页开关即时生效；架构 §5.4）。
     *
     * @param visible true = startForeground(1001, notification)；Android 12+ 后台启动
     *   前台服务可能抛 ForegroundServiceStartNotAllowedException，try-catch 兜底降级为
     *   普通常驻通知 notify（约定 8：系统调用失败不崩服务）。
     * @param visible false = stopForeground(STOP_FOREGROUND_REMOVE) + cancel 双保险取消通知，
     *   服务以纯后台形态继续监听。
     */
    fun applyOngoingVisibility(visible: Boolean) {
        if (visible) {
            // 断连期间 scope 已被 teardown 取消，这里重建后再 launch，否则开关会「点了没反应」
            ensureServiceScope()
            serviceScope.launch {
                val filtering = runCatching {
                    (application as NfApplication).container.settingsRepository.isMasterEnabled()
                }.getOrDefault(true)
                val statusNotification = OngoingStatusNotification(this@NfNotificationListener)
                val notification = statusNotification.buildNotification(filtering = filtering)
                runCatching {
                    startForeground(OngoingStatusNotification.NOTIFICATION_ID, notification)
                    NfLog.i(this@NfNotificationListener, "常驻通知已开启（前台化）")
                }.onFailure {
                    NfLog.w(this@NfNotificationListener, "前台化失败，降级为常驻通知: ${it.message}")
                    statusNotification.show(filtering = filtering)
                }
            }
        } else {
            runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
                .onFailure { NfLog.w(this, "停止前台化失败: ${it.message}") }
            OngoingStatusNotification(this).hide()
            NfLog.i(this, "常驻通知已关闭，服务以后台形态继续监听")
        }
    }
}

/** V10.8：媒体字段在 extras 里的键名前缀（`media.title` / `media.artist` / …）。 */
private const val MEDIA_FIELD_PREFIX = "media."
