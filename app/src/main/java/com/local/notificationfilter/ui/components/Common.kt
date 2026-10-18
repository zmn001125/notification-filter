package com.local.notificationfilter.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.local.notificationfilter.core.model.ActionType
import com.local.notificationfilter.core.model.NotificationRecord
import com.local.notificationfilter.core.model.ResultKind
import com.local.notificationfilter.core.model.SnoozeCalculator
import com.local.notificationfilter.core.model.isFailureTrace
import com.local.notificationfilter.ui.theme.ActionColors
import com.local.notificationfilter.ui.theme.ActionKind
import com.local.notificationfilter.ui.theme.actionColorsOf
import com.local.notificationfilter.ui.theme.defaultIcon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 结果标签样式：对齐线框稿 .tag.solid / .gray / .dash / .dot / 默认描边。 */
enum class TagStyle { SOLID, GRAY, DASH, DOT, OUTLINE }

/**
 * 边框样式 → 五动作语义种类映射（§6.2 三重编码之「样式」维）：
 * 实心=消除、浅蓝底=替换、虚线=推迟、点线=朗读、描边=放行。
 */
private fun TagStyle.toActionKind(): ActionKind = when (this) {
    TagStyle.SOLID -> ActionKind.BLOCK
    TagStyle.GRAY -> ActionKind.REPLACE
    TagStyle.DASH -> ActionKind.SNOOZE
    TagStyle.DOT -> ActionKind.READ
    TagStyle.OUTLINE -> ActionKind.PASS
}

/**
 * 结果标签（V2 三重编码：语义色 × V1 边框样式 × 动作图标，§6.2）：
 * 消除=实心、替换=浅蓝底、推迟=虚线、朗读=点线、放行=白底描边。
 *
 * 颜色默认按 [TagStyle.toActionKind] 从 [actionColorsOf] 取；
 * 传 [actionColors] 可显式覆盖（如「已恢复」中性色，不占五行动义色）。
 * 五动作标签默认带 12dp 动作小图标；显式传 [actionColors] 时不套默认图标。
 *
 * V8 需求 6：屏幕显示类（弹幕 / 悬浮）在样式上仍是描边，但**语义色必须走主题主色**——
 * 用 [actionKind] 显式指定语义种类，避免「描边 → 放行 → 绿」这条兜底链把
 * 「有屏幕显示效果」和「只是放行」染成同一个绿色。
 */
@Composable
fun ResultTag(
    text: String,
    style: TagStyle,
    icon: ImageVector? = null,
    modifier: Modifier = Modifier,
    actionColors: ActionColors? = null,
    actionKind: ActionKind? = null,
) {
    val kind = actionKind ?: style.toActionKind()
    // 「未命中」是**否定结果**（没有任何规则命中），与「放行」（正常放行，绿 + 对勾）语义相反，
    // 因此必须绕开 `OUTLINE → PASS` 这条绿色兜底链，走中性灰 + 错号（V9 需求 3）。
    val missTag = actionKind == null && actionColors == null && isMissTag(text)
    val colors = actionColors ?: if (missTag) neutralTagColors() else actionColorsOf(kind)
    val tagIcon: ImageVector? = icon ?: when {
        // 显式传了语义色（如「已恢复」中性色）→ 不套默认动作图标（V1 行为不变）
        actionColors != null -> null
        missTag -> Icons.Filled.Close
        else -> kind.defaultIcon()
    }

    // 容器 / 文字 / 边框 / 图标四色按样式分支解析（V1 分支结构不变，仅换色源）
    val bg: Color
    val fg: Color
    val border: Color
    val iconTint: Color
    when (style) {
        TagStyle.SOLID -> {
            bg = colors.container
            fg = colors.onContainer
            border = colors.accent
            iconTint = colors.onContainer
        }
        TagStyle.GRAY -> {
            bg = colors.container
            fg = colors.onContainer
            border = colors.container
            iconTint = colors.onContainer
        }
        TagStyle.DASH, TagStyle.DOT, TagStyle.OUTLINE -> {
            bg = Color.Transparent
            fg = colors.text
            border = colors.accent
            iconTint = colors.accent
        }
    }

    Row(
        modifier = modifier
            .then(
                if (style == TagStyle.DASH || style == TagStyle.DOT || style == TagStyle.OUTLINE) {
                    Modifier.dashedBorder(style, border)
                } else {
                    Modifier
                },
            )
            .background(bg, TagShape)
            // 高度贴近文字行高（V9 需求 3：原来 7dp/2dp 内边距 + 10sp 显得比文字高出一圈）。
            // 保留 1dp 纵向内边距让描边不贴字，再以 heightIn 兜到 20dp 保证触感与对齐稳定。
            .heightIn(min = TagMinHeight)
            .padding(horizontal = 6.dp, vertical = 1.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (tagIcon != null) {
            Icon(
                imageVector = tagIcon,
                contentDescription = null,
                // 「未命中」的图标与文字同为中性灰（描边另用 outline，深一档做区分）
                tint = if (missTag) fg else iconTint,
                modifier = Modifier.size(12.dp),
            )
            Spacer(Modifier.size(2.dp))
        }
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            lineHeight = 14.sp,
            fontWeight = FontWeight.Medium,
            color = fg,
        )
    }
}

