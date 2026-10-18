package com.local.notificationfilter.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Checkbox
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.DisposableEffect
import com.local.notificationfilter.core.media.MediaSessionHolder
import com.local.notificationfilter.core.media.MediaSnapshot
import com.local.notificationfilter.service.NfNotificationListener
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.local.notificationfilter.core.model.FieldGroup
import com.local.notificationfilter.core.model.FieldRef
import com.local.notificationfilter.core.model.MatchField
import com.local.notificationfilter.core.model.MetaFieldKind

/**
 * 字段选择器底部弹层（V2T05，架构 §6.5 / PRD 3.2）。
 *
 * 三个分组：常用字段（六文本）/ 通知信息（六元信息）/ Extras 字段
 * （最近出现过列表按频次 + 手动输入完整键名，支持 `filterbox.field.xxx` 深层键名）。
 *
 * 勾选语义：`selected` 中已有 → 已选态；点击未选项 / 已选项统一回调 [onToggle]，
 * 由 ViewModel 判断增删（与编辑页 chips 删除共用同一入口）。
 *
 * @param recentKeysLoading 「最近出现过」列表加载中（空态文案据此区分「加载中 / 流水为空」）
 * @param extrasError 手动输入校验错误文案（非空 / 含空白 / 超 128 字节 / 已选重复），
 *   由 ViewModel 校验后回传，在输入框旁内联展示；成功添加后由本组件自动清空输入框
 *   （观察到输入键进入 selected 即清空，失败保留文本便于修改）。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun FieldPickerSheet(
    selected: Set<FieldRef>,
    recentExtrasKeys: List<Pair<String, String>>,
    onToggle: (FieldRef) -> Unit,
    onAddExtras: (String) -> Unit,
    onDismiss: () -> Unit,
    recentKeysLoading: Boolean = false,
    extrasError: String? = null,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var input by remember { mutableStateOf("") }

    // V10.9：**面板打开期间临时启用媒体采集**，否则用户永远看不到「有什么字段、值是什么」
    // （采集本来只在「已有规则引用 media.*」时开，而用户正是要先进来看才决定选不选 → 鸡生蛋）。
    // 离开时交给监听服务按当前规则重新评估：没有任何规则引用就关掉，不留常驻开销。
    LaunchedEffect(Unit) {
        // 顺序很重要：先开闸，再主动读一次。
        // 只开闸不读 → 要等下一次 metadata 变化才有数据，
        // 歌曲暂停时甚至永远空白，看起来就像"没抓到"。
        MediaSessionHolder.setEnabled(true)
        NfNotificationListener.instance?.refreshMediaNow()
    }
    DisposableEffect(Unit) {
        onDispose { NfNotificationListener.instance?.refreshMediaCollection() }
    }
    val mediaMap by MediaSessionHolder.byPackage.collectAsState()
    val latestMedia = remember(mediaMap) { mediaMap.values.maxByOrNull { it.updatedAtMs } }

    // 成功添加（输入键进入已选集合）后清空输入框；校验失败保留文本
    val inputKey = input.trim()
    LaunchedEffect(selected, inputKey) {
        if (inputKey.isNotEmpty() &&
            selected.any { it is FieldRef.Extras && it.key == inputKey }
        ) {
            input = ""
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 560.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
        ) {
            Text(
                text = "选择匹配字段",
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "字段为规则级共享：一条规则一组被选字段，对所有关键词生效。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // —— 分组一：常用字段（六文本）——
            Spacer(Modifier.height(12.dp))
            Text(
                text = FieldGroup.TEXT.label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                MatchField.ALL.forEach { field ->
                    val ref = FieldRef.Text(field)
                    FilterChip(
                        selected = ref in selected,
                        onClick = { onToggle(ref) },
                        label = { Text(field.label) },
                    )
                }
            }

            // —— 分组二：通知信息（六元信息）——
            Spacer(Modifier.height(14.dp))
            Text(
                text = FieldGroup.META.label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = "key 为整串匹配；tag 无值按 null；是否常驻填 true / false。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                MetaFieldKind.entries.forEach { kind ->
                    val ref = FieldRef.Meta(kind)
                    FilterChip(
                        selected = ref in selected,
                        onClick = { onToggle(ref) },
                        label = { Text(kind.label) },
                    )
                }
            }

            // —— V10.8：媒体会话字段（正在播放的媒体）——
            // 这些键不是通知自带的，而是构造快照时由 MediaSession 注入的「虚拟 extras」
            // （键名前缀 `media.`）。这里给一组固定候选，用户不用背键名。
            // 注意：只有**规则真的引用了**这里任一字段，采集才会启用（否则完全不采）。
            Spacer(Modifier.height(14.dp))
            Text(
                text = "媒体会话字段（正在播放）",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = "来自 MediaSession：媒体标题 / 歌手 / 专辑等。" +
                    "勾选任一字段后才会开始采集，否则零开销。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                MEDIA_FIELD_PRESETS.forEach { (key, label) ->
                    val already = selected.any { it is FieldRef.Extras && it.key == key }
                    val value = mediaValueOf(latestMedia, key)
                    AssistChip(
                        onClick = { onAddExtras(key) },
                        enabled = !already,
                        label = {
                            Text(
                                when {
                                    already -> "$label ✓"
                                    // 有值就直接显示（截断），用户一眼能看到当前媒体标题/歌手是什么
                                    value != null -> "$label：${value.take(12)}" +
                                        if (value.length > 12) "…" else ""
                                    else -> label
                                },
                            )
                        },
                    )
                }
            }

            // —— 分组三：Extras 字段（最近 + 手动）——
            Spacer(Modifier.height(14.dp))
            Text(
                text = FieldGroup.EXTRAS.label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = "支持深层键名（如 filterbox.field.CHANNEL_ID）；键名大小写敏感，" +
                    "可从 P7 详情页复制字段名。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.Top) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    placeholder = { Text("输入 extras 键名，如 filterbox.field.CHANNEL_ID") },
                    singleLine = true,
                    isError = extrasError != null,
                    supportingText = extrasError?.let { { Text(it) } },
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.size(8.dp))
                OutlinedButton(
                    onClick = { onAddExtras(input) },
                    modifier = Modifier.padding(top = 8.dp),
                ) {
                    Icon(Icons.Filled.Add, contentDescription = "添加该 extras 字段")
                    Spacer(Modifier.size(4.dp))
                    Text("添加")
                }
            }

            Spacer(Modifier.height(8.dp))
            Text(
                text = "最近出现过（按频次）",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            when {
                recentKeysLoading && recentExtrasKeys.isEmpty() -> Text(
                    text = "正在从流水聚合…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                recentExtrasKeys.isEmpty() -> Text(
                    text = "流水为空或暂无 extras 字段，请手动输入键名",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                else -> Column(
                    modifier = Modifier.heightIn(max = 220.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    recentExtrasKeys.forEach { (key, sample) ->
                        val ref = FieldRef.Extras(key)
                        val on = ref in selected
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(
                                checked = on,
                                onCheckedChange = { onToggle(ref) },
                            )
                            Spacer(Modifier.size(6.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    text = key,
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (sample.isNotBlank()) {
                                    Text(
                                        text = "示例：$sample",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            Text(
                text = "命名提示：android.* 为系统标准字段，其余多为厂商私有或自定义前缀。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 已选字段 chip（V2T05 编辑页条件区）：分组前缀 + 标签 + 删除 ×；
 * extras 字段 chip 点击本体可看示例值（[onShowSample]），文本 / 元信息不提供。
 *
 * 灰度主题下以分组前缀（文 / 信 / Extras）区分三组，不依赖颜色（T08 彩色化前）。
 */
