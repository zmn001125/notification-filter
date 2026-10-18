package com.local.notificationfilter.ui.screens.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.FilterAlt
import androidx.compose.material.icons.filled.FormatListBulleted
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LayersClear
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Power
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.Lifecycle
import com.local.notificationfilter.service.OverlayController
import com.local.notificationfilter.ui.screens.record.ResultFilter
import com.local.notificationfilter.ui.components.EmptyState
import com.local.notificationfilter.ui.components.ListenerStatusCard
import com.local.notificationfilter.ui.components.NfCard
import com.local.notificationfilter.ui.components.NotificationRecordItem
import com.local.notificationfilter.ui.components.SectionTitle
import com.local.notificationfilter.ui.components.listenerStatusOf
import com.local.notificationfilter.ui.navigation.NfRoutes
import com.local.notificationfilter.ui.theme.ActionKind
import com.local.notificationfilter.ui.theme.AppTheme
import com.local.notificationfilter.ui.theme.DarkMode
import com.local.notificationfilter.ui.theme.actionColorsOf
import com.local.notificationfilter.util.PermissionChecker
import com.local.notificationfilter.util.PermissionType

/**
 * P2 首页（仪表盘）：总开关大按钮 / 监听服务状态卡 + 常驻通知开关（V2 需求⑤⑥）/
 * 权限缺失提醒 / 今日概览 / 最近通知 / 黑名单快捷入口 /
 * 右下角搜索 FAB / 顶部简易菜单（关于 · 重新引导 · 清空流水 · 主题设置 ·
 * 在多任务中隐藏 · 清除屏幕显示）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onNavigate: (String) -> Unit,
    onOpenDetail: (Long) -> Unit,
    onReRunGuide: () -> Unit,
) {
    val vm: HomeViewModel = viewModel()
    val state by vm.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var menuOpen by remember { mutableStateOf(false) }
    var showAbout by remember { mutableStateOf(false) }
    var showThemeDialog by remember { mutableStateOf(false) }
    var showOngoingRiskDialog by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    val now = remember { System.currentTimeMillis() }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refreshPermissions() }

    // 一次性提示（重新绑定结果等）
    LaunchedEffect(state.hint) {
        val hint = state.hint ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(hint)
        vm.consumeHint()
    }

    if (showAbout) {
        AboutDialog(onDismiss = { showAbout = false })
    }

    // V4-5：主题设置（色相主题 + 深浅模式两个正交开关）
    if (showThemeDialog) {
        ThemeSettingsDialog(
            currentTheme = state.theme,
            currentDarkMode = state.darkMode,
            onThemeChange = vm::setTheme,
            onDarkModeChange = vm::setDarkMode,
            onDismiss = { showThemeDialog = false },
        )
    }

    // 首次关闭常驻通知的二次风险确认（V2 需求⑤）
    if (showOngoingRiskDialog) {
        AlertDialog(
            onDismissRequest = { showOngoingRiskDialog = false },
            title = { Text("关闭常驻通知") },
            text = {
                Text("关闭后若被系统回收，过滤将静默失效，需重开 App。确定关闭吗？")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showOngoingRiskDialog = false
                        vm.markOngoingRiskAcknowledged()
                        vm.setShowOngoing(false)
                    },
                ) { Text("仍要关闭") }
            },
            dismissButton = {
                TextButton(onClick = { showOngoingRiskDialog = false }) { Text("取消") }
            },
        )
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("通知过滤", fontWeight = FontWeight.Bold) },
                actions = {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "菜单")
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        // V11：菜单图标的统一口径 —— **每一项都必须有 leadingIcon**。
                        // 原先 7 项里 5 项没图标、2 项有，视觉上参差不齐（用户真机反馈）。
                        // 图标只表意不表态：状态用 trailingIcon（如「在多任务中隐藏」的勾），
                        // 语义用 leadingIcon，两者不混用。
                        DropdownMenuItem(
                            text = { Text("关于") },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Filled.Info,
                                    contentDescription = null,
                                )
                            },
                            onClick = {
                                menuOpen = false
                                showAbout = true
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("重新走权限引导") },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Filled.RestartAlt,
                                    contentDescription = null,
                                )
                            },
                            onClick = {
                                menuOpen = false
                                onReRunGuide()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("清空流水") },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Filled.DeleteSweep,
                                    contentDescription = null,
                                )
                            },
                            onClick = {
                                menuOpen = false
                                vm.clearRecords()
                            },
                        )
                        // V4-5：主题设置入口
                        DropdownMenuItem(
                            text = { Text("主题设置") },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Filled.Palette,
                                    contentDescription = null,
                                )
                            },
                            onClick = {
                                menuOpen = false
                                showThemeDialog = true
                            },
                        )
                        // V8：多任务（最近任务）隐藏开关。菜单项本身就是切换按钮，
                        // 右侧勾选态 = 当前设置（与「主题设置」等入口同处一个 DropdownMenu，
                        // 不额外引入 Switch，避免菜单内出现点击热区冲突）。
                        DropdownMenuItem(
                            text = { Text("在多任务中隐藏") },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Filled.VisibilityOff,
                                    contentDescription = null,
                                )
                            },
                            trailingIcon = {
                                if (state.hideFromRecents) {
                                    Icon(
                                        imageVector = Icons.Filled.Check,
                                        contentDescription = "已隐藏",
                                        tint = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            },
                            onClick = {
                                menuOpen = false
                                // 只改设置：真机生效由 MainActivity 订阅同一 Flow 完成
                                vm.setHideFromRecents(!state.hideFromRecents)
                            },
                        )
                        // V11：备份与恢复（本地文件夹 / 坚果云 WebDAV / 自动定时）
                        DropdownMenuItem(
                            text = { Text("备份与恢复") },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Filled.CloudSync,
                                    contentDescription = null,
                                )
                            },
                            onClick = {
                                menuOpen = false
                                onNavigate(NfRoutes.BACKUP)
                            },
                        )
                        // V10：使用说明（App 内帮助，边看边配）
                        DropdownMenuItem(
                            text = { Text("使用说明") },
                            leadingIcon = {
                                Icon(
                                    // Info 让给「关于」，帮助手册用 MenuBook，避免两项图标重复
                                    imageVector = Icons.AutoMirrored.Filled.MenuBook,
                                    contentDescription = null,
                                )
                            },
                            onClick = {
                                menuOpen = false
                                onNavigate(NfRoutes.HELP)
                            },
                        )
                        // P0-1：屏幕叠加层（弹幕 / 常驻悬浮）的唯一手动退出入口。
                        // 叠加窗口带 FLAG_NOT_TOUCHABLE（点不中）且没有通知栏条目，
                        // 「0 = 常驻」的悬浮若没有这一项用户就完全关不掉。
                        DropdownMenuItem(
                            text = { Text("清除屏幕显示") },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Filled.LayersClear,
                                    contentDescription = null,
                                )
                            },
                            onClick = {
                                menuOpen = false
                                // dismissAll 内部已切主线程；它会摘掉全部弹幕 + 悬浮
                                // + 悬浮预览，并清空排队与轨道占用。
                                OverlayController.dismissAll()
                            },
                        )
                    }
                },
            )
        },
        floatingActionButton = {
            // V6-⑥：FAB 显式用主题主色（M3 默认 primaryContainer 是 14% 淡化色，观感脱节）
            FloatingActionButton(
                onClick = { onNavigate(NfRoutes.search()) },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ) {
                Icon(Icons.Filled.Search, contentDescription = "搜索流水")
            }
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                // V4-2：顶栏与「过滤中」卡片之间加间距（原为紧贴）
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            // —— 总开关 ——
            MasterSwitchCard(
                enabled = state.masterEnabled,
                activeRuleCount = state.activeRuleCount,
                onToggle = vm::toggleMaster,
            )

            Spacer(Modifier.height(12.dp))

            // —— 监听服务状态卡 + 常驻通知开关（V2 需求⑤⑥，架构 §6.6）——
            ServiceStatusCard(
                listenerGranted = state.listenerGranted,
                connected = state.connected,
                showOngoing = state.showOngoing,
                onAuthorize = {
                    PermissionChecker.openSettings(
                        PermissionType.NOTIFICATION_LISTENER,
                        context,
                    )
                },
                onRebind = vm::requestRebind,
                onShowOngoingChange = { on ->
                    // 首次关闭：先弹风险确认，确认后再写入并生效
                    if (!on && !state.ongoingRiskAcknowledged) {
                        showOngoingRiskDialog = true
                    } else {
                        vm.setShowOngoing(on)
                    }
                },
            )

            Spacer(Modifier.height(12.dp))

            // —— 权限缺失提醒 ——
            if (state.missingPermissions.isNotEmpty()) {
                Card(
                    modifier = Modifier.fillMaxWidth().clickable { onReRunGuide() },
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                    ),
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier.size(26.dp).clip(CircleShape)
                                .background(MaterialTheme.colorScheme.error),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Warning,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onError,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                        Spacer(Modifier.size(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = "有 ${state.missingPermissions.size} 项权限未授予，可能漏通知",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                            )
                            Text(
                                text = "点此重新进入授权引导",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                            )
                        }
                        Text("›", color = MaterialTheme.colorScheme.onErrorContainer)
                    }
                }
                Spacer(Modifier.height(12.dp))
            }

            // —— 今日概览 ——
            NfCard {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    OverviewNumber(
                        value = state.todayFiltered,
                        label = "今日消除",
                        icon = Icons.Filled.FilterAlt,
                        tint = actionColorsOf(ActionKind.BLOCK).accent,
                        // V9 需求 1：点进去只看「消除」的流水
                        onClick = { onNavigate(NfRoutes.record(ResultFilter.BLOCK.name)) },
                    )
                    OverviewNumber(
                        value = state.todayPassed,
                        label = "今日放行",
                        icon = Icons.Filled.Visibility,
                        tint = actionColorsOf(ActionKind.PASS).accent,
                        onClick = { onNavigate(NfRoutes.record(ResultFilter.PASS.name)) },
                    )
                    OverviewNumber(
                        value = state.activeRuleCount,
                        label = "在跑规则",
                        icon = Icons.Filled.FormatListBulleted,
                        tint = MaterialTheme.colorScheme.primary,
                        onClick = { onNavigate(NfRoutes.RULES) },
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            // —— 最近通知 ——
            SectionTitle(
                text = "最近通知",
                trailing = {
                    Text(
                        text = "查看全部 ›",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.clickable { onNavigate(NfRoutes.RECORD) },
                    )
                },
            )
            Spacer(Modifier.height(4.dp))
            NfCard {
                if (state.recentRecords.isEmpty()) {
                    EmptyState("还没有收到通知", icon = Icons.Filled.NotificationsOff)
                } else {
                    state.recentRecords.forEach { record ->
                        NotificationRecordItem(
                            record = record,
                            now = now,
                            onClick = { onOpenDetail(record.recordId) },
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            // —— 快捷入口：黑名单 ——
            SectionTitle(text = "快捷入口")
            Spacer(Modifier.height(4.dp))
            NfCard {
                Row(
                    modifier = Modifier.fillMaxWidth().clickable { onNavigate(NfRoutes.BLACKLIST) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // 黑名单入口用 Block 图标圆牌（§6.4：非头像、不用首字）
                    val blockColors = actionColorsOf(ActionKind.BLOCK)
                    Box(
                        modifier = Modifier.size(36.dp).clip(CircleShape)
                            .background(blockColors.container),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Block,
                            contentDescription = null,
                            tint = blockColors.onContainer,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    Spacer(Modifier.size(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text("应用黑名单", style = MaterialTheme.typography.titleSmall)
                        Text(
                            text = "已拉黑 ${state.blacklistCount} 个应用，命中即无条件消除",
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

            Spacer(Modifier.height(32.dp))
        }
    }
}

/**
 * 服务状态卡（V2 需求⑤⑥）：三态（监听中 / 已授权·等待连接 / 未连接）+ 常驻通知开关。
 * 卡片常驻首页（不论权限是否缺失）。
 */