/** 标签圆角与最小高度（V9 需求 3 起统一为紧凑尺寸，[drawTagBorder] 同步使用）。 */
private val TagShape = RoundedCornerShape(4.dp)
private val TagCornerRadius: Dp = 4.dp
private val TagMinHeight: Dp = 20.dp

/** 「未命中」标签文案：与 RuleTestPanel 的展示文案保持一致（唯一判定依据）。 */
private const val MISS_TAG_TEXT = "未命中"

/**
 * 是否渲染为「未命中」标签（V9 需求 3）。
 *
 * 判定收紧到「调用方没有显式给语义色 / 语义种类，且文案就是未命中」：
 * - 调用点 `ResultTag(text = "未命中", style = TagStyle.OUTLINE)` 无需改动；
 * - 「放行」（同为 OUTLINE）与「屏幕显示」（显式传了 actionKind）不受影响，
 *   仍是各自的绿色 / 主题主色 + 对勾；
 * - 显式传了 `actionColors` 的调用（如「已恢复」中性色）优先，不会被改写。
 */
private fun isMissTag(text: String): Boolean = text.trim() == MISS_TAG_TEXT

/**
 * 「未命中」专用中性色（V9 需求 3）：文字 / 图标取 `MaterialTheme.colorScheme.onSurfaceVariant` 灰，
 * 描边取 `outline`，底色透明（保持描边样式，不退化成纯文字）。
 *
 * 为什么不复用 `ActionKind.PASS` 的语义词：未命中表示「没命中任何规则」，是**否定结果**；
 * 放行是「命中后决定放行」，是**正常结果**。两者含义相反，颜色必须分开。
 * 语义色表 ActionColors.kt 只读，本文件内用 MaterialTheme 角色解决，不新增色值。
 */
@Composable
private fun neutralTagColors(): ActionColors = ActionColors(
    accent = MaterialTheme.colorScheme.outline,
    container = Color.Transparent,
    onContainer = MaterialTheme.colorScheme.onSurfaceVariant,
    text = MaterialTheme.colorScheme.onSurfaceVariant,
)

/**
 * 「已恢复」标签专用中性色：维持 V1 虚线样式 + 中性灰文字/描边，
 * 不占五行动义色（§6.2 表注）。与「未命中」共用同一套中性灰，语义上都是「无动作结果」。
 */
@Composable
private fun restoredTagColors(): ActionColors = neutralTagColors()

/**
 * 屏幕显示类结果（[ResultKind.SHOW]）细分到语义种类：
 * 命中里带悬浮就展示悬浮色/图标（画中画），否则按弹幕处理（字幕条）。
 *
 * 两者颜色目前都派生自主题主色（V8 需求 6 的颜色分工表），区分只体现在图标上。
 */
private fun showActionKind(actions: List<ActionType>): ActionKind =
    if (actions.contains(ActionType.FLOAT)) ActionKind.FLOAT else ActionKind.DANMAKU

