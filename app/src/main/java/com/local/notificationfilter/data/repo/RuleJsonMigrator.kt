package com.local.notificationfilter.data.repo

import com.local.notificationfilter.core.model.MatchField
import com.local.notificationfilter.core.model.MatchMode
import com.local.notificationfilter.core.model.NfJson
import com.local.notificationfilter.core.model.RuleConditionDefaults
import com.local.notificationfilter.core.model.SnoozePresets
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/**
 * 规则 JSON 迁移器：V1 → V2（V2T04，架构 §2.6 / PRD 3.4 决策 D5）、
 * V2 → V3 snooze 时刻列表（架构 §2.5）、V5 焦点通知清除。
 *
 * **纯 Kotlin object，无 Android 依赖、无日志副作用**（迁移日志由调用方
 * RuleRepository 以 `NfLog.w(RuleJsonMigrator, ...)` 输出，tag = NF:RuleJsonMigrator）；
 * 单测直接全覆盖。
 *
 * 检测口径（共享约定 4）：JSON 对象**含 `conditions` 键且不含 `matchMode`** → V1 旧规则。
 * V1 结构（源码事实）：`Rule { id, name, enabled, order,
 * conditions: List<RuleCondition{fields, keyword, mode: INCLUDE|EXCLUDE}>, scope, actions }`。
 *
 * 映射表（架构 §2.6 表 / PRD 3.4 迁移小节）：
 * - 全部条件 INCLUDE → `TEXT_ANY`，keywords = 全部包含词（trim、去空行）；
 * - 全部条件 EXCLUDE → `TEXT_NOT_ANY`，keywords = 全部不包含词；
 * - 混合（INCLUDE + EXCLUDE）→ `TEXT_A_NOT_B`，keywords = A 组，excludeKeywords = B 组
 *   （V1 条件间 OR → V2 A-OR + B-AND，近似不等价，用户已接受）；
 * - 无条件 / 关键词全空 → `TEXT_ANY` + 空 keywords（V1 实际不命中，引擎空关键词兜底）；
 * - fields 取**第一个**条件的 fields（V1 UI 规则级共享），异常/为空回落默认三字段；
 * - `actions.replace.customTemplate` → `customTitleTemplate`（V1 语义即模板作标题）；
 * - id / name / enabled / order / scope / actions 其余键**原样保留**；
 * - 产出 `metaFields=[]`、`extrasFields=[]`、`regex=null`，`excludeKeywords=[]`（非 A_NOT_B 时）。
 *
 * 幂等：对已是 V2 的 JSON（含 matchMode）原样返回，输出字符串与输入完全相同；
 * V1 JSON 迁移一次后即含 matchMode，再次调用不再变化。
 */
object RuleJsonMigrator {

    /** V1 条件 mode 字面量（V1 `MatchMode` 枚举名，kotlinx 按名序列化）。 */
    private const val MODE_INCLUDE = "INCLUDE"
    private const val MODE_EXCLUDE = "EXCLUDE"

    /**
     * 是否为待迁移的 V1 旧规则 JSON。
     * 非法 JSON / 非 JSON 对象 / 不含 `conditions` / 已含 `matchMode` → false（绝不抛异常）。
     */
    fun needsMigration(json: String): Boolean {
        val obj = parseObjectOrNull(json) ?: return false
        return obj.containsKey("conditions") && !obj.containsKey("matchMode")
    }

    /**
     * V5：是否需要写回存储的完整口径——V1 待迁移 **或** 含 `actions.focus` 死数据 **或**
     * 含 V5 前的旧渐变结构（`bgStart`/`bgEnd`/`bgAngle`，见 [migrateOverlayGradientIfNeeded]）。
     * `needsMigration` 只认 V1，V2-V4 时代落库的带 focus / 旧渐变规则不会进入启动写回清单，
     * 导致 DB 里死数据永久残留；RuleRepository.migrateAllIfNeeded 的筛选应改用本函数。
     */
    fun needsWriteback(json: String): Boolean {
        if (needsMigration(json)) return true
        val obj = parseObjectOrNull(json) ?: return false
        val actions = obj["actions"] as? JsonObject ?: return false
        if (actions.containsKey("focus")) return true
        return hasLegacyGradient(actions)
    }