@Composable
private fun ServiceStatusCard(
    listenerGranted: Boolean,
    connected: Boolean,
    showOngoing: Boolean,
    onAuthorize: () -> Unit,
    onRebind: () -> Unit,
    onShowOngoingChange: (Boolean) -> Unit,
) {
    NfCard {
        ListenerStatusCard(
            status = listenerStatusOf(granted = listenerGranted, connected = connected),
            onAuthorize = onAuthorize,
            onRebind = onRebind,
        )
        HorizontalDivider(
            modifier = Modifier.padding(vertical = 10.dp),
            color = MaterialTheme.colorScheme.outlineVariant,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("常驻通知", style = MaterialTheme.typography.titleSmall)
                Text(
                    text = if (showOngoing) {
                        "通知栏显示「过滤中 / 已暂停」，服务以前台形态运行"
                    } else {
                        "通知栏不显示本 App 条目，服务以后台形态运行"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "关闭后若被系统回收，过滤将静默失效，需重开 App",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.size(10.dp))
            Switch(checked = showOngoing, onCheckedChange = onShowOngoingChange)
        }
    }
}

@Composable
private fun MasterSwitchCard(
    enabled: Boolean,
    activeRuleCount: Int,
    onToggle: (Boolean) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier.size(44.dp).clip(CircleShape)
                    .background(
                        if (enabled) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.surfaceVariant
                    ),
                contentAlignment = Alignment.Center,
            ) {
                // 电源图标替代 V1 的 ON/OFF 字符（§6.4 退役字符）
                Icon(
                    imageVector = Icons.Filled.Power,
                    contentDescription = if (enabled) "过滤已开启" else "过滤已暂停",
                    tint = if (enabled) MaterialTheme.colorScheme.onPrimary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(24.dp),
                )
            }
            Spacer(Modifier.size(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = if (enabled) "过滤中" else "已暂停",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = if (enabled) "$activeRuleCount 条规则正在运行 · 点击暂停"
                    else "全部规则已暂停 · 点击开启",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = enabled, onCheckedChange = onToggle)
        }
    }
}

