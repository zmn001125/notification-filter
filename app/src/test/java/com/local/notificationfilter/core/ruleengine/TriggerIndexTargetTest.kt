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
 * V12.1：目标规则从**单选**放宽为**多选**（[OverlayTrigger.targetRuleIds]），
 * 旧单字段 [OverlayTrigger.targetRuleId] 仅为兼容旧 JSON 保留，两者经
 * [OverlayTrigger.allTargetRuleIds] 合并。
 *
 * 覆盖规格：
 * 1. 条件的目标规则（旧单字段 / 新多选字段 / 两者合并）进 [TriggerIndex.targets]
 *    （有 float 才进），条件规则自身进 [TriggerIndex.rules]；
 * 2. 指向**没有 float** 的规则 / 指向**自身**都不进 targets；
 * 3. 无条件规则 → 索引为空（零运行时开销红线不变）；
 * 4. [OverlayTrigger.targetRuleIds] / [OverlayTrigger.targetRuleId] 的 JSON round-trip
 *    与旧数据（缺字段 / 只有旧单字段）兼容；
 * 5. [OverlayTrigger.summary] 在单目标 / 多目标下的文案。
 */
class TriggerIndexTargetTest {

    /** 测试夹具：一条启用了「自定义位置悬浮」的规则；目标字段非空 = 配一条指向它的条件。 */
    private fun rule(
        id: Long,
        enabled: Boolean = true,
        float: FloatStyle? = FloatStyle(),
        triggerTargetId: Long? = null,
        triggerTargetIds: List<Long> = emptyList(),
    ): Rule = Rule(
        id = id,
        name = "规则 $id",
        enabled = enabled,
        actions = RuleActionConfig(
            float = float,
            floatTrigger = if (triggerTargetId != null || triggerTargetIds.isNotEmpty()) {
                OverlayTriggerConfig(
                    triggers = listOf(
                        OverlayTrigger(
                            kind = OverlayTriggerKind.APP_NOTIFICATION.name,
                            packages = setOf("com.tencent.mm"),
                            targetRuleId = triggerTargetId,
                            targetRuleIds = triggerTargetIds,
                        ),
                    ),
                )
            } else {
                null
            },
        ),
    )

    @Test
    fun `旧单目标字段_目标规则进targets_条件规则自身进rules`() {
        // V12.0 兼容性红线：老数据只有 targetRuleId，也必须能驱动目标悬浮窗
        val a = rule(id = 1L, triggerTargetId = 5L)
        val b = rule(id = 5L)
        val index = TriggerIndex.build(listOf(a, b))
        // rules 只收条件规则（A）；targets 只收被指向且有 float 的 B
        assertEquals(setOf(1L), index.rules.keys)
        assertEquals(setOf(5L), index.targets.keys)
        assertEquals(b, index.targets[5L])
        assertTrue(!index.isEmpty)
    }

    @Test
    fun `新多目标字段_多个目标都进targets`() {
        val a = rule(id = 1L, triggerTargetIds = listOf(5L, 6L))
        val b = rule(id = 5L)
        val c = rule(id = 6L)
        val index = TriggerIndex.build(listOf(a, b, c))
        assertEquals(setOf(1L), index.rules.keys)
        // 两个目标都配了 float → 同时进 targets（一条条件控制两扇窗）
        assertEquals(setOf(5L, 6L), index.targets.keys)
        assertTrue(!index.isEmpty)
    }

    @Test
    fun `新旧目标字段合并_并集都进targets`() {
        val a = rule(id = 1L, triggerTargetId = 5L, triggerTargetIds = listOf(6L))
        val b = rule(id = 5L)
        val c = rule(id = 6L)
        val index = TriggerIndex.build(listOf(a, b, c))
        // allTargetRuleIds = {5, 6}：旧单字段与新多选字段取并集
        assertEquals(setOf(5L, 6L), index.targets.keys)
    }

