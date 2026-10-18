package com.local.notificationfilter.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.local.notificationfilter.NfApplication
import com.local.notificationfilter.util.NfLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 开机 / 本 App 升级广播：① 重排全部未触发的延时重发任务；② 重发常驻状态通知。
 * （监听权仍在时系统会自动重新绑定监听服务，无需手动拉起。）
 *
 * 常驻通知的恢复条件（V2 需求⑤）：已授权 && 总开关开启 && 常驻通知开关开启，
 * 三者缺一不展示（默认关闭时通知栏不应出现本 App 条目）。
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) {
            return
        }
        NfLog.i(this, "收到广播 action=$action，开始恢复")

        val container = (context.applicationContext as? NfApplication)?.container ?: return
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                container.repostScheduler.rescheduleAll()
                // V11：自动备份闹钟也必须在开机 / 升级后重排（force-stop 会被系统清掉闹钟）
                runCatching { container.backupScheduler.reschedule() }
                    .onFailure { NfLog.w(this@BootReceiver, "自动备份重排失败: ${it.message}") }
                // 常驻通知：仅在监听权已授予 && 总开关开启 && 常驻通知开关开启时展示
                val granted = runCatching {
                    val names = android.provider.Settings.Secure.getString(
                        context.contentResolver,
                        "enabled_notification_listeners",
                    ).orEmpty()
                    names.contains(context.packageName)
                }.getOrDefault(false)
                val masterOn = container.settingsRepository.isMasterEnabled()
                val showOngoing = container.settingsRepository.isShowOngoing()
                if (granted && masterOn && showOngoing) {
                    OngoingStatusNotification(context).show(filtering = true)
                } else {
                    NfLog.i(this, "跳过常驻通知恢复：granted=$granted master=$masterOn ongoing=$showOngoing")
                }
            } finally {
                pendingResult.finish()
            }
        }
    }
}
