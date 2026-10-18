package com.local.notificationfilter.data.db

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Update
import com.local.notificationfilter.core.model.IdOrder
import kotlinx.coroutines.flow.Flow

// ==================== 聚合查询结果 POJO ====================

data class AppStatRaw(
    val packageName: String,
    val appName: String,
    val filtered: Int,
    val passed: Int,
)

data class DayStatRaw(
    val epochDay: Int,
    val filtered: Int,
    val passed: Int,
)

data class DayTotalsRaw(
    val filtered: Int,
    val passed: Int,
)

/** V3-2 规则命中数聚合结果（ruleId → count）。 */
data class RuleHitCountRaw(
    val ruleId: Long,
    val count: Int,
)

// ==================== RuleDao ====================

@Dao
abstract class RuleDao {

    @Query("SELECT * FROM rules ORDER BY `order` ASC, id ASC")
    abstract fun observeAll(): Flow<List<RuleEntity>>

    @Query("SELECT * FROM rules ORDER BY `order` ASC, id ASC")
    abstract suspend fun getAll(): List<RuleEntity>

    @Query("SELECT COALESCE(MAX(`order`), -1) FROM rules")
    abstract suspend fun maxOrder(): Int

    @Insert
    abstract suspend fun insert(entity: RuleEntity): Long

    @Update
    abstract suspend fun update(entity: RuleEntity)

    @Query("DELETE FROM rules WHERE id = :id")
    abstract suspend fun deleteById(id: Long)

    @Query("UPDATE rules SET `order` = :order WHERE id = :id")
    abstract suspend fun updateOrder(id: Long, order: Int)

    /**
     * 仅更新规则 JSON 单列（V2T04 旧规则迁移写回；不触碰冗余的 name/enabled/order 列，
     * 迁移保持这些列语义不变）。
     */
    @Query("UPDATE rules SET json = :json WHERE id = :id")
    abstract suspend fun updateJson(id: Long, json: String)

    @Transaction
    open suspend fun updateOrders(orders: List<IdOrder>) {
        orders.forEach { updateOrder(it.id, it.order) }
    }
}

// ==================== RecordDao ====================

@Dao
abstract class RecordDao {

    @Insert
    abstract suspend fun insert(entity: RecordEntity): Long

    /** 保留最近 [keep] 条，其余淘汰（RecordRepository.KEEP）。 */
    @Query(
        """
        DELETE FROM notification_records
        WHERE recordId NOT IN (
            SELECT recordId FROM notification_records ORDER BY recordId DESC LIMIT :keep
        )
        """
    )
    abstract suspend fun trimTo(keep: Int)

    @Transaction
    open suspend fun insertAndTrim(entity: RecordEntity, keep: Int): Long {
        val id = insert(entity)
        // P2-1：稳态下插入后的条数恰好是 keep + 1，原实现仍然每次完整跑一遍
        // 「DELETE … WHERE recordId NOT IN (SELECT … LIMIT 1000)」（1000 行规模的子查询，
        // 几乎总是删 0 行，却在写事务里带来额外 WAL 落盘）。改为先计数、确实超量才淘汰。
        // 一次 COUNT(*) 由 SQLite 走表记录/索引计数，成本远低于一次 NOT IN 全表比较。
        if (countAll() > keep) trimTo(keep)
        return id
    }

    /** 当前流水总条数（P2-1：淘汰判定的前置条件）。 */
    @Query("SELECT COUNT(*) FROM notification_records")
    abstract suspend fun countAll(): Int

    @Query("SELECT * FROM notification_records ORDER BY recordId DESC LIMIT :limit")
    abstract fun observeRecent(limit: Int): Flow<List<RecordEntity>>

    @Query("SELECT * FROM notification_records WHERE recordId = :id")
    abstract suspend fun findById(id: Long): RecordEntity?

    @Query("SELECT * FROM notification_records WHERE `key` = :key ORDER BY recordId DESC LIMIT 1")
    abstract suspend fun findByKey(key: String): RecordEntity?

    /**
     * V3-2 规则命中数：ruleId 非空且 result ∈ {BLOCK,SNOOZE,REPLACE,READ} 的流水按 ruleId 聚合。
     * 黑名单消除的 record.ruleId 为 null → 自然不计入。命中数随流水滚动淘汰自然衰减。
     */
    @Query(
        """
        SELECT ruleId AS ruleId, COUNT(*) AS count
        FROM notification_records
        WHERE ruleId IS NOT NULL
          AND result IN ('BLOCK','SNOOZE','REPLACE','READ')
        GROUP BY ruleId
        """,
    )
    abstract fun observeHitCounts(): Flow<List<RuleHitCountRaw>>

