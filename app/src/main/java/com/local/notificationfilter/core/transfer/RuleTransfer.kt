package com.local.notificationfilter.core.transfer

import com.local.notificationfilter.core.model.NfJson
import com.local.notificationfilter.core.model.Rule
import com.local.notificationfilter.data.repo.RuleJsonMigrator
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/**
 * 规则导入导出（V5 需求⑦：分享 / 备份）。
 *
 * **纯 Kotlin，无 Android 依赖**，可纯 JVM 单测；UI 层（剪贴板 / SAF 文件读写）
 * 只负责拿到或交出字符串，统一走这里的封套编解码。
 *
 * 导出封套格式（单条导出同封套，`rules` 只含一条）：
 * ```json
 * {
 *   "app": "notification-filter",
 *   "kind": "rules",
 *   "version": 1,
 *   "exportedAt": 1735689600000,
 *   "rules": [ { …规则完整 JSON… } ]
 * }
 * ```
 *
 * `rules` 元素与本地存储 JSON 同构（`NfJson.encodeToString(Rule)` 的产物，含
 * 导出方的 id / order）；导入时 id 归零、order 由导入方按「追加到当前末尾」重建
 * （见 `RuleRepository.importRules`），导入列表内部相对顺序保持不变。
 *
 * 导入容错：封套校验失败（app / kind / version 不符）整体拒绝并给出中文原因；
 * 封套合法后逐条解析——每条先过 [RuleJsonMigrator.migrateJsonIfNeeded]（兼容
 * V1 旧格式规则），非法条目**跳过**并计入 [ImportResult.skippedCount]，不拖垮整体导入。
 */
object RuleTransfer {

    /** 封套 app 标识（防误导入其他应用的 JSON）。 */
    const val ENVELOPE_APP = "notification-filter"

    /** 封套内容类型标识（为将来导出其他内容预留）。 */
    const val ENVELOPE_KIND = "rules"

    /** 封套格式版本；结构变更时递增并在 import 中做兼容。 */
    const val ENVELOPE_VERSION = 1

    @Serializable
    private data class Envelope(
        val app: String,
        val kind: String,
        val version: Int,
        val exportedAt: Long,
        val rules: List<JsonElement>,
    )

    /** 导出多条规则为封套 JSON 字符串；[exportedAt] 可注入以便单测。 */
    fun export(rules: List<Rule>, exportedAt: Long = System.currentTimeMillis()): String {
        val envelope = Envelope(
            app = ENVELOPE_APP,
            kind = ENVELOPE_KIND,
            version = ENVELOPE_VERSION,
            exportedAt = exportedAt,
            rules = rules.map { NfJson.encodeToJsonElement(Rule.serializer(), it) },
        )
        return NfJson.encodeToString(envelope)
    }

    /** 导出单条规则（同封套，rules 只含一条）。 */
    fun export(rule: Rule, exportedAt: Long = System.currentTimeMillis()): String =
        export(listOf(rule), exportedAt)

    /**
     * 解析导入内容（剪贴板文本 / 文件文本统一入口）。
     *
     * 绝不抛异常：任何非法输入都落入 [ImportResult.errorMessage] 或计入 skipped。
     */
    fun import(content: String): ImportResult {
        val root = runCatching { NfJson.parseToJsonElement(content) }.getOrNull()
            ?: return ImportResult.failed("内容不是有效的 JSON")
        val obj = root as? JsonObject
            ?: return ImportResult.failed("内容不是有效的规则导出文件（应为 JSON 对象）")

        // —— 封套校验：任一不符整体拒绝，给出中文明确原因 ——
        val app = obj["app"].stringOrNull()
            ?: return ImportResult.failed("缺少 app 标识，不是本应用的规则导出文件")
        if (app != ENVELOPE_APP) {
            return ImportResult.failed("文件来自其他来源（app=\"$app\"），不是本应用的规则导出文件")
        }
        val kind = obj["kind"].stringOrNull()
        if (kind != ENVELOPE_KIND) {
            return ImportResult.failed("内容类型不是规则（kind=${kind?.let { "\"$it\"" } ?: "缺失"}）")
        }
        val version = (obj["version"] as? JsonPrimitive)?.intOrNull
            ?: return ImportResult.failed("缺少格式版本号，无法识别")
        if (version != ENVELOPE_VERSION) {
            return ImportResult.failed(
                "不支持的导出版本 v$version（当前支持 v$ENVELOPE_VERSION），请升级应用后再导入",
            )
        }
        val rulesArray = obj["rules"] as? JsonArray
            ?: return ImportResult.failed("文件中没有规则列表（rules）")

        // —— 逐条解析：过旧格式迁移后 decode；坏条目跳过不拖垮整体 ——
        val rules = mutableListOf<Rule>()
        var skipped = 0
        rulesArray.forEach { element ->
            val rule = decodeRuleOrNull(element)
            if (rule != null) rules += rule else skipped++
        }
        return ImportResult(
            rules = rules,
            successCount = rules.size,
            skippedCount = skipped,
            errorMessage = null,
        )
    }

    /**
     * 单条规则元素 → [Rule]：非 JSON 对象 / 迁移或 decode 失败一律返回 null（跳过）。
     * 成功时 id / order 归零，由导入方（Repository）重建。
     */
    private fun decodeRuleOrNull(element: JsonElement): Rule? {
        val obj = element as? JsonObject ?: return null
        return runCatching {
            val migrated = RuleJsonMigrator.migrateJsonIfNeeded(obj.toString())
            NfJson.decodeFromString<Rule>(migrated).copy(id = 0L, order = 0)
        }.getOrNull()
    }

    /** JsonElement 取字符串内容；非字符串 Primitive / 对象 / 数组 / null 一律返回 null。 */
    private fun JsonElement?.stringOrNull(): String? =
        (this as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
}

/** 导入结果：封套级失败时 [errorMessage] 非空（其余字段为 0 / 空）；否则给出成功 / 跳过计数。 */
data class ImportResult(
    val rules: List<Rule>,
    val successCount: Int,
    val skippedCount: Int,
    val errorMessage: String?,
) {
    companion object {
        /** 封套级失败（整体拒绝）：无规则、无计数，只有中文错误原因。 */
        fun failed(message: String): ImportResult =
            ImportResult(
                rules = emptyList(),
                successCount = 0,
                skippedCount = 0,
                errorMessage = message,
            )
    }
}
