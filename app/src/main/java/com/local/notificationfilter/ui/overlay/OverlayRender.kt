package com.local.notificationfilter.ui.overlay

import android.util.TypedValue
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.local.notificationfilter.core.model.DanmakuStackDirection
import com.local.notificationfilter.core.model.DanmakuStyle
import com.local.notificationfilter.core.model.FloatStyle
import com.local.notificationfilter.core.model.GradientStop
import com.local.notificationfilter.core.model.OverlayAlign
import com.local.notificationfilter.core.model.OverlayAnim
import com.local.notificationfilter.core.model.OverlayBoxStyle
import com.local.notificationfilter.core.model.OverlayGradientKind
import com.local.notificationfilter.core.model.OverlayPayload
import com.local.notificationfilter.core.model.OverlayTextStyle
import com.local.notificationfilter.ui.components.AppIconLoader
import com.local.notificationfilter.ui.components.LoadedAppIcon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * 屏幕显示类动作的 **Compose 渲染层**（V4 需求 7-②弹幕 / 7-④悬浮）。
 *
 * 职责边界：
 * - 只负责「把样式数据模型画出来」，**不碰 WindowManager / 生命周期**
 *   （那部分在 service/OverlayController）；
 * - 弹幕与悬浮**共用 [OverlayBubble]**（同一套样式模型、同一份渲染代码），差别只在
 *   外层容器（[DanmakuBubble] 负责滚动与回收，[FloatBubble] 负责定位与自动消失）；
 * - **同一份渲染函数也被配置页的内嵌预览条复用**（[OverlayPreviewBar]），保证所见即所得。
 *
 * 颜色约定：模型里的颜色是 ARGB Long（如 `0xFFFFFFFF`），统一由 [argbColor] 转 [Color]。
 * 悬浮层画在其它 App 之上，**不能用 MaterialTheme 的语义色**，只能用用户自己配的值。
 */

// ==================== 类型转换 ====================

/** ARGB Long（`0xAARRGGBB`）→ Compose Color。 */
fun argbColor(argb: Long): Color = Color(argb)

/** ARGB Long → Color，并按 [alpha] 系数（0-1）叠加。 */
private fun argbColor(argb: Long, alpha: Float): Color = Color(argb).copy(alpha = alpha)

/**
 * 背景画刷（V5 需求⑤）：纯色 / 线性渐变 / 径向渐变（多色标）。
 *
 * - [OverlayBoxStyle.bgGradient] 可渲染（非 NONE 且色标 ≥ 2）→ 渐变：色标按
 *   [GradientStop.position] 排序后传给 Compose 的 `colorStops` 参数
 *   （「位置 0..1 + 颜色」与模型语义天然一一对应）；
 * - **线性**：方向按 angleDeg（度，0 = 左→右），起止点用**盒子比例坐标**
 *   （0..1 的 Offset，Compose 按绘制区域自动缩放），无需知道实际像素尺寸；
 * - **径向**：中心固定为气泡中心。`Brush.radialGradient` 的像素 radius 在
 *   `Modifier.background` 里拿不到绘制尺寸，因此「半径比例」用**色标位置压缩**等价
 *   实现：用户色标位置整体乘以 radiusRatio —— 渐变在 radiusRatio 比例处就到达末色、
 *   之外平铺末色，视觉效果与「半径越小光斑越集中」完全一致，且与区域尺寸无关；
 * - 渐变失效（NONE / 色标 < 2）→ 纯色 [OverlayBoxStyle.bgFallbackColor]；
 * - [OverlayBoxStyle.bgAlpha] 统一叠加到每个颜色上（`copy(alpha =)`）。
 */
private fun backgroundBrush(box: OverlayBoxStyle): Brush {
    val spec = box.bgGradient
    if (!spec.isRenderable) return SolidColor(argbColor(box.bgFallbackColor, box.bgAlpha))

    // 径向：把色标位置按 radiusRatio 压缩（模拟「半径比例」），之外平铺末色
    val positionScale = if (spec.kindEnum == OverlayGradientKind.RADIAL) {
        spec.radiusRatio.coerceIn(0.1f, 1f)
    } else {
        1f
    }
    val sorted = spec.stops.sortedBy { it.position.coerceIn(0f, 1f) }
    val colorStops: Array<Pair<Float, Color>> = sorted
        .map { (it.position.coerceIn(0f, 1f) * positionScale) to argbColor(it.color, box.bgAlpha) }
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

        OverlayGradientKind.NONE -> SolidColor(argbColor(box.bgFallbackColor, box.bgAlpha))
    }
}

/** 九宫格 → Compose Alignment。 */
fun alignOf(align: OverlayAlign): Alignment = when (align) {
    OverlayAlign.TOP_START -> Alignment.TopStart
    OverlayAlign.TOP_CENTER -> Alignment.TopCenter
    OverlayAlign.TOP_END -> Alignment.TopEnd
    OverlayAlign.CENTER_START -> Alignment.CenterStart
    OverlayAlign.CENTER -> Alignment.Center
    OverlayAlign.CENTER_END -> Alignment.CenterEnd
    OverlayAlign.BOTTOM_START -> Alignment.BottomStart
    OverlayAlign.BOTTOM_CENTER -> Alignment.BottomCenter
    OverlayAlign.BOTTOM_END -> Alignment.BottomEnd
}

/** 九宫格 → 文字水平对齐（垂直方向由外层 Box 的 Alignment 决定）。 */
private fun textAlignOf(align: OverlayAlign): TextAlign = when (align) {
    OverlayAlign.TOP_START, OverlayAlign.CENTER_START, OverlayAlign.BOTTOM_START -> TextAlign.Start
    OverlayAlign.TOP_END, OverlayAlign.CENTER_END, OverlayAlign.BOTTOM_END -> TextAlign.End
    else -> TextAlign.Center
}

/** 圆角形状（四角独立）。 */
private fun cornerShapeOf(box: OverlayBoxStyle) = RoundedCornerShape(
    topStart = box.cornerTopStart.dp,
    topEnd = box.cornerTopEnd.dp,
    bottomEnd = box.cornerBottomEnd.dp,
    bottomStart = box.cornerBottomStart.dp,
)

/**
 * 边缘羽化遮罩（V6 需求④）：气泡四边各 [featherDp] 宽的 alpha 从 0 渐变到 1。
 *
 * 实现手法（Compose 里「按绘制结果做 alpha 遮罩」的标准做法）：
 * 1. `graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }` 把整棵
 *    子树先画进**离屏层**（否则 DstIn 会作用到下层 App 的像素上，把下面的内容也擦掉）；
 * 2. `drawWithContent { drawContent(); 四边渐变遮罩 }`：每条遮罩是 黑→透明 的线性渐变，
 *    用 `BlendMode.DstIn` 合成——Dst 保留遮罩 alpha 通道，于是边缘被柔和淡出。
 *
 * 对纯色背景同样生效（遮罩作用于整棵子树的最终像素，不关心背景是不是渐变）。
 * [featherDp] <= 0 时调用方直接不加这个修饰符（零开销）。
 */
private fun Modifier.featherMask(featherDp: Float): Modifier =
    graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithContent {
            drawContent()
            val f = featherDp.dp.toPx().coerceAtMost(minOf(size.width, size.height) / 2f)
            if (f <= 0f) return@drawWithContent
            // 四条边：黑（Dst 全保留）→ 透明（Dst 全擦除），方向指向边缘外
            drawRect(
                brush = Brush.verticalGradient(
                    listOf(Color.Transparent, Color.Black),
                    startY = 0f,
                    endY = f,
                ),
                topLeft = Offset.Zero,
                size = Size(size.width, f),
                blendMode = BlendMode.DstIn,
            )
            drawRect(
                brush = Brush.verticalGradient(
                    listOf(Color.Black, Color.Transparent),
                    startY = size.height - f,
                    endY = size.height,
                ),
                topLeft = Offset(0f, size.height - f),
                size = Size(size.width, f),
                blendMode = BlendMode.DstIn,
            )
            drawRect(
                brush = Brush.horizontalGradient(
                    listOf(Color.Transparent, Color.Black),
                    startX = 0f,
                    endX = f,
                ),
                topLeft = Offset.Zero,
                size = Size(f, size.height),
                blendMode = BlendMode.DstIn,
            )
            drawRect(
                brush = Brush.horizontalGradient(
                    listOf(Color.Black, Color.Transparent),
                    startX = size.width - f,
                    endX = size.width,
                ),
                topLeft = Offset(size.width - f, 0f),
                size = Size(f, size.height),
                blendMode = BlendMode.DstIn,
            )
        }

