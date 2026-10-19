package com.local.notificationfilter.ui.screens.rules

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.local.notificationfilter.NfApplication
import com.local.notificationfilter.core.aggregate.AggregationRenderer
import com.local.notificationfilter.core.aggregate.ItemExtractor
import com.local.notificationfilter.core.ruleengine.RegexCompat
import com.local.notificationfilter.core.model.AggregationConfig
import com.local.notificationfilter.core.model.AggregationItem
import com.local.notificationfilter.core.model.AggregationOutput
import com.local.notificationfilter.core.model.ChargeState
import com.local.notificationfilter.core.model.DanmakuStyle
import com.local.notificationfilter.core.model.DeviceStateCondition
import com.local.notificationfilter.core.model.EscapeCodec
import com.local.notificationfilter.core.model.ExtractorConfig
import com.local.notificationfilter.core.model.ExtrasKeys
import com.local.notificationfilter.core.model.FieldRef
import com.local.notificationfilter.core.model.FloatStyle
import com.local.notificationfilter.core.model.GroupMatchMode
import com.local.notificationfilter.core.model.KeywordGroup
import com.local.notificationfilter.core.model.KeywordGroupsConfig
import com.local.notificationfilter.core.model.MatchField
import com.local.notificationfilter.core.model.MatchMode
import com.local.notificationfilter.core.model.MetaFieldKind
import com.local.notificationfilter.core.model.NotificationRecord
import com.local.notificationfilter.core.model.NotificationSnapshot
import com.local.notificationfilter.core.model.OverlayTrigger
import com.local.notificationfilter.core.model.OverlayTriggerConfig
import com.local.notificationfilter.core.model.OverlayTriggerKind
import com.local.notificationfilter.core.model.Placeholders
import com.local.notificationfilter.core.model.RegexConfig
import com.local.notificationfilter.core.model.RuleGroups
import com.local.notificationfilter.core.model.ReplaceConfig
import com.local.notificationfilter.core.model.RewriteMode
import com.local.notificationfilter.core.model.Rule
import com.local.notificationfilter.core.model.RuleActionConfig
import com.local.notificationfilter.core.model.RuleConditionDefaults
import com.local.notificationfilter.core.model.RuleScope
import com.local.notificationfilter.core.model.ScreenState
import com.local.notificationfilter.core.model.ScopeType
import com.local.notificationfilter.core.model.SnoozeCalculator
import com.local.notificationfilter.core.model.SnoozeConfig
import com.local.notificationfilter.core.model.SnoozePresets
import com.local.notificationfilter.core.model.TimeWindow
import com.local.notificationfilter.core.model.Weekday
import com.local.notificationfilter.data.repo.extrasPairs
import com.local.notificationfilter.service.NfNotificationListener
import com.local.notificationfilter.util.AppUtils
import com.local.notificationfilter.util.NfLog
import com.local.notificationfilter.util.PermissionChecker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ==================== V7 需求 8：聚合编辑器的表单类型 ====================

/**
 * 单个提取器的**表单态**（V7 需求 8）。
 *
 * 为什么不直接用 [ExtractorConfig]：分隔符在模型里存的是**已解析的真实字符**
 * （`EscapeCodec.decode` 之后），而界面上要显示 `\n` 这种转义写法（用户可直接回车，
 * 也可输入 `\n` 字面量）。表单态保留**界面原文** [fieldSeparatorRaw]，
 * 保存时才 [EscapeCodec.decode]，回显时 [EscapeCodec.encode]——
 * 这样 `decode(encode(x)) == x` 恒成立，`toFormState` 才能做到无损。
 *
 * [fieldIds] 是 **List 而非 Set**：顺序即多字段拼接顺序（需求 8 明确）。
 * 用 [FieldRef.id] 存储（`FieldRef` 未标 @Serializable，不能直接进模型）。
 */
data class ExtractorForm(
    /** 列表号 1..9，稳定不变；对应模板占位符 `{itemsN}`。 */
    val listIndex: Int = 1,
    /** 列表名（仅 UI 展示）。 */
    val name: String = "提取器",
    /** 参与提取的字段（顺序 = 拼接顺序）。 */
    val fieldIds: List<String> = listOf(MatchField.TEXT.name),
    /** 字段拼接符的界面原文（可能是 `\n` 字面量）。 */
    val fieldSeparatorRaw: String = "\\n",
    /** 正则（空 = 该提取器未配置，不参与提取）。 */
    val pattern: String = "",
    val ignoreCase: Boolean = true,
    /** 捕获组拼装格式（留空 = `$0`）。 */
    val captureFormat: String = "\\$0",
    val dedupe: Boolean = true,
    /** 条数上限（1..50）。 */
    val limit: Int = 10,
    val enabled: Boolean = true,
) {
    /** [fieldIds] 的强类型视图（坏 id 由 `FieldRef.fromId` 兜底，不抛异常）。 */
    val listFields: List<FieldRef> get() = fieldIds.map { FieldRef.fromId(it) }

    /** 正则是否非法（非空且编译失败）；合法 / 空白返回 null。实时校验用。 */
    val patternError: String? get() = when {
        pattern.isBlank() -> null
        // 与提取器同一编译口径（都走 RegexCompat）；失败时给出**具体原因**，
        // 而不是一句「正则语法有误」——Android(ICU) 的坑（后行断言里用 * + {n,}、
        // 全角符号、\g{n} 等）用户根本猜不到。
        RegexCompat.compileOrNull(pattern, ignoreCase) == null ->
            RegexCompat.explain(pattern) ?: REGEX_ERROR
        else -> null
    }

    /** 是否可参与提取（与 `ExtractorConfig.isActive` 同口径）。 */
    val isActive: Boolean get() = enabled && pattern.isNotBlank() && limit > 0

    /** 表单 → 模型（分隔符在此解析转义码）。 */
    fun toConfig(): ExtractorConfig = ExtractorConfig(
        listIndex = listIndex,
        name = name,
        fieldIds = fieldIds,
        fieldSeparator = EscapeCodec.decode(fieldSeparatorRaw),
        pattern = pattern,
        ignoreCase = ignoreCase,
        captureFormat = captureFormat,
        dedupe = dedupe,
        limit = limit,
        enabled = enabled,
    )

    companion object {
        /** 非法正则的内联红字文案（架构 §5.2 逐字规定）。 */
        const val REGEX_ERROR: String = "正则语法有误"
    }
}

/** 模型 → 表单（分隔符在此反向转义，保证回显与保存一致）。 */
fun ExtractorConfig.toForm(): ExtractorForm = ExtractorForm(
    listIndex = listIndex,
    name = name,
    fieldIds = fieldIds,
    fieldSeparatorRaw = EscapeCodec.encode(fieldSeparator),
    pattern = pattern,
    ignoreCase = ignoreCase,
    captureFormat = captureFormat,
    dedupe = dedupe,
    limit = limit,
    enabled = enabled,
)

/** 单个提取器「实时试跑」的结果（UI 展示用；不落库）。 */
sealed interface ExtractorTrial {
    /** 抽到了条目（可能少于上限）。 */
    data class Hit(val items: List<String>) : ExtractorTrial

    /** 正则合法但一条都没匹配到。 */
    data object NoMatch : ExtractorTrial

    /** 失败（正则语法错误 / 无字段 / 运行异常）。 */
    data class Error(val message: String) : ExtractorTrial

    /** 还没有任何通知样本（流水为空）。 */
    data object NoSample : ExtractorTrial
}

