package com.local.notificationfilter.core.ruleengine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Android 正则兼容层单测（V11.4）。
 *
 * 为什么必须测：Android 的正则引擎是 ICU4C，**后行断言里不允许 `*` `+` `{n,}`**
 * （抛 `Look-behind group does not have an obvious maximum length`），
 * 而桌面 JDK 13+ 允许——所以这类失败**在 JVM 单测里根本复现不出来**，只能在真机上炸。
 * 这里直接对「改写函数」与「诊断文案」做纯函数断言，绕开引擎差异。
 */
class RegexCompatTest {

    // ==================== 无上限量词识别 ====================

    @Test
    fun `识别后行断言里的无上限量词`() {
        assertTrue(RegexCompat.hasUnboundedQuantifier("取件码[：:]?\\s*"))
        assertTrue(RegexCompat.hasUnboundedQuantifier("a+"))
        assertTrue(RegexCompat.hasUnboundedQuantifier("a{2,}"))
        assertTrue(RegexCompat.hasUnboundedQuantifier("(?:ab)+c"))
    }

    @Test
    fun `有限量词与转义字符不算无上限`() {
        assertFalse(RegexCompat.hasUnboundedQuantifier("取件码[：:]?"))
        assertFalse(RegexCompat.hasUnboundedQuantifier("a{2,4}"))
        assertFalse(RegexCompat.hasUnboundedQuantifier("\\*")) // 转义的字面星号
        assertFalse(RegexCompat.hasUnboundedQuantifier("[a*+]")) // 字符类里的星号是字面量
        assertFalse(RegexCompat.hasUnboundedQuantifier("取件码："))
    }

    // ==================== 等价改写 ====================

    @Test
    fun `无上限后行断言被改写成消耗式非捕获组`() {
        assertEquals(
            "(?:取件码[：:]?\\s*)\\d{6}",
            RegexCompat.rewriteUnboundedLookbehind("(?<=取件码[：:]?\\s*)\\d{6}"),
        )
    }

    @Test
    fun `固定长度后行断言不改写`() {
        assertNull(RegexCompat.rewriteUnboundedLookbehind("(?<=取件码：)\\d{6}"))
    }

    @Test
    fun `不对负向后行断言动手`() {
        // (?<!X) 语义是「前面不是 X」，无法用消耗式分组等价表达 → 必须保持原样
        assertNull(RegexCompat.rewriteUnboundedLookbehind("(?<!广告+)\\d{6}"))
    }

    @Test
    fun `嵌套分组时不会把内层右括号当成断言收尾`() {
        assertEquals(
            "(?:(?:顺丰|圆通)+)\\d{6}",
            RegexCompat.rewriteUnboundedLookbehind("(?<=(?:顺丰|圆通)+)\\d{6}"),
        )
    }

    @Test
    fun `改写后的模式语义等价`() {
        val source = "您的取件码： 123456，请及时取件"
        val rewritten = RegexCompat.rewriteUnboundedLookbehind("(?<=取件码：\\s*)\\d{6}")
        assertNotNull(rewritten)
        // 原写法（JDK 17 支持无上限后行断言）与新写法都应当命中同一段
        assertTrue(Regex("(?<=取件码：\\s*)\\d{6}").containsMatchIn(source))
        assertTrue(Regex(rewritten!!).containsMatchIn(source))
        // 捕获组编号不变：$1 仍然是数字串
        assertEquals("123456", Regex(rewritten).find(source)?.groupValues?.get(0)?.takeLast(6))
    }

    // ==================== 编译入口 ====================

    @Test
    fun `正常正则原样编译且不标记改写`() {
        val compiled = RegexCompat.compileOrNull("(?<=取件码：)\\d{6}", ignoreCase = true)
        assertNotNull(compiled)
        assertFalse(compiled!!.rewritten)
        assertEquals("(?<=取件码：)\\d{6}", compiled.pattern)
        assertTrue(compiled.regex.containsMatchIn("取件码：123456"))
    }

    @Test
    fun `空模式与非法模式返回 null`() {
        assertNull(RegexCompat.compileOrNull("", ignoreCase = true))
        assertNull(RegexCompat.compileOrNull("   ", ignoreCase = true))
        assertNull(RegexCompat.compileOrNull("(((", ignoreCase = true))
    }

    // ==================== 诊断文案 ====================

    @Test
    fun `全角符号给出明确提示`() {
        assertTrue(
            RegexCompat.explain("（取件码）").orEmpty().contains("全角"),
        )
    }

    @Test
    fun `不支持的构造有中文说明`() {
        assertTrue(RegexCompat.explain("(a)\\g{1}").orEmpty().contains("\\g"))
        assertTrue(RegexCompat.explain("(?<=a+)(a)").orEmpty().contains("后行断言"))
    }

    @Test
    fun `没有已知问题时返回 null`() {
        assertNull(RegexCompat.explain("(?<=取件码：)\\d{6}"))
        assertNull(RegexCompat.explain("顺丰|圆通"))
    }
}
