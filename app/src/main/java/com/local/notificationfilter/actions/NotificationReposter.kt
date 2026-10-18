package com.local.notificationfilter.actions

import android.app.Notification
import android.app.Notification.BigPictureStyle
import android.app.Notification.BigTextStyle
import android.app.Notification.Builder
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.Icon
import android.os.Build
import android.service.notification.StatusBarNotification
import androidx.core.os.BundleCompat
import com.local.notificationfilter.R
import com.local.notificationfilter.core.aggregate.AggregationRenderer
import com.local.notificationfilter.core.aggregate.Rendered
import com.local.notificationfilter.core.model.AggregationConfig
import com.local.notificationfilter.core.model.AggregationLists
import com.local.notificationfilter.core.model.NfJson
import com.local.notificationfilter.core.model.NotificationRecord
import com.local.notificationfilter.core.model.NotificationSnapshot
import com.local.notificationfilter.core.model.PendingRepost
import com.local.notificationfilter.core.model.PlaceholderExtract
import com.local.notificationfilter.core.model.Placeholders
import com.local.notificationfilter.core.model.ReplaceConfig
import com.local.notificationfilter.core.model.RewriteMode
import com.local.notificationfilter.service.NfChannels
import com.local.notificationfilter.util.NfLog

/**
 * 替换重发（P0-7）：取消原通知后，以本 App 名义重发改写版。
 *
 * - 渠道：每源 App 一个 `repost_<pkg>`；渠道名 = 原 App 名或「替换通知」（规则可控）；
 * - 点击：优先复用原通知 contentIntent（即时路径由管线传入），降级为打开源 App 首页；
 * - 图片：优先 EXTRA_PICTURE；`EXTRA_REDUCED_IMAGES` 为 true 时系统已压缩，按原样携带
 *   （风险 2：原图不可恢复）；
 * - 占位符统一由 [Placeholders] 渲染（架构共享约定 5；V2T07 起四组占位符全覆盖）；
 * - 小图标：保留原 App 图标（keepIcon）或使用本 App 图标；minSdk 26 可直接用
 *   `Notification.Builder.setSmallIcon(Icon)`，无需兼容库。
 *
 * V2T07（需求③）双模板渲染见 [renderTitle] / [renderText]；延时路径的「调度时渲染定型 +
 * 双重渲染修复」见 [postFromTask]。
 *
 * V7（需求 8 / 架构 §3.8 D15）：延时任务多一个文案来源 `AGGREGATION`——到点用
 * 「到点那一刻的最新聚合列表」重算模板，见 [renderAggregated] + [postFromTask] 三参重载。
 * **两个重载都不再渲染模板**（V2T07 的双重渲染红线照旧）：文案由调用方定型后传入。
 *
 * @param source 原通知对象（即时路径传入，用于复用图标/图片；延时路径为 null）。
 */
class NotificationReposter(private val context: Context) {

    private val manager: NotificationManager? =
        context.getSystemService(NotificationManager::class.java)

    /**
     * 立即重发（替换单独使用 / 朗读后替换 / 推迟×替换取消后即时路径）。
     *
     * @param extract 即时提取上下文（`{re1}`..`{re9}`，见 [PlaceholderExtract]）；
     *   默认 [PlaceholderExtract.NONE] → 与改前逐字一致（老调用点零改动）
     */
    fun post(
        snapshot: NotificationSnapshot,
        cfg: ReplaceConfig,
        ruleId: Long? = null,
        contentIntent: PendingIntent? = null,
        source: StatusBarNotification? = null,
        extract: PlaceholderExtract = PlaceholderExtract.NONE,
    ): Boolean {
        // 即时路径：模板在本处渲染（占位符按通知到达时的值定型）
        val title = renderTitle(snapshot, cfg, extract)
        val text = renderText(snapshot, cfg, extract)
        return postRendered(snapshot, cfg, ruleId, title, text, contentIntent, source)
    }

