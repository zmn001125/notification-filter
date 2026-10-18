package com.local.notificationfilter.data.repo

import com.local.notificationfilter.core.model.ActionType
import com.local.notificationfilter.core.model.FieldRef
import com.local.notificationfilter.core.model.MatchField
import com.local.notificationfilter.core.model.MatchMode
import com.local.notificationfilter.core.model.NfJson
import com.local.notificationfilter.core.model.NotificationSnapshot
import com.local.notificationfilter.core.model.RewriteMode
import com.local.notificationfilter.core.model.Rule
import com.local.notificationfilter.core.model.SnoozeConfig
import com.local.notificationfilter.core.model.SnoozePresets
import com.local.notificationfilter.core.model.ScopeType
import com.local.notificationfilter.core.model.AggregationConfig
import com.local.notificationfilter.core.model.AggregationOutput
import com.local.notificationfilter.core.model.DeviceStateCondition
import com.local.notificationfilter.core.model.ExtractorConfig
import com.local.notificationfilter.core.model.FloatStyle
import com.local.notificationfilter.core.model.OverlayTrigger
import com.local.notificationfilter.core.model.OverlayTriggerConfig
import com.local.notificationfilter.core.model.OverlayTriggerKind
import com.local.notificationfilter.core.model.PendingRepost
import com.local.notificationfilter.core.model.RepostRenderSource
import com.local.notificationfilter.core.model.RuleActionConfig
import com.local.notificationfilter.core.model.ScreenState
import com.local.notificationfilter.core.model.TimeWindow
import com.local.notificationfilter.core.model.TriggerEdge
import com.local.notificationfilter.core.model.TriggerEffect
import com.local.notificationfilter.core.ruleengine.RuleEngine
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * V1 → V2 规则 JSON 迁移单测（V2T04，架构 §2.6 / PRD 3.4 决策 D5）。
 *
 * V1 JSON 结构（源码事实，架构 §2.1 对照）：
 * ```
 * {"id":..,"name":..,"enabled":..,"order":..,
 *  "conditions":[{"fields":["TITLE",..],"keyword":"..","mode":"INCLUDE|EXCLUDE"},..],
 *  "scope":{"type":"GLOBAL","packages":[]},
 *  "actions":{"block":..,"snooze":null,
 *             "replace":{"mode":"CUSTOM_TEXT","customTemplate":"..",..},"readAloud":..}}
 * ```
 * 覆盖：四种 V1 形态映射 / fields 口径 / customTemplate 改名 / 幂等 /
 * needsMigration 边界 / 迁移产物 decode 往返 / 引擎语义端到端（含无条件不命中、A_NOT_B 三态）。
 */
class RuleJsonMigratorTest {

    // ==================== V1 JSON 样例（按 V1 实际序列化结构构造） ====================

    /** 全部条件 INCLUDE（V1：条件间 OR，任一包含词命中即命中）。 */
    private val v1AllInclude = """
        {"id":7,"name":"顺丰取件码","enabled":true,"order":2,
         "conditions":[
           {"fields":["TITLE","TEXT","TEXTLINES"],"keyword":"顺丰","mode":"INCLUDE"},
           {"fields":["TITLE","TEXT","TEXTLINES"],"keyword":"取件码","mode":"INCLUDE"}],
         "scope":{"type":"GLOBAL","packages":[]},
         "actions":{"block":true,"snooze":null,"replace":null,"readAloud":false}}
    """.trimIndent()

    /** 全部条件 EXCLUDE。 */
    private val v1AllExclude = """
        {"id":8,"name":"广告拦截","enabled":true,"order":1,
         "conditions":[
           {"fields":["TITLE","TEXT"],"keyword":"广告","mode":"EXCLUDE"}],
         "scope":{"type":"ONLY","packages":["com.tencent.mm"]},
         "actions":{"block":false,"snooze":null,"replace":null,"readAloud":false}}
    """.trimIndent()

    /** 混合 INCLUDE + EXCLUDE（V1 条件间 OR → V2 A-OR + B-AND，近似不等价，用户已接受）。 */
    private val v1Mixed = """
        {"id":9,"name":"微信消息但非红包","enabled":false,"order":0,
         "conditions":[
           {"fields":["TITLE","TEXT"],"keyword":"微信","mode":"INCLUDE"},
           {"fields":["TITLE","TEXT"],"keyword":"红包","mode":"EXCLUDE"}],
         "scope":{"type":"GLOBAL","packages":[]},
         "actions":{"block":false,"snooze":null,"replace":null,"readAloud":true}}
    """.trimIndent()

