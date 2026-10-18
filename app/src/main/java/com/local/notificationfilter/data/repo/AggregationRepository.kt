package com.local.notificationfilter.data.repo

import com.local.notificationfilter.core.model.AggregationItem
import com.local.notificationfilter.core.model.AggregationLists
import com.local.notificationfilter.data.db.AggregationDao
import com.local.notificationfilter.data.db.AggregationItemEntity
import com.local.notificationfilter.util.NfLog
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * 聚合暂存列表仓库（V7 需求 8，架构 §3.7）。
 *
 * 职责：
 * - 领域模型 [AggregationItem] ↔ 实体 [AggregationItemEntity] 的映射；
 * - 「写后汰旧」「同值刷新到最前」的事务语义（实现在 `AggregationDao.appendAndTrim`）；
 * - 给运行时（管线/卡片/推迟重发）提供「最新在前」的列表读取与实时流。
 *
 * 排序口径：所有读取都按 `createdAt DESC, id DESC`，即**最新在前**（`index = 0` 最新）。
 * 上限口径：`limit <= 0` 视为非法配置——写入不汰旧、读取返回空列表（宁可少显示，
 * 也不把库撑爆；`ExtractorConfig.isActive` 已保证正常配置里 `limit > 0`）。
 */
class AggregationRepository(private val dao: AggregationDao) {

    /**
     * 追加一条（作用域：单规则单列表 = `item.ruleId` + `item.listIndex`）。
     *
     * 事务内顺序执行「删同值（[dedupe] 时）→ 插入 → 汰旧到 [limit] 条」，
     * 因此并发写入不会产生同值两条，也不会有「刚插入就被别人汰掉」的窗口。
     *
     * @param dedupe true = 相同内容只留一条且刷新到最前（用户口径）；
     *   false = 允许同值重复插入，但唯一索引 `(ruleId, listIndex, value)` 会静默忽略，
     *   即**不会**出现两条完全相同的行（更不会抛异常）。
     * @param limit 写入后保留的最大条数（最新 [limit] 条），`<= 0` 表示不限制。
     */
    suspend fun append(item: AggregationItem, dedupe: Boolean, limit: Int) {
        if (item.value.isEmpty()) {
            // 空条目没有任何展示价值，且会占掉一条上限名额（渲染时表现为一个空行）。
            NfLog.d(this, "跳过空聚合条目: rule=${item.ruleId} list=${item.listIndex}")
            return
        }
        dao.appendAndTrim(item.toEntity(), dedupe, limit)
    }

    /**
     * 取该规则该列表最近 [limit] 条，**最新在前**。
     * [limit] `<= 0` → 空列表（不发起查询）。
     */
    suspend fun listOf(ruleId: Long, listIndex: Int, limit: Int): List<String> {
        if (limit <= 0) return emptyList()
        return dao.valuesOf(ruleId, listIndex, limit)
    }

    /**
     * 读全部列表（多提取器场景），供渲染上下文 [AggregationLists] 用。
     *
     * @param limits `listIndex → 该列表上限`（通常来自 `AggregationConfig.active`）；
     *   未出现在 map 里的 `listIndex`（例如 1..max 之间的空洞）按空列表填充，
     *   保证 `AggregationLists.listOf(N)` 的下标语义与 `{itemsN}` 一一对应。
     */
    suspend fun listsOf(ruleId: Long, limits: Map<Int, Int>): AggregationLists {
        val effective = limits.filterKeys { it >= LIST_INDEX_MIN }
        val maxIndex = effective.keys.maxOrNull() ?: return AggregationLists.EMPTY
        val lists = ArrayList<List<String>>(maxIndex)
        for (index in LIST_INDEX_MIN..maxIndex) {
            val limit = effective[index] ?: 0
            lists += if (limit > 0) dao.valuesOf(ruleId, index, limit) else emptyList()
        }
        return AggregationLists(lists)
    }

    /** 删除单条（规则页「当前汇总」的手动删除；需要行主键）。 */
    suspend fun deleteItem(id: Long) {
        dao.deleteById(id)
    }

    /**
     * 按「列表 + 文本」删除单条（**T07 UI 用这个**）。
     *
     * 为什么需要：T01 定型的 [AggregationItem] 领域模型**不带行主键**，
     * 「当前汇总」区块只拿得到 `(ruleId, listIndex, value)`；而
     * `UNIQUE(ruleId, listIndex, value)` 保证这三元组唯一确定一行，删起来无歧义。
     */
    suspend fun deleteByValue(ruleId: Long, listIndex: Int, value: String) {
        dao.deleteSameValue(ruleId, listIndex, value)
    }

    /**
     * 清空：[listIndex] 为 null → 清空该规则所有列表；否则只清该列表。
     */
    suspend fun clear(ruleId: Long, listIndex: Int? = null) {
        if (listIndex == null) {
            dao.deleteByRule(ruleId)
        } else {
            dao.deleteList(ruleId, listIndex)
        }
    }

    /** 规则页实时观察（「当前汇总」区块）：按列表分组、组内最新在前。 */
    fun observeItems(ruleId: Long): Flow<List<AggregationItem>> =
        dao.observeByRule(ruleId).map { entities -> entities.map { it.toDomain() } }

    /** 规则被删除时级联清理（由 `RuleRepository` 的删除回调触发）。 */
    suspend fun deleteByRule(ruleId: Long) {
        dao.deleteByRule(ruleId)
    }

    /** 该列表当前条数（UI 摘要 / 调试核对用）。 */
    suspend fun countOf(ruleId: Long, listIndex: Int): Int = dao.countList(ruleId, listIndex)

    // ==================== 模型 ↔ 实体 ====================

    private fun AggregationItemEntity.toDomain(): AggregationItem = AggregationItem(
        ruleId = ruleId,
        listIndex = listIndex,
        value = value,
        createdAt = createdAt,
    )

    private fun AggregationItem.toEntity(): AggregationItemEntity = AggregationItemEntity(
        // id 始终交给自增：同值去重时「删旧 + 插新」才能拿到新的 id/createdAt 并排到最前。
        ruleId = ruleId,
        listIndex = listIndex,
        value = value,
        createdAt = createdAt,
    )

    private companion object {
        /** 提取器列表标识下界（`ExtractorConfig.listIndex` 为 1..9）。 */
        const val LIST_INDEX_MIN: Int = 1
    }
}
