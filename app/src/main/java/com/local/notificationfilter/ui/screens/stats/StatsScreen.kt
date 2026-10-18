package com.local.notificationfilter.ui.screens.stats

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.local.notificationfilter.core.model.AppStat
import com.local.notificationfilter.ui.components.AppAvatar
import com.local.notificationfilter.ui.components.EmptyState
import com.local.notificationfilter.ui.components.NfCard
import com.local.notificationfilter.ui.components.SectionTitle
import com.local.notificationfilter.ui.components.TrendChart

/**
 * P11 统计页：范围切换（今天/本周/总计）→ 总览（过滤/放行/过滤率）→
 * 趋势图（近 7 天 / 近 4 周，双系列）→ 按 App 计数（排序切换，点击预填进搜索）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatsScreen(
    onOpenSearchForApp: (String) -> Unit,
) {
    val vm: StatsViewModel = viewModel()
    val state by vm.uiState.collectAsStateWithLifecycle()

    // 一次性挂起查询，靠 ON_RESUME 触发重查（与记录页一致）。
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refresh() }

    Scaffold(
        topBar = { TopAppBar(title = { Text("统计", fontWeight = FontWeight.Bold) }) },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                // V9 间距：顶栏与第一行内容（范围切换 chips）之间留 12dp
                .padding(top = 12.dp),
        ) {
            // —— 范围切换 ——
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatsViewModel.Range.entries.forEach { range ->
                    FilterChip(
                        selected = state.range == range,
                        onClick = { vm.selectRange(range) },
                        label = { Text(range.label) },
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            // —— 总览 ——
            NfCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    OverviewNumber(state.filtered.toString(), "过滤")
                    OverviewNumber(state.passed.toString(), "放行")
                    OverviewNumber(
                        value = if (state.total == 0) "—" else "${state.filterRatePercent}%",
                        label = "过滤率",
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            // —— 趋势图 ——
            NfCard {
                SectionTitle(
                    text = "趋势",
                    trailing = {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            StatsViewModel.TrendMode.entries.forEach { mode ->
                                val selected = state.trendMode == mode
                                Text(
                                    text = mode.label,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = if (selected) {
                                        MaterialTheme.colorScheme.onPrimaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(
                                            if (selected) {
                                                MaterialTheme.colorScheme.primaryContainer
                                            } else {
                                                MaterialTheme.colorScheme.surfaceVariant
                                            },
                                        )
                                        .clickable { vm.selectTrendMode(mode) }
                                        .padding(horizontal = 8.dp, vertical = 4.dp),
                                )
                            }
                        }
                    },
                )
                Spacer(Modifier.height(8.dp))
                if (state.trend.isEmpty()) {
                    EmptyState("暂无数据", icon = Icons.Filled.BarChart)
                } else {
                    TrendChart(points = state.trend)
                }
            }

            Spacer(Modifier.height(16.dp))

            // —— 按 App 计数 ——
            SectionTitle(
                text = "按应用",
                trailing = {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        StatsViewModel.AppSort.entries.forEach { sort ->
                            FilterChip(
                                selected = state.appSort == sort,
                                onClick = { vm.selectAppSort(sort) },
                                label = { Text(sort.label) },
                            )
                        }
                    }
                },
            )
            Spacer(Modifier.height(8.dp))
            NfCard {
                if (state.apps.isEmpty()) {
                    EmptyState("暂无数据", icon = Icons.Filled.Inbox)
                } else {
                    state.apps.forEach { app ->
                        AppStatRow(
                            app = app,
                            onClick = { onOpenSearchForApp(app.packageName) },
                        )
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            // —— 口径说明 ——
            Text(
                text = "口径：过滤 = 消除 / 推迟 / 替换任一动作或黑名单命中；放行 = 其余" +
                    "（仅朗读或无动作）。趋势按周一为周起点；空日期补零。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun OverviewNumber(value: String, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 单行：头像 + 应用名 + 过滤/放行计数 + 占比条；点击预填进搜索。 */
@Composable
private fun AppStatRow(
    app: AppStat,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppAvatar(appName = app.appName, packageName = app.packageName, size = 36.dp)
        Spacer(Modifier.size(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = app.appName,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.size(3.dp))
            // 占比条：过滤部分实心、总长浅灰（与图表系列色一致）。
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            ) {
                if (app.total > 0 && app.filtered > 0) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(app.filtered.toFloat() / app.total.toFloat())
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(MaterialTheme.colorScheme.onSurface),
                    )
                }
            }
            Spacer(Modifier.size(3.dp))
            Text(
                text = "过滤 ${app.filtered} · 放行 ${app.passed} · 共 ${app.total}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.size(8.dp))
        Text(
            text = "›",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.outline,
        )
    }
}
