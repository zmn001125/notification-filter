package com.local.notificationfilter.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **即时提取**占位符 `{re1}`..`{re9}` 的单测（纯 JVM，无 Android 依赖）。
 *
 * 语义（见 [Placeholders] 类注释）：用规则第 N 个**激活**提取器对**当前这一条通知**
 * 跑一次正则，把抽到的全部条目按 `itemSeparator` 拼成一个字符串；
 * 与跨通知累积的 `{itemsN}` 完全不同，勿混淆。
 *
 * 覆盖：
 * 1. 基本渲染（一个提取器 + `$1` 捕获组）；
 * 2. 无匹配 → 空串；
 * 3. 多个匹配 → 按 `itemSeparator` 连接（含自定义分隔符 / `dedupe` / `limit`）；
 * 4. `{re10}` / `{RE1}` / 未配置的 `{re5}` → 空串；[Placeholders.findUnknown] 按
 *    `reCount` 口径报警；
 * 5. 非法正则 / 捕获组越界 → **不抛异常**，渲染空串；
 * 6. 回归：不传 `extract` 时 [Placeholders.render] 与改前逐字一致（`{itemsN}` 仍走 extras）。
 */
class PlaceholderExtractTest {

    // ==================== 测试夹具 ====================

    private fun snapshot(text: String, title: String? = "顺丰速运"): NotificationSnapshot =
        NotificationSnapshot(
            key = "0|com.sf.express|1|tag|0",
            notifId = 1,
            packageName = "com.sf.express",
            appName = "顺丰速运",
            title = title,
            text = text,
        )

    /** 一个「取件码」提取器：`listIndex` / 正则 / 捕获格式可调。 */
    private fun extractor(
        listIndex: Int = 1,
        name: String = "取件码",
        pattern: String = PICKUP_PATTERN,
        captureFormat: String = "\$1",
        dedupe: Boolean = true,
        limit: Int = 10,
        enabled: Boolean = true,
    ): ExtractorConfig = ExtractorConfig(
        listIndex = listIndex,
        name = name,
        fieldIds = listOf(MatchField.TEXT.name),
        fieldSeparator = "\n",
        pattern = pattern,
        ignoreCase = true,
        captureFormat = captureFormat,
        dedupe = dedupe,
        limit = limit,
        enabled = enabled,
    )

    private fun extractOf(
        snapshot: NotificationSnapshot,
        extractors: List<ExtractorConfig>,
        itemSeparator: String = "\n",
    ): PlaceholderExtract = PlaceholderExtract.of(snapshot, extractors, itemSeparator)

    private companion object {
        /** 6 位取件码（`取件码：123456` / `取件码是 123456` 都认）。 */
        const val PICKUP_PATTERN: String = "取件码[：:为是]?\\s*(\\d{6})"
    }

    // ==================== 1. 基本渲染 ====================

    @Test
    fun re1_基本渲染_取件码() {
        val snap = snapshot("您的取件码是 123456，请尽快取件")
        val extract = extractOf(snap, listOf(extractor()))

        assertEquals("123456", extract.value(1))
        assertEquals("码：123456", Placeholders.render("码：{re1}", snap, extract = extract))
    }

    @Test
    fun re1_整段匹配_默认captureFormat() {
        val snap = snapshot("取件码：654321 已到达驿站")
        val extract = extractOf(snap, listOf(extractor(captureFormat = "\$0")))
        // $0 = 整段匹配（含「取件码：」前缀）
        assertEquals("取件码：654321", Placeholders.render("{re1}", snap, extract = extract))
    }

    @Test
    fun reN_多个提取器按listIndex各自取值() {
        val snap = snapshot("取件码：111111，运单号 SF1234567890")
        val extract = extractOf(
            snap,
            listOf(
                extractor(listIndex = 1, pattern = PICKUP_PATTERN),
                extractor(listIndex = 3, name = "运单号", pattern = "SF(\\d{10})"),
            ),
        )
        assertEquals("111111", extract.value(1))
        assertEquals("1234567890", extract.value(3))
        assertEquals(
            "取件码 111111 / 运单 1234567890",
            Placeholders.render("取件码 {re1} / 运单 {re3}", snap, extract = extract),
        )
    }

