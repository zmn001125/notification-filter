package com.local.notificationfilter.ui.screens.record

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.local.notificationfilter.NfApplication
import com.local.notificationfilter.core.model.NotificationRecord
import com.local.notificationfilter.core.model.ResultKind
import com.local.notificationfilter.core.model.SearchFilter
import com.local.notificationfilter.service.ActiveNotificationsProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import java.time.Instant
import java.time.ZoneId

/**
 * 记录页的「处理动作」过滤器（V9 需求 6：顶部快捷过滤，多选 = OR）。
 *
 * 与 [ResultKind] 的关系刻意做成「UI 过滤维度 ⊇ 落库结果枚举」：
 * - 前 6 项与 [ResultKind] 一一对应（[kind] 非空）；
 * - [UNMATCHED]（未命中）**不是**一个 [ResultKind]** —— 数据层没有这个枚举值，
 *   它由 `NotificationRecord.ruleId == null`（没有任何规则命中）+ `!blacklistHit`
 *   （也不在黑名单命中路径上）表达。这类记录落库时 `result` 为
 *   [ResultKind.PASS]，因此它同时也是「放行」的子集 —— 两个 chip 同时选中属于
 *   OR 语义下的正常重叠，不是 bug（架构 D：结果枚举不变，过滤维度在 UI 层派生）。
 *
 * [name] 即路由参数 `record?result=XXX` 的取值（见 NfRoutes.record）。
 */
enum class ResultFilter(val label: String, val kind: ResultKind?) {
    BLOCK("消除", ResultKind.BLOCK),
    SNOOZE("推迟", ResultKind.SNOOZE),
    REPLACE("替换", ResultKind.REPLACE),
    READ("朗读", ResultKind.READ),
    SHOW("屏幕显示", ResultKind.SHOW),
    PASS("放行", ResultKind.PASS),

    /** 未命中：没有命中任何规则（ruleId == null）且非黑名单命中。数据层无同名枚举。 */
    UNMATCHED("未命中", null),
    ;

    /**
     * 该记录是否命中本过滤器（多选时由调用方做 OR）。
     *
     * V11：改成**按效果集合**匹配 `record.effectKinds`，不再是「主结论相等」。
     * 例：「消除 + 朗读」的规则命中后 `result` 只有 `BLOCK`，但 `effectKinds`
     * 是 `[BLOCK, READ]` —— 这样选「朗读」也能筛出它，正是用户要的「过滤多个效果」。
     *
     * 由此带来的语义变化（**是预期行为，不是 bug**）：
     * - 「放行」与「未命中」可能重叠（仅朗读失败的记录 `result=PASS` 但没有命中规则）；
     * - 只配弹幕的规则 `result=SHOW`，选「屏幕显示」可筛出；
     * - 「消除」与「屏幕显示」可同时命中同一条记录。
     */
    fun matches(record: NotificationRecord): Boolean = if (kind == null) {
        // 未命中：既没有规则命中，也不在黑名单路径上（blacklistHit 的记录总会带 result=BLOCK）
        record.ruleId == null && !record.blacklistHit
    } else {
        kind in record.effectKinds
    }

    companion object {
        /** 路由参数 → 过滤器；空串 / null / 未知名字一律返回 null（= 不过滤，失败安全）。 */
        fun fromName(name: String?): ResultFilter? =
            if (name.isNullOrBlank()) {
                null
            } else {
                entries.firstOrNull { it.name == name }
            }

        /** 逗号分隔的路由参数 → 过滤器集合（路由目前只传单值，保留多值扩展位）。 */
        fun fromNames(raw: String?): Set<ResultFilter> {
            if (raw.isNullOrBlank()) return emptySet()
            return raw.split(',')
                .mapNotNull { fromName(it.trim()) }
                .toSet()
        }
    }
}

/**
 * P3/P4 记录页 ViewModel：双子页签（最近通知 / 明细流水）+ 今天/本周/全部筛选
 * + V9 需求 6 的「处理动作」多选过滤。
 *
 * 明细流水基于 observeRecent 全量候选（<= 1000 条）在内存按起始时间过滤，
 * 既自动跟随新流水刷新，又避免为三个范围各写一条 SQL。
 * 动作过滤在同一份内存快照上再叠一层（两个页签共用同一组过滤状态）。
 */
class RecordViewModel(application: Application) : AndroidViewModel(application) {

    enum class Tab { RECENT, DETAIL }

    enum class Range(val label: String) {
        TODAY("今天"),
        WEEK("本周"),
        ALL("全部"),
    }

    data class RecordUiState(
        val tab: Tab = Tab.RECENT,
        val range: Range = Range.ALL,
        /** 已叠好「时间范围 + 动作过滤」的最近通知（<= [RECENT_LIMIT] 条）。 */
        val recent: List<NotificationRecord> = emptyList(),
        /** 已叠好「时间范围 + 动作过滤」的明细流水。 */
        val detail: List<NotificationRecord> = emptyList(),
        val refreshedAt: Long = System.currentTimeMillis(),
        val listening: Boolean = false,
        /** V9：当前选中的动作过滤器（空 = 不过滤）。 */
        val selectedResults: Set<ResultFilter> = emptySet(),
    ) {
        /** 动作过滤是否生效（UI 用它决定是否显示「已筛选 N 条」与专用空态文案）。 */
        val filterActive: Boolean get() = selectedResults.isNotEmpty()
    }

    private val container = (application as NfApplication).container
    private val recordRepository = container.recordRepository

    private val tabFlow = MutableStateFlow(Tab.RECENT)
    private val rangeFlow = MutableStateFlow(Range.ALL)
    private val refreshTick = MutableStateFlow(System.currentTimeMillis())

