package com.local.notificationfilter.core.ruleengine

import com.local.notificationfilter.core.model.ChargeState
import com.local.notificationfilter.core.model.DeviceState
import com.local.notificationfilter.core.model.DeviceStateCondition
import com.local.notificationfilter.core.model.ScreenState
import com.local.notificationfilter.core.model.TimeWindow
import com.local.notificationfilter.core.model.Weekday
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

/**
 * 设备状态条件评估器（V3 需求⑦，架构 D7）。
 *
 * 语义（与 RuleEngine 的关键词匹配解耦，单独评估）：
 * - 全部 AND：四个子项全部通过才算通过；
 * - 某子项为空（条件侧）= 不限制 = 恒通过；
 * - 某子项对应的 DeviceState 字段为 null（运行时侧·试跑模式）= 跳过该子项 = 恒通过
 *   （架构 D8：试跑不评估设备状态）；
 * - 时间窗口：多窗口 OR + 跨午夜（start > end 时窗口跨越 0 点）；
 * - 星期：多选 OR；
 * - 窗口与星期 AND（两者都设置时须同时满足）。
 */
object DeviceStateEvaluator {

    /**
     * 评估设备状态条件是否满足。
     *
     * @param cond 规则侧条件（[DeviceStateCondition.isEmpty] = 无限制）
     * @param state 运行时设备状态（[DeviceState.TEST_MODE] = 试跑跳过）
     * @return null = 通过（满足或被跳过）；非 null = 未通过原因（分号分隔各子项）
     */
    fun evaluate(cond: DeviceStateCondition, state: DeviceState): String? {
        if (cond.isEmpty) return null
        val reasons = mutableListOf<String>()
        evaluateScreen(cond, state, reasons)
        evaluateCharge(cond, state, reasons)
        evaluateTime(cond, state, reasons)
        return if (reasons.isEmpty()) null else reasons.joinToString("；")
    }

    // ==================== 子项评估 ====================

    private fun evaluateScreen(
        cond: DeviceStateCondition,
        state: DeviceState,
        reasons: MutableList<String>,
    ) {
        if (cond.screenStates.isEmpty() || state.screenOn == null) return
        val current = if (state.screenOn) ScreenState.ON else ScreenState.OFF
        if (current !in cond.screenStates) {
            reasons.add("屏幕状态不匹配（当前${current.label}）")
        }
    }

    private fun evaluateCharge(
        cond: DeviceStateCondition,
        state: DeviceState,
        reasons: MutableList<String>,
    ) {
        if (cond.chargeStates.isEmpty() || state.charging == null) return
        if (state.charging !in cond.chargeStates) {
            reasons.add("充电状态不匹配（当前${state.charging.label}）")
        }
    }

    /**
     * 时间 + 星期评估（架构 D7：窗口多 OR + 跨午夜；星期多选 OR；窗口与星期 AND）。
     */
    private fun evaluateTime(
        cond: DeviceStateCondition,
        state: DeviceState,
        reasons: MutableList<String>,
    ) {
        val hasTime = cond.timeWindows.isNotEmpty()
        val hasWeekday = cond.weekdays.isNotEmpty()
        if (!hasTime && !hasWeekday) return
        if (state.now == null) return // 试跑模式：跳过

        val zdt = Instant.ofEpochMilli(state.now).atZone(ZoneId.systemDefault())
        val localTime = zdt.toLocalTime()
        val dow = zdt.dayOfWeek

        if (hasWeekday) {
            val currentWeekday = Weekday.fromDayOfWeek(dow)
            if (currentWeekday !in cond.weekdays) {
                reasons.add("星期不匹配（当前${currentWeekday.label}）")
            }
        }

        if (hasTime) {
            val inAnyWindow = cond.timeWindows.any { containsTime(it, localTime) }
            if (!inAnyWindow) {
                reasons.add("不在时间窗口内（当前${formatHHmm(localTime)}）")
            }
        }
    }

    // ==================== 时间窗口原语 ====================

    /**
     * 当前时刻是否落在窗口内。
     * - start <= end：普通窗口 `[start, end]`；
     * - start > end：跨午夜窗口 `[start, 24:00) ∪ [00:00, end]`。
     */
    private fun containsTime(window: TimeWindow, t: LocalTime): Boolean {
        val s = parseHHmm(window.start) ?: return false
        val e = parseHHmm(window.end) ?: return false
        return if (s <= e) {
            t >= s && t <= e
        } else {
            t >= s || t <= e
        }
    }

    /** "HH:mm" → LocalTime；非法格式返回 null（调用方跳过该窗口）。 */
    private fun parseHHmm(s: String): LocalTime? {
        val parts = s.trim().split(":")
        if (parts.size != 2) return null
        val h = parts[0].toIntOrNull() ?: return null
        val m = parts[1].toIntOrNull() ?: return null
        if (h !in 0..23 || m !in 0..59) return null
        return runCatching { LocalTime.of(h, m) }.getOrNull()
    }

    private fun formatHHmm(t: LocalTime): String {
        val h = t.hour.toString().padStart(2, '0')
        val m = t.minute.toString().padStart(2, '0')
        return "$h:$m"
    }
}
