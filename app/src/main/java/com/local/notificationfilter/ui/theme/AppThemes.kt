package com.local.notificationfilter.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.lerp

/**
 * V4-5：内置色相主题。
 *
 * 每个主题给出**浅/深两套主色**（深色主题需要提亮主色以保证在深背景上的对比度），
 * 其余灰度角色（background / surface / variant / outline / error）各主题共用同一套值，
 * 保证切换主题时只有"主色调"变化、信息层级与可读性不变。
 *
 * 五动作语义色 [ActionColors] 是独立色板，不随本主题切换（动作三重编码必须稳定）。
 */
enum class AppTheme(
    val label: String,
    val lightPrimary: Color,
    val darkPrimary: Color,
) {
    INDIGO("靛蓝", Color(0xFF3B5BDB), Color(0xFFB0C6FF)),
    TEAL("青绿", Color(0xFF00796B), Color(0xFF6BD9C4)),
    AMBER("琥珀", Color(0xFFE8590C), Color(0xFFFFB37A)),
    VIOLET("紫罗兰", Color(0xFF6741D9), Color(0xFFC4B2FF)),
    ROSE("玫红", Color(0xFFC2255C), Color(0xFFFFA8C5)),
    GRAPHITE("石墨灰", Color(0xFF333333), Color(0xFFC7C7C7)),
    ;

    companion object {
        /** 按存储名解析，未知值回落靛蓝（默认主题）。 */
        fun of(name: String?): AppTheme =
            entries.firstOrNull { it.name == name } ?: INDIGO
    }
}

/**
 * V4-5：深浅模式，与色相主题**正交**（用户可独立选择"靛蓝+深色"或"玫红+浅色"）。
 */
enum class DarkMode(val label: String) {
    SYSTEM("跟随系统"),
    LIGHT("浅色"),
    DARK("深色"),
    ;

    companion object {
        fun of(name: String?): DarkMode =
            entries.firstOrNull { it.name == name } ?: SYSTEM
    }
}

/** 按主题 + 是否深色生成 M3 配色方案。 */
internal fun colorSchemeOf(theme: AppTheme, dark: Boolean) =
    if (dark) darkSchemeOf(theme) else lightSchemeOf(theme)

/**
 * 浅色配色方案（V7-①：强调色全线跟随主题主色）。
 *
 * `secondary` / `tertiary` 两族不再写死薄荷绿 / 琥珀黄，全部从 [AppTheme.lightPrimary]
 * 派生——Compose M3 的 FilterChip / SegmentedButton / Switch 选中态默认取
 * `secondaryContainer` + `onSecondaryContainer`，FAB 与卡片 elevation 叠加取 `surfaceTint`，
 * 因此只要本文件派生到位，全工程所有「强调/选中/高亮」语义自动跟随主题，调用点零改动。
 */