/** 流水记录的结果标签（含黑名单来源与「已恢复」特例）。 */
@Composable
fun RecordResultTag(record: NotificationRecord, modifier: Modifier = Modifier) {
    val (text, style) = when {
        record.restoredAt != null -> "已恢复" to TagStyle.DASH
        record.blacklistHit && record.result == ResultKind.BLOCK -> "消除" to TagStyle.SOLID
        else -> when (record.result) {
            ResultKind.PASS -> "放行" to TagStyle.OUTLINE
            ResultKind.BLOCK -> "消除" to TagStyle.SOLID
            ResultKind.SNOOZE -> "推迟" to TagStyle.DASH
            ResultKind.REPLACE -> "替换" to TagStyle.GRAY
            ResultKind.READ -> "朗读" to TagStyle.DOT
            ResultKind.SHOW -> "屏幕显示" to TagStyle.OUTLINE
        }
    }
    // 只有「未恢复的屏幕显示」才用主题主色语义（已恢复走中性色，见 restoredTagColors）。
    val showKind = if (record.restoredAt == null && record.result == ResultKind.SHOW) {
        showActionKind(record.executedActions)
    } else {
        null
    }
    ResultTag(
        text = text,
        style = style,
        actionColors = if (record.restoredAt != null) restoredTagColors() else null,
        actionKind = showKind,
        modifier = modifier,
    )
}

/**
 * 单个动作的小标签（详情页 / 规则卡 / 测试结果用）。
 *
 * V8 需求 6：屏幕显示类（弹幕 / 悬浮）样式仍是描边，但语义色显式指定为
 * [ActionKind.DANMAKU] / [ActionKind.FLOAT]（= 主题主色），
 * 不再顺着「描边 → 放行」的兜底映射被染成放行的绿。
 *
 * V11：降级 / 失败留痕（朗读跳过、替换失败、屏幕显示跳过）改用错误色，
 * 否则它们和「放行」长得一模一样——用户完全看不出朗读失败了。
 */
@Composable
fun ActionTag(action: ActionType, modifier: Modifier = Modifier) {
    val style = when (action) {
        ActionType.BLOCK -> TagStyle.SOLID
        ActionType.REPLACE -> TagStyle.GRAY
        ActionType.SNOOZE -> TagStyle.DASH
        ActionType.READ_ALOUD -> TagStyle.DOT
        ActionType.READ_ALOUD_SKIPPED -> TagStyle.OUTLINE
        ActionType.REPLACE_FAILED -> TagStyle.OUTLINE
        // V4 屏幕显示类：弹幕/悬浮统一用描边样式，跳过项用点线以示降级
        ActionType.DANMAKU -> TagStyle.OUTLINE
        ActionType.FLOAT -> TagStyle.OUTLINE
        ActionType.OVERLAY_SKIPPED -> TagStyle.DOT
    }
    val kind = when (action) {
        ActionType.DANMAKU -> ActionKind.DANMAKU
        ActionType.FLOAT -> ActionKind.FLOAT
        // 其余动作的语义种类由样式维唯一决定（§6.2），无需显式指定
        else -> null
    }
    ResultTag(
        text = action.label,
        style = style,
        actionKind = kind,
        // 失败留痕走错误色：与正常的「放行 / 朗读 / 替换」彻底区分
        actionColors = if (action.isFailureTrace()) failureTagColors() else null,
        modifier = modifier,
    )
}

/** 降级 / 失败留痕标签的错误色四件套（V11）。 */
@Composable
private fun failureTagColors(): ActionColors = ActionColors(
    accent = MaterialTheme.colorScheme.error,
    container = MaterialTheme.colorScheme.errorContainer,
    onContainer = MaterialTheme.colorScheme.onErrorContainer,
    text = MaterialTheme.colorScheme.error,
)

