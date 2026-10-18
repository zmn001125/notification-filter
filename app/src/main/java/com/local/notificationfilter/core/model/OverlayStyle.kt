package com.local.notificationfilter.core.model

import kotlinx.serialization.Serializable

/**
 * 屏幕显示类动作的样式数据模型（V4 需求 7-②弹幕 / 7-④自定义位置悬浮）。
 *
 * 设计约定：
 * - **纯 Kotlin + kotlinx.serialization，无 Android / Compose 依赖**（与 core/model 其余
 *   模型一致，可 JVM 单测）；颜色一律以 **ARGB Long** 表示（如 `0xFFFFFFFF` 不透明白、
 *   `0xCC000000` 80% 黑），Compose 侧由 ui/overlay 的扩展函数转成 [androidx.compose.ui.graphics.Color]；
 * - 弹幕与悬浮**共用同一套子模型**（[OverlayTextStyle] / [OverlayBoxStyle] / [OverlayAnim]），
 *   只是外层容器不同（[DanmakuStyle] 含双形态滚动/定位参数，[FloatStyle] 含屏幕对齐与偏移）；
 * - 全部字段带默认值 → 新增字段对旧 JSON 向后兼容（`ignoreUnknownKeys = true` +
 *   `encodeDefaults = true`，旧规则反序列化后新字段取默认值）。
 *
 * 数值单位：尺寸一律 **dp**（density-independent pixel），字号一律 **sp**，时长一律 **秒**；
 * **贯穿式弹幕速度为 px/秒**（[DanmakuStyle.speedPxps]，V5 起，UI 要求手填具体数值）。
 * `0` 表示「自适应 / 不限」，与 UI 上的「自动」语义一致。
 *
 * V5 兼容说明：
 * - 弹幕/悬浮新增字段全部带默认值，旧 JSON 直接 decode 即取默认（贯穿式、原样文案）；
 * - **渐变结构重构**（bgStart/bgEnd/bgAngle → [GradientSpec]）：旧字段无法平滑 decode，
 *   由 `data/repo/RuleJsonMigrator.migrateOverlayGradientIfNeeded` 做一次性迁移（幂等）。
 */

// ==================== 枚举 ====================

/**
 * 九宫格对齐（同时用于「悬浮窗在屏幕上的位置」与「文字在气泡内的位置」）。
 * 命名沿用 Compose Alignment 口径，UI 直接按 3×3 网格渲染。
 */
enum class OverlayAlign(val label: String) {
    TOP_START("左上"),
    TOP_CENTER("上中"),
    TOP_END("右上"),
    CENTER_START("左中"),
    CENTER("居中"),
    CENTER_END("右中"),
    BOTTOM_START("左下"),
    BOTTOM_CENTER("下中"),
    BOTTOM_END("右下"),
    ;

    companion object {
        val ALL: List<OverlayAlign> = entries.toList()

        /** 按枚举名解析（JSON 存名），未知值回落 [CENTER]。 */
        fun of(name: String?): OverlayAlign =
            entries.firstOrNull { it.name == name } ?: CENTER
    }
}

/** 出现/消失动画（6 种；NONE = 直接显示）。 */
enum class OverlayAnim(val label: String) {
    NONE("无"),
    FADE("淡入淡出"),
    SLIDE_LEFT("从右滑入"),
    SLIDE_TOP("从上滑入"),
    /** V9：从左缘进入（向右移动）。 */
    SLIDE_RIGHT("从左滑入"),
    /** V9：从下缘进入（向上移动）。 */
    SLIDE_BOTTOM("从下滑入"),
    SCALE("缩放弹出"),
    BOUNCE("回弹弹出"),
    ;

    companion object {
        val ALL: List<OverlayAnim> = entries.toList()

        fun of(name: String?): OverlayAnim =
            entries.firstOrNull { it.name == name } ?: FADE
    }
}

// ==================== 渐变模型（V5 需求⑤：无 / 线性 / 径向 + 多色标） ====================

