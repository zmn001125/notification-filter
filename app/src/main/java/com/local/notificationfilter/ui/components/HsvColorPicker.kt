package com.local.notificationfilter.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 无极选色面板（V5 需求⑤）：**色相条 + 饱和度/亮度二维调色区 + Alpha 透明度条 +
 * HEX 手输**，替代旧的预制色板。
 *
 * 为什么 Canvas 自绘：Compose Material3 没有现成 HSV 选色组件，而「无极选色」需要
 * 连续取色（色板离散点永远挑不准用户要的那个色）。三块全部 Canvas + 指针手势：
 * - **SV 调色区**（左白→右纯色、上彩→下黑的双向线性渐变叠加）；
 * - **色相条**（0..360° 七段彩虹渐变横条）；
 * - **Alpha 条**（棋盘格底 + 透明→当前色渐变）；
 * 每块带游标指示当前值，点按/拖动均可。
 *
 * 颜色仍以 **ARGB Long** 对外（与本 App core 层模型约定一致）；内部用 HSV + Alpha
 * 四个分量持有，互不串扰（拖 SV 区不动 alpha，拖 alpha 不动色相）。
 *
 * 约定（D1 教训）：本组件属于通用控件库，**不 import 任何业务包**；ARGB↔Color 的
 * 转换就地私有实现。
 *
 * 已知取舍（记录备查）：拖动产生的 HSVA 会经 8bit ARGB 量化后回调，色相若被量化
 * 损耗（如 359.9° → 358°）时游标可能与手指有 ≤1° 的轻微偏移，松手后消失；
 * 不做「外部回写同步」是为了避免拖动每帧重组时游标被量化值拉扯抖动。
 */