    /**
     * 结构化条件过滤（关键词在 Repository 内按字段内存匹配，保证字段级精度）。
     * 空集合条件通过对应的 noXxxFilter 开关短路。
     */
    @Query(
        """
        SELECT * FROM notification_records WHERE
            (:fromTime IS NULL OR postTime >= :fromTime) AND
            (:toTime IS NULL OR postTime <= :toTime) AND
            (:noPackageFilter OR packageName IN (:packages)) AND
            (:noResultFilter OR result IN (:results)) AND
            (:noRuleFilter OR ruleId = :ruleId)
        ORDER BY recordId DESC LIMIT :limit
        """
    )
    abstract suspend fun searchBasic(
        packages: List<String>,
        noPackageFilter: Boolean,
        results: List<String>,
        noResultFilter: Boolean,
        ruleId: Long?,
        noRuleFilter: Boolean,
        fromTime: Long?,
        toTime: Long?,
        limit: Int,
    ): List<RecordEntity>

    @Query("UPDATE notification_records SET result = :result, executedActionsJson = :actionsJson, ruleId = :ruleId, ruleName = :ruleName, hitFieldsJson = :hitFieldsJson, blacklistHit = :blacklistHit WHERE recordId = :recordId")
    abstract suspend fun updateResult(
        recordId: Long,
        result: String,
        actionsJson: String,
        hitFieldsJson: String,
        ruleId: Long?,
        ruleName: String?,
        blacklistHit: Boolean,
    )

    @Query("UPDATE notification_records SET restoredAt = :restoredAt WHERE recordId = :recordId")
    abstract suspend fun markRestored(recordId: Long, restoredAt: Long)

    /**
     * V10.11 刷屏修复：把「同一条通知的又一次内容更新」就地写回既有流水行。
     *
     * 与 [updateResult] 的分工：那个只改写**结论**（result / actions / ruleId ...），
     * 这个改写**内容**（标题 / 正文 / extras / 时间戳 / flags ...）。两者作用于同一行、
     * 互不覆盖，配合起来等价于「重插一遍这条流水，但不新增行、不动 recordId」。
     *
     * **刻意不动 `restoredAt`**：snooze 恢复标记是「这条通知是否已经恢复过」的历史事实，
     * 内容更新不应把它抹掉（抹掉会让同一条通知被重复标记恢复）。
     *
     * **刻意不动 `recordId`**：行位置（列表按 recordId DESC 排序）保持原样 ——
     * 正在刷新的那条通知应当原地更新，而不是每 1.2 秒跳到列表顶部。
     */
    @Query(
        """
        UPDATE notification_records SET
            `key` = :key,
            notifId = :notifId,
            packageName = :packageName,
            appName = :appName,
            postTime = :postTime,
            extrasJson = :extrasJson,
            titleSummary = :titleSummary,
            textSummary = :textSummary,
            userId = :userId,
            channelId = :channelId,
            isOngoing = :isOngoing,
            flags = :flags
        WHERE recordId = :recordId
        """
    )
    abstract suspend fun updateSnapshot(
        recordId: Long,
        key: String,
        notifId: Int,
        packageName: String,
        appName: String,
        postTime: Long,
        extrasJson: String,
        titleSummary: String,
        textSummary: String,
        userId: Int,
        channelId: String?,
        isOngoing: Boolean,
        flags: Int,
    )

    @Query("DELETE FROM notification_records")
    abstract suspend fun clearAll()
}

// ==================== StatsDao ====================

