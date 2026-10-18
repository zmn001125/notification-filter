package com.local.notificationfilter.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
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
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * 监听服务 UI 三态（V2 需求⑥，架构 §5.1 / §6.6）。
 *
 * 「已授权」（系统设置里有本 App 监听组件）与「服务已连接」（onListenerConnected 已回调）
 * 是两个独立状态源，授权 ≠ 连接，禁止出现「已授权却红字未授权」。
 */
enum class ListenerServiceStatus {
    /** granted && connected：服务已连接，正在接收通知（绿）。 */
    LISTENING,

    /** granted && !connected：已授权，正在连接监听服务（黄）。 */
    WAITING,

    /** !granted：未授权通知使用权（红）。 */
    NOT_GRANTED,
}

/** 由「已授权」与「已连接」推导 UI 三态（引导页 / 首页共用同一推导）。 */
fun listenerStatusOf(granted: Boolean, connected: Boolean): ListenerServiceStatus = when {
    !granted -> ListenerServiceStatus.NOT_GRANTED
    connected -> ListenerServiceStatus.LISTENING
    else -> ListenerServiceStatus.WAITING
}

/** 等待连接态超过该时长后才展示「点此重新绑定」（架构 §6.6，UI 计时）。 */
const val REBIND_APPEAR_DELAY_MS = 5_000L

// 三态状态点颜色（本波不追求彩色化，仅状态点区分绿 / 黄 / 红）。
private val StatusGreen = Color(0xFF2F9E44)
private val StatusYellow = Color(0xFFF59F00)
private val StatusRed = Color(0xFFFA5252)

/** 三态状态点颜色（绿 / 黄 / 红）。 */
fun listenerStatusColor(status: ListenerServiceStatus): Color = when (status) {
    ListenerServiceStatus.LISTENING -> StatusGreen
    ListenerServiceStatus.WAITING -> StatusYellow
    ListenerServiceStatus.NOT_GRANTED -> StatusRed
}

/** 小圆点状态指示（现有主题 + CircleShape，三态颜色区分）。 */
@Composable
fun ListenerStatusDot(status: ListenerServiceStatus, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(10.dp)
            .clip(CircleShape)
            .background(listenerStatusColor(status)),
    )
}

/**
 * 首页「监听服务状态」卡（架构 §6.6 原型）：
 * - 监听中（绿）：「服务已连接，正在接收通知」；
 * - 已授权 · 等待连接（黄）：「已授权，正在连接监听服务…」，>5s 后出「点此重新绑定」；
 * - 未连接（红）：「未授权通知使用权」+「去授权」。
 */
@Composable
fun ListenerStatusCard(
    status: ListenerServiceStatus,
    onAuthorize: () -> Unit,
    onRebind: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ListenerStatusDot(status)
        Spacer(Modifier.size(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = when (status) {
                    ListenerServiceStatus.LISTENING -> "监听中"
                    ListenerServiceStatus.WAITING -> "已授权 · 等待连接"
                    ListenerServiceStatus.NOT_GRANTED -> "未连接"
                },
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                text = when (status) {
                    ListenerServiceStatus.LISTENING -> "服务已连接，正在接收通知"
                    ListenerServiceStatus.WAITING -> "已授权，正在连接监听服务…"
                    ListenerServiceStatus.NOT_GRANTED -> "未授权通知使用权"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        when (status) {
            ListenerServiceStatus.LISTENING -> Unit
            ListenerServiceStatus.NOT_GRANTED -> Button(
                onClick = onAuthorize,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
            ) {
                Text("去授权", style = MaterialTheme.typography.labelMedium)
            }

            ListenerServiceStatus.WAITING -> WaitingRebindButton(onClick = onRebind)
        }
    }
}

/**
 * 「点此重新绑定」按钮：仅在等待连接态挂起 [REBIND_APPEAR_DELAY_MS] 后出现，
 * 避免连接中的正常窗口内闪烁（引导页与首页状态卡共用）。
 */
@Composable
fun WaitingRebindButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(REBIND_APPEAR_DELAY_MS)
        visible = true
    }
    if (visible) {
        OutlinedButton(
            onClick = onClick,
            modifier = modifier,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
        ) {
            Text("点此重新绑定", style = MaterialTheme.typography.labelMedium)
        }
    }
}
