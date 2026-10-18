package com.local.notificationfilter.core.model

import kotlinx.serialization.Serializable

// ==================== 规则侧：生效条件（可序列化，存入 Rule JSON） ====================

/** 屏幕状态（V3 需求⑦）。 */
enum class ScreenState(val label: String) {
    ON("亮屏"),
    OFF("熄屏"),
}

/** 充电状态（V3 需求⑦）。 */
enum class ChargeState(val label: String) {
    BATTERY("电池供电"),
    WIRED("有线充电"),
    WIRELESS("无线充电"),
}

/**
 * 星期（V3 需求⑦）。
 *
 * 用自定义枚举而非 `java.time.DayOfWeek`，避免 kotlinx.serialization 对 JDK 枚举的
 * 编译期序列化器生成歧义；运行时通过 [fromDayOfWeek] 从 JDK 转入。
 */
@Serializable
enum class Weekday(val label: String) {
    MON("周一"),
    TUE("周二"),
    WED("周三"),
    THU("周四"),
    FRI("周五"),
    SAT("周六"),
    SUN("周日"),
    ;

    companion object {
        /** JDK [java.time.DayOfWeek] → 本枚举（MONDAY=1 → MON=index 0）。 */
        fun fromDayOfWeek(dow: java.time.DayOfWeek): Weekday = entries[dow.value - 1]
    }
}

/**
 * 时间窗口（V3 需求⑦）：HH:mm 起止；`start > end` 表示跨午夜窗口（如 22:00-06:00）。
 */
@Serializable
data class TimeWindow(
    val start: String, // "HH:mm"
    val end: String,   // "HH:mm"
)

/**
 * 规则的设备状态生效条件（V3 需求⑦，架构 D7）。
 *
 * 四个子项均可多选；**全部 AND**；某子项为空 = 不限制：
 * - [screenStates]：选中状态之一即满足（OR）
 * - [chargeStates]：选中状态之一即满足（OR）
 * - [timeWindows]：多个窗口 OR + 跨午夜
 * - [weekdays]：多选 OR
 * - 窗口与星期 AND（两者都设置时须同时满足）
 *
 * 全空（默认）= 无设备状态限制，规则恒满足此前提条件。
 */
@Serializable
data class DeviceStateCondition(
    val screenStates: Set<ScreenState> = emptySet(),
    val chargeStates: Set<ChargeState> = emptySet(),
    val timeWindows: List<TimeWindow> = emptyList(),
    val weekdays: Set<Weekday> = emptySet(),
) {
    /** 全部子项为空 = 无限制（引擎恒通过此前提）。 */
    val isEmpty: Boolean get() =
        screenStates.isEmpty() && chargeStates.isEmpty() &&
            timeWindows.isEmpty() && weekdays.isEmpty()

    /** 列表页一句话摘要（架构 §5.6）；空返回空串。 */
    fun summary(): String {
        if (isEmpty) return ""
        val parts = mutableListOf<String>()
        if (screenStates.isNotEmpty()) {
            parts.add("屏幕：" + screenStates.joinToString("/") { it.label })
        }
        if (chargeStates.isNotEmpty()) {
            parts.add("充电：" + chargeStates.joinToString("/") { it.label })
        }
        if (timeWindows.isNotEmpty()) {
            parts.add("时间：" + timeWindows.joinToString("/") { "${it.start}-${it.end}" })
        }
        if (weekdays.isNotEmpty()) {
            parts.add("星期：" + weekdays.joinToString("/") { it.label })
        }
        return parts.joinToString(" ｜ ")
    }
}

// ==================== 运行时侧：当前设备状态快照（引擎评估用） ====================

/**
 * 运行时设备状态快照（V3 需求⑦，架构 D6）。
 *
 * 各字段可空：`null` = 未知（试跑模式）；引擎遇 null 跳过对应子条件（视为满足），
 * 与架构 D8「试跑不评估设备状态」一致。
 *
 * @param screenOn 屏幕亮灭；null = 未读取（试跑）
 * @param charging 当前充电状态；null = 未读取（试跑）
 * @param now 当前时间戳（毫秒）；null = 试跑模式，时间/星期子条件跳过
 */
data class DeviceState(
    val screenOn: Boolean? = null,
    val charging: ChargeState? = null,
    val now: Long? = null,
) {
    companion object {
        /** 试跑模式专用：全空，引擎跳过所有设备状态子条件（架构 D8）。 */
        val TEST_MODE: DeviceState = DeviceState()
    }
}