/** P6 编辑页表单状态（五区：名称 / 条件 / 范围 / 动作 / 测试由面板自理）。 */
data class RuleEditUiState(
    val isNew: Boolean = true,
    val loaded: Boolean = false,
    /** 当前编辑的规则 id；0 = 尚未保存（「当前汇总」区块据此显示空态）。 */
    val ruleId: Long = 0L,
    val name: String = "",
    /** 匹配模式（单选八项；新建默认「所有内容」，PRD 默认档）。 */
    val matchMode: MatchMode = MatchMode.ALL_CONTENT,
    /** 被选字段（文本 / 元信息 / extras；V2T05 起经字段选择器维护）。 */
    val selectedFields: Set<FieldRef> = RuleConditionDefaults.FIELDS
        .map { FieldRef.Text(it) }.toSet(),
    /** 最近流水中出现过的 extras 键（V2T05 选择器数据源；打开选择器前加载）。 */
    val recentExtrasKeys: List<Pair<String, String>> = emptyList(),
    /** 最近 extras 键聚合加载中（选择器空态文案据此区分「加载中 / 流水为空」）。 */
    val recentKeysLoading: Boolean = false,
    /** extras 字段录入校验错误（选择器输入框旁内联展示；V2T05）。 */
    val extrasFieldError: String? = null,
    /** A 组关键词（所有文本模式共用）。 */
    val keywords: List<String> = listOf(""),
    /** B 组关键词（仅 TEXT_A_NOT_B 可见）。 */
    val excludeKeywords: List<String> = listOf(""),
    /** 分组匹配关键词组（V12.2，仅 TEXT_GROUPS 可见；组间 AND、组内按各组 mode 判定）。 */
    val keywordGroups: List<KeywordGroup> = listOf(KeywordGroup()),
    /** V13.0 本规则所属分组（可多个；空 = 未分组）。注意与 [keywordGroups]（匹配用）无关。 */
    val ruleGroups: List<String> = emptyList(),
    /** V13.0 库里已存在的分组名（供快速加入，避免重复手打）。 */
    val knownGroups: List<String> = emptyList(),
    /** V13.0 新建分组输入框的草稿。 */
    val groupDraft: String = "",
    /** 正则输入（仅 REGEX 模式）。 */
    val regexPattern: String = "",
    /** true = 不区分大小写（默认）。 */
    val regexIgnoreCase: Boolean = true,
    val scopeType: ScopeType = ScopeType.GLOBAL,
    val scopePackages: Set<String> = emptySet(),
    /**
     * 作用范围 App 的**展示名**：包名 → 「应用名」或「应用名（包名）」（重名时补包名）。
     *
     * V9.4：这些名字**必须**在 [Dispatchers.IO] 上解析 —— 解析链会走到
     * `AppUtils.appLabelWithPkgIfDuplicated` → `AppUtils.installedApps`，那是一次全量包枚举
     * （几百个包 × 逐个查 launchIntent，几百毫秒）。V9.4 之前编辑页在**组合期**直接调它，
     * 点「编辑」进页的一两帧正好被这次枚举顶住，真机表现就是「卡一下」。
     * 现在统一由 [RuleEditViewModel.refreshScopeLabels] 异步算好写回本字段，
     * 组合期只读这里的值（缺失时回落包名）。
     */
    val scopeAppNames: Map<String, String> = emptyMap(),
    val block: Boolean = false,
    /**
     * V10 任务 B：「静音消除」（[block] 的子选项）。
     *
     * 语义 = 命中时把该通知所属渠道静音（`setSound(null)` + 关闭振动）并消除通知。
     * Android 限制：无法只静音单条通知，会静音该渠道后续所有通知（UI 上有小字说明）。
     * 表单里独立保存，关掉「消除」时值仍然保留，重新勾选「消除」即恢复用户选择。
     */
    val silentRemove: Boolean = false,
    /**
     * V13.2：「也消除常驻通知」（[block] 的子选项）。
     *
     * 语义 = 命中带「进行中」标记的通知（音乐 / 下载 / 导航 / 前台服务等）时，
     * 用系统「打盹」让它休眠约 1 年（到期自动恢复，届时规则重新判定）。
     * 表单里独立保存，关掉「消除」时值仍然保留，重新勾选「消除」即恢复用户选择。
     */
    val blockOngoing: Boolean = false,
    val readAloud: Boolean = false,
    val snoozeOn: Boolean = false,
    val snoozePreset: String? = SnoozePresets.M30,
    val snoozeCustomMinutes: String = "",
    val snoozeCustomTime: String = "",
    /** V3 需求④：推迟时刻列表（HH:mm，主体字段；引擎取最近下一时刻）。 */
    val snoozeTimes: List<String> = emptyList(),
    val replaceOn: Boolean = false,
    val replaceMode: RewriteMode = RewriteMode.PLAIN_TEXT,
    /** 替换·标题模板（承接 V1 replaceTemplate；空 = 回落原标题/应用名）。 */
    val replaceTitleTemplate: String = "",
    /** 替换·正文模板（V2 新增；空 = 回落原正文/消息列表首条）。 */
    val replaceTextTemplate: String = "",
    val keepIcon: Boolean = true,
    val keepLargeImage: Boolean = false,
    val channelUseAppName: Boolean = true,
    // —— V4 需求 7-②/④：屏幕显示类动作 ——
    /** 弹幕显示开关与样式（开关关闭时样式不落库）。 */
    val danmakuOn: Boolean = false,
    val danmakuStyle: DanmakuStyle = DanmakuStyle(),
    /** 自定义位置悬浮开关与样式。 */
    val floatOn: Boolean = false,
    val floatStyle: FloatStyle = FloatStyle(),
    /** V3 需求⑦：设备状态生效条件（全空 = 无限制）。 */
    val deviceScreenStates: Set<ScreenState> = emptySet(),
    val deviceChargeStates: Set<ChargeState> = emptySet(),
    val deviceTimeWindows: List<TimeWindow> = emptyList(),
    val deviceWeekdays: Set<Weekday> = emptySet(),

    // ==================== V7 需求 8：内容提取与汇总 ====================

    /** 总开关（关 → 保存时 `aggregation = null`）。 */
    val aggregationOn: Boolean = false,
    /** 提取器列表（顺序即 UI 展示顺序；每条的 `listIndex` 才决定 `{itemsN}`）。 */
    val extractors: List<ExtractorForm> = emptyList(),
    /** 汇总标题模板（支持 `{items1}`..`{items9}` 与全部通知占位符）。 */
    val aggregationTitleTemplate: String = "",
    /** 汇总正文模板。 */
    val aggregationTextTemplate: String = "",
    /** 条目分隔符的**界面原文**（保存时 `EscapeCodec.decode`）。 */
    val aggregationItemSeparatorRaw: String = "\\n",
    /** 输出方式（可多选；空 = 只攒不输出）。 */
    val aggregationOutputs: Set<AggregationOutput> = emptySet(),
    /** 「当前汇总」实时数据：`listIndex` → 条目列表（`observeItems` 分组）。 */
    val aggregationItems: Map<Int, List<AggregationItem>> = emptyMap(),
    /** 各提取器的试跑结果：`listIndex` → 结果。 */
    val extractorTrials: Map<Int, ExtractorTrial> = emptyMap(),
    /** 试跑样本（最近一条流水还原的快照；null = 无样本）。 */
    val extractorSample: NotificationSnapshot? = null,
    /** 样本是否已尝试加载过（避免每次点击都查库）。 */
    val extractorSampleLoaded: Boolean = false,
    /** 列表号变更后的提示（模板里的 `{itemsN}` 不会自动跟着改）。 */
    val listIndexNotice: String? = null,

    // ==================== V7 需求 7：悬浮显示条件 ====================

    /** 卡片可见性条件（空 = 沿用 V6「命中即显示」）。 */
    val floatTriggers: List<OverlayTrigger> = emptyList(),
    /** 规则选择器数据源（`RULE_NOTIFICATION` 条件用，已排除自身）。 */
    val selectableRules: List<Rule> = emptyList(),
    /** 规则列表加载中。 */
    val rulesLoading: Boolean = false,

    val saving: Boolean = false,
    val error: String? = null,
    /**
     * 未知占位符确认（V2T07）：保存时发现未识别 token 时非空，UI 弹确认框；
     * 用户「仍要保存」→ [RuleEditViewModel.confirmSaveWithUnknownPlaceholders]；「返回修改」→
     * [RuleEditViewModel.dismissPlaceholderWarning]。
     */
    val placeholderWarning: List<String>? = null,
    /** 确认未知占位符后继续保存时暂存的「是否保存并测试」。 */
    val pendingAndTest: Boolean? = null,
    /** 「保存并测试」后自增，测试面板据此自动试跑。 */
    val testRunToken: Int = 0,
)

/** 保存结果事件（驱动导航 / 不清屏）。 */
sealed interface RuleEditEvent {
    /** 普通保存：返回列表。 */
    data object SavedBack : RuleEditEvent

    /** 保存并测试：留在本页，测试区自动试跑。 */
    data object SavedForTest : RuleEditEvent
}

/**
 * P6 规则编辑 ViewModel：表单状态 + 校验 + 保存；测试面板所需数据由此提供。
 *
 * 动作互斥约束（PRD）：消除与推迟/替换矛盾；消除 + 朗读合法；推迟 × 替换合法。
 *
 * V7（需求 7/8）：新增两块表单状态——聚合配置（[ExtractorForm] 列表 + 模板 + 输出）
 * 与悬浮条件（[OverlayTrigger] 列表）。两者都遵守「`toFormState` 无损」：
 * 新建 → 保存 → 重开，所有字段回显一致（转义码由 [EscapeCodec] 双向保证）。
 */
class RuleEditViewModel(application: Application) : AndroidViewModel(application) {

    private val container = (application as NfApplication).container
    private val ruleRepository = container.ruleRepository
    private val recordRepository = container.recordRepository

    /** T03 提供的聚合暂存仓库（`AppContainer.aggregationRepository`）。 */
    private val aggregationRepository = container.aggregationRepository

    private val _uiState = MutableStateFlow(RuleEditUiState())
    val uiState: StateFlow<RuleEditUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<RuleEditEvent>()
    val events: SharedFlow<RuleEditEvent> = _events.asSharedFlow()

    /** 已加载的原始规则（保存时保留 id/order/enabled）。 */
    private var loadedRule: Rule? = null

    /** 「当前汇总」实时流收集任务（规则切换 / 保存后重建）。 */
    private var itemsJob: Job? = null

    /** 作用范围 App 名解析任务（连续改选时取消上一次，避免旧结果覆盖新结果）。 */
    private var scopeLabelsJob: Job? = null

