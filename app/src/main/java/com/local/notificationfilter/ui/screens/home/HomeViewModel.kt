package com.local.notificationfilter.ui.screens.home

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.local.notificationfilter.NfApplication
import com.local.notificationfilter.core.model.NotificationRecord
import com.local.notificationfilter.data.repo.RuleRepository
import com.local.notificationfilter.service.AccessibilityGate
import com.local.notificationfilter.service.ListenerStatusHolder
import com.local.notificationfilter.service.NfNotificationListener
import com.local.notificationfilter.service.OngoingStatusNotification
import com.local.notificationfilter.ui.theme.AppTheme
import com.local.notificationfilter.ui.theme.DarkMode
import com.local.notificationfilter.util.ListenerBinder
import com.local.notificationfilter.util.PermissionChecker
import com.local.notificationfilter.util.PermissionProbe
import com.local.notificationfilter.util.PermissionType
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class HomeUiState(
    val masterEnabled: Boolean = false,
    val todayFiltered: Int = 0,
    val todayPassed: Int = 0,
    val activeRuleCount: Int = 0,
    val blacklistCount: Int = 0,
    val recentRecords: List<NotificationRecord> = emptyList(),
    val missingPermissions: List<PermissionType> = emptyList(),
    /** 通知使用权是否已授权（Secure 查询口径；V2 需求⑥：授权 ≠ 连接）。 */
    val listenerGranted: Boolean = false,
    /** 监听服务是否已连接（ListenerStatusHolder 推送，唯一事实来源）。 */
    val connected: Boolean = false,
    /** 常驻通知开关（V2 需求⑤，默认关闭）。 */
    val showOngoing: Boolean = false,
    /** 「关闭常驻通知」风险是否已确认（首次关闭弹窗判断）。 */
    val ongoingRiskAcknowledged: Boolean = false,
    /** V4-5：当前色相主题名（AppTheme.name）。 */
    val theme: String = AppTheme.INDIGO.name,
    /** V4-5：当前深浅模式名（DarkMode.name），与色相主题正交。 */
    val darkMode: String = DarkMode.SYSTEM.name,
    /** V8：是否在系统多任务（最近任务）界面隐藏本 App，默认 false。 */
    val hideFromRecents: Boolean = false,
    /** 一次性提示（重新绑定结果等）；UI 展示后调 [consumeHint] 清空。 */
    val hint: String? = null,
)

/**
 * P2 首页：总开关（持久化 + 联动常驻通知）、服务三态、常驻通知开关、
 * 今日概览、最近通知、权限缺失提醒。
 */
class HomeViewModel(application: Application) : AndroidViewModel(application) {

