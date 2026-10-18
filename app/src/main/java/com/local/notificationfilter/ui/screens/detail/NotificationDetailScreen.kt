package com.local.notificationfilter.ui.screens.detail

import androidx.compose.foundation.clickable
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.local.notificationfilter.core.model.NotificationRecord
import com.local.notificationfilter.core.model.Rule
import com.local.notificationfilter.data.repo.extrasPairs
import com.local.notificationfilter.NfApplication
import com.local.notificationfilter.service.NfNotificationListener
import com.local.notificationfilter.ui.components.AppAvatar
import com.local.notificationfilter.ui.components.EmptyState
import com.local.notificationfilter.ui.components.KeyValueRow
import com.local.notificationfilter.ui.components.NfCard
import com.local.notificationfilter.ui.components.RecordEffectTags
import com.local.notificationfilter.ui.components.SectionTitle
import com.local.notificationfilter.util.copyTextToClipboard
import com.local.notificationfilter.util.isVendorExtrasKey
import com.local.notificationfilter.util.toFullDateTime
import com.local.notificationfilter.util.toHexFlags

/**
 * P7 完整字段详情：头部（应用/包名/时间/结果）+ 命中信息横幅 +
 * 规则操作（新建 / 查看现有规则，V8 上移到元信息之前）+
 * 元信息区（key/id/tag/userId/CHANNEL_ID/ONGOING/flags）+ extras 逐行原样
 * （厂商私有字段标徽标）。
 *
 * V8 变更：
 * - 删除「一键复制整份」（入口与逻辑一并移除，不留占位）；
 * - 「新建规则」「查看现有规则」两个入口移到「元信息」之前；
 * - 顶栏与第一张卡片之间补 12dp 间距。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationDetailScreen(
    recordId: Long,
    locateKey: String?,
    onBack: () -> Unit,
    onCreateRuleForApp: (String) -> Unit = {},
    onEditRule: (Long) -> Unit = {},
    /** V9 需求 5：跳到「该应用的所有消息」（记录/搜索页按包名过滤）。 */
    onOpenAppMessages: (String) -> Unit = {},
) {
    val vm: NotificationDetailViewModel = viewModel()
    val state by vm.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LaunchedEffect(recordId, locateKey) { vm.load(recordId, locateKey) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("通知完整字段") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            val record = state.record
            if (state.notFound || record == null) {
                if (!state.loading) {
                    EmptyState("未找到该通知的流水记录（可能已被滚动淘汰）", icon = Icons.Filled.Inbox)
                }
                return@Column
            }

            // V8：顶栏与第一张卡片之间补 12dp（首个卡片若是横幅则无内边距，直接生效）
            Spacer(Modifier.height(12.dp))

            // —— 命中信息横幅（有规则命中信息时展示） ——
            val hitBanner = hitBannerText(record)
            if (hitBanner != null) {
                Card(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    ),
                ) {
                    Text(
                        text = hitBanner,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(10.dp),
                    )
                }
            }

            // —— 头部：应用 + 时间 + 结果 ——
            // 注意：规则效果标签就放在下面头部右侧的 RecordEffectTags（V11 用户反馈：
            // 不再单独做「规则效果」卡片，应用标题那一行已经显示，重复了）。
            NfCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AppAvatar(appName = record.appName, packageName = record.packageName, size = 40.dp)
                    Spacer(Modifier.size(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = record.appName,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = record.packageName,
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        RecordEffectTags(record = record, max = 3)
                        Spacer(Modifier.size(4.dp))
                        Text(
                            text = record.postTime.toFullDateTime(),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            // ==================== 规则操作（V8：上移到「元信息」之前） ====================
            SectionTitle(text = "规则操作")
            Spacer(Modifier.height(6.dp))
            // 为该应用新建规则（scope 预填 ONLY + 本包名）
            OutlinedButton(
                onClick = { onCreateRuleForApp(record.packageName) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("为「${record.appName}」新建规则")
            }
            Text(
                text = "新建规则的作用范围已预填为「仅 ${record.appName}」",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 4.dp),
            )

            // V9 需求 5：四个快捷操作（应用消息 / 系统设置 / 消除 / 恢复）
            Spacer(Modifier.height(8.dp))
            val detailContext = LocalContext.current
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                DetailActionButton(
                    text = "该应用消息",
                    modifier = Modifier.weight(1f),
                ) { onOpenAppMessages(record.packageName) }
                DetailActionButton(
                    text = "应用设置",
                    modifier = Modifier.weight(1f),
                ) {
                    runCatching {
                        detailContext.startActivity(
                            Intent(
                                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.parse("package:${record.packageName}"),
                            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }.onFailure {
                        Toast.makeText(detailContext, "无法打开系统设置", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                DetailActionButton(
                    text = "消除通知",
                    modifier = Modifier.weight(1f),
                ) {
                    val listener = NfNotificationListener.instance
                    val ok = listener != null && runCatching {
                        listener.cancelNotification(record.key)
                    }.isSuccess
                    Toast.makeText(
                        detailContext,
                        if (ok) "已消除该通知" else "该通知已不在通知栏",
                        Toast.LENGTH_SHORT,
                    ).show()
                }
                DetailActionButton(
                    text = "恢复通知",
                    modifier = Modifier.weight(1f),
                ) {
                    val container = (detailContext.applicationContext as? NfApplication)?.container
                    val ok = container?.notificationReposter?.repostRecord(record) == true
                    Toast.makeText(
                        detailContext,
                        if (ok) "已重新发布到通知栏" else "恢复失败（可能缺少通知权限）",
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            }
            Text(
                text = "「恢复」会用原应用的渠道名重新发布这条通知（实际由本 App 代发）。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 4.dp),
            )

            // 该 App 的现有规则列表（点击进入编辑）
            if (state.rulesForApp.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Text(
                    text = "该 App 的现有规则（${state.rulesForApp.size}）",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
                state.rulesForApp.forEach { rule ->
                    RuleRow(rule = rule, onClick = { onEditRule(rule.id) })
                }
            }

            Spacer(Modifier.height(16.dp))

            // —— 元信息区 ——
            SectionTitle(text = "元信息")
            NfCard {
                KeyValueRow(key = "key", value = record.key)
                KeyValueRow(key = "id", value = record.notifId.toString())
                // tag 从 key 第 4 段解析（userId|pkg|id|tag|uid；"null" 字面量 = 无 tag）
                KeyValueRow(
                    key = "tag",
                    value = record.key.split("|").getOrNull(3)?.takeIf { it != "null" } ?: "无",
                )
                KeyValueRow(key = "userId", value = record.userId.toString())
                KeyValueRow(key = "CHANNEL_ID", value = record.channelId ?: "—")
                KeyValueRow(key = "postTime", value = record.postTime.toFullDateTime())
                KeyValueRow(key = "ONGOING", value = record.isOngoing.toString())
                KeyValueRow(key = "flags", value = record.flags.toHexFlags())
                if (record.restoredAt != null) {
                    KeyValueRow(
                        key = "restoredAt",
                        value = "${record.restoredAt.toFullDateTime()}（已恢复）",
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            // —— extras 全部字段（原样展示，不做解析） ——
            SectionTitle(text = "Extras 全部字段（原样展示，不做解析）")
            NfCard {
                val pairs = record.extrasPairs()
                if (pairs.isEmpty()) {
                    Text(
                        text = "（无 extras 字段）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    pairs.forEach { (k, v) ->
                        ExtrasRow(key = k, value = v, onCopyPlaceholder = {
                            context.copyTextToClipboard("{extras.$k}")
                        })
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

/** 命中信息横幅文案；无命中信息返回 null（不展示）。 */
private fun hitBannerText(record: NotificationRecord): String? {
    val hasRule = !record.ruleName.isNullOrBlank()
    val hasFields = record.hitFields.isNotEmpty()
    if (!hasRule && !hasFields) return null
    return buildString {
        if (hasFields) {
            append("命中字段：")
            append(record.hitFields.joinToString("、") { it.label })
        }
        if (hasRule) {
            if (isNotEmpty()) append(" ｜ ")
            append("命中规则：${record.ruleName}")
        }
        // V11：已执行的动作不塞进这条纯文本横幅（措辞「将执行」也是错的），
        // 由头部右侧的 RecordEffectTags 多标签展示；
        // V11.1：应用户要求删掉了独立的「规则效果」卡片（与应用标题行重复）。
    }
}

/** 单条「该 App 的现有规则」行（点击进入编辑）。 */
@Composable
private fun RuleRow(rule: Rule, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(Modifier.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = rule.name.ifBlank { "未命名规则" },
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                if (!rule.enabled) {
                    Text(
                        text = "已停用",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            }
            Text(
                text = rule.summary() +
                    if (rule.deviceState.summary().isNotEmpty())
                        " ｜ " + rule.deviceState.summary()
                    else "",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** extras 行：键（等宽小字 + 厂商私有徽标）+ 值原样。点击键复制占位符 {extras.KEY}。 */
@Composable
private fun ExtrasRow(key: String, value: String, onCopyPlaceholder: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Row(
            modifier = Modifier
                .size(width = 150.dp, height = 18.dp)
                .clickable { onCopyPlaceholder() },
        ) {
            Text(
                text = key,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
            )
            if (key.isVendorExtrasKey()) {
                Spacer(Modifier.size(4.dp))
                Text(
                    text = "厂商私有",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        }
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * V9 需求 5：详情页快捷操作按钮（紧凑版）。
 *
 * 用等高 36dp + 小内边距，让四个操作能排成两行而不占满屏幕；
 * 文字 `labelMedium` + 单行，符合「提示语不截断、卡片不臃肿」的既有风格。
 */
@Composable
private fun DetailActionButton(
    text: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.height(36.dp),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
        )
    }
}
