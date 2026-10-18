package com.local.notificationfilter.data.prefs

import android.content.Context
import com.local.notificationfilter.core.model.NfJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

/**
 * 一个通知渠道被静音**之前**的原始设置快照（V10 任务 B）。
 *
 * 为什么必须留底：Android 没有「临时静音某渠道」的 API，能做的只有把渠道改成静音
 * （`setSound(null, null)` + `enableVibration(false)`），这是**持久覆盖**——改完之后
 * 就再也读不到用户原本的设置（重要性 / 铃声 / 振动都被新值顶掉）。
 * 因此静音前把原值整份抄下来，`restoreChannel()` 才有东西可恢复。
 *
 * 键 = `packageName + channelId`（`(App, 渠道)` 唯一确定一个渠道）。
 *
 * @param channelName 渠道名（恢复时按原样重建，界面上不出现空名）
 * @param importance 原始重要性（`NotificationManager.IMPORTANCE_*`）
 * @param soundUri 原始铃声 Uri 字符串；null = 原本就无声
 * @param shouldVibrate 原始是否振动
 * @param savedAt 记录时刻（排查用）
 */
@Serializable
data class ChannelOriginal(
    val packageName: String,
    val channelId: String,
    val channelName: String = "",
    val importance: Int = 0,
    val soundUri: String? = null,
    val shouldVibrate: Boolean = false,
    val savedAt: Long = 0L,
)

/**
 * 渠道原始设置存储（V10 任务 B）：`(packageName, channelId)` → [ChannelOriginal]。
 *
 * 为什么用 SharedPreferences 而不是 DataStore / Room：
 * - 这是**旁路**数据（只服务于「恢复渠道」这一个能力），不参与任何主链路查询，
 *   不需要 Flow / 事务 / 迁移；
 * - 写入点在通知处理热路径上，SharedPreferences 的 `apply()` 是异步落盘、接口同步返回，
 *   不会给管线加 IPC 或磁盘等待；
 * - 不碰 Room schema → 无迁移风险（PRD 明确要求本轮不加 Room 迁移）。
 *
 * 全部方法 `runCatching` 兜底：存储坏掉只影响「能否恢复」，绝不影响通知处理。
 */
class ChannelSilenceStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * 记录一份原始设置；**已存在则不覆盖**。
     *
     * 「不覆盖」是刻意设计：第一次静音前存的是真正的原始值；若第二次命中同一条渠道时
     * 又存一次，存下来的就是**已被我们改成静音的值**——原始铃声/振动永久丢失，恢复功能失效。
     *
     * @return true = 本次真的写入了（之前没有记录）；false = 已有记录或写入失败
     */
    fun remember(original: ChannelOriginal): Boolean {
        val key = keyOf(original.packageName, original.channelId)
        return runCatching {
            if (prefs.contains(key)) {
                false
            } else {
                prefs.edit()
                    .putString(key, NfJson.encodeToString(original))
                    .apply()
                true
            }
        }.getOrDefault(false)
    }

    /** 读取某个渠道的原始设置；没有记录 / 解析失败 → null。 */
    fun original(packageName: String, channelId: String): ChannelOriginal? {
        val key = keyOf(packageName, channelId)
        val raw = runCatching { prefs.getString(key, null) }.getOrNull() ?: return null
        return runCatching { NfJson.decodeFromString<ChannelOriginal>(raw) }.getOrNull()
    }

    /** 全部已记录的原始设置（恢复全部 / 排查用）。解析失败的单条直接跳过。 */
    fun originals(): List<ChannelOriginal> = runCatching {
        prefs.all.values.mapNotNull { value ->
            val raw = value as? String ?: return@mapNotNull null
            runCatching { NfJson.decodeFromString<ChannelOriginal>(raw) }.getOrNull()
        }
    }.getOrDefault(emptyList())

    /** 删除某条记录（恢复成功后调用，避免下次静音时误以为「已存过」而跳过留底）。 */
    fun forget(packageName: String, channelId: String) {
        runCatching { prefs.edit().remove(keyOf(packageName, channelId)).apply() }
    }

    /** 清空全部记录（调试 / 重置用）。 */
    fun clear() {
        runCatching { prefs.edit().clear().apply() }
    }

    private fun keyOf(packageName: String, channelId: String): String = "$packageName\u0000$channelId"

    private companion object {
        /** 独立于 DataStore（SettingsStore）的轻量旁路存储，互不影响。 */
        const val PREFS_NAME = "nf_channel_silence"
    }
}
