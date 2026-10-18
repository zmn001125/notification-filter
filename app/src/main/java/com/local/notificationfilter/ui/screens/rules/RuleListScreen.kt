package com.local.notificationfilter.ui.screens.rules

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DragIndicator
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.local.notificationfilter.core.model.MatchMode
import com.local.notificationfilter.core.model.Rule
import com.local.notificationfilter.core.model.ScopeType
import com.local.notificationfilter.core.model.SnoozeCalculator
import com.local.notificationfilter.ui.components.ActionTag
import com.local.notificationfilter.ui.components.DragReorderLazyColumn
import com.local.notificationfilter.ui.components.EmptyState
import com.local.notificationfilter.ui.theme.ActionColors
import com.local.notificationfilter.ui.theme.ActionKind
import com.local.notificationfilter.ui.theme.actionColorsOf
import com.local.notificationfilter.util.AppUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * P5 规则列表（Tab 2）：顺序即优先级，长按 ⠿ 手柄拖动调整；
 * 列表尾部为应用黑名单 / 批量测试入口；右下角为紧凑「新建」按钮（V8 需求①：38dp）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RuleListScreen(
    onCreateRule: () -> Unit,
    onEditRule: (Long) -> Unit,
    onOpenBlacklist: () -> Unit,
    onOpenTest: () -> Unit,
) {
    val vm: RuleListViewModel = viewModel()
    val state by vm.uiState.collectAsStateWithLifecycle()
    val listState: LazyListState = rememberLazyListState()
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    var pendingDelete by remember { mutableStateOf<Rule?>(null) }
    var menuOpen by remember { mutableStateOf(false) }
    /** V5 需求⑦：待导出的（封套 JSON → 建议文件名）；非空时弹出导出方式选择框。 */
    var pendingExport by remember { mutableStateOf<Pair<String, String>?>(null) }

    // 导出到文件（SAF CreateDocument）；uri 为 null = 用户取消，静默收起
    val saveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        val pending = pendingExport
        if (uri != null && pending != null) {
            scope.launch {
                val ok = withContext(Dispatchers.IO) {
                    runCatching {
                        context.contentResolver.openOutputStream(uri)?.use { out ->
                            out.write(pending.first.toByteArray(Charsets.UTF_8))
                        } ?: error("无法打开输出流")
                    }.isSuccess
                }
                vm.showHint(if (ok) "已保存到文件" else "保存文件失败")
            }
        }
        pendingExport = null
    }

    // 从文件导入（SAF OpenDocument；mimeTypes 放宽以兼容各 ROM 文件管理器）
    val openLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult // 用户取消
        scope.launch {
            val text = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        input.bufferedReader(Charsets.UTF_8).readText()
                    }
                }.getOrNull()
            }
            if (text == null) vm.showHint("读取文件失败") else vm.importFromText(text)
        }
    }

    // 一次性提示（导入导出结果等）
    LaunchedEffect(state.hint) {
        val hint = state.hint ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(hint)
        vm.consumeHint()
    }

    // 导出方式选择：复制到剪贴板 / 保存为文件
    pendingExport?.let { (json, fileName) ->
        AlertDialog(
            onDismissRequest = { pendingExport = null },
            title = { Text("导出规则") },
            text = { Text("导出为 JSON，可用于分享或备份；请选择导出方式。") },
            confirmButton = {
                TextButton(onClick = {
                    clipboard.setText(AnnotatedString(json))
                    pendingExport = null
                    vm.showHint("已复制到剪贴板")
                }) { Text("复制到剪贴板") }
            },
            dismissButton = {
                TextButton(onClick = { saveLauncher.launch(fileName) }) { Text("保存为文件") }
            },
        )
    }

    pendingDelete?.let { rule ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除规则") },
            text = { Text("确定删除「${rule.name.ifBlank { "未命名规则" }}」吗？删除后该规则不再处理任何通知。") },
            confirmButton = {
                TextButton(onClick = {
                    vm.delete(rule)
                    pendingDelete = null
                }) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("取消") }
            },
        )
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("规则", fontWeight = FontWeight.Bold) },
                actions = {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "菜单")
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("导出全部规则") },
                            onClick = {
                                menuOpen = false
                                vm.exportAllJson()?.let { json ->
                                    pendingExport = json to allRulesFileName()
                                }
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("导入规则（剪贴板）") },
                            onClick = {
                                menuOpen = false
                                // Compose ClipboardManager 无 getClip()（那是原生 API），用 getText()
                                val text = clipboard.getText()?.text
                                if (text.isNullOrBlank()) {
                                    vm.showHint("剪贴板为空，请先复制规则 JSON 文本")
                                } else {
                                    vm.importFromText(text)
                                }
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("导入规则（文件）") },
                            onClick = {
                                menuOpen = false
                                openLauncher.launch(arrayOf("application/json", "text/*", "*/*"))
                            },
                        )
                    }
                },
            )
        },
        floatingActionButton = {
            // V8 需求①：新建入口收小到「跟文字差不多高」——原 ExtendedFloatingActionButton
            // （56dp 高 + 大内边距的胶囊，视觉过重）改为 38dp 高的紧凑胶囊：
            // 只保留 16dp 小图标 + 「新建」二字，位置仍在右下角，
            // 强调色仍沿用 V6-⑥ 的 containerColor = primary / contentColor = onPrimary。
            Button(
                onClick = onCreateRule,
                modifier = Modifier.height(NEW_RULE_BUTTON_HEIGHT),
                shape = RoundedCornerShape(NEW_RULE_BUTTON_HEIGHT / 2),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ),
                // 垂直内边距交给固定高度，水平只留 14dp，避免文字两侧出现大片空白
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp),
                elevation = ButtonDefaults.buttonElevation(
                    defaultElevation = 3.dp,
                    pressedElevation = 6.dp,
                ),
            ) {
                Icon(
                    imageVector = Icons.Filled.Add,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.size(4.dp))
                Text("新建", style = MaterialTheme.typography.labelLarge)
            }
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            Text(
                text = "按列表顺序匹配，一条通知只执行首个命中的规则；长按左侧手柄拖动调整优先级（顺序即优先级）",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )

            // —— V13.0 分组 tab：「全部」+ 各分组；一条规则可属多个分组，所以在多个 tab 下都出现 ——
            // 没有任何规则带分组时整行不显示，避免给不用分组的人凭空多占一行。
            if (state.allGroups.isNotEmpty()) {
                val tabs: List<String?> = listOf(null) + state.allGroups
                val selectedIndex = tabs.indexOf(state.selectedGroup).coerceAtLeast(0)
                ScrollableTabRow(
                    selectedTabIndex = selectedIndex,
                    edgePadding = 12.dp,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    tabs.forEachIndexed { _, group ->
                        Tab(
                            selected = state.selectedGroup == group,
                            onClick = { vm.selectGroup(group) },
                            text = {
                                Text(
                                    text = group ?: "全部",
                                    style = MaterialTheme.typography.labelLarge,
                                    maxLines = 1,
                                )
                            },
                        )
                    }
                }
                // 分组视图内禁止拖动（order 是全局优先级，过滤态交换会打乱不可见规则的顺序）
                if (state.selectedGroup != null) {
                    Text(
                        text = "当前为分组视图，排序是全局优先级，请切回「全部」再拖动调整",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                    )
                }
            }

            val rulesCount = state.rules.size
            // V9 性能：列表模型只在「规则内容 / 黑名单数量」变化时重建。
            // 原来这段 `buildList{}` 写在组合体里，任何一次与本列表无关的重组
            // （如拖动帧、开关切换）都会新建一份 List 实例 → LazyColumn 整表重新
            // diff + `animateItem` 重新起动画，这是拖动卡顿的第二个来源。
            val rows = remember(state.rules, state.blacklistCount, rulesCount) {
                buildList {
                    if (rulesCount == 0) add(RuleListItem.Empty)
                    state.rules.forEachIndexed { index, rule ->
                        add(RuleListItem.RuleRow(rule, index + 1))
                    }
                    add(RuleListItem.BlacklistRow(state.blacklistCount))
                    add(RuleListItem.TestRow)
                }
            }
            DragReorderLazyColumn(
                items = rows,
                key = { row ->
                    when (row) {
                        is RuleListItem.RuleRow -> "rule-${row.rule.id}"
                        RuleListItem.Empty -> "empty"
                        is RuleListItem.BlacklistRow -> "blacklist"
                        RuleListItem.TestRow -> "test"
                    }
                },
                onMove = { from, to ->
                    // 只允许在规则区內交换（入口行/空态行不参与排序）
                    if (to < rulesCount) vm.move(from, to)
                },
                onDragFinished = { vm.commitOrder() },
                modifier = Modifier.weight(1f),
                listState = listState,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) { row, _ ->
                when (row) {
                    is RuleListItem.RuleRow -> RuleCard(
                        rule = row.rule,
                        ordinal = row.ordinal,
                        hitCount = state.hitCounts[row.rule.id] ?: 0,
                        onToggle = { vm.toggleEnabled(row.rule) },
                        // V9.4：编辑入口**只做跳转**，不做任何 IO。
                        // 排序早已在拖动手势结束（onDragFinished → vm.commitOrder）时提交过，
                        // 这里绝不能再补一次同步写库 / 查库 / 枚举已安装应用，
                        // 否则「点编辑 → 进编辑页」之间会被主线程 IO 顶住。
                        onEdit = { onEditRule(row.rule.id) },
                        onDelete = { pendingDelete = row.rule },
                        onDuplicate = { vm.duplicate(row.rule) },
                        onExport = {
                            pendingExport =
                                vm.exportRuleJson(row.rule) to singleRuleFileName(row.rule.name)
                        },
                    )

                    RuleListItem.Empty -> EmptyState(
                        "还没有规则，点右下角「新建」开始",
                        icon = Icons.Filled.PlaylistAdd,
                    )

                    is RuleListItem.BlacklistRow -> EntryRow(
                        icon = Icons.Filled.Block,
                        iconColors = actionColorsOf(ActionKind.BLOCK),
                        title = "应用黑名单",
                        subtitle = "已拉黑 ${row.count} 个应用，命中即无条件消除",
                        onClick = onOpenBlacklist,
                    )

                    RuleListItem.TestRow -> EntryRow(
                        icon = Icons.Filled.Science,
                        iconColors = actionColorsOf(ActionKind.REPLACE),
                        title = "批量测试",
                        subtitle = "对已有规则批量试跑：快照 / 流水 / 手填素材",
                        onClick = onOpenTest,
                    )
                }
            }
        }
    }
}