    @Test
    fun reN_与固定占位符和extras混排_互不误伤() {
        val snap = snapshot("您的取件码是 123456").copy(
            extras = listOf("android.title" to "EXTRA标题"),
        )
        val extract = extractOf(snap, listOf(extractor()))
        assertEquals(
            "顺丰速运|123456|您的取件码是 123456|EXTRA标题",
            Placeholders.render("{title}|{re1}|{text}|{extras.android.title}", snap, extract = extract),
        )
        // 同一个 token 出现两次都取到值（逐 token 替换，不消耗）
        assertEquals("123456+123456", Placeholders.render("{re1}+{re1}", snap, extract = extract))
    }

    // ==================== 2. 无匹配 → 空串 ====================

    @Test
    fun re1_无匹配渲染空串() {
        val snap = snapshot("您有一个快递已签收")
        val extract = extractOf(snap, listOf(extractor()))

        assertEquals("", extract.value(1))
        assertEquals("码：", Placeholders.render("码：{re1}", snap, extract = extract))
        // 不留 {re1} 字面量
        assertTrue(!Placeholders.render("码：{re1}", snap, extract = extract).contains("{re1}"))
    }

    @Test
    fun re1_提取器未启用或正则为空_渲染空串() {
        val snap = snapshot("您的取件码是 123456")
        assertTrue(extractOf(snap, listOf(extractor(enabled = false))).value(1).isEmpty())
        assertTrue(extractOf(snap, listOf(extractor(pattern = ""))).value(1).isEmpty())
        assertTrue(extractOf(snap, listOf(extractor(limit = 0))).value(1).isEmpty())
        assertTrue(extractOf(snap, emptyList()).value(1).isEmpty())
    }

    @Test
    fun re1_未配置的提取器号_渲染空串() {
        val snap = snapshot("您的取件码是 123456")
        // 只配了 listIndex = 1，却写 {re5}
        val extract = extractOf(snap, listOf(extractor(listIndex = 1)))
        assertTrue(extract.value(5).isEmpty())
        assertTrue(extract.value(9).isEmpty())
        // 未配置的号一律渲染空串；前后字面量原样保留（render 只在整体首尾 trim 空白）
        assertEquals("前后", Placeholders.render("前{re5}后", snap, extract = extract))
        assertEquals("前后", Placeholders.render("前{re9}后", snap, extract = extract))
    }

    // ==================== 3. 多个匹配 → 按分隔符连接 ====================

    @Test
    fun re1_多个匹配按默认换行连接() {
        val snap = snapshot("取件码：111111；另有取件码：222222")
        val extract = extractOf(snap, listOf(extractor()))
        assertEquals("111111\n222222", extract.value(1))
        assertEquals("111111\n222222", Placeholders.render("{re1}", snap, extract = extract))
    }

    @Test
    fun re1_多个匹配按自定义分隔符连接() {
        val snap = snapshot("取件码：111111；另有取件码：222222")
        assertEquals(
            "111111、222222",
            extractOf(snap, listOf(extractor()), itemSeparator = "、").value(1),
        )
        // 留空分隔符 = 直接相接
        assertEquals(
            "111111222222",
            extractOf(snap, listOf(extractor()), itemSeparator = "").value(1),
        )
        // 字面量 \n 与真实换行等价（EscapeCodec 解析，与 AggregationRenderer 同口径）
        assertEquals(
            "111111\n222222",
            extractOf(snap, listOf(extractor()), itemSeparator = "\\n").value(1),
        )
    }

    @Test
    fun re1_去重与条数上限生效() {
        val snap = snapshot("取件码：111111；取件码：111111；取件码：222222")
        // dedupe = true：重复只留一条
        assertEquals("111111\n222222", extractOf(snap, listOf(extractor())).value(1))
        // dedupe = false：保留全部
        assertEquals(
            "111111\n111111\n222222",
            extractOf(snap, listOf(extractor(dedupe = false))).value(1),
        )
        // limit = 2：截断
        assertEquals("111111\n222222", extractOf(snap, listOf(extractor(limit = 2))).value(1))
        assertEquals("111111", extractOf(snap, listOf(extractor(limit = 1))).value(1))
    }

