package com.local.notificationfilter

import com.local.notificationfilter.core.model.ActionType
import com.local.notificationfilter.core.model.DanmakuMode
import com.local.notificationfilter.core.model.DanmakuStackDirection
import com.local.notificationfilter.core.model.DanmakuStyle
import com.local.notificationfilter.core.model.FloatStyle
import com.local.notificationfilter.core.model.GradientSpec
import com.local.notificationfilter.core.model.GradientStop
import com.local.notificationfilter.core.model.NfJson
import com.local.notificationfilter.core.model.OverlayAlign
import com.local.notificationfilter.core.model.OverlayAnim
import com.local.notificationfilter.core.model.OverlayBoxStyle
import com.local.notificationfilter.core.model.OverlayGradientKind
import com.local.notificationfilter.core.model.OverlayTextStyle
import com.local.notificationfilter.core.model.Rule
import com.local.notificationfilter.core.model.RuleActionConfig
import com.local.notificationfilter.core.model.SnoozeConfig
import com.local.notificationfilter.data.repo.RuleJsonMigrator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * V4 屏幕显示类动作（7-②弹幕 / 7-④悬浮）的模型与序列化单测。
 *
 * V5：焦点通知（7-①）已整体删除（个人侧载拿不到小米授权），相关用例移除；
 * 旧 JSON 中 focus 段的清除迁移用例见 RuleJsonMigratorTest。
 *
 * V5 需求③④⑤ 追加覆盖（纯 JVM，无 Android 依赖）：
 * - 样式模型**序列化往返无损**（规则 JSON 存 Room 单列，必须可逆）；
 * - **弹幕双形态**：DanmakuMode / DanmakuStackDirection 解析与回落、定位式字段往返、
 *   贯穿式 speedPxps（px/秒）默认值；
 * - **占位符模板**：弹幕/悬浮 titleTemplate/textTemplate 往返与默认留空；
 * - **渐变模型**：GradientSpec（无/线性/径向 + 多色标 + 角度/半径比例）往返、
 *   isRenderable 边界、旧双色字段经 RuleJsonMigrator 迁移后的映射正确性；
 * - **旧规则 JSON 向后兼容**：不含新字段时反序列化取默认值、plannedActions 不含新动作；
 * - 九宫格 / 动画枚举解析（未知值回落）；
 * - 动作组合的执行顺序与互斥语义不变（朗读 → 消除 → 推迟 → 替换 → 弹幕 → 悬浮）。
 */
class OverlayStyleTest {

    // ==================== 样式序列化往返 ====================

    @Test
    fun danmakuStyle_roundTrip() {
        val style = DanmakuStyle(
            text = OverlayTextStyle(sizeSp = 20f, bold = true, color = 0xFF00FF00L, maxLines = 2),
            box = OverlayBoxStyle(
                bgSolidColor = 0xCC112233L,
                bgGradient = GradientSpec(
                    kind = OverlayGradientKind.LINEAR.name,
                    stops = listOf(
                        GradientStop(0f, 0xCC112233L),
                        GradientStop(1f, 0xCC445566L),
                    ),
                    angleDeg = 45f,
                ),
                cornerTopStart = 1f,
                cornerTopEnd = 2f,
                cornerBottomEnd = 3f,
                cornerBottomStart = 4f,
                padStart = 5f,
                padTop = 6f,
                padEnd = 7f,
                padBottom = 8f,
            ),
            anim = OverlayAnim.BOUNCE.name,
            mode = DanmakuMode.POSITIONED.name,
            speedPxps = 800f,
            displaySeconds = 9f,
            maxConcurrent = 4,
            showIcon = false,
            showTitle = false,
            separator = " | ",
            topMarginDp = 120f,
            trackGapDp = 12f,
            stackAlign = OverlayAlign.BOTTOM_CENTER.name,
            offsetXDp = 16f,
            offsetYDp = 88f,
            stackDirection = DanmakuStackDirection.BOTTOM_UP.name,
            stackGapDp = 6f,
            titleTemplate = "{app}：{title}",
            textTemplate = "取件码 {extras.code}",
        )
        val json = NfJson.encodeToString(style)
        val back = NfJson.decodeFromString<DanmakuStyle>(json)
        assertEquals(style, back)
        assertEquals(OverlayAnim.BOUNCE, back.animEnum)
        assertEquals(DanmakuMode.POSITIONED, back.modeEnum)
        assertEquals(OverlayAlign.BOTTOM_CENTER, back.stackAlignEnum)
        assertEquals(DanmakuStackDirection.BOTTOM_UP, back.stackDirectionEnum)
        assertEquals(listOf(1f, 2f, 3f, 4f), back.box.corners)
        assertEquals(listOf(5f, 6f, 7f, 8f), back.box.paddings)
        assertTrue(back.box.isGradient)
        assertEquals(2, back.box.bgGradient.stops.size)
    }

