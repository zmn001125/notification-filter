package com.local.notificationfilter.actions

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.local.notificationfilter.core.aggregate.AggregationRenderer
import com.local.notificationfilter.core.aggregate.Rendered
import com.local.notificationfilter.core.model.ActionType
import com.local.notificationfilter.core.model.AggregationConfig
import com.local.notificationfilter.core.model.AggregationLists
import com.local.notificationfilter.core.model.NfJson
import com.local.notificationfilter.core.model.NotificationSnapshot
import com.local.notificationfilter.core.model.OverlayPayload
import com.local.notificationfilter.core.model.PendingRepost
import com.local.notificationfilter.core.model.PlaceholderExtras
import com.local.notificationfilter.core.model.PlaceholderExtract
import com.local.notificationfilter.core.model.Placeholders
import com.local.notificationfilter.core.model.ReplaceConfig
import com.local.notificationfilter.core.model.RepostRenderSource
import com.local.notificationfilter.core.model.Rule
import com.local.notificationfilter.core.model.RuleActionConfig
import com.local.notificationfilter.core.model.SnoozeCalculator
import com.local.notificationfilter.service.OverlayController
import com.local.notificationfilter.util.NfLog
import kotlinx.serialization.encodeToString

/**
 * 聚合列表上下文来源（V7 需求 8，架构 §3.8 / D15）：
 * 「给定规则 → 该规则各提取器的当前列表」的挂起函数。
 *
 * [ActionExecutor] 只需要这一件事，因此**不直接依赖数据层**：由装配点（`AppContainer`）
 * 注入一个实现，生产实现即 `AggregationRecorder::lists`（T05，签名完全对齐
 * `suspend fun lists(rule: Rule): AggregationLists`）。未装配时用 [NO_AGGREGATION_LISTS]
 * （等价 V6：没有 `{itemsN}` 上下文，渲染结果与 V6 逐字一致）。
 *
 * 用函数类型而不是接口：`actions` 包不必 import 数据层的任何类型，也不引入
 * SAM/`fun interface` 的语言细节；装配处一行 lambda 即可。
 */
typealias AggregationListsSource = suspend (Rule) -> AggregationLists

/** 未装配时的默认实现：恒空上下文（`{itemsN}` 渲染为空串，与 V6 行为一致）。 */
private val NO_AGGREGATION_LISTS: AggregationListsSource = { _: Rule -> AggregationLists.EMPTY }

/**
 * 动作执行器（T05）：按动作组合调度，朗读始终先行。
 *
 * 组合矩阵（架构 §1.3(3)）：
 * - 消除（±朗读）：cancelNotification；V10 起可叠加「静音消除」（先把来源渠道静音再取消）；
 * - 朗读（单独）：TTS，原通知不动；
 * - 推迟（单独）：系统 snoozeNotification，到点以同 key 恢复；
 * - 推迟 × 替换：立即 cancel + 自建延时重发（AlarmManager 精确到点）；
 * - 替换（单独）：cancel + 立即重发。
 *
 * 取消 / 推迟依赖 [NotificationListenerService] 实例，服务连接时 [attach]。
 *
 * V7（架构 §3.8 / §3.9）：
 * - [RuleActionConfig.floatIsConditional] 为 true 时**不再**在这里 showFloat（条件卡片由
 *   `OverlayConditionArbiter` 专管，否则每条命中都会顶掉卡片、重播动画，条件形同虚设）；
 * - 屏幕显示载荷渲染注入 `{itemsN}` 聚合上下文（[buildOverlayPayload]）；
 * - 推迟重发任务额外落 `renderSource` / `aggregationJson`，到点用最新列表重渲染。
 *
 * @param aggregationLists 聚合列表上下文来源（V7；默认恒空，由 T05 装配为
 *   `{ rule -> aggregationRecorder.lists(rule) }`。未装配时本类功能正常，仅 `{itemsN}` 为空）
 * @param channelSilencer 渠道静音器（V10 任务 B）：`actions.silentRemove == true` 时，
 *   消除前先把该通知所属渠道静音（并留底原始设置，供后续恢复）
 */
