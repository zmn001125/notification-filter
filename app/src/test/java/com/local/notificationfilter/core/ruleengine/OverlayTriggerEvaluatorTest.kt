package com.local.notificationfilter.core.ruleengine

import com.local.notificationfilter.core.model.ChargeState
import com.local.notificationfilter.core.model.DeviceState
import com.local.notificationfilter.core.model.DeviceStateCondition
import com.local.notificationfilter.core.model.OverlayTrigger
import com.local.notificationfilter.core.model.OverlayTriggerConfig
import com.local.notificationfilter.core.model.OverlayTriggerKind
import com.local.notificationfilter.core.model.ScreenState
import com.local.notificationfilter.core.model.TimeWindow
import com.local.notificationfilter.core.model.TriggerEdge
import com.local.notificationfilter.core.model.TriggerEffect
import com.local.notificationfilter.core.model.Weekday
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/**
 * 六类条件求值单测（V7 需求 7，架构 §7.5）。
 *
 * 覆盖：文档 §7.5 用例表逐条（六类 kind × 真 / 假 / 不可判定）+ "HIDE 优先" 冲突用例
 * + 边界（非法 clockAt / 无事件上下文 / 无前台包名 / hideWhenFalse / 空条件集）。
 */
class OverlayTriggerEvaluatorTest {

    /** 固定日期（2024-06-03 = 周一），用本地时区换算成 epoch 毫秒，避免测试机时区差异。 */
    private val monday: LocalDate = LocalDate.of(2024, 6, 3)

    private fun epochAt(hour: Int, minute: Int, second: Int = 0, date: LocalDate = monday): Long =
        date.atTime(hour, minute, second).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private fun ctx(
        deviceState: DeviceState = DeviceState.TEST_MODE,
        now: Long = 0L,
        event: TriggerEvent? = null,
        foreground: String? = null,
        firedClockKeys: Set<Int> = emptySet(),
    ): TriggerContext = TriggerContext(
        deviceState = deviceState,
        now = now,
        lastEvent = event,
        foregroundPackage = foreground,
        firedClockKeys = firedClockKeys,
    )

    private fun deviceTrigger(
        cond: DeviceStateCondition,
        effect: TriggerEffect = TriggerEffect.SHOW,
        hideWhenFalse: Boolean = false,
    ): OverlayTrigger = OverlayTrigger(
        kind = OverlayTriggerKind.DEVICE_STATE.name,
        effect = effect,
        hideWhenFalse = hideWhenFalse,
        device = cond,
    )

    private fun clockTrigger(
        at: String,
        effect: TriggerEffect = TriggerEffect.SHOW,
        hideWhenFalse: Boolean = false,
    ): OverlayTrigger = OverlayTrigger(
        kind = OverlayTriggerKind.CLOCK_AT.name,
        effect = effect,
        hideWhenFalse = hideWhenFalse,
        clockAt = at,
    )

    private fun appTrigger(
        packages: Set<String>,
        edge: TriggerEdge = TriggerEdge.OCCUR,
        effect: TriggerEffect = TriggerEffect.SHOW,
        hideWhenFalse: Boolean = false,
    ): OverlayTrigger = OverlayTrigger(
        kind = OverlayTriggerKind.APP_NOTIFICATION.name,
        effect = effect,
        hideWhenFalse = hideWhenFalse,
        packages = packages,
        edge = edge,
    )

    private fun ruleTrigger(
        ruleIds: Set<Long>,
        edge: TriggerEdge = TriggerEdge.OCCUR,
        effect: TriggerEffect = TriggerEffect.SHOW,
        hideWhenFalse: Boolean = false,
    ): OverlayTrigger = OverlayTrigger(
        kind = OverlayTriggerKind.RULE_NOTIFICATION.name,
        effect = effect,
        hideWhenFalse = hideWhenFalse,
        ruleIds = ruleIds,
        edge = edge,
    )

    private fun foregroundTrigger(packages: Set<String>): OverlayTrigger = OverlayTrigger(
        kind = OverlayTriggerKind.FOREGROUND_APP.name,
        packages = packages,
    )

    // ==================== 三值断言辅助（区分 false 与 null） ====================

    private fun assertTrue3(result: Boolean?) = assertEquals("期望 true，实际 $result", true, result)

    private fun assertFalse3(result: Boolean?) = assertEquals("期望 false，实际 $result", false, result)

