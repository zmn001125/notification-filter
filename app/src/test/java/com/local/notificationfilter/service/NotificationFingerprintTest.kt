package com.local.notificationfilter.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 同 key 内容指纹去重单测（V6.1 / 审计 P1-6）。
 *
 * 为什么必须测：去重是**会吞掉真实通知**的一类改动。判错的两种方向都有代价——
 *  - 该跳的没跳 → 音乐/下载通知每秒刷流水、每秒弹一条弹幕（回到优化前的耗电）；
 *  - 不该跳的跳了 → 用户收不到提醒（最坏形态，且难复现）。
 * 因此这里把「什么算内容变了」逐条钉死，并覆盖拼接歧义与首条到达的语义。
 */
class NotificationFingerprintTest {

    private fun fp(title: String?, text: String?, lines: List<String> = emptyList()): Long =
        NotificationFingerprint.of(title, text, lines)

    @Test
    fun `相同三元组得到相同指纹`() {
        assertEquals(fp("张三", "在吗", listOf("在吗")), fp("张三", "在吗", listOf("在吗")))
    }

    @Test
    fun `标题变化即内容变化`() {
        assertNotEquals(fp("张三", "在吗"), fp("李四", "在吗"))
    }

    @Test
    fun `正文变化即内容变化`() {
        assertNotEquals(fp("张三", "在吗"), fp("张三", "在么"))
    }

    @Test
    fun `消息列表变化即内容变化`() {
        assertNotEquals(fp("群", "摘要", listOf("a")), fp("群", "摘要", listOf("b")))
    }

    @Test
    fun `消息列表元素数量变化即内容变化`() {
        assertNotEquals(fp("群", "摘要", listOf("a")), fp("群", "摘要", listOf("a", "b")))
    }

    /**
     * 拼接歧义：列表元素之间必须用分隔符连接。
     * 若用空串连接，`["1","23"]` 与 `["12","3"]` 会拼出同一个 `"123"` → 误判为「未变」。
     */
    @Test
    fun `列表元素拼接无歧义`() {
        assertNotEquals(fp("群", "摘要", listOf("1", "23")), fp("群", "摘要", listOf("12", "3")))
    }

    /** 字段之间同理：`title="ab", text="c"` 不能与 `title="a", text="bc"` 撞车。 */
    @Test
    fun `字段拼接无歧义`() {
        assertNotEquals(fp("ab", "c"), fp("a", "bc"))
    }

    /** null 与空串等价（通知缺字段一律按空串处理，避免同一内容因 null/"" 反复触发动作）。 */
    @Test
    fun `null 与空串等价`() {
        assertEquals(fp(null, null), fp("", ""))
        assertEquals(fp("张三", null), fp("张三", ""))
    }

    /** 首个到达：没有历史指纹时必须处理（返回 false = 不跳过）。 */
    @Test
    fun `首条到达不跳过`() {
        val current = fp("张三", "在吗")
        assertFalse(NotificationFingerprint.shouldSkip(null, current))
    }

    /** 同 key 内容完全一致 → 跳过（这才是去重要达到的效果）。 */
    @Test
    fun `指纹相同则跳过`() {
        val current = fp("张三", "在吗")
        assertTrue(NotificationFingerprint.shouldSkip(current, current))
    }

    /** 内容变了 → 必须处理（进度更新、新消息都属此类）。 */
    @Test
    fun `指纹不同不跳过`() {
        assertFalse(NotificationFingerprint.shouldSkip(fp("张三", "在吗"), fp("张三", "在吗？")))
    }

    /**
     * 空通知也是合法输入：不抛异常，且两次空通知互相判等。
     * （某些 App 会发只有 extras、没有 title/text 的通知。）
     */
    @Test
    fun `全空通知不抛异常且自洽`() {
        val empty = fp(null, null, emptyList())
        assertEquals(empty, fp(null, null, emptyList()))
        assertTrue(NotificationFingerprint.shouldSkip(empty, empty))
    }

    /** 大小写与空白是内容的一部分，不做归一化（用户关心的正是原样差异）。 */
    @Test
    fun `大小写与空白不归一化`() {
        assertNotEquals(fp("Hello", "x"), fp("hello", "x"))
        assertNotEquals(fp("张三", "在吗"), fp("张三", "在吗 "))
    }

    /** 长文本也不应抛异常（FNV 遍历逐字符，无长度上限）。 */
    @Test
    fun `长文本可用`() {
        val long = "很长的正文".repeat(2000)
        assertEquals(fp("t", long), fp("t", long))
        assertNotEquals(fp("t", long), fp("t", long + "!"))
    }

    // ==================== V10.2 缺陷 C：附加签名（subText / summaryText / 进度） ====================

    private fun fpSigned(
        title: String?,
        text: String?,
        lines: List<String> = emptyList(),
        signature: String = "",
    ): Long = NotificationFingerprint.of(title, text, lines, signature)

    /**
     * 附加签名的**默认值契约**：不传 / 传空串必须与 V10.1 的三元组口径逐值一致。
     *
     * 为什么必须钉死：老调用点（正常运行路径）与老单测都按三元组算指纹，
     * 若空签名改变了哈希值，运行期缓存与测试口径就会悄悄漂移，去重行为随之改变。
     */
    @Test
    fun `附加签名为空时与旧三元组口径一致`() {
        assertEquals(fp("张三", "在吗", listOf("在吗")), fpSigned("张三", "在吗", listOf("在吗")))
        assertEquals(fp(null, null), fpSigned(null, null, emptyList(), ""))
        assertEquals(fp("群", "摘要", listOf("a", "b")), fpSigned("群", "摘要", listOf("a", "b"), ""))
    }

    /**
     * 缺陷 C 的核心断言：正文三元组完全相同、只有附加签名不同 → 视为「内容变了」。
     *
     * 对应真机场景：百度网盘复用同一 key 重发通知，进度只在 progress 里变，
     * title/text/textLines 一字不差。修复前这类更新被判为「未变」而整条丢弃。
     */
    @Test
    fun `附加签名变化即内容变化`() {
        assertNotEquals(
            fpSigned("百度网盘", "正在备份", signature = "0\u00000\u000010/100"),
            fpSigned("百度网盘", "正在备份", signature = "0\u00000\u000011/100"),
        )
    }

    /** 附加签名与正文之间不得有拼接歧义（分隔符口径与正文拼接一致）。 */
    @Test
    fun `附加签名与正文拼接无歧义`() {
        assertNotEquals(
            fpSigned("t", "ab", signature = "c"),
            fpSigned("t", "a", signature = "bc"),
        )
    }

    /** 进度签名：取 extras 里 `android.progress` / `android.progressMax` 两个键。 */
    @Test
    fun `进度签名取自 android_progress 与 android_progressMax`() {
        val extras = listOf(
            "android.progress" to "10",
            "android.progressMax" to "100",
            "android.title" to "百度网盘",
        )
        assertEquals("10/100", progressSignature(extras))
        // 只有当前值、没有上限时也要可用（部分 ROM 只塞一个键）
        assertEquals("10/", progressSignature(listOf("android.progress" to "10")))
        assertNotEquals(
            progressSignature(extras),
            progressSignature(listOf("android.progress" to "11", "android.progressMax" to "100")),
        )
    }

    /** 没有进度键 → 空串（不引入恒定后缀，避免指纹里堆无信息量的常量）。 */
    @Test
    fun `无进度键时进度签名为空串`() {
        assertEquals("", progressSignature(emptyList()))
        assertEquals("", progressSignature(listOf("android.title" to "x", "android.text" to "y")))
    }
}