class ActionExecutor(
    private val ttsManager: TtsManager,
    private val reposter: NotificationReposter,
    private val repostScheduler: RepostScheduler,
    private val channelSilencer: ChannelSilencer,
    private val aggregationLists: AggregationListsSource = NO_AGGREGATION_LISTS,
) {

    @Volatile
    private var listener: NotificationListenerService? = null

    fun attach(service: NotificationListenerService) {
        listener = service
        // V10 任务 B：静音器也要拿到 NLS 才能读源 App 的渠道（否则只能读本包渠道）
        channelSilencer.attach(service)
    }

    fun detach() {
        listener = null
        channelSilencer.detach()
    }

    /** 直接取消一条通知（黑名单路径用，不走规则）。 */
    fun cancelDirect(key: String): Boolean = cancel(key)

    /**
     * 执行一条规则的动作。
     * @return 实际执行（或尝试执行）的动作留痕，含降级/失败标记。
     */
    suspend fun execute(
        rule: Rule,
        snapshot: NotificationSnapshot,
        actions: List<ActionType>,
        source: StatusBarNotification,
    ): List<ActionType> {
        val executed = mutableListOf<ActionType>()
        val now = System.currentTimeMillis()
        val cfg = rule.actions

        // —— 朗读先行（可叠加于任何组合） ——
        if (ActionType.READ_ALOUD in actions) {
            val ok = ttsManager.speak(snapshot.title, snapshot.text)
            executed.add(if (ok) ActionType.READ_ALOUD else ActionType.READ_ALOUD_SKIPPED)
        }

        // —— V7 需求 8：聚合渲染上下文（**仅当规则配了可用聚合时才读库**） ——
        // 未配聚合 → `aggregation == null` → 一个 DB 查询都不发（既有规则零额外开销）。
        val aggregation: AggregationConfig? = cfg.aggregation?.takeIf { it.enabled }
        // 「推迟到点用最新列表重渲染」只在用户显式勾了「推迟」输出时生效（D15），
        // 否则推迟×替换仍走替换模板（V6 行为逐字不变）。
        val snoozeAggregation: AggregationConfig? = aggregation?.takeIf { it.outputSnooze }
        // 只有「真的会有渲染动作」时才读库（性能红线：不为无用规则多一次查询）。
        // 条件悬浮（floatIsConditional）不在此渲染载荷，故不计入。
        val needsAggregationContext: Boolean = aggregation != null &&
            (
                snoozeAggregation != null ||
                    ActionType.DANMAKU in actions ||
                    (ActionType.FLOAT in actions && !cfg.floatIsConditional)
                )
        val aggregationContext: AggregationLists =
            if (needsAggregationContext) listsOf(rule) else AggregationLists.EMPTY

        when {
            // —— 消除（终止性动作） ——
            ActionType.BLOCK in actions -> {
                // V10 任务 B：静音消除。顺序刻意放在 cancel **之前**——
                // 渠道静音是持久覆盖，先做能保证「这条通知被消除」与「渠道已静音」两件事
                // 互不依赖；即便 cancel 失败（服务未连接），静音也已经落地，下次通知不再响。
                // 平台限制见 ChannelSilencer 类注释：只能静音整个渠道，无法只静音这一条。
                if (cfg.silentRemove) {
                    runCatching {
                        channelSilencer.mute(
                            packageName = snapshot.packageName,
                            // 多用户 / 分身：直接用源通知的 UserHandle（公开 API 里没有 userId → UserHandle 的转换）
                            user = source.user,
                            channelId = snapshot.channelId,
                        )
                    }.onFailure {
                        NfLog.e(this, "静音消除失败 pkg=${snapshot.packageName}: ${it.message}")
                    }
                }
                if (cancel(source.key)) executed.add(ActionType.BLOCK)
            }

            // —— 推迟 × 替换：立即消失，到点重发改写版 ——
            ActionType.SNOOZE in actions && ActionType.REPLACE in actions -> {
                val triggerAt = cfg.snooze?.let { SnoozeCalculator.resolveSnooze(it, now) }
                    ?: (now + DEFAULT_SNOOZE_MS)
                if (
                    scheduleRepost(
                        rule = rule,
                        snapshot = snapshot,
                        source = source,
                        triggerAt = triggerAt,
                        cfg = cfg,
                        // V7：只有「聚合 → 输出推迟」才切到 AGGREGATION 来源；否则 PLAIN（V6 原样）
                        aggregation = snoozeAggregation,
                        lists = aggregationContext,
                    )
                ) {
                    executed.add(ActionType.SNOOZE)
                    executed.add(ActionType.REPLACE)
                }
            }

            // —— 推迟（单独）：系统 snooze，到点以相同 key 恢复 ——
            ActionType.SNOOZE in actions -> {
                val triggerAt = cfg.snooze?.let { SnoozeCalculator.resolveSnooze(it, now) }
                    ?: (now + DEFAULT_SNOOZE_MS)
                if (snoozeAggregation != null) {
                    // V7 需求 8：聚合要「到点用最新列表重渲染」，而系统 snoozeNotification
                    // 无法改文案 → 改走自建延时重发（取消 + 到点以渲染结果重发）。
                    // 该分支仅在勾选了「聚合 → 输出推迟」时进入，V6 规则不受影响。
                    if (
                        scheduleRepost(
                            rule = rule,
                            snapshot = snapshot,
                            source = source,
                            triggerAt = triggerAt,
                            cfg = cfg,
                            aggregation = snoozeAggregation,
                            lists = aggregationContext,
                        )
                    ) {
                        executed.add(ActionType.SNOOZE)
                    }
                } else {
                    val durationMs = (triggerAt - now).coerceAtLeast(MIN_SNOOZE_MS)
                    if (snooze(source.key, durationMs)) executed.add(ActionType.SNOOZE)
                }
            }

            // —— 替换（单独） ——
            ActionType.REPLACE in actions -> {
                val replaceCfg = cfg.replace ?: ReplaceConfig()
                if (cancel(source.key)) {
                    val ok = reposter.post(
                        snapshot = snapshot,
                        cfg = replaceCfg,
                        ruleId = rule.id,
                        contentIntent = source.notification.contentIntent,
                        source = source,
                        // 即时提取上下文：替换文案可用 {reN} 从这条通知里现抓内容
                        extract = extractOf(rule, snapshot),
                    )
                    executed.add(if (ok) ActionType.REPLACE else ActionType.REPLACE_FAILED)
                }
            }
        }

        // —— V4 屏幕显示类动作（7-②弹幕 / 7-④悬浮；V5 已删除 7-①焦点通知） ——
        // 与上述动作**不互斥**：消除 / 替换后仍可把内容打到屏幕上（payload 已定型，
        // 不依赖原通知是否还在）。无悬浮窗权限时 OverlayController 返回 false → 记跳过。
        //
        // 以 `actions` 为**唯一事实来源**（D14 教训）：先判 `in actions`，再用
        // `cfg.xxx?.let` 取样式配置——不能只看 `cfg.xxx != null`，否则调用方按 actions
        // 做前置过滤（例如关掉弹幕开关）后这里仍会照样执行。
        if (ActionType.DANMAKU in actions) {
            cfg.danmaku?.let { style ->
                // V5 需求③：弹幕文案模板在此渲染（拿得到 snapshot.extras；留空回落原文案）
                // V7 需求 8：模板可含 {itemsN}，上下文来自该规则的聚合列表
                val ok = OverlayController.enqueueDanmaku(
                    buildOverlayPayload(
                        rule = rule,
                        snapshot = snapshot,
                        titleTemplate = style.titleTemplate,
                        textTemplate = style.textTemplate,
                        lists = aggregationContext,
                    ),
                    style,
                )
                executed.add(if (ok) ActionType.DANMAKU else ActionType.OVERLAY_SKIPPED)
            }
        }

        if (ActionType.FLOAT in actions) {
            cfg.float?.let { style ->
                // V9 语义修正（用户明确要求）：**配了条件也照常显示悬浮**。
                // 条件不再接管卡片生命周期，只负责「提前显示 / 立即清除」：
                // - 悬浮的内容、位置、停留时长、出现/消失动画全部仍按样式执行；
                // - 命中一条新通知就正常刷新悬浮内容（条件不会把它挡住或顶掉）；
                // - 条件命中时才由 OverlayConditionArbiter 调 dismissFloat 提前撤下。
                // V7 曾在此跳过 showFloat（条件是唯一出卡路径），导致「停留时长失效、
                // 只有条件成立才出卡」，与用户预期相反，已废弃。
                // V12：key = 规则 id —— 不同规则的悬浮各占一窗、同时显示互不顶掉；
                // 条件若指定了目标规则（OverlayTrigger.targetRuleId），则由
                // OverlayConditionArbiter 开那条规则的窗，不走本路径。
                val ok = OverlayController.showFloat(
                    rule.id.toString(),
                    buildOverlayPayload(
                        rule = rule,
                        snapshot = snapshot,
                        titleTemplate = style.titleTemplate,
                        textTemplate = style.textTemplate,
                        lists = aggregationContext,
                    ),
                    style,
                )
                executed.add(if (ok) ActionType.FLOAT else ActionType.OVERLAY_SKIPPED)
            }
        }

        return executed
    }

    /**
     * 排一个「到点重发」任务：先按当前上下文定型兜底文案 → 取消原通知 → 落库 + 精确闹钟。
     *
     * V7（§3.8）关键点：**调度时仍然渲染并落库一份文案**（作为到点回落），
     * 但当 [aggregation] 非空时额外落 `renderSource = AGGREGATION` + `aggregationJson`
     * （完整 [AggregationConfig]），到点由 `RepostAlarmReceiver` 用「那一刻的最新列表」重算。
     *
     * @return true = 原通知已取消且任务已排上（调用方据此记 executed）
     */
    private suspend fun scheduleRepost(
        rule: Rule,
        snapshot: NotificationSnapshot,
        source: StatusBarNotification,
        triggerAt: Long,
        cfg: RuleActionConfig,
        aggregation: AggregationConfig?,
        lists: AggregationLists,
    ): Boolean {
        val replaceCfg = cfg.replace ?: ReplaceConfig()
        // 即时提取上下文（{reN}）：调度时按「通知到达那一刻」定型，供替换 / 聚合模板使用。
        val extract = extractOf(rule, snapshot)
        // 兜底文案：配了聚合 → 用聚合模板 + 当前列表；否则 → 替换模板（V6 逐字不变）
        val rendered: Rendered = if (aggregation != null) {
            AggregationRenderer.render(aggregation, snapshot, lists, extract)
        } else {
            Rendered(
                title = reposter.renderTitle(snapshot, replaceCfg, extract),
                text = reposter.renderText(snapshot, replaceCfg, extract),
            )
        }
        if (!cancel(source.key)) return false
        repostScheduler.schedule(
            PendingRepost(
                ruleId = rule.id,
                triggerAt = triggerAt,
                originalKey = source.key,
                packageName = snapshot.packageName,
                appName = snapshot.appName,
                repostTitle = rendered.title,
                repostText = rendered.text,
                replaceConfigJson = NfJson.encodeToString(replaceCfg),
                channelUseAppName = replaceCfg.channelUseAppName,
                // V7：PLAIN（默认值）= V6 行为；AGGREGATION = 到点重渲染（D15）
                renderSource = if (aggregation != null) {
                    RepostRenderSource.AGGREGATION.name
                } else {
                    RepostRenderSource.PLAIN.name
                },
                // 存**完整 AggregationConfig**（含提取器、模板、条目分隔符、输出开关）——
                // 到点重算模板只需要它 + 最新列表，无需再读规则表。
                aggregationJson = aggregation?.let { NfJson.encodeToString(it) },
            ),
        )
        return true
    }

    /**
     * 屏幕显示载荷：取展示用标题/正文（与原通知是否被取消无关）。
     *
     * V5 需求③/④：弹幕/悬浮支持占位符模板——与「替换」动作完全同一套
     * [com.local.notificationfilter.core.model.Placeholders]（四组占位符 +
     * extras 键名大小写敏感）。**模板留空 = 回落原通知标题/正文**（V4 行为）。
     * 渲染时机刻意放在动作执行时而非入队时：此处拿得到 snapshot 的 extras。
     *
     * V7 需求 8：额外接受 [lists]（该规则当前聚合列表），规则的
     * `aggregation` 可用时经 [AggregationRenderer.extras] 组装 `{itemsN}` 上下文。
     *
     * **模板优先级**（与 `OverlayConditionArbiter` 的卡片口径一致，T05 约定）：
     * 1. 动作样式模板（[titleTemplate] / [textTemplate]）；
     * 2. 聚合·组合模板（`aggregation.titleTemplate` / `textTemplate`）；
     * 3. 原通知标题 / 正文（→ 标题）。
     *
     * 未配聚合（`aggregation == null`）时，第 2 级恒为空、extras 恒为
     * [PlaceholderExtras.NONE]、也不做「渲染为空再回落」——**渲染结果与 V6 逐字一致**
     * （V6 允许模板渲染出空串，此处不擅自"修好"，以免改变既有用户看到的内容）。
     *
     * 公开是为了让条件卡片路径（`OverlayConditionArbiter`）复用同一处渲染，避免二次实现。
     *
     * @param lists 聚合列表上下文；默认 [AggregationLists.EMPTY]
     */
    fun buildOverlayPayload(
        rule: Rule,
        snapshot: NotificationSnapshot,
        titleTemplate: String = "",
        textTemplate: String = "",
        lists: AggregationLists = AggregationLists.EMPTY,
    ): OverlayPayload {
        val aggregation = rule.actions.aggregation?.takeIf { it.enabled }
        val extras: PlaceholderExtras = if (aggregation != null) {
            AggregationRenderer.extras(lists, aggregation.itemSeparator)
        } else {
            PlaceholderExtras.NONE
        }
        // 即时提取上下文（{reN}）：弹幕 / 悬浮文案可用正则从这条通知里现抓内容。
        // 规则没配提取器时恒为 NONE（零开销，渲染结果与改前逐字一致）。
        val extract: PlaceholderExtract = extractOf(rule, snapshot)
        // 第 1 级为空才用第 2 级；两者都空 → 走第 3 级（原通知）。
        val useTitleTemplate = titleTemplate.ifBlank { aggregation?.titleTemplate.orEmpty() }
        val useTextTemplate = textTemplate.ifBlank { aggregation?.textTemplate.orEmpty() }

        val fallbackTitle = snapshot.displayTitle
        val fallbackText = snapshot.displayText.ifBlank { fallbackTitle }

        val title = if (useTitleTemplate.isBlank()) {
            fallbackTitle
        } else {
            Placeholders.render(useTitleTemplate, snapshot, extras, extract)
                .orFallback(fallbackTitle, aggregation != null)
        }
        val text = if (useTextTemplate.isBlank()) {
            fallbackText
        } else {
            Placeholders.render(useTextTemplate, snapshot, extras, extract)
                .orFallback(fallbackText, aggregation != null)
        }
        return OverlayPayload(
            ruleId = rule.id,
            packageName = snapshot.packageName,
            appName = snapshot.appName,
            title = title,
            text = text,
            postTime = if (snapshot.postTime > 0) snapshot.postTime else System.currentTimeMillis(),
        )
    }

    /**
     * 聚合路径下「模板非空但渲染结果为空」要回落（典型：模板只写了 `{itemsN}` 而列表还没攒到
     * 条目，否则会出一张空白卡）；非聚合路径保持 V6 原样（可以渲染成空串）。
     */
    private fun String.orFallback(fallback: String, whenBlank: Boolean): String =
        if (whenBlank) ifBlank { fallback } else this

    /**
     * 组装该规则的**即时提取**上下文（`{re1}`..`{re9}`，见 [PlaceholderExtract]）。
     *
     * 与 [listsOf] 的分工：`listsOf` 读库拿「跨通知累积列表」喂 `{itemsN}`；本函数**不读库**，
     * 只用当前这条 [snapshot] 现场跑一次正则喂 `{reN}`。规则没配聚合 / 没配激活提取器 →
     * [PlaceholderExtract.NONE]（零开销，且渲染结果与加 `{reN}` 之前逐字一致）。
     *
     * 任何异常都降级为空上下文（不能因为用户写坏正则就不执行动作）。
     */
    private fun extractOf(rule: Rule, snapshot: NotificationSnapshot): PlaceholderExtract =
        runCatching { PlaceholderExtract.of(snapshot, rule.actions.aggregation) }
            .onFailure { NfLog.w(this, "即时提取失败 rule=${rule.id}: ${it.message}") }
            .getOrDefault(PlaceholderExtract.NONE)

    /**
     * 读该规则的聚合列表；**任何异常都降级为空上下文**（不能因为读库失败就不执行动作）。
     */
    private suspend fun listsOf(rule: Rule): AggregationLists =
        runCatching { aggregationLists(rule) }
            .onFailure { NfLog.w(this, "读取聚合列表失败 rule=${rule.id}: ${it.message}") }
            .getOrDefault(AggregationLists.EMPTY)

    // ==================== 系统调用（全部 try-catch，绝不崩溃服务） ====================

    private fun cancel(key: String): Boolean {
        val service = listener ?: run {
            NfLog.w(this, "监听服务未连接，无法取消通知 key=$key")
            return false
        }
        return runCatching { service.cancelNotification(key) }
            .onFailure { NfLog.e(this, "取消通知失败 key=$key: ${it.message}") }
            .isSuccess
    }

    private fun snooze(key: String, durationMs: Long): Boolean {
        val service = listener ?: run {
            NfLog.w(this, "监听服务未连接，无法推迟通知 key=$key")
            return false
        }
        return runCatching { service.snoozeNotification(key, durationMs) }
            .onFailure { NfLog.e(this, "推迟通知失败 key=$key: ${it.message}") }
            .isSuccess
    }

    private companion object {
        const val DEFAULT_SNOOZE_MS = 30 * 60 * 1000L
        const val MIN_SNOOZE_MS = 60 * 1000L
    }
}
