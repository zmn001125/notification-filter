package com.local.notificationfilter.util

import android.content.Context
import kotlinx.coroutines.delay

/**
 * 权限返回重试探测（V2 需求⑥，架构 §5.2）。
 *
 * 背景：从系统设置页返回 App 的瞬间，`enabled_notification_listeners` 的值可能
 * 尚未落库、监听服务可能尚未绑定完成，单次查询会拿到旧值 → 「已授权却显示未授权」。
 *
 * 策略：三拍重试 0s / 0.5s / 1.5s，任一拍 NOTIFICATION_LISTENER 翻正即提前返回；
 * 查询逻辑统一复用 [PermissionChecker.checkAll]，此处只包一层重试，不重复实现。
 * 总跨度 ≤1.5s + 本地查询耗时，满足验收「≤2s 显示已授权」。
 */
object PermissionProbe {

    /** 首拍之后的两拍等待（毫秒）：0s → 500ms → 1500ms。 */
    private val RETRY_DELAYS_MS = longArrayOf(500L, 1_000L)

    suspend fun withRetry(
        context: Context,
        manualPerms: Set<String>,
    ): List<PermissionStatus> {
        var statuses = PermissionChecker.checkAll(context, manualPerms)
        if (statuses.isListenerGranted()) return statuses
        for (delayMs in RETRY_DELAYS_MS) {
            delay(delayMs)
            statuses = PermissionChecker.checkAll(context, manualPerms)
            if (statuses.isListenerGranted()) return statuses
        }
        return statuses
    }

    private fun List<PermissionStatus>.isListenerGranted(): Boolean =
        any { it.type == PermissionType.NOTIFICATION_LISTENER && it.granted }
}