    /**
     * 延时到点重发（RepostAlarmReceiver 调用；source 已不可得，图标/图片降级）。
     *
     * V2T07 修复双重渲染 bug（架构 §4.3）：[PendingRepost] 存的是**调度时已渲染定型**的
     * 文案（`repostTitle` / `repostText`，占位符按通知到达时的值定型，V1 语义），此处
     * **直接原样重发、不再走模板渲染**——V1 拿已渲染文案再渲染一遍会导致 `{title}` 类
     * 模板被二次替换（如 `[QQ] ` 前缀重复叠加）。
     *
     * V7：等价于 `postFromTask(task, task.repostTitle, task.repostText)`（PLAIN 路径，
     * 行为与 V6 逐字一致）。
     */
    fun postFromTask(task: PendingRepost): Boolean =
        postFromTask(task, task.repostTitle, task.repostText)

    /**
     * 延时到点重发·指定文案（V7 需求 8 的 `AGGREGATION` 路径用）。
     *
     * 调用方（`RepostAlarmReceiver`）已用 [renderAggregated] 拿到「到点最新列表」的渲染结果，
     * 这里**只负责发**——绝不再次渲染（V2T07 红线：二次渲染会把 `{title}` 之类再次替换，
     * 已知会导致 `[QQ] ` 前缀重复叠加）。
     *
     * @param title 已定型标题（空 → 回落 `task.repostTitle`，避免发出空通知）
     * @param text 已定型正文（空 → 回落 `task.repostText`）
     */
    fun postFromTask(task: PendingRepost, title: String, text: String): Boolean {
        val cfg = decodeReplaceConfig(task)
        return postRendered(
            snapshot = snapshotFromTask(task),
            cfg = cfg,
            ruleId = task.ruleId,
            title = title.ifBlank { task.repostTitle },
            text = text.ifBlank { task.repostText },
            contentIntent = null,
            source = null,
        )
    }

    // ==================== 聚合重渲染（V7 需求 8，架构 §3.8） ====================

    /**
     * 用「到点那一刻的最新聚合列表」重算延时任务的标题与正文。
     *
     * 本方法**不读库**（`NotificationReposter` 不持有 Repository）：列表由调用方
     * （`RepostAlarmReceiver`，跑在 IO 协程里）读好后传入，这里只做纯渲染。
     * 渲染本身仍委托 [AggregationRenderer] → [Placeholders.render]（模板唯一实现，D13/Q5）。
     *
     * 回归语义：合成快照的 `title/text` 取调度时已定型的文案，因此模板里若有 `{title}`，
     * 取到的是「调度时标题」；[AggregationRenderer.render] 的空模板回落也正好落到同一份
     * 兜底文案——不会出现空白卡片/空白通知。
     *
     * @param task 延时任务（提供包名/应用名/原始 key 等上下文）
     * @param config 从 `task.aggregationJson` 反序列化出的聚合配置
     * @param lists 该规则当前的最新列表（列表为空时由调用方决定回落，见 Receiver）
     */
    fun renderAggregated(
        task: PendingRepost,
        config: AggregationConfig,
        lists: AggregationLists,
    ): Rendered = AggregationRenderer.render(config, snapshotFromTask(task), lists)

    // ==================== 文案渲染（V2T07 双模板 + Placeholders） ====================

    /**
     * 标题：非自定义模式取原标题；CUSTOM_TEXT 渲染标题模板，空 → 回落原标题/应用名。
     *
     * @param extract 即时提取上下文（`{re1}`..`{re9}`；见 [PlaceholderExtract]），
     *   让「替换·自定义标题」也能用正则从当前通知里抓内容。默认空上下文 = 改前行为。
     */
    fun renderTitle(
        snapshot: NotificationSnapshot,
        cfg: ReplaceConfig,
        extract: PlaceholderExtract = PlaceholderExtract.NONE,
    ): String = when (cfg.mode) {
        RewriteMode.STRIP_IMAGES, RewriteMode.PLAIN_TEXT ->
            snapshot.title?.takeIf { it.isNotBlank() } ?: snapshot.appName

        RewriteMode.CUSTOM_TEXT ->
            // V2T03 连带改名：customTemplate → customTitleTemplate（语义不变：模板作标题）。
            // V2T07：双模板 + 四组占位符；空 → 回落原标题/应用名。
            Placeholders.render(cfg.customTitleTemplate.orEmpty(), snapshot, extract = extract)
                .ifBlank { snapshot.title?.takeIf { it.isNotBlank() } ?: snapshot.appName }
    }