    /** 无条件（V1 引擎实际不命中；不得放大成「所有内容」误杀）。 */
    private val v1NoCondition = """
        {"id":10,"name":"空规则","enabled":true,"order":3,
         "conditions":[],
         "scope":{"type":"GLOBAL","packages":[]},
         "actions":{"block":true,"snooze":null,"replace":null,"readAloud":false}}
    """.trimIndent()

    /** 带替换自定义模板（customTemplate 待改名）+ 未知 V1 键。 */
    private val v1WithCustomTemplate = """
        {"id":11,"name":"改写通知","enabled":true,"order":4,
         "legacyNote":"V1 遗留的未知键，迁移后应保留且不影响 decode",
         "conditions":[
           {"fields":["TITLE"],"keyword":"取件码","mode":"INCLUDE"}],
         "scope":{"type":"EXCLUDE","packages":["com.tencent.mm"]},
         "actions":{"block":false,"snooze":null,
           "replace":{"mode":"CUSTOM_TEXT","customTemplate":"[{app}] {title}",
                      "keepIcon":true,"keepLargeImage":true,"channelUseAppName":false},
           "readAloud":false}}
    """.trimIndent()

    /** conditions 边缘：keyword 待 trim / 空白行 / 未知 mode。 */
    private val v1EdgeConditions = """
        {"id":12,"name":"边缘形态","enabled":true,"order":5,
         "conditions":[
           {"fields":["TITLE","SUBTEXT"],"keyword":"  顺丰  ","mode":"INCLUDE"},
           {"fields":["TITLE","SUBTEXT"],"keyword":"   ","mode":"INCLUDE"},
           {"fields":["TITLE","SUBTEXT"],"keyword":"推广","mode":"SOMETHING_ELSE"}],
         "scope":{"type":"GLOBAL","packages":[]},
         "actions":{"block":false,"snooze":null,"replace":null,"readAloud":false}}
    """.trimIndent()

    /** 第一个条件 fields 缺失、第二个条件 fields 非法 → 回落默认三字段。 */
    private val v1BrokenFields = """
        {"id":13,"name":"字段异常","enabled":true,"order":6,
         "conditions":[
           {"keyword":"顺丰","mode":"INCLUDE"},
           {"fields":["GARBAGE_FIELD"],"keyword":"取件码","mode":"INCLUDE"}],
         "scope":{"type":"GLOBAL","packages":[]},
         "actions":{"block":false,"snooze":null,"replace":null,"readAloud":false}}
    """.trimIndent()

    private val defaultFields =
        setOf(MatchField.TITLE, MatchField.TEXT, MatchField.TEXTLINES)

    private fun decodeRule(json: String): Rule = NfJson.decodeFromString<Rule>(json)

    private fun migratedRule(v1Json: String): Rule =
        decodeRule(RuleJsonMigrator.migrateJsonIfNeeded(v1Json))

    private fun snap(title: String? = "微信支付", text: String? = null) = NotificationSnapshot(
        key = "0|com.tencent.mm|1|null|1",
        notifId = 1,
        packageName = "com.tencent.mm",
        appName = "微信",
        title = title,
        text = text,
    )

    // ==================== ① 四种 V1 形态映射 ====================

    @Test
    fun `全部INCLUDE_映射为TEXT_ANY`() {
        val rule = migratedRule(v1AllInclude)
        assertEquals(MatchMode.TEXT_ANY, rule.matchMode)
        assertEquals(listOf("顺丰", "取件码"), rule.keywords)
        assertTrue(rule.excludeKeywords.isEmpty())
        assertTrue(rule.metaFields.isEmpty())
        assertTrue(rule.extrasFields.isEmpty())
        assertNull(rule.regex)
        // 其余键原样保留
        assertEquals(7L, rule.id)
        assertEquals("顺丰取件码", rule.name)
        assertTrue(rule.enabled)
        assertEquals(2, rule.order)
        assertTrue(rule.actions.block)
    }

    @Test
    fun `全部EXCLUDE_映射为TEXT_NOT_ANY`() {
        val rule = migratedRule(v1AllExclude)
        assertEquals(MatchMode.TEXT_NOT_ANY, rule.matchMode)
        assertEquals(listOf("广告"), rule.keywords)
        assertTrue(rule.excludeKeywords.isEmpty())
        // scope 原样保留（ONLY + 包名集合）
        assertEquals(ScopeType.ONLY, rule.scope.type)
        assertEquals(setOf("com.tencent.mm"), rule.scope.packages)
        assertEquals(8L, rule.id)
    }