    // ==================== DEVICE_STATE ====================

    @Test
    fun `DEVICE_STATE 亮屏条件成立`() {
        val trigger = deviceTrigger(DeviceStateCondition(screenStates = setOf(ScreenState.ON)))
        assertTrue3(OverlayTriggerEvaluator.evaluate(trigger, ctx(deviceState = DeviceState(screenOn = true))))
    }

    @Test
    fun `DEVICE_STATE 亮屏条件不成立`() {
        val trigger = deviceTrigger(DeviceStateCondition(screenStates = setOf(ScreenState.ON)))
        assertFalse3(OverlayTriggerEvaluator.evaluate(trigger, ctx(deviceState = DeviceState(screenOn = false))))
    }

    @Test
    fun `DEVICE_STATE 屏幕未知时不可判定`() {
        val trigger = deviceTrigger(DeviceStateCondition(screenStates = setOf(ScreenState.ON)))
        assertNull(OverlayTriggerEvaluator.evaluate(trigger, ctx(deviceState = DeviceState.TEST_MODE)))
    }

    @Test
    fun `DEVICE_STATE 充电状态判定`() {
        val trigger = deviceTrigger(DeviceStateCondition(chargeStates = setOf(ChargeState.WIRED)))
        assertTrue3(
            OverlayTriggerEvaluator.evaluate(
                trigger,
                ctx(deviceState = DeviceState(charging = ChargeState.WIRED)),
            ),
        )
        assertFalse3(
            OverlayTriggerEvaluator.evaluate(
                trigger,
                ctx(deviceState = DeviceState(charging = ChargeState.BATTERY)),
            ),
        )
        assertNull(
            OverlayTriggerEvaluator.evaluate(trigger, ctx(deviceState = DeviceState.TEST_MODE)),
        )
    }

    @Test
    fun `DEVICE_STATE 时间窗口内`() {
        val trigger = deviceTrigger(
            DeviceStateCondition(timeWindows = listOf(TimeWindow("09:00", "18:00"))),
        )
        assertTrue3(OverlayTriggerEvaluator.evaluate(trigger, ctx(now = epochAt(12, 0))))
    }

    @Test
    fun `DEVICE_STATE 时间窗口外`() {
        val trigger = deviceTrigger(
            DeviceStateCondition(timeWindows = listOf(TimeWindow("09:00", "18:00"))),
        )
        assertFalse3(OverlayTriggerEvaluator.evaluate(trigger, ctx(now = epochAt(20, 0))))
    }

    @Test
    fun `DEVICE_STATE 跨午夜窗口两侧都成立`() {
        val trigger = deviceTrigger(
            DeviceStateCondition(timeWindows = listOf(TimeWindow("22:00", "06:00"))),
        )
        assertTrue3(OverlayTriggerEvaluator.evaluate(trigger, ctx(now = epochAt(23, 0))))
        assertTrue3(OverlayTriggerEvaluator.evaluate(trigger, ctx(now = epochAt(3, 0))))
        assertFalse3(OverlayTriggerEvaluator.evaluate(trigger, ctx(now = epochAt(12, 0))))
    }

    @Test
    fun `DEVICE_STATE 星期判定`() {
        val trigger = deviceTrigger(DeviceStateCondition(weekdays = setOf(Weekday.MON)))
        assertTrue3(OverlayTriggerEvaluator.evaluate(trigger, ctx(now = epochAt(10, 0))))
        assertFalse3(
            OverlayTriggerEvaluator.evaluate(
                trigger,
                ctx(now = epochAt(10, 0, date = LocalDate.of(2024, 6, 4))),
            ),
        )
    }

    @Test
    fun `DEVICE_STATE 时间未知时不可判定`() {
        val trigger = deviceTrigger(
            DeviceStateCondition(timeWindows = listOf(TimeWindow("09:00", "18:00"))),
        )
        assertNull(OverlayTriggerEvaluator.evaluate(trigger, ctx(now = 0L)))
    }

    @Test
    fun `DEVICE_STATE 条件全空时不成立也不可判定（返回 null）`() {
        val trigger = deviceTrigger(DeviceStateCondition())
        assertFalse(trigger.isEffective)
        assertNull(OverlayTriggerEvaluator.evaluate(trigger, ctx(now = epochAt(12, 0))))
    }

