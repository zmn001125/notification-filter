package com.local.notificationfilter.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.local.notificationfilter.util.AppInfo
import com.local.notificationfilter.util.AppUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 「无图标」标签圆角。 */
private val NoLauncherTagShape = RoundedCornerShape(4.dp)

/**
 * App 多选弹窗（规则作用范围 / 黑名单 / 测试手填素材共用）。
 *
 * 数据源为 [AppUtils.installedApps]（**全部已安装应用**，含无桌面图标的系统组件/服务型应用）。
 * 旧实现只取「有桌面入口」的子集，会把「没有桌面图标但照样会发通知」的包全部漏掉，
 * 导致用户投诉「选 App 时只有一部分 App」。
 *
 * 顶部搜索框支持名称 / 包名（忽略大小写），并附带一个默认关闭的「只看有图标的」筛选开关：
 * 关闭 = 显示全部；开启 = 只保留 `hasLauncher == true`。
 *
 * @param singleSelect true 时单击即确认并只返回一个包名（手填素材选应用）。
 */
@Composable
fun AppPickerDialog(
    title: String = "选择应用",
    initialSelected: Set<String> = emptySet(),
    singleSelect: Boolean = false,
    onDismiss: () -> Unit,
    onConfirm: (Set<String>) -> Unit,
) {
    val context = LocalContext.current
    /**
     * 全量已安装应用（已排除本 App / 空包名；排序：有图标 → 用户应用 → 名称升序）。
     *
     * V9.4：`AppUtils.installedApps` 是「枚举全部已安装包 + 逐个查 launchIntent」的同步
     * Binder IPC，几百毫秒级。旧实现 `remember { AppUtils.installedApps(context) }` 只避免了
     * 重组时重复取引用，**枚举仍然在主线程跑**，打开弹窗就卡一下。
     * 现在改成 [produceState] + [Dispatchers.IO]：枚举在后台线程完成，期间 value 为 null，
     * 列表区显示「读取中」占位，不占主线程。
     *
     * 数据源、排序、`hasLauncher` 标记、以及「空枚举结果不写 5 分钟缓存」的既有语义
     * 完全不变 —— V9.2 的包可见性处理只改调用时机，不改逻辑。
     */
    val apps: List<AppInfo>? by produceState<List<AppInfo>?>(initialValue = null, context) {
        value = withContext(Dispatchers.IO) { AppUtils.installedApps(context) }
    }
    /** true = 后台枚举还没回来（列表区显示占位，不显示「没有匹配的应用」）。 */
    val loading: Boolean = apps == null
    val appList: List<AppInfo> = apps.orEmpty()
    var query by remember { mutableStateOf("") }
    /** 只看有桌面图标的应用。默认关 —— 关闭时才符合「列出所有 App」的预期。 */
    var onlyLauncher by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(initialSelected) }

    // 200+ 项时每次重组都重新过滤会有明显开销，三个 key 任一变化才重算。
    val filtered = remember(query, appList, onlyLauncher) {
        appList.asSequence()
            .filter { !onlyLauncher || it.hasLauncher }
            .filter { app ->
                query.isBlank() ||
                    app.appName.contains(query, ignoreCase = true) ||
                    app.packageName.contains(query, ignoreCase = true)
            }
            .toList()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(modifier = Modifier.heightIn(max = 400.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("搜索应用名或包名") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                // 紧凑一行：左侧筛选开关（32dp 高），右侧命中数量（让「只显示一部分」可自查）。
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    FilterChip(
                        selected = onlyLauncher,
                        onClick = { onlyLauncher = !onlyLauncher },
                        label = {
                            Text(
                                text = "只看有图标的",
                                style = MaterialTheme.typography.labelMedium,
                            )
                        },
                    )
                    Text(
                        text = if (loading) "读取中…" else "共 ${filtered.size} 个",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                when {
                    // 后台枚举中：先占位，别把「没有匹配的应用」闪给用户。
                    loading -> PickerHintBox(
                        title = "正在读取已安装应用…",
                        subtitle = "应用较多时可能需要一两秒",
                    )

                    filtered.isEmpty() -> PickerHintBox(
                        title = "没有匹配的应用",
                        subtitle = when {
                            onlyLauncher && query.isNotBlank() ->
                                "关掉上方「只看有图标的」，或用别的关键词试试"
                            onlyLauncher ->
                                "已开启「只看有图标的」，关掉它可以看到全部应用"
                            appList.isEmpty() ->
                                "没有读到已安装应用列表，请稍后重试或重启本应用"
                            else ->
                                "换个关键词试试（可搜应用名或包名）"
                        },
                    )

                    else -> LazyColumn(modifier = Modifier.weight(1f, fill = false)) {
                        items(filtered, key = { it.packageName }) { app ->
                            val checked = app.packageName in selected
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        if (singleSelect) {
                                            onConfirm(setOf(app.packageName))
                                        } else {
                                            selected = if (checked) selected - app.packageName
                                            else selected + app.packageName
                                        }
                                    }
                                    .padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(checked = checked, onCheckedChange = null)
                                Spacer(Modifier.size(8.dp))
                                AppAvatar(appName = app.appName, packageName = app.packageName, size = 32.dp)
                                Spacer(Modifier.size(8.dp))
                                Column(Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        // weight(fill = false)：名称最多占满剩余宽度，标签始终可见。
                                        Text(
                                            text = app.appName,
                                            style = MaterialTheme.typography.bodyMedium,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f, fill = false),
                                        )
                                        if (!app.hasLauncher) {
                                            Spacer(Modifier.size(4.dp))
                                            NoLauncherTag()
                                        }
                                    }
                                    Text(
                                        text = app.packageName,
                                        style = MaterialTheme.typography.labelSmall,
                                        fontFamily = FontFamily.Monospace,
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
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(selected) },
                enabled = selected.isNotEmpty(),
            ) {
                Text(if (selected.isEmpty()) "确定" else "确定（${selected.size}）")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

/**
 * 列表区的居中提示块（后台枚举中 / 无匹配结果共用）。
 *
 * V9.4：枚举搬到 IO 后，弹窗会先经历一段「还没数据」的时间，
 * 这段时间必须有占位，而不是直接显示「没有匹配的应用」误导用户。
 *
 * @param title 主提示。
 * @param subtitle 次要说明（可操作的下一步）。
 */
@Composable
private fun PickerHintBox(title: String, subtitle: String) {
    Box(
        modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 灰色小标签「无图标」：标记该包没有桌面入口（系统组件 / 服务型应用 / 已隐藏的应用）。
 *
 * 仅用中性色（surfaceVariant + onSurfaceVariant），不抢主标题的视觉权重。
 */
@Composable
private fun NoLauncherTag() {
    Text(
        text = "无图标",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        modifier = Modifier
            .background(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = NoLauncherTagShape,
            )
            .padding(horizontal = 4.dp, vertical = 1.dp),
    )
}
