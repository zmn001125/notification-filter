package com.local.notificationfilter.core.ruleengine

import com.local.notificationfilter.core.model.DeviceState
import com.local.notificationfilter.core.model.DeviceStateCondition
import com.local.notificationfilter.core.model.OverlayTrigger
import com.local.notificationfilter.core.model.OverlayTriggerConfig
import com.local.notificationfilter.core.model.OverlayTriggerKind
import com.local.notificationfilter.core.model.SnoozeCalculator
import com.local.notificationfilter.core.model.TriggerEdge
import com.local.notificationfilter.core.model.TriggerEffect
import java.time.Instant
import java.time.ZoneId

/**
 * 触发事件类型（core 层最小模型）。
 *
 * 它是 [TriggerContext.lastEvent] 的取值，与 service 层的通知事件一一对应
 * （T04 的 `NotificationEventBus` 负责把系统回调翻译成这三种）：
 * - [POSTED]：通知出现（新发布 / 更新）；
 * - [REMOVED]：通知消失（用户划掉 / App 取消）；
 * - [MATCHED]：通知被某条规则命中（带 `ruleId`），用于 `RULE_NOTIFICATION`。
 *
 * 之所以在 core 里定义而不是直接引用 service 层的事件类：core 层必须保持
 * **纯 Kotlin、可 JVM 单测、不依赖 Android**（架构 §7 的要求），而 service 层类带
 * `StatusBarNotification`。映射是一行 if，成本极低。
 */
enum class TriggerEventType {
    /** 通知出现。 */
    POSTED,

    /** 通知消失。 */
    REMOVED,

    /** 通知被规则命中（必须带 `ruleId`）。 */
    MATCHED,
}

/**
 * 一次触发事件（求值输入的一小部分）。
 *
 * @param type 事件类型
 * @param packageName 事件相关包名（POSTED / REMOVED 必填；MATCHED 可留空）
 * @param ruleId 命中的规则 id（仅 [TriggerEventType.MATCHED] 有意义）
 */
data class TriggerEvent(
    val type: TriggerEventType = TriggerEventType.POSTED,
    val packageName: String = "",
    val ruleId: Long? = null,
) {
    companion object {
        /** 通知出现事件。 */
        fun posted(packageName: String): TriggerEvent =
            TriggerEvent(TriggerEventType.POSTED, packageName, null)

        /** 通知消失事件。 */
        fun removed(packageName: String): TriggerEvent =
            TriggerEvent(TriggerEventType.REMOVED, packageName, null)

        /** 规则命中事件。 */
        fun matched(ruleId: Long, packageName: String = ""): TriggerEvent =
            TriggerEvent(TriggerEventType.MATCHED, packageName, ruleId)

        /**
         * 按条件边沿构造事件（管线便捷入口：同一个 `edge` 配置既能造 OCCUR 也能造 REMOVE）。
         *
         * @param edge 边沿（[TriggerEdge.OCCUR] → [TriggerEventType.POSTED]，
         *   [TriggerEdge.REMOVE] → [TriggerEventType.REMOVED]）
         */
        fun ofEdge(edge: TriggerEdge, packageName: String, ruleId: Long? = null): TriggerEvent =
            when (edge) {
                TriggerEdge.OCCUR ->
                    if (ruleId != null) matched(ruleId, packageName) else posted(packageName)

                TriggerEdge.REMOVE -> TriggerEvent(TriggerEventType.REMOVED, packageName, ruleId)
            }
    }
}

/**
 * 条件求值上下文（架构 §7.5）。
 *
 * 求值侧只读该结构，不读系统、不读时钟（[now] 由调用方注入），因此可在 JVM 单测里
 * 精确构造每一种组合（含"不可判定"）。
 *
 * @param deviceState 运行时设备状态（屏幕 / 充电；`now` 留空时用 [now] 补齐）
 * @param now 当前时刻（epoch 毫秒；`<= 0` = 未知 → 时间类条件不可判定）
 * @param lastEvent 本次事件（通知类条件用；null = 没有事件上下文）
 * @param foregroundPackage 当前前台包名（无障碍服务上报；null = 未知 / 未授权）
 * @param firedClockKeys 今日已触发过的 `CLOCK_AT` 分钟键集合（[OverlayTriggerEvaluator.clockKey]），
 *   用于「每天只触发一次」，避免 22:00 这一分钟内每次 tick 都重复触发
 */