    // ==================== CLOCK_AT ====================

    @Test
    fun `CLOCK_AT 同一分钟成立`() {
        assertTrue3(
            OverlayTriggerEvaluator.evaluate(
                clockTrigger("22:00"),
                ctx(now = epochAt(22, 0, second = 30)),
            ),
        )
    }

    @Test
    fun `CLOCK_AT 过了那一分钟不成立`() {
        assertFalse3(
            OverlayTriggerEvaluator.evaluate(
                clockTrigger("22:00"),
                ctx(now = epochAt(22, 1)),
            ),
        )
    }

    @Test
    fun `CLOCK_AT 本分钟已触发过则不再投票`() {
        assertNull(
            OverlayTriggerEvaluator.evaluate(
                clockTrigger("22:00"),
                ctx(now = epochAt(22, 0), firedClockKeys = setOf(22 * 60)),
            ),
        )
    }

    @Test
    fun `CLOCK_AT 非法格式按不成立处理`() {
        assertEquals(false, OverlayTriggerEvaluator.evaluate(clockTrigger("abc"), ctx(now = epochAt(22, 0))))
        assertEquals(false, OverlayTriggerEvaluator.evaluate(clockTrigger("25:00"), ctx(now = epochAt(22, 0))))
        assertEquals(false, OverlayTriggerEvaluator.evaluate(clockTrigger(""), ctx(now = epochAt(22, 0))))
    }

    @Test
    fun `CLOCK_AT 时间未知时不可判定`() {
        assertNull(OverlayTriggerEvaluator.evaluate(clockTrigger("22:00"), ctx(now = 0L)))
    }

    @Test
    fun `clockKey 与 minuteOfDay 一致`() {
        assertEquals(22 * 60, OverlayTriggerEvaluator.clockKey("22:00"))
        assertEquals(22 * 60, OverlayTriggerEvaluator.minuteOfDay(epochAt(22, 0)))
        assertNull(OverlayTriggerEvaluator.clockKey("nope"))
        assertNull(OverlayTriggerEvaluator.minuteOfDay(0L))
    }

    // ==================== APP_NOTIFICATION ====================

    @Test
    fun `APP_NOTIFICATION 命中包名成立`() {
        assertTrue3(
            OverlayTriggerEvaluator.evaluate(
                appTrigger(setOf("pkgA")),
                ctx(event = TriggerEvent.posted("pkgA")),
            ),
        )
    }

    @Test
    fun `APP_NOTIFICATION 其他包名不成立`() {
        assertFalse3(
            OverlayTriggerEvaluator.evaluate(
                appTrigger(setOf("pkgA")),
                ctx(event = TriggerEvent.posted("pkgB")),
            ),
        )
    }

    @Test
    fun `APP_NOTIFICATION 消失边沿成立`() {
        assertTrue3(
            OverlayTriggerEvaluator.evaluate(
                appTrigger(setOf("pkgA"), edge = TriggerEdge.REMOVE),
                ctx(event = TriggerEvent.removed("pkgA")),
            ),
        )
    }

    @Test
    fun `APP_NOTIFICATION 边沿不匹配不成立`() {
        assertFalse3(
            OverlayTriggerEvaluator.evaluate(
                appTrigger(setOf("pkgA"), edge = TriggerEdge.REMOVE),
                ctx(event = TriggerEvent.posted("pkgA")),
            ),
        )
        assertFalse3(
            OverlayTriggerEvaluator.evaluate(
                appTrigger(setOf("pkgA"), edge = TriggerEdge.OCCUR),
                ctx(event = TriggerEvent.removed("pkgA")),
            ),
        )
    }

    @Test
    fun `APP_NOTIFICATION 没有事件上下文时按不成立处理`() {
        assertFalse3(OverlayTriggerEvaluator.evaluate(appTrigger(setOf("pkgA")), ctx()))
    }

    @Test
    fun `APP_NOTIFICATION 多个包名是 OR`() {
        val trigger = appTrigger(setOf("pkgA", "pkgB"))
        assertTrue3(OverlayTriggerEvaluator.evaluate(trigger, ctx(event = TriggerEvent.posted("pkgB"))))
    }

    // ==================== RULE_NOTIFICATION ====================

