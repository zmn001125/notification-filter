package com.local.notificationfilter.core.aggregate

import com.local.notificationfilter.core.model.ExtractorConfig
import com.local.notificationfilter.core.model.MatchField
import com.local.notificationfilter.core.model.NotificationSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 条目提取单测（V7 需求 8，架构 §7.2）。
 *
 * 覆盖：文档 §7.2 用例表逐条 + 边界（空正则 / 非法正则 / 超长输入 / 捕获组越界 /
 * 一条通知多个匹配 / 去重 / limit / 字段分隔符转义 / 空白丢弃 / 幂等）。
 */
class ItemExtractorTest {

    /** 取件码正则（文档用例原文）。 */
    private val pickupPattern = "取件码[：:]?\\s*(\\d{4})-(\\d{2})"

    @Before
    fun setUp() {
        ItemExtractor.resetStats()
    }

    private fun snapshot(
        title: String? = null,
        text: String? = null,
        extras: List<Pair<String, String>> = emptyList(),
        textLines: List<String> = emptyList(),
    ): NotificationSnapshot = NotificationSnapshot(
        key = "0|com.sf.express|1|tag|0",
        notifId = 1,
        packageName = "com.sf.express",
        appName = "顺丰速运",
        title = title,
        text = text,
        textLines = textLines,
        extras = extras,
    )

    private fun cfg(
        pattern: String = pickupPattern,
        captureFormat: String = "\$0",
        fieldIds: List<String> = listOf(MatchField.TEXT.name),
        fieldSeparator: String = "\n",
        ignoreCase: Boolean = true,
        dedupe: Boolean = true,
        limit: Int = 10,
        enabled: Boolean = true,
    ): ExtractorConfig = ExtractorConfig(
        listIndex = 1,
        name = "取件码",
        fieldIds = fieldIds,
        fieldSeparator = fieldSeparator,
        pattern = pattern,
        ignoreCase = ignoreCase,
        captureFormat = captureFormat,
        dedupe = dedupe,
        limit = limit,
        enabled = enabled,
    )

    // ==================== 文档 §7.2 用例表 ====================

    @Test
    fun `captureFormat 用捕获组拼装`() {
        val result = ItemExtractor.extract(
            snapshot(text = "您的取件码：0000-00，请及时领取"),
            cfg(captureFormat = "\$1-\$2"),
        )
        assertEquals("0000-00", result)
    }

    @Test
    fun `captureFormat 用整段匹配`() {
        val result = ItemExtractor.extract(
            snapshot(text = "您的取件码：0000-00，请及时领取"),
            cfg(captureFormat = "\$0"),
        )
        assertEquals("取件码：0000-00", result)
    }

    @Test
    fun `多字段拼接后可跨字段抽取`() {
        val result = ItemExtractor.extract(
            snapshot(title = "取件码", text = "1111-11"),
            cfg(
                pattern = "取件码\\s*(\\d{4}-\\d{2})",
                captureFormat = "\$1",
                fieldIds = listOf(MatchField.TITLE.name, MatchField.TEXT.name),
                fieldSeparator = "\n",
            ),
        )
        assertEquals("1111-11", result)
    }

    @Test
    fun `extras 字段可参与提取`() {
        val result = ItemExtractor.extract(
            snapshot(extras = listOf("code" to "2222-22")),
            cfg(
                pattern = "(\\d{4}-\\d{2})",
                captureFormat = "\$1",
                fieldIds = listOf("extras:code"),
            ),
        )
        assertEquals("2222-22", result)
    }

    @Test
    fun `非法正则不抛异常并返回空`() {
        val result = ItemExtractor.extractAll(
            snapshot(text = "您的取件码：0000-00"),
            cfg(pattern = "((("),
        )
        assertTrue(result.isEmpty())
        assertFalse(ItemExtractor.isPatternValid("((("))
    }

    @Test
    fun `未匹配返回空`() {
        assertTrue(
            ItemExtractor.extractAll(snapshot(text = "今天没有快递"), cfg()).isEmpty(),
        )
    }

    @Test
    fun `捕获组越界渲染为空串不抛异常`() {
        // 正则只有 2 组，格式用 $5：$1-$5 → "0000-"（$5 空串），trim 后非空
        val result = ItemExtractor.extract(
            snapshot(text = "取件码：0000-00"),
            cfg(captureFormat = "\$1-\$5"),
        )
        assertEquals("0000-", result)
    }

