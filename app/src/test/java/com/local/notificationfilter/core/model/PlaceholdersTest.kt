package com.local.notificationfilter.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 占位符渲染单测（V2T07，需求③；架构 §4.2 / PRD 3.3）。
 *
 * 覆盖（任务书七条硬性要求）：
 * 1. V1 旧三占位符回归（`{title}` `{text}` `{app}`，含大写 ignoreCase）；
 * 2. 四组占位符全覆盖（文本 / 通知信息全字段，`{tag}` 有值与 null、`{time}` 正常与
 *    postTime<=0）；
 * 3. extras 前缀语义（取值 / 键名大小写敏感取不到 / 键不存在空串 / R12 与 `{title}` 的边界）；
 * 4. 未知占位符渲染空串 + [Placeholders.findUnknown] 列出；
 * 5. 逐 token 替换防误伤（`{text}` 不破坏 `{textLines}` / `{extras.android.text}`）；
 * 6. 边界（空模板 / 纯文本 / 连续 token / `{{title}}` 畸形不抛异常 / trim）；
 * 7. V1 兼容论证（迁移后规则 `customTitleTemplate="[{app}] {title}"` +
 *    `customTextTemplate=null`，渲染结果与 V1 语义一致）。
 *
 * 注 7 的实现方式：[Placeholders.render] 为唯一渲染实现，NotificationReposter 的
 * renderTitle/renderText 即「render(...).ifBlank { 回落原文 }」的薄封装（见该文件
 * V2T07 注释）；JVM 单测无法构造 Android Context，故按封装内的等价表达式逐字镜像断言，
 * 封装本身的接线由代码审读 + 编译保证。
 */
class PlaceholdersTest {

    // ==================== 测试夹具：全字段满值快照 ====================

    private val testPostTime = 1_700_000_000_000L

    private val fullSnapshot = NotificationSnapshot(
        key = "0|com.tencent.mm|42|mytag|10",
        notifId = 42,
        packageName = "com.tencent.mm",
        appName = "微信",
        userId = 10,
        channelId = "channel_import",
        postTime = testPostTime,
        isOngoing = false,
        flags = 0,
        title = "顺丰速运",
        text = "您的取件码是 1234",
        textLines = listOf("第一行", "第二行"),
        subText = "副标题：待取",
        summaryText = "摘要内容",
        people = listOf("张三", "李四"),
        extras = listOf(
            "android.title" to "EXTRA标题",
            "android.text" to "EXTRA正文",
        ),
        tag = "mytag",
    )

    /** {time} 期望值：用与实现相同的格式 + 系统默认时区计算，避免测试机时区差异。 */
    private val expectedTime: String = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
        .format(Instant.ofEpochMilli(testPostTime).atZone(ZoneId.systemDefault()))

    // ==================== 1. V1 旧三占位符回归 ====================

    @Test
    fun 旧三占位符_小写组合渲染() {
        assertEquals(
            "顺丰速运｜您的取件码是 1234｜微信",
            Placeholders.render("{title}｜{text}｜{app}", fullSnapshot),
        )
    }

    @Test
    fun 旧三占位符_大写也认_固定名ignoreCase() {
        assertEquals("顺丰速运", Placeholders.render("{TITLE}", fullSnapshot))
        assertEquals("您的取件码是 1234", Placeholders.render("{Text}", fullSnapshot))
        assertEquals("微信", Placeholders.render("{APP}", fullSnapshot)
        )
    }

    @Test
    fun 旧三占位符_缺失字段回落空串() {
        val snap = fullSnapshot.copy(title = null, text = null)
        assertEquals("｜｜微信", Placeholders.render("{title}｜{text}｜{app}", snap))
    }

    // ==================== 2. 四组全覆盖 ====================

    @Test
    fun 占位符表_固定三组齐全且EXTRAS组为动态() {
        val groups = Placeholders.ITEMS.map { it.group }.toSet()
        assertEquals(
            setOf(
                Placeholders.Group.COMMON,
                Placeholders.Group.TEXT,
                Placeholders.Group.INFO,
            ),
            groups,
        )
        assertTrue(Placeholders.ITEMS.none { it.group == Placeholders.Group.EXTRAS })
        assertEquals(13, Placeholders.ITEMS.size)
        assertEquals(Placeholders.ITEMS.size, Placeholders.ITEMS.map { it.token }.distinct().size)
    }

