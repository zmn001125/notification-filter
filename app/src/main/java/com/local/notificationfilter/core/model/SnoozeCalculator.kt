package com.local.notificationfilter.core.model

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * 推迟时长计算（纯函数，JVM 可测）。
 *
 * 「今晚 8 点」= 当天 20:00，已过则次日 20:00（架构共享约定 4）；
 * 自定义时刻同理：当天已过则取次日同一时刻。
 */
object SnoozeCalculator {

    private const val MINUTE_MS = 60_000L
    private const val DAY_MS = 24 * 60 * 60 * 1000L

    /** 解析出绝对触发时间（epoch 毫秒）；配置不完整返回 null。 */
    fun resolve(
        cfg: SnoozeConfig,
        now: Long,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Long? {
        return when (cfg.preset) {
            SnoozePresets.M10 -> now + 10 * MINUTE_MS
            SnoozePresets.M30 -> now + 30 * MINUTE_MS
            SnoozePresets.H1 -> now + 60 * MINUTE_MS
            SnoozePresets.TONIGHT_20 -> atTimeOrNextDay(now, zone, 20, 0)
            SnoozePresets.CUSTOM_MINUTES ->
                cfg.customMinutes?.takeIf { it > 0 }?.let { now + it * MINUTE_MS }

            SnoozePresets.CUSTOM_TIME -> parseHHmm(cfg.customTimeHHmm)?.let { (h, m) ->
                atTimeOrNextDay(now, zone, h, m)
            }

            else -> null
        }
    }

    /** 「HH:mm」→ (时, 分)；非法返回 null。 */
    fun parseHHmm(text: String?): Pair<Int, Int>? {
        val parts = text?.split(":") ?: return null
        if (parts.size != 2) return null
        val h = parts[0].trim().toIntOrNull() ?: return null
        val m = parts[1].trim().toIntOrNull() ?: return null
        if (h !in 0..23 || m !in 0..59) return null
        return h to m
    }

    /** 触发时刻的可读描述，如「今晚 20:00」「35 分钟后」。 */
    fun describe(cfg: SnoozeConfig, now: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        val triggerAt = resolve(cfg, now, zone) ?: return "未设置"
        val deltaMin = (triggerAt - now) / MINUTE_MS
        return when (cfg.preset) {
            SnoozePresets.M10 -> "10 分钟后"
            SnoozePresets.M30 -> "30 分钟后"
            SnoozePresets.H1 -> "1 小时后"
            SnoozePresets.TONIGHT_20 -> "今晚 20:00"
            SnoozePresets.CUSTOM_MINUTES -> "$deltaMin 分钟后"
            SnoozePresets.CUSTOM_TIME -> formatTime(triggerAt, zone)
            else -> formatTime(triggerAt, zone)
        }
    }

    /** epoch 毫秒 → 「HH:mm」。 */
    fun formatTime(epochMs: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        val t = Instant.ofEpochMilli(epochMs).atZone(zone).toLocalTime()
        return "%02d:%02d".format(t.hour, t.minute)
    }

    /** epoch 毫秒 → 「MM-DD HH:mm」。 */
    fun formatDateTime(epochMs: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        val dt = Instant.ofEpochMilli(epochMs).atZone(zone).toLocalDateTime()
        return "%02d-%02d %02d:%02d".format(dt.monthValue, dt.dayOfMonth, dt.hour, dt.minute)
    }

    /** 列表用时间：当天 → 「HH:mm」，否则 → 「MM-DD HH:mm」。 */
    fun formatListTime(epochMs: Long, now: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        val dt = Instant.ofEpochMilli(epochMs).atZone(zone)
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        return if (dt.toLocalDate() == today) {
            "%02d:%02d".format(dt.hour, dt.minute)
        } else {
            "%02d-%02d %02d:%02d".format(dt.monthValue, dt.dayOfMonth, dt.hour, dt.minute)
        }
    }

    /** epoch 毫秒 → 相对时间描述（刚刚 / n 分钟前 / n 小时前 / n 天前 / 日期）。 */
    fun relativeTime(epochMs: Long, now: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        val delta = now - epochMs
        return when {
            delta < 0 -> formatDateTime(epochMs, zone)
            delta < MINUTE_MS -> "刚刚"
            delta < 60 * MINUTE_MS -> "${delta / MINUTE_MS} 分钟前"
            delta < DAY_MS -> "${delta / (60 * MINUTE_MS)} 小时前"
            delta < 7 * DAY_MS -> "${delta / DAY_MS} 天前"
            else -> formatDateTime(epochMs, zone)
        }
    }

    private fun atTimeOrNextDay(now: Long, zone: ZoneId, hour: Int, minute: Int): Long {
        val date = LocalDate.ofInstant(Instant.ofEpochMilli(now), zone)
        val today = date.atTime(LocalTime.of(hour, minute)).atZone(zone).toInstant().toEpochMilli()
        return if (today > now) today
        else date.plusDays(1).atTime(LocalTime.of(hour, minute)).atZone(zone).toInstant()
            .toEpochMilli()
    }

    // ==================== V3：时刻列表 + 取最近下一时刻（架构 §3.1，C1） ====================

    /**
     * 时刻列表中「最近的下一时刻」（epoch 毫秒；C1）：
     * - 当天仍未来 → 取当天中 > now 的最小时刻；
     * - 当天全部已过 → 取次日列表中最早时刻；
     * - 列表为空 / 全部非法 → null。
     * 非法时刻（如 "25:00"、"abc"）跳过该项，其余正常。
     */
    fun resolveNearest(
        times: List<String>,
        now: Long,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Long? {
        val nowZoned = Instant.ofEpochMilli(now).atZone(zone)
        val today = nowZoned.toLocalDate()
        // 当天未来时刻候选
        val todayCandidates = mutableListOf<Pair<Int, Int>>()
        for (t in times) {
            val hm = parseHHmm(t) ?: continue
            val todayEpoch = today.atTime(LocalTime.of(hm.first, hm.second))
                .atZone(zone).toInstant().toEpochMilli()
            if (todayEpoch > now) todayCandidates += hm
        }
        if (todayCandidates.isNotEmpty()) {
            val (h, m) = todayCandidates.minBy { it.first * 60 + it.second }
            return today.atTime(LocalTime.of(h, m)).atZone(zone).toInstant().toEpochMilli()
        }
        // 当天全过 → 次日列表中最早时刻
        val tomorrow = today.plusDays(1)
        val tomorrowCandidates = mutableListOf<Pair<Int, Int>>()
        for (t in times) {
            val hm = parseHHmm(t) ?: continue
            tomorrowCandidates += hm
        }
        if (tomorrowCandidates.isEmpty()) return null
        val (h, m) = tomorrowCandidates.minBy { it.first * 60 + it.second }
        return tomorrow.atTime(LocalTime.of(h, m)).atZone(zone).toInstant().toEpochMilli()
    }

    /** V3 统一入口：times 非空走 resolveNearest；否则回退 V2 resolve（旧规则兼容，D1）。 */
    fun resolveSnooze(
        cfg: SnoozeConfig,
        now: Long,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Long? = if (cfg.times.isNotEmpty()) resolveNearest(cfg.times, now, zone)
    else resolve(cfg, now, zone)

    /** V3 可读描述：times 非空 → 「下一时刻 HH:mm」；否则回退 V2 describe。 */
    fun describeSnooze(cfg: SnoozeConfig, now: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        if (cfg.times.isNotEmpty()) {
            val triggerAt = resolveNearest(cfg.times, now, zone) ?: return "未设置"
            return "下一时刻 ${formatTime(triggerAt, zone)}"
        }
        return describe(cfg, now, zone)
    }
}
