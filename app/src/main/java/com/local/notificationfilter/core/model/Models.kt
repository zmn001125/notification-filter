package com.local.notificationfilter.core.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 全部领域数据模型（core 层，无 Android 依赖）。
 *
 * 规则模型可序列化为 JSON 存入 Room 单列（架构共享约定 11，V2 schemaVersion = 2；
 * V1 旧 JSON 的迁移见 RuleJsonMigrator，架构 §2.6）；通知快照 / 流水 / 统计等为
 * 内存与 Room 实体间的强类型表示。
 */

// ==================== JSON 配置（唯一实例） ====================

val NfJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

// ==================== 规则 ====================

/**
 * 一条过滤规则（V2 需求④）：**一个匹配模式（单选互斥）** + 模式参数（A/B 组关键词 /
 * 正则 / V12.2 分组匹配）+ 被选字段（文本 / 元信息 / extras，规则级共享）+ 作用范围 + 动作组合。
 */
@Serializable
data class Rule(
    val id: Long = 0L,
    val name: String = "",
    val enabled: Boolean = true,
    val order: Int = 0,
    /** 匹配模式（V2：规则级单选，替代 V1 条件级 INCLUDE/EXCLUDE）。 */
    val matchMode: MatchMode = MatchMode.ALL_CONTENT,
    /** 被选字段·文本类（V1 六字段，规则级共享）。 */
    val fields: Set<MatchField> = RuleConditionDefaults.FIELDS,
    /** 被选字段·通知元信息类。 */
    val metaFields: Set<MetaFieldKind> = emptySet(),
    /** 被选字段·extras 任意键（大小写敏感）。 */
    val extrasFields: Set<String> = emptySet(),
    /** A 组关键词（所有文本模式共用；TEXT_A_NOT_B 时为 A 组）。 */
    val keywords: List<String> = emptyList(),
    /** B 组关键词（仅 TEXT_A_NOT_B 使用）。 */
    val excludeKeywords: List<String> = emptyList(),
    /** 正则配置（仅 REGEX 模式使用）。 */
    val regex: RegexConfig? = null,
    val scope: RuleScope = RuleScope(),
    val actions: RuleActionConfig = RuleActionConfig(),
    /** V3 需求⑦：设备状态生效条件（全空 = 无限制，引擎恒通过）。 */
    val deviceState: DeviceStateCondition = DeviceStateCondition(),
    /** 分组匹配配置（V12.2，仅 matchMode = TEXT_GROUPS 时使用；null = 未配置）。 */
    val keywordGroups: KeywordGroupsConfig? = null,
) {
    val schemaVersion: Int get() = SCHEMA_VERSION

    /** 全部被选字段（引擎统一入口；顺序固定：文本 → 元信息 → extras）。 */
    fun fieldRefs(): List<FieldRef> =
        fields.map { FieldRef.Text(it) } + metaFields.map { FieldRef.Meta(it) } +
            extrasFields.map { FieldRef.Extras(it) }

    /** 列表页一句话摘要（新模式文案，架构 §3.6）。 */
    fun summary(): String = when (matchMode) {
        MatchMode.ALL_CONTENT -> "所有内容"

        MatchMode.TEXT_ANY -> "${modeWord("包含任一", "或")}${fieldsPart()}"
        MatchMode.TEXT_ALL -> "${modeWord("包含全部")}${fieldsPart()}"
        MatchMode.TEXT_NOT_ANY -> "${modeWord("不包含任一")}${fieldsPart()}"
        MatchMode.TEXT_NOT_ALL -> "${modeWord("不包含全部")}${fieldsPart()}"

        MatchMode.TEXT_A_NOT_B -> buildString {
            append("包含")
            append(keywordsPart(keywords))
            append("且不包含")
            append(keywordsPart(excludeKeywords))
        }

        MatchMode.REGEX -> buildString {
            append("正则：/")
            append(regex?.pattern.orEmpty())
            append("/")
            append(if (regex?.ignoreCase != false) "（不区分大小写）" else "（区分大小写）")
        }

        MatchMode.TEXT_GROUPS -> buildString {
            append("分组匹配")
            val groups = keywordGroups?.groups.orEmpty()
            if (groups.isEmpty()) {
                append("（未设置）")
            } else {
                groups.forEachIndexed { i, g ->
                    if (i > 0) append("＋")
                    append("第 ${i + 1} 组")
                    val words = g.words.map { it.trim() }.filter { it.isNotEmpty() }
                    if (words.isEmpty()) {
                        append("（空）")
                    } else {
                        append(
                            if (g.mode == GroupMatchMode.ANY) {
                                words.joinToString("或") { "「$it」" }
                            } else {
                                words.joinToString("且") { "「$it」" }
                            },
                        )
                    }
                }
            }
            append(fieldsPart())
        }
    }

    /**
     * 模式词 + 关键词部分，如 `包含任一「顺丰」或「取件码」`。
     * [connector] 为关键词间连接符：「包含任一」用「或」，其余文本模式直接拼接
     * （架构 §3.6 摘要表口径）。
     */
    private fun modeWord(word: String, connector: String? = null): String =
        "$word${keywordsPart(keywords, connector)}"

    /** 关键词摘要：`「顺丰」或「取件码」`；总长 >20 字截断加省略号；空则 `（未设置）`。 */
    private fun keywordsPart(words: List<String>, connector: String? = null): String {
        val valid = words.map { it.trim() }.filter { it.isNotEmpty() }
        if (valid.isEmpty()) return "（未设置）"
        val full =
            if (connector == null) valid.joinToString("") { "「$it」" }
            else valid.joinToString(connector) { "「$it」" }
        return if (full.length > 20) full.take(20) + "…" else full
    }

    /** 字段摘要：`（标题、正文）`；超过 3 个显示 `（标题、正文、消息列表等 N 个字段）`。 */
    private fun fieldsPart(): String {
        val labels = fieldRefs().map { it.label }
        return when {
            labels.isEmpty() -> ""
            labels.size > 3 ->
                "（${labels.take(3).joinToString("、")}等 ${labels.size} 个字段）"

            else -> "（${labels.joinToString("、")}）"
        }
    }

    companion object {
        const val SCHEMA_VERSION = 2
    }
}

