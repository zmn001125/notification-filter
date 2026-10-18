package com.local.notificationfilter.core.model

import com.local.notificationfilter.core.aggregate.ItemExtractor
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 占位符统一解析器（V2T07，架构 §4.2 / PRD 3.3；纯 Kotlin 无 Android 依赖，JVM 单测全覆盖）。
 *
 * 唯一渲染处（架构共享约定 5）：只有本对象与 [com.local.notificationfilter.actions.NotificationReposter]
 * 消费模板；UI 的保存校验（未知占位符提示）经 [findUnknown] 出口。
 *
 * 四组占位符（[ITEMS]）：
 * - 常用（V1 兼容）：`{title}` `{text}` `{app}`；
 * - 文本字段：`{textLines}` `{subText}` `{summaryText}` `{people}`；
 * - 通知信息：`{key}` `{id}` `{tag}` `{pkg}` `{channelId}` `{time}`；
 * - Extras 字段：`{extras.<完整键名>}`（非固定枚举，UI chips 由调用方按规则已选 extras
 *   字段动态生成 token）。
 *
 * 大小写规则（架构 D10 / R12）：
 * - 固定名**ignoreCase** 匹配（延续 V1 行为，`{TITLE}` 也要认）；
 * - `extras.` **前缀与键名均大小写敏感**（键是精确标识）：`{extras.title}` 取 extras 里
 *   的 `title` 键，**不是** `{title}` 的语义（R12 明示此边界）；
 * - extras 键不存在 → 空串（与需求②「该键不存在按空串处理」一致）；
 * - V7 追加的 `items<n>` 同样**大小写敏感**且**只认 1..9 单个数字**：
 *   `{ITEMS1}` / `{items0}` / `{items10}` 均为未识别 token（`{items10}` 不会被拆成
 *   `{items1}` + `0`）。
 *
 * V7 需求 8（内容提取聚合）追加一组**动态** token：`{items1}`..`{items9}`。
 * 取值来自调用方传入的 [PlaceholderExtras]（各提取器列表的**已渲染文本**），
 * 列表不存在 / 为空 / 越界 → 空串（与 V6"未识别 token → 空串"口径一致，见 §4.2）。
 * 该组 token **不进 [ITEMS] chips 表**（和 `extras.` 一样由调用方按规则实际配置动态生成）。
 *
 * ### `{reN}`（即时提取）与 `{itemsN}`（累积列表）的区别——勿混淆
 * 两者共用同一套提取器配置（`Rule.aggregation.extractors` 的 `listIndex` 就是 N），
 * 但**取数范围完全不同**：
 * - `{reN}`：**只针对当前这一条通知**跑一次正则，把这条通知里抽到的全部条目按
 *   `itemSeparator` 拼成一个字符串（不读库、不落库、不留到下次）。典型用法：
 *   「替换标题模板 = 取件码 {re1}」——每来一条通知就当场从这条通知里抓。
 *   上下文由 [PlaceholderExtract] 承载（[PlaceholderExtract.of] 组装）；
 * - `{itemsN}`：**跨多条通知累积**的列表（经 `aggregation_items` 表暂存、去重、
 *   按 limit 汰旧），取的是"到目前为止攒下来的所有条目"。上下文由
 *   [PlaceholderExtras] 承载（由 `AggregationRenderer.extras(...)` 组装）。
 *
 * 一句话：`{reN}` = 当前这条（即时）；`{itemsN}` = 到目前为止（累积）。
 *
 * 渲染规则：
 * - 逐 token 扫描解析（[TOKEN_REGEX]），**不用连续 String.replace**——否则 `{text}` 会
 *   误伤 `{textLines}`、`{extras.android.text}` 会被部分替换；
 * - 未识别占位符渲染按空串处理（PRD：不留 `{xxx}` 字样）；保存时 [findUnknown] 给出提示；
 * - 渲染结果整体 trim（延续 V1 `renderTemplate` 的 trim 行为，保证旧模板输出不变）。
 */
object Placeholders {

    /** 占位符分组（UI chips 分组 / 展示用）。 */
    enum class Group(val label: String) {
        COMMON("常用"),
        TEXT("文本字段"),
        INFO("通知信息"),
        EXTRAS("Extras 字段"),
    }

    /** 一个占位符项（token = 模板中的字面写法）。 */
    data class Item(val token: String, val desc: String, val group: Group)

    /** extras 前缀（大小写敏感，见 [resolveToken]）。 */
    const val EXTRAS_PREFIX = "extras."