/**
 * 一条流水的**多效果**标签（V11）。
 *
 * - `executedActions` 为空 → 回落单标签 [RecordResultTag]（放行 / 已恢复 / 黑名单消除）；
 * - 否则把每个已执行动作渲染成一个 [ActionTag]，最多 [max] 个，超出显示 `+N`。
 *
 * 「已恢复」作为中性前缀标签保留（它是时间态，不是效果）。
 * 黑名单命中的通知 `executedActions` 只有 `BLOCK`，自然只出一个「消除」。
 *
 * 布局刻意用 `Row` 而不是 `FlowRow`：这一行和应用名/时间/规则名挤在同一条
 * `Row` 里，必须保持单行高度，不能换行把列表撑高（详情页用 `FlowRow` 全量展示）。
 */
@Composable
fun RecordEffectTags(
    record: NotificationRecord,
    max: Int,
    modifier: Modifier = Modifier,
) {
    val actions = record.executedActions
    if (actions.isEmpty()) {
        RecordResultTag(record = record, modifier = modifier)
        return
    }
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (record.restoredAt != null) {
            ResultTag(text = "已恢复", style = TagStyle.DASH, actionColors = restoredTagColors())
        }
        val shown = actions.take(max)
        shown.forEach { ActionTag(action = it) }
        val hidden = actions.size - shown.size
        if (hidden > 0) {
            Text(
                text = "+$hidden",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 虚线 / 点线 / 实线描边的圆角框（Compose BorderStroke 不支持虚线，自绘实现）。 */
private fun Modifier.dashedBorder(style: TagStyle, color: Color): Modifier = this.then(
    Modifier.drawTagBorder(style, color),
)

private fun Modifier.drawTagBorder(style: TagStyle, color: Color): Modifier =
    drawWithCache {
        val pathEffect = if (style == TagStyle.DASH) {
            PathEffect.dashPathEffect(floatArrayOf(7f, 5f), 0f)
        } else {
            PathEffect.dashPathEffect(floatArrayOf(1.5f, 5f), 0f)
        }
        val cap = if (style == TagStyle.DOT) StrokeCap.Round else StrokeCap.Butt
        onDrawWithContent {
            drawContent()
            drawRoundRect(
                color = color,
                cornerRadius = CornerRadius(TagCornerRadius.toPx()),
                style = Stroke(
                    width = 1.2.dp.toPx(),
                    pathEffect = pathEffect,
                    cap = cap,
                ),
            )
        }
    }

/** 小节标题。 */
@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier, trailing: (@Composable () -> Unit)? = null) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        trailing?.invoke()
    }
}

/**
 * 空状态。
 *
 * @param icon 可选语义图标（§6.4：无通知=NotificationsOff、无规则=PlaylistAdd、无流水=Inbox）；
 *   V2T09 起各页面调用点才传图标，本波只提供参数与渲染。
 */
@Composable
fun EmptyState(text: String, icon: ImageVector? = null, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxWidth().padding(vertical = 40.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(40.dp),
                )
                Spacer(Modifier.height(8.dp))
            }
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 应用头像（§6.4 / V7-②；V9 改为「原图直出、不裁不垫」）。
 *
 * - [packageName] 非空时经 [AppIconLoader] 在 IO 线程取真实应用图标（四级兜底）；
 * - 有图标时**不裁形、不垫底色**：图标按 [size] 等比完整显示（[ContentScale.Fit]），
 *   自适应图标显示它自己的满幅方图、老图标显示原始形状——形状不统一是用户明确接受的取舍，
 *   换来的是「不要那块自造圆底、固定大小」；
 * - 只有「完全取不到图标、落回首字母」时才给圆底 + `onSurfaceVariant` 字色，
 *   否则首字母在浅色/白色卡片上会糊成一片；
 * - [size] 由调用方决定（选择器 32dp / 统计与黑名单 36dp / 列表与详情 40dp），
 *   视觉层级靠它区分，不做固定 dp；缓存 key 含 `packageName` 与 `sizePx`，三种尺寸独立渲染。
 */
