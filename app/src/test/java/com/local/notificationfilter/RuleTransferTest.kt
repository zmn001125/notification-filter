package com.local.notificationfilter

import com.local.notificationfilter.core.model.MatchField
import com.local.notificationfilter.core.model.MatchMode
import com.local.notificationfilter.core.model.MetaFieldKind
import com.local.notificationfilter.core.model.RegexConfig
import com.local.notificationfilter.core.model.ReplaceConfig
import com.local.notificationfilter.core.model.RewriteMode
import com.local.notificationfilter.core.model.Rule
import com.local.notificationfilter.core.model.RuleScope
import com.local.notificationfilter.core.model.ScopeType
import com.local.notificationfilter.core.model.SnoozeConfig
import com.local.notificationfilter.core.transfer.ImportResult
import com.local.notificationfilter.core.transfer.RuleTransfer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 规则导入导出封套单测（V5 需求⑦，纯 JVM）。
 *
 * 覆盖：导出→导入往返字段一致 / 封套校验（app、kind、version 中文报错）/
 * 畸形 JSON 不崩溃 / 封套内嵌 V1 旧格式规则经迁移导入 / 空 rules 数组 /
 * 好坏条目混合时好条目照常导入且计数正确。
 */
class RuleTransferTest {

    /** 功能较全的规则样例（正则 + 元信息 + extras 字段 + 范围 + 替换动作）。 */
    private val ruleFull = Rule(
        id = 42L,
        name = "快递通知精简",
        enabled = true,
        order = 3,
        matchMode = MatchMode.REGEX,
        fields = setOf(MatchField.TITLE, MatchField.TEXT),
        metaFields = setOf(MetaFieldKind.CHANNEL_ID),
        extrasFields = setOf("filterbox.field.CHANNEL_ID"),
        regex = RegexConfig(pattern = "取件码\\s*(\\d+)", ignoreCase = true),
        scope = RuleScope(type = ScopeType.ONLY, packages = setOf("com.sf.express")),
        actions = com.local.notificationfilter.core.model.RuleActionConfig(
            replace = ReplaceConfig(
                mode = RewriteMode.CUSTOM_TEXT,
                customTitleTemplate = "快递：{app}",
                customTextTemplate = "{text}",
                keepIcon = true,
            ),
            readAloud = true,
        ),
    )

    /** A_NOT_B 关键词规则样例。 */
    private val ruleKeywords = Rule(
        id = 7L,
        name = "广告 A 非 B",
        enabled = false,
        order = 0,
        matchMode = MatchMode.TEXT_A_NOT_B,
        keywords = listOf("优惠", "红包"),
        excludeKeywords = listOf("已过期"),
        actions = com.local.notificationfilter.core.model.RuleActionConfig(
            block = true,
            snooze = SnoozeConfig(times = listOf("20:00", "08:30")),
        ),
    )

    // ==================== 往返一致 ====================

    @Test
    fun `export then import roundtrip preserves all portable fields`() {
        val exported = RuleTransfer.export(listOf(ruleFull, ruleKeywords), exportedAt = 1_700_000_000_000L)
        val result = RuleTransfer.import(exported)

        assertNull(result.errorMessage)
        assertEquals(2, result.successCount)
        assertEquals(0, result.skippedCount)
        // id / order 由导入方重建（归零），其余字段逐项一致
        result.rules.forEachIndexed { index, imported ->
            val origin = listOf(ruleFull, ruleKeywords)[index]
            assertEquals(0L, imported.id)
            assertEquals(0, imported.order)
            assertEquals(origin.copy(id = 0L, order = 0), imported)
        }
    }

    @Test
    fun `single rule export uses same envelope with one element`() {
        val exported = RuleTransfer.export(ruleFull)
        assertTrue(exported.contains("\"app\":\"notification-filter\""))
        assertTrue(exported.contains("\"kind\":\"rules\""))
        assertTrue(exported.contains("\"version\":1"))

        val result = RuleTransfer.import(exported)
        assertNull(result.errorMessage)
        assertEquals(1, result.successCount)
        assertEquals(ruleFull.copy(id = 0L, order = 0), result.rules.single())
    }

    // ==================== 封套校验 ====================