/** 列表行类型：规则卡片 + 空态 + 两个入口行（入口行不参与拖动排序）。 */
private sealed interface RuleListItem {
    data class RuleRow(val rule: Rule, val ordinal: Int) : RuleListItem
    data object Empty : RuleListItem
    data class BlacklistRow(val count: Int) : RuleListItem
    data object TestRow : RuleListItem
}

@Composable
private fun RuleCard(
    rule: Rule,
    ordinal: Int,
    hitCount: Int,
    onToggle: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onDuplicate: () -> Unit = {},
    onExport: () -> Unit = {},
) {
    val context = LocalContext.current

    // V9.4（点「编辑」卡顿专项）：卡片的两段派生文案**只在规则内容变化时**重算。
    //
    // 这两段原来直接写在组合体里，任何与本卡无关的重组都会重跑一遍——最典型的就是
    // 点「编辑」后导航的过渡帧：旧页在淡出期间仍在合成树里，会跟着重组一次。
    // 而 [scopeSummary] 内部要查 PackageManager（`AppUtils.appNameOf`），
    // 缓存未命中时是一次同步 Binder IPC（未安装的包还会抛异常再回落），
    // 一条规则挂几个包名就是几次 IPC，正好落在「点击 → 跳转」那一帧上。
    // 用 remember 钉住后，点击那一帧不再有任何 PackageManager 调用；
    // 推迟描述含 `System.currentTimeMillis()`，必须实时算，故不缓存。
    val kwText = remember(rule) { keywordSummary(rule) }
    val scopeText = remember(rule, context) { scopeSummary(rule, context) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // —— 标题行：拖动手柄 + 名称 + 开关 ——
            // V10.5：名称原来和「动作标签 + 命中数 + 开关」挤在同一行（weight(1f) + maxLines=1），
            // 名字稍长就被省略号截断。现在名称独占一行（最多 2 行），
            // 动作标签与命中数下移到单独一行，给名称腾出整行宽度。
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Filled.DragIndicator,
                    contentDescription = "拖动调整优先级",
                    tint = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.size(18.dp).padding(end = 2.dp),
                )
                Text(
                    text = "$ordinal. ${rule.name.ifBlank { "未命名规则" }}",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    // V4-4：规则名称换成显眼的主色
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.size(8.dp))
                Switch(checked = rule.enabled, onCheckedChange = { onToggle() })
            }

            Spacer(Modifier.height(6.dp))

            // —— 动作标签 + 命中数（V10.5 从标题行下移）——
            Row(verticalAlignment = Alignment.CenterVertically) {
                ActionTagsOf(rule)
                Spacer(Modifier.weight(1f))
                // V3-2 命中数；V4-4：圆角与内边距对齐 ActionTag（10.dp / h7 v2）统一尺寸
                Surface(
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                ) {
                    Text(
                        text = "命中 $hitCount 次",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
                    )
                }
            }

            // —— V13.0 所属分组（可多个）——
            if (rule.groups.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                GroupTagsOf(rule)
            }

            Spacer(Modifier.height(6.dp))

            // —— 关键词行（V3-2 结构化）——
            if (kwText.isNotBlank()) {
                Text(
                    text = "关键词：$kwText",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
            }

            // —— 条件 / 范围 / 推迟 / 已停用 ——
            Text(
                text = buildString {
                    append("条件：${rule.summary()}")
                    append(" ｜ 范围：$scopeText")
                    rule.actions.snooze?.let {
                        append(" · 推迟 ${SnoozeCalculator.describeSnooze(it, System.currentTimeMillis())}")
                    }
                    if (!rule.enabled) append(" · 已停用")
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(8.dp))

            // —— 操作行 ——
            // V10.6：饱和度再降一档，**低于关键词那类说明文字**。
            // 上一版只是把主题色压到 70% 不透明度，色相饱和度仍是原值，视觉上还是比
            // `onSurfaceVariant` 的关键词行更艳。这一版改成真正的 HSV 降饱和：
            // 保留色相（四个按钮仍能区分），饱和度压到 35%、明度压到 85%。
            val editTint = MaterialTheme.colorScheme.primary.mutedTint()
            val copyTint = MaterialTheme.colorScheme.tertiary.mutedTint()
            val exportTint = MaterialTheme.colorScheme.secondary.mutedTint()
            val deleteTint = MaterialTheme.colorScheme.error.mutedTint()
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                TextButton(
                    onClick = onEdit,
                    colors = ButtonDefaults.textButtonColors(contentColor = editTint),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Edit,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                    )
                    Spacer(Modifier.size(4.dp))
                    Text("编辑")
                }
                Spacer(Modifier.size(4.dp))
                // V4-6：复制按钮（与编辑、删除并列）
                TextButton(
                    onClick = onDuplicate,
                    colors = ButtonDefaults.textButtonColors(contentColor = copyTint),
                ) {
                    Icon(
                        imageVector = Icons.Filled.ContentCopy,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                    )
                    Spacer(Modifier.size(4.dp))
                    Text("复制")
                }
                Spacer(Modifier.size(4.dp))
                // V5 需求⑦：导出该规则（分享 / 备份）
                TextButton(
                    onClick = onExport,
                    colors = ButtonDefaults.textButtonColors(contentColor = exportTint),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Share,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                    )
                    Spacer(Modifier.size(4.dp))
                    Text("导出")
                }
                Spacer(Modifier.size(4.dp))
                TextButton(
                    onClick = onDelete,
                    colors = ButtonDefaults.textButtonColors(contentColor = deleteTint),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Delete,
                        contentDescription = null,
                        tint = deleteTint,
                        modifier = Modifier.size(14.dp),
                    )
                    Spacer(Modifier.size(4.dp))
                    Text("删除")
                }
            }
        }
    }
}

