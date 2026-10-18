package com.local.notificationfilter.ui.screens.test

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.local.notificationfilter.NfApplication
import com.local.notificationfilter.core.model.NotificationRecord
import com.local.notificationfilter.core.model.Rule
import kotlinx.coroutines.flow.first

/**
 * P10 规则测试 ViewModel：素材来源（快照 / 流水 / 手填）与规则全集由
 * [com.local.notificationfilter.ui.components.RuleTestPanel] 自理，这里只提供数据。
 */
class RuleTestViewModel(application: Application) : AndroidViewModel(application) {

    private val container = (application as NfApplication).container
    private val ruleRepository = container.ruleRepository
    private val recordRepository = container.recordRepository

    /** 当前生效规则全集（含停用的——试跑要能看出「被停用挡住」的效果）。 */
    suspend fun loadAllRules(): List<Rule> = ruleRepository.getAll()

    /** 流水素材：最近 100 条。 */
    suspend fun loadRecentRecords(): List<NotificationRecord> =
        recordRepository.observeRecent(100).first()
}