    @Test
    fun `混合条件_映射为TEXT_A_NOT_B`() {
        val rule = migratedRule(v1Mixed)
        assertEquals(MatchMode.TEXT_A_NOT_B, rule.matchMode)
        assertEquals(listOf("微信"), rule.keywords)
        assertEquals(listOf("红包"), rule.excludeKeywords)
        // 元数据原样保留（enabled=false / readAloud=true）
        assertFalse(rule.enabled)
        assertTrue(rule.actions.readAloud)
    }

    @Test
    fun `无条件_映射为TEXT_ANY加空关键词`() {
        val rule = migratedRule(v1NoCondition)
        assertEquals(MatchMode.TEXT_ANY, rule.matchMode)
        assertTrue(rule.keywords.isEmpty())
        // 不得被放大成「所有内容」
        assertTrue(rule.matchMode != MatchMode.ALL_CONTENT)
        assertTrue(rule.actions.block)
    }

    @Test
    fun `fields取第一个条件`() {
        val rule = migratedRule(v1AllInclude)
        assertEquals(setOf(MatchField.TITLE, MatchField.TEXT, MatchField.TEXTLINES), rule.fields)
    }

    @Test
    fun `fields缺失或非法_回落默认三字段`() {
        val rule = migratedRule(v1BrokenFields)
        assertEquals(defaultFields, rule.fields)
        assertEquals(listOf("顺丰", "取件码"), rule.keywords)
    }

    @Test
    fun `keyword_trim后收集_空白行与未知mode弃用`() {
        val rule = migratedRule(v1EdgeConditions)
        assertEquals(MatchMode.TEXT_ANY, rule.matchMode)
        assertEquals(listOf("顺丰"), rule.keywords)
        // fields 来自第一个条件（SUBTEXT 亦保留）
        assertEquals(setOf(MatchField.TITLE, MatchField.SUBTEXT), rule.fields)
    }

    // ==================== ② customTemplate → customTitleTemplate ====================

    @Test
    fun `customTemplate改名为customTitleTemplate`() {
        val rule = migratedRule(v1WithCustomTemplate)
        val replace = rule.actions.replace!!
        assertEquals(RewriteMode.CUSTOM_TEXT, replace.mode)
        assertEquals("[{app}] {title}", replace.customTitleTemplate)
        assertNull(replace.customTextTemplate)
        // 其余 replace 键原样
        assertTrue(replace.keepIcon)
        assertTrue(replace.keepLargeImage)
        assertFalse(replace.channelUseAppName)
        // 未知 V1 键不影响 decode（ignoreUnknownKeys），scope 保留
        assertEquals(11L, rule.id)
        assertEquals(ScopeType.EXCLUDE, rule.scope.type)
    }

    // ==================== ③ 幂等性 ====================

    @Test
    fun `幂等_V2JSON原样返回`() {
        val v2Json = NfJson.encodeToString(migratedRule(v1AllInclude))
        assertFalse(RuleJsonMigrator.needsMigration(v2Json))
        // 字符串完全相等（同一引用内容，非重新编码）
        assertEquals(v2Json, RuleJsonMigrator.migrateJsonIfNeeded(v2Json))
    }

    @Test
    fun `幂等_V1JSON迁移一次后不再变化`() {
        val once = RuleJsonMigrator.migrateJsonIfNeeded(v1Mixed)
        assertTrue(once.contains("matchMode"))
        assertFalse(once.contains("conditions"))
        assertEquals(once, RuleJsonMigrator.migrateJsonIfNeeded(once))
    }

    // ==================== ④ needsMigration 边界 ====================

    @Test
    fun `needsMigration_正确区分V1与V2`() {
        assertTrue(RuleJsonMigrator.needsMigration(v1AllInclude))
        val v2Json = NfJson.encodeToString(migratedRule(v1AllInclude))
        assertFalse(RuleJsonMigrator.needsMigration(v2Json))
    }

    @Test
    fun `needsMigration_非法与非规则JSON不抛异常`() {
        val garbage = listOf(
            "", "not json", "{ broken", "[]", "null", "123", "\"str\"",
            "{}",                        // 无 conditions 键
            "{\"id\":1,\"name\":\"x\"}", // 规则样但无 conditions
        )
        garbage.forEach { json ->
            assertFalse("应判 false: $json", RuleJsonMigrator.needsMigration(json))
            assertEquals("应原样返回: $json", json, RuleJsonMigrator.migrateJsonIfNeeded(json))
        }
    }

    // ==================== ⑤⑥ 引擎语义端到端（迁移产物直接喂引擎） ====================

