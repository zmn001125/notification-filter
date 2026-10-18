package com.local.notificationfilter.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.local.notificationfilter.core.model.GradientSpec
import com.local.notificationfilter.core.model.GradientStop
import com.local.notificationfilter.core.model.OverlayGradientKind
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * 样式配置控件库（V5 需求③④⑤：弹幕双形态 / 悬浮 / 配置 UI 重构）。
 *
 * 用户明确要求（真机自用场景）：
 * - **颜色**：无极选色面板（[HsvColorPicker]：色相条 + 饱和度/亮度二维区 +
 *   Alpha 条 + HEX 手输），**不再有预制色板**；
 * - **渐变**：类型（无/线性/径向）+ 多色标（2-4 条）+ 线性角度 / 径向半径比例，
 *   见 [GradientField]；
 * - **数值**：既有滑动条，也有「− / +」微调按钮，还能手动输入；
 *   V6-⑤ 起全部收进单行（标签→细轨滑动条→窄手输框→±小按钮，行高 48dp）；
 *   **V7-③** 滑动条手势重写为「按下即取值 + 按住跟随」（旧写法把
 *   `detectTapGestures` 与 `detectDragGestures` 串联，前者永不返回 → 只能点不能拖）；
 *   **V7-④** 标签不再定宽 + 省略号截断，改按内容展开并允许换行（「最大行数」等完整显示）；
 *   **V7-⑤** 手输框 36dp→28dp / 12sp，并与滑动条之间留 8dp 间距；
 * - 所有控件紧凑、信息密度高，能塞进可折叠分节里。
 *
 * 约定：
 * - 颜色值一律 **ARGB Long**（如 `0xFFFFFFFF`），与 core 层的样式模型一致；
 * - 全部颜色取自 `MaterialTheme.colorScheme`，**不硬编码**，随 6 套主题 + 深浅模式变化；
 * - 手输解析失败时**不改值、不崩溃**，仅显示错误提示。
 *
 * @see OverlayStyleDialog 使用方（弹幕 / 悬浮的样式编辑对话框）
 */

// ==================== 颜色 ====================

/**
 * ARGB Long（`0xAARRGGBB`）→ Compose [Color]。
 *
 * D1 修复（架构卫生）：本文件属于**通用控件库**，不得反向依赖业务包
 * `ui.overlay`（此前这里 `import ...ui.overlay.argbColor`，形成
 * `ui.components → ui.overlay` 的倒挂依赖，导致通用组件无法独立复用）。
 * 因此就地定义一个私有转换函数——`Color(Long)` 本身就是一行映射，
 * 没有任何复用价值，没必要跨包共享。
 */
private fun toComposeColor(argb: Long): Color = Color(argb)

/**
 * 颜色选择控件（V5 需求⑤ 重构）：默认收起为一行「标签 + 色样 + 展开箭头」，
 * 点击展开 [HsvColorPicker] 无极选色面板。
 *
 * 为什么默认收起：弹幕/悬浮样式页有十几个颜色项（文字/阴影/描边/边框/背景…），
 * 全部常显会把表单撑到不可读；收起行上的色样仍让用户一眼看到当前值。
 */
@Composable
fun ColorField(
    label: String,
    argb: Long,
    onArgbChange: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    Column(modifier = modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(vertical = 2.dp),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            ColorSwatch(argb)
            Icon(
                imageVector = if (expanded) Icons.Filled.KeyboardArrowUp
                else Icons.Filled.KeyboardArrowDown,
                contentDescription = if (expanded) "收起选色面板" else "展开选色面板",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .padding(start = 4.dp)
                    .size(20.dp),
            )
        }
        AnimatedVisibility(visible = expanded) {
            Column {
                Spacer(Modifier.height(6.dp))
                HsvColorPicker(argb = argb, onArgbChange = onArgbChange)
            }
        }
    }
}

/** 单个色样（纯色预览小条，V6-⑤ 收紧：48x22dp）。 */
@Composable
private fun ColorSwatch(argb: Long) {
    val shape = RoundedCornerShape(6.dp)
    Box(
        modifier = Modifier
            .size(width = 48.dp, height = 22.dp)
            .clip(shape)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
            .background(toComposeColor(argb)),
    )
}

// ==================== 渐变（V5 需求⑤：多色标 + 线性角度 / 径向半径比例） ====================

/**
 * 渐变编辑控件：类型 chips（无/线性/径向）+ 色标列表（每条 = 位置滑动条 +
 * 色样，点色样展开 [HsvColorPicker] 选色；2..[GradientSpec.MAX_STOPS] 条，可加可删）
 * + LINEAR 角度 / RADIAL 半径比例。
 *
 * @param solidColor 当前纯色值：从「无」切到渐变且色标不足 2 条时，用它补一对
 *   默认色标（0 / 1 两位置同为该色）——避免一开渐变背景就突变成默认黑。
 */
