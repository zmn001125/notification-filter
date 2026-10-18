package com.local.notificationfilter.ui.screens.search

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.local.notificationfilter.NfApplication
import com.local.notificationfilter.core.model.MatchField
import com.local.notificationfilter.core.model.NotificationRecord
import com.local.notificationfilter.core.model.ResultKind
import com.local.notificationfilter.core.model.Rule
import com.local.notificationfilter.core.model.SearchFilter
import com.local.notificationfilter.util.AppUtils
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/** 时间范围选项（P9）。 */
enum class TimeRange(val label: String) {
    TODAY("今天"),
    WEEK("本周"),
    CUSTOM("自定义起止"),
}

/** P9 搜索页状态。 */
data class SearchUiState(
    val keyword: String = "",
    /** 空集合 = 全部字段。 */
    val fields: Set<MatchField> = emptySet(),
    val packages: Set<String> = emptySet(),
    val packageNames: List<String> = emptyList(),
    val timeRange: TimeRange = TimeRange.TODAY,
    val customFromText: String = "",
    val customToText: String = "",
    val results: Set<ResultKind> = emptySet(),
    val ruleId: Long? = null,
    val ruleName: String? = null,
    val rules: List<Rule> = emptyList(),
    val presetPackageName: String? = null,
    val searching: Boolean = false,
    val searched: Boolean = false,
    val matches: List<NotificationRecord> = emptyList(),
    val error: String? = null,
) {
    /** 字段 chips 的「全部字段」选中态。 */
    val allFieldsSelected: Boolean get() = fields.isEmpty() || fields.size == MatchField.ALL.size
}

/**
 * P9 搜索 ViewModel：关键词 + 字段范围 + App + 时间 + 动作 + 命中规则，条件叠加；
 * 结构化条件走 SQL，关键词按字段在内存匹配（RecordRepository.search）。
 */
class SearchViewModel(application: Application) : AndroidViewModel(application) {

    private val container = (application as NfApplication).container
    private val recordRepository = container.recordRepository
    private val ruleRepository = container.ruleRepository

    private val _uiState = MutableStateFlow(SearchUiState())
    val uiState: StateFlow<SearchUiState> = _uiState.asStateFlow()

    private var initialized = false

    /** 首次初始化：拉规则列表（命中规则选择器用）；presetPackage 预填 App（统计页入口）。 */
    fun init(presetPackage: String?) {
        if (initialized) return
        initialized = true
        viewModelScope.launch {
            val rules = ruleRepository.getAll()
            val presetName = presetPackage?.let { AppUtils.appNameOf(getApplication(), it) }
            _uiState.update { s ->
                s.copy(
                    rules = rules,
                    presetPackageName = presetName,
                    packages = presetPackage?.let { setOf(it) } ?: s.packages,
                    packageNames = presetName?.let { listOf(it) } ?: s.packageNames,
                )
            }
        }
    }

    fun setKeyword(text: String) = _uiState.update { it.copy(keyword = text) }

    /** 单个字段 chip 勾选/取消。 */
    fun toggleField(field: MatchField) = _uiState.update { s ->
        val next = if (field in s.fields) s.fields - field else s.fields + field
        s.copy(fields = next)
    }

    /** 「全部字段」chip：全选 / 全不选（空集合=全部）。 */
    fun toggleAllFields() = _uiState.update { s ->
        s.copy(fields = if (s.allFieldsSelected) emptySet() else MatchField.ALL.toSet())
    }

    fun addPackage(pkg: String) = _uiState.update { s ->
        if (pkg in s.packages) s
        else s.copy(
            packages = s.packages + pkg,
            packageNames = s.packageNames + AppUtils.appNameOf(getApplication(), pkg),
        )
    }

    fun removePackage(pkg: String) = _uiState.update { s ->
        s.copy(packages = s.packages - pkg)
    }

    fun setTimeRange(range: TimeRange) = _uiState.update { it.copy(timeRange = range) }

    fun setCustomFrom(text: String) = _uiState.update { it.copy(customFromText = text) }

    fun setCustomTo(text: String) = _uiState.update { it.copy(customToText = text) }

    fun toggleResult(kind: ResultKind) = _uiState.update { s ->
        s.copy(results = if (kind in s.results) s.results - kind else s.results + kind)
    }

    fun setRule(rule: Rule?) = _uiState.update {
        it.copy(ruleId = rule?.id, ruleName = rule?.name)
    }

    fun consumeError() = _uiState.update { it.copy(error = null) }

    fun search() {
        val s = _uiState.value
        val range = runCatching { resolveTimeRange(s) }.getOrElse { e ->
            _uiState.update { it.copy(error = e.message ?: "时间格式应为 MM-DD HH:mm") }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(searching = true, error = null) }
            val filter = SearchFilter(
                keyword = s.keyword.takeIf { it.isNotBlank() },
                fields = s.fields.ifEmpty { MatchField.ALL.toSet() },
                packages = s.packages,
                fromTime = range.first,
                toTime = range.second,
                results = s.results,
                ruleId = s.ruleId,
            )
            val matches = recordRepository.search(filter)
            _uiState.update {
                it.copy(searching = false, searched = true, matches = matches)
            }
        }
    }

    /** 时间范围 → (from, to) epoch 毫秒；自定义解析失败抛 IllegalArgumentException。 */
    private fun resolveTimeRange(s: SearchUiState): Pair<Long?, Long?> {
        val zone = ZoneId.systemDefault()
        return when (s.timeRange) {
            TimeRange.TODAY ->
                LocalDate.now(zone).atStartOfDay(zone).toInstant().toEpochMilli() to null

            TimeRange.WEEK -> {
                val today = LocalDate.now(zone)
                val monday = today.minusDays((today.dayOfWeek.value - 1).toLong())
                monday.atStartOfDay(zone).toInstant().toEpochMilli() to null
            }

            TimeRange.CUSTOM ->
                parseDateTime(s.customFromText, zone) to parseDateTime(s.customToText, zone)
        }
    }

    /** 解析「MM-DD HH:mm」或「YYYY-MM-DD HH:mm」；空串返回 null，非法抛 IllegalArgumentException。 */
    private fun parseDateTime(text: String, zone: ZoneId): Long? {
        val t = text.trim()
        if (t.isEmpty()) return null
        val patterns = listOf("yyyy-MM-dd HH:mm", "MM-dd HH:mm")
        patterns.forEach { p ->
            try {
                val dt = LocalDateTime.parse(t, DateTimeFormatter.ofPattern(p))
                val withYear = if (p == "MM-dd HH:mm") dt.withYear(LocalDate.now(zone).year) else dt
                return withYear.atZone(zone).toInstant().toEpochMilli()
            } catch (_: DateTimeParseException) {
            }
        }
        throw IllegalArgumentException("时间格式应为 MM-DD HH:mm")
    }
}
