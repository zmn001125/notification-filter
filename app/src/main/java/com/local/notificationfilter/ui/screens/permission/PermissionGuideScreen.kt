package com.local.notificationfilter.ui.screens.permission

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.PictureInPicture
import androidx.compose.material.icons.filled.Power
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.Lifecycle
import com.local.notificationfilter.LocalAppContainer
import com.local.notificationfilter.core.model.Rule
import com.local.notificationfilter.service.AccessibilityGate
import com.local.notificationfilter.service.ListenerStatusHolder
import com.local.notificationfilter.ui.components.WaitingRebindButton
import com.local.notificationfilter.util.ListenerBinder
import com.local.notificationfilter.util.PermissionChecker
import com.local.notificationfilter.util.PermissionProbe
import com.local.notificationfilter.util.PermissionType
import kotlinx.coroutines.launch

/**
 * P1 权限引导（首次启动全屏页）。
 * 五项权限卡片 + 状态实时检测（onResume 三拍重试刷新）+ 缺项置灰主按钮 + 跳过入口。
 * 通知使用权卡片三态展示（V2 需求⑥）：未授权 / 已授权·等待连接 / 监听中。
 * V4：悬浮窗权限项按需展示（见 `needOverlay`，未启用屏幕显示动作时不出现）。
 */