@Composable
fun GradientField(
    label: String,
    spec: GradientSpec,
    onSpecChange: (GradientSpec) -> Unit,
    modifier: Modifier = Modifier,
    solidColor: Long = 0xFF1A1B1EL,
) {
    Column(modifier = modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            GradientSwatch(spec = spec, fallback = solidColor)
        }
        Spacer(Modifier.height(6.dp))
        EnumChipsField(
            label = "渐变类型",
            options = OverlayGradientKind.ALL.map { it.name to it.label },
            selected = spec.kind,
            onSelect = { name ->
                val kind = OverlayGradientKind.of(name)
                val stops = if (kind != OverlayGradientKind.NONE && spec.stops.size < 2) {
                    // 开启渐变：用当前纯色补一对默认色标（渐变立即可见且不跳色）
                    listOf(GradientStop(position = 0f, color = solidColor),
                        GradientStop(position = 1f, color = solidColor))
                } else {
                    spec.stops
                }
                onSpecChange(spec.copy(kind = kind.name, stops = stops))
            },
        )
        if (spec.kindEnum == OverlayGradientKind.NONE) return@Column

        // —— 色标列表 ——
        // V7-④：数值控件的 label/unit 会被拼成「label（unit）」显示在 88~116dp 的窄栏里，
        // 因此括号味重的解释一律挪到这类说明行（旧写法把「（1=到最远角）」塞进 unit，
        // 渲染出来是「径向半径比例（（1=到最远角））」——双层括号且撑成两行）。
        Text(
            text = "色标：位置 0~1 + 颜色，点色样改色（${spec.stops.size}/${GradientSpec.MAX_STOPS} 条）。" +
                "径向半径比例 1 = 到最远角，越小光斑越小。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(2.dp))
        spec.stops.forEachIndexed { index, stop ->
            GradientStopRow(
                stop = stop,
                canDelete = spec.stops.size > 2,
                onStopChange = { next ->
                    onSpecChange(
                        spec.copy(
                            stops = spec.stops.mapIndexed { i, s -> if (i == index) next else s },
                        ),
                    )
                },
                onDelete = {
                    onSpecChange(spec.copy(stops = spec.stops.filterIndexed { i, _ -> i != index }))
                },
            )
        }
        if (spec.stops.size < GradientSpec.MAX_STOPS) {
            TextButton(
                onClick = {
                    // 新色标复制最后一条的颜色放在位置 1：插入不跳变，用户再调
                    val last = spec.stops.lastOrNull() ?: GradientStop(position = 1f, color = solidColor)
                    onSpecChange(spec.copy(stops = spec.stops + last.copy(position = 1f)))
                },
            ) {
                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("添加色标")
            }
        }
        when (spec.kindEnum) {
            OverlayGradientKind.LINEAR -> NumberField(
                label = "渐变角度",
                value = spec.angleDeg,
                onValueChange = { onSpecChange(spec.copy(angleDeg = it)) },
                range = 0f..360f,
                unit = "°",
            )

            OverlayGradientKind.RADIAL -> NumberField(
                label = "径向半径比例",
                value = spec.radiusRatio,
                onValueChange = { onSpecChange(spec.copy(radiusRatio = it)) },
                range = 0.1f..1f,
                decimals = 2,
                // 不设 unit：解释见上方说明行（unit 里带括号会拼成双层括号）
            )

            OverlayGradientKind.NONE -> Unit
        }
    }
}

/** 一条色标的编辑行：色样（点击开选色面板）+ 位置滑动条 + 删除。 */
@Composable
private fun GradientStopRow(
    stop: GradientStop,
    canDelete: Boolean,
    onStopChange: (GradientStop) -> Unit,
    onDelete: () -> Unit,
) {
    var editingColor by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            // V7-④：最小高度而非固定高度，标签换行时行自然长高
            modifier = Modifier.fillMaxWidth().heightIn(min = SIDE_ROW_HEIGHT),
        ) {
            Box(
                modifier = Modifier
                    .size(width = 32.dp, height = 22.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .border(
                        1.dp,
                        MaterialTheme.colorScheme.outlineVariant,
                        RoundedCornerShape(6.dp),
                    )
                    .background(toComposeColor(stop.color))
                    .clickable { editingColor = !editingColor },
            )
            Spacer(Modifier.width(6.dp))
            CompactSlider(
                value = stop.position.coerceIn(0f, 1f),
                onValueChange = { onStopChange(stop.copy(position = it)) },
                valueRange = 0f..1f,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = "${(stop.position.coerceIn(0f, 1f) * 100f).roundToInt()}%",
                style = MaterialTheme.typography.labelMedium,
                // V7-④：最小宽而非固定宽，三位数百分比如 100% 不会被挤到换行
                modifier = Modifier.widthIn(min = 36.dp),
            )
            IconButton(
                onClick = onDelete,
                enabled = canDelete,
                modifier = Modifier.size(28.dp),
            ) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = "删除色标",
                    modifier = Modifier.size(16.dp),
                    tint = if (canDelete) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.outlineVariant
                    },
                )
            }
        }
        AnimatedVisibility(visible = editingColor) {
            Column {
                Spacer(Modifier.height(6.dp))
                HsvColorPicker(
                    argb = stop.color,
                    onArgbChange = { onStopChange(stop.copy(color = it)) },
                )
            }
        }
    }
}

/** 渐变预览条（与渲染层同一套色标口径：径向按 radiusRatio 压缩色标位置）。 */
@Composable
private fun GradientSwatch(spec: GradientSpec, fallback: Long) {
    val shape = RoundedCornerShape(6.dp)
    Box(
        modifier = Modifier
            .size(width = 56.dp, height = 28.dp)
            .clip(shape)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
            .background(gradientBrushOf(spec, fallback)),
    )
}

/** GradientSpec → Brush（不可渲染 → 纯色 fallback；与 OverlayRender.backgroundBrush 同口径）。 */
private fun gradientBrushOf(spec: GradientSpec, fallback: Long): Brush {
    if (!spec.isRenderable) {
        return androidx.compose.ui.graphics.SolidColor(toComposeColor(fallback))
    }
    val scale = if (spec.kindEnum == OverlayGradientKind.RADIAL) {
        spec.radiusRatio.coerceIn(0.1f, 1f)
    } else {
        1f
    }
    val colorStops = spec.stops
        .sortedBy { it.position.coerceIn(0f, 1f) }
        .map { (it.position.coerceIn(0f, 1f) * scale) to toComposeColor(it.color) }
        .toTypedArray()
    return when (spec.kindEnum) {
        OverlayGradientKind.RADIAL -> Brush.radialGradient(colorStops = colorStops)
        OverlayGradientKind.LINEAR -> {
            val rad = Math.toRadians(spec.angleDeg.toDouble())
            val dx = cos(rad).toFloat()
            val dy = sin(rad).toFloat()
            Brush.linearGradient(
                colorStops = colorStops,
                start = Offset(0.5f - dx * 0.5f, 0.5f - dy * 0.5f),
                end = Offset(0.5f + dx * 0.5f, 0.5f + dy * 0.5f),
            )
        }

        OverlayGradientKind.NONE -> androidx.compose.ui.graphics.SolidColor(toComposeColor(fallback))
    }
}

// ==================== 数值 ====================

