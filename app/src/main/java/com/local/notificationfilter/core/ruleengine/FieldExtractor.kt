package com.local.notificationfilter.core.ruleengine

import com.local.notificationfilter.core.media.MediaSessionHolder
import android.app.Notification
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.service.notification.StatusBarNotification
import com.local.notificationfilter.core.model.NotificationSnapshot

/**
 * 从系统通知对象提取匹配视角快照 + 全量 extras dump。
 *
 * dump 约定（架构共享约定 5）：keySet 排序；值统一字符串化；
 * `byte[]` → `[B@<hex> (byte[<size>])`；展示与复制共用同一结果。
 */
object FieldExtractor {

    fun fromStatusBar(sbn: StatusBarNotification, appName: String): NotificationSnapshot {
        val notification = sbn.notification
        val extras: Bundle = notification.extras ?: Bundle()
        val userId = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) sbn.userId else 0
        return NotificationSnapshot(
            key = sbn.key,
            notifId = sbn.id,
            packageName = sbn.packageName,
            appName = appName,
            userId = userId,
            channelId = notification.channelId,
            postTime = sbn.postTime,
            isOngoing = (notification.flags and Notification.FLAG_ONGOING_EVENT) != 0,
            flags = notification.flags,
            title = charSequence(extras, Notification.EXTRA_TITLE)
                ?: charSequence(extras, Notification.EXTRA_TITLE_BIG),
            // V10.2 缺陷 A：正文补 `EXTRA_BIG_TEXT` 回落。
            // BigTextStyle / InboxStyle 的通知经常只设 `EXTRA_BIG_TEXT`（甚至不设 `EXTRA_TEXT`），
            // 例如百度网盘的传输 / 备份类通知——修复前 text 恒为 null，
            // 规则里写「正文包含 xxx」永远不命中。口径与 title 一致（title 已有 TITLE_BIG 回落），
            // 空白串仍视为 null（沿用 charSequence 内部 `takeIf { isNotBlank() }`）。
            text = charSequence(extras, Notification.EXTRA_TEXT)
                ?: charSequence(extras, Notification.EXTRA_BIG_TEXT),
            // V10.2 缺陷 B：`EXTRA_TEXT_LINES` 为空时回落到 MessagingStyle 的 `EXTRA_MESSAGES`。
            textLines = charSequenceArray(extras, Notification.EXTRA_TEXT_LINES)
                .ifEmpty { messageLines(extras) },
            subText = charSequence(extras, Notification.EXTRA_SUB_TEXT),
            summaryText = charSequence(extras, Notification.EXTRA_SUMMARY_TEXT),
            people = stringArray(extras, Notification.EXTRA_PEOPLE),
            // V10.8：把「当前媒体会话」的字段以 `media.*` 键并入 extras。
            // 这样规则侧不用新增字段类型——现有的「extras 任意键匹配」与
            // `{extras.media.xxx}` 占位符立刻可用，改动面最小、也最不容易出错。
            // 采集未启用（没有规则用到）时 mediaExtras() 返回空列表，零开销。
            extras = dumpExtras(extras) + mediaExtras(),
            // V2 新增：独立 tag 字段（与 key 第 4 段同源，规则匹配 MetaFieldKind.TAG 用）
            tag = sbn.tag,
        )
    }


    /**
     * 当前媒体会话字段 → `media.*` 键（V10.8）。
     *
     * 键名约定：标准字段用驼峰（`media.displayTitle`），其余 metadata 键**原样接在
     * `media.` 后面**（例如 `media.android.media.metadata.MEDIA_ID`），
     * 保证「所有字段」都能被规则命中——包括播放器自定义键。
     */
    private fun mediaExtras(): List<Pair<String, String>> {
        if (!MediaSessionHolder.enabled) return emptyList()
        val m = MediaSessionHolder.latest() ?: return emptyList()
        return buildList {
            fun put(k: String, v: String?) {
                v?.takeIf { it.isNotBlank() }?.let { add("media.$k" to it) }
            }
            put("package", m.packageName)
            put("title", m.title)
            put("displayTitle", m.displayTitle)
            put("displaySubtitle", m.displaySubtitle)
            put("displayDescription", m.displayDescription)
            put("artist", m.artist)
            put("album", m.album)
            put("albumArtist", m.albumArtist)
            put("track", m.track)
            put("genre", m.genre)
            put("composer", m.composer)
            put("writer", m.writer)
            put("year", m.year)
            put("durationMs", m.durationMs?.toString())
            add("media.playing" to if (m.playing) "true" else "false")
            m.extras.forEach { (key, value) -> put(key, value) }
        }
    }

    private fun charSequence(bundle: Bundle, key: String): String? =
        bundle.getCharSequence(key)?.toString()?.takeIf { it.isNotBlank() }

    private fun charSequenceArray(bundle: Bundle, key: String): List<String> {
        val array = bundle.getCharSequenceArray(key) ?: return emptyList()
        return array.mapNotNull { it?.toString()?.takeIf { s -> s.isNotBlank() } }
    }

    /**
     * 读 MessagingStyle 的消息数组（[Notification.EXTRA_MESSAGES]）并拼成文本行列表。
     *
     * 为什么需要（V10.2 缺陷 B）：聊天类 App 常常**不设** `EXTRA_TEXT_LINES`，只把多条消息塞进
     * `EXTRA_MESSAGES`（每个元素是一个 `Bundle`，含 `text` / `sender` / `time` 键）。
     * 修复前 `textLines` 只认 `EXTRA_TEXT_LINES`，这类通知的多行内容整体丢失，
     * 规则里「消息列表包含 xxx」永远不会命中。
     *
     * 输出形如 `"发送者: 内容"`；没有 sender 时只放内容；缺 `text` 的项直接跳过。
     *
     * - 键名写字面量：`Notification.MessagingStyle.Message.KEY_TEXT` / `KEY_SENDER` 是 @hide 的，
     *   编译期不可见；`"text"` / `"sender"` 是 AOSP 稳定的实际键名。
     * - `EXTRA_MESSAGES` 是 API 24+ 才有的（minSdk 26，安全）。
     * - 全程 `runCatching` 兜底：个别 ROM 会在该键里塞 `CharSequence[]` 或自定义 Parcelable，
     *   类型不对就返回空列表，绝不让监听服务因一条脏 extras 崩溃（架构约定 8）。
     *
     * @param bundle 通知 extras
     * @return 消息文本行；无消息或读取失败时为空列表（绝不返回 null）
     */
    // `getParcelableArray(String)` 从 API 33 起被废弃（改为带 Class 的重载），
    // 但带 Class 的重载只有 API 33+ 才有；为让 minSdk 26 走同一条路径，这里沿用旧重载并抑制警告。
    @Suppress("DEPRECATION")
    private fun messageLines(bundle: Bundle): List<String> = runCatching {
        val messages = bundle.getParcelableArray(Notification.EXTRA_MESSAGES)
            ?: return@runCatching emptyList()
        messages.mapNotNull { item ->
            val message = item as? Bundle ?: return@mapNotNull null
            val content = message.getCharSequence(MESSAGE_KEY_TEXT)
                ?.toString()
                ?.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            val sender = message.getCharSequence(MESSAGE_KEY_SENDER)
                ?.toString()
                ?.takeIf { it.isNotBlank() }
            if (sender != null) "$sender$MESSAGE_SENDER_SEPARATOR$content" else content
        }
    }.getOrDefault(emptyList())

    private fun stringArray(bundle: Bundle, key: String): List<String> {
        val array = bundle.getStringArray(key) ?: return emptyList()
        return array.mapNotNull { it?.takeIf { s -> s.isNotBlank() } }
    }

    /** extras 全量 dump（坏值不崩溃，标记为 `<unreadable>`）。 */
    fun dumpExtras(extras: Bundle): List<Pair<String, String>> =
        extras.keySet().orEmpty().sorted().map { key ->
            key to runCatching { stringifyValue(extras.get(key)) }
                .getOrElse { "<unreadable: ${it.javaClass.simpleName}>" }
        }

    private fun stringifyValue(value: Any?): String = when (value) {
        null -> "null"
        is CharSequence -> value.toString()
        is ByteArray -> "[B@" + Integer.toHexString(value.hashCode()) + " (byte[" + value.size + "])"
        is Array<*> -> "[" + value.joinToString(", ") { stringifyValue(it) } + "]"
        is Bitmap -> "Bitmap(${value.width}x${value.height})"
        else -> value.toString()
    }

    /**
     * MessagingStyle 消息 `Bundle` 内的文本键。
     *
     * 对应 `Notification.MessagingStyle.Message.KEY_TEXT`（`@hide`，编译期不可见），
     * 实际值固定为 `"text"`。
     */
    private const val MESSAGE_KEY_TEXT = "text"

    /**
     * MessagingStyle 消息 `Bundle` 内的发送者键。
     *
     * 对应 `Notification.MessagingStyle.Message.KEY_SENDER`（`@hide`），实际值固定为 `"sender"`；
     * 新版系统另有一个 `sender_person`（`Person` 对象），本 App 只按需求读 `"sender"`。
     */
    private const val MESSAGE_KEY_SENDER = "sender"

    /** 消息行里「发送者」与「内容」之间的分隔符（与 [NotificationSnapshot.textLines] 的展示口径一致）。 */
    private const val MESSAGE_SENDER_SEPARATOR = ": "
}
