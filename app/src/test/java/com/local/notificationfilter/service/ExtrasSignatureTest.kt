package com.local.notificationfilter.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * 内容指纹的 extras 签名（V11.4）。
 *
 * 修的是什么：V10.2 只把 `android.progress / progressMax` 纳入指纹，于是**其它任何**
 * extras 字段变化都不会被识别为「内容变了」，通知在去重处被整条丢弃 ——
 * 流水、详情页、规则都拿不到新值（用户报「通知还在、字段在变、却一直获取不到新内容」）。
 *
 * 同时必须守住两条稳定性要求，否则去重彻底失效（每秒跑全链路）：
 * 1. 同一份 extras 每次算出的签名必须逐字节相同；
 * 2. `dumpExtras` 里 ByteArray 的 identityHash（`[B@1a2b3c (byte[1024])`）每次重投都变，
 *    必须归一化成只含长度的稳定形式。
 */
class ExtrasSignatureTest {

    @Test
    fun `同一份 extras 签名稳定`() {
        val extras = listOf(
            "android.title" to "取件通知",
            "android.text" to "取件码 123456",
            "vendor.state" to "待取件",
        )
        assertEquals(extrasSignature(extras), extrasSignature(extras))
    }

    @Test
    fun `任意字段变化都会改变签名`() {
        val base = listOf("android.title" to "取件通知", "vendor.state" to "待取件")
        val changed = listOf("android.title" to "取件通知", "vendor.state" to "已签收")
        assertNotEquals(extrasSignature(base), extrasSignature(changed))
    }

    @Test
    fun `只有自定义字段变化也能被识别`() {
        // 这条就是用户场景：标题/正文都没变，变的是一个自定义 extras
        val before = listOf("android.title" to "下载", "vendor.percent" to "10")
        val after = listOf("android.title" to "下载", "vendor.percent" to "11")
        assertNotEquals(extrasSignature(before), extrasSignature(after))
    }

    @Test
    fun `ByteArray 的 identityHash 不参与签名`() {
        // dumpExtras 对 ByteArray 的两种可能输出（同一份字节，两次重投）
        val first = listOf("android.picture" to "[B@1a2b3c (byte[1024])")
        val second = listOf("android.picture" to "[B@9f8e7d (byte[1024])")
        assertEquals(extrasSignature(first), extrasSignature(second))
        // 但长度变了要算「内容变了」
        val bigger = listOf("android.picture" to "[B@1a2b3c (byte[2048])")
        assertNotEquals(extrasSignature(first), extrasSignature(bigger))
    }

    @Test
    fun `没有 extras 时为空串`() {
        assertEquals("", extrasSignature(emptyList()))
    }

    @Test
    fun `指纹随 extras 变化 而不是只看正文`() {
        val sameText = "同一段正文"
        val a = NotificationFingerprint.of(
            title = "标题", text = sameText, textLines = emptyList(),
            extraSignature = extrasSignature(listOf("vendor.state" to "待取件")),
        )
        val b = NotificationFingerprint.of(
            title = "标题", text = sameText, textLines = emptyList(),
            extraSignature = extrasSignature(listOf("vendor.state" to "已签收")),
        )
        assertNotEquals("extras 变化必须改变指纹（否则更新会被当成重复丢弃）", a, b)
        // 内容变了 → 不允许被去重跳过
        assertFalse(NotificationFingerprint.shouldSkip(a, b))
    }
}
