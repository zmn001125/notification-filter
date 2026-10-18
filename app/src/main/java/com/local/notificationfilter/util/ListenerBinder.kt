package com.local.notificationfilter.util

import android.content.ComponentName
import android.content.Context
import android.service.notification.NotificationListenerService
import com.local.notificationfilter.service.NfNotificationListener

/**
 * 监听服务重新绑定（V2 需求⑤，架构 §5.4）。
 *
 * 「已授权但服务未连接」且等待 >5s 时，请求系统重新绑定监听服务。
 * HyperOS3 有效性待真机验证（架构 §11 R3）；调用失败（如系统拒绝）返回 false，
 * 由 UI 降级提示用户去系统设置重新开关通知使用权。
 */
object ListenerBinder {

    fun requestRebind(context: Context): Boolean =
        runCatching {
            NotificationListenerService.requestRebind(
                ComponentName(context, NfNotificationListener::class.java),
            )
        }.isSuccess
}
