package com.local.notificationfilter.service

import android.accessibilityservice.AccessibilityService
import com.local.notificationfilter.util.NfLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 进程内唯一「当前前台应用包名」事实来源（V7 需求 7-⑥，架构 §3.2）。
 *
 * 数据来源只有一个：[NfAccessibilityService] 的 `onAccessibilityEvent`（见该类的三层过滤）。
 * 本类**不主动探测**、不轮询、无定时器——纯被动写入，符合「纯事件回调、成本可忽略」的口径。
 *
 * 与 [DeviceStateProvider] / [ListenerStatusHolder] 同模式：纯 Kotlin object + StateFlow，
 * 无 Hilt、无 Context 泄漏（[attach] 只记包名与连接事实，不持有 service 引用）。
 *
 * 语义约定：
 * - `null` = 尚不知道当前前台是谁（服务未开启 / 刚开机 / 系统回到桌面且桌面被过滤）。
 *   条件求值必须把 `null` 当作「不匹配任何 App」，**不能**当作「未知即放行」，否则
 *   「打开某 App 才显示」会在服务未开启时恒真（架构 §3.5「不可判定」口径）。
 * - 只保留**最后一个非忽略包名**：用户从微信回到桌面时，若桌面被系统报为
 *   `com.miui.home`，该值会被更新成桌面包名（不匹配任何用户配置的 App → 条件转假），
 *   这正是「退出 App 就消失」需要的行为。
 */
object ForegroundAppHolder {

    private val _packageName = MutableStateFlow<String?>(null)

    /** 当前前台包名（`null` = 未知）；订阅方无需区分线程，StateFlow 已保证可见性。 */
    val packageName: StateFlow<String?> = _packageName.asStateFlow()

    /** 当前前台包名快照（非响应式读取，供求值热路径使用）。 */
    val current: String? get() = _packageName.value

    /** 无障碍服务当前是否处于已连接状态（`onServiceConnected` 后为 true）。 */
    @Volatile
    var attached: Boolean = false
        private set

    /** 本 App 包名（判断「事件来自自己」时用；[attach] 时记录，避免 `onAccessibilityEvent` 里再查）。 */
    @Volatile
    private var selfPackageName: String? = null

    /**
     * 服务连接时挂载（幂等：重复调用只更新自包名，不重复注册任何东西）。
     *
     * @param service 已连接的无障碍服务实例；**只读取** `packageName`，不保存引用。
     */
    fun attach(service: AccessibilityService) {
        selfPackageName = service.packageName
        if (attached) {
            NfLog.d(this, "attach 重复调用，忽略（already attached）")
            return
        }
        attached = true
        NfLog.i(this, "前台包名持有者已挂载 current=${_packageName.value}")
    }

    /**
     * 服务断开 / 销毁 / 被打断时卸载（幂等）。
     *
     * 清空包名：服务不开时「前台是谁」不可知，残留旧值会让「打开某 App」条件在服务关闭后
     * 依然恒真（用户明明没开 App，卡片却一直显示）。故这里必须回到 `null`。
     */
    fun detach() {
        if (!attached && _packageName.value == null) return
        attached = false
        selfPackageName = null
        _packageName.value = null
        NfLog.i(this, "前台包名持有者已卸载（packageName 已清空）")
    }

    /**
     * 更新当前前台包名（由 [NfAccessibilityService] 调用，内部**同值去重**）。
     *
     * @param packageName 命中过滤条件后的包名；`null` / 空串按「忽略本次」处理，不写入。
     * @return true = 值确实变了（订阅者会被唤醒）。
     */
    fun update(packageName: String?): Boolean {
        val pkg = packageName?.trim()?.takeIf { it.isNotEmpty() } ?: return false
        if (pkg == selfPackageName) return false
        if (_packageName.value == pkg) return false
        _packageName.value = pkg
        NfLog.i(this, "前台应用变化 → $pkg")
        return true
    }
}
