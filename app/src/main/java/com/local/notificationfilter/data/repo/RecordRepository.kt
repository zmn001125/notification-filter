package com.local.notificationfilter.data.repo

import com.local.notificationfilter.core.model.ActionType
import com.local.notificationfilter.core.model.ExtrasKeys
import com.local.notificationfilter.core.model.FieldRef
import com.local.notificationfilter.core.model.MatchField
import com.local.notificationfilter.core.model.NfJson
import com.local.notificationfilter.core.model.NotificationRecord
import com.local.notificationfilter.core.model.NotificationSnapshot
import com.local.notificationfilter.core.model.ResultKind
import com.local.notificationfilter.core.model.SearchFilter
import com.local.notificationfilter.data.db.RecordDao
import com.local.notificationfilter.data.db.RecordEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.PairSerializer
import kotlinx.serialization.builtins.serializer

/**
 * 流水仓库：插入（同事务淘汰至 [KEEP] 条）/ 最近列表 / 详情 / 关键词字段级搜索。
 *
 * 搜索策略：结构化条件（App / 时间 / 结果 / 规则）走 SQL，关键词在内存按
 * [SearchFilter.fields] 限定字段做「包含」匹配（不区分大小写），保证字段级精度。
 */
class RecordRepository(private val dao: RecordDao) {

    suspend fun insert(record: NotificationRecord): Long =
        dao.insertAndTrim(record.toEntity(), KEEP)

    fun observeRecent(limit: Int): Flow<List<NotificationRecord>> =
        dao.observeRecent(limit).map { list -> list.map { it.toRecord() } }
            // V11 性能：`toRecord()` 每条要解析 2 个 JSON（executedActions / hitFields），
            // 1000 条就是 2000 次解析。Room 的 Flow 默认在订阅者上下文（= 主线程）跑 map，
            // 记录页每次有通知进来都会整体重算一遍 → 主线程被 JSON 解析占住。
            // 挪到 Default 后 UI 只接收成品列表，掉帧消失。
            .flowOn(Dispatchers.Default)

    /** V3-2 规则命中数流（ruleId → count）；黑名单消除不计入（ruleId 为 null）。 */
    fun observeHitCounts(): Flow<Map<Long, Int>> =
        dao.observeHitCounts().map { list -> list.associate { it.ruleId to it.count } }

    suspend fun findById(id: Long): NotificationRecord? = dao.findById(id)?.let { it.toRecord() }

    suspend fun findByKey(key: String): NotificationRecord? =
        dao.findByKey(key)?.let { it.toRecord() }

    suspend fun updateResult(
        recordId: Long,
        result: ResultKind,
        actions: List<ActionType>,
        hitFields: Set<FieldRef>,
        ruleId: Long?,
        ruleName: String?,
        blacklistHit: Boolean,
    ) {
        dao.updateResult(
            recordId = recordId,
            result = result.name,
            actionsJson = NfJson.encodeToString(ListSerializer(String.serializer()), actions.map { it.name }),
            // V2：hitFields 以 FieldRef.id 序列化（TITLE / meta:KEY / extras:<键名>）；
            // 旧流水存的 MatchField 枚举名由 FieldRef.fromId 兼容读出（见 toRecord）
            hitFieldsJson = NfJson.encodeToString(ListSerializer(String.serializer()), hitFields.map { it.id }),
            ruleId = ruleId,
            ruleName = ruleName,
            blacklistHit = blacklistHit,
        )
    }

    /** 系统 snooze 到点恢复：打恢复时间戳，不改结果与计数。 */
    suspend fun markRestored(recordId: Long, restoredAt: Long) {
        dao.markRestored(recordId, restoredAt)
    }

