package com.local.notificationfilter.core.ruleengine

import com.local.notificationfilter.core.model.ActionType
import com.local.notificationfilter.core.model.DeviceState
import com.local.notificationfilter.core.model.DeviceStateCondition
import com.local.notificationfilter.core.model.FieldRef
import com.local.notificationfilter.core.model.GroupMatchMode
import com.local.notificationfilter.core.model.KeywordGroup
import com.local.notificationfilter.core.model.KeywordGroupsConfig
import com.local.notificationfilter.core.model.MatchField
import com.local.notificationfilter.core.model.MatchMode
import com.local.notificationfilter.core.model.MetaFieldKind
import com.local.notificationfilter.core.model.NfJson
import com.local.notificationfilter.core.model.NotificationSnapshot
import com.local.notificationfilter.core.model.RegexConfig
import com.local.notificationfilter.core.model.ReplaceConfig
import com.local.notificationfilter.core.model.RewriteMode
import com.local.notificationfilter.core.model.Rule
import com.local.notificationfilter.core.model.RuleActionConfig
import com.local.notificationfilter.core.model.RuleConditionDefaults
import com.local.notificationfilter.core.model.RuleScope
import com.local.notificationfilter.core.model.ScopeType
import com.local.notificationfilter.core.model.ScreenState
import com.local.notificationfilter.core.model.SnoozeConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 规则引擎 JVM 单测（V2T03 验收用例集，无 Android 依赖）。
 *
 * 覆盖规格（架构 §3 / PRD 3.4）：
 * 1. 既有七模式语义：ALL_CONTENT 恒真；TEXT_ANY；TEXT_ALL 跨字段分布（顺丰在标题 +
 *    取件码在正文 → 命中）；TEXT_NOT_ANY 与 TEXT_NOT_ALL 的 K={A,B} 三情况对照表；
 *    TEXT_A_NOT_B（A 命中且 B 均不出现 → 命中 / B 出现一个 → 不命中 / A 未命中 → 不命中）；
 *    REGEX 逐字段。
 * 2. 正则：默认不区分大小写；ignoreCase=false 区分；逐字段命中归因。
 * 3. 字段扩展：Meta(KEY/NOTIF_ID/TAG/CHANNEL_ID/USER_ID/IS_ONGOING) 与
 *    FieldRef.Extras（含深层键名，无此键按空串）。
 * 4. 防御性：文本模式空关键词 / A_NOT_B 的 A 空或 B 空 / REGEX 空 pattern 均不命中。
 * 5. 回归：scope 三态、order 首个命中、disabled 跳过、动作计划、大小写不敏感。
 * 6. FieldRef.fromId 与旧数据兼容；Rule.summary() 新文案。
 * 7. V12.2 分组匹配（TEXT_GROUPS）：组间 AND、组内 ANY/ALL、跨字段分布、
 *    hitFields 并集去重、空词/空组防御性；keywordGroups JSON round-trip 与
 *    旧规则（无该键）反序列化为 null 的零迁移兼容。
 */
class RuleEngineTest {

    // ==================== 测试夹具 ====================

    private fun snap(
        pkg: String = "com.tencent.mm",
        title: String? = "微信支付",
        text: String? = "你已到账 100 元",
        textLines: List<String> = listOf("张三: 在吗", "李四: 晚上吃饭"),
        subText: String? = null,
        summaryText: String? = null,
        people: List<String> = emptyList(),
        notifId: Int = 1,
        userId: Int = 0,
        uid: Int = 1,
        channelId: String? = null,
        isOngoing: Boolean = false,
        tag: String? = null,
        extras: List<Pair<String, String>> = emptyList(),
    ) = NotificationSnapshot(
        // key 格式 userId|pkg|id|tag|uid（系统稳定格式；第 4 段即 tag）
        key = "$userId|$pkg|$notifId|${tag ?: "null"}|$uid",
        notifId = notifId,
        packageName = pkg,
        appName = "微信",
        userId = userId,
        channelId = channelId,
        postTime = 1_700_000_000_000L,
        isOngoing = isOngoing,
        title = title,
        text = text,
        textLines = textLines,
        subText = subText,
        summaryText = summaryText,
        people = people,
        extras = extras,
        tag = tag,
    )

    private fun rule(
        id: Long = 1L,
        name: String = "测试规则",
        enabled: Boolean = true,
        order: Int = 0,
        matchMode: MatchMode = MatchMode.ALL_CONTENT,
        fields: Set<MatchField> = RuleConditionDefaults.FIELDS,
        metaFields: Set<MetaFieldKind> = emptySet(),
        extrasFields: Set<String> = emptySet(),
        keywords: List<String> = emptyList(),
        excludeKeywords: List<String> = emptyList(),
        regex: RegexConfig? = null,
        keywordGroups: KeywordGroupsConfig? = null,
        scope: RuleScope = RuleScope(),
        actions: RuleActionConfig = RuleActionConfig(),
        deviceState: DeviceStateCondition = DeviceStateCondition(),
    ) = Rule(
        id = id,
        name = name,
        enabled = enabled,
        order = order,
        matchMode = matchMode,
        fields = fields,
        metaFields = metaFields,
        extrasFields = extrasFields,
        keywords = keywords,
        excludeKeywords = excludeKeywords,
        regex = regex,
        keywordGroups = keywordGroups,
        scope = scope,
        actions = actions,
        deviceState = deviceState,
    )

    // ==================== ① ALL_CONTENT 恒真 ====================

    @Test
    fun `所有内容_恒真_无字段无关键词也命中`() {
        val r = rule(matchMode = MatchMode.ALL_CONTENT, fields = emptySet(), keywords = emptyList())
        val result = RuleEngine.matchRule(r, snap())
        assertTrue(result.hit)
        assertTrue(result.hitFields.isEmpty())
        assertEquals("模式为所有内容，条件恒成立", result.reason)
        assertTrue(result.plannedActions.isEmpty())
    }

    @Test
    fun `所有内容_携带动作时plannedActions正确`() {
        val r = rule(
            matchMode = MatchMode.ALL_CONTENT,
            actions = RuleActionConfig(block = true, readAloud = true),
        )
        val result = RuleEngine.matchRule(r, snap())
        assertTrue(result.hit)
        assertEquals(listOf(ActionType.READ_ALOUD, ActionType.BLOCK), result.plannedActions)
    }

