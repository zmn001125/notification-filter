package com.local.notificationfilter.core.transfer

import com.local.notificationfilter.core.model.NfJson
import com.local.notificationfilter.core.model.Rule
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/**
 * 备份 / 恢复的封套编解码（V11）。**纯 Kotlin，无 Android 依赖，可纯 JVM 单测。**
 *
 * 为什么单独做一个 codec 而不是复用 [RuleTransfer]：
 * 备份要装的不只是规则，还有黑名单与应用设置。但**规则的解析必须复用
 * [RuleTransfer.import]** —— 它已经处理了「逐条过 `RuleJsonMigrator` 迁移、坏条目跳过、
 * 封套校验」这些容易写错的细节。本类的做法是：把 backup 封套里的 `rules` 数组
 * 重新拼成一个 `kind="rules"` 的封套字符串再喂给 `RuleTransfer.import`，
 * 于是「规则怎么解析」永远只有一份实现。
 *
 * 备份封套格式：
 * ```json
 * {
 *   "app": "notification-filter",
 *   "kind": "backup",
 *   "version": 1,
 *   "exportedAt": 1735689600000,
 *   "rules": [ { …规则完整 JSON… } ],
 *   "blacklist": [ "com.tencent.mm", "com.taobao.taobao" ],
 *   "settings": { "masterEnabled": true, "theme": "INDIGO", … }
 * }
 * ```
 *
 * 兼容策略：
 * - `blacklist` / `settings` 缺失 → 按空处理（早期只有规则的备份文件仍可导入）；
 * - `app` / `kind` / `version` 任一不符 → **整体拒绝**并给中文原因（防止误导入
 *   其他 App 的 JSON 或未来版本的不兼容结构）；
 * - 单条规则坏掉 → 跳过并计入 [BackupImport.skippedRuleCount]，不拖垮整体。
 */
object BackupCodec {

    /** 封套内容类型标识。 */
    const val ENVELOPE_KIND = "backup"

    /** 备份封套格式版本；结构变更时递增并在 [import] 中兼容。 */
    const val VERSION = 1

    /**
     * 需要备份的应用设置项。
     *
     * **刻意只收这几个**：它们是「影响过滤行为」与「影响界面观感」的用户选择，
     * 且都能通过 `SettingsStore` 的 setter 原样还原。`manual_perms` /
     * `guide_completed`（权限引导状态）属于**设备相关状态**，换机后照抄会骗过
     * 权限引导页，故不备份。
     */
    @Serializable
    data class SettingsPayload(
        val masterEnabled: Boolean? = null,
        val theme: String? = null,
        val darkMode: String? = null,
        val showOngoing: Boolean? = null,
        val hideFromRecents: Boolean? = null,
    ) {
        /** 有值的设置项中文名（给「确认恢复」预览用）。 */
        fun appliedKeyLabels(): List<String> = buildList {
            if (masterEnabled != null) add("总开关")
            if (theme != null) add("主题")
            if (darkMode != null) add("深浅模式")
            if (showOngoing != null) add("常驻通知")
            if (hideFromRecents != null) add("多任务中隐藏")
        }
    }

    @Serializable
    private data class Envelope(
        val app: String,
        val kind: String,
        val version: Int,
        val exportedAt: Long,
        val rules: List<kotlinx.serialization.json.JsonElement> = emptyList(),
        val blacklist: List<String> = emptyList(),
        val settings: SettingsPayload? = null,
    )

    /** 导出完整备份；[exportedAt] 可注入以便单测。 */
    fun export(
        rules: List<Rule>,
        blacklist: List<String>,
        settings: SettingsPayload,
        exportedAt: Long = System.currentTimeMillis(),
    ): String {
        val envelope = Envelope(
            app = RuleTransfer.ENVELOPE_APP,
            kind = ENVELOPE_KIND,
            version = VERSION,
            exportedAt = exportedAt,
            rules = rules.map { NfJson.encodeToJsonElement(Rule.serializer(), it) },
            blacklist = blacklist,
            settings = settings,
        )
        return NfJson.encodeToString(envelope)
    }