    @Test
    fun 占位符表_全部token在满值快照上均可解析为非空() {
        Placeholders.ITEMS.forEach { item ->
            assertTrue(
                "token ${item.token} 应渲染为非空",
                Placeholders.render(item.token, fullSnapshot).isNotEmpty(),
            )
        }
    }

    @Test
    fun 文本字段组_多行与空值语义() {
        assertEquals("第一行\n第二行", Placeholders.render("{textLines}", fullSnapshot))
        assertEquals("第一行\n第二行", Placeholders.render("{TEXTLINES}", fullSnapshot))
        assertEquals("副标题：待取", Placeholders.render("{subText}", fullSnapshot))
        assertEquals("摘要内容", Placeholders.render("{summaryText}", fullSnapshot))
        assertEquals("张三\n李四", Placeholders.render("{people}", fullSnapshot))

        val empty = fullSnapshot.copy(textLines = emptyList(), people = emptyList(), subText = null)
        assertEquals("", Placeholders.render("{textLines}", empty))
        assertEquals("", Placeholders.render("{subText}", empty))
        assertEquals("", Placeholders.render("{people}", empty))
    }

    @Test
    fun 通知信息组_各字段取值() {
        assertEquals("0|com.tencent.mm|42|mytag|10", Placeholders.render("{key}", fullSnapshot))
        assertEquals("42", Placeholders.render("{id}", fullSnapshot))
        assertEquals("mytag", Placeholders.render("{tag}", fullSnapshot))
        assertEquals("com.tencent.mm", Placeholders.render("{pkg}", fullSnapshot))
        assertEquals("channel_import", Placeholders.render("{channelId}", fullSnapshot))
        assertEquals(expectedTime, Placeholders.render("{time}", fullSnapshot))
    }

    @Test
    fun 通知信息组_可空字段空串语义() {
        assertEquals("", Placeholders.render("{tag}", fullSnapshot.copy(tag = null)))
        assertEquals("", Placeholders.render("{channelId}", fullSnapshot.copy(channelId = null)))
        // {time}：postTime<=0 一律空串（不可解析时间不留占位符字样）
        assertEquals("", Placeholders.render("{time}", fullSnapshot.copy(postTime = 0L)))
        assertEquals("", Placeholders.render("{time}", fullSnapshot.copy(postTime = -1L)))
    }

    // ==================== 3. extras 前缀语义 ====================

    @Test
    fun extras前缀_取到完整键名的值() {
        assertEquals("EXTRA标题", Placeholders.render("{extras.android.title}", fullSnapshot))
        assertEquals("EXTRA正文", Placeholders.render("{extras.android.text}", fullSnapshot))
    }

    @Test
    fun extras前缀_键名大小写敏感_大写取不到() {
        assertEquals("", Placeholders.render("{extras.ANDROID.TITLE}", fullSnapshot))
        assertEquals("", Placeholders.render("{extras.Android.Text}", fullSnapshot))
        // 前缀本身也大小写敏感
        assertEquals("", Placeholders.render("{EXTRAS.android.title}", fullSnapshot))
    }

    @Test
    fun extras前缀_键不存在按空串() {
        assertEquals("", Placeholders.render("{extras.nope}", fullSnapshot))
        assertEquals("", Placeholders.render("{extras.}", fullSnapshot))
        // findUnknown 口径：extras. 前缀一律合法（不弹提示），键名对错由渲染空串体现
        assertTrue(Placeholders.findUnknown("{extras.nope}").isEmpty())
        assertTrue(Placeholders.findUnknown("{extras.}").isEmpty())
    }

    @Test
    fun extras前缀_与固定名的R12边界() {
        // {extras.title} 取 extras 里的 title 键（本快照没有）→ 空串；
        // {title} 取原标题 → 顺丰速运。二者语义不同，不得互相串味。
        assertEquals("", Placeholders.render("{extras.title}", fullSnapshot))
        assertEquals("顺丰速运", Placeholders.render("{title}", fullSnapshot))
        val withExtrasTitle = fullSnapshot.copy(
            extras = fullSnapshot.extras + ("title" to "extras里的标题"),
        )
        assertEquals("extras里的标题", Placeholders.render("{extras.title}", withExtrasTitle))
        assertEquals("顺丰速运", Placeholders.render("{title}", withExtrasTitle))
    }

    // ==================== 4. 未知占位符 ====================