/** 规则级匹配模式（单选，互斥不可叠加；文案与白话解释见架构 §2.1 / PRD 3.4）。 */
enum class MatchMode(val label: String, val desc: String) {
    ALL_CONTENT("所有内容", "无条件命中，是否执行只看作用范围"),
    TEXT_ANY("包含任一", "任一关键词出现在任一被选字段即命中"),
    TEXT_ALL("包含全部", "每个关键词都要出现，可以分别出现在不同字段"),
    TEXT_NOT_ANY("不包含任一", "所有关键词都不出现才命中"),
    TEXT_NOT_ALL("不包含全部", "不是所有关键词都出现，即命中"),
    TEXT_A_NOT_B("包含 A 且不包含 B", "A 组任一出现，且 B 组一个都不出现"),
    REGEX("正则表达式", "对被选字段逐字段做正则匹配"),
    TEXT_GROUPS("分组匹配", "关键词分多组：每组都要满足，组内可设任一/全部出现"),
}

/** 通知元信息可匹配项（postTime 不做规则匹配，PRD Out of Scope）。 */
enum class MetaFieldKind(val label: String, val hint: String) {
    KEY("通知 key", "整串 userId|包名|id|tag|uid"),
    NOTIF_ID("通知 ID", "数字 id"),
    TAG("tag", "无 tag 的通知按 null 匹配"),
    CHANNEL_ID("渠道 ID", "通知渠道"),
    USER_ID("用户 ID", "分身/多用户，通常为 0"),
    IS_ONGOING("是否常驻", "常驻填 true，非常驻填 false"),
}

/** 正则配置（仅 REGEX 模式；ignoreCase=true 对应 RegexOption.IGNORE_CASE）。 */
@Serializable
data class RegexConfig(
    val pattern: String = "",
    val ignoreCase: Boolean = true,
)

/** 组内匹配方式（仅 MatchMode.TEXT_GROUPS）。 */
enum class GroupMatchMode(val label: String) {
    ANY("任一出现"),
    ALL("全部出现"),
}

/** 一组关键词（仅 MatchMode.TEXT_GROUPS）。 */
@Serializable
data class KeywordGroup(
    val words: List<String> = emptyList(),
    val mode: GroupMatchMode = GroupMatchMode.ANY,
)

/**
 * 分组匹配配置（V12.2，仅 matchMode = TEXT_GROUPS 使用）。
 * 组间 AND：每一组都必须满足；组内按 [KeywordGroup.mode] 判定。
 * 每个关键词仍在「任一被选字段」中查找（可分别出现在不同字段，沿用 matchedFields 语义）。
 */
