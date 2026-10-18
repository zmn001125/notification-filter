package com.local.notificationfilter.service

import com.local.notificationfilter.core.model.NotificationSnapshot
import com.local.notificationfilter.core.ruleengine.TriggerEvent
import com.local.notificationfilter.core.ruleengine.TriggerEventType
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * 通知「出现 / 消失 / 规则命中」事件的**唯一**进程内广播口（V7 架构 §3.2 / §3.3）。
 *
 * 事件元素类型直接用 core 层的 [TriggerEvent]（T02 定稿，41 例单测已锁定其形状）：
 * 本类只负责把 Android 侧的通知回调**翻译**成 `TriggerEvent` 并广播，不另造事件模型
 * ——core 层不能依赖 `service/` 与 Android（`StatusBarNotification` 进不了 JVM 单测）。
 *
 * 设计约束（与架构 §3.4 性能红线一一对应）：
 * 1. **单例 object、进程内、零 Android UI 依赖**：不 import `android.util.Log`，
 *    只依赖 kotlinx.coroutines + core 模型 → 可在纯 JVM 单测里直接订阅、发射。
 * 2. **发送方永不挂起**：`MutableSharedFlow` + `tryEmit` + `DROP_OLDEST`。
 *    发射点位于通知监听回调链路（[NotificationPipeline] 的单线程 scope），
 *    一旦允许 suspend 就可能反压整条管线。
 * 3. **零订阅者零开销**：无人订阅时直接 return，连事件对象都不构造。
 *    `OverlayConditionArbiter` 未 attach（无任何条件）时正是这种状态。
 * 4. **不缓冲历史**：`replay = 0`。迟到订阅者不补发旧事件——条件求值只关心「从现在起」，
 *    重放旧事件会让卡片在 attach 瞬间被历史通知误触发。
 * 5. **前台 App 变化不进本总线**：`Arbiter` 在收到任何事件与 ticker 时直接读
 *    [ForegroundAppHolder.packageName] 的当前值即可，避免双源不一致。
 *
 * 订阅方（T05 `OverlayConditionArbiter`）：`NotificationEventBus.events.collect { ... }`。
 */
object NotificationEventBus {

    /**
     * 环形缓冲容量。
     *
     * 取值理由：订阅方是单线程 arbiter，处理一个事件是纯内存查表；32 条足够吸收
     * 「一连串通知涌入 + 订阅方正好在做一次 DB 读」的短时抖动。再大只是多占内存，
     * 并不能提升正确性——条件求值是幂等的状态驱动，`DROP_OLDEST` 丢的正是最旧的。
     */
    const val BUFFER_CAPACITY: Int = 32

    /**
     * 「通知 key → 该通知的 (包名, 命中规则 id)」短表容量上限（LRU）。
     *
     * 只用于**给 REMOVED 事件补 `ruleId`**（条件 ⑤「满足某规则的通知消失」需要它）。
     * 包名本身不必查表（`onNotificationRemoved` 的 `sbn.packageName` 一直可用），
     * 故表缺失也只是丢掉 `ruleId` 这一个附加信息，不会导致包名错误。
     * 256 条足以覆盖通知栏里同时存活的通知数量，内存占用可忽略。
     */
    private const val MAX_TRACKED_KEYS: Int = 256

