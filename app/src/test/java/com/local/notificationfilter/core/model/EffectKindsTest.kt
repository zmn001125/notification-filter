package com.local.notificationfilter.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「多效果」派生口径单测（V11）。
 *
 * 为什么必须测：`effectKinds` 是记录列表多标签、记录页筛选、搜索页筛选、
 * 复制文本四处**共用的唯一口径**。判错的两个方向都有代价——
 *  - 少算 → 用户筛「朗读」筛不出「消除+朗读」的记录（功能等于没做）；
 *  - 多算 → 筛「弹幕」时会混进根本没弹幕的记录（用户会认为筛选有 bug）。
 * 另外失败留痕（朗读跳过 / 替换失败 / 屏幕显示跳过）的归属最容易写错，逐条钉死。
 */
class EffectKindsTest {

    // ==================== ActionType → ResultKind 映射 ====================

    @Test
    fun `正常动作的映射`() {
        assertEquals(ResultKind.BLOCK, ActionType.BLOCK.resultKind())
        assertEquals(ResultKind.SNOOZE, ActionType.SNOOZE.resultKind())
        assertEquals(ResultKind.REPLACE, ActionType.REPLACE.resultKind())
        assertEquals(ResultKind.READ, ActionType.READ_ALOUD.resultKind())
        assertEquals(ResultKind.SHOW, ActionType.DANMAKU.resultKind())
        assertEquals(ResultKind.SHOW, ActionType.FLOAT.resultKind())
    }

    @Test
    fun `失败留痕归入父动作的桶`() {
        // 朗读被跳过 —— 用户筛「朗读」时应当能看到「朗读跳过」的记录
        assertEquals(ResultKind.READ, ActionType.READ_ALOUD_SKIPPED.resultKind())
        assertEquals(ResultKind.REPLACE, ActionType.REPLACE_FAILED.resultKind())
        // 屏幕显示跳过：执行时已丢失原意图（弹幕还是悬浮不知道），两边都算
        assertEquals(ResultKind.SHOW, ActionType.OVERLAY_SKIPPED.resultKind())
    }

    @Test
    fun `失败留痕判定只覆盖三种动作`() {
        assertTrue(ActionType.READ_ALOUD_SKIPPED.isFailureTrace())
        assertTrue(ActionType.REPLACE_FAILED.isFailureTrace())
        assertTrue(ActionType.OVERLAY_SKIPPED.isFailureTrace())
        // 正常动作一律不算失败
        listOf(
            ActionType.BLOCK,
            ActionType.SNOOZE,
            ActionType.REPLACE,
            ActionType.READ_ALOUD,
            ActionType.DANMAKU,
            ActionType.FLOAT,
        ).forEach { assertFalse("$it 不应判为失败留痕", it.isFailureTrace()) }
    }

    // ==================== NotificationRecord.effectKinds ====================

    private fun record(
        actions: List<ActionType>,
        result: ResultKind,
        blacklistHit: Boolean = false,
        restoredAt: Long? = null,
    ) = NotificationRecord(
        recordId = 1L,
        key = "k",
        notifId = 1,
        packageName = "pkg",
        appName = "App",
        postTime = 0L,
        result = result,
        executedActions = actions,
        blacklistHit = blacklistHit,
        restoredAt = restoredAt,
    )

    @Test
    fun `单动作记录只有一个效果`() {
        assertEquals(
            listOf(ResultKind.BLOCK),
            record(listOf(ActionType.BLOCK), ResultKind.BLOCK).effectKinds,
        )
    }

    @Test
    fun `消除加朗读加弹幕得到三个效果且保持执行顺序`() {
        // ActionExecutor 的真实追加顺序：先朗读 → 再消除 → 最后弹幕
        val r = record(
            actions = listOf(ActionType.READ_ALOUD, ActionType.BLOCK, ActionType.DANMAKU),
            result = ResultKind.BLOCK, // classify 的优先级压成 BLOCK
        )
        assertEquals(listOf(ResultKind.READ, ResultKind.BLOCK, ResultKind.SHOW), r.effectKinds)
    }

    @Test
    fun `弹幕加悬浮去重成一个屏幕显示`() {
        val r = record(
            actions = listOf(ActionType.DANMAKU, ActionType.FLOAT),
            result = ResultKind.SHOW,
        )
        // 去重：SHOW 只出现一次（但列表行渲染仍会显示两个 chip，那走 executedActions）
        assertEquals(listOf(ResultKind.SHOW), r.effectKinds)
    }

    @Test
    fun `同桶的失败留痕与正常动作去重`() {
        // 「朗读 + 朗读跳过」在极端情况下都可能出现（多动作分支），只算一个 READ
        val r = record(
            actions = listOf(ActionType.READ_ALOUD, ActionType.READ_ALOUD_SKIPPED),
            result = ResultKind.READ,
        )
        assertEquals(listOf(ResultKind.READ), r.effectKinds)
    }

    @Test
    fun `无动作的记录回落主结论`() {
        // 未命中规则 / 仅放行的旧数据：executedActions 为空，必须仍能被「放行」筛出
        assertEquals(listOf(ResultKind.PASS), record(emptyList(), ResultKind.PASS).effectKinds)
        // 黑名单理论上总有 BLOCK 动作，但即使缺失也不能返回空列表
        assertEquals(listOf(ResultKind.BLOCK), record(emptyList(), ResultKind.BLOCK).effectKinds)
    }

    @Test
    fun `筛选语义 —— 选朗读能命中消除加朗读的记录`() {
        val r = record(listOf(ActionType.BLOCK, ActionType.READ_ALOUD), ResultKind.BLOCK)
        // 这正是修复前的 bug：record.result 只有 BLOCK，筛 READ 筛不到
        assertTrue(ResultKind.READ in r.effectKinds)
        assertTrue(ResultKind.BLOCK in r.effectKinds)
        // 反例：没配弹幕就不该被「屏幕显示」筛出来
        assertFalse(ResultKind.SHOW in r.effectKinds)
    }

    @Test
    fun `已恢复不改变效果集合`() {
        val r = record(listOf(ActionType.SNOOZE), ResultKind.SNOOZE, restoredAt = 123L)
        assertEquals(listOf(ResultKind.SNOOZE), r.effectKinds)
        // 已恢复只影响显示（tagLabel），不影响筛选
        assertEquals("已恢复", r.tagLabel)
    }
}
