package com.local.notificationfilter.data.repo

import com.local.notificationfilter.core.model.NfJson
import com.local.notificationfilter.core.model.PendingRepost
import com.local.notificationfilter.core.model.RepostRenderSource
import com.local.notificationfilter.data.db.PendingRepostEntity
import com.local.notificationfilter.data.db.RepostDao

/** 延时重发任务仓库（pending_reposts 表；闹钟由 RepostScheduler 管理，不落库）。 */
class RepostRepository(private val dao: RepostDao) {

    suspend fun insert(task: PendingRepost): Long = dao.insert(task.toEntity())

    suspend fun allPending(): List<PendingRepost> = dao.allPending().map { it.toPendingRepost() }

    suspend fun delete(id: Long) {
        dao.deleteById(id)
    }

    suspend fun deleteByRule(ruleId: Long) {
        dao.deleteByRule(ruleId)
    }

    suspend fun deleteByOriginalKey(key: String) {
        dao.deleteByOriginalKey(key)
    }

    // ==================== 实体 ↔ 模型 ====================

    private fun PendingRepostEntity.toPendingRepost(): PendingRepost = PendingRepost(
        id = id,
        ruleId = ruleId,
        triggerAt = triggerAt,
        originalKey = originalKey,
        packageName = packageName,
        appName = appName,
        repostTitle = repostTitle,
        repostText = repostText,
        replaceConfigJson = replaceConfigJson,
        channelUseAppName = channelUseAppName,
        // V7：两列必须双向映射。库里的旧行由 MIGRATION_1_2 补 'PLAIN'；
        // 万一读到空串/未知值，`PendingRepost.renderSourceEnum` 也会回落 PLAIN（安全侧）。
        renderSource = renderSource,
        aggregationJson = aggregationJson,
    )

    private fun PendingRepost.toEntity(): PendingRepostEntity = PendingRepostEntity(
        id = id,
        ruleId = ruleId,
        triggerAt = triggerAt,
        originalKey = originalKey,
        packageName = packageName,
        appName = appName,
        repostTitle = repostTitle,
        repostText = repostText,
        replaceConfigJson = replaceConfigJson,
        channelUseAppName = channelUseAppName,
        // V7：空串归一成 PLAIN，避免把脏值写进 NOT NULL 的 renderSource 列
        // （`RepostRenderSource.of("")` 回落 PLAIN，但库里不该出现空串）。
        renderSource = RepostRenderSource.of(renderSource).name,
        aggregationJson = aggregationJson,
    )
}