    private val _events = MutableSharedFlow<TriggerEvent>(
        replay = 0,
        extraBufferCapacity = BUFFER_CAPACITY,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** 只读事件流（订阅方唯一入口）。 */
    val events: SharedFlow<TriggerEvent> = _events.asSharedFlow()

    /** 当前订阅者数量（0 = 无人关心条件，发射直接短路）。 */
    val subscriberCount: Int get() = _events.subscriptionCount.value

    /** 是否有订阅者。 */
    val hasSubscribers: Boolean get() = _events.subscriptionCount.value > 0

    /**
     * key → (包名, 命中规则 id)。`accessOrder = true` 的 LRU，超限自动淘汰最久未用。
     *
     * 线程安全：发射点全在管线单线程 scope 内，但本表也允许被任意线程读，
     * 故所有访问都走 `synchronized(trackedKeys)`（临界区只有一次 put/get，无锁竞争压力）。
     */
    private val trackedKeys = object : LinkedHashMap<String, TrackedKey>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, TrackedKey>?): Boolean =
            size > MAX_TRACKED_KEYS
    }

    /** 一条通知在「出现 / 命中」时记录下来的翻译信息。 */
    private data class TrackedKey(val packageName: String, val ruleId: Long?)

    /**
     * 发射一条已翻译好的事件（**非 suspend**，可在任意线程、任意回调里调用）。
     *
     * 无订阅者时立即返回（零开销）；缓冲区满时按 `DROP_OLDEST` 丢弃最旧事件，
     * 由底层策略保证 `tryEmit` 恒成功，故无需处理返回值、也无需任何日志
     * （本类刻意不依赖 `android.util.Log`，保持可在纯 JVM 单测里订阅）。
     *
     * @param event 已构造的事件；调用方通常是本类的三个便捷方法，或 T05 的自定义场景。
     */
    fun emit(event: TriggerEvent) {
        if (_events.subscriptionCount.value == 0) return
        _events.tryEmit(event)
    }

    /**
     * 发射「通知出现」事件（`onNotificationPosted` 且已通过防递归 / 指纹去重）。
     *
     * @param snapshot 通知快照（`FieldExtractor.fromStatusBar` 的产物）；
     *   本方法只取 `packageName` 进 [TriggerEvent]，同时把 `key` 记入内部短表，
     *   供后续 [emitRemoved] 补 `ruleId` 用。
     */
    fun emitPosted(snapshot: NotificationSnapshot) {
        if (!hasSubscribers) return
        track(snapshot.key, snapshot.packageName, null)
        emit(TriggerEvent.posted(snapshot.packageName))
    }

    /**
     * 发射「规则命中」事件（通知命中了 [ruleId] 这条规则）。
     *
     * 与 [emitPosted] 是**两条独立事件**：条件 ④ 关心「App 有通知」，条件 ⑤ 关心
     * 「有通知命中了规则 X」，同一条通知可以同时满足两者，订阅方按 kind 分别消费。
     *
     * @param snapshot 通知快照（取 `packageName` 与 `key`）。
     * @param ruleId 命中的规则 id。
     */
    fun emitMatched(snapshot: NotificationSnapshot, ruleId: Long) {
        if (!hasSubscribers) return
        track(snapshot.key, snapshot.packageName, ruleId)
        emit(TriggerEvent.matched(ruleId, snapshot.packageName))
    }

    /**
     * 发射「通知消失」事件（`onNotificationRemoved`，含被本 App 消除的）。
     *
     * 翻译规则：
     * - 包名**总是**用调用方给的 [packageName]（`StatusBarNotification` 在移除回调里仍带该字段）；
     * - `ruleId` 从内部短表按 [notificationKey] 查（该通知此前命中过某规则时才有）→
     *   支持条件 ⑤ 的「满足某规则的通知消失」边沿；查不到则发无 `ruleId` 的 REMOVED。
     *
     * @param packageName 通知所属包名。
     * @param notificationKey `StatusBarNotification.key`（用于查表补 `ruleId` 并清理条目）。
     */
    fun emitRemoved(packageName: String, notificationKey: String) {
        if (!hasSubscribers) return
        val tracked = forget(notificationKey)
        val pkg = tracked?.packageName?.takeIf { it.isNotEmpty() } ?: packageName
        val ruleId = tracked?.ruleId
        emit(
            if (ruleId != null) {
                TriggerEvent(TriggerEventType.REMOVED, pkg, ruleId)
            } else {
                TriggerEvent.removed(pkg)
            },
        )
    }

    // ==================== 内部：key → (pkg, ruleId) 短表 ====================

    private fun track(key: String, packageName: String, ruleId: Long?) {
        if (key.isEmpty()) return
        synchronized(trackedKeys) { trackedKeys[key] = TrackedKey(packageName, ruleId) }
    }

    private fun forget(key: String): TrackedKey? {
        if (key.isEmpty()) return null
        return synchronized(trackedKeys) { trackedKeys.remove(key) }
    }
}