@Composable
fun AppAvatar(
    appName: String,
    packageName: String? = null,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
) {
    val context = LocalContext.current
    val density = LocalDensity.current.density
    val sizePx = with(LocalDensity.current) { size.roundToPx() }

    // 图标解码/绘制走 IO 线程：一屏十几行列表，主线程做 Drawable→Bitmap 会掉帧。
    // key 里带 sizePx：同一包名在不同尺寸下必须各自渲染，否则 32dp 的位图会被拉到 40dp 发虚。
    val icon by produceState<LoadedAppIcon?>(initialValue = null, packageName, sizePx) {
        val pkg = packageName
        value = if (pkg.isNullOrBlank()) {
            null
        } else {
            withContext(Dispatchers.IO) { AppIconLoader.load(context, pkg, sizePx, density) }
        }
    }

    val iconBitmap = icon?.bitmap

    Box(
        modifier = modifier.size(size),
        contentAlignment = Alignment.Center,
    ) {
        if (iconBitmap != null) {
            Image(
                bitmap = iconBitmap,
                contentDescription = appName,
                contentScale = ContentScale.Fit,
                modifier = Modifier.size(size),
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surfaceVariant, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = appName.take(1).ifBlank { "?" },
                    fontSize = (size.value * 0.4).sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** 键值行（详情页元信息区）。 */
@Composable
fun KeyValueRow(key: String, value: String, modifier: Modifier = Modifier) {
    Row(modifier = modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(
            text = key,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(width = 108.dp, height = 18.dp),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
    }
}

/** 区块卡片。 */
@Composable
fun NfCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(modifier = Modifier.padding(12.dp), content = content)
    }
}

/**
 * 图表系列色（§6.2 / §6.4 语义色）：
 * 过滤系列=消除语义色、放行系列=放行语义色，随主题自动切换。
 */
val chartFilteredColor: Color
    @Composable get() = actionColorsOf(ActionKind.BLOCK).accent

val chartPassedColor: Color
    @Composable get() = actionColorsOf(ActionKind.PASS).accent

/**
 * 通知记录列表项（首页最近通知 / 记录页 / 搜索结果共用）。
 *
 * V9 需求 2：三行结构，**标题必须出现**——
 * 1. 应用名（降级为 `labelSmall` 灰字，仍是来源标识）+ 右侧时间 / 结果标签 / 规则名；
 * 2. 标题 `record.titleSummary`（`titleSmall`，单行省略）；
 * 3. 正文 `record.textSummary`（`bodySmall`，两行省略；为空则整行不渲染，不留空行）。
 *
 * 排版细节：
 * - 标题为空但正文非空 → 第二行填「无标题」灰字（而不是把正文挪到标题位），
 *   保证每行标题的位置稳定，扫读时不会串行；
 * - 标题正文都为空 → 只显示应用名 + 一行「（无内容）」灰字。
 */
@Composable
fun NotificationRecordItem(
    record: NotificationRecord,
    now: Long,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val appName = record.appName.ifBlank { record.packageName.orEmpty().ifBlank { "未知应用" } }
    val title = record.titleSummary.trim()
    val body = record.textSummary.trim()
    val hasTitle = title.isNotBlank()
    val hasBody = body.isNotBlank()

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppAvatar(appName = appName, packageName = record.packageName)
        Spacer(Modifier.size(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            // —— 第一行：应用名（来源）+ 时间 / 结果标签 / 规则名（保持原有顺序与样式）——
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = appName,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(Modifier.size(6.dp))
                Text(
                    text = SnoozeCalculator.formatListTime(record.postTime, now),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
                Spacer(Modifier.size(5.dp))
                // V11：一条规则可配多个动作 → 这里最多显示 2 个效果标签，超出折叠成「+N」
                // （点进详情看全量）。不再只显示 classify() 压出来的那一个主结论。
                RecordEffectTags(record = record, max = 2)
                if (!record.ruleName.isNullOrBlank()) {
                    Spacer(Modifier.size(5.dp))
                    Text(
                        text = record.ruleName,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                }
            }
            // —— 第二行：标题（缺失时给占位，保证行位稳定）——
            when {
                hasTitle -> Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                hasBody -> Text(
                    text = "无标题",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    maxLines = 1,
                )
                else -> Text(
                    text = "（无内容）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            // —— 第三行：正文（为空时整行不渲染，不占位）——
            if (hasBody) {
                Text(
                    text = body,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