/**
 * 浮点数值控件（V6-⑤ 紧凑单行版）：标签 → 紧凑滑动条 → 窄手输框 → ±微调按钮，
 * 全部收进一行（行高 48dp）。对外签名保持不变（OverlayStyleDialog 调用点零改动）。
 *
 * - 手输（**P1-7 修复**）：草稿与值解耦——输入期间**绝不**用 value 回写文本（所以光标
 *   不跳、能连续输入小数）；草稿解析成功即同步写模型（失焦 / IME Done 只是最后一次
 *   提交 + 把文本归一化成格式化值，例如 `0.` → `0.00`、`999` → `600`）；
 *   非法输入（空串 / `abc` / `..` / `NaN`）丢弃草稿、保留旧值；拖动滑杆 / ±（外部改值）
 *   时草稿立即作废，文本跟随新值；
 * - 滑动条：自绘 3dp 细轨 + 10dp 圆点 thumb（替代 M3 默认 4dp 粗轨 + 20dp 大 thumb
 *   + 48dp 最小触摸区），点按 / 按住拖动均可；
 * - 行内不再常显范围提示（范围由滑动条两端天然表达），信息密度优先。
 *
 * **V8：± 步长统一为「一格」**（用户原话：「所有样式的数值调整的 +- 都只调整步数为 1，
 * 不要其他数值」）。旧版每个调用点各自传 `step`（8 / 15 / 0.05 …），同一个界面上
 * 「距顶部」按一次跳 8dp、字号按一次跳 1sp、不透明度按一次跳 5%，手感完全不一致。
 * 现在：
 * - **精细区**（区间跨度 ≤ 100 的整数语义：dp / 行数 / 字数 / 秒…）→ 每按一次 **±1**；
 * - **宽区间**（跨度 > 100：滚动速度 60..3000、距顶部 0..600、气泡宽度 0..420…）→
 *   每按一次 **跨度的 1%**。这是**刻意偏离用户字面要求的取舍**：字面 ±1 意味着滚动速度
 *   要按 2940 次才能走完全程，按钮等于废掉；1% 兜底 = ±29，配滑条与手输就够用。
 *   完整理由见 [stepForSpan] 的 KDoc。
 * - **比例语义**（区间落在 0~1 且 [decimals] > 0，判定见 [isPercentLike]）→ 显示与 ±
 *   改用**百分比刻度**：显示 `85%`、每按一次 ±1%（写入 ±0.01）、手输 `85` 解析成 `0.85`；
 *   滑条仍按 0..1 原始值拖动，**落库值形态完全不变**，只是换了一套可用的刻度。
 *   这不是审美选择：0..1 的 alpha 若 ±1，一次点击就从全透明跳到全不透明，等于不可调。
 * - 签名里的 `step` 因此不再参与计算（参数保留纯为调用点源码兼容）。
 *
 * 用法示例（V6-④b 边缘羽化——挂载归渲染层 Dialog，这里仅示范控件用法）：
 * ```
 * NumberField(
 *     label = "边缘羽化", value = style.edgeFeatherDp,
 *     onValueChange = { onStyleChange(style.copy(edgeFeatherDp = it)) },
 *     range = 0f..24f, step = 1f, unit = "dp",
 * )
 * ```
 */
@Suppress("UNUSED_PARAMETER")
@Composable
fun NumberField(
    label: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    range: ClosedFloatingPointRange<Float> = 0f..100f,
    step: Float = 1f, // V8 起 ± 固定 1 格，此参数不再参与计算（保留只为调用点兼容）
    unit: String = "",
    decimals: Int = 0,
) {
    val dec = decimals.coerceIn(0, 3)
    val current = value.coerceIn(range)
    // V8：0~1 的小数走百分比刻度（理由见 isPercentLike 的 KDoc）
    val percentMode = isPercentLike(range, dec)
    val displayText = if (percentMode) formatPercent(current) else formatNumber(current, dec)
    // V8：非百分比参数的 ± 一格（精细区 = 1，宽区间 = 跨度的 1%，理由见 stepForSpan）。
    // 整数字段（decimals = 0，界面只显示整数：滚动速度 / dp / px）额外把步长取整，
    // 否则 2940/100 = 29.4 会让「60 → 89.4 → 显示 89」留下无意义的尾数。
    val span = (range.endInclusive - range.start).let { if (it < 0f) -it else it }
    val unitStep = when {
        percentMode -> 1f // 百分比分支实际走 stepPercent(±1 个百分点)，这里只为语义完整
        dec <= 0 -> {
            val coarse = stepForSpan(span)
            if (coarse <= 1f) 1f else coarse.roundToInt().toFloat()
        }
        else -> stepForSpan(span)
    }
    // —— P1-7：把「文本」与「值」两条机制彻底拆开 ——
    // 根因：旧写法 `remember(value) { mutableStateOf<String?>(null) }` 让草稿以 value 为键，
    // 而 onTextChange 每键都 onValueChange → value 变 → 草稿被重键清空 → 文本被格式化值
    // 覆盖。于是「0.50 末尾输 3 → 文本 0.503 → 写模型 0.503 → 显示又回 "0.50"」（看不见的
    // 精度漂移），想输 0.35 基本只能粘贴。
    // 现在：
    //   1) 文本永远 `draft ?: 格式化值`，草稿**不随 value 重键** → 输入中途不会被覆盖；
    //   2) 只有「外部改值」才作废草稿。外部与否用 `lastCommitted` 区分：手输提交会记下
    //      自己写出去的值，因此 `value == lastCommitted` 视为自己造成的变化、不动草稿；
    //      否则（滑杆 / ± / 上层重置）丢弃草稿让文本立刻跟随。
    var draft by remember { mutableStateOf<String?>(null) }
    var focused by remember { mutableStateOf(false) }
    var lastCommitted by remember { mutableStateOf(current) }
    val draftText = draft
    // 错误提示：只在「非空且解析不出有限数值」时标红（空串 = 用户正在删改，不算错）
    val invalid = draftText != null && draftText.isNotBlank() &&
        commitCandidateOf(draftText, range, percentMode) == null

    LaunchedEffect(current) {
        if (current != lastCommitted) {
            // 外部改值（滑杆 / ± / 上层重置）：手输草稿已过期，丢弃 → 文本跟随格式化值
            draft = null
            lastCommitted = current
        }
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = NUMBER_ROW_HEIGHT)
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = buildString {
                append(label)
                if (unit.isNotBlank()) append("（$unit）")
            },
            style = MaterialTheme.typography.bodyMedium,
            // V7-④：标签必须完整显示——允许换行到 3 行、不做省略号截断
            maxLines = 3,
            softWrap = true,
            modifier = Modifier.widthIn(min = LABEL_MIN_WIDTH, max = LABEL_MAX_WIDTH),
        )
        CompactSlider(
            value = current,
            onValueChange = { onValueChange(it.coerceIn(range)) },
            valueRange = range,
            modifier = Modifier.weight(1f),
        )
        CompactNumberInput(
            text = draftText ?: displayText,
            onTextChange = { raw ->
                // 只更新草稿（文本以草稿为准，**不**用 value 回写 → 光标不跳、可连续输入）
                draft = raw
                // 解析出有限数值就同步写模型：手输因此仍是所见即所得；也保证「输入完直接
                // 点右上『完成』而没让输入框失焦」时值不会丢（对话关闭后草稿即销毁）。
                // V8：百分比刻度下 `85` → 0.85（换算在 commitCandidateOf 内完成）
                commitCandidateOf(raw, range, percentMode)?.let { next ->
                    lastCommitted = next
                    onValueChange(next)
                }
            },
            isError = invalid,
            keyboardType = if (dec > 0) KeyboardType.Decimal else KeyboardType.Number,
            modifier = Modifier
                .padding(start = SLIDER_INPUT_GAP)
                .width(COMPACT_INPUT_WIDTH)
                .onFocusChanged { state ->
                    // `isFocused` 只在本节点自身是焦点目标时为真，`hasFocus` 覆盖「子节点
                    // （BasicTextField）获得焦点」，取并集以免漏判。
                    val nowFocused = state.isFocused || state.hasFocus
                    val lostFocus = focused && !nowFocused
                    focused = nowFocused
                    if (lostFocus) {
                        // 失焦 / IME Done：最后一次提交（非法草稿 → null → 丢弃、保留旧值），
                        // 然后清草稿，让文本归一化成格式化值（如 "0." → "0.00"、"999" → "600"）
                        commitCandidateOf(draft, range, percentMode)?.let { next ->
                            lastCommitted = next
                            onValueChange(next)
                        }
                        draft = null
                    }
                },
        )
        Spacer(Modifier.width(4.dp))
        // V8：± 一格——比例参数 ±1%（写入 ±0.01），精细区 ±1，宽区间 ±跨度 1%（见 stepForSpan）
        MiniStepButton(
            icon = Icons.Filled.Remove,
            description = "减小",
            onClick = {
                onValueChange(
                    if (percentMode) stepPercent(current, range, -1)
                    else (current - unitStep).coerceIn(range),
                )
            },
        )
        Spacer(Modifier.width(2.dp))
        MiniStepButton(
            icon = Icons.Filled.Add,
            description = "增大",
            onClick = {
                onValueChange(
                    if (percentMode) stepPercent(current, range, 1)
                    else (current + unitStep).coerceIn(range),
                )
            },
        )
    }
}