@Serializable
data class KeywordGroupsConfig(
    val groups: List<KeywordGroup> = emptyList(),
)

/** V1 `RuleCondition.DEFAULT_FIELDS` 的迁移落点：默认被选字段（标题 / 正文 / 消息列表）。 */
object RuleConditionDefaults {
    val FIELDS: Set<MatchField> =
        setOf(MatchField.TITLE, MatchField.TEXT, MatchField.TEXTLINES)
}

/** 可匹配的 extras 文本字段（V1 六项，V2 起与元信息 / extras 任意键并列，见 [FieldRef]）。 */
enum class MatchField(val label: String) {
    TITLE("标题"),
    TEXT("正文"),
    TEXTLINES("消息列表"),
    SUBTEXT("副标题"),
    SUMMARYTEXT("摘要"),
    PEOPLE("发送者"),
    ;

    companion object {
        val ALL: List<MatchField> = entries.toList()
    }
}

/** 作用范围三选一。 */
@Serializable
data class RuleScope(
    val type: ScopeType = ScopeType.GLOBAL,
    val packages: Set<String> = emptySet(),
)

enum class ScopeType(val label: String) {
    GLOBAL("全局"),
    ONLY("仅指定 App"),
    EXCLUDE("全局，排除指定 App"),
}

/**
 * 动作组合：block 与 snooze/replace 互斥（UI 置灰约束），readAloud 可叠加。
 *
 * V4 追加两个屏幕显示类动作（[danmaku] / [float]）：它们**不取消原通知**，
 * 因此可与其他动作自由叠加（典型组合：消除 + 悬浮）。
 *
 * V5：焦点通知（[focus]，写 `miui.focus.param`）已整体删除——个人侧载拿不到小米授权，
 * 旧规则 JSON 中的 focus 段由 RuleJsonMigrator 迁移时清除并写回。
 *
 * V7：需求 7（卡片可见性条件）挂在**动作层** [floatTrigger]，需求 8（内容提取聚合）挂在
 * [aggregation]——两者都是「带默认值的新增可空键」，V1–V6 老规则 decode 后为 `null`，
 * 所有新分支不进入，行为与 V6 完全一致（架构 §2.1）。
 */
@Serializable
data class RuleActionConfig(
    val block: Boolean = false,
    val snooze: SnoozeConfig? = null,
    val replace: ReplaceConfig? = null,
    val readAloud: Boolean = false,

    /**
     * V10 任务 B：「静音消除」——[block] 的**子选项**（默认 false，老规则行为逐字不变）。
     *
     * 语义（受 Android 平台限制，用户已确认接受）：命中时先把该通知所属的
     * `NotificationChannel` 静音（`setSound(null)` + `enableVibration(false)`），再消除通知。
     * 「只静音这一条」在公开 API 下无法实现——`onNotificationPosted` 回调时声音已经发出，
     * 因此静音必然作用到**该渠道后续所有通知**。
     *
     * 存法：规则 JSON 单列里的普通布尔键（缺键 → 默认 false），**无需 Room 迁移**。
     * 只在 [block] 为 true 时有意义（`ActionExecutor` 只在消除分支里读它）。
     */
    val silentRemove: Boolean = false,

    /** V4 需求 7-②：弹幕显示样式（null = 未启用）。 */
    val danmaku: DanmakuStyle? = null,
    /** V4 需求 7-④：自定义位置悬浮显示样式（null = 未启用）。 */
    val float: FloatStyle? = null,

    /** V7 需求 7：float 卡片的显示 / 消失条件；null 或空 = 沿用 V6 行为（命中即显示）。 */
    val floatTrigger: OverlayTriggerConfig? = null,

    /** V7 需求 8：内容提取 + 聚合；null = 未启用。 */
    val aggregation: AggregationConfig? = null,
) {
    /** 将要执行的动作列表（顺序即执行顺序：朗读先行，屏幕显示类最后）。 */
    fun plannedActions(): List<ActionType> = buildList {
        if (readAloud) add(ActionType.READ_ALOUD)
        if (block) add(ActionType.BLOCK)
        if (snooze != null) add(ActionType.SNOOZE)
        if (replace != null) add(ActionType.REPLACE)
        if (danmaku != null) add(ActionType.DANMAKU)
        if (float != null) add(ActionType.FLOAT)
    }

    val isEmpty: Boolean
        get() = !block && snooze == null && replace == null && !readAloud &&
            danmaku == null && float == null &&
            (floatTrigger == null || floatTrigger.isEmpty) &&
            (aggregation == null || !aggregation.enabled)

    /**
     * 是否启用了任一屏幕显示类动作（弹幕 / 悬浮）。
     *
     * **注意**：`floatTrigger` 单独存在（无 float）不算——没有 float 样式就没有卡片可控制，
     * 这种配置在 UI 上不可达、在编译期被 `OverlayConditionArbiter` 丢弃。
     */
    val hasOverlay: Boolean get() = danmaku != null || float != null

    /**
     * V7 新增：该规则的 float 是否**由条件接管**。
     * true → `ActionExecutor` 不再「命中即 showFloat」（否则每条命中都顶掉/重播卡片），
     * 改由 `OverlayConditionArbiter` 按条件显示与重绘（架构 §3.9）。
     */
    val floatIsConditional: Boolean
        get() = float != null && floatTrigger != null && !floatTrigger.isEmpty
}

