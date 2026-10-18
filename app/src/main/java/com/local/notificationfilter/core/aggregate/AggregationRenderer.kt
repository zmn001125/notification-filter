package com.local.notificationfilter.core.aggregate

import com.local.notificationfilter.core.model.AggregationConfig
import com.local.notificationfilter.core.model.AggregationLists
import com.local.notificationfilter.core.model.EscapeCodec
import com.local.notificationfilter.core.model.NotificationSnapshot
import com.local.notificationfilter.core.model.PlaceholderExtras
import com.local.notificationfilter.core.model.PlaceholderExtract
import com.local.notificationfilter.core.model.Placeholders

/**
 * 一次聚合渲染的结果（标题 + 正文），已可直接交给悬浮卡片 / 重发通知。
 *
 * @param title 渲染后的标题（**保证非空**：模板为空或渲染为空时回落原通知标题）
 * @param text 渲染后的正文（**保证非空**：模板为空或渲染为空时回落原通知正文 → 标题）
 */
data class Rendered(val title: String, val text: String)

/**
 * 聚合汇总渲染器（V7 需求 8，架构 §7.4）。
 *
 * **它自己不做任何模板解析**——团队铁律：模板语法只有
 * [Placeholders.render] 一处实现（架构 D13 / §4.1）。本对象的四件事：
 * 1. [resolveSeparator]：条目分隔符的转义解析（内部一行 [EscapeCodec.decode]，
 *    是全工程解析 `\n` / `\t` / `\r` / `\\` 的**唯一入口**，保存时由 ViewModel 调用）；
 * 2. [join]：把一条列表按分隔符拼成一段文本（UI 预览用，与
 *    `PlaceholderExtras.items` 同源同语义）；
 * 3. [extras] / [extrasOf]：把各列表 + 分隔符组装成 [PlaceholderExtras]
 *    （"已拼好的渲染上下文"，**不涉及 `{` `}`**）；
 * 4. [render] / [renderAll]：**委托** [Placeholders.render] 出标题与正文。
 *
 * 本文件里不出现任何 `{` `}` 扫描 / 正则替换（架构 §4.1 的 Q5 定案：批准委托）。
 */
object AggregationRenderer {

    /** `{itemsN}` 的合法 N 上界（与 [Placeholders.MAX_ITEM_INDEX] 一致，1..9）。 */
    private const val LIST_SLOTS: Int = Placeholders.MAX_ITEM_INDEX

    /**
     * 条目分隔符的转义解析（**唯一入口**，架构 D14 / Q5）。
     *
     * 支持 `\n`（换行）/ `\t`（制表）/ `\r`（回车）/ `\\`（字面反斜杠）；其它 `\x` 原样保留；
     * 结尾孤立 `\` 原样保留；真实换行不受影响（单遍扫描，见 [EscapeCodec.decode]）。
     *
     * @param raw 用户在输入框里填的原文（可能是字面量 `\n`，也可能是直接回车）
     * @return 真实字符（空串 = 直接相接）
     */
    fun resolveSeparator(raw: String): String = EscapeCodec.decode(raw)

    /**
     * 把一条列表按分隔符拼成一段文本（UI「当前汇总」预览用）。
     *
     * @param list 条目列表（最新的在前）
     * @param separator 分隔符原文（内部先经 [resolveSeparator] 解析）
     * @return 拼接结果（空列表 → 空串）
     */
    fun join(list: List<String>, separator: String): String =
        list.joinToString(resolveSeparator(separator))

    /**
     * 组装模板上下文（**不解析任何 token**）。
     *
     * @param lists 下标 0 对应 `{items1}`；超过 9 个的部分被忽略（`{itemsN}` 只到 9）
     * @param separator 条目分隔符原文（内部先经 [resolveSeparator] 解析，调用方传原始值即可）
     * @return `{items1}`..`{items9}` 的渲染上下文
     */
    fun extras(lists: List<List<String>>, separator: String): PlaceholderExtras =
        PlaceholderExtras(
            lists = lists.take(LIST_SLOTS),
            itemSeparator = resolveSeparator(separator),
        )

