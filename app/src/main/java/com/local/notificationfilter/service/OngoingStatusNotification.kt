package com.local.notificationfilter.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.local.notificationfilter.MainActivity
import com.local.notificationfilter.NfApplication
import com.local.notificationfilter.R
import com.local.notificationfilter.util.NfLog

/** 通知渠道 ID 集中管理（常驻状态渠道 + 每源 App 一个重发渠道）。 */
object NfChannels {

    /** 常驻「过滤中 / 已暂停」状态渠道。 */
    const val STATUS = "nf_status"

    /** 替换重发渠道（每源 App 一个）。 */
    fun repost(packageName: String): String = "repost_$packageName"

    fun ensureStatusChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(STATUS) != null) return
        val channel = NotificationChannel(
            STATUS,
            context.getString(R.string.status_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = context.getString(R.string.status_channel_desc)
            setShowBadge(false)
            enableVibration(false)
        }
        manager.createNotificationChannel(channel)
    }
}

/**
 * 常驻「过滤中 / 已暂停」通知（PRD 已确认决策 6 + HyperOS3 保活手段）。
 * 与监听服务前台化共用同一通知 ID：FGS 提升失败时降级为普通 ongoing 通知。
 */
class OngoingStatusNotification(private val context: Context) {

    fun show(filtering: Boolean) {
        NfChannels.ensureStatusChannel(context)
        val notification = buildNotification(filtering)
        runCatching {
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.notify(NOTIFICATION_ID, notification)
        }.onFailure { NfLog.w(this, "常驻通知发布失败: ${it.message}") }
    }

    fun hide() {
        runCatching {
            context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
        }
    }

    /** 构建常驻通知（前台化 startForeground 也复用同一实例）。 */
    fun buildNotification(filtering: Boolean): Notification {
        val contentIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(context, NfChannels.STATUS)
            .setSmallIcon(R.drawable.ic_stat_filter)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(
                if (filtering) context.getString(R.string.status_filtering)
                else context.getString(R.string.status_paused)
            )
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(contentIntent)
            .build()
    }

    companion object {
        const val NOTIFICATION_ID = 1001
    }
}