    /** 加载指定规则；ruleId <= 0 为新建。重复调用只加载一次。prefillPkg 用于从详情页建规预填 ONLY+包名。 */
    fun load(ruleId: Long, prefillPkg: String? = null) {
        if (_uiState.value.loaded) return
        viewModelScope.launch {
            val allRules = ruleRepository.getAll()
            val rule = if (ruleId > 0L) {
                allRules.firstOrNull { it.id == ruleId }
            } else {
                null
            }
            loadedRule = rule
            _uiState.value = if (rule == null) {
                // 新建：可选预填作用范围（从详情页「为该应用建规」入口）
                val base = RuleEditUiState(isNew = true, loaded = true)
                if (!prefillPkg.isNullOrBlank()) {
                    base.copy(
                        scopeType = ScopeType.ONLY,
                        scopePackages = setOf(prefillPkg),
                    )
                } else {
                    base
                }
            } else {
                rule.toFormState()
            }
            // V13.0：分组候选来自库里已出现的分组名（getAll 走内存缓存，成本可忽略）
            _uiState.update { it.copy(knownGroups = RuleGroups.distinctOf(allRules)) }
            // V9.4：应用名（含「重名补包名」）一律交给 IO 线程异步解析，
            // 此处不再同步查 PackageManager —— 否则首帧就被全量包枚举顶住。
            refreshScopeLabels()
            val id = _uiState.value.ruleId
            if (id > 0L) observeAggregation(id)
        }
    }

    // ==================== 表单更新：匹配条件 ====================

    fun setName(name: String) = _uiState.update { it.copy(name = name) }

    fun setMatchMode(mode: MatchMode) = _uiState.update { it.copy(matchMode = mode) }

    /** 勾选 / 取消一个被选字段（文本 / 元信息 / extras 统一入口）。 */
    fun toggleField(ref: FieldRef) = _uiState.update { s ->
        val next = if (ref in s.selectedFields) s.selectedFields - ref else s.selectedFields + ref
        s.copy(selectedFields = next)
    }

    /**
     * extras 字段录入（V2T05 字段选择器）：校验通过才加入 [RuleEditUiState.selectedFields]，
     * 不合法时把错误文案写入 [RuleEditUiState.extrasFieldError] 供输入框内联展示。
     * 校验口径（架构 §6.5）：非空 / 不含空白 / ≤128 字节（UTF-8）/ 不与已选重复。
     */
    fun addExtrasField(key: String) {
        val s = _uiState.value
        val k = key.trim()
        val error = validateExtrasKey(k, s.selectedFields)
        if (error != null) {
            _uiState.update { it.copy(extrasFieldError = error) }
        } else {
            _uiState.update {
                it.copy(
                    selectedFields = it.selectedFields + FieldRef.Extras(k),
                    extrasFieldError = null,
                )
            }
        }
    }

    /** extras 键名校验；合法返回 null，否则返回错误文案。 */
    private fun validateExtrasKey(key: String, selected: Set<FieldRef>): String? = when {
        key.isEmpty() -> "键名不能为空"
        key.any { it.isWhitespace() } -> "键名不能包含空白字符"
        key.toByteArray(Charsets.UTF_8).size > EXTRAS_KEY_MAX_BYTES -> "键名长度不能超过 128 字节"
        selected.any { it is FieldRef.Extras && it.key == key } -> "该字段已在已选列表中"
        else -> null
    }

    fun consumeExtrasFieldError() = _uiState.update { it.copy(extrasFieldError = null) }

    /** 打开字段选择器前加载「最近出现过的 extras 键」（一次性聚合，架构 §6.5）。 */
    fun loadRecentExtrasKeys() {
        if (_uiState.value.recentKeysLoading) return
        viewModelScope.launch {
            _uiState.update { it.copy(recentKeysLoading = true) }
            val keys = runCatching { recordRepository.recentExtrasKeys() }
                .getOrDefault(emptyList())
            _uiState.update { it.copy(recentExtrasKeys = keys, recentKeysLoading = false) }
        }
    }

    fun removeField(ref: FieldRef) = _uiState.update { s ->
        s.copy(selectedFields = s.selectedFields - ref)
    }

    /** 快捷组合（文本字段部分）：默认三字段 / 全部文本字段；元信息选择保留。 */
    fun applyTextFieldsQuick(fields: Set<MatchField>) = _uiState.update { s ->
        val nonText = s.selectedFields.filter { it !is FieldRef.Text }.toSet()
        s.copy(selectedFields = nonText + fields.map { FieldRef.Text(it) })
    }

    // —— A 组关键词 ——

    fun addKeyword() = _uiState.update { it.copy(keywords = it.keywords + "") }

    fun updateKeyword(index: Int, keyword: String) = _uiState.update { s ->
        if (index !in s.keywords.indices) s
        else s.copy(keywords = s.keywords.mapIndexed { i, v -> if (i == index) keyword else v })
    }

    fun removeKeyword(index: Int) = _uiState.update { s ->
        if (s.keywords.size <= 1) s
        else s.copy(keywords = s.keywords.filterIndexed { i, _ -> i != index })
    }

    // —— B 组关键词（仅 TEXT_A_NOT_B）——

    fun addExcludeKeyword() = _uiState.update { it.copy(excludeKeywords = it.excludeKeywords + "") }

    fun updateExcludeKeyword(index: Int, keyword: String) = _uiState.update { s ->
        if (index !in s.excludeKeywords.indices) s
        else s.copy(
            excludeKeywords = s.excludeKeywords.mapIndexed { i, v -> if (i == index) keyword else v },
        )
    }

    fun removeExcludeKeyword(index: Int) = _uiState.update { s ->
        if (s.excludeKeywords.size <= 1) s
        else s.copy(excludeKeywords = s.excludeKeywords.filterIndexed { i, _ -> i != index })
    }

    // —— 分组匹配关键词组（V12.2，仅 TEXT_GROUPS）——

    /** 改某组内某个下标的词。 */
    fun updateGroupWord(groupIndex: Int, wordIndex: Int, word: String) = _uiState.update { s ->
        if (groupIndex !in s.keywordGroups.indices) s
        else s.copy(
            keywordGroups = s.keywordGroups.mapIndexed { gi, g ->
                if (gi != groupIndex) {
                    g
                } else if (wordIndex !in g.words.indices) {
                    g
                } else {
                    g.copy(words = g.words.mapIndexed { wi, v -> if (wi == wordIndex) word else v })
                }
            },
        )
    }

    /** 某组末尾追加一个空词行（提交时由 KeywordEditor 回调填充）。 */
    fun addGroupWord(groupIndex: Int) = _uiState.update { s ->
        if (groupIndex !in s.keywordGroups.indices) s
        else s.copy(
            keywordGroups = s.keywordGroups.mapIndexed { gi, g ->
                if (gi == groupIndex) g.copy(words = g.words + "") else g
            },
        )
    }

    /** 删某组内某个下标的词（该组至少留 1 行，与 A/B 组关键词同一口径）。 */
    fun removeGroupWord(groupIndex: Int, wordIndex: Int) = _uiState.update { s ->
        if (groupIndex !in s.keywordGroups.indices) s
        else s.copy(
            keywordGroups = s.keywordGroups.mapIndexed { gi, g ->
                if (gi != groupIndex || g.words.size <= 1) {
                    g
                } else {
                    g.copy(words = g.words.filterIndexed { wi, _ -> wi != wordIndex })
                }
            },
        )
    }

    // —— 所属分组（V13.0，归类用，不参与匹配）——

    /** 新建分组输入框草稿。 */
    fun setGroupDraft(text: String) = _uiState.update {
        it.copy(groupDraft = text.take(RuleGroups.MAX_NAME_LENGTH))
    }

    /** 加入一个分组（去空白、去重；已存在则忽略）。 */
    fun addRuleGroup(name: String) = _uiState.update { s ->
        val normalized = RuleGroups.normalize(s.ruleGroups + name)
        if (normalized == s.ruleGroups) s else s.copy(ruleGroups = normalized, groupDraft = "")
    }

    /** 移除一个分组。 */
    fun removeRuleGroup(name: String) = _uiState.update { s ->
        s.copy(ruleGroups = s.ruleGroups - name)
    }

    /** 设某组组内匹配方式（任一出现 / 全部出现）。 */
    fun setGroupMode(groupIndex: Int, mode: GroupMatchMode) = _uiState.update { s ->
        if (groupIndex !in s.keywordGroups.indices) s
        else s.copy(
            keywordGroups = s.keywordGroups.mapIndexed { gi, g ->
                if (gi == groupIndex) g.copy(mode = mode) else g
            },
        )
    }

    /** 追加一组（新组默认「任一出现」、一个空词行）。 */
    fun addGroup() = _uiState.update { s ->
        s.copy(keywordGroups = s.keywordGroups + KeywordGroup())
    }

    /** 删除整组（至少留 1 组，避免空配置无法下手）。 */
    fun removeGroup(index: Int) = _uiState.update { s ->
        if (s.keywordGroups.size <= 1) s
        else s.copy(keywordGroups = s.keywordGroups.filterIndexed { i, _ -> i != index })
    }

    // —— 正则（仅 REGEX）——

    fun setRegexPattern(pattern: String) = _uiState.update { it.copy(regexPattern = pattern) }

    fun toggleRegexCase() = _uiState.update { it.copy(regexIgnoreCase = !it.regexIgnoreCase) }

    fun setScopeType(type: ScopeType) = _uiState.update { it.copy(scopeType = type) }