    @Test
    fun floatStyle_roundTrip() {
        val style = FloatStyle(
            anim = OverlayAnim.SLIDE_TOP.name,
            screenAlign = OverlayAlign.BOTTOM_END.name,
            offsetTopDp = 300f,
            offsetEndDp = 24f,
            displaySeconds = 0f, // 常驻
            clickToOpen = false,
            titleTemplate = "{app}",
            textTemplate = "{text}（{time}）",
        )
        val back = NfJson.decodeFromString<FloatStyle>(NfJson.encodeToString(style))
        assertEquals(style, back)
        assertEquals(OverlayAlign.BOTTOM_END, back.screenAlignEnum)
        assertEquals(OverlayAnim.SLIDE_TOP, back.animEnum)
        assertEquals("{text}（{time}）", back.textTemplate)
    }

    // ==================== V5：渐变模型 ====================

    @Test
    fun gradientSpec_roundTrip_radialMultiStops() {
        val spec = GradientSpec(
            kind = OverlayGradientKind.RADIAL.name,
            stops = listOf(
                GradientStop(0f, 0xFFFFFFFFL),
                GradientStop(0.55f, 0xFF1C7ED6L),
                GradientStop(1f, 0xFF1A1B1EL),
            ),
            radiusRatio = 0.7f,
        )
        val back = NfJson.decodeFromString<GradientSpec>(NfJson.encodeToString(spec))
        assertEquals(spec, back)
        assertEquals(OverlayGradientKind.RADIAL, back.kindEnum)
        assertTrue(back.isRenderable)
        assertEquals(3, back.stops.size)
    }

    @Test
    fun gradientSpec_renderableBoundary() {
        // NONE 即使有色标也不渲染；非 NONE 色标 < 2 不渲染
        assertFalse(GradientSpec().isRenderable)
        assertFalse(
            GradientSpec(
                kind = OverlayGradientKind.NONE.name,
                stops = listOf(GradientStop(0f, 1L), GradientStop(1f, 2L)),
            ).isRenderable,
        )
        assertFalse(
            GradientSpec(
                kind = OverlayGradientKind.LINEAR.name,
                stops = listOf(GradientStop(0f, 1L)),
            ).isRenderable,
        )
        assertTrue(
            GradientSpec(
                kind = OverlayGradientKind.LINEAR.name,
                stops = listOf(GradientStop(0f, 1L), GradientStop(1f, 2L)),
            ).isRenderable,
        )
    }

    @Test
    fun gradientKind_unknownFallsBackToNone() {
        assertEquals(OverlayGradientKind.LINEAR, OverlayGradientKind.of("LINEAR"))
        assertEquals(OverlayGradientKind.NONE, OverlayGradientKind.of("WARP"))
        assertEquals(OverlayGradientKind.NONE, OverlayGradientKind.of(null))
        assertEquals(3, OverlayGradientKind.ALL.size)
        assertEquals(4, GradientSpec.MAX_STOPS)
    }

    // ==================== V5：弹幕双形态 ====================

