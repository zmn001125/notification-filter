package com.local.notificationfilter.core.model

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * V13.2「也消除常驻通知」子选项单测（零迁移 + 子选项语义）。
 *
 * 覆盖（任务书三条硬性要求）：
 * 1. **零迁移断言（最重要）**：旧规则 JSON（`actions` 里没有 `blockOngoing` 键）
 *    反序列化后 `actions.blockOngoing == false`——V13.2 之前落库的规则行为逐字不变；
 * 2. `RuleActionConfig(blockOngoing = true)` 经 NfJson 往返值不变（存法 = 普通布尔键）；
 * 3. `plannedActions()` 不因 `blockOngoing` 多出任何 ActionType（它是子选项，不是新动作）。
 */
class BlockOngoingTest {

    // ==================== 1. 零迁移：旧 JSON 无该键 → false ====================

    /** V12 时代旧规则：actions 齐全但**没有** blockOngoing 键。 */
    private val legacyRuleJson = """
        {"id":7,"name":"V12旧规则","enabled":true,"order":2,
         "matchMode":"TEXT_ANY","fields":["TITLE","TEXT"],"keywords":["顺丰"],
         "scope":{"type":"GLOBAL","packages":[]},
         "actions":{"block":true,"snooze":null,"replace":null,"readAloud":false,
                    "silentRemove":true,"danmaku":null,"float":null,
                    "floatTrigger":null,"aggregation":null}}
    """.trimIndent()

    /** 更早的旧规则：actions 只有最老的四个键。 */
    private val ancientRuleJson = """
        {"id":8,"name":"远古规则","enabled":true,"order":0,
         "matchMode":"ALL_CONTENT",
         "scope":{"type":"GLOBAL","packages":[]},
         "actions":{"block":true,"snooze":null,"replace":null,"readAloud":false}}
    """.trimIndent()

    @Test
    fun 旧规则JSON无blockOngoing键_反序列化为false() {
        val rule = NfJson.decodeFromString<Rule>(legacyRuleJson)
        assertFalse("旧规则 decode 后 blockOngoing 必须为 false（零迁移）", rule.actions.blockOngoing)
        // 同时验证静音消除这个先行子选项也不回归
        assertTrue(rule.actions.silentRemove)
        assertTrue(rule.actions.block)

        val ancient = NfJson.decodeFromString<Rule>(ancientRuleJson)
        assertFalse(ancient.actions.blockOngoing)
        assertFalse(ancient.actions.silentRemove)
    }

    @Test
    fun 旧RuleActionConfig_JSON无该键_反序列化为false() {
        // 只 decode actions 一段（最小复现单元）
        val cfg = NfJson.decodeFromString<RuleActionConfig>(
            """{"block":true,"snooze":null,"replace":null,"readAloud":false}""",
        )
        assertFalse(cfg.blockOngoing)
        assertFalse(cfg.silentRemove)
        assertTrue(cfg.block)
    }

    // ==================== 2. 往返一致 ====================

    @Test
    fun blockOngoing为true_JSON往返值不变() {
        val cfg = RuleActionConfig(
            block = true,
            blockOngoing = true,
            silentRemove = true,
        )
        val json = NfJson.encodeToString(cfg)
        // 存法 = 规则 JSON 单列里的普通布尔键（encodeDefaults = true，键必落库）
        assertTrue(json.contains("\"blockOngoing\":true"))
        val decoded = NfJson.decodeFromString<RuleActionConfig>(json)
        assertEquals(cfg, decoded)
        assertTrue(decoded.blockOngoing)
    }

    @Test
    fun blockOngoing为false_JSON往返值不变() {
        val cfg = RuleActionConfig(block = true)
        val decoded = NfJson.decodeFromString<RuleActionConfig>(NfJson.encodeToString(cfg))
        assertEquals(cfg, decoded)
        assertFalse(decoded.blockOngoing)
    }

    @Test
    fun 整条Rule往返_blockOngoing不丢() {
        val rule = Rule(
            id = 42L,
            name = "压常驻",
            matchMode = MatchMode.ALL_CONTENT,
            actions = RuleActionConfig(block = true, blockOngoing = true),
        )
        val decoded = NfJson.decodeFromString<Rule>(NfJson.encodeToString(rule))
        assertTrue(decoded.actions.blockOngoing)
        assertTrue(decoded.actions.block)
    }

    // ==================== 3. plannedActions 不多出动作 ====================

    @Test
    fun plannedActions_不因blockOngoing多出动作() {
        val withSub = RuleActionConfig(block = true, blockOngoing = true).plannedActions()
        val withoutSub = RuleActionConfig(block = true, blockOngoing = false).plannedActions()
        assertEquals(withoutSub, withSub)
        assertEquals(listOf(ActionType.BLOCK), withSub)
    }

    @Test
    fun plannedActions_未勾消除时子选项不产动作() {
        // 子选项只在 block 为 true 时有意义：block=false 时它就是普通布尔死字段
        assertTrue(RuleActionConfig(block = false, blockOngoing = true).plannedActions().isEmpty())
        // VM 落库时用 s.block && s.blockOngoing 把关，这里锁定模型层语义：
        // block=false + blockOngoing=true 与全 false 等价
        assertEquals(
            RuleActionConfig().plannedActions(),
            RuleActionConfig(block = false, blockOngoing = true).plannedActions(),
        )
    }

    @Test
    fun isEmpty_不因blockOngoing变化() {
        // 子选项单独勾选不改变「空动作」判定（block 才是门）：与全 false 完全等价
        assertTrue(RuleActionConfig(blockOngoing = true).isEmpty)
        assertEquals(RuleActionConfig().isEmpty, RuleActionConfig(blockOngoing = true).isEmpty)
        // block=true 时两者都非空（block 决定）
        assertFalse(RuleActionConfig(block = true, blockOngoing = true).isEmpty)
    }
}