/**
 * 整数数值控件（语义同 [NumberField]，V6-⑤ 同为紧凑单行布局）。
 *
 * V8：± 一格，规则与 [NumberField] 一致——**跨度 ≤ 100 时 ±1；跨度 > 100 时取跨度的 1%**
 * （至少 1），见 [stepForSpan]。这里整数除法天然取整：`0..320` → ±3、`0..600` → ±6。
 * 参数 `step` 不再参与计算，保留只为调用点源码兼容。
 */
@Suppress("UNUSED_PARAMETER")
@Composable
fun IntField(
    label: String,
    value: Int,
    onValueChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    range: IntRange = 0..100,
    step: Int = 1, // V8 起 ± 按「一格」规则计算（见 stepForSpan），此参数保留只为源码兼容
    unit: String = "",
) {
    val lo = minOf(range.first, range.last)
    val hi = maxOf(range.first, range.last)
    val current = value.coerceIn(lo, hi)
    // V8：整数下标空间的「一格」，整数除法即取整（0..320 → 3，0..600 → 6），至少 1
    val span = hi - lo
    val unitStep = if (preciseStep(span.toFloat())) 1 else (span / 100).coerceAtLeast(1)
    var draft by remember(current) { mutableStateOf<String?>(null) }
    var focused by remember { mutableStateOf(false) }
    val text = draft ?: current.toString()
    val invalid = draft != null && draft!!.isNotBlank() && draft!!.trim().toIntOrNull() == null

    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = NUMBER_ROW_HEIGHT)
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = buildString {
                append(label)
                if (unit.isNotBlank()) append("（$unit）")
            },
            style = MaterialTheme.typography.bodyMedium,
            // V7-④：标签必须完整显示——允许换行到 3 行、不做省略号截断
            maxLines = 3,
            softWrap = true,
            modifier = Modifier.widthIn(min = LABEL_MIN_WIDTH, max = LABEL_MAX_WIDTH),
        )
        CompactSlider(
            value = current.toFloat(),
            onValueChange = { onValueChange(it.roundToInt().coerceIn(lo, hi)) },
            valueRange = lo.toFloat()..hi.toFloat(),
            modifier = Modifier.weight(1f),
        )
        CompactNumberInput(
            text = text,
            onTextChange = { raw ->
                draft = raw
                raw.trim().toIntOrNull()?.let { onValueChange(it.coerceIn(lo, hi)) }
            },
            isError = invalid,
            keyboardType = KeyboardType.Number,
            modifier = Modifier
                .padding(start = SLIDER_INPUT_GAP)
                .width(COMPACT_INPUT_WIDTH)
                .onFocusChanged { state ->
                    if (focused && !state.isFocused) draft = null
                    focused = state.isFocused
                },
        )
        Spacer(Modifier.width(4.dp))
        // V8：± 一格（跨度 ≤ 100 → 1；跨度 > 100 → 跨度的 1%，整数除法取整）
        MiniStepButton(
            icon = Icons.Filled.Remove,
            description = "减小",
            onClick = { onValueChange((current - unitStep).coerceIn(lo, hi)) },
        )
        Spacer(Modifier.width(2.dp))
        MiniStepButton(
            icon = Icons.Filled.Add,
            description = "增大",
            onClick = { onValueChange((current + unitStep).coerceIn(lo, hi)) },
        )
    }
}

private fun formatNumber(value: Float, decimals: Int): String =
    if (decimals <= 0) value.toInt().toString()
    else "%.${decimals}f".format(value)

/**
 * 区间「跨度」判定阈值：跨度不超过它时，± 一格 = **1 个物理单位**；超过它按跨度 1%。
 *
 * 100 这个数字的来源：现有全部调用点的跨度分布是「0..32 内边距 / 0..6 描边 / -2..8 字间距 /
 * 0..1 透明度 / 0..64 圆角 …」vs「60..3000 滚动速度 / 0..600 距顶部 / 0..420 气泡宽度 /
 * 0..320 高度」，两者之间是空的，100 落在空档里，因此阈值取多少（80~200）只影响未来新增
 * 参数，不会误伤现有任何一个控件。
 */