    /**
     * 勾选的作用范围包名集合。
     *
     * V9.4：展示名**不在**这里同步解析（旧实现逐个调 `AppUtils.appNameOf`，
     * 20 个包 = 20 次主线程 Binder IPC），改由 [refreshScopeLabels] 在 IO 线程异步回填。
     */
    fun setScopePackages(packages: Set<String>) {
        _uiState.update { it.copy(scopePackages = packages) }
        refreshScopeLabels()
    }

    /**
     * 在 IO 线程解析 [RuleEditUiState.scopePackages] 的展示名，写回 [RuleEditUiState.scopeAppNames]。
     *
     * 三段式设计（对应「点编辑不卡 + 显示不退化」两个目标）：
     * 1. 同步填一份「包名 → 包名」占位 → chip 立刻有字可显，不会先空白再跳变；
     * 2. 真正的「应用名（重名时补包名）」在 [Dispatchers.IO] 上解析（[AppUtils.installedApps]
     *    的全量包枚举只发生在这里，且枚举结果仍走 AppUtils 内部的 5 分钟缓存）；
     * 3. 结果按包名合并回 state，只保留仍在 [RuleEditUiState.scopePackages] 里的条目
     *    （期间用户又改选了 → 丢弃过期结果）。
     */
    private fun refreshScopeLabels() {
        scopeLabelsJob?.cancel()
        scopeLabelsJob = null
        val packages = _uiState.value.scopePackages
        _uiState.update { s ->
            s.copy(
                scopeAppNames = s.scopePackages.associateWith { pkg ->
                    s.scopeAppNames[pkg] ?: pkg
                },
            )
        }
        if (packages.isEmpty()) return
        scopeLabelsJob = viewModelScope.launch {
            val app: Application = getApplication()
            val resolved = withContext(Dispatchers.IO) {
                packages.associateWith { pkg ->
                    runCatching { AppUtils.appLabelWithPkgIfDuplicated(app, pkg) }
                        .getOrDefault(pkg)
                }
            }
            _uiState.update { s ->
                s.copy(
                    scopeAppNames = s.scopePackages.associateWith { pkg ->
                        resolved[pkg] ?: s.scopeAppNames[pkg] ?: pkg
                    },
                )
            }
        }
    }

    // ==================== 动作（互斥约束在此落地） ====================

    /** 勾选/取消「消除」：与推迟/替换互斥——选中即清退二者，取消则释放置灰。 */
    fun toggleBlock() = _uiState.update { s ->
        val nextBlock = !s.block
        // V10：静音消除（[RuleEditUiState.silentRemove]）在这里**刻意不动**——它是「消除」的
        // 子选项，与弹幕/悬浮样式同策略：误点一次开关不该丢掉已调好的配置；
        // 真正落库由 buildRuleFromForm 的 `s.block && s.silentRemove` 把关。
        // V13.2：常驻通知子选项（[RuleEditUiState.blockOngoing]）同策略，也刻意不动。
        s.copy(
            block = nextBlock,
            snoozeOn = if (nextBlock) false else s.snoozeOn,
            replaceOn = if (nextBlock) false else s.replaceOn,
        )
    }

    /**
     * V10 任务 B：「静音消除」开关（只在「消除」已勾选时可见）。
     *
     * 只翻布尔、不做副作用：渠道静音的留底/恢复全部由运行时 `ChannelSilencer` 负责，
     * 编辑页不碰系统渠道（用户可能只是打开看看，没有保存就不该改系统设置）。
     */
    fun toggleSilentRemove() = _uiState.update { s -> s.copy(silentRemove = !s.silentRemove) }

    /**
     * V13.2：「也消除常驻通知」开关（只在「消除」已勾选时可见）。
     *
     * 只翻布尔、不做副作用——与 [toggleSilentRemove] 同策略：编辑页不碰系统，
     * 用户可能只是打开看看，没有保存就不该有任何行为变化。
     */
    fun toggleBlockOngoing() = _uiState.update { s -> s.copy(blockOngoing = !s.blockOngoing) }

    fun toggleReadAloud() = _uiState.update { it.copy(readAloud = !it.readAloud) }

    /** 勾选推迟/替换：消除已选时置灰不可点（由 UI 拦截），此处只做状态翻转。 */
    fun toggleSnooze() = _uiState.update { s ->
        val on = !s.snoozeOn
        s.copy(snoozeOn = on, snoozePreset = if (on && s.snoozePreset == null) SnoozePresets.M30 else s.snoozePreset)
    }

    fun toggleReplace() = _uiState.update { it.copy(replaceOn = !it.replaceOn) }

    fun setSnoozePreset(preset: String) = _uiState.update { it.copy(snoozePreset = preset) }

    fun setSnoozeCustomMinutes(text: String) =
        _uiState.update { it.copy(snoozeCustomMinutes = text.filter { c -> c.isDigit() }.take(4)) }

    fun setSnoozeCustomTime(text: String) =
        _uiState.update { it.copy(snoozeCustomTime = text.take(5)) }

    fun setReplaceMode(mode: RewriteMode) = _uiState.update { it.copy(replaceMode = mode) }

    fun setReplaceTitleTemplate(text: String) =
        _uiState.update { it.copy(replaceTitleTemplate = text) }

    fun setReplaceTextTemplate(text: String) =
        _uiState.update { it.copy(replaceTextTemplate = text) }

    fun toggleKeepIcon() = _uiState.update { it.copy(keepIcon = !it.keepIcon) }

    fun toggleKeepLargeImage() = _uiState.update { it.copy(keepLargeImage = !it.keepLargeImage) }

    fun toggleChannelUseAppName() = _uiState.update { it.copy(channelUseAppName = !it.channelUseAppName) }

    // ==================== V4 屏幕显示类动作 ====================

    /**
     * 弹幕开关。
     *
     * 只翻布尔、不动 [RuleEditUiState.danmakuStyle]：样式落库由 `buildRuleOrError`
     * 的 `takeIf { danmakuOn }` 把关，关闭时样式对象本来就不会写入；反之若在这里重置
     * 成默认值，用户误点一次开关就会丢掉调好的全部参数，重新打开也无法恢复。
     */
    fun toggleDanmaku() = _uiState.update { s -> s.copy(danmakuOn = !s.danmakuOn) }

    fun updateDanmakuStyle(style: DanmakuStyle) = _uiState.update { it.copy(danmakuStyle = style) }

    /** 自定义位置悬浮开关（同 [toggleDanmaku]：只翻开关，保留已调样式）。 */
    fun toggleFloat() = _uiState.update { s -> s.copy(floatOn = !s.floatOn) }

    fun updateFloatStyle(style: FloatStyle) = _uiState.update { it.copy(floatStyle = style) }

    /**
     * 「常驻（不自动消失）」开关（架构 §5.5）：等价于把 `displaySeconds` 置 0，
     * 复用现有字段，不新增模型。开启后卡片不自动消失（仍可被条件 / 首页菜单撤下）。
     */
    fun toggleFloatSticky() = _uiState.update { s ->
        s.copy(
            floatStyle = if (s.floatStyle.displaySeconds > 0f) {
                s.floatStyle.copy(displaySeconds = 0f)
            } else {
                s.floatStyle.copy(displaySeconds = 8f)
            },
        )
    }

    // ==================== V3 需求④：推迟时刻列表 ====================

    /** 添加一个推迟时刻（HH:mm 格式校验通过才加入）。 */
    fun addSnoozeTime(hhmm: String) {
        val t = hhmm.trim()
        if (SnoozeCalculator.parseHHmm(t) == null) return
        _uiState.update { s ->
            if (s.snoozeTimes.contains(t)) s
            else s.copy(snoozeTimes = s.snoozeTimes + t)
        }
    }

    /** 删除指定索引的推迟时刻。 */
    fun removeSnoozeTime(index: Int) = _uiState.update { s ->
        if (index !in s.snoozeTimes.indices) s
        else s.copy(snoozeTimes = s.snoozeTimes.filterIndexed { i, _ -> i != index })
    }

    // ==================== V3 需求⑦：设备状态生效条件 ====================

    /**
     * 整体替换设备状态条件（V7 起设备状态编辑抽成 `DeviceStateFields` 组件，
     * 规则级与条件级共用同一份控件，只回调一个完整 [DeviceStateCondition]）。
     */
    fun setDeviceState(condition: DeviceStateCondition) = _uiState.update { s ->
        s.copy(
            deviceScreenStates = condition.screenStates,
            deviceChargeStates = condition.chargeStates,
            deviceTimeWindows = condition.timeWindows,
            deviceWeekdays = condition.weekdays,
        )
    }

    fun consumeError() = _uiState.update { it.copy(error = null) }

    // ==================== V7 需求 8：内容提取与汇总 ====================

    /** 总开关；首次开启时自动补一个 1 号提取器（避免空白列表不知从何下手）。 */
    fun toggleAggregation() = _uiState.update { s ->
        val on = !s.aggregationOn
        val forms = if (on && s.extractors.isEmpty()) listOf(ExtractorForm(listIndex = 1)) else s.extractors
        s.copy(aggregationOn = on, extractors = forms)
    }