/** 文字样式（含阴影）。 */
private fun textStyleOf(style: OverlayTextStyle): TextStyle = TextStyle(
    fontSize = style.sizeSp.sp,
    letterSpacing = style.letterSpacingSp.sp,
    lineHeight = style.lineHeightSp.sp,
    fontWeight = if (style.bold) FontWeight.Bold else FontWeight.Normal,
    fontStyle = if (style.italic) FontStyle.Italic else FontStyle.Normal,
    color = argbColor(style.color),
    textAlign = textAlignOf(style.alignEnum),
    shadow = if (style.shadowRadius > 0f) {
        Shadow(
            color = argbColor(style.shadowColor),
            offset = Offset(style.shadowDx, style.shadowDy),
            blurRadius = style.shadowRadius,
        )
    } else {
        null
    },
)

/**
 * 文字：带可选描边。Compose 无原生 stroke 文本 API，故 [OverlayTextStyle.strokeWidth] > 0
 * 时用 `drawStyle = Stroke` 先画一层描边、再叠一层实心字。
 */
@Composable
private fun StyledText(
    content: String,
    style: OverlayTextStyle,
    modifier: Modifier = Modifier,
) {
    val base = textStyleOf(style)
    val maxLines = if (style.maxLines > 0) style.maxLines else Int.MAX_VALUE
    val overflow = if (style.maxLines > 0) TextOverflow.Ellipsis else TextOverflow.Clip

    if (style.strokeWidth > 0f) {
        Box(modifier = modifier) {
            Text(
                text = content,
                style = base.copy(
                    color = argbColor(style.strokeColor),
                    drawStyle = Stroke(width = style.strokeWidth),
                ),
                maxLines = maxLines,
                overflow = overflow,
            )
            Text(text = content, style = base, maxLines = maxLines, overflow = overflow)
        }
    } else {
        Text(
            text = content,
            style = base,
            maxLines = maxLines,
            overflow = overflow,
            modifier = modifier,
        )
    }
}

/**
 * 源 App 图标（取不到返回 null）。
 *
 * V7-②：改走 [AppIconLoader] 统一加载（三级兜底 + LRU 缓存 + 自适应/方图分流）。
 * - **不再在主线程解码**：图标解码与 Drawable→Bitmap 绘制在 [Dispatchers.IO] 完成，
 *   弹幕密集时不会因为每条气泡各取一次图标而掉帧；同包名同尺寸命中缓存直出。
 * - [sizePx] 由调用点按**展示尺寸**换算（旧实现固定 48px 位图却在 20dp 位置显示，
 *   高密度屏上被放大发虚）。
 * - 返回值是 [LoadedAppIcon]：**形状判断看 `circularReady`，不再看 `adaptive`**。
 *   加载器现在两条分支都输出「圆形底板」位图——自适应图标裁圆铺满，传统方图是
 *   「圆底盘 + 内部 0.86 缩放」，因此气泡侧统一裁圆即可；`circularReady == false`
 *   （将来若有原样输出的分支）才退回圆角方形。
 * - 失败兜底维持原语义：null → 不画图标（气泡内没有 appName 的展示位，不做首字母兜底）。
 */
@Composable
private fun rememberAppIcon(packageName: String, sizePx: Int): LoadedAppIcon? {
    val context = LocalContext.current
    val density = LocalDensity.current.density
    val icon by produceState<LoadedAppIcon?>(initialValue = null, packageName, sizePx) {
        value = if (packageName.isBlank() || sizePx <= 0) {
            null
        } else {
            withContext(Dispatchers.IO) { AppIconLoader.load(context, packageName, sizePx, density) }
        }
    }
    return icon
}

// ==================== 出现 / 消失动画（V8 需求：「不然很生硬」） ====================

/**
 * 一次动画在某个进度下的**可绘制状态**：透明度 + 位移 + 缩放。
 *
 * 只描述「画成什么样」，不含时长与驱动方式——时长按场景固定在调用点（入场 260ms /
 * 定位式入场 280ms / 退场 240ms），驱动统一用 `Animatable`。
 *
 * 为什么抽成数据类而不是三段散落的表达式：入场与退场必须**严格互逆**（退场的 e=0
 * 必须逐像素等于入场的 p=1），散着写迟早会有一处忘记同步，表现出来就是退场起手
 * 「闪一下」再消失。
 */
private data class OverlayAnimVisual(
    val alpha: Float = 1f,
    val offsetXDp: Float = 0f,
    val offsetYDp: Float = 0f,
    val scale: Float = 1f,
) {
    companion object {
        /** 完全静止（无动画 / 动画终态）：不透明、无位移、不缩放。 */
        val STILL: OverlayAnimVisual = OverlayAnimVisual()
    }
}

/** 水平滑入 / 滑出的位移距离（dp）：沿用 V4 定稿的 120dp，**入场观感不得改变**。 */
private const val SLIDE_X_DP = 120f

/** 垂直滑入 / 滑出的位移距离（dp）：沿用 V4 定稿的 80dp，**入场观感不得改变**。 */
private const val SLIDE_Y_DP = 80f

/**
 * 某个动画的**起点位移**（dp）：入场时从该位移衰减到 0；退场时从 0 涨回该位移。
 *
 * 方向语义（V8 关键约定）：
 * - [OverlayAnim.SLIDE_LEFT] 的标签是「从右滑入」→ 起点在**右侧**（+120dp）；
 *   于是退场沿同一轴走回右侧（+120dp）——入场向左运动、退场向右运动，
 *   **运动方向相反**（需求原话），同时不会横穿屏幕跑去另一侧；
 * - [OverlayAnim.SLIDE_TOP] 的标签是「从上滑入」→ 起点在**上方**（−80dp），
 *   退场即向上走回顶部（入场向下、退场向上，同样是运动方向相反）；
 * - 其余动画（NONE / FADE / SCALE / BOUNCE）无位移。
 *
 * **退场方向不是「把入场方向取负」**：入场与退场各自只按**自己那个枚举值**的几何取值。
 * 所以「入场 SLIDE_TOP + 退场 SLIDE_LEFT」= 「从上进来、往右出去」，是两个各自
 * 语义化的方向；若改成取负，会得到一个既不是 SLIDE_TOP 也不是 SLIDE_LEFT 的
 * 第三条方向（从上进来、往下出去，看起来像"掉下去"），完全无法向用户解释。
 */
private fun startOffsetOf(anim: OverlayAnim): Pair<Float, Float> = when (anim) {
    OverlayAnim.SLIDE_LEFT -> SLIDE_X_DP to 0f
    OverlayAnim.SLIDE_TOP -> 0f to -SLIDE_Y_DP
    // V9：对称的另外两个方向。SLIDE_LEFT/TOP 是「起点在右/上」，这两个起点在左/下。
    OverlayAnim.SLIDE_RIGHT -> -SLIDE_X_DP to 0f
    OverlayAnim.SLIDE_BOTTOM -> 0f to SLIDE_Y_DP
    else -> 0f to 0f
}

/** 该动画是否带缩放（SCALE / BOUNCE：从 0 弹到 1）。 */
private fun isScaleAnim(anim: OverlayAnim): Boolean =
    anim == OverlayAnim.SCALE || anim == OverlayAnim.BOUNCE

