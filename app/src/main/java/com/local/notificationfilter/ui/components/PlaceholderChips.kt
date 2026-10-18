package com.local.notificationfilter.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AssistChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.local.notificationfilter.core.model.Placeholders

/**
 * 占位符 chips（V2T07，需求③；规则编辑页替换·自定义文案双模板的插入辅助）。
 *
 * 按 [Placeholders.Group] 四组展示，点击 chip → [onInsert] 回调 token 原文
 * （如 `{title}` / `{extras.android.title}`），由调用方插入到当前聚焦模板的光标处。
 *
 * Extras 组说明：`{extras.<键名>}` 不是固定枚举，本组 chips 由调用方把**规则已选的
 * extras 字段**（[com.local.notificationfilter.core.model.FieldRef.Extras]）拼成
 * token 经 [extrasTokens] 传入；为空时展示语法提示而非空 chips。键名大小写敏感、
 * 通知中不存在该键按空串——判定口径见 [Placeholders] 类注释（R12）。
 *
 * V7 需求 8 追加第 5 组「汇总列表」：`{items1}`..`{items9}` 同样是**动态** token
 * （只有配了提取器的列表号才有意义），由调用方按当前规则实际的提取器列表号拼成
 * token 经 [itemsTokens] 传入——与 [extrasTokens] 完全同一机制。
 *
 * 追加第 6 组「即时提取」：`{re1}`..`{re9}` —— 用规则第 N 个提取器对**当前这一条通知**
 * 跑正则、重新拼好显示（与 `{itemsN}` 的"跨通知累积列表"不同，见 [Placeholders] 类注释）。
 * 同样是动态 token，由调用方经 [extractTokens] 传入 `token 原文 → 显示名（提取器名）`；
 * 为空时整组不显示（不留空分组）。可直接用 [Placeholders.extractTokens] 生成。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PlaceholderChips(
    onInsert: (String) -> Unit,
    modifier: Modifier = Modifier,
    extrasTokens: List<String> = emptyList(),
    itemsTokens: List<String> = emptyList(),
    extractTokens: List<Pair<String, String>> = emptyList(),
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = "占位符（点击插入光标处；固定名大小写不敏感，未识别的 {xxx} 渲染时按空串）",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(6.dp))
        Placeholders.Group.entries.forEach { group ->
            val items = Placeholders.ITEMS.filter { it.group == group }
            val hasExtrasChips = group == Placeholders.Group.EXTRAS && extrasTokens.isNotEmpty()
            if (items.isEmpty() && !hasExtrasChips) {
                // Extras 组且当前规则未选 extras 字段：只留语法提示，不放空 chips
                if (group == Placeholders.Group.EXTRAS) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "「${group.label}」当前规则未选择；写法 {extras.键名}" +
                            "（键名大小写敏感，通知中没有该键时按空串处理）",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                return@forEach
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = group.label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(2.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (group == Placeholders.Group.EXTRAS) {
                    extrasTokens.forEach { token ->
                        AssistChip(
                            onClick = { onInsert(token) },
                            label = { Text(token) },
                        )
                    }
                } else {
                    items.forEach { item ->
                        AssistChip(
                            onClick = { onInsert(item.token) },
                            label = { Text(item.token) },
                        )
                    }
                }
            }
            if (group == Placeholders.Group.EXTRAS) {
                Text(
                    text = "键名大小写敏感；通知中没有该键时按空串处理",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // —— V7 需求 8：第 5 组「汇总列表」（{items1}..{items9}，动态 token）——
        if (itemsTokens.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = "汇总列表",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(2.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                itemsTokens.forEach { token ->
                    AssistChip(
                        onClick = { onInsert(token) },
                        label = { Text(token) },
                    )
                }
            }
            Text(
                text = "列表号与「内容提取」里每个提取器左上角的列表 N 对应；列表为空时按空串处理",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // —— 第 6 组「即时提取」（{re1}..{re9}，动态 token：token → 提取器名）——
        if (extractTokens.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = "即时提取（当前这条通知）",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(2.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                extractTokens.forEach { (token, label) ->
                    AssistChip(
                        onClick = { onInsert(token) },
                        label = { Text(label) },
                    )
                }
            }
            Text(
                text = "用该提取器的正则从当前这条通知里提取内容后重新显示；" +
                    "多个结果按条目分隔符连接，没匹配到按空串处理",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * V9.6：占位符**常驻速查卡**（规则编辑页固定位置，始终可见）。
 *
 * 为什么需要：原来的 [PlaceholderChips] 只出现在「动作 → 替换 → 自定义文案」里面，
 * 用户不开替换动作、或替换用默认模式时**完全看不到占位符**（真机反馈原话：
 * 「根本看不到占位符」）。本卡不依赖任何动作开关，把全部 token 平铺出来，
 * 点一下复制到剪贴板，粘贴到弹幕 / 悬浮 / 替换标题正文 / 朗读文案里都能用。
 *
 * 与 [PlaceholderChips] 的分工：那个是「带着光标插入」（要聚焦某个输入框），
 * 这个是「随时查、随时复制」——不需要任何输入框处于聚焦态。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PlaceholderReferenceCard(
    onCopy: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    androidx.compose.material3.Card(
        modifier = modifier.fillMaxWidth(),
        colors = androidx.compose.material3.CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = "可用占位符（点一下复制，粘到任意模板里）",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
            )
            Spacer(Modifier.height(8.dp))
            // 不分小组平铺：这里的目标是「一眼看全、随手复制」，
            // 分 5 组加小标题会把卡片撑得很高，反而不利于扫视。
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Placeholders.ITEMS.forEach { item ->
                    AssistChip(
                        onClick = { onCopy(item.token) },
                        label = { Text(item.token) },
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = "其他写法：{extras.键名}（任意 extras 字段，大小写敏感）" +
                    " · {items1}~{items9}（跨多条通知累积）" +
                    " · {re1}~{re9}（对当前这条通知即时提取）",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                // 「我填 {re1} 会说没有这个东西」——原因是它需要先在⑤配提取器。
                // 这个前提不讲清，用户只会以为功能不存在。
                text = "注意 {re1}~{re9} 与 {items1}~{items9} 都要先在「⑤ 内容提取与汇总」里" +
                    "打开开关并添加提取器（启用 + 正则非空 + 条数上限 > 0），" +
                    "第 N 个提取器才对应 {reN}；没配好时它们会被判为未识别占位符。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                // 「弹幕和悬浮窗里内容要怎么提取显示」——直接写出路径，
                // 否则用户会去这张卡里找输入框（它只负责复制，不负责填）。
                text = "弹幕 / 悬浮的内容模板在：④ 动作 → 打开「弹幕」或「悬浮」开关 → " +
                    "点「弹幕样式设置」/「悬浮样式设置」→ 对话框里「内容」下的两个输入框" +
                    "（标题 / 正文）。两个都留空时自动回落原通知标题与正文。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
