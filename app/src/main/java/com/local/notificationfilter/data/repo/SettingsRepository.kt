package com.local.notificationfilter.data.repo

import com.local.notificationfilter.data.prefs.SettingsStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

/** 设置仓库：总开关 / 引导完成标记 / 常驻通知开关（DataStore 之上的薄封装，供 UI 与服务共用）。 */
class SettingsRepository(private val store: SettingsStore) {

    val masterEnabled: Flow<Boolean> = store.masterEnabled

    val guideCompleted: Flow<Boolean> = store.guideCompleted

    /** 常驻通知开关（V2 需求⑤）：默认关闭。 */
    val showOngoing: Flow<Boolean> = store.showOngoing

    /** 「关闭常驻通知」风险确认标记（首次关闭弹窗判断）。 */
    val ongoingRiskAcknowledged: Flow<Boolean> = store.ongoingRiskAcknowledged

    /** V4-5：色相主题名（存 [com.local.notificationfilter.ui.theme.AppTheme] 的 name）。 */
    val theme: Flow<String> = store.theme

    /** V4-5：深浅模式名（存 [com.local.notificationfilter.ui.theme.DarkMode] 的 name）。 */
    val darkMode: Flow<String> = store.darkMode

    /**
     * V8：是否在系统多任务（最近任务）界面隐藏本 App，默认 false。
     *
     * 数据层只承载开关状态；「隐藏」本身是任务级运行时属性，
     * 由 `MainActivity.applyRecentsVisibility()` / `applyFromSettings()` 施加。
     */
    val hideFromRecents: Flow<Boolean> = store.hideFromRecents

    suspend fun setMasterEnabled(value: Boolean) {
        store.setMasterEnabled(value)
    }

    suspend fun setGuideCompleted(value: Boolean) {
        store.setGuideCompleted(value)
    }

    suspend fun setShowOngoing(value: Boolean) {
        store.setShowOngoing(value)
    }

    suspend fun setOngoingRiskAcknowledged(value: Boolean) {
        store.setOngoingRiskAcknowledged(value)
    }

    /** V4-5：设置色相主题。 */
    suspend fun setTheme(value: String) {
        store.setTheme(value)
    }

    /** V4-5：设置深浅模式。 */
    suspend fun setDarkMode(value: String) {
        store.setDarkMode(value)
    }

    /** V8：设置「在多任务中隐藏」开关（Activity 侧订阅本 Flow 后即时应用到任务）。 */
    suspend fun setHideFromRecents(value: Boolean) {
        store.setHideFromRecents(value)
    }

    /**
     * 一次性读取当前总开关（管线热路径用）。
     *
     * V13.1 耗电修复：优先读 [SettingsStore.masterEnabledSnapshot] 进程内快照。
     * 本方法在**每条通知**的热路径上调用（「媒体变化重扫」里还会对每条通知再调一次）；
     * 原来每次都 `masterEnabled.first()`——稳态虽是内存读，但每次都要新建 collector、
     * 挂起一次协程、走一遍 Flow 算子再取消，通知密集时是持续的协程调度开销。
     *
     * 快照的唯一写入点是 `SettingsStore.setMasterEnabled`（含备份恢复），故不会陈旧。
     * `null` = 进程内尚未写过/读过，此时回源一次并记住。
     */
    suspend fun isMasterEnabled(): Boolean =
        store.masterEnabledSnapshot ?: masterEnabled.first().also { store.cacheMasterEnabled(it) }

    /** 一次性读取常驻通知开关（服务端 onListenerConnected / BootReceiver 恢复用）。 */
    suspend fun isShowOngoing(): Boolean = showOngoing.first()

    /**
     * 一次性读取「在多任务中隐藏」开关（MainActivity 启动 / onResume 应用用）。
     * 读取失败时由调用方按 false 兜底（宁可显示在最近任务里，也不要静默隐藏后无法唤起）。
     */
    suspend fun isHideFromRecents(): Boolean = hideFromRecents.first()
}