    /**
     * 需要迁移则返回迁移后 JSON 字符串，否则**原样返回入参**（幂等）。
     * 入参非法 JSON 时同样原样返回（调用方 decode 时的报错行为与迁移前一致）。
     *
     * 迁移流水线（逐段叠加，各自幂等）：
     * V1→V2 条件映射 → V2→V3 snooze 时刻列表 → V5 focus 键清除（焦点通知整体下线，
     * 个人侧载拿不到小米授权；用户拍板：显式清除 `actions.focus` 而非留在数据里忽略，
     * 写回由 RuleRepository 的启动一次性迁移统一完成）→ V5 弹幕/悬浮样式升级
     * （渐变结构重构 + 贯穿式速度字段换名，见 [migrateOverlayGradientIfNeeded]）。
     */
    fun migrateJsonIfNeeded(json: String): String {
        val afterV2 = if (!needsMigration(json)) {
            json
        } else {
            val obj = parseObjectOrNull(json) ?: return json
            try {
                NfJson.encodeToString(JsonObject.serializer(), buildMigratedObject(obj))
            } catch (e: Exception) {
                // 构造过程的防御兜底（正常不可达）：返回原 JSON，读路径行为不变
                json
            }
        }
        return migrateOverlayGradientIfNeeded(stripFocusIfNeeded(migrateSnoozeIfNeeded(afterV2)))
    }

    /**
     * V5：清除 `actions.focus` 配置段（焦点通知功能已删除）。
     *
     * `NfJson` 虽配了 `ignoreUnknownKeys = true`（留在 JSON 里也能 decode），但用户明确
     * 要求不留死数据：解析阶段把该键从 `actions` 对象中移除。focus 键缺失 → 原样返回
     * （保证幂等，重复调用输出与输入逐字符一致）。
     */
    fun stripFocusIfNeeded(json: String): String {
        val obj = parseObjectOrNull(json) ?: return json
        val actions = obj["actions"] as? JsonObject ?: return json
        if (!actions.containsKey("focus")) return json
        val newActions = JsonObject(actions.toMap() - "focus")
        return NfJson.encodeToString(
            JsonObject.serializer(),
            JsonObject(obj.toMap() + ("actions" to newActions)),
        )
    }

    /**
     * V2 → V3 SnoozeConfig 时刻列表迁移（幂等 + 回退，架构 §2.5 / D1-D3）：
     * - `customTimeHHmm` 非空 → `times = [customTimeHHmm]`（已含则不重复加入）；
     * - `preset = tonight20` → `times = ["20:00"]`（同上幂等并入）；
     * - 相对时长（preset ∈ {10m,30m,1h} 或 customMinutes 非空）→ **不换算**（D2），旧字段保留；
     * - 已是 V3 形态（times 非空）→ 不动。
     * 无 snooze / 无可迁移字段 → 原样返回。
     */
    fun migrateSnoozeIfNeeded(json: String): String {
        val obj = parseObjectOrNull(json) ?: return json
        val actions = obj["actions"] as? JsonObject ?: return json
        val snooze = actions["snooze"] as? JsonObject ?: return json
        // 已是 V3 形态（times 非空）→ 不动
        val existingTimes = (snooze["times"] as? JsonArray)
        if (existingTimes != null && existingTimes.isNotEmpty()) return json

        val times = mutableListOf<String>()
        snooze["customTimeHHmm"]?.jsonPrimitiveOrNull()?.takeIf { it.isNotBlank() }?.let {
            if (it !in times) times += it
        }
        if (snooze["preset"]?.jsonPrimitiveOrNull() == SnoozePresets.TONIGHT_20 && "20:00" !in times) {
            times += "20:00"
        }
        if (times.isEmpty()) return json // 相对时长或无字段 → 不换算、不动（D2）

        val newSnooze = snooze.toMap().toMutableMap().apply {
            put("times", JsonArray(times.map { JsonPrimitive(it) }))
        }
        val newActions = JsonObject(actions.toMap() + ("snooze" to JsonObject(newSnooze)))
        val out = JsonObject(obj.toMap() + ("actions" to newActions))
        return NfJson.encodeToString(JsonObject.serializer(), out)
    }

    // ==================== V5：弹幕/悬浮样式升级（渐变结构重构 + 贯穿式速度换名） ====================

    /** 旧渐变三键（V5 前 OverlayBoxStyle 的字段名；迁移后从 JSON 中删除）。 */
    private val LEGACY_GRADIENT_KEYS = listOf("bgStart", "bgEnd", "bgAngle")

    /** V5 前贯穿式弹幕的速度字段（dp/秒，与现 px/秒 无法无损换算，迁移时直接弃用）。 */
    private const val LEGACY_DANMAKU_SPEED_KEY = "speedDps"

    /** actions 下弹幕 / 悬浮两个样式段的键名。 */
    private val OVERLAY_STYLE_KEYS = listOf("danmaku", "float")