/** 规则动作标签（多动作时并排展示，最多放得下为止）。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ActionTagsOf(rule: Rule) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        rule.actions.plannedActions().forEach { action ->
            ActionTag(action = action)
            Spacer(Modifier.size(2.dp))
        }
    }
}

/** V13.0 规则所属分组标签：一条规则可属多组，这里全部列出来。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GroupTagsOf(rule: Rule) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        rule.groups.forEach { group ->
            Surface(
                shape = androidx.compose.foundation.shape.RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.secondaryContainer,
            ) {
                Text(
                    text = group,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
                )
            }
        }
    }
}

/** 范围摘要：全局 / 仅 微信、淘宝 / 排除 腾讯新闻。 */
private fun scopeSummary(rule: Rule, context: android.content.Context): String {
    val names = rule.scope.packages
        .map { AppUtils.appNameOf(context, it) }
        .joinToString("、")
    return when (rule.scope.type) {
        ScopeType.GLOBAL -> "全局"
        ScopeType.ONLY -> if (names.isEmpty()) "未选择 App" else "仅 $names"
        ScopeType.EXCLUDE -> if (names.isEmpty()) "未选择 App" else "排除 $names"
    }
}

/** V3-2 关键词摘要（按匹配模式组织）。 */
private fun keywordSummary(rule: Rule): String {
    val regexPattern = rule.regex?.pattern?.takeIf { it.isNotBlank() }
    return when {
        regexPattern != null -> "正则：$regexPattern"
        rule.matchMode == MatchMode.TEXT_A_NOT_B ->
            "A：${rule.keywords.joinToString(" / ")} ｜ 不含 B：${rule.excludeKeywords.joinToString(" / ")}"
        rule.matchMode == MatchMode.TEXT_NOT_ANY || rule.matchMode == MatchMode.TEXT_NOT_ALL ->
            "不含：${rule.keywords.joinToString(" / ")}"
        else -> rule.keywords.joinToString(" / ")
    }
}

