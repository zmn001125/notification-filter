package com.local.notificationfilter.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember

/**
 * M3 彩色双主题入口（docs/ARCHITECTURE-V2.md §6.1）。
 *
 * 配色方案本身由 [AppThemes.kt] 的 `colorSchemeOf(theme, dark)` 生成：
 * - `primary` 系取自 [AppTheme.lightPrimary] / [AppTheme.darkPrimary]（六色相可选）；
 * - `secondary` / `tertiary` 两族 + `surfaceTint` / `inversePrimary`
 *   全部从同一主色派生（V7-①：强调色全线跟随主题色），因此全工程 FilterChip、
 *   动画按钮、渐变类型按钮、深浅模式按钮、统计页标签的选中态都会随之变色，
 *   调用点无需任何改动；
 * - background / surface / surfaceVariant / onSurfaceVariant / outline / outlineVariant /
 *   error 系保留 V1 灰度现值，仅 primary 系与 error 容器换彩色。
 *
 * 五动作语义色见 [ActionColors.kt]（§6.2），由 [LocalActionColors] 单独提供：
 * - **拦截（消除）/ 推迟 / 替换 / 放行**四类为固定语义色，不随色相主题切换
 *   （动作三重编码必须稳定，红/橙/蓝/绿在任何色相下含义一致）；
 * - **屏幕显示类（弹幕 / 悬浮）**例外：V8 需求 6 起语义色跟随 `primary` 实时派生
 *   （经 `actionColorsOf` 注入主色，由 `ActionColors.primaryBackedColors` 计算），
 *   以便与「放行 / 未命中」的绿明确区分，并跟随用户在主题设置里选的色相。
 *
 * V5-1：删除 V4-1 的「window 背景 = surface」方案——真机无效，因为外层 Scaffold 会用
 * background 色盖住 window 背景，状态栏区域透出的仍是 background 而非顶栏 surface。
 * 真正的修复在 NfNavHost：内屏 TopAppBar 延伸进状态栏区域（顶栏一体化），此处不再设 window 背景。
 *
 * V7-①：删除历史遗留的两份写死配色常量（原 `LightScheme` / `DarkScheme`，从未被引用，
 * 其 secondary/tertiary 是固定的薄荷绿/琥珀黄，一旦被重新接线就会让「强调色跟随主题」失效），
 * 配色方案唯一来源收敛到 [colorSchemeOf]。
 */

/**
 * V4-5：[theme] 色相主题与 [darkMode] 深浅模式两个正交开关；
 * 不传参时等价于旧行为（靛蓝 + 跟随系统）。
 */
@Composable
fun NotificationFilterTheme(
    theme: AppTheme = AppTheme.INDIGO,
    darkMode: DarkMode = DarkMode.SYSTEM,
    content: @Composable () -> Unit,
) {
    val darkTheme = when (darkMode) {
        DarkMode.SYSTEM -> isSystemInDarkTheme()
        DarkMode.LIGHT -> false
        DarkMode.DARK -> true
    }
    val colorScheme = colorSchemeOf(theme, darkTheme)

    // 五动作语义色板随主题切换（与 MaterialTheme.colorScheme 同源同刻）
    val actionPalette = remember(darkTheme) {
        if (darkTheme) ActionPalette.Dark else ActionPalette.Light
    }
    CompositionLocalProvider(LocalActionColors provides actionPalette) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography(),
            content = content,
        )
    }
}
