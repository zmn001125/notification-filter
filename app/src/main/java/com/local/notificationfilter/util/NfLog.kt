package com.local.notificationfilter.util

import android.util.Log

/**
 * 统一日志工具（架构共享约定 7）。
 *
 * - tag = "NF:" + 类名，release 构建关闭 debug 日志；
 * - 通知处理关键路径（取消 / 重发 / 调度 / 失败）必须记日志，便于真机排查。
 */
object NfLog {

    private const val GLOBAL_TAG = "NF"

    /** 是否输出 debug 日志（由 BuildConfig.DEBUG 决定，release 关闭）。 */
    val debugEnabled: Boolean = com.local.notificationfilter.BuildConfig.DEBUG

    private fun tag(any: Any): String = "$GLOBAL_TAG:${any.javaClass.simpleName}"

    fun d(any: Any, msg: String) {
        if (debugEnabled) Log.d(tag(any), msg)
    }

    fun i(any: Any, msg: String) {
        Log.i(tag(any), msg)
    }

    fun w(any: Any, msg: String, tr: Throwable? = null) {
        Log.w(tag(any), msg, tr)
    }

    fun e(any: Any, msg: String, tr: Throwable? = null) {
        Log.e(tag(any), msg, tr)
    }
}