    @Test
    fun `端到端_全部INCLUDE_引擎命中语义与V1一致`() {
        val rule = migratedRule(v1AllInclude)
        // 任一包含词出现在任一被选字段即命中（顺丰在标题）
        assertTrue(RuleEngine.matchRule(rule, snap(title = "顺丰快递")).hit)
        // 取件码在正文同样命中
        assertTrue(RuleEngine.matchRule(rule, snap(title = "包裹提醒", text = "取件码 8888")).hit)
        // 都不出现 → 不命中
        val miss = RuleEngine.matchRule(rule, snap(title = "微信支付"))
        assertFalse(miss.hit)
        assertTrue(miss.reason.contains("包含任一未满足"))
    }

    @Test
    fun `端到端_全部EXCLUDE_不包含语义与V1一致`() {
        val rule = migratedRule(v1AllExclude)
        // scope=ONLY com.tencent.mm：本包名内，不含广告 → 命中；含广告 → 不命中
        assertTrue(RuleEngine.matchRule(rule, snap(title = "正常消息")).hit)
        val miss = RuleEngine.matchRule(rule, snap(title = "全是广告"))
        assertFalse(miss.hit)
        assertTrue(miss.reason.contains("不包含任一未满足"))
        // 作用范围先于条件：ONLY 之外的包名直接不命中
        val outOfScope = RuleEngine.matchRule(
            rule,
            snap(title = "正常消息").copy(packageName = "com.taobao.taobao"),
        )
        assertFalse(outOfScope.hit)
        assertTrue(outOfScope.reason.contains("作用范围不匹配"))
    }

    @Test
    fun `端到端_混合规则_A出现B不出现才命中`() {
        val rule = migratedRule(v1Mixed)
        // A 中 B 无 → 命中
        assertTrue(RuleEngine.matchRule(rule, snap(title = "微信支付")).hit)
        // B 出现 → 不命中
        val r2 = RuleEngine.matchRule(rule, snap(title = "微信红包"))
        assertFalse(r2.hit)
        assertTrue(r2.reason.contains("B 组"))
        // A 未出现 → 不命中
        val r3 = RuleEngine.matchRule(rule, snap(title = "支付宝到账"))
        assertFalse(r3.hit)
        assertTrue(r3.reason.contains("A 组"))
    }

    @Test
    fun `端到端_无条件规则_引擎判不命中_与V1行为一致`() {
        val rule = migratedRule(v1NoCondition)
        val result = RuleEngine.matchRule(rule, snap(title = "任何内容都不该命中"))
        assertFalse(result.hit)
        assertEquals("关键词条件为空", result.reason)
    }

    @Test
    fun `端到端_带自定义模板规则_迁移后引擎可直接消费`() {
        val rule = migratedRule(v1WithCustomTemplate)
        // scope=EXCLUDE com.tencent.mm：用范围外的包名验证条件侧命中
        val result = RuleEngine.matchRule(
            rule,
            snap(title = "取件码 8888").copy(packageName = "com.taobao.taobao"),
        )
        assertTrue(result.hit)
        assertEquals(setOf(FieldRef.Text(MatchField.TITLE)), result.hitFields)
        // 替换动作配置随 actions 原样保留 → 计划动作为替换
        assertEquals(listOf(ActionType.REPLACE), result.plannedActions)
    }

    // ==================== V2 → V3 SnoozeConfig 时刻列表迁移（架构 §2.5，D1-D3） ====================

    /** V2 规则 JSON（含 matchMode）+ actions.snooze.customTimeHHmm → 迁移后 times=[该时刻]。 */
    @Test
    fun `snooze_customTimeHHmm迁移为times_幂等`() {
        val v2 = """{"id":1,"name":"r","enabled":true,"order":1,"matchMode":"TEXT_ANY",
            "fields":["TITLE"],"metaFields":[],"extrasFields":[],"keywords":["x"],"excludeKeywords":[],"regex":null,
            "scope":{"type":"GLOBAL","packages":[]},
            "actions":{"block":false,"snooze":{"preset":"custom_time","customTimeHHmm":"20:30"},
            "replace":null,"readAloud":false}}""".trimIndent()
        val once = RuleJsonMigrator.migrateJsonIfNeeded(v2)
        val cfg = NfJson.decodeFromString<Rule>(once).actions.snooze!!
        assertEquals(listOf("20:30"), cfg.times)
        // 幂等：再迁移 times 不重复
        val twice = RuleJsonMigrator.migrateJsonIfNeeded(once)
        val cfg2 = NfJson.decodeFromString<Rule>(twice).actions.snooze!!
        assertEquals(listOf("20:30"), cfg2.times)
    }

