package com.local.notificationfilter.core.ruleengine

import com.local.notificationfilter.core.model.DeviceState
import com.local.notificationfilter.core.model.FieldRef
import com.local.notificationfilter.core.model.GroupMatchMode
import com.local.notificationfilter.core.model.MatchMode
import com.local.notificationfilter.core.model.MetaFieldKind
import com.local.notificationfilter.core.model.NotificationSnapshot
import com.local.notificationfilter.core.model.RegexConfig
import com.local.notificationfilter.core.model.Rule
import com.local.notificationfilter.core.model.ScopeType
import kotlin.text.RegexOption

/**
 * 规则引擎（纯 Kotlin，无 Android 依赖，JVM 单测全覆盖）。
 *
 * 语义（架构 §3 / PRD 3.4，验收以 RuleEngineTest 为准）：
 * 1. 主流程：**作用范围检查（V1 逻辑不变）→ 按 [MatchMode] 派发 → 汇总
 *    hitFields / reason / plannedActions**；
 * 2. [MatchMode.ALL_CONTENT] 恒真：不看字段不看关键词，是否执行仅由作用范围决定；
 * 3. `hit(k, f)` = 字段 f 的文本包含关键词 k，**不区分大小写**；
 * 4. 「包含全部」= ∀k∈K: ∃f hit(k,f)——**每个关键词各自在任一被选字段出现即可，
 *    不要求同一字段装下全部**（用户已拍板案例：顺丰在标题 + 取件码在正文 → 命中）；
 *    与「包含任一」（∃k∃f）形成 ∃/∀ 对偶，未命中原因可定位到具体缺失词；
 * 5. 「不包含任一」= ∀k: ∀f ¬hit；「不包含全部」= ¬(包含全部) = ∃k: ∀f ¬hit；
 * 6. [MatchMode.REGEX] 逐字段 `containsMatchIn`（架构 §3.4：跨字段拼接无法归因
 *    命中字段且引入歧义匹配，故逐字段；R1 待产品确认）；
 * 7. 防御性判定（UI 已拦截，引擎兜底）：文本模式关键词为空 / A_NOT_B 的 A 或 B
 *    为空 / 正则 pattern 空白 → 一律不命中并给出对应 reason；
 * 8. `enabled = false` 的规则跳过；按 `order` 升序返回**首个**命中。
 * 9. [MatchMode.TEXT_GROUPS]（V12.2）组间 AND：每一组都必须满足；组内按
 *    [GroupMatchMode.ANY]（任一出现）/ [GroupMatchMode.ALL]（全部出现）判定。
 *    每个关键词仍在任一被选字段中查找（可分别出现在不同字段），命中字段为各组并集。
 */
object RuleEngine {

