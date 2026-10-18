package com.local.notificationfilter.ui.screens.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.local.notificationfilter.core.model.MatchField
import com.local.notificationfilter.core.model.NotificationRecord
import com.local.notificationfilter.core.model.ResultKind
import com.local.notificationfilter.core.model.Rule
import com.local.notificationfilter.core.model.SnoozeCalculator
import com.local.notificationfilter.ui.components.AppPickerDialog
import com.local.notificationfilter.ui.components.EmptyState
import com.local.notificationfilter.ui.components.NfCard
import com.local.notificationfilter.ui.components.RecordResultTag
import com.local.notificationfilter.ui.components.SectionTitle

/**
 * P9 搜索流水：关键词 + 字段范围 + App + 时间范围 + 处理动作 + 命中规则，条件叠加；
 * 从统计页进入时预填 App（banner 提示）。点击结果进 P7。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SearchScreen(
    presetPackage: String?,
    onOpenDetail: (Long) -> Unit,
    onBack: () -> Unit,
) {
    val vm: SearchViewModel = viewModel()
    val state by vm.uiState.collectAsStateWithLifecycle()
    var showAppPicker by remember { mutableStateOf(false) }
    var showRulePicker by remember { mutableStateOf(false) }

    LaunchedEffect(presetPackage) { vm.init(presetPackage) }

    state.error?.let { msg ->
        AlertDialog(
            onDismissRequest = { vm.consumeError() },
            title = { Text("搜索条件有误") },
            text = { Text(msg) },
            confirmButton = {
                TextButton(onClick = { vm.consumeError() }) { Text("知道了") }
            },
        )
    }

    if (showAppPicker) {
        AppPickerDialog(
            title = "选择应用",
            initialSelected = state.packages,
            onDismiss = { showAppPicker = false },
            onConfirm = { selected ->
                selected.forEach { vm.addPackage(it) }
                showAppPicker = false
            },
        )
    }

    if (showRulePicker) {
        RulePickerDialog(
            rules = state.rules,
            currentRuleId = state.ruleId,
            onDismiss = { showRulePicker = false },
            onSelect = { rule ->
                vm.setRule(rule)
                showRulePicker = false
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("搜索流水") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                // V9 间距：顶栏与第一行内容（预填提示卡 / 「关键词」标题）之间留 12dp
                .padding(top = 12.dp),
        ) {
            // —— 统计页预填提示 ——
            if (state.presetPackageName != null) {
                Card(
                    // 顶部间距已由内容容器统一提供，这里只留下方 8dp，避免与容器 12dp 叠加成 20dp
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    ),
                ) {
                    Text(
                        text = "已从统计页带入：${state.presetPackageName}（条件可改，可叠加）",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(10.dp),
                    )
                }
            }

            // —— 关键词 + 字段范围 ——
            SectionTitle(text = "关键词")
            OutlinedTextField(
                value = state.keyword,
                onValueChange = vm::setKeyword,
                placeholder = { Text("搜索标题 / 正文 / 字段内容") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(
                    selected = state.allFieldsSelected,
                    onClick = vm::toggleAllFields,
                    label = { Text("全部字段") },
                )
                MatchField.ALL.forEach { field ->
                    FilterChip(
                        selected = field in state.fields,
                        onClick = { vm.toggleField(field) },
                        label = { Text(field.label) },
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            // —— 应用 ——
            SectionTitle(text = "应用")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                state.packages.forEach { pkg ->
                    val name = state.packageNames
                        .getOrNull(state.packages.indexOf(pkg))
                        ?: pkg
                    InputChip(
                        selected = true,
                        onClick = { vm.removePackage(pkg) },
                        label = { Text(name) },
                        trailingIcon = {
                            Text("×", style = MaterialTheme.typography.labelMedium)
                        },
                    )
                }
            }
            if (state.packages.isEmpty()) {
                Text(
                    text = "不限应用",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(4.dp))
            OutlinedButton(onClick = { showAppPicker = true }) { Text("添加 App") }

            Spacer(Modifier.height(16.dp))

            // —— 时间范围 ——
            SectionTitle(text = "时间范围")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TimeRange.entries.forEach { range ->
                    FilterChip(
                        selected = state.timeRange == range,
                        onClick = { vm.setTimeRange(range) },
                        label = { Text(range.label) },
                    )
                }
            }
            if (state.timeRange == TimeRange.CUSTOM) {
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = state.customFromText,
                        onValueChange = vm::setCustomFrom,
                        placeholder = { Text("起：MM-DD HH:mm") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = state.customToText,
                        onValueChange = vm::setCustomTo,
                        placeholder = { Text("止：MM-DD HH:mm") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            // —— 处理动作 ——
            SectionTitle(text = "处理动作")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ResultKind.entries.forEach { kind ->
                    FilterChip(
                        selected = kind in state.results,
                        onClick = { vm.toggleResult(kind) },
                        label = { Text(kind.label) },
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            // —— 命中规则 ——
            SectionTitle(text = "命中规则")
            // 用局部变量承接：state 是 delegated property，Kotlin 无法对它做智能转换，
            // 原写法靠 `!!` 绕过，一旦将来 state 在同一次组合里被刷新就是崩溃点。
            val activeRuleName = state.ruleName
            if (activeRuleName != null) {
                InputChip(
                    selected = true,
                    onClick = { vm.setRule(null) },
                    label = { Text(activeRuleName) },
                    trailingIcon = {
                        Text("×", style = MaterialTheme.typography.labelMedium)
                    },
                )
            } else {
                Text(
                    text = "不限规则",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(4.dp))
            OutlinedButton(
                onClick = { showRulePicker = true },
                enabled = state.rules.isNotEmpty(),
            ) {
                Text(if (state.rules.isEmpty()) "暂无规则" else "选择规则")
            }

            Spacer(Modifier.height(16.dp))
            Button(
                onClick = vm::search,
                enabled = !state.searching,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (state.searching) "搜索中…" else "搜索")
            }

            // —— 结果 ——
            Spacer(Modifier.height(16.dp))
            SectionTitle(text = "结果")
            when {
                !state.searched -> EmptyState("点「搜索」查看结果 · 空结果会有提示", icon = Icons.Filled.Search)
                state.matches.isEmpty() -> EmptyState("没有符合条件的记录", icon = Icons.Filled.Inbox)
                else -> {
                    Text(
                        text = "共 ${state.matches.size} 条结果（条件可叠加）",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(4.dp))
                    state.matches.forEach { record ->
                        SearchResultRow(
                            record = record,
                            keyword = state.keyword,
                            onClick = { onOpenDetail(record.recordId) },
                        )
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** 命中规则单选弹窗（含「不限」）。 */
@Composable
private fun RulePickerDialog(
    rules: List<Rule>,
    currentRuleId: Long?,
    onDismiss: () -> Unit,
    onSelect: (Rule?) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择命中规则") },
        text = {
            LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(null) }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = currentRuleId == null, onClick = { onSelect(null) })
                        Text("不限规则")
                    }
                }
                items(rules.size, key = { rules[it].id }) { index ->
                    val rule = rules[index]
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(rule) }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = currentRuleId == rule.id,
                            onClick = { onSelect(rule) },
                        )
                        Text(rule.name.ifBlank { "未命名规则" })
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

/** 搜索结果行：时间 · 应用 · 结果标签 + 摘要（关键词高亮）。 */
@Composable
private fun SearchResultRow(
    record: NotificationRecord,
    keyword: String,
    onClick: () -> Unit,
) {
    val now = remember { System.currentTimeMillis() }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
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
        }
        val summary = record.textSummary.ifBlank { record.titleSummary }
        if (summary.isNotBlank()) {
            Text(
                text = highlightKeyword(summary, keyword),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** 摘要内关键词高亮（对齐线框稿 <em class="hit">）。 */
@Composable
private fun highlightKeyword(text: String, keyword: String): AnnotatedString {
    val hl = MaterialTheme.colorScheme.primaryContainer
    val k = keyword.trim().lowercase()
    if (k.isEmpty() || text.isBlank()) return AnnotatedString(text)
    val lower = text.lowercase()
    val start = lower.indexOf(k)
    if (start < 0) return AnnotatedString(text)
    return buildAnnotatedString {
        append(text.substring(0, start))
        withStyle(SpanStyle(background = hl)) {
            append(text.substring(start, minOf(start + k.length, text.length)))
        }
        val end = minOf(start + k.length, text.length)
        if (end < text.length) append(text.substring(end))
    }
}