    @Test
    fun `snooze_TONIGHT_20迁移为times_2000_幂等`() {
        val v2 = """{"id":1,"name":"r","enabled":true,"order":1,"matchMode":"TEXT_ANY",
            "fields":["TITLE"],"metaFields":[],"extrasFields":[],"keywords":["x"],"excludeKeywords":[],"regex":null,
            "scope":{"type":"GLOBAL","packages":[]},
            "actions":{"block":false,"snooze":{"preset":"tonight20"},
            "replace":null,"readAloud":false}}""".trimIndent()
        val once = RuleJsonMigrator.migrateJsonIfNeeded(v2)
        val cfg = NfJson.decodeFromString<Rule>(once).actions.snooze!!
        assertEquals(listOf("20:00"), cfg.times)
        // 幂等
        val twice = RuleJsonMigrator.migrateJsonIfNeeded(once)
        val cfg2 = NfJson.decodeFromString<Rule>(twice).actions.snooze!!
        assertEquals(listOf("20:00"), cfg2.times)
    }

    @Test
    fun `snooze_相对时长保留不换算_times仍空`() {
        // preset=10m（相对时长）→ D2 不换算成绝对时刻，旧字段保留，times 仍空
        val v2 = """{"id":1,"name":"r","enabled":true,"order":1,"matchMode":"TEXT_ANY",
            "fields":["TITLE"],"metaFields":[],"extrasFields":[],"keywords":["x"],"excludeKeywords":[],"regex":null,
            "scope":{"type":"GLOBAL","packages":[]},
            "actions":{"block":false,"snooze":{"preset":"10m"},
            "replace":null,"readAloud":false}}""".trimIndent()
        val migrated = RuleJsonMigrator.migrateJsonIfNeeded(v2)
        val cfg = NfJson.decodeFromString<Rule>(migrated).actions.snooze!!
        assertTrue(cfg.times.isEmpty())
        assertEquals(SnoozePresets.M10, cfg.preset)
    }

    @Test
    fun `snooze_已是V3形态_times非空_不动`() {
        val v3 = """{"id":1,"name":"r","enabled":true,"order":1,"matchMode":"TEXT_ANY",
            "fields":["TITLE"],"metaFields":[],"extrasFields":[],"keywords":["x"],"excludeKeywords":[],"regex":null,
            "scope":{"type":"GLOBAL","packages":[]},
            "actions":{"block":false,"snooze":{"times":["09:00","18:00"]},
            "replace":null,"readAloud":false}}""".trimIndent()
        val migrated = RuleJsonMigrator.migrateJsonIfNeeded(v3)
        val cfg = NfJson.decodeFromString<Rule>(migrated).actions.snooze!!
        assertEquals(listOf("09:00", "18:00"), cfg.times)
    }

    @Test
    fun `snooze_无snooze字段_原样返回不报错`() {
        val v2 = """{"id":1,"name":"r","enabled":true,"order":1,"matchMode":"TEXT_ANY",
            "fields":["TITLE"],"metaFields":[],"extrasFields":[],"keywords":["x"],"excludeKeywords":[],"regex":null,
            "scope":{"type":"GLOBAL","packages":[]},
            "actions":{"block":true,"snooze":null,"replace":null,"readAloud":false}}""".trimIndent()
        val migrated = RuleJsonMigrator.migrateJsonIfNeeded(v2)
        val rule = NfJson.decodeFromString<Rule>(migrated)
        assertNull(rule.actions.snooze)
    }

    // ==================== ⑤ V5 焦点通知清除（功能已删除，focus 段从 JSON 中移除） ====================

    @Test
    fun `focus_含focus段的V4规则_迁移后focus被清除且其余动作保留`() {
        // V4 时代落库的规则 JSON：actions 里带完整 focus 配置段
        val v4 = """{"id":3,"name":"取件码上岛","enabled":true,"order":0,"matchMode":"TEXT_ANY",
            "fields":["TITLE"],"metaFields":[],"extrasFields":[],"keywords":["取件码"],"excludeKeywords":[],"regex":null,
            "scope":{"type":"GLOBAL","packages":[]},
            "actions":{"block":false,"snooze":null,"replace":null,"readAloud":false,
            "danmaku":{"speedDps":200.0},
            "focus":{"titleTemplate":"{title}","textTemplate":"{text}","infoTemplate":"{extras.code}","staySeconds":90,"keepNotification":false}}}""".trimIndent()
        val migrated = RuleJsonMigrator.migrateJsonIfNeeded(v4)
        // 清除后的 JSON 不再含 focus 键（用户拍板：显式清除，而非留在数据里靠 ignoreUnknownKeys 忽略）
        assertFalse("迁移产物不应再含 focus 键", migrated.contains("\"focus\""))
        // 其余动作原样保留：弹幕动作本身不丢（样式段仍在、弹幕动作仍计划执行）。
        // 注意：speedDps（dp/秒）是 V5 已删除的旧字段，会被渐变迁移段一并弃用、
        // 回落 px/秒默认值——dp/px 随屏幕密度浮动无法无损换算，这是有意行为而非数据丢失。
        val rule = NfJson.decodeFromString<Rule>(migrated)
        val dm = rule.actions.danmaku
        assertTrue("弹幕样式段不应被清除", dm != null)
        assertEquals(
            com.local.notificationfilter.core.model.DanmakuStyle().speedPxps,
            dm!!.speedPxps,
        )
        assertEquals(listOf(ActionType.DANMAKU), rule.actions.plannedActions())
        // 清除后可正常 decode（RuleActionConfig 已无 focus 字段）
        assertFalse(rule.actions.isEmpty)
    }

