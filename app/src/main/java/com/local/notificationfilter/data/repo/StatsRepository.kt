package com.local.notificationfilter.data.repo

import com.local.notificationfilter.core.model.AppStat
import com.local.notificationfilter.core.model.DayStat
import com.local.notificationfilter.data.db.DayStatRaw
import com.local.notificationfilter.data.db.StatsDao
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * 统计仓库：计数自增 + 按 App 汇总 + 按天趋势（空日期补零）。
 *
 * 口径（架构共享约定 6）：filtered = 动作含 BLOCK/SNOOZE/REPLACE 任一或黑名单命中；
 * passed = 其余（仅朗读 / 无动作）。
 */
class StatsRepository(private val dao: StatsDao) {

    suspend fun increment(packageName: String, appName: String, epochDay: Int, filtered: Boolean) {
        dao.increment(packageName, appName, epochDay, filtered)
    }

    suspend fun byApp(fromDay: Int): List<AppStat> =
        dao.byAppRaw(fromDay).map { AppStat(it.packageName, it.appName, it.filtered, it.passed) }

    /** [fromDay]..[toDay] 闭区间逐天返回，无数据补零（趋势图直接可用）。 */
    suspend fun trend(fromDay: Int, toDay: Int): List<DayStat> {
        if (toDay < fromDay) return emptyList()
        val raw = dao.trendRaw(fromDay, toDay).associateBy { it.epochDay }
        return (fromDay..toDay).map { day ->
            raw[day]?.let { DayStat(it.epochDay, it.filtered, it.passed) }
                ?: DayStat(day, 0, 0)
        }
    }

    /** 单日合计（响应式，首页今日概览用）。 */
    fun observeDay(epochDay: Int): Flow<DayStat> =
        dao.observeDay(epochDay).map { DayStat(epochDay, it.filtered, it.passed) }
}