    @Test
    fun `所有内容_作用范围不匹配则不命中`() {
        val r = rule(
            matchMode = MatchMode.ALL_CONTENT,
            scope = RuleScope(ScopeType.ONLY, setOf("com.taobao")),
        )
        val result = RuleEngine.matchRule(r, snap())
        assertFalse(result.hit)
        assertTrue(result.reason.contains("作用范围"))
    }

    // ==================== ② 五个文本子模式 ====================

    @Test
    fun `包含任一_任一关键词任一字段命中`() {
        val r = rule(
            matchMode = MatchMode.TEXT_ANY,
            keywords = listOf("退款", "到账"),
            fields = setOf(MatchField.TITLE, MatchField.TEXT),
        )
        val result = RuleEngine.matchRule(r, snap())
        assertTrue(result.hit)
        // 「到账」命中正文；「退款」未出现
        assertEquals(setOf(FieldRef.Text(MatchField.TEXT)), result.hitFields)
        assertTrue(result.reason.contains("正文"))
    }

    @Test
    fun `包含任一_全部关键词均未出现_不命中`() {
        val r = rule(
            matchMode = MatchMode.TEXT_ANY,
            keywords = listOf("退款", "红包"),
            fields = setOf(MatchField.TITLE, MatchField.TEXT),
        )
        val result = RuleEngine.matchRule(r, snap())
        assertFalse(result.hit)
        assertTrue(result.reason.contains("包含任一未满足"))
    }

    @Test
    fun `包含全部_跨字段分布_顺丰在标题取件码在正文_命中`() {
        // 用户已拍板案例：每个关键词各自在任一被选字段出现即可，不要求同一字段装下全部
        val r = rule(
            matchMode = MatchMode.TEXT_ALL,
            keywords = listOf("顺丰", "取件码"),
            fields = setOf(MatchField.TITLE, MatchField.TEXT),
        )
        val result = RuleEngine.matchRule(
            r,
            snap(title = "顺丰快递提醒", text = "请凭取件码 8888 取件"),
        )
        assertTrue(result.hit)
        assertEquals(
            setOf(FieldRef.Text(MatchField.TITLE), FieldRef.Text(MatchField.TEXT)),
            result.hitFields,
        )
    }

    @Test
    fun `包含全部_同一字段装下全部也命中`() {
        val r = rule(
            matchMode = MatchMode.TEXT_ALL,
            keywords = listOf("顺丰", "取件码"),
            fields = setOf(MatchField.TITLE, MatchField.TEXT),
        )
        val result = RuleEngine.matchRule(r, snap(title = "顺丰取件码通知", text = "无关正文"))
        assertTrue(result.hit)
    }

    @Test
    fun `包含全部_只含一个词_不命中且reason指出缺失词`() {
        val r = rule(
            matchMode = MatchMode.TEXT_ALL,
            keywords = listOf("顺丰", "取件码"),
            fields = setOf(MatchField.TITLE, MatchField.TEXT),
        )
        val result = RuleEngine.matchRule(r, snap(title = "顺丰快递提醒", text = "请查收"))
        assertFalse(result.hit)
        assertTrue(result.reason.contains("包含全部未满足"))
        assertTrue(result.reason.contains("取件码")) // 定位到具体缺失词
        assertFalse(result.reason.contains("顺丰」")) // 顺丰已出现，不应列为缺失
    }

    @Test
    fun `不包含任一与不包含全部_K等于AB_三种情况对照表`() {
        val fields = setOf(MatchField.TITLE, MatchField.TEXT)
        fun notAny() = rule(matchMode = MatchMode.TEXT_NOT_ANY, keywords = listOf("广告", "推广"), fields = fields)
        fun notAll() = rule(matchMode = MatchMode.TEXT_NOT_ALL, keywords = listOf("广告", "推广"), fields = fields)

        // 情况 1：A、B 都没出现 → 两者都命中
        val none = snap(title = "微信支付", text = "到账 100 元")
        assertTrue(RuleEngine.matchRule(notAny(), none).hit)
        assertTrue(RuleEngine.matchRule(notAll(), none).hit)
        assertTrue(RuleEngine.matchRule(notAny(), none).hitFields.isEmpty())

        // 情况 2：A 出现、B 没出现 → 不包含任一【不命中】、不包含全部【命中】
        val onlyA = snap(title = "广告推送", text = "到账 100 元")
        assertFalse(RuleEngine.matchRule(notAny(), onlyA).hit)
        assertTrue(RuleEngine.matchRule(notAll(), onlyA).hit)
        val notAnyMiss = RuleEngine.matchRule(notAny(), onlyA)
        assertTrue(notAnyMiss.reason.contains("不包含任一未满足"))
        assertTrue(notAnyMiss.reason.contains("广告"))

        // 情况 3：A、B 都出现 → 两者都不命中
        val both = snap(title = "广告推广", text = "优惠活动")
        assertFalse(RuleEngine.matchRule(notAny(), both).hit)
        assertFalse(RuleEngine.matchRule(notAll(), both).hit)
        assertTrue(RuleEngine.matchRule(notAll(), both).reason.contains("所有关键词都出现了"))
    }

    @Test
    fun `包含A且不包含B_A命中且B均不出现_命中`() {
        val r = rule(
            matchMode = MatchMode.TEXT_A_NOT_B,
            keywords = listOf("微信"),
            excludeKeywords = listOf("红包"),
            fields = setOf(MatchField.TITLE, MatchField.TEXT),
        )
        val result = RuleEngine.matchRule(r, snap(title = "微信支付", text = "到账 100 元"))
        assertTrue(result.hit)
        assertEquals(setOf(FieldRef.Text(MatchField.TITLE)), result.hitFields)
    }

    @Test
    fun `包含A且不包含B_B出现一个_不命中`() {
        val r = rule(
            matchMode = MatchMode.TEXT_A_NOT_B,
            keywords = listOf("微信"),
            excludeKeywords = listOf("红包", "转账"),
            fields = setOf(MatchField.TITLE, MatchField.TEXT),
        )
        val result = RuleEngine.matchRule(r, snap(title = "微信红包", text = "转账 100 元"))
        assertFalse(result.hit)
        assertTrue(result.reason.contains("B 组未通过"))
        assertTrue(result.reason.contains("红包"))
    }

