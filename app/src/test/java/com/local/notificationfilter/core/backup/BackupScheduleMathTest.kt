package com.local.notificationfilter.core.backup

import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 自动备份触发时刻单测（V11）。
 *
 * 为什么必须测：算错了不会崩溃，只会**悄悄地**不备份（或每小时狂备份一次）。
 * 这类「静默失效」在真机上极难发现，所以把六个边界钉死在这里。
 */
class BackupScheduleMathTest {

    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")

    /** 便于断言的本地时间构造。 */
    private fun at(y: Int, m: Int, d: Int, h: Int, min: Int): Long =
        LocalDateTime.of(y, m, d, h, min).atZone(zone).toInstant().toEpochMilli()

    private fun next(now: Long, hour: Int = 3, minute: Int = 0, interval: Int = 24): LocalDateTime =
        java.time.Instant.ofEpochMilli(
            BackupScheduleMath.nextTriggerAt(now, hour, minute, interval, zone),
        ).atZone(zone).toLocalDateTime()

    @Test
    fun `今天还没到点就返回今天的时刻`() {
        val now = at(2026, 10, 18, 1, 0)
        assertEquals(LocalDateTime.of(2026, 10, 18, 3, 0), next(now))
    }

    @Test
    fun `今天已经过点就推到明天同一时刻`() {
        val now = at(2026, 10, 18, 5, 0)
        assertEquals(LocalDateTime.of(2026, 10, 19, 3, 0), next(now))
    }

    @Test
    fun `正好等于当前分钟时推到下一次（严格大于 now）`() {
        // 若用 >= 判定，会在 03:00:00 这一刻算出"下一次=现在"，导致立刻再触发一次
        val now = at(2026, 10, 18, 3, 0)
        val result = next(now)
        assertEquals(LocalDateTime.of(2026, 10, 19, 3, 0), result)
    }

    @Test
    fun `间隔 72 小时时跳过三天`() {
        val now = at(2026, 10, 18, 5, 0)
        assertEquals(LocalDateTime.of(2026, 10, 21, 3, 0), next(now, interval = 72))
    }

    @Test
    fun `间隔 168 小时时跳到下周同一天`() {
        val now = at(2026, 10, 18, 5, 0)
        assertEquals(LocalDateTime.of(2026, 10, 25, 3, 0), next(now, interval = 168))
    }

    @Test
    fun `跨月边界正确`() {
        val now = at(2026, 10, 31, 23, 59)
        assertEquals(LocalDateTime.of(2026, 11, 1, 3, 0), next(now))
    }

    @Test
    fun `跨年边界正确`() {
        val now = at(2026, 12, 31, 23, 0)
        assertEquals(LocalDateTime.of(2027, 1, 1, 3, 0), next(now))
    }

    @Test
    fun `非法间隔与越界时刻被收敛`() {
        val now = at(2026, 10, 18, 1, 0)
        // interval<=0 → 按 24 兜底；hour/minute 越界被夹到合法区间
        val result = next(now, hour = 99, minute = -5, interval = 0)
        assertEquals(LocalDateTime.of(2026, 10, 18, 23, 0), result)
    }

    @Test
    fun `结果永远严格晚于 now`() {
        val samples = listOf(
            at(2026, 10, 18, 0, 0),
            at(2026, 10, 18, 3, 0),
            at(2026, 10, 18, 12, 30),
            at(2026, 10, 18, 23, 59),
        )
        samples.forEach { now ->
            val trigger = BackupScheduleMath.nextTriggerAt(now, 3, 0, 24, zone)
            assertTrue("now=$now 时算出 $trigger，必须更晚", trigger > now)
        }
    }
}