@Composable
fun PermissionGuideScreen(onFinished: () -> Unit) {
    val context = LocalContext.current
    val container = LocalAppContainer.current
    val scope = rememberCoroutineScope()

    val manualPerms by container.settingsStore.manualPerms
        .collectAsStateWithLifecycle(initialValue = emptySet())

    /** 监听服务连接状态（架构共享约定 6：连接状态唯一事实来源）。 */
    val listenerConnected by ListenerStatusHolder.connected.collectAsStateWithLifecycle()

    /**
     * V4：悬浮窗权限（PRD 原意）只在用户真的启用了屏幕显示动作（弹幕 / 自定义位置悬浮 /
     * 焦点通知）时才需要。无条件展示会让不用这些功能的用户永久挂一条点不掉的红色
     * 「未授权」——该权限 autoDetect，也不提供「我已授权」手动兜底。
     *
     * 口径带 enabled：停用规则不触发任何动作，故不索取权限；此处订阅规则流，
     * 用户重新启用规则后该项会自动出现，不会漏。
     *
     * 数据来源复用现有装配：订阅规则流（而不是一次性 getAll），用户去编辑页关掉全部
     * 屏幕显示动作后返回本页，该项会自动消失；无需新增 ViewModel 或全局状态。
     */
    val rules by container.ruleRepository.observeAll()
        .collectAsStateWithLifecycle(initialValue = emptyList<Rule>())
    val needOverlay = rules.any { it.enabled && it.actions.hasOverlay }

    /**
     * V7 需求 7-⑥：无障碍服务只在存在「打开了某个 App」条件时才需要。
     *
     * 判定复用 [AccessibilityGate.isNeeded]（纯函数，与运行时标记、与服务事件派发口径**同一处**），
     * 不在本页重写一遍 any{}，避免「页面说需要、运行时说不需要」的漂移。
     */
    val needAccessibility = AccessibilityGate.isNeeded(rules)

    var statuses by remember {
        mutableStateOf(PermissionChecker.checkAll(context, emptySet()))
    }
    var askedPostNotifications by remember { mutableStateOf(false) }

    // 从系统设置页返回时刷新真实状态（V2 需求⑥：三拍重试 0s/0.5s/1.5s，任一拍翻正即停）
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        scope.launch { statuses = PermissionProbe.withRetry(context, manualPerms) }
    }

    val postNotificationsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        askedPostNotifications = true
        scope.launch { statuses = PermissionProbe.withRetry(context, manualPerms) }
    }

    // 展示项与「未授权项」必须同源过滤：否则主按钮会因隐藏的 OVERLAY / ACCESSIBILITY 永远置灰
    // （V4.2 修过的 D15/D16 口径）。needXxx = false 时该项既不渲染、也不计入 missing。
    val visibleStatuses = statuses.filter { status ->
        (needOverlay || status.type != PermissionType.OVERLAY) &&
            (needAccessibility || status.type != PermissionType.ACCESSIBILITY)
    }
    val missing = visibleStatuses.filter { !it.granted }

    /**
     * 「已开启但当前没有规则使用」的灰字提示（架构 §3.6.1 UI 提示口径 2）。
     * 该项此时**不在** [visibleStatuses] 里（不渲染卡片、不计入 missing），
     * 故单独用一行说明，提示用户可自行去关闭以省电——**不自动关闭**（Q2 定案）。
     */
    val accessibilityNotNeeded = !needAccessibility &&
        statuses.firstOrNull { it.type == PermissionType.ACCESSIBILITY }?.granted == true

    Surface(modifier = Modifier.fillMaxSize().statusBarsPadding(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
        ) {
            Spacer(Modifier.height(16.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 20.dp, horizontal = 14.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = "欢迎使用通知过滤",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "一个纯本地的通知管家：自动消除、推迟、替换或朗读你不想要的通知",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
            Text(
                text = "需要以下权限才能开始工作",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))

            visibleStatuses.forEach { status ->
                PermissionCard(
                    status = status,
                    listenerConnected = if (status.type == PermissionType.NOTIFICATION_LISTENER) {
                        listenerConnected
                    } else {
                        null
                    },
                    onGrant = {
                        when (status.type) {
                            PermissionType.POST_NOTIFICATIONS -> {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !askedPostNotifications) {
                                    postNotificationsLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                } else {
                                    PermissionChecker.openSettings(status.type, context)
                                }
                            }

                            else -> PermissionChecker.openSettings(status.type, context)
                        }
                    },
                    onMarkGranted = {
                        scope.launch { container.settingsStore.setManualPerm(status.type.name, true) }
                    },
                    onUnmark = {
                        scope.launch { container.settingsStore.setManualPerm(status.type.name, false) }
                    },
                    onRebind = if (status.type == PermissionType.NOTIFICATION_LISTENER) {
                        {
                            // 已授权但服务未连接：请求系统重新绑定 + 重新三拍探测
                            scope.launch {
                                ListenerBinder.requestRebind(context)
                                statuses = PermissionProbe.withRetry(context, manualPerms)
                            }
                        }
                    } else {
                        null
                    },
                )
                Spacer(Modifier.height(10.dp))
            }

            // 无障碍服务「已开启但无规则使用」的灰字提示（不自动关闭，只告知）
            if (accessibilityNotNeeded) {
                Text(
                    text = "无障碍服务已开启，${AccessibilityGate.HINT_NOT_NEEDED}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
                )
            }

            if (missing.isNotEmpty()) {
                Text(
                    text = "还有 ${missing.size} 项未授权：" +
                        missing.joinToString("、") { it.type.title },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
            }

            Button(
                onClick = {
                    scope.launch {
                        container.settingsRepository.setGuideCompleted(true)
                        onFinished()
                    }
                },
                enabled = missing.isEmpty(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (missing.isEmpty()) "已全部授权，进入应用" else "还差 ${missing.size} 项授权")
            }

            TextButton(
                onClick = {
                    scope.launch {
                        container.settingsRepository.setGuideCompleted(true)
                        onFinished()
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("跳过，先进入首页", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun PermissionCard(
    status: com.local.notificationfilter.util.PermissionStatus,
    /** 通知使用权卡片专用：服务连接状态（V2 需求⑥，授权 ≠ 连接）；其余权限传 null。 */
    listenerConnected: Boolean? = null,
    onGrant: () -> Unit,
    onMarkGranted: () -> Unit,
    onUnmark: () -> Unit,
    onRebind: (() -> Unit)? = null,
) {
    val type = status.type
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier.size(36.dp),
                contentAlignment = Alignment.Center,
            ) {
                Surface(
                    shape = CircleShape,
                    color = if (status.granted) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.size(36.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        // 权限语义图标（§6.4 退役「权」字牌）
                        Icon(
                            imageVector = when (type) {
                                PermissionType.NOTIFICATION_LISTENER -> Icons.Filled.Notifications
                                PermissionType.POST_NOTIFICATIONS -> Icons.Filled.NotificationsActive
                                PermissionType.AUTOSTART -> Icons.Filled.Power
                                PermissionType.BACKGROUND_POPUP -> Icons.Filled.OpenInNew
                                PermissionType.BATTERY_UNRESTRICTED -> Icons.Filled.BatteryChargingFull
                                // V4：悬浮窗（仅在启用弹幕 / 自定义位置悬浮时才需要）
                                PermissionType.OVERLAY -> Icons.Filled.PictureInPicture
                                // V7：无障碍服务（仅在配置「打开了某个 App」条件时才需要）
                                PermissionType.ACCESSIBILITY -> Icons.Filled.TouchApp
                            },
                            contentDescription = null,
                            tint = if (status.granted) MaterialTheme.colorScheme.onPrimaryContainer
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }
            Spacer(Modifier.size(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(type.title, style = MaterialTheme.typography.titleSmall)
                Text(
                    text = type.desc,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = when {
                        // 通知使用权三态（V2 需求⑥）：监听中 / 已授权·等待连接 / 未授权
                        status.granted && type == PermissionType.NOTIFICATION_LISTENER && listenerConnected == true -> "监听中"
                        status.granted && type == PermissionType.NOTIFICATION_LISTENER -> "已授权 · 等待连接…"
                        status.granted -> "已授权"
                        else -> "未授权"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (status.granted) MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.error,
                )
            }
            Spacer(Modifier.size(8.dp))
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.Center) {
                if (status.granted) {
                    OutlinedButton(
                        onClick = onUnmark,
                        enabled = !type.autoDetect,
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(
                            horizontal = 12.dp,
                            vertical = 4.dp,
                        ),
                    ) {
                        Text("已授权", style = MaterialTheme.typography.labelMedium)
                    }
                    // 已授权但服务未连接：>5s 后出「点此重新绑定」（与首页状态卡一致）
                    if (type == PermissionType.NOTIFICATION_LISTENER &&
                        listenerConnected == false &&
                        onRebind != null
                    ) {
                        WaitingRebindButton(onClick = onRebind)
                    }
                } else {
                    Button(
                        onClick = onGrant,
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(
                            horizontal = 12.dp,
                            vertical = 4.dp,
                        ),
                    ) {
                        Text("去授权", style = MaterialTheme.typography.labelMedium)
                    }
                    if (!type.autoDetect) {
                        TextButton(
                            onClick = onMarkGranted,
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                                horizontal = 8.dp,
                                vertical = 2.dp,
                            ),
                        ) {
                            Text("我已授权", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }
    }
}
