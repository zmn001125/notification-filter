package com.local.notificationfilter.core.ruleengine

import com.local.notificationfilter.core.aggregate.ItemExtractor
import com.local.notificationfilter.core.model.ExtractorConfig
import com.local.notificationfilter.core.model.MatchField
import com.local.notificationfilter.core.model.MatchMode
import com.local.notificationfilter.core.model.NotificationSnapshot
import com.local.notificationfilter.core.model.RegexConfig
import com.local.notificationfilter.core.model.Rule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 正向 / 负向断言在「规则匹配」与「内容提取」两条链路上的可用性（V11.4）。
 *
 * 用户报「断言好像不能用」，实际原因有两类：
 * 1. Android 的 ICU 引擎不允许后行断言里出现 `*` `+` `{n,}`（桌面 JDK 允许）→ 见 [RegexCompatTest]；
 * 2. 断言跨字段时必须在**同一字段内**成立（引擎逐字段匹配），不是「不支持断言」。
 *
 * 这里把四种断言的正常用法钉死，防止后续改动把这条路弄坏。
 */
class RegexLookaroundTest {

    private fun snap(text: String) = NotificationSnapshot(
        key = "0|com.test|1|tag|0",
        notifId = 1,
        packageName = "com.test",
        appName = "测试",
        title = "【丰巢】取件通知",
        text = text,
        people = emptyList(),
    )

    private fun rule(pattern: String) = Rule(
        id = 1,
        name = "断言测试",
        matchMode = MatchMode.REGEX,
        fields = setOf(MatchField.TEXT),
        regex = RegexConfig(pattern = pattern, ignoreCase = true),
    )

    private fun extract(pattern: String, text: String, format: String = "$0"): List<String> =
        ItemExtractor.extractAll(
            snap(text),
            ExtractorConfig(
                listIndex = 1,
                name = "t",
                fieldIds = listOf(MatchField.TEXT.name),
                fieldSeparator = "\n",
                pattern = pattern,
                captureFormat = format,
                limit = 10,
            ),
        )

    @Test
    fun `四种断言都能命中`() {
        val text = "您好，取件码：123456，请及时取件"
        listOf(
            "(?=.*取件码)",            // 正向先行
            "(?!.*广告)",              // 负向先行
            "(?<=取件码：)\\d{6}",     // 正向后行（定长）
            "(?<!【广告】)取件码：\\d{6}", // 负向后行
        ).forEach { pattern ->
            val result = RuleEngine.matchFirst(listOf(rule(pattern)), snap(text))
            assertNotNull("规则未返回结果：$pattern", result)
            assertTrue("断言未命中：$pattern", result!!.hit)
        }
    }

    @Test
    fun `负向断言在条件成立时不命中`() {
        val text = "【广告】取件码：999999"
        val result = RuleEngine.matchFirst(listOf(rule("(?<!【广告】)取件码：\\d{6}")), snap(text))
        // matchFirst 的契约：全部未命中返回 null（不是 hit=false 的结果对象）
        assertNull("负向断言应不命中", result)
    }

    @Test
    fun `后行断言可从当前通知里即时提取`() {
        assertEquals(listOf("123456"), extract("(?<=取件码：)\\d{6}", "取件码：123456，请取件"))
    }

    @Test
    fun `用捕获组写法同样可取到内容`() {
        // 断言不写进捕获格式：前面文字进分组，取 $1 —— 跨引擎最稳的写法
        assertEquals(listOf("654321"), extract("取件码[：:]\\s*(\\d{6})", "取件码：654321", "$1"))
    }

    @Test
    fun `无上限后行断言即使走兼容改写也能命中`() {
        // Android 上原样编译失败 → 兼容层改写成 (?:…)，仍然必须命中
        val result = RuleEngine.matchFirst(
            listOf(rule("(?<=取件码[：:]?\\s*)\\d{6}")),
            snap("取件码：  123456"),
        )
        assertTrue("无上限后行断言未命中", result?.hit == true)
    }

    @Test
    fun `断言只作用于被选字段 跨字段不成立`() {
        // 标题里的「取件」+ 正文里的「码」不能被一个后行断言跨字段串起来：
        // 引擎是逐字段匹配的（这是设计口径，不是 bug）
        val crossField = Rule(
            id = 2,
            name = "跨字段",
            matchMode = MatchMode.REGEX,
            fields = setOf(MatchField.TEXT),
            regex = RegexConfig(pattern = "(?<=【丰巢】取件)\\d{6}", ignoreCase = true),
        )
        assertNull("跨字段断言不应命中", RuleEngine.matchFirst(listOf(crossField), snap("通知：123456")))
    }
}
