package com.local.notificationfilter.core.aggregate

/**
 * 暂存列表的「插入 + 去重 + 上限汰旧」纯函数（V7 需求 8，架构 §7.3）。
 *
 * 语义（唯一实现，DB 层的 `UNIQUE(ruleId, listIndex, value)` + `LIMIT` 删除是同一语义的
 * 持久化实现，见架构 §3.7）：
 * - **最新的在前**（`[0]` 位是最新条目，卡片正文按此顺序展示）；
 * - `dedupe = true`：同值条目只留一条，且**保留最新位置**（先删掉旧的同值，再插到最前）；
 * - 超 [limit] 条时**汰尾**（保留最新的 `limit` 条）；
 * - 条目一律 `trim()` 后存储 / 比较（用户可能抽到带空格的匹配）；
 * - `incoming` 为 null / 空白 → 原列表**原样返回**（不做任何 reorder，避免无意义重排）。
 *
 * 纯函数：不落库、不读时钟、不依赖 Android，可在 JVM 单测里全量覆盖。
 */
object ItemListMerger {

    /** 单列表条数上限的下界（编辑器范围 1..50，架构 §1.3 `ExtractorConfig.limit`）。 */
    const val MIN_LIMIT: Int = 1

    /** 单列表条数上限的上界（越界手填值在此被夹住）。 */
    const val MAX_LIMIT: Int = 50

    /**
     * 把一批新条目合并进已有列表（V7）。
     *
     * [incoming] 的**顺序即抽取顺序**（一条通知里自然阅读顺序），合并后整体排在已有条目之前，
     * 因此结果形如 `incoming[0], incoming[1], …, existing[0], existing[1], …`
     * （`incoming` 自身在 `dedupe = true` 时内部也去重，保序）。
     *
     * @param existing 已有列表（最新的在前）
     * @param incoming 本次新抽出的条目（可为空集；空 / 全空白 → 原列表原样返回）
     * @param limit 条数上限（`<= 0` → 空列表；`> 50` → 夹到 50）
     * @param dedupe 是否按值去重（true = 删旧留新）
     * @return 合并后的新列表（新列表实例，不修改入参）
     */
    fun merge(
        existing: List<String>,
        incoming: List<String>,
        limit: Int,
        dedupe: Boolean,
    ): List<String> {
        // 上限非法：调用方本应已过滤（ExtractorConfig.isActive），这里是兜底
        if (limit <= 0) return emptyList()
        val cap = limit.coerceIn(MIN_LIMIT, MAX_LIMIT)

        val fresh = incoming.map { it.trim() }.filter { it.isNotEmpty() }
        if (fresh.isEmpty()) return existing

        val pooled = existing.map { it.trim() }.filter { it.isNotEmpty() }
        val result = ArrayList<String>(minOf(pooled.size + fresh.size, cap))

        if (dedupe) {
            // LinkedHashSet = 保序去重；新条目在前，同值只在首次出现处保留
            val seen = LinkedHashSet<String>(fresh.size + pooled.size)
            seen.addAll(fresh)
            result.addAll(seen)
            for (item in pooled) {
                if (item !in seen) result.add(item)
            }
        } else {
            result.addAll(fresh)
            result.addAll(pooled)
        }
        return result.take(cap)
    }

    /**
     * 单条入口（架构 §7.3 的签名）：等价 `merge(existing, listOf(incoming), limit, dedupe)`。
     *
     * 单条时更直观：命中的那一条永远排在最前，重复项被移到最前而非新增。
     *
     * @param incoming 本次抽到的一条（null / 空白 → 原列表原样返回）
     * @return 合并后的新列表
     */
    fun merge(
        existing: List<String>,
        incoming: String?,
        limit: Int,
        dedupe: Boolean,
    ): List<String> = merge(existing, listOfNotNull(incoming), limit, dedupe)

    /**
     * 列表是否已满（UI「当前汇总」区块提示用）。
     *
     * @param list 当前列表
     * @param limit 上限（同样按 1..50 夹取；非法上限视为已满，避免提示失真）
     */
    fun isFull(list: List<String>, limit: Int): Boolean {
        if (limit <= 0) return true
        return list.size >= limit.coerceIn(MIN_LIMIT, MAX_LIMIT)
    }
}