    @Test
    fun danmakuStyle_defaults_throughModeWithPxSpeed() {
        val style = DanmakuStyle()
        // 默认 = 贯穿式（需求③明示「默认」），速度单位为 px/秒
        assertEquals(DanmakuMode.THROUGH, style.modeEnum)
        assertTrue(style.speedPxps > 0f)
        // 模板默认留空 = 用原通知标题/正文
        assertEquals("", style.titleTemplate)
        assertEquals("", style.textTemplate)
        // 定位式字段均有可用默认值（切形态开箱即用）
        assertEquals(OverlayAlign.TOP_CENTER, style.stackAlignEnum)
        assertEquals(DanmakuStackDirection.TOP_DOWN, style.stackDirectionEnum)
    }

    @Test
    fun danmakuModeAndStackDirection_unknownFallback() {
        assertEquals(DanmakuMode.POSITIONED, DanmakuMode.of("POSITIONED"))
        assertEquals(DanmakuMode.THROUGH, DanmakuMode.of("SCROLL"))
        assertEquals(DanmakuMode.THROUGH, DanmakuMode.of(null))
        assertEquals(DanmakuStackDirection.BOTTOM_UP, DanmakuStackDirection.of("BOTTOM_UP"))
        assertEquals(DanmakuStackDirection.TOP_DOWN, DanmakuStackDirection.of("REVERSE"))
        assertEquals(DanmakuStackDirection.TOP_DOWN, DanmakuStackDirection.of(null))
    }

    @Test
    fun boxStyle_fallbackColor_prefersFirstStopThenSolid() {
        val solidOnly = OverlayBoxStyle(bgSolidColor = 0xFFAABBCC)
        assertEquals(0xFFAABBCC, solidOnly.bgFallbackColor)
        assertFalse(solidOnly.isGradient)

        val withStops = OverlayBoxStyle(
            bgSolidColor = 0xFFAABBCC,
            bgGradient = GradientSpec(
                kind = OverlayGradientKind.LINEAR.name,
                stops = listOf(GradientStop(0f, 0xFF000001L), GradientStop(1f, 0xFF000002L)),
            ),
        )
        assertTrue(withStops.isGradient)
        // 渐变可渲染时回落色取色标 0（渐变失效的兜底）
        assertEquals(0xFF000001L, withStops.bgFallbackColor)
    }

    // ==================== V5：旧 JSON 兼容 + 渐变迁移 ====================

    @Test
    fun ruleJson_v4Danmaku_decodesAfterMigration() {
        // V4 时代落库的弹幕样式：speedDps（dp/秒）+ 旧渐变三键
        val oldJson = """
            {"id":9,"name":"旧弹幕","enabled":true,"order":0,"matchMode":"ALL_CONTENT",
             "actions":{"danmaku":{"speedDps":200.0,"displaySeconds":8.0,"maxConcurrent":2,
             "box":{"bgStart":-885837003,"bgEnd":-862349226,"bgAngle":45.0,"bgAlpha":1.0}}}}
        """.trimIndent()
        val migrated = RuleJsonMigrator.migrateJsonIfNeeded(oldJson)
        val rule = NfJson.decodeFromString<Rule>(migrated)
        val dm = rule.actions.danmaku!!
        // 旧速度字段无法换算 → 弃用回落默认 px/s
        assertEquals(DanmakuStyle().speedPxps, dm.speedPxps)
        // 其余字段原样保留
        assertEquals(8f, dm.displaySeconds)
        assertEquals(2, dm.maxConcurrent)
        // 双色线性渐变 → LINEAR 双色标
        val g = dm.box.bgGradient
        assertEquals(OverlayGradientKind.LINEAR, g.kindEnum)
        assertEquals(2, g.stops.size)
        assertEquals(-885837003L, g.stops[0].color)
        assertEquals(0f, g.stops[0].position)
        assertEquals(-862349226L, g.stops[1].color)
        assertEquals(1f, g.stops[1].position)
        assertEquals(45f, g.angleDeg)
        // 纯色回落 = 原 bgStart
        assertEquals(-885837003L, dm.box.bgSolidColor)
        // 新字段取默认（贯穿式 + 空模板）
        assertEquals(DanmakuMode.THROUGH, dm.modeEnum)
        assertEquals("", dm.titleTemplate)
    }