    /**
     * UI chips 数据源（Extras 组不在本表：`{extras.<键名>}` 由调用方按规则已选 extras
     * 字段动态生成，见 PlaceholderChips 的 extrasTokens 参数）。
     */
    val ITEMS: List<Item> = listOf(
        Item("{title}", "原标题", Group.COMMON),
        Item("{text}", "原正文", Group.COMMON),
        Item("{app}", "原应用名", Group.COMMON),
        Item("{textLines}", "消息列表（多行）", Group.TEXT),
        Item("{subText}", "副标题", Group.TEXT),
        Item("{summaryText}", "摘要", Group.TEXT),
        Item("{people}", "发送者", Group.TEXT),
        Item("{key}", "通知 key", Group.INFO),
        Item("{id}", "通知 ID", Group.INFO),
        Item("{tag}", "tag", Group.INFO),
        Item("{pkg}", "包名", Group.INFO),
        Item("{channelId}", "渠道 ID", Group.INFO),
        Item("{time}", "发送时间 yyyy-MM-dd HH:mm", Group.INFO),
    )

    /** token 扫描：`\{([^{}]+)\}`——花括号内不含花括号，逐 token 替换，互不误伤。 */
    private val TOKEN_REGEX = Regex("\\{([^{}]+)\\}")

    /**
     * `{itemsN}` 的 token 名（V7）：**大小写敏感**、N 只允许 1..9 单个数字。
     * 用 `matchEntire` 保证 `{items10}` / `{items0}` / `{ITEMS1}` 不被误认。
     */
    private val ITEMS_NAME = Regex("items([1-9])")

    /**
     * `{reN}` 的 token 名（即时提取）：与 [ITEMS_NAME] 同口径——**大小写敏感**、
     * N 只允许 1..9 单个数字，`matchEntire` 保证 `{re10}` / `{re0}` / `{RE1}` 不被误认。
     */
    private val RE_NAME = Regex("re([1-9])")

    /** `{itemsN}` 的合法 N 上界（与文档 §4.2 一致：1..9）。 */
    const val MAX_ITEM_INDEX: Int = 9

    /** `{time}` 固定格式（本地时区，PRD 明示不提供格式自定义）。 */
    private val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

    /** 固定名集合（ignoreCase 匹配；extras 前缀不在此列）。 */
    private val FIXED_NAMES: Set<String> = ITEMS.map { it.token.removeSurrounding("{", "}") }.toSet()

    /**
     * 渲染模板：逐 token 替换为快照值；未识别 / 不存在的值一律按空串；结果 trim
     * （V1 兼容：V1 `renderTemplate` 即 trim 后输出）。
     *
     * @param template 模板原文
     * @param snapshot 通知快照（固定名 / extras 的取值来源）
     * @param extras V7 需求 8 的聚合列表上下文；默认 [PlaceholderExtras.NONE]，
     *   因此 V6 的全部调用点（`NotificationReposter` / `ActionExecutor`）零改动、行为零变化
     * @param extract 即时提取上下文（`{re1}`..`{re9}`，见 [PlaceholderExtract]）；
     *   默认 [PlaceholderExtract.NONE] → `{reN}` 一律渲染空串（老调用点零改动）
     */
    fun render(
        template: String,
        snapshot: NotificationSnapshot,
        extras: PlaceholderExtras = PlaceholderExtras.NONE,
        extract: PlaceholderExtract = PlaceholderExtract.NONE,
    ): String =
        TOKEN_REGEX.replace(template) { match ->
            resolveToken(match.groupValues[1], snapshot, extras, extract)
        }.trim()

    /**
     * 保存校验用：返回模板中**未识别**的 token 原文列表（如 `["{xxx}"]`，去重保序）。
     * 判定口径与 [render] 一致：`extras.` 前缀一律合法；固定名 ignoreCase；
     * `{itemsN}` 仅当 `1 <= N <= itemCount` 时合法；`{reN}` 仅当 `1 <= N <= reCount` 时合法。
     *
     * @param template 模板原文
     * @param itemCount 该规则可用的 `{itemsN}` 上界（= `AggregationConfig.maxListIndex`）；
     *   默认 0 → 与 V6 完全一致（`{items1}` 也会被判为未识别）
     * @param reCount 该规则可用的 `{reN}` 上界（同样取 `AggregationConfig.maxListIndex`，
     *   因为 `{reN}` 与 `{itemsN}` 共用提取器的 `listIndex`）；默认 0 → 与 V6 完全一致
     */
    fun findUnknown(template: String, itemCount: Int = 0, reCount: Int = 0): List<String> =
        TOKEN_REGEX.findAll(template)
            .map { it.groupValues[1] }
            .filter { name -> !isKnownToken(name, itemCount, reCount) }
            .map { "{$it}" }
            .distinct()
            .toList()

