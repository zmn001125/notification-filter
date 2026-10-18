package com.local.notificationfilter.ui.theme

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.PictureInPicture
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * 五类处理动作的语义种类（docs/ARCHITECTURE-V2.md §6.2 三重编码映射表）。
 *
 * 与 [com.local.notificationfilter.core.model.ResultKind] /
 * [com.local.notificationfilter.core.model.ActionType] 的对应关系：
 * BLOCK=消除、SNOOZE=推迟、REPLACE=替换、READ=朗读、PASS=放行。
 */
enum class ActionKind {
    BLOCK,
    SNOOZE,
    REPLACE,
    READ,
    PASS,

    /** V4 需求 7-②：弹幕。 */
    DANMAKU,

    /** V4 需求 7-④：自定义位置悬浮。 */
    FLOAT,
}

/**
 * 单个动作的语义色四件套（§6.2）：
 * - [accent]：边框 / 图标 / 图表系列主色；
 * - [container]：实心或浅底标签的容器色（透明表示无容器，即虚线/点线/描边样式）；
 * - [onContainer]：容器上的文字 / 图标色；
 * - [text]：透明容器标签上的文字色（直接压在页面/卡片底色上）。
 */
data class ActionColors(
    val accent: Color,
    val container: Color,
    val onContainer: Color,
    val text: Color,
)

/**
 * 五动作默认图标（三重编码之「图标」维，24dp 线性，tint 由调用方决定）。
 *
 * 消除=Block、推迟=Schedule、替换=SwapHoriz、朗读=VolumeUp、放行=CheckCircle（§6.2 表末列）。
 * V2T09-T11 的动作卡 / 状态点等场景复用同一映射，避免各处重复定义。
 */
fun ActionKind.defaultIcon(): ImageVector = when (this) {
    ActionKind.BLOCK -> Icons.Filled.Block
    ActionKind.SNOOZE -> Icons.Filled.Schedule
    ActionKind.REPLACE -> Icons.Filled.SwapHoriz
    ActionKind.READ -> Icons.Filled.VolumeUp
    ActionKind.PASS -> Icons.Filled.CheckCircle
    // V4 屏幕显示类：弹幕=字幕条、悬浮=画中画
    ActionKind.DANMAKU -> Icons.Filled.Subtitles
    ActionKind.FLOAT -> Icons.Filled.PictureInPicture
}

/**
 * 该动作的语义色是否跟随主题主色。
 *
 * V8 需求 6：屏幕显示类（弹幕 [ActionKind.DANMAKU] / 悬浮 [ActionKind.FLOAT]）改用主题主色，
 * 以便与「放行 / 未命中」的绿**明确区分**；其余五动作保持稳定的固定语义色
 * （动作三重编码必须稳定，不能跟着色相主题漂移）。
 */
fun ActionKind.usesThemePrimary(): Boolean =
    this == ActionKind.DANMAKU || this == ActionKind.FLOAT

// —— 默认主题（AppTheme.INDIGO）的主色 / 表面色：仅供「无 Compose 主题上下文」的静态兜底使用 ——
private val LightPrimary = Color(0xFF3B5BDB)
private val LightSurface = Color(0xFFFFFBFE)
private val LightOnSurface = Color(0xFF1C1B1F)
private val DarkPrimary = Color(0xFFB0C6FF)
private val DarkSurface = Color(0xFF141218)
private val DarkOnSurface = Color(0xFFE6E1E5)

/**
 * 由主题主色派生「屏幕显示类」语义色（V8 需求 6）。
 *
 * 为什么要动态派生：屏幕显示（弹幕 / 悬浮）以前用固定青绿 / 紫色，其中浅色主题的青绿
 * `0xFF0CA678` 与「放行」的绿 `0xFF2F9E44` 肉眼几乎分不开，用户以为「有弹幕」和「只是放行」
 * 是同一回事。现在统一取**主题主色**：浅色主题主色偏深、深色主题主色偏亮，天然与
 * 拦截红 / 推迟橙 / 放行绿三条稳定语义色区分开，并且跟随「主题设置」里选的色相。
 *
 * 对比度保障（深色模式必须看得清）：
 * - [ActionColors.accent] 直接用主色（主色本身即是该主题下的最高对比强调色）；
 * - [ActionColors.text] / [ActionColors.onContainer] 与 `onSurface` 混 12%~14%
 *   （浅色主题偏暗、深色主题偏亮），确保压在本页底色 / 浅容器上依然清晰；
 * - [ActionColors.container] 用 `surface` 与主色混 12%（浅）/ 24%（深）得到**不透明**浅底，
 *   避免半透明叠加导致不同背景上观感不一致。
 */
