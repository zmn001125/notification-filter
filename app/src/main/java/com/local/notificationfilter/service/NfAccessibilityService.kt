package com.local.notificationfilter.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import com.local.notificationfilter.util.NfLog

/**
 * V7 需求 7-⑥「打开了某个 App」的事件源（架构 §3.6.2）。
 *
 * 职责极窄：只把「窗口状态变化」事件里的**包名**写进 [ForegroundAppHolder]，不做任何业务判断、
 * 不读界面内容、不 hold 任何状态、不发起网络/DB 调用。条件求值在
 * `OverlayConditionArbiter`（T05）里完成。
 *
 * 三条硬约束（主理人已拍板，勿改）：
 * 1. **不调用 `disableSelf()`**。本服务是纯事件回调（只订阅一种事件、无轮询、无定时器、
 *    `canRetrieveWindowContent = false`），空转成本近似 0；而 `disableSelf()` 之后
 *    用户必须回系统设置手动重开，体验代价远大于收益。因此「按需」落在
 *    [AccessibilityGate] 的**不索取权限 / 不派发事件 / 给提示**三层，而不是自动关闭。
 * 2. **隐私最小化**：`res/xml/accessibility_service_config.xml` 里
 *    `canRetrieveWindowContent="false"`——我们只要事件里的包名，不读窗口树。
 * 3. **三层事件过滤**（顺序即代码顺序，任一命中直接 return）：
 *    类型过滤 → 空包名过滤 → 自身 / 系统 UI / 输入法包名过滤。
 *    否则用户下拉通知栏、切输入法都会被误判成「打开了某个 App」，卡片会乱闪。
 */
class NfAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        // 运行时再声明一次事件类型：即使 xml 配置被 ROM 忽略（部分定制 ROM 只认代码里的
        // serviceInfo），也只收窗口状态变化这一种事件。
        serviceInfo = (serviceInfo ?: AccessibilityServiceInfo()).apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            // 不需要「检索窗口内容」相关能力（隐私最小化；本服务只读事件包名）
            flags = flags and AccessibilityServiceInfo.FLAG_REQUEST_ENHANCED_WEB_ACCESSIBILITY.inv()
            notificationTimeout = EVENT_TIMEOUT_MS.toLong()
        }
        ForegroundAppHolder.attach(this)
        AccessibilityGate.onConnected(packageName)
        NfLog.i(this, "无障碍服务已连接（只订阅窗口状态变化，不读界面内容）")
    }

    /**
     * 事件入口。**所有过滤都在这里**，写进 [ForegroundAppHolder] 后再无任何副作用。
     *
     * 过滤链（顺序不可调换）：
     * 1. `eventType != TYPE_WINDOW_STATE_CHANGED` → 丢弃。
     *    （xml 里同时声明了 `typeWindowsChanged`，但那个事件量大且不携带「哪个包到了前台」
     *    的可靠语义，**不处理**；保留声明是为了不让部分 ROM 因「配置与请求不一致」而静默禁用服务。）
     * 2. 包名为空 / 全空白 → 丢弃（系统偶发投递无包名的窗口事件）。
     * 3. 包名 == 本 App → 丢弃（我们自己弹授权页 / 悬浮窗交互会产生该事件，
     *    否则「打开本 App」会被误记为前台应用）。
     * 4. 包名 ∈ [IGNORED_PACKAGES]（系统 UI / 通知栏 / 安全中心 / 输入法）→ 丢弃。
     */
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val e: AccessibilityEvent = event ?: return
        if (e.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return

        val pkg: String = e.packageName?.toString()?.trim().orEmpty()
        if (pkg.isEmpty()) return

        if (pkg == packageName) return
        if (pkg in IGNORED_PACKAGES) return

        // 内部同值去重（StateFlow 不会为同值重复唤醒订阅者）
        ForegroundAppHolder.update(pkg)
    }

    /** 系统要求实现；本服务不处理打断（无可中断的长任务），保持空实现。 */
    override fun onInterrupt() {
        // 被打断 = 本次会话不可用：清空前台包名并卸载，避免残留旧值让条件恒真
        ForegroundAppHolder.detach()
        NfLog.w(this, "无障碍服务被系统打断（onInterrupt），已清空前台包名")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        ForegroundAppHolder.detach()
        NfLog.i(this, "无障碍服务被解绑（onUnbind）")
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        ForegroundAppHolder.detach()
        NfLog.i(this, "无障碍服务销毁（onDestroy）")
        super.onDestroy()
    }

    private companion object {

        /** 事件合并窗口（毫秒）：同一包名的高频窗口切换在 100ms 内合并，减少回调次数。 */
        const val EVENT_TIMEOUT_MS = 100

        /**
         * 不视为「前台应用」的包名。
         *
         * 依据：这些包名对应的界面（通知栏下拉、最近任务、权限弹窗、通知中心、输入法键盘）
         * **不代表用户切换了 App**，写进 [ForegroundAppHolder] 会让「打开了某个 App」条件
         * 在用户下拉通知栏 / 弹出键盘时被误触发（卡片乱闪）。
         *
         * **刻意不在本表里的两类**：
         * - 桌面（`com.miui.home` / `com.miui.home.launcher`）：必须让它写进持有者，
         *   这样用户回到桌面时前台包名变成桌面 → 不匹配任何用户配置的 App →
         *   「打开某 App 显示」条件自然转假（`hideWhenFalse` 得以生效）。若忽略桌面，
         *   前台包名会**停留在上一个 App**，卡片就永远撤不下来。
         * - 真正的用户 App（含 `com.android.settings`）：用户可能想对它们配条件，不能吞。
         */
        val IGNORED_PACKAGES: Set<String> = setOf(
            // AOSP / 通用：通知栏、状态栏、最近任务、音量/电源弹窗
            "com.android.systemui",
            // HyperOS / MIUI：权限弹窗、控制中心、通知中心
            "com.miui.securitycenter",
            "com.miui.notification",
            // 输入法（键盘弹出会产生窗口状态变化）
            "com.android.inputmethod.latin",
            "com.google.android.inputmethod.latin",
            "com.sohu.inputmethod.sogou.xiaomi",
            "com.sohu.inputmethod.sogou",
            "com.baidu.input_mi",
            "com.iflytek.inputmethod.miui",
        )
    }
}
