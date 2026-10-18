package com.local.notificationfilter.core.ruleengine

/**
 * 正则编译的「Android 兼容层」。
 *
 * ## 为什么要这一层（真机踩到的差异）
 * Android 的正则引擎从 API 10 起换成 **ICU4C**。它与桌面 JDK 的关键差别是：
 * **后行断言（lookbehind）里不允许出现无上限量词**（`*`、`+`、`{n,}`），编译时直接抛
 * `PatternSyntaxException: Look-behind group does not have an obvious maximum length`。
 * 而桌面 JDK 13+ 已经放开了这条限制。
 *
 * 于是出现最气人的场景：用户在电脑上（或任何在线正则工具里）验证通过的
 * `(?<=取件码[：:]?\s*)\d{6}`，装到手机上**这条规则永远不命中**，看起来就是
 * 「正向 / 负向断言不支持」。实际上固定长度的断言（`(?<=取件码：)`）在 Android 上完全可用。
 *
 * ## 本类做两件事
 * 1. [compileOrNull]：先按原样编译；失败时尝试**等价改写**
 *    `(?<=X)P` → `(?:X)P`（仅当 X 里有无上限量词）。对「是否命中」与「按捕获组 `$N` 取值」
 *    完全等价，唯一差别是 `$0` 会多包含 X 那段文字。是否发生了改写通过
 *    [Compiled.rewritten] 上报，调用方可以提示用户。
 * 2. [explain]：把常见的「Android 不支持」翻译成中文原因，交给保存校验与试跑面板展示，
 *    不再只丢一句「正则语法有误」。
 *
 * ## 刻意不做的事
 * 负向后行断言 `(?<!X)` 无法用「消耗式分组」等价表达（语义是「前面不是 X」），
 * 因此不做自动改写，只报错并给出改法。瞎改会让规则行为变得不可预测，比报错更糟。
 */
object RegexCompat {

    /** 编译结果：正则本体 + 实际使用的模式 + 是否走了兼容改写。 */
    data class Compiled(
        val regex: Regex,
        /** 实际编译用的模式。未改写时与入参逐字相同。 */
        val pattern: String,
        /** true = 入参在 Android 上编译不过，已按等价形式改写后成功编译。 */
        val rewritten: Boolean,
    )

    /**
     * 编译正则；空模式 / 非法模式 → null（**绝不抛异常**：调用方都在真实通知的热路径上）。
     *
     * @param pattern 用户填写的正则原文
     * @param ignoreCase 是否忽略大小写
     */
    fun compileOrNull(pattern: String, ignoreCase: Boolean): Compiled? {
        if (pattern.isBlank()) return null
        val options = if (ignoreCase) setOf(RegexOption.IGNORE_CASE) else emptySet()

        runCatching { Regex(pattern, options) }.getOrNull()
            ?.let { return Compiled(it, pattern, rewritten = false) }

        // 原样编译失败 → 只尝试「后行断言无上限量词」这一种已知改写；其它语法错误仍然返回 null
        val rewrittenPattern = rewriteUnboundedLookbehind(pattern) ?: return null
        val rewrittenRegex = runCatching { Regex(rewrittenPattern, options) }.getOrNull() ?: return null
        return Compiled(rewrittenRegex, rewrittenPattern, rewritten = true)
    }

    /**
     * 给出「为什么这条正则在 Android 上不生效」的中文解释；没有已知问题返回 null。
     *
     * 只在**编译失败**或用户主动查询时调用，不在匹配热路径上。
     */
    fun explain(pattern: String): String? {
        lookbehinds(pattern).forEach { (negative, inner) ->
            if (hasUnboundedQuantifier(inner)) {
                return if (negative) {
                    "负向后行断言 (?<!…) 里不能用 * + {n,}（Android 的正则引擎要求长度有上限）。" +
                        "请改成固定长度，例如 (?<!广告)："
                } else {
                    "后行断言 (?<=…) 里不能用 * + {n,}（Android 的正则引擎要求长度有上限）。" +
                        "已自动按 (?:…) 等价改写；也可以改用捕获组：前面的文字放进括号，捕获格式填 \$1"
                }
            }
        }
        // 只把「几乎不可能是正文」的语法符号列进来：全角冒号 / 逗号在中文通知里是常见正文
        // （`(?<=取件码：)` 这种写法本身是对的），不能误报。
        val fullWidthSyntax = "（）｛｝［］＊＋｜＄"
        if (pattern.any { it in fullWidthSyntax }) {
            return "正则里有全角符号（如 （ ） ｛ ｝ ＊ ＋ ｜），必须换成半角 ( ) { } * + | 。"
        }
        if (pattern.contains("\\g{")) return "Android 不支持 \\g{n} 反向引用，请改用 \\1 \\2"
        if (pattern.contains("(?#")) return "Android 不支持 (?#…) 注释语法"
        if (pattern.contains("(?{") || pattern.contains("??{")) return "Android 不支持 (?{code}) 代码构造"
        if (CONDITIONAL.containsMatchIn(pattern)) return "Android 不支持 (?(条件)…) 条件构造"
        return null
    }

