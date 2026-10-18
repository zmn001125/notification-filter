package com.local.notificationfilter.ui.screens.rules

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.local.notificationfilter.core.model.ChargeState
import com.local.notificationfilter.core.model.DeviceStateCondition
import com.local.notificationfilter.core.model.OverlayTrigger
import com.local.notificationfilter.core.model.OverlayTriggerKind
import com.local.notificationfilter.core.model.Rule
import com.local.notificationfilter.core.model.ScreenState
import com.local.notificationfilter.core.model.SnoozeCalculator
import com.local.notificationfilter.core.model.TimeWindow
import com.local.notificationfilter.core.model.TriggerEdge
import com.local.notificationfilter.core.model.TriggerEffect
import com.local.notificationfilter.core.model.Weekday
import com.local.notificationfilter.ui.components.AppPickerDialog
import com.local.notificationfilter.ui.components.CollapsibleSection
import com.local.notificationfilter.ui.components.NfCard
import com.local.notificationfilter.ui.components.SwitchField
import com.local.notificationfilter.ui.components.TimePickerDialog
import com.local.notificationfilter.util.AppUtils
import com.local.notificationfilter.util.PermissionChecker
import com.local.notificationfilter.util.PermissionType

/**
 * 规则编辑页 §⑥「悬浮显示条件」（V7 需求 7）。
 *
 * 语义（唯一实现 `OverlayTriggerEvaluator`，本文件只负责编辑）：
 * - 多条条件之间是 **OR**（任一满足即触发）；
 * - 每条条件有「效果（显示 / 隐藏）」+「条件不成立时也触发（取反）」两个独立开关；
 * - 参数没配齐的条件在编译期被丢弃（`OverlayTrigger.isEffective`），UI 用红字提前告知；
 * - 显示前置是「④ 动作 → 自定义位置悬浮」已勾选（否则本节无意义）；
 * - V12：每条条件还可选「满足时作用于哪些规则的悬浮窗」（`OverlayTrigger.targetRuleIds`
 *   多选，留空 = 本条规则自己的），于是不同规则的悬浮窗可同时在屏幕上显示、互不顶掉。
 *
 * @param vm 规则编辑 ViewModel（表单状态唯一所有者）
 * @param state 当前表单快照
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun OverlayTriggerEditorSection(vm: RuleEditViewModel, state: RuleEditUiState) {
    val effective = state.floatTriggers.count { it.isEffective }
    val summary = if (effective == 0) "无（命中即显示）" else "$effective 条条件"

    CollapsibleSection(
        title = "⑥ 悬浮显示条件",
        summary = summary,
        defaultExpanded = false,
        // V8：页面级分节大标题跟主题色
        titleColor = MaterialTheme.colorScheme.primary,
    ) {
        if (!state.floatOn) {
            Text(
                text = "先在上方「④ 动作」里勾选「自定义位置悬浮」，本节的条件才有意义。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@CollapsibleSection
        }

        Spacer(Modifier.height(4.dp))
        Text(
            text = "任一条件满足即触发（OR）；同时命中「显示」与「消失」时以消失为准。" +
                "不同规则的悬浮窗可同时在屏幕上显示，互不顶掉；每条条件可指定作用于哪条规则的" +
                "悬浮窗，留空 = 本条规则自己的。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (state.floatTriggers.isEmpty()) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = "未设置条件：卡片会一直显示到手动清除或 30 分钟兜底",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        var rulePickerFor by remember { mutableStateOf<Int?>(null) }
        // V12：「满足时作用于哪些规则的悬浮窗」多选弹窗的持有者（节级，避免每张卡各持一份）
        var targetPickerFor by remember { mutableStateOf<Int?>(null) }

        state.floatTriggers.forEachIndexed { index, trigger ->
            Spacer(Modifier.height(8.dp))
            TriggerCard(
                index = index,
                trigger = trigger,
                selectableRules = state.selectableRules,
                rulesLoading = state.rulesLoading,
                onUpdate = { transform -> vm.updateTrigger(index, transform) },
                onRemove = { vm.removeTrigger(index) },
                onOpenRulePicker = {
                    vm.loadSelectableRules()
                    rulePickerFor = index
                },
                onOpenTargetPicker = {
                    vm.loadSelectableRules()
                    targetPickerFor = index
                },
            )
        }

        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = { vm.addTrigger(OverlayTriggerKind.DEVICE_STATE) }) {
            // V9：图标已经是「+」，文案里不要再带一个「+」，否则显示成「＋ ＋添加条件」
            Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.size(4.dp))
            Text("添加条件")
        }

        // 规则多选对话框（放在卡片外，避免每张卡各自持有一份弹窗状态）
        // 条件被删掉时自动关掉对话框（不在组合期写状态，避免重组抖动）
        LaunchedEffect(rulePickerFor, state.floatTriggers.size) {
            val current = rulePickerFor
            if (current != null && current >= state.floatTriggers.size) {
                rulePickerFor = null
            }
        }
        rulePickerFor?.let { index ->
            val trigger = state.floatTriggers.getOrNull(index)
            if (trigger != null) {
                RulePickerDialog(
                    rules = state.selectableRules,
                    loading = state.rulesLoading,
                    initialSelected = trigger.ruleIds,
                    onDismiss = { rulePickerFor = null },
                    onConfirm = { ids ->
                        vm.updateTrigger(index) { it.copy(ruleIds = ids) }
                        rulePickerFor = null
                    },
                )
            }
        }

        // V12：目标规则多选对话框（条件满足时作用于哪些规则的悬浮窗）
        LaunchedEffect(targetPickerFor, state.floatTriggers.size) {
            val current = targetPickerFor
            if (current != null && current >= state.floatTriggers.size) {
                targetPickerFor = null
            }
        }
        targetPickerFor?.let { index ->
            val trigger = state.floatTriggers.getOrNull(index)
            if (trigger != null) {
                TargetRulePickerDialog(
                    rules = state.selectableRules,
                    loading = state.rulesLoading,
                    initialSelected = trigger.allTargetRuleIds,
                    onDismiss = { targetPickerFor = null },
                    onConfirm = { ids ->
                        // 空集合 = 只作用于本条规则自己的悬浮窗（旧单选字段不再写入）
                        vm.updateTrigger(index) { it.copy(targetRuleIds = ids.toList()) }
                        targetPickerFor = null
                    },
                )
            }
        }

        Spacer(Modifier.height(8.dp))
        Text(
            // V9 语义修正：条件**不接管**卡片生命周期，只做「提前显示 / 提前清除」。
            text = "说明：条件的唯一作用是「提前显示悬浮」或「立即清除悬浮」；" +
                "悬浮的内容、位置、停留时长、动画等全部仍按上面的悬浮样式执行。" +
                "没配条件时，悬浮照常随通知出现并按时消失。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ==================== 单条条件卡片 ====================

/** 一条条件：类型 chips + 参数区 + 效果 chips + 目标规则 + 取反开关 + 删除。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TriggerCard(
    index: Int,
    trigger: OverlayTrigger,
    selectableRules: List<Rule>,
    rulesLoading: Boolean,
    onUpdate: ((OverlayTrigger) -> OverlayTrigger) -> Unit,
    onRemove: () -> Unit,
    onOpenRulePicker: () -> Unit,
    onOpenTargetPicker: () -> Unit,
) {
    NfCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "#${index + 1} ${trigger.summary()}",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onRemove) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = "删除这条条件",
                    modifier = Modifier.size(18.dp),
                )
            }
        }

        // —— 类型 ——
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            OverlayTriggerKind.ALL.forEach { kind ->
                FilterChip(
                    selected = trigger.kindEnum == kind,
                    onClick = { onUpdate { it.copy(kind = kind.name) } },
                    label = { Text(kind.label) },
                )
            }
        }

        Spacer(Modifier.height(6.dp))

        // —— 参数区（按类型）——
        when (trigger.kindEnum) {
            OverlayTriggerKind.DEVICE_STATE -> DeviceStateFields(
                condition = trigger.device,
                onChange = { cond -> onUpdate { it.copy(device = cond) } },
                showIntro = false,
            )

            OverlayTriggerKind.CLOCK_AT -> ClockAtFields(
                clockAt = trigger.clockAt,
                onChange = { hhmm -> onUpdate { it.copy(clockAt = hhmm) } },
            )

            OverlayTriggerKind.APP_NOTIFICATION -> AppNotificationFields(
                packages = trigger.packages,
                onChange = { pkgs -> onUpdate { it.copy(packages = pkgs) } },
            )

            OverlayTriggerKind.FOREGROUND_APP -> ForegroundAppFields(
                packages = trigger.packages,
                onChange = { pkgs -> onUpdate { it.copy(packages = pkgs) } },
            )

            OverlayTriggerKind.RULE_NOTIFICATION -> {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedButton(onClick = onOpenRulePicker) { Text("选择规则") }
                    Text(
                        text = if (trigger.ruleIds.isEmpty()) {
                            "未选择"
                        } else {
                            trigger.ruleIds.joinToString("、") { id ->
                                selectableRules.firstOrNull { it.id == id }
                                    ?.let { it.name.ifBlank { "规则 ${it.id}" } }
                                    ?: "规则 $id"
                            }
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                }
                Text(
                    text = if (rulesLoading) "正在加载规则列表…" else "只能引用其他已保存的规则",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // 引用到的规则若已删除，提示失效（不影响保存，求值时按"永不满足"处理）
                val missing = trigger.ruleIds.filter { id -> selectableRules.none { it.id == id } }
                if (!rulesLoading && selectableRules.isNotEmpty() && missing.isNotEmpty()) {
                    Text(
                        text = "有 ${missing.size} 条被引用的规则已不存在：${missing.joinToString("、")}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }

        // 通知类条件的边沿（出现 / 消失）
        if (trigger.kindEnum == OverlayTriggerKind.APP_NOTIFICATION ||
            trigger.kindEnum == OverlayTriggerKind.RULE_NOTIFICATION
        ) {
            Spacer(Modifier.height(4.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TriggerEdge.entries.forEach { edge ->
                    FilterChip(
                        selected = trigger.edge == edge,
                        onClick = { onUpdate { it.copy(edge = edge) } },
                        label = { Text(edge.label) },
                    )
                }
            }
        }

        // —— 效果 ——
        Spacer(Modifier.height(8.dp))
        Text(
            text = "满足时：",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TriggerEffect.entries.forEach { effect ->
                FilterChip(
                    selected = trigger.effect == effect,
                    onClick = { onUpdate { it.copy(effect = effect) } },
                    label = { Text(effect.label) },
                )
            }
        }

        // —— V12：满足时作用于哪条规则的悬浮窗 ——
        Spacer(Modifier.height(8.dp))
        Text(
            text = "满足时作用于：",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(onClick = onOpenTargetPicker) {
                Text(
                    if (trigger.allTargetRuleIds.isEmpty()) {
                        "当前规则的悬浮窗"
                    } else {
                        "${trigger.allTargetRuleIds.size} 条其他规则的悬浮窗"
                    },
                )
            }
            Text(
                text = if (trigger.allTargetRuleIds.isEmpty()) {
                    "（留空 = 本条规则自己的悬浮窗）"
                } else {
                    trigger.allTargetRuleIds.joinToString("、") { id ->
                        selectableRules.firstOrNull { it.id == id }
                            ?.let { it.name.ifBlank { "规则 ${it.id}" } }
                            ?: "规则 $id"
                    }
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
        }
        // 任一目标规则不存在（或没有配置悬浮动作）→ 红字提示（保存不受影响，
        // 求值时该条件按「目标缺失」跳过，见 OverlayConditionArbiter.applyShow）
        val missingTargets = trigger.allTargetRuleIds.filter { id ->
            selectableRules.none { it.id == id && it.actions.float != null }
        }
        if (missingTargets.isNotEmpty() && !rulesLoading && selectableRules.isNotEmpty()) {
            Text(
                text = "有 ${missingTargets.size} 条目标规则不存在或未配置悬浮",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        // —— 取反 ——
        SwitchField(
            label = "条件不成立时也触发（取反）",
            checked = trigger.hideWhenFalse,
            onCheckedChange = { v -> onUpdate { it.copy(hideWhenFalse = v) } },
            subtitle = "例：在时间段内显示、离开时段就消失",
        )

        // —— 未配齐提示 ——
        if (!trigger.isEffective) {
            Text(
                text = "参数未配齐，此条件不会生效（保存后会被引擎丢弃）",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

// ==================== 各类条件的参数区 ====================

/** `CLOCK_AT`：每天到点时刻（V8：共用 [TimePickerDialog]，表盘上方带 HH:mm 输入框）。 */
@Composable
private fun ClockAtFields(clockAt: String, onChange: (String) -> Unit) {
    var picker by remember { mutableStateOf(false) }
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedButton(onClick = { picker = true }) { Text("选择时刻") }
        Text(
            text = if (SnoozeCalculator.parseHHmm(clockAt) != null) "每天 $clockAt" else "未设置",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (picker) {
        TimePickerDialog(
            title = "选择每天的时刻",
            initial = clockAt,
            onDismiss = { picker = false },
            onConfirm = { hhmm ->
                onChange(hhmm)
                picker = false
            },
        )
    }
}

/** `APP_NOTIFICATION`：指定 App 集合（复用 [AppPickerDialog]）。 */
@Composable
private fun AppNotificationFields(packages: Set<String>, onChange: (Set<String>) -> Unit) {
    var showPicker by remember { mutableStateOf(false) }
    AppPickRow(
        packages = packages,
        buttonText = "选择 App",
        onOpen = { showPicker = true },
        onRemove = { pkg -> onChange(packages - pkg) },
    )
    if (showPicker) {
        AppPickerDialog(
            title = "选择 App（这些 App 的通知会触发）",
            initialSelected = packages,
            onDismiss = { showPicker = false },
            onConfirm = { selected ->
                onChange(selected)
                showPicker = false
            },
        )
    }
}

/**
 * `FOREGROUND_APP`：打开了某个 App（无障碍服务上报的前台包名）。
 *
 * 未授权无障碍时给出红字 + 「去授权」按钮——去的是系统无障碍列表页，
 * 回本页后靠 [permissionTick] 自增重新读取授权状态。
 */
@Composable
private fun ForegroundAppFields(packages: Set<String>, onChange: (Set<String>) -> Unit) {
    val context = LocalContext.current
    var showPicker by remember { mutableStateOf(false) }
    var permissionTick by remember { mutableIntStateOf(0) }
    val granted = remember(permissionTick) {
        PermissionChecker.isAccessibilityGranted(context)
    }

    AppPickRow(
        packages = packages,
        buttonText = "选择 App",
        onOpen = { showPicker = true },
        onRemove = { pkg -> onChange(packages - pkg) },
    )
    if (!granted) {
        Spacer(Modifier.height(4.dp))
        Text(
            text = "未授予无障碍服务，此条件不会生效",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.error,
        )
        OutlinedButton(onClick = {
            // 与权限引导页同一入口（T04：无障碍列表页，失败回落应用详情页）
            PermissionChecker.openSettings(PermissionType.ACCESSIBILITY, context)
            permissionTick++
        }) { Text("去授权") }
    }
    if (showPicker) {
        AppPickerDialog(
            title = "选择 App（打开这些 App 时触发）",
            initialSelected = packages,
            onDismiss = { showPicker = false },
            onConfirm = { selected ->
                onChange(selected)
                showPicker = false
            },
        )
    }
}

/**
 * 「选择 App」按钮 + 已选应用名（两处复用）。
 *
 * V12：已选 App 用 [InputChip] 展示，点 chip 本体即删除（[onRemove]），
 * 「选择 App」按钮仍是增选入口。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AppPickRow(
    packages: Set<String>,
    buttonText: String,
    onOpen: () -> Unit,
    onRemove: (String) -> Unit,
) {
    val context = LocalContext.current
    OutlinedButton(onClick = onOpen) { Text(buttonText) }
    if (packages.isEmpty()) {
        Text(
            text = "未选择（此条件不会生效）",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    } else {
        Spacer(Modifier.height(4.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            packages.forEach { pkg ->
                InputChip(
                    selected = true,
                    onClick = { onRemove(pkg) },
                    label = { Text(AppUtils.appNameOf(context, pkg)) },
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

/**
 * 规则多选对话框（`RULE_NOTIFICATION` 用）：排除自身已由 ViewModel 完成。
 *
 * @param loading 数据源加载中（空态文案据此区分「加载中 / 没有其他规则」）
 */
@Composable
private fun RulePickerDialog(
    rules: List<Rule>,
    loading: Boolean,
    initialSelected: Set<Long>,
    onDismiss: () -> Unit,
    onConfirm: (Set<Long>) -> Unit,
) {
    var selected by remember { mutableStateOf(initialSelected) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择规则") },
        text = {
            Column(modifier = Modifier.heightIn(max = 400.dp)) {
                Text(
                    text = "任一规则命中通知即满足（只能引用其他已保存的规则）",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                when {
                    loading && rules.isEmpty() -> Text(
                        text = "正在加载规则…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    rules.isEmpty() -> Text(
                        text = "还没有其他已保存的规则可引用",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    else -> LazyColumn {
                        items(rules.size) { i ->
                            val rule = rules[i]
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(
                                    checked = rule.id in selected,
                                    onCheckedChange = { checked ->
                                        selected = if (checked) selected + rule.id
                                        else selected - rule.id
                                    },
                                )
                                Text(
                                    text = rule.name.ifBlank { "规则 ${rule.id}" },
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(selected) }) { Text("确定") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

// ==================== 目标规则多选对话框（V12） ====================

/**
 * 目标规则**多选**对话框（V12：条件满足时作用于哪些规则的悬浮窗）。
 *
 * 首项「当前规则的悬浮窗（默认）」是特殊项：勾选它 = 清空其它勾选
 * （语义 = 只作用于本条规则自己）；未勾选时正常多选其它规则。
 * 其余只列**已配置悬浮动作**的规则（没有悬浮样式的规则被指向也没有窗口可作用，
 * 与 TriggerIndex.targets 同口径）。排除自身已由 ViewModel 的 `loadSelectableRules` 完成。
 *
 * @param rules 可选规则（ViewModel 已排除自身）
 * @param loading 数据源加载中
 * @param initialSelected 当前已选中的目标规则 id 集合（空 = 当前规则）
 */
@Composable
private fun TargetRulePickerDialog(
    rules: List<Rule>,
    loading: Boolean,
    initialSelected: Set<Long>,
    onDismiss: () -> Unit,
    onConfirm: (Set<Long>) -> Unit,
) {
    var selected by remember { mutableStateOf(initialSelected) }
    val candidates = rules.filter { it.actions.float != null }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择目标规则（可多选）") },
        text = {
            Column(modifier = Modifier.heightIn(max = 400.dp)) {
                Text(
                    text = "勾选『当前规则』= 只作用于本条规则自己的悬浮窗；" +
                        "多选 = 同时作用于多条规则的悬浮窗。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                when {
                    loading && rules.isEmpty() -> Text(
                        text = "正在加载规则…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    candidates.isEmpty() -> Text(
                        text = "没有其他已配置悬浮动作的规则",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    else -> LazyColumn {
                        item {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(
                                    // 特殊项：仅当未勾选任何其它规则时呈勾选态
                                    checked = selected.isEmpty(),
                                    onCheckedChange = { checked ->
                                        // 勾选 = 清空其它勾选（只作用于本条规则自己）；
                                        // 取消勾选无意义（空集合即当前规则），不做处理
                                        if (checked) selected = emptySet()
                                    },
                                )
                                Text(
                                    text = "当前规则的悬浮窗（默认）",
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                        items(candidates.size) { i ->
                            val rule = candidates[i]
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(
                                    checked = rule.id in selected,
                                    onCheckedChange = { checked ->
                                        selected = if (checked) selected + rule.id
                                        else selected - rule.id
                                    },
                                )
                                Text(
                                    text = rule.name.ifBlank { "规则 ${rule.id}" },
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(selected) }) {
                Text(if (selected.isEmpty()) "确定" else "确定（${selected.size}）")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

// ==================== 设备状态条件（规则级与条件级共用的控件组） ====================

/**
 * 设备状态条件编辑控件组（屏幕 / 充电 / 时间窗口 / 星期）。
 *
 * **组件级复用**：规则编辑页 §⑦「设备状态生效条件」与 §⑥ 的 `DEVICE_STATE` 条件
 * 用的是同一套控件、同一个回调形状（整体 [DeviceStateCondition] 进出），
 * 避免两处各写一套导致口径漂移。
 *
 * @param condition 当前条件（全空 = 不限制）
 * @param onChange 任一子项变化后的**完整**条件
 * @param showIntro 是否显示组首说明（规则级 true；条件级 false，因为卡内已有上下文）
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DeviceStateFields(
    condition: DeviceStateCondition,
    onChange: (DeviceStateCondition) -> Unit,
    showIntro: Boolean = true,
) {
    if (showIntro) {
        Text(
            text = "全部 AND；某项不选 = 不限制。试跑不评估设备状态（需真机验证）。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
    }

    // —— 屏幕状态 ——
    Text(
        text = "屏幕状态",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(4.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        ScreenState.entries.forEach { state ->
            FilterChip(
                selected = state in condition.screenStates,
                onClick = {
                    val next = if (state in condition.screenStates) {
                        condition.screenStates - state
                    } else {
                        condition.screenStates + state
                    }
                    onChange(condition.copy(screenStates = next))
                },
                label = { Text(state.label) },
            )
        }
    }

    Spacer(Modifier.height(10.dp))

    // —— 充电状态 ——
    Text(
        text = "充电状态",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(4.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        ChargeState.entries.forEach { state ->
            FilterChip(
                selected = state in condition.chargeStates,
                onClick = {
                    val next = if (state in condition.chargeStates) {
                        condition.chargeStates - state
                    } else {
                        condition.chargeStates + state
                    }
                    onChange(condition.copy(chargeStates = next))
                },
                label = { Text(state.label) },
            )
        }
    }

    Spacer(Modifier.height(10.dp))

    // —— 时间窗口 ——
    Text(
        text = "时间窗口（多窗口 OR，支持跨午夜）",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(4.dp))
    if (condition.timeWindows.isNotEmpty()) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            condition.timeWindows.forEachIndexed { index, window ->
                FilterChip(
                    selected = true,
                    onClick = {
                        onChange(
                            condition.copy(
                                timeWindows = condition.timeWindows.filterIndexed { i, _ ->
                                    i != index
                                },
                            ),
                        )
                    },
                    label = { Text("${window.start}-${window.end}") },
                    trailingIcon = {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = "删除该窗口",
                            modifier = Modifier.size(16.dp),
                        )
                    },
                )
            }
        }
        Spacer(Modifier.height(4.dp))
    }
    TimeWindowEditor(
        onAdd = { start, end ->
            if (SnoozeCalculator.parseHHmm(start) == null ||
                SnoozeCalculator.parseHHmm(end) == null
            ) {
                return@TimeWindowEditor
            }
            val exists = condition.timeWindows.any { it.start == start && it.end == end }
            if (!exists) {
                onChange(condition.copy(timeWindows = condition.timeWindows + TimeWindow(start, end)))
            }
        },
    )

    Spacer(Modifier.height(10.dp))

    // —— 星期 ——
    Text(
        text = "星期（多选 OR）",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(4.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Weekday.entries.forEach { day ->
            FilterChip(
                selected = day in condition.weekdays,
                onClick = {
                    val next = if (day in condition.weekdays) {
                        condition.weekdays - day
                    } else {
                        condition.weekdays + day
                    }
                    onChange(condition.copy(weekdays = next))
                },
                label = { Text(day.label) },
            )
        }
    }
}

/**
 * 时间窗口编辑器：起 / 止均用共用 [TimePickerDialog] 填写（表盘上方带 HH:mm 输入框），
 * 点 + 添加当前窗口。默认 09:00-17:00；`start > end` 即跨午夜窗口。
 */
@Composable
private fun TimeWindowEditor(onAdd: (start: String, end: String) -> Unit) {
    var start by remember { mutableStateOf("09:00") }
    var end by remember { mutableStateOf("17:00") }
    /** 0 = 编辑起始，1 = 编辑结束，null = 无弹窗。 */
    var pickerTarget by remember { mutableStateOf<Int?>(null) }

    if (pickerTarget != null) {
        val isStart = pickerTarget == 0
        TimePickerDialog(
            title = if (isStart) "选择起始时刻" else "选择结束时刻",
            initial = if (isStart) start else end,
            onDismiss = { pickerTarget = null },
            onConfirm = { hhmm ->
                if (isStart) start = hhmm else end = hhmm
                pickerTarget = null
            },
        )
    }

    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedButton(onClick = { pickerTarget = 0 }, modifier = Modifier.weight(1f)) {
            Text("起 $start")
        }
        Text("—")
        OutlinedButton(onClick = { pickerTarget = 1 }, modifier = Modifier.weight(1f)) {
            Text("止 $end")
        }
        IconButton(onClick = { onAdd(start, end) }) {
            Icon(Icons.Filled.Add, contentDescription = "添加时间窗口")
        }
    }
    Text(
        text = if (start > end) "跨午夜窗口（$start - 次日 $end）" else "当前时段 $start - $end",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