    /**
     * 检测：actions 的 danmaku/float 段里是否还带 V5 前的旧结构（渐变三键或旧速度键）。
     * 供 [needsWriteback] 与 [migrateOverlayGradientIfNeeded] 共用，保证两个口径一致。
     */
    private fun hasLegacyGradient(actions: JsonObject): Boolean =
        OVERLAY_STYLE_KEYS.any { key ->
            val style = actions[key] as? JsonObject ?: return@any false
            val box = style["box"] as? JsonObject
            LEGACY_GRADIENT_KEYS.any { box?.containsKey(it) == true } ||
                (key == "danmaku" && style.containsKey(LEGACY_DANMAKU_SPEED_KEY))
        }

    /**
     * V5：弹幕 / 悬浮样式升级迁移（幂等）。
     *
     * 背景渐变结构重构（需求⑤）：V5 前 `box = { bgStart, bgEnd?, bgAngle, ... }`（双色线性），
     * V5 改为 `box = { bgSolidColor, bgGradient: { kind, stops, angleDeg, radiusRatio }, ... }`
     * （无/线性/径向 + 多色标）。迁移规则：
     * - 含 `bgEnd`（非 null）→ 双色线性渐变：`bgSolidColor = bgStart`，
     *   `bgGradient = { kind: "LINEAR", stops: [{0, bgStart}, {1, bgEnd}], angleDeg = bgAngle }`；
     * - 不含 `bgEnd`（纯色）→ `bgSolidColor = bgStart`，`bgGradient` 缺省（NONE）；
     * - 迁移后删除 `bgStart`/`bgEnd`/`bgAngle` 三个旧键（不留死数据，同 focus 清除原则）；
     * - 已是 V5 形态（含 `bgGradient` 或 `bgSolidColor`）→ 该 box 不动（幂等）。
     *
     * 贯穿式弹幕速度换名（需求③）：V5 前 `speedDps`（dp/秒）→ V5 `speedPxps`（px/秒）。
     * dp 与 px 随屏幕密度浮动（dp≈3 的屏幕上差 3 倍），**无法无损换算**，故直接删除
     * 旧键、回落默认值 420 px/s——宁可用户重调一次，也不留下一个语义错误的数值。
     *
     * 无 danmaku/float 段、或两段均已是新形态 → 原样返回（输出与输入逐字符一致）。
     */
    fun migrateOverlayGradientIfNeeded(json: String): String {
        val obj = parseObjectOrNull(json) ?: return json
        val actions = obj["actions"] as? JsonObject ?: return json

        var changed = false
        val newActions = actions.toMap().toMutableMap()
        for (key in OVERLAY_STYLE_KEYS) {
            val style = actions[key] as? JsonObject ?: continue
            val migrated = migrateOverlayStyleObject(key, style)
            if (migrated != null) {
                newActions[key] = migrated
                changed = true
            }
        }
        if (!changed) return json
        return NfJson.encodeToString(
            JsonObject.serializer(),
            JsonObject(obj.toMap() + ("actions" to JsonObject(newActions))),
        )
    }