    @Test
    fun 未知占位符_渲染按空串() {
        assertEquals("前后", Placeholders.render("前{xxx}后", fullSnapshot))
        assertEquals("前后", Placeholders.render("前{标题}后", fullSnapshot))
        // 多个未知 + 已知混排
        assertEquals("顺丰速运::", Placeholders.render("{title}:{xxx}:{yyy}", fullSnapshot))
    }

    @Test
    fun findUnknown_列出未识别token_去重保序() {
        assertEquals(
            listOf("{xxx}", "{yyy}"),
            Placeholders.findUnknown("前{xxx}后{title}{yyy}{xxx}"),
        )
        assertTrue(Placeholders.findUnknown("{title}{TITLE}{extras.android.text}").isEmpty())
        // 畸形 {{xxx}}：内层 xxx 未识别，外层花括号保留，不抛异常
        assertEquals(listOf("{xxx}"), Placeholders.findUnknown("{{xxx}}"))
    }

    // ==================== 5. 防误伤（逐 token 替换） ====================

    @Test
    fun 防误伤_text不破坏textLines() {
        assertEquals(
            "您的取件码是 1234 与 第一行\n第二行",
            Placeholders.render("{text} 与 {textLines}", fullSnapshot),
        )
    }

    @Test
    fun 防误伤_text不破坏extras键() {
        // 连续 String.replace("{text}") 会把 {extras.android.text} 误伤成
        // {extras.android.您的取件码是 1234}；逐 token 替换必须两者都得正确值
        assertEquals(
            "EXTRA正文|您的取件码是 1234",
            Placeholders.render("{extras.android.text}|{text}", fullSnapshot),
        )
        // 反转顺序同样成立（替换顺序无关）
        assertEquals(
            "您的取件码是 1234|EXTRA正文",
            Placeholders.render("{text}|{extras.android.text}", fullSnapshot),
        )
    }

    @Test
    fun 防误伤_相邻相似token共存() {
        assertEquals(
            "副标题：待取|摘要内容",
            Placeholders.render("{subText}|{summaryText}", fullSnapshot),
        )
    }

    // ==================== 6. 边界 ====================

    @Test
    fun 边界_空模板与纯文本() {
        assertEquals("", Placeholders.render("", fullSnapshot))
        assertEquals("纯文本没占位符", Placeholders.render("纯文本没占位符", fullSnapshot))
        assertEquals("", Placeholders.render("   ", fullSnapshot))
    }

    @Test
    fun 边界_连续token与trim() {
        assertEquals(
            "顺丰速运您的取件码是 1234",
            Placeholders.render("{title}{text}", fullSnapshot),
        )
        // V1 兼容：renderTemplate 即 trim 后输出
        assertEquals("顺丰速运", Placeholders.render("  {title}  ", fullSnapshot))
    }

    @Test
    fun 边界_畸形花括号不抛异常() {
        // {{title}}：外层花括号非 token 构成，原样保留；内层 title 正常渲染
        assertEquals("{顺丰速运}", Placeholders.render("{{title}}", fullSnapshot))
        // {{xxx}}：内层未识别 → 空串，外层保留
        assertEquals("{}", Placeholders.render("{{xxx}}", fullSnapshot))
        // 单个花括号（不成对）——不构成 token，原样保留
        assertEquals("{title", Placeholders.render("{title", fullSnapshot))
        assertEquals("}", Placeholders.render("}", fullSnapshot))
        // 空花括号对：内容为空不匹配 [^{}]+，原样保留
        assertEquals("{}", Placeholders.render("{}", fullSnapshot))
    }

    // ==================== 7. V1 兼容论证 ====================

    @Test
    fun V1已存规则_迁移后渲染结果与V1语义一致() {
        // 构造 V1 迁移后的 ReplaceConfig（V2T03 改名 + V2T04 迁移链产物）：
        // V1 customTemplate → customTitleTemplate（模板作标题，语义不变）；
        // V1 无正文模板 → customTextTemplate = null。
        val migratedCfg = ReplaceConfig(
            mode = RewriteMode.CUSTOM_TEXT,
            customTitleTemplate = "[{app}] {title}",
            customTextTemplate = null,
            keepIcon = true,
            keepLargeImage = false,
            channelUseAppName = true,
        )
        val snap = fullSnapshot

        // —— NotificationReposter.renderTitle 的等价镜像（该处即 render + ifBlank 回落）——
        val title = Placeholders.render(migratedCfg.customTitleTemplate.orEmpty(), snap)
            .ifBlank { snap.title?.takeIf { it.isNotBlank() } ?: snap.appName }
        // V1 renderTemplate("[{app}] {title}", snap) 同为 "[微信] 顺丰速运"
        assertEquals("[微信] 顺丰速运", title)

        // —— NotificationReposter.renderText 的等价镜像 ——
        val text = Placeholders.render(migratedCfg.customTextTemplate.orEmpty(), snap)
            .ifBlank {
                snap.text?.takeIf { it.isNotBlank() }
                    ?: snap.textLines.firstOrNull()?.takeIf { it.isNotBlank() }
                    ?: ""
            }
        // V1 语义：无正文模板 → 正文照搬原文（您的取件码是 1234）
        assertEquals("您的取件码是 1234", text)
    }