    /** V9 需求 6：多选动作过滤状态（空集合 = 不过滤）。 */
    private val resultFilterFlow = MutableStateFlow<Set<ResultFilter>>(emptySet())

    /** 过滤状态的独立只读视图（chip 组可直接订阅，无需依赖整个 [RecordUiState]）。 */
    val selectedResults: StateFlow<Set<ResultFilter>> = resultFilterFlow.asStateFlow()

    /**
     * 全量候选（最近 [DETAIL_LIMIT] 条）单路订阅。
     *
     * V6.1 优化：原实现同时订阅 `observeRecent(100)` 与 `observeRecent(1000)`，
     * 同一张表查两遍；每次 emission 要对约 1100 行各做 2 次 JSON 解析（executedActions/hitFields）。
     * 改为单路后「最近通知」从同一份候选里 `take(RECENT_LIMIT)`，
     * 解析量直接减半，且两列表永远来自同一快照（不会出现两个列表短暂不一致）。
     * 等价性依据：DAO 查询为 `ORDER BY recordId DESC LIMIT n`，故 `take(100)` 与 `limit=100` 结果一致。
     */
    private val allFlow = recordRepository.observeRecent(DETAIL_LIMIT)

    val uiState: StateFlow<RecordUiState> = combine(
        tabFlow,
        rangeFlow,
        refreshTick,
        allFlow,
        resultFilterFlow,
    ) { tab, range, tick, all, filters ->
        val from = rangeStart(range)
        RecordUiState(
            tab = tab,
            range = range,
            // 先截取（最近 N 条），再叠动作过滤：口径与 V8 一致 —— 过滤只影响「显示什么」，
            // 不改变候选池大小，避免「筛掉几条后又从更早的流水里补进来」造成的跳动。
            recent = all.take(RECENT_LIMIT).applyResultFilter(filters),
            detail = all.filter { it.postTime >= from }.applyResultFilter(filters),
            refreshedAt = tick,
            listening = ActiveNotificationsProvider.isAttached,
            selectedResults = filters,
        )
    }
        // V11 性能：上面这段 combine 里的筛选（对最近 1000 条逐条算 effectKinds）
        // 原本跑在 viewModelScope 的 Main 上，高通知量时会卡列表滚动。挪到 Default，
        // UI 只拿最终不可变快照。
        .flowOn(Dispatchers.Default)
        .stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = RecordUiState(),
    )

    fun selectTab(tab: Tab) {
        tabFlow.value = tab
    }

    fun selectRange(range: Range) {
        rangeFlow.value = range
    }

    /**
     * 切换单个动作过滤器的选中态（多选；选中集合非空即生效，多个之间为 OR）。
     *
     * 用函数式 `update` 语义手工构造新集合：`Set` 的 `+/-` 已返回新实例，
     * 这里再保证「值未变化时不发射」，避免无意义的整页重组。
     */
    fun toggleResult(filter: ResultFilter) {
        val current = resultFilterFlow.value
        val next = if (filter in current) current - filter else current + filter
        if (next != current) resultFilterFlow.value = next
    }

    /** 清除全部动作过滤（恢复「不过滤」）。 */
    fun clearResultFilter() {
        if (resultFilterFlow.value.isNotEmpty()) resultFilterFlow.value = emptySet()
    }

    /**
     * 加载路由预置过滤（V9 需求 1：从首页「今日消除 / 今日放行」跳进来时预选一个 chip）。
     *
     * 只做赋值而非累加：预置与 chip 组共用同一份状态，进入页面时预选一项，
     * 用户随后可以自由增减。空集合 = 不动（普通入口进入时保持用户上次的手动选择）。
     * 由 [RecordScreen] 用 `LaunchedEffect(presetResults)` 调用，保证同一路由参数只应用一次。
     */
    fun applyPresetResults(preset: Set<ResultFilter>) {
        if (preset.isEmpty()) return
        resultFilterFlow.value = preset
    }

    /** 刷新（更新时间戳文案 + 监听状态；数据本身随数据库 Flow 自动更新）。 */
    fun refresh() {
        refreshTick.value = System.currentTimeMillis()
    }

    /** 范围起始 epoch 毫秒；ALL 返回 0 不过滤。 */
    private fun rangeStart(range: Range, zone: ZoneId = ZoneId.systemDefault()): Long =
        when (range) {
            Range.ALL -> 0L
            Range.TODAY -> Instant.now().atZone(zone).toLocalDate()
                .atStartOfDay(zone).toInstant().toEpochMilli()

            Range.WEEK -> {
                val today = Instant.now().atZone(zone).toLocalDate()
                val monday = today.minusDays((today.dayOfWeek.value - 1).toLong())
                monday.atStartOfDay(zone).toInstant().toEpochMilli()
            }
        }

    /** 供详情/搜索入口复用的空条件（保留扩展位）。 */
    fun emptyFilter(): SearchFilter = SearchFilter()

    /** 在已按时间范围过滤好的列表上再叠一层「动作结果」过滤（空集合 = 原样返回）。 */
    private fun List<NotificationRecord>.applyResultFilter(
        filters: Set<ResultFilter>,
    ): List<NotificationRecord> {
        if (filters.isEmpty()) return this
        return filter { record -> filters.any { it.matches(record) } }
    }

    private companion object {
        /** 「最近通知」页签展示条数（从全量候选里截取）。 */
        const val RECENT_LIMIT = 100

        /** 明细流水候选上限，与流水滚动淘汰保留量保持一致。 */
        const val DETAIL_LIMIT = 1000
    }
}
