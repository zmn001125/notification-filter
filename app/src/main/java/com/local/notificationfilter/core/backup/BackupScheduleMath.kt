package com.local.notificationfilter.core.backup

import java.time.Instant
import java.time.ZoneId

/**
 * 自动备份的「下一次触发时刻」计算（V11）。**纯函数，可纯 JVM 单测。**
 *
 * 语义：从「今天 hour:minute」起算，若已过去就按 [intervalHours] 往后推，
 * 直到**严格晚于** now。因为间隔都是 24 的整数倍，时刻永远落在同一个钟点上
 * （每天 03:00 / 每 3 天 03:00 / 每周 03:00），不会越跑越偏。
 *
 * 为什么不做「每 N 天固定日期」：用户只关心「几点备份」，不关心星期几。
 * 用「从现在起逐次 +interval」最简单，也不会因为漏跑一次就整体错位。
 */
object BackupScheduleMath {

    /**
     * @param nowMillis 当前时刻
     * @param hour 目标小时 0..23
     * @param minute 目标分钟 0..59
     * @param intervalHours 间隔小时数（24 / 72 / 168），非法值按 24 兜底
     */
    fun nextTriggerAt(
        nowMillis: Long,
        hour: Int,
        minute: Int,
        intervalHours: Int,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Long {
        val step = if (intervalHours >= 1) intervalHours.toLong() else 24L
        val h = hour.coerceIn(0, 23)
        val m = minute.coerceIn(0, 59)
        val today = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
        var candidate = today.atTime(h, m)
        // 最多循环 8 次（按周间隔也就 1~2 次），加个硬上限防注入的异常间隔
        var guard = 0
        while (candidate.atZone(zone).toInstant().toEpochMilli() <= nowMillis && guard < 64) {
            candidate = candidate.plusHours(step)
            guard++
        }
        return candidate.atZone(zone).toInstant().toEpochMilli()
    }
}