/** 推迟参数：preset 见 [SnoozePresets]；自定义分钟 / 自定义时刻二选一。 */
@Serializable
data class SnoozeConfig(
    /** V3：时刻列表（HH:mm，按用户添加顺序存；引擎不依赖顺序，取最近下一时刻）。新规则只用此字段。 */
    val times: List<String> = emptyList(),
    // —— V2 兼容字段（deprecated；仅旧规则迁移保留，引擎 times 为空时回退使用，架构 D1）——
    val preset: String? = null,
    val customMinutes: Int? = null,
    val customTimeHHmm: String? = null,
)

object SnoozePresets {
    const val M10 = "10m"
    const val M30 = "30m"
    const val H1 = "1h"
    const val TONIGHT_20 = "tonight20"
    const val CUSTOM_MINUTES = "custom_minutes"
    const val CUSTOM_TIME = "custom_time"

    val ALL = listOf(M10, M30, H1, TONIGHT_20, CUSTOM_MINUTES, CUSTOM_TIME)

    fun label(preset: String?): String = when (preset) {
        M10 -> "10 分钟后"
        M30 -> "30 分钟后"
        H1 -> "1 小时后"
        TONIGHT_20 -> "今晚 8 点"
        CUSTOM_MINUTES -> "自定义分钟"
        CUSTOM_TIME -> "自定义时刻"
        else -> "未设置"
    }
}

/**
 * 替换参数：三种改写模式 + 保留开关 + 渠道名规则。
 *
 * V2（需求③）起自定义文案拆为双模板：标题模板 [customTitleTemplate]（承接 V1
 * `customTemplate`，语义不变：模板作标题）+ 正文模板 [customTextTemplate]（V2 新增）；
 * 两者均可独立留空（空 = 回落原文）。
 */
@Serializable
data class ReplaceConfig(
    val mode: RewriteMode = RewriteMode.PLAIN_TEXT,
    /** 标题模板（CUSTOM_TEXT）；空 = 回落原标题/应用名。V1 customTemplate 迁移至此。 */
    val customTitleTemplate: String? = null,
    /** 正文模板（CUSTOM_TEXT）；空 = 回落原正文/消息列表首条。V2 新增。 */
    val customTextTemplate: String? = null,
    val keepIcon: Boolean = true,
    val keepLargeImage: Boolean = false,
    val channelUseAppName: Boolean = true,
)

enum class RewriteMode(val label: String) {
    /** 去大图标和图片：纯文字基础通知。 */
    STRIP_IMAGES("去掉图标和图片"),

    /** 精简为纯文本：标题 + 正文，去富样式。 */
    PLAIN_TEXT("精简为纯文本"),

    /** 自定义文案：标题 / 正文双模板渲染（占位符见 T07 Placeholders）。 */
    CUSTOM_TEXT("自定义文案"),
}

// ==================== 通知快照 ====================

/**
 * 一条通知的匹配视角快照（FieldExtractor 产物）。
 *
 * [extras] 为全量字段 dump：`List<Pair<key, value>>`，key 已排序、值统一字符串化
 * （架构共享约定 5），存储与展示共用同一结果，保证「所见即所存」。
 *
 * [tag]（V2 新增）：StatusBarNotification 的 tag，与 key 第 4 段同源；
 * 流水还原快照时从 `userId|pkg|id|tag|uid` 第 4 段解析（T06）。
 */