data class TriggerContext(
    val deviceState: DeviceState = DeviceState.TEST_MODE,
    val now: Long = 0L,
    val lastEvent: TriggerEvent? = null,
    val foregroundPackage: String? = null,
    val firedClockKeys: Set<Int> = emptySet(),
)

/**
 * 一次条件集合求值的结论。
 *
 * **HIDE 优先**（架构 D6）：同时命中 SHOW 与 HIDE 时 [shouldShow] 为 false、
 * [shouldHide] 为 true——安全侧：宁可少留一张卡，也不留一张按用户配置本该消失的卡。
 * 调用方只需 `if (shouldHide) … else if (shouldShow) …`，或直接用 [effective]。
 *
 * @param shouldShow 应当显示卡片
 * @param shouldHide 应当隐藏卡片（与 [shouldShow] 互斥，优先级更高）
 */
data class TriggerDecision(
    val shouldShow: Boolean = false,
    val shouldHide: Boolean = false,
) {
    /** 折成单一效果（HIDE 优先）；两者皆假 = 无动作（null）。 */
    val effective: TriggerEffect?
        get() = when {
            shouldHide -> TriggerEffect.HIDE
            shouldShow -> TriggerEffect.SHOW
            else -> null
        }

    /** 是否无任何动作（既不显示也不隐藏）。 */
    val isNoop: Boolean get() = !shouldShow && !shouldHide

    companion object {
        /** 无动作。 */
        val NONE: TriggerDecision = TriggerDecision()
    }
}

/**
 * 六类行为触发条件的求值器（V7 需求 7，架构 §7.5）。**纯函数**：无状态、无 Android 依赖。
 *
 * 求值规则（[evaluate] 返回三值）：
 * - `true`：条件成立；
 * - `false`：条件不成立（参数非法 / 事件不匹配 / 无上下文也算不成立）；
 * - `null`：**不可判定**，不投票（例如试跑模式 `screenOn == null`、"该分钟已触发过"）。
 *
 * 参数没配齐（`OverlayTrigger.isEffective == false`）→ `null`，唯一例外是
 * `CLOCK_AT` 的格式非法（"25:00" / "abc"）→ `false`（用户写错＝这条条件不成立，
 * 而不是"永远悬空"）。这与 `isEffective` 的口径一致，二者不会互相矛盾地触发动作。
 *
 * 五类 kind 的判定：
 * - [OverlayTriggerKind.DEVICE_STATE]：**复用** [DeviceStateEvaluator]（屏幕 / 充电 /
 *   时间段 / 星期，内部 AND、各子项多选 OR、支持跨午夜），不新写一套判定；
 * - [OverlayTriggerKind.CLOCK_AT]：`HH:mm` 与"当前时刻的时分"相同即为真（分钟级粒度）；
 * - [OverlayTriggerKind.APP_NOTIFICATION]：事件的包名 ∈ `packages` **且**边沿匹配；
 * - [OverlayTriggerKind.RULE_NOTIFICATION]：事件的 `ruleId` ∈ `ruleIds` **且**边沿匹配；
 * - [OverlayTriggerKind.FOREGROUND_APP]：前台包名 ∈ `packages`。
 *
 * 多条条件之间是 **OR**（任一成立即触发）；某条 `hideWhenFalse = true` 时，
 * 它"不成立"也会动作（施加 [TriggerEffect] 的反面），用于"离开时间段就消失"。
 */
object OverlayTriggerEvaluator {

    /** 一天的分钟数（`CLOCK_AT` 分钟键基数）。 */
    private const val MINUTES_PER_DAY: Int = 24 * 60

    /**
     * 评估**一条**条件（三值）。
     *
     * @param trigger 条件
     * @param ctx 求值上下文
     * @return true / false / null（不可判定）
     */
    fun evaluate(trigger: OverlayTrigger, ctx: TriggerContext): Boolean? {
        if (!trigger.isEffective) {
            // CLOCK_AT 写法非法 = 用户配错 = 不成立；其余"没配齐" = 不参与（不投票）
            return if (trigger.kindEnum == OverlayTriggerKind.CLOCK_AT) false else null
        }
        return when (trigger.kindEnum) {
            OverlayTriggerKind.DEVICE_STATE -> evaluateDeviceState(trigger.device, ctx)
            OverlayTriggerKind.CLOCK_AT -> evaluateClockAt(trigger.clockAt, ctx)
            OverlayTriggerKind.APP_NOTIFICATION -> evaluateAppNotification(trigger, ctx)
            OverlayTriggerKind.RULE_NOTIFICATION -> evaluateRuleNotification(trigger, ctx)
            OverlayTriggerKind.FOREGROUND_APP -> evaluateForegroundApp(trigger, ctx)
        }
    }