// ==================== V8 需求①：新建入口尺寸 ====================

/**
 * 「新建」入口高度：38dp。
 *
 * 取值依据（用户诉求「跟文字差不多大就行」）：labelLarge 行高约 20sp ≈ 26dp，
 * 加上下呼吸与 16dp 图标，38dp 是最小的自然可读高度；同时 ≥36dp，
 * 满足最小可点击热区，不会出现点不中的情况。
 */
private val NEW_RULE_BUTTON_HEIGHT = 38.dp

// ==================== V5 需求⑦：导出文件名 ====================

/** 全部导出文件名：`nf-rules-yyyyMMdd-HHmmss.json`。 */
private fun allRulesFileName(now: Long = System.currentTimeMillis()): String =
    "nf-rules-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(now)) + ".json"

/** 单条导出文件名：`nf-rule-<规则名>.json`（文件名非法字符替换为下划线）。 */
private fun singleRuleFileName(ruleName: String): String =
    "nf-rule-" + sanitizeFileName(ruleName.ifBlank { "未命名规则" }) + ".json"

/** 文件名净化：非法字符（`\ / : * ? " < > |`、空白与控制字符）替换为下划线。 */
private fun sanitizeFileName(name: String): String {
    val cleaned = name.replace(Regex("[\\\\/:*?\"<>|\\s\\p{Cntrl}]+"), "_").trim('_')
    return cleaned.ifBlank { "rule" }
}