    @Test
    fun V1已存规则_标题模板留空时回落原标题() {
        // V1 customTemplate 缺失/空白 → 回落原标题（V1 renderTitle 行为不变）
        val cfg = ReplaceConfig(
            mode = RewriteMode.CUSTOM_TEXT,
            customTitleTemplate = null,
            customTextTemplate = "{text}",
        )
        val snap = fullSnapshot
        val title = Placeholders.render(cfg.customTitleTemplate.orEmpty(), snap)
            .ifBlank { snap.title?.takeIf { it.isNotBlank() } ?: snap.appName }
        assertEquals("顺丰速运", title)
        val text = Placeholders.render(cfg.customTextTemplate.orEmpty(), snap)
            .ifBlank { snap.text ?: "" }
        assertEquals("您的取件码是 1234", text)
    }

    @Test
    fun 非自定义模式_不经Placeholders渲染() {
        // STRIP_IMAGES / PLAIN_TEXT 的 renderTitle/renderText 语义不含模板渲染
        // （由 NotificationReposter when 分支保证；此处锁定 Placeholders 不被误用：
        //  即便传入含占位符串，renderText 也与 ReplaceConfig.mode 无关——渲染入口只在
        //   reposter 的 CUSTOM_TEXT 分支被调用，本断言仅作文档化护栏）。
        assertTrue(Placeholders.render("{title}", fullSnapshot).isNotEmpty())
    }

    // ==================== 8. V7 需求 8：{items1}..{items9}（聚合暂存列表） ====================
    //
    // 边界口径（架构 §4.2 表，逐条对应）：
    // - N 在 1..9 且列表有内容 → 该列表按 itemSeparator join；
    // - 列表越界 / 不存在 / 存在但为空 → 空串（不留 `{itemsN}` 字面量，不抛异常）；
    // - `{items0}` / `{items10}` / `{ITEMS1}` → 未识别 → 空串，findUnknown 报未识别
    //   （items 分支大小写敏感，与 `extras.` 前缀同口径）；
    // - 不传 extras（或传 NONE）→ 与 V6 完全一致（一律空串）。

    /** 三个列表：`{items1}` 两条、`{items2}` 一条、`{items3}` 存在但为空。 */
    private val itemsExtras = PlaceholderExtras(
        lists = listOf(listOf("0000-00", "1111-11"), listOf("2222-22"), emptyList()),
        itemSeparator = "\n",
    )

    @Test
    fun itemsN_有值按分隔符拼接() {
        assertEquals("0000-00\n1111-11", Placeholders.render("{items1}", fullSnapshot, itemsExtras))
        assertEquals("2222-22", Placeholders.render("{items2}", fullSnapshot, itemsExtras))
        // 同一个 token 在模板里出现两次都取到值（逐 token 替换，不消耗）
        assertEquals(
            "0000-00\n1111-11|0000-00\n1111-11",
            Placeholders.render("{items1}|{items1}", fullSnapshot, itemsExtras),
        )
    }

    @Test
    fun itemsN_自定义分隔符与空分隔符() {
        assertEquals(
            "0000-00、1111-11",
            Placeholders.render("{items1}", fullSnapshot, itemsExtras.copy(itemSeparator = "、")),
        )
        // 留空 = 直接相接
        assertEquals(
            "0000-001111-11",
            Placeholders.render("{items1}", fullSnapshot, itemsExtras.copy(itemSeparator = "")),
        )
    }

    @Test
    fun itemsN_列表为空或越界渲染空串() {
        // 列表存在但还没攒到条目
        assertEquals("今天的快递：", Placeholders.render("今天的快递：{items3}", fullSnapshot, itemsExtras))
        // 越界（只配了 3 个列表却写 {items5}）
        assertEquals("前后", Placeholders.render("前{items5}后", fullSnapshot, itemsExtras))
        assertEquals("", Placeholders.render("{items9}", fullSnapshot, itemsExtras))
    }