@Composable
fun HsvColorPicker(
    argb: Long,
    onArgbChange: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    // —— 内部状态：HSV 三分量 + Alpha，由 argb 初始化；仅内部拖动时主动 emit ——
    var hue by remember { mutableFloatStateOf(hueOf(argb)) }
    var saturation by remember { mutableFloatStateOf(satOf(argb)) }
    var value by remember { mutableFloatStateOf(valOf(argb)) }
    var alpha by remember { mutableFloatStateOf(alphaOf(argb)) }

    /** 由当前 HSVA 组装 ARGB 并回调（拖动游标时连续触发）。 */
    fun emit() {
        onArgbChange(hsvaToArgb(hue, saturation, value, alpha))
    }

    val currentColor = Color(hsvToArgbInt(hue, saturation, value)).copy(alpha = alpha)

    Column(modifier = modifier.fillMaxWidth()) {
        // —— 当前色预览 + HEX ——
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .border(
                        1.dp,
                        MaterialTheme.colorScheme.outlineVariant,
                        RoundedCornerShape(8.dp),
                    ),
            ) {
                // 棋盘格底 + 当前色（透出 alpha）+ 描边
                CheckerBoard(modifier = Modifier.matchParentSize())
                Box(Modifier.matchParentSize().background(currentColor))
            }
            Spacer(Modifier.width(10.dp))
            HexInputField(
                argb = argb,
                onArgbChange = { next ->
                    // 手输 HEX：回填 HSV 游标，让三块面板与输入值同步
                    hue = hueOf(next)
                    saturation = satOf(next)
                    value = valOf(next)
                    alpha = alphaOf(next)
                    onArgbChange(next)
                },
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(10.dp))

        // —— 饱和度/亮度二维调色区 ——
        SvPanel(
            hue = hue,
            saturation = saturation,
            value = value,
            onChange = { s, v ->
                saturation = s
                value = v
                emit()
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(160.dp),
        )
        Spacer(Modifier.height(10.dp))

        // —— 色相条 ——
        HueBar(
            hue = hue,
            onChange = { h ->
                hue = h
                emit()
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(20.dp),
        )
        Spacer(Modifier.height(10.dp))

        // —— Alpha 透明度条 ——
        AlphaBar(
            baseColor = Color(hsvToArgbInt(hue, saturation, value)),
            alpha = alpha,
            onChange = { a ->
                alpha = a
                emit()
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(20.dp),
        )
    }
}

// ==================== SV 二维调色区 ====================

/**
 * 饱和度/亮度二维调色区：底为当前色相纯色，叠两层渐变——横向 白→透明（S 轴）、
 * 纵向 透明→黑（V 轴）。游标位置 = (saturation, 1-value)。
 *
 * 手势（**V7-③ 重写：跟手拖动**）：旧实现 `detectTapGestures { }` 后接
 * `detectDragGestures { }` 是无效组合——`detectTapGestures` 内部是
 * `awaitEachGesture` 的无限循环，只有协程被取消才返回，因此拖动那一句**永不执行**。
 * 现在合并为一个自定义手势循环：按下即取值、按住持续跟随同一根手指、抬手退出。
 */
@Composable
private fun SvPanel(
    hue: Float,
    saturation: Float,
    value: Float,
    onChange: (Float, Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val pureHue = Color(hsvToArgbInt(hue, 1f, 1f))
    val shape = RoundedCornerShape(10.dp)
    Box(
        modifier = modifier
            .clip(shape)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
            .pointerInput(Unit) {
                // 手指坐标 → (S, V)：滑出面板边界时 x/y 可能为负或超出 size，
                // coerceIn 已兜住（钳到 0/1 继续可用），所以**不 break**。
                fun update(offset: Offset) {
                    if (size.width <= 0 || size.height <= 0) return
                    val s = (offset.x / size.width).coerceIn(0f, 1f)
                    val v = 1f - (offset.y / size.height).coerceIn(0f, 1f)
                    onChange(s, v)
                }
                awaitEachGesture {
                    // 按下即取值（跟手的关键：不设 touch slop、不等抬手）
                    val down = awaitFirstDown(requireUnconsumed = false)
                    update(down.position)
                    down.consume()
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) {
                            change.consume()
                            break
                        }
                        update(change.position)
                        change.consume()
                    }
                }
            },
    ) {
        Canvas(Modifier.matchParentSize()) {
            drawRect(pureHue)
            // S 轴：左 白（S=0）→ 右 透明（S=1 露出纯色）
            drawRect(
                Brush.horizontalGradient(listOf(Color.White, Color.Transparent)),
            )
            // V 轴：上 透明（V=1）→ 下 黑（V=0）
            drawRect(
                Brush.verticalGradient(listOf(Color.Transparent, Color.Black)),
            )
            // 游标：当前 (S, 1-V) 位置
            val cx = size.width * saturation
            val cy = size.height * (1f - value)
            drawCircle(
                color = Color.White,
                radius = SV_CURSOR_OUTER,
                center = Offset(cx, cy),
                style = Stroke(width = SV_CURSOR_RING),
            )
            drawCircle(
                color = Color.Black.copy(alpha = 0.35f),
                radius = SV_CURSOR_OUTER + 2f,
                center = Offset(cx, cy),
                style = Stroke(width = 1f),
            )
        }
    }
}

// ==================== 色相条 ====================

/**
 * 色相条：0..360° 彩虹渐变横条，游标为竖条指示。
 *
 * 手势（V7-③）：与 [SvPanel] 同一套「按下即取值 + 按住跟随」循环，
 * 替代此前无效的 `detectTapGestures` + `detectDragGestures` 串联。
 */
@Composable
private fun HueBar(
    hue: Float,
    onChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(10.dp)
    Box(
        modifier = modifier
            .clip(shape)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
            .pointerInput(Unit) {
                fun update(offset: Offset) {
                    if (size.width <= 0) return
                    onChange((offset.x / size.width).coerceIn(0f, 1f) * 360f)
                }
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    update(down.position)
                    down.consume()
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) {
                            change.consume()
                            break
                        }
                        update(change.position)
                        change.consume()
                    }
                }
            },
    ) {
        Canvas(Modifier.matchParentSize()) {
            drawRect(Brush.horizontalGradient(HUE_COLORS))
            val cx = size.width * (hue / 360f).coerceIn(0f, 1f)
            // 游标：白芯竖条 + 深色描边，任何底色上都看得清
            drawRoundRect(
                color = Color.Black.copy(alpha = 0.4f),
                topLeft = Offset(cx - BAR_CURSOR_HALF - 1f, 0f),
                size = Size((BAR_CURSOR_HALF + 1f) * 2f, size.height),
                cornerRadius = CornerRadius(3f),
            )
            drawRoundRect(
                color = Color.White,
                topLeft = Offset(cx - BAR_CURSOR_HALF, 0f),
                size = Size(BAR_CURSOR_HALF * 2f, size.height),
                cornerRadius = CornerRadius(2f),
            )
        }
    }
}