    @Test
    fun `包含A且不包含B_A未命中_不命中`() {
        val r = rule(
            matchMode = MatchMode.TEXT_A_NOT_B,
            keywords = listOf("退款"),
            excludeKeywords = listOf("红包"),
            fields = setOf(MatchField.TITLE, MatchField.TEXT),
        )
        val result = RuleEngine.matchRule(r, snap())
        assertFalse(result.hit)
        assertTrue(result.reason.contains("A 组未命中"))
    }

    // ==================== ③ 正则 ====================

    @Test
    fun `正则_默认不区分大小写`() {
        val r = rule(
            matchMode = MatchMode.REGEX,
            keywords = emptyList(),
            fields = setOf(MatchField.TITLE),
            regex = RegexConfig(pattern = "ADcome", ignoreCase = true),
        )
        assertTrue(RuleEngine.matchRule(r, snap(title = "adcome 到了")).hit)
    }

    @Test
    fun `正则_ignoreCaseFalse时区分大小写`() {
        val cfg = RegexConfig(pattern = "ADCOME", ignoreCase = false)
        val r = rule(matchMode = MatchMode.REGEX, fields = setOf(MatchField.TITLE), regex = cfg)
        assertFalse(RuleEngine.matchRule(r, snap(title = "adcome 到了")).hit)
        assertTrue(RuleEngine.matchRule(r, snap(title = "ADCOME 到了")).hit)
    }

    @Test
    fun `正则_逐字段匹配_仅命中字段计入hitFields`() {
        val r = rule(
            matchMode = MatchMode.REGEX,
            fields = setOf(MatchField.TITLE, MatchField.TEXT),
            regex = RegexConfig(pattern = "到账"),
        )
        // 标题不含、正文含 → 命中且 hitFields 只含正文
        val result = RuleEngine.matchRule(r, snap(title = "微信支付", text = "你已到账 100 元"))
        assertTrue(result.hit)
        assertEquals(setOf(FieldRef.Text(MatchField.TEXT)), result.hitFields)
    }

    @Test
    fun `正则_多字段同时命中全部汇总`() {
        val r = rule(
            matchMode = MatchMode.REGEX,
            fields = setOf(MatchField.TITLE, MatchField.TEXT),
            regex = RegexConfig(pattern = "微信"),
        )
        val result = RuleEngine.matchRule(r, snap(title = "微信支付", text = "微信支付通知"))
        assertTrue(result.hit)
        assertEquals(
            setOf(FieldRef.Text(MatchField.TITLE), FieldRef.Text(MatchField.TEXT)),
            result.hitFields,
        )
    }

    @Test
    fun `正则_非法pattern_引擎兜底不命中`() {
        val r = rule(
            matchMode = MatchMode.REGEX,
            fields = setOf(MatchField.TITLE),
            regex = RegexConfig(pattern = "(unclosed"),
        )
        val result = RuleEngine.matchRule(r, snap())
        assertFalse(result.hit)
        assertTrue(result.reason.contains("正则表达式有误"))
    }

    // ==================== ④ 字段扩展：元信息 + extras ====================

    @Test
    fun `元信息_KEY整串匹配`() {
        val r = rule(
            matchMode = MatchMode.TEXT_ANY,
            keywords = listOf("0|com.tencent.mobileqq|519|null|10321"),
            metaFields = setOf(MetaFieldKind.KEY),
            fields = emptySet(),
        )
        val s = snap(
            pkg = "com.tencent.mobileqq",
            notifId = 519,
            title = "QQ 消息",
            uid = 10321,
        )
        assertTrue(RuleEngine.matchRule(r, s).hit)
        // 不同 key 的快照不命中
        assertFalse(RuleEngine.matchRule(r, snap()).hit)
    }

    @Test
    fun `元信息_NOTIF_ID匹配`() {
        val r = rule(
            matchMode = MatchMode.TEXT_ANY,
            keywords = listOf("519"),
            metaFields = setOf(MetaFieldKind.NOTIF_ID),
            fields = emptySet(),
        )
        assertTrue(RuleEngine.matchRule(r, snap(notifId = 519)).hit)
        assertFalse(RuleEngine.matchRule(r, snap(notifId = 520)).hit)
    }

    @Test
    fun `元信息_TAG有值与无值null`() {
        val r = rule(
            matchMode = MatchMode.TEXT_ANY,
            keywords = listOf("promo"),
            metaFields = setOf(MetaFieldKind.TAG),
            fields = emptySet(),
        )
        assertTrue(RuleEngine.matchRule(r, snap(tag = "promo_summer")).hit)
        val nullRule = r.copy(keywords = listOf("null"))
        assertTrue(RuleEngine.matchRule(nullRule, snap(tag = null)).hit)
        assertFalse(RuleEngine.matchRule(nullRule, snap(tag = "promo")).hit)
    }

    @Test
    fun `元信息_CHANNEL_ID与USER_ID匹配`() {
        val channelRule = rule(
            matchMode = MatchMode.TEXT_ANY,
            keywords = listOf("social"),
            metaFields = setOf(MetaFieldKind.CHANNEL_ID),
            fields = emptySet(),
        )
        assertTrue(RuleEngine.matchRule(channelRule, snap(channelId = "com.tencent.mm.social")).hit)
        assertFalse(RuleEngine.matchRule(channelRule, snap(channelId = "other")).hit)

        val userRule = rule(
            matchMode = MatchMode.TEXT_ANY,
            keywords = listOf("10"),
            metaFields = setOf(MetaFieldKind.USER_ID),
            fields = emptySet(),
        )
        assertTrue(RuleEngine.matchRule(userRule, snap(userId = 10)).hit)
        assertFalse(RuleEngine.matchRule(userRule, snap(userId = 0)).hit)
    }

    @Test
    fun `元信息_IS_ONGOING按true_false匹配`() {
        fun ongoingRule(kw: String) = rule(
            matchMode = MatchMode.TEXT_ANY,
            keywords = listOf(kw),
            metaFields = setOf(MetaFieldKind.IS_ONGOING),
            fields = emptySet(),
        )
        assertTrue(RuleEngine.matchRule(ongoingRule("true"), snap(isOngoing = true)).hit)
        assertFalse(RuleEngine.matchRule(ongoingRule("true"), snap(isOngoing = false)).hit)
        assertTrue(RuleEngine.matchRule(ongoingRule("false"), snap(isOngoing = false)).hit)
    }

