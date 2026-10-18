package com.local.notificationfilter.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.local.notificationfilter.ui.theme.ActionKind
import com.local.notificationfilter.ui.theme.actionColorsOf

/**
 * 趋势图数据点（一个柱组：过滤 + 放行）。
 *
 * [label] 为 X 轴文案（按天 = 「MM-DD」，按周 = 周一的「MM-DD」）。
 */
data class TrendPoint(
    val label: String,
    val filtered: Int,
    val passed: Int,
) {
    val total: Int get() = filtered + passed
}

/**
 * 双系列分组柱状图（P11 趋势区）：每组两柱，左=过滤、右=放行。
 *
 * 系列色为五动作语义色（§6.2 / §6.4）：过滤=[ActionKind.BLOCK] 语义色、
 * 放行=[ActionKind.PASS] 语义色，随主题自动切换；坐标轴 / 网格 / 文字颜色
 * 均走 MaterialTheme.colorScheme，浅深两套可读。
 *
 * 图例为「色点 + 文字」双通道（禁止仅靠颜色传达，§6.4）。
 * 全零时不画柱、仅保留基线；标签行与图例由本组件一并渲染，
 * X 轴标签用等宽列布局，保证与柱组中心对齐。
 */
@Composable
fun TrendChart(
    points: List<TrendPoint>,
    modifier: Modifier = Modifier,
    chartHeight: Dp = 150.dp,
) {
    if (points.isEmpty()) return

    val filteredColor = actionColorsOf(ActionKind.BLOCK).accent
    val passedColor = actionColorsOf(ActionKind.PASS).accent
    val baselineColor = MaterialTheme.colorScheme.outlineVariant
    val gridColor = MaterialTheme.colorScheme.outlineVariant

    Column(modifier = modifier.fillMaxWidth()) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(chartHeight),
        ) {
            val groupCount = points.size
            val groupWidth = size.width / groupCount
            val gap = 3.dp.toPx()
            val barWidth = (groupWidth * 0.26f).coerceAtMost(18.dp.toPx())
            val maxTotal = points.maxOf { it.total }.coerceAtLeast(1)
            val radius = CornerRadius(3.dp.toPx(), 3.dp.toPx())

            // 横向参考网格（1/3、2/3 处，随主题的弱化色，先画后压柱）
            listOf(1f / 3f, 2f / 3f).forEach { fraction ->
                drawLine(
                    color = gridColor,
                    start = Offset(0f, size.height * fraction),
                    end = Offset(size.width, size.height * fraction),
                    strokeWidth = 1.dp.toPx(),
                    alpha = 0.6f,
                )
            }

            points.forEachIndexed { index, point ->
                val groupCenter = groupWidth * index + groupWidth / 2f
                if (point.filtered > 0) {
                    val barHeight = size.height * point.filtered / maxTotal
                    drawRoundRect(
                        color = filteredColor,
                        topLeft = Offset(groupCenter - barWidth - gap / 2f, size.height - barHeight),
                        size = Size(barWidth, barHeight),
                        cornerRadius = radius,
                    )
                }
                if (point.passed > 0) {
                    val barHeight = size.height * point.passed / maxTotal
                    drawRoundRect(
                        color = passedColor,
                        topLeft = Offset(groupCenter + gap / 2f, size.height - barHeight),
                        size = Size(barWidth, barHeight),
                        cornerRadius = radius,
                    )
                }
            }

            // 基线
            drawLine(
                color = baselineColor,
                start = Offset(0f, size.height),
                end = Offset(size.width, size.height),
                strokeWidth = 1.dp.toPx(),
            )
        }

        Spacer(Modifier.height(6.dp))

        // X 轴标签：等分列与柱组中心对齐
        Row(Modifier.fillMaxWidth()) {
            points.forEach { point ->
                Text(
                    text = point.label,
                    style = MaterialTheme.typography.labelSmall,
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        // 图例：色点 + 文字（双通道传达，禁止仅靠颜色）
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            LegendDot(color = filteredColor, text = "过滤")
            LegendDot(color = passedColor, text = "放行")
        }
    }
}

@Composable
private fun LegendDot(color: Color, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(color),
        )
        Spacer(Modifier.size(4.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