    /** 添加提取器：列表号取当前**未占用的最小正整数**（1..9，与 `{itemsN}` 对应）。 */
    fun addExtractor() {
        val s = _uiState.value
        val used = s.extractors.map { it.listIndex }.toSet()
        val free = (1..MAX_LIST_INDEX).firstOrNull { it !in used }
        if (free == null) {
            _uiState.update { it.copy(error = "最多 $MAX_LIST_INDEX 个提取器（对应 {items1}..{items$MAX_LIST_INDEX}）") }
            return
        }
        _uiState.update { it.copy(extractors = it.extractors + ExtractorForm(listIndex = free)) }
    }

    /** 就地修改某个提取器（按 `listIndex` 定位，`listIndex` 本身不允许在此改）。 */
    fun updateExtractor(listIndex: Int, transform: (ExtractorForm) -> ExtractorForm) =
        _uiState.update { s ->
            if (s.extractors.none { it.listIndex == listIndex }) {
                s
            } else {
                s.copy(
                    extractors = s.extractors.map {
                        if (it.listIndex == listIndex) transform(it).copy(listIndex = listIndex) else it
                    },
                )
            }
        }

    /**
     * 改列表号（`{itemsN}` 的 N）。已被占用则拒绝并提示；
     * 成功时给出「模板里的 `{itemsN}` 不会自动跟着变」的提示（架构 §5.2 要求）。
     */
    fun changeExtractorListIndex(from: Int, to: Int) {
        val target = to.coerceIn(1, MAX_LIST_INDEX)
        _uiState.update { s ->
            when {
                target == from -> s
                s.extractors.any { it.listIndex == target } -> s.copy(
                    listIndexNotice = "列表号 $target 已被占用，请换一个",
                )

                else -> s.copy(
                    extractors = s.extractors.map {
                        if (it.listIndex == from) it.copy(listIndex = target) else it
                    },
                    // 旧号的条目不会跟着迁移（仓库无「改号」接口），一并说明
                    listIndexNotice = "列表号已改为 $target：模板里的 {items$from} 不会自动变成 " +
                        "{items$target}，请手动修改；原 $from 号已攒的条目仍留在库里（可用「当前汇总」清空）",
                    extractorTrials = s.extractorTrials.minus(from),
                )
            }
        }
    }

    fun consumeListIndexNotice() = _uiState.update { it.copy(listIndexNotice = null) }

    /** 勾选 / 取消提取器的参与字段（追加到末尾 = 拼接顺序的最后一位）。 */
    fun toggleExtractorField(listIndex: Int, ref: FieldRef) = updateExtractor(listIndex) { f ->
        if (ref.id in f.fieldIds) f.copy(fieldIds = f.fieldIds - ref.id)
        else f.copy(fieldIds = f.fieldIds + ref.id)
    }

    /** 把某个参与字段前移一位（点击 chip 即前移，用于调整拼接顺序）。 */
    fun moveExtractorFieldEarlier(listIndex: Int, ref: FieldRef) = updateExtractor(listIndex) { f ->
        val idx = f.fieldIds.indexOf(ref.id)
        if (idx <= 0) {
            f
        } else {
            val next = f.fieldIds.toMutableList()
            next.add(idx - 1, next.removeAt(idx))
            f.copy(fieldIds = next)
        }
    }

    /** 提取器内新增 extras 字段（键名校验与规则级字段同口径）。 */
    fun addExtractorExtrasField(listIndex: Int, key: String) {
        val form = _uiState.value.extractors.firstOrNull { it.listIndex == listIndex } ?: return
        val k = key.trim()
        val error = validateExtrasKey(k, form.listFields.toSet())
        if (error != null) {
            _uiState.update { it.copy(extrasFieldError = error) }
            return
        }
        updateExtractor(listIndex) { it.copy(fieldIds = it.fieldIds + FieldRef.Extras(k).id) }
        _uiState.update { it.copy(extrasFieldError = null) }
    }

    /** 删除提取器（已攒条目一并清空，UI 需二次确认）。 */
    fun removeExtractor(listIndex: Int) {
        _uiState.update { s ->
            s.copy(
                extractors = s.extractors.filterNot { it.listIndex == listIndex },
                extractorTrials = s.extractorTrials - listIndex,
                aggregationItems = s.aggregationItems - listIndex,
            )
        }
        clearAggregationList(listIndex)
    }

    fun setAggregationTitleTemplate(text: String) =
        _uiState.update { it.copy(aggregationTitleTemplate = text) }

    fun setAggregationTextTemplate(text: String) =
        _uiState.update { it.copy(aggregationTextTemplate = text) }

    fun setAggregationItemSeparator(raw: String) =
        _uiState.update { it.copy(aggregationItemSeparatorRaw = raw) }

    /** 勾选 / 取消一种输出方式（SNOOZE / FLOAT，可多选）。 */
    fun toggleAggregationOutput(output: AggregationOutput) = _uiState.update { s ->
        val next = if (output in s.aggregationOutputs) s.aggregationOutputs - output
        else s.aggregationOutputs + output
        s.copy(aggregationOutputs = next)
    }

    /**
     * 实时试跑一个提取器：用**最近一条流水**还原的快照跑一遍
     * [ItemExtractor.extractAll]（T02 纯逻辑，参数序为 `(snapshot, cfg)`），
     * 结果写进 [RuleEditUiState.extractorTrials]。
     * 没有样本时置 [ExtractorTrial.NoSample]（UI 文案「还没有通知样本，命中一次后可预览」）。
     */
    fun testExtractor(listIndex: Int) {
        viewModelScope.launch {
            ensureSample()
            val form = _uiState.value.extractors.firstOrNull { it.listIndex == listIndex } ?: return@launch
            val sample = _uiState.value.extractorSample
            val trial: ExtractorTrial = when {
                sample == null -> ExtractorTrial.NoSample
                form.pattern.isBlank() -> ExtractorTrial.Error("请先填写正则表达式")
                form.fieldIds.isEmpty() -> ExtractorTrial.Error("请先选择参与字段")
                !form.enabled -> ExtractorTrial.Error("这个提取器已停用（打开「启用这个提取器」再试）")
                else -> runCatching { ItemExtractor.extractAll(sample, form.toConfig()) }
                    .fold(
                        onSuccess = { items ->
                            if (items.isEmpty()) ExtractorTrial.NoMatch else ExtractorTrial.Hit(items)
                        },
                        onFailure = { e ->
                            ExtractorTrial.Error(e.message ?: ExtractorForm.REGEX_ERROR)
                        },
                    )
            }
            _uiState.update { it.copy(extractorTrials = it.extractorTrials + (listIndex to trial)) }
        }
    }

    /** 加载一次试跑样本（最近一条流水 → 快照）；已加载过则直接返回。 */
    private suspend fun ensureSample() {
        if (_uiState.value.extractorSampleLoaded) return
        val record = runCatching { recordRepository.observeRecent(1).first().firstOrNull() }
            .getOrNull()
        _uiState.update {
            it.copy(extractorSample = record?.toSampleSnapshot(), extractorSampleLoaded = true)
        }
    }

    /**
     * 「当前汇总」：删除单条（T03 `AggregationRepository`）。
     *
     * ⚠️ 签名对齐点：任务书给的 `deleteItem(id: Long)` 需要行主键，而 T01 的
     * [AggregationItem] **没有 id 字段**（只有 ruleId / listIndex / value / createdAt），
     * 故这里调用按三元组删除的 `deleteByValue(ruleId, listIndex, value)`
     * （已与 eng-v7-t03 对齐请求；若最终是 `deleteItem(id)` + 模型加 id，只需改这一行）。
     */
    fun deleteAggregationItem(item: AggregationItem) {
        viewModelScope.launch {
            runCatching {
                aggregationRepository.deleteByValue(item.ruleId, item.listIndex, item.value)
            }.onFailure { e ->
                _uiState.update { it.copy(error = "删除汇总条目失败：${e.message}") }
            }
        }
    }

    /** 「当前汇总」：清空某个列表（`listIndex == null` 时清空该规则全部列表）。 */
    fun clearAggregationList(listIndex: Int?) {
        val ruleId = _uiState.value.ruleId
        if (ruleId <= 0L) return
        viewModelScope.launch {
            runCatching { aggregationRepository.clear(ruleId, listIndex) }
                .onFailure { e -> _uiState.update { it.copy(error = "清空汇总失败：${e.message}") } }
        }
    }

    /** 订阅该规则的聚合条目（实时流 → 按 `listIndex` 分组）。 */
    private fun observeAggregation(ruleId: Long) {
        itemsJob?.cancel()
        itemsJob = viewModelScope.launch {
            runCatching {
                aggregationRepository.observeItems(ruleId).collect { items ->
                    _uiState.update { it.copy(aggregationItems = items.groupBy { i -> i.listIndex }) }
                }
            }
        }
    }

    // ==================== V7 需求 7：悬浮显示条件 ====================

    /** 追加一条指定类型的条件（参数留空 = 未配齐，UI 会红字提示）。 */
    fun addTrigger(kind: OverlayTriggerKind) = _uiState.update { s ->
        s.copy(floatTriggers = s.floatTriggers + OverlayTrigger(kind = kind.name))
    }

    /** 就地修改一条条件（按列表下标定位）。 */
    fun updateTrigger(index: Int, transform: (OverlayTrigger) -> OverlayTrigger) =
        _uiState.update { s ->
            if (index !in s.floatTriggers.indices) s
            else s.copy(
                floatTriggers = s.floatTriggers.mapIndexed { i, t -> if (i == index) transform(t) else t },
            )
        }