    // ==================== 4. 大小写敏感 / 只认 1..9 / findUnknown ====================

    @Test
    fun reN_大小写敏感且只认1到9单个数字() {
        val snap = snapshot("您的取件码是 123456")
        val extract = extractOf(snap, listOf(extractor()))

        // 已配置的 {re1} 正常取到
        assertEquals("123456", Placeholders.render("{re1}", snap, extract = extract))
        // 大写 / 0 / 两位数一律未识别 → 空串
        assertEquals("", Placeholders.render("{RE1}", snap, extract = extract))
        assertEquals("", Placeholders.render("{Re1}", snap, extract = extract))
        assertEquals("", Placeholders.render("{re0}", snap, extract = extract))
        assertEquals("", Placeholders.render("{re10}", snap, extract = extract))
        // 反过来：{re1} 与相邻字面量 "0" 互不干扰
        assertEquals("1234560", Placeholders.render("{re1}0", snap, extract = extract))
    }

    @Test
    fun reN_findUnknown按reCount口径判定() {
        // 该规则只配了 1 个激活提取器 → reCount = 1
        assertEquals(emptyList<String>(), Placeholders.findUnknown("{re1}", reCount = 1))
        // 未配置的 {re5} → 报警
        assertEquals(listOf("{re5}"), Placeholders.findUnknown("x{re5}", reCount = 1))
        // 默认 reCount = 0（老调用点口径）：{re1} 也判未识别
        assertEquals(listOf("{re1}"), Placeholders.findUnknown("{re1}"))
        // 大小写 / 0 / 两位数一律未识别（reCount 给到上限 9 也不认）
        assertEquals(
            listOf("{re0}", "{re10}", "{RE1}"),
            Placeholders.findUnknown("{re0}{re10}{RE1}", reCount = 9),
        )
        assertEquals(emptyList<String>(), Placeholders.findUnknown("{re9}", reCount = 9))
        // 与既有未知 token 混排：只报真正未知的
        assertEquals(listOf("{xxx}"), Placeholders.findUnknown("{re1}{xxx}", reCount = 1))
    }

    @Test
    fun reN_findUnknown与itemsN互不干扰() {
        // 同一条规则：itemsCount = 2、reCount = 1
        assertEquals(
            emptyList<String>(),
            Placeholders.findUnknown("{items1}{items2}{re1}", itemCount = 2, reCount = 1),
        )
        assertEquals(
            listOf("{items3}", "{re2}"),
            Placeholders.findUnknown("{items3}{re2}", itemCount = 2, reCount = 1),
        )
    }

    @Test
    fun reN_不进入固定chips表_由extractTokens动态生成() {
        assertTrue(Placeholders.ITEMS.none { it.token.startsWith("{re") })
        assertEquals(13, Placeholders.ITEMS.size)

        // 激活提取器 → token → 显示名
        val tokens = Placeholders.extractTokens(
            AggregationConfig(
                extractors = listOf(
                    extractor(listIndex = 1),
                    extractor(listIndex = 2, pattern = ""),
                ),
            ),
        )
        // 第 2 个正则为空 = 未激活，不出 chip
        assertEquals(listOf("{re1}" to "取件码"), tokens)
        assertEquals(emptyList<Pair<String, String>>(), Placeholders.extractTokens(null))
        assertEquals(
            emptyList<Pair<String, String>>(),
            Placeholders.extractTokens(AggregationConfig(extractors = listOf(extractor(pattern = "")))),
        )
    }

    // ==================== 5. 非法正则不抛异常 ====================

    @Test
    fun re1_非法正则不抛异常_渲染空串() {
        val snap = snapshot("您的取件码是 123456")
        val extract = extractOf(snap, listOf(extractor(pattern = "(((")))

        assertEquals("", extract.value(1))
        assertEquals("码：", Placeholders.render("码：{re1}", snap, extract = extract))
    }