private fun primaryBackedColors(
    primary: Color,
    onSurface: Color,
    surface: Color,
    dark: Boolean,
): ActionColors {
    val textMix = if (dark) 0.14f else 0.12f
    val containerMix = if (dark) 0.24f else 0.12f
    val derivedText = lerp(primary, onSurface, textMix)
    return ActionColors(
        accent = primary,
        container = lerp(surface, primary, containerMix),
        onContainer = derivedText,
        text = derivedText,
    )
}

// —— 浅色五动作语义色（§6.2 表格逐值落地；容器为「透明」的条目 onContainer 回落文字色） ——
private val LightActionColors: Map<ActionKind, ActionColors> = mapOf(
    ActionKind.BLOCK to ActionColors(
        accent = Color(0xFFE03131),
        container = Color(0xFFE03131),
        onContainer = Color(0xFFFFFFFF),
        text = Color(0xFFC92A2A),
    ),
    ActionKind.SNOOZE to ActionColors(
        accent = Color(0xFFF08C00),
        container = Color.Transparent,
        onContainer = Color(0xFFD9480F),
        text = Color(0xFFD9480F),
    ),
    ActionKind.REPLACE to ActionColors(
        accent = Color(0xFF1C7ED6),
        container = Color(0xFFE7F5FF),
        onContainer = Color(0xFF1971C2),
        text = Color(0xFF1971C2),
    ),
    ActionKind.READ to ActionColors(
        accent = Color(0xFF7048E8),
        container = Color.Transparent,
        onContainer = Color(0xFF6741D9),
        text = Color(0xFF6741D9),
    ),
    ActionKind.PASS to ActionColors(
        accent = Color(0xFF2F9E44),
        container = Color.Transparent,
        onContainer = Color(0xFF2B8A3E),
        text = Color(0xFF2B8A3E),
    ),
    // —— V4 屏幕显示类（浅色；V8 需求 6 改为主题主色派生，这里只是无主题上下文的兜底）——
    ActionKind.DANMAKU to primaryBackedColors(
        primary = LightPrimary,
        onSurface = LightOnSurface,
        surface = LightSurface,
        dark = false,
    ),
    ActionKind.FLOAT to primaryBackedColors(
        primary = LightPrimary,
        onSurface = LightOnSurface,
        surface = LightSurface,
        dark = false,
    ),
)

// —— 深色五动作语义色（§6.2 表格逐值落地；容器为「透明」的条目 onContainer 回落文字色） ——
private val DarkActionColors: Map<ActionKind, ActionColors> = mapOf(
    ActionKind.BLOCK to ActionColors(
        accent = Color(0xFFFF8787),
        container = Color(0xFFFF8787),
        onContainer = Color(0xFF5C0606),
        text = Color(0xFFFFA8A8),
    ),
    ActionKind.SNOOZE to ActionColors(
        accent = Color(0xFFFFC078),
        container = Color.Transparent,
        onContainer = Color(0xFFFFD8A8),
        text = Color(0xFFFFD8A8),
    ),
    ActionKind.REPLACE to ActionColors(
        accent = Color(0xFF74C0FC),
        container = Color(0xFF17364E),
        onContainer = Color(0xFFA5D8FF),
        text = Color(0xFFA5D8FF),
    ),
    ActionKind.READ to ActionColors(
        accent = Color(0xFFB197FC),
        container = Color.Transparent,
        onContainer = Color(0xFFD0BFFF),
        text = Color(0xFFD0BFFF),
    ),
    ActionKind.PASS to ActionColors(
        accent = Color(0xFF8CE99A),
        container = Color.Transparent,
        onContainer = Color(0xFFB2F2BB),
        text = Color(0xFFB2F2BB),
    ),
    // —— V4 屏幕显示类（深色；V8 需求 6 改为主题主色派生，这里只是无主题上下文的兜底）——
    ActionKind.DANMAKU to primaryBackedColors(
        primary = DarkPrimary,
        onSurface = DarkOnSurface,
        surface = DarkSurface,
        dark = true,
    ),
    ActionKind.FLOAT to primaryBackedColors(
        primary = DarkPrimary,
        onSurface = DarkOnSurface,
        surface = DarkSurface,
        dark = true,
    ),
)