data class NotificationSnapshot(
    val key: String,
    val notifId: Int,
    val packageName: String,
    val appName: String,
    val userId: Int = 0,
    val channelId: String? = null,
    val postTime: Long = 0L,
    val isOngoing: Boolean = false,
    val flags: Int = 0,
    val title: String? = null,
    val text: String? = null,
    val textLines: List<String> = emptyList(),
    val subText: String? = null,
    val summaryText: String? = null,
    val people: List<String> = emptyList(),
    val extras: List<Pair<String, String>> = emptyList(),
    val tag: String? = null,
) {
    /** 取指定文本字段的文本（规则匹配 / 搜索共用）。 */
    fun fieldText(field: MatchField): String = when (field) {
        MatchField.TITLE -> title.orEmpty()
        MatchField.TEXT -> text.orEmpty()
        MatchField.TEXTLINES -> textLines.joinToString("\n")
        MatchField.SUBTEXT -> subText.orEmpty()
        MatchField.SUMMARYTEXT -> summaryText.orEmpty()
        MatchField.PEOPLE -> people.joinToString("\n")
    }

    /**
     * 取指定 [FieldRef] 的文本（V2 引擎统一取值入口）。
     * - [MetaFieldKind.TAG]：无 tag 按 `"null"`（与 key 整串展示一致，PRD 已确认此歧义）；
     * - [MetaFieldKind.IS_ONGOING]：`"true"` / `"false"`；
     * - [FieldRef.Extras]：extras 中该键的值（键名大小写敏感精确匹配），不存在按空串。
     */
    fun fieldText(ref: FieldRef): String = when (ref) {
        is FieldRef.Text -> fieldText(ref.field)
        is FieldRef.Meta -> when (ref.kind) {
            MetaFieldKind.KEY -> key
            MetaFieldKind.NOTIF_ID -> notifId.toString()
            MetaFieldKind.TAG -> tag ?: "null"
            MetaFieldKind.CHANNEL_ID -> channelId.orEmpty()
            MetaFieldKind.USER_ID -> userId.toString()
            MetaFieldKind.IS_ONGOING -> isOngoing.toString()
        }

        is FieldRef.Extras -> extras.firstOrNull { it.first == ref.key }?.second.orEmpty()
    }

    /** 列表展示用标题（缺失时回落正文 / 应用名）。 */
    val displayTitle: String
        get() = title?.takeIf { it.isNotBlank() } ?: text?.takeIf { it.isNotBlank() } ?: appName

    val displayText: String
        get() = text?.takeIf { it.isNotBlank() }
            ?: textLines.firstOrNull()?.takeIf { it.isNotBlank() }
            ?: ""
}

// ==================== 通知流水 ====================

/** 一条通知的最终处理结果。 */
enum class ResultKind(val label: String) {
    /** 放行（未命中规则且无动作，或仅朗读）。 */
    PASS("放行"),

    /** 消除（含黑名单命中）。 */
    BLOCK("消除"),

    /** 推迟（系统 snooze；到点恢复后打 restoredAt）。 */
    SNOOZE("推迟"),

    /** 替换（以本 App 名义重发）。 */
    REPLACE("替换"),

    /** 朗读（仅朗读时）。 */
    READ("朗读"),

    /**
     * V4：仅做屏幕显示（弹幕 / 悬浮窗 / 焦点通知），原通知照常留在通知栏。
     * 统计口径上**不算过滤**（[NotificationRecord.countsAsFiltered] 不含这些动作）。
     */
    SHOW("屏幕显示"),
}

/** 已执行（或尝试执行）的动作；含降级/失败留痕（架构共享约定 8）。 */
enum class ActionType(val label: String) {
    BLOCK("消除"),
    SNOOZE("推迟"),
    REPLACE("替换"),
    READ_ALOUD("朗读"),
    /** TTS 不可用，朗读被跳过。 */
    READ_ALOUD_SKIPPED("朗读跳过"),

    /** 替换重发失败。 */
    REPLACE_FAILED("替换失败"),

    // —— V4 需求 7-①/②/④：屏幕显示类动作 ——
    /** 弹幕显示（WindowManager 叠加层，FIFO 排队）。 */
    DANMAKU("弹幕"),

    /** 自定义位置悬浮显示。 */
    FLOAT("悬浮"),

    /** 屏幕显示类动作未执行（缺悬浮窗权限 / 服务未就绪）。 */
    OVERLAY_SKIPPED("屏幕显示跳过"),
}