// ==================== Alpha 透明度条 ====================

/**
 * Alpha 条：棋盘格底 + 左透明→右当前色渐变。
 *
 * 手势（V7-③）：与 [SvPanel] 同一套「按下即取值 + 按住跟随」循环。
 */
@Composable
private fun AlphaBar(
    baseColor: Color,
    alpha: Float,
    onChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(10.dp)
    Box(
        modifier = modifier
            .clip(shape)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
            .pointerInput(Unit) {
                fun update(offset: Offset) {
                    if (size.width <= 0) return
                    onChange((offset.x / size.width).coerceIn(0f, 1f))
                }
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    update(down.position)
                    down.consume()
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) {
                            change.consume()
                            break
                        }
                        update(change.position)
                        change.consume()
                    }
                }
            },
    ) {
        CheckerBoard(Modifier.matchParentSize())
        Canvas(Modifier.matchParentSize()) {
            drawRect(
                Brush.horizontalGradient(
                    listOf(baseColor.copy(alpha = 0f), baseColor.copy(alpha = 1f)),
                ),
            )
            val cx = size.width * alpha.coerceIn(0f, 1f)
            drawRoundRect(
                color = Color.Black.copy(alpha = 0.4f),
                topLeft = Offset(cx - BAR_CURSOR_HALF - 1f, 0f),
                size = Size((BAR_CURSOR_HALF + 1f) * 2f, size.height),
                cornerRadius = CornerRadius(3f),
            )
            drawRoundRect(
                color = Color.White,
                topLeft = Offset(cx - BAR_CURSOR_HALF, 0f),
                size = Size(BAR_CURSOR_HALF * 2f, size.height),
                cornerRadius = CornerRadius(2f),
            )
        }
    }
}

/** 棋盘格（透明度指示的行业惯例画法）：浅灰/白交替小方格。 */
@Composable
private fun CheckerBoard(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val cell = CHECKER_CELL
        val cols = (size.width / cell).roundToInt() + 1
        val rows = (size.height / cell).roundToInt() + 1
        for (r in 0..rows) {
            for (c in 0..cols) {
                drawRect(
                    color = if ((r + c) % 2 == 0) CHECKER_LIGHT else CHECKER_DARK,
                    topLeft = Offset(c * cell, r * cell),
                    size = Size(cell, cell),
                )
            }
        }
    }
}

// ==================== HEX 手输 ====================

