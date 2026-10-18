package com.local.notificationfilter.ui.screens.record

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.FilterAltOff
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.local.notificationfilter.core.model.NotificationRecord
import com.local.notificationfilter.core.model.SnoozeCalculator
import com.local.notificationfilter.ui.components.EmptyState
import com.local.notificationfilter.ui.components.NfCard
import com.local.notificationfilter.ui.components.NotificationRecordItem
import com.local.notificationfilter.ui.components.RecordResultTag

/**
 * 记录页子页签（最近通知 / 明细流水）。
 * 明细流水带搜索入口（跳 P9）与 今天/本周/全部 筛选；点击条目进 P7。
 *
 * V9 需求 6：在两个页签**下方**新增一行横向可滚动的「处理动作」多选过滤 chip
 * （消除 / 推迟 / 替换 / 朗读 / 屏幕显示 / 放行 / 未命中），两个页签共用同一组
 * 过滤状态（[RecordViewModel.selectedResults]）。多选之间为 OR。
 *
 * @param presetResults 路由预置过滤（V9 需求 1）：首页「今日消除 / 今日放行」跳进来时
 *   带上对应结果，进入页面即预选一个 chip；空集合 = 不预置（普通入口）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordScreen(
    onOpenDetail: (Long) -> Unit,
    onOpenSearch: () -> Unit,
    presetResults: Set<ResultFilter> = emptySet(),
) {
    val vm: RecordViewModel = viewModel()
    val state by vm.uiState.collectAsStateWithLifecycle()

    // 回到本页时刷新时间戳与监听状态文案
    LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_RESUME) { vm.refresh() }

    // V9 需求 1：路由预置过滤（key 为参数内容，同一路由参数只应用一次，
    // 之后用户的 toggle 不会被重组覆盖）
    LaunchedEffect(presetResults) { vm.applyPresetResults(presetResults) }

    Scaffold(
        topBar = { TopAppBar(title = { Text("记录", fontWeight = FontWeight.Bold) }) },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            val tabIndex = if (state.tab == RecordViewModel.Tab.RECENT) 0 else 1
            // V8 需求③：Tab 高度压缩。默认 Tab 是 48dp（为「图标在上 + 文字在下」的
            // 双行布局预留），本页只有「图标 + 文字」横向单行，用 36dp 足够，
            // 且 36dp ≥ 最小可点击热区，不必担心点不中。
            TabRow(selectedTabIndex = tabIndex) {
                Tab(
                    selected = tabIndex == 0,
                    onClick = { vm.selectTab(RecordViewModel.Tab.RECENT) },
                    modifier = Modifier.height(COMPACT_TAB_HEIGHT),
                    text = {
                        CompactTabLabel(
                            icon = Icons.Filled.Notifications,
                            label = "最近通知",
                        )
                    },
                )
                Tab(
                    selected = tabIndex == 1,
                    onClick = { vm.selectTab(RecordViewModel.Tab.DETAIL) },
                    modifier = Modifier.height(COMPACT_TAB_HEIGHT),
                    text = {
                        CompactTabLabel(
                            icon = Icons.Filled.ReceiptLong,
                            label = "明细流水",
                        )
                    },
                )
            }

            // V9 需求 4：Tab 与第一行内容之间留 ≥12dp 间距（原为紧贴）。
            Spacer(Modifier.height(TAB_CONTENT_GAP))

            // —— V9 需求 6：动作过滤 chip 组（两个页签共用） ——
            ResultFilterBar(
                selected = state.selectedResults,
                onToggle = vm::toggleResult,
                onClear = vm::clearResultFilter,
            )

            Spacer(Modifier.height(FILTER_BAR_GAP))

            when (state.tab) {
                RecordViewModel.Tab.RECENT -> RecentTab(
                    records = state.recent,
                    refreshedAt = state.refreshedAt,
                    listening = state.listening,
                    filterActive = state.filterActive,
                    onOpenDetail = onOpenDetail,
                )

                RecordViewModel.Tab.DETAIL -> DetailTab(
                    records = state.detail,
                    range = state.range,
                    filterActive = state.filterActive,
                    onSelectRange = vm::selectRange,
                    onOpenSearch = onOpenSearch,
                    onOpenDetail = onOpenDetail,
                )
            }
        }
    }
}

/**
 * 动作过滤 chip 组（V9 需求 6）。
 *
 * - 横向可滚动：7 个 chip 在窄屏（< 400dp）放不下一行，滚动而非换行，保证纵向空间给列表；
 * - 多选：每个 chip 独立 toggle，选中集合非空即生效，多值之间为 OR；
 * - 右侧「清除筛选」只在有选中时出现（无选中显示清除按钮没有意义，还会误导用户点了会变）。
 */
