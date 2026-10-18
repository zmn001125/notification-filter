package com.local.notificationfilter.core.model

import kotlinx.serialization.Serializable

/** 一个提取器（需求 8：一条规则可配多个，每个各自一个暂存列表）。 */
@Serializable
data class ExtractorConfig(
    /**
     * 列表标识，1..9。**稳定不变**（`{itemsN}` 的 N 就是它），
     * 增删提取器后重新编号会让已攒的条目错位，故 UI 用"取当前未占用的最小正整数"分配，
     * 并允许用户在删除后重新添加时拿到同一个号。
     */
    val listIndex: Int = 1,
    /** 列表名（仅 UI 展示，如「取件码」）。 */
    val name: String = "提取器",

    /**
     * 输入字段（**复刻 FieldRef 的选择能力**：文本 / 元信息 / extras）。
     *
     * 存 [FieldRef.id]（`FieldRef.kt:15-64` 的序列化口径，共享约定 2）——
     * `FieldRef` 是 sealed class 且**未标 @Serializable**，不能直接进 @Serializable 模型。
     */
    val fieldIds: List<String> = listOf(MatchField.TEXT.name),

    /** 多字段拼接符（拼接后整体做一次正则匹配，见 §7 `ItemExtractor.extract`）。 */
    val fieldSeparator: String = "\n",

    /** 正则（Java/RE2 语法，与规则正则同一实现 `kotlin.text.Regex`）。 */
    val pattern: String = "",
    val ignoreCase: Boolean = true,

    /**
     * 捕获组拼装格式：`$0` = 整段匹配，`$1`..`$9` = 捕获组。
     * 例：`$1-$2` 把「取件码：0000-00」的两组拼成 `0000-00`。
     * 留空 = 等价 `$0`。
     *
     * 注：默认值写成 `"\$0"`（转义美元符）而不是 `"$0"`——两者运行时值都是两字符 `$0`，
     * 但 `\$` 形式对 Kotlin 字符串模板解析无歧义（避免 `$0` 被误当模板起点）。
     */
    val captureFormat: String = "\$0",

    /** 去重（相同条目只留一条，且移到最前）。 */
    val dedupe: Boolean = true,

    /** 列表条数上限（超限汰旧，编辑器范围 1..50）。 */
    val limit: Int = 10,

    val enabled: Boolean = true,
) {
    /** [fieldIds] 的强类型视图（坏 id 由 `FieldRef.fromId` 兜底为 Extras，不抛异常）。 */
    val listFields: List<FieldRef> get() = fieldIds.map { FieldRef.fromId(it) }

    /** 是否可参与提取（关掉 / 正则空 / 上限非法都不参与，避免把噪声写进库）。 */
    val isActive: Boolean get() = enabled && pattern.isNotBlank() && limit > 0

    /** 分节收起 / 列表页摘要。 */
    fun summary(): String = if (isActive) "$name（上限 $limit）" else "（未启用）"
}

/** 聚合的输出方式（可多选：同时推迟 + 常驻悬浮，用户示例两条路都要）。 */
enum class AggregationOutput(val label: String) {
    /** 推迟：沿用现有推迟动作，但**到点用最新列表**重渲染（D15）。 */
    SNOOZE("推迟（到点时用最新汇总重发）"),

    /** 常驻悬浮：卡片内容 = 渲染结果，列表变即重绘；需配「自定义位置悬浮」样式。 */
    FLOAT("常驻悬浮（汇总变化时实时更新）"),
}

/**
 * 一条规则的内容提取 + 聚合配置（需求 8）。
 *
 * 作用域：去重、条数上限、正则都以「单条规则单个提取器」为单位（用户已拍板）；
 * 存储上作用域键 = `(ruleId, listIndex)`（见 §3.7 的 `aggregation_items` 唯一索引）。
 */
@Serializable
data class AggregationConfig(
    val extractors: List<ExtractorConfig> = emptyList(),

    /** 组合·标题模板（支持 `{items1}`..`{items9}` 与全部通知占位符）。 */
    val titleTemplate: String = "",
    /** 组合·正文模板。 */
    val textTemplate: String = "",

    /**
     * 条目分隔符（**已由 EscapeCodec 把 `\n` / `\t` 解析成真实字符**，D14）。
     * 默认 `"\n"`（换行）；留空 = 直接相接。
     */
    val itemSeparator: String = "\n",

    /** 输出方式（可多选；空 = 只攒不输出，用于「当前汇总」手工查看）。 */
    val outputs: Set<AggregationOutput> = emptySet(),
) {
    /** 参与提取的提取器（按 listIndex 升序，保证 {itemsN} 与列表下标一致）。 */
    val active: List<ExtractorConfig>
        get() = extractors.filter { it.isActive }.sortedBy { it.listIndex }

    /** 是否有可参与提取的提取器。 */
    val enabled: Boolean get() = active.isNotEmpty()

    /** 是否输出到常驻悬浮。 */
    val outputFloat: Boolean get() = AggregationOutput.FLOAT in outputs

    /** 是否输出到推迟重发。 */
    val outputSnooze: Boolean get() = AggregationOutput.SNOOZE in outputs

    /** `{itemsN}` 的合法 N 上界（UI 与 findUnknown 共用）。 */
    val maxListIndex: Int get() = active.maxOfOrNull { it.listIndex } ?: 0

    /** 分节收起 / 列表页摘要。 */
    fun summary(): String =
        if (!enabled) "" else "${active.size} 个提取器 · ${outputs.joinToString("/") { it.label }}"
}

// ==================== 运行时类型（不落库、不序列化） ====================

/**
 * 一次渲染的输入：各提取器当前列表。
 * 下标 0 对应 `listIndex = 1`（即 `{items1}`）；缺失的列表按空列表处理。
 */
data class AggregationLists(val lists: List<List<String>> = emptyList()) {

    /** 取 `listIndex`（1 基）对应的列表；缺失 / 越界 → 空列表。 */
    fun listOf(index1Based: Int): List<String> = lists.getOrNull(index1Based - 1).orEmpty()

    /** 全部列表的条目总数。 */
    val totalItems: Int get() = lists.sumOf { it.size }

    companion object {
        /** 空上下文（无任何列表）。 */
        val EMPTY = AggregationLists()
    }
}

/** 「当前汇总」区块的一行（UI 展示 + 单条删除定位用）。 */
data class AggregationItem(
    val ruleId: Long,
    val listIndex: Int,
    val value: String,
    val createdAt: Long,
)
