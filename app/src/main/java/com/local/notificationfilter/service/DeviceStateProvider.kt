package com.local.notificationfilter.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager
import com.local.notificationfilter.core.model.ChargeState
import com.local.notificationfilter.core.model.DeviceState
import com.local.notificationfilter.util.NfLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 进程内唯一「当前设备状态」事实来源（V3 需求⑦，架构 D6）。
 *
 * 与 [ListenerStatusHolder] 同模式：纯 Kotlin object + StateFlow，无 Hilt。
 * 监听服务连接时调用 [attach] 注册系统广播（屏幕亮灭 / 电源插拔），断开时 [detach]。
 *
 * 引擎评估：[NotificationPipeline] 读取 [current] 并传入 `RuleEngine.matchFirstRule`；
 * UI 规则编辑页可观察 [state] 展示当前设备状态预览。
 *
 * 三字段中 `now` 始终为当前时间戳（每次读取实时计算），`screenOn` / `charging` 由广播更新。
 *
 * P2-9（省电）：充电状态**不订阅** `ACTION_BATTERY_CHANGED`。那是 sticky 广播，电量 / 电压 /
 * 温度任何变化都会重投（实测分钟级），每次投递都会写 [_state] 并可能唤醒 UI 订阅者，而我们
 * 只需要其中的 `EXTRA_PLUGGED`。改为只在**插拔那一刻**触发的
 * [Intent.ACTION_POWER_CONNECTED] / [Intent.ACTION_POWER_DISCONNECTED]（两者**不是** sticky，
 * 故初始值仍需在 [attach] 时主动读一次 sticky 的 `ACTION_BATTERY_CHANGED`）。
 */
object DeviceStateProvider {

    private val _state = MutableStateFlow(DeviceState())
    val state: StateFlow<DeviceState> = _state.asStateFlow()

    /** 当前设备状态快照（now 实时取 System.currentTimeMillis）。 */
    val current: DeviceState
        get() = _state.value.copy(now = System.currentTimeMillis())

    private var registered = false
    private var appContext: Context? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_ON -> update(screenOn = true)
                Intent.ACTION_SCREEN_OFF -> update(screenOn = false)
                // 插电：ACTION_POWER_CONNECTED 自带的 extras 不含 EXTRA_PLUGGED（该 action 靠
                // 「此刻已插电」这一事实表达状态），故主动读一次 sticky 只为区分有线 / 无线。
                Intent.ACTION_POWER_CONNECTED -> update(charging = chargingOnPowerConnected(context))
                // 拔电：无需读取任何 extras，直接回到电池供电
                Intent.ACTION_POWER_DISCONNECTED -> update(charging = ChargeState.BATTERY)
            }
        }
    }

    /**
     * 注册系统广播（监听服务连接时调用）。
     * 首次 attach 时同步读取当前屏幕 / 电池状态作为初始值。
     */
    fun attach(context: Context) {
        if (registered) return
        val ctx = context.applicationContext
        appContext = ctx

        // 初始屏幕状态
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val screenOn = pm?.isInteractive ?: true

        // 初始充电状态：ACTION_BATTERY_CHANGED 是 sticky broadcast，注册即拿到最新值
        // （插拔广播本身不是 sticky，只能在 attach 时这样读一次，之后靠插拔广播维持）
        val charging = stickyChargingOrNull(ctx) ?: ChargeState.BATTERY

        _state.value = DeviceState(screenOn = screenOn, charging = charging, now = System.currentTimeMillis())

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
        }
        runCatching { ctx.registerReceiver(receiver, filter) }
            .onFailure { NfLog.w(this, "设备状态广播注册失败: ${it.message}") }
        registered = true
        NfLog.i(this, "DeviceStateProvider 已挂载（screenOn=$screenOn charging=$charging）")
    }

    /** 注销广播（监听服务断开 / 销毁时调用）。屏幕亮灭与插拔广播共用同一个 [receiver]，一次注销。 */
    fun detach() {
        if (!registered) return
        runCatching { appContext?.unregisterReceiver(receiver) }
            .onFailure { NfLog.w(this, "设备状态广播注销失败: ${it.message}") }
        registered = false
        appContext = null
        NfLog.i(this, "DeviceStateProvider 已卸载")
    }

    private fun update(screenOn: Boolean? = null, charging: ChargeState? = null) {
        val cur = _state.value
        _state.value = cur.copy(
            screenOn = screenOn ?: cur.screenOn,
            charging = charging ?: cur.charging,
            now = System.currentTimeMillis(),
        )
    }

    /**
     * 插电瞬间的充电类型：读一次 sticky 的 `ACTION_BATTERY_CHANGED`（**仅此一次**，不常驻订阅）。
     *
     * 兜底口径：sticky 尚未刷新（`EXTRA_PLUGGED` 仍为 0）或读取失败时按 [ChargeState.WIRED]——
     * 此刻确实已插电，退回 BATTERY 会让「充电中」规则失效。
     */
    private fun chargingOnPowerConnected(context: Context?): ChargeState {
        val ctx = context?.applicationContext ?: appContext ?: return ChargeState.WIRED
        val sticky = stickyChargingOrNull(ctx) ?: return ChargeState.WIRED
        return if (sticky == ChargeState.BATTERY) ChargeState.WIRED else sticky
    }

    /**
     * 一次性读取 sticky 的 `ACTION_BATTERY_CHANGED` 并换算成 [ChargeState]；
     * 系统不返回该 sticky intent（极少数精简 ROM）时返回 null，由调用方决定兜底值。
     */
    private fun stickyChargingOrNull(ctx: Context): ChargeState? {
        val batteryIntent = runCatching {
            ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        }.getOrNull() ?: return null
        val plugged = batteryIntent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
        return chargeFromPlugged(plugged)
    }

    /** Android EXTRA_PLUGGED 值 → [ChargeState]；0 = 电池，AC/USB = 有线，Wireless = 无线。 */
    private fun chargeFromPlugged(plugged: Int): ChargeState = when (plugged) {
        0 -> ChargeState.BATTERY
        BatteryManager.BATTERY_PLUGGED_AC,
        BatteryManager.BATTERY_PLUGGED_USB -> ChargeState.WIRED
        BatteryManager.BATTERY_PLUGGED_WIRELESS -> ChargeState.WIRELESS
        else -> ChargeState.BATTERY
    }
}
