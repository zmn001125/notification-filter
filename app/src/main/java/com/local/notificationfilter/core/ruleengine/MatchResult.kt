package com.local.notificationfilter.core.ruleengine

import com.local.notificationfilter.core.model.ActionType
import com.local.notificationfilter.core.model.FieldRef

/**
 * 规则匹配结果（纯数据，无 Android 依赖）。
 *
 * @param hit 是否命中
 * @param hitFields 实际参与命中的字段（V2：文本 / 元信息 / extras 统一为 FieldRef）
 * @param plannedActions 命中后将执行的动作（朗读先行）
 * @param reason 人类可读的命中/未命中原因（测试区排错用，定位到具体模式/关键词）
 */
data class MatchResult(
    val hit: Boolean,
    val ruleId: Long? = null,
    val ruleName: String? = null,
    val hitFields: Set<FieldRef> = emptySet(),
    val plannedActions: List<ActionType> = emptyList(),
    val reason: String = "",
) {
    /** 「标题、正文」式命中字段摘要（空集合 → 空串）。 */
    fun hitFieldsLabel(): String = hitFields.joinToString("、") { it.label }
}