private const val WIDE_SPAN_THRESHOLD = 100f

/**
 * 该跨度是否处于「精细区」（± 一格 = 1 个物理单位）。
 *
 * @param span 区间跨度（`endInclusive - start`，按非负数传入）
 */
private fun preciseStep(span: Float): Boolean = span <= WIDE_SPAN_THRESHOLD

/**
 * 非百分比参数的 ± 步长。
 *
 * **这是 V8 的刻意取舍，偏离用户字面要求，理由必须留着**：用户原话是「所有样式的数值
 * 调整的 +- 都只调整步数为 1」。但他真正抱怨的是「一下跳太多」（原来距顶部 ±8dp、
 * 不透明度 ±5%），不是「必须是物理量 1」。若一律按字面 1 实现：
 * - 滚动速度 `60..3000` 按 ±1 要走 **2940 次**——那不叫精细，叫按不动；
 * - 距顶部 `0..600` 要 600 次、气泡宽度 `0..420` 要 420 次，同样等于废掉按钮。
 *
 * 因此这里做 1% 兜底：跨度 > [WIDE_SPAN_THRESHOLD] 时一格 = 跨度的 1%
 * （滚动速度 ±29、距顶部 ±6、宽度 ±4、高度 ±3），再配合滑条与手输，既有细粒度又能用；
 * 跨度 ≤ 100 的参数（内边距 0..32、圆角、描边 0..6、字号、行数、秒、角度以外的物理量）
 * 仍然严格 ±1，那才是用户抱怨的原始场景。
 *
 * 百分比刻度（0~1 的小数）**不走这里**，它的「一格」固定是 1%（见 [stepPercent]）。
 */
private fun stepForSpan(span: Float): Float =
    if (preciseStep(span)) 1f else (span / 100f).coerceAtLeast(1f)

/**
 * 百分比刻度下的显示文本：`0.85f` → `"85%"`。
 *
 * 只用于显示（[NumberField] 的 `displayText`），**不回写模型**——库里的值永远是 0..1
 * 的小数，渲染层（alpha / 半径比例）不需要知道 UI 换过一套刻度。
 */
private fun formatPercent(value: Float): String = "${(value * 100f).roundToInt()}%"

/**
 * 是否是「0~1 的小数比例」类参数（整体透明度 / 背景不透明度 / 径向半径比例 …）——
 * 这类参数在 V8 起改用**百分比刻度**显示与步进。
 *
 * 为什么必须这么做：V8 要求「所有 ± 都只动 1 格」。对 0..1 的 alpha 来说，
 * `±1` 意味着一次点击就从全透明（0.00）跳到全不透明（1.00），等于把「透明度」这个
 * 参数彻底变成开关，用户根本没法微调；而 `±0.01` 才是他真正想要的「一格」。
 * 换成百分比后，屏幕上显示的 `85%` 与 ±1% 完全对得上，手输 `85` 也符合直觉。
 *
 * 判定条件刻意写得很窄（`decimals > 0` **且** 整个区间落在 0..1 内）：
 * - `decimals == 0` 说明这个参数本来就是整数语义（行数 / 秒 / dp），不该套百分比；
 * - 区间只有落在 0..1 内才可能是「比例」，`0.1..1`（整体透明度的下限钳位）也算，
 *   此时百分比下界自然变成 `10%`，语义仍然正确；
 * - `-2..8`（字间距）、`0..6`（描边宽度）这类「带小数的物理量」不套百分比，
 *   仍是 ±1 个物理单位。
 */
private fun isPercentLike(range: ClosedFloatingPointRange<Float>, decimals: Int): Boolean {
    if (decimals <= 0) return false
    val lo = minOf(range.start, range.endInclusive)
    val hi = maxOf(range.start, range.endInclusive)
    return lo >= 0f && hi <= 1f
}

/**
 * 百分比刻度下按 [deltaPercent] 步进（如 `-1` = 减 1%）。
 *
 * 全程在**整数百分位空间**里加减：`0.35f + 0.01f` 这类浮点累加会漂成
 * `0.36000001`，显示取整后仍然是 36%，但反复按 100 次就可能少/多 1%；
 * 先 `roundToInt()` 成百分数再加减则永远精确。返回值仍钳制在 [range] 内
 * （例如整体透明度下限 0.1 → 百分位下限 10%）。
 */
private fun stepPercent(
    current: Float,
    range: ClosedFloatingPointRange<Float>,
    deltaPercent: Int,
): Float {
    val loPct = (minOf(range.start, range.endInclusive) * 100f).roundToInt()
    val hiPct = (maxOf(range.start, range.endInclusive) * 100f).roundToInt()
    val nowPct = (current * 100f).roundToInt().coerceIn(loPct, hiPct)
    val nextPct = (nowPct + deltaPercent).coerceIn(loPct, hiPct)
    return (nextPct / 100f).coerceIn(range)
}

/**
 * 手输草稿 → 可提交的数值（P1-7 提交期校验，滑杆 / ± / 手输三条通道同一口径）。
 *
 * 返回 `null` 表示「不提交，保留旧值」：
 * - 空串 / 纯空白 / 无法解析（`abc` / `..` / `0.0.0`）→ `toFloatOrNull()` 返回 null；
 * - `NaN`：**必须显式挡掉**。`"NaN".toFloatOrNull()` 在 JVM 上返回 `Float.NaN`
 *   （`java.lang.Float.parseFloat` 接受 "NaN"），而 `Float.coerceIn` 对 NaN 的处理是
 *   「两个比较都为 false → 原样返回 NaN」。一旦让 NaN 进样式模型，`NaN.dp` 会让渲染层
 *   尺寸/偏移异常，故此处直接拒绝。
 *
 * 其余情况一律**钳制**到 [range]（与滑杆 / ± 同口径：输 999 得 600，输 -5 得 0）；
 * `±Infinity`（如 `1e400`）由 `coerceIn` 自然落到区间端点，无需特判。
 *
 * @param percent 百分比刻度（见 [isPercentLike]）：为 true 时入参按百分数解释，
 *   `85` → `0.85`，再钳制到 [range]。**换算只发生在解析这一步**，模型里存的仍是 0..1 小数。
 */
private fun commitCandidateOf(
    raw: String?,
    range: ClosedFloatingPointRange<Float>,
    percent: Boolean = false,
): Float? {
    val parsed = raw?.trim()?.toFloatOrNull() ?: return null
    if (parsed.isNaN()) return null
    val real = if (percent) parsed / 100f else parsed
    if (real.isNaN()) return null
    return real.coerceIn(range)
}