    @Test
    fun `目标规则没有float_不进targets`() {
        val a = rule(id = 1L, triggerTargetIds = listOf(3L, 4L))
        val c = rule(id = 3L, float = null)
        val d = rule(id = 4L)
        val index = TriggerIndex.build(listOf(a, c, d))
        assertEquals(setOf(1L), index.rules.keys)
        // 3 没有 float 被跳过；4 有 float 正常进
        assertEquals(setOf(4L), index.targets.keys)
    }

    @Test
    fun `指向自身的id出现在targetRuleIds里_不进targets_rules含自身`() {
        val a = rule(id = 1L, triggerTargetIds = listOf(1L, 2L))
        val b = rule(id = 2L)
        val index = TriggerIndex.build(listOf(a, b))
        assertEquals(setOf(1L), index.rules.keys)
        // 1 是条件规则自身（不收进 targets）；2 正常进
        assertEquals(setOf(2L), index.targets.keys)
    }

    @Test
    fun `无条件规则_索引为空`() {
        val b = rule(id = 2L)
        val index = TriggerIndex.build(listOf(b))
        assertTrue(index.isEmpty)
        assertTrue(index.targets.isEmpty())
    }

    @Test
    fun `targetRuleIds_JSON round-trip`() {
        val trigger = OverlayTrigger(
            kind = OverlayTriggerKind.APP_NOTIFICATION.name,
            packages = setOf("com.tencent.mm"),
            targetRuleId = 7L,
            targetRuleIds = listOf(8L, 9L),
        )
        val json = NfJson.encodeToString(trigger)
        val decoded = NfJson.decodeFromString<OverlayTrigger>(json)
        assertEquals(listOf(8L, 9L), decoded.targetRuleIds)
        assertEquals(7L, decoded.targetRuleId)
        // 全部目标 = 多选字段 ∪ 旧单字段
        assertEquals(setOf(7L, 8L, 9L), decoded.allTargetRuleIds)
    }

    @Test
    fun `旧JSON只含targetRuleId_反序列化后allTargetRuleIds含它`() {
        // V12.0 及更早的规则 JSON 只有单目标键；ignoreUnknownKeys + 默认值保证老数据行为不变
        val oldJson = """{"kind":"APP_NOTIFICATION","effect":"SHOW","hideWhenFalse":false,""" +
            """"packages":["com.tencent.mm"],"targetRuleId":5,"edge":"OCCUR"}"""
        val decoded = NfJson.decodeFromString<OverlayTrigger>(oldJson)
        assertEquals(5L, decoded.targetRuleId)
        assertTrue(decoded.targetRuleIds.isEmpty())
        assertEquals(setOf(5L), decoded.allTargetRuleIds)
    }

    @Test
    fun `旧JSON缺两个目标字段_反序列化为默认空值`() {
        // V11 及更早的规则 JSON 没有这两个键
        val oldJson = """{"kind":"APP_NOTIFICATION","effect":"SHOW","hideWhenFalse":false,""" +
            """"packages":["com.tencent.mm"],"edge":"OCCUR"}"""
        val decoded = NfJson.decodeFromString<OverlayTrigger>(oldJson)
        assertNull(decoded.targetRuleId)
        assertTrue(decoded.targetRuleIds.isEmpty())
        assertTrue(decoded.allTargetRuleIds.isEmpty())
    }

    @Test
    fun `summary_单目标与多目标文案`() {
        fun triggerOf(vararg ids: Long) = OverlayTrigger(
            kind = OverlayTriggerKind.APP_NOTIFICATION.name,
            packages = setOf("com.tencent.mm"),
            targetRuleIds = ids.toList(),
        )
        // 0 个目标：不提目标
        assertTrue(!triggerOf().summary().contains("指定规则"))
        // 1 个目标：沿用 V12.0 文案
        assertTrue(triggerOf(5L).summary().contains("（作用于指定规则）"))
        // 多个目标：文案点明条数
        assertTrue(triggerOf(5L, 6L).summary().contains("（作用于 2 条指定规则）"))
    }
}
