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
 * 自动备份的闹钟接收器（V11）。
 *
 * 与 `RepostAlarmReceiver` 同一套规矩：
 * 1. **绝不在 onReceive 主线程里做 IO**：`goAsync()` + IO 协程，`finally` 里 `finish()`；
 * 2. **异常绝不逃出 onReceive**：广播抛异常会被系统记成处理失败，极端情况下影响
 *    后续广播投递，所以整段包 try/catch；
 * 3. **无论成功失败都要重排下一次**：否则一次网络故障就把自动备份永久停掉；
 * 4. 应用被 force-stop 后闹钟会被系统清掉，这是平台行为（与延迟重发一致），
 *    重排由 [BootReceiver] 在开机 / 升级时补上。
 */
class BackupAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_AUTO_BACKUP) return
        val container = (context.applicationContext as? NfApplication)?.container
        if (container == null) {
            NfLog.w(this, "自动备份跳过：AppContainer 不可用")
            return
        }
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                runCatching { container.backupRepository.runAutoBackup() }
                    .onFailure { NfLog.e(this@BackupAlarmReceiver, "自动备份异常: ${it.message}") }
                // 自续期：先重排下一次，再结束广播
                runCatching { container.backupScheduler.reschedule() }
                    .onFailure { NfLog.e(this@BackupAlarmReceiver, "自动备份重排失败: ${it.message}") }
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        const val ACTION_AUTO_BACKUP = "com.local.notificationfilter.action.AUTO_BACKUP"
    }
}