    /**
     * 正文：非自定义模式取原正文（回落消息列表首条）；CUSTOM_TEXT 渲染正文模板，空 → 回落原正文（V1 行为）。
     *
     * @param extract 即时提取上下文（`{re1}`..`{re9}`），语义同 [renderTitle]。
     */
    fun renderText(
        snapshot: NotificationSnapshot,
        cfg: ReplaceConfig,
        extract: PlaceholderExtract = PlaceholderExtract.NONE,
    ): String = when (cfg.mode) {
        RewriteMode.STRIP_IMAGES, RewriteMode.PLAIN_TEXT ->
            snapshot.text?.takeIf { it.isNotBlank() }
                ?: snapshot.textLines.firstOrNull()?.takeIf { it.isNotBlank() }
                ?: ""

        // V2T07：自定义文案可改正文（双模板独立填写/独立留空）；空模板回落原正文。
        RewriteMode.CUSTOM_TEXT ->
            Placeholders.render(cfg.customTextTemplate.orEmpty(), snapshot, extract = extract)
                .ifBlank {
                    snapshot.text?.takeIf { it.isNotBlank() }
                        ?: snapshot.textLines.firstOrNull()?.takeIf { it.isNotBlank() }
                        ?: ""
                }
    }

    // ==================== 构建细节 ====================

    /**
     * 延时任务的合成快照：只用于渠道 / 图标 / 包名 / 时间等**上下文**，不参与模板渲染
     * （PLAIN 路径的文案完全由 `task.repostTitle/repostText` 决定，V2T07 语义）。
     *
     * V7：把调度时定型的文案挂到 `title/text` 上，供 [renderAggregated] 拼 `{title}` /
     * 空模板回落使用；对 PLAIN 路径**无任何影响**（[build] 只用 postTime/packageName/key）。
     */
    private fun snapshotFromTask(task: PendingRepost): NotificationSnapshot =
        NotificationSnapshot(
            key = task.originalKey,
            notifId = 0,
            packageName = task.packageName,
            appName = task.appName,
            postTime = System.currentTimeMillis(),
            title = task.repostTitle.takeIf { it.isNotBlank() },
            text = task.repostText.takeIf { it.isNotBlank() },
        )

    /** `replaceConfigJson` 解析；损坏时降级默认配置（PLAIN_TEXT 纯文本），绝不抛。 */
    private fun decodeReplaceConfig(task: PendingRepost): ReplaceConfig =
        runCatching { NfJson.decodeFromString<ReplaceConfig>(task.replaceConfigJson) }
            .getOrElse {
                NfLog.e(this, "延时重发配置解析失败: ${it.message}")
                ReplaceConfig()
            }

    /**
     * 已渲染文案的统一落库/发送路径（即时与延时共用；不再做任何模板渲染）。
     * 原 V1 `post` 的 ensureChannel + build + notify 逻辑原样内聚于此。
     */
    private fun postRendered(
        snapshot: NotificationSnapshot,
        cfg: ReplaceConfig,
        ruleId: Long?,
        title: String,
        text: String,
        contentIntent: PendingIntent?,
        source: StatusBarNotification?,
    ): Boolean = runCatching {
        ensureChannel(snapshot.packageName, snapshot.appName, cfg.channelUseAppName)
        val notification = build(snapshot, cfg, title, text, contentIntent, source)
        val id = notificationId(snapshot.key)
        manager?.notify(id, notification)
        NfLog.i(this, "替换重发 pkg=${snapshot.packageName} id=$id mode=${cfg.mode}")
        true
    }.getOrElse {
        NfLog.e(this, "替换重发失败: ${it.message}")
        false
    }