    @Test
    fun `focus_清除幂等_无focus段原样返回`() {
        // 无 focus 段：输出与输入逐字符一致（保证写回不会产生无意义 diff）
        val noFocus = """{"id":1,"name":"r","enabled":true,"order":1,"matchMode":"TEXT_ANY",
            "fields":["TITLE"],"metaFields":[],"extrasFields":[],"keywords":["x"],"excludeKeywords":[],"regex":null,
            "scope":{"type":"GLOBAL","packages":[]},
            "actions":{"block":true,"snooze":null,"replace":null,"readAloud":false}}""".trimIndent()
        assertEquals(noFocus, RuleJsonMigrator.migrateJsonIfNeeded(noFocus))

        // 已清除过一次（只留弹幕）：再次调用不再变化
        val stripped = """{"id":2,"name":"r2","enabled":true,"order":0,"matchMode":"TEXT_ANY",
            "fields":["TITLE"],"metaFields":[],"extrasFields":[],"keywords":["y"],"excludeKeywords":[],"regex":null,
            "scope":{"type":"GLOBAL","packages":[]},
            "actions":{"block":false,"snooze":null,"replace":null,"readAloud":false,"danmaku":{}}}""".trimIndent()
        assertEquals(stripped, RuleJsonMigrator.migrateJsonIfNeeded(stripped))
    }

    @Test
    fun `focus_V1规则带focus_迁移链末端同样清除`() {
        // V1 形态（conditions）+ 本不该存在的 focus 键（防御性组合）：V1→V2 映射后 focus 仍被清除
        val v1WithFocus = """{"id":5,"name":"旧规则","enabled":true,"order":0,
            "conditions":[{"fields":["TITLE"],"keyword":"取件码","mode":"INCLUDE"}],
            "scope":{"type":"GLOBAL","packages":[]},
            "actions":{"block":false,"snooze":null,"replace":null,"readAloud":false,
            "focus":{"titleTemplate":"{title}","textTemplate":"{text}","infoTemplate":"","staySeconds":60,"keepNotification":true}}}""".trimIndent()
        val migrated = RuleJsonMigrator.migrateJsonIfNeeded(v1WithFocus)
        assertFalse(migrated.contains("\"focus\""))
        val rule = NfJson.decodeFromString<Rule>(migrated)
        assertEquals(MatchMode.TEXT_ANY, rule.matchMode)
        assertEquals(listOf("取件码"), rule.keywords)
    }

    // ==================== V7：新字段 JSON 兼容基线（M1–M4，架构 §2.5） ====================
    //
    // 结论（架构 D12）：V7 **不新增迁移步骤**、**不扩 needsWriteback 口径**。
    // 这四条用例是防止未来误改的回归护栏：
    // - M1 老规则 decode 后新字段为 null / 默认值；
    // - M2 needsWriteback 仍为 false（启动不写回）；
    // - M3 migrateJsonIfNeeded 对 V6 JSON **逐字符不变**（DB 里一个字节都不动）；
    // - M4 含全部 V7 新字段的规则 encode→decode 全等（且不被迁移器改写）。

