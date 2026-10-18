package com.local.notificationfilter.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 转义码解析单测（V7 需求 8；架构 §4.3 / §7.1）。
 *
 * 覆盖（架构 §7.1 表格 9 条 + 边界补充）：
 * 1. `\n` / `\t` / `\r` 三个已知转义；
 * 2. `\\` → 一个字面反斜杠；
 * 3. 未知转义（`\z` / `\0`）**原样保留**，不吞字符；
 * 4. 结尾孤立反斜杠原样保留；
 * 5. 空串 → 空串；
 * 6. 输入里的真实换行 / 制表符不被二次处理；
 * 7. **单遍扫描**（非链式 replace）：`\\n`（3 字符）→ `\n`（2 字符）；
 * 8. 混合串一次解析；
 * 9. [EscapeCodec.encode] 回显；`decode(encode(x)) == x` 往返。
 *
 * 纯 JVM：不触任何 Android 类型。
 */
class EscapeCodecTest {

    // ==================== 1. 已知转义 ====================

    @Test
    fun 换行_反斜杠n转成真实换行() {
        assertEquals("a\nb", EscapeCodec.decode("a\\nb"))
        // 只有一个 token 时也成立
        assertEquals("\n", EscapeCodec.decode("\\n"))
    }

    @Test
    fun 制表_反斜杠t转成真实制表符() {
        assertEquals("\t", EscapeCodec.decode("\\t"))
        assertEquals("A\tB", EscapeCodec.decode("A\\tB"))
    }

    @Test
    fun 回车_反斜杠r转成真实回车() {
        assertEquals("\r", EscapeCodec.decode("\\r"))
        assertEquals("A\r\nB", EscapeCodec.decode("A\\r\\nB"))
    }

    // ==================== 2. 字面反斜杠 ====================

    @Test
    fun 双反斜杠转成一个字面反斜杠() {
        assertEquals("\\", EscapeCodec.decode("\\\\"))
        // "a\\\\nb"（5 字符：a \ \ n b）→ "a\\nb"（4 字符：a 反斜杠 n b，字母 n 仍是字母）
        assertEquals("a\\nb", EscapeCodec.decode("a\\\\nb"))
    }

    // ==================== 3. 未知转义原样保留 ====================

    @Test
    fun 未知转义原样保留_不吞字符() {
        assertEquals("a\\zb", EscapeCodec.decode("a\\zb"))
        assertEquals("\\0", EscapeCodec.decode("\\0"))
        assertEquals("\\w+", EscapeCodec.decode("\\w+"))
        // 未知转义里的字母不会被当成已知转义
        assertEquals("\\N", EscapeCodec.decode("\\N"))
        assertEquals("\\T", EscapeCodec.decode("\\T"))
    }

    // ==================== 4. 结尾孤立反斜杠 ====================

    @Test
    fun 结尾孤立反斜杠原样保留() {
        assertEquals("ab\\", EscapeCodec.decode("ab\\"))
        assertEquals("\\", EscapeCodec.decode("\\"))
        // 单字符边界：反斜杠不能吞掉自己
        assertEquals(1, EscapeCodec.decode("\\").length)
    }

    // ==================== 5. 空串 ====================

    @Test
    fun 空串返回空串() {
        assertEquals("", EscapeCodec.decode(""))
        assertEquals("", EscapeCodec.encode(""))
    }

    // ==================== 6. 真实字符不被二次处理 ====================

    @Test
    fun 真实换行与制表不被二次处理() {
        assertEquals("a\nb", EscapeCodec.decode("a\nb"))
        assertEquals("a\tb\r\n", EscapeCodec.decode("a\tb\r\n"))
        assertEquals("第一行\n第二行", EscapeCodec.decode("第一行\n第二行"))
    }

    // ==================== 7. 单遍扫描（非链式 replace） ====================

    @Test
    fun 单遍扫描_反斜杠反斜杠n只解析一次() {
        // 输入 3 字符：\ \ n（Kotlin 字面量 "\\\\n"）
        // 期望 2 字符：\ n（Kotlin 字面量 "\\n"）
        // 若用 text.replace("\\\\","\\").replace("\\n","\n") 的链式写法，会得到 1 个换行（错误）
        assertEquals("\\n", EscapeCodec.decode("\\\\n"))
        assertEquals(2, EscapeCodec.decode("\\\\n").length)
    }

    // ==================== 8. 混合串 ====================

    @Test
    fun 混合串_多个转义一次解析() {
        assertEquals("第1行\n第2行\t末列", EscapeCodec.decode("第1行\\n第2行\\t末列"))
        assertEquals("A\\B\nC", EscapeCodec.decode("A\\\\B\\nC"))
        // 已知 + 未知混合
        assertEquals("\\z\nA\\zb", EscapeCodec.decode("\\z\\nA\\zb"))
    }

    // ==================== 9. encode 回显 + 往返 ====================

    @Test
    fun encode_真实字符回显成转义写法() {
        assertEquals("\\n", EscapeCodec.encode("\n"))
        assertEquals("\\t", EscapeCodec.encode("\t"))
        assertEquals("\\r", EscapeCodec.encode("\r"))
        assertEquals("\\\\", EscapeCodec.encode("\\"))
        assertEquals("a\\nb", EscapeCodec.encode("a\nb"))
        // 无转义需求的文本原样返回
        assertEquals("直接相接", EscapeCodec.encode("直接相接"))
    }

    @Test
    fun 往返_decode_encode互为逆运算() {
        val samples = listOf(
            "",
            "\n",
            "\t",
            "\r",
            "\\",
            "a\nb",
            "a\tb",
            "A\\B\nC\tD\rE",
            "无任何转义",
            "\\n在文本里的字面量",
            "第一行\n第二行\n",
        )
        samples.forEach { text ->
            assertEquals("往返失败：<$text>", text, EscapeCodec.decode(EscapeCodec.encode(text)))
        }
    }

    @Test
    fun 分隔符实际场景_留空与常见写法() {
        // 用户留空 → 直接相接（decode 不改动空串）
        assertEquals("", EscapeCodec.decode(""))
        // 直接敲的换行（多行输入框）→ 真换行，无需转义
        assertEquals("\n", EscapeCodec.decode("\n"))
        // 输入的 \n 字面量 → 真换行
        assertEquals("\n", EscapeCodec.decode("\\n"))
        // 自定义分隔符原样
        assertEquals("、", EscapeCodec.decode("、"))
    }
}