    @Test
    fun `extras字段_深层键名命中 channel_read_aloud`() {
        val r = rule(
            matchMode = MatchMode.TEXT_ANY,
            keywords = listOf("channel_read_aloud"),
            extrasFields = setOf("filterbox.field.CHANNEL_ID"),
            fields = emptySet(),
        )
        val s = snap(extras = listOf("filterbox.field.CHANNEL_ID" to "channel_read_aloud"))
        val result = RuleEngine.matchRule(r, s)
        assertTrue(result.hit)
        assertEquals(setOf(FieldRef.Extras("filterbox.field.CHANNEL_ID")), result.hitFields)
    }

    @Test
    fun `extras字段_键名大小写敏感_无此键按空串不命中`() {
        val r = rule(
            matchMode = MatchMode.TEXT_ANY,
            keywords = listOf("channel_read_aloud"),
            extrasFields = setOf("filterbox.field.channel_id"), // 小写，与快照键不一致
            fields = emptySet(),
        )
        val s = snap(extras = listOf("filterbox.field.CHANNEL_ID" to "channel_read_aloud"))
        assertFalse(RuleEngine.matchRule(r, s).hit)
        // 键不存在 → 空串 → 不命中
        val r2 = r.copy(extrasFields = setOf("not.exist.key"))
        assertFalse(RuleEngine.matchRule(r2, s).hit)
    }

    @Test
    fun `混合字段_文本加元信息加extras同时参与匹配`() {
        val r = rule(
            matchMode = MatchMode.TEXT_ANY,
            keywords = listOf("取件码"),
            fields = setOf(MatchField.TITLE),
            metaFields = setOf(MetaFieldKind.KEY),
            extrasFields = setOf("miui.push.extra"),
        )
        val s = snap(
            title = "顺丰快递",
            extras = listOf("miui.push.extra" to "取件码 8888"),
        )
        val result = RuleEngine.matchRule(r, s)
        assertTrue(result.hit)
        assertEquals(setOf(FieldRef.Extras("miui.push.extra")), result.hitFields)
    }

    // ==================== ⑤ 防御性判定 ====================

    @Test
    fun `防御_文本模式空关键词不命中`() {
        val r = rule(
            matchMode = MatchMode.TEXT_ANY,
            keywords = emptyList(),
            fields = setOf(MatchField.TITLE),
        )
        val result = RuleEngine.matchRule(r, snap())
        assertFalse(result.hit)
        assertEquals("关键词条件为空", result.reason)

        // 全空白关键词同样视为空
        val blank = rule(
            matchMode = MatchMode.TEXT_ALL,
            keywords = listOf("  ", "\t"),
            fields = setOf(MatchField.TITLE),
        )
        assertFalse(RuleEngine.matchRule(blank, snap()).hit)
    }

    @Test
    fun `防御_A_NOT_B的A空或B空不命中`() {
        val fields = setOf(MatchField.TITLE, MatchField.TEXT)
        val aEmpty = rule(
            matchMode = MatchMode.TEXT_A_NOT_B,
            keywords = emptyList(),
            excludeKeywords = listOf("红包"),
            fields = fields,
        )
        val resultA = RuleEngine.matchRule(aEmpty, snap())
        assertFalse(resultA.hit)
        assertTrue(resultA.reason.contains("A 组关键词为空"))

        val bEmpty = rule(
            matchMode = MatchMode.TEXT_A_NOT_B,
            keywords = listOf("微信"),
            excludeKeywords = emptyList(),
            fields = fields,
        )
        val resultB = RuleEngine.matchRule(bEmpty, snap())
        assertFalse(resultB.hit)
        assertTrue(resultB.reason.contains("B 组关键词为空"))
    }

    @Test
    fun `防御_正则空pattern不命中`() {
        val r = rule(
            matchMode = MatchMode.REGEX,
            fields = setOf(MatchField.TITLE),
            regex = RegexConfig(pattern = "   "),
        )
        val result = RuleEngine.matchRule(r, snap())
        assertFalse(result.hit)
        assertTrue(result.reason.contains("正则表达式为空"))
    }

    @Test
    fun `防御_未配置正则不命中`() {
        val r = rule(matchMode = MatchMode.REGEX, fields = setOf(MatchField.TITLE))
        assertFalse(RuleEngine.matchRule(r, snap()).hit)
    }

    // ==================== ⑥ 回归：大小写 / 范围 / 顺序 / 动作 ====================

    @Test
    fun `大小写不敏感`() {
        val r = rule(
            matchMode = MatchMode.TEXT_ANY,
            keywords = listOf("AD"),
            fields = setOf(MatchField.TITLE),
        )
        assertTrue(RuleEngine.matchRule(r, snap(title = "ad 来了")).hit)
        val r2 = r.copy(keywords = listOf("ad"))
        assertTrue(RuleEngine.matchRule(r2, snap(title = "AD 来了")).hit)
    }

    @Test
    fun `关键词前后空格被trim`() {
        val r = rule(
            matchMode = MatchMode.TEXT_ANY,
            keywords = listOf("  到账  "),
            fields = setOf(MatchField.TEXT),
        )
        assertTrue(RuleEngine.matchRule(r, snap()).hit)
    }

    @Test
    fun `多字段_任一字段命中即满足`() {
        val r = rule(
            matchMode = MatchMode.TEXT_ANY,
            keywords = listOf("吃饭"),
            fields = setOf(MatchField.TITLE, MatchField.TEXT, MatchField.TEXTLINES),
        )
        val result = RuleEngine.matchRule(r, snap())
        assertTrue(result.hit)
        assertEquals(setOf(FieldRef.Text(MatchField.TEXTLINES)), result.hitFields)
    }

    @Test
    fun `范围_GLOBAL_ONLY_EXCLUDE三态`() {
        val build = { scope: RuleScope ->
            rule(
                matchMode = MatchMode.TEXT_ANY,
                keywords = listOf("到账"),
                fields = setOf(MatchField.TEXT),
                scope = scope,
            )
        }
        val rGlobal = build(RuleScope(ScopeType.GLOBAL))
        assertTrue(RuleEngine.matchRule(rGlobal, snap(pkg = "com.taobao")).hit)

        val rOnly = build(RuleScope(ScopeType.ONLY, setOf("com.taobao", "com.jingdong")))
        assertTrue(RuleEngine.matchRule(rOnly, snap(pkg = "com.taobao")).hit)
        assertFalse(RuleEngine.matchRule(rOnly, snap(pkg = "com.tencent.mm")).hit)

        val rExclude = build(RuleScope(ScopeType.EXCLUDE, setOf("com.tencent.mm")))
        assertFalse(RuleEngine.matchRule(rExclude, snap(pkg = "com.tencent.mm")).hit)
        assertTrue(RuleEngine.matchRule(rExclude, snap(pkg = "com.taobao")).hit)
    }