@Dao
abstract class StatsDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertIgnore(entity: StatDailyEntity): Long

    @Query(
        """
        UPDATE stats_daily
        SET filtered = filtered + :filteredDelta, passed = passed + :passedDelta
        WHERE packageName = :packageName AND epochDay = :epochDay
        """
    )
    abstract suspend fun bump(
        packageName: String,
        epochDay: Int,
        filteredDelta: Int,
        passedDelta: Int,
    )

    /**
     * V10.7 耗电修复：只更新**当天**那一行的应用名。
     *
     * 原 SQL 没有 `epochDay` 条件 → 每条通知都要 `UPDATE` 该包**全部历史日行**
     * （用久了就是几百行/次，每次都是一个 fsync 事务）。统计页按天聚合展示，
     * 历史行保留旧名字不影响使用；App 改名后从当天起显示新名字。
     *
     * V13.1 再加 `AND appName <> :appName`：**名字没变时一行都不匹配**，
     * SQLite 就不会重写这一行、也不追加 WAL 帧。原来这条 UPDATE 对每条通知无条件执行，
     * 而应用名几乎永远不变 —— 是纯粹的白写。
     */
    @Query(
        "UPDATE stats_daily SET appName = :appName " +
            "WHERE packageName = :packageName AND epochDay = :epochDay AND appName <> :appName"
    )
    abstract suspend fun refreshAppName(packageName: String, appName: String, epochDay: Int)

    @Transaction
    open suspend fun increment(
        packageName: String,
        appName: String,
        epochDay: Int,
        filtered: Boolean,
    ) {
        // V13.1：新行已带着正确的 appName 插入，不必再 UPDATE 一遍（省一条写语句）。
        // insertIgnore 返回 -1 表示「行已存在」，只有这种情况才可能需要刷新应用名。
        val inserted = insertIgnore(StatDailyEntity(packageName, appName, epochDay, 0, 0))
        if (inserted == -1L) refreshAppName(packageName, appName, epochDay)
        bump(packageName, epochDay, if (filtered) 1 else 0, if (filtered) 0 else 1)
    }

    @Query(
        """
        SELECT packageName, appName, SUM(filtered) AS filtered, SUM(passed) AS passed
        FROM stats_daily WHERE epochDay >= :fromDay
        GROUP BY packageName, appName
        ORDER BY (filtered + passed) DESC, filtered DESC
        """
    )
    abstract suspend fun byAppRaw(fromDay: Int): List<AppStatRaw>

    @Query(
        """
        SELECT epochDay, SUM(filtered) AS filtered, SUM(passed) AS passed
        FROM stats_daily WHERE epochDay BETWEEN :fromDay AND :toDay
        GROUP BY epochDay ORDER BY epochDay ASC
        """
    )
    abstract suspend fun trendRaw(fromDay: Int, toDay: Int): List<DayStatRaw>

    @Query(
        """
        SELECT COALESCE(SUM(filtered), 0) AS filtered, COALESCE(SUM(passed), 0) AS passed
        FROM stats_daily WHERE epochDay = :epochDay
        """
    )
    abstract fun observeDay(epochDay: Int): Flow<DayTotalsRaw>
}

// ==================== BlacklistDao ====================

@Dao
abstract class BlacklistDao {

    @Query("SELECT * FROM blacklist ORDER BY addedAt DESC")
    abstract fun observeAll(): Flow<List<BlacklistEntity>>

    @Query("SELECT EXISTS(SELECT 1 FROM blacklist WHERE packageName = :pkg)")
    abstract suspend fun contains(pkg: String): Boolean

    /**
     * V13.1：一次性取出全部包名，供 [com.local.notificationfilter.data.repo.BlacklistRepository]
     * 建立进程内缓存（黑名单只有几行，一次全取比每条通知查一次便宜得多）。
     */
    @Query("SELECT packageName FROM blacklist")
    abstract suspend fun allPackages(): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun add(entity: BlacklistEntity)

    @Query("DELETE FROM blacklist WHERE packageName = :pkg")
    abstract suspend fun remove(pkg: String)

    /** V11 备份恢复：清空整张黑名单（「覆盖式恢复」用，恢复前会自动存快照）。 */
    @Query("DELETE FROM blacklist")
    abstract suspend fun clearAll()

    @Query("SELECT COUNT(*) FROM blacklist")
    abstract fun observeCount(): Flow<Int>
}

// ==================== RepostDao ====================

@Dao
abstract class RepostDao {

    @Insert
    abstract suspend fun insert(entity: PendingRepostEntity): Long

    @Query("SELECT * FROM pending_reposts ORDER BY triggerAt ASC")
    abstract suspend fun allPending(): List<PendingRepostEntity>

    @Query("SELECT * FROM pending_reposts WHERE id = :id")
    abstract suspend fun findById(id: Long): PendingRepostEntity?

    @Query("DELETE FROM pending_reposts WHERE id = :id")
    abstract suspend fun deleteById(id: Long)

    @Query("DELETE FROM pending_reposts WHERE ruleId = :ruleId")
    abstract suspend fun deleteByRule(ruleId: Long)

    @Query("DELETE FROM pending_reposts WHERE originalKey = :key")
    abstract suspend fun deleteByOriginalKey(key: String)
}

// ==================== AggregationDao ====================

/**
 * 聚合暂存列表 DAO（V7 需求 8）。
 *
 * 作用域键 = `(ruleId, listIndex)`；排序口径统一为 `createdAt DESC, id DESC`
 * （同一毫秒内写入多条时按主键兜底，保证「最新在前」的全序稳定）。
 * 所有写路径都收在 [appendAndTrim] 一个事务里，避免「删同值 / 插入 / 汰旧」被并发打断。
 */
@Dao
abstract class AggregationDao {

    /** 插入（唯一索引冲突时静默忽略；返回值 -1 表示未插入）。 */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertIgnore(entity: AggregationItemEntity): Long

