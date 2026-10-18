package com.local.notificationfilter.core.backup

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 备份文件名规则（V11.1 抽成纯函数，方便单测）。
 *
 * V11.0 的规则是「固定文件名反复覆盖」，用户反馈要**按当天时间命名、保留多份**
 * （云端尤其如此），于是所有文件名都带时间戳。三类前缀互相区分，自动清理时
 * 只清自己那一类，绝不会误删用户手动备份：
 *
 * - `nf-backup-<时间戳>.json`       手动备份（本地 / 云端），永不自动删
 * - `nf-auto-<时间戳>.json`         自动备份，保留最近 [AUTO_KEEP] 份
 * - `nf-before-restore-<时间戳>.json`  恢复前快照，保留最近 [SNAPSHOT_KEEP] 份
 *
 * 时间戳格式定宽补零（`yyyyMMdd-HHmmss`），所以**字典序 = 时间序**，
 * 排序即排序，不需要解析日期。
 */
object BackupNames {

    /** 手动备份前缀。 */
    const val MANUAL_PREFIX = "nf-backup-"

    /** 自动备份前缀。 */
    const val AUTO_PREFIX = "nf-auto-"

    /** 恢复前快照前缀。 */
    const val SNAPSHOT_PREFIX = "nf-before-restore-"

    /** 自动备份云端保留份数（约一个月的日备）。 */
    const val AUTO_KEEP = 30

    /** 恢复前快照保留份数。 */
    const val SNAPSHOT_KEEP = 5

    /**
     * 定宽时间戳格式。
     *
     * **必须用 `DateTimeFormatter`（不可变、线程安全）而不是 `SimpleDateFormat`**：
     * 备份命名会在多处并发调用（备份页的 ViewModel + `BackupAlarmReceiver` 的自动备份
     * 协程），而 `SimpleDateFormat` 内部是可变状态，并发格式化会产出乱码日期甚至抛异常。
     */
    private val FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")

    /** 当前时间的时间戳部分（`yyyyMMdd-HHmmss`，本地时区）。 */
    fun timestamp(nowMillis: Long = System.currentTimeMillis()): String =
        FORMAT.format(Instant.ofEpochMilli(nowMillis).atZone(ZoneId.systemDefault()))

    /** 手动备份文件名：`nf-backup-20261018-1605.json`。 */
    fun manual(nowMillis: Long = System.currentTimeMillis()): String =
        "$MANUAL_PREFIX${timestamp(nowMillis)}${BackupTarget.EXTENSION}"

    /** 自动备份文件名：`nf-auto-20261018-0300.json`。 */
    fun auto(nowMillis: Long = System.currentTimeMillis()): String =
        "$AUTO_PREFIX${timestamp(nowMillis)}${BackupTarget.EXTENSION}"

    /** 恢复前快照文件名：`nf-before-restore-20261018-1605.json`。 */
    fun beforeRestore(nowMillis: Long = System.currentTimeMillis()): String =
        "$SNAPSHOT_PREFIX${timestamp(nowMillis)}${BackupTarget.EXTENSION}"

    fun isManual(name: String): Boolean =
        name.startsWith(MANUAL_PREFIX) && name.endsWith(BackupTarget.EXTENSION, true)

    fun isAuto(name: String): Boolean =
        name.startsWith(AUTO_PREFIX) && name.endsWith(BackupTarget.EXTENSION, true)

    fun isBeforeRestore(name: String): Boolean =
        name.startsWith(SNAPSHOT_PREFIX) && name.endsWith(BackupTarget.EXTENSION, true)

    /**
     * 从「现有文件列表」算出应该删掉的旧文件（保持最近 [keep] 份，新的在前）。
     *
     * 列表无需预先排序；内部按字典序（= 时间序）倒序排。非本类前缀的文件
     * （用户手动备份等）原样忽略，绝不删除。
     */
    fun staleFiles(existing: List<String>, prefix: String, keep: Int): List<String> =
        existing
            .filter { it.startsWith(prefix) && it.endsWith(BackupTarget.EXTENSION, true) }
            .sortedDescending()
            .drop(keep.coerceAtLeast(0))
}
