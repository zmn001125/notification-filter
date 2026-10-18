package com.local.notificationfilter.core.ruleengine

import com.local.notificationfilter.core.model.FloatStyle
import com.local.notificationfilter.core.model.NfJson
import com.local.notificationfilter.core.model.OverlayTrigger
import com.local.notificationfilter.core.model.OverlayTriggerConfig
import com.local.notificationfilter.core.model.OverlayTriggerKind
import com.local.notificationfilter.core.model.Rule
import com.local.notificationfilter.core.model.RuleActionConfig
import com.local.notificationfilter.service.TriggerIndex
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * V12「多悬浮窗并存 + 悬浮显示条件可选择目标规则」的编译期单测（纯 JVM，无 Android 依赖）。
 *
 * 覆盖规格：
 * 1. 条件的 `targetRuleId` 指向的规则进 [TriggerIndex.targets]（有 float 才进），
 *    条件规则自身进 [TriggerIndex.rules]；
 * 2. 指向**没有 float** 的规则 / 指向**自身**都不进 targets；
 * 3. 无条件规则 → 索引为空（零运行时开销红线不变）；
 * 4. [OverlayTrigger.targetRuleId] 的 JSON round-trip 与旧数据（缺字段）兼容。
 */
class TriggerIndexTargetTest {

    /** 测试夹具：一条启用了「自定义位置悬浮」的规则；[triggerTargetId] 非空 = 配一条指向它的条件。 */
    private fun rule(
        id: Long,
        enabled: Boolean = true,
        float: FloatStyle? = FloatStyle(),
        triggerTargetId: Long? = null,
    ): Rule = Rule(
        id = id,
        name = "规则 $id",
        enabled = enabled,
        actions = RuleActionConfig(
            float = float,
            floatTrigger = if (triggerTargetId != null) {
                OverlayTriggerConfig(
                    triggers = listOf(
                        OverlayTrigger(
                            kind = OverlayTriggerKind.APP_NOTIFICATION.name,
                            packages = setOf("com.tencent.mm"),
                            targetRuleId = triggerTargetId,
                        ),
                    ),
                )
            } else {
                null
            },
        ),
    )

    @Test
    fun `目标规则进targets_条件规则自身进rules`() {
        val a = rule(id = 1L, triggerTargetId = 2L)
        val b = rule(id = 2L)
        val index = TriggerIndex.build(listOf(a, b))
        // rules 只收条件规则（A）；targets 只收被指向且有 float 的 B
        assertEquals(setOf(1L), index.rules.keys)
        assertEquals(setOf(2L), index.targets.keys)
        assertEquals(b, index.targets[2L])
        assertTrue(!index.isEmpty)
    }

    @Test
    fun `目标规则没有float_不进targets`() {
        val a = rule(id = 1L, triggerTargetId = 3L)
        val c = rule(id = 3L, float = null)
        val index = TriggerIndex.build(listOf(a, c))
        assertEquals(setOf(1L), index.rules.keys)
        assertTrue(index.targets.isEmpty())
    }

    @Test
    fun `目标规则指向自身_不进targets_rules含自身`() {
        val a = rule(id = 1L, triggerTargetId = 1L)
        val index = TriggerIndex.build(listOf(a))
        assertEquals(setOf(1L), index.rules.keys)
        assertTrue(index.targets.isEmpty())
    }

    @Test
    fun `无条件规则_索引为空`() {
        val b = rule(id = 2L)
        val index = TriggerIndex.build(listOf(b))
        assertTrue(index.isEmpty)
        assertTrue(index.targets.isEmpty())
    }

    @Test
    fun `targetRuleId_JSON round-trip`() {
        val trigger = OverlayTrigger(
            kind = OverlayTriggerKind.APP_NOTIFICATION.name,
            packages = setOf("com.tencent.mm"),
            targetRuleId = 7L,
        )
        val json = NfJson.encodeToString(trigger)
        val decoded = NfJson.decodeFromString<OverlayTrigger>(json)
        assertEquals(7L, decoded.targetRuleId)
    }

    @Test
    fun `旧JSON缺targetRuleId字段_反序列化为null`() {
        // V11 及更早的规则 JSON 没有这个键；ignoreUnknownKeys + 默认值保证老数据行为不变
        val oldJson = """{"kind":"APP_NOTIFICATION","effect":"SHOW","hideWhenFalse":false,""" +
            """"packages":["com.tencent.mm"],"edge":"OCCUR"}"""
        val decoded = NfJson.decodeFromString<OverlayTrigger>(oldJson)
        assertNull(decoded.targetRuleId)
    }
}