/**
 * 五动作色板（浅/深两套）。
 *
 * 由 [NotificationFilterTheme] 按当前主题提供；不直接读 isSystemInDarkTheme，
 * 以保证主题被强制指定（如预览 / 强制浅色）时语义色同步跟随。
 */
class ActionPalette(
    val dark: Boolean,
    private val lightColors: Map<ActionKind, ActionColors>,
    private val darkColors: Map<ActionKind, ActionColors>,
) {
    /**
     * 取当前主题下指定动作的语义色；未知 kind 防御性回落放行色。
     *
     * @param primary 当前主题主色。非空且 [kind] 属于屏幕显示类（[ActionKind.usesThemePrimary]）时，
     *   按主色**实时派生**（V8 需求 6），忽略静态表里的兜底值；其余动作不受该参数影响。
     * @param onSurface 当前主题的 `onSurface`，用于派生文字色时的对比度微调；null 时用默认主题值。
     * @param surface 当前主题的 `surface`，用于派生不透明容器色；null 时用默认主题值。
     */
    fun colorsOf(
        kind: ActionKind,
        primary: Color? = null,
        onSurface: Color? = null,
        surface: Color? = null,
    ): ActionColors {
        if (primary != null && kind.usesThemePrimary()) {
            return primaryBackedColors(
                primary = primary,
                onSurface = onSurface ?: if (dark) DarkOnSurface else LightOnSurface,
                surface = surface ?: if (dark) DarkSurface else LightSurface,
                dark = dark,
            )
        }
        return (if (dark) darkColors else lightColors)[kind] ?: lightColors.getValue(ActionKind.PASS)
    }

    companion object {
        /** 浅色主题色板。 */
        val Light: ActionPalette = ActionPalette(
            dark = false,
            lightColors = LightActionColors,
            darkColors = DarkActionColors,
        )

        /** 深色主题色板。 */
        val Dark: ActionPalette = ActionPalette(
            dark = true,
            lightColors = LightActionColors,
            darkColors = DarkActionColors,
        )
    }
}

/**
 * 五动作语义色 CompositionLocal（§6.3）。
 *
 * 默认值为浅色色板：组件在 Theme 外被渲染（如 @Preview 未包主题）时仍有可用色值。
 */
val LocalActionColors: ProvidableCompositionLocal<ActionPalette> =
    staticCompositionLocalOf { ActionPalette.Light }

/**
 * 取当前主题下 [kind] 的语义色四件套（§6.3 唯一读取入口）。
 *
 * V8 需求 6：屏幕显示类（[ActionKind.DANMAKU] / [ActionKind.FLOAT]）额外注入当前主题的
 * `primary` / `onSurface` / `surface`，实现「屏幕显示 = 主题主色」，与放行绿彻底区分；
 * 其余动作只用 [LocalActionColors]，不受这些参数影响。三个新增参数都有默认值，
 * **既有调用点 `actionColorsOf(kind)` 无需改动**。
 */
@Composable
fun actionColorsOf(
    kind: ActionKind,
    primary: Color = MaterialTheme.colorScheme.primary,
    onSurface: Color = MaterialTheme.colorScheme.onSurface,
    surface: Color = MaterialTheme.colorScheme.surface,
): ActionColors = LocalActionColors.current.colorsOf(kind, primary, onSurface, surface)