    /**
     * 求值一组条件，给出「显示 / 隐藏」结论（**HIDE 优先**）。
     *
     * @param triggers 条件集合（未配齐的会被剔除，等价 `OverlayTriggerConfig.effective`）
     * @param ctx 求值上下文
     * @return 结论；无条件 / 全部不可判定 → [TriggerDecision.NONE]
     */
    fun decide(triggers: List<OverlayTrigger>, ctx: TriggerContext): TriggerDecision {
        var show = false
        var hide = false
        for (trigger in triggers) {
            if (!trigger.isEffective) continue
            val truth = evaluate(trigger, ctx) ?: continue // 不可判定 = 不投票
            val action = actionOf(trigger, truth) ?: continue
            when (action) {
                TriggerEffect.SHOW -> show = true
                TriggerEffect.HIDE -> hide = true
            }
        }
        return TriggerDecision(shouldShow = show && !hide, shouldHide = hide)
    }

    /**
     * 求值一组条件（[OverlayTriggerConfig] 入口）。
     *
     * @param config 条件集合（null 语义由调用方处理：null / 空 = 沿用 V6「命中即显示」）
     * @param ctx 求值上下文
     */
    fun decide(config: OverlayTriggerConfig, ctx: TriggerContext): TriggerDecision =
        decide(config.effective, ctx)

    /**
     * 求值一组条件，折成单一效果（架构 §7.5 的签名）。
     *
     * @return [TriggerEffect.HIDE] / [TriggerEffect.SHOW] / null（无动作或不可判定）
     */
    fun resolve(config: OverlayTriggerConfig, ctx: TriggerContext): TriggerEffect? =
        decide(config, ctx).effective

    /**
     * 一条条件在"成立 / 不成立"时应施加的效果。
     *
     * - 成立 → 配置的 [OverlayTrigger.effect]；
     * - 不成立 → `hideWhenFalse` 为 true 时取反，否则无动作（null）。
     */
    private fun actionOf(trigger: OverlayTrigger, truth: Boolean): TriggerEffect? =
        if (truth) trigger.effect
        else if (trigger.hideWhenFalse) opposite(trigger.effect)
        else null

    /** 效果取反。 */
    private fun opposite(effect: TriggerEffect): TriggerEffect = when (effect) {
        TriggerEffect.SHOW -> TriggerEffect.HIDE
        TriggerEffect.HIDE -> TriggerEffect.SHOW
    }

    // ==================== 各 kind 的判定 ====================

    /**
     * `DEVICE_STATE`：整体委托 [DeviceStateEvaluator]。
     *
     * 先做「可判定性」检查：只要某个**已配置**的子条件在运行时侧没有数据
     * （`screenOn == null` / `charging == null` / `now == null`），就返回 null（不投票）。
     * 不能直接依赖 `DeviceStateEvaluator` 的"跳过"行为——那是"视为满足"，会让试跑模式
     * 下的条件恒真（架构 §1.2 明确要避免"恒真/恒假"）。
     */
    private fun evaluateDeviceState(cond: DeviceStateCondition, ctx: TriggerContext): Boolean? {
        if (cond.isEmpty) return null
        val state = mergeState(ctx)
        if (!isDecidable(cond, state)) return null
        return DeviceStateEvaluator.evaluate(cond, state) == null
    }

    /**
     * 把 [TriggerContext.now] 补进设备状态：调用方既可只填 `ctx.now`（最常用），
     * 也可自行填 `deviceState.now`（两者都填时以 `deviceState.now` 为准）。
     */
    private fun mergeState(ctx: TriggerContext): DeviceState {
        val effectiveNow = ctx.deviceState.now ?: ctx.now.takeIf { it > 0L }
        return ctx.deviceState.copy(now = effectiveNow)
    }

    /** 每个已配置的子条件在运行时侧是否都有数据（否则整条不可判定）。 */
    private fun isDecidable(cond: DeviceStateCondition, state: DeviceState): Boolean {
        if (cond.screenStates.isNotEmpty() && state.screenOn == null) return false
        if (cond.chargeStates.isNotEmpty() && state.charging == null) return false
        val needsTime = cond.timeWindows.isNotEmpty() || cond.weekdays.isNotEmpty()
        if (needsTime && state.now == null) return false
        return true
    }

