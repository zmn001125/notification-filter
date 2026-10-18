package com.local.notificationfilter.actions

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.local.notificationfilter.core.backup.BackupScheduleMath
import com.local.notificationfilter.data.prefs.BackupStore
import com.local.notificationfilter.service.BackupAlarmReceiver
import com.local.notificationfilter.util.NfLog
import kotlinx.coroutines.flow.first

/**
 * 自动备份的闹钟调度（V11）。
 *
 * 为什么用 `AlarmManager.setAndAllowWhileIdle` 而不是 `setExactAndAllowWhileIdle`：
 * 备份不需要秒级准时，`setAndAllowWhileIdle` 能穿过 Doze 且**不要求精确闹钟权限**
 * （`SCHEDULE_EXACT_ALARM` 在部分 ROM 上需要用户手动授予，而 `useExactAndAllowWhileIdle`
 * 有每日次数限制）。代价是系统可能延迟几分钟触发，对「每天 3 点备份一次」完全可接受。
 *
 * 为什么不用 WorkManager：项目没有该依赖，为「一天一次」引入一整套 JobScheduler
 * 封装不划算；而且本项目的 `RepostScheduler` 已经在用 AlarmManager，两种机制并存
 * 反而增加心智负担。
 *
 * 每次触发后由 [BackupAlarmReceiver] 重新 `reschedule()`（一次性闹钟 + 自续期），
 * 这是跨 Doze / 跨重启最稳的写法：不依赖 repeating alarm 的漂移行为。
 */
class BackupScheduler(
    private val context: Context,
    private val store: BackupStore,
) {

    private val alarmManager: AlarmManager? =
        context.getSystemService(AlarmManager::class.java)

    /**
     * 按当前设置重排下一次自动备份。
     *
     * 调用点：设置项变更后、开机 / 应用升级后（`BootReceiver`）、
     * 以及每次闹钟触发完（[BackupAlarmReceiver]）。
     */
    suspend fun reschedule() {
        val enabled = store.autoEnabled.first()
        if (!enabled) {
            cancel()
            return
        }
        val hour = store.autoHour.first()
        val minute = store.autoMinute.first()
        val interval = store.autoIntervalHours.first()
        val next = BackupScheduleMath.nextTriggerAt(
            nowMillis = System.currentTimeMillis(),
            hour = hour,
            minute = minute,
            intervalHours = interval,
        )
        runCatching {
            alarmManager?.setAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                next,
                pendingIntent(),
            )
        }.onFailure {
            NfLog.e(this, "自动备份闹钟设置失败: ${it.message}")
        }
    }

    /** 取消已排的自动备份（关闭开关时调用）。 */
    fun cancel() {
        runCatching { alarmManager?.cancel(pendingIntent()) }
            .onFailure { NfLog.w(this, "自动备份闹钟取消失败: ${it.message}") }
    }

    private fun pendingIntent(): PendingIntent {
        val intent = Intent(context, BackupAlarmReceiver::class.java).apply {
            action = BackupAlarmReceiver.ACTION_AUTO_BACKUP
        }
        var flags = PendingIntent.FLAG_UPDATE_CURRENT
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            flags = flags or PendingIntent.FLAG_IMMUTABLE
        }
        return PendingIntent.getBroadcast(context, REQUEST_CODE, intent, flags)
    }

    private companion object {
        /** 固定 requestCode：同一时刻只会有一个「下一次备份」待触发。 */
        const val REQUEST_CODE = 1001
    }
}