    @Test
    fun ruleJson_v4SolidBackground_migratesToSolidOnly() {
        // 无 bgEnd → 纯色：只搬 bgStart → bgSolidColor，不产生渐变配置
        val oldJson = """
            {"id":10,"name":"纯色悬浮","enabled":true,"order":0,"matchMode":"ALL_CONTENT",
             "actions":{"float":{"screenAlign":"TOP_END",
             "box":{"bgStart":-16776960,"bgAlpha":0.9}}}}
        """.trimIndent()
        val migrated = RuleJsonMigrator.migrateJsonIfNeeded(oldJson)
        val rule = NfJson.decodeFromString<Rule>(migrated)
        val fl = rule.actions.float!!
        assertEquals(-16776960L, fl.box.bgSolidColor)
        assertEquals(OverlayGradientKind.NONE, fl.box.bgGradient.kindEnum)
        assertFalse(fl.box.isGradient)
        assertEquals(0.9f, fl.box.bgAlpha)
        // 悬浮模板字段默认留空
        assertEquals("", fl.titleTemplate)
    }

    @Test
    fun gradientMigration_idempotentAndNeedsWriteback() {
        val oldJson = """
            {"id":11,"name":"幂等","enabled":true,"order":0,"matchMode":"ALL_CONTENT",
             "actions":{"danmaku":{"speedDps":100.0,
             "box":{"bgStart":-1,"bgEnd":-16777216,"bgAngle":90.0}}}}
        """.trimIndent()
        assertTrue(RuleJsonMigrator.needsWriteback(oldJson))
        val once = RuleJsonMigrator.migrateJsonIfNeeded(oldJson)
        val twice = RuleJsonMigrator.migrateJsonIfNeeded(once)
        assertEquals(once, twice) // 二次迁移逐字符不变
        assertFalse(RuleJsonMigrator.needsWriteback(once)) // 迁移后不再需要写回
        // 新形态 JSON 不含旧键
        assertFalse(once.contains("bgStart"))
        assertFalse(once.contains("speedDps"))
    }

    @Test
    fun gradientMigration_v5Json_untouched() {
        // 已是 V5 形态：原样返回（逐字符一致）
        val rule = Rule(
            id = 12L,
            name = "新渐变",
            actions = RuleActionConfig(
                danmaku = DanmakuStyle(
                    box = OverlayBoxStyle(
                        bgGradient = GradientSpec(
                            kind = OverlayGradientKind.RADIAL.name,
                            stops = listOf(GradientStop(0f, -1), GradientStop(1f, -16777216)),
                            radiusRatio = 0.6f,
                        ),
                    ),
                ),
            ),
        )
        val json = NfJson.encodeToString(rule)
        assertEquals(json, RuleJsonMigrator.migrateJsonIfNeeded(json))
        assertFalse(RuleJsonMigrator.needsWriteback(json))
    }

    // ==================== V6：边缘羽化 ====================

    @Test
    fun boxStyle_edgeFeather_roundTrip() {
        val box = OverlayBoxStyle(
            bgSolidColor = 0xE61A1B1EL,
            edgeFeatherDp = 12.5f,
        )
        val back = NfJson.decodeFromString<OverlayBoxStyle>(NfJson.encodeToString(box))
        assertEquals(box, back)
        assertEquals(12.5f, back.edgeFeatherDp)
    }

    @Test
    fun boxStyle_edgeFeather_oldJsonDefaultsToZero() {
        // V5 时代落库的气泡 JSON（无 edgeFeatherDp 字段）→ decode 取默认 0（关闭）
        val oldJson = """{"bgSolidColor":-16776960,"bgAlpha":0.9,"widthDp":0.0}"""
        val box = NfJson.decodeFromString<OverlayBoxStyle>(oldJson)
        assertEquals(0f, box.edgeFeatherDp)
        assertEquals(-16776960L, box.bgSolidColor)
    }