/** 列表尾部入口行（黑名单 / 批量测试）：语义图标圆牌（§6.4，非首字头像）。 */
@Composable
private fun EntryRow(
    icon: ImageVector,
    iconColors: ActionColors,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier.size(36.dp).clip(CircleShape)
                    .background(iconColors.container),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = iconColors.onContainer,
                    modifier = Modifier.size(20.dp),
                )
            }
            Spacer(Modifier.size(10.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outline,
            )
        }
    }
}


/**
 * 把主题色压成「低饱和」版本（V10.6）。
 *
 * 为什么不用 `copy(alpha = …)`：只降透明度会让人变淡，但**色相饱和度仍是原值**，
 * 视觉上依然比 `onSurfaceVariant` 的关键词行更艳。这里直接改 HSV：
 * 保留色相（四个操作按钮仍能互相区分），把饱和度压到 [saturationFactor]、
 * 明度压到 [valueFactor]，确保它比正文类文字更"退后"。
 */
private fun Color.mutedTint(
    saturationFactor: Float = 0.35f,
    valueFactor: Float = 0.85f,
): Color {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(toArgb(), hsv)
    hsv[1] = (hsv[1] * saturationFactor).coerceIn(0f, 1f)
    hsv[2] = (hsv[2] * valueFactor).coerceIn(0f, 1f)
    return Color(android.graphics.Color.HSVToColor(hsv))
}
