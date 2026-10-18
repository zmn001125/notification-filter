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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.local.notificationfilter.core.aggregate.AggregationRenderer
import com.local.notificationfilter.core.aggregate.ItemListMerger
import com.local.notificationfilter.core.model.AggregationItem
import com.local.notificationfilter.core.model.AggregationLists
import com.local.notificationfilter.core.model.AggregationOutput
import com.local.notificationfilter.core.model.FieldRef
import com.local.notificationfilter.core.model.NotificationSnapshot
import com.local.notificationfilter.core.aggregate.ItemExtractor
import com.local.notificationfilter.core.model.Placeholders
import com.local.notificationfilter.service.ActiveNotificationsProvider
import com.local.notificationfilter.core.model.SnoozeCalculator
import com.local.notificationfilter.ui.components.CollapsibleSection
import com.local.notificationfilter.ui.components.FieldPickerSheet
import com.local.notificationfilter.ui.components.IntField
import com.local.notificationfilter.ui.components.NfCard
import com.local.notificationfilter.ui.components.PlaceholderChips
import com.local.notificationfilter.ui.components.SwitchField

/**
 * 规则编辑页 §⑤「内容提取与汇总」（V7 需求 8）。
 *
 * 结构（自上而下，与架构 §5.2 的逐控件文案表一一对应）：
 * 1. 总开关 → 关掉即 `aggregation = null`（不落库）；
 * 2. 提取器卡片列表（每个提取器一张可折叠卡：列表号 / 名称 / 输入字段 / 拼接符 /
 *    正则 / 忽略大小写 / 条目格式 / 试跑 / 去重 / 上限 / 启用 / 删除）；
 * 3. 组合模板（标题 + 正文 + 条目分隔符）+ 实时预览（唯一渲染入口
 *    `AggregationRenderer.render(config, snapshot, lists)`，内部委托 `Placeholders.render`）；
 * 4. 输出方式（推迟 / 常驻悬浮）+ 与动作的联动红字；
 * 5. 「当前汇总」区块（`observeItems` 实时流 → 分组 → 单条删除 / 一键清空）。
 *
 * 设计取舍：本组件直接持有 [RuleEditViewModel]（表单状态唯一所有者）。整块表单有
 * 20+ 个回调，逐条透传只会把「字段名 → 参数名」的映射错误风险放大，且同包内
 * 已按此风格组织（`RuleEditScreen` 直接调 `vm::` 方法）。视觉上全部复用现有
 * `CollapsibleSection` / `StyleControls` / `NfCard`，不引入任何新依赖与新设计语言。
 *
 * @param vm 规则编辑 ViewModel（表单状态的读写两端）
 * @param state 当前表单快照（由调用方 `collectAsStateWithLifecycle` 取得，避免二次订阅）
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AggregationEditorSection(vm: RuleEditViewModel, state: RuleEditUiState) {
    val activeCount = state.extractors.count { it.isActive }
    val summary = when {
        !state.aggregationOn -> "未启用"
        activeCount == 0 -> "已开启 · 还没有可用提取器"
        else -> buildString {
            append("$activeCount 个提取器")
            if (state.aggregationOutputs.isNotEmpty()) {
                append(" · ")
                append(state.aggregationOutputs.joinToString("/") { it.label.substringBefore("（") })
            }
        }
    }

    CollapsibleSection(
        title = "⑤ 内容提取与汇总",
        summary = summary,
        defaultExpanded = false,
        // V8：页面级分节大标题跟主题色（嵌套的「提取器 N」子卡仍用默认 onSurface）
        titleColor = MaterialTheme.colorScheme.primary,
    ) {
        Spacer(Modifier.height(4.dp))
        SwitchField(
            label = "启用内容提取与汇总",
            checked = state.aggregationOn,
            onCheckedChange = { vm.toggleAggregation() },
            subtitle = "命中通知后按正则抽取条目攒起来，可用 {items1}..{items9} 拼成一条汇总",
        )

        if (!state.aggregationOn) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = "未启用：不攒任何条目，规则行为与其他版本完全一致。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@CollapsibleSection
        }

        // ==================== V9.6 使用说明（就地写清，别让用户去猜） ====================
        // 用户反复卡在「{re1} 说没有这个东西」「不知道怎么让提取生效」——
        // 判定规则本身没错，错在界面没告诉他前提。说明必须长在这里，而不是文档里。
        Spacer(Modifier.height(8.dp))
        NfCard {
            Text(
                text = "怎么用（4 步）",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "① 点下面「添加提取器」——列表号就是占位符里的 N（第 1 个 → {re1}）\n" +
                    "② 勾选要匹配的字段（标题 / 正文 / 消息列表）\n" +
                    "③ 填正则，要取的部分用括号包起来（例：取件码[：:为是]?\\s*(\\d{6})），" +
                    "捕获格式填 \$1；再勾「启用」，条数上限 > 0\n" +
                    "④ 回到模板里用 {reN} 或 {itemsN}：弹幕 / 悬浮的内容、替换的标题正文都能用",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "两者区别：\n" +
                    "{re1} = 只从**当前这一条**通知里抓，实时、不攒\n" +
                    "{items1} = 把**多条**通知的抓取结果攒成列表（默认换行连接）",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "⚠ 启用 + 正则非空 + 上限 > 0，三者缺一 {re1} 就会被判成「未识别占位符」。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        // ==================== V9.6 试提取（当场验证正则到底出不出内容） ====================
        // 用户反馈「提取内容的功能我也没看到你做好」——真正缺的是**可验证性**：
        // 配好正则后只能等着真实通知来才知道对不对，等于盲配。
        // 这里直接用「通知栏里第一条通知」当素材，把每个启用提取器的产出摊开显示。
        Spacer(Modifier.height(8.dp))
        var extractProbe by remember { mutableStateOf<String?>(null) }
        TextButton(onClick = {
            // 用表单 → 配置的官方转换（与保存/试跑同一份），避免自己拼字段漏项
            val active = state.toAggregationConfig().extractors.filter { it.isActive }
            val snap = ActiveNotificationsProvider.snapshots().firstOrNull()
            extractProbe = when {
                active.isEmpty() ->
                    "还没有「已启用且正则非空」的提取器 —— 先加一个并勾上启用。"

                snap == null ->
                    "通知栏现在是空的。先让任意 App 来一条通知，再回来点这个按钮。"

                else -> buildString {
                    append("素材：${snap.appName}｜")
                    append(snap.title ?: "（无标题）")
                    append("｜")
                    append(snap.text ?: "（无正文）")
                    append("\n\n")
                    active.forEach { cfg ->
                        val items = runCatching { ItemExtractor.extractAll(snap, cfg) }
                            .getOrDefault(emptyList())
                        append("{re${cfg.listIndex}}")
                        append("（${cfg.name.ifBlank { "未命名" }}）← ")
                        append(
                            if (items.isEmpty()) {
                                "没匹配到内容 → 检查正则里的中文冒号/空格/数字位数"
                            } else {
                                items.joinToString(" ／ ")
                            },
                        )
                        append("\n")
                    }
                }
            }
        }) {
            Text("用通知栏第一条通知试提取")
        }
        extractProbe?.let { text ->
            Spacer(Modifier.height(4.dp))
            NfCard {
                Text(
                    text = text,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // ==================== 提取器列表 ====================
        Spacer(Modifier.height(10.dp))
        Text(
            text = "提取器（${state.extractors.size} 个，每个对应一个暂存列表）",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        state.listIndexNotice?.let { notice ->
            Spacer(Modifier.height(4.dp))
            NfCard {
                Text(
                    text = notice,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
                Spacer(Modifier.height(4.dp))
                TextButton(onClick = { vm.consumeListIndexNotice() }) { Text("知道了") }
            }
        }

        var fieldPickerFor by remember { mutableStateOf<Int?>(null) }
        var deleteTarget by remember { mutableStateOf<ExtractorForm?>(null) }

        state.extractors.forEach { form ->
            Spacer(Modifier.height(8.dp))
            ExtractorCard(
                form = form,
                trial = state.extractorTrials[form.listIndex],
                storedCount = state.aggregationItems[form.listIndex]?.size ?: 0,
                hasSample = state.extractorSampleLoaded,
                onOpenFieldPicker = {
                    vm.loadRecentExtrasKeys()
                    fieldPickerFor = form.listIndex
                },
                onChangeListIndex = { target -> vm.changeExtractorListIndex(form.listIndex, target) },
                onRequestDelete = { deleteTarget = form },
                vm = vm,
            )
        }

        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = { vm.addExtractor() }) {
            Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.size(4.dp))
            Text("+ 添加提取器")
        }
        if (state.extractors.isEmpty()) {
            Text(
                text = "还没有提取器：每个提取器负责「从哪些字段、按什么正则、抽出什么」。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // 字段选择器（复用现有 FieldPickerSheet；顺序由卡片里的 chips 自行维护）
        // 提取器被删掉时自动关掉选择器（不在组合期写状态，避免重组抖动）
        LaunchedEffect(fieldPickerFor, state.extractors) {
            if (fieldPickerFor != null &&
                state.extractors.none { it.listIndex == fieldPickerFor }
            ) {
                fieldPickerFor = null
            }
        }
        fieldPickerFor?.let { listIndex ->
            val form = state.extractors.firstOrNull { it.listIndex == listIndex }
            if (form != null) {
                FieldPickerSheet(
                    selected = form.listFields.toSet(),
                    recentExtrasKeys = state.recentExtrasKeys,
                    recentKeysLoading = state.recentKeysLoading,
                    extrasError = state.extrasFieldError,
                    onToggle = { ref -> vm.toggleExtractorField(listIndex, ref) },
                    onAddExtras = { key -> vm.addExtractorExtrasField(listIndex, key) },
                    onDismiss = {
                        fieldPickerFor = null
                        vm.consumeExtrasFieldError()
                    },
                )
            }
        }

        // 删除提取器：二次确认
        deleteTarget?.let { target ->
            AlertDialog(
                onDismissRequest = { deleteTarget = null },
                title = { Text("删除提取器") },
                text = {
                    Text(
                        "删除「${target.name}」（列表 ${target.listIndex}）？" +
                            "已攒的条目会一并删除，且不可恢复。",
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        vm.removeExtractor(target.listIndex)
                        deleteTarget = null
                    }) { Text("删除") }
                },
                dismissButton = {
                    TextButton(onClick = { deleteTarget = null }) { Text("取消") }
                },
            )
        }

        // ==================== 组合模板 + 实时预览 ====================
        Spacer(Modifier.height(14.dp))
        CombinedTemplateBlock(
            state = state,
            titleTemplate = state.aggregationTitleTemplate,
            textTemplate = state.aggregationTextTemplate,
            itemSeparatorRaw = state.aggregationItemSeparatorRaw,
            onTitleChange = vm::setAggregationTitleTemplate,
            onTextChange = vm::setAggregationTextTemplate,
            onSeparatorChange = vm::setAggregationItemSeparator,
        )

        // ==================== 输出方式 ====================
        Spacer(Modifier.height(14.dp))
        Text(
            text = "输出方式（可多选；都不勾 = 只攒不输出，可在下方「当前汇总」查看）",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(6.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            AggregationOutput.entries.forEach { output ->
                FilterChip(
                    selected = output in state.aggregationOutputs,
                    onClick = { vm.toggleAggregationOutput(output) },
                    label = { Text(output.label) },
                )
            }
        }
        // 联动校验红字（与保存拦截同文案）
        if (AggregationOutput.SNOOZE in state.aggregationOutputs && !state.snoozeOn) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = "已启用「推迟」输出，请同时勾选上面的「推迟」动作",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        if (AggregationOutput.FLOAT in state.aggregationOutputs && !state.floatOn) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = "已启用「常驻悬浮」输出，请同时勾选上面的「自定义位置悬浮」动作",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        if (AggregationOutput.FLOAT in state.aggregationOutputs && state.floatOn) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = "汇总以这张卡片显示；样式与位置沿用「悬浮样式设置」",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // ==================== 当前汇总 ====================
        Spacer(Modifier.height(14.dp))
        CurrentSummaryBlock(state = state, vm = vm)
    }
}

// ==================== 单个提取器卡片 ====================

/**
 * 一张提取器卡片（可折叠）。标题即「块头」：`提取器 N · 已抽 X 条`。
 *
 * 字段顺序维护方式：参与字段以**有序 chips** 展示，点 chip = 前移一位（调整拼接顺序），
 * 点 × = 移除；「+ 添加字段」打开复用组件 [FieldPickerSheet]（追加到末尾）。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ExtractorCard(
    form: ExtractorForm,
    trial: ExtractorTrial?,
    storedCount: Int,
    hasSample: Boolean,
    onOpenFieldPicker: () -> Unit,
    onChangeListIndex: (Int) -> Unit,
    onRequestDelete: () -> Unit,
    vm: RuleEditViewModel,
) {
    val index = form.listIndex
    CollapsibleSection(
        title = "提取器 $index · 已抽 $storedCount 条",
        summary = if (form.isActive) "上限 ${form.limit}" else "未启用",
        defaultExpanded = true,
    ) {
        // —— 列表号（改号会提示 {itemsN} 不会跟着变）——
        IntField(
            label = "列表号",
            value = index,
            onValueChange = { onChangeListIndex(it) },
            range = 1..RuleEditViewModel.MAX_LIST_INDEX,
            unit = "占位符 {items$index}",
        )
        Text(
            text = "列表号决定占位符写法：本卡对应 {items$index}；改号不会迁移库里已攒的条目。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(6.dp))
        OutlinedTextField(
            value = form.name,
            onValueChange = { v -> vm.updateExtractor(index) { it.copy(name = v) } },
            label = { Text("列表名称（仅用于识别）") },
            placeholder = { Text("如：取件码") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        // —— 参与字段（有序 chips）——
        Spacer(Modifier.height(8.dp))
        Text(
            text = "输入字段（可多选，按顺序拼接）",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        if (form.fieldIds.isEmpty()) {
            Text(
                text = "还没有选择字段（至少选一个，否则不会抽取）",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
            )
        } else {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                form.listFields.forEachIndexed { fieldIndex, ref ->
                    AssistChip(
                        onClick = { vm.moveExtractorFieldEarlier(index, ref) },
                        label = { Text("${fieldIndex + 1} ${ref.label}") },
                        trailingIcon = {
                            Icon(
                                Icons.Filled.Close,
                                contentDescription = "移除字段 ${ref.label}",
                                modifier = Modifier.size(16.dp),
                            )
                        },
                    )
                }
            }
            Spacer(Modifier.height(2.dp))
            Text(
                text = "点 chip 前移一位（调整拼接顺序），点 × 移除",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(6.dp))
        OutlinedButton(onClick = onOpenFieldPicker) { Text("+ 添加字段") }

        // —— 字段拼接符 ——
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = form.fieldSeparatorRaw,
            onValueChange = { v -> vm.updateExtractor(index) { it.copy(fieldSeparatorRaw = v) } },
            label = { Text("字段之间的拼接符") },
            placeholder = { Text("留空=直接相接") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            text = "支持 \\n（换行）与 \\t（制表）；留空则各字段直接相接",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // —— 正则 ——
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = form.pattern,
            onValueChange = { v -> vm.updateExtractor(index) { it.copy(pattern = v) } },
            label = { Text("正则表达式") },
            placeholder = { Text("如：取件码[：:]?\\s*(\\d{4})-(\\d{2})") },
            singleLine = true,
            isError = form.patternError != null,
            supportingText = form.patternError?.let { { Text(it) } },
            textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            modifier = Modifier.fillMaxWidth(),
        )
        SwitchField(
            label = "不区分大小写",
            checked = form.ignoreCase,
            onCheckedChange = { v -> vm.updateExtractor(index) { it.copy(ignoreCase = v) } },
        )

        // —— 捕获组格式 ——
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(
            value = form.captureFormat,
            onValueChange = { v -> vm.updateExtractor(index) { it.copy(captureFormat = v) } },
            label = { Text("条目格式（\$1 \$2 为捕获组，\$0 为整段）") },
            placeholder = { Text("如：\$1-\$2") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            text = "\$0 整段匹配、\$1 第一组……；留空等价 \$0",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // —— 实时试跑 ——
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = { vm.testExtractor(index) }) { Text("测试这条正则") }
        Spacer(Modifier.height(4.dp))
        Text(
            text = when (trial) {
                null -> if (hasSample) {
                    "点上方按钮用最近一条通知试跑一次"
                } else {
                    "还没有通知样本，命中一次后可预览"
                }

                ExtractorTrial.NoSample -> "还没有通知样本，命中一次后可预览"
                ExtractorTrial.NoMatch -> "未匹配（正则合法，但这条通知里没抽到内容）"
                is ExtractorTrial.Error -> "试跑失败：${trial.message}"
                is ExtractorTrial.Hit -> "抽到 ${trial.items.size} 条：" +
                    trial.items.joinToString("、")
            },
            style = MaterialTheme.typography.labelSmall,
            color = if (trial is ExtractorTrial.Error) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )

        // —— 去重 / 上限 / 启用 ——
        Spacer(Modifier.height(6.dp))
        SwitchField(
            label = "相同条目只保留一条（新的排最前）",
            checked = form.dedupe,
            onCheckedChange = { v -> vm.updateExtractor(index) { it.copy(dedupe = v) } },
            // 关闭后「同值静默不入列」是库层唯一约束的必然结果（用户看不到），必须翻译成人话
            subtitle = "关闭后同内容不会重复入列，也不会被移到最前",
        )
        IntField(
            label = "列表最多保留条数",
            value = form.limit,
            onValueChange = { v ->
                // 与 T02 `ItemListMerger.MIN_LIMIT..MAX_LIMIT` 同一区间（唯一事实来源）
                vm.updateExtractor(index) {
                    it.copy(limit = v.coerceIn(ItemListMerger.MIN_LIMIT, ItemListMerger.MAX_LIMIT))
                }
            },
            range = ItemListMerger.MIN_LIMIT..ItemListMerger.MAX_LIMIT,
            unit = "条",
        )
        SwitchField(
            label = "启用这个提取器",
            checked = form.enabled,
            onCheckedChange = { v -> vm.updateExtractor(index) { it.copy(enabled = v) } },
        )

        // —— 删除 ——
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onRequestDelete) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = "删除这个提取器（已攒的条目一并删除）",
                    tint = MaterialTheme.colorScheme.error,
                )
            }
            Text(
                text = "删除这个提取器（已攒的条目一并删除）",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ==================== 组合模板 + 实时预览 ====================

/**
 * 组合模板区：标题模板 / 正文模板 / 条目分隔符 + 占位符 chips + 实时预览。
 *
 * 渲染**只调** [AggregationRenderer.render]（内部委托 `Placeholders.render`；UI 不扫 `{itemsN}`、
 * 也不自行判空回落），预览用的上下文是
 * 「当前已攒条目」——即用户在这里看到的就是最终会发出去的内容。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CombinedTemplateBlock(
    state: RuleEditUiState,
    titleTemplate: String,
    textTemplate: String,
    itemSeparatorRaw: String,
    onTitleChange: (String) -> Unit,
    onTextChange: (String) -> Unit,
    onSeparatorChange: (String) -> Unit,
) {
    // TextFieldValue 驱动：占位符 chips 需要在光标处插入（String 拿不到 selection）
    var titleValue by remember { mutableStateOf(TextFieldValue(titleTemplate)) }
    var textValue by remember { mutableStateOf(TextFieldValue(textTemplate)) }
    /** 当前聚焦框：0 = 标题 / 1 = 正文（chips 插入目标）。 */
    var focused by remember { mutableIntStateOf(0) }

    LaunchedEffect(titleTemplate, textTemplate) {
        if (titleValue.text != titleTemplate) titleValue = TextFieldValue(titleTemplate)
        if (textValue.text != textTemplate) textValue = TextFieldValue(textTemplate)
    }

    val insertToken: (String) -> Unit = { token ->
        val field = if (focused == 0) titleValue else textValue
        val start = field.selection.start.coerceIn(0, field.text.length)
        val newText = field.text.substring(0, start) + token + field.text.substring(start)
        val newField = TextFieldValue(newText, TextRange(start + token.length))
        if (focused == 0) {
            titleValue = newField
            onTitleChange(newText)
        } else {
            textValue = newField
            onTextChange(newText)
        }
    }

    Text(
        text = "组合模板（用 {itemsN} 把列表拼成一条汇总）",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(6.dp))
    OutlinedTextField(
        value = titleValue,
        onValueChange = {
            titleValue = it
            onTitleChange(it.text)
        },
        label = { Text("汇总标题模板") },
        placeholder = { Text("如：今天的快递") },
        modifier = Modifier
            .fillMaxWidth()
            .onFocusChanged { if (it.isFocused) focused = 0 },
    )
    Spacer(Modifier.height(6.dp))
    OutlinedTextField(
        value = textValue,
        onValueChange = {
            textValue = it
            onTextChange(it.text)
        },
        label = { Text("汇总正文模板") },
        placeholder = { Text("如：{items1} / {items2}") },
        minLines = 3,
        modifier = Modifier
            .fillMaxWidth()
            .onFocusChanged { if (it.isFocused) focused = 1 },
    )
    Spacer(Modifier.height(6.dp))
    OutlinedTextField(
        value = itemSeparatorRaw,
        onValueChange = onSeparatorChange,
        label = { Text("条目分隔符") },
        placeholder = { Text("可直接回车；也可输入 \\n \\t；留空=直接相接") },
        minLines = 3,
        textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
        modifier = Modifier.fillMaxWidth(),
    )

    // 占位符 chips（含 {items1}..{items9} 组；token 由实际提取器列表号生成）
    Spacer(Modifier.height(6.dp))
    PlaceholderChips(
        onInsert = insertToken,
        extrasTokens = state.selectedFields
            .filterIsInstance<FieldRef.Extras>()
            .map { "{extras.${it.key}}" },
        itemsTokens = state.extractors
            .filter { it.isActive }
            .sortedBy { it.listIndex }
            .map { "{items${it.listIndex}}" },
        // V9.4：{re1}..{re9} 即时提取（对**当前这条通知**跑正则），与 {itemsN}（累积列表）区分
        extractTokens = Placeholders.extractTokens(state.toAggregationConfig()),
    )

    // —— 实时预览 ——
    // 唯一渲染入口：`AggregationRenderer.render(config, snapshot, lists)`（内部委托
    // `Placeholders.render`，并在「模板非空但结果为空」时回落原通知标题/正文，
    // 与运行时（ActionExecutor / 通知重发）**完全同一条路径**，UI 不再自行判空回落）。
    Spacer(Modifier.height(8.dp))
    val maxIndex = state.extractors.maxOfOrNull { it.listIndex } ?: 0
    val lists = remember(state.aggregationItems, maxIndex) {
        AggregationLists(
            List(maxIndex) { idx -> state.aggregationItems[idx + 1].orEmpty().map { it.value } },
        )
    }
    val snapshot = state.extractorSample ?: EMPTY_PREVIEW_SNAPSHOT
    val rendered = AggregationRenderer.render(state.toAggregationConfig(), snapshot, lists)

    NfCard {
        Text(
            text = "实时预览（用当前已攒条目渲染，与真正发出时同一条渲染路径）",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "标题：${rendered.title}",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = "正文：${rendered.text}",
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = if (lists.totalItems == 0) {
                "列表还是空的：上面展示的是「一条都没攒到」时的回落结果（原通知标题/正文）"
            } else {
                "固定占位符（{title} 等）取最近一条通知的值；{itemsN} 取上表列表。"
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 预览用的空快照（固定占位符一律渲染空串，`{itemsN}` 不受影响）。 */
private val EMPTY_PREVIEW_SNAPSHOT: NotificationSnapshot = NotificationSnapshot(
    key = "",
    notifId = 0,
    packageName = "",
    appName = "",
)

// ==================== 「当前汇总」区块 ====================

/**
 * 「当前汇总」（架构 §5.3）：按提取器分组展示已攒条目，支持单条删除与一键清空。
 *
 * 数据源是 `AggregationRepository.observeItems(ruleId)` 的 Flow（在 ViewModel 里
 * 分组后落到 [RuleEditUiState.aggregationItems]）——规则命中后自动刷新。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CurrentSummaryBlock(state: RuleEditUiState, vm: RuleEditViewModel) {
    Text(
        text = "当前汇总（实时）",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(6.dp))

    if (state.ruleId <= 0L) {
        NfCard {
            Text(
                text = "保存规则后才能查看汇总",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    val total = state.aggregationItems.values.sumOf { it.size }
    var showClearAll by remember { mutableStateOf(false) }

    if (total == 0) {
        NfCard {
            Text(
                text = "还没有累积内容，规则命中后会自动出现在这里",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    // 展平成「分组头 + 条目行」，用有高度上限的 LazyColumn 页内滚动（架构 §5.3）
    val rows: List<Any> = buildList {
        state.aggregationItems.toSortedMap().forEach { (listIndex, items) ->
            val extractor = state.extractors.firstOrNull { it.listIndex == listIndex }
            val name = extractor?.name ?: "列表 $listIndex"
            // 满了给个提示（汰旧会静默吃掉最旧一条，用户需要知道「新的进了、旧的走了」）
            val limit = extractor?.limit ?: 0
            val full = limit > 0 && ItemListMerger.isFull(items.map { it.value }, limit)
            add("$name（${items.size} 条${if (full) " · 已到上限 $limit" else ""}）")
            items.forEach { add(it) }
        }
    }
    val now = remember(state.aggregationItems) { System.currentTimeMillis() }

    NfCard {
        LazyColumn(modifier = Modifier.heightIn(max = 240.dp)) {
            items(rows.size) { rowIndex ->
                when (val row = rows[rowIndex]) {
                    is String -> Text(
                        text = row,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp, bottom = 2.dp),
                    )

                    is AggregationItem -> Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = row.value,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.size(6.dp))
                        Text(
                            text = SnoozeCalculator.relativeTime(row.createdAt, now),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        IconButton(onClick = { vm.deleteAggregationItem(row) }) {
                            Icon(
                                Icons.Filled.Close,
                                contentDescription = "删除这条",
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }

                    else -> Unit
                }
            }
        }
    }

    Spacer(Modifier.height(6.dp))
    OutlinedButton(onClick = { showClearAll = true }) {
        Icon(Icons.Filled.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
        Spacer(Modifier.size(4.dp))
        Text("一键清空全部汇总")
    }

    if (showClearAll) {
        AlertDialog(
            onDismissRequest = { showClearAll = false },
            title = { Text("清空全部汇总") },
            text = { Text("清空后已攒的 $total 条不可恢复，确定？") },
            confirmButton = {
                TextButton(onClick = {
                    vm.clearAggregationList(null)
                    showClearAll = false
                }) { Text("清空") }
            },
            dismissButton = {
                TextButton(onClick = { showClearAll = false }) { Text("取消") }
            },
        )
    }
}