private fun lightSchemeOf(t: AppTheme): ColorScheme {
    val primaryColor = t.lightPrimary
    // 容器底色：主色叠白（14% ≈ M3 的 secondaryContainer 明度，8% 用于更弱的 tertiary）
    val secondarySurface = primaryColor.copy(alpha = 0.14f).compositeOver(Color(0xFFFFFFFF))
    val tertiarySurface = primaryColor.copy(alpha = 0.08f).compositeOver(Color(0xFFFFFFFF))
    // 容器上的文字：主色压深 22%。琥珀（#E8590C）直接当文字在 14% 底上对比度仅 ≈3.0:1，
    // 压深后 ≈4.6:1 达到 WCAG AA（14sp 正文阈值 4.5:1）；深主色压深后视觉差异极小。
    val onTintedContainer = lerp(primaryColor, Color(0xFF000000), 0.22f)
    return lightColorScheme(
        primary = primaryColor,
        onPrimary = Color(0xFFFFFFFF),
        primaryContainer = primaryColor.copy(alpha = 0.14f).compositeOver(Color(0xFFFFFFFF)),
        onPrimaryContainer = primaryColor,
        // —— 强调色系：全部派生自主色 ——
        secondary = primaryColor,
        onSecondary = Color(0xFFFFFFFF),
        secondaryContainer = secondarySurface,
        onSecondaryContainer = onTintedContainer,
        tertiary = primaryColor,
        onTertiary = Color(0xFFFFFFFF),
        tertiaryContainer = tertiarySurface,
        onTertiaryContainer = onTintedContainer,
        surfaceTint = primaryColor,
        inversePrimary = primaryColor,
        background = Color(0xFFF5F6F8),
        onBackground = Color(0xFF1F2329),
        surface = Color(0xFFFFFFFF),
        onSurface = Color(0xFF1F2329),
        // V6-⑥：底栏容器与页面背景协调——surface 上叠 4% 主题色（介于白与灰底之间，
        // 随六主题变色），替代 M3 默认 surfaceContainer 的固定灰
        surfaceContainer = primaryColor.copy(alpha = 0.04f).compositeOver(Color(0xFFFFFFFF)),
        surfaceVariant = Color(0xFFEDEFF2),
        onSurfaceVariant = Color(0xFF5F6368),
        outline = Color(0xFFC9CDD3),
        outlineVariant = Color(0xFFE4E7EB),
        error = Color(0xFFBA1A1A),
        onError = Color(0xFFFFFFFF),
        errorContainer = Color(0xFFFFDAD6),
        onErrorContainer = Color(0xFF410002),
    )
}

/**
 * 深色配色方案（V7-①：强调色跟随主题）。
 *
 * 深色下底色是深灰、文字必须浅：容器底 = 主色叠深色 surface，容器上文字 = 主色向白插值 55%，
 * 保证 `onSecondaryContainer` 一定是浅色系，蓝/紫/玫红等深主色在深底上也清晰可读。
 */
private fun darkSchemeOf(t: AppTheme): ColorScheme {
    val primaryColor = t.darkPrimary
    // 与 surface 同源的调和底（深色下主色容器必须坐在这层之上，否则会发灰发脏）
    val base = Color(0xFF1D2126)
    val onTintedContainer = lerp(primaryColor, Color(0xFFFFFFFF), 0.55f)
    return darkColorScheme(
        primary = primaryColor,
        onPrimary = Color(0xFF001B3D),
        primaryContainer = primaryColor.copy(alpha = 0.22f).compositeOver(Color(0xFF14171A)),
        onPrimaryContainer = primaryColor,
        // —— 强调色系：全部派生自主色 ——
        secondary = primaryColor,
        onSecondary = Color(0xFF001B3D),
        secondaryContainer = primaryColor.copy(alpha = 0.26f).compositeOver(base),
        onSecondaryContainer = onTintedContainer,
        tertiary = primaryColor,
        onTertiary = Color(0xFF001B3D),
        tertiaryContainer = primaryColor.copy(alpha = 0.18f).compositeOver(base),
        onTertiaryContainer = onTintedContainer,
        surfaceTint = primaryColor,
        inversePrimary = primaryColor,
        background = Color(0xFF14171A),
        onBackground = Color(0xFFE4E7EB),
        surface = Color(0xFF1D2126),
        onSurface = Color(0xFFE4E7EB),
        // V6-⑥：深色底栏容器——surface 上叠 8% 主题主色
        surfaceContainer = primaryColor.copy(alpha = 0.08f).compositeOver(base),
        surfaceVariant = Color(0xFF2A2F35),
        onSurfaceVariant = Color(0xFFB9BDC2),
        outline = Color(0xFF5F6368),
        outlineVariant = Color(0xFF3A3F45),
        error = Color(0xFFFFB4AB),
        onError = Color(0xFF690005),
        errorContainer = Color(0xFF93000A),
        onErrorContainer = Color(0xFFFFDAD6),
    )
}