    @Test
    fun `捕获组越界且整体为空时返回空`() {
        // 只用 $5（不存在）→ 拼装结果为空串 → 该匹配被丢弃
        assertTrue(
            ItemExtractor.extractAll(
                snapshot(text = "取件码：0000-00"),
                cfg(captureFormat = "\$5"),
            ).isEmpty(),
        )
    }

    @Test
    fun `ignoreCase 为真时大小写不敏感`() {
        val result = ItemExtractor.extract(
            snapshot(text = "CODE 3333-33"),
            cfg(pattern = "code\\s*(\\d{4}-\\d{2})", captureFormat = "\$1", ignoreCase = true),
        )
        assertEquals("3333-33", result)
    }

    @Test
    fun `ignoreCase 为假时大小写敏感`() {
        assertTrue(
            ItemExtractor.extractAll(
                snapshot(text = "CODE 3333-33"),
                cfg(pattern = "code\\s*(\\d{4}-\\d{2})", ignoreCase = false),
            ).isEmpty(),
        )
    }

    @Test
    fun `同一通知重复调用结果相同（幂等）`() {
        val snap = snapshot(text = "取件码：0000-00")
        val config = cfg(captureFormat = "\$1-\$2")
        assertEquals(
            ItemExtractor.extractAll(snap, config),
            ItemExtractor.extractAll(snap, config),
        )
    }

    // ==================== 本次新增：findAll（全部匹配） ====================

    @Test
    fun `一条通知里的两个取件码都能抽出`() {
        val result = ItemExtractor.extractAll(
            snapshot(text = "取件码 0000-00，另有取件码 1111-11，请一起领取"),
            cfg(captureFormat = "\$1-\$2"),
        )
        assertEquals(listOf("0000-00", "1111-11"), result)
    }

    @Test
    fun `extract 只取第一条`() {
        val snap = snapshot(text = "取件码 0000-00，另有取件码 1111-11")
        val config = cfg(captureFormat = "\$1-\$2")
        assertEquals("0000-00", ItemExtractor.extract(snap, config))
        assertEquals(listOf("0000-00", "1111-11"), ItemExtractor.extractAll(snap, config))
    }

    @Test
    fun `同一通知内相同条目在 dedupe 为真时只留一条`() {
        val result = ItemExtractor.extractAll(
            snapshot(text = "取件码 0000-00，取件码 0000-00"),
            cfg(captureFormat = "\$1-\$2", dedupe = true),
        )
        assertEquals(listOf("0000-00"), result)
    }

    @Test
    fun `dedupe 为假时保留重复条目`() {
        val result = ItemExtractor.extractAll(
            snapshot(text = "取件码 0000-00，取件码 0000-00"),
            cfg(captureFormat = "\$1-\$2", dedupe = false),
        )
        assertEquals(listOf("0000-00", "0000-00"), result)
    }

    @Test
    fun `超过 limit 只保留前 limit 条`() {
        val result = ItemExtractor.extractAll(
            snapshot(text = "取件码 0000-00，取件码 1111-11，取件码 2222-22"),
            cfg(captureFormat = "\$1-\$2", limit = 2),
        )
        assertEquals(listOf("0000-00", "1111-11"), result)
    }

    // ==================== 边界 ====================

    @Test
    fun `空正则不参与提取`() {
        assertTrue(
            ItemExtractor.extractAll(snapshot(text = "任意内容"), cfg(pattern = "")).isEmpty(),
        )
        assertTrue(
            ItemExtractor.extractAll(snapshot(text = "任意内容"), cfg(pattern = "   ")).isEmpty(),
        )
    }

    @Test
    fun `enabled 为假时不提取`() {
        assertTrue(
            ItemExtractor.extractAll(snapshot(text = "取件码：0000-00"), cfg(enabled = false))
                .isEmpty(),
        )
    }

    @Test
    fun `limit 非法时不提取`() {
        assertTrue(
            ItemExtractor.extractAll(snapshot(text = "取件码：0000-00"), cfg(limit = 0)).isEmpty(),
        )
        assertTrue(
            ItemExtractor.extractAll(snapshot(text = "取件码：0000-00"), cfg(limit = -3)).isEmpty(),
        )
    }

    @Test
    fun `captureFormat 留空等价整段匹配`() {
        val result = ItemExtractor.extract(
            snapshot(text = "您的取件码：0000-00"),
            cfg(captureFormat = ""),
        )
        assertEquals("取件码：0000-00", result)
    }