    fun removeTrigger(index: Int) = _uiState.update { s ->
        if (index !in s.floatTriggers.indices) s
        else s.copy(floatTriggers = s.floatTriggers.filterIndexed { i, _ -> i != index })
    }

    /** 加载规则选择器数据源（排除当前编辑的规则自身）。 */
    fun loadSelectableRules() {
        if (_uiState.value.rulesLoading) return
        viewModelScope.launch {
            _uiState.update { it.copy(rulesLoading = true) }
            val selfId = _uiState.value.ruleId
            val all = runCatching { ruleRepository.getAll() }.getOrDefault(emptyList())
            _uiState.update {
                it.copy(
                    selectableRules = all.filter { r -> r.id != selfId },
                    rulesLoading = false,
                )
            }
        }
    }

    /**
     * 是否已授予无障碍服务（`FOREGROUND_APP` 条件的前置）。
     *
     * 复用 T04 的唯一判定入口 `PermissionChecker.isAccessibilityGranted`
     * （`enabled_accessibility_services` + `AccessibilityManager` 双通道），
     * 避免 UI 侧再写一份口径不同的检测。
     */
    fun accessibilityGranted(): Boolean =
        PermissionChecker.isAccessibilityGranted(getApplication())

    // ==================== 校验与保存 ====================

    /** 表单 → Rule；不合法返回错误文案（架构 §8 保存校验）。 */
    private fun buildRuleOrError(): Pair<Rule?, String?> {
        val s = _uiState.value
        if (s.scopeType != ScopeType.GLOBAL && s.scopePackages.isEmpty()) {
            return null to "请选择作用范围的 App"
        }
        if (s.selectedFields.isEmpty()) {
            return null to "请至少选择一个匹配字段"
        }

        // —— 模式参数校验（防御性：UI 已拦截，引擎亦兜底）——
        val keywords = s.keywords.map { it.trim() }.filter { it.isNotEmpty() }
        val excludeKeywords = s.excludeKeywords.map { it.trim() }.filter { it.isNotEmpty() }
        when (s.matchMode) {
            MatchMode.ALL_CONTENT -> Unit // 不要求关键词
            MatchMode.TEXT_ANY, MatchMode.TEXT_ALL,
            MatchMode.TEXT_NOT_ANY, MatchMode.TEXT_NOT_ALL,
                -> if (keywords.isEmpty()) return null to "请至少填写一个关键词"

            MatchMode.TEXT_A_NOT_B -> {
                if (keywords.isEmpty()) return null to "请至少填写 A 组关键词（要包含的）"
                if (excludeKeywords.isEmpty()) return null to "请至少填写 B 组关键词（要排除的）"
            }

            MatchMode.REGEX -> {
                if (s.regexPattern.isBlank()) return null to "请填写正则表达式"
                // V11.4：走 RegexCompat（含 Android ICU 的后行断言兼容改写），
                // 失败时把具体原因说清楚，别只丢一句「语法有误」。
                if (RegexCompat.compileOrNull(s.regexPattern, s.regexIgnoreCase) == null) {
                    return null to (
                        RegexCompat.explain(s.regexPattern) ?: "正则表达式有误，请检查语法"
                        )
                }
            }

            // V12.2 分组匹配：清理空词/空组后仍无有效分组 → 拦截（引擎侧同样兜底不命中）
            MatchMode.TEXT_GROUPS -> {
                val validGroups = s.keywordGroups
                    .map { g -> g.copy(words = g.words.map { it.trim() }.filter { it.isNotEmpty() }) }
                    .filter { it.words.isNotEmpty() }
                if (validGroups.isEmpty()) {
                    return null to "请至少填写一组关键词，且每组至少一个词"
                }
            }
        }

        // —— V7 需求 8：聚合校验（三条拦截之一/之二）——
        if (s.aggregationOn) {
            if (s.extractors.none { it.isActive }) {
                return null to "请至少配置一个可用的提取器（需填写正则表达式并启用）"
            }
            s.extractors.firstOrNull { it.patternError != null }?.let { bad ->
                // 非法正则：红字文案来自 ExtractorForm.REGEX_ERROR
                return null to "提取器「${bad.name}」的${ExtractorForm.REGEX_ERROR}，请修正后再保存"
            }
            if (AggregationOutput.SNOOZE in s.aggregationOutputs && !s.snoozeOn) {
                return null to "已启用「推迟」输出，请同时勾选上面的「推迟」动作"
            }
            if (AggregationOutput.FLOAT in s.aggregationOutputs && !s.floatOn) {
                return null to "已启用「常驻悬浮」输出，请同时勾选上面的「自定义位置悬浮」动作"
            }
        }

        // —— V7 需求 7：`FOREGROUND_APP` 条件需要无障碍服务（三条拦截之三）——
        val needsAccessibility = s.floatTriggers.any {
            it.kindEnum == OverlayTriggerKind.FOREGROUND_APP && it.isEffective
        }
        if (needsAccessibility && !accessibilityGranted()) {
            return null to "「打开了某个 App」条件需要无障碍服务，请先在系统设置中授权该服务"
        }

        // 推迟：开启但时间不合法 → 拦截（构造交给 [buildRuleFromForm]，与试跑草稿同一份）
        if (s.snoozeOn && buildSnoozeOrNull() == null) {
            return null to "请完善推迟时间（自定义分钟需大于 0，时刻格式为 HH:mm）"
        }
        // 替换：自定义文案模式下「标题 / 正文」两个模板都空 → 拦截
        if (s.replaceOn &&
            s.replaceMode == RewriteMode.CUSTOM_TEXT &&
            s.replaceTitleTemplate.isBlank() &&
            s.replaceTextTemplate.isBlank()
        ) {
            return null to "请至少填写一个模板（标题或正文）"
        }

        // 校验通过 → 复用与「试跑草稿」同一份转换，保证保存后的真实行为与试跑一致
        return buildRuleFromForm() to null
    }

    /**
     * 表单 → Rule（**纯构造**，保存与试跑草稿共用；不写库、不改真实规则）。
     *
     * 保存链路 [buildRuleOrError] 校验通过后也走这里，因此「试跑用的草稿」与
     * 「保存后真正生效的规则」是同一份转换，不会出现两份逻辑各自走偏。
     *
     * 与保存的唯一差异：这里**不做校验**——试跑要回答的是「眼前这份草稿现在会怎样」。
     * 草稿非法也不会崩：空关键词 / 空正则 / 空作用范围 → 引擎一律判不命中并给 reason
     * （RuleEngine 第 7 条防御性判定；非法正则内部 `runCatching` 兜底），
     * 用户看到的是「自己这份草稿为什么不命中」，而不是上一次保存的旧结论。
     */
    private fun buildRuleFromForm(): Rule {
        val s = _uiState.value
        val keywords = s.keywords.map { it.trim() }.filter { it.isNotEmpty() }
        val excludeKeywords = s.excludeKeywords.map { it.trim() }.filter { it.isNotEmpty() }
        // 校验由 [buildRuleOrError] 负责；这里非法一律回落 null（引擎按「无该配置」处理）
        val snooze = if (s.snoozeOn) buildSnoozeOrNull() else null
        val replace = if (s.replaceOn) {
            ReplaceConfig(
                mode = s.replaceMode,
                customTitleTemplate = s.replaceTitleTemplate.takeIf { it.isNotBlank() },
                customTextTemplate = s.replaceTextTemplate.takeIf { it.isNotBlank() },
                keepIcon = s.keepIcon,
                keepLargeImage = s.keepLargeImage,
                channelUseAppName = s.channelUseAppName,
            )
        } else null

        return Rule(
            id = loadedRule?.id ?: 0L,
            name = s.name.trim(),
            enabled = loadedRule?.enabled ?: true,
            order = loadedRule?.order ?: 0,
            matchMode = s.matchMode,
            fields = s.selectedFields.filterIsInstance<FieldRef.Text>()
                .map { it.field }.toSet(),
            metaFields = s.selectedFields.filterIsInstance<FieldRef.Meta>()
                .map { it.kind }.toSet(),
            extrasFields = s.selectedFields.filterIsInstance<FieldRef.Extras>()
                .map { it.key }.toSet(),
            keywords = keywords,
            excludeKeywords = if (s.matchMode == MatchMode.TEXT_A_NOT_B) excludeKeywords else emptyList(),
            regex = if (s.matchMode == MatchMode.REGEX) {
                RegexConfig(pattern = s.regexPattern, ignoreCase = s.regexIgnoreCase)
            } else {
                null
            },
            // V13.0 所属分组（归类用，任何模式都可设；规范化去空白/去重/去空）
            groups = RuleGroups.normalize(s.ruleGroups),
            // V12.2：仅 TEXT_GROUPS 落库；清理空词/空组后为空则写 null（= 未配置）
            keywordGroups = if (s.matchMode == MatchMode.TEXT_GROUPS) {
                val groups = s.keywordGroups
                    .map { g -> g.copy(words = g.words.map { it.trim() }.filter { it.isNotEmpty() }) }
                    .filter { it.words.isNotEmpty() }
                if (groups.isEmpty()) null else KeywordGroupsConfig(groups)
            } else {
                null
            },
            scope = RuleScope(type = s.scopeType, packages = s.scopePackages),
            actions = RuleActionConfig(
                block = s.block,
                // V10 任务 B：静音消除只在「消除」真正启用时才落库（否则是个无意义死字段）
                silentRemove = s.block && s.silentRemove,
                // V13.2：常驻通知子选项同样只在「消除」真正启用时才落库
                blockOngoing = s.block && s.blockOngoing,
                snooze = snooze,
                replace = replace,
                readAloud = s.readAloud,
                // V4 屏幕显示类：开关关闭 → null（引擎不执行）
                danmaku = s.danmakuStyle.takeIf { s.danmakuOn },
                float = s.floatStyle.takeIf { s.floatOn },
                // V7 需求 7：空列表 → null（= 沿用 V6「命中即显示」）
                floatTrigger = s.floatTriggers
                    .takeIf { it.isNotEmpty() }
                    ?.let { OverlayTriggerConfig(it) },
                // V7 需求 8：总开关关闭 → null（不落库）
                aggregation = buildAggregationOrNull(),
            ),
            deviceState = DeviceStateCondition(
                screenStates = s.deviceScreenStates,
                chargeStates = s.deviceChargeStates,
                timeWindows = s.deviceTimeWindows,
                weekdays = s.deviceWeekdays,
            ),
        )
    }