/**
 * HEX 手输：支持 `#RRGGBB` / `RRGGBB` / `#AARRGGBB` / `AARRGGBB`；失败不改值。
 *
 * **P1-8 修复**：文本状态与 [argb] 解耦，输入过程中**绝不**用 `argb` 回写文本。
 * 旧写法 `remember(argb) { mutableStateOf(formatHexArgb(argb)) }` + 「6 位即时提交」
 * 会把 8 位通道废掉：输 `#80FFFFFF` 逐字符到第 6 位 `80FFFF` 就命中 `length == 6`
 * → 立刻提交 → `argb` 变化 → `remember(argb)` 重建 `text` = `"#FF80FFFF"` → 光标跳位，
 * 后续 `FF` 接到错误位置（`#FF80FFFF` 的透明度被改成了 FF）。
 *
 * 现在：输入只改本地草稿；6 位仍即时提交（预览要实时反馈），8 位继续累积，
 * 第 8 位到达时按 8 位解析覆盖提交。外部改色（拖 SV / 色相条 / Alpha 条）与失焦时
 * 才把文本归一化回 [argb] 的 `#AARRGGBB` 形式。
 *
 * 双保险：除「聚焦中不同步」外，还比对 `lastEmitted`（我们最近一次回写出去的值）——
 * 即便某些机型/版本上焦点回调没按时到达（`focused` 一直是 false），`argb` 由手输
 * 自己造成的变化也不会触发文本回写，8 位通道仍然可用。
 */