    @Test
    fun `captureFormat 里的美元符后非数字时原样保留`() {
        // 格式 "价格$100"：`$` 后是 '1'（数字）→ 会被当成 $1 捕获组，这是文档写死的语法；
        // 故这里验证 `$a`（非数字）被保留
        val result = ItemExtractor.extract(
            snapshot(text = "取件码 0000-00"),
            cfg(pattern = "(\\d{4}-\\d{2})", captureFormat = "\$a\$1"),
        )
        assertEquals("\$a0000-00", result)
    }

    @Test
    fun `匹配结果只有空白时被丢弃`() {
        // "a b" 里的空格能被 \s+ 匹配到，但 trim 后为空 → 该条被丢弃
        assertTrue(
            ItemExtractor.extractAll(snapshot(text = "a b"), cfg(pattern = "\\s+")).isEmpty(),
        )
    }

    @Test
    fun `匹配结果被 trim`() {
        val result = ItemExtractor.extract(
            snapshot(text = "  取件码 0000-00  "),
            cfg(pattern = "\\s*取件码\\s*(\\d{4}-\\d{2})\\s*", captureFormat = "\$0"),
        )
        assertEquals("取件码 0000-00", result)
    }

    @Test
    fun `fieldSeparator 支持字面量转义`() {
        // fieldSeparator 传字面量 "\\n"（反斜杠 + n），经 EscapeCodec 解析成真实换行
        val result = ItemExtractor.extract(
            snapshot(title = "取件码", text = "1111-11"),
            cfg(
                pattern = "取件码\\s*(\\d{4}-\\d{2})",
                captureFormat = "\$1",
                fieldIds = listOf(MatchField.TITLE.name, MatchField.TEXT.name),
                fieldSeparator = "\\n",
            ),
        )
        assertEquals("1111-11", result)
    }

    @Test
    fun `空白字段不参与拼接`() {
        val text = ItemExtractor.inputText(
            snapshot(title = "取件码", text = null),
            cfg(fieldIds = listOf(MatchField.TITLE.name, MatchField.TEXT.name)),
        )
        assertEquals("取件码", text)
    }

    @Test
    fun `超长输入被截断且末尾内容不被提取`() {
        val prefix = "x".repeat(ItemExtractor.MAX_INPUT_CHARS)
        val snap = snapshot(text = prefix + "取件码：9999-99")
        val result = ItemExtractor.extractAll(snap, cfg(captureFormat = "\$1-\$2"))
        assertTrue("超出 8KB 的部分应被丢弃", result.isEmpty())
        assertEquals(1L, ItemExtractor.truncatedInputCount)
    }

    @Test
    fun `未到上限时不记录截断`() {
        ItemExtractor.extractAll(snapshot(text = "取件码：0000-00"), cfg())
        assertEquals(0L, ItemExtractor.truncatedInputCount)
    }

    @Test
    fun `没有字段文本时返回空`() {
        assertTrue(ItemExtractor.extractAll(snapshot(), cfg()).isEmpty())
        assertNull(ItemExtractor.extract(snapshot(), cfg()))
    }

    @Test
    fun `非法正则判定与提取口径一致`() {
        assertTrue(ItemExtractor.isPatternValid("(\\d{4})"))
        assertFalse(ItemExtractor.isPatternValid("["))
        assertFalse(ItemExtractor.isPatternValid(""))
    }

    @Test
    fun `正则编译缓存不影响重复提取结果`() {
        // V11.2 给 ItemExtractor 加了正则编译缓存：同一 (pattern, ignoreCase) 只编译一次。
        // 这条测试钉住「缓存命中路径与首次编译路径结果完全一致」，防止缓存改动带来语义漂移。
        val snap = snapshot(text = "取件码：1234-56")
        val first = ItemExtractor.extractAll(snap, cfg())
        val second = ItemExtractor.extractAll(snap, cfg())
        assertEquals(first, second)
        assertEquals(1, first.size)

        // 大小写口径不同的同一条正则必须各自独立（缓存的 key 含 ignoreCase）
        val lower = cfg(pattern = "abc", ignoreCase = true)
        val sensitive = cfg(pattern = "abc", ignoreCase = false)
        assertEquals(listOf("ABC"), ItemExtractor.extractAll(snapshot(text = "ABC"), lower))
        assertTrue(ItemExtractor.extractAll(snapshot(text = "ABC"), sensitive).isEmpty())
    }
}