    @Test
    fun re1_捕获组越界不抛异常_渲染空串() {
        val snap = snapshot("您的取件码是 123456")
        // 正则只有一个捕获组却写 $5
        val extract = extractOf(snap, listOf(extractor(captureFormat = "\$5")))
        assertEquals("", extract.value(1))
        assertEquals("", Placeholders.render("{re1}", snap, extract = extract))
    }

    @Test
    fun re1_多个提取器中有一个写坏_其余照常() {
        val snap = snapshot("取件码：111111，运单号 SF1234567890")
        val extract = extractOf(
            snap,
            listOf(
                extractor(listIndex = 1, pattern = "[["), // 写坏的
                extractor(listIndex = 2, pattern = "SF(\\d{10})"),
            ),
        )
        assertEquals("", extract.value(1))
        assertEquals("1234567890", extract.value(2))
    }

    // ==================== 6. 回归：不传 extract 与改前一致 ====================

    @Test
    fun 不传extract时_reN渲染空串_与改前一致() {
        val snap = snapshot("您的取件码是 123456")
        // 老调用点形态：只传 template + snapshot
        assertEquals("", Placeholders.render("{re1}", snap))
        assertEquals("前后", Placeholders.render("前{re1}后", snap))
        // 显式 NONE 与不传等价
        assertEquals("", Placeholders.render("{re1}", snap, extract = PlaceholderExtract.NONE))
        // 老三样照样工作
        assertEquals("顺丰速运｜您的取件码是 123456", Placeholders.render("{title}｜{text}", snap))
    }

    @Test
    fun 不传extract时_itemsN仍走extras() {
        val snap = snapshot("您的取件码是 123456")
        val extras = PlaceholderExtras(
            lists = listOf(listOf("0000-00", "1111-11")),
            itemSeparator = "\n",
        )
        // items 分支不受新参数影响
        assertEquals("0000-00\n1111-11", Placeholders.render("{items1}", snap, extras))
        // 同时给 extras + extract：两者各取各的
        val extract = extractOf(snap, listOf(extractor()))
        assertEquals(
            "累积：0000-00\n1111-11｜即时：123456",
            Placeholders.render("累积：{items1}｜即时：{re1}", snap, extras, extract),
        )
    }

    @Test
    fun 空模板与畸形token_不抛异常() {
        val snap = snapshot("您的取件码是 123456")
        val extract = extractOf(snap, listOf(extractor()))
        assertEquals("", Placeholders.render("", snap, extract = extract))
        assertEquals("纯文本", Placeholders.render("纯文本", snap, extract = extract))
        // 畸形双花括号：外层不解析，内层 {re1} 照常被替换（与既有 {xxx} 口径一致）
        assertEquals("{123456}", Placeholders.render("{{re1}}", snap, extract = extract))
        assertEquals("{}", Placeholders.render("{}", snap, extract = extract))
    }

    // ==================== PlaceholderExtract.of 的 AggregationConfig 重载 ====================

    @Test
    fun of_AggregationConfig重载() {
        val snap = snapshot("取件码：111111；取件码：222222")
        val config = AggregationConfig(
            extractors = listOf(extractor(listIndex = 1)),
            itemSeparator = "、",
        )
        assertEquals("111111、222222", PlaceholderExtract.of(snap, config).value(1))
        // 无提取器 / null → NONE
        assertEquals(PlaceholderExtract.NONE, PlaceholderExtract.of(snap, null))
        assertEquals(
            PlaceholderExtract.NONE,
            PlaceholderExtract.of(snap, AggregationConfig()),
        )
        assertEquals(
            PlaceholderExtract.NONE,
            PlaceholderExtract.of(snap, AggregationConfig(extractors = listOf(extractor(pattern = "")))),
        )
    }

    @Test
    fun 多字段拼接输入_按fieldSeparator连接后再匹配() {
        val snap = snapshot(text = "123456", title = "取件码：")
        // 标题 + 正文 拼成「取件码：\n123456」后再匹配（跨字段正则）
        val cfg = extractor(pattern = "取件码：\\s*(\\d{6})", captureFormat = "\$1").copy(
            fieldIds = listOf(MatchField.TITLE.name, MatchField.TEXT.name),
        )
        assertEquals("123456", extractOf(snap, listOf(cfg)).value(1))
    }
}