/** 背景渐变类型。 */
enum class OverlayGradientKind(val label: String) {
    /** 无渐变（纯色，取色标 0 号或 [OverlayBoxStyle.bgSolidColor]）。 */
    NONE("无"),

    /** 线性渐变（方向由 [GradientSpec.angleDeg] 决定，0 = 左→右）。 */
    LINEAR("线性"),

    /** 径向渐变（中心固定为气泡中心，半径比例由 [GradientSpec.radiusRatio] 决定）。 */
    RADIAL("径向"),
    ;

    companion object {
        val ALL: List<OverlayGradientKind> = entries.toList()

        /** 按枚举名解析（JSON 存名），未知值回落 [NONE]（宁可纯色也不报错）。 */
        fun of(name: String?): OverlayGradientKind =
            entries.firstOrNull { it.name == name } ?: NONE
    }
}

/** 一个渐变色标：位置 0..1 + ARGB 颜色。 */
@Serializable
data class GradientStop(
    /** 在渐变轴上的位置（0=起点/圆心，1=终点/边缘）。 */
    val position: Float = 0f,
    val color: Long = 0xFF000000L,
)

/**
 * 渐变配置（V5 需求⑤：多色标 2-4 条 + 线性角度 / 径向半径比例可调）。
 *
 * - [kind] = NONE 时 [stops] 无意义（渲染层取纯色）；
 * - [stops] 至少 2 条才有渐变意义；渲染层会按 [GradientStop.position] 排序后插值；
 * - [angleDeg] 仅 LINEAR 生效（度，0 = 左→右，顺时针）；[radiusRatio] 仅 RADIAL 生效
 *   （0.1~1.0，相对「中心到最远角」的距离比例，越小光斑越集中）。
 */
@Serializable
data class GradientSpec(
    val kind: String = OverlayGradientKind.NONE.name,
    /** 渐变色标（2-4 条；不足 2 条时渲染层按纯色处理）。 */
    val stops: List<GradientStop> = emptyList(),
    /** 线性渐变角度（度，0 = 左→右）。 */
    val angleDeg: Float = 0f,
    /** 径向渐变半径比例（0.1~1.0，相对中心到最远角）。 */
    val radiusRatio: Float = 1f,
) {
    val kindEnum: OverlayGradientKind get() = OverlayGradientKind.of(kind)

    /** 是否是可渲染的渐变（类型非 NONE 且色标 ≥ 2 条）。 */
    val isRenderable: Boolean
        get() = kindEnum != OverlayGradientKind.NONE && stops.size >= 2

    companion object {
        /** UI 侧添加新色标的数量上限（需求：至少支持 2-4 条）。 */
        const val MAX_STOPS = 4
    }
}

// ==================== 弹幕形态（V5 需求③） ====================

/** 弹幕形态：贯穿式（默认，不停留滚过）/ 定位式（九宫格定位 + 淡入 + 停留）。 */
enum class DanmakuMode(val label: String) {
    /** 贯穿式：从屏幕右缘滚入、**不停留**、直接滚出左缘；速度手填 px/秒。 */
    THROUGH("贯穿式"),

    /** 定位式：按九宫格 + X/Y 偏移落位，淡入出现、停留后淡出，多条逐条出现。 */
    POSITIONED("定位式"),

    ;

    companion object {
        val ALL: List<DanmakuMode> = entries.toList()

        /** 按枚举名解析，未知值回落 [THROUGH]（默认形态）。 */
        fun of(name: String?): DanmakuMode =
            entries.firstOrNull { it.name == name } ?: THROUGH
    }
}

/** 定位式弹幕的堆叠方向：新条从上到下排 / 从下往上排。 */
enum class DanmakuStackDirection(val label: String) {
    /** 从上到下：第 1 条在基准位置，后续条依次**向下**排。 */
    TOP_DOWN("从上到下"),

    /** 从下到上：第 1 条在基准位置，后续条依次**向上**排。 */
    BOTTOM_UP("从下到上"),
    ;