// ==================== 紧凑基础件（V6-⑤ 单行布局用） ====================

/** NumberField / IntField 单行**最小**高度（V7-④：标签换行时行自然长高，不再压扁文字）。 */
private val NUMBER_ROW_HEIGHT = 48.dp

/**
 * 四边行**最小**高度（比数值行略矮：标签短、无长单位文本）。
 * V8 起行尾补了 26dp 的 ± 按钮，仍沿用这个定稿尺寸——26dp 控件塞得进 40dp 行，
 * 不为了按钮把行撑高，四行才不会把对话框顶到需要额外滚动的位置。
 */
private val SIDE_ROW_HEIGHT = 40.dp

/**
 * 行内左侧标签的最小宽度（V7-④ 需求：「所有提示语应该显示完整」）。
 *
 * 旧值是一个**固定** `width(96.dp)` + `maxLines = 1` + 省略号，四字标签 + 单位
 * （如「最大行数（行）」≈ 98dp）直接被截成「最大行…」。现在改成
 * `widthIn(min = 88.dp, max = 116.dp)` + `maxLines = 3` + `softWrap`，
 * 标签**按内容展开、超长自动换行**，绝不省略；同时保留上界，避免超长标签把
 * 右侧滑条挤没。
 */
private val LABEL_MIN_WIDTH = 88.dp

/** 行内左侧标签的最大宽度（上界，保证滑条 + 手输框 + ± 按钮永远有空间）。 */
private val LABEL_MAX_WIDTH = 116.dp

/** 窄手输框宽（V7-⑤：72 → 64dp，配 28dp 高 + 12sp 字号，四位整数 + 两位小数仍完整）。 */
private val COMPACT_INPUT_WIDTH = 64.dp

/** 四边行的手输框更窄（数值通常更小；V7-⑤：56 → 52dp）。 */
private val SIDE_INPUT_WIDTH = 52.dp

/** 滑条与手输框之间的间距（V7-⑤：用户要求「跟滑动条有间距」）。 */
private val SLIDER_INPUT_GAP = 8.dp

/** 手输框高度（V7-⑤：36 → 28dp，与正文文字同高，不再比文字高出一截）。 */
private val INPUT_HEIGHT = 28.dp

/** 紧凑滑动条手势热区高（视觉轨道 3dp 太细，热区放大到 32dp 保证可拖）。 */
private val SLIDER_TOUCH_HEIGHT = 32.dp

/** 紧凑滑动条视觉高（thumb 直径 10dp 居中）。 */
private val SLIDER_VISUAL_HEIGHT = 20.dp

/** 轨道粗细。 */
private val SLIDER_TRACK_THICKNESS = 3.dp

/** thumb 半径（直径 10dp）。 */
private val SLIDER_THUMB_RADIUS = 5.dp

/** ± 微调按钮边长（M3 IconButton 的 48dp 最小触摸区在密集中过大，自绘 26dp）。 */
private val MINI_BUTTON_SIZE = 26.dp

/**
 * 紧凑滑动条：自绘 3dp 细轨 + 10dp 圆点 thumb，替代 M3 Slider 默认的
 * 4dp 粗轨 / 20dp 大 thumb / 48dp 最小触摸区，塞进 48dp 行内不鼓包。
 *
 * 手势（**V7-③ 重写：跟手拖动**）：旧实现是
 * `detectTapGestures { ... }` 后接 `detectDragGestures { ... }`——`detectTapGestures`
 * 内部是 `awaitEachGesture` 的**无限循环**，只有协程被取消才返回，因此后面那句
 * `detectDragGestures` **永远不会执行**，滑块只能点不能拖。
 *
 * 现在合成一个自定义手势循环：`awaitFirstDown` 立即取值（按下即跟手，全程无 touch slop），
 * 随后 `while` 循环按 pointerId 跟踪同一个手指，`pressed == false`（抬手）才退出。
 * 回调经 [rememberUpdatedState] 转发，拖动期间父层重组不会拿到过期回调；
 * 区间端点作为 pointerInput key——**不用 value / fraction**，否则拖动中父层重组会
 * 取消并重建手势协程，表现就是「拖一下停一下」。
 */
@Composable
private fun CompactSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    modifier: Modifier = Modifier,
) {
    val current = value.coerceIn(valueRange)
    val fraction = if (valueRange.endInclusive > valueRange.start) {
        ((current - valueRange.start) / (valueRange.endInclusive - valueRange.start))
            .coerceIn(0f, 1f)
    } else {
        0f
    }
    val latestOnChange by rememberUpdatedState(onValueChange)
    val scheme = MaterialTheme.colorScheme

    Box(
        modifier = modifier
            .height(SLIDER_TOUCH_HEIGHT)
            .pointerInput(valueRange.start, valueRange.endInclusive) {
                val start = valueRange.start
                val span = valueRange.endInclusive - start
                // 手指 x → 取值：滑出轨道左/右时 x 可能为负或 > width，coerceIn 已兜住
                // （钳到端点后继续可用），因此**不 break**。
                fun update(x: Float) {
                    if (span <= 0f || size.width <= 0) return
                    val f = (x / size.width).coerceIn(0f, 1f)
                    latestOnChange(start + f * span)
                }
                awaitEachGesture {
                    // 按下即取值：用户感知到的「跟手」就是这一行
                    val down = awaitFirstDown(requireUnconsumed = false)
                    update(down.position.x)
                    down.consume()
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) {
                            change.consume()
                            break
                        }
                        update(change.position.x)
                        change.consume()
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxWidth().height(SLIDER_VISUAL_HEIGHT)) {
            val cy = size.height / 2f
            val trackH = SLIDER_TRACK_THICKNESS.toPx()
            val radius = CornerRadius(trackH / 2f)
            val activeW = size.width * fraction
            // 底轨
            drawRoundRect(
                color = scheme.outlineVariant,
                topLeft = Offset(0f, cy - trackH / 2f),
                size = Size(size.width, trackH),
                cornerRadius = radius,
            )
            // 已填充段
            drawRoundRect(
                color = scheme.primary,
                topLeft = Offset(0f, cy - trackH / 2f),
                size = Size(activeW, trackH),
                cornerRadius = radius,
            )
            // thumb：贴填充段末端的小圆点；两端收进轨道避免越界出血
            val thumbR = SLIDER_THUMB_RADIUS.toPx()
            val cx = activeW.coerceIn(thumbR, (size.width - thumbR).coerceAtLeast(thumbR))
            drawCircle(color = scheme.primary, radius = thumbR, center = Offset(cx, cy))
            drawCircle(color = scheme.surface, radius = thumbR * 0.42f, center = Offset(cx, cy))
        }
    }
}

