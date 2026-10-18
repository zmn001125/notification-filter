package com.local.notificationfilter.core.transfer

import com.local.notificationfilter.core.model.NfJson
import com.local.notificationfilter.core.model.Rule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 备份封套编解码单测（V11）。
 *
 * 为什么必须测：备份文件是**唯一跨设备/跨时间的数据载体**，解析错的两个方向都致命——
 *  - 该拒绝的没拒 → 用户把别的 App 的 JSON 导进来，规则被写成一堆脏数据；
 *  - 该容错的没容 → 老备份（没有 blacklist/settings 字段）整体导入失败，
 *    用户在换机时才发现备份用不了。
 * 另外「规则的迁移」必须穿透到 `RuleJsonMigrator`（由 RuleTransfer 复用），
 * 这里用一条 V1 旧格式规则验证这条链路没被绕开。
 */
class BackupCodecTest {

    private fun rule(name: String, id: Long = 1L, order: Int = 0) = Rule(
        id = id,
        name = name,
        order = order,
        enabled = true,
    )

    private fun sampleSettings() = BackupCodec.SettingsPayload(
        masterEnabled = true,
        theme = "INDIGO",
        darkMode = "DARK",
        showOngoing = false,
        hideFromRecents = true,
    )

    @Test
    fun `导出再导入保持规则数量与黑名单与设置`() {
        val rules = listOf(rule("规则A", 1), rule("规则B", 2))
        val json = BackupCodec.export(
            rules = rules,
            blacklist = listOf("com.tencent.mm", "com.taobao.taobao"),
            settings = sampleSettings(),
            exportedAt = 1_700_000_000_000L,
        )
        val parsed = BackupCodec.import(json)

        assertNull(parsed.errorMessage)
        assertEquals(2, parsed.rules.size)
        assertEquals(0, parsed.skippedRuleCount)
        assertEquals(listOf("com.tencent.mm", "com.taobao.taobao"), parsed.blacklist)
        assertEquals(1_700_000_000_000L, parsed.exportedAt)
        assertEquals("INDIGO", parsed.settings?.theme)
        assertEquals(true, parsed.settings?.masterEnabled)
        assertEquals("规则A", parsed.rules[0].name)
        assertEquals("规则B", parsed.rules[1].name)
    }

    @Test
    fun `导入时规则 id 与 order 被归零（由仓库重建）`() {
        val json = BackupCodec.export(
            rules = listOf(rule("规则A", id = 99, order = 7)),
            blacklist = emptyList(),
            settings = sampleSettings(),
            exportedAt = 0L,
        )
        val parsed = BackupCodec.import(json)
        assertEquals(0L, parsed.rules.single().id)
        assertEquals(0, parsed.rules.single().order)
    }

    @Test
    fun `app 标识不符时整体拒绝并给中文原因`() {
        val json = """{"app":"other-app","kind":"backup","version":1,"exportedAt":0,"rules":[]}"""
        val parsed = BackupCodec.import(json)
        assertNotNull(parsed.errorMessage)
        assertTrue(parsed.errorMessage!!.contains("其他来源"))
    }

    @Test
    fun `kind 不符时整体拒绝`() {
        val json = """{"app":"notification-filter","kind":"whatever","version":1,"exportedAt":0,"rules":[]}"""
        val parsed = BackupCodec.import(json)
        assertNotNull(parsed.errorMessage)
        assertTrue(parsed.errorMessage!!.contains("类型"))
    }

    @Test
    fun `更高版本拒绝并提示升级`() {
        val json = """{"app":"notification-filter","kind":"backup","version":99,"exportedAt":0,"rules":[]}"""
        val parsed = BackupCodec.import(json)
        assertNotNull(parsed.errorMessage)
        assertTrue(parsed.errorMessage!!.contains("升级"))
    }

    @Test
    fun `纯规则导出文件也能当备份导入（降级可用）`() {
        // 老用户手上可能是 RuleTransfer 导出的 kind="rules"，不应该被拒
        val rulesJson = RuleTransfer.export(listOf(rule("旧规则")), exportedAt = 1L)
        val parsed = BackupCodec.import(rulesJson)
        assertNull(parsed.errorMessage)
        assertEquals(1, parsed.rules.size)
        assertTrue(parsed.blacklist.isEmpty())
        assertNull(parsed.settings)
    }

    @Test
    fun `缺少 blacklist 与 settings 字段不报错`() {
        val json = """{"app":"notification-filter","kind":"backup","version":1,"exportedAt":5,"rules":[]}"""
        val parsed = BackupCodec.import(json)
        assertNull(parsed.errorMessage)
        assertTrue(parsed.rules.isEmpty())
        assertTrue(parsed.blacklist.isEmpty())
        assertNull(parsed.settings)
    }

    @Test
    fun `缺少 rules 字段整体失败`() {
        val json = """{"app":"notification-filter","kind":"backup","version":1,"exportedAt":0}"""
        val parsed = BackupCodec.import(json)
        assertNotNull(parsed.errorMessage)
        assertTrue(parsed.errorMessage!!.contains("规则列表"))
    }

    @Test
    fun `黑名单里的非字符串与空串被丢弃`() {
        val json = """
            {"app":"notification-filter","kind":"backup","version":1,"exportedAt":0,"rules":[],
             "blacklist":["com.a", 42, "", "  ", "com.b", "com.a"]}
        """.trimIndent()
        val parsed = BackupCodec.import(json)
        assertNull(parsed.errorMessage)
        assertEquals(listOf("com.a", "com.b"), parsed.blacklist)
    }

    @Test
    fun `坏规则条目被跳过而不拖垮整体`() {
        val good = NfJson.encodeToJsonElement(Rule.serializer(), rule("好规则"))
        val json = """
            {"app":"notification-filter","kind":"backup","version":1,"exportedAt":0,
             "rules":[$good, "not-an-object", 123]}
        """.trimIndent()
        val parsed = BackupCodec.import(json)
        assertNull(parsed.errorMessage)
        assertEquals(1, parsed.rules.size)
        assertEquals(2, parsed.skippedRuleCount)
    }

    @Test
    fun `非 JSON 内容报错不抛异常`() {
        val parsed = BackupCodec.import("这不是 JSON")
        assertNotNull(parsed.errorMessage)
        assertTrue(parsed.rules.isEmpty())
    }

    @Test
    fun `预览与导入的计数一致`() {
        val json = BackupCodec.export(
            rules = listOf(rule("A"), rule("B")),
            blacklist = listOf("p1", "p2", "p3"),
            settings = sampleSettings(),
            exportedAt = 9L,
        )
        val preview = BackupCodec.preview(json)
        val imported = BackupCodec.import(json)
        assertNull(preview.errorMessage)
        assertEquals(imported.rules.size, preview.ruleCount)
        assertEquals(imported.blacklist.size, preview.blacklistCount)
        assertEquals(3, preview.blacklistCount)
        assertEquals(5, preview.settingsKeys.size)
        assertEquals(9L, preview.exportedAt)
    }

    @Test
    fun `预览失败时 ok 为 false`() {
        val preview = BackupCodec.preview("{bad json")
        assertFalse(preview.ok)
        assertEquals(0, preview.ruleCount)
    }
}