    private fun build(
        snapshot: NotificationSnapshot,
        cfg: ReplaceConfig,
        title: String,
        text: String,
        contentIntent: PendingIntent?,
        source: StatusBarNotification?,
    ): Notification {
        val channelId = NfChannels.repost(snapshot.packageName)
        val builder = Builder(context, channelId)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(resolveSmallIcon(cfg, source))
            .setWhen(if (snapshot.postTime > 0) snapshot.postTime else System.currentTimeMillis())
            .setShowWhen(true)
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_MESSAGE)
            // 富样式一律去除：STRIP_IMAGES / PLAIN_TEXT 均为纯文本观感
            .setStyle(BigTextStyle().bigText(text))

        // 大图：仅非去图模式且开关开启时携带
        if (cfg.mode != RewriteMode.STRIP_IMAGES && cfg.keepLargeImage) {
            val bitmap = source?.notification?.extras?.let {
                BundleCompat.getParcelable(it, Notification.EXTRA_PICTURE, Bitmap::class.java)
            }
            if (bitmap != null) {
                builder.setStyle(BigPictureStyle().bigPicture(bitmap).setSummaryText(text))
            }
        }

        val clickIntent = contentIntent ?: launchIntentFor(snapshot.packageName)
        if (clickIntent != null) builder.setContentIntent(clickIntent)
        return builder.build()
    }

    /**
     * V9 需求 5：把一条**已记录 / 已被消除**的通知重新发回通知栏（详情页「恢复通知」）。
     *
     * 我们无法以别的应用身份发送，因此走本 App 的代发通道，但**渠道名沿用原应用**
     * （`channelUseAppName = true`），观感上仍是那个应用的通知。通知 id 用 key 的 hash，
     * 重复点「恢复」只会覆盖同一条，不会堆出重复通知。
     *
     * 与推迟链路无关：不读写 `PendingRepost`，也不涉及聚合渲染。
     */
    fun repostRecord(record: NotificationRecord): Boolean = runCatching {
        val title = record.titleSummary.trim().ifBlank { record.appName }
        val text = record.textSummary.trim()
        val snapshot = NotificationSnapshot(
            key = record.key,
            notifId = record.notifId,
            packageName = record.packageName,
            appName = record.appName,
            userId = record.userId,
            postTime = record.postTime,
            title = title,
            text = text,
            textLines = listOf(text).filter { it.isNotBlank() },
        )
        val cfg = ReplaceConfig(mode = RewriteMode.PLAIN_TEXT, channelUseAppName = true, keepIcon = true)
        ensureChannel(snapshot.packageName, snapshot.appName, cfg.channelUseAppName)
        val notification = build(snapshot, cfg, title, text, contentIntent = null, source = null)
        val id = notificationId(snapshot.key)
        manager?.notify(id, notification)
        NfLog.i(this, "恢复通知 pkg=${snapshot.packageName} id=$id")
        manager != null
    }.getOrElse {
        NfLog.e(this, "恢复通知失败 pkg=${record.packageName}: ${it.message}")
        false
    }

    private fun resolveSmallIcon(cfg: ReplaceConfig, source: StatusBarNotification?): Icon {
        if (cfg.keepIcon) {
            val original = source?.notification?.smallIcon
            if (original != null) return original
        }
        return Icon.createWithResource(context, R.drawable.ic_stat_filter)
    }

    private fun launchIntentFor(packageName: String): PendingIntent? {
        val intent = context.packageManager.getLaunchIntentForPackage(packageName) ?: return null
        return PendingIntent.getActivity(
            context,
            packageName.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun ensureChannel(packageName: String, appName: String, useAppName: Boolean) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val id = NfChannels.repost(packageName)
        val name = if (useAppName && appName.isNotBlank()) appName
        else context.getString(R.string.repost_channel_name)
        val channel = NotificationChannel(id, name, NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = context.getString(R.string.repost_channel_desc)
            setShowBadge(true)
        }
        runCatching { manager?.createNotificationChannel(channel) }
    }

    /** 同一 key 的重发复用同一通知 ID（重复触发视为更新）。 */
    private fun notificationId(key: String): Int = key.hashCode()
}