    @Test
    fun `范围_ONLY_空列表_任何App都不命中`() {
        val r = rule(
            matchMode = MatchMode.TEXT_ANY,
            keywords = listOf("到账"),
            fields = setOf(MatchField.TEXT),
            scope = RuleScope(ScopeType.ONLY, emptySet()),
        )
        assertFalse(RuleEngine.matchRule(r, snap()).hit)
    }

    @Test
    fun `disabled规则被跳过_首个命中与入参顺序无关`() {
        val second = rule(
            id = 2L, name = "第二条", order = 1,
            matchMode = MatchMode.TEXT_ANY, keywords = listOf("到账"),
            fields = setOf(MatchField.TEXT),
        )
        val first = rule(
            id = 1L, name = "第一条", order = 0,
            matchMode = MatchMode.TEXT_ANY, keywords = listOf("微信"),
            fields = setOf(MatchField.TITLE),
        )
        val disabled = first.copy(enabled = false)
        // 乱序传入：order 小的先命中
        var result = RuleEngine.matchFirst(listOf(second, first), snap(title = "微信支付", text = "到账"))
        assertNotNull(result)
        assertEquals("第一条", result!!.ruleName)
        // 首条被禁用 → 落到第二条
        result = RuleEngine.matchFirst(listOf(second, disabled), snap())
        assertEquals("第二条", result!!.ruleName)
        // 全部禁用 / 未命中 → null
        assertNull(RuleEngine.matchFirst(listOf(disabled), snap()))
        assertNull(RuleEngine.matchFirst(listOf(rule(matchMode = MatchMode.TEXT_ANY, keywords = listOf("退款"))), snap()))
    }

    @Test
    fun `动作计划_朗读先行_替换与推迟可组合`() {
        val r = rule(
            matchMode = MatchMode.ALL_CONTENT,
            actions = RuleActionConfig(
                block = true,
                snooze = SnoozeConfig(preset = "10m"),
                readAloud = true,
            ),
        )
        assertEquals(
            listOf(ActionType.READ_ALOUD, ActionType.BLOCK, ActionType.SNOOZE),
            RuleEngine.matchRule(r, snap()).plannedActions,
        )
        val r2 = rule(
            matchMode = MatchMode.ALL_CONTENT,
            actions = RuleActionConfig(snooze = SnoozeConfig(preset = "30m")),
        )
        assertEquals(listOf(ActionType.SNOOZE), RuleEngine.matchRule(r2, snap()).plannedActions)
    }

    // ==================== ⑦ 试跑器 / 手填素材 ====================

    @Test
    fun `试跑器_命中计数与乱序取首个`() {
        val rules = listOf(
            rule(id = 1L, order = 0, matchMode = MatchMode.TEXT_ANY, keywords = listOf("到账"), fields = setOf(MatchField.TEXT)),
            rule(id = 2L, order = 1, matchMode = MatchMode.TEXT_ANY, keywords = listOf("退款"), fields = setOf(MatchField.TITLE)),
        )
        val snapshots = listOf(
            snap(title = "微信支付", text = "到账 100 元"),
            snap(title = "退款成功", text = "退款 100 元"),
            snap(title = "普通消息", text = "你好"),
        )
        val outcomes = RuleTester.run(rules, snapshots)
        assertEquals(3, outcomes.size)
        assertTrue(outcomes[0].hit)
        assertTrue(outcomes[1].hit)
        assertFalse(outcomes[2].hit)
        assertEquals(2 to 3, RuleTester.summarize(outcomes))
        assertEquals(1L, outcomes[0].result?.ruleId)
    }

    @Test
    fun `手填素材_六文本字段均可匹配`() {
        val snapshot = ManualSnapshotFactory.create(
            packageName = "com.demo",
            appName = "演示",
            fields = mapOf(
                MatchField.TITLE to "订单已发货",
                MatchField.TEXT to "顺丰快递 已揽收",
                MatchField.TEXTLINES to "第一行\n第二行 取件码 8888",
                MatchField.SUBTEXT to "副标题 物流",
                MatchField.SUMMARYTEXT to "摘要 已签收",
                MatchField.PEOPLE to "张三\n李四",
            ),
        )
        assertEquals("com.demo", snapshot.packageName)
        val rules = listOf(
            rule(matchMode = MatchMode.TEXT_ANY, keywords = listOf("取件码"), fields = setOf(MatchField.TEXTLINES)),
            rule(matchMode = MatchMode.TEXT_ANY, keywords = listOf("签收"), fields = setOf(MatchField.SUMMARYTEXT)),
            rule(matchMode = MatchMode.TEXT_ANY, keywords = listOf("李四"), fields = setOf(MatchField.PEOPLE)),
            rule(matchMode = MatchMode.TEXT_ANY, keywords = listOf("物流"), fields = setOf(MatchField.SUBTEXT)),
            rule(matchMode = MatchMode.TEXT_ANY, keywords = listOf("揽收"), fields = setOf(MatchField.TEXT)),
            rule(matchMode = MatchMode.TEXT_ANY, keywords = listOf("发货"), fields = setOf(MatchField.TITLE)),
        )
        rules.forEach { r ->
            assertTrue("字段 ${r.fields} 应命中", RuleEngine.matchRule(r, snapshot).hit)
        }
        // extras dump 含非标题/正文字段
        val keys = snapshot.extras.map { it.first }.toSet()
        assertTrue(keys.contains("android.textLines"))
        assertTrue(keys.contains("android.people"))
    }