    /**
     * V6 规则 JSON（真实结构：V6 用 `encodeDefaults = true` 写满 float 全字段；
     * 无 focus / 无旧渐变三键 / 无可换算 snooze → 迁移器应原样返回）。
     */
    private val v6RuleWithFloat = """
        {"id":21,"name":"取件码悬浮","enabled":true,"order":0,"matchMode":"TEXT_ANY",
         "fields":["TITLE"],"metaFields":[],"extrasFields":[],"keywords":["取件码"],
         "excludeKeywords":[],"regex":null,
         "scope":{"type":"GLOBAL","packages":[]},
         "deviceState":{"screenStates":[],"chargeStates":[],"timeWindows":[],"weekdays":[]},
         "actions":{"block":false,"snooze":null,"replace":null,"readAloud":false,"danmaku":null,
         "float":{"text":{},"box":{},"anim":"FADE","screenAlign":"TOP_END","offsetTopDp":120.0,
         "offsetEndDp":12.0,"displaySeconds":8.0,"showIcon":true,"showTitle":true,
         "separator":" · ","titleTemplate":"","textTemplate":"","clickToOpen":true}}}
    """.trimIndent()

    /** 含全部 V7 新字段的规则：5 类条件各一条 + 3 个提取器 + 双输出。 */
    private fun v7Rule(): Rule = Rule(
        id = 31L,
        name = "V7 全字段",
        enabled = true,
        order = 0,
        matchMode = MatchMode.TEXT_ANY,
        fields = setOf(MatchField.TEXT),
        keywords = listOf("取件码"),
        deviceState = DeviceStateCondition(screenStates = setOf(ScreenState.ON)),
        actions = RuleActionConfig(
            float = FloatStyle(
                displaySeconds = 0f,
                maxHeightDp = 240f,
                contentScrollable = true,
                touchable = true,
            ),
            floatTrigger = OverlayTriggerConfig(
                triggers = listOf(
                    OverlayTrigger(
                        kind = OverlayTriggerKind.DEVICE_STATE.name,
                        effect = TriggerEffect.SHOW,
                        hideWhenFalse = true,
                        device = DeviceStateCondition(
                            screenStates = setOf(ScreenState.ON),
                            timeWindows = listOf(TimeWindow("09:00", "18:00")),
                        ),
                    ),
                    OverlayTrigger(
                        kind = OverlayTriggerKind.CLOCK_AT.name,
                        effect = TriggerEffect.HIDE,
                        clockAt = "22:00",
                    ),
                    OverlayTrigger(
                        kind = OverlayTriggerKind.APP_NOTIFICATION.name,
                        effect = TriggerEffect.HIDE,
                        packages = setOf("com.taobao.taobao"),
                        edge = TriggerEdge.REMOVE,
                    ),
                    OverlayTrigger(
                        kind = OverlayTriggerKind.RULE_NOTIFICATION.name,
                        ruleIds = setOf(5L, 6L),
                    ),
                    OverlayTrigger(
                        kind = OverlayTriggerKind.FOREGROUND_APP.name,
                        hideWhenFalse = true,
                        packages = setOf("com.eg.android.AlipayGphone"),
                    ),
                ),
            ),
            aggregation = AggregationConfig(
                extractors = listOf(
                    ExtractorConfig(
                        listIndex = 1,
                        name = "取件码",
                        fieldIds = listOf("TEXT", "TITLE"),
                        pattern = "取件码[：:]?\\s*(\\d{4}-\\d{2})",
                        captureFormat = "\$1",
                        limit = 20,
                    ),
                    ExtractorConfig(
                        listIndex = 2,
                        name = "金额",
                        fieldIds = listOf("extras:filterbox.field.CODE"),
                        pattern = "¥(\\d+)",
                        dedupe = false,
                    ),
                    ExtractorConfig(
                        listIndex = 3,
                        name = "备注",
                        pattern = "备注[：:](.+)",
                        ignoreCase = false,
                    ),
                ),
                titleTemplate = "今天的快递：{items1}",
                textTemplate = "{items1}\n{items2}\n{items3}",
                itemSeparator = "\n",
                outputs = setOf(AggregationOutput.SNOOZE, AggregationOutput.FLOAT),
            ),
        ),
    )

    @Test
    fun `M1_V6规则无V7键_反序列化后新字段为默认`() {
        val rule = decodeRule(v6RuleWithFloat)
        // V7 新增的两个可空字段：缺键 → null（NfJson.ignoreUnknownKeys + 缺键取默认）
        assertNull(rule.actions.floatTrigger)
        assertNull(rule.actions.aggregation)
        // 派生语义：不是条件卡片 → ActionExecutor 仍走 V6 的命中即 showFloat
        assertFalse(rule.actions.floatIsConditional)

        // V6 既有 float 字段一字不变
        val float = rule.actions.float!!
        assertEquals(8.0f, float.displaySeconds)
        assertEquals(120.0f, float.offsetTopDp)
        assertEquals(12.0f, float.offsetEndDp)
        assertEquals(" · ", float.separator)
        assertTrue(float.showTitle)
        assertTrue(float.clickToOpen)

        // FloatStyle 的 3 个 V7 新字段取默认值 = V6 行为（不限高 / 不可滚动 / 不可触摸）
        assertEquals(0.0f, float.maxHeightDp)
        assertFalse(float.contentScrollable)
        assertFalse(float.touchable)
    }