/**
 * V11：单个动作归入哪个「效果」类别。
 *
 * 「效果」= 用户在记录页能筛的那 6 类（`ResultKind`）。这里把**降级/失败留痕**
 * 归到父动作：朗读跳过仍算朗读、替换失败仍算替换、屏幕显示跳过既算弹幕也算悬浮
 * （执行时已丢失原意图，两边都放进桶里，宁可多筛出来也别让用户找不到）。
 *
 * 这是「多效果筛选」的唯一口径 —— `NotificationRecord.effectKinds`、
 * 记录页 `matches()`、搜索页内存过滤三处必须都走它，不要各写一份 when。
 */
fun ActionType.resultKind(): ResultKind = when (this) {
    ActionType.BLOCK -> ResultKind.BLOCK
    ActionType.SNOOZE -> ResultKind.SNOOZE
    ActionType.REPLACE, ActionType.REPLACE_FAILED -> ResultKind.REPLACE
    ActionType.READ_ALOUD, ActionType.READ_ALOUD_SKIPPED -> ResultKind.READ
    ActionType.DANMAKU, ActionType.FLOAT, ActionType.OVERLAY_SKIPPED -> ResultKind.SHOW
}

/**
 * 该动作是否是「降级 / 失败留痕」（用户需要一眼看出没做成）。
 *
 * 这三种动作**不会**影响 `result` 的分类（`NotificationPipeline.classify()` 只认
 * `READ_ALOUD` / `REPLACE`），所以它们在旧版本里一律显示成「放行」——朗读失败用户
 * 完全看不出来。V11 起由渲染层用错误色单独标识。
 */
fun ActionType.isFailureTrace(): Boolean = when (this) {
    ActionType.READ_ALOUD_SKIPPED,
    ActionType.REPLACE_FAILED,
    ActionType.OVERLAY_SKIPPED,
    -> true
    else -> false
}

/**
 * 流水记录（notification_records 表的领域视图）。
 *
 * [hitFields]（V2）：实际参与命中的字段集合，存库口径为 [FieldRef.id] 列表
 * （架构共享约定 2；旧数据的 MatchField 枚举名由 `FieldRef.fromId` 兼容读出）。
 */
data class NotificationRecord(
    val recordId: Long = 0L,
    val key: String,
    val notifId: Int,
    val packageName: String,
    val appName: String,
    val postTime: Long,
    val result: ResultKind = ResultKind.PASS,
    val executedActions: List<ActionType> = emptyList(),
    val hitFields: Set<FieldRef> = emptySet(),
    val ruleId: Long? = null,
    val ruleName: String? = null,
    val blacklistHit: Boolean = false,
    val extrasJson: String = "[]",
    val titleSummary: String = "",
    val textSummary: String = "",
    /** 系统 snooze 到点恢复的时间；非空表示该通知已恢复显示。 */
    val restoredAt: Long? = null,
    /** 详情页元信息：多用户/分身（API 判断）。 */
    val userId: Int = 0,
    val channelId: String? = null,
    val isOngoing: Boolean = false,
    val flags: Int = 0,
) {
    /** 统计口径（架构共享约定 6，唯一事实来源）。 */
    val countsAsFiltered: Boolean
        get() = blacklistHit || executedActions.any {
            it == ActionType.BLOCK || it == ActionType.SNOOZE || it == ActionType.REPLACE
        }

    /** 列表标签文案：已恢复优先于结果。 */
    val tagLabel: String
        get() = when {
            restoredAt != null -> "已恢复"
            blacklistHit && result == ResultKind.BLOCK -> "消除"
            else -> result.label
        }

    /**
     * V11：这条流水贡献的**效果集合**（去重、保持动作执行顺序）。
     *
     * 为什么需要它：一条规则可以同时配多个动作（例如「消除 + 朗读 + 弹幕」），
     * `executedActions` 已经完整落库，但 [result] 是 `classify()` 压出来的**单个主结论**。
     * 列表/详情/筛选若只看 [result]，用户就永远只看到优先级最高的那一个效果。
     *
     * 口径：
     * - `executedActions` 非空 → 逐个走 [ActionType.resultKind] 去重；
     * - 为空（未命中规则、仅放行、旧数据）→ 回落 `listOf(result)`，保证「放行」等
     *   无动作记录仍可被筛出来。
     *
     * **不落库、不进 JSON**：纯派生，随 `executedActions` 一起走。
     */
    val effectKinds: List<ResultKind>
        get() {
            if (executedActions.isEmpty()) return listOf(result)
            val out = LinkedHashSet<ResultKind>(executedActions.size)
            executedActions.forEach { out += it.resultKind() }
            return out.toList()
        }
}

