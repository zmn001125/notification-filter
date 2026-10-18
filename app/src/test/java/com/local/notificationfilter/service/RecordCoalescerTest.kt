package com.local.notificationfilter.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 同 key 流水合并判定单测（V10.11 刷屏修复）。
 *
 * 为什么必须测：这个判定错了两个方向都要命——
 *  - 该合的不合 → 一条下载通知跑一小时就把记录页刷满（本次修的 bug）；
 *  - 不该合的合了 → 用户连收两条微信，第二条被合进第一条那一行，**看起来像丢通知**。
 * 所以把时间窗边界、首见、移除后、时钟回拨四种情形逐条钉死。
 */
class RecordCoalescerTest {

    private val window = RecordCoalescer.WINDOW_MS
    private val t0 = 1_700_000_000_000L

    @Test
    fun `首次见到（没有既有行）必须插新行`() {
        assertFalse(RecordCoalescer.shouldCoalesce(lastWriteAt = null, now = t0))
    }

    @Test
    fun `窗口内的更新合并进既有行`() {
        assertTrue(RecordCoalescer.shouldCoalesce(lastWriteAt = t0, now = t0 + 1))
        assertTrue(RecordCoalescer.shouldCoalesce(lastWriteAt = t0, now = t0 + 1_200))
        assertTrue(RecordCoalescer.shouldCoalesce(lastWriteAt = t0, now = t0 + window - 1))
    }

    @Test
    fun `恰好等于窗口边界仍然合并`() {
        // 边界取闭区间：<= 窗口都算同一轮，避免「刚好卡在 10 分钟」时行为不可预测
        assertTrue(RecordCoalescer.shouldCoalesce(lastWriteAt = t0, now = t0 + window))
    }

    @Test
    fun `超出窗口另起一行`() {
        assertFalse(RecordCoalescer.shouldCoalesce(lastWriteAt = t0, now = t0 + window + 1))
        assertFalse(RecordCoalescer.shouldCoalesce(lastWriteAt = t0, now = t0 + window * 3))
    }

    @Test
    fun `时钟回拨时不合并`() {
        // now 早于 lastWriteAt（NTP 校时 / 用户改时间）→ 必须另起一行。
        // 若判成合并，映射会一直成立，新通知会持续被写进旧行（用户看到"丢通知"）。
        assertFalse(RecordCoalescer.shouldCoalesce(lastWriteAt = t0, now = t0 - 1))
        assertFalse(RecordCoalescer.shouldCoalesce(lastWriteAt = t0, now = 0L))
    }

    @Test
    fun `自定义窗口生效`() {
        assertTrue(RecordCoalescer.shouldCoalesce(lastWriteAt = t0, now = t0 + 5_000, windowMs = 5_000))
        assertFalse(RecordCoalescer.shouldCoalesce(lastWriteAt = t0, now = t0 + 5_001, windowMs = 5_000))
    }

    @Test
    fun `一条持续刷新的通知只占一行 — 模拟一小时进度更新`() {
        // 模拟：每 1.2 秒一次内容更新（管线节流后的真实频率），持续 1 小时。
        // 期望：因为每次都落在窗口内，所以**一次都不插新行**——流水里始终只有 1 行。
        // 这正是修复前做不到的事（修复前 = 3000 行，把 1000 条的流水表和记录页全刷满）。
        val interval = 1_200L
        val oneHour = 60 * 60 * 1000L
        var lastWriteAt = t0
        var coalesceCount = 0
        var newRowCount = 0
        var t = t0
        while (t - t0 < oneHour) {
            t += interval
            if (RecordCoalescer.shouldCoalesce(lastWriteAt, t)) {
                coalesceCount++
            } else {
                newRowCount++
            }
            // 无论合不合并，管线都会把 lastWriteAt 推到 now（合并时改内存值，插行时登记新行）
            lastWriteAt = t
        }
        assertEquals("一小时 1.2 秒一次 = 3000 次合并", 3_000, coalesceCount)
        assertEquals("一小时长任务在流水里必须只占一行", 0, newRowCount)
    }

    @Test
    fun `长时间没有更新的同 key 通知再次更新时另起一行`() {
        // 真实场景：某条常驻通知昨晚更新过一行，今天又更新 → 不能把那行老记录改写掉
        // （它沉在列表底部，用户会以为新通知丢了）。超出窗口即换行。
        val yesterday = t0 - 24 * 60 * 60 * 1000L
        assertFalse(RecordCoalescer.shouldCoalesce(lastWriteAt = yesterday, now = t0))
    }
}