    companion object {
        val ALL: List<DanmakuStackDirection> = entries.toList()

        /** 按枚举名解析，未知值回落 [TOP_DOWN]。 */
        fun of(name: String?): DanmakuStackDirection =
            entries.firstOrNull { it.name == name } ?: TOP_DOWN
    }
}

// ==================== 子模型 ====================

/**
 * 文字样式。
 *
 * [align] 为九宫格：文字块在气泡内的落位（气泡比文字大时才看得出差别）。
 * [strokeWidth] > 0 时按描边渲染（Compose 侧用多层偏移绘制模拟，无 Paint 依赖）。
 * [marquee] = true 时单行超长自动横向滚动（取件码场景常用）。
 */
@Serializable
data class OverlayTextStyle(
    val sizeSp: Float = 14f,
    val letterSpacingSp: Float = 0f,
    val lineHeightSp: Float = 20f,
    val bold: Boolean = false,
    val italic: Boolean = false,
    /** 主色（ARGB Long）。 */
    val color: Long = 0xFFFFFFFFL,
    val align: String = OverlayAlign.CENTER.name,
    /** 0 = 不限（自适应）。 */
    val maxLines: Int = 0,
    val marquee: Boolean = false,
    val shadowRadius: Float = 0f,
    val shadowDx: Float = 0f,
    val shadowDy: Float = 0f,
    val shadowColor: Long = 0x99000000L,
    val strokeWidth: Float = 0f,
    val strokeColor: Long = 0xFF000000L,
) {
    val alignEnum: OverlayAlign get() = OverlayAlign.of(align)
}

/**
 * 气泡（外框）样式。
 *
 * 背景（V5 需求⑤）：纯色由 [bgSolidColor] 表示；渐变由 [bgGradient] 表示
 * （类型 无/线性/径向 + 多色标 + 线性角度 / 径向半径比例，见 [GradientSpec]）。
 * [bgAlpha] 为整体不透明度（0-1），与颜色自身的 alpha **相乘**（Compose 侧 `copy(alpha = ...)`）。
 * 四角圆角 / 四边内间距均**独立可调**（索引顺序：0=左上 1=右上 2=右下 3=左下）。
 */
@Serializable
data class OverlayBoxStyle(
    /** 0 = 自适应内容宽度。 */
    val widthDp: Float = 0f,
    /** 0 = 自适应内容高度。 */
    val heightDp: Float = 0f,
    /**
     * 纯色背景（ARGB Long）。
     *
     * V5 前该字段叫 `bgStart`（兼作线性渐变起点色）；渐变结构重构后纯色语义独立成
     * 本字段。旧 JSON 的 `bgStart`/`bgEnd`/`bgAngle` 由 RuleJsonMigrator 迁移到
     * [bgSolidColor] + [bgGradient]。
     */
    val bgSolidColor: Long = 0xE61A1B1EL,
    /** 渐变配置；[GradientSpec.isRenderable] 为 false 时按纯色渲染。 */
    val bgGradient: GradientSpec = GradientSpec(),
    val bgAlpha: Float = 1f,
    val cornerTopStart: Float = 14f,
    val cornerTopEnd: Float = 14f,
    val cornerBottomEnd: Float = 14f,
    val cornerBottomStart: Float = 14f,
    val borderWidth: Float = 0f,
    val borderColor: Long = 0x59FFFFFFL,
    val padStart: Float = 12f,
    val padTop: Float = 8f,
    val padEnd: Float = 12f,
    val padBottom: Float = 8f,
    val alpha: Float = 1f,
    /**
     * 边缘羽化宽度（dp，0 = 关闭；V6 需求④）。
     *
     * 气泡四边各 [edgeFeatherDp] 宽的 alpha 从 0 渐变到 1，使边缘（**含渐变背景与
     * 纯色背景**——本字段不局限渐变）柔和淡出，叠在其他 App 上时不生硬。
     * 渲染层用离屏合成 + DstIn 渐变遮罩实现（见 OverlayRender.featherMask）。
     */
    val edgeFeatherDp: Float = 0f,
) {
    val corners: List<Float>
        get() = listOf(cornerTopStart, cornerTopEnd, cornerBottomEnd, cornerBottomStart)

    val paddings: List<Float> get() = listOf(padStart, padTop, padEnd, padBottom)

    /** 是否呈现渐变（可渲染的渐变配置）。 */
    val isGradient: Boolean get() = bgGradient.isRenderable

    /** 渐变失效（NONE 或色标不足 2 条）时的回落色：优先色标 0，其次纯色字段。 */
    val bgFallbackColor: Long
        get() = bgGradient.stops.firstOrNull()?.color ?: bgSolidColor
}