    /**
     * V10.11 刷屏修复：把「同一条通知的又一次内容更新」就地写回既有流水行。
     *
     * 语义：调用方已确认这条通知与 [recordId] 那一行是**同一条存活通知**（没有被移除过），
     * 于是这次更新不新插一行，只刷新内容字段。与 [updateResult] 配合使用——
     * 先 `updateSnapshot` 刷内容，再 `updateResult` 刷结论，等价于「重插一条但不占新行」。
     *
     * 字段口径与 `NotificationPipeline.insertRecord` 严格一致（extrasJson / 摘要 / 渠道 / flags
     * 全部同源），否则「首行」与「后续更新」在详情页会看起来不一样。
     */
    suspend fun updateSnapshot(
        recordId: Long,
        snapshot: NotificationSnapshot,
        appName: String,
        now: Long,
    ) {
        dao.updateSnapshot(
            recordId = recordId,
            key = snapshot.key,
            notifId = snapshot.notifId,
            packageName = snapshot.packageName,
            appName = appName,
            postTime = if (snapshot.postTime > 0) snapshot.postTime else now,
            extrasJson = encodeExtras(snapshot.extras),
            titleSummary = snapshot.displayTitle,
            textSummary = snapshot.displayText,
            userId = snapshot.userId,
            channelId = snapshot.channelId,
            isOngoing = snapshot.isOngoing,
            flags = snapshot.flags,
        )
    }

    private fun encodeExtras(extras: List<Pair<String, String>>): String = runCatching {
        NfJson.encodeToString(pairListSerializer, extras)
    }.getOrDefault("[]")

    suspend fun clearAll() {
        dao.clearAll()
    }