@Composable
private fun ResultFilterBar(
    selected: Set<ResultFilter>,
    onToggle: (ResultFilter) -> Unit,
    onClear: () -> Unit,
) {
    val scrollState = rememberScrollState()
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .horizontalScroll(scrollState)
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ResultFilter.entries.forEach { filter ->
                val isSelected = filter in selected
                FilterChip(
                    selected = isSelected,
                    onClick = { onToggle(filter) },
                    label = { Text(filter.label) },
                    leadingIcon = if (isSelected) {
                        {
                            Icon(
                                imageVector = Icons.Filled.Check,
                                contentDescription = null,
                                modifier = Modifier.size(FilterChipDefaults.IconSize),
                            )
                        }
                    } else {
                        null
                    },
                )
            }
        }
        if (selected.isNotEmpty()) {
            TextButton(
                onClick = onClear,
                contentPadding = PaddingValues(horizontal = 10.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.FilterAltOff,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.size(4.dp))
                Text(
                    text = "清除筛选",
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        } else {
            Spacer(Modifier.size(16.dp))
        }
    }
}

/**
 * 紧凑 Tab 标签：图标与文字放在**同一行**。
 *
 * 一般写法把 icon / text 分别交给 `Tab` 的 icon、text 两个槽位，M3 会排成
 * 「图标在上、文字在下」的两行结构（默认 48dp 高度就是为它预留的）。
 * 本页只需要「图标 + 文字」横排，合成一个 text 槽即可把高度压到 36dp。
 */
@Composable
private fun CompactTabLabel(icon: ImageVector, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(COMPACT_TAB_ICON_SIZE),
        )
        Spacer(Modifier.size(6.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
        )
    }
}

