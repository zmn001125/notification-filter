package com.local.notificationfilter.ui.screens.stats

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.local.notificationfilter.NfApplication
import com.local.notificationfilter.core.model.AppStat
import com.local.notificationfilter.ui.components.TrendPoint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import java.time.LocalDate
import java.time.ZoneId

/**
 * P11 统计页 ViewModel。
 *
 * 总览与按 App 列表：走 StatsRepository.byApp(fromDay)（一次查询同时得到两处数据，
 * 保证「总览 = 各 App 之和」口径一致）；范围今天/本周/总计 → 起始 epochDay。
 * 趋势图：按天取近 7 天、按周取近 4 周（周一起算，见 StatsRepository.trend 补零）。
 * 数据为一次性挂起查询，靠页面 ON_RESUME 的 refreshTick 触发重查。
 */
class StatsViewModel(application: Application) : AndroidViewModel(application) {

    enum class Range(val label: String) {
        TODAY("今天"),
        WEEK("本周"),
        ALL("总计"),
    }

    enum class AppSort(val label: String) {
        FILTERED("按过滤"),
        PASSED("按放行"),
    }

    enum class TrendMode(val label: String) {
        DAY("近 7 天"),
        WEEK("近 4 周"),
    }

    data class StatsUiState(
        val range: Range = Range.WEEK,
        val filtered: Int = 0,
        val passed: Int = 0,
        val apps: List<AppStat> = emptyList(),
        val appSort: AppSort = AppSort.FILTERED,
        val trendMode: TrendMode = TrendMode.DAY,
        val trend: List<TrendPoint> = emptyList(),
    ) {
        val total: Int get() = filtered + passed

        /** 过滤率百分比（0..100；无数据时为 0，UI 显示「—」）。 */
        val filterRatePercent: Int get() = if (total == 0) 0 else filtered * 100 / total
    }

    private val container = (application as NfApplication).container
    private val statsRepository = container.statsRepository

    private val rangeFlow = MutableStateFlow(Range.WEEK)
    private val appSortFlow = MutableStateFlow(AppSort.FILTERED)
    private val trendModeFlow = MutableStateFlow(TrendMode.DAY)
    private val refreshTick = MutableStateFlow(System.currentTimeMillis())

    val uiState: StateFlow<StatsUiState> = combine(
        rangeFlow,
        appSortFlow,
        trendModeFlow,
        refreshTick,
    ) { range, appSort, trendMode, _ ->
        load(range, appSort, trendMode)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = StatsUiState(),
    )

    fun selectRange(range: Range) {
        rangeFlow.value = range
    }

    fun selectAppSort(sort: AppSort) {
        appSortFlow.value = sort
    }

    fun selectTrendMode(mode: TrendMode) {
        trendModeFlow.value = mode
    }

    /** 页面 ON_RESUME 时调用：重新挂起查询，拉取最新计数。 */
    fun refresh() {
        refreshTick.value = System.currentTimeMillis()
    }

    private suspend fun load(
        range: Range,
        appSort: AppSort,
        trendMode: TrendMode,
    ): StatsUiState {
        val today = LocalDate.now(ZoneId.systemDefault())
        val fromDay = when (range) {
            Range.TODAY -> today.toEpochDay().toInt()
            Range.WEEK -> mondayOf(today).toEpochDay().toInt()
            Range.ALL -> 0
        }
        val apps = statsRepository.byApp(fromDay).sortedWith(appComparator(appSort))
        return StatsUiState(
            range = range,
            filtered = apps.sumOf { it.filtered },
            passed = apps.sumOf { it.passed },
            apps = apps,
            appSort = appSort,
            trendMode = trendMode,
            trend = buildTrend(today, trendMode),
        )
    }

    private suspend fun buildTrend(today: LocalDate, mode: TrendMode): List<TrendPoint> =
        when (mode) {
            TrendMode.DAY -> {
                val from = today.minusDays(6)
                statsRepository.trend(
                    fromDay = from.toEpochDay().toInt(),
                    toDay = today.toEpochDay().toInt(),
                ).map { TrendPoint(label = dayLabel(it.epochDay), filtered = it.filtered, passed = it.passed) }
            }

            TrendMode.WEEK -> {
                // 本周周一往前推 21 天，覆盖完整的 4 个周一起算周（含本周，可能不满 7 天）。
                val from = mondayOf(today).minusDays(21)
                statsRepository.trend(
                    fromDay = from.toEpochDay().toInt(),
                    toDay = today.toEpochDay().toInt(),
                )
                    .groupBy { mondayOfEpochDay(it.epochDay) }
                    .toSortedMap()
                    .map { (monday, days) ->
                        TrendPoint(
                            label = dayLabel(monday.toEpochDay().toInt()),
                            filtered = days.sumOf { it.filtered },
                            passed = days.sumOf { it.passed },
                        )
                    }
            }
        }

    private fun appComparator(sort: AppSort): Comparator<AppStat> = when (sort) {
        AppSort.FILTERED -> compareByDescending<AppStat> { it.filtered }
            .thenByDescending { it.total }
            .thenBy { it.appName }

        AppSort.PASSED -> compareByDescending<AppStat> { it.passed }
            .thenByDescending { it.total }
            .thenBy { it.appName }
    }

    private fun mondayOf(date: LocalDate): LocalDate =
        date.minusDays((date.dayOfWeek.value - 1).toLong())

    private fun mondayOfEpochDay(epochDay: Int): LocalDate =
        mondayOf(LocalDate.ofEpochDay(epochDay.toLong()))

    private fun dayLabel(epochDay: Int): String {
        val date = LocalDate.ofEpochDay(epochDay.toLong())
        return "%02d-%02d".format(date.monthValue, date.dayOfMonth)
    }
}