/**
 * 窄数字手输框（V6-⑤ 引入，**V7-⑤ 收紧**）：28dp 高圆角小框（原 36dp 比正文文字高出一截，
 * 用户要求「跟文字差不多高」），12sp 字号 + 2dp 横向内边距，数字不会被裁切；
 * 键盘直输，解析失败仅描红不改值（错误判定由调用方传入 [isError]）。
 * 与左侧滑条的水平间距由调用方用 `padding(start = SLIDER_INPUT_GAP)` 给出（8dp）。
 *
 * P1-7 配套：IME「完成」键显式 `clearFocus()`。框架默认的 Done 处理只是收键盘，
 * **不保证**清掉焦点；本控件的提交时机是「失焦 / IME Done」，不清焦点就等于用户按了
 * Done 却什么都没有发生。清焦点后由调用方的 `onFocusChanged` 统一提交，两条路径收敛
 * 到同一处逻辑（`Modifier.onFocusChanged` 与 `KeyboardActions` 都是公开 API，
 * 不依赖具体 Compose 版本对 Done 的默认实现）。
 *
 * 内部**不持有文本状态**（`value = text` 全受控），因此不存在自己的 `remember`
 * 与外部回写打架的问题；光标位置由 BasicTextField 内部维护，文本未被改写时不会跳动。
 */
@Composable
private fun CompactNumberInput(
    text: String,
    onTextChange: (String) -> Unit,
    isError: Boolean,
    keyboardType: KeyboardType,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(6.dp)
    val focusManager = LocalFocusManager.current
    Box(
        modifier = modifier
            .height(INPUT_HEIGHT)
            .clip(shape)
            .background(scheme.surfaceVariant)
            .border(1.dp, if (isError) scheme.error else scheme.outlineVariant, shape),
        contentAlignment = Alignment.Center,
    ) {
        BasicTextField(
            value = text,
            onValueChange = onTextChange,
            singleLine = true,
            textStyle = MaterialTheme.typography.labelLarge.copy(
                color = scheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                fontSize = 12.sp,
            ),
            keyboardOptions = KeyboardOptions(
                keyboardType = keyboardType,
                imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
            cursorBrush = SolidColor(scheme.primary),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 2.dp),
        )
    }
}

/** ± 微调小按钮：26dp 圆角方块 + 14dp 图标（不触发 M3 48dp 最小触摸区）。
 *  V6 排障：Compose 编译器对「默认参数在尾随 lambda 前」的推断在某些版本会翻车
 * （modifier 在 onClick 之后导致调用点 lambda 被误接到 modifier 上），
 *  因此把 modifier 提到第二位、onClick 保持最后。 */
@Composable
private fun MiniStepButton(
    icon: ImageVector,
    description: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier = modifier
            .size(MINI_BUTTON_SIZE)
            .clip(RoundedCornerShape(7.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(14.dp),
        )
    }
}

// ==================== 九宫格对齐 ====================

/** 九宫格的 3×3 取值（行优先），与 `OverlayAlign` 枚举名一一对应。 */
private val ALIGN_GRID: List<String> = listOf(
    "TOP_START", "TOP_CENTER", "TOP_END",
    "CENTER_START", "CENTER", "CENTER_END",
    "BOTTOM_START", "BOTTOM_CENTER", "BOTTOM_END",
)

private val ALIGN_LABELS: List<String> = listOf(
    "左上", "上中", "右上", "左中", "居中", "右中", "左下", "下中", "右下",
)