/**
 * 入场视觉状态（[progress] 0 → 1，1 = 终态）。
 *
 * 与 V4 / V6 定稿的观感**逐像素等价**：
 * - NONE → 完全静止（alpha 1，无位移，不缩放）；
 * - FADE → alpha 跟随 p；
 * - SLIDE_LEFT / SLIDE_TOP → 位移按 `(1 - p)` 从起点衰减到 0，alpha 跟随 p；
 * - SCALE / BOUNCE → 缩放取**未钳制到 1** 的原始 progress（BOUNCE 的 spring 过冲
 *   > 1 才会真实表现为「先略微放大再回落」），只按 [MAX_POP_SCALE] 设上限防溢出屏幕。
 *
 * **本函数是入场动画的唯一实现**，改它等于改全部入场观感（含 V6 的条件卡片），
 * 因此除了「新增退场」之外不得再动这里的算式。
 */
private fun entranceVisual(anim: OverlayAnim, progress: Float): OverlayAnimVisual {
    if (anim == OverlayAnim.NONE) return OverlayAnimVisual.STILL
    val p = progress.coerceIn(0f, 1f)
    val (startX, startY) = startOffsetOf(anim)
    return OverlayAnimVisual(
        alpha = p,
        offsetXDp = startX * (1f - p),
        offsetYDp = startY * (1f - p),
        scale = if (isScaleAnim(anim)) progress.coerceIn(0f, MAX_POP_SCALE) else 1f,
    )
}

/**
 * 退场视觉状态（[progress] 0 → 1，1 = 完全消失）。
 *
 * 与 [entranceVisual] **严格互逆**：e = 0 时就是入场的终态（alpha 1、位移 0、缩放 1），
 * e = 1 时 alpha 0、位移回到该动画自己的起点方向、SCALE / BOUNCE 缩小到 0。
 * 因此独立退场动画起手不会「跳一下」。
 *
 * 只处理「独立退场动画」；[OverlayAnim.NONE] 表示**没有退场动画（硬切）**，
 * 调用方据此不启动退场阶段，不会走到这里（这里返回静止只是兜底）。
 */
private fun exitVisual(anim: OverlayAnim, progress: Float): OverlayAnimVisual {
    if (anim == OverlayAnim.NONE) return OverlayAnimVisual.STILL
    val e = progress.coerceIn(0f, 1f)
    val (startX, startY) = startOffsetOf(anim)
    val fade = (1f - e).coerceIn(0f, 1f)
    return OverlayAnimVisual(
        alpha = fade,
        offsetXDp = startX * e,
        offsetYDp = startY * e,
        scale = if (isScaleAnim(anim)) fade.coerceIn(0f, MAX_POP_SCALE) else 1f,
    )
}

/**
 * 退场动画时长（ms）。
 *
 * 240ms：比入场（260ms）略快——「消失」在视觉上应该比「出现」干脆一点，
 * 否则用户会盯着一个已经不想看的气泡等它慢慢走完。
 *
 * **必须小于 `OverlayController.EXIT_TIMEOUT_MS`（超时兜底 300ms）**，
 * 否则每次退场都会被超时强制摘除、最后一帧被掐掉。
 */
private const val EXIT_ANIM_MS = 240

/**
 * 退场动画的 [AnimationSpec]：BOUNCE 用弹簧（收缩带一点回弹），其余走 240ms tween。
 *
 * 为什么退场也要区分：用户选了「回弹弹出」，如果退场一律线性收缩，这一项的
 * 个性就只体现在入场、退场又变回死板——需求要的正是「出现和消失都能配」。
 */
private fun exitSpecOf(anim: OverlayAnim, durationMs: Int = EXIT_ANIM_MS): AnimationSpec<Float> =
    if (anim == OverlayAnim.BOUNCE) {
        spring(dampingRatio = Spring.DampingRatioMediumBouncy)
    } else {
        tween(durationMillis = durationMs.coerceIn(100, 1000))
    }

/**
 * 退场请求信号（V8）：`OverlayController` → 渲染层的**单向**通知。
 *
 * 为什么需要它：控制器原来的 `dismissFloat()` 是「立刻 removeView」，窗口一摘，
 * 气泡里的退场动画根本没有播放的帧——用户看到的永远是「啪一下消失」。
 * 现在控制器的撤下路径改成：
 * 1. `signal.request()`（本类）；
 * 2. 渲染层用 [awaitExitRequest] 感知到 → 播退场动画（[EXIT_ANIM_MS]）；
 * 3. 播完回调 `onFinished()` → 控制器才 `removeView`；
 * 4. 若 `OverlayController.EXIT_TIMEOUT_MS`（300ms）内没等到 `onFinished()`，
 *    控制器**超时强制** removeView——窗口绝不会因为动画没回调而永久留在屏幕上。
 *
 * 用 `MutableState` 而不是 Flow：渲染层在 Compose 协程里用 `snapshotFlow` 读即可，
 * 不需要额外的收集器生命周期，窗口销毁时也不会泄漏（随 composition 一起丢弃）。
 */
class OverlayExitSignal {

    private val state = mutableStateOf(false)

    /** 渲染层读取：是否已被请求退场。 */
    val requested: Boolean get() = state.value

    /**
     * 控制器调用：请求退场。
     *
     * 幂等由调用方（`OverlayWindow.markExitRequested()`）保证——重复 request 不会
     * 触发第二次退场回调，因此这里直接置 true 即可。
     */
    fun request() {
        state.value = true
    }
}

/**
 * 挂起，直到 [signal] 收到退场请求。
 *
 * `snapshotFlow` 而不是轮询：无请求时**零唤醒**（不占用主线程消息、不耗电），
 * 请求到达时被快照系统直接唤醒一次。
 */
private suspend fun awaitExitRequest(signal: OverlayExitSignal) {
    snapshotFlow { signal.requested }.first { it }
}

/**
 * 贯穿式弹幕边缘淡入 / 淡出的**已行进比例**区间（V8）。
 *
 * 0.15 = 进出各占总路程的 15%：与速度解耦（快弹幕淡得快、慢弹幕淡得慢，观感一致），
 * 而固定毫秒（例如 150ms）在速度调到 1200px/s 时只覆盖十几像素，等于没有过渡。
 */
private const val EDGE_FADE_FRACTION = 0.15f

/**
 * 贯穿式弹幕的边缘淡入淡出系数（0 = 完全透明，1 = 完全显示）。
 *
 * [traveledPx]：已行进距离（0 = 气泡刚触到右缘，[totalPx] = 完全离开左缘）。
 * [totalPx]：总路程 = 屏宽 + 气泡实宽（与滚动动画同口径）。
 *
 * 前 [EDGE_FADE_FRACTION] 段淡入、后同样一段淡出，中间满显；取两者较小值，
 * 于是极短气泡（总路程也短）不会出现「还没淡入完就开始淡出」的死区——
 * 那种情况会整体压暗，而不是闪烁。
 */
private fun edgeFadeAlphaOf(traveledPx: Float, totalPx: Float): Float {
    val total = totalPx.coerceAtLeast(1f)
    val fadePx = (total * EDGE_FADE_FRACTION).coerceAtLeast(1f)
    val fadeIn = (traveledPx / fadePx).coerceIn(0f, 1f)
    val fadeOut = ((total - traveledPx) / fadePx).coerceIn(0f, 1f)
    return minOf(fadeIn, fadeOut)
}

// ==================== 气泡（弹幕 / 悬浮 / 预览 共用） ====================

/** 按 [separator] 拼出要显示的文案（标题可关）。 */
fun overlayContentOf(payload: OverlayPayload, showTitle: Boolean, separator: String): String =
    if (showTitle && payload.title.isNotBlank()) {
        "${payload.title}$separator${payload.text}"
    } else {
        payload.text
    }

