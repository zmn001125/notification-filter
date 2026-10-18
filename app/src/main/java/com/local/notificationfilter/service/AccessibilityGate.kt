package com.local.notificationfilter.service

import com.local.notificationfilter.core.model.Rule
import com.local.notificationfilter.data.repo.RuleRepository
import com.local.notificationfilter.util.NfLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 「无障碍服务当前是否真的被需要」的判定与状态标记（V7 架构 §3.6.1 / §3.6.3）。
 *
 * 本类**不启停服务**（Q2 定案：一次都不调 `disableSelf()`），只做三件事：
 * 1. 提供一个**纯函数** [isNeeded]，回答「这批规则里有没有用到 `FOREGROUND_APP` 条件」；
 * 2. 把结论缓存成进程内标记 [needed]（StateFlow），供日志 / UI 灰字提示读取；
 * 3. 启动时 [syncFromDb] 读一次库，把标记初始化正确。
 *
 * 触发时机（**只在规则变更 / 启动**，绝不在每条通知上跑）：
 * - 启动：`NfApplication.onCreate()` → [syncFromDb]（由 T05 接线）；
 * - 规则变更：`OverlayConditionArbiter.compile(rules)` 之后 → [sync]（由 T05 接线）。
 * 通知热路径**不得**调用本类（扫全库规则是 O(规则数)，违反架构 §3.4 性能红线 2）。
 */
object AccessibilityGate {

    private val _needed = MutableStateFlow(false)

    /** 当前是否「有生效规则使用 `FOREGROUND_APP` 条件」；驱动授权页按需展示与灰字提示。 */
    val needed: StateFlow<Boolean> = _needed.asStateFlow()

    /** 需要但未授权时的提示口径（授权页 / 规则编辑页共用，避免文案漂移）。 */
    const val HINT_NOT_GRANTED: String = "未授予无障碍服务，此条件不会生效"

    /** 已开启但没有任何规则使用时的灰字提示口径（不自动关闭，只提示）。 */
    const val HINT_NOT_NEEDED: String = "当前无规则使用，可关闭以省电"

    /** 无障碍服务是否已连接（由 [NfAccessibilityService.onServiceConnected] 置位）。 */
    val serviceConnected: Boolean get() = ForegroundAppHolder.attached

    /**
     * **纯函数**：这批规则里是否有生效规则需要无障碍服务。
     *
     * 判定口径（与授权页展示、与服务事件派发完全一致，只此一处）：
     * - 只看 `enabled = true` 的规则（停用规则不执行任何动作，索取权限无意义）；
     * - 只看 `actions.floatTrigger` 里**有效**的条目（`OverlayTriggerConfig.needsForegroundApp`
     *   内部走 `effective`，已剔除参数没配全的条件）；
     * - 列表为空 → 立即返回 false（不分配中间集合，也不做任何 when 分发）。
     *
     * @param rules 规则列表（调用方负责提供最新列表）。
     */
    fun isNeeded(rules: List<Rule>): Boolean {
        if (rules.isEmpty()) return false
        return rules.any { rule ->
            rule.enabled && rule.actions.floatTrigger?.needsForegroundApp == true
        }
    }

    /**
     * 用最新规则列表刷新标记（由 `OverlayConditionArbiter.compile` 调用）。
     *
     * @return 刷新后的标记值（true = 需要无障碍服务）。
     */
    fun sync(rules: List<Rule>): Boolean {
        val needed = isNeeded(rules)
        val changed = _needed.value != needed
        _needed.value = needed
        if (changed) {
            NfLog.i(
                this,
                if (needed) {
                    "无障碍服务变为「需要」：存在使用「打开了某个 App」条件的规则"
                } else {
                    "无障碍服务变为「不需要」：无规则使用该条件（不自动关闭服务，仅在 UI 提示）"
                },
            )
        }
        return needed
    }

    /**
     * 启动时从数据库刷新一次标记（`NfApplication.onCreate()` 调用；只读、不启停服务）。
     *
     * @param repository 规则仓库（走其内存缓存 [RuleRepository.getAll]，不会每次读库）。
     * @return 刷新后的标记值。
     */
    suspend fun syncFromDb(repository: RuleRepository): Boolean {
        val rules = runCatching { repository.getAll() }
            .onFailure { NfLog.w(this, "读取规则失败，无障碍需求标记保持原值: ${it.message}") }
            .getOrDefault(emptyList())
        return sync(rules)
    }

    /**
     * 无障碍服务连接回调（[NfAccessibilityService.onServiceConnected] 调用）。
     *
     * 只记录日志：连接事实由 [ForegroundAppHolder.attached] 表达，本方法**不做任何开关动作**
     * （尤其是「不需要时自动关闭」——Q2 已否决）。
     *
     * @param servicePackageName 服务所在包名（日志用）。
     */
    fun onConnected(servicePackageName: String) {
        NfLog.i(
            this,
            "无障碍服务已连接 pkg=$servicePackageName needed=${_needed.value}（不会自动关闭服务）",
        )
    }
}
