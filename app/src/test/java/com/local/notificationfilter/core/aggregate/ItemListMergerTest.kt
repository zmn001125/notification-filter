package com.local.notificationfilter.core.aggregate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 列表合并单测（V7 需求 8，架构 §7.3）。
 *
 * 覆盖：文档 §7.3 用例表逐条 + 边界（limit 0/负/超大、incoming 空白、trim、
 * 多条目顺序、已有列表超限）。
 */
class ItemListMergerTest {

    // ==================== 文档 §7.3 用例表 ====================

    @Test
    fun `空列表加一条`() {
        assertEquals(
            listOf("A"),
            ItemListMerger.merge(emptyList(), "A", limit = 10, dedupe = true),
        )
    }

    @Test
    fun `新条目排在最前`() {
        assertEquals(
            listOf("C", "A", "B"),
            ItemListMerger.merge(listOf("A", "B"), "C", limit = 10, dedupe = true),
        )
    }

    @Test
    fun `重复条目移到最前且只留一条`() {
        assertEquals(
            listOf("A", "B"),
            ItemListMerger.merge(listOf("A", "B"), "A", limit = 10, dedupe = true),
        )
    }

    @Test
    fun `已有重复条目在再次命中时收敛为一条`() {
        assertEquals(
            listOf("A"),
            ItemListMerger.merge(listOf("A", "A"), "A", limit = 10, dedupe = true),
        )
    }

    @Test
    fun `关闭去重时重复条目并存`() {
        assertEquals(
            listOf("A", "A", "B"),
            ItemListMerger.merge(listOf("A", "B"), "A", limit = 10, dedupe = false),
        )
    }

    @Test
    fun `超过上限淘汰尾部`() {
        assertEquals(
            listOf("D", "A", "B"),
            ItemListMerger.merge(listOf("A", "B", "C"), "D", limit = 3, dedupe = true),
        )
    }

    @Test
    fun `limit 为 0 或负数返回空列表`() {
        assertTrue(ItemListMerger.merge(listOf("A"), "B", limit = 0, dedupe = true).isEmpty())
        assertTrue(ItemListMerger.merge(listOf("A"), "B", limit = -1, dedupe = true).isEmpty())
    }

    @Test
    fun `limit 超大被夹到 50`() {
        val existing = (1..60).map { "old$it" }
        val merged = ItemListMerger.merge(existing, "new", limit = 1000, dedupe = true)
        assertEquals(ItemListMerger.MAX_LIMIT, merged.size)
        assertEquals("new", merged.first())
    }

    @Test
    fun `incoming 全空白时原列表不变`() {
        val existing = listOf("A", "B")
        assertEquals(existing, ItemListMerger.merge(existing, "  ", limit = 10, dedupe = true))
        assertEquals(existing, ItemListMerger.merge(existing, "", limit = 10, dedupe = true))
        assertEquals(existing, ItemListMerger.merge(existing, null, limit = 10, dedupe = true))
    }

    @Test
    fun `条目按 trim 后比较与存储`() {
        assertEquals(
            listOf("A"),
            ItemListMerger.merge(listOf("A"), " A ", limit = 10, dedupe = true),
        )
        assertEquals(
            listOf("B", "A"),
            ItemListMerger.merge(listOf("A"), " B ", limit = 10, dedupe = true),
        )
    }

    // ==================== 本次新增：多条目入口 ====================

    @Test
    fun `多条新条目按抽取顺序排在已有条目之前`() {
        assertEquals(
            listOf("X", "Y", "A", "B"),
            ItemListMerger.merge(listOf("A", "B"), listOf("X", "Y"), limit = 10, dedupe = true),
        )
    }

    @Test
    fun `多条新条目内部也按值去重`() {
        assertEquals(
            listOf("X", "Y", "A"),
            ItemListMerger.merge(listOf("A"), listOf("X", "Y", "X"), limit = 10, dedupe = true),
        )
    }

    @Test
    fun `多条新条目与已有条目交叉重复时保留最新位置`() {
        assertEquals(
            listOf("B", "A", "C"),
            ItemListMerger.merge(listOf("A", "B", "C"), listOf("B", "A"), limit = 10, dedupe = true),
        )
    }

    @Test
    fun `多条新条目遇到空集时原样返回`() {
        val existing = listOf("A", "B")
        assertEquals(existing, ItemListMerger.merge(existing, emptyList(), limit = 10, dedupe = true))
        assertEquals(existing, ItemListMerger.merge(existing, listOf("  ", ""), limit = 10, dedupe = true))
    }

    @Test
    fun `不修改入参列表`() {
        val existing = mutableListOf("A")
        val incoming = mutableListOf("B")
        ItemListMerger.merge(existing, incoming, limit = 10, dedupe = true)
        assertEquals(listOf("A"), existing)
        assertEquals(listOf("B"), incoming)
    }

    @Test
    fun `已有列表本身超限时合并后被裁到上限`() {
        assertEquals(
            listOf("N", "A", "B"),
            ItemListMerger.merge(listOf("A", "B", "C", "D"), "N", limit = 3, dedupe = true),
        )
    }

    @Test
    fun `大小写不同的条目视为不同条目`() {
        assertEquals(
            listOf("a", "A"),
            ItemListMerger.merge(listOf("A"), "a", limit = 10, dedupe = true),
        )
    }

    @Test
    fun `isFull 判定`() {
        assertTrue(ItemListMerger.isFull(listOf("A", "B"), limit = 2))
        assertFalse(ItemListMerger.isFull(listOf("A"), limit = 2))
        assertTrue(ItemListMerger.isFull(listOf("A"), limit = 0))
    }
}
