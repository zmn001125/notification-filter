package com.local.notificationfilter.core.model

import kotlinx.serialization.Serializable

/**
 * 悬浮卡片可见性条件的类型（V7 需求 7 六类行为）。
 *
 * 六类行为被收敛成 **5 个 kind**——因为「亮屏/锁屏」「充电状态」「时间段内保持」
 * 三类共用 [DeviceStateCondition]（用户明确要求复用「设备状态生效条件」的口径，
 * core/model/DeviceState.kt:65-94），只有「到某个时刻」这个**单点**时刻无法用
 * TimeWindow（有起止两端，DeviceState.kt:47-50）表达，才独立成 CLOCK_AT。
 */
enum class OverlayTriggerKind(val label: String) {
    /** 需求 7-①/②(时段)/③：屏幕亮灭 + 充电状态 + 时间段 + 星期，复用 DeviceStateEvaluator。 */
    DEVICE_STATE("手机状态（亮屏 / 充电 / 时段）"),

    /** 需求 7-②(到点)：每日到达某个时刻（HH:mm，分钟级粒度）。 */
    CLOCK_AT("到了某个时刻"),

    /** 需求 7-④：指定 App 的通知出现 / 消失。 */
    APP_NOTIFICATION("某个 App 的通知"),

    /** 需求 7-⑤：满足某规则条件的通知出现 / 消失（引用已有规则 id）。 */
    RULE_NOTIFICATION("某条规则命中"),

    /** 需求 7-⑥：打开了某个 App（无障碍服务上报的前台包名）。 */
    FOREGROUND_APP("打开了某个 App"),
    ;

    companion object {
        /** 全部 kind（UI 下拉顺序即此顺序）。 */
        val ALL: List<OverlayTriggerKind> = entries.toList()

        /** 按枚举名解析（JSON 存名）；未知值回落 [DEVICE_STATE]（与 OverlayStyle.kt:89-92 同口径）。 */
        fun of(name: String?): OverlayTriggerKind =
            entries.firstOrNull { it.name == name } ?: DEVICE_STATE
    }
}

/** 条件成立时的效果（V9：用「悬浮」而不是「卡片」，与用户词汇一致）。 */
enum class TriggerEffect(val label: String) {
    SHOW("提前显示悬浮"),
    HIDE("立即清除悬浮"),
}

/**
 * 通知类条件的边沿。
 *
 * V9 改文案：原来只写「通知出现时 / 通知消失时」，用在「某条规则命中」上会读不通
 * （规则本身不会"出现"），改成对两种来源都成立的说法。
 */
enum class TriggerEdge(val label: String) {
    OCCUR("通知出现 / 规则命中时"),
    REMOVE("通知被划掉时"),
}

/**
 * **一条**行为触发条件（V7 需求 7）。
 *
 * 求值语义（唯一实现：[com.local.notificationfilter.core.ruleengine.OverlayTriggerEvaluator]）：
 * - 该条评估为「真」→ 施加 [effect]；
 * - 该条评估为「假」且 [hideWhenFalse] = true → 施加 [effect] 的反面
 *   （用于「在时间段内保持显示、离开时段就消失」「打开某 App 显示、退出就消失」）；
 * - 参数没配齐（[isEffective] = false）→ 编译期直接丢弃，**不允许出现"恒真/恒假"的条件**
 *   （否则 `DeviceStateCondition` 全空时 `evaluate` 恒返回 null = 恒真，
 *   DeviceStateEvaluator.kt:35 `if (cond.isEmpty) return null`，会变成"永远显示"的坑）。
 *
 * 多条条件之间是 **OR**（任一满足即触发，用户已拍板）；冲突时 HIDE 优先（D6）。
 */