    @Test
    fun `手填素材_签名扩展_meta与extras可匹配`() {
        val snapshot = ManualSnapshotFactory.create(
            packageName = "com.demo",
            appName = "演示",
            fields = mapOf(MatchField.TITLE to "快递提醒"),
            extras = mapOf("filterbox.field.CHANNEL_ID" to "channel_read_aloud"),
            meta = mapOf(
                MetaFieldKind.TAG to "promo_summer",
                MetaFieldKind.CHANNEL_ID to "social",
                MetaFieldKind.IS_ONGOING to "true",
                MetaFieldKind.NOTIF_ID to "77",
            ),
        )
        val extrasRule = rule(
            matchMode = MatchMode.TEXT_ANY,
            keywords = listOf("channel_read_aloud"),
            extrasFields = setOf("filterbox.field.CHANNEL_ID"),
            fields = emptySet(),
        )
        assertTrue(RuleEngine.matchRule(extrasRule, snapshot).hit)
        val tagRule = rule(
            matchMode = MatchMode.TEXT_ANY,
            keywords = listOf("promo_summer"),
            metaFields = setOf(MetaFieldKind.TAG),
            fields = emptySet(),
        )
        assertTrue(RuleEngine.matchRule(tagRule, snapshot).hit)
        val ongoingRule = rule(
            matchMode = MatchMode.TEXT_ANY,
            keywords = listOf("true"),
            metaFields = setOf(MetaFieldKind.IS_ONGOING),
            fields = emptySet(),
        )
        assertTrue(RuleEngine.matchRule(ongoingRule, snapshot).hit)
        assertEquals(77, snapshot.notifId)
        assertEquals("social", snapshot.channelId)
        // 手填自动 key（manual:<uuid>）——KEY 类条件在手填素材上不生效（T06 提示）
        val keyRule = rule(
            matchMode = MatchMode.TEXT_ANY,
            keywords = listOf("manual:"),
            metaFields = setOf(MetaFieldKind.KEY),
            fields = emptySet(),
        )
        assertTrue(RuleEngine.matchRule(keyRule, snapshot).hit)
    }

    // ==================== ⑧ FieldRef.fromId 与旧数据兼容 ====================

    @Test
    fun `FieldRef_fromId_旧枚举名与坏数据都不抛异常`() {
        assertEquals(FieldRef.Text(MatchField.TITLE), FieldRef.fromId("TITLE"))
        assertEquals(FieldRef.Text(MatchField.TEXTLINES), FieldRef.fromId("TEXTLINES"))
        assertEquals(FieldRef.Meta(MetaFieldKind.KEY), FieldRef.fromId("meta:KEY"))
        assertEquals(FieldRef.Meta(MetaFieldKind.IS_ONGOING), FieldRef.fromId("meta:IS_ONGOING"))
        assertEquals(
            FieldRef.Extras("android.sortKey"),
            FieldRef.fromId("extras:android.sortKey"),
        )
        assertEquals(
            FieldRef.Extras("filterbox.field.CHANNEL_ID"),
            FieldRef.fromId("extras:filterbox.field.CHANNEL_ID"),
        )
        // 坏数据兜底为 Extras（原样展示，绝不抛异常）
        val garbage = FieldRef.fromId("GARBAGE")
        assertTrue(garbage is FieldRef.Extras)
        assertEquals("extras:GARBAGE", garbage.id)
        // 旧流水历史数据 ["TITLE","TEXT"] 原样可读
        assertEquals(
            setOf(FieldRef.Text(MatchField.TITLE), FieldRef.Text(MatchField.TEXT)),
            listOf("TITLE", "TEXT").map { FieldRef.fromId(it) }.toSet(),
        )
        // meta: 前缀 + 坏枚举名也兜底
        assertTrue(FieldRef.fromId("meta:NOT_A_KIND") is FieldRef.Extras)
    }

    @Test
    fun `FieldRef_id与label规范`() {
        assertEquals("TITLE", FieldRef.Text(MatchField.TITLE).id)
        assertEquals("标题", FieldRef.Text(MatchField.TITLE).label)
        assertEquals("meta:NOTIF_ID", FieldRef.Meta(MetaFieldKind.NOTIF_ID).id)
        assertEquals("通知 ID", FieldRef.Meta(MetaFieldKind.NOTIF_ID).label)
        assertEquals("extras:miui.keyguard.confirm", FieldRef.Extras("miui.keyguard.confirm").id)
        assertEquals("miui.keyguard.confirm", FieldRef.Extras("miui.keyguard.confirm").label)
    }

    @Test
    fun `hitFieldsLabel_汇总展示`() {
        val r = rule(
            matchMode = MatchMode.TEXT_ANY,
            keywords = listOf("微信"),
            fields = setOf(MatchField.TITLE, MatchField.TEXT),
        )
        val result = RuleEngine.matchRule(r, snap(title = "微信支付", text = "微信支付通知"))
        assertEquals("标题、正文", result.hitFieldsLabel())
    }

    // ==================== ⑨ Rule.summary() 新文案 ====================

    @Test
    fun `规则摘要_各模式文案`() {
        val f = setOf(MatchField.TITLE, MatchField.TEXT)
        assertEquals("所有内容", rule(matchMode = MatchMode.ALL_CONTENT).summary())
        assertEquals(
            "包含任一「顺丰」或「取件码」（标题、正文）",
            rule(matchMode = MatchMode.TEXT_ANY, keywords = listOf("顺丰", "取件码"), fields = f).summary(),
        )
        assertEquals(
            "包含全部「顺丰」「取件码」（标题、正文）",
            rule(matchMode = MatchMode.TEXT_ALL, keywords = listOf("顺丰", "取件码"), fields = f).summary(),
        )
        assertEquals(
            "不包含任一「广告」（标题）",
            rule(matchMode = MatchMode.TEXT_NOT_ANY, keywords = listOf("广告"), fields = setOf(MatchField.TITLE)).summary(),
        )
        assertEquals(
            "包含「微信」且不包含「红包」",
            rule(
                matchMode = MatchMode.TEXT_A_NOT_B,
                keywords = listOf("微信"),
                excludeKeywords = listOf("红包"),
                fields = f,
            ).summary(),
        )
        assertEquals(
            "正则：/(顺丰|取件码)/（不区分大小写）",
            rule(matchMode = MatchMode.REGEX, fields = f, regex = RegexConfig("(顺丰|取件码)")).summary(),
        )
        // V12.2：分组匹配（组1 任一 + 组2 全部）
        assertEquals(
            "分组匹配第 1 组「true」＋第 2 组「salt」且「nest」（标题、正文）",
            rule(
                matchMode = MatchMode.TEXT_GROUPS,
                fields = f,
                keywordGroups = KeywordGroupsConfig(
                    groups = listOf(
                        KeywordGroup(words = listOf("true"), mode = GroupMatchMode.ANY),
                        KeywordGroup(
                            words = listOf("salt", "nest"),
                            mode = GroupMatchMode.ALL,
                        ),
                    ),
                ),
            ).summary(),
        )
    }

