package com.local.notificationfilter.actions

import com.local.notificationfilter.core.aggregate.ItemExtractor
import com.local.notificationfilter.core.model.AggregationItem
import com.local.notificationfilter.core.model.AggregationLists
import com.local.notificationfilter.core.model.ExtractorConfig
import com.local.notificationfilter.core.model.NotificationSnapshot
import com.local.notificationfilter.core.model.Rule
import com.local.notificationfilter.data.repo.AggregationRepository
import com.local.notificationfilter.util.NfLog

/**
 * 聚合录制器（V7 需求 8，架构 §3.2 / §3.7）。
 *
 * 职责**只有两件事**：
 * 1. [record]：一条通知命中规则后，对规则里每个启用的提取器做 [ItemExtractor.extractAll]，
 *    把抽出的条目逐条写入 `aggregation_items`（去重 / 汰旧按**该提取器自己的配置**）；
 * 2. [lists]：读该规则各列表，组装成渲染上下文 [AggregationLists]（供卡片 / 推迟重发用）。
 *
 * 设计约束（逐条对应架构要求）：
 * - **调用点唯一**：只在 `NotificationPipeline` 命中规则且 `actions.aggregation.enabled` 时调用
 *   一次（放在动作执行**之前**，于是同一次命中的卡片 / 推迟渲染都能看到刚写入的条目）。
 *   刻意**不**放进 `ActionExecutor`：那里是动作执行器，负责「做什么」而不是「记什么」，
 *   且 `ActionExecutor` 在 T06 由他人改动，记录点放管线可避免重复记录。
 * - **永不让通知处理崩**：提取 / 写库全程 `runCatching`，失败只记日志；
 *   用户写坏的正则、DB 锁定、并发写冲突都只影响这一次录制，不影响过滤主链路。
 * - **零额外成本**：无聚合配置 → 两处都是 `emptyXxx` 立即返回，不做任何 IO
 *   （`aggregation == null` 时连 DB 都不碰）。
 * - **作用域 = 单规则单列表**：`(rule.id, extractor.listIndex)` 是唯一索引键，
 *   提取器之间的条目绝不串味（用户已拍板的口径）。
 *
 * [onItemsChanged] 由 `OverlayConditionArbiter` 在构造时挂上：列表真的变了才回调
 * （`written > 0`），驱动「汇总卡片内容原地更新」（架构 §3.4 红线 4）。
 */
class AggregationRecorder(private val repository: AggregationRepository) {

    /**
     * 条目写入成功后的回调（参数 = 规则 id）。
     *
     * 用可空回调而不是直接依赖 `OverlayConditionArbiter`：两者互相需要
     * （Arbiter 要读列表、Recorder 要通知重绘），用回调把依赖收成单向，装配处无环。
     * 未装配（null）= 无人关心重绘，行为不受影响。
     */
    var onItemsChanged: ((Long) -> Unit)? = null

    /**
     * 命中规则后录制一次（V7 需求 8）。
     *
     * 处理流程：
     * 1. `actions.aggregation` 为 null / 没有启用的提取器 → 立即返回 0（零 IO）；
     * 2. 对每个启用的提取器（按 `listIndex` 升序，取自 `AggregationConfig.active`）：
     *    [ItemExtractor.extractAll] → 逐条 [AggregationRepository.append]；
     * 3. 有任何一条写入成功 → 触发一次 [onItemsChanged]（**只触发一次**，
     *    多提取器同时命中不重复重绘）。
     *
     * @param rule 命中的规则（只用 `id` 与 `actions.aggregation`）
     * @param snapshot 本次命中的通知快照（提取输入）
     * @return 实际尝试写入的条目数（0 = 没有条目 / 全部失败）；去重被 DB 忽略的条目
     *   仍计入本返回值——调用方只用它判断「是否值得重绘」，不需要精确到行级
     */
    suspend fun record(rule: Rule, snapshot: NotificationSnapshot): Int {
        val config = rule.actions.aggregation ?: return 0
        if (!config.enabled) return 0
        val extractors = config.active
        if (extractors.isEmpty()) return 0

        val createdAt = if (snapshot.postTime > 0L) snapshot.postTime else System.currentTimeMillis()
        var written = 0
        for (extractor in extractors) {
            written += recordOne(rule.id, extractor, snapshot, createdAt)
        }
        if (written > 0) {
            // 联调验收用（T06 的「到点重发里两条取件码都在」需要区分"没写进去" vs "渲染丢了"）：
            // 这行出现 = 本次命中确实入列了 N 条
            val listTags = extractors.joinToString(",") { "L" + it.listIndex }
            NfLog.d(this, "聚合录制 rule=${rule.id} 写入 $written 条，列表 $listTags")
            // 回调本身也必须兜住：重绘是 UI 侧行为，不能反向打断录制链路
            runCatching { onItemsChanged?.invoke(rule.id) }
                .onFailure { NfLog.w(this, "聚合重绘回调失败 rule=${rule.id}: ${it.message}") }
        }
        return written
    }

    /**
     * 读该规则各列表（供渲染 / 卡片 / 推迟重发用）。
     *
     * 上限取**各提取器自己的** `limit`（`AggregationConfig.active`），因此
     * `{itemsN}` 的显示条数与用户在编辑器里配的上限严格一致。
     *
     * @return 各列表上下文；无聚合配置 / 无启用提取器 / 读库失败 → [AggregationLists.EMPTY]
     */
    suspend fun lists(rule: Rule): AggregationLists {
        val config = rule.actions.aggregation ?: return AggregationLists.EMPTY
        val limits = config.active.associate { it.listIndex to it.limit }
        if (limits.isEmpty()) return AggregationLists.EMPTY
        return runCatching { repository.listsOf(rule.id, limits) }
            .onFailure { NfLog.w(this, "读取聚合列表失败 rule=${rule.id}: ${it.message}") }
            .getOrDefault(AggregationLists.EMPTY)
    }

    /**
     * 单提取器录制：提取 → 逐条写入 → 返回尝试写入的条数。
     *
     * 提取与写入分别兜底：一次提取失败不影响同规则的其他提取器（用户的多个取件码正则
     * 互不牵连）；一条写入失败不影响同批其他条目（例如唯一索引并发冲突）。
     */
    private suspend fun recordOne(
        ruleId: Long,
        extractor: ExtractorConfig,
        snapshot: NotificationSnapshot,
        createdAt: Long,
    ): Int {
        val items = runCatching { ItemExtractor.extractAll(snapshot, extractor) }
            .onFailure {
                NfLog.w(
                    this,
                    "提取失败（正则可能非法）rule=$ruleId list=${extractor.listIndex}: ${it.message}",
                )
            }
            .getOrDefault(emptyList())
        if (items.isEmpty()) return 0

        var written = 0
        for (value in items) {
            val item = AggregationItem(
                ruleId = ruleId,
                listIndex = extractor.listIndex,
                value = value,
                createdAt = createdAt,
            )
            runCatching { repository.append(item, extractor.dedupe, extractor.limit) }
                .onSuccess { written++ }
                .onFailure {
                    NfLog.w(
                        this,
                        "写入聚合条目失败 rule=$ruleId list=${extractor.listIndex}: ${it.message}",
                    )
                }
        }
        return written
    }
}