    private val container = (application as NfApplication).container

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    /**
     * V8：「在多任务中隐藏」开关的独立只读视图（UI 只订阅用）。
     * 与 [HomeUiState.hideFromRecents] 同源（均由 DataStore 驱动），保留独立 StateFlow
     * 是为了让只需要这一个标志的调用方不必依赖整个 [HomeUiState]。
     * 注意：ViewModel **不**触碰 Activity / ActivityManager，真正的任务属性由 MainActivity 施加。
     */
    val hideFromRecents: StateFlow<Boolean> = uiState
        .map { it.hideFromRecents }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    init {
        viewModelScope.launch {
            container.settingsRepository.masterEnabled.collectLatest { on ->
                _uiState.update { it.copy(masterEnabled = on) }
            }
        }
        viewModelScope.launch {
            container.settingsRepository.showOngoing.collectLatest { on ->
                _uiState.update { it.copy(showOngoing = on) }
            }
        }
        viewModelScope.launch {
            container.settingsRepository.ongoingRiskAcknowledged.collectLatest { acked ->
                _uiState.update { it.copy(ongoingRiskAcknowledged = acked) }
            }
        }
        // V4-5：色相主题 + 深浅模式
        viewModelScope.launch {
            container.settingsRepository.theme.collectLatest { name ->
                _uiState.update { it.copy(theme = name) }
            }
        }
        viewModelScope.launch {
            container.settingsRepository.darkMode.collectLatest { name ->
                _uiState.update { it.copy(darkMode = name) }
            }
        }
        // V8：多任务隐藏开关（真机生效由 MainActivity 订阅同一 Flow 完成）
        viewModelScope.launch {
            container.settingsRepository.hideFromRecents.collectLatest { hidden ->
                _uiState.update { it.copy(hideFromRecents = hidden) }
            }
        }
        // 连接状态：进程内唯一事实来源（服务 onListenerConnected/Disconnected 推送）
        viewModelScope.launch {
            ListenerStatusHolder.connected.collectLatest { connected ->
                _uiState.update { it.copy(connected = connected) }
            }
        }
        viewModelScope.launch {
            container.ruleRepository.observeAll().collectLatest { rules ->
                _uiState.update { it.copy(activeRuleCount = rules.count { r -> r.enabled }) }
            }
        }
        viewModelScope.launch {
            container.blacklistRepository.observeCount().collectLatest { count ->
                _uiState.update { it.copy(blacklistCount = count) }
            }
        }
        viewModelScope.launch {
            container.recordRepository.observeRecent(RECENT_LIMIT).collectLatest { records ->
                _uiState.update { it.copy(recentRecords = records) }
            }
        }
        viewModelScope.launch {
            // epochDay：1970-01-01 起的天数，全数据层（Entity/DAO/Repository）均为 Int 口径
            val today: Int = LocalDate.now(ZoneId.systemDefault()).toEpochDay().toInt()
            container.statsRepository.observeDay(today).collectLatest { day ->
                _uiState.update { it.copy(todayFiltered = day.filtered, todayPassed = day.passed) }
            }
        }
        refreshPermissions()
    }

    /** 切换总开关：持久化 + 按常驻开关决定刷新/取消常驻通知（V2 需求⑤）。 */
    fun toggleMaster(on: Boolean) {
        viewModelScope.launch {
            container.settingsRepository.setMasterEnabled(on)
            // V13.2：配置变了 → 作废「常驻通知已打盹」抑制表。
            // 否则关掉总开关再打开，之前被压着的常驻通知仍不出现（用户以为开关没生效）。
            NfNotificationListener.instance?.clearOngoingSuppress()
            val app = getApplication<Application>()
            if (container.settingsRepository.isShowOngoing()) {
                // 常驻通知开：文案随总开关刷新（过滤中 / 已暂停）
                OngoingStatusNotification(app).show(on)
            } else {
                // 常驻通知关：本就不应有条目，hide 双保险
                OngoingStatusNotification(app).hide()
            }
            _uiState.update { it.copy(masterEnabled = on) }
        }
    }

    /**
     * 切换常驻通知开关：持久化 + 通知服务即时显隐。
     * 服务未连接（instance 为空）时仅持久化，重连后由 onListenerConnected 按开关恢复。
     */
    fun setShowOngoing(on: Boolean) {
        viewModelScope.launch {
            container.settingsRepository.setShowOngoing(on)
            NfNotificationListener.instance?.applyOngoingVisibility(on)
        }
    }

    /** V4-5：切换色相主题（写入 DataStore，MainActivity 收集后重建配色）。 */
    fun setTheme(name: String) {
        viewModelScope.launch { container.settingsRepository.setTheme(name) }
    }

    /** V4-5：切换深浅模式（与色相主题正交）。 */
    fun setDarkMode(name: String) {
        viewModelScope.launch { container.settingsRepository.setDarkMode(name) }
    }

    /**
     * V8：切换「在多任务中隐藏」。
     *
     * 本方法只写 DataStore —— 这是刻意的分工：
     * - 写入后 DataStore 会回放新值，MainActivity 订阅同一 Flow 立即把值应用到
     *   `AppTask.setExcludeFromRecents()`，所以「点一下就生效」不依赖本方法碰 Activity；
     * - 首页菜单的勾选态也由同一 Flow 驱动，无需本地乐观更新（避免与磁盘态打架）。
     *
     * 同时给一条一次性提示，明确告知「重新打开应用后仍然生效」，避免用户以为
     * 只是本次会话的临时效果（历史任务不会凭空重排，只影响本任务的呈现）。
     */
    fun setHideFromRecents(hidden: Boolean) {
        viewModelScope.launch {
            container.settingsRepository.setHideFromRecents(hidden)
            _uiState.update {
                it.copy(
                    hint = if (hidden) "已隐藏（重新打开应用后仍生效）" else "已在多任务中显示",
                )
            }
        }
    }

