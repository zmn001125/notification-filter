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

    /** 一次性读取当前总开关（管线热路径用）。 */
    suspend fun isMasterEnabled(): Boolean = masterEnabled.first()

    /** 一次性读取常驻通知开关（服务端 onListenerConnected / BootReceiver 恢复用）。 */
    suspend fun isShowOngoing(): Boolean = showOngoing.first()

    /**
     * 一次性读取「在多任务中隐藏」开关（MainActivity 启动 / onResume 应用用）。
     * 读取失败时由调用方按 false 兜底（宁可显示在最近任务里，也不要静默隐藏后无法唤起）。
     */
    suspend fun isHideFromRecents(): Boolean = hideFromRecents.first()
}