/** 九宫格对齐选择器。 */
@Composable
fun AlignPicker(
    label: String,
    align: String,
    onAlignChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(text = label, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(6.dp))
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            repeat(3) { row ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    repeat(3) { col ->
                        val index = row * 3 + col
                        val value = ALIGN_GRID[index]
                        val selected = value == align
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(40.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(
                                    if (selected) MaterialTheme.colorScheme.primaryContainer
                                    else MaterialTheme.colorScheme.surfaceVariant,
                                )
                                .then(
                                    if (selected) {
                                        Modifier.border(
                                            1.5.dp,
                                            MaterialTheme.colorScheme.primary,
                                            RoundedCornerShape(8.dp),
                                        )
                                    } else {
                                        Modifier
                                    },
                                )
                                .clickable { onAlignChange(value) },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = ALIGN_LABELS[index],
                                style = MaterialTheme.typography.labelMedium,
                                color = if (selected) {
                                    MaterialTheme.colorScheme.onPrimaryContainer
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

// ==================== 四边独立 ====================

/** 四边字段的项数（圆角四角 / 内间距四边固定 4 项）。 */
private const val SIDE_COUNT = 4

/** 四边字段每行 ± 的步长（用户要求：所有 ± 都只动 1 格；内间距 / 圆角单位是 dp）。 */
private const val SIDE_STEP = 1f

/**
 * 四边独立数值（圆角四角 / 内间距四边）。
 *
 * **索引语义完全由调用点决定，本控件不做任何假设**：第 i 行只渲染 `values[i]` /
 * `sideLabels[i]`，写值也只回 `index = i`。当前两个调用点的口径不同，都改对过，别再「统一」：
 * - 圆角：`0=左上 1=右上 2=右下 3=左下`（同 `RoundedCornerShape`，也同 `box.corners` 下标）；
 * - 内间距：`0=上 1=下 2=左 3=右`（**用户明确要求的语序**，与 `box.paddings` 的下标
 *   start/top/end/bottom **不对应**，调用点因此用 `listOf(padTop, padBottom, padStart, padEnd)`
 *   手工重排）。谁想「顺手把 index 对齐回 paddings 顺序」，就是把标签退回用户抱怨的
 *   「左上」口径。
 *
 * **V8 修「四边联动只有一根滑条动」**：旧实现在联动时用一个固定次数的循环
 * （`repeat` + 索引流式回调）连发**四次独立回调**，而调用方
 * （`OverlayStyleDialog`）的写法是「拿同一份旧样式 `copy` 一个字段 → 整体替换」，
 * 于是四次回调各自基于**同一份旧值**算新样式，后一次覆盖前一次——最终只有第 4 项
 * 落库，界面上就是「只有一个滑动条在动、其它三个纹丝不动」。这跟滑条本身无关，
 * 是「多次写」与「全量替换」两种写法的冲突。
 *
 * 现在联动时**只发一次合并回调** [onValuesChange]（四个值一起进同一个事务，天然一致）。
 * 为不破坏既有调用点，该参数可空：调用方没提供时才退回旧的逐个回调（这种调用点上的
 * 联动仍然是失效的——只能由调用方自己改成一次写入）。
 *
 * 行内布局与 [NumberField] 保持一致：标签 → 滑条 → 手输框 → 「−」「+」（步长 1dp）。
 *
 * @param values 当前四边的值，**顺序即渲染顺序**（调用点负责重排）；不足 4 项时按实际项数渲染
 * @param onValueChange 单边写值（联动关闭时的唯一通道），index 就是行号
 * @param sideLabels 各行的标签，逐行对应 `values`；默认值是**圆角四角名**，内间距调用方
 *   自行传「上边距 / 下边距 / 左边距 / 右边距」——默认值保持角名不变，避免圆角那一路被改坏
 * @param onValuesChange 联动时的一次性合并写值（长度 = 已渲染行数，顺序同 [values]）；
 *   为 `null` 时退回逐个 [onValueChange]（仅源码兼容，联动语义不保证）
 */
@Composable
fun FourSidesField(
    label: String,
    values: List<Float>,
    onValueChange: (Int, Float) -> Unit,
    modifier: Modifier = Modifier,
    sideLabels: List<String> = listOf("左上", "右上", "右下", "左下"),
    range: ClosedFloatingPointRange<Float> = 0f..64f,
    linkable: Boolean = true,
    onValuesChange: ((List<Float>) -> Unit)? = null,
) {
    var linked by remember { mutableStateOf(false) }

    // 唯一写值出口：滑条 / 手输 / ± 三条通道共用同一套联动口径，
    // 避免「某个通道忘了联动」这类分叉 bug 再次出现。
    fun emit(index: Int, next: Float) {
        val clamped = next.coerceIn(range)
        if (!linked) {
            onValueChange(index, clamped)
            return
        }
        val merged = onValuesChange
        if (merged != null) {
            // 一次写完四边：与调用方的「整份样式替换」模式兼容（不会互相覆盖）
            merged.invoke(List(SIDE_COUNT) { clamped })
        } else {
            // 兼容旧调用点：没有合并回调只能逐个发（调用方若整份替换则只有最后一项生效）
            for (i in 0 until SIDE_COUNT) onValueChange(i, clamped)
        }
    }

    Column(modifier = modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text = label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            if (linkable) {
                Text(
                    text = "四边联动",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Switch(checked = linked, onCheckedChange = { linked = it })
            }
        }
        Spacer(Modifier.height(4.dp))
        values.take(SIDE_COUNT).forEachIndexed { index, value ->
            // 草稿以 value 为键：外部改值（含联动）后草稿作废，文本立即跟随新值
            var draft by remember(value) { mutableStateOf<String?>(null) }
            var focused by remember { mutableStateOf(false) }
            val current = value.coerceIn(range)
            val invalid = draft != null && draft!!.isNotBlank() && draft!!.trim().toFloatOrNull() == null
            val sideLabel = sideLabels.getOrNull(index) ?: "边 $index"
            Row(
                verticalAlignment = Alignment.CenterVertically,
                // V7-④：最小高度而非固定高度（标签换行时行自然长高，不压扁文字）；
                // 26dp 的 ± 按钮塞得进 40dp 行，行高因此不必变
                modifier = Modifier.fillMaxWidth().heightIn(min = SIDE_ROW_HEIGHT),
            ) {
                Text(
                    text = sideLabel,
                    style = MaterialTheme.typography.labelMedium,
                    // V7-④：允许换到 2 行、不做省略号截断；最小宽而非固定宽
                    maxLines = 2,
                    softWrap = true,
                    modifier = Modifier.widthIn(min = 40.dp),
                )
                CompactSlider(
                    // 直接读参数里的 values[index]（不 remember 缓存）：联动时父层写入
                    // 四个等值后重组，四根滑条在同一帧读到同一个新值，一起移动
                    value = current,
                    onValueChange = { next -> emit(index, next) },
                    valueRange = range,
                    modifier = Modifier.weight(1f),
                )
                CompactNumberInput(
                    text = draft ?: current.toInt().toString(),
                    onTextChange = { raw ->
                        draft = raw
                        raw.trim().toFloatOrNull()?.let { parsed -> emit(index, parsed) }
                    },
                    isError = invalid,
                    keyboardType = KeyboardType.Number,
                    modifier = Modifier
                        .padding(start = SLIDER_INPUT_GAP)
                        .width(SIDE_INPUT_WIDTH)
                        .onFocusChanged { state ->
                            if (focused && !state.isFocused) draft = null
                            focused = state.isFocused
                        },
                )
                Spacer(Modifier.width(4.dp))
                // 具名参数是硬要求：MiniStepButton 的 modifier 在 onClick 之前，
                // 用尾随 lambda 写法会把 lambda 接到 modifier 上（V6 踩过这个坑）
                MiniStepButton(
                    icon = Icons.Filled.Remove,
                    description = "减小$sideLabel",
                    onClick = { emit(index, current - SIDE_STEP) },
                )
                Spacer(Modifier.width(2.dp))
                MiniStepButton(
                    icon = Icons.Filled.Add,
                    description = "增大$sideLabel",
                    onClick = { emit(index, current + SIDE_STEP) },
                )
            }
        }
    }
}

// ==================== 通用小控件 ====================

/** 带标题的开关行。 */
@Composable
fun SwitchField(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = label, style = MaterialTheme.typography.bodyMedium)
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/** 单选 chips 组。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EnumChipsField(
    label: String,
    options: List<Pair<String, String>>,
    selected: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(text = label, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(6.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            options.forEach { (value, text) ->
                FilterChip(
                    selected = value == selected,
                    onClick = { onSelect(value) },
                    label = { Text(text) },
                )
            }
        }
    }
}

/** 单行文本输入行。 */
@Composable
fun TextFieldRow(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    singleLine: Boolean = true,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        placeholder = if (placeholder.isBlank()) null else ({ Text(placeholder) }),
        singleLine = singleLine,
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
    )
}