@Serializable
data class OverlayTrigger(
    /** 类型名（存枚举 name，未知值回落 DEVICE_STATE —— 与 OverlayStyle 的 kind 字段同口径）。 */
    val kind: String = OverlayTriggerKind.DEVICE_STATE.name,
    /** 条件为真时的效果。 */
    val effect: TriggerEffect = TriggerEffect.SHOW,
    /** 条件为假时是否也触发（= 取反边）。默认 false = 只在"为真"时动作。 */
    val hideWhenFalse: Boolean = false,

    // —— 参数区（按 kind 取用，其余保持默认，不参与求值）——

    /**
     * kind = DEVICE_STATE：复用规则已有的设备状态条件
     * （屏幕 / 充电 / 时间窗口 / 星期，内部 AND、各子项多选 OR、支持跨午夜）。
     */
    val device: DeviceStateCondition = DeviceStateCondition(),

    /** kind = CLOCK_AT：每日到点时刻（"HH:mm"）。 */
    val clockAt: String = "",

    /** kind = APP_NOTIFICATION / FOREGROUND_APP：目标包名集合（多个是 OR）。 */
    val packages: Set<String> = emptySet(),

    /** kind = RULE_NOTIFICATION：被引用的规则 id（任一命中即满足）。 */
    val ruleIds: Set<Long> = emptySet(),

    /**
     * V12.0 的旧单目标字段，**仅为兼容旧 JSON 而保留**；
     * 新代码一律用 [allTargetRuleIds] / [targetRuleIds]。
     *
     * 语义（与 V12.0 一致）：非 null = 把「显示 / 立即清除」作用到**那条规则**的悬浮窗
     * （用它自己的样式与内容），于是不同规则的悬浮窗可以同时在屏幕上存在、互不顶掉。
     *
     * 注意：目标是**可选项**，不参与 [isEffective] 的「参数是否配齐」判定——
     * 配没配目标都不影响这条条件本身的成立与否。
     */
    val targetRuleId: Long? = null,

    /**
     * 条件作用的**目标规则** id 列表（V12.1，多选）。
     * 空 = 当前规则（本条规则自己的悬浮窗）；非空 = 同时作用到这些规则的悬浮窗
     * （各用它们自己的样式与内容），于是一条条件可以控制多扇窗。
     */
    val targetRuleIds: List<Long> = emptyList(),

    /** kind = APP_NOTIFICATION / RULE_NOTIFICATION：关注「出现」还是「消失」。 */
    val edge: TriggerEdge = TriggerEdge.OCCUR,
) {
    /** [kind] 字符串的强类型视图（未知值回落 DEVICE_STATE，见 [OverlayTriggerKind.of]）。 */
    val kindEnum: OverlayTriggerKind get() = OverlayTriggerKind.of(kind)

    /** 全部目标规则 id（多选字段 ∪ 旧单字段；空 = 当前规则）。 */
    val allTargetRuleIds: Set<Long> get() = (targetRuleIds + listOfNotNull(targetRuleId)).toSet()

    /**
     * 参数是否配齐。未配齐的条目在编译期被丢弃（[OverlayTriggerConfig.effective]），
     * 原因见类注释最后一段。
     */
    val isEffective: Boolean
        get() = when (kindEnum) {
            // device 全空 = 恒真（DeviceStateEvaluator 立即 return null）→ 必须显式排除
            OverlayTriggerKind.DEVICE_STATE -> !device.isEmpty
            OverlayTriggerKind.CLOCK_AT -> SnoozeCalculator.parseHHmm(clockAt) != null
            OverlayTriggerKind.APP_NOTIFICATION,
            OverlayTriggerKind.FOREGROUND_APP,
            -> packages.isNotEmpty()

            OverlayTriggerKind.RULE_NOTIFICATION -> ruleIds.isNotEmpty()
        }

    /** 列表页 / 分节收起时的一句话摘要。 */
    fun summary(): String {
        if (!isEffective) return "（未配置完整）"
        val edgeWord = if (hideWhenFalse) "真/假双向" else effect.label
        val base = when (kindEnum) {
            OverlayTriggerKind.DEVICE_STATE -> "设备状态（${device.summary()}）→ $edgeWord"
            OverlayTriggerKind.CLOCK_AT ->
                "每天 $clockAt → $edgeWord"

            OverlayTriggerKind.APP_NOTIFICATION ->
                "${packages.size} 个 App ${edge.label} → $edgeWord"

            OverlayTriggerKind.RULE_NOTIFICATION ->
                "${ruleIds.size} 条规则命中通知${edge.label} → $edgeWord"

            OverlayTriggerKind.FOREGROUND_APP ->
                "打开 ${packages.size} 个 App 之一 → $edgeWord"
        }
        // V12.1：配了目标规则（可多选）时在文案末尾点明（用户最关心「这条条件到底动谁的窗」）
        val targetCount = allTargetRuleIds.size
        return when {
            targetCount == 0 -> base
            targetCount == 1 -> "$base（作用于指定规则）"
            else -> "$base（作用于 $targetCount 条指定规则）"
        }
    }
}

/** 一张卡片（或一条规则）的完整条件集合。null / 空 = 无条件下（沿用 V6 行为）。 */
@Serializable
data class OverlayTriggerConfig(
    val triggers: List<OverlayTrigger> = emptyList(),
) {
    /** 参与求值的条件（已剔除未配齐的）。 */
    val effective: List<OverlayTrigger> get() = triggers.filter { it.isEffective }

    /** 是否没有任何有效条件（= 沿用 V6「命中即显示」行为）。 */
    val isEmpty: Boolean get() = effective.isEmpty()

    /** 是否需要无障碍服务（决定权限引导是否索取第 6 类权限，见 §3.6）。 */
    val needsForegroundApp: Boolean
        get() = effective.any { it.kindEnum == OverlayTriggerKind.FOREGROUND_APP }

    /** 是否有时间敏感条件（决定是否启动分钟 ticker，见 §3.4）。 */
    val needsClockTick: Boolean
        get() = effective.any {
            it.kindEnum == OverlayTriggerKind.CLOCK_AT ||
                (it.kindEnum == OverlayTriggerKind.DEVICE_STATE &&
                    (it.device.timeWindows.isNotEmpty() || it.device.weekdays.isNotEmpty()))
        }

    /** 分节收起 / 列表页摘要。 */
    fun summary(): String =
        if (isEmpty) "无（命中即显示）" else "${effective.size} 条条件"
}
