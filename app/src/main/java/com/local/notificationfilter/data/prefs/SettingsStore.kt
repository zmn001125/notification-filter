package com.local.notificationfilter.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * DataStore 配置存储：总开关、引导完成标记（少量键值，架构共享约定 3）。
 *
 * V11：`nfDataStore` 委托已抽到 `NfDataStore.kt`（与 [BackupStore] 共用同一个
 * `nf_settings` 文件；同进程内同名 DataStore 只能声明一次委托）。
 */
class SettingsStore(private val context: Context) {

    private val keyMasterEnabled = booleanPreferencesKey("master_enabled")
    private val keyGuideCompleted = booleanPreferencesKey("guide_completed")

    /** 无标准查询 API 的权限（自启动 / 后台弹窗）：用户手动确认后记入。 */
    private val keyManualPerms = stringSetPreferencesKey("manual_perms")

    /** 常驻通知开关（V2 需求⑤，决策 D7）：默认 false = 默认不显示常驻通知。 */
    private val keyShowOngoing = booleanPreferencesKey("show_ongoing_notification")

    /** 「关闭常驻通知」风险确认标记（首次关闭时弹二次确认，确认后写入）。 */
    private val keyOngoingRiskAcknowledged = booleanPreferencesKey("ongoing_risk_acknowledged")

    /** V4-5：色相主题（存 [AppTheme] 的 name），默认 INDIGO。 */
    private val keyTheme = stringPreferencesKey("app_theme")

    /** V4-5：深浅模式（SYSTEM / LIGHT / DARK），与色相主题正交，默认跟随系统。 */
    private val keyDarkMode = stringPreferencesKey("dark_mode")

    /**
     * V8：是否在系统多任务（最近任务）界面隐藏本 App 任务。
     * 默认 false —— 新装用户行为与历史版本完全一致。
     * 安卓没有运行时可切的 Manifest 属性，实际生效由 MainActivity 调
     * `AppTask.setExcludeFromRecents()` 完成，这里只负责持久化开关状态。
     */
    private val keyHideFromRecents = booleanPreferencesKey("hide_from_recents")

    companion object {
        /** 默认色相主题（靛蓝）。 */
        const val DEFAULT_THEME = "INDIGO"

        /** 默认深浅模式（跟随系统）。 */
        const val DEFAULT_DARK_MODE = "SYSTEM"
    }

    /** 总开关：默认关闭（首次安装需用户显式开启）。 */
    val masterEnabled: Flow<Boolean> =
        context.nfDataStore.data.map { it[keyMasterEnabled] ?: false }

    /** V4-5：当前色相主题名（默认 INDIGO，与 ui.theme.AppTheme 的 name 对应）。 */
    val theme: Flow<String> =
        context.nfDataStore.data.map { it[keyTheme] ?: DEFAULT_THEME }

    /** V4-5：当前深浅模式（默认 SYSTEM 跟随系统，与 ui.theme.DarkMode 的 name 对应）。 */
    val darkMode: Flow<String> =
        context.nfDataStore.data.map { it[keyDarkMode] ?: DEFAULT_DARK_MODE }

    /**
     * V8：是否在系统多任务（最近任务）界面隐藏本 App。默认 false（不隐藏）。
     * 注意：本 Flow 只描述**用户意图**，真正生效需 MainActivity 调
     * `AppTask.setExcludeFromRecents()`（运行时任务属性，不跨任务重建保留）。
     */
    val hideFromRecents: Flow<Boolean> =
        context.nfDataStore.data.map { it[keyHideFromRecents] ?: false }

    /** 权限引导是否已完成（决定起始页）。 */
    val guideCompleted: Flow<Boolean> =
        context.nfDataStore.data.map { it[keyGuideCompleted] ?: false }

    /** 已手动确认的权限名集合（PermissionType.name）。 */
    val manualPerms: Flow<Set<String>> =
        context.nfDataStore.data.map { it[keyManualPerms] ?: emptySet() }

    /**
     * 常驻通知开关（V2 需求⑤）：true = 通知栏显示「过滤中 / 已暂停」并前台化保活；
     * false（默认）= 不显示，服务以纯后台形态运行。
     */
    val showOngoing: Flow<Boolean> =
        context.nfDataStore.data.map { it[keyShowOngoing] ?: false }

    /** 「关闭常驻通知」风险是否已被用户确认（首次关闭弹窗判断）。 */
    val ongoingRiskAcknowledged: Flow<Boolean> =
        context.nfDataStore.data.map { it[keyOngoingRiskAcknowledged] ?: false }

    suspend fun setMasterEnabled(value: Boolean) {
        context.nfDataStore.edit { it[keyMasterEnabled] = value }
    }

    suspend fun setGuideCompleted(value: Boolean) {
        context.nfDataStore.edit { it[keyGuideCompleted] = value }
    }

    suspend fun setManualPerm(name: String, confirmed: Boolean) {
        context.nfDataStore.edit { prefs ->
            val current = prefs[keyManualPerms] ?: emptySet()
            prefs[keyManualPerms] = if (confirmed) current + name else current - name
        }
    }

    suspend fun setShowOngoing(value: Boolean) {
        context.nfDataStore.edit { it[keyShowOngoing] = value }
    }

    /** V4-5：写入色相主题。 */
    suspend fun setTheme(value: String) {
        context.nfDataStore.edit { it[keyTheme] = value }
    }

    /** V4-5：写入深浅模式。 */
    suspend fun setDarkMode(value: String) {
        context.nfDataStore.edit { it[keyDarkMode] = value }
    }

    /** V8：写入「在多任务中隐藏」开关（真正应用由 MainActivity 负责）。 */
    suspend fun setHideFromRecents(value: Boolean) {
        context.nfDataStore.edit { it[keyHideFromRecents] = value }
    }

    suspend fun setOngoingRiskAcknowledged(value: Boolean) {
        context.nfDataStore.edit { it[keyOngoingRiskAcknowledged] = value }
    }
}
