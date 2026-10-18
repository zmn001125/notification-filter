package com.local.notificationfilter.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 进程内唯一「监听服务连接状态」事实来源（V2 需求⑥，架构 §5.1）。
 *
 * 纯 Kotlin object + StateFlow，无 Android 依赖；监听服务与 UI 同进程，
 * 服务回调 [NfNotificationListener.onListenerConnected] /
 * [NfNotificationListener.onListenerDisconnected] 直接推送，
 * 引导页 / 首页 / 后续测试面板的「服务未连接」提示统一读此 Flow，不得各自查询。
 */
object ListenerStatusHolder {

    private val _connected = MutableStateFlow(false)

    /** true = onListenerConnected 已回调，服务正在接收通知。 */
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    fun setConnected() {
        _connected.value = true
    }

    fun setDisconnected() {
        _connected.value = false
    }
}