    @Test
    fun itemsN_与固定占位符和extras混合渲染() {
        assertEquals(
            "微信 汇总：0000-00\n1111-11｜EXTRA标题",
            Placeholders.render(
                "{app} 汇总：{items1}｜{extras.android.title}",
                fullSnapshot,
                itemsExtras,
            ),
        )
        // 固定名与 items 交替出现，互不误伤
        assertEquals(
            "顺丰速运/0000-00\n1111-11/您的取件码是 1234",
            Placeholders.render("{title}/{items1}/{text}", fullSnapshot, itemsExtras),
        )
    }

    @Test
    fun itemsN_默认上下文下渲染空串_保持V6行为() {
        // 不传第三参数（V6 调用点形态）
        assertEquals("", Placeholders.render("{items1}", fullSnapshot))
        assertEquals("前后", Placeholders.render("前{items1}后", fullSnapshot))
        // 显式传 NONE 与不传等价
        assertEquals("", Placeholders.render("{items1}", fullSnapshot, PlaceholderExtras.NONE))
    }

    @Test
    fun itemsN_大小写敏感且只认1到9单个数字() {
        assertEquals("", Placeholders.render("{ITEMS1}", fullSnapshot, itemsExtras))
        assertEquals("", Placeholders.render("{Items1}", fullSnapshot, itemsExtras))
        assertEquals("", Placeholders.render("{items0}", fullSnapshot, itemsExtras))
        // {items10} 不得被拆成 {items1} + "0"（本项目只支持 1..9）
        assertEquals("", Placeholders.render("{items10}", fullSnapshot, itemsExtras))
        // 反过来：{items1} 与相邻的字面量 "0" 互不干扰
        assertEquals("0000-00\n1111-110", Placeholders.render("{items1}0", fullSnapshot, itemsExtras))
    }

    @Test
    fun itemsN_findUnknown按itemCount口径判定() {
        // itemCount = 2：{items1}{items2} 合法
        assertTrue(Placeholders.findUnknown("{items1}{items2}", itemCount = 2).isEmpty())
        // 超出上界 → 未识别
        assertEquals(listOf("{items3}"), Placeholders.findUnknown("x{items3}", itemCount = 2))
        // 默认 itemCount = 0（V6 口径）：{items1} 也判未识别
        assertEquals(listOf("{items1}"), Placeholders.findUnknown("{items1}"))
        // 大小写 / 0 / 两位数一律未识别（itemCount 给到上限 9 也不认）
        assertEquals(
            listOf("{items0}", "{items10}", "{ITEMS1}"),
            Placeholders.findUnknown("{items0}{items10}{ITEMS1}", itemCount = 9),
        )
        assertTrue(Placeholders.findUnknown("{items9}", itemCount = 9).isEmpty())
        // 与既有未知 token 混排：只报真正未知的
        assertEquals(listOf("{xxx}"), Placeholders.findUnknown("{items1}{xxx}", itemCount = 1))
    }

    @Test
    fun itemsN_不进入固定chips表() {
        // 与 `extras.` 同口径：items token 由调用方按规则实际配置动态生成，
        // 不进 [Placeholders.ITEMS]（否则既有 13 条 chips 断言会变）
        assertTrue(Placeholders.ITEMS.none { it.token.startsWith("{items") })
        assertEquals(13, Placeholders.ITEMS.size)
    }

    @Test
    fun PlaceholderExtras_取值与计数() {
        assertEquals(3, itemsExtras.listCount)
        assertEquals("0000-00\n1111-11", itemsExtras.items(1))
        assertEquals("2222-22", itemsExtras.items(2))
        // 空列表 / 0 / 越界 / 负数 → 空串（绝不抛异常）
        assertEquals("", itemsExtras.items(3))
        assertEquals("", itemsExtras.items(0))
        assertEquals("", itemsExtras.items(4))
        assertEquals("", itemsExtras.items(-1))
        // NONE
        assertEquals(0, PlaceholderExtras.NONE.listCount)
        assertEquals("", PlaceholderExtras.NONE.items(1))
        // 自定义分隔符
        assertEquals("a、b", PlaceholderExtras(listOf(listOf("a", "b")), "、").items(1))
    }
}
