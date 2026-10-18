package com.local.notificationfilter.ui.screens.overlay

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.FilledTonalButton
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.local.notificationfilter.core.model.DanmakuMode
import com.local.notificationfilter.core.model.DanmakuStackDirection
import com.local.notificationfilter.core.model.DanmakuStyle
import com.local.notificationfilter.core.model.FloatStyle
import com.local.notificationfilter.core.model.OverlayAlign
import com.local.notificationfilter.core.model.OverlayAnim
import com.local.notificationfilter.core.model.OverlayBoxStyle
import com.local.notificationfilter.core.model.OverlayPayload
import com.local.notificationfilter.core.model.OverlayTextStyle
import com.local.notificationfilter.core.model.Placeholders
import com.local.notificationfilter.service.OverlayController
import com.local.notificationfilter.ui.components.AlignPicker
import com.local.notificationfilter.ui.components.CollapsibleSection
import com.local.notificationfilter.ui.components.ColorField
import com.local.notificationfilter.ui.components.EnumChipsField
import com.local.notificationfilter.ui.components.FourSidesField
import com.local.notificationfilter.ui.components.GradientField
import com.local.notificationfilter.ui.components.IntField
import com.local.notificationfilter.ui.components.NumberField
import com.local.notificationfilter.ui.components.PlaceholderChips
import com.local.notificationfilter.ui.components.SwitchField
import com.local.notificationfilter.ui.overlay.OverlayPreviewBar
import com.local.notificationfilter.util.PermissionChecker
import kotlinx.coroutines.delay

/**
 * 屏幕显示样式配置对话框（V5 需求③弹幕双形态 / 需求④悬浮真机预览 / 需求⑤配置 UI）。
 *
 * 交互要点（用户明确要求）：
 * - **弹幕**：顶部常驻**页内预览条**（不随配置区滚动消失）；静态参数（颜色/尺寸/透明度）
 *   经重组实时反映；**动画参数（速度/停留/形态/条数/方向）一变，预览自动重播一遍**
 *   （animReplayTick 驱动 OverlayPreviewBar 重播入场动画）；另有「真机预览」按钮把
 *   当前样式实跑一条弹幕到屏幕上；
 * - **悬浮**：**没有页内预览条**——打开对话框即把编辑中的样式投到**屏幕实际位置**
 *   （[OverlayController.showFloatPreview]，与正式悬浮同一渲染管线，所见即所得），
 *   参数每改一次防抖 ~300ms 后重新投影，关闭/销毁对话框时撤掉（DisposableEffect 兜底）；
 * - 弹幕/悬浮文案支持**占位符模板**（与「替换」同一套 [Placeholders]，留空 = 原文案），
 *   chips 插入到当前聚焦模板的光标处（交互同规则编辑页的替换模板，V2T07）；
 * - 颜色 = 无极选色面板（HsvColorPicker，经 [ColorField]）；渐变 = 类型 + 多色标 +
 *   线性角度/径向半径比例（[GradientField]）；
 * - 各分区均可折叠（复用 [CollapsibleSection]）。
 *
 * 采用**页内 Dialog 而非导航页**：避免 popBackStack 导致规则编辑页 ViewModel 重建、
 * 未保存表单丢失。
 */

/** 样式编辑目标（决定显示哪一组分区）。 */
enum class OverlayStyleKind(val label: String) {
    DANMAKU("弹幕"),
    FLOAT("自定义位置悬浮"),
}

/** 悬浮真机预览的刷新防抖（ms）：拖动滑杆期间不连续重建悬浮窗。 */
private const val FLOAT_PREVIEW_DEBOUNCE_MS = 300L

/**
 * V9.10：预览「发射」按钮的循环上限。
 *
 * 点一次加一条，到 [PREVIEW_LAUNCH_CYCLE] 后回到 1 —— 再多就超出预览条限高被裁掉，
 * 反而看不清排布。三条足够判断「会不会互相遮住」。
 */
private const val PREVIEW_LAUNCH_CYCLE = 3

/**
 * V9.4：`{re1}`..`{re9}` 即时提取 token 的名字（不含花括号）。
 *
 * 用于从 [OverlayStyleDialog] 收到的提取 chips 反推出 `{reN}` 的可用上界，
 * 让「未识别占位符」提示与 [Placeholders.findUnknown] 的口径保持一致。
 */
private val RE_TOKEN_NAME = Regex("re([1-9])")

/**
 * 「消失动画」的可选集合（V8 / V9.3）。
 *
 * 入场动画可以花哨（滑入/回弹…），退场动画花哨反而拖慢「信息让位」的节奏，
 * 因此这里只需要这几档：无 / 淡出 / 四向滑出 / 缩放。
 *
 * **V9.3 语义纠偏**：`NONE` 的标签就是「无」，渲染层**真的什么动画都不做**
 * （窗口立即摘除）——不再写成「无（沿用淡出）」把两种语义混在一起。
 * 用户反馈「无动画就是无动画，不要淡入淡出」正是这条：想要渐隐请选「淡出」。
 *
 * 值是枚举 `name`（JSON 与渲染层同一套），文字只用于 chip 显示。
 */
