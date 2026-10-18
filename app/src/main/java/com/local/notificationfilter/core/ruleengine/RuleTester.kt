package com.local.notificationfilter.core.ruleengine

import com.local.notificationfilter.core.model.ExtrasKeys
import com.local.notificationfilter.core.model.MatchField
import com.local.notificationfilter.core.model.MetaFieldKind
import com.local.notificationfilter.core.model.NotificationSnapshot
import com.local.notificationfilter.core.model.Rule
import java.util.UUID

/** 批量试跑的单条结果（素材 + 命中结论）。 */
data class TestOutcome(
    val snapshot: NotificationSnapshot,
    /** null 表示未命中任何规则。 */
    val result: MatchResult?,
) {
    val hit: Boolean get() = result?.hit == true
}

/**
 * 规则试跑器：P6 页内测试与 P10 批量测试共用（架构 §4.3）。
 * 输入已按 order 排序的规则全集，逐条素材取首个命中。
 */
object RuleTester {

    fun run(rules: List<Rule>, snapshots: List<NotificationSnapshot>): List<TestOutcome> {
        val sorted = rules.sortedWith(compareBy({ it.order }, { it.id }))
        return snapshots.map { snapshot -> TestOutcome(snapshot, RuleEngine.matchFirst(sorted, snapshot)) }
    }

    /** 命中 / 未命中计数（结果区顶部摘要）。 */
    fun summarize(outcomes: List<TestOutcome>): Pair<Int, Int> {
        val hit = outcomes.count { it.hit }
        return hit to outcomes.size
    }
}

/**
 * 手填测试素材的构造工具（P6/P10 手填来源；架构 §3.7）。
 *
 * V2 签名扩展（T05/T06 的 UI 分层落地，本波先提供纯 Kotlin 能力）：
 * - [fields]：V1 六文本字段（保留，TITLE/TEXT 有独立列，其余同步进 extras dump）；
 * - [extras]：extras 键值手填（键名大小写敏感，与规则 extras 字段匹配口径一致）；
 * - [meta]：元信息手填——TAG / NOTIF_ID / CHANNEL_ID / USER_ID / IS_ONGOING
 *   （IS_ONGOING 按 "true" 忽略大小写解析；KEY 由系统生成，手填不生效）。
 */
object ManualSnapshotFactory {

    fun create(
        packageName: String,
        appName: String,
        fields: Map<MatchField, String> = emptyMap(),
        extras: Map<String, String> = emptyMap(),
        meta: Map<MetaFieldKind, String> = emptyMap(),
        postTime: Long = System.currentTimeMillis(),
    ): NotificationSnapshot {
        val extrasMap = LinkedHashMap<String, String>()
        // 文本字段中除 TITLE/TEXT 外的四字段按 V1 行为同步进 extras dump
        fields.entries
            .filter { it.value.isNotBlank() }
            .sortedBy { it.key.ordinal }
            .forEach { (field, value) ->
                // keyOf 对 TITLE/TEXT 返回 null（二者有独立列，不走 extras），直接跳过
                ExtrasKeys.keyOf(field)?.let { key -> extrasMap[key] = value }
            }
        // 手填 extras 键值（显式录入优先，与同名字段派生键去重）
        extras.entries
            .filter { it.key.isNotBlank() && it.value.isNotBlank() }
            .sortedBy { it.key }
            .forEach { (key, value) -> extrasMap[key] = value }

        return NotificationSnapshot(
            key = "manual:${UUID.randomUUID()}",
            notifId = meta[MetaFieldKind.NOTIF_ID]?.trim()?.toIntOrNull() ?: 0,
            packageName = packageName.ifBlank { "manual.input" },
            appName = appName.ifBlank { "手填通知" },
            userId = meta[MetaFieldKind.USER_ID]?.trim()?.toIntOrNull() ?: 0,
            channelId = meta[MetaFieldKind.CHANNEL_ID]?.trim()?.takeIf { it.isNotBlank() },
            postTime = postTime,
            isOngoing = meta[MetaFieldKind.IS_ONGOING]?.trim()?.equals("true", ignoreCase = true) ?: false,
            flags = 0,
            title = fields[MatchField.TITLE]?.takeIf { it.isNotBlank() },
            text = fields[MatchField.TEXT]?.takeIf { it.isNotBlank() },
            textLines = fields[MatchField.TEXTLINES]?.takeIf { it.isNotBlank() }
                ?.split("\n")?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty(),
            subText = fields[MatchField.SUBTEXT]?.takeIf { it.isNotBlank() },
            summaryText = fields[MatchField.SUMMARYTEXT]?.takeIf { it.isNotBlank() },
            people = fields[MatchField.PEOPLE]?.takeIf { it.isNotBlank() }
                ?.split("\n")?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty(),
            extras = extrasMap.map { (k, v) -> k to v },
            tag = meta[MetaFieldKind.TAG]?.trim()?.takeIf { it.isNotBlank() },
        )
    }
}
