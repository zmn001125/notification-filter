package com.local.notificationfilter.core.aggregate

import com.local.notificationfilter.core.model.AggregationConfig
import com.local.notificationfilter.core.model.AggregationLists
import com.local.notificationfilter.core.model.NotificationSnapshot
import com.local.notificationfilter.core.model.PlaceholderExtras
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 汇总渲染单测（V7 需求 8，架构 §7.4）。
 *
 * 覆盖：文档 §7.4 用例表逐条 + 边界（空列表 / 越界 itemsN / 空模板回落 /
 * 模板渲染为空时回落 / 条目分隔符转义 / 9 槽位展开）。
 *
 * 关键纪律：`{itemsN}` 与全部占位符的展开**只能**由 `Placeholders` 完成，
 * 本测试因此同时断言"混合占位符"与"未识别 token"的既有口径未被破坏。
 */
class AggregationRendererTest {

    private fun snapshot(
        title: String? = "顺丰速运",
        text: String? = "您的取件码已到",
        appName: String = "顺丰速运",
    ): NotificationSnapshot = NotificationSnapshot(
        key = "0|com.sf.express|1|tag|0",
        notifId = 1,
        packageName = "com.sf.express",
        appName = appName,
        title = title,
        text = text,
    )

    private val twoLists = listOf(
        listOf("0000-00", "1111-11"),
        listOf("2222-22"),
    )

    // ==================== 文档 §7.4 用例表 ====================

    @Test
    fun `两个列表用中文顿号拼接`() {
        val extras = AggregationRenderer.extras(twoLists, "、")
        val result = AggregationRenderer.render("{items1}、{items2}", snapshot(), extras)
        assertEquals("0000-00、1111-11、2222-22", result)
    }

    @Test
    fun `换行分隔符每行一条`() {
        val extras = AggregationRenderer.extras(twoLists, "\n")
        val result = AggregationRenderer.render("{items1}", snapshot(), extras)
        assertEquals("0000-00\n1111-11", result)
    }

    @Test
    fun `空分隔符直接相接`() {
        val extras = AggregationRenderer.extras(twoLists, "")
        val result = AggregationRenderer.render("{items1}", snapshot(), extras)
        assertEquals("0000-001111-11", result)
    }

    @Test
    fun `列表为空时 itemsN 渲染空串`() {
        val extras = AggregationRenderer.extras(AggregationLists.EMPTY, "\n")
        val result = AggregationRenderer.render("今天的快递：{items1}", snapshot(), extras)
        assertEquals("今天的快递：", result)
    }

    @Test
    fun `itemsN 越界渲染空串`() {
        val extras = AggregationRenderer.extras(twoLists, "\n")
        val result = AggregationRenderer.render("前{items3}后", snapshot(), extras)
        assertEquals("前后", result)
    }

    @Test
    fun `混合占位符分别走快照与列表`() {
        val extras = AggregationRenderer.extras(twoLists, "、")
        val result = AggregationRenderer.render("{app} 汇总：{items1}", snapshot(), extras)
        assertEquals("顺丰速运 汇总：0000-00、1111-11", result)
    }

    @Test
    fun `resolveSeparator 解析字面量转义`() {
        assertEquals("\n", AggregationRenderer.resolveSeparator("\\n"))
        assertEquals("\t", AggregationRenderer.resolveSeparator("\\t"))
        assertEquals("", AggregationRenderer.resolveSeparator(""))
        // 真实换行不会被二次处理
        assertEquals("\n", AggregationRenderer.resolveSeparator("\n"))
    }

    // ==================== join / extras 组装 ====================

    @Test
    fun `join 与 itemsN 同源同语义`() {
        val extras = AggregationRenderer.extras(twoLists, "、")
        assertEquals(
            AggregationRenderer.join(twoLists[0], "、"),
            extras.items(1),
        )
    }

    @Test
    fun `join 空列表返回空串`() {
        assertEquals("", AggregationRenderer.join(emptyList(), "\n"))
    }