    /** 删除同列表下的同值条目（去重时「先删旧再插新」以保证最新的在前）。 */
    @Query(
        """
        DELETE FROM aggregation_items
        WHERE ruleId = :ruleId AND listIndex = :listIndex AND value = :value
        """
    )
    abstract suspend fun deleteSameValue(ruleId: Long, listIndex: Int, value: String)

    /**
     * 保留该列表最新 [keep] 条，其余淘汰（与 `RecordDao.trimTo` 同款写法）。
     * 调用方必须保证 `keep > 0`（`keep <= 0` 会清空整个列表）。
     */
    @Query(
        """
        DELETE FROM aggregation_items
        WHERE ruleId = :ruleId AND listIndex = :listIndex
          AND id NOT IN (
              SELECT id FROM aggregation_items
              WHERE ruleId = :ruleId AND listIndex = :listIndex
              ORDER BY createdAt DESC, id DESC LIMIT :keep
          )
        """
    )
    abstract suspend fun trimListTo(ruleId: Long, listIndex: Int, keep: Int)

    /**
     * 写入一条 + 去重 + 汰旧，**单事务**（并发安全）。
     *
     * - [dedupe] = true：先删掉同列表同值的旧行（若有），新行因此拿到新 `id`/`createdAt`
     *   并排到最前，列表内同值恒只有一条；
     * - [dedupe] = false：直接插入；若跨列表/跨规则无关，但同值已存在时由唯一索引
     *   `IGNORE` 兜底（不会抛异常，只是不新增），列表内仍不会出现重复行；
     * - [keep] > 0 时按 [trimListTo] 淘汰超限旧条目（[keep] <= 0 视为不限制，不删除任何行）。
     *
     * @return 新行主键；-1 = 被唯一索引忽略（未插入）。
     */
    @Transaction
    open suspend fun appendAndTrim(
        entity: AggregationItemEntity,
        dedupe: Boolean,
        keep: Int,
    ): Long {
        if (dedupe) deleteSameValue(entity.ruleId, entity.listIndex, entity.value)
        val id = insertIgnore(entity)
        if (keep > 0) trimListTo(entity.ruleId, entity.listIndex, keep)
        return id
    }

    /** 该列表最近 [limit] 条的**纯文本**（渲染上下文用，最新在前）。 */
    @Query(
        """
        SELECT value FROM aggregation_items
        WHERE ruleId = :ruleId AND listIndex = :listIndex
        ORDER BY createdAt DESC, id DESC LIMIT :limit
        """
    )
    abstract suspend fun valuesOf(ruleId: Long, listIndex: Int, limit: Int): List<String>

    /** 该列表最近 [limit] 条的完整行（规则页「当前汇总」用，最新在前）。 */
    @Query(
        """
        SELECT * FROM aggregation_items
        WHERE ruleId = :ruleId AND listIndex = :listIndex
        ORDER BY createdAt DESC, id DESC LIMIT :limit
        """
    )
    abstract suspend fun itemsOf(
        ruleId: Long,
        listIndex: Int,
        limit: Int,
    ): List<AggregationItemEntity>

    /** 该规则全部条目（按列表分组、最新在前；`limitsOf` 多提取器场景用）。 */
    @Query(
        """
        SELECT * FROM aggregation_items WHERE ruleId = :ruleId
        ORDER BY listIndex ASC, createdAt DESC, id DESC
        """
    )
    abstract suspend fun itemsByRule(ruleId: Long): List<AggregationItemEntity>

    /** 规则页「当前汇总」区块的实时流（同样按列表分组、最新在前）。 */
    @Query(
        """
        SELECT * FROM aggregation_items WHERE ruleId = :ruleId
        ORDER BY listIndex ASC, createdAt DESC, id DESC
        """
    )
    abstract fun observeByRule(ruleId: Long): Flow<List<AggregationItemEntity>>

    @Query("DELETE FROM aggregation_items WHERE id = :id")
    abstract suspend fun deleteById(id: Long)

    /** 清空该规则的单个列表（[listIndex] 为 null 时用 [deleteByRule]）。 */
    @Query("DELETE FROM aggregation_items WHERE ruleId = :ruleId AND listIndex = :listIndex")
    abstract suspend fun deleteList(ruleId: Long, listIndex: Int)

    /** 清空该规则全部列表（规则删除时级联清理）。 */
    @Query("DELETE FROM aggregation_items WHERE ruleId = :ruleId")
    abstract suspend fun deleteByRule(ruleId: Long)

    /** 该列表当前条数（汰旧/UI 摘要用，亦便于调试核对）。 */
    @Query("SELECT COUNT(*) FROM aggregation_items WHERE ruleId = :ruleId AND listIndex = :listIndex")
    abstract suspend fun countList(ruleId: Long, listIndex: Int): Int
}

// ==================== NfDatabase ====================
// 见 NfDatabase.kt