    @Test
    fun `规则摘要_字段超过三个显示等N个字段_关键词超20字省略`() {
        val r = rule(
            matchMode = MatchMode.TEXT_ANY,
            keywords = listOf("一二三四五六七八九十一二三四五六七八九十一二"),
            fields = MatchField.ALL.toSet(),
        )
        val summary = r.summary()
        assertTrue(summary.contains("等 6 个字段"))
        assertTrue(summary.contains("…"))
        // 关键词截断到 20 字 + 省略号（加模式词与字段摘要整体仍应远短于全量文本）
        assertTrue(summary.length < 60)
    }

    @Test
    fun `ReplaceConfig_双模板字段可序列化`() {
        val cfg = ReplaceConfig(
            mode = RewriteMode.CUSTOM_TEXT,
            customTitleTemplate = "[{app}] {title}",
            customTextTemplate = "来自 {app}：{text}",
        )
        // V1 JSON 无键时也能解出（ignoreUnknownKeys + 默认值）
        val decoded = com.local.notificationfilter.core.model.NfJson
            .decodeFromString<ReplaceConfig>("""{"mode":"CUSTOM_TEXT","keepIcon":true}""")
        assertEquals(null, decoded.customTitleTemplate)
        assertEquals(null, decoded.customTextTemplate)
        assertEquals(cfg.customTitleTemplate, "[{app}] {title}")
    }

    // ==================== ⑩ 设备状态生效条件（V3 需求⑦） ====================

    @Test
    fun `设备状态_试跑模式默认参数_条件设置也不阻拦`() {
        // ALL_CONTENT + 屏幕=OFF 条件，但 matchRule 默认 deviceState=TEST_MODE → 跳过 → 命中
        val r = rule(
            matchMode = MatchMode.ALL_CONTENT,
            deviceState = DeviceStateCondition(screenStates = setOf(ScreenState.OFF)),
        )
        val result = RuleEngine.matchRule(r, snap()) // 不传 deviceState，用默认 TEST_MODE
        assertTrue(result.hit)
    }

    @Test
    fun `设备状态_屏幕条件_当前亮屏命中_熄屏不命中`() {
        val r = rule(
            matchMode = MatchMode.ALL_CONTENT,
            deviceState = DeviceStateCondition(screenStates = setOf(ScreenState.ON)),
        )
        // 当前亮屏 → 命中
        assertTrue(RuleEngine.matchRule(r, snap(), DeviceState(screenOn = true)).hit)
        // 当前熄屏 → 不命中，原因含设备状态
        val off = RuleEngine.matchRule(r, snap(), DeviceState(screenOn = false))
        assertFalse(off.hit)
        assertTrue(off.reason.contains("设备状态不满足"))
        assertTrue(off.reason.contains("屏幕状态不匹配"))
    }

    @Test
    fun `设备状态_作用范围不匹配优先于设备状态`() {
        // scope=ONLY 限定其他包 + 屏幕条件；snapshot 包名不在 scope → 作用范围不匹配先返回
        val r = rule(
            matchMode = MatchMode.ALL_CONTENT,
            scope = RuleScope(ScopeType.ONLY, setOf("com.other")),
            deviceState = DeviceStateCondition(screenStates = setOf(ScreenState.OFF)),
        )
        val result = RuleEngine.matchRule(r, snap(), DeviceState(screenOn = true))
        assertFalse(result.hit)
        assertTrue(result.reason.contains("作用范围不匹配"))
        assertTrue(!result.reason.contains("设备状态"))
    }

    @Test
    fun `设备状态_空条件_任何设备状态都命中`() {
        val r = rule(matchMode = MatchMode.ALL_CONTENT) // deviceState 默认空
        assertTrue(RuleEngine.matchRule(r, snap(), DeviceState(screenOn = true)).hit)
        assertTrue(RuleEngine.matchRule(r, snap(), DeviceState(screenOn = false)).hit)
        assertTrue(RuleEngine.matchRule(r, snap(), DeviceState.TEST_MODE).hit)
    }

    @Test
    fun `设备状态_matchFirstRule_按设备状态筛选首个命中`() {
        val r1 = rule(
            id = 1L, order = 0, name = "仅熄屏",
            matchMode = MatchMode.ALL_CONTENT,
            deviceState = DeviceStateCondition(screenStates = setOf(ScreenState.OFF)),
        )
        val r2 = rule(
            id = 2L, order = 1, name = "仅亮屏",
            matchMode = MatchMode.ALL_CONTENT,
            deviceState = DeviceStateCondition(screenStates = setOf(ScreenState.ON)),
        )
        // 当前亮屏 → r1 不命中，r2 命中
        val onResult = RuleEngine.matchFirstRule(listOf(r1, r2), snap(), DeviceState(screenOn = true))
        assertNotNull(onResult)
        assertEquals("仅亮屏", onResult!!.second.ruleName)
        // 当前熄屏 → r1 命中（order 小）
        val offResult = RuleEngine.matchFirstRule(listOf(r1, r2), snap(), DeviceState(screenOn = false))
        assertNotNull(offResult)
        assertEquals("仅熄屏", offResult!!.second.ruleName)
    }

    // ==================== ⑪ V12.2 分组匹配（TEXT_GROUPS） ====================

    /** 构造分组匹配规则（默认标题/正文/消息列表三字段）。 */
    private fun groupsRule(
        groups: List<KeywordGroup>,
        fields: Set<MatchField> = RuleConditionDefaults.FIELDS,
    ) = rule(
        matchMode = MatchMode.TEXT_GROUPS,
        fields = fields,
        keywordGroups = KeywordGroupsConfig(groups),
    )

    @Test
    fun `分组匹配_两组都满足_命中`() {
        // 组1 [true]（任一）；组2 [salt, nest]（任一）——只出现 nest 即满足组2
        val r = groupsRule(
            listOf(
                KeywordGroup(words = listOf("true"), mode = GroupMatchMode.ANY),
                KeywordGroup(words = listOf("salt", "nest"), mode = GroupMatchMode.ANY),
            ),
        )
        val result = RuleEngine.matchRule(r, snap(title = "true", text = "nest 已就位"))
        assertTrue(result.hit)
        assertTrue(result.reason.contains("命中字段"))
    }

    @Test
    fun `分组匹配_组1缺true_不命中_原因含第1组`() {
        val r = groupsRule(
            listOf(
                KeywordGroup(words = listOf("true"), mode = GroupMatchMode.ANY),
                KeywordGroup(words = listOf("salt", "nest"), mode = GroupMatchMode.ANY),
            ),
        )
        val result = RuleEngine.matchRule(r, snap(title = "false", text = "salt 与 nest"))
        assertFalse(result.hit)
        assertTrue(result.reason.contains("第 1 组"))
        assertTrue(result.reason.contains("「true」"))
    }