    @Test
    fun `wrong app is rejected with chinese reason`() {
        val json = """{"app":"other-app","kind":"rules","version":1,"exportedAt":0,"rules":[]}"""
        val result = RuleTransfer.import(json)
        assertNotNull(result.errorMessage)
        assertTrue(result.errorMessage!!.contains("app"))
        assertTrue(result.rules.isEmpty())
    }

    @Test
    fun `wrong kind is rejected`() {
        val json = """{"app":"notification-filter","kind":"settings","version":1,"exportedAt":0,"rules":[]}"""
        val result = RuleTransfer.import(json)
        assertNotNull(result.errorMessage)
        assertTrue(result.errorMessage!!.contains("类型"))
    }

    @Test
    fun `unsupported version is rejected`() {
        val json = """{"app":"notification-filter","kind":"rules","version":99,"exportedAt":0,"rules":[]}"""
        val result = RuleTransfer.import(json)
        assertNotNull(result.errorMessage)
        assertTrue(result.errorMessage!!.contains("版本"))
    }

    @Test
    fun `missing version is rejected`() {
        val json = """{"app":"notification-filter","kind":"rules","exportedAt":0,"rules":[]}"""
        assertNotNull(RuleTransfer.import(json).errorMessage)
    }

    // ==================== 容错 ====================

    @Test
    fun `malformed json does not crash and reports error`() {
        listOf("", "not json {{{", "[1,2,3]", "\"just a string\"", "null").forEach { bad ->
            val result = RuleTransfer.import(bad)
            assertNotNull("应给出错误原因: <$bad>", result.errorMessage)
            assertTrue(result.rules.isEmpty())
        }
    }

    @Test
    fun `empty rules array imports nothing without error`() {
        val exported = RuleTransfer.export(emptyList())
        val result = RuleTransfer.import(exported)
        assertNull(result.errorMessage)
        assertEquals(0, result.successCount)
        assertEquals(0, result.skippedCount)
        assertTrue(result.rules.isEmpty())
    }

    @Test
    fun `bad entries are skipped while good ones import with correct counts`() {
        val good = com.local.notificationfilter.core.model.NfJson
            .encodeToJsonElement(Rule.serializer(), ruleKeywords)
        val envelope = """
            {"app":"notification-filter","kind":"rules","version":1,"exportedAt":0,
             "rules":[$good, 123, {"name":5}, "oops"]}
        """.trimIndent()

        val result: ImportResult = RuleTransfer.import(envelope)
        assertNull(result.errorMessage)
        assertEquals(1, result.successCount)
        assertEquals(3, result.skippedCount)
        assertEquals(ruleKeywords.copy(id = 0L, order = 0), result.rules.single())
    }

    // ==================== 旧格式兼容 ====================

    @Test
    fun `legacy v1 rule json inside envelope is migrated and imported`() {
        // V1 结构（conditions + mode INCLUDE/EXCLUDE，无 matchMode），与 RuleJsonMigratorTest 同口径
        val v1Legacy = """
            {"id":7,"name":"顺丰取件码","enabled":true,"order":2,
             "conditions":[
               {"fields":["TITLE","TEXT","TEXTLINES"],"keyword":"顺丰","mode":"INCLUDE"},
               {"fields":["TITLE","TEXT","TEXTLINES"],"keyword":"取件码","mode":"INCLUDE"}],
             "scope":{"type":"GLOBAL","packages":[]},
             "actions":{"block":true,"snooze":null,"replace":null,"readAloud":false}}
        """.trimIndent()
        val envelope = """
            {"app":"notification-filter","kind":"rules","version":1,"exportedAt":0,
             "rules":[$v1Legacy]}
        """.trimIndent()

        val result = RuleTransfer.import(envelope)
        assertNull(result.errorMessage)
        assertEquals(1, result.successCount)
        val rule = result.rules.single()
        assertEquals(0L, rule.id)
        assertEquals(0, rule.order)
        assertEquals("顺丰取件码", rule.name)
        assertEquals(MatchMode.TEXT_ANY, rule.matchMode)
        assertEquals(listOf("顺丰", "取件码"), rule.keywords)
        assertTrue(rule.actions.block)
    }
}
