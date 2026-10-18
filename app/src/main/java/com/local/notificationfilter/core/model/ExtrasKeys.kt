package com.local.notificationfilter.core.model

/**
 * extras 键名常量（android.app.Notification.EXTRA_*），
 * 字段提取（FieldExtractor）、流水搜索、详情展示共用，避免各处硬编码。
 */
object ExtrasKeys {
    const val TITLE = "android.title"
    const val TEXT = "android.text"
    const val TEXTLINES = "android.textLines"
    const val SUBTEXT = "android.subText"
    const val SUMMARYTEXT = "android.summaryText"
    const val PEOPLE = "android.people"
    const val TITLE_BIG = "android.title.big"
    const val CHANNEL_ID = "android.channel_id"
    const val SORT_KEY = "android.sortKey"

    /** MatchField → extras 键；TITLE/TEXT 有独立摘要列，不走 extras。 */
    fun keyOf(field: MatchField): String? = when (field) {
        MatchField.TITLE -> null
        MatchField.TEXT -> null
        MatchField.TEXTLINES -> TEXTLINES
        MatchField.SUBTEXT -> SUBTEXT
        MatchField.SUMMARYTEXT -> SUMMARYTEXT
        MatchField.PEOPLE -> PEOPLE
    }
}