    /**
     * 最近流水中出现过的 extras 键聚合（V2T05 字段选择器数据源，架构 §6.5）：
     * 从最近 [fromLimit] 条流水的 extrasJson 统计键频次，按频次降序（同频次按键名）
     * 取前 [top] 项，返回 `(键名, 示例值)`；示例值取该键在最近流水中的首次出现值。
     *
     * 一次性查询（[first] 取快照，不持续订阅）：只在打开字段选择器时调用一次，
     * 不放进 Flow 反复计算；流水被清空则返回空列表（UI 手动输入兜底）。
     * extrasJson 解析复用 [extrasPairs]（坏 JSON 退化为空，不抛异常）。
     */
    suspend fun recentExtrasKeys(
        fromLimit: Int = FROM_LIMIT,
        top: Int = TOP,
    ): List<Pair<String, String>> {
        val records = dao.observeRecent(fromLimit).first().map { it.toRecord() }
        val counts = LinkedHashMap<String, Int>()
        val samples = HashMap<String, String>()
        records.forEach { record ->
            record.extrasPairs().forEach { (key, value) ->
                counts[key] = (counts[key] ?: 0) + 1
                samples.getOrPut(key) { value }
            }
        }
        return counts.entries
            .sortedWith(
                compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key },
            )
            .take(top)
            .map { it.key to samples[it.key].orEmpty() }
    }

    suspend fun search(filter: SearchFilter): List<NotificationRecord> {
        val rows = dao.searchBasic(
            packages = filter.packages.toList(),
            noPackageFilter = filter.packages.isEmpty(),
            results = filter.results.map { it.name },
            // V11：结果筛选改到内存按「效果集合」判定，SQL 不再按单值 result 预筛。
            // 原因：SQL 只能比 `result IN (...)`（主结论），而「消除 + 朗读」的记录
            // result=BLOCK、effectKinds=[BLOCK,READ] —— 在 SQL 层筛「朗读」会把它漏掉。
            // 候选上限 SEARCH_LIMIT=2000 且 toRecord() 本来就要解析 actionsJson，
            // 所以这次改动是**零额外解析成本**，只是把谓词从 SQL 挪到内存。
            noResultFilter = true,
            ruleId = filter.ruleId,
            noRuleFilter = filter.ruleId == null,
            fromTime = filter.fromTime,
            toTime = filter.toTime,
            limit = SEARCH_LIMIT,
        )
        return withContext(Dispatchers.Default) {
            rows.map { it.toRecord() }
                .filter { matchesEffects(it, filter) }
                .filter { matchesKeyword(it, filter) }
        }
    }

    /** V11：按效果集合过滤（与记录页 `ResultFilter.matches` 同一口径）。 */
    private fun matchesEffects(record: NotificationRecord, filter: SearchFilter): Boolean {
        if (filter.results.isEmpty()) return true
        val effects = record.effectKinds
        return filter.results.any { it in effects }
    }

    // ==================== 关键词内存匹配 ====================

    private fun matchesKeyword(record: NotificationRecord, filter: SearchFilter): Boolean {
        val keyword = filter.keyword?.trim()?.lowercase() ?: return true
        if (keyword.isEmpty()) return true
        val extrasMap = record.extrasPairs().toMap()
        return filter.fields.any { field ->
            val raw = when (field) {
                MatchField.TITLE -> record.titleSummary
                MatchField.TEXT -> record.textSummary
                else -> ExtrasKeys.keyOf(field)?.let { extrasMap[it] }.orEmpty()
            }
            raw.lowercase().contains(keyword)
        }
    }

    // ==================== 实体 ↔ 模型 ====================

    private fun RecordEntity.toRecord(): NotificationRecord = NotificationRecord(
        recordId = recordId,
        key = key,
        notifId = notifId,
        packageName = packageName,
        appName = appName,
        postTime = postTime,
        result = runCatching { ResultKind.valueOf(result) }.getOrDefault(ResultKind.PASS),
        executedActions = decodeStringList(executedActionsJson).mapNotNull { name ->
            runCatching { ActionType.valueOf(name) }.getOrNull()
        },
        hitFields = decodeStringList(hitFieldsJson).map { FieldRef.fromId(it) }.toSet(),
        ruleId = ruleId,
        ruleName = ruleName,
        blacklistHit = blacklistHit,
        extrasJson = extrasJson,
        titleSummary = titleSummary,
        textSummary = textSummary,
        restoredAt = restoredAt,
        userId = userId,
        channelId = channelId,
        isOngoing = isOngoing,
        flags = flags,
    )

    private fun NotificationRecord.toEntity(): RecordEntity = RecordEntity(
        recordId = recordId,
        key = key,
        notifId = notifId,
        packageName = packageName,
        appName = appName,
        postTime = postTime,
        result = result.name,
        executedActionsJson = NfJson.encodeToString(
            ListSerializer(String.serializer()),
            executedActions.map { it.name },
        ),
        hitFieldsJson = NfJson.encodeToString(
            ListSerializer(String.serializer()),
            hitFields.map { it.id },
        ),
        ruleId = ruleId,
        ruleName = ruleName,
        blacklistHit = blacklistHit,
        extrasJson = extrasJson,
        titleSummary = titleSummary,
        textSummary = textSummary,
        restoredAt = restoredAt,
        userId = userId,
        channelId = channelId,
        isOngoing = isOngoing,
        flags = flags,
    )

    private fun decodeStringList(json: String): List<String> =
        runCatching { NfJson.decodeFromString(ListSerializer(String.serializer()), json) }
            .getOrDefault(emptyList())

    companion object {
        /** 流水滚动淘汰保留条数（PRD 已确认约 1000 条）。 */
        const val KEEP = 1000

        /** 搜索候选上限（<= KEEP，一次全量候选后内存过滤）。 */
        const val SEARCH_LIMIT = 2000

        /** 最近 extras 键聚合的流水扫描条数（架构 §6.5 默认 200）。 */
        const val FROM_LIMIT = 200

        /** 最近 extras 键聚合返回条数（架构 §6.5 top 20）。 */
        const val TOP = 20
    }
}

private val pairListSerializer =
    ListSerializer(PairSerializer(String.serializer(), String.serializer()))

/**
 * extrasJson → 字段对列表（坏 JSON 退化为空列表，绝不抛异常影响主链路）。
 *
 * 文件级扩展：测试面板（UI 层）还原流水素材字段时也要用。
 */
fun NotificationRecord.extrasPairs(): List<Pair<String, String>> =
    runCatching { NfJson.decodeFromString(pairListSerializer, extrasJson) }.getOrDefault(emptyList())
