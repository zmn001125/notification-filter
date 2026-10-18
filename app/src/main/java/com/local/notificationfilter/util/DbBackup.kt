package com.local.notificationfilter.util

import android.content.Context
import java.io.File
import java.io.IOException

/**
 * 旧规则迁移前的数据库备份（V2T04，架构 §2.6 第 3 条 / PRD 决策 D5）。
 *
 * - 复制 `nf.db` + `nf.db-wal` + `nf.db-shm`（SQLite WAL 三件套，保证快照完整）
 *   到 `filesDir/nf_backup_v1.db`（及同前缀 sidecar）；
 * - **仅当备份不存在才创建，不覆盖旧备份**（保留用户手动清理前的唯一旧版快照）；
 * - 备份已是纯文件复制、失败不抛异常（调用方 RuleRepository 记录日志并继续，
 *   读路径即时迁移幂等兜底正确性）。
 */
object DbBackup {

    /** 备份文件名（与数据库名一一对应， Room 打开需三件套齐全）。 */
    const val BACKUP_DB_NAME = "nf_backup_v1.db"

    private const val DB_NAME = "nf.db"

    /**
     * 执行备份。
     * @return true = 本次创建了新备份；false = 无需备份（备份已存在 / 数据库文件不存在）。
     */
    fun backup(context: Context): Boolean {
        val dbFile = context.getDatabasePath(DB_NAME)
        if (!dbFile.exists()) {
            NfLog.w(this, "数据库文件不存在，跳过备份: ${dbFile.absolutePath}")
            return false
        }
        val target = File(context.filesDir, BACKUP_DB_NAME)
        if (target.exists()) {
            // 已有旧备份：不覆盖（迁移只在检测到旧规则时触发，备份只做一次）
            return false
        }
        return try {
            copySidecar(dbFile, target)
            copySidecar(File("${dbFile.path}-wal"), File("${target.path}-wal"))
            copySidecar(File("${dbFile.path}-shm"), File("${target.path}-shm"))
            NfLog.i(this, "已创建迁移前备份: ${target.absolutePath}")
            true
        } catch (e: IOException) {
            NfLog.w(this, "备份失败: ${e.message}")
            // 清理半成品，避免下次误判「已有备份」
            listOf(target, File("${target.path}-wal"), File("${target.path}-shm"))
                .forEach { runCatching { it.delete() } }
            false
        }
    }

    /** 复制单个 sidecar 文件；源不存在（如 WAL 已 checkpoint）则跳过。 */
    private fun copySidecar(src: File, dst: File) {
        if (!src.exists()) return
        src.copyTo(dst, overwrite = true)
    }
}