    @Test
    fun `RULE_NOTIFICATION 命中规则成立`() {
        assertTrue3(
            OverlayTriggerEvaluator.evaluate(
                ruleTrigger(setOf(5L)),
                ctx(event = TriggerEvent.matched(5L, "pkgA")),
            ),
        )
    }

    @Test
    fun `RULE_NOTIFICATION 其他规则不成立`() {
        assertFalse3(
            OverlayTriggerEvaluator.evaluate(
                ruleTrigger(setOf(5L)),
                ctx(event = TriggerEvent.matched(6L, "pkgA")),
            ),
        )
    }

    @Test
    fun `RULE_NOTIFICATION 无 ruleId 的事件不成立`() {
        assertFalse3(
            OverlayTriggerEvaluator.evaluate(
                ruleTrigger(setOf(5L)),
                ctx(event = TriggerEvent.posted("pkgA")),
            ),
        )
    }

    @Test
    fun `RULE_NOTIFICATION 消失边沿成立`() {
        assertTrue3(
            OverlayTriggerEvaluator.evaluate(
                ruleTrigger(setOf(5L), edge = TriggerEdge.REMOVE),
                ctx(event = TriggerEvent(TriggerEventType.REMOVED, "pkgA", 5L)),
            ),
        )
    }

    // ==================== FOREGROUND_APP ====================

    @Test
    fun `FOREGROUND_APP 前台命中成立`() {
        assertTrue3(
            OverlayTriggerEvaluator.evaluate(foregroundTrigger(setOf("pkgA")), ctx(foreground = "pkgA")),
        )
    }

    @Test
    fun `FOREGROUND_APP 前台不匹配不成立`() {
        assertFalse3(
            OverlayTriggerEvaluator.evaluate(foregroundTrigger(setOf("pkgA")), ctx(foreground = "pkgB")),
        )
    }

    @Test
    fun `FOREGROUND_APP 无前台信息时按不成立处理`() {
        assertFalse3(OverlayTriggerEvaluator.evaluate(foregroundTrigger(setOf("pkgA")), ctx()))
    }

    // ==================== resolve / decide（含 HIDE 优先） ====================

    @Test
    fun `一条真一条假且不取反时以真的为准`() {
        val config = OverlayTriggerConfig(
            listOf(
                deviceTrigger(DeviceStateCondition(screenStates = setOf(ScreenState.ON))),
                deviceTrigger(
                    DeviceStateCondition(screenStates = setOf(ScreenState.OFF)),
                    effect = TriggerEffect.HIDE,
                ),
            ),
        )
        assertEquals(
            TriggerEffect.SHOW,
            OverlayTriggerEvaluator.resolve(config, ctx(deviceState = DeviceState(screenOn = true))),
        )
    }

    @Test
    fun `同时命中 SHOW 与 HIDE 时 HIDE 优先`() {
        val config = OverlayTriggerConfig(
            listOf(
                deviceTrigger(DeviceStateCondition(screenStates = setOf(ScreenState.ON))),
                deviceTrigger(
                    DeviceStateCondition(screenStates = setOf(ScreenState.ON)),
                    effect = TriggerEffect.HIDE,
                ),
            ),
        )
        val decision = OverlayTriggerEvaluator.decide(
            config,
            ctx(deviceState = DeviceState(screenOn = true)),
        )
        assertTrue(decision.shouldHide)
        assertFalse(decision.shouldShow)
        assertEquals(TriggerEffect.HIDE, decision.effective)
        assertEquals(
            TriggerEffect.HIDE,
            OverlayTriggerEvaluator.resolve(config, ctx(deviceState = DeviceState(screenOn = true))),
        )
    }

    @Test
    fun `hideWhenFalse 在窗口内显示`() {
        val config = OverlayTriggerConfig(
            listOf(
                deviceTrigger(
                    DeviceStateCondition(timeWindows = listOf(TimeWindow("09:00", "18:00"))),
                    effect = TriggerEffect.SHOW,
                    hideWhenFalse = true,
                ),
            ),
        )
        assertEquals(
            TriggerEffect.SHOW,
            OverlayTriggerEvaluator.resolve(config, ctx(now = epochAt(12, 0))),
        )
    }