    @Test
    fun `M2_V6规则含float_needsWriteback为false`() {
        assertFalse(RuleJsonMigrator.needsMigration(v6RuleWithFloat))
        assertFalse(RuleJsonMigrator.needsWriteback(v6RuleWithFloat))
    }

    @Test
    fun `M3_V6规则_migrate逐字符不变_零写入`() {
        val migrated = RuleJsonMigrator.migrateJsonIfNeeded(v6RuleWithFloat)
        // 逐字符相等 = 「DB 里一个字节都不变」，这是对"行为不变"最强的保证
        assertEquals(v6RuleWithFloat, migrated)
        // 不写出任何 V7 新键（比"写回默认值"更强：连 diff 都没有）
        assertFalse(migrated.contains("floatTrigger"))
        assertFalse(migrated.contains("aggregation"))
        assertFalse(migrated.contains("maxHeightDp"))
        assertFalse(migrated.contains("contentScrollable"))
        assertFalse(migrated.contains("touchable"))
        assertFalse(migrated.contains("renderSource"))
    }

    @Test
    fun `M4_V7规则_encode_decode全等_五类条件与三提取器不丢`() {
        val rule = v7Rule()
        val json = NfJson.encodeToString(rule)

        // 写路径：encodeDefaults = true → 新键确实落进 JSON（Room 单列 / 导出文件都复用）
        assertTrue(json.contains("\"floatTrigger\""))
        assertTrue(json.contains("\"aggregation\""))
        assertTrue(json.contains("\"maxHeightDp\""))
        assertTrue(json.contains("\"contentScrollable\""))
        assertTrue(json.contains("\"touchable\""))

        // 读路径：全等往返（字段名一旦漂移，这里立刻红）
        assertEquals(rule, NfJson.decodeFromString<Rule>(json))

        val decoded = NfJson.decodeFromString<Rule>(json)
        // 五类 kind 各一条，全部有效（isEffective 过滤后仍是 5 条）
        assertEquals(5, decoded.actions.floatTrigger!!.triggers.size)
        assertEquals(5, decoded.actions.floatTrigger!!.effective.size)
        assertEquals(
            OverlayTriggerKind.ALL.toSet(),
            decoded.actions.floatTrigger!!.triggers.map { it.kindEnum }.toSet(),
        )
        // float + 非空条件 → 由条件接管
        assertTrue(decoded.actions.floatIsConditional)
        // 三提取器全有效，上界 = 3（决定 findUnknown 的 itemCount）
        val agg = decoded.actions.aggregation!!
        assertEquals(3, agg.active.size)
        assertEquals(3, agg.maxListIndex)
        assertEquals("今天的快递：{items1}", agg.titleTemplate)
        assertEquals("\n", agg.itemSeparator)
        assertTrue(agg.outputSnooze)
        assertTrue(agg.outputFloat)
        assertEquals(listOf("TEXT", "TITLE"), agg.extractors[0].fieldIds)
        assertEquals("\$1", agg.extractors[0].captureFormat)
        // 迁移器同样不动 V7 JSON（V7 没有任何需迁移的形态）
        assertEquals(json, RuleJsonMigrator.migrateJsonIfNeeded(json))
    }

    @Test
    fun `M5_PendingRepost新字段默认值即老行为`() {
        val task = PendingRepost(
            triggerAt = 1_700_000_000_000L,
            originalKey = "0|com.tencent.mm|1|null|1",
            packageName = "com.tencent.mm",
            appName = "微信",
            repostTitle = "标题",
            repostText = "正文",
            replaceConfigJson = "{}",
        )
        // 老任务（V6 落库 / 新构造不填新字段）→ PLAIN + 无聚合配置
        assertEquals(RepostRenderSource.PLAIN.name, task.renderSource)
        assertNull(task.aggregationJson)
        assertEquals(RepostRenderSource.PLAIN, task.renderSourceEnum)
        assertFalse(task.isAggregated)
        // DB 里读到未来未知值 / null → 安全回落 PLAIN（老行为），不抛异常
        assertEquals(RepostRenderSource.PLAIN, RepostRenderSource.of("SOMETHING_NEW"))
        assertEquals(RepostRenderSource.PLAIN, RepostRenderSource.of(null))
        assertEquals(RepostRenderSource.AGGREGATION, RepostRenderSource.of("AGGREGATION"))
    }
}