/**
 * 一条气泡：外框（背景 / 圆角 / 边框 / 内间距 / 最大宽度 / 透明度 / 边缘羽化）
 * + 内容（图标 + 文字）。
 *
 * 弹幕、悬浮、配置页内嵌预览**共用此函数**，确保调参与实机效果完全一致。
 *
 * [onMeasuredHeightDp]（V6 需求③）：气泡**实测高度**（dp）回报给控制器——堆叠/轨道
 * 排布按实测高度累计，不再用估算常量（「轨道间距调 0 还是离很远」的根因就是估算
 * 高度与真实内容高度不符）。回调幂等（值没变不重复回调），调用方自行过滤。
 *
 * [onMeasuredWidthPx]（P1-3）：气泡**实测宽度**（**像素**，直接给 `onSizeChanged` 的
 * 原始值）。贯穿式弹幕的滚动是像素口径的匀速位移，必须知道气泡自身占多宽才能算出
 * 「滚入 → 完全滚出」的真实路程与时长（见 [DanmakuBubble]）。默认 null = 不回报，
 * 不影响既有调用点。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun OverlayBubble(
    payload: OverlayPayload,
    text: OverlayTextStyle,
    box: OverlayBoxStyle,
    modifier: Modifier = Modifier,
    showIcon: Boolean = true,
    showTitle: Boolean = true,
    separator: String = " · ",
    content: String = overlayContentOf(payload, showTitle, separator),
    onMeasuredHeightDp: ((Float) -> Unit)? = null,
    onMeasuredWidthPx: ((Float) -> Unit)? = null,
    /**
     * V7 需求 8：内容区最大高度（dp，0 = 不限）。
     *
     * 只约束**文字块**高度，不约束气泡本身——背景 / 圆角 / 内间距保持原样，
     * 于是「限高 + 内部滚动」看起来仍然是一张完整的卡片，而不是被裁掉一半的方块。
     * 默认 0 = 不限，V6 全部调用点行为不变。
     */
    contentMaxHeightDp: Float = 0f,
    /**
     * V7 需求 8：内容区是否可上下滚动（仅 [contentMaxHeightDp] > 0 时生效）。
     *
     * 需要窗口可触摸才能真正用上（`OverlayController` 只在条件卡片上放开触摸）。
     */
    contentScrollable: Boolean = false,
) {
    val shape = cornerShapeOf(box)
    // V7 需求 8：滚动状态必须在这里 remember（随气泡生命周期存活，重组不丢滚动位置）
    val scrollState = rememberScrollState()
    val scrollable = contentMaxHeightDp > 0f && contentScrollable
    // 顺序：先限高、再滚动。verticalScroll 拿到的是「已被限定的最大高度」，
    // 于是内容超出时出现滚动条而非把卡片撑高。
    val contentModifier = Modifier
        .then(if (contentMaxHeightDp > 0f) Modifier.heightIn(max = contentMaxHeightDp.dp) else Modifier)
        .then(if (scrollable) Modifier.verticalScroll(scrollState) else Modifier)
    // V7-②：图标按**实际展示尺寸**（20dp）在该密度下的像素值渲染，避免旧实现固定 48px
    // 被 Image 放大后发虚；尺寸语义不变（仍由本处的 20dp 决定，不是用户可调样式参数）。
    val iconDp = 20.dp
    val iconPx = with(LocalDensity.current) { iconDp.roundToPx() }
    val icon = if (showIcon) rememberAppIcon(payload.packageName, iconPx) else null
    val density = LocalDensity.current
    var lastReportedDp = remember { -1f }
    var lastReportedWidthPx = remember { -1f }
    val needsSizeReport = onMeasuredHeightDp != null || onMeasuredWidthPx != null

    Box(
        modifier = modifier
            .alpha(box.alpha)
            // V6 需求③ / P1-3：实测尺寸回报（onSizeChanged 每次布局尺寸变化都触发；
            // 用 lastReported* 幂等过滤，避免回调风暴）
            .then(
                if (needsSizeReport) {
                    Modifier.onSizeChanged { size ->
                        if (onMeasuredHeightDp != null) {
                            val dp = with(density) { size.height.toDp().value }
                            if (dp != lastReportedDp) {
                                lastReportedDp = dp
                                onMeasuredHeightDp(dp)
                            }
                        }
                        if (onMeasuredWidthPx != null) {
                            val widthPx = size.width.toFloat()
                            if (widthPx != lastReportedWidthPx) {
                                lastReportedWidthPx = widthPx
                                onMeasuredWidthPx(widthPx)
                            }
                        }
                    }
                } else {
                    Modifier
                },
            )
            // D2 修复：widthDp / heightDp 原来是「上限」且 heightDp 完全没被读取，
            // 用户调「区域宽高」零效果。现改为**固定尺寸**（0 = 自适应），符合
            // 「自定义位置区域宽高」的语义。
            .then(if (box.widthDp > 0f) Modifier.width(box.widthDp.dp) else Modifier)
            .then(if (box.heightDp > 0f) Modifier.height(box.heightDp.dp) else Modifier)
            // V6 需求④：边缘羽化（四边 alpha 渐变淡出，纯色/渐变背景都生效）。
            // 必须在 clip/background **之后**加：遮罩作用的是整棵子树的最终像素。
            .then(if (box.edgeFeatherDp > 0f) Modifier.featherMask(box.edgeFeatherDp) else Modifier)
            .clip(shape)
            .then(
                if (box.borderWidth > 0f) {
                    Modifier.border(box.borderWidth.dp, argbColor(box.borderColor), shape)
                } else {
                    Modifier
                },
            )
            .background(backgroundBrush(box))
            .padding(
                start = box.padStart.dp,
                top = box.padTop.dp,
                end = box.padEnd.dp,
                bottom = box.padBottom.dp,
            ),
        contentAlignment = alignOf(text.alignEnum),
    ) {
        Row(modifier = contentModifier, verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                // V9：图标原图直显——loader 不再做圆形/底盘处理（circularReady 恒 false），
                // 这里也不加任何形状裁剪，等比填充固定尺寸即可（用户要求「不要底色、固定大小」）。
                Image(
                    bitmap = icon.bitmap,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.size(iconDp),
                )
                Spacer(Modifier.size(6.dp))
            }
            // V10.7 耗电修复：`basicMarquee()` 默认 iterations = Int.MAX_VALUE，
            // 文字一溢出就是**永久无限动画**——只要气泡挂在屏幕上，就 60/120fps 不停重绘，
            // 常驻悬浮时等于让显示管线全天不休息（实测耗电主因之一）。
            // 改为**有限次**：跑 [MARQUEE_ITERATIONS] 遍后停住，信息仍能看到，功耗有上界。
            val textModifier = if (text.marquee) {
                Modifier.basicMarquee(iterations = MARQUEE_ITERATIONS)
            } else {
                Modifier
            }
            StyledText(content = content, style = text, modifier = textModifier)
        }
        // V7 需求 8：溢出提示——避免「看起来只有 3 条其实有 8 条」。
        // 只在「限高 + 可滚动 + 还能往下滚」时出现；滚到底后自动消失。
        if (scrollable && scrollState.canScrollForward) {
            Text(
                text = SCROLL_HINT_TEXT,
                style = MaterialTheme.typography.labelSmall,
                color = argbColor(text.color, SCROLL_HINT_ALPHA),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(top = 2.dp),
            )
        }
    }
}

// ==================== 弹幕 · 贯穿式（THROUGH，默认形态） ====================

/**
 * 贯穿式弹幕（V5 需求③ 形态A）：从屏幕右缘滚入、**不停留**、直接滚出左缘，
 * 完全滚出后回调 [onFinished]（控制器据此回收 Window 并放行下一条排队）。
 *
 * 滚动速度 [DanmakuStyle.speedPxps] 单位为 **px/秒**：滚屏时长 =
 * (屏宽 + **气泡实宽**) / 速度。
 *
 * P1-3 修复：从前把 `BoxWithConstraints` 的 `maxWidth`（= 屏宽，因为外层是
 * `fillMaxWidth`）当成「气泡宽」用，导致三个后果：①滚屏时长恒为 2×屏宽/速度，
 * 与内容长度无关（速度调节失真、长通知滚得比短通知快的情况被完全抹平）；
 * ②气泡在 `offset = -真实宽` 时就已出屏，之后继续**空跑** (屏宽 − 真实宽)/速度 秒
 * 才回调 [onFinished]（1080px 屏 + 300px 气泡 + 140px/s ≈ 白跑 5.6 秒）——
 * 轨道迟迟不归还、`drainQueue()` 不执行，同屏满时下一条要多等这段时间。
 * 现在改为用 [OverlayBubble] 的 `onSizeChanged` 实测宽度（px）驱动动画：
 * 起点 = 屏宽（右缘之外）、终点 = `-气泡宽`（完全滚出左缘），路程/时长都是真实值。
 *
 * 首帧兜底：`onSizeChanged` 在同一帧的布局阶段才回调，动画可能先启动。故先
 * `withTimeoutOrNull(120ms)` 等首次测量；**超时/仍为 0 就退化为旧的「按可用宽度估」
 * 行为**（宁可时长不准，也绝不能让弹幕卡住不动）。
 *
 * [replayKey]（仅配置页预览用）：变化时从头重播一遍滚动动画（用户强调「动画参数
 * 一变，预览自动重播」）。实机弹幕固定传 0，等价于旧的 LaunchedEffect(payload)。
 *
 * [previewMode]（V6 需求①）：true 时滚出左缘后**回到左缘静止停住**（不回调
 * [onFinished]）——预览永远要有可见内容，播完就消失的预览等于没有预览。
 * 再次重播由 [replayKey] 驱动（snap 回右缘重新滚一遍）。
 *
 * **V8 需求③（边缘淡入淡出）**：[DanmakuStyle.edgeFade] = true 时，气泡在**右缘刚
 * 进入**时 alpha 0 → 1（按已行进路程的前 [EDGE_FADE_FRACTION] 段），**左缘即将离开**
 * 时 alpha 1 → 0（后同样一段），中间满显；false 时保持恒定 alpha（旧观感）。
 * 该效果**只改渲染 alpha**，滚动时长 / 路程 / 实测宽度逻辑一行未动。
 */
