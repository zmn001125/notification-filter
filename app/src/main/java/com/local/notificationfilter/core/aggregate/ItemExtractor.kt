package com.local.notificationfilter.core.aggregate

import com.local.notificationfilter.core.model.EscapeCodec
import com.local.notificationfilter.core.model.ExtractorConfig
import com.local.notificationfilter.core.model.NotificationSnapshot
import java.util.LinkedHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * 条目提取器（V7 需求 8，架构 §7.2）：从**一条通知**里按正则抽出条目。
 *
 * 处理流程（顺序即语义，勿调换）：
 * 1. [ExtractorConfig.isActive] 为 false（关掉 / 正则空 / `limit <= 0`）→ 直接空列表；
 * 2. 正则编译 **容错**：非法正则（如 `(((`）→ 空列表，**绝不抛异常**
 *    （引擎正在处理真实通知，不能被用户写坏的正则搞崩）；
 * 3. 按 [ExtractorConfig.fieldIds] 取字段文本（`NotificationSnapshot.fieldText(FieldRef)`，
 *    与规则匹配同一取值口径），用 `EscapeCodec.decode(fieldSeparator)` 连接成**一段**输入文本；
 * 4. [Regex.findAll] 取**全部匹配**（一条通知里出现两个取件码要都能抽出来）；
 * 5. 每条匹配按 [ExtractorConfig.captureFormat] 拼装（`$0` 整段、`$1`..`$9` 捕获组，
 *    组不存在 → 空串），再 `trim()`；trim 后为空 → 丢弃该条；
 * 6. `dedupe = true` → 同值只留一条（保序）；最后按 `limit` 截断。
 *
 * ### 非法正则与 ReDoS（安全边界，**缓解不是根治**）
 * - 非法正则：[runCatching] 包住编译，返回空列表；
 * - 灾难性回溯（`(a+)+$` 之类，架构 §8）：本类只做**输入长度上限**
 *   [MAX_INPUT_CHARS]（超出部分丢弃并累计到 [truncatedInputCount]，供调用方日志告警）
 *   与**匹配条数上限** [MAX_MATCHES]（避免长文本 + 全文匹配的 O(n²) 循环）。
 *   **注意**：长度上限无法消除回溯爆炸——一段 8KB 的 `aaaa…ab` 仍可能让 `(a+)+$`
 *   跑很久。彻底根治需要换 RE2 或加超时线程（架构 §8 已列为已知风险），
 *   本类不引入线程、不引入新依赖，故**只做缓解**。保存时的正则合法性校验（UI 侧）
 *   是第一道防线，[isPatternValid] 供其复用。
 */
object ItemExtractor {

    /**
     * 输入文本长度上限（字符）。超出部分被丢弃（并计入 [truncatedInputCount]）。
     *
     * 8KB ≈ 40 条 200 字的推送，远超单条通知的实际正文长度（Android 系统对
     * `EXTRA_TEXT` 本身也有长度限制），因此正常场景不会被截断。
     */
    const val MAX_INPUT_CHARS: Int = 8192

    /**
     * 单次提取最多处理的匹配条数（防长文本 + 极短正则导致的 O(n) 次匹配 + O(n) 次拼装）。
     * 正常情况下 `limit <= 50`（[ExtractorConfig.limit] 上限），200 已远超所需。
     */
    const val MAX_MATCHES: Int = 200

    /** [captureFormat] 留空时的等价格式（`$0` = 整段匹配）。 */
    private const val DEFAULT_CAPTURE_FORMAT: String = "\$0"

    /** 正则编译缓存容量（规则里提取器数量有限，128 足够且不会无限涨）。 */
    private const val REGEX_CACHE_CAPACITY: Int = 128

    /** 输入被截断的累计次数（进程内计数，供调用方打日志；不参与提取逻辑）。 */
    private val truncatedCounter: AtomicLong = AtomicLong(0L)

