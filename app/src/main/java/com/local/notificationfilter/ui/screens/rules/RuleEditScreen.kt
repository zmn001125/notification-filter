package com.local.notificationfilter.ui.screens.rules

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.local.notificationfilter.core.model.AggregationOutput
import com.local.notificationfilter.core.model.OverlayPayload
import com.local.notificationfilter.core.model.DeviceStateCondition
import com.local.notificationfilter.core.model.FieldRef
import com.local.notificationfilter.core.model.GroupMatchMode
import com.local.notificationfilter.core.model.MatchField
import com.local.notificationfilter.core.model.MatchMode
import com.local.notificationfilter.core.model.Placeholders
import com.local.notificationfilter.core.model.RewriteMode
import com.local.notificationfilter.core.model.RuleConditionDefaults
import com.local.notificationfilter.core.model.RuleGroups
import com.local.notificationfilter.core.model.ScopeType
import com.local.notificationfilter.core.model.SnoozePresets
import com.local.notificationfilter.ui.components.AppPickerDialog
import com.local.notificationfilter.ui.components.CollapsibleSection
import com.local.notificationfilter.ui.components.FieldPickerSheet
import com.local.notificationfilter.ui.components.NfCard
import com.local.notificationfilter.ui.components.PlaceholderChips
import com.local.notificationfilter.ui.components.PlaceholderReferenceCard
import com.local.notificationfilter.ui.components.RuleTestPanel
import com.local.notificationfilter.ui.components.SectionTitle
import com.local.notificationfilter.ui.components.SelectedFieldChip
import com.local.notificationfilter.ui.components.TimePickerDialog
import com.local.notificationfilter.ui.screens.overlay.OverlayStyleDialog
import com.local.notificationfilter.ui.screens.overlay.OverlayStyleKind
import com.local.notificationfilter.service.OverlayController
import com.local.notificationfilter.ui.theme.ActionKind
import com.local.notificationfilter.util.PermissionChecker
import com.local.notificationfilter.util.PermissionType
import com.local.notificationfilter.ui.theme.actionColorsOf
import com.local.notificationfilter.ui.theme.defaultIcon
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * P6 规则编辑（从 P5 进入）：五区表单 + 动作多选卡片（互斥置灰）+ 页内测试区。
 *
 * V2（需求④）：匹配条件 = **模式单选（八项）+ 被选字段（已选 chips + 字段选择器，
 * 文本/元信息/extras 统一入口，需求②）+ 模式参数（A/B 组关键词 / 正则）**；
 * 关键词区随模式显隐。
 *
 * V2（需求③）：替换·自定义文案为**双模板**（标题 / 正文独立填写或留空），模板框下方
 * 占位符 chips 点击插入到当前聚焦框的**光标处**；保存时发现未识别占位符先弹确认框
 * （渲染按空串，提示但不阻断，可坚持保存）。
 *
 * 「保存并测试」= 先保存 → 测试区自动展开并试跑 → 滚动定位到第一条结果；「保存」= 保存后返回列表。
 *
 * V8（本轮）：保存按钮改为底部悬浮（Scaffold `bottomBar`，正文底部留白避免遮挡）；
 * 分节大标题统一走主题色——①②③④⑦ 用公共 `CollapsibleSection(titleColor = primary)`，
 * ⑧ 测试用本文件内受控的 [NfSectionCard]（「保存并测试」需要程序化展开）。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun RuleEditScreen(
    ruleId: Long,
    onBack: () -> Unit,
    onOpenDetail: (Long) -> Unit,
    prefillPkg: String? = null,
) {
    val vm: RuleEditViewModel = viewModel()
    val state by vm.uiState.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    // V9.6：占位符速查卡的「点击复制」出口（不需要任何输入框处于聚焦态）
    val clipboard = LocalClipboardManager.current
    val scrollState = rememberScrollState()
    var showAppPicker by remember { mutableStateOf(false) }
    var showFieldPicker by remember { mutableStateOf(false) }
    var showSnoozeTimePicker by remember { mutableStateOf(false) }
    var showDeviceTimeWindowPicker by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }

    // —— V8 任务 2：⑧ 测试区展开态（由本页持有，「保存并测试」可强制展开） ——
    var testSectionExpanded by rememberSaveable { mutableStateOf(false) }

    // —— V8 任务 2 · 第三步：滚动定位锚点 ——
    // 思路：正文是 verticalScroll 的 Column，滚动只是把该 Column 在视口里平移，
    // 因此「子孙节点相对 Column 内容坐标」在滚动时是稳定的 → 用 positionInRoot 之差换算。
    val density = LocalDensity.current
    var contentCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var testSectionCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var firstResultCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    /** ⑧ 测试卡片在正文中的纵向偏移（结果锚点的兜底目标）。 */
    var testSectionY by remember { mutableIntStateOf(0) }
    /** 第一条试跑结果（「命中 X / 共 Y 条」那行）在正文中的纵向偏移。 */
    var firstResultY by remember { mutableIntStateOf(0) }
    /** 待执行的「滚动到第一条结果」请求（结果渲染出来后兑现）。 */
    var pendingScrollToResult by remember { mutableStateOf(false) }

    LaunchedEffect(contentCoords, testSectionCoords, firstResultCoords) {
        val content = contentCoords ?: return@LaunchedEffect
        val contentTop = content.positionInRoot().y
        testSectionCoords?.let { testSectionY = (it.positionInRoot().y - contentTop).roundToInt() }
        firstResultCoords?.let { firstResultY = (it.positionInRoot().y - contentTop).roundToInt() }
    }

    // —— V4 屏幕显示：样式编辑对话框 + 悬浮窗权限态 ——
    val context = LocalContext.current
    var styleDialog by remember { mutableStateOf<OverlayStyleKind?>(null) }
    /** 权限检查刷新计数（开关切换 / 样式页返回 / 真机预览时自增）。 */
    var permissionTick by remember { mutableIntStateOf(0) }
    val overlayGranted = remember(permissionTick, state.danmakuOn, state.floatOn) {
        PermissionChecker.canDrawOverlays(context)
    }

    /** 真机预览：弹一条样式页当前配置的弹幕（无权限时提示）。 */
    val previewDanmaku = {
        permissionTick++
        val payload = OverlayPayload(
            ruleId = 0L,
            packageName = context.packageName,
            appName = "示例",
            title = "顺丰速运",
            text = "取件码 8-3-9201，请在 24 小时内领取",
        )
        val ok = OverlayController.enqueueDanmaku(payload, state.danmakuStyle)
        if (!ok) {
            scope.launch { snackbarHostState.showSnackbar("悬浮窗权限未授予，无法预览弹幕") }
        }
        Unit
    }

    /** 真机预览：弹一条自定义位置悬浮。 */
    val previewFloat = {
        permissionTick++
        val payload = OverlayPayload(
            ruleId = 0L,
            packageName = context.packageName,
            appName = "示例",
            title = "顺丰速运",
            text = "取件码 8-3-9201，请在 24 小时内领取",
        )
        // V12：预览走固定 key（__preview__），与业务悬浮（key = 规则 id）互不顶掉
        val ok = OverlayController.showFloat(OverlayController.PREVIEW_FLOAT_KEY, payload, state.floatStyle)
        if (!ok) {
            scope.launch { snackbarHostState.showSnackbar("悬浮窗权限未授予，无法预览悬浮窗") }
        }
        Unit
    }

    // V2T07 双模板本地态（TextFieldValue 驱动：占位符 chips 需在光标处插入，
    // 单纯 String value 拿不到 selection）；外部状态变化经 LaunchedEffect 同步。
    var titleTemplateValue by remember { mutableStateOf(TextFieldValue(state.replaceTitleTemplate)) }
    var textTemplateValue by remember { mutableStateOf(TextFieldValue(state.replaceTextTemplate)) }
    /** 当前聚焦的模板框：0 = 标题 / 1 = 正文（ chips 插入目标；从未聚焦过按标题）。 */
    var focusedTemplate by remember { mutableIntStateOf(0) }

    // 外部状态变化（典型：规则加载完成后回填）同步到本地；本地输入不回环（guard !=）
    LaunchedEffect(state.replaceTitleTemplate, state.replaceTextTemplate) {
        if (titleTemplateValue.text != state.replaceTitleTemplate) {
            titleTemplateValue = TextFieldValue(state.replaceTitleTemplate)
        }
        if (textTemplateValue.text != state.replaceTextTemplate) {
            textTemplateValue = TextFieldValue(state.replaceTextTemplate)
        }
    }

    // 占位符 chips 插入：向当前聚焦模板的光标处插入 token，光标移到 token 之后
    val insertPlaceholder: (String) -> Unit = { token ->
        val field = if (focusedTemplate == 0) titleTemplateValue else textTemplateValue
        val start = field.selection.start.coerceIn(0, field.text.length)
        val newText = field.text.substring(0, start) + token + field.text.substring(start)
        val newValue = TextFieldValue(newText, TextRange(start + token.length))
        if (focusedTemplate == 0) {
            titleTemplateValue = newValue
            vm.setReplaceTitleTemplate(newText)
        } else {
            textTemplateValue = newValue
            vm.setReplaceTextTemplate(newText)
        }
    }

    LaunchedEffect(ruleId, prefillPkg) { vm.load(ruleId, prefillPkg) }

    // 保存事件：普通保存返回列表；保存并测试留在本页（由 testRunToken 触发面板）
    LaunchedEffect(Unit) {
        vm.events.collect { event ->
            if (event is RuleEditEvent.SavedBack) onBack()
        }
    }

    // 校验失败弹窗
    state.error?.let { msg ->
        AlertDialog(
            onDismissRequest = { vm.consumeError() },
            title = { Text("无法保存") },
            text = { Text(msg) },
            confirmButton = {
                TextButton(onClick = { vm.consumeError() }) { Text("知道了") }
            },
        )
    }

    // 未知占位符确认弹窗（V2T07：渲染时按空串，提示但不阻断，可坚持保存）
    state.placeholderWarning?.let { unknown ->
        AlertDialog(
            onDismissRequest = { vm.dismissPlaceholderWarning() },
            title = { Text("有未识别的占位符") },
            text = {
                Text(
                    "以下占位符无法识别，渲染时会按空串处理：\n" +
                        unknown.joinToString("、") +
                        "\n\n仍要保存吗？",
                )
            },
            confirmButton = {
                TextButton(onClick = { vm.confirmSaveWithUnknownPlaceholders() }) {
                    Text("仍要保存")
                }
            },
            dismissButton = {
                TextButton(onClick = { vm.dismissPlaceholderWarning() }) { Text("返回修改") }
            },
        )
    }

    if (showAppPicker) {
        AppPickerDialog(
            title = when (state.scopeType) {
                ScopeType.ONLY -> "选择生效的 App"
                ScopeType.EXCLUDE -> "选择要排除的 App"
                ScopeType.GLOBAL -> "选择应用"
            },
            initialSelected = state.scopePackages,
            onDismiss = { showAppPicker = false },
            onConfirm = { selected ->
                vm.setScopePackages(selected)
                showAppPicker = false
            },
        )
    }

    if (showFieldPicker) {
        FieldPickerSheet(
            selected = state.selectedFields,
            recentExtrasKeys = state.recentExtrasKeys,
            recentKeysLoading = state.recentKeysLoading,
            extrasError = state.extrasFieldError,
            onToggle = vm::toggleField,
            onAddExtras = vm::addExtrasField,
            onDismiss = {
                showFieldPicker = false
                vm.consumeExtrasFieldError()
            },
        )
    }

    // V8 任务 4：推迟时刻 —— 共用 TimePickerDialog（表盘上方带 HH:mm 输入框，双向同步）
    if (showSnoozeTimePicker) {
        TimePickerDialog(
            title = "选择推迟时刻",
            initialHour = 8,
            initialMinute = 0,
            onDismiss = { showSnoozeTimePicker = false },
            onConfirm = { hour, minute ->
                val hh = hour.toString().padStart(2, '0')
                val mm = minute.toString().padStart(2, '0')
                vm.addSnoozeTime("$hh:$mm")
                showSnoozeTimePicker = false
            },
        )
    }

    // V4：屏幕显示样式编辑对话框（页内 Dialog，避免导航导致表单状态丢失）
    styleDialog?.let { kind ->
        OverlayStyleDialog(
            kind = kind,
            danmaku = state.danmakuStyle,
            float = state.floatStyle,
            onDone = { d, f ->
                vm.updateDanmakuStyle(d)
                vm.updateFloatStyle(f)
                styleDialog = null
                permissionTick++
            },
            onDismiss = { styleDialog = null },
            // V9.4：弹幕 / 悬浮文案同样能用 {re1}..{re9} 即时提取
            extractTokens = Placeholders.extractTokens(
                state.takeIf { it.aggregationOn }?.toAggregationConfig()
            ),
        )
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(if (state.isNew) "新建规则" else "编辑规则") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
        // —— V8 任务 2：保存按钮悬浮固定在页面底部（半透明 surface 底衬 + 阴影），
        //    高度由 Scaffold 计入 innerPadding，正文因此不会被遮挡 ——
        bottomBar = {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
                shadowElevation = 8.dp,
                tonalElevation = 3.dp,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        // edge-to-edge：避开系统手势条 / 三键导航栏
                        .navigationBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(
                            onClick = { vm.save(andTest = true) },
                            enabled = !state.saving,
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(if (state.saving) "保存中…" else "保存并测试")
                        }
                        OutlinedButton(
                            onClick = { vm.save(andTest = false) },
                            enabled = !state.saving,
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("保存")
                        }
                    }
                    Text(
                        text = "「保存并测试」= 先保存 → 底部测试区自动展开并试跑",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(scrollState)
                .padding(horizontal = 16.dp)
                .onGloballyPositioned { contentCoords = it },
        ) {
            if (!state.loaded) {
                Spacer(Modifier.height(40.dp))
                Text(
                    text = "加载中…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                return@Column
            }

            // V8 任务 2 · 间距：顶栏与第一张卡片之间留 8dp（卡片自身还有 4dp 上内边距 → 合计 12dp）
            Spacer(Modifier.height(8.dp))

            // ==================== ① 规则名称 ====================
            CollapsibleSection(
                title = "① 规则名称",
                titleColor = MaterialTheme.colorScheme.primary,
            ) {
                OutlinedTextField(
                    value = state.name,
                    onValueChange = vm::setName,
                    placeholder = { Text("选填，默认按条件生成") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            // ==================== 分组（归类标签） ====================
            CollapsibleSection(
                title = "分组（归类标签）",
                summary = if (state.ruleGroups.isEmpty()) {
                    "未分组"
                } else {
                    state.ruleGroups.joinToString("、")
                },
                titleColor = MaterialTheme.colorScheme.primary,
            ) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "只用于规则列表顶部 tab 归类筛选，不参与任何匹配判定。一条规则可以同时属于多个分组。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))

                if (state.ruleGroups.isEmpty()) {
                    Text(
                        text = "尚未加入任何分组（列表页只会显示在「全部」里）",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        state.ruleGroups.forEach { g ->
                            InputChip(
                                selected = true,
                                onClick = { vm.removeRuleGroup(g) },
                                label = { Text(g) },
                                trailingIcon = {
                                    Icon(
                                        Icons.Filled.Close,
                                        contentDescription = "移除分组 $g",
                                        modifier = Modifier.size(16.dp),
                                    )
                                },
                            )
                        }
                    }
                }

                Spacer(Modifier.height(10.dp))

                // —— 新建并加入 ——
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = state.groupDraft,
                        onValueChange = vm::setGroupDraft,
                        placeholder = { Text("新分组名，最多 ${RuleGroups.MAX_NAME_LENGTH} 字") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(
                            onDone = { vm.addRuleGroup(state.groupDraft) },
                        ),
                    )
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(
                        onClick = { vm.addRuleGroup(state.groupDraft) },
                        enabled = state.groupDraft.isNotBlank(),
                    ) { Text("加入") }
                }

                // —— 已有分组一键加入 ——
                val suggestions = state.knownGroups.filter { it !in state.ruleGroups }
                if (suggestions.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = "已有分组（点一下加入）",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(6.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        suggestions.forEach { g ->
                            AssistChip(onClick = { vm.addRuleGroup(g) }, label = { Text(g) })
                        }
                    }
                }
            }

            // ==================== ② 匹配条件 ====================
            CollapsibleSection(
                title = "② 匹配条件",
                summary = state.matchMode.label,
                titleColor = MaterialTheme.colorScheme.primary,
            ) {
                Spacer(Modifier.height(6.dp))

                // —— 模式单选（八项，互斥）——
                ModeSelector(mode = state.matchMode, onSelect = vm::setMatchMode)
                Spacer(Modifier.height(4.dp))
                Text(
                    text = state.matchMode.desc,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(Modifier.height(12.dp))

                // —— 已选字段（V2T05：chips 可删可看示例值；十二项平铺收进字段选择器）——
                Text(
                    text = "被选字段（${state.selectedFields.size}）",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
                if (state.selectedFields.isEmpty()) {
                    Text(
                        text = "尚未选择字段（「所有内容」模式不看字段；其他模式至少选一个）",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        state.selectedFields.forEach { ref ->
                            val extrasRef = ref as? FieldRef.Extras
                            val sample = extrasRef?.key?.let { key ->
                                state.recentExtrasKeys.firstOrNull { it.first == key }?.second
                            }
                            SelectedFieldChip(
                                ref = ref,
                                onRemove = { vm.removeField(ref) },
                                onShowSample = if (extrasRef != null) {
                                    {
                                        scope.launch {
                                            snackbarHostState.showSnackbar(
                                                if (sample.isNullOrBlank()) {
                                                    "字段 ${extrasRef.key}：暂无示例值（最近流水中未出现）"
                                                } else {
                                                    "字段 ${extrasRef.key} 示例值：$sample"
                                                },
                                            )
                                        }
                                    }
                                } else {
                                    null
                                },
                            )
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(
                        onClick = {
                            vm.loadRecentExtrasKeys()
                            showFieldPicker = true
                        },
                    ) {
                        Text("+ 添加字段")
                    }
                    AssistChip(
                        onClick = {
                            vm.applyTextFieldsQuick(RuleConditionDefaults.FIELDS)
                        },
                        label = { Text("快捷：标题+正文+消息列表") },
                    )
                    AssistChip(
                        onClick = {
                            vm.applyTextFieldsQuick(MatchField.ALL.toSet())
                        },
                        label = { Text("快捷：全部文本字段") },
                    )
                }

                Spacer(Modifier.height(12.dp))

                // —— 模式参数区（随模式显隐）——
                when (state.matchMode) {
                    // 所有内容：关键词区整体隐藏（不是置灰留着误导）
                    MatchMode.ALL_CONTENT -> Text(
                        text = "「所有内容」不看字段与关键词，条件恒成立；是否执行动作只看作用范围。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    MatchMode.TEXT_ANY, MatchMode.TEXT_ALL,
                    MatchMode.TEXT_NOT_ANY, MatchMode.TEXT_NOT_ALL,
                    -> {
                        KeywordEditor(
                            title = "关键词（${modeKeywordHint(state.matchMode)}）",
                            rows = state.keywords,
                            onChange = vm::updateKeyword,
                            onAdd = vm::addKeyword,
                            onRemove = vm::removeKeyword,
                        )
                        Text(
                            text = modeExplainer(state.matchMode),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    MatchMode.TEXT_A_NOT_B -> {
                        KeywordEditor(
                            title = "A 组关键词（要包含，任一出现）",
                            rows = state.keywords,
                            onChange = vm::updateKeyword,
                            onAdd = vm::addKeyword,
                            onRemove = vm::removeKeyword,
                        )
                        Spacer(Modifier.height(8.dp))
                        KeywordEditor(
                            title = "B 组关键词（不包含，一个都不出现）",
                            rows = state.excludeKeywords,
                            onChange = vm::updateExcludeKeyword,
                            onAdd = vm::addExcludeKeyword,
                            onRemove = vm::removeExcludeKeyword,
                        )
                        Text(
                            text = modeExplainer(state.matchMode),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    // —— V12.2 分组匹配：组间 AND、组内 ANY/ALL；每组一张卡 ——
                    MatchMode.TEXT_GROUPS -> {
                        state.keywordGroups.forEachIndexed { index, group ->
                            if (index > 0) Spacer(Modifier.height(8.dp))
                            NfCard {
                                // 卡头行：「第 N 组」+ 组内模式二选一 +（多组时）删除该组
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = "第 ${index + 1} 组",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.weight(1f),
                                    )
                                    FilterChip(
                                        selected = group.mode == GroupMatchMode.ANY,
                                        onClick = { vm.setGroupMode(index, GroupMatchMode.ANY) },
                                        label = { Text(GroupMatchMode.ANY.label) },
                                    )
                                    Spacer(Modifier.size(6.dp))
                                    FilterChip(
                                        selected = group.mode == GroupMatchMode.ALL,
                                        onClick = { vm.setGroupMode(index, GroupMatchMode.ALL) },
                                        label = { Text(GroupMatchMode.ALL.label) },
                                    )
                                    if (state.keywordGroups.size > 1) {
                                        IconButton(onClick = { vm.removeGroup(index) }) {
                                            Icon(
                                                Icons.Filled.Close,
                                                contentDescription = "删除该组",
                                                modifier = Modifier.size(18.dp),
                                            )
                                        }
                                    }
                                }
                                Spacer(Modifier.height(6.dp))
                                KeywordEditor(
                                    title = "组内关键词（${group.mode.label}）",
                                    rows = group.words,
                                    onChange = { wi, w -> vm.updateGroupWord(index, wi, w) },
                                    onAdd = { vm.addGroupWord(index) },
                                    onRemove = { wi -> vm.removeGroupWord(index, wi) },
                                )
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(onClick = vm::addGroup) { Text("+ 添加一组") }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "分多组，组间全部满足才命中；组内『任一出现』或『全部出现』可选。" +
                                "每个关键词在任一被选字段中查找（可分别在不同字段）。" +
                                "例：第 1 组填 true，第 2 组填 salt、nest 选『任一出现』" +
                                "= 必须出现 true 且 salt/nest 任一出现。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    MatchMode.REGEX -> {
                        OutlinedTextField(
                            value = state.regexPattern,
                            onValueChange = vm::setRegexPattern,
                            label = { Text("正则表达式") },
                            placeholder = { Text("如：(顺丰|取件码)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(4.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "区分大小写",
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f),
                            )
                            Switch(
                                checked = !state.regexIgnoreCase,
                                onCheckedChange = { vm.toggleRegexCase() },
                            )
                        }
                        Text(
                            text = "对被选字段逐字段做正则匹配，任一字段命中即命中；不区分大小写为默认。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            // ==================== ③ 作用范围 ====================
            CollapsibleSection(
                title = "③ 作用范围",
                summary = state.scopeType.label,
                titleColor = MaterialTheme.colorScheme.primary,
            ) {
                Spacer(Modifier.height(4.dp))
                ScopeType.entries.forEach { type ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { vm.setScopeType(type) }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = state.scopeType == type,
                            onClick = { vm.setScopeType(type) },
                        )
                        Spacer(Modifier.size(4.dp))
                        Column {
                            Text(type.label, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                text = when (type) {
                                    ScopeType.GLOBAL -> "对所有 App 的通知生效"
                                    ScopeType.ONLY -> "勾选后才可选 App 多选列表"
                                    ScopeType.EXCLUDE -> "全局生效，但跳过勾选的 App"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                if (state.scopeType != ScopeType.GLOBAL) {
                    Spacer(Modifier.height(4.dp))
                    OutlinedButton(onClick = { showAppPicker = true }) { Text("选择 App") }
                    if (state.scopePackages.isNotEmpty()) {
                        Spacer(Modifier.height(6.dp))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            // V8 需求 9：选中的 App 显示**应用名**（重名时自动带上包名区分），
                            // 不再直接显示包名。
                            //
                            // V9.4：展示名改由 ViewModel 在 IO 线程解析后放进 state.scopeAppNames，
                            // 组合期不再直接调 AppUtils —— 那会触发「枚举全部已安装应用」的同步
                            // Binder IPC，正好落在进页的一两帧上，真机表现就是「卡一下」。
                            // 解析尚未回来时回落包名，显示效果与最终态一致（只是少了应用名）。
                            state.scopePackages.forEach { pkg ->
                                // V12：已选 App 应可删除——点 chip 本体即从作用范围中移除，
                                //  trailingIcon 的关闭图标仅作视觉提示。「选择 App」按钮仍是增选入口。
                                InputChip(
                                    selected = true,
                                    onClick = { vm.setScopePackages(state.scopePackages - pkg) },
                                    label = { Text(state.scopeAppNames[pkg] ?: pkg) },
                                    trailingIcon = {
                                        Icon(
                                            Icons.Filled.Close,
                                            contentDescription = "移除该应用",
                                            modifier = Modifier.size(16.dp),
                                        )
                                    },
                                )
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            // ==================== ④ 动作 ====================
            CollapsibleSection(
                title = "④ 动作（多选，可组合）",
                titleColor = MaterialTheme.colorScheme.primary,
            ) {
                if (state.block) {
                    Text(
                        text = "消除与推迟/替换矛盾，不能同选；消除 + 朗读合法（念完删掉）",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(vertical = 4.dp),
                    )
                }
                ActionCard(
                    kind = ActionKind.BLOCK,
                    checked = state.block,
                    title = "消除",
                    desc = "终止性动作：命中通知直接不显示；与推迟/替换矛盾",
                    onToggle = vm::toggleBlock,
                )
                // —— V10 任务 B：静音消除（「消除」的子选项，未勾选消除时不显示）——
                if (state.block) {
                    ToggleRow(
                        label = "静音",
                        checked = state.silentRemove,
                        onToggle = vm::toggleSilentRemove,
                    )
                    Text(
                        text = "Android 限制：无法只静音单条通知，会静音该渠道后续所有通知" +
                            "（渠道原始设置已留底，可恢复）",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 4.dp),
                    )
                    // —— V13.2：常驻通知（「消除」的子选项，未勾选消除时不显示）——
                    ToggleRow(
                        label = "常驻通知",
                        checked = state.blockOngoing,
                        onToggle = vm::toggleBlockOngoing,
                    )
                    Text(
                        text = "命中带「进行中」标记的通知（音乐、下载、导航、前台服务等）时，" +
                            "用系统的「打盹」机制让它休眠约 1 年：期间同一条通知再更新也不会回来，" +
                            "且不需要每次重发都删一次；到期后自动恢复，届时规则会重新判定。" +
                            "只影响这一条通知，不影响该应用的其他常驻通知。" +
                            "若应用改用别的通知或方式展示，可能仍压不住；系统自身的常驻通知" +
                            "（系统更新、安全提示等）不建议消除。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 4.dp),
                    )
                }
                ActionCard(
                    kind = ActionKind.SNOOZE,
                    checked = state.snoozeOn,
                    enabled = !state.block,
                    title = "推迟",
                    desc = "先消失，到点恢复；可与替换组合（到点以本 App 名义重发改写版）",
                    onToggle = vm::toggleSnooze,
                )
                ActionCard(
                    kind = ActionKind.REPLACE,
                    checked = state.replaceOn,
                    enabled = !state.block,
                    title = "替换",
                    desc = "取消原通知，以本 App 名义重发（可去图 / 精简 / 自定义文案）；可与推迟组合",
                    onToggle = vm::toggleReplace,
                )
                ActionCard(
                    kind = ActionKind.READ,
                    checked = state.readAloud,
                    title = "朗读",
                    desc = "可叠加项：TTS 念出标题+正文，先朗读，其他动作照常执行",
                    onToggle = vm::toggleReadAloud,
                )

                // —— V4 屏幕显示类动作（7-②弹幕 / 7-④悬浮） ——
                // 与消除/推迟/替换**不互斥**：可叠加在任意组合上（内容已在内存定型）。
                Text(
                    text = "屏幕显示（可叠加，原通知是否保留由各动作决定）",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
                )
                ActionCard(
                    kind = ActionKind.DANMAKU,
                    checked = state.danmakuOn,
                    title = "弹幕显示",
                    desc = "命中通知以弹幕形式滚过屏幕，多条按到达顺序排队（需悬浮窗权限）",
                    onToggle = vm::toggleDanmaku,
                )
                ActionCard(
                    kind = ActionKind.FLOAT,
                    checked = state.floatOn,
                    title = "自定义位置悬浮",
                    desc = "在屏幕指定位置停留显示，可设自动消失时长或常驻（需悬浮窗权限）",
                    onToggle = vm::toggleFloat,
                )

                // 悬浮窗权限提示（仅在启用弹幕/悬浮且未授权时显示）
                if ((state.danmakuOn || state.floatOn) && !overlayGranted) {
                    Spacer(Modifier.height(6.dp))
                    NfCard {
                        Text(
                            text = "尚未授予「显示在其他应用上层」权限，弹幕 / 悬浮不会弹出。" +
                                "点此前往系统设置授权。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                        Spacer(Modifier.height(6.dp))
                        OutlinedButton(onClick = {
                            PermissionChecker.openSettings(
                                PermissionType.OVERLAY,
                                context,
                            )
                        }) { Text("去授权悬浮窗") }
                    }
                }

                // —— 各自的样式入口 ——
                if (state.danmakuOn) {
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { styleDialog = OverlayStyleKind.DANMAKU }) {
                            Text("弹幕样式设置")
                        }
                        OutlinedButton(onClick = { previewDanmaku() }) { Text("真机预览") }
                    }
                }
                if (state.floatOn) {
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { styleDialog = OverlayStyleKind.FLOAT }) {
                            Text("悬浮样式设置")
                        }
                        OutlinedButton(onClick = { previewFloat() }) { Text("真机预览") }
                    }
                    // —— V7 §5.5：常驻（不自动消失）——把 displaySeconds 置 0，复用现有字段 ——
                    Spacer(Modifier.height(2.dp))
                    ToggleRow(
                        label = "常驻（不自动消失）",
                        checked = state.floatStyle.displaySeconds <= 0f,
                        onToggle = vm::toggleFloatSticky,
                    )
                    Text(
                        text = "开启后「停留时长」为 0，卡片不会自动关闭；" +
                            "可用首页菜单「清除屏幕显示」随时撤下。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                // —— V7 §5.5：聚合输出与动作的联动提示 ——
                if (state.floatOn && AggregationOutput.FLOAT in state.aggregationOutputs) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "汇总以这张卡片显示；样式与位置沿用上方「悬浮样式设置」",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (!state.snoozeOn && AggregationOutput.SNOOZE in state.aggregationOutputs) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "已启用「推迟」输出，请同时勾选上面的「推迟」动作",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                // —— V3 推迟参数区：时刻列表 + 时钟表盘 ——
                if (state.snoozeOn) {
                    Spacer(Modifier.height(8.dp))
                    NfCard {
                        Text(
                            text = "推迟时刻（取列表中最近的下一时刻）",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(6.dp))
                        // 已添加的时刻列表（chips 可删）
                        if (state.snoozeTimes.isNotEmpty()) {
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                state.snoozeTimes.forEachIndexed { index, time ->
                                    InputChip(
                                        selected = false,
                                        onClick = {},
                                        label = { Text(time) },
                                        trailingIcon = {
                                            Icon(
                                                Icons.Filled.Close,
                                                contentDescription = "删除该时刻",
                                                modifier = Modifier.size(16.dp).clickable {
                                                    vm.removeSnoozeTime(index)
                                                },
                                            )
                                        },
                                    )
                                }
                            }
                            Spacer(Modifier.height(6.dp))
                        }
                        // V4-3：统一用时钟表盘填写（表盘内含键盘输入模式，不再额外出输入框）
                        OutlinedButton(onClick = { showSnoozeTimePicker = true }) {
                            Text("+ 添加时刻（时钟表盘）")
                        }

                        Spacer(Modifier.height(8.dp))
                        // V2 快捷相对时长（保留兼容；时刻列表为空时使用）
                        Text(
                            text = "快捷（相对时长，时刻列表为空时生效）：",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(4.dp))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            SnoozePresetChip("10 分钟", SnoozePresets.M10, state.snoozePreset, vm::setSnoozePreset)
                            SnoozePresetChip("30 分钟", SnoozePresets.M30, state.snoozePreset, vm::setSnoozePreset)
                            SnoozePresetChip("1 小时", SnoozePresets.H1, state.snoozePreset, vm::setSnoozePreset)
                            SnoozePresetChip("今晚 8 点", SnoozePresets.TONIGHT_20, state.snoozePreset, vm::setSnoozePreset)
                        }
                        Spacer(Modifier.height(6.dp))
                        OutlinedTextField(
                            value = state.snoozeCustomMinutes,
                            onValueChange = {
                                vm.setSnoozeCustomMinutes(it)
                                vm.setSnoozePreset(SnoozePresets.CUSTOM_MINUTES)
                            },
                            label = { Text("自定义分钟") },
                            placeholder = { Text("如 45") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }

                // —— 替换参数区（V2 双模板）——
                if (state.replaceOn) {
                    Spacer(Modifier.height(8.dp))
                    NfCard {
                        Text(
                            text = "改写方式（三选）",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(6.dp))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            RewriteMode.entries.forEach { mode ->
                                FilterChip(
                                    selected = state.replaceMode == mode,
                                    onClick = { vm.setReplaceMode(mode) },
                                    label = { Text(mode.label) },
                                )
                            }
                        }
                        if (state.replaceMode == RewriteMode.CUSTOM_TEXT) {
                            Spacer(Modifier.height(8.dp))
                            OutlinedTextField(
                                value = titleTemplateValue,
                                onValueChange = {
                                    titleTemplateValue = it
                                    vm.setReplaceTitleTemplate(it.text)
                                },
                                label = { Text("通知标题模板（留空回落原标题）") },
                                placeholder = { Text("如：[{app}] {title}") },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .onFocusChanged { if (it.isFocused) focusedTemplate = 0 },
                            )
                            Spacer(Modifier.height(6.dp))
                            OutlinedTextField(
                                value = textTemplateValue,
                                onValueChange = {
                                    textTemplateValue = it
                                    vm.setReplaceTextTemplate(it.text)
                                },
                                label = { Text("通知内容模板（留空回落原正文）") },
                                placeholder = { Text("如：来自 {app}：{text}") },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .onFocusChanged { if (it.isFocused) focusedTemplate = 1 },
                            )
                            // V9.4：{re1}..{re9}「即时提取」的可用上界 = 激活提取器的最大列表号。
                            // 不传它的话，在替换文案里写 {re1} 会被误报成「未识别占位符」。
                            val reCount = remember(state.extractors, state.aggregationOn) {
                                // 跟随「内容提取」总开关：关着时运行时不会提取，故上界按 0 判
                                if (!state.aggregationOn) {
                                    0
                                } else {
                                    state.extractors.filter { it.isActive }
                                        .maxOfOrNull { it.listIndex } ?: 0
                                }
                            }
                            val unknownTokens = (
                                Placeholders.findUnknown(state.replaceTitleTemplate, reCount = reCount) +
                                    Placeholders.findUnknown(state.replaceTextTemplate, reCount = reCount)
                                ).distinct()
                            if (unknownTokens.isNotEmpty()) {
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    text = "未识别的占位符（渲染时按空串，保存时会再确认）：" +
                                        unknownTokens.joinToString("、"),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                                // V9.6：{reN} 被判「未识别」最常见的原因是根本没配提取器。
                                // 只说「未识别」用户无从下手（真机反馈：「我填 {re1} 会说没有这个东西」），
                                // 这里直接把「要做什么」写出来。
                                if (unknownTokens.any { it.startsWith("{re") }) {
                                    Text(
                                        text = "关于 {re1}~{re9}：它对应「⑤ 内容提取与汇总」里的第 N 个提取器。" +
                                            "请先打开该区开关、添加提取器、填好正则并勾选启用——" +
                                            "三个条件缺一不可（启用 + 正则非空 + 条数上限 > 0）。" +
                                            "配好之前它会被当成未识别占位符。",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            }
                            Spacer(Modifier.height(6.dp))
                            PlaceholderChips(
                                onInsert = insertPlaceholder,
                                extrasTokens = state.selectedFields
                                    .filterIsInstance<FieldRef.Extras>()
                                    .map { "{extras.${it.key}}" },
                                // V9.4：{re1}..{re9} 即时提取 —— 用提取器的正则从**当前这条
                                // 通知**里抓内容重新显示。开启「内容提取」并配好提取器后出现。
                                extractTokens = Placeholders.extractTokens(
                                    state.takeIf { it.aggregationOn }?.toAggregationConfig()
                                ),
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = "两个模板都可独立填写/留空，保存时至少填一个；" +
                                    "{time} 为发送时间（yyyy-MM-dd HH:mm）；" +
                                    "Extras 键名大小写敏感，通知中没有该键时按空串。",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        ToggleRow("保留图标", state.keepIcon, vm::toggleKeepIcon)
                        ToggleRow("保留大图", state.keepLargeImage, vm::toggleKeepLargeImage)
                        ToggleRow("渠道名用原应用名", state.channelUseAppName, vm::toggleChannelUseAppName)
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            // ==================== 占位符速查（默认收起，不打断主流程） ====================
            // V9.8：原来是一张常驻展开的大卡片，夹在「动作」和「内容提取」之间，
            // 十几行 chips + 三段说明把主流程冲散了。改成可折叠分节：
            // 需要时点开、点一下即复制，平时不占位置。
            CollapsibleSection(
                title = "占位符速查",
                summary = "点开查看全部写法，点一下即复制",
                defaultExpanded = false,
                titleColor = MaterialTheme.colorScheme.primary,
            ) {
                PlaceholderReferenceCard(
                    onCopy = { token -> clipboard.setText(AnnotatedString(token)) },
                )
            }

            Spacer(Modifier.height(16.dp))

            // ==================== ⑤ 内容提取与汇总（V7 需求 8） ====================
            AggregationEditorSection(vm = vm, state = state)

            Spacer(Modifier.height(16.dp))

            // ==================== ⑥ 悬浮显示条件（V7 需求 7） ====================
            OverlayTriggerEditorSection(vm = vm, state = state)

            Spacer(Modifier.height(16.dp))

            // ==================== ⑦ 设备状态生效条件（V3 需求⑦） ====================
            val ruleDeviceCondition = DeviceStateCondition(
                screenStates = state.deviceScreenStates,
                chargeStates = state.deviceChargeStates,
                timeWindows = state.deviceTimeWindows,
                weekdays = state.deviceWeekdays,
            )
            CollapsibleSection(
                title = "⑦ 设备状态生效条件",
                summary = ruleDeviceCondition.summary().ifBlank { "无限制" },
                titleColor = MaterialTheme.colorScheme.primary,
            ) {
                DeviceStateFields(
                    condition = ruleDeviceCondition,
                    onChange = vm::setDeviceState,
                    showIntro = true,
                )
            }

            Spacer(Modifier.height(16.dp))

            // ==================== ⑧ 测试（编辑页底部 · 主路径） ====================
            // 展开态由本页持有：CollapsibleSection 的展开态是内部 rememberSaveable，
            // 外部无法强制展开，故这里用本文件内的 NfSectionCard 接管（交互与样式一致）。
            NfSectionCard(
                title = "⑧ 测试",
                expanded = testSectionExpanded,
                onToggleExpanded = { testSectionExpanded = !testSectionExpanded },
                modifier = Modifier.onGloballyPositioned { testSectionCoords = it },
            ) {
                Spacer(Modifier.height(6.dp))
                RuleTestPanel(
                    // 试跑用「已保存规则 + 当前草稿」：不保存也能用眼前这份草稿判定
                    rulesProvider = vm::loadRulesForTest,
                    recordsProvider = vm::loadRecentRecords,
                    onOpenDetail = { recordId, _ -> onOpenDetail(recordId) },
                    // 高亮用「当前草稿关键词」：改了关键词不必先保存，高亮立刻跟着变
                    draftKeywords = state.keywords,
                    autoRunToken = state.testRunToken,
                    // 第一条结果的定位锚点（面板在渲染结果块时挂载）
                    resultsAnchorModifier = Modifier.onGloballyPositioned { firstResultCoords = it },
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }

            // 正文末尾留白：避免最后一张卡片贴住悬浮保存条
            Spacer(Modifier.height(16.dp))

            // ==================== V8 任务 2：保存并测试的三步链路 ====================
            // 第一步：展开测试区（testRunToken 变化 ⇔ 保存成功且点了「保存并测试」）
            LaunchedEffect(state.testRunToken) {
                if (state.testRunToken > 0) {
                    testSectionExpanded = true
                    pendingScrollToResult = true
                }
            }

            // 第二步：试跑由 RuleTestPanel 内部消费 autoRunToken 自动触发
            // （复用现有试跑入口，本页不另写匹配逻辑）。

            // 第三步：滚动定位到第一条结果（锚点有值 ⇔ 结果已渲染）
            LaunchedEffect(pendingScrollToResult, firstResultY) {
                if (!pendingScrollToResult || firstResultY <= 0) return@LaunchedEffect
                // 等展开动画（expandVertically）与结果行完成一次布局
                delay(180L)
                val topGap = with(density) { 12.dp.roundToPx() }
                scrollState.animateScrollTo((firstResultY - topGap).coerceAtLeast(0))
                // 结果行渲染后 maxValue 会变大，再补一次对齐（两次都很便宜）
                delay(80L)
                scrollState.animateScrollTo((firstResultY - topGap).coerceAtLeast(0))
                pendingScrollToResult = false
            }

            // 兜底：一条结果都没有（素材为空 / 试跑失败）时，至少把测试区滚进视野
            LaunchedEffect(pendingScrollToResult) {
                if (!pendingScrollToResult) return@LaunchedEffect
                delay(1500L)
                if (!pendingScrollToResult) return@LaunchedEffect
                if (testSectionY > 0) {
                    val topGap = with(density) { 12.dp.roundToPx() }
                    scrollState.animateScrollTo((testSectionY - topGap).coerceAtLeast(0))
                }
                pendingScrollToResult = false
            }
        }
    }
}

// ==================== 子组件 ====================

/**
 * 分节卡片的「受控」版本（V8）：展开态由调用方持有。
 *
 * 只有 ⑧ 测试需要它 ——「保存并测试」必须在外部把卡片程序化展开，
 * 而公共 `CollapsibleSection` 的展开态是内部的 `rememberSaveable(title)`（无法外部接管，
 * 且那套逻辑还有别处依赖，不能改）。其余分节（①②③④⑦）直接用
 * `CollapsibleSection(titleColor = primary)`，不必重复实现。
 *
 * 视觉与 `CollapsibleSection` 保持一致（同样的 Card / 标题行 / 展开动画），
 * 差别只有两点：① 展开态受控；② 标题固定用主题色。
 *
 * @param title 标题（主题色加粗）
 * @param expanded 当前是否展开
 * @param onToggleExpanded 点击标题行时的切换回调
 * @param summary 收起时的摘要（可选）
 * @param modifier 作用于卡片本体（⑧ 测试用它挂滚动锚点）
 */
@Composable
private fun NfSectionCard(
    title: String,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    summary: String? = null,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth().padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggleExpanded)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                // V8：大标题用主题色
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f),
            )
            if (!expanded && summary != null) {
                Text(
                    text = summary,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false).padding(end = 4.dp),
                )
            }
            Icon(
                imageVector = if (expanded) Icons.Filled.KeyboardArrowUp
                else Icons.Filled.KeyboardArrowDown,
                contentDescription = if (expanded) "收起" else "展开",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(),
            exit = shrinkVertically(),
        ) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                content()
            }
        }
    }
}

/** 模式单选（八项互斥；FlowRow chips + 选中项 desc 由调用方展示）。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ModeSelector(mode: MatchMode, onSelect: (MatchMode) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        MatchMode.entries.forEach { m ->
            FilterChip(
                selected = mode == m,
                onClick = { onSelect(m) },
                label = { Text(m.label) },
            )
        }
    }
}

/**
 * A/B 组关键词编辑器（V10.4：**标签化**）。
 *
 * 旧实现是「一行一个长输入框」，几个关键词就把表单撑得很高、也不好扫。
 * 现在改为：已确认的词显示成小方块（chip）——**点方块进入编辑、点叉删除**；
 * 输入框只在「新增 / 编辑中」时出现，确认后立刻收成方块。
 *
 * 编辑中的词会先记在 [editingIndex] 里，确认时原位替换；不确认就点别处也不会丢原文，
 * 因为 chip 本身仍显示旧值（只是输入框里是副本）。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun KeywordEditor(
    title: String,
    rows: List<String>,
    onChange: (Int, String) -> Unit,
    onAdd: () -> Unit,
    onRemove: (Int) -> Unit,
) {
    /** null = 正在新增；非 null = 正在编辑该下标的词。 */
    var editingIndex by remember { mutableStateOf<Int?>(null) }
    var editText by remember { mutableStateOf("") }

    fun commit() {
        val text = editText.trim()
        val idx = editingIndex
        when {
            text.isEmpty() -> Unit // 空输入直接取消，不产生空 chip
            idx != null && idx in rows.indices -> onChange(idx, text)
            else -> {
                // 新增：onAdd 先在末尾补一行，新下标 = 追加前的 size
                onAdd()
                onChange(rows.size, text)
            }
        }
        editingIndex = null
        editText = ""
    }

    Column {
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(6.dp))

        // —— 已有关键词：小方块 ——
        val visible = rows.withIndex().filter { it.value.isNotBlank() }
        if (visible.isNotEmpty()) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                visible.forEach { (index, keyword) ->
                    KeywordChip(
                        text = keyword,
                        onEdit = {
                            editingIndex = index
                            editText = keyword
                        },
                        onRemove = {
                            if (editingIndex == index) {
                                editingIndex = null
                                editText = ""
                            }
                            onRemove(index)
                        },
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
        }

        // —— 输入框：只在新增/编辑时出现（确认后收成方块，不再常驻长条输入框）——
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = editText,
                onValueChange = { editText = it },
                placeholder = {
                    Text(if (editingIndex == null) "输入关键词后回车" else "修改后回车确认")
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { commit() }),
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.size(6.dp))
            TextButton(
                onClick = { commit() },
                enabled = editText.isNotBlank() || editingIndex != null,
            ) {
                Text(if (editingIndex == null) "添加" else "确定")
            }
            if (editingIndex != null) {
                TextButton(onClick = {
                    editingIndex = null
                    editText = ""
                }) { Text("取消") }
            }
        }
    }
}

/** 一个关键词小方块：点文字进入编辑，点右侧叉删除。 */
@Composable
private fun KeywordChip(
    text: String,
    onEdit: () -> Unit,
    onRemove: () -> Unit,
) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.secondaryContainer,
        // 整个方块可点 → 进入编辑
        modifier = Modifier.clickable(onClick = onEdit),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 10.dp, end = 4.dp, top = 5.dp, bottom = 5.dp),
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Spacer(Modifier.size(2.dp))
            Icon(
                imageVector = Icons.Filled.Close,
                contentDescription = "删除该关键词",
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                // 单独接点击：点叉只删，不会先触发「编辑」
                modifier = Modifier
                    .size(18.dp)
                    .clipToBounds()
                    .clickable(onClick = onRemove),
            )
        }
    }
}

/** 各文本模式的关键词区标题括注。 */
private fun modeKeywordHint(mode: MatchMode): String = when (mode) {
    MatchMode.TEXT_ANY -> "任一出现即命中"
    MatchMode.TEXT_ALL -> "每个都要出现"
    MatchMode.TEXT_NOT_ANY -> "都不出现才命中"
    MatchMode.TEXT_NOT_ALL -> "非全部出现即命中"
    else -> ""
}

/** 各文本模式的白话解释（不区分大小写为共同约定）。 */
private fun modeExplainer(mode: MatchMode): String = when (mode) {
    MatchMode.TEXT_ANY -> "任一关键词出现在任一被选字段即命中（关键词之间为「或」）"
    MatchMode.TEXT_ALL -> "每个关键词都要出现，可以分别出现在不同字段（不区分大小写）"
    MatchMode.TEXT_NOT_ANY -> "所有关键词都不出现才命中（关键词之间为「且」）"
    MatchMode.TEXT_NOT_ALL -> "不是所有关键词都出现，即命中（至少一个词没出现）"
    MatchMode.TEXT_A_NOT_B -> "A 组任一出现，且 B 组一个都不出现（不区分大小写）"
    MatchMode.TEXT_GROUPS ->
        "分多组，组间全部满足才命中；组内『任一出现』或『全部出现』可选（不区分大小写）"
    else -> ""
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SnoozePresetChip(
    label: String,
    preset: String,
    current: String?,
    onSelect: (String) -> Unit,
) {
    FilterChip(
        selected = current == preset,
        onClick = { onSelect(preset) },
        label = { Text(label) },
    )
}

/**
 * 动作选择卡（V2 图标化，§6.2 三重编码）：语义图标圆牌 + Checkbox + 选中态语义色描边。
 * 退役 V1 的 ☑/☐ 字符勾选。
 */
@Composable
private fun ActionCard(
    kind: ActionKind,
    checked: Boolean,
    title: String,
    desc: String,
    onToggle: () -> Unit,
    enabled: Boolean = true,
) {
    val colors = actionColorsOf(kind)
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .alpha(if (enabled) 1f else 0.4f)
            .clickable(enabled = enabled, onClick = onToggle),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        // 选中态：动作语义色描边（未选中保持默认无边框）
        border = if (checked) BorderStroke(1.5.dp, colors.accent) else null,
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier.size(36.dp).clip(CircleShape)
                    .background(
                        if (checked) colors.container
                        else MaterialTheme.colorScheme.surfaceVariant
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = kind.defaultIcon(),
                    contentDescription = null,
                    tint = if (checked) colors.onContainer
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
            Spacer(Modifier.size(10.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Text(
                    text = desc,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Checkbox(checked = checked, onCheckedChange = { onToggle() }, enabled = enabled)
        }
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onToggle: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = { onToggle() })
    }
}