    /**
     * V10.7 耗电修复：正则**编译缓存**。
     *
     * 原来每条通知、每个正则规则都 `Regex(pattern)` 重新编译一次——编译成本远高于匹配本身，
     * 对每秒刷新的音乐/下载通知就是纯浪费。按 `(pattern, ignoreCase)` 缓存，容量有上界防泄漏。
     *
     * V11.4：编译统一走 [RegexCompat]（Android ICU 不允许后行断言里有无上限量词，
     * 失败时按 `(?:…)` 等价改写并标记），缓存里存改写后的结果，避免每次都重试两遍。
     */
    private val regexCache = object : LinkedHashMap<String, RegexCompat.Compiled>(64, 0.75f, true) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<String, RegexCompat.Compiled>?,
        ): Boolean = size > 128
    }

    @Synchronized
    private fun cachedRegex(pattern: String, ignoreCase: Boolean): RegexCompat.Compiled? {
        val key = (if (ignoreCase) "i\u0000" else "s\u0000") + pattern
        regexCache[key]?.let { return it }
        val compiled = RegexCompat.compileOrNull(pattern, ignoreCase) ?: return null
        regexCache[key] = compiled
        return compiled
    }

    /** 按序取首个命中的规则；全部未命中返回 null。入参顺序无关，内部按 order 排序。 */
    fun matchFirst(
        rules: List<Rule>,
        snapshot: NotificationSnapshot,
        deviceState: DeviceState = DeviceState.TEST_MODE,
    ): MatchResult? = matchFirstRule(rules, snapshot, deviceState)?.second

    /** 同 [matchFirst]，但同时返回命中的规则对象（管线执行动作需要）。 */
    fun matchFirstRule(
        rules: List<Rule>,
        snapshot: NotificationSnapshot,
        deviceState: DeviceState = DeviceState.TEST_MODE,
    ): Pair<Rule, MatchResult>? {
        val sorted = rules.sortedWith(compareBy({ it.order }, { it.id }))
        for (rule in sorted) {
            if (!rule.enabled) continue
            val result = matchRule(rule, snapshot, deviceState)
            if (result.hit) return rule to result
        }
        return null
    }

    /**
     * 单条规则匹配（不判断 enabled，供测试与试跑逐条展示）。
     *
     * V3 需求⑦（架构 D5）：新增 [deviceState] 参数，默认 [DeviceState.TEST_MODE]
     * （试跑跳过设备状态评估）；作用范围通过后、模式派发前评估设备状态条件，
     * 不满足则不命中并返回原因。
     */
    fun matchRule(
        rule: Rule,
        snapshot: NotificationSnapshot,
        deviceState: DeviceState = DeviceState.TEST_MODE,
    ): MatchResult {
        val base = MatchResult(hit = false, ruleId = rule.id, ruleName = rule.name)

        // —— 作用范围（V1 逻辑不变）——
        val scopeOk = when (rule.scope.type) {
            ScopeType.GLOBAL -> true
            ScopeType.ONLY -> snapshot.packageName in rule.scope.packages
            ScopeType.EXCLUDE -> snapshot.packageName !in rule.scope.packages
        }
        if (!scopeOk) {
            return base.copy(reason = "作用范围不匹配（${rule.scope.type.label}）")
        }

        // —— 设备状态生效条件（V3 需求⑦，架构 D7）——
        val deviceStateReason = DeviceStateEvaluator.evaluate(rule.deviceState, deviceState)
        if (deviceStateReason != null) {
            return base.copy(reason = "设备状态不满足：$deviceStateReason")
        }

        val refs = rule.fieldRefs()
        val keywords = rule.keywords.map { it.trim() }.filter { it.isNotEmpty() }

        return when (rule.matchMode) {
            // 无条件命中：条件恒成立，是否执行动作仅由作用范围决定
            MatchMode.ALL_CONTENT -> base.copy(
                hit = true,
                hitFields = emptySet(),
                plannedActions = rule.actions.plannedActions(),
                reason = "模式为所有内容，条件恒成立",
            )

            MatchMode.TEXT_ANY -> matchTextAny(rule, base, refs, keywords, snapshot)
            MatchMode.TEXT_ALL -> matchTextAll(rule, base, refs, keywords, snapshot)
            MatchMode.TEXT_NOT_ANY -> matchTextNotAny(rule, base, refs, keywords, snapshot)
            MatchMode.TEXT_NOT_ALL -> matchTextNotAll(rule, base, refs, keywords, snapshot)
            MatchMode.TEXT_A_NOT_B -> matchANotB(rule, base, refs, keywords, snapshot)
            MatchMode.REGEX -> matchRegex(rule, base, refs, snapshot)
            MatchMode.TEXT_GROUPS -> matchGroups(rule, base, refs, snapshot)
        }
    }

    // ==================== 文本子模式 ====================

    /** 包含任一：∃k∈K: ∃f hit(k,f)；hitFields = 命中关键词的字段并集。 */
    private fun matchTextAny(
        rule: Rule,
        base: MatchResult,
        refs: List<FieldRef>,
        keywords: List<String>,
        snapshot: NotificationSnapshot,
    ): MatchResult {
        if (keywords.isEmpty()) return base.copy(reason = "关键词条件为空")
        val hitFields = linkedSetOf<FieldRef>()
        keywords.forEach { k -> hitFields.addAll(matchedFields(k, refs, snapshot)) }
        return if (hitFields.isNotEmpty()) {
            hit(rule, base, hitFields, "命中字段：${label(hitFields)}")
        } else {
            base.copy(reason = "包含任一未满足：${quoteJoin(keywords)}均未出现")
        }
    }

    /**
     * 包含全部：∀k∈K: ∃f hit(k,f)——每个关键词各自在任一被选字段出现即可，
     * 不要求同一字段装下全部（定义理由见类注释）。
     */
    private fun matchTextAll(
        rule: Rule,
        base: MatchResult,
        refs: List<FieldRef>,
        keywords: List<String>,
        snapshot: NotificationSnapshot,
    ): MatchResult {
        if (keywords.isEmpty()) return base.copy(reason = "关键词条件为空")
        val perKeyword = keywords.map { it to matchedFields(it, refs, snapshot) }
        val missing = perKeyword.filter { it.second.isEmpty() }.map { it.first }
        if (missing.isNotEmpty()) {
            return base.copy(reason = "包含全部未满足：${quoteJoin(missing)}未出现")
        }
        val hitFields = linkedSetOf<FieldRef>()
        perKeyword.forEach { (_, fields) -> hitFields.addAll(fields) }
        return hit(rule, base, hitFields, "命中字段：${label(hitFields)}")
    }

    /** 不包含任一：∀k∈K: ∀f ¬hit(k,f)；命中时 hitFields 恒空（无任何词出现）。 */
    private fun matchTextNotAny(
        rule: Rule,
        base: MatchResult,
        refs: List<FieldRef>,
        keywords: List<String>,
        snapshot: NotificationSnapshot,
    ): MatchResult {
        if (keywords.isEmpty()) return base.copy(reason = "关键词条件为空")
        val appeared = keywords.mapNotNull { k ->
            matchedFields(k, refs, snapshot).takeIf { it.isNotEmpty() }?.let { k to it }
        }
        if (appeared.isEmpty()) {
            return hit(rule, base, emptySet(), "不包含任一满足：所有关键词均未出现")
        }
        val desc = appeared.joinToString("；") { (k, fields) ->
            "「$k」出现在 ${label(fields)}"
        }
        return base.copy(reason = "不包含任一未满足：$desc")
    }

    /** 不包含全部：∃k∈K: ∀f ¬hit(k,f)（至少一个词完全没出现）。 */
    private fun matchTextNotAll(
        rule: Rule,
        base: MatchResult,
        refs: List<FieldRef>,
        keywords: List<String>,
        snapshot: NotificationSnapshot,
    ): MatchResult {
        if (keywords.isEmpty()) return base.copy(reason = "关键词条件为空")
        val absent = keywords.filter { matchedFields(it, refs, snapshot).isEmpty() }
        if (absent.isEmpty()) {
            return base.copy(reason = "不包含全部未满足：所有关键词都出现了")
        }
        // 供参考：出现了的词所在字段
        val appearedFields = linkedSetOf<FieldRef>()
        keywords.filter { it !in absent }.forEach { k ->
            appearedFields.addAll(matchedFields(k, refs, snapshot))
        }
        return hit(
            rule,
            base,
            appearedFields,
            "不包含全部满足：${quoteJoin(absent)}未出现",
        )
    }

    /** 包含 A 且不包含 B：(∃a∈A: ∃f hit(a,f)) ∧ (∀b∈B: ∀f ¬hit(b,f))。 */
    private fun matchANotB(
        rule: Rule,
        base: MatchResult,
        refs: List<FieldRef>,
        keywords: List<String>,
        snapshot: NotificationSnapshot,
    ): MatchResult {
        if (keywords.isEmpty()) return base.copy(reason = "A 组关键词为空")
        val excludeKeywords = rule.excludeKeywords.map { it.trim() }.filter { it.isNotEmpty() }
        if (excludeKeywords.isEmpty()) return base.copy(reason = "B 组关键词为空")

        val aFields = linkedSetOf<FieldRef>()
        keywords.forEach { k -> aFields.addAll(matchedFields(k, refs, snapshot)) }
        if (aFields.isEmpty()) {
            return base.copy(reason = "A 组未命中：${quoteJoin(keywords)}均未出现")
        }
        val bAppeared = excludeKeywords.mapNotNull { b ->
            matchedFields(b, refs, snapshot).takeIf { it.isNotEmpty() }?.let { b to it }
        }
        if (bAppeared.isNotEmpty()) {
            val desc = bAppeared.joinToString("；") { (b, fields) ->
                "「$b」出现在 ${label(fields)}"
            }
            return base.copy(reason = "B 组未通过：$desc")
        }
        return hit(rule, base, aFields, "命中字段：${label(aFields)}")
    }

    // ==================== 正则 ====================

    /**
     * 正则：对被选字段**逐字段** `containsMatchIn`，任一字段命中即命中；
     * hitFields = 命中字段集合。空文本字段不参与匹配（避免 `^$` 之类模式误命中空字段）。
     */
    private fun matchRegex(
        rule: Rule,
        base: MatchResult,
        refs: List<FieldRef>,
        snapshot: NotificationSnapshot,
    ): MatchResult {
        val cfg: RegexConfig = rule.regex ?: return base.copy(reason = "未配置正则表达式")
        if (cfg.pattern.isBlank()) return base.copy(reason = "正则表达式为空")
        val compiled = cachedRegex(cfg.pattern, cfg.ignoreCase)
            ?: return base.copy(
                reason = RegexCompat.explain(cfg.pattern)
                    ?: "正则表达式有误：${cfg.pattern}",
            )
        val regex = compiled.regex

        val hitFields = refs.filter { ref ->
            val text = snapshot.fieldText(ref)
            text.isNotEmpty() && regex.containsMatchIn(text)
        }
        // 走了兼容改写要在原因里说清楚：用户看到"能命中"但 `$0` 里多了前缀时不会困惑
        val rewriteNote = if (compiled.rewritten) "（后行断言已按 (?:…) 兼容改写）" else ""
        return if (hitFields.isNotEmpty()) {
            hit(rule, base, hitFields.toSet(), "命中字段：${label(hitFields)}$rewriteNote")
        } else {
            base.copy(reason = "正则未命中任何被选字段：/${cfg.pattern}/$rewriteNote")
        }
    }

    // ==================== 分组匹配（V12.2 TEXT_GROUPS） ====================

    /**
     * 分组匹配：组间 AND（每一组都必须满足），组内按 [GroupMatchMode] 判定——
     * ANY = 组内任一关键词出现；ALL = 组内每个关键词都要出现。
     *
     * 每个关键词仍在「任一被选字段」中查找（[matchedFields]，可分别出现在不同字段，
     * 与 TEXT_ALL 同一口径）；命中字段取各组命中字段的并集（同一字段只记一次）。
     *
     * 防御性判定（UI 已拦截，引擎兜底）：未配置分组 / 分组列表为空 /
     * 某组关键词为空 → 一律不命中，reason 定位到具体组号与缺失词。
     */
    private fun matchGroups(
        rule: Rule,
        base: MatchResult,
        refs: List<FieldRef>,
        snapshot: NotificationSnapshot,
    ): MatchResult {
        val cfg = rule.keywordGroups ?: return base.copy(reason = "未配置分组")
        if (cfg.groups.isEmpty()) return base.copy(reason = "未配置任何分组")
        val hitFields = linkedSetOf<FieldRef>()
        cfg.groups.forEachIndexed { gi, group ->
            val words = group.words.map { it.trim() }.filter { it.isNotEmpty() }
            if (words.isEmpty()) return base.copy(reason = "第 ${gi + 1} 组未填写关键词")
            val groupOk = when (group.mode) {
                GroupMatchMode.ANY -> words.any { w -> matchedFields(w, refs, snapshot).isNotEmpty() }
                GroupMatchMode.ALL -> words.all { w -> matchedFields(w, refs, snapshot).isNotEmpty() }
            }
            if (!groupOk) {
                val modeWord = if (group.mode == GroupMatchMode.ANY) "任一出现" else "全部出现"
                val missing = words.filter { w -> matchedFields(w, refs, snapshot).isEmpty() }
                return base.copy(reason = "第 ${gi + 1} 组未满足（$modeWord）：缺 ${quoteJoin(missing)}")
            }
            words.forEach { w -> hitFields.addAll(matchedFields(w, refs, snapshot)) }
        }
        return hit(rule, base, hitFields, "命中字段：${label(hitFields)}")
    }

    // ==================== 原语 ====================

    /** 关键词 k 实际命中的字段列表（供 hitFields 汇总；不区分大小写）。 */
    private fun matchedFields(
        keyword: String,
        refs: List<FieldRef>,
        snapshot: NotificationSnapshot,
    ): List<FieldRef> = refs.filter { snapshot.fieldText(it).contains(keyword, ignoreCase = true) }

    /** 命中结果包装（统一补 plannedActions）。 */
    private fun hit(
        rule: Rule,
        base: MatchResult,
        hitFields: Set<FieldRef>,
        reason: String,
    ): MatchResult = base.copy(
        hit = true,
        hitFields = hitFields,
        plannedActions = rule.actions.plannedActions(),
        reason = reason,
    )

    private fun label(refs: Collection<FieldRef>): String = refs.joinToString("、") { it.label }

    private fun quoteJoin(words: List<String>): String =
        words.joinToString("、") { "「$it」" }
}