    /** 「关闭常驻通知」风险已确认（首次关闭二次确认后写入，之后不再弹窗）。 */
    fun markOngoingRiskAcknowledged() {
        viewModelScope.launch {
            container.settingsRepository.setOngoingRiskAcknowledged(true)
        }
    }

    /**
     * 权限状态刷新（首页 onResume 与 init 时调用）。
     * V2 需求⑥：返回重试三拍 0s / 0.5s / 1.5s，任一拍通知使用权翻正即提前返回。
     *
     * V4：悬浮窗权限只在**有启用中的规则用到屏幕显示动作**（弹幕 / 悬浮 / 焦点通知）时
     * 才算「必须授权」。该权限 autoDetect、无手动兜底，若无条件计入缺失项，不用这些
     * 功能的用户首页会永久挂着「有 1 项权限未授予」的黄色告警。
     *
     * 过滤放在这里（上游）而不是 HomeScreen：UI 层只是消费 [HomeUiState.missingPermissions]，
     * 不需要知道 OVERLAY 这回事。首页已有 ON_RESUME 重刷机制，一次性读取足够。
     *
     * V7 需求 7-⑥：无障碍服务（[PermissionType.ACCESSIBILITY]）同口径——只在**有启用规则
     * 使用「打开了某个 App」条件**时才算必须授权，否则既不计数也不展示（否则首页会永久挂
     * 一条用户点进去也不知道该开什么的红色未授权项）。判定直接复用
     * [AccessibilityGate.isNeeded]（与授权页展示、运行时标记**同一实现**，不写第二套 `any{}`）。
     *
     * 性能：本方法只在 init / ON_RESUME / 重新绑定后各跑一次，**不在重组路径上**
     * （HomeScreen 只读 [HomeUiState.missingPermissions] 这个布尔列表）。规则只读一次
     * （[RuleRepository.getAll] 命中 P1-5 内存缓存），一次遍历同时算出两个「是否需要」标志，
     * 不新增第二次全表扫描。
     */
    fun refreshPermissions() {
        viewModelScope.launch {
            val manual = container.settingsStore.manualPerms.first()
            val statuses = PermissionProbe.withRetry(getApplication(), manual)
            // 读规则失败时按 false 处理：宁可少提示一条，也不要让整个权限区块崩掉
            val rules = runCatching {
                container.ruleRepository.getAll()
            }.getOrDefault(emptyList())
            val needOverlay = rules.any { it.enabled && it.actions.hasOverlay }
            val needAccessibility = AccessibilityGate.isNeeded(rules)
            val missing = statuses
                .filter { !it.granted && (needOverlay || it.type != PermissionType.OVERLAY) }
                .filter { needAccessibility || it.type != PermissionType.ACCESSIBILITY }
                .map { it.type }
            val granted = statuses.firstOrNull {
                it.type == PermissionType.NOTIFICATION_LISTENER
            }?.granted ?: false
            _uiState.update {
                it.copy(missingPermissions = missing, listenerGranted = granted)
            }
        }
    }

    /** 「点此重新绑定」：服务未连接时请求系统重新绑定（HyperOS3 有效性待真机验证）。 */
    fun requestRebind() {
        val ok = ListenerBinder.requestRebind(getApplication())
        _uiState.update {
            it.copy(
                hint = if (ok) "已请求重新绑定监听服务，请稍候…"
                else "重新绑定失败，请到系统设置重新开关通知使用权",
            )
        }
        if (ok) refreshPermissions()
    }

    /** 一次性提示已被 UI 展示（Snackbar 弹出后调用）。 */
    fun consumeHint() {
        _uiState.update { it.copy(hint = null) }
    }

    /** 清空流水（设置菜单；统计计数保留）。 */
    fun clearRecords() {
        viewModelScope.launch { container.recordRepository.clearAll() }
    }

    companion object {
        const val RECENT_LIMIT = 5
    }
}