@Composable
private fun OverviewNumber(
    value: Int,
    label: String,
    icon: ImageVector,
    tint: Color,
    onClick: (() -> Unit)? = null,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .then(
                if (onClick != null) {
                    Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .clickable(onClick = onClick)
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                } else {
                    Modifier
                },
            ),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.size(4.dp))
        Text(
            text = value.toString(),
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

/** 「关于」弹窗（V1 极简：一段说明）。 */
@Composable
fun AboutDialog(onDismiss: () -> Unit) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("关于通知过滤") },
        text = {
            Text(
                "一个纯本地的通知管家：自动消除、推迟、替换或朗读你不想要的通知。\n\n" +
                    "· 不联网、无账号、数据只存本机\n" +
                    "· 规则命中即按配置动作处理\n" +
                    "· 「替换」= 取消原通知后以本 App 名义重发",
            )
        },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) { Text("知道了") }
        },
    )
}

/**
 * V4-5 主题设置弹窗：色相主题（色块单选）+ 深浅模式（三选一）。
 * 两者正交——用户可任意组合（如"玫红 + 深色"）。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ThemeSettingsDialog(
    currentTheme: String,
    currentDarkMode: String,
    onThemeChange: (String) -> Unit,
    onDarkModeChange: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("主题设置") },
        text = {
            Column {
                Text(
                    text = "色相主题",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    AppTheme.entries.forEach { theme ->
                        val selected = theme.name == currentTheme
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(CircleShape)
                                    .background(theme.lightPrimary)
                                    .then(
                                        if (selected) {
                                            Modifier.padding(0.dp)
                                        } else {
                                            Modifier
                                        },
                                    )
                                    .clickable { onThemeChange(theme.name) },
                                contentAlignment = Alignment.Center,
                            ) {
                                if (selected) {
                                    Icon(
                                        imageVector = Icons.Filled.Check,
                                        contentDescription = null,
                                        tint = Color.White,
                                        modifier = Modifier.size(22.dp),
                                    )
                                }
                            }
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = theme.label,
                                style = MaterialTheme.typography.labelSmall,
                                color = if (selected) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))
                Text(
                    text = "深浅模式",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    DarkMode.entries.forEach { mode ->
                        FilterChip(
                            selected = mode.name == currentDarkMode,
                            onClick = { onDarkModeChange(mode.name) },
                            label = { Text(mode.label) },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("完成") }
        },
    )
}