    /**
     * `{reN}` 的 chips 数据源（UI 用）：`token 原文` → `显示名（提取器 name）`。
     *
     * 与 `extras.` / `{itemsN}` 同为动态 token，故**不进 [ITEMS] 固定表**；
     * 只为该规则**激活**的提取器生成（未启用的提取器不给 chip，避免插进去渲染空串）。
     *
     * @param config 规则的聚合配置（null / 无激活提取器 → 空列表，UI 侧据此整组不显示）
     * @return 形如 `("{re1}" to "取件码")`，按 `listIndex` 升序
     */
    fun extractTokens(config: AggregationConfig?): List<Pair<String, String>> =
        config?.active
            ?.filter { it.listIndex in 1..MAX_ITEM_INDEX }
            ?.map { cfg ->
                "{re${cfg.listIndex}}" to
                    (cfg.name.takeIf { it.isNotBlank() } ?: "提取器${cfg.listIndex}")
            }
            ?: emptyList()

    /** token 是否为已识别（extras. 前缀 / `{itemsN}` 且 N ≤ itemCount / `{reN}` 且 N ≤ reCount / 固定名 ignoreCase）。 */
    private fun isKnownToken(name: String, itemCount: Int, reCount: Int): Boolean {
        if (name.startsWith(EXTRAS_PREFIX)) return true
        val itemIndex = ITEMS_NAME.matchEntire(name)?.groupValues?.get(1)?.toIntOrNull()
        if (itemIndex != null) return itemIndex <= itemCount
        val reIndex = RE_NAME.matchEntire(name)?.groupValues?.get(1)?.toIntOrNull()
        if (reIndex != null) return reIndex <= reCount
        return FIXED_NAMES.any { it.equals(name, ignoreCase = true) }
    }

    /**
     * 解析单个 token 名（不含花括号）。
     * extras 分支：键名**大小写敏感**精确查找，不存在 → 空串（R12 边界见类注释）。
     * items 分支（V7）：大小写敏感、只认 1..9，列表缺失 / 为空 → 空串。
     * re 分支（即时提取）：大小写敏感、只认 1..9，未配置该提取器 / 无匹配 → 空串。
     */
    private fun resolveToken(
        name: String,
        snapshot: NotificationSnapshot,
        extras: PlaceholderExtras,
        extract: PlaceholderExtract,
    ): String {
        if (name.startsWith(EXTRAS_PREFIX)) {
            val key = name.removePrefix(EXTRAS_PREFIX)
            return snapshot.extras.firstOrNull { it.first == key }?.second.orEmpty()
        }
        ITEMS_NAME.matchEntire(name)?.let { match ->
            return extras.items(match.groupValues[1].toInt())
        }
        RE_NAME.matchEntire(name)?.let { match ->
            return extract.value(match.groupValues[1].toInt())
        }
        return when (name.lowercase()) {
            "title" -> snapshot.title.orEmpty()
            "text" -> snapshot.text.orEmpty()
            "app" -> snapshot.appName
            "textlines" -> snapshot.textLines.joinToString("\n")
            "subtext" -> snapshot.subText.orEmpty()
            "summarytext" -> snapshot.summaryText.orEmpty()
            "people" -> snapshot.people.joinToString("\n")
            "key" -> snapshot.key
            "id" -> snapshot.notifId.toString()
            "tag" -> snapshot.tag.orEmpty()
            "pkg" -> snapshot.packageName
            "channelid" -> snapshot.channelId.orEmpty()
            "time" -> formatTime(snapshot.postTime)
            // 未识别占位符：按空串渲染（PRD：不留 {xxx} 字样；保存时 findUnknown 提示）
            else -> ""
        }
    }

    /** `{time}`：本地时区 yyyy-MM-dd HH:mm；postTime <= 0 → 空串。 */
    private fun formatTime(postTime: Long): String =
        if (postTime > 0) {
            TIME_FORMAT.format(Instant.ofEpochMilli(postTime).atZone(ZoneId.systemDefault()))
        } else {
            ""
        }
}

/**
 * 聚合列表上下文（V7 需求 8：内容提取聚合的 `{itemsN}`）。
 *
 * 只承载"数据"，**不实现任何模板语法**——token 扫描仍然只有 [Placeholders.render] 一处。
 * 由 `core/aggregate/AggregationRenderer.extras(...)` 组装（它自己也不解析 `{` `}`），
 * 再作为 `extras` 参数传给 [Placeholders.render] / 供 `Placeholders.findUnknown(itemCount)`
 * 的 `itemCount` 配套使用。
 *
 * 与 `AggregationLists`（core/model/Aggregation.kt）的分工：`AggregationLists` 是运行时的
 * 原始列表容器，本类型是「已按条目分隔符拼装好的渲染上下文」；两者都只是数据载体。
 *
 * @param lists 下标 0 对应 `{items1}`；缺失的列表按空列表处理
 * @param itemSeparator 条目分隔符（**已由 [EscapeCodec] 把 `\n` / `\t` 解析成真实字符**）；
 *   空串 = 直接相接
 */
