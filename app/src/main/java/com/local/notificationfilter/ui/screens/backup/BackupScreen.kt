package com.local.notificationfilter.ui.screens.backup

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.local.notificationfilter.core.backup.RestoreMode
import com.local.notificationfilter.util.toFullDateTime

/**
 * 备份与恢复页（V11）。
 *
 * 三段式布局，与用户心智一致：**本地** → **云端（WebDAV）** → **自动化**。
 * 每段内部统一是「配置 → 立即执行 → 历史文件（可恢复）」。
 *
 * 恢复一律走两步：点文件上的「恢复」→ 弹出预览（多少条规则 / 黑名单 / 哪些设置）
 * → 再点「确认恢复」。备份恢复不可逆，不允许一键直达。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupScreen(
    onBack: () -> Unit,
    vm: BackupViewModel = viewModel(),
) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    // 选文件夹（SAF 树）：拿到的 Uri 交给 VM 去 takePersistableUriPermission
    val folderLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri -> vm.selectFolder(uri) }

    LaunchedEffect(state.message) {
        val msg = state.message
        if (!msg.isNullOrBlank()) {
            snackbarHostState.showSnackbar(msg)
            vm.clearMessage()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("备份与恢复") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item { Spacer(Modifier.height(4.dp)) }

            // ==================== 概览 ====================
            item {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    ),
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text("最近一次备份", style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = if (state.lastRunAt == 0L) {
                                "还没有备份过"
                            } else {
                                "${state.lastRunAt.toFullDateTime()} ｜ ${state.lastRunMessage}"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = if (state.lastRunAt != 0L && !state.lastRunOk) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "备份内容：规则 + 黑名单 + 应用设置。恢复时规则**追加**（不会删你现有的规则），" +
                                "黑名单与设置按备份覆盖；恢复前会自动存一份快照。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            // ==================== 本地 ====================
            item {
                SectionCard(title = "本地备份") {
                    Text(
                        text = "文件夹：" + (state.folderUri?.let { shortenUri(it) } ?: "未选择"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { folderLauncher.launch(null) },
                            enabled = !state.busy,
                        ) {
                            Icon(Icons.Filled.Folder, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.size(4.dp))
                            Text("选择文件夹")
                        }
                        OutlinedButton(
                            onClick = { vm.backupToLocal() },
                            enabled = !state.busy && state.folderUri != null,
                        ) {
                            Icon(Icons.Filled.Save, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.size(4.dp))
                            Text("立即备份")
                        }
                    }
                    if (state.localFiles.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Text("备份文件", style = MaterialTheme.typography.labelMedium)
                        state.localFiles.take(8).forEach { name ->
                            BackupFileRow(
                                name = name,
                                enabled = !state.busy,
                                onRestore = { vm.requestRestoreLocal(name) },
                                onDelete = { vm.requestDeleteLocal(name) },
                            )
                        }
                    }
                }
            }

            // ==================== WebDAV ====================
            item {
                SectionCard(title = "坚果云 WebDAV") {
                    Text(
                        text = "坚果云需要「应用专用密码」：网页版 → 账户信息 → 安全选项 → 添加应用密码。" +
                            "不要填登录密码，一定会认证失败。远程目录不存在时会自动创建。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(6.dp))
                    SwitchRow(
                        label = "启用 WebDAV",
                        checked = state.webDavEnabled,
                        onCheckedChange = { vm.setWebDavEnabled(it) },
                    )
                    var url by remember(state.webDavUrl) { mutableStateOf(state.webDavUrl) }
                    var account by remember(state.webDavAccount) { mutableStateOf(state.webDavAccount) }
                    var password by remember { mutableStateOf("") }
                    var path by remember(state.webDavRemotePath) { mutableStateOf(state.webDavRemotePath) }

                    OutlinedTextField(
                        value = url,
                        onValueChange = { url = it },
                        label = { Text("服务器地址") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(6.dp))
                    OutlinedTextField(
                        value = account,
                        onValueChange = { account = it },
                        label = { Text("账号（坚果云登录邮箱）") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(6.dp))
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = {
                            Text(if (state.passwordSaved) "应用密码（留空 = 不修改）" else "应用密码")
                        },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(6.dp))
                    OutlinedTextField(
                        value = path,
                        onValueChange = { path = it },
                        label = { Text("远程目录") },
                        supportingText = {
                            Text(
                                "文件名按当天时间自动生成（如 nf-backup-20261018-1605.json），可保留多份",
                                style = MaterialTheme.typography.labelSmall,
                            )
                        },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { vm.saveWebDav(url, account, password, path); password = "" },
                            enabled = !state.busy,
                        ) { Text("保存") }
                        OutlinedButton(
                            onClick = { vm.testWebDav() },
                            enabled = !state.busy && state.webDavEnabled,
                        ) { Text("测试连接") }
                        OutlinedButton(
                            onClick = { vm.backupToWebDav() },
                            enabled = !state.busy && state.webDavEnabled,
                        ) {
                            Icon(Icons.Filled.CloudUpload, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.size(4.dp))
                            Text("立即上传")
                        }
                    }
                    if (state.remoteFiles.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Text("云端文件", style = MaterialTheme.typography.labelMedium)
                        state.remoteFiles.take(8).forEach { name ->
                            BackupFileRow(
                                name = name,
                                enabled = !state.busy,
                                onRestore = { vm.requestRestoreWebDav(name) },
                                onDelete = { vm.requestDeleteWebDav(name) },
                            )
                        }
                    }
                }
            }

            // ==================== 自动备份 ====================
            item {
                SectionCard(title = "自动备份") {
                    SwitchRow(
                        label = "开启自动备份（${state.autoSummary}）",
                        checked = state.autoEnabled,
                        onCheckedChange = { vm.setAutoEnabled(it) },
                    )
                    Spacer(Modifier.height(6.dp))
                    Text("间隔", style = MaterialTheme.typography.labelMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(24 to "每天", 72 to "每 3 天", 168 to "每周").forEach { (h, label) ->
                            FilterChip(
                                selected = state.intervalHours == h,
                                onClick = { vm.setInterval(h) },
                                label = { Text(label) },
                            )
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text("时间", style = MaterialTheme.typography.labelMedium)
                        OutlinedTextField(
                            value = state.autoHour.toString().padStart(2, '0'),
                            onValueChange = { s ->
                                s.toIntOrNull()?.let { if (it in 0..23) vm.setAutoTime(it, state.autoMinute) }
                            },
                            singleLine = true,
                            modifier = Modifier.size(width = 76.dp, height = 56.dp),
                            label = { Text("时") },
                        )
                        OutlinedTextField(
                            value = state.autoMinute.toString().padStart(2, '0'),
                            onValueChange = { s ->
                                s.toIntOrNull()?.let { if (it in 0..59) vm.setAutoTime(state.autoHour, it) }
                            },
                            singleLine = true,
                            modifier = Modifier.size(width = 76.dp, height = 56.dp),
                            label = { Text("分") },
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Text("目标", style = MaterialTheme.typography.labelMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(
                            selected = state.autoToLocal,
                            onClick = { vm.setTargets(!state.autoToLocal, state.autoToWebDav) },
                            label = { Text("本地文件夹") },
                        )
                        FilterChip(
                            selected = state.autoToWebDav,
                            onClick = { vm.setTargets(state.autoToLocal, !state.autoToWebDav) },
                            label = { Text("WebDAV") },
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "自动备份按时间命名、保留多份：云端每次生成一个新文件，仅保留最近 30 份，" +
                            "更早的自动删除（手动备份与恢复前快照不会被自动删）；" +
                            "本地仍写入固定文件名并覆盖，不在你的文件夹里堆文件。" +
                            "系统可能会延迟几分钟触发，属正常现象。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            item { Spacer(Modifier.height(24.dp)) }
        }
    }

// ==================== 删除确认对话框（V11.1） ====================
val pendingDelete = state.pendingDelete
if (pendingDelete != null) {
    AlertDialog(
        onDismissRequest = { vm.cancelDelete() },
        title = { Text("删除这个备份文件？") },
        text = {
            Column {
                Text(
                    "${pendingDelete.sourceLabel}：${pendingDelete.fileName}",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "删除后无法恢复。本地文件会从你选的文件夹移除，云端文件会从坚果云移除。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { vm.confirmDelete() }) {
                Text("确认删除", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = { vm.cancelDelete() }) { Text("取消") }
        },
    )
}

// ==================== 恢复确认对话框 ====================
val pending = state.pendingRestore
if (pending != null) {
        val preview = pending.preview
        AlertDialog(
            onDismissRequest = { vm.cancelRestore() },
            title = { Text("确认恢复？") },
            text = {
                Column {
                    Text("来源：${pending.sourceLabel}", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "规则 ${preview.ruleCount} 条" +
                            if (preview.skippedRuleCount > 0) "（另有 ${preview.skippedRuleCount} 条无法识别会跳过）" else "",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text("黑名单 ${preview.blacklistCount} 条", style = MaterialTheme.typography.bodySmall)
                    Text(
                        "设置：" + preview.settingsKeys.ifEmpty { listOf("（备份里没有）") }.joinToString("、"),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "规则会**追加**到现有规则之后，不会删除你现在的规则。" +
                            "黑名单与设置会被备份覆盖。恢复前会自动存一份快照。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { vm.confirmRestore(RestoreMode.FULL) }) {
                    Text("全部恢复")
                }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { vm.confirmRestore(RestoreMode.MERGE_RULES_ONLY) }) {
                        Text("只恢复规则")
                    }
                    TextButton(onClick = { vm.cancelRestore() }) { Text("取消") }
                }
            },
        )
    }
}

// ==================== 小部件 ====================

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f),
        )
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun BackupFileRow(
    name: String,
    enabled: Boolean,
    onRestore: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = name,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onRestore, enabled = enabled) {
            Icon(Icons.Filled.Restore, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.size(4.dp))
            Text("恢复")
        }
        IconButton(onClick = onDelete, enabled = enabled) {
            Icon(
                Icons.Filled.Delete,
                contentDescription = "删除 $name",
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.error,
            )
        }
    }
}

/** 把冗长的 SAF 树 Uri 缩成最后一段，界面上看得懂就行。 */
private fun shortenUri(uri: String): String {
    val tail = uri.substringAfterLast("%3A", uri)
        .substringAfterLast('/', uri)
        .replace("%2F", "/")
    return tail.ifBlank { uri.take(40) }
}