// ==================== 延时重发任务 ====================

/**
 * 推迟 × 替换：到点后以本 App 名义重发的改写版通知任务。
 *
 * V7（需求 8）：新增 [renderSource] / [aggregationJson] 两字段，**默认值即老行为**
 * （`PLAIN` / `null`）——V1–V6 落库的旧任务重放时走的还是"原样重发已渲染文案"，
 * 行为与 V6 完全一致（架构 §2.1 / §2.4 的 Room 迁移给旧行补 `'PLAIN'`）。
 */
data class PendingRepost(
    val id: Long = 0L,
    val ruleId: Long? = null,
    val triggerAt: Long,
    val originalKey: String,
    val packageName: String,
    val appName: String,
    /** 调度时已渲染定型的文案（V6 行为）；`renderSource = AGGREGATION` 时作为**兜底**。 */
    val repostTitle: String,
    val repostText: String,
    val replaceConfigJson: String,
    val channelUseAppName: Boolean = true,

    /** V7：文案来源。PLAIN（默认）= 原样重发上面的已渲染文案；AGGREGATION = 到点用最新汇总重渲染。 */
    val renderSource: String = RepostRenderSource.PLAIN.name,

    /** V7：AGGREGATION 时的聚合配置 JSON（含条目分隔符，到点用它重算模板）。 */
    val aggregationJson: String? = null,
) {
    /** [renderSource] 的强类型视图（未知值回落 [RepostRenderSource.PLAIN]，与枚举 of 口径一致）。 */
    val renderSourceEnum: RepostRenderSource get() = RepostRenderSource.of(renderSource)

    /** 到点是否需要用「最新聚合列表」重渲染（false = V6 原样重发）。 */
    val isAggregated: Boolean get() = renderSourceEnum == RepostRenderSource.AGGREGATION
}

/** 延时重发的文案来源（V7 需求 8）。 */
enum class RepostRenderSource {
    /** 原样重发调度时已渲染好的文案（V6 行为，默认）。 */
    PLAIN,

    /** 到点从 DB 读该规则的最新聚合列表并重新渲染模板（D15）。 */
    AGGREGATION,
    ;

    companion object {
        /** 按枚举名解析（JSON/DB 存名）；未知值回落 [PLAIN]（安全侧：老行为）。 */
        fun of(name: String?): RepostRenderSource =
            entries.firstOrNull { it.name == name } ?: PLAIN
    }
}

// ==================== 统计 ====================

/** 某 App 某天的计数（stats_daily 表领域视图）。 */
data class StatDaily(
    val packageName: String,
    val appName: String = "",
    val epochDay: Int,
    val filtered: Int = 0,
    val passed: Int = 0,
)

/** 按 App 汇总（统计页列表）。 */
data class AppStat(
    val packageName: String,
    val appName: String,
    val filtered: Int,
    val passed: Int,
) {
    val total: Int get() = filtered + passed
}

/** 按天汇总（趋势图数据点）。 */
data class DayStat(
    val epochDay: Int,
    val filtered: Int,
    val passed: Int,
)

// ==================== 黑名单 ====================

data class BlacklistEntry(
    val packageName: String,
    val appName: String,
    val addedAt: Long,
)

// ==================== 搜索 ====================

/** 流水搜索条件（P9）；keyword 的字段范围由 [fields] 限定（文本六字段口径）。 */
data class SearchFilter(
    val keyword: String? = null,
    val fields: Set<MatchField> = MatchField.ALL.toSet(),
    val packages: Set<String> = emptySet(),
    val fromTime: Long? = null,
    val toTime: Long? = null,
    val results: Set<ResultKind> = emptySet(),
    val ruleId: Long? = null,
) {
    val hasAnyCondition: Boolean
        get() = !keyword.isNullOrBlank() || packages.isNotEmpty() || fromTime != null ||
            toTime != null || results.isNotEmpty() || ruleId != null
}

// ==================== 其他小模型 ====================

/** 规则排序批量更新载体。 */
data class IdOrder(
    val id: Long,
    val order: Int,
)