    /** 聚合配置：总开关关闭 → null；开启时按表单组装（实现见 [toAggregationConfig]）。 */
    private fun buildAggregationOrNull(): AggregationConfig? =
        _uiState.value.takeIf { it.aggregationOn }?.toAggregationConfig()

    private fun buildSnoozeOrNull(): SnoozeConfig? {
        val s = _uiState.value
        // V3 需求④：times 列表优先（HH:mm 合法校验）；非空时作为主体
        val validTimes = s.snoozeTimes
            .map { it.trim() }
            .filter { SnoozeCalculator.parseHHmm(it) != null }
        if (validTimes.isNotEmpty()) {
            return SnoozeConfig(times = validTimes)
        }
        // V2 回退：旧规则或用户未填时刻时按 preset
        return when (s.snoozePreset) {
            SnoozePresets.M10, SnoozePresets.M30, SnoozePresets.H1, SnoozePresets.TONIGHT_20 ->
                SnoozeConfig(preset = s.snoozePreset)

            SnoozePresets.CUSTOM_MINUTES -> {
                val minutes = s.snoozeCustomMinutes.trim().toIntOrNull()
                if (minutes == null || minutes <= 0) null
                else SnoozeConfig(preset = SnoozePresets.CUSTOM_MINUTES, customMinutes = minutes)
            }

            SnoozePresets.CUSTOM_TIME -> {
                val hhmm = s.snoozeCustomTime.trim()
                if (SnoozeCalculator.parseHHmm(hhmm) == null) null
                else SnoozeConfig(preset = SnoozePresets.CUSTOM_TIME, customTimeHHmm = hhmm)
            }

            else -> null
        }
    }

    /**
     * 保存（V2T07 两步流）：先 [buildRuleOrError] 校验 → 表单错误直接弹窗；
     * 再 [collectUnknownPlaceholders] 检查未知占位符 —— 有则弹确认框**不保存**，
     * 用户坚持才落库；无则直接持久化并发送事件。
     *
     * @param andTest true = 保存并测试（留在本页并触发测试区自动试跑）
     */
    fun save(andTest: Boolean) {
        val (rule, error) = buildRuleOrError()
        if (error != null || rule == null) {
            _uiState.update { it.copy(error = error ?: "表单不完整") }
            return
        }
        val unknown = collectUnknownPlaceholders()
        if (unknown.isNotEmpty()) {
            // 未知占位符只是「渲染时按空串」的提示，不阻断保存（架构 §6.6：提示但允许坚持）
            _uiState.update { it.copy(placeholderWarning = unknown, pendingAndTest = andTest) }
            return
        }
        persistAndEmit(rule, andTest)
    }

    /** 未知占位符确认框：「仍要保存」——清提示，重建规则后按原参数持久化。 */
    fun confirmSaveWithUnknownPlaceholders() {
        val andTest = _uiState.value.pendingAndTest ?: false
        _uiState.update { it.copy(placeholderWarning = null, pendingAndTest = null) }
        // 重建而非暂存 rule：确认框停留期间表单理论上可能被并行修改，以最新表单为准
        val (rule, error) = buildRuleOrError()
        if (error != null || rule == null) {
            _uiState.update { it.copy(error = error ?: "表单不完整") }
            return
        }
        persistAndEmit(rule, andTest)
    }

    /** 未知占位符确认框：「返回修改」——仅清提示，不保存。 */
    fun dismissPlaceholderWarning() =
        _uiState.update { it.copy(placeholderWarning = null, pendingAndTest = null) }

    /**
     * 收集当前模板中的未识别占位符（替换·自定义文案双模板 + V7 汇总双模板）。
     *
     * 判定口径与渲染一致（[Placeholders.findUnknown]：固定名 ignoreCase、`extras.` 前缀合法、
     * `{itemsN}` 需 `N <= itemCount`、V9.4 的 `{reN}` 需 `N <= reCount`——两者都取
     * 激活提取器的列表号上界）。
     */
    private fun collectUnknownPlaceholders(): List<String> {
        val s = _uiState.value
        val unknown = mutableListOf<String>()
        // V9.4/V9.6：{re1}..{re9} 即时提取——上界 = 激活提取器的最大列表号。
        // **必须跟随「内容提取」总开关**：关掉聚合时 buildRuleFromForm 会把 aggregation 置空，
        // 运行时 {re1} 渲染空串；若这里仍按提取器算出大于 0 的上界，就会出现
        // 「保存不报未识别、实际却没有内容」的误导。开关关着一律按 reCount = 0 判。
        val reCount = if (s.aggregationOn) {
            s.extractors.filter { it.isActive }.maxOfOrNull { it.listIndex } ?: 0
        } else {
            0
        }
        if (s.replaceOn && s.replaceMode == RewriteMode.CUSTOM_TEXT) {
            // 替换动作不走聚合列表，{itemsN} 仍按未识别提示（与 V9.3 及之前一致）
            unknown += Placeholders.findUnknown(s.replaceTitleTemplate, reCount = reCount)
            unknown += Placeholders.findUnknown(s.replaceTextTemplate, reCount = reCount)
        }
        if (s.aggregationOn) {
            val itemCount = s.extractors.filter { it.isActive }.maxOfOrNull { it.listIndex } ?: 0
            unknown += Placeholders.findUnknown(s.aggregationTitleTemplate, itemCount, reCount)
            unknown += Placeholders.findUnknown(s.aggregationTextTemplate, itemCount, reCount)
        }
        return unknown.distinct()
    }

    /** 持久化并发送保存事件（[rule] 已通过 [buildRuleOrError] 校验）。 */
    private fun persistAndEmit(rule: Rule, andTest: Boolean) {
        viewModelScope.launch {
            _uiState.update { it.copy(saving = true, error = null) }
            val newId = ruleRepository.save(rule)
            val hadId = _uiState.value.ruleId
            loadedRule = rule.copy(id = newId)
            _uiState.update {
                it.copy(
                    saving = false,
                    ruleId = newId,
                    isNew = false,
                    testRunToken = if (andTest) it.testRunToken + 1 else it.testRunToken,
                )
            }
            // 首次保存后「当前汇总」区块才有 ruleId 可关联 → 立刻开始订阅
            if (hadId <= 0L && newId > 0L) observeAggregation(newId)
            // V10 任务 A：规则**已落库**（`save` 内部已失效规则缓存）→ 立刻对通知栏里已有的
            // 通知重跑一遍，用户不必「等下一次通知」才看到规则生效。
            // 断连场景（监听未连接）`instance` 为 null → 静默跳过，绝不让保存失败。
            // V10.8：规则可能新增/删除了对 `media.*` 字段的引用 → 重新评估是否采集媒体会话
            runCatching { NfNotificationListener.instance?.refreshMediaCollection() }
                .onFailure { NfLog.w(this@RuleEditViewModel, "媒体采集开关刷新失败: ${it.message}") }
            runCatching {
                NfNotificationListener.instance?.reprocessActiveAfterRuleChange(
                    // V10.3：只执行刚保存的这条规则（用户明确要求：不跑之前保存的规则）
                    rule = rule,
                    reason = if (hadId <= 0L) "新建规则 id=$newId" else "修改规则 id=$newId",
                )
            }.onFailure {
                NfLog.w(this@RuleEditViewModel, "存量通知补处理触发失败: ${it.message}")
            }
            _events.emit(if (andTest) RuleEditEvent.SavedForTest else RuleEditEvent.SavedBack)
        }
    }

    // ==================== 测试面板数据提供 ====================

    /**
     * 试跑结果里草稿规则的**展示用兜底名**（未命名的新建 / 名称被清空的既有规则）。
     *
     * 只在 [loadRulesForTest] 返回的内存副本上生效：不写库、不改表单状态，
     * 列表页 / 详情页 / 记录流水等任何其它地方都不会出现这个名字。
     */
    private val DRAFT_RULE_DISPLAY_NAME = "（未命名草稿）"