@Composable
fun DanmakuBubble(
    payload: OverlayPayload,
    style: DanmakuStyle,
    onFinished: () -> Unit,
    modifier: Modifier = Modifier,
    replayKey: Int = 0,
    previewMode: Boolean = false,
    onMeasuredHeightDp: ((Float) -> Unit)? = null,
    /**
     * V11.8：滚动**真正开始**时回调一次（参数 = 气泡实测宽度 px、本次滚动时长 ms）。
     *
     * 控制器据此算出「这条轨道什么时候可以再塞一条弹幕」——
     * 屏幕右侧留白被让出来之后，新弹幕就能立刻进场，不必等前一条整条滚完。
     */
    onScrollStarted: ((bubbleWidthPx: Float, scrollMs: Long) -> Unit)? = null,
) {
    val screenWidthDp: Float = LocalConfiguration.current.screenWidthDp.toFloat()
    val density = LocalDensity.current
    val offsetXPx = remember { Animatable(0f) }
    // P1-3：气泡实测宽度（px），由 OverlayBubble 的 onSizeChanged 回传；0 = 尚未测到
    var bubbleWidthPx by remember { mutableStateOf(0f) }

    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val maxWidthDp = maxWidth.value
        LaunchedEffect(payload, replayKey) {
            // P1-3：等首次测量（最多 120ms）；已测到则 snapshotFlow 会立即返回
            if (bubbleWidthPx <= 0f) {
                withTimeoutOrNull(BUBBLE_WIDTH_WAIT_MS) {
                    snapshotFlow { bubbleWidthPx }.first { it > 0f }
                }
            }
            // 屏宽/气泡宽统一换算 px，与 speedPxps 同口径
            val screenPx = with(density) { screenWidthDp.dp.toPx() }
            // 实测宽度优先；仍拿不到（超时兜底）退回旧行为：用可用宽度当气泡宽
            val bubblePx = bubbleWidthPx
                .takeIf { it > 0f }
                ?: with(density) { maxWidthDp.dp.toPx() }
            val speed = style.speedPxps.coerceAtLeast(1f)
            // 滚屏时长 = 总路程（屏宽 + 气泡实宽）/ 速度；钳制避免极端数值卡死/闪现
            val scrollMs = ((screenPx + bubblePx) / speed * 1000f)
                .toLong().coerceIn(300L, 30000L)
            offsetXPx.snapTo(screenPx) // 右缘之外起滚
            // V11.8：动画即将开始，把「宽度 + 时长」交给控制器算轨道复用时刻
            onScrollStarted?.invoke(bubblePx, scrollMs)
            offsetXPx.animateTo(
                -bubblePx, // 完全滚出左缘才结束（不停留）
                tween(durationMillis = scrollMs.toInt()),
            )
            if (previewMode) {
                // V6 需求①：预览播完后回到左缘**保持可见**（等下一次 replayKey 重播），
                // 不回调 onFinished——预览条没有「回收窗口」语义。
                offsetXPx.snapTo(0f)
            } else {
                onFinished()
            }
        }

        // V8 需求③：边缘淡入淡出（仅 style.edgeFade = true 时生效）。
        //
        // **只影响渲染 alpha**：滚动时长 / 路程 / 实测宽度那一套（P1-3 的敏感区）
        // 一行都没动——总路程与速度仍完全由上面的 LaunchedEffect 决定。
        // alpha 在 graphicsLayer 的绘制阶段计算（读 offsetXPx.value 只失效本图层，
        // **不触发重组**），所以高速滚动时不会因为每帧重组而掉帧。
        val edgeFadeModifier: Modifier = if (style.edgeFade) {
            Modifier.graphicsLayer {
                // 与滚动动画同口径的像素换算：屏宽 + 气泡实宽 = 总路程
                val screenPx = screenWidthDp.dp.toPx()
                val bubblePx = bubbleWidthPx.takeIf { it > 0f }
                    ?: maxWidthDp.dp.toPx() // 尚未测到宽度时与滚动动画用同一个兜底
                val totalPx = screenPx + bubblePx
                val traveledPx = screenPx - offsetXPx.value
                alpha = edgeFadeAlphaOf(traveledPx, totalPx)
            }
        } else {
            Modifier
        }

        OverlayBubble(
            payload = payload,
            text = style.text,
            box = style.box,
            showIcon = style.showIcon,
            showTitle = style.showTitle,
            separator = style.separator,
            onMeasuredHeightDp = onMeasuredHeightDp,
            // P1-3：宽度回传（px 原值），驱动上面的真实路程/时长
            onMeasuredWidthPx = { w -> bubbleWidthPx = w },
            modifier = Modifier
                .align(Alignment.CenterStart)
                .offset { IntOffset(offsetXPx.value.roundToInt(), 0) }
                .padding(start = 8.dp)
                // V8：边缘淡入淡出（edgeFade=false 时不加任何修饰符，零开销）
                .then(edgeFadeModifier),
        )
    }
}

// ==================== 弹幕 · 定位式（POSITIONED） ====================

/**
 * 定位式弹幕（V5 需求③ 形态B）：**淡入出现** → 停留 [DanmakuStyle.displaySeconds]
 * 秒 → 淡出消失 → 回调 [onFinished]（控制器补位下一条排队弹幕）。
 *
 * 落位由控制器用 WindowManager gravity + x/y 完成（九宫格 [DanmakuStyle.stackAlign] +
 * [DanmakuStyle.offsetXDp]/[DanmakuStyle.offsetYDp] + 堆叠偏移），本组件只管内容
 * 与淡入淡出——渲染层的定位逻辑为零，避免与 FloatBubble 的「内边距推位」产生两套口径。
 *
 * [previewMode]（V6 需求①）：true 时**停在停留态不淡出、不回调 [onFinished]**——
 * 预览播完就停在「淡入完成、内容满显」的可见状态，等下一次 [replayKey] 重播。
 *
 * **V8 需求②（出现 / 消失动画可配）**：
 * - 出现按 [DanmakuStyle.animIn]：NONE = 直接出现（无动画）；FADE = 280ms 淡入；
 *   SLIDE_LEFT / SLIDE_TOP 带位移、SCALE / BOUNCE 带缩放（BOUNCE 用弹簧）；时长统一 280ms。
 *   **`animIn` 的模型默认值是 FADE** —— 老规则 decode 后取它，仍是升级前的淡入观感；
 * - 消失按 [DanmakuStyle.animOut]：**NONE = 真的没有动画（硬切，立即消失）**；
 *   其余按 [EXIT_ANIM_MS]（240ms）播放该动画自己的位移 / 缩放 / 淡出；
 * - 停留时长、逐条错峰 `staggerMs`、[previewMode] 的「不淡出 / 不回调」语义**完全不变**。
 */
