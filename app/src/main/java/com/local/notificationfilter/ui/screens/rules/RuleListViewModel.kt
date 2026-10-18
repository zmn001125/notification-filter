package com.local.notificationfilter.ui.screens.rules

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.local.notificationfilter.NfApplication
import com.local.notificationfilter.core.model.Rule
import com.local.notificationfilter.core.transfer.RuleTransfer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** P5 列表状态：规则（已按 order 排序）+ 黑名单计数 + 各规则命中数（V3-2）。 */
data class RuleListUiState(
    val rules: List<Rule> = emptyList(),
    val blacklistCount: Int = 0,
    val hitCounts: Map<Long, Int> = emptyMap(),
    /** 一次性提示（导入导出结果等）；UI 用 Snackbar 展示后调 [RuleListViewModel.consumeHint] 清空。 */
    val hint: String? = null,
)

/**
 * P5 规则列表 ViewModel。
 *
 * 排序交互：拖动过程只改 [reorderOverride] 内存列表（界面立即响应），
 * 松手 [commitOrder] 才批量写库；写库后数据库流自然刷新，override 清除。
 */
class RuleListViewModel(application: Application) : AndroidViewModel(application) {

    private val container = (application as NfApplication).container
    private val ruleRepository = container.ruleRepository
    private val blacklistRepository = container.blacklistRepository
    private val recordRepository = container.recordRepository

    /** 拖动中的内存重排；null 表示以数据库流为准。 */
    private val reorderOverride = MutableStateFlow<List<Rule>?>(null)

    /** 最近一次数据库快照（拖动开始时作为基线）。 */
    private var latestRules: List<Rule> = emptyList()

    /** 一次性 Snackbar 提示（V5 需求⑦ 导入导出结果）；UI 展示后调 [consumeHint] 清空。 */
    private val hintFlow = MutableStateFlow<String?>(null)

    val uiState: StateFlow<RuleListUiState> = combine(
        ruleRepository.observeAll(),
        blacklistRepository.observeCount(),
        recordRepository.observeHitCounts(),
        reorderOverride,
        hintFlow,
    ) { rules, count, hitCounts, override, hint ->
        latestRules = rules.sortedWith(compareBy({ it.order }, { it.id }))
        RuleListUiState(
            rules = (override ?: latestRules),
            blacklistCount = count,
            hitCounts = hitCounts,
            hint = hint,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = RuleListUiState(),
    )

    /** 当前生效顺序（拖动中取 override，否则取库内快照）。 */
    fun currentList(): List<Rule> = reorderOverride.value ?: latestRules

    /** 拖动交换：仅改内存，不写库。 */
    fun move(from: Int, to: Int) {
        val current = currentList()
        if (from == to || from !in current.indices || to !in current.indices) return
        val mutable = current.toMutableList()
        val item = mutable.removeAt(from)
        mutable.add(to, item)
        reorderOverride.value = mutable
    }

    /** 松手提交：批量持久化新 order 并退出 override 态。 */
    fun commitOrder() {
        val override = reorderOverride.value ?: return
        reorderOverride.value = null
        viewModelScope.launch {
            ruleRepository.updateOrders(override.map { it.id })
        }
    }

    /** 列表页启停开关（即时持久化）。 */
    fun toggleEnabled(rule: Rule) {
        viewModelScope.launch {
            ruleRepository.save(rule.copy(enabled = !rule.enabled))
        }
    }

    fun delete(rule: Rule) {
        viewModelScope.launch { ruleRepository.delete(rule.id) }
    }

    /**
     * V4-6：复制规则——复制出一条同名副本（名称加「（副本）」后缀），
     * id 归零由 Repository 分配新 id，order 排在原规则之后，其余配置（匹配条件 /
     * 作用范围 / 动作 / 设备状态）原样照搬。
     *
     * 走 `RuleRepository.insertAt` 而非 save：save 会把新增规则的 order 覆盖成
     * 队尾（max + 1），副本会跑到列表末尾；insertAt 会先给目标位置腾空位，
     * 用户看到的就是「副本紧跟原规则下方」。
     */
    fun duplicate(rule: Rule) {
        viewModelScope.launch {
            val copyName = buildString {
                append(rule.name.ifBlank { "未命名规则" })
                append("（副本）")
            }
            ruleRepository.insertAt(
                rule.copy(
                    id = 0L,
                    name = copyName,
                    order = rule.order + 1,
                ),
            )
        }
    }

    // ==================== V5 需求⑦：规则导入导出 ====================

    /** 导出全部规则为封套 JSON；列表为空时提示并返回 null（UI 不弹导出方式选择）。 */
    fun exportAllJson(): String? {
        val rules = currentList()
        if (rules.isEmpty()) {
            showHint("当前没有规则可导出")
            return null
        }
        return RuleTransfer.export(rules)
    }

    /** 导出单条规则为封套 JSON（rules 只含一条）。 */
    fun exportRuleJson(rule: Rule): String = RuleTransfer.export(rule)

    /**
     * 统一导入入口（剪贴板文本 / 文件文本都走这里）：解析封套 → 逐条迁移兼容 →
     * 原样批量插入（不加后缀、不去重，id / order 由 Repository 重建到列表末尾）。
     */
    fun importFromText(text: String) {
        val result = RuleTransfer.import(text)
        result.errorMessage?.let {
            showHint("导入失败：$it")
            return
        }
        if (result.rules.isEmpty()) {
            showHint(
                if (result.skippedCount > 0) "没有可导入的规则（${result.skippedCount} 条格式不符已跳过）"
                else "文件中没有规则",
            )
            return
        }
        viewModelScope.launch {
            val inserted = ruleRepository.importRules(result.rules)
            showHint(buildString {
                append("成功导入 $inserted 条")
                if (result.skippedCount > 0) append("，跳过 ${result.skippedCount} 条")
            })
        }
    }

    /** UI 侧事件（已复制到剪贴板 / 文件已保存等）统一走 Snackbar 提示。 */
    fun showHint(message: String) {
        hintFlow.value = message
    }

    /** 一次性提示已被 UI 展示（Snackbar 弹出后调用）。 */
    fun consumeHint() {
        hintFlow.value = null
    }
}
