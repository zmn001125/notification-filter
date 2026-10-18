package com.local.notificationfilter.ui.screens.detail

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.local.notificationfilter.NfApplication
import com.local.notificationfilter.core.model.NotificationRecord
import com.local.notificationfilter.core.model.Rule
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** P7 详情页状态。 */
data class NotificationDetailUiState(
    val loading: Boolean = true,
    val record: NotificationRecord? = null,
    val notFound: Boolean = false,
    /** V3 需求③：该通知所属 App 的现有规则（scope 含此包名的规则）。 */
    val rulesForApp: List<Rule> = emptyList(),
)

/**
 * P7 完整字段详情 ViewModel。
 *
 * 两种定位方式：recordId 直接定位 / locateKey 按通知 key 定位（测试结果入口）；
 * 两者都失败（如流水已淘汰）时给 notFound 空态。
 *
 * V3 需求③：加载完成后同时拉取该 App 的现有规则（scope.packages 含此包名），
 * 供详情页展示「该 App 的现有规则」列表 + 点击直达编辑。
 */
class NotificationDetailViewModel(application: Application) : AndroidViewModel(application) {

    private val container = (application as NfApplication).container
    private val recordRepository = container.recordRepository
    private val ruleRepository = container.ruleRepository

    private val _uiState = MutableStateFlow(NotificationDetailUiState())
    val uiState: StateFlow<NotificationDetailUiState> = _uiState.asStateFlow()

    fun load(recordId: Long, locateKey: String?) {
        if (!_uiState.value.loading) return
        viewModelScope.launch {
            val record = when {
                recordId > 0L -> recordRepository.findById(recordId)
                !locateKey.isNullOrBlank() -> recordRepository.findByKey(locateKey)
                else -> null
            }
            // V3：拉取该 App 的现有规则（scope.packages 含此包名）
            val rules = if (record != null) {
                ruleRepository.getAll().filter { rule ->
                    record.packageName in rule.scope.packages ||
                        (rule.scope.type == com.local.notificationfilter.core.model.ScopeType.GLOBAL)
                }
            } else {
                emptyList()
            }
            _uiState.value = NotificationDetailUiState(
                loading = false,
                record = record,
                notFound = record == null,
                rulesForApp = rules,
            )
        }
    }
}