@Composable
fun PositionedDanmakuBubble(
    payload: OverlayPayload,
    style: DanmakuStyle,
    onFinished: () -> Unit,
    modifier: Modifier = Modifier,
    replayKey: Int = 0,
    /** 淡入前的错峰延迟（ms）：定位式「多条逐条依次出现」，第 i 条 = i × 150ms。 */
    staggerMs: Int = 0,
    previewMode: Boolean = false,
    onMeasuredHeightDp: ((Float) -> Unit)? = null,
) {
    val progress = remember { Animatable(0f) }
    /** V8：独立退场动画的进度（0 = 满显，1 = 完全消失），仅 animOut != NONE 时使用。 */
    val exitProgress = remember { Animatable(0f) }
    /** V8：是否已进入退场阶段（决定用入场视觉还是退场视觉）。 */
    var exiting by remember { mutableStateOf(false) }

    LaunchedEffect(payload, replayKey) {
        // 重播（replayKey 变化）时退场状态必须归零，否则预览会停在「已消失」的终态
        exiting = false
        exitProgress.snapTo(0f)
        progress.snapTo(0f)
        if (staggerMs > 0) delay(staggerMs.toLong()) // 逐条依次出现（语义不变）
        // —— 出现（V8 需求②：按 animIn；错峰与停留语义一字未改）——
        when (style.animIn) {
            // NONE = 真的「直接出现」（无动画），与 FloatStyle.anim 的 NONE 语义对齐。
            //
            // ⚠️ 本分支的正确性**硬依赖**一个前提：`DanmakuStyle.animIn` 的模型默认值
            // 是 `FADE`（不是 NONE）。老规则 JSON 里没有 animIn 字段 → decode 取默认值
            // → 仍是 280ms 淡入，升级后观感不变（「默认值等于旧观感」）。
            // 若模型默认值被回退成 NONE，老规则的定位式弹幕会变成**硬闪出现**（正是本次
            // 要消除的「生硬」）——那时必须把这一行改成 fall-through 到 FADE 的淡入分支。
            OverlayAnim.NONE -> progress.snapTo(1f)

            OverlayAnim.FADE ->
                progress.animateTo(1f, tween(durationMillis = style.animInMs.coerceIn(100, 1000)))

            OverlayAnim.BOUNCE ->
                progress.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy))

            // SLIDE_LEFT / SLIDE_TOP / SCALE：位移或缩放 + 同步淡入，时长沿用淡入的 280ms
            else -> progress.animateTo(1f, tween(durationMillis = style.animInMs.coerceIn(100, 1000)))
        }
        delay((style.displaySeconds * 1000f).toLong().coerceAtLeast(300L)) // 停留（语义不变）
        if (previewMode) {
            // V6 需求①：预览停在停留态（满显可见），不淡出、不回调；
            // 静态参数改动靠重组实时反映，动画参数改动由 replayKey 重新触发本 Effect
            return@LaunchedEffect
        }
        // —— 消失（V8 需求②：按 animOut）——
        //
        // V9.3 语义纠偏：`animOut == NONE` = **真的没有动画**（硬切），不再隐式淡出 260ms。
        // 界面上选「无」就该什么都不做；要渐隐请选「淡入淡出」。
        if (style.animOut != OverlayAnim.NONE) {
            exiting = true
            exitProgress.snapTo(0f)
            exitProgress.animateTo(1f, exitSpecOf(style.animOut, style.animOutMs))
        }
        onFinished()
    }

    // V8：退场视觉优先。animOut = NONE 时 exiting 永远是 false，因此始终走入场视觉的
    // 终态（progress = 1 → alpha 1、无位移、无缩放），即「硬切」。
    val visual: OverlayAnimVisual = if (exiting) {
        exitVisual(style.animOut, exitProgress.value)
    } else {
        entranceVisual(style.animIn, progress.value)
    }
    // 只有 SCALE / BOUNCE 才需要一层缩放图层；其余情况不加（省一次离屏合成）
    val scaleModifier: Modifier = if (visual.scale != 1f) {
        Modifier.graphicsLayer {
            scaleX = visual.scale
            scaleY = visual.scale
        }
    } else {
        Modifier
    }

    Box(
        modifier = modifier
            .offset(x = visual.offsetXDp.dp, y = visual.offsetYDp.dp)
            .alpha(visual.alpha),
    ) {
        OverlayBubble(
            payload = payload,
            text = style.text,
            box = style.box,
            showIcon = style.showIcon,
            showTitle = style.showTitle,
            separator = style.separator,
            onMeasuredHeightDp = onMeasuredHeightDp,
            // 缩放锚在气泡自身中心（与 FloatBubble 的 D6 口径一致）
            modifier = scaleModifier,
        )
    }
}

/** 定位式弹幕的堆叠方向 → 垂直偏移符号（从下到上时第 i 条向上抬）。 */
internal fun stackSignOf(direction: DanmakuStackDirection): Int =
    if (direction == DanmakuStackDirection.BOTTOM_UP) -1 else 1


// ==================== 悬浮 ====================

/**
 * 单条悬浮：按 [FloatStyle.screenAlign] 停靠在屏幕指定位置，[FloatStyle.displaySeconds]
 * 秒后自动消失（0 = 常驻，等下一条命中替换或用户手动关闭）。
 *
 * 入场动画由 [FloatStyle.animEnum] 决定（6 种：无 / 淡入 / 右滑入 / 上滑入 / 缩放 / 回弹）。
 *
 * **V8 需求①（消失动画可配）**：到点退场时——
 * - [FloatStyle.animOut] = NONE → **真的什么动画都不做**（V9.3 纠偏）：窗口立即摘除，
 *   不淡出、不位移、不等 260ms。界面上选「无」就是「无」，不再隐式倒放入场动画；
 * - animOut != NONE → 按该动画**自己的几何方向**播一个独立退场动画（[EXIT_ANIM_MS] = 240ms），
 *   播完才回调 [onFinished]。位移方向与入场相反：入场从左向右停住，退场就往右退回去
 *   （见 [startOffsetOf] 的方向说明），**不是**把入场方向取负。
 *
 * V9.3「无缝衔接」：出现动画 =「无」时，同一样式的下一条内容由控制器**原地换内容**
 * （不重建窗口、不重播入场），于是屏幕上看起来就是上一条直接变成新的一条——
 * 没有闪烁、没有淡出再淡入。计时靠 [timerKey] 重新开始。
 *
 * 入场动画的算式与时长（260ms / BOUNCE 的 spring / NONE 直接出现）**一字未改**。
 *
 * [autoDismiss]（V6 需求①）：false 时**忽略 displaySeconds 的自动消失**——配置对话框的
 * 真机预览必须一直挂在屏幕上（播完就消失的预览等于没有预览）；预览实例的停留时长
 * 语义由「对话框关闭时撤掉」承担，入场动画重播由控制器重建窗口驱动。
 */