@Composable
private fun HexInputField(
    argb: Long,
    onArgbChange: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    var text by remember { mutableStateOf(formatHexArgb(argb)) }
    var focused by remember { mutableStateOf(false) }
    var lastEmitted by remember { mutableStateOf(argb) }
    val invalid = remember(text) { parseHexArgb(text) == null }

    // 未聚焦时（外部拖动改色 / 刚进入编辑）把文本同步成当前值；
    // 聚焦期间**不动**文本，否则输入到一半会被外部值覆盖/光标跳位。
    LaunchedEffect(argb, focused) {
        if (!focused && argb != lastEmitted) {
            text = formatHexArgb(argb)
            lastEmitted = argb
        }
    }

    OutlinedTextField(
        value = text,
        onValueChange = { raw ->
            text = raw
            // 6 / 8 位合法即提交（预览实时反馈）；解析函数本身不改（已审计无缺陷）
            parseHexArgb(raw)?.let { next ->
                lastEmitted = next
                onArgbChange(next)
            }
        },
        label = { Text("HEX（#AARRGGBB）") },
        isError = invalid,
        supportingText = { if (invalid) Text("格式有误，应为 #RRGGBB 或 #AARRGGBB") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
        modifier = modifier.onFocusChanged { state ->
            // `isFocused` 只在本节点自身是焦点目标时为真，`hasFocus` 覆盖「子节点
            // （内部 BasicTextField）获得焦点」，取并集以免漏判。
            val nowFocused = state.isFocused || state.hasFocus
            if (focused && !nowFocused) {
                // 失焦：文本归一化成 #AARRGGBB（非法输入直接丢弃，argb 未被它改动过）
                text = formatHexArgb(argb)
                lastEmitted = argb
            }
            focused = nowFocused
        },
    )
}

// ==================== 纯函数：ARGB Long ↔ HSVA ====================

/** ARGB Long → Compose Color（本文件私有，不依赖业务包）。 */
private fun toColor(argb: Long): Color = Color(argb)

/** 取 Alpha 分量（0..1）。 */
private fun alphaOf(argb: Long): Float = toColor(argb).alpha

/** 取色相（0..360）。 */
private fun hueOf(argb: Long): Float {
    val c = toColor(argb)
    return rgbToHue(c.red, c.green, c.blue)
}

/** 取饱和度（0..1）。 */
private fun satOf(argb: Long): Float {
    val c = toColor(argb)
    val max = maxOf(c.red, c.green, c.blue)
    val min = minOf(c.red, c.green, c.blue)
    return if (max == 0f) 0f else (max - min) / max
}

/** 取明度（0..1）。 */
private fun valOf(argb: Long): Float {
    val c = toColor(argb)
    return maxOf(c.red, c.green, c.blue)
}

/** RGB(0..1) → 色相 0..360（灰度时回落 0°，色相在灰度下无意义）。 */
private fun rgbToHue(r: Float, g: Float, b: Float): Float {
    val max = maxOf(r, g, b)
    val min = minOf(r, g, b)
    val d = max - min
    if (d == 0f) return 0f
    val h = when (max) {
        r -> ((g - b) / d) % 6f
        g -> (b - r) / d + 2f
        else -> (r - g) / d + 4f
    } * 60f
    return if (h < 0f) h + 360f else h
}

/**
 * HSV → 不透明 ARGB Int（0xFFRRGGBB）。
 *
 * 手写而不调 android.graphics.Color.HSVToColor：本文件是通用 UI 组件，保持
 * android 依赖最小（Canvas 绘制除外）；且算法直白可控，灰度边界无歧义。
 */
private fun hsvToArgbInt(h: Float, s: Float, v: Float): Int {
    val hue = ((h % 360f) + 360f) % 360f
    val sat = s.coerceIn(0f, 1f)
    val value = v.coerceIn(0f, 1f)
    val c = value * sat
    val x = c * (1f - abs((hue / 60f) % 2f - 1f))
    val m = value - c
    val (r, g, b) = when ((hue / 60f).toInt()) {
        0 -> Triple(c, x, 0f)
        1 -> Triple(x, c, 0f)
        2 -> Triple(0f, c, x)
        3 -> Triple(0f, x, c)
        4 -> Triple(x, 0f, c)
        else -> Triple(c, 0f, x)
    }
    val ri = ((r + m) * 255f).roundToInt().coerceIn(0, 255)
    val gi = ((g + m) * 255f).roundToInt().coerceIn(0, 255)
    val bi = ((b + m) * 255f).roundToInt().coerceIn(0, 255)
    return (0xFF shl 24) or (ri shl 16) or (gi shl 8) or bi
}

/** HSVA → ARGB Long（0xAARRGGBB）。 */
private fun hsvaToArgb(h: Float, s: Float, v: Float, a: Float): Long {
    val rgb = hsvToArgbInt(h, s, v).toLong() and 0xFFFFFFL
    val alpha = (a.coerceIn(0f, 1f) * 255f).roundToInt().toLong() shl 24
    return alpha or rgb
}

/** 解析 HEX；失败返回 null。 */
private fun parseHexArgb(raw: String): Long? {
    val s = raw.trim().removePrefix("#")
    if (s.isEmpty()) return null
    if (s.any { it !in '0'..'9' && it !in 'a'..'f' && it !in 'A'..'F' }) return null
    return when (s.length) {
        6 -> runCatching { ("FF$s").toLong(16) }.getOrNull()
        8 -> runCatching { s.toLong(16) }.getOrNull()
        else -> null
    }
}

/** ARGB Long → `#AARRGGBB`。 */
private fun formatHexArgb(argb: Long): String =
    "#" + (argb and 0xFFFFFFFFL).toString(16).uppercase().padStart(8, '0')

// ==================== 常量 ====================

/** SV 区游标半径（px，Canvas 内绘制无需 dp 换算的精密尺寸）。 */
private const val SV_CURSOR_OUTER = 10f

/** SV 区游标白环宽度。 */
private const val SV_CURSOR_RING = 3f

/** 横条游标半宽（px）。 */
private const val BAR_CURSOR_HALF = 3f

/** 棋盘格单元边长（px）。 */
private const val CHECKER_CELL = 8f

/** 棋盘格亮色。 */
private val CHECKER_LIGHT = Color(0xFFFFFFFF)

/** 棋盘格暗色。 */
private val CHECKER_DARK = Color(0xFFC7C7C7)

/** 色相条七段彩虹（0/60/120/180/240/300/360°）。 */
private val HUE_COLORS: List<Color> = listOf(
    Color(0xFFFF0000), Color(0xFFFFFF00), Color(0xFF00FF00), Color(0xFF00FFFF),
    Color(0xFF0000FF), Color(0xFFFF00FF), Color(0xFFFF0000),
)