private val ANIM_OUT_OPTIONS: List<Pair<String, String>> = listOf(
    OverlayAnim.NONE.name to "无",
    OverlayAnim.FADE.name to "淡出",
    // V9：四个方向的滑出与出现动画一一对应
    OverlayAnim.SLIDE_LEFT.name to "向右滑出",
    OverlayAnim.SLIDE_RIGHT.name to "向左滑出",
    OverlayAnim.SLIDE_TOP.name to "向上滑出",
    OverlayAnim.SLIDE_BOTTOM.name to "向下滑出",
    OverlayAnim.SCALE.name to "缩放",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OverlayStyleDialog(
    kind: OverlayStyleKind,
    danmaku: DanmakuStyle = DanmakuStyle(),
    float: FloatStyle = FloatStyle(),
    onDone: (DanmakuStyle, FloatStyle) -> Unit,
    onDismiss: () -> Unit,
    /**
     * V9.4：`{re1}`..`{re9}`「即时提取」chips（token 原文 to 显示名）。
     *
     * 弹幕 / 悬浮文案同样支持用提取器的正则从**当前这条通知**里抓内容重新显示，
     * 所以样式对话框里的占位符 chips 也要有这一组。默认空 = 老调用点行为不变。
     */
    extractTokens: List<Pair<String, String>> = emptyList(),
) {
    var d by remember { mutableStateOf(danmaku) }
    var f by remember { mutableStateOf(float) }

    val context = LocalContext.current
    // D11：预览载荷与「本 App 图标」自洽（App 名 + 取件码文案），语义一致不误导。
    val previewPayload = remember(context) {
        OverlayPayload(
            ruleId = 0L,
            packageName = context.packageName,
            appName = "通知过滤",
            title = "通知过滤",
            text = "取件码 8-3-9201，请在 24 小时内领取",
        )
    }

    // 动画参数变化计数：弹幕页内预览 / 悬浮真机预览共用的「重播」触发源。
    // 静态参数（颜色/尺寸/透明度）靠 Compose 重组实时反映，不计入（否则拖滑杆时
    // 预览会被反复打断重播）。
    var animReplayTick by remember { mutableIntStateOf(0) }

    // —— 悬浮真机预览（V5 需求④）——
    // 打开悬浮样式对话框即投影到真实屏幕位置；参数变化防抖 300ms 后重新投影；
    // 对话框销毁（关闭/离开页面）时撤掉预览。权限未授予时 OverlayController 内部
    // 静默跳过，顶部提示条说明状态。
    val floatPreviewGranted = remember { PermissionChecker.canDrawOverlays(context) }
    if (kind == OverlayStyleKind.FLOAT) {
        LaunchedEffect(f, animReplayTick) {
            delay(FLOAT_PREVIEW_DEBOUNCE_MS)
            OverlayController.showFloatPreview(previewPayload, f)
        }
        DisposableEffect(Unit) {
            onDispose { OverlayController.dismissFloatPreview() }
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            Column(Modifier.fillMaxSize()) {
                TopAppBar(
                    title = { Text("${kind.label}样式") },
                    navigationIcon = {
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Filled.Close, contentDescription = "关闭")
                        }
                    },
                    actions = {
                        Button(
                            onClick = { onDone(d, f) },
                            modifier = Modifier.padding(end = 8.dp),
                        ) { Text("完成") }
                    },
                )

                // —— 预览区（钉在顶部，不随下方滚动）——
                if (kind == OverlayStyleKind.DANMAKU) {
                    // V9.11：按钮不再**叠在**预览条上（上一版挡住了预览），
                    // 改为排在预览条**下方**的独立一行——既不遮内容，也不占滚动区。
                    var launchCount by remember { mutableIntStateOf(1) }
                    Column(modifier = Modifier.fillMaxWidth()) {
                        OverlayPreviewBar(
                            payload = previewPayload,
                            style = d,
                            replayKey = animReplayTick,
                            launchCount = launchCount,
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.End,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            // 「发射」：在预览里加一条（1→2→3→1 循环），看同屏多条如何排布
                            FilledTonalButton(
                                onClick = {
                                    launchCount = launchCount % PREVIEW_LAUNCH_CYCLE + 1
                                    animReplayTick++
                                },
                            ) { Text("发射", style = MaterialTheme.typography.labelMedium) }
                            Spacer(Modifier.width(8.dp))
                            // 「真机发射」：按当前样式真的往屏幕上打一条，所见即所得
                            FilledTonalButton(
                                onClick = { OverlayController.enqueueDanmaku(previewPayload, d) },
                            ) { Text("真机发射", style = MaterialTheme.typography.labelMedium) }
                        }
                    }
                } else {
                    FloatPreviewHintBar(granted = floatPreviewGranted)
                }

                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(horizontal = 12.dp),
                ) {
                    item {
                        if (kind == OverlayStyleKind.DANMAKU) {
                            DanmakuSections(
                                style = d,
                                onChange = { next ->
                                    // 动画类参数变化 → 预览自动重播一遍（用户强调的通用原则）
                                    if (next.animSignature() != d.animSignature()) animReplayTick++
                                    d = next
                                },
                                onPreviewOnDevice = {
                                    OverlayController.enqueueDanmaku(previewPayload, d)
                                },
                                extractTokens = extractTokens,
                            )
                        } else {
                            FloatSections(
                                style = f,
                                onChange = { next ->
                                    if (next.animSignature() != f.animSignature()) animReplayTick++
                                    f = next
                                },
                                extractTokens = extractTokens,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 动画类参数签名：只包含「驱动动画/排布」的字段——它一变预览就必须重播；
 * 静态参数（颜色/尺寸/圆角等）刻意排除，靠重组实时反映、不打断动画。
 */
private fun DanmakuStyle.animSignature(): List<Any?> = listOf(
    mode, animIn, animOut, animInMs, animOutMs, edgeFade, speedPxps, displaySeconds, maxConcurrent,
    stackAlign, stackDirection, topMarginDp, trackGapDp, stackGapDp, offsetXDp, offsetYDp,
)

private fun FloatStyle.animSignature(): List<Any?> = listOf(
    anim, animOut, animInMs, animOutMs, screenAlign, offsetTopDp, offsetEndDp, displaySeconds,
)

// ==================== 弹幕 ====================

@Composable
private fun DanmakuSections(
    style: DanmakuStyle,
    onChange: (DanmakuStyle) -> Unit,
    onPreviewOnDevice: () -> Unit,
    /** V9.4：透传给 [TemplateFields] 的 `{reN}` 即时提取 chips。 */
    extractTokens: List<Pair<String, String>> = emptyList(),
) {
    // ① 形态（放最前：它决定 ③ 渲染哪一组参数）
    CollapsibleSection(
        title = "① 弹幕形态",
        titleColor = MaterialTheme.colorScheme.primary,
        summary = style.modeEnum.label,
    ) {
        EnumChipsField(
            label = "形态",
            options = DanmakuMode.ALL.map { it.name to it.label },
            selected = style.mode,
            onSelect = { onChange(style.copy(mode = it)) },
        )
        Text(
            text = when (style.modeEnum) {
                DanmakuMode.THROUGH ->
                    "贯穿式：从屏幕右缘滚入、不停留、直接滚出左缘；多条占用顶部横向轨道。"

                DanmakuMode.POSITIONED ->
                    "定位式：在九宫格位置淡入出现、停留后淡出；多条按排队方向依次堆叠。"


            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(6.dp))
        OutlinedButton(onClick = onPreviewOnDevice, modifier = Modifier.fillMaxWidth()) {
            Text("真机预览")
        }
    }

    // ② 显示内容（含占位符模板）
    DanmakuContentSection(style, onChange, extractTokens)

    // ③ 形态专属参数（两形态互斥：同一时刻只有一种在屏幕上真实生效，
    // 所以两者共用序号 ③ —— 任一形态下看到的编号都是连续的 ①②③④⑤，不会跳号）
    when (style.modeEnum) {
        DanmakuMode.THROUGH -> ThroughDanmakuSection(style, onChange)
        DanmakuMode.POSITIONED -> PositionedDanmakuSection(style, onChange)
    }

    // ④/⑤ 文字 / 气泡（两形态共用；序号由调用点传入——弹幕区与悬浮区各一套编号）
    TextSection(
        title = "④ 文字样式",
        text = style.text,
        onChange = { onChange(style.copy(text = it)) },
    )
    BoxSection(
        title = "⑤ 气泡外观",
        box = style.box,
        onChange = { onChange(style.copy(box = it)) },
    )
}

/** 弹幕「显示内容」：图标/标题开关 + 占位符模板 + 分隔符。 */
@Composable
private fun DanmakuContentSection(
    style: DanmakuStyle,
    onChange: (DanmakuStyle) -> Unit,
    /** V9.4：透传给 [TemplateFields] 的 `{reN}` 即时提取 chips。 */
    extractTokens: List<Pair<String, String>> = emptyList(),
) {
    CollapsibleSection(
        title = "② 显示内容",
        titleColor = MaterialTheme.colorScheme.primary,
        summary = if (style.titleTemplate.isBlank() && style.textTemplate.isBlank()) {
            "原文案"
        } else {
            "自定义模板"
        },
    ) {
        SwitchField(
            label = "显示应用图标",
            checked = style.showIcon,
            onCheckedChange = { onChange(style.copy(showIcon = it)) },
        )
        SwitchField(
            label = "显示标题",
            checked = style.showTitle,
            onCheckedChange = { onChange(style.copy(showTitle = it)) },
            subtitle = "关闭则只显示正文",
        )
        TemplateFields(
            titleTemplate = style.titleTemplate,
            textTemplate = style.textTemplate,
            onTitleChange = { onChange(style.copy(titleTemplate = it)) },
            onTextChange = { onChange(style.copy(textTemplate = it)) },
            // V9.4：弹幕 / 悬浮文案同样支持 {re1}..{re9} 即时提取
            extractTokens = extractTokens,
        )
        OutlinedTextField(
            value = style.separator,
            onValueChange = { onChange(style.copy(separator = it)) },
            label = { Text("标题与正文分隔符") },
            placeholder = { Text("如： · ") },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
        )
    }
}

/** 弹幕「贯穿式参数」（形态A：不停留滚过屏幕）。 */
@Composable
private fun ThroughDanmakuSection(style: DanmakuStyle, onChange: (DanmakuStyle) -> Unit) {
    CollapsibleSection(
        title = "③ 贯穿式参数",
        titleColor = MaterialTheme.colorScheme.primary,
        summary = "${style.speedPxps.toInt()}px/s · 同屏 ${style.maxConcurrent} 条",
    ) {
        NumberField(
            label = "滚动速度",
            value = style.speedPxps,
            onValueChange = { onChange(style.copy(speedPxps = it)) },
            range = 60f..3000f,
            step = 20f,
            unit = "px/s",
        )
        Text(
            text = "贯穿式不停留：滚屏时长 =（屏宽+气泡宽）÷ 速度；速度越大滚得越快。" +
                "超出同屏条数的新弹幕排队等位。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        IntField(
            label = "同屏最多条数",
            value = style.maxConcurrent,
            onValueChange = { onChange(style.copy(maxConcurrent = it)) },
            range = 1..5,
        )
        NumberField(
            label = "距顶部",
            value = style.topMarginDp,
            onValueChange = { onChange(style.copy(topMarginDp = it)) },
            range = 0f..400f,
            step = 8f,
            unit = "dp",
        )
        NumberField(
            label = "轨道间距",
            value = style.trackGapDp,
            onValueChange = { onChange(style.copy(trackGapDp = it)) },
            range = 0f..24f,
            step = 2f,
            unit = "dp",
        )
        // V8：贯穿式进出屏幕边缘的淡入淡出（默认关 = 旧行为，老规则观感不变）
        SwitchField(
            label = "边缘淡入淡出",
            checked = style.edgeFade,
            onCheckedChange = { onChange(style.copy(edgeFade = it)) },
            subtitle = "进出屏幕左右边缘时淡入淡出",
        )
    }
}

/** 弹幕「定位式参数」（形态B：九宫格落位 + 淡入 + 停留 + 堆叠）。 */
@Composable
private fun PositionedDanmakuSection(style: DanmakuStyle, onChange: (DanmakuStyle) -> Unit) {
    CollapsibleSection(
        title = "③ 定位式参数",
        titleColor = MaterialTheme.colorScheme.primary,
        summary = "${OverlayAlign.of(style.stackAlign).label} · 停留 " +
            "${style.displaySeconds.toInt()}s · ${DanmakuStackDirection.of(style.stackDirection).label}",
    ) {
        AlignPicker(
            label = "落位（九宫格）",
            align = style.stackAlign,
            onAlignChange = { onChange(style.copy(stackAlign = it)) },
        )
        NumberField(
            label = "X 偏移",
            value = style.offsetXDp,
            onValueChange = { onChange(style.copy(offsetXDp = it)) },
            range = 0f..200f,
            step = 4f,
            unit = "dp",
        )
        NumberField(
            label = "Y 偏移",
            value = style.offsetYDp,
            onValueChange = { onChange(style.copy(offsetYDp = it)) },
            range = 0f..600f,
            step = 8f,
            unit = "dp",
        )
        Text(
            text = "X / Y 偏移：正方向指向屏幕中线，0 = 贴边落位。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        NumberField(
            label = "停留时长",
            value = style.displaySeconds,
            onValueChange = { onChange(style.copy(displaySeconds = it)) },
            range = 1f..30f,
            step = 1f,
            unit = "秒",
        )
        // V8：出现 / 消失动画各自独立可配（旧版只有固定淡入 + 淡出，用户嫌「生硬」）
        EnumChipsField(
            label = "出现动画",
            options = OverlayAnim.ALL.map { it.name to it.label },
            selected = style.animIn.name,
            onSelect = { onChange(style.copy(animIn = OverlayAnim.of(it))) },
        )
        EnumChipsField(
            label = "消失动画",
            options = ANIM_OUT_OPTIONS,
            selected = style.animOut.name,
            onSelect = { onChange(style.copy(animOut = OverlayAnim.of(it))) },
        )
        Text(
            text = "选「无」= 到点立即消失（不做任何动画）；要渐隐请选「淡出」。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // V9：出现/消失时长分开调（用户要求「动画有持续时间的选项」）
        IntField(
            label = "出现时长",
            value = style.animInMs,
            onValueChange = { onChange(style.copy(animInMs = it)) },
            range = 100..1000,
            unit = "毫秒",
        )
        IntField(
            label = "消失时长",
            value = style.animOutMs,
            onValueChange = { onChange(style.copy(animOutMs = it)) },
            range = 100..1000,
            unit = "毫秒",
        )
        Text(
            text = "时长越大动画越慢；消失时长同时决定退场超时兜底（+60ms）。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        IntField(
            label = "同屏最多条数",
            value = style.maxConcurrent,
            onValueChange = { onChange(style.copy(maxConcurrent = it)) },
            range = 1..5,
        )
        EnumChipsField(
            label = "排队方向",
            options = DanmakuStackDirection.ALL.map { it.name to it.label },
            selected = style.stackDirection,
            onSelect = { onChange(style.copy(stackDirection = it)) },
        )
        NumberField(
            label = "条间距",
            value = style.stackGapDp,
            onValueChange = { onChange(style.copy(stackGapDp = it)) },
            range = 0f..24f,
            step = 2f,
            unit = "dp",
        )
    }
}

// ==================== 悬浮 ====================

/**
 * 垂直偏移项标签（P2-8）：按屏幕停靠方向动态生成。
 *
 * `OverlayController` 里 `yDp = style.offsetTopDp` 与 `gravityOf()` 相组合，语义是
 * **从停靠边向屏幕中线量**：TOP 系 → 距屏幕顶部；BOTTOM 系 → 距屏幕底部；
 * 居中系由 `Gravity.CENTER_VERTICAL` 从垂直中线量（正数向下）。
 * 原先写死「距屏幕顶部」，9 个位置里有 6 个文案与实际方向不符，用户越调越迷惑。
 */
private fun verticalOffsetLabel(align: OverlayAlign): String = when (align) {
    OverlayAlign.TOP_START, OverlayAlign.TOP_CENTER, OverlayAlign.TOP_END -> "距顶部"
    OverlayAlign.BOTTOM_START, OverlayAlign.BOTTOM_CENTER, OverlayAlign.BOTTOM_END -> "距底部"
    else -> "偏离中线"
}

/**
 * 水平偏移项标签（P2-8）：口径同 [verticalOffsetLabel]。
 *
 * `FloatBubble` 用 `offsetEndDp` 作 start/end 内边距：START 系从左侧量、
 * END 系从右侧量（居中系没有停靠边，取值为横向偏离中线）。
 */
private fun horizontalOffsetLabel(align: OverlayAlign): String = when (align) {
    OverlayAlign.TOP_START, OverlayAlign.CENTER_START, OverlayAlign.BOTTOM_START -> "距左侧"
    OverlayAlign.TOP_END, OverlayAlign.CENTER_END, OverlayAlign.BOTTOM_END -> "距右侧"
    else -> "偏离中线"
}

@Composable
private fun FloatSections(
    style: FloatStyle,
    onChange: (FloatStyle) -> Unit,
    /** V9.4：透传给 [TemplateFields] 的 `{reN}` 即时提取 chips。 */
    extractTokens: List<Pair<String, String>> = emptyList(),
) {
    // ① 显示内容（含占位符模板）
    CollapsibleSection(
        title = "① 显示内容",
        titleColor = MaterialTheme.colorScheme.primary,
        summary = if (style.titleTemplate.isBlank() && style.textTemplate.isBlank()) {
            "原文案"
        } else {
            "自定义模板"
        },
    ) {
        SwitchField(
            label = "显示应用图标",
            checked = style.showIcon,
            onCheckedChange = { onChange(style.copy(showIcon = it)) },
        )
        SwitchField(
            label = "显示标题",
            checked = style.showTitle,
            onCheckedChange = { onChange(style.copy(showTitle = it)) },
            subtitle = "关闭则只显示正文",
        )
        TemplateFields(
            titleTemplate = style.titleTemplate,
            textTemplate = style.textTemplate,
            onTitleChange = { onChange(style.copy(titleTemplate = it)) },
            onTextChange = { onChange(style.copy(textTemplate = it)) },
            // V9.4：弹幕 / 悬浮文案同样支持 {re1}..{re9} 即时提取
            extractTokens = extractTokens,
        )
        OutlinedTextField(
            value = style.separator,
            onValueChange = { onChange(style.copy(separator = it)) },
            label = { Text("标题与正文分隔符") },
            placeholder = { Text("如： · ") },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
        )
    }

    // ② 位置与动画
    CollapsibleSection(
        title = "② 位置与动画",
        titleColor = MaterialTheme.colorScheme.primary,
        summary = "${OverlayAlign.of(style.screenAlign).label} · " +
            if (style.displaySeconds > 0f) "停留 ${style.displaySeconds.toInt()}s" else "常驻",
    ) {
        AlignPicker(
            label = "屏幕停靠位置",
            align = style.screenAlign,
            onAlignChange = { onChange(style.copy(screenAlign = it)) },
        )
        NumberField(
            label = verticalOffsetLabel(style.screenAlignEnum),
            value = style.offsetTopDp,
            onValueChange = { onChange(style.copy(offsetTopDp = it)) },
            range = 0f..600f,
            step = 8f,
            unit = "dp",
        )
        NumberField(
            label = horizontalOffsetLabel(style.screenAlignEnum),
            value = style.offsetEndDp,
            onValueChange = { onChange(style.copy(offsetEndDp = it)) },
            range = 0f..200f,
            step = 4f,
            unit = "dp",
        )
        NumberField(
            label = "停留时长",
            value = style.displaySeconds,
            onValueChange = { onChange(style.copy(displaySeconds = it)) },
            range = 0f..120f,
            step = 1f,
            unit = "秒",
        )
        EnumChipsField(
            label = "出现动画",
            options = OverlayAnim.ALL.map { it.name to it.label },
            selected = style.anim,
            onSelect = { onChange(style.copy(anim = it)) },
        )
        // V8：消失动画独立成组（旧版消失 = 把入场进度倒放，末端生硬）
        EnumChipsField(
            label = "消失动画",
            options = ANIM_OUT_OPTIONS,
            selected = style.animOut.name,
            onSelect = { onChange(style.copy(animOut = OverlayAnim.of(it))) },
        )
        Text(
            text = "选「无」= 到点立即消失；「出现」也选「无」时，连续命中会在同一条悬浮里" +
                "直接换文字（无缝衔接，不重播动画、不闪烁）。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // V9：悬浮的出现/消失时长分开调
        IntField(
            label = "出现时长",
            value = style.animInMs,
            onValueChange = { onChange(style.copy(animInMs = it)) },
            range = 100..1000,
            unit = "毫秒",
        )
        IntField(
            label = "消失时长",
            value = style.animOutMs,
            onValueChange = { onChange(style.copy(animOutMs = it)) },
            range = 100..1000,
            unit = "毫秒",
        )
        Text(
            text = "时长越大动画越慢；消失时长同时决定退场超时兜底（+60ms）。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = "说明：悬浮窗为「点击穿透」——不拦截屏幕触摸，避免挡住下方 App。" +
                "因此卡片本身不可点击，到时自动消失即可。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    // ③ 卡片高度与滚动（V7 需求 8：汇总卡片条数多了不能撑满屏幕）
    CollapsibleSection(
        title = "③ 卡片高度与滚动",
        titleColor = MaterialTheme.colorScheme.primary,
        defaultExpanded = false,
        summary = when {
            style.maxHeightDp <= 0f -> "不限制高度"
            style.contentScrollable -> "最高 ${style.maxHeightDp.toInt()}dp · 可滑动"
            else -> "最高 ${style.maxHeightDp.toInt()}dp"
        },
    ) {
        IntField(
            label = "内容区最大高度",
            value = style.maxHeightDp.toInt(),
            onValueChange = { onChange(style.copy(maxHeightDp = it.toFloat())) },
            range = 0..600,
            step = 8,
            unit = "dp（0=不限）",
        )
        Text(
            text = "超出后卡片内部滚动",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SwitchField(
            label = "内容超出时可上下滑动",
            checked = style.contentScrollable,
            onCheckedChange = { onChange(style.copy(contentScrollable = it)) },
            subtitle = "注意：开启后卡片会拦截手指触摸（不再点击穿透），仅建议汇总卡片使用",
        )
        Text(
            text = "聚合汇总卡片建议：最大高度 240dp + 开启滑动",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    // ④ 文字 / ⑤ 气泡（序号由调用点传入——悬浮区自己一套编号）
    TextSection(
        title = "④ 文字样式",
        text = style.text,
        onChange = { onChange(style.copy(text = it)) },
    )
    BoxSection(
        title = "⑤ 气泡外观",
        box = style.box,
        onChange = { onChange(style.copy(box = it)) },
    )
}

/** 悬浮模式的顶部提示条：告知「预览已投到屏幕实际位置」（或权限未授予）。 */
@Composable
private fun FloatPreviewHintBar(granted: Boolean) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Text(
            text = if (granted) {
                "预览已投到屏幕实际位置——下方修改约 0.3s 后即时生效；关闭本页自动撤掉预览。"
            } else {
                "悬浮窗权限未授予，真机预览不可用；请先授予权限后重开本页。下方修改仍会保存到样式。"
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
        )
    }
}

// ==================== 共用：占位符模板（V5 需求③/④） ====================

/**
 * 标题/正文占位符模板编辑（与规则编辑页替换模板同一套交互，V2T07）：
 * - TextFieldValue 驱动（chips 需要拿到光标位置插入）；
 * - [PlaceholderChips] 点击插入到**当前聚焦**的模板框光标处；
 * - 未识别占位符红字提示（[Placeholders.findUnknown]），但不阻断保存
 *   （渲染时未识别 token 按空串，与「替换」一致）。
 */
@Composable
private fun TemplateFields(
    titleTemplate: String,
    textTemplate: String,
    onTitleChange: (String) -> Unit,
    onTextChange: (String) -> Unit,
    /**
     * V9.4：`{re1}`..`{re9}` 即时提取 chips（token 原文 to 显示名），由调用方从规则的
     * 提取器配置生成（[Placeholders.extractTokens]）。默认空 → 与 V9.3 及之前一致
     * （不显示该组、且 `{reN}` 会被判为未识别）。
     */
    extractTokens: List<Pair<String, String>> = emptyList(),
) {
    var titleValue by remember { mutableStateOf(TextFieldValue(titleTemplate)) }
    var textValue by remember { mutableStateOf(TextFieldValue(textTemplate)) }
    /** 0 = 标题框 / 1 = 正文框（chips 插入目标；未聚焦过按标题）。 */
    var focused by remember { mutableIntStateOf(0) }

    // 外部状态回填（如形态切换后样式对象重建）：只在外部值与本地文本不一致时同步，避免回环
    LaunchedEffect(titleTemplate, textTemplate) {
        if (titleValue.text != titleTemplate) titleValue = TextFieldValue(titleTemplate)
        if (textValue.text != textTemplate) textValue = TextFieldValue(textTemplate)
    }

    fun insertToken(token: String) {
        val field = if (focused == 0) titleValue else textValue
        val sel = field.selection
        val start = sel.min.coerceIn(0, field.text.length)
        val end = sel.max.coerceIn(0, field.text.length)
        val newText = field.text.substring(0, start) + token + field.text.substring(end)
        val cursor = start + token.length
        val next = TextFieldValue(newText, TextRange(cursor))
        if (focused == 0) {
            titleValue = next
            onTitleChange(newText)
        } else {
            textValue = next
            onTextChange(newText)
        }
    }

    Text(
        text = "占位符模板：留空则用原通知标题/正文，占位符与「替换」同一套。",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp),
    )
    OutlinedTextField(
        value = titleValue,
        onValueChange = {
            titleValue = it
            onTitleChange(it.text)
        },
        label = { Text("标题模板") },
        placeholder = { Text("如：{app} · {title}") },
        singleLine = true,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .onFocusChanged { if (it.isFocused) focused = 0 },
    )
    OutlinedTextField(
        value = textValue,
        onValueChange = {
            textValue = it
            onTextChange(it.text)
        },
        label = { Text("正文模板") },
        placeholder = { Text("如：取件码 {extras.code} / {text}") },
        singleLine = true,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .onFocusChanged { if (it.isFocused) focused = 1 },
    )

    // V9.4：{reN} 的可用上界由传入的提取 chips 推导，否则在弹幕/悬浮文案里写 {re1}
    // 会被误报成「未识别占位符」（而它其实是能正常渲染的）。
    val reCount = remember(extractTokens) {
        extractTokens.mapNotNull { pair ->
            RE_TOKEN_NAME.matchEntire(pair.first.removeSurrounding("{", "}"))
                ?.groupValues?.get(1)?.toIntOrNull()
        }.maxOrNull() ?: 0
    }
    val unknown = remember(titleTemplate, textTemplate, reCount) {
        (
            Placeholders.findUnknown(titleTemplate, reCount = reCount) +
                Placeholders.findUnknown(textTemplate, reCount = reCount)
            ).distinct()
    }
    if (unknown.isNotEmpty()) {
        Text(
            text = "未识别占位符：${unknown.joinToString("、")}（按空串渲染）",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.error,
        )
        Spacer(Modifier.height(4.dp))
    }
    PlaceholderChips(onInsert = ::insertToken, extractTokens = extractTokens)
    // V9.6：把「有条件才生效」的 token 前提直接写在这里。
    // 用户反馈过「{re1} 说没有这个东西」——问题在前提没写明。
    Text(
        text = "注意：{re1}~{re9} 需先在「⑤ 内容提取与汇总」里配好并启用提取器。",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.error,
    )
}

// ==================== 共用：文字 ====================

@Composable
private fun TextSection(
    title: String,
    text: OverlayTextStyle,
    onChange: (OverlayTextStyle) -> Unit,
) {
    CollapsibleSection(
        title = title,
        titleColor = MaterialTheme.colorScheme.primary,
        summary = "${text.sizeSp.toInt()}sp ${if (text.bold) "粗体" else ""}",
        defaultExpanded = false,
    ) {
        NumberField(
            label = "字号",
            value = text.sizeSp,
            onValueChange = { onChange(text.copy(sizeSp = it)) },
            range = 8f..40f,
            step = 1f,
            unit = "sp",
        )
        NumberField(
            label = "字间距",
            value = text.letterSpacingSp,
            onValueChange = { onChange(text.copy(letterSpacingSp = it)) },
            range = -2f..8f,
            step = 0.5f,
            decimals = 1,
            unit = "sp",
        )
        NumberField(
            label = "行高",
            value = text.lineHeightSp,
            onValueChange = { onChange(text.copy(lineHeightSp = it)) },
            range = 10f..60f,
            step = 1f,
            unit = "sp",
        )
        SwitchField(
            label = "粗体",
            checked = text.bold,
            onCheckedChange = { onChange(text.copy(bold = it)) },
        )
        SwitchField(
            label = "斜体",
            checked = text.italic,
            onCheckedChange = { onChange(text.copy(italic = it)) },
        )
        ColorField(
            label = "文字颜色",
            argb = text.color,
            onArgbChange = { onChange(text.copy(color = it)) },
        )
        AlignPicker(
            label = "文字对齐",
            align = text.align,
            onAlignChange = { onChange(text.copy(align = it)) },
        )
        IntField(
            label = "最大行数",
            value = text.maxLines,
            onValueChange = { onChange(text.copy(maxLines = it)) },
            range = 0..10,
            unit = "行",
        )
        Text(
            text = "行数 = 0 表示不限制，超出按气泡高度裁剪。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SwitchField(
            label = "单行超长时横向滚动",
            checked = text.marquee,
            onCheckedChange = { onChange(text.copy(marquee = it)) },
            subtitle = "取件码等长串常用",
        )

        Text(
            text = "阴影",
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(top = 8.dp),
        )
        NumberField(
            label = "模糊半径",
            value = text.shadowRadius,
            onValueChange = { onChange(text.copy(shadowRadius = it)) },
            range = 0f..24f,
            step = 1f,
        )
        NumberField(
            label = "水平偏移",
            value = text.shadowDx,
            onValueChange = { onChange(text.copy(shadowDx = it)) },
            range = -12f..12f,
            step = 1f,
        )
        NumberField(
            label = "垂直偏移",
            value = text.shadowDy,
            onValueChange = { onChange(text.copy(shadowDy = it)) },
            range = -12f..12f,
            step = 1f,
        )
        ColorField(
            label = "阴影颜色",
            argb = text.shadowColor,
            onArgbChange = { onChange(text.copy(shadowColor = it)) },
        )

        Text(
            text = "描边",
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(top = 8.dp),
        )
        NumberField(
            label = "描边宽度",
            value = text.strokeWidth,
            onValueChange = { onChange(text.copy(strokeWidth = it)) },
            range = 0f..6f,
            step = 0.5f,
            decimals = 1,
        )
        ColorField(
            label = "描边颜色",
            argb = text.strokeColor,
            onArgbChange = { onChange(text.copy(strokeColor = it)) },
        )
    }
}

// ==================== 共用：气泡外框（含 V5 渐变编辑器） ====================

@Composable
private fun BoxSection(
    title: String,
    box: OverlayBoxStyle,
    onChange: (OverlayBoxStyle) -> Unit,
) {
    CollapsibleSection(
        title = title,
        titleColor = MaterialTheme.colorScheme.primary,
        summary = "背景 / 圆角 / 边框 / 内间距",
        defaultExpanded = false,
    ) {
        NumberField(
            label = "宽度",
            value = box.widthDp,
            onValueChange = { onChange(box.copy(widthDp = it)) },
            range = 0f..420f,
            step = 8f,
            unit = "dp",
        )
        NumberField(
            label = "高度",
            value = box.heightDp,
            onValueChange = { onChange(box.copy(heightDp = it)) },
            range = 0f..320f,
            step = 8f,
            unit = "dp",
        )
        Text(
            text = "宽度 / 高度 = 0 时按内容自适应。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        ColorField(
            label = "背景纯色",
            argb = box.bgSolidColor,
            onArgbChange = { onChange(box.copy(bgSolidColor = it)) },
        )
        // V5 需求⑤：渐变 = 类型（无/线性/径向）+ 多色标 + 线性角度/径向半径比例
        GradientField(
            label = "背景渐变",
            spec = box.bgGradient,
            onSpecChange = { onChange(box.copy(bgGradient = it)) },
            solidColor = box.bgSolidColor,
        )
        NumberField(
            label = "背景不透明度",
            value = box.bgAlpha,
            onValueChange = { onChange(box.copy(bgAlpha = it)) },
            range = 0f..1f,
            step = 0.05f,
            decimals = 2,
        )
        NumberField(
            label = "整体透明度",
            value = box.alpha,
            onValueChange = { onChange(box.copy(alpha = it)) },
            range = 0.1f..1f,
            step = 0.05f,
            decimals = 2,
        )
        // 角顺序：left-top → right-top → right-bottom → left-bottom
        // （Compose RoundedCornerShape 口径，与 OverlayBoxStyle.corners 一致）
        FourSidesField(
            label = "圆角（四角独立）",
            values = box.corners,
            onValueChange = { index, value ->
                onChange(
                    when (index) {
                        0 -> box.copy(cornerTopStart = value)
                        1 -> box.copy(cornerTopEnd = value)
                        2 -> box.copy(cornerBottomEnd = value)
                        else -> box.copy(cornerBottomStart = value)
                    },
                )
            },
            // 四角联动：**一次**写入四个角。旧路径走 repeat(4){ onValueChange(i, v) }，
            // 四次回调都基于同一个旧 box 复制，最后一次覆盖前三次 → 只有一角生效。
            onValuesChange = { next ->
                if (next.size >= 4) {
                    onChange(
                        box.copy(
                            cornerTopStart = next[0],
                            cornerTopEnd = next[1],
                            cornerBottomEnd = next[2],
                            cornerBottomStart = next[3],
                        ),
                    )
                }
            },
        )
        NumberField(
            label = "边框宽度",
            value = box.borderWidth,
            onValueChange = { onChange(box.copy(borderWidth = it)) },
            range = 0f..6f,
            step = 0.5f,
            decimals = 1,
            unit = "dp",
        )
        ColorField(
            label = "边框颜色",
            argb = box.borderColor,
            onArgbChange = { onChange(box.copy(borderColor = it)) },
        )
        // V6 需求④：边缘羽化（0=关闭；四边 alpha 渐变淡出，纯色/渐变背景都生效）
        NumberField(
            label = "边缘羽化",
            value = box.edgeFeatherDp,
            onValueChange = { onChange(box.copy(edgeFeatherDp = it)) },
            range = 0f..32f,
            step = 1f,
            unit = "dp",
        )
        // V8 需求：内边距按用户语言排成「上 / 下 / 左 / 右」（旧版是「左上右下」四角口径，
        // 与实际含义对不上）。注意 **只是调用点重排**：FloatStyle/OverlayBoxStyle.paddings
        // 内部顺序仍是 start/top/end/bottom（渲染层 + 旧 JSON 都依赖它，不能动）。
        FourSidesField(
            label = "内间距（四边独立）",
            values = listOf(box.padTop, box.padBottom, box.padStart, box.padEnd),
            sideLabels = listOf("上边距", "下边距", "左边距", "右边距"),
            onValueChange = { index, value ->
                onChange(
                    when (index) {
                        0 -> box.copy(padTop = value)
                        1 -> box.copy(padBottom = value)
                        2 -> box.copy(padStart = value)
                        else -> box.copy(padEnd = value)
                    },
                )
            },
            // 四边联动：一次写入四个值（同上，逐条回调会互相覆盖）
            onValuesChange = { next ->
                if (next.size >= 4) {
                    onChange(
                        box.copy(
                            padTop = next[0],
                            padBottom = next[1],
                            padStart = next[2],
                            padEnd = next[3],
                        ),
                    )
                }
            },
            range = 0f..32f,
        )
    }
}