@Composable
fun FloatBubble(
    payload: OverlayPayload,
    style: FloatStyle,
    onFinished: () -> Unit,
    modifier: Modifier = Modifier,
    autoDismiss: Boolean = true,
    /**
     * V7 需求 8：是否撑满窗口宽度。
     *
     * - `true`（默认 = V6 行为）：根 Box `fillMaxWidth()` + start/end 内边距把气泡推到停靠侧，
     *   适用于「窗口宽度 MATCH_PARENT、点击穿透」的弹幕 / 普通悬浮 / 预览；
     * - `false`（条件卡片）：根 Box 只按内容 wrap（最大屏宽 [CONDITIONAL_MAX_WIDTH_FRACTION]），
     *   宽度由窗口 `WRAP_CONTENT` + gravity + `params.x` 决定，**不再**用内边距做水平停靠
     *   （否则「整条横带」会把下方 App 点死）。
     */
    fillWidth: Boolean = true,
    /** V7 需求 8：内容区最大高度（dp，0 = 不限；超出时按 [scrollable] 决定是否内部滚动）。 */
    maxHeightDp: Float = 0f,
    /** V7 需求 8：内容区是否可上下滚动（需配合 `maxHeightDp > 0` 与可触摸窗口）。 */
    scrollable: Boolean = false,
    /**
     * 入场动画的重播键：**只有它变化才重播**入场动画。
     *
     * 默认 `payload`（= V6 的 `LaunchedEffect(payload)` 行为，`OverlayPayload` 是 data class，
     * 按值比较）。条件卡片在最外层把「首次显示时的内容签名」冻结成本参数传入，
     * 于是同一张卡片内容原地更新时 **key 不变 → 不重播动画**（架构 §3.4 红线 4）。
     * 注意：**不要**在这里直接用 `payload` 的引用做 key —— 每次更新都是新实例，会重播。
     */
    animKey: Any? = payload,
    /**
     * V9.3：「停留 N 秒」计时的重置键（与 [animKey] 分离）。
     *
     * 为什么必须分开：出现动画 = 「无」时，悬浮窗口会**原地换内容**（与上一条无缝衔接，
     * 见 `OverlayController.showFloat`），此时 [animKey] 被冻结 → 入场动画不重播；
     * 但新内容理应拿到完整的停留时长，所以计时挂在这个每次都递增的键上。
     *
     * 默认等于 [animKey]，因此不传的调用方行为与旧版完全一致。
     */
    timerKey: Any? = animKey,
    /**
     * V8：外部退场请求（见 [OverlayExitSignal]）。
     *
     * null = 没人能从外部撤下这条悬浮（调用方的旧行为：`autoDismiss` 计时到期自动走，
     * 或 `autoDismiss = false` 时永不消失）。非 null 时：
     * - `autoDismiss` 计时与外部请求**谁先到算谁**，都进入同一个退场流程；
     * - `autoDismiss = false`（条件卡片 / 预览）也会响应外部请求，播完退场再回调
     *   [onFinished]——否则「条件卡片被撤下」永远是硬切。
     */
    exitSignal: OverlayExitSignal? = null,
) {
    val progress = remember { Animatable(0f) }
    /** V8：独立退场动画的进度（0 = 满显，1 = 完全消失），仅 animOut != NONE 时使用。 */
    val exitProgress = remember { Animatable(0f) }
    /** V8：是否已进入「独立退场动画」阶段（决定用入场视觉还是退场视觉）。 */
    var exiting by remember { mutableStateOf(false) }

    // —— V9.3：入场 与「计时 + 退场」拆成两个 effect ——
    //
    // 原来是一个 `LaunchedEffect(animKey)` 连着写到底。窗口原地换内容时（出现动画 =「无」
    // 的悬浮与上一条无缝衔接）内容变了但入场不该重播，而「停留 N 秒」又必须重新开始计时——
    // 一个 key 无法同时表达这两件事，故拆开：animKey 管动画，timerKey 管计时。

    LaunchedEffect(animKey) {
        // 重播入场时把退场状态归零（条件卡片冻结 animKey，正常情况下不会走到）
        exiting = false
        exitProgress.snapTo(0f)
        // V9.3：热更新重播入场必须先回到起点，否则 `animateTo(1f)` 从当前值（已经是
        // 1f）出发，等于没有动画。
        progress.snapTo(0f)
        // —— 入场：V4 定稿行为，算式与时长**一字未改** ——
        when (style.animEnum) {
            // 「无」= 真的没有动画：直接以终态出现（不淡入、不位移、不缩放）。
            OverlayAnim.NONE -> progress.snapTo(1f)
            OverlayAnim.BOUNCE ->
                progress.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy))

            else -> progress.animateTo(1f, tween(durationMillis = style.animInMs.coerceIn(100, 1000)))
        }
    }

    LaunchedEffect(timerKey) {
        // —— 等「该退场了」：自动到时 与 外部请求，谁先到算谁 ——
        if (autoDismiss && style.displaySeconds > 0f) {
            val timerMs = (style.displaySeconds * 1000f).toLong()
            if (exitSignal == null) {
                delay(timerMs) // 旧行为：纯计时
            } else {
                // 最多等到计时到期；期间收到外部退场请求则**立即**开始退场
                withTimeoutOrNull(timerMs) { awaitExitRequest(exitSignal) }
            }
        } else if (exitSignal == null) {
            // autoDismiss=false（预览）：停在入场终态，永不回调 onFinished（旧行为）
            return@LaunchedEffect
        } else {
            // 条件卡片 / 预览：不受 displaySeconds 驱动，只等外部撤下
            awaitExitRequest(exitSignal)
        }

        // —— 退场 ——
        //
        // V9.3 语义纠偏：`animOut == NONE` = **真的没有动画**，硬切。
        // 旧实现是「把入场进度反向回播」（FADE 进来就淡出 260ms），于是界面上明明选的是
        // 「无」，看到的却是淡入淡出——这正是本轮用户反馈要修的点。
        // 想要渐隐请选「淡入淡出」，不再由「无」隐式提供。
        if (style.animOut != OverlayAnim.NONE) {
            // 独立退场动画：按 animOut 自己的几何走，播完再回调
            exiting = true
            exitProgress.snapTo(0f)
            exitProgress.animateTo(1f, exitSpecOf(style.animOut, style.animOutMs))
        }
        onFinished()
        // 注意：animOut != NONE 时 progress 仍停在 1f，视觉完全由 exitProgress 接管
        // （见下面的 visual 选择），因此独立退场动画起手 = 入场终态，不会「闪一下」。
    }

    // ==================== 视觉（V8：入场 / 退场统一出口） ====================

    // 入场：与 V4 定稿逐像素等价（entranceVisual 的算式就是原来那三行）。
    // 退场（animOut != NONE）：由 exitVisual 接管，起点恰好等于入场终态。
    val visual: OverlayAnimVisual = if (exiting) {
        exitVisual(style.animOut, exitProgress.value)
    } else {
        entranceVisual(style.animEnum, progress.value)
    }
    val alpha = visual.alpha
    val offsetXDp = visual.offsetXDp.dp
    val offsetYDp = visual.offsetYDp.dp

    // D6 修复：SCALE / BOUNCE 此前只有枚举、没有任何缩放修饰符，实际观感与 FADE
    // **完全一样**（6 种动画只有 4 种能被看出来）。缩放由 entranceVisual / exitVisual
    // 统一给出：用**未裁剪**的 progress 值，让 BOUNCE 的 spring 过冲（>1）真实体现为
    // 「先略微放大再回落」的回弹感；上限夹到 MAX_POP_SCALE 防止异常弹簧参数把气泡
    // 放大到溢出屏幕。其余动画缩放恒为 1，保持原有表现（FADE 仍是纯透明度）。
    val scale = visual.scale

    // —— 停靠方向的水平内边距 ——
    // D3 修复：垂直偏移**只由 WindowManager 的 params.y 负责**（窗口高度是 wrap_content，
    // 这里再加 padding(top) 会把偏移算两遍，默认 120dp 实际显示在 240dp）。
    // 水平方向窗口宽度是 MATCH_PARENT，故用 start/end 内边距把气泡推到对应一侧。
    val align = style.screenAlignEnum
    val stickEnd = align == OverlayAlign.TOP_END || align == OverlayAlign.CENTER_END ||
        align == OverlayAlign.BOTTOM_END
    val stickStart = align == OverlayAlign.TOP_START || align == OverlayAlign.CENTER_START ||
        align == OverlayAlign.BOTTOM_START
    val sidePad = style.offsetEndDp.dp
    // V6.1 修复：水平居中（TOP_CENTER / CENTER / BOTTOM_CENTER）时既非 stickStart 也非
    // stickEnd，sidePad 完全不参与布局 → 「距右侧/距左侧」这个控件在这三个位置是**死值**
    // （配置页却写着「偏离中线」，用户调了没反应）。居中情形改为对气泡自身施加 x 偏移：
    // 用 padding 会让可用宽度变窄（宽卡片被裁），而 offset 只移动已摆放好的内容。
    val centerHorizontally = !stickStart && !stickEnd
    val centerShift = if (centerHorizontally) sidePad else 0.dp

    // D4 修复：根 Box 原来没有撑满宽度，尺寸退化为内容尺寸 →
    // contentAlignment 没有多余空间可分配，`padding(end=…)` 也只是加内边距、
    // 不会把内容推到右边，结果选「右下」却仍贴在左上。撑满**宽度**后九宫格停靠
    // 才真正生效。
    //
    // P0-1 修复：这里原来是 `fillMaxSize()`（撑满宽 + 高）。悬浮窗口的布局参数是
    // `WRAP_CONTENT` 高，`fillMaxSize` 会让 Box 在当前测量约束下**吃满屏高**，
    // 于是「一张卡片」实际变成整屏高的合成层：气泡本身仍画在正确的一角，但多出一个
    // 不可见、不可触摸、占满屏幕的图层（额外的离屏合成开销 + 长截屏/无障碍误判）。
    // 九宫格的**水平**停靠只依赖宽度，垂直位置由窗口 `params.y` 决定（见 D3），
    // 因此只需要 `fillMaxWidth()`——垂直方向随内容 wrap，与 D3 的口径完全一致。
    //
    // V7 需求 8：条件卡片（fillWidth = false）的窗口是 WRAP_CONTENT，宽度不能再靠
    // fillMaxWidth + 内边距，改为「按内容 wrap + 最大屏宽 85%」：既不会撑成整条横带，
    // 又不会因为一条长文本把卡片顶到屏幕外。
    val rootModifier = if (fillWidth) {
        modifier
            .fillMaxWidth()
            .padding(
                start = if (stickStart) sidePad else 0.dp,
                end = if (stickEnd) sidePad else 0.dp,
            )
            .offset(x = offsetXDp, y = offsetYDp)
            .alpha(alpha)
    } else {
        val maxWidth = (LocalConfiguration.current.screenWidthDp * CONDITIONAL_MAX_WIDTH_FRACTION).dp
        modifier
            .widthIn(max = maxWidth)
            .wrapContentWidth()
            .offset(x = offsetXDp, y = offsetYDp)
            .alpha(alpha)
    }
    Box(
        modifier = rootModifier,
        contentAlignment = alignOf(align),
    ) {
        OverlayBubble(
            payload = payload,
            text = style.text,
            box = style.box,
            showIcon = style.showIcon,
            showTitle = style.showTitle,
            separator = style.separator,
            // V7 需求 8：限高 + 内部滚动（只在调用方明确传入时生效，默认 0 = 不限 = V6 行为）
            contentMaxHeightDp = maxHeightDp,
            contentScrollable = scrollable,
            // D6：缩放锚在气泡自身中心（不是屏幕 Box），弹出观感才自然；
            // 其余动画 scale 恒为 1，等价于不加任何缩放。
            // V6.1：水平居中时在此处施加「偏离中线」位移（见 centerShift 说明）。
            modifier = Modifier
                .offset(x = if (fillWidth) centerShift else 0.dp)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                },
        )
    }
}

