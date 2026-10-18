package com.local.notificationfilter.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.local.notificationfilter.util.NfLog
import java.io.File
import java.io.IOException

/**
 * Room 数据库（V7：版本 2，导出 schema 便于校验迁移）。
 *
 * 表清单（6 张）：
 * - V1 起：`rules` / `notification_records` / `stats_daily` / `pending_reposts` / `blacklist`
 * - V7 新增：`aggregation_items`（需求 8 聚合暂存）
 *
 * **版本历史**：
 * - v1 → v2（V7）：**纯增量**——新建 `aggregation_items` 表 + `pending_reposts` 加 2 列
 *   （`renderSource` / `aggregationJson`）。既有 5 张表的 schema **一个字段都没动**，
 *   因此迁移不会重写任何既有行（`rules` / 流水 / 统计的数据不经过迁移代码）。
 *
 * **红线**：禁止任何「销毁式迁移」（Room 的 `fallback…Destructive…` 系列 API）。本 App 是
 * 自用长期使用，用户机上有真实规则/流水/统计，销毁式迁移等于丢数据。升级前另做一次
 * 文件级备份（[backupBeforeMigrationIfNeeded]，见架构 §2.4）。
 */
@Database(
    entities = [
        RuleEntity::class,
        RecordEntity::class,
        StatDailyEntity::class,
        PendingRepostEntity::class,
        BlacklistEntity::class,
        AggregationItemEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
abstract class NfDatabase : RoomDatabase() {

    abstract fun ruleDao(): RuleDao

    abstract fun recordDao(): RecordDao

    abstract fun statsDao(): StatsDao

    abstract fun blacklistDao(): BlacklistDao

    abstract fun repostDao(): RepostDao

    /** V7 新增：聚合暂存列表。 */
    abstract fun aggregationDao(): AggregationDao

    companion object {
        private const val DB_NAME = "nf.db"

        /**
         * V7 迁移前的一次性快照文件名（与 V2T04 的 `nf_backup_v1.db` **刻意区分**：
         * 后者可能已由旧版本创建过，继续用同名会因「备份已存在」而静默跳过，
         * 起不到保护 v1→v2 的作用）。
         */
        private const val PRE_MIGRATION_BACKUP_NAME = "nf_backup_pre_v2.db"

        @Volatile
        private var instance: NfDatabase? = null

        fun get(context: Context): NfDatabase =
            instance ?: synchronized(this) {
                instance ?: run {
                    val appContext = context.applicationContext
                    // 必须在 build()/首次访问之前完成：Room 的迁移发生在数据库首次打开时，
                    // 一旦打开就没有"迁移前快照"可拍了。
                    backupBeforeMigrationIfNeeded(appContext)
                    Room.databaseBuilder(
                        appContext,
                        NfDatabase::class.java,
                        DB_NAME,
                    )
                        .addMigrations(*ALL_MIGRATIONS)
                        .build()
                        .also { instance = it }
                }
            }

        /**
         * V7 迁移：**纯增量**（`CREATE TABLE` + `ADD COLUMN`），不触碰任何既有数据。
         *
         * 幂等性：Room 只在版本号变更时执行一次；且每条语句都带 `IF NOT EXISTS` /
         * `DEFAULT`，即使被重复执行也不会失败或产生歧义值。
         *
         * **列定义必须与实体逐字一致**（含 `AUTOINCREMENT`、`NOT NULL`、默认值、索引名），
         * 否则 Room 启动时 `validateMigration` 会抛
         * `IllegalStateException: Migration didn't properly handle: ...`。
         */
        val MIGRATION_1_2: Migration = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 1) 聚合暂存表（对应 AggregationItemEntity）
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `aggregation_items` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `ruleId` INTEGER NOT NULL,
                        `listIndex` INTEGER NOT NULL,
                        `value` TEXT NOT NULL,
                        `createdAt` INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                // 2) 索引：与实体 @Entity(indices = [...]) 的名字/列序/唯一性完全一致
                //    （Room 2.6 起按名字比对，名字对不上同样会校验失败）
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS " +
                        "`index_aggregation_items_ruleId_listIndex_value` " +
                        "ON `aggregation_items` (`ruleId`, `listIndex`, `value`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_aggregation_items_ruleId_listIndex` " +
                        "ON `aggregation_items` (`ruleId`, `listIndex`)",
                )
                // 3) pending_reposts 加两列：旧行自动取默认值（PLAIN / NULL）＝ V6 行为
                db.execSQL(
                    "ALTER TABLE `pending_reposts` " +
                        "ADD COLUMN `renderSource` TEXT NOT NULL DEFAULT 'PLAIN'",
                )
                db.execSQL(
                    "ALTER TABLE `pending_reposts` ADD COLUMN `aggregationJson` TEXT",
                )
                NfLog.i(NfDbLogTag, "Room v1 → v2 迁移完成（新增 aggregation_items + 2 列）")
            }
        }

        /** 全部迁移（按版本顺序注册；新增迁移只追加到这里，不要在装配点分散 `.addMigrations`）。 */
        val ALL_MIGRATIONS: Array<Migration> = arrayOf(MIGRATION_1_2)

        /**
         * 迁移前数据库快照（架构 §2.4 的风险缓解）。
         *
         * 时机：`Room.databaseBuilder(...).build()` 之前（`build()` 本身惰性，真正的打开
         * 与迁移发生在首次 DAO 访问，所以此处一定早于迁移）。
         * 策略：
         * - `nf.db` 不存在（全新安装）→ 什么也不做；
         * - 目标快照已存在 → 不覆盖（只保留升级那一刻的唯一快照，成本恒定一次文件拷贝）；
         * - 复制 `nf.db` + `-wal` + `-shm` 三件套（与 [com.local.notificationfilter.util.DbBackup]
         *   同口径），保证快照是完整的 WAL 视图；
         * - 任何异常只记日志、**不阻断启动**（备份是兜底，不是前置条件）。
         */
        private fun backupBeforeMigrationIfNeeded(context: Context) {
            try {
                val dbFile = context.getDatabasePath(DB_NAME)
                if (!dbFile.exists()) {
                    NfLog.d(NfDbLogTag, "数据库文件不存在（全新安装），跳过迁移前备份")
                    return
                }
                val target = File(context.filesDir, PRE_MIGRATION_BACKUP_NAME)
                if (target.exists()) return
                copySidecar(dbFile, target)
                copySidecar(File("${dbFile.path}-wal"), File("${target.path}-wal"))
                copySidecar(File("${dbFile.path}-shm"), File("${target.path}-shm"))
                NfLog.i(NfDbLogTag, "已创建 v1→v2 迁移前快照: ${target.absolutePath}")
            } catch (e: IOException) {
                NfLog.w(NfDbLogTag, "迁移前快照失败（继续启动，迁移本身不受影响）: ${e.message}")
                // 清理半成品，避免下次误判「已有快照」
                val target = File(context.filesDir, PRE_MIGRATION_BACKUP_NAME)
                listOf(target, File("${target.path}-wal"), File("${target.path}-shm"))
                    .forEach { runCatching { it.delete() } }
            } catch (e: SecurityException) {
                NfLog.w(NfDbLogTag, "迁移前快照无权限（继续启动）: ${e.message}")
            }
        }

        /** 复制单个文件；源不存在（如 WAL 已 checkpoint）则跳过。 */
        private fun copySidecar(src: File, dst: File) {
            if (!src.exists()) return
            src.copyTo(dst, overwrite = true)
        }
    }
}

/**
 * 数据库层日志标签（`NfLog` 由对象类名派生 tag；匿名 `Migration` 的 `simpleName` 为空串，
 * 故用一个具名占位对象，日志里显示为 `NF:NfDbLogTag`）。
 */
private object NfDbLogTag
