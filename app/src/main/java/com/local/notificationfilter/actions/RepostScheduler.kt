package com.local.notificationfilter.actions

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.local.notificationfilter.core.model.PendingRepost
import com.local.notificationfilter.data.repo.RepostRepository
import com.local.notificationfilter.service.RepostAlarmReceiver
import com.local.notificationfilter.util.NfLog

/**
 * 延时重发调度（T06）：任务持久化到 Room，闹钟用 [AlarmManager.setExactAndAllowWhileIdle]
 * （Doze 下仍可精确触发；WorkManager 15 分钟最小周期不适用）。
 *
 * P2-10：原实现用 [AlarmManager.setAlarmClock]——那是**闹钟级** API，会在状态栏显示闹钟图标
 * （用户会以为是闹钟）且是 Doze 下最强唤醒，对「延迟重发一条通知」语义过重。现改为
 * `setExactAndAllowWhileIdle`：唤醒级别降档、状态栏不再有闹钟图标，触发时刻仍精确。
 * `PendingIntent` 的 requestCode / flags / action 常量保持原样，已入库任务的闹钟可被正确取消/重排。
 *
 * 已知限制：重启后原 contentIntent 不可恢复（PendingIntent 无法序列化落库），
 * 到点重发降级为「打开源 App」（待明确 #7，V1 接受）。
 */
class RepostScheduler(
    private val context: Context,
    private val repository: RepostRepository,
) {

    private val alarmManager: AlarmManager? =
        context.getSystemService(AlarmManager::class.java)

    /** 持久化任务并排闹钟；返回任务 id。 */
    suspend fun schedule(task: PendingRepost): Long {
        val id = repository.insert(task)
        NfLog.i(this, "延时重发任务 id=$id triggerAt=${task.triggerAt} pkg=${task.packageName}")
        setAlarm(task.copy(id = id))
        return id
    }

    /** 取消单个任务（闹钟 + 库）。 */
    suspend fun cancel(id: Long) {
        alarmManager?.cancel(pendingIntent(id))
        repository.delete(id)
        NfLog.i(this, "取消延时重发任务 id=$id")
    }

    /** 规则被删除：清理其全部未触发任务。 */
    suspend fun cancelByRule(ruleId: Long) {
        val tasks = repository.allPending().filter { it.ruleId == ruleId }
        tasks.forEach { alarmManager?.cancel(pendingIntent(it.id)) }
        repository.deleteByRule(ruleId)
        if (tasks.isNotEmpty()) NfLog.i(this, "规则 $ruleId 删除，清理 ${tasks.size} 个延时任务")
    }

    /** 原通知被源 App 主动取消：按原始 key 清理（待明确 #6 已预留，默认仍按计划重发）。 */
    suspend fun cancelByOriginalKey(key: String) {
        repository.deleteByOriginalKey(key)
    }

    /** 开机 / 本 App 升级后重排全部未触发任务（闹钟不持久化）。 */
    suspend fun rescheduleAll() {
        val tasks = repository.allPending()
        tasks.forEach { setAlarm(it) }
        NfLog.i(this, "重启后重排 ${tasks.size} 个延时重发任务")
    }

    private fun setAlarm(task: PendingRepost) {
        val manager = alarmManager ?: return
        val pi = pendingIntent(task.id)
        runCatching {
            // P2-10：精确闹钟（API 23+，minSdk 26 恒可用），Doze 下仍可按时触发；
            // 不显示状态栏闹钟图标，唤醒级别低于 setAlarmClock。
            manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, task.triggerAt, pi)
        }.onFailure {
            NfLog.e(this, "setExactAndAllowWhileIdle 失败 id=${task.id}: ${it.message}")
            // 降级：无精确闹钟权限（Manifest 仅声明 USE_EXACT_ALARM 时，Android 12/12L 上
            // setExact* 会抛 SecurityException）→ 回退到窗口闹钟（±60s，Doze 结束后触发）
            runCatching { manager.setWindow(AlarmManager.RTC_WAKEUP, task.triggerAt, 60_000L, pi) }
        }
    }

    private fun pendingIntent(id: Long): PendingIntent {
        val intent = Intent(context, RepostAlarmReceiver::class.java).apply {
            action = RepostAlarmReceiver.ACTION_REPOST
            putExtra(RepostAlarmReceiver.EXTRA_TASK_ID, id)
        }
        var flags = PendingIntent.FLAG_UPDATE_CURRENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            flags = flags or PendingIntent.FLAG_IMMUTABLE
        }
        return PendingIntent.getBroadcast(context, id.toInt(), intent, flags)
    }
}