    /**
     * 解析备份内容（本地文件 / WebDAV 下载 / 剪贴板统一入口）。
     *
     * **绝不抛异常**：任何非法输入都落入 [BackupImport.errorMessage]。
     */
    fun import(content: String): BackupImport {
        val root = runCatching { NfJson.parseToJsonElement(content) }.getOrNull()
            ?: return BackupImport.failed("内容不是有效的 JSON")
        val obj = root as? kotlinx.serialization.json.JsonObject
            ?: return BackupImport.failed("内容不是有效的备份文件（应为 JSON 对象）")

        val app = (obj["app"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
            ?: return BackupImport.failed("缺少 app 标识，不是本应用的备份文件")
        if (app != RuleTransfer.ENVELOPE_APP) {
            return BackupImport.failed("文件来自其他来源（app=\"$app\"），不是本应用的备份文件")
        }
        val kind = (obj["kind"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
        if (kind != ENVELOPE_KIND) {
            // 允许直接导入「纯规则」导出文件（老用户手上可能就是这个）——
            // 只是没有黑名单与设置，属于降级可用而不是失败。
            if (kind != RuleTransfer.ENVELOPE_KIND) {
                return BackupImport.failed(
                    "内容类型不是备份（kind=${kind?.let { "\"$it\"" } ?: "缺失"}）",
                )
            }
        }
        val version = (obj["version"] as? JsonPrimitive)?.intOrNull
            ?: return BackupImport.failed("缺少格式版本号，无法识别")
        if (version > VERSION) {
            return BackupImport.failed(
                "备份来自更新的版本（v$version > 当前支持 v$VERSION），请升级应用后再恢复",
            )
        }

        val exportedAt = (obj["exportedAt"] as? JsonPrimitive)?.let { runCatching { it.content.toLong() }.getOrNull() }
            ?: 0L

        // —— 规则：拼成 rules 封套，复用 RuleTransfer 的全部迁移与容错 ——
        val rulesArray = obj["rules"] as? JsonArray
            ?: return BackupImport.failed("备份文件里没有规则列表（rules）")
        val rulesEnvelope = buildJsonObject {
            put("app", RuleTransfer.ENVELOPE_APP)
            put("kind", RuleTransfer.ENVELOPE_KIND)
            put("version", RuleTransfer.ENVELOPE_VERSION)
            put("exportedAt", exportedAt)
            put("rules", rulesArray)
        }.toString()
        val parsed = RuleTransfer.import(rulesEnvelope)
        parsed.errorMessage?.let { return BackupImport.failed("规则解析失败：$it") }

        // —— 黑名单：缺失 / 元素非字符串一律跳过，不失败 ——
        val blacklist = (obj["blacklist"] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.contentOrNull }
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.distinct()
            .orEmpty()

        // —— 设置：缺失 → null（恢复时保持现状，不覆盖） ——
        val settings = obj["settings"]?.let { element ->
            runCatching {
                NfJson.decodeFromJsonElement(SettingsPayload.serializer(), element)
            }.getOrNull()
        }

        return BackupImport(
            rules = parsed.rules,
            skippedRuleCount = parsed.skippedCount,
            blacklist = blacklist,
            settings = settings,
            exportedAt = exportedAt,
            errorMessage = null,
        )
    }

    /**
     * 只读预览：给「确认恢复」弹窗用，**不产生任何副作用**。
     *
     * 实现上直接复用 [import]（解析成本一次，规则数上限也就几十条，可以接受），
     * 避免「预览说 12 条、真导入变成 10 条」这种口径漂移。
     */
    fun preview(content: String): BackupPreview = previewOf(import(content))

    /** 由已解析结果生成预览（ViewModel 已经 import 过时避免二次解析）。 */
    fun previewOf(parsed: BackupImport): BackupPreview = BackupPreview(
        ruleCount = parsed.rules.size,
        skippedRuleCount = parsed.skippedRuleCount,
        blacklistCount = parsed.blacklist.size,
        settingsKeys = parsed.settings?.appliedKeyLabels().orEmpty(),
        exportedAt = parsed.exportedAt,
        errorMessage = parsed.errorMessage,
    )

}
/** 解析结果：封套级失败时 [errorMessage] 非空（其余字段为空）。 */
data class BackupImport(
    val rules: List<Rule>,
    val skippedRuleCount: Int,
    val blacklist: List<String>,
    val settings: BackupCodec.SettingsPayload?,
    val exportedAt: Long,
    val errorMessage: String?,
) {
    val ok: Boolean get() = errorMessage == null

    companion object {
        fun failed(message: String): BackupImport = BackupImport(
            rules = emptyList(),
            skippedRuleCount = 0,
            blacklist = emptyList(),
            settings = null,
            exportedAt = 0L,
            errorMessage = message,
        )
    }
}

/** 恢复前的确认预览（不含具体内容，只有计数与设置项名）。 */
data class BackupPreview(
    val ruleCount: Int,
    val skippedRuleCount: Int,
    val blacklistCount: Int,
    val settingsKeys: List<String>,
    val exportedAt: Long,
    val errorMessage: String?,
) {
    val ok: Boolean get() = errorMessage == null
}
