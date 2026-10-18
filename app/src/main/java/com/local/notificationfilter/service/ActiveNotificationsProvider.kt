package com.local.notificationfilter.service

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.local.notificationfilter.core.ruleengine.FieldExtractor
import com.local.notificationfilter.core.model.NotificationSnapshot
import com.local.notificationfilter.util.AppUtils

/**
 * 当前通知栏快照提供者（P6/P10 测试素材来源之一，PRD 风险 3）。
 *
 * 监听服务连接时 attach；未连接（未授权 / 服务未起）时返回空列表。
 * 快照不含本 App 自身通知（防递归约定同样适用于测试素材）。
 */
object ActiveNotificationsProvider {

    @Volatile
    private var service: NotificationListenerService? = null

    fun attach(service: NotificationListenerService) {
        this.service = service
    }

    fun detach() {
        this.service = null
    }

    val isAttached: Boolean get() = service != null

    /** 当前通知栏快照（最新在前）。 */
    fun snapshots(): List<NotificationSnapshot> {
        val s = service ?: return emptyList()
        return runCatching {
            s.activeNotifications.orEmpty()
                .filter { it.packageName != AppUtils.selfPackage(s) }
                .sortedByDescending { it.postTime }
                .map { sbn -> FieldExtractor.fromStatusBar(sbn, AppUtils.appNameOf(s, sbn.packageName)) }
        }.getOrDefault(emptyList())
    }
}