/** D6：缩放动画的最大放大倍数（容纳 BOUNCE 的 spring 过冲，同时防止溢出屏幕）。 */
private const val MAX_POP_SCALE = 1.2f

/**
 * 条件卡片（可触摸、WRAP_CONTENT 窗口）的最大宽度 = 屏宽 × 本系数（V7 需求 8）。
 *
 * 0.85：留出两侧留白，既让长文本有足够宽度换行，又保证「不是整条横带」——
 * 用户仍能点到卡片左右两侧下方 App 的按钮。
 */
private const val CONDITIONAL_MAX_WIDTH_FRACTION = 0.85f

/** 内容区可滚动时的溢出提示文案（滚到底自动消失）。 */
private const val SCROLL_HINT_TEXT = "⌄ 上滑查看更多"

/** 溢出提示的透明度（叠在正文上，不能盖住内容）。 */
private const val SCROLL_HINT_ALPHA = 0.6f

/**
 * 贯穿式弹幕等待「气泡首次实测宽度」的上限（ms，P1-3）。
 *
 * `onSizeChanged` 在同一帧的布局阶段回调，而 `LaunchedEffect` 在组合后立刻启动，
 * 两者可能赛跑。等一小会儿（远小于一帧的感知阈值）拿到真实宽度就用真实路程；
 * 超时则退化为旧的「按可用宽度估」行为——宁可时长不准，也绝不能让弹幕卡住不动。
 */
private const val BUBBLE_WIDTH_WAIT_MS = 120L

/**
 * V9.6：预览条最大高度。
 *
 * 宽度设得极小时（例如 1dp）正文会一个字一行，高度可达数百 dp；预览条又没有高度上限，
 * 于是把下方所有设置项挤出屏幕、用户无法再调整。160dp ≈ 3~4 行正文，足够看清效果。
 */
private val PREVIEW_MAX_HEIGHT_DP = 160.dp

/**
 * V10.7：跑马灯滚动遍数。
 *
 * 用有限次而不是无限：无限滚动会让窗口**永远在出帧**，是常驻悬浮耗电的主要原因。
 * 3 遍足够把超长内容看完；超过这个长度的信息本来也不适合用跑马灯读。
 */
private const val MARQUEE_ITERATIONS = 3

/** V9.10：预览里最多同时显示几条（再多会超出限高被裁掉，反而看不清）。 */
private const val PREVIEW_MAX_LAUNCH = 3

// ==================== 预览（配置页内嵌 sticky 条） ====================

/**
 * 配置页顶部的**常驻预览条**（仅弹幕形态使用；悬浮的预览已改为真机投影，
 * 见 OverlayStyleDialog）。
 *
 * 用户强调的通用原则：静态参数（颜色/尺寸/透明度）经 Compose 重组**实时**反映；
 * 动画参数一变，调用方把 [replayKey] +1，本组件把入场动画**完整重播一遍**：
 * - 贯穿式 → [DanmakuBubble] 的贯穿滚动（速度 px/s 即刻体现时长变化）；
 * - 定位式 → [PositionedDanmakuBubble] 的「淡入 → 停留」。
 *
 * V6 需求①：两种形态都传 previewMode=true——**播完不停在不可见终态**（贯穿式回到
 * 左缘停住、定位式停在满显停留态），任何时候预览条里都有可见内容。
 */
@Composable
fun OverlayPreviewBar(
    payload: OverlayPayload,
    style: DanmakuStyle,
    modifier: Modifier = Modifier,
    replayKey: Int = 0,
    /**
     * V9.10：预览里同时显示几条（「发射」按钮每点一次 +1，到 [PREVIEW_MAX_LAUNCH] 循环）。
     *
     * 用途：一条一条地看，根本看不出「多条同时出现会不会互相遮住」——
     * 用户要的就是「实时预览多个弹幕/气泡是怎么显示的」。
     */
    launchCount: Int = 1,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            // V9.6 修复「预览条高度撑爆、盖住所有设置项」：
            // 根因 = 宽度被设到 1dp 时正文会**一个字一行**地换行（几十行），
            // 而高度又是「0 = 自适应」，于是预览条把下方全部设置项挤出屏幕，
            // 用户连滑块都点不到，等于样式再也调不回来。
            // 这里做硬性限高 + 裁剪：预览可以看不全（宽度过小本来就看不全），
            // 但绝不允许它侵占设置区。真实显示仍按用户设定值渲染，不受影响。
            .heightIn(max = PREVIEW_MAX_HEIGHT_DP)
            .clipToBounds()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // V9.10：按「发射次数」渲染多条，用来观察同屏多条时的排布与互相遮挡。
        // replayKey 各加 index，保证每条都从入场动画起点开始播，而不是共享一个进度。
        repeat(launchCount.coerceIn(1, PREVIEW_MAX_LAUNCH)) { index ->
            when (style.modeEnum) {
                com.local.notificationfilter.core.model.DanmakuMode.THROUGH -> DanmakuBubble(
                    payload = payload,
                    style = style,
                    onFinished = { /* 预览条内自循环由 replayKey 驱动，不回调 */ },
                    replayKey = replayKey + index,
                    previewMode = true,
                )

                com.local.notificationfilter.core.model.DanmakuMode.POSITIONED -> PositionedDanmakuBubble(
                    payload = payload,
                    style = style,
                    onFinished = { },
                    replayKey = replayKey + index,
                    previewMode = true,
                )

            }
        }
    }
}