    @Test
    fun `分组匹配_组2缺salt和nest_不命中_原因含第2组且缺词正确`() {
        val r = groupsRule(
            listOf(
                KeywordGroup(words = listOf("true"), mode = GroupMatchMode.ANY),
                KeywordGroup(words = listOf("salt", "nest"), mode = GroupMatchMode.ANY),
            ),
        )
        val result = RuleEngine.matchRule(r, snap(title = "true", text = "什么都没有"))
        assertFalse(result.hit)
        assertTrue(result.reason.contains("第 2 组"))
        assertTrue(result.reason.contains("任一出现"))
        assertTrue(result.reason.contains("「salt」"))
        assertTrue(result.reason.contains("「nest」"))
    }

    @Test
    fun `分组匹配_组内ALL只出现一个_不命中`() {
        // 组2 配 ALL（全部出现），只出现 salt → 不满足
        val r = groupsRule(
            listOf(
                KeywordGroup(words = listOf("true"), mode = GroupMatchMode.ANY),
                KeywordGroup(words = listOf("salt", "nest"), mode = GroupMatchMode.ALL),
            ),
        )
        val result = RuleEngine.matchRule(r, snap(title = "true", text = "salt 已就位"))
        assertFalse(result.hit)
        assertTrue(result.reason.contains("第 2 组"))
        assertTrue(result.reason.contains("全部出现"))
        assertTrue(result.reason.contains("「nest」"))
        assertFalse(result.reason.contains("「salt」")) // salt 已出现，不算缺
    }

    @Test
    fun `分组匹配_跨字段_true在标题_salt在正文_两字段都计入hitFields`() {
        val r = groupsRule(
            listOf(
                KeywordGroup(words = listOf("true"), mode = GroupMatchMode.ANY),
                KeywordGroup(words = listOf("salt"), mode = GroupMatchMode.ANY),
            ),
            fields = setOf(MatchField.TITLE, MatchField.TEXT),
        )
        val result = RuleEngine.matchRule(r, snap(title = "true", text = "salt 已就位"))
        assertTrue(result.hit)
        assertEquals(
            setOf(FieldRef.Text(MatchField.TITLE), FieldRef.Text(MatchField.TEXT)),
            result.hitFields,
        )
    }

    @Test
    fun `分组匹配_某组词全空_不命中_原因含第N组未填写`() {
        val r = groupsRule(
            listOf(
                KeywordGroup(words = listOf("true"), mode = GroupMatchMode.ANY),
                KeywordGroup(words = listOf("", "   "), mode = GroupMatchMode.ANY),
            ),
        )
        val result = RuleEngine.matchRule(r, snap(title = "true", text = "salt 已就位"))
        assertFalse(result.hit)
        assertTrue(result.reason.contains("第 2 组未填写关键词"))
    }

    @Test
    fun `分组匹配_groups为空_不命中_有原因`() {
        val r = rule(matchMode = MatchMode.TEXT_GROUPS, keywordGroups = KeywordGroupsConfig())
        val result = RuleEngine.matchRule(r, snap(title = "true", text = "salt 已就位"))
        assertFalse(result.hit)
        assertTrue(result.reason.contains("未配置任何分组"))
    }

    @Test
    fun `分组匹配_未配置keywordGroups_不命中_有原因`() {
        val r = rule(matchMode = MatchMode.TEXT_GROUPS, keywordGroups = null)
        val result = RuleEngine.matchRule(r, snap(title = "true", text = "salt 已就位"))
        assertFalse(result.hit)
        assertTrue(result.reason.contains("未配置分组"))
    }

    @Test
    fun `分组匹配_hitFields为各组命中字段并集_同字段只记一次`() {
        // 组1 [true] 命中标题+正文；组2 [salt] 只命中正文 → 并集 {标题, 正文}（正文只一份）
        val r = groupsRule(
            listOf(
                KeywordGroup(words = listOf("true"), mode = GroupMatchMode.ANY),
                KeywordGroup(words = listOf("salt"), mode = GroupMatchMode.ANY),
            ),
            fields = setOf(MatchField.TITLE, MatchField.TEXT),
        )
        val result = RuleEngine.matchRule(
            r,
            snap(title = "true", text = "true 与 salt", textLines = emptyList()),
        )
        assertTrue(result.hit)
        assertEquals(
            setOf(FieldRef.Text(MatchField.TITLE), FieldRef.Text(MatchField.TEXT)),
            result.hitFields,
        )
    }

    @Test
    fun `分组匹配_JSON round-trip_keywordGroups完整保留`() {
        val r = rule(
            matchMode = MatchMode.TEXT_GROUPS,
            fields = setOf(MatchField.TITLE, MatchField.TEXT),
            keywordGroups = KeywordGroupsConfig(
                groups = listOf(
                    KeywordGroup(words = listOf("true"), mode = GroupMatchMode.ANY),
                    KeywordGroup(words = listOf("salt", "nest"), mode = GroupMatchMode.ALL),
                ),
            ),
        )
        val json = NfJson.encodeToString(Rule.serializer(), r)
        val decoded = NfJson.decodeFromString<Rule>(json)
        assertEquals(MatchMode.TEXT_GROUPS, decoded.matchMode)
        assertEquals(r.keywordGroups, decoded.keywordGroups)
        assertEquals(GroupMatchMode.ALL, decoded.keywordGroups!!.groups[1].mode)
    }

    @Test
    fun `分组匹配_旧规则JSON无keywordGroups键_反序列化为null`() {
        // V12.1 及以前导出的规则 JSON 没有该键 → 零迁移兼容
        val oldJson =
            """{"id":7,"name":"旧规则","enabled":true,"order":3,"matchMode":"TEXT_ANY",""" +
                """"fields":["TITLE","TEXT","TEXTLINES"],"keywords":["顺丰"]}"""
        val decoded = NfJson.decodeFromString<Rule>(oldJson)
        assertEquals(MatchMode.TEXT_ANY, decoded.matchMode)
        assertNull(decoded.keywordGroups)
        // 旧规则行为不变：照常按 TEXT_ANY 匹配
        assertTrue(RuleEngine.matchRule(decoded, snap(title = "顺丰速运")).hit)
    }
}