    @Test
    fun `hideWhenFalse 在窗口外取反为隐藏`() {
        val config = OverlayTriggerConfig(
            listOf(
                deviceTrigger(
                    DeviceStateCondition(timeWindows = listOf(TimeWindow("09:00", "18:00"))),
                    effect = TriggerEffect.SHOW,
                    hideWhenFalse = true,
                ),
            ),
        )
        assertEquals(
            TriggerEffect.HIDE,
            OverlayTriggerEvaluator.resolve(config, ctx(now = epochAt(20, 0))),
        )
    }

    @Test
    fun `hideWhenFalse 为假时不成立等于无动作`() {
        val config = OverlayTriggerConfig(
            listOf(deviceTrigger(DeviceStateCondition(screenStates = setOf(ScreenState.ON)))),
        )
        assertNull(
            OverlayTriggerEvaluator.resolve(config, ctx(deviceState = DeviceState(screenOn = false))),
        )
    }

    @Test
    fun `hideWhenFalse 用于前台 App 退出即隐藏`() {
        val trigger = OverlayTrigger(
            kind = OverlayTriggerKind.FOREGROUND_APP.name,
            effect = TriggerEffect.SHOW,
            hideWhenFalse = true,
            packages = setOf("pkgA"),
        )
        assertEquals(
            TriggerEffect.SHOW,
            OverlayTriggerEvaluator.resolve(OverlayTriggerConfig(listOf(trigger)), ctx(foreground = "pkgA")),
        )
        assertEquals(
            TriggerEffect.HIDE,
            OverlayTriggerEvaluator.resolve(OverlayTriggerConfig(listOf(trigger)), ctx(foreground = "pkgB")),
        )
    }

    @Test
    fun `全部不可判定时无结论`() {
        val config = OverlayTriggerConfig(
            listOf(deviceTrigger(DeviceStateCondition(screenStates = setOf(ScreenState.ON)))),
        )
        assertNull(OverlayTriggerEvaluator.resolve(config, ctx(deviceState = DeviceState.TEST_MODE)))
        assertTrue(OverlayTriggerEvaluator.decide(config, ctx()).isNoop)
    }

    @Test
    fun `条件集为空时无结论`() {
        assertNull(OverlayTriggerEvaluator.resolve(OverlayTriggerConfig(), ctx(now = epochAt(12, 0))))
        assertEquals(TriggerDecision.NONE, OverlayTriggerEvaluator.decide(emptyList(), ctx()))
    }

    @Test
    fun `未配齐的条件被剔除不参与求值`() {
        val config = OverlayTriggerConfig(
            listOf(
                appTrigger(emptySet()), // packages 空 → isEffective = false
                deviceTrigger(DeviceStateCondition()), // device 全空 → isEffective = false
            ),
        )
        assertTrue(config.isEmpty)
        assertNull(OverlayTriggerEvaluator.resolve(config, ctx(event = TriggerEvent.posted("pkgA"))))
    }

    @Test
    fun `多条条件之间是 OR`() {
        val config = OverlayTriggerConfig(
            listOf(
                appTrigger(setOf("pkgA")),
                foregroundTrigger(setOf("pkgB")),
            ),
        )
        assertEquals(
            TriggerEffect.SHOW,
            OverlayTriggerEvaluator.resolve(config, ctx(foreground = "pkgB")),
        )
        assertEquals(
            TriggerEffect.SHOW,
            OverlayTriggerEvaluator.resolve(config, ctx(event = TriggerEvent.posted("pkgA"))),
        )
        assertNull(OverlayTriggerEvaluator.resolve(config, ctx(event = TriggerEvent.posted("pkgC"))))
    }

    @Test
    fun `TriggerEvent ofEdge 工厂语义`() {
        assertEquals(TriggerEventType.POSTED, TriggerEvent.ofEdge(TriggerEdge.OCCUR, "pkgA").type)
        assertEquals(TriggerEventType.REMOVED, TriggerEvent.ofEdge(TriggerEdge.REMOVE, "pkgA").type)
        assertEquals(5L, TriggerEvent.ofEdge(TriggerEdge.OCCUR, "pkgA", 5L).ruleId)
    }

    @Test
    fun `TriggerDecision effective 与 isNoop`() {
        assertEquals(TriggerEffect.SHOW, TriggerDecision(shouldShow = true).effective)
        assertEquals(TriggerEffect.HIDE, TriggerDecision(shouldHide = true).effective)
        assertNull(TriggerDecision.NONE.effective)
        assertTrue(TriggerDecision.NONE.isNoop)
    }
}