data class PlaceholderExtras(
    val lists: List<List<String>> = emptyList(),
    val itemSeparator: String = "\n",
) {
    /**
     * `{itemsN}`（1 基）的渲染值：该列表按 [itemSeparator] join。
     * 越界 / 列表不存在 / 列表为空 → 空串（绝不抛异常，也不留 `{itemsN}` 字面量）。
     */
    fun items(index1Based: Int): String =
        lists.getOrNull(index1Based - 1)?.joinToString(itemSeparator).orEmpty()

    /** 列表数量（`{items1}`..`{items<listCount>}` 为"有数据来源"的 token）。 */
    val listCount: Int get() = lists.size

    companion object {
        /** 空上下文：等价 V6 行为（`{itemsN}` 一律渲染空串）。 */
        val NONE = PlaceholderExtras()
    }
}

/**
 * **即时提取**上下文（`{re1}`..`{re9}`）：用规则的第 N 个激活提取器对**当前这一条通知**
 * 跑一次正则，把抽到的全部条目按 `itemSeparator` 拼成一个字符串。
 *
 * 与 [PlaceholderExtras]（跨多条通知的**累积列表**）的分工见 [Placeholders] 类注释——
 * 一句话：`{reN}` = 当前这条；`{itemsN}` = 到目前为止攒下来的。
 *
 * 只承载"数据"，**不实现任何模板语法**（token 扫描仍然只有 [Placeholders.render] 一处）；
 * 也不读库、不写库（纯内存、单次渲染内有效），因此可在 JVM 单测里直接构造。
 *
 * @param values `listIndex`（1 基）→ 已拼好的文本；缺失的下标按空串处理
 */
data class PlaceholderExtract(val values: Map<Int, String> = emptyMap()) {

    /**
     * `{reN}`（1 基）的渲染值。**越界 / 未配置该提取器 / 无匹配 → 空串**
     * （绝不抛异常，也不留 `{reN}` 字面量）。
     */
    fun value(index1Based: Int): String = values[index1Based].orEmpty()

    /** 已配置的提取器个数（仅供日志 / 断言）。 */
    val count: Int get() = values.size

    companion object {
        /** 空上下文：等价改前行为（`{reN}` 一律渲染空串）。 */
        val NONE = PlaceholderExtract()

        /**
         * 组装即时提取上下文（**纯函数**，便于单测）：对每个激活提取器跑一次
         * [ItemExtractor.extractAll]（其内部已按 `dedupe` / `limit` 处理），
         * 再把结果按 `itemSeparator` 拼成一段文本放进 `values[listIndex]`。
         *
         * 容错：任何异常（非法正则、捕获组越界……）都被吞掉，该提取器按空串处理——
         * 引擎正在处理真实通知，**绝不能被用户写坏的正则搞崩**。
         *
         * @param snapshot 当前这条通知（提取的输入）
         * @param extractors 规则的提取器列表（未激活 / `listIndex` 不在 1..9 的被跳过）
         * @param itemSeparator 条目分隔符原文（内部经 [EscapeCodec.decode] 解析，
         *   与 `AggregationRenderer.extras` 同口径；默认 `\n`）
         * @return 即时提取上下文（无任何激活提取器 → [NONE] 等价的空上下文）
         */
        fun of(
            snapshot: NotificationSnapshot,
            extractors: List<ExtractorConfig>,
            itemSeparator: String = "\n",
        ): PlaceholderExtract {
            if (extractors.isEmpty()) return NONE
            val separator = EscapeCodec.decode(itemSeparator)
            val values = HashMap<Int, String>(extractors.size * 2 + 1)
            for (cfg in extractors) {
                if (!cfg.isActive) continue
                val index = cfg.listIndex
                if (index !in 1..Placeholders.MAX_ITEM_INDEX) continue
                val items = runCatching { ItemExtractor.extractAll(snapshot, cfg) }
                    .getOrElse { emptyList() }
                values[index] = items.joinToString(separator)
            }
            return if (values.isEmpty()) NONE else PlaceholderExtract(values)
        }

        /**
         * 重载：直接吃一条规则的聚合配置（[AggregationConfig]）。
         *
         * @param snapshot 当前这条通知
         * @param config 规则的聚合配置（null / 无激活提取器 → [NONE]）
         */
        fun of(snapshot: NotificationSnapshot, config: AggregationConfig?): PlaceholderExtract {
            val cfg = config ?: return NONE
            if (!cfg.enabled) return NONE
            return of(snapshot, cfg.extractors, cfg.itemSeparator)
        }
    }
}