@Composable
fun SelectedFieldChip(
    ref: FieldRef,
    onRemove: () -> Unit,
    onShowSample: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val groupTag = when (ref.group) {
        FieldGroup.TEXT -> "文"
        FieldGroup.META -> "信"
        FieldGroup.EXTRAS -> "Extras"
    }
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Row(
            modifier = Modifier
                .then(
                    if (onShowSample != null) {
                        Modifier.clickable { onShowSample() }
                    } else {
                        Modifier
                    },
                )
                .padding(start = 8.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "$groupTag｜${ref.label}",
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.size(2.dp))
            IconButton(
                onClick = onRemove,
                modifier = Modifier.size(20.dp),
            ) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = "移除字段 ${ref.label}",
                    modifier = Modifier.size(14.dp),
                )
            }
        }
    }
}

/**
 * V10.8：媒体会话字段的快捷候选（键名 → 显示名）。
 *
 * 键名与 `FieldExtractor.mediaExtras()` 的生成口径必须一致，否则选了也匹配不到。
 * `media.title` 对应 `METADATA_KEY_TITLE`；部分播放器会用它放当前播放进度或自定义文案，不要假设它一定是歌名。
 */
private val MEDIA_FIELD_PRESETS: List<Pair<String, String>> = listOf(
    "media.title" to "媒体标题",
    "media.displayTitle" to "显示标题",
    "media.displaySubtitle" to "显示副标题",
    "media.displayDescription" to "显示描述",
    "media.artist" to "歌手",
    "media.album" to "专辑",
    "media.albumArtist" to "专辑艺术家",
    "media.composer" to "作曲",
    "media.writer" to "作词",
    "media.genre" to "流派",
    "media.year" to "年份",
    "media.durationMs" to "时长(ms)",
    "media.playing" to "播放中",
    "media.package" to "媒体 App 包名",
)

/**
 * 从媒体快照里取某个 `media.*` 键的当前值（V10.9）。
 *
 * 只为「在字段候选上显示实时值」服务——让用户点之前就知道这个字段现在是什么内容，
 * 而不是盲目勾选。取值口径与 `FieldExtractor.mediaExtras()` 必须一致。
 */
private fun mediaValueOf(m: MediaSnapshot?, key: String): String? {
    if (m == null) return null
    return when (key) {
        "media.title" -> m.title
        "media.displayTitle" -> m.displayTitle
        "media.displaySubtitle" -> m.displaySubtitle
        "media.displayDescription" -> m.displayDescription
        "media.artist" -> m.artist
        "media.album" -> m.album
        "media.albumArtist" -> m.albumArtist
        "media.composer" -> m.composer
        "media.writer" -> m.writer
        "media.genre" -> m.genre
        "media.year" -> m.year
        "media.durationMs" -> m.durationMs?.toString()
        "media.playing" -> if (m.playing) "true" else "false"
        "media.package" -> m.packageName
        else -> if (key.startsWith("media.")) {
            m.extras.firstOrNull { it.first == key.removePrefix("media.") }?.second
        } else {
            null
        }
    }?.takeIf { it.isNotBlank() }
}
