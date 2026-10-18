package com.local.notificationfilter.ui.screens.blacklist

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.local.notificationfilter.NfApplication
import com.local.notificationfilter.core.model.BlacklistEntry
import com.local.notificationfilter.util.AppUtils
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * P8 黑名单页 ViewModel：列表随数据库 Flow 自动刷新（增删即时同步）。
 *
 * 添加时按包名解析当前应用名（用户可在弹窗里多选）；条目移除即时生效。
 */
class BlacklistViewModel(application: Application) : AndroidViewModel(application) {

    data class BlacklistUiState(
        val entries: List<BlacklistEntry> = emptyList(),
    ) {
        val count: Int get() = entries.size
    }

    private val container = (application as NfApplication).container
    private val blacklistRepository = container.blacklistRepository

    val uiState: StateFlow<BlacklistUiState> = blacklistRepository.observeAll()
        .map { BlacklistUiState(entries = it) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = BlacklistUiState(),
        )

    /** 批量加入黑名单（弹窗多选确认）。 */
    fun addPackages(packages: Set<String>) {
        if (packages.isEmpty()) return
        viewModelScope.launch {
            packages.forEach { pkg ->
                val appName = AppUtils.appNameOf(getApplication(), pkg)
                blacklistRepository.add(pkg, appName)
            }
        }
    }

    fun remove(packageName: String) {
        viewModelScope.launch { blacklistRepository.remove(packageName) }
    }
}