    // ==================== 内部实现 ====================

    private val CONDITIONAL = Regex("""\(\?\([A-Za-z]""")

    /**
     * 把 `(?<=X)` 改写为 `(?:X)`（仅当 X 含无上限量词）。
     *
     * 只处理 `(?<=`；`(?<!` 语义不同，不能这样改写。
     *
     * @return 改写后的模式；无可改写内容时返回 null
     */
    internal fun rewriteUnboundedLookbehind(pattern: String): String? {
        val sb = StringBuilder(pattern.length)
        var i = 0
        var changed = false
        while (i < pattern.length) {
            val start = indexOfLookbehind(pattern, i)
            if (start < 0) {
                sb.append(pattern, i, pattern.length)
                break
            }
            // matchingParen 从 `(` 起算深度，因此传 start（不是 inner 起点）；
            // 传 inner 起点会把内层分组的 `)` 误当成后行断言的收尾括号。
            val close = matchingParen(pattern, start)
            if (close < 0) {
                sb.append(pattern, i, pattern.length)
                break
            }
            val inner = pattern.substring(start + 4, close)
            if (hasUnboundedQuantifier(inner)) {
                sb.append(pattern, i, start)
                sb.append("(?:")
                sb.append(inner)
                sb.append(')')
                changed = true
            } else {
                sb.append(pattern, i, close + 1)
            }
            i = close + 1
        }
        return if (changed) sb.toString() else null
    }

    /** 从 [from] 起找未被转义的 `(?<=` 下标；找不到返回 -1。 */
    private fun indexOfLookbehind(pattern: String, from: Int): Int {
        var i = from
        while (i <= pattern.length - 4) {
            if (pattern.startsWith("(?<=", i) && !isEscaped(pattern, i)) return i
            i++
        }
        return -1
    }

    /**
     * 从 `(` 的位置 [open] 找配对的 `)`；正确处理转义与字符类 `[…]`（类里的括号不配对）。
     *
     * @return `)` 的下标；不配对返回 -1
     */
    private fun matchingParen(pattern: String, open: Int): Int {
        var depth = 0
        var inClass = false
        var i = open
        while (i < pattern.length) {
            val c = pattern[i]
            when {
                isEscaped(pattern, i) -> Unit
                inClass -> if (c == ']') inClass = false
                c == '[' -> inClass = true
                c == '(' -> depth++
                c == ')' -> {
                    depth--
                    if (depth == 0) return i
                }
            }
            i++
        }
        return -1
    }

    /** [index] 处的字符是否被反斜杠转义（考虑连续反斜杠的奇偶）。 */
    private fun isEscaped(pattern: String, index: Int): Boolean {
        var backslashes = 0
        var i = index - 1
        while (i >= 0 && pattern[i] == '\\') {
            backslashes++
            i--
        }
        return backslashes % 2 == 1
    }

    /**
     * 片段里是否含**无上限量词**：`*` `+` 或 `{n,}`（在字符类 `[…]` 之外、未被转义）。
     *
     * 这是 Android(ICU) 后行断言的唯一硬限制；`?` 与 `{n}` `{n,m}` 都允许。
     */
    internal fun hasUnboundedQuantifier(inner: String): Boolean {
        var inClass = false
        var i = 0
        while (i < inner.length) {
            val c = inner[i]
            when {
                isEscaped(inner, i) -> Unit
                inClass -> if (c == ']') inClass = false
                c == '[' -> inClass = true
                c == '*' || c == '+' -> return true
                c == '{' -> {
                    val close = inner.indexOf('}', i)
                    if (close > i) {
                        val body = inner.substring(i + 1, close)
                        if (body.endsWith(",")) return true // {n,} = 无上限
                        i = close
                    }
                }
            }
            i++
        }
        return false
    }

    /** 枚举模式里所有后行断言：`(negative, 内部片段)`。 */
    private fun lookbehinds(pattern: String): List<Pair<Boolean, String>> {
        val result = mutableListOf<Pair<Boolean, String>>()
        var i = 0
        while (i <= pattern.length - 4) {
            val negative = pattern.startsWith("(?<!", i) && !isEscaped(pattern, i)
            val positive = !negative && pattern.startsWith("(?<=", i) && !isEscaped(pattern, i)
            if (!negative && !positive) {
                i++
                continue
            }
            val close = matchingParen(pattern, i)
            if (close < 0) break
            result += negative to pattern.substring(i + 4, close)
            i = close + 1
        }
        return result
    }
}
