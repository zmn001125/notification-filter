package com.local.notificationfilter.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * SnoozeCalculator V3 单测（架构 §3.1，C1）。
 *
 * 用固定时区 [ZONE]（Asia/Shanghai）保证跨测试机可复现；
 * `epoch(date, h, m)` 构造该时区下指定日期时刻的 epoch 毫秒。
 */
class SnoozeCalculatorTest {

    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")
    private val baseDate: LocalDate = LocalDate.of(2026, 10, 17) // 周六

    private fun epoch(date: LocalDate, hour: Int, minute: Int): Long =
        date.atTime(LocalTime.of(hour, minute)).atZone(zone).toInstant().toEpochMilli()

    private fun epoch(hour: Int, minute: Int): Long = epoch(baseDate, hour, minute)

    private val times = listOf("09:00", "12:00", "18:00")

    // ==================== C1：取最近下一时刻 ====================

    @Test
    fun 当天有未来时刻_取最小的那个() {
        // now=13:00，当天未来有 18:00（12:00 已过）→ 18:00
        val now = epoch(13, 0)
        val expected = epoch(18, 0)
        assertEquals(expected, SnoozeCalculator.resolveNearest(times, now, zone))
    }

    @Test
    fun 当天时刻全已过_取次日最早() {
        // now=19:00，当天全部已过 → 次日 09:00
        val now = epoch(19, 0)
        val expected = epoch(baseDate.plusDays(1), 9, 0)
        assertEquals(expected, SnoozeCalculator.resolveNearest(times, now, zone))
    }

    @Test
    fun 次日早上_取当天首个未来时刻() {
        // now=次日 08:00 → 当天 09:00
        val now = epoch(baseDate.plusDays(1), 8, 0)
        val expected = epoch(baseDate.plusDays(1), 9, 0)
        assertEquals(expected, SnoozeCalculator.resolveNearest(times, now, zone))
    }

    @Test
    fun 列表含非法时刻_跳过该项其余正常() {
        // "25:00" 非法跳过；"12:00"/"18:00" 仍参与，now=13:00 → 18:00
        val now = epoch(13, 0)
        val withIllegal = listOf("25:00", "12:00", "18:00", "abc")
        assertEquals(epoch(18, 0), SnoozeCalculator.resolveNearest(withIllegal, now, zone))
    }

    @Test
    fun 空列表_返回null() {
        assertNull(SnoozeCalculator.resolveNearest(emptyList(), epoch(13, 0), zone))
    }

    @Test
    fun 全部非法_返回null() {
        assertNull(SnoozeCalculator.resolveNearest(listOf("25:00", "abc"), epoch(13, 0), zone))
    }

    @Test
    fun 恰好等于某时刻_该时刻视为已过取下一个() {
        // now=12:00 整，12:00 不算未来（>now 严格）→ 取 18:00
        val now = epoch(12, 0)
        assertEquals(epoch(18, 0), SnoozeCalculator.resolveNearest(times, now, zone))
    }

    // ==================== resolveSnooze：times 非空走 resolveNearest，空回退 V2 ====================

    @Test
    fun resolveSnooze_times非空_走resolveNearest() {
        val cfg = SnoozeConfig(times = times)
        val now = epoch(13, 0)
        assertEquals(epoch(18, 0), SnoozeCalculator.resolveSnooze(cfg, now, zone))
    }

    @Test
    fun resolveSnooze_times空_回退V2resolve_相对时长() {
        // 旧规则：preset=10m → now + 10 分钟（V2 行为不变，D1 回退）
        val cfg = SnoozeConfig(preset = SnoozePresets.M10)
        val now = epoch(13, 0)
        assertEquals(now + 10 * 60_000L, SnoozeCalculator.resolveSnooze(cfg, now, zone))
    }

    @Test
    fun resolveSnooze_times空_回退V2resolve_今晚8点() {
        val cfg = SnoozeConfig(preset = SnoozePresets.TONIGHT_20)
        val now = epoch(13, 0) // 13:00，当天 20:00 仍未来
        assertEquals(epoch(20, 0), SnoozeCalculator.resolveSnooze(cfg, now, zone))
    }

    @Test
    fun describeSnooze_times非空_显示下一时刻() {
        val cfg = SnoozeConfig(times = times)
        val now = epoch(13, 0)
        assertEquals("下一时刻 18:00", SnoozeCalculator.describeSnooze(cfg, now, zone))
    }

    @Test
    fun describeSnooze_times空_回退V2describe() {
        val cfg = SnoozeConfig(preset = SnoozePresets.M10)
        val now = epoch(13, 0)
        assertEquals("10 分钟后", SnoozeCalculator.describeSnooze(cfg, now, zone))
    }

    @Test
    fun describeSnooze_空配置_未设置() {
        val cfg = SnoozeConfig()
        assertEquals("未设置", SnoozeCalculator.describeSnooze(cfg, epoch(13, 0), zone))
    }
}
