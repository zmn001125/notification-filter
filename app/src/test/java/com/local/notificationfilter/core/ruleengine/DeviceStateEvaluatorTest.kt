package com.local.notificationfilter.core.ruleengine

import com.local.notificationfilter.core.model.ChargeState
import com.local.notificationfilter.core.model.DeviceState
import com.local.notificationfilter.core.model.DeviceStateCondition
import com.local.notificationfilter.core.model.ScreenState
import com.local.notificationfilter.core.model.TimeWindow
import com.local.notificationfilter.core.model.Weekday
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DeviceStateEvaluator 单测（V3 需求⑦，架构 D7）。
 *
 * 覆盖：空条件 / 屏幕状态 / 充电状态 / 时间窗口（普通+跨午夜+多窗口OR）/ 星期（多选OR）
 * / 窗口与星期AND / 全子项AND / 试跑模式跳过 / 非法时间格式跳过。
 *
 * 时间戳构造：2026-01-05 = 周一（经推算 2026-01-01 为周四）。
 */
class DeviceStateEvaluatorTest {

    private fun ts(year: Int, month: Int, day: Int, hour: Int, minute: Int = 0): Long =
        LocalDateTime.of(year, month, day, hour, minute)
            .atZone(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()

    // —— 周一 10:00（测试基准时刻）——
    private val monday10am = ts(2026, 1, 5, 10, 0)
    private val monday3am = ts(2026, 1, 5, 3, 0)
    private val monday23 = ts(2026, 1, 5, 23, 0)
    private val tuesday10am = ts(2026, 1, 6, 10, 0)
    private val saturday10am = ts(2026, 1, 10, 10, 0)

    // ==================== 空条件 ====================

    @Test
    fun `空条件_恒通过`() {
        assertNull(DeviceStateEvaluator.evaluate(DeviceStateCondition(), DeviceState()))
        assertNull(DeviceStateEvaluator.evaluate(DeviceStateCondition(), DeviceState.TEST_MODE))
    }

    // ==================== 屏幕状态 ====================

    @Test
    fun `屏幕_亮屏条件_当前亮屏_通过`() {
        val cond = DeviceStateCondition(screenStates = setOf(ScreenState.ON))
        assertNull(DeviceStateEvaluator.evaluate(cond, DeviceState(screenOn = true)))
    }

    @Test
    fun `屏幕_亮屏条件_当前熄屏_不通过`() {
        val cond = DeviceStateCondition(screenStates = setOf(ScreenState.ON))
        val reason = DeviceStateEvaluator.evaluate(cond, DeviceState(screenOn = false))
        assertTrue(reason?.contains("屏幕状态不匹配") == true)
        assertTrue(reason?.contains("熄屏") == true)
    }

    @Test
    fun `屏幕_多选ON_OFF_任一满足`() {
        val cond = DeviceStateCondition(screenStates = setOf(ScreenState.ON, ScreenState.OFF))
        assertNull(DeviceStateEvaluator.evaluate(cond, DeviceState(screenOn = true)))
        assertNull(DeviceStateEvaluator.evaluate(cond, DeviceState(screenOn = false)))
    }

    @Test
    fun `屏幕_试跑模式screenOn为null_跳过_通过`() {
        val cond = DeviceStateCondition(screenStates = setOf(ScreenState.ON))
        assertNull(DeviceStateEvaluator.evaluate(cond, DeviceState(screenOn = null)))
    }

    // ==================== 充电状态 ====================

    @Test
    fun `充电_有线条件_当前有线_通过`() {
        val cond = DeviceStateCondition(chargeStates = setOf(ChargeState.WIRED))
        assertNull(DeviceStateEvaluator.evaluate(cond, DeviceState(charging = ChargeState.WIRED)))
    }

    @Test
    fun `充电_有线条件_当前电池_不通过`() {
        val cond = DeviceStateCondition(chargeStates = setOf(ChargeState.WIRED))
        val reason = DeviceStateEvaluator.evaluate(cond, DeviceState(charging = ChargeState.BATTERY))
        assertTrue(reason?.contains("充电状态不匹配") == true)
        assertTrue(reason?.contains("电池供电") == true)
    }

    @Test
    fun `充电_多选有线无线_当前无线_通过`() {
        val cond = DeviceStateCondition(chargeStates = setOf(ChargeState.WIRED, ChargeState.WIRELESS))
        assertNull(DeviceStateEvaluator.evaluate(cond, DeviceState(charging = ChargeState.WIRELESS)))
    }

    @Test
    fun `充电_试跑模式charging为null_跳过_通过`() {
        val cond = DeviceStateCondition(chargeStates = setOf(ChargeState.WIRED))
        assertNull(DeviceStateEvaluator.evaluate(cond, DeviceState(charging = null)))
    }

    // ==================== 时间窗口 ====================

    @Test
    fun `时间窗口_普通09到17_12点在窗口内_通过`() {
        val cond = DeviceStateCondition(timeWindows = listOf(TimeWindow("09:00", "17:00")))
        assertNull(DeviceStateEvaluator.evaluate(cond, DeviceState(now = monday10am)))
    }

    @Test
    fun `时间窗口_普通09到17_08点在窗口前_不通过`() {
        val cond = DeviceStateCondition(timeWindows = listOf(TimeWindow("09:00", "17:00")))
        val eight = ts(2026, 1, 5, 8, 0)
        val reason = DeviceStateEvaluator.evaluate(cond, DeviceState(now = eight))
        assertTrue(reason?.contains("不在时间窗口内") == true)
    }

    @Test
    fun `时间窗口_普通09到17_18点在窗口后_不通过`() {
        val cond = DeviceStateCondition(timeWindows = listOf(TimeWindow("09:00", "17:00")))
        val eighteen = ts(2026, 1, 5, 18, 0)
        val reason = DeviceStateEvaluator.evaluate(cond, DeviceState(now = eighteen))
        assertTrue(reason?.contains("不在时间窗口内") == true)
    }

    @Test
    fun `时间窗口_跨午夜22到06_23点在窗口内_通过`() {
        val cond = DeviceStateCondition(timeWindows = listOf(TimeWindow("22:00", "06:00")))
        assertNull(DeviceStateEvaluator.evaluate(cond, DeviceState(now = monday23)))
    }

    @Test
    fun `时间窗口_跨午夜22到06_03点在窗口内_通过`() {
        val cond = DeviceStateCondition(timeWindows = listOf(TimeWindow("22:00", "06:00")))
        assertNull(DeviceStateEvaluator.evaluate(cond, DeviceState(now = monday3am)))
    }

    @Test
    fun `时间窗口_跨午夜22到06_12点不在窗口内_不通过`() {
        val cond = DeviceStateCondition(timeWindows = listOf(TimeWindow("22:00", "06:00")))
        val noon = ts(2026, 1, 5, 12, 0)
        val reason = DeviceStateEvaluator.evaluate(cond, DeviceState(now = noon))
        assertTrue(reason?.contains("不在时间窗口内") == true)
    }

    @Test
    fun `时间窗口_多窗口OR_上午下午两个窗口`() {
        val cond = DeviceStateCondition(
            timeWindows = listOf(TimeWindow("09:00", "11:00"), TimeWindow("14:00", "17:00")),
        )
        // 10:00 → 在上午窗口
        assertNull(DeviceStateEvaluator.evaluate(cond, DeviceState(now = monday10am)))
        // 15:00 → 在下午窗口
        val three = ts(2026, 1, 5, 15, 0)
        assertNull(DeviceStateEvaluator.evaluate(cond, DeviceState(now = three)))
        // 12:00 → 不在任一窗口
        val noon = ts(2026, 1, 5, 12, 0)
        assertTrue(DeviceStateEvaluator.evaluate(cond, DeviceState(now = noon))?.contains("不在时间窗口内") == true)
    }

    // ==================== 星期 ====================

    @Test
    fun `星期_仅周一_当前周一_通过`() {
        val cond = DeviceStateCondition(weekdays = setOf(Weekday.MON))
        assertNull(DeviceStateEvaluator.evaluate(cond, DeviceState(now = monday10am)))
    }

    @Test
    fun `星期_仅周一_当前周二_不通过`() {
        val cond = DeviceStateCondition(weekdays = setOf(Weekday.MON))
        val reason = DeviceStateEvaluator.evaluate(cond, DeviceState(now = tuesday10am))
        assertTrue(reason?.contains("星期不匹配") == true)
        assertTrue(reason?.contains("周二") == true)
    }

    @Test
    fun `星期_多选周一三五_当前周六_不通过`() {
        val cond = DeviceStateCondition(weekdays = setOf(Weekday.MON, Weekday.WED, Weekday.FRI))
        val reason = DeviceStateEvaluator.evaluate(cond, DeviceState(now = saturday10am))
        assertTrue(reason?.contains("星期不匹配") == true)
    }

    @Test
    fun `星期_多选周一三五_当前周三_通过`() {
        val cond = DeviceStateCondition(weekdays = setOf(Weekday.MON, Weekday.WED, Weekday.FRI))
        val wed = ts(2026, 1, 7, 10, 0)
        assertNull(DeviceStateEvaluator.evaluate(cond, DeviceState(now = wed)))
    }

    // ==================== 窗口 + 星期 AND ====================

    @Test
    fun `窗口与星期AND_周一10点在09到17且是周一_通过`() {
        val cond = DeviceStateCondition(
            timeWindows = listOf(TimeWindow("09:00", "17:00")),
            weekdays = setOf(Weekday.MON),
        )
        assertNull(DeviceStateEvaluator.evaluate(cond, DeviceState(now = monday10am)))
    }

    @Test
    fun `窗口与星期AND_周二10点在窗口但不是周一_不通过`() {
        val cond = DeviceStateCondition(
            timeWindows = listOf(TimeWindow("09:00", "17:00")),
            weekdays = setOf(Weekday.MON),
        )
        val reason = DeviceStateEvaluator.evaluate(cond, DeviceState(now = tuesday10am))
        assertTrue(reason?.contains("星期不匹配") == true)
        assertTrue(!reason!!.contains("不在时间窗口内"))
    }

    @Test
    fun `窗口与星期AND_周一20点是周一但不在窗口_不通过`() {
        val cond = DeviceStateCondition(
            timeWindows = listOf(TimeWindow("09:00", "17:00")),
            weekdays = setOf(Weekday.MON),
        )
        val eight = ts(2026, 1, 5, 20, 0)
        val reason = DeviceStateEvaluator.evaluate(cond, DeviceState(now = eight))
        assertTrue(reason?.contains("不在时间窗口内") == true)
    }

    // ==================== 全子项 AND ====================

    @Test
    fun `全子项AND_全部满足_通过`() {
        val cond = DeviceStateCondition(
            screenStates = setOf(ScreenState.ON),
            chargeStates = setOf(ChargeState.WIRED),
            timeWindows = listOf(TimeWindow("09:00", "17:00")),
            weekdays = setOf(Weekday.MON),
        )
        val state = DeviceState(screenOn = true, charging = ChargeState.WIRED, now = monday10am)
        assertNull(DeviceStateEvaluator.evaluate(cond, state))
    }

    @Test
    fun `全子项AND_屏幕不满足_不通过且原因含屏幕`() {
        val cond = DeviceStateCondition(
            screenStates = setOf(ScreenState.ON),
            chargeStates = setOf(ChargeState.WIRED),
        )
        val state = DeviceState(screenOn = false, charging = ChargeState.WIRED)
        val reason = DeviceStateEvaluator.evaluate(cond, state)
        assertTrue(reason?.contains("屏幕状态不匹配") == true)
    }

    @Test
    fun `全子项AND_两个不满足_原因含两条`() {
        val cond = DeviceStateCondition(
            screenStates = setOf(ScreenState.ON),
            chargeStates = setOf(ChargeState.WIRED),
        )
        val state = DeviceState(screenOn = false, charging = ChargeState.BATTERY)
        val reason = DeviceStateEvaluator.evaluate(cond, state)
        assertTrue(reason?.contains("屏幕状态不匹配") == true)
        assertTrue(reason?.contains("充电状态不匹配") == true)
        assertTrue(reason?.contains("；") == true)
    }

    // ==================== 试跑模式 ====================

    @Test
    fun `试跑模式_全空DeviceState_条件设置也不阻拦`() {
        val cond = DeviceStateCondition(
            screenStates = setOf(ScreenState.OFF),
            chargeStates = setOf(ChargeState.WIRELESS),
            timeWindows = listOf(TimeWindow("22:00", "06:00")),
            weekdays = setOf(Weekday.SUN),
        )
        // 全空 DeviceState（试跑）→ 所有子项被跳过 → 通过
        assertNull(DeviceStateEvaluator.evaluate(cond, DeviceState.TEST_MODE))
    }

    // ==================== 非法格式 ====================

    @Test
    fun `非法时间格式_该窗口视为不匹配_不通过`() {
        val cond = DeviceStateCondition(timeWindows = listOf(TimeWindow("xx", "17:00")))
        val reason = DeviceStateEvaluator.evaluate(cond, DeviceState(now = monday10am))
        // 非法窗口被跳过 = 无有效窗口 → 不在任一窗口内 → 不通过
        assertTrue(reason?.contains("不在时间窗口内") == true)
    }

    @Test
    fun `混合合法与非法窗口_合法窗口命中则通过`() {
        val cond = DeviceStateCondition(
            timeWindows = listOf(TimeWindow("bad", "17:00"), TimeWindow("09:00", "11:00")),
        )
        // 10:00 在第二个合法窗口内 → 通过
        assertNull(DeviceStateEvaluator.evaluate(cond, DeviceState(now = monday10am)))
    }

    @Test
    fun `边界_恰好等于start_通过`() {
        val cond = DeviceStateCondition(timeWindows = listOf(TimeWindow("10:00", "17:00")))
        assertNull(DeviceStateEvaluator.evaluate(cond, DeviceState(now = monday10am)))
    }

    @Test
    fun `边界_恰好等于end_通过`() {
        val cond = DeviceStateCondition(timeWindows = listOf(TimeWindow("09:00", "10:00")))
        assertNull(DeviceStateEvaluator.evaluate(cond, DeviceState(now = monday10am)))
    }
}
