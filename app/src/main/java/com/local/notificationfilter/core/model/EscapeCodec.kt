package com.local.notificationfilter.core.model

/**
 * UI 转义码解析（V7 需求 8；纯 Kotlin，无 Android 依赖，JVM 单测全覆盖）。
 *
 * 用户要求「条目分隔符必须支持换行——可直接回车，也支持输入 `\n` `\t` 转义，留空=直接相接」。
 *
 * **解析时机：保存时**（ViewModel 调 [decode] 后写进 [AggregationConfig.itemSeparator]）。
 * 理由：①渲染是热路径（卡片每次重绘、推迟到点都要跑），不该重复解析；
 * ②`Placeholders` 保持"只做 token 替换"的单一职责；③用户在"预览/测试"里看到的是最终效果。
 *
 * 本对象**只做转义解析，不含任何模板语义**（模板渲染唯一实现仍是 [Placeholders]）。
 *
 * 规则（单遍扫描，**不**用链式 replace 以免 `\\n` 被误伤）：
 * - `\n` → 换行；`\t` → 制表；`\r` → 回车；
 * - `\\` → 一个字面反斜杠；
 * - 其它 `\x` → **原样保留** `\x`（不吞字符，用户输入路径正则时不会被悄悄改掉）；
 * - 结尾孤立 `\` → 原样保留；
 * - 输入中的真实换行 / 制表符**不再二次处理**，原样输出。
 *
 * 单遍扫描的正确性要点：`\\n`（3 字符：`\` `\` `n`）中第一个 `\` 与第二个 `\` 配成
 * 转义序列产出字面反斜杠，随后的 `n` 是普通字符，最终得到 `\n`（2 字符：反斜杠 + n）
 * ——若用 `text.replace("\\", "\\").replace("\\n", "\n")` 这类链式写法，会被二次转义成换行。
 */
object EscapeCodec {

    /** 转义引导字符。 */
    private const val BACKSLASH = '\\'

    /**
     * 解析转义码：`\n` `\t` `\r` `\\` 按上表转换，未知转义与末尾孤立反斜杠原样保留。
     *
     * @param text 用户在编辑框里输入的原文（可含真实换行）
     * @return 可入库 / 可直接用于渲染的真实字符文本
     */
    fun decode(text: String): String {
        if (text.isEmpty()) return text
        // 快路径：不含反斜杠则无需解析（绝大多数情况下用户直接回车）
        if (text.indexOf(BACKSLASH) < 0) return text
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c != BACKSLASH) {
                out.append(c)
                i += 1
                continue
            }
            // c == '\\'
            if (i == text.length - 1) {
                // 结尾孤立反斜杠：原样保留（不吞字符、不报错）
                out.append(BACKSLASH)
                i += 1
                continue
            }
            when (val next = text[i + 1]) {
                'n' -> out.append('\n')
                't' -> out.append('\t')
                'r' -> out.append('\r')
                BACKSLASH -> out.append(BACKSLASH)
                // 未知转义（如 \z）：原样保留两个字符，绝不吞掉后续字符
                else -> {
                    out.append(BACKSLASH)
                    out.append(next)
                }
            }
            i += 2
        }
        return out.toString()
    }

    /**
     * [decode] 的反向（回显用）：真实换行 / 制表 / 回车 → `\n` `\t` `\r`，字面反斜杠 → `\\`。
     *
     * 保证「存进去什么、界面上看到什么」一致，且 `decode(encode(x)) == x` 对任意字符串成立。
     *
     * @param text 已入库的真实字符文本
     * @return 单行输入框里可回显的转义写法
     */
    fun encode(text: String): String {
        if (text.isEmpty()) return text
        val out = StringBuilder(text.length)
        for (c in text) {
            when (c) {
                '\n' -> out.append("\\n")
                '\t' -> out.append("\\t")
                '\r' -> out.append("\\r")
                BACKSLASH -> out.append("\\\\")
                else -> out.append(c)
            }
        }
        return out.toString()
    }
}