    /**
     * `CLOCK_AT`：`HH:mm` 与当前时刻的时分相同 → true（分钟级粒度）。
     *
     * 同一天同一分钟只应触发一次：调用方在触发后把 [clockKey] 放进
     * [TriggerContext.firedClockKeys]，本分钟内的后续 tick 返回 null（不重复触发）。
     */
    private fun evaluateClockAt(clockAt: String, ctx: TriggerContext): Boolean? {
        val parsed = SnoozeCalculator.parseHHmm(clockAt) ?: return false
        if (ctx.now <= 0L) return null
        val key = clockKey(parsed.first, parsed.second)
        if (key in ctx.firedClockKeys) return null
        val localTime = Instant.ofEpochMilli(ctx.now).atZone(ZoneId.systemDefault()).toLocalTime()
        return localTime.hour == parsed.first && localTime.minute == parsed.second
    }

    /**
     * `APP_NOTIFICATION`：事件包名在 `packages` 内且边沿匹配。
     *
     * 没有事件上下文（为 null）→ false（"不成立"）。管线只在事件到达时派发这类条件，
     * 因此分钟 ticker 不会误判成"通知消失了"（架构 §3.5 的分桶派发）。
     */
    private fun evaluateAppNotification(trigger: OverlayTrigger, ctx: TriggerContext): Boolean {
        val event = ctx.lastEvent ?: return false
        if (event.packageName.isEmpty()) return false
        if (event.packageName !in trigger.packages) return false
        return edgeMatches(trigger.edge, event)
    }

    /** `RULE_NOTIFICATION`：事件 `ruleId` 在 `ruleIds` 内且边沿匹配。 */
    private fun evaluateRuleNotification(trigger: OverlayTrigger, ctx: TriggerContext): Boolean {
        val event = ctx.lastEvent ?: return false
        val ruleId = event.ruleId ?: return false
        if (ruleId !in trigger.ruleIds) return false
        return edgeMatches(trigger.edge, event)
    }

    /**
     * `FOREGROUND_APP`：前台包名在 `packages` 内。
     *
     * 前台包名未知（无障碍未授权 / 刚启动还没事件）→ false。
     */
    private fun evaluateForegroundApp(trigger: OverlayTrigger, ctx: TriggerContext): Boolean {
        val foreground = ctx.foregroundPackage ?: return false
        if (foreground.isEmpty()) return false
        return foreground in trigger.packages
    }

    /** 边沿是否匹配：OCCUR ↔ POSTED / MATCHED；REMOVE ↔ REMOVED。 */
    private fun edgeMatches(edge: TriggerEdge, event: TriggerEvent): Boolean = when (edge) {
        TriggerEdge.OCCUR ->
            event.type == TriggerEventType.POSTED || event.type == TriggerEventType.MATCHED

        TriggerEdge.REMOVE -> event.type == TriggerEventType.REMOVED
    }

    // ==================== 对外工具（供 Arbiter / UI 复用） ====================

    /**
     * `CLOCK_AT` 的分钟键（0..1439）：`"22:00"` → 1320。
     *
     * @return 非法格式 → null（调用方据此跳过，不抛异常）
     */
    fun clockKey(clockAt: String): Int? {
        val parsed = SnoozeCalculator.parseHHmm(clockAt) ?: return null
        return clockKey(parsed.first, parsed.second)
    }

    /** 分钟键（时 × 60 + 分）；参数越界时按 [MINUTES_PER_DAY] 夹取，避免脏数据建出负键。 */
    private fun clockKey(hour: Int, minute: Int): Int =
        (hour * 60 + minute).coerceIn(0, MINUTES_PER_DAY - 1)

    /**
     * 某一时刻的当日分钟数（0..1439）：供 Arbiter 判断"今天是否已经过了这个点"。
     *
     * @param nowMillis epoch 毫秒（本地时区）
     * @return 分钟数；`nowMillis <= 0` → null
     */
    fun minuteOfDay(nowMillis: Long, zone: ZoneId = ZoneId.systemDefault()): Int? {
        if (nowMillis <= 0L) return null
        val localTime = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalTime()
        return localTime.hour * 60 + localTime.minute
    }
}