// ==================== 弹幕 ====================

/**
 * 弹幕样式（V5 需求③：双形态）。
 *
 * 两种形态（[DanmakuMode]，[mode] 字段）：
 * - **贯穿式 THROUGH（默认）**：从屏幕右缘滚入、**不停留**、直接滚出左缘；
 *   滚动速度 [speedPxps] 手填具体数值（px/秒），滚屏时长 = (屏宽+气泡宽)/速度；
 *   多条按 FIFO 排队、同屏最多 [maxConcurrent] 条、轨道距顶 [topMarginDp] + 间距
 *   [trackGapDp]（沿用 V4 的轨道语义）。
 * - **定位式 POSITIONED**：按九宫格 [stackAlign] + [offsetXDp]/[offsetYDp] 落位，
 *   淡入出现、停留 [displaySeconds] 秒后淡出；多条弹幕逐条依次出现、按
 *   [stackDirection] 方向堆叠（第 i 条相对基准偏移 i×(条目高+[stackGapDp])）、
 *   同屏最多 [maxConcurrent] 条（满了排队等位）。
 *
 * 文案模板（[titleTemplate] / [textTemplate]）：与「替换」动作同一套 Placeholders
 * 占位符（四组 + extras 大小写敏感）；**留空 = 用原通知标题/正文**。渲染时机在
 * 动作执行时（ActionExecutor 拿得到 snapshot.extras）。
 *
 * [separator] 为「标题 / 内容」之间的分隔符（默认 ` · `，可改任意字符串）。
 * [anim] 保留 V4 字段用于向后兼容（旧 JSON 里有值，decode 不丢）；定位式淡入为
 * 固定语义，贯穿式入场即滚入，均不再读 [anim]。
 */