    /**
     * 组装模板上下文（[AggregationLists] 重载，供管线直接使用）。
     *
     * 展开成**固定 9 个槽位**：缺失的 `listIndex` 填空列表，于是 `{items3}`（未配置该提取器）
     * 自然渲染为空串，不需要调用方先补齐列表。
     *
     * @param lists 各提取器当前列表（下标 0 = `listIndex 1`）
     * @param itemSeparator 条目分隔符原文
     */
    fun extras(lists: AggregationLists, itemSeparator: String): PlaceholderExtras =
        extras(List(LIST_SLOTS) { position -> lists.listOf(position + 1) }, itemSeparator)

    /**
     * 委托 [Placeholders.render] 渲染一个模板（本对象不解析 token）。
     *
     * @param template 模板原文（可为空；空模板由调用方决定回落策略）
     * @param snapshot 通知快照（固定占位符 / `{extras.x}` 的取值来源）
     * @param extras 聚合列表上下文（默认 [PlaceholderExtras.NONE]）
     * @param extract 即时提取上下文 `{re1}`..`{re9}`（默认 [PlaceholderExtract.NONE]，
     *   与改前逐字一致；见 [PlaceholderExtract]）
     * @return 渲染结果（[Placeholders.render] 已 trim）
     */
    fun render(
        template: String,
        snapshot: NotificationSnapshot,
        extras: PlaceholderExtras = PlaceholderExtras.NONE,
        extract: PlaceholderExtract = PlaceholderExtract.NONE,
    ): String = Placeholders.render(template, snapshot, extras, extract)

    /**
     * 渲染一条聚合配置的标题与正文（[AggregationConfig] 入口）。
     *
     * 回落口径与「替换」动作 / 悬浮卡片完全一致
     * （`ActionExecutor.overlayPayload`：`titleTemplate.isBlank()` → `snapshot.displayTitle`，
     * `textTemplate.isBlank()` → `snapshot.displayText.ifBlank { snapshot.displayTitle }`），
     * 并额外保证「模板非空但渲染结果为空」时也回落——否则会出现一张只有边框的空白卡片
     * （典型：模板只写了 `{items1}` 而列表还没攒到条目）。
     *
     * 聚合模板同样支持 `{reN}`（**当前这条通知**的即时提取）——与 `{itemsN}`
     * （跨通知累积列表）的分工见 [Placeholders] 类注释。
     *
     * @param config 聚合配置（用 `titleTemplate` / `textTemplate` / `itemSeparator` /
     *   `extractors`）
     * @param snapshot 触发本次渲染的通知快照
     * @param lists 各提取器当前列表
     * @param extract 即时提取上下文；默认 `null` = 由 [config] + [snapshot] 现场组装
     *   （`PlaceholderExtract.of(snapshot, config)`）。调用方（如 `ActionExecutor`）若在
     *   本次渲染之外已经算好，可传入以复用（省一次正则）。
     * @return 标题 + 正文（两者都保证非空）
     */
    fun render(
        config: AggregationConfig,
        snapshot: NotificationSnapshot,
        lists: AggregationLists = AggregationLists.EMPTY,
        extract: PlaceholderExtract? = null,
    ): Rendered {
        val context = extras(lists, config.itemSeparator)
        val reContext = extract ?: PlaceholderExtract.of(snapshot, config)
        val fallbackTitle = snapshot.displayTitle
        val fallbackText = snapshot.displayText.ifBlank { fallbackTitle }

        val title = if (config.titleTemplate.isBlank()) {
            fallbackTitle
        } else {
            render(config.titleTemplate, snapshot, context, reContext).ifBlank { fallbackTitle }
        }
        val text = if (config.textTemplate.isBlank()) {
            fallbackText
        } else {
            render(config.textTemplate, snapshot, context, reContext).ifBlank { fallbackText }
        }
        return Rendered(title = title, text = text)
    }

    /**
     * 渲染一组已组装好的上下文（[render] 的别名重载，供 `NotificationReposter` 这类
     * 「手上已有 [PlaceholderExtras]」的调用方按名字找到入口）。
     *
     * @param template 模板原文
     * @param snapshot 通知快照
     * @param extras 聚合列表上下文
     * @param extract 即时提取上下文（默认 [PlaceholderExtract.NONE]）
     */
    fun renderAll(
        template: String,
        snapshot: NotificationSnapshot,
        extras: PlaceholderExtras,
        extract: PlaceholderExtract = PlaceholderExtract.NONE,
    ): String = render(template, snapshot, extras, extract)
}