    // ==================== 枚举解析 ====================

    @Test
    fun alignOf_unknownFallsBackToCenter() {
        assertEquals(OverlayAlign.TOP_END, OverlayAlign.of("TOP_END"))
        assertEquals(OverlayAlign.CENTER, OverlayAlign.of("NO_SUCH"))
        assertEquals(OverlayAlign.CENTER, OverlayAlign.of(null))
        assertEquals(9, OverlayAlign.ALL.size)
    }

    @Test
    fun animOf_unknownFallsBackToFade() {
        assertEquals(OverlayAnim.SCALE, OverlayAnim.of("SCALE"))
        assertEquals(OverlayAnim.FADE, OverlayAnim.of(""))
        // V9 需求 8：新增「从左滑入 / 从下滑入」，与既有四值共 8 种
        assertEquals(OverlayAnim.SLIDE_RIGHT, OverlayAnim.of("SLIDE_RIGHT"))
        assertEquals(OverlayAnim.SLIDE_BOTTOM, OverlayAnim.of("SLIDE_BOTTOM"))
        assertEquals(8, OverlayAnim.ALL.size)
    }

    @Test
    fun textStyle_alignEnumParses() {
        assertEquals(OverlayAlign.TOP_START, OverlayTextStyle(align = "TOP_START").alignEnum)
        assertEquals(OverlayAlign.CENTER, OverlayTextStyle().alignEnum)
    }

    // ==================== 动作组合 ====================

    @Test
    fun plannedActions_orderAndOverlayFlags() {
        val cfg = RuleActionConfig(
            readAloud = true,
            block = false,
            snooze = SnoozeConfig(times = listOf("20:00")),
            replace = null,
            danmaku = DanmakuStyle(),
            float = FloatStyle(),
        )
        assertEquals(
            listOf(
                ActionType.READ_ALOUD,
                ActionType.SNOOZE,
                ActionType.DANMAKU,
                ActionType.FLOAT,
            ),
            cfg.plannedActions(),
        )
        assertTrue(cfg.hasOverlay)
        assertFalse(cfg.isEmpty)
    }

    @Test
    fun emptyConfig_hasNoOverlay() {
        val cfg = RuleActionConfig()
        assertTrue(cfg.isEmpty)
        assertFalse(cfg.hasOverlay)
        assertTrue(cfg.plannedActions().isEmpty())
    }

    @Test
    fun onlyDanmaku_hasOverlayButNotFiltered() {
        val cfg = RuleActionConfig(danmaku = DanmakuStyle())
        assertTrue(cfg.hasOverlay)
        assertFalse(cfg.isEmpty)
        assertEquals(listOf(ActionType.DANMAKU), cfg.plannedActions())
    }

    // ==================== 规则 JSON 向后兼容 ====================

    @Test
    fun ruleJson_withoutOverlayFields_decodesToNull() {
        // 模拟 V3 时代存下的旧 JSON：完全没有 danmaku / float 两个键
        val oldJson = """{"id":1,"name":"旧规则","enabled":true,"order":0,"actions":{"block":true}}"""
        val rule = NfJson.decodeFromString<Rule>(oldJson)
        assertNull(rule.actions.danmaku)
        assertNull(rule.actions.float)
        assertFalse(rule.actions.hasOverlay)
        assertEquals(listOf(ActionType.BLOCK), rule.actions.plannedActions())
    }

    @Test
    fun ruleJson_withOverlayFields_roundTrip() {
        val rule = Rule(
            id = 7L,
            name = "取件码弹幕",
            actions = RuleActionConfig(
                danmaku = DanmakuStyle(displaySeconds = 8f),
                float = FloatStyle(displaySeconds = 12f),
            ),
        )
        val back = NfJson.decodeFromString<Rule>(NfJson.encodeToString(rule))
        assertEquals(rule, back)
        assertEquals(8f, back.actions.danmaku?.displaySeconds ?: 0f)
        assertEquals(12f, back.actions.float?.displaySeconds ?: 0f)
    }
}
