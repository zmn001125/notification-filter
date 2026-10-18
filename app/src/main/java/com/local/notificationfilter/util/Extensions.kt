package com.local.notificationfilter.util

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import com.local.notificationfilter.core.model.ExtrasKeys
import com.local.notificationfilter.core.model.NotificationRecord
import com.local.notificationfilter.core.model.SnoozeCalculator
import com.local.notificationfilter.data.repo.extrasPairs
import java.time.Instant
import java.time.ZoneId

/**
 * 通用扩展（T12 详情页为主）：时间格式化、flags 十六进制、剪贴板、厂商字段判定。
 */

/** epoch 毫秒 → 「MM-DD HH:mm:ss」（详情页完整时间戳）。 */
fun Long.toFullDateTime(zone: ZoneId = ZoneId.systemDefault()): String {
    val dt = Instant.ofEpochMilli(this).atZone(zone).toLocalDateTime()
    return "%02d-%02d %02d:%02d:%02d".format(
        dt.monthValue, dt.dayOfMonth, dt.hour, dt.minute, dt.second,
    )
}

/** flags → 「0x00000008」式十六进制。 */
fun Int.toHexFlags(): String = "0x%08x".format(this)

/** 已知的标准 extras 键（android.* 白名单）；其余判为厂商私有。 */
private val KNOWN_ANDROID_KEYS: Set<String> = setOf(
    ExtrasKeys.TITLE,
    ExtrasKeys.TEXT,
    ExtrasKeys.TEXTLINES,
    ExtrasKeys.SUBTEXT,
    ExtrasKeys.SUMMARYTEXT,
    ExtrasKeys.PEOPLE,
    ExtrasKeys.TITLE_BIG,
    ExtrasKeys.CHANNEL_ID,
    ExtrasKeys.SORT_KEY,
    "android.template",
)

/** extras 键是否为厂商私有字段（P7 标「厂商私有」徽标）。 */
fun String.isVendorExtrasKey(): Boolean =
    !startsWith("android.") || this !in KNOWN_ANDROID_KEYS

/** 整份通知的纯文本（一键复制用：元信息 + extras 全量字段）。 */
fun NotificationRecord.toClipboardText(): String = buildString {
    appendLine("应用：$appName ($packageName)")
    appendLine("时间：${postTime.toFullDateTime()}")
    // V11：复制也要带全部效果，不再只有主结论那一个
    val effects = if (restoredAt != null) {
        "已恢复 / " + effectKinds.joinToString(" / ") { it.label }
    } else {
        effectKinds.joinToString(" / ") { it.label }
    }
    append("结果：$effects")
    if (!ruleName.isNullOrBlank()) append("（命中规则：$ruleName）")
    if (blacklistHit) append("（黑名单）")
    appendLine()
    if (executedActions.isNotEmpty()) {
        appendLine("已执行动作：${executedActions.joinToString("、") { it.label }}")
    }
    appendLine("key：$key")
    appendLine("id：$notifId")
    appendLine("userId：$userId")
    appendLine("CHANNEL_ID：${channelId ?: "—"}")
    appendLine("postTime：${postTime.toFullDateTime()}")
    appendLine("ONGOING：$isOngoing")
    appendLine("flags：${flags.toHexFlags()}")
    if (restoredAt != null) {
        appendLine("restoredAt：${restoredAt.toFullDateTime()}（已恢复）")
    }
    appendLine()
    appendLine("—— extras 全部字段 ——")
    extrasPairs().forEach { (k, v) ->
        appendLine("$k = $v")
    }
}

/** 复制纯文本到剪贴板。 */
fun Context.copyTextToClipboard(text: String) {
    val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    cm.setPrimaryClip(ClipData.newPlainText("通知字段", text))
}

/** 列表用相对时间（详情页头部复用 SnoozeCalculator.relativeTime）。 */
fun Long.toRelativeTime(now: Long = System.currentTimeMillis()): String =
    SnoozeCalculator.relativeTime(this, now)
