package com.local.notificationfilter.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.local.notificationfilter.core.model.RepostRenderSource

// ==================== rules ====================

/**
 * 规则表：整条规则以 JSON 单列存储（[json]），并冗余 name/enabled/order 列
 * 供排序与列表查询（架构共享约定 11：schemaVersion = 1）。
 */
@Entity(tableName = "rules")
data class RuleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val name: String,
    val enabled: Boolean,
    val order: Int,
    val schemaVersion: Int,
    val json: String,
)

// ==================== notification_records ====================

/** 通知流水表：约 1000 条滚动淘汰（RecordDao.insertAndTrim）。 */
@Entity(
    tableName = "notification_records",
    indices = [
        Index(value = ["postTime"]),
        Index(value = ["key"]),
        Index(value = ["packageName"]),
        Index(value = ["result"]),
    ],
)
data class RecordEntity(
    @PrimaryKey(autoGenerate = true) val recordId: Long = 0L,
    val key: String,
    val notifId: Int,
    val packageName: String,
    val appName: String,
    val postTime: Long,
    /** ResultKind.name */
    val result: String,
    /** ActionType 名单 JSON 数组 */
    val executedActionsJson: String,
    /**
     * FieldRef.id 名单 JSON 数组（架构共享约定 2：TITLE / meta:KEY / extras:<键名>）。
     * V1 旧数据为 MatchField 枚举名，读取时由 FieldRef.fromId 兼容（schema 不变）。
     */
    val hitFieldsJson: String,
    val ruleId: Long?,
    val ruleName: String?,
    val blacklistHit: Boolean,
    /** 全量 extras dump（JSON 数组 of [key, value]），展示与复制共用。 */
    val extrasJson: String,
    val titleSummary: String,
    val textSummary: String,
    /** 系统 snooze 到点恢复的时间，未恢复为 null。 */
    val restoredAt: Long? = null,
    /** 详情页元信息（P7）：多用户/分身 id。 */
    val userId: Int = 0,
    /** 通知渠道 id（extras 中亦有，单列便于元信息区直接展示）。 */
    val channelId: String? = null,
    /** 是否进行中通知（FLAG_ONGOING）。 */
    val isOngoing: Boolean = false,
    /** 原始 flags（详情页以十六进制展示）。 */
    val flags: Int = 0,
)

// ==================== stats_daily ====================

/** 按 App × 天的过滤/放行计数。 */
@Entity(
    tableName = "stats_daily",
    primaryKeys = ["packageName", "epochDay"],
)
data class StatDailyEntity(
    val packageName: String,
    val appName: String,
    val epochDay: Int,
    val filtered: Int = 0,
    val passed: Int = 0,
)

// ==================== pending_reposts ====================

/**
 * 推迟 × 替换的到点重发任务（闹钟不持久化，任务持久化，重启后重排）。
 *
 * V7（需求 8）：新增 [renderSource] / [aggregationJson] 两列，**默认值即 V6 行为**。
 * Room 迁移 `MIGRATION_1_2` 用 `ALTER TABLE ... DEFAULT 'PLAIN'` 给旧行补值，
 * 因此实体侧的 `@ColumnInfo(defaultValue = "'PLAIN'")` 必须与迁移 SQL 逐字一致
 * （字符串默认值在 Room 里要连单引号一起写），否则启动时 schema 校验会抛
 * `IllegalStateException: Migration didn't properly handle pending_reposts`。
 */
@Entity(tableName = "pending_reposts")
data class PendingRepostEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val ruleId: Long?,
    val triggerAt: Long,
    val originalKey: String,
    val packageName: String,
    val appName: String,
    val repostTitle: String,
    val repostText: String,
    /** ReplaceConfig JSON */
    val replaceConfigJson: String,
    val channelUseAppName: Boolean,

    /** V7：文案来源 `RepostRenderSource.name`（PLAIN = 原样重发已渲染文案）。 */
    @ColumnInfo(defaultValue = "'PLAIN'") val renderSource: String = RepostRenderSource.PLAIN.name,

    /** V7：AGGREGATION 时的聚合配置 JSON（`AggregationConfig` 序列化），可为空。 */
    val aggregationJson: String? = null,
)

// ==================== aggregation_items ====================

/**
 * 聚合暂存条目（V7 需求 8）。
 *
 * 作用域 = 「单条规则单个提取器」：`(ruleId, listIndex)` 唯一确定一个列表；
 * `(ruleId, listIndex, value)` 唯一索引承担**去重**的兜底（同内容只留一条）。
 * 「同值刷新到最前」由 `AggregationRepository.append` 的删旧 + 插入实现；
 * 超限汰旧由 `AggregationDao.trimListTo`（保留 `createdAt` 最新的 N 条）完成。
 *
 * 索引与 `NfDatabase.MIGRATION_1_2` 里 `CREATE [UNIQUE] INDEX` 的名字/列序/唯一性
 * 必须完全一致——Room 启动时按名字比对索引（Room 2.6 起连列序也比）。
 */
@Entity(
    tableName = "aggregation_items",
    indices = [
        Index(value = ["ruleId", "listIndex", "value"], unique = true),
        Index(value = ["ruleId", "listIndex"]),
    ],
)
data class AggregationItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val ruleId: Long,
    /** 提取器列表标识（1..9，稳定不变，见 `ExtractorConfig.listIndex`）。 */
    val listIndex: Int,
    /** 提取出的条目文本（已按 `captureFormat` 拼装）。 */
    val value: String,
    /** 写入时刻（汰旧排序键；同值去重时会刷新）。 */
    val createdAt: Long,
)

// ==================== blacklist ====================

/** 应用黑名单：无条件消除，优先于一切规则。 */
@Entity(tableName = "blacklist")
data class BlacklistEntity(
    @PrimaryKey val packageName: String,
    val appName: String,
    val addedAt: Long,
)