@Serializable
data class DanmakuStyle(
    val text: OverlayTextStyle = OverlayTextStyle(),
    val box: OverlayBoxStyle = OverlayBoxStyle(),
    val anim: String = OverlayAnim.SLIDE_LEFT.name,
    /**
     * V8：定位式出现动画。
     *
     * 默认 **FADE** 而不是 NONE —— `NONE` 在渲染层表示「直接出现（无动画）」，
     * 若默认 NONE，所有老规则的定位式弹幕升级后会从原来的淡入变成**硬闪出现**。
     * 默认 FADE 才能「老规则零观感变化 + NONE 真正表示无动画」两者兼得。
     */
    val animIn: OverlayAnim = OverlayAnim.FADE,
    /**
     * V8：定位式消失动画。
     *
     * V9.3 语义纠偏：`NONE` = **真的没有动画**（硬切，立即消失），不再隐式淡出 260ms。
     * 旧实现把 NONE 当成「沿用旧的纯淡出」，于是界面上选「无」却看到淡入淡出——
     * 这正是用户反馈要修的点。要渐隐请显式选 [OverlayAnim.FADE]。
     */
    val animOut: OverlayAnim = OverlayAnim.NONE,
    /** V8：贯穿式弹幕进出屏幕边缘时是否淡入淡出（默认关，等于旧行为）。 */
    val edgeFade: Boolean = false,
    /** V9：定位式出现动画时长（ms，100..1000）。默认 280 = V5 定稿的淡入时长。 */
    val animInMs: Int = 280,
    /** V9：定位式消失动画时长（ms，100..1000）。默认 240 = V8 的退场时长。 */
    val animOutMs: Int = 240,
    /** 弹幕形态（贯穿式 / 定位式），默认贯穿式。 */
    val mode: String = DanmakuMode.THROUGH.name,
    /**
     * 贯穿式滚动速度（**px/秒**，V5 起；UI 手填具体数值）。
     *
     * 注意：V4 的 `speedDps`（dp/秒）字段已删除——dp/px 在 dp≈3 的屏幕上差 3 倍，
     * 无法无损换算，旧值由 RuleJsonMigrator 直接弃用、回落本默认值。
     */
    val speedPxps: Float = 420f,
    /** 定位式停留秒数（贯穿式不停留，忽略本字段）。 */
    val displaySeconds: Float = 6f,
    /** 同屏最多条数（贯穿式 = 轨道数；定位式 = 堆叠条数）。 */
    val maxConcurrent: Int = 3,
    val showIcon: Boolean = true,
    val showTitle: Boolean = true,
    val separator: String = " · ",
    /** 贯穿式轨道整体距屏幕顶部的距离（避开状态栏/挖孔区域）。 */
    val topMarginDp: Float = 96f,
    /** 贯穿式轨道间距（dp）。 */
    val trackGapDp: Float = 8f,
    /** 定位式落位九宫格（基准锚点）。 */
    val stackAlign: String = OverlayAlign.TOP_CENTER.name,
    /** 定位式水平偏移（dp，相对基准锚点，向屏幕中线方向为正）。 */
    val offsetXDp: Float = 0f,
    /** 定位式垂直偏移（dp，相对基准锚点，向屏幕中线方向为正）。 */
    val offsetYDp: Float = 120f,
    /** 定位式堆叠方向（从上到下 / 从下到上）。 */
    val stackDirection: String = DanmakuStackDirection.TOP_DOWN.name,
    /** 定位式相邻条间距（dp）。 */
    val stackGapDp: Float = 8f,
    /** 标题模板（留空 = 原通知标题）。 */
    val titleTemplate: String = "",
    /** 正文模板（留空 = 原通知正文）。 */
    val textTemplate: String = "",

) {
    val animEnum: OverlayAnim get() = OverlayAnim.of(anim)

    val modeEnum: DanmakuMode get() = DanmakuMode.of(mode)

    val stackAlignEnum: OverlayAlign get() = OverlayAlign.of(stackAlign)

    val stackDirectionEnum: DanmakuStackDirection get() = DanmakuStackDirection.of(stackDirection)


    companion object {
        fun default(): DanmakuStyle = DanmakuStyle()
    }
}

// ==================== 自定义位置悬浮 ====================

/**
 * 悬浮窗样式（需求 7-④）。
 *
 * 语义：命中通知后在**屏幕指定位置**显示一张卡片，[displaySeconds] 秒后自动消失；
 * `displaySeconds = 0` 表示**常驻**（直到下一条命中替换它，或用户手动关闭）。
 *
 * [screenAlign] 决定停靠角落，[offsetTopDp] / [offsetEndDp] 为距屏幕顶部 / 右侧的额外偏移
 * （右下停靠时 [offsetEndDp] 即距右边，左上停靠时语义为距最近的对应边，由渲染层按对齐解释）。
 */
