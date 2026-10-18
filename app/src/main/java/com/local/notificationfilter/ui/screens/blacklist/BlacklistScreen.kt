package com.local.notificationfilter.ui.screens.blacklist

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Block
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.local.notificationfilter.core.model.BlacklistEntry
import com.local.notificationfilter.ui.components.AppAvatar
import com.local.notificationfilter.ui.components.AppPickerDialog
import com.local.notificationfilter.ui.components.EmptyState
import com.local.notificationfilter.ui.components.NfCard
import com.local.notificationfilter.ui.components.SectionTitle

/**
 * P8 应用黑名单：命中的通知无条件消除，优先于所有规则。
 *
 * 顶部说明卡 + 条目列表（头像/应用名/包名/移除）+ FAB 添加（复用 AppPickerDialog 多选）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BlacklistScreen(
    onBack: () -> Unit,
) {
    val vm: BlacklistViewModel = viewModel()
    val state by vm.uiState.collectAsStateWithLifecycle()
    var showPicker by remember { mutableStateOf(false) }

    if (showPicker) {
        AppPickerDialog(
            title = "添加到黑名单",
            initialSelected = state.entries.map { it.packageName }.toSet(),
            onDismiss = { showPicker = false },
            onConfirm = { selected ->
                showPicker = false
                vm.addPackages(selected)
            },
        )
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("应用黑名单", fontWeight = FontWeight.Bold) }) },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showPicker = true },
                // V6-⑥：FAB 显式用主题主色（默认 primaryContainer 淡化色观感脱节）
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ) {
                Icon(Icons.Filled.Add, contentDescription = "添加应用")
            }
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                // V9 间距：顶栏与第一张卡片（说明卡）之间留 12dp
                .padding(top = 12.dp),
        ) {
            // —— 说明卡 ——
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        text = "无条件消除",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "黑名单中的应用，其全部通知都会被直接消除，不再经过规则匹配。" +
                            "即使规则里配了放行或朗读，也以黑名单为准。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            // —— 条目列表 ——
            SectionTitle(text = "已拉黑 ${state.count} 个应用")
            Spacer(Modifier.height(8.dp))
            if (state.entries.isEmpty()) {
                NfCard {
                    EmptyState("还没有拉黑任何应用", icon = Icons.Filled.Block)
                }
            } else {
                state.entries.forEach { entry ->
                    BlacklistRow(
                        entry = entry,
                        onRemove = { vm.remove(entry.packageName) },
                    )
                    Spacer(Modifier.height(8.dp))
                }
            }

            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun BlacklistRow(
    entry: BlacklistEntry,
    onRemove: () -> Unit,
) {
    NfCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppAvatar(appName = entry.appName, packageName = entry.packageName, size = 36.dp)
            Spacer(Modifier.size(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = entry.appName,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = entry.packageName,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.size(8.dp))
            TextButton(onClick = onRemove) { Text("移除") }
        }
    }
}