    /**
     * 迁移单个样式段（danmaku / float）；无需迁移返回 null。
     * [styleKey] 用于区分只有弹幕才有的旧速度键。
     */
    private fun migrateOverlayStyleObject(styleKey: String, style: JsonObject): JsonObject? {
        var changed = false
        val out = style.toMap().toMutableMap()

        // —— 旧速度键（仅弹幕）——
        if (styleKey == "danmaku" && out.remove(LEGACY_DANMAKU_SPEED_KEY) != null) {
            changed = true // dp/秒 无法换算成 px/秒，直接弃用回落默认值（见函数注释）
        }

        // —— 渐变结构重构 ——
        val box = out["box"] as? JsonObject
        if (box != null && LEGACY_GRADIENT_KEYS.any { box.containsKey(it) }) {
            val newBox = box.toMap().toMutableMap()
            val bgStart = box["bgStart"]?.jsonPrimitiveOrNull()?.toLongOrNull()
            val bgEnd = (box["bgEnd"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull()
            val bgAngle = box["bgAngle"]?.jsonPrimitiveOrNull()?.toFloatOrNull() ?: 0f
            if (bgStart != null) {
                newBox["bgSolidColor"] = JsonPrimitive(bgStart)
            }
            if (bgEnd != null && bgStart != null) {
                // 双色线性 → 新结构 LINEAR 双色标
                newBox["bgGradient"] = JsonObject(
                    mapOf(
                        "kind" to JsonPrimitive("LINEAR"),
                        "stops" to JsonArray(
                            listOf(
                                JsonObject(
                                    mapOf(
                                        "position" to JsonPrimitive(0f),
                                        "color" to JsonPrimitive(bgStart),
                                    ),
                                ),
                                JsonObject(
                                    mapOf(
                                        "position" to JsonPrimitive(1f),
                                        "color" to JsonPrimitive(bgEnd),
                                    ),
                                ),
                            ),
                        ),
                        "angleDeg" to JsonPrimitive(bgAngle),
                    ),
                )
            }
            LEGACY_GRADIENT_KEYS.forEach { newBox.remove(it) }
            out["box"] = JsonObject(newBox)
            changed = true
        }
        return if (changed) JsonObject(out) else null
    }

    // ==================== 内部实现 ====================

    /** 解析为 JsonObject；任何失败（非对象 / 非法 JSON）返回 null。 */
    private fun parseObjectOrNull(json: String): JsonObject? =
        runCatching { NfJson.parseToJsonElement(json).jsonObject }.getOrNull()

    /**
     * 构造迁移后的 JsonObject：V1 键除 `conditions` 外全部保留（未知键一并保留，
     * decode 时由 ignoreUnknownKeys 忽略），写入 V2 新键。
     */
    private fun buildMigratedObject(obj: JsonObject): JsonObject {
        val conditions = obj["conditions"] as? JsonArray
        val includes = mutableListOf<String>()
        val excludes = mutableListOf<String>()
        var firstFields: List<String>? = null

        conditions?.forEach { element ->
            val condition = element as? JsonObject ?: return@forEach
            // fields 只取第一个条件的（V1 UI 规则级共享，每条一致；异常时下面回落默认）
            if (firstFields == null) {
                firstFields = condition["fields"]?.let { fields ->
                    (fields as? JsonArray)?.mapNotNull { it.jsonPrimitiveOrNull() }
                }
            }
            val keyword = condition["keyword"]?.jsonPrimitiveOrNull()?.trim().orEmpty()
            if (keyword.isEmpty()) return@forEach
            when (condition["mode"]?.jsonPrimitiveOrNull()) {
                MODE_INCLUDE -> includes += keyword
                MODE_EXCLUDE -> excludes += keyword
                // 未知 mode：该条关键词弃用（V1 引擎同样无法识别）
            }
        }

        val matchMode = when {
            includes.isNotEmpty() && excludes.isNotEmpty() -> MatchMode.TEXT_A_NOT_B
            includes.isNotEmpty() -> MatchMode.TEXT_ANY
            excludes.isNotEmpty() -> MatchMode.TEXT_NOT_ANY
            // 无条件 / 关键词全空：V1 实际不命中 → TEXT_ANY + 空 keywords（引擎兜底同行为）
            else -> MatchMode.TEXT_ANY
        }
        val keywords = if (matchMode == MatchMode.TEXT_NOT_ANY) excludes else includes
        val excludeKeywords =
            if (matchMode == MatchMode.TEXT_A_NOT_B) excludes else emptyList()

        val fieldNames = firstFields
            ?.mapNotNull { name -> runCatching { MatchField.valueOf(name) }.getOrNull()?.name }
            ?.takeIf { it.isNotEmpty() }
            ?: RuleConditionDefaults.FIELDS.map { it.name }

        // V1 键除 conditions 外原样保留（含未知键），再写入 V2 新键
        val out = obj.filterKeys { it != "conditions" }.toMutableMap()
        out["matchMode"] = JsonPrimitive(matchMode.name)
        out["fields"] = JsonArray(fieldNames.map { JsonPrimitive(it) })
        out["metaFields"] = JsonArray(emptyList())
        out["extrasFields"] = JsonArray(emptyList())
        out["keywords"] = JsonArray(keywords.map { JsonPrimitive(it) })
        out["excludeKeywords"] = JsonArray(excludeKeywords.map { JsonPrimitive(it) })
        out["regex"] = JsonNull
        // actions 缺失时不补键（decode 用默认值）；存在则做 customTemplate 改名
        if (obj.containsKey("actions")) {
            out["actions"] = renameCustomTemplate(obj["actions"])
        }
        return JsonObject(out)
    }

    /** JsonPrimitive 取字符串内容；元素不是 Primitive（对象/数组/null）时返回 null。 */
    private fun JsonElement.jsonPrimitiveOrNull(): String? =
        (this as? JsonPrimitive)?.contentOrNull

    /**
     * `actions.replace.customTemplate` → `actions.customTitleTemplate` 改名（其余键原样）。
     * replace 缺失 / 非对象 / 不含 customTemplate → actions 原样返回。
     */
    private fun renameCustomTemplate(actions: JsonElement?): JsonElement {
        if (actions !is JsonObject) return actions ?: JsonNull
        val replace = actions["replace"]
        if (replace !is JsonObject || !replace.containsKey("customTemplate")) return actions
        val newReplace = replace.toMap().toMutableMap().apply {
            remove("customTemplate")
            // customTitleTemplate 承接原值（V1 中该键非空；若为 null 也如实转移）
            put("customTitleTemplate", replace["customTemplate"] ?: JsonNull)
        }
        return JsonObject(actions.toMap() + ("replace" to JsonObject(newReplace)))
    }
}