    /** 试跑用规则全集（含刚保存的修改）。 */
    suspend fun loadAllRules(): List<Rule> = ruleRepository.getAll()

    /**
     * **编辑页测试专用**的试跑规则全集：已保存规则 + 当前草稿注入（**不写库**）。
     *
     * 为什么必须注入：编辑页改了关键词 / 作用范围 / 匹配模式后不点保存直接点「试跑」，
     * 若仍只跑 [loadAllRules] 的已保存规则，用户看到的就是**上一次保存的旧结论**
     * （真机反馈原话：「会显示之前的测试结果」）。注入后判定用的就是眼前这份草稿。
     *
     * 注入规则（保持与保存后一致的优先级语义）：
     * 名称兜底：草稿名为空时用 [DRAFT_RULE_DISPLAY_NAME]，**只改这里返回的副本**
     * （结果行「命中规则：xxx」展示用）——`buildRuleFromForm()` 与保存落库都不受影响，
     * 库里存的仍是表单里那个（可能为空的）真实名字。
     */
    suspend fun loadRulesForTest(): List<Rule> {
        // V9.9：**只跑当前这一条规则**（草稿），不再混入已保存的其他规则。
        //
        // 旧实现返回「已保存规则全集 + 草稿」，而 RuleEngine.matchFirst 是「按 order 升序、
        // 先命中先处理」——于是测试面板显示的其实是「这条素材在**所有规则**里第一条命中的结果」：
        // 命中数、高亮用的关键词、甚至「命中规则：xxx」都可能是**别的规则**给的
        // （真机反馈原话：「高亮和命中还是会显示其他规则的东西，命中数也会显示其他规则的」）。
        //
        // 测试面板要回答的问题只有一个：「我眼前这条规则，对这批素材会不会命中」。
        // 所以这里只返回草稿本身。批量/全局视角的测试在独立的「规则测试」页（那里用 loadAllRules）。
        //
        // 注意：不再做 runCatching 吞异常——构造失败要让上层 runTest 的 try/catch 显示原因，
        // 静默返回空列表会让用户看到「0 条结果」却不知道为什么。
        val built = buildRuleFromForm()
        // 仅展示用兜底名：不写库、不改 state.name、不进 buildRuleFromForm()
        val draft = if (built.name.isBlank()) {
            built.copy(name = DRAFT_RULE_DISPLAY_NAME)
        } else {
            built
        }
        return listOf(draft)
    }

    /** 流水素材：最近 100 条。 */
    suspend fun loadRecentRecords(): List<NotificationRecord> =
        recordRepository.observeRecent(100).first()

    // ==================== 实体 → 表单 ====================

    private fun Rule.toFormState(): RuleEditUiState {
        val snooze = actions.snooze
        val replace = actions.replace
        val aggregation = actions.aggregation
        val defaultFields: Set<FieldRef> = RuleConditionDefaults.FIELDS
            .map { FieldRef.Text(it) }.toSet()
        return RuleEditUiState(
            isNew = false,
            loaded = true,
            ruleId = id,
            name = name,
            matchMode = matchMode,
            selectedFields = fieldRefs().toSet().ifEmpty { defaultFields },
            keywords = keywords.ifEmpty { listOf("") },
            excludeKeywords = excludeKeywords.ifEmpty { listOf("") },
            // V12.2：旧规则（无 keywordGroups 键）回显一组空词行；空 groups 同样兜底一组
            keywordGroups = keywordGroups?.groups?.ifEmpty { listOf(KeywordGroup()) }
                ?: listOf(KeywordGroup()),
            // V13.0：旧规则无 groups 键 → 空列表（未分组）
            ruleGroups = groups,
            regexPattern = regex?.pattern.orEmpty(),
            regexIgnoreCase = regex?.ignoreCase ?: true,
            scopeType = scope.type,
            scopePackages = scope.packages,
            // V9.4：展示名不在实体→表单这一步解析（本方法跑在加载协程里，同步解析会在首帧
            // 触发 PackageManager 调用）；由 load() 末尾的 refreshScopeLabels() 在 IO 上回填。
            block = actions.block,
            // V10 任务 B：老规则（JSON 里没有该键）decode 后为 false → 开关回显「关闭」
            silentRemove = actions.silentRemove,
            // V13.2：同上，老规则缺键 → false → 开关回显「关闭」
            blockOngoing = actions.blockOngoing,
            readAloud = actions.readAloud,
            snoozeOn = snooze != null,
            snoozePreset = snooze?.preset ?: SnoozePresets.M30,
            snoozeCustomMinutes = snooze?.customMinutes?.toString().orEmpty(),
            snoozeCustomTime = snooze?.customTimeHHmm.orEmpty(),
            snoozeTimes = snooze?.times ?: emptyList(),
            replaceOn = replace != null,
            replaceMode = replace?.mode ?: RewriteMode.PLAIN_TEXT,
            replaceTitleTemplate = replace?.customTitleTemplate.orEmpty(),
            replaceTextTemplate = replace?.customTextTemplate.orEmpty(),
            keepIcon = replace?.keepIcon ?: true,
            keepLargeImage = replace?.keepLargeImage ?: false,
            channelUseAppName = replace?.channelUseAppName ?: true,
            danmakuOn = actions.danmaku != null,
            danmakuStyle = actions.danmaku ?: DanmakuStyle(),
            floatOn = actions.float != null,
            floatStyle = actions.float ?: FloatStyle(),
            deviceScreenStates = deviceState.screenStates,
            deviceChargeStates = deviceState.chargeStates,
            deviceTimeWindows = deviceState.timeWindows,
            deviceWeekdays = deviceState.weekdays,
            // —— V7 需求 8（逐字段无损回显）——
            aggregationOn = aggregation != null,
            extractors = aggregation?.extractors?.map { it.toForm() } ?: emptyList(),
            aggregationTitleTemplate = aggregation?.titleTemplate.orEmpty(),
            aggregationTextTemplate = aggregation?.textTemplate.orEmpty(),
            aggregationItemSeparatorRaw = EscapeCodec.encode(
                aggregation?.itemSeparator ?: DEFAULT_ITEM_SEPARATOR,
            ),
            aggregationOutputs = aggregation?.outputs ?: emptySet(),
            // —— V7 需求 7 ——
            floatTriggers = actions.floatTrigger?.triggers ?: emptyList(),
        )
    }

    companion object {
        /** extras 键名最大字节数（UTF-8，架构 §6.5 手动输入校验）。 */
        const val EXTRAS_KEY_MAX_BYTES = 128

        /** 提取器列表号上界（与 `Placeholders.MAX_ITEM_INDEX` 一致：`{items1}`..`{items9}`）。 */
        const val MAX_LIST_INDEX = Placeholders.MAX_ITEM_INDEX

        /** 条目分隔符默认值（真实换行；界面上回显为 `\n`）。 */
        const val DEFAULT_ITEM_SEPARATOR = "\n"
    }
}

// ==================== 表单 → 聚合配置（保存与预览共用） ====================

/**
 * 表单快照 → [AggregationConfig]（**保存与 ⑤ 实时预览共用同一实现**，避免两处组装漂移）。
 *
 * 分隔符在这一步才做转义解析，且只经 `AggregationRenderer.resolveSeparator`
 * （架构 Q5 的唯一入口）。
 */
fun RuleEditUiState.toAggregationConfig(): AggregationConfig = AggregationConfig(
    extractors = extractors.map { it.toConfig() },
    titleTemplate = aggregationTitleTemplate,
    textTemplate = aggregationTextTemplate,
    itemSeparator = AggregationRenderer.resolveSeparator(aggregationItemSeparatorRaw),
    outputs = aggregationOutputs,
)

// ==================== 流水 → 快照（试跑素材） ====================

/**
 * 流水记录 → 匹配视角快照（聚合提取器「实时试跑」的输入）。
 *
 * 与 `RuleTestPanel` 内的同名映射同一口径（那边是 private，无法跨文件复用）：
 * 标题 / 正文取摘要列，其余字段从 extras 还原，tag 从 key 第 4 段解析。
 */
// TODO: 与 RuleTestPanel.toTestSnapshot() 等价，后续应提为 data/repo 的公开扩展，避免两边漂移
private fun NotificationRecord.toSampleSnapshot(): NotificationSnapshot {
    val extras = extrasPairs()
    fun extra(key: String): String? = extras.firstOrNull { it.first == key }?.second
    fun lines(key: String): List<String> =
        extra(key)?.split("\n")?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()

    return NotificationSnapshot(
        key = key,
        notifId = notifId,
        packageName = packageName,
        appName = appName,
        userId = userId,
        channelId = channelId,
        postTime = postTime,
        isOngoing = isOngoing,
        flags = flags,
        title = titleSummary.takeIf { it.isNotBlank() },
        text = textSummary.takeIf { it.isNotBlank() },
        textLines = lines(ExtrasKeys.TEXTLINES),
        subText = extra(ExtrasKeys.SUBTEXT),
        summaryText = extra(ExtrasKeys.SUMMARYTEXT),
        people = lines(ExtrasKeys.PEOPLE),
        extras = extras,
        tag = key.split("|").getOrNull(3)?.takeIf { it != "null" },
    )
}