    /**
     * V11.2 耗电修复：正则**编译缓存**（与 `RuleEngine.cachedRegex` 同款口径）。
     *
     * 原来每次 [extractAll] 都 `Regex(pattern)` 重新编译一次。调用点全是高频路径：
     * - 管线里每条通知、每个启用的提取器一次（`AggregationRecorder`、`{reN}` 渲染）；
     * - 规则编辑页**每敲一个字符**做一次合法性校验（[isPatternValid]）。
     * 正则编译成本远高于匹配本身（音乐/下载类通知每秒刷新时就是纯浪费）。
     * 按 `(pattern, ignoreCase)` 缓存，容量有上界防泄漏；`accessOrder = true` 做 LRU。
     */
    private val regexCache = object : LinkedHashMap<String, Regex>(64, 0.75f, true) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<String, Regex>?,
        ): Boolean = size > REGEX_CACHE_CAPACITY
    }

    /** 自进程启动以来，输入文本被 [MAX_INPUT_CHARS] 截断的次数。 */
    val truncatedInputCount: Long get() = truncatedCounter.get()

    /** 归零 [truncatedInputCount]（单测用；运行时不需要调用）。 */
    fun resetStats() {
        truncatedCounter.set(0L)
    }

    /**
     * 抽取一条通知里的**全部**条目（V7）。
     *
     * @param snapshot 通知快照（字段文本来源）
     * @param cfg 提取器配置（正则 / 字段 / 格式 / 去重 / 上限）
     * @return 已 trim、已去重（`dedupe = true` 时）、已按 `limit` 截断的条目列表；
     *   未匹配 / 正则非法 / 配置未启用 → **空列表**（绝不返回 null，绝不抛异常）
     */
    fun extractAll(snapshot: NotificationSnapshot, cfg: ExtractorConfig): List<String> {
        if (!cfg.isActive) return emptyList()
        val regex = compileOrNull(cfg.pattern, cfg.ignoreCase) ?: return emptyList()
        if (cfg.limit <= 0) return emptyList()

        val input = inputText(snapshot, cfg)
        if (input.isBlank()) return emptyList()

        val collected = ArrayList<String>()
        var visited = 0
        for (match in regex.findAll(input)) {
            val item = applyCaptureFormat(match, cfg.captureFormat).trim()
            if (item.isNotEmpty()) collected.add(item)
            visited++
            if (visited >= MAX_MATCHES) break
        }
        if (collected.isEmpty()) return emptyList()

        val deduped = if (cfg.dedupe) collected.distinct() else collected
        return deduped.take(cfg.limit)
    }

    /**
     * 抽取**第一**个条目（架构 §7.2 的签名，等价 `extractAll(...).firstOrNull()`）。
     *
     * 保留该入口是为了「只关心有没有 / 想要第一条」的调用方（如测试面板预览）；
     * 真正写列表的管线（`AggregationRecorder`）应使用 [extractAll]，否则一条通知里
     * 的第二个取件码会被丢掉。
     *
     * @return 第一个条目；未匹配 / 正则非法 / 结果为空 → null
     */
    fun extract(snapshot: NotificationSnapshot, cfg: ExtractorConfig): String? =
        extractAll(snapshot, cfg).firstOrNull()

    /**
     * 组装用于正则匹配的输入文本：各选中字段的文本用 `EscapeCodec.decode(fieldSeparator)`
     * 连接，整体按 [MAX_INPUT_CHARS] 截断。
     *
     * 空白字段**不参与拼接**（否则空标题会凭空插入一个分隔符，让「标题 + 正文」的跨字段
     * 正则出现多余空行）；因此 `标题="取件码"` + `正文` 为空时，输入只有 `取件码`。
     *
     * @param snapshot 通知快照
     * @param cfg 提取器配置（只用 `fieldIds` / `fieldSeparator`）
     * @return 拼接后的输入文本（可能被截断，可能为空串）
     */
    fun inputText(snapshot: NotificationSnapshot, cfg: ExtractorConfig): String {
        val separator = EscapeCodec.decode(cfg.fieldSeparator)
        val parts = cfg.listFields
            .map { ref -> snapshot.fieldText(ref) }
            .filter { it.isNotBlank() }
        val joined = parts.joinToString(separator)
        if (joined.length <= MAX_INPUT_CHARS) return joined
        truncatedCounter.incrementAndGet()
        return joined.substring(0, MAX_INPUT_CHARS)
    }

    /**
     * 正则是否合法（UI 保存校验用，避免用户存下写坏的正则）。
     *
     * @param pattern 正则原文
     * @param ignoreCase 是否忽略大小写（不影响合法性，仅保持与 [extractAll] 同签名口径）
     * @return true = 可编译
     */
    fun isPatternValid(pattern: String, ignoreCase: Boolean = true): Boolean =
        compileOrNull(pattern, ignoreCase) != null

    // ==================== 内部实现 ====================

    /**
     * 编译正则；空模式 / 非法模式 → null（不抛异常）。
     *
     * 空模式必须显式排除：`Regex("")` 在每个字符位置都产生一个空匹配，
     * 会把一条通知抽出成百上千个空串（虽然后续 trim 后会被丢弃，但白烧 CPU）。
     *
     * 命中 [regexCache] 时零编译成本；缓存未命中才真正编译一次。
     */
    @Synchronized
    private fun compileOrNull(pattern: String, ignoreCase: Boolean): Regex? {
        if (pattern.isBlank()) return null
        val key = (if (ignoreCase) "i\u0000" else "s\u0000") + pattern
        regexCache[key]?.let { return it }
        val options = if (ignoreCase) setOf(RegexOption.IGNORE_CASE) else emptySet()
        val compiled = runCatching { Regex(pattern, options) }.getOrNull() ?: return null
        regexCache[key] = compiled
        return compiled
    }

    /**
     * 按 `captureFormat` 拼装一条匹配结果。
     *
     * 语法（与架构 §1.3 `ExtractorConfig.captureFormat` 一致）：
     * - `$0` = 整段匹配；`$1`..`$9` = 对应捕获组；
     * - 捕获组不存在（正则只有 2 组却写 `$5`）→ **渲染空串**，不抛异常；
     * - `$` 后不是数字 → 原样保留该 `$`（用户写 `价格$100` 不会被吞）；
     * - 格式串为空 / 全空白 → 等价 `$0`。
     *
     * 只支持 `$0`..`$9` 单数字形式（架构明示）；`$10` 会被解析为「`$1` + 字面量 `0`」。
     *
     * @param match 一条正则匹配
     * @param format 捕获组拼装格式
     * @return 拼装结果（**未 trim**，由调用方决定）
     */
    private fun applyCaptureFormat(match: MatchResult, format: String): String {
        val effective = if (format.isBlank()) DEFAULT_CAPTURE_FORMAT else format
        val builder = StringBuilder(effective.length + 16)
        var i = 0
        while (i < effective.length) {
            val c = effective[i]
            val nextIsDigit = i + 1 < effective.length && effective[i + 1].isDigit()
            if (c == '$' && nextIsDigit) {
                val groupIndex = effective[i + 1] - '0'
                // groupValues 越界 / 未参与匹配的组 → 空串（getOrElse 兜底，绝不抛异常）
                builder.append(match.groupValues.getOrElse(groupIndex) { "" })
                i += 2
            } else {
                builder.append(c)
                i++
            }
        }
        return builder.toString()
    }
}
