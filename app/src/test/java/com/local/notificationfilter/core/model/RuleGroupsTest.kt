package com.local.notificationfilter.core.model

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 规则分组（V13.0，归类标签）纯函数单测。
 *
 * 为什么必须测：`Rule.groups` 是**零迁移**新增字段（旧规则 JSON 无 `groups` 键 →
 * 反序列化为空列表），改动只碰列表页过滤与编辑页录入，**不参与匹配判定**。
 * 判错的两个方向都有代价：
 *  - `normalize` 不去空白/不去重 → 列表页 tab 出现「工作」与「工作 」两个同义组，
 *    过滤时互相漏条目（用户会认为「加了组却筛不出来」）；
 *  - `filter` 对 null / 空白串也做过滤 → 「全部」页反而只剩未分组规则（灾难级）。
 * 另钉死：旧 JSON（无 groups 键）反序列化必须为空列表，保证 V13.0 覆盖安装不炸。
 */
class RuleGroupsTest {

    private fun rule(id: Long, vararg groups: String): Rule = Rule(
        id = id,
        name = "r$id",
        enabled = true,
        order = id.toInt(),
        groups = groups.toList(),
    )

    // ==================== normalize ====================

    @Test
    fun `normalize 去空白去空串去重并保序`() {
        assertEquals(
            listOf("工作", "家人"),
            RuleGroups.normalize(listOf(" 工作 ", "工作", "", "   ", "家人")),
        )
    }

    @Test
    fun `normalize 截断到 20 字`() {
        val out = RuleGroups.normalize(listOf("a".repeat(50)))
        assertEquals(1, out.size)
        assertEquals(RuleGroups.MAX_NAME_LENGTH, out[0].length)
        assertEquals("a".repeat(20), out[0])
    }

    @Test
    fun `normalize 全空输入得空列表`() {
        assertTrue(RuleGroups.normalize(emptyList()).isEmpty())
        assertTrue(RuleGroups.normalize(listOf("", " ", "\t")).isEmpty())
    }

    // ==================== distinctOf ====================

    @Test
    fun `distinctOf 汇总全部规则的分组并去重排序`() {
        val rules = listOf(
            rule(1, "工作", "家人"),
            rule(2, "家人", " 工作 "),
            rule(3),
        )
        // 排序口径 = String.compareTo（Unicode 码点），确定性即可，tab 顺序稳定最重要
        assertEquals(listOf("家人", "工作"), RuleGroups.distinctOf(rules))
    }

    @Test
    fun `distinctOf 对无分组规则集得空列表`() {
        assertTrue(RuleGroups.distinctOf(emptyList()).isEmpty())
        assertTrue(RuleGroups.distinctOf(listOf(rule(1))).isEmpty())
    }

    // ==================== filter ====================

    @Test
    fun `filter 的 null 与空白都表示不过滤`() {
        val rules = listOf(rule(1, "工作"), rule(2))
        assertEquals(rules, RuleGroups.filter(rules, null))
        assertEquals(rules, RuleGroups.filter(rules, ""))
        assertEquals(rules, RuleGroups.filter(rules, "   "))
    }

    @Test
    fun `filter 一条规则属多组时两组都能筛到它`() {
        val both = rule(1, "工作", "家人")
        val onlyWork = rule(2, "工作")
        val none = rule(3)
        val rules = listOf(both, onlyWork, none)

        assertEquals(listOf(both, onlyWork), RuleGroups.filter(rules, "工作"))
        assertEquals(listOf(both), RuleGroups.filter(rules, "家人"))
    }

    @Test
    fun `filter 命中不存在的分组得空列表`() {
        assertTrue(RuleGroups.filter(listOf(rule(1, "工作")), "不存在").isEmpty())
    }

    // ==================== 零迁移：旧 JSON 无 groups 键 ====================

    @Test
    fun `旧规则 JSON 无 groups 键时反序列化为空列表`() {
        val json = """
            {"id":7,"name":"老规则","enabled":true,"order":0,
             "matchMode":"ALL_CONTENT","fields":["TITLE"],
             "scope":{"type":"GLOBAL","packages":[]},"actions":{}}
        """.trimIndent()
        val r: Rule = NfJson.decodeFromString(json)
        assertTrue(r.groups.isEmpty())
        assertEquals(7L, r.id)
        assertEquals("老规则", r.name)
        assertEquals(MatchMode.ALL_CONTENT, r.matchMode)
    }

    @Test
    fun `带 groups 的规则 JSON 往返一致`() {
        val original = rule(9, "工作", "家人")
        val back: Rule = NfJson.decodeFromString(NfJson.encodeToString(original))
        assertEquals(listOf("工作", "家人"), back.groups)
        assertEquals(original, back)
    }
}