@Serializable
data class FloatStyle(
    val text: OverlayTextStyle = OverlayTextStyle(),
    val box: OverlayBoxStyle = OverlayBoxStyle(
        widthDp = 0f,
        heightDp = 0f,
        cornerTopStart = 16f,
        cornerTopEnd = 16f,
        cornerBottomEnd = 16f,
        cornerBottomStart = 16f,
    ),
    val anim: String = OverlayAnim.FADE.name,
    /**
     * V8：消失动画（独立于入场）。
     *
     * V9.3 语义纠偏：`NONE` = **真的没有动画**（硬切，窗口立即摘除），不再「把入场进度
     * 倒放」——旧实现让界面上选「无」的用户依然看到淡入淡出/滑出，与标签不符。
     * 要渐隐/滑出请显式选 `FADE` / `SLIDE_*` / `SCALE` / `BOUNCE`。
     *
     * 注意本字段用枚举而非 String + xxxEnum 访问器：它与 [anim]、[mode] 的
     * String 约定不同，是 V8 新增字段的直接类型（JSON 形状与 String 写法完全一致，
     * 枚举按 name 序列化）。渲染层按 `when (style.animOut)` 消费。
     */
    val animOut: OverlayAnim = OverlayAnim.NONE,
    /** V9：出现动画时长（ms，100..1000）。默认 260 = V4 定稿的入场时长。 */
    val animInMs: Int = 260,
    /**
     * V9：消失动画时长（ms，100..1000）。默认 240 = V8 的退场时长。
     *
     * `OverlayController` 的退场超时兜底按 `animOutMs + 60` 计算（见 `requestExit`），
     * 因此调大本值不会被 300ms 的超时把动画掐断。
     */
    val animOutMs: Int = 240,
    val screenAlign: String = OverlayAlign.TOP_END.name,
    val offsetTopDp: Float = 120f,
    val offsetEndDp: Float = 12f,
    /** 停留秒数；0 = 常驻。 */
    val displaySeconds: Float = 8f,
    val showIcon: Boolean = true,
    val showTitle: Boolean = true,
    val separator: String = " · ",
    /** 标题模板（留空 = 原通知标题；与弹幕同一套 Placeholders）。 */
    val titleTemplate: String = "",
    /** 正文模板（留空 = 原通知正文；与弹幕同一套 Placeholders）。 */
    val textTemplate: String = "",
    /**
     * 点击卡片后的行为：true = 跳转原通知（contentIntent），false = 仅关闭。
     *
     * **当前未生效**：叠加窗口带 `FLAG_NOT_TOUCHABLE`（点击穿透，避免挡住下方 App），
     * 窗口收不到触摸事件，故本字段为「预留」。若将来改为可触摸窗口再启用。
     * 配置页不暴露此项。
     */
    val clickToOpen: Boolean = true,

    // —— V7 需求 8：卡片高度与滚动（全部默认 = V6 行为，老规则 decode 后行为不变）——

    /**
     * V7 需求 8：卡片**内容区**最大高度（dp，0 = 不限）。
     * 条目多了不能撑满屏幕——超出时内容区内部滚动（需 [contentScrollable] = true）。
     */
    val maxHeightDp: Float = 0f,

    /**
     * V7 需求 8：内容区是否可滚动（仅 maxHeightDp > 0 且窗口可触摸时生效）。
     * **代价**：启用后卡片窗口必须可触摸（去掉 FLAG_NOT_TOUCHABLE），见架构 §3.10。
     */
    val contentScrollable: Boolean = false,

    /**
     * V7：窗口是否可触摸。仅由「条件卡片 / 聚合卡片」的渲染路径置 true，
     * 业务单条悬浮（V6 路径）恒 false（点击穿透，避免挡住下方 App）。
     * 配置页**不暴露**此项（由功能推导，不由用户手调）。
     */
    val touchable: Boolean = false,
) {
    val animEnum: OverlayAnim get() = OverlayAnim.of(anim)

    val screenAlignEnum: OverlayAlign get() = OverlayAlign.of(screenAlign)

    companion object {
        fun default(): FloatStyle = FloatStyle()
    }
}

// ==================== 屏幕显示载荷 ====================

/**
 * 一次屏幕显示的内容载荷（由管线在命中时构造，交给 OverlayController 渲染）。
 *
 * 非序列化：只在进程内存活，不落库。
 */
data class OverlayPayload(
    val ruleId: Long,
    val packageName: String,
    val appName: String,
    val title: String,
    val text: String,
    val postTime: Long = System.currentTimeMillis(),
)