/** P3 最近通知：实时列表（结果标签 + 命中规则名）。 */
@Composable
private fun RecentTab(
    records: List<NotificationRecord>,
    refreshedAt: Long,
    listening: Boolean,
    filterActive: Boolean,
    onOpenDetail: (Long) -> Unit,
) {
    val now = remember { System.currentTimeMillis() }
    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            text = "${SnoozeCalculator.formatListTime(refreshedAt, now)} 刷新 · " +
                (if (listening) "实时监听中" else "监听服务未连接") +
                " · 点条目看完整字段",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        // V9 需求 6：过滤生效时的条数提示
        FilterCountHint(visibleCount = records.size, active = filterActive, onClear = null)
        if (records.isEmpty()) {
            EmptyState(
                text = if (filterActive) "当前筛选下没有记录，试试清除筛选" else "还没有收到通知",
                icon = if (filterActive) Icons.Filled.FilterAltOff else Icons.Filled.NotificationsOff,
            )
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(records.size, key = { records[it].recordId }) { index ->
                    val record = records[index]
                    NotificationRecordItem(
                        record = record,
                        now = now,
                        onClick = { onOpenDetail(record.recordId) },
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
    }
}

/** P4 明细流水：搜索入口 + 今天/本周/全部 + 紧凑行。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DetailTab(
    records: List<NotificationRecord>,
    range: RecordViewModel.Range,
    filterActive: Boolean,
    onSelectRange: (RecordViewModel.Range) -> Unit,
    onOpenSearch: () -> Unit,
    onOpenDetail: (Long) -> Unit,
) {
    val now = remember { System.currentTimeMillis() }
    Column(modifier = Modifier.fillMaxSize()) {
        // —— 搜索入口（跳 P9） ——
        NfCard(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onOpenSearch),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Filled.Search,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.size(8.dp))
                Text(
                    text = "搜索流水…（关键词 / 应用 / 动作）",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.outline,
                )
            }
        }

        // —— 范围 chips ——
        Row(
            modifier = Modifier.padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            RecordViewModel.Range.entries.forEach { r ->
                FilterChip(
                    selected = range == r,
                    onClick = { onSelectRange(r) },
                    label = { Text(r.label) },
                )
            }
        }

        // V9 需求 6：过滤生效时的条数提示（「已筛选 N 条」）
        FilterCountHint(visibleCount = records.size, active = filterActive, onClear = null)

        if (records.isEmpty()) {
            EmptyState(
                text = if (filterActive) "当前筛选下没有记录，试试清除筛选" else "该范围内没有流水记录",
                icon = if (filterActive) Icons.Filled.FilterAltOff else Icons.Filled.Inbox,
            )
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(records.size, key = { records[it].recordId }) { index ->
                    DetailRecordRow(
                        record = records[index],
                        now = now,
                        onClick = { onOpenDetail(records[index].recordId) },
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
    }
}

/**
 * 「已筛选 N 条」提示（V9 需求 6）。仅在过滤生效时显示，未过滤时不占纵向空间。
 */
@Composable
private fun FilterCountHint(visibleCount: Int, active: Boolean, onClear: (() -> Unit)?) {
    if (!active) return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "已筛选 $visibleCount 条",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f),
        )
        if (onClear != null) {
            TextButton(
                onClick = onClear,
                contentPadding = PaddingValues(horizontal = 8.dp),
            ) { Text("清除", style = MaterialTheme.typography.labelSmall) }
        }
    }
}

/** 明细行（对齐线框稿 P4）：等宽时间 · 应用名 · 结果标签（+ 规则名）+ 摘要。 */
@Composable
private fun DetailRecordRow(
    record: NotificationRecord,
    now: Long,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = SnoozeCalculator.formatListTime(record.postTime, now),
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = " · ",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = record.appName,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Spacer(Modifier.size(4.dp))
            Text(
                text = "· ",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            RecordResultTag(record = record)
            if (!record.ruleName.isNullOrBlank()) {
                Spacer(Modifier.size(5.dp))
                Text(
                    text = record.ruleName,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        val summary = record.textSummary.ifBlank { record.titleSummary }
        if (summary.isNotBlank()) {
            Text(
                text = summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

// ==================== V8 需求③：紧凑 Tab 尺寸 ====================

/**
 * 紧凑 Tab 高度。
 *
 * M3 `Tab` 默认 48dp：那是「图标在上 + 文字在下」两行布局所需；
 * 本页图标与文字同排（见 [CompactTabLabel]），一行 labelMedium 约 20dp，
 * 加 8dp 上下呼吸即 36dp；36dp 同时满足最小可点击热区（手指可稳定点中）。
 */
private val COMPACT_TAB_HEIGHT = 36.dp

/** 紧凑 Tab 图标边长：与 labelMedium 的字高视觉持平。 */
private val COMPACT_TAB_ICON_SIZE = 16.dp

// ==================== V9 需求 4 / 需求 6：间距 ====================

/** V9 需求 4：Tab 与第一行内容（过滤 chip 组）之间的间距，≥12dp。 */
private val TAB_CONTENT_GAP = 12.dp

/** chip 组与页签内容之间的间距（chip 自带内边距，这里只需 2dp 呼吸）。 */
private val FILTER_BAR_GAP = 2.dp