    @Test
    fun `extras 超过 9 个列表时只取前 9 个`() {
        val lots = (1..12).map { index -> listOf("v$index") }
        val extras = AggregationRenderer.extras(lots, "\n")
        assertEquals(9, extras.listCount)
        assertEquals("v9", extras.items(9))
        assertEquals("", extras.items(10))
    }

    @Test
    fun `extras 按 listIndex 展开为固定 9 槽`() {
        // 只给了 listIndex=1 的列表：{items2} 存在但为空
        val lists = AggregationLists(listOf(listOf("0000-00")))
        val extras = AggregationRenderer.extras(lists, "\n")
        assertEquals(9, extras.listCount)
        assertEquals("0000-00", extras.items(1))
        assertEquals("", extras.items(2))
    }

    @Test
    fun `extras 解析字面量分隔符`() {
        val lists = AggregationLists(listOf(listOf("A", "B")))
        val extras = AggregationRenderer.extras(lists, "\\n")
        assertEquals("A\nB", extras.items(1))
    }

    @Test
    fun `NONE 上下文下 itemsN 渲染空串`() {
        assertEquals("", PlaceholderExtras.NONE.items(1))
        assertEquals("", AggregationRenderer.render("{items1}", snapshot(), PlaceholderExtras.NONE))
    }

    // ==================== render(config) 回落口径 ====================

    private fun config(
        titleTemplate: String = "",
        textTemplate: String = "",
        itemSeparator: String = "\n",
    ): AggregationConfig = AggregationConfig(
        titleTemplate = titleTemplate,
        textTemplate = textTemplate,
        itemSeparator = itemSeparator,
    )

    @Test
    fun `模板为空回落原通知标题与正文`() {
        val rendered = AggregationRenderer.render(
            config = config(),
            snapshot = snapshot(),
            lists = AggregationLists(twoLists),
        )
        assertEquals("顺丰速运", rendered.title)
        assertEquals("您的取件码已到", rendered.text)
    }

    @Test
    fun `正文为空时回落标题`() {
        val rendered = AggregationRenderer.render(
            config = config(),
            snapshot = snapshot(title = "只有标题", text = null),
            lists = AggregationLists.EMPTY,
        )
        assertEquals("只有标题", rendered.title)
        assertEquals("只有标题", rendered.text)
    }

    @Test
    fun `模板正常时用模板渲染`() {
        val rendered = AggregationRenderer.render(
            config = config(titleTemplate = "快递汇总（{app}）", textTemplate = "{items1}"),
            snapshot = snapshot(),
            lists = AggregationLists(twoLists),
        )
        assertEquals("快递汇总（顺丰速运）", rendered.title)
        assertEquals("0000-00\n1111-11", rendered.text)
    }

    @Test
    fun `模板非空但渲染为空时回落原文`() {
        // 只写了 {items1} 且列表还没攒到条目 → 卡片不能空白
        val rendered = AggregationRenderer.render(
            config = config(titleTemplate = "{items1}", textTemplate = "{items2}"),
            snapshot = snapshot(title = "顺丰速运", text = "您的取件码已到"),
            lists = AggregationLists.EMPTY,
        )
        assertEquals("顺丰速运", rendered.title)
        assertEquals("您的取件码已到", rendered.text)
    }

    @Test
    fun `config 的 itemSeparator 走转义解析`() {
        val rendered = AggregationRenderer.render(
            config = config(titleTemplate = "{items1}", itemSeparator = "\\t"),
            snapshot = snapshot(),
            lists = AggregationLists(twoLists),
        )
        assertEquals("0000-00\t1111-11", rendered.title)
    }

    @Test
    fun `未识别占位符仍渲染空串（既有口径不变）`() {
        val rendered = AggregationRenderer.render(
            config = config(titleTemplate = "汇总{不存在}"),
            snapshot = snapshot(),
            lists = AggregationLists(twoLists),
        )
        assertEquals("汇总", rendered.title)
    }
}
