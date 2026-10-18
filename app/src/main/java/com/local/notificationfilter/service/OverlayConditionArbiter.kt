package com.local.notificationfilter.service

import com.local.notificationfilter.actions.AggregationRecorder
import com.local.notificationfilter.core.model.AggregationLists
import com.local.notificationfilter.core.model.NotificationSnapshot
import com.local.notificationfilter.core.model.OverlayPayload
import com.local.notificationfilter.core.model.OverlayTrigger
import com.local.notificationfilter.core.model.OverlayTriggerKind
import com.local.notificationfilter.core.model.Rule
import com.local.notificationfilter.core.ruleengine.OverlayTriggerEvaluator
import com.local.notificationfilter.core.ruleengine.TriggerContext
import com.local.notificationfilter.core.ruleengine.TriggerEvent
import com.local.notificationfilter.core.ruleengine.TriggerEventType
import com.local.notificationfilter.data.repo.RuleRepository
import com.local.notificationfilter.util.NfLog
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap
import kotlin.collections.ifEmpty
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 条件 → 计划 的**倒排索引**（V7 需求 7，架构 §3.4 红线 3）。
 *
 * 编译期把「配了 `floatTrigger` 且 `floatIsConditional` 的启用规则」按条件 `kind` 分桶，
 * 运行期一次事件只做 `O(1)` 桶查 + `O(桶内计划数)`（通常 ≤ 3）求值，
 * **绝不遍历所有规则的所有条件**。
 *
 * 分桶口径（与 `OverlayTriggerKind` 一一对应）：
 * - [byPackage]：`APP_NOTIFICATION` 的 `packages` 元素 → 关心该包名的规则 id；
 * - [byRuleId]：`RULE_NOTIFICATION` 的 `ruleIds` 元素 → 关心「某条规则命中」的规则 id；
 * - [byForegroundPackage]：`FOREGROUND_APP` 的 `packages` 元素 → 关心该前台包名的规则 id；
 * - [deviceSensitive]：含 `DEVICE_STATE` 条件的规则（由设备状态流驱动）；
 * - [clockSensitive]：含 `CLOCK_AT`，或含「时间段 / 星期」子条件的规则（由分钟 ticker 驱动）；
 * - [stateful]：上面两者的并集（架构 §3.4 的字段名，供日志 / 自检）。
 *
 * `OCCUR` / `REMOVE` 边沿**不参与分桶**：它们只决定「桶里的规则怎么投票」，
 * 而「要不要进桶」只看包名 / 规则 id 是否被引用。两边沿共用同一桶，
 * 于是「通知出现 → 显示」和「通知消失 → 隐藏」按同一张索引工作。
 *
 * 纯 Kotlin（不碰 Android / 协程），可单测。
 */
internal class TriggerIndex(
    /** kind = APP_NOTIFICATION：包名 → 受影响的规则 id。 */
    val byPackage: Map<String, Set<Long>> = emptyMap(),
    /** kind = RULE_NOTIFICATION：被引用规则 id → 引用它的规则 id。 */
    val byRuleId: Map<Long, Set<Long>> = emptyMap(),
    /** kind = FOREGROUND_APP：包名 → 受影响的规则 id。 */
    val byForegroundPackage: Map<String, Set<Long>> = emptyMap(),
    /** kind = DEVICE_STATE：只受设备状态流驱动。 */
    val deviceSensitive: Set<Long> = emptySet(),
    /** kind = CLOCK_AT（或时间段 / 星期）：需要分钟 ticker。 */
    val clockSensitive: Set<Long> = emptySet(),
    /** 参与条件求值的规则本体（id → Rule），运行期取样式与聚合配置用。 */
    val rules: Map<Long, Rule> = emptyMap(),

    /**
     * V12：被条件的目标规则（V12.1 起可多选，见 [OverlayTrigger.allTargetRuleIds]）引用、
     * 且 [RuleActionConfig.float] 非空的目标规则（id → Rule）。
     * **不含**条件规则自身——它本来就在 [rules] 里。
     *
     * 用途：条件的「显示 / 立即清除」可以打到**别的规则**的悬浮窗上（用它自己的样式与
     * 内容），于是不同规则的悬浮窗能同时在屏幕上存在、互不顶掉。
     */
    val targets: Map<Long, Rule> = emptyMap(),
) {

    /** `DEVICE_STATE` / `CLOCK_AT` 的并集（架构 §3.4 `TriggerIndex.stateful`）。 */
    val stateful: Set<Long> get() = deviceSensitive + clockSensitive

    /** 是否需要分钟 ticker（有任何时间敏感条件才需要）。 */
    val needsClockTick: Boolean get() = clockSensitive.isNotEmpty()

    /** 是否需要订阅通知事件总线（没有任何通知类条件时零订阅）。 */
    val needsNotificationEvents: Boolean get() = byPackage.isNotEmpty() || byRuleId.isNotEmpty()

    /** 是否需要订阅前台 App 变化（没有任何 `FOREGROUND_APP` 条件时零订阅）。 */
    val needsForeground: Boolean get() = byForegroundPackage.isNotEmpty()

    /** 空索引 = **零运行时开销**（不订阅、不起 ticker、不查表）。 */
    val isEmpty: Boolean get() = rules.isEmpty()

    /**
     * 一条通知出现（`POSTED`）时，只返回「关心这个包名」的规则。
     *
     * @param pkg 通知所属包名
     */
    fun onPosted(pkg: String): Set<Long> = if (pkg.isEmpty()) emptySet() else byPackage[pkg].orEmpty()

    /**
     * 一条通知命中规则（`MATCHED`）时，只返回「引用了这条规则」的规则。
     *
     * @param ruleId 命中的规则 id
     */
    fun onMatched(ruleId: Long): Set<Long> = byRuleId[ruleId].orEmpty()

    /**
     * 前台 App 变化时，只返回「关心这个包名」的规则。
     *
     * @param pkg 新的前台包名（`null` = 未知，不匹配任何桶）
     */
    fun onForeground(pkg: String?): Set<Long> =
        if (pkg.isNullOrEmpty()) emptySet() else byForegroundPackage[pkg].orEmpty()

    companion object {

        /** 空索引（未编译 / 无任何条件规则时使用）。 */
        val EMPTY: TriggerIndex = TriggerIndex()

        /**
         * 编译规则列表 → 索引。
         *
         * 编译期丢弃（**第二道闸之外的第一道闸**，架构 §3.4 红线 2）：
         * - `rule.enabled == false`：停用规则不执行任何动作；
         * - `actions.float == null`：没有卡片就没有可控制的对象；
         * - `actions.floatIsConditional == false`：`floatTrigger` 为空或无效
         *   （`OverlayTriggerConfig.effective` 已剔除参数没配齐的条件）→ 走 V6 普通悬浮路径；
         * - `effective` 为空：同上（配置全是无效条件）。
         *
         * 参数没配齐的条件（`isEffective == false`）**不进桶也不参与求值**，
         * 于是「配错参数」不产生任何运行时成本，也不会变成恒真 / 恒假的坑。
         *
         * @param rules 全量规则（调用方保证是最新列表）
         */
        fun build(rules: List<Rule>): TriggerIndex {
            val byPackage = LinkedHashMap<String, MutableSet<Long>>()
            val byRuleId = LinkedHashMap<Long, MutableSet<Long>>()
            val byForeground = LinkedHashMap<String, MutableSet<Long>>()
            val deviceSensitive = LinkedHashSet<Long>()
            val clockSensitive = LinkedHashSet<Long>()
            val kept = LinkedHashMap<Long, Rule>()
            // V12：被条件的目标规则（V12.1 起可多选；有 float 才收；自身不收）
            val targets = LinkedHashMap<Long, Rule>()

            for (rule in rules) {
                if (!rule.enabled) continue
                // floatIsConditional 内部已判 float != null 且 floatTrigger 非空
                if (!rule.actions.floatIsConditional) continue
                val effective = rule.actions.floatTrigger?.effective ?: continue
                if (effective.isEmpty()) continue

                kept[rule.id] = rule
                var needsTick = false
                for (trigger in effective) {
                    // V12：收集「这条条件作用于哪条规则的悬浮窗」。目标从全量列表解析，
                    // 有 float 才进 targets（没有悬浮样式的规则被指向也没有窗口可作用）；
                    // 指向自身不进（它本来就在 rules 里）。
                    // V12.1：目标可多选（allTargetRuleIds = 新多选字段 ∪ 旧单字段），
                    // 一条条件可同时把多条规则收进 targets。
                    for (targetId in trigger.allTargetRuleIds) {
                        if (targetId == rule.id || targetId in targets) continue
                        rules.firstOrNull { it.id == targetId }
                            ?.takeIf { it.actions.float != null }
                            ?.let { targets[targetId] = it }
                    }
                    when (trigger.kindEnum) {
                        OverlayTriggerKind.APP_NOTIFICATION ->
                            trigger.packages.forEach { pkg ->
                                byPackage.getOrPut(pkg) { LinkedHashSet() }.add(rule.id)
                            }

                        OverlayTriggerKind.RULE_NOTIFICATION ->
                            trigger.ruleIds.forEach { refId ->
                                byRuleId.getOrPut(refId) { LinkedHashSet() }.add(rule.id)
                            }

                        OverlayTriggerKind.FOREGROUND_APP ->
                            trigger.packages.forEach { pkg ->
                                byForeground.getOrPut(pkg) { LinkedHashSet() }.add(rule.id)
                            }

                        OverlayTriggerKind.DEVICE_STATE -> {
                            deviceSensitive += rule.id
                            // 时间段 / 星期是"时间敏感"的：进出窗口没有任何事件可依附，
                            // 只能靠分钟 tick 重新求值（否则卡片永远不会按时消失）。
                            if (trigger.device.timeWindows.isNotEmpty() ||
                                trigger.device.weekdays.isNotEmpty()
                            ) {
                                needsTick = true
                            }
                        }

                        // CLOCK_AT 只由分钟 ticker 驱动，**不进** deviceSensitive
                        // （否则会白订阅设备状态流，还会被 DEVICE 来源的裁剪规则过滤成空列表）
                        OverlayTriggerKind.CLOCK_AT -> needsTick = true
                    }
                }
                if (needsTick) clockSensitive += rule.id
            }

            if (kept.isEmpty()) return EMPTY
            return TriggerIndex(
                byPackage = byPackage,
                byRuleId = byRuleId,
                byForegroundPackage = byForeground,
                deviceSensitive = deviceSensitive,
                clockSensitive = clockSensitive,
                rules = kept,
                targets = targets,
            )
        }
    }
}

/**
 * 条件卡片的**载荷构造器**（V7 需求 7/8）。
 *
 * 为什么用可注入的函数类型而不是在这里读 `ActionExecutor`：卡片载荷的渲染口径必须与
 * 「弹幕 / 悬浮 / 推迟重发」**完全一致**（模板优先级、`{itemsN}`、extras 大小写敏感），
 * 而唯一实现是 [com.local.notificationfilter.actions.ActionExecutor.buildOverlayPayload]。
 * 装配点把那个方法包一层传进来，于是「模板渲染只有一处实现」这条铁律在本类里不会被破坏，
 * 同时本类不必持有 `ActionExecutor`（它还需要监听服务实例，生命周期不一样）。
 *
 * 签名与 `ActionExecutor.buildOverlayPayload(rule, snapshot, titleTemplate, textTemplate, lists)`
 * 一一对应（非挂起：纯函数 + 已就绪的列表上下文）。
 */
fun interface OverlayPayloadBuilder {
    fun build(
        rule: Rule,
        snapshot: NotificationSnapshot,
        titleTemplate: String,
        textTemplate: String,
        lists: AggregationLists,
    ): OverlayPayload
}

/**
 * 条件悬浮编排器（V7 需求 7 + 8 的运行时，架构 §3.2 / §3.3 / §3.4 / §3.9；V12 扩展为多窗口）。
 *
 * 职责一句话：**把「事件 / 设备状态 / 分钟」翻译成「悬浮窗的显示 / 原地更新 / 撤下」**。
 *
 * 三条不变量（实现时逐条落实，改动前请先读）：
 * 1. **订阅数恒为 3~5 个**（与条件条数完全解耦）：本类各订阅**一次**
 *    `NotificationEventBus.events`、`DeviceStateProvider.state`、`ForegroundAppHolder.packageName`、
 *    一个分钟 ticker、以及用于重新编译的 `RuleRepository.observeAll()`。
 *    条件本身**永远不持有监听器**（红线 1）。
 * 2. **无规则 = 零开销**（红线 2）：[compile] 产出空 [TriggerIndex] 时立即退订全部事件源、
 *    不启动 ticker、撤下本类开过的悬浮窗；唯一保留的常驻成本是 `observeAll()` 的一次 Flow 订阅
 *    （否则规则新增后没人重编译）。
 * 3. **通知密集时不遍历所有规则**（红线 3）：只对 [TriggerIndex] 桶内的规则求值。
 *
 * 线程模型：内部单线程 scope（`Dispatchers.Default.limitedParallelism(1)`）——
 * 事件排队、求值、DB 读全部串行，`clockStates` 之类的可变状态因此不需要额外加锁；
 * [compile] 例外（可能由主线程 / 规则流线程调用），对共享表的修剪一律用
 * `ConcurrentHashMap` 的单键操作，不做跨线程遍历。
 *
 * 与 `OverlayController` 的分工：本类**不做任何窗口操作**，只调用
 * `OverlayController.showFloat` / `updateFloat` / `dismissFloat`（V12 起按目标规则
 * （V12.1 起可多选）id 一规则一窗，不同规则的悬浮窗互不顶掉）；
 * 「同 key 原地更新 vs 重建」由控制器内部判定
 * （它持有窗口与门面状态，判断才不会跨线程失真）。
 */
class OverlayConditionArbiter(
    private val ruleRepository: RuleRepository,
    private val recorder: AggregationRecorder,
    /**
     * 载荷构造器：装配点传 `ActionExecutor::buildOverlayPayload` 的包装（见 [OverlayPayloadBuilder]）。
     * **非空**：卡片没有内容就没有意义，缺这个依赖应该编译期暴露而不是运行期空白。
     */
    private val payloadBuilder: OverlayPayloadBuilder,
) {

    /** 单线程执行器：见类注释「线程模型」。 */
    @Volatile
    private var scope: CoroutineScope? = null

    /** 是否已 attach（由 [attach] / [detach] 维护；未 attach 时不订阅任何源）。 */
    @Volatile
    private var attached: Boolean = false

    /** 当前编译产物（[compile] 整体替换，读侧只取快照，不做增量修改）。 */
    @Volatile
    private var index: TriggerIndex = TriggerIndex.EMPTY

    /**
     * 「悬浮窗口 key（目标规则 id 字符串）→ 开它的 owner 规则 id」（V12）。
     *
     * 替代旧 `cardKey`（从「一张卡片」扩成「多扇窗」），只用于两处判断：
     * 1. [onForeground]：把当前正显示窗口的 owner 规则也带回求值（退出事件不在桶里）；
     * 2. [pruneRuntimeState]：目标规则已失效 → 撤下对应窗口。
     * 真实窗口是否存在由 `OverlayController.floatKeys` 表达（[refreshCard] 按它校验，
     * 用户刚清屏后绝不复活）。
     * 允许短暂失真：窗口被用户清屏 / 自动消失后条目仍在，但 showFloat 幂等、
     * refreshCard 有实况校验，失真不会导致错误的重复入场。
     */
    private val shownKeys = ConcurrentHashMap<String, Long>()

    private var eventsJob: Job? = null
    private var deviceJob: Job? = null
    private var foregroundJob: Job? = null
    private var tickerJob: Job? = null

    /** 规则变更监听（唯一常驻订阅；[detach] 时一并取消）。 */
    private var rulesJob: Job? = null

    /**
     * 「规则 id → 最近一次命中的通知快照」。
     *
     * 为什么需要：条件卡片的内容（标题 / 正文 / `{itemsN}` 之外的占位符）来自**触发它的那条
     * 通知**，而 `NotificationEventBus` 的事件只带包名与规则 id（T04 定型的形状，不带 snapshot）。
     * 因此管线在命中点调用 [rememberSnapshot] 补这一手；本表只为**条件规则**留存，
     * 无条件的规则一条都不会进（[rememberSnapshot] 入口即短路）。
     */
    private val lastSnapshots = ConcurrentHashMap<Long, NotificationSnapshot>()

    /** `CLOCK_AT` 的「今日已触发」集合（规则 id → 分钟键集合）。 */
    private val clockStates = ConcurrentHashMap<Long, ClockFireState>()

    init {
        // 聚合列表变化 → 重绘卡片内容（架构 §3.3 的 refreshCard）。
        // 在这里装配而不是在 AppContainer：装配处一旦形成
        // 「Arbiter 需要 Recorder、Recorder 回调 Arbiter」的构造环，懒加载会重入死锁。
        recorder.onItemsChanged = { ruleId -> refreshCard(ruleId) }
    }

    // ==================== 生命周期 ====================

    /**
     * 挂载：建立执行器 + 订阅规则变更流，并按当前 [index] 决定是否订阅事件源。
     *
     * **幂等**：重复调用只补建缺失的 job（重连场景 `attach → detach → attach` 安全）。
     * 调用点：`NfNotificationListener.onListenerConnected`（与 `DeviceStateProvider.attach` 同点）。
     */
    @Synchronized
    fun attach() {
        if (attached) {
            // 重复 attach（同一进程内多次 onListenerConnected）：只补齐缺失的订阅
            applySubscriptions()
            return
        }
        attached = true
        ensureScope()
        startRuleWatcher()
        applySubscriptions()
        NfLog.i(this, "条件编排器已挂载（条件规则 ${index.rules.size} 条）")
    }

    /**
     * 卸载：取消全部订阅（含 ticker 与规则流）、清空运行时状态、撤下本类开过的悬浮窗。
     *
     * **幂等**；调用点：`NfNotificationListener.teardown()`（断连 / 销毁共用）。
     * 索引本身（[index]）**不清空**——它只是编译产物，重连时 [attach] 会立刻按它恢复订阅。
     */
    @Synchronized
    fun detach() {
        attached = false
        stopSubscriptions()
        rulesJob?.cancel()
        rulesJob = null
        scope?.cancel()
        scope = null
        clearRuntimeState()
        // V12：撤下本类开过的所有悬浮窗（只撤 shownKeys 里的——业务悬浮不归本类管）。
        // detach/compile 可能在非主线程调用，dismissFloat 内部已 post 主线程，安全。
        shownKeys.keys.toList().forEach { OverlayController.dismissFloat(it) }
        shownKeys.clear()
        NfLog.i(this, "条件编排器已卸载")
    }

    /**
     * 编译规则列表 → 重建 [TriggerIndex]（**任何规则变更 / 启动时**调用一次）。
     *
     * 副作用（顺序固定）：
     * 1. 刷新 `AccessibilityGate` 的需求标记（只更新标记与 UI 状态，**不启停服务**，Q2）；
     * 2. 修剪已失效的运行时状态（被删规则 / 被改成无条件规则的历史快照、卡片）；
     * 3. 空索引 → 退订全部事件源 + 停 ticker + 撤卡（红线 2）；
     * 4. 否则按需补建 / 取消各订阅（已存在的不重建，避免重建丢事件）。
     *
     * 线程：可在任意线程调用（`@Synchronized`）；订阅本身投递到内部单线程 scope。
     *
     * @param rules 全量规则（最新列表）
     */
    @Synchronized
    fun compile(rules: List<Rule>) {
        val built = TriggerIndex.build(rules)
        val before = index.rules.size
        index = built
        // 无障碍需求标记：只更新状态，绝不启停服务
        runCatching { AccessibilityGate.sync(rules) }
            .onFailure { NfLog.w(this, "无障碍需求标记刷新失败: ${it.message}") }

        pruneRuntimeState(built)

        if (built.isEmpty) {
            stopSubscriptions()
            // V12：没有条件规则了 → 撤下本类开过的悬浮窗（业务悬浮不受影响）
            shownKeys.keys.toList().forEach { OverlayController.dismissFloat(it) }
            shownKeys.clear()
            if (before != 0) {
                NfLog.i(this, "条件索引已清空（无从条件规则），事件源全部退订")
            }
            return
        }
        NfLog.i(
            this,
            "条件索引已编译：规则 ${built.rules.size} 条 / 包名桶 ${built.byPackage.size} / " +
                "规则桶 ${built.byRuleId.size} / 前台桶 ${built.byForegroundPackage.size} / " +
                "设备 ${built.deviceSensitive.size} / 时钟 ${built.clockSensitive.size}",
        )
        applySubscriptions()
    }

    /** 从数据库读一次规则并编译（启动 / 重连时的便捷入口，省得调用方再拼 getAll）。 */
    suspend fun compileFromDb() {
        val rules = runCatching { ruleRepository.getAll() }
            .onFailure { NfLog.w(this, "读取规则失败，条件索引保持原状: ${it.message}") }
            .getOrDefault(emptyList())
        compile(rules)
    }

    // ==================== 外部钩子 ====================

    /**
     * 记住一条通知命中规则时的快照（管线在命中点调用，用于卡片内容渲染）。
     *
     * 除了「命中的这条规则自己」要吃这份快照，还要顺手喂给**引用了它的条件规则**
     * （条件 ⑤「满足某规则的通知出现」）：否则那张悬浮窗拿不到任何通知内容，
     * 只能渲染成一张合成快照（规则名 + 模板）。命中规则本身通常不是条件规则，
     * 所以这一步是条件 ⑤ 能看到真实内容的唯一途径。
     *
     * V12：目标规则（V12.1 起可多选）同样要留存快照——它的悬浮窗由条件驱动
     * 显示，没有快照就只剩合成内容（规则名 + 模板）。
     *
     * **零成本保证**：无任何条件规则时 `index.isEmpty` 直接返回；既不是条件规则 /
     * 目标规则、也没有任何规则引用它时直接返回。因此没有条件悬浮的用户，每条通知只多两次判断。
     *
     * @param ruleId 命中的规则 id
     * @param snapshot 本次命中的通知快照
     */
    fun rememberSnapshot(ruleId: Long, snapshot: NotificationSnapshot) {
        val current = index
        if (current.isEmpty) return
        val isConditional = ruleId in current.rules || ruleId in current.targets
        val referencing = current.byRuleId[ruleId].orEmpty()
        if (!isConditional && referencing.isEmpty()) return
        if (lastSnapshots.size >= MAX_SNAPSHOTS) {
            // 规则数量级极小（人工维护），超限只可能是脏数据；整体清空比 LRU 记账更划算
            lastSnapshots.clear()
        }
        if (isConditional) lastSnapshots[ruleId] = snapshot
        for (referencingRuleId in referencing) {
            lastSnapshots[referencingRuleId] = snapshot
        }
    }

    /**
     * 请求重绘某条规则的悬浮窗内容（聚合列表新增条目后调用）。
     *
     * 「窗口还在不在」的判定**必须放进行内执行**（在单线程 scope 上排队）：
     * 回调来自管线线程，它比编排器自己的「显示」流程更早或更晚都可能。放进 scope 后，
     * 判定的执行顺序天然排在之前那次 `applyShow` 之后，于是「新条目到了但窗口还显示旧内容」
     * 不会发生；反过来用户刚点了「清除屏幕显示」时窗口已不在，本次刷新直接跳过，
     * **绝不会把用户清掉的悬浮重新拉起来**。
     *
     * V12：入口放宽到目标规则（[TriggerIndex.targets]）——它的悬浮窗同样可能是条件开的，
     * 聚合条目变了也要刷新。
     *
     * @param ruleId 规则 id
     */
    fun refreshCard(ruleId: Long) {
        val current = index
        if (current.isEmpty) return
        if (ruleId !in current.rules && ruleId !in current.targets) return
        val s = scope ?: return
        if (!attached) return
        s.launch {
            // 窗口不在（用户刚清屏 / 已自动消失 / 服务断连全清）→ 绝不复活
            if (ruleId.toString() !in OverlayController.floatKeys) return@launch
            val rule = index.rules[ruleId] ?: index.targets[ruleId] ?: return@launch
            val payload = buildPayload(rule) ?: return@launch
            OverlayController.updateFloat(rule.id.toString(), payload)
        }
    }

    // ==================== 事件源 ====================

    /** 建立（或复用）单线程执行器。 */
    private fun ensureScope() {
        if (scope?.isActive == true) return
        scope = CoroutineScope(
            SupervisorJob() + Dispatchers.Default.limitedParallelism(1),
        )
    }

    /**
     * 订阅规则变更流（唯一的常驻订阅）。
     *
     * 去重口径：只取「影响条件编译的字段」做投影，于是「只改个名字 / 只改模板」不会重编译
     * （架构 §3.4 红线 2 的注意事项）。投影里刻意**不含** `titleTemplate` /
     * `textTemplate`：它们只影响渲染内容，而渲染在每次显示时实时读取最新规则，
     * 不需要重建索引。
     */
    @Synchronized
    private fun startRuleWatcher() {
        if (rulesJob != null) return
        val s = scope ?: return
        rulesJob = s.launch {
            ruleRepository.observeAll()
                .map { rules -> rules to projectionOf(rules) }
                .distinctUntilChanged { old, new -> old.second == new.second }
                .collect { (rules, _) -> compile(rules) }
        }
    }

    /** 条件编译相关的投影（用于 `distinctUntilChanged` 去重）。 */
    private fun projectionOf(rules: List<Rule>): List<String> = rules.map { rule ->
        buildString {
            append(rule.id).append('|')
            append(rule.enabled).append('|')
            append(rule.actions.float != null).append('|')
            append(rule.actions.floatTrigger?.triggers?.joinToString(";") { it.toString() })
        }
    }

    /**
     * 按当前 [index] 调整事件源订阅（只补建缺失的、只取消不再需要的）。
     *
     * 为什么要「按需」而不是「全订阅」：无 `FOREGROUND_APP` 条件时不订阅前台包名流，
     * 无 `CLOCK_AT` / 时间段条件时不起 ticker —— 这正是「配错条件 / 没配条件零成本」的落地点。
     */
    @Synchronized
    private fun applySubscriptions() {
        val current = index
        if (!attached || current.isEmpty) {
            stopSubscriptions()
            return
        }
        val s = ensureScopeAndGet() ?: return

        // 1) 通知事件总线：只有存在通知类条件时才订阅（发射侧 hasSubscribers 短路）
        if (current.needsNotificationEvents) {
            if (eventsJob == null) {
                eventsJob = s.launch {
                    NotificationEventBus.events.collect { event -> onEvent(event) }
                }
            }
        } else {
            eventsJob?.cancel()
            eventsJob = null
        }

        // 2) 设备状态流：只有存在 DEVICE_STATE 条件时才订阅
        if (current.deviceSensitive.isNotEmpty()) {
            if (deviceJob == null) {
                deviceJob = s.launch {
                    DeviceStateProvider.state.collect { onDeviceState() }
                }
            }
        } else {
            deviceJob?.cancel()
            deviceJob = null
        }

        // 3) 前台包名流：只有存在 FOREGROUND_APP 条件时才订阅
        if (current.needsForeground) {
            if (foregroundJob == null) {
                foregroundJob = s.launch {
                    ForegroundAppHolder.packageName.collect { pkg -> onForeground(pkg) }
                }
            }
        } else {
            foregroundJob?.cancel()
            foregroundJob = null
        }

        // 4) 分钟 ticker：只有时间敏感条件才启动
        if (current.needsClockTick) {
            if (tickerJob == null) {
                tickerJob = s.launch { runMinuteTicker() }
            }
        } else {
            tickerJob?.cancel()
            tickerJob = null
        }
    }

    /** 取消全部事件源订阅（不动 [rulesJob]：它是重编译的唯一通道）。 */
    @Synchronized
    private fun stopSubscriptions() {
        eventsJob?.cancel()
        eventsJob = null
        deviceJob?.cancel()
        deviceJob = null
        foregroundJob?.cancel()
        foregroundJob = null
        tickerJob?.cancel()
        tickerJob = null
    }

    private fun ensureScopeAndGet(): CoroutineScope? {
        ensureScope()
        return scope
    }

    // ==================== 求值 ====================

    /** 通知事件（`POSTED` / `MATCHED` / `REMOVED`）。 */
    private suspend fun onEvent(event: TriggerEvent) {
        val current = index
        if (current.isEmpty) return
        val ids: Set<Long> = when (event.type) {
            TriggerEventType.POSTED -> current.onPosted(event.packageName)

            TriggerEventType.MATCHED ->
                event.ruleId?.let { current.onMatched(it) }.orEmpty()

            // 消失边沿两条路：APP_NOTIFICATION（按包名）与 RULE_NOTIFICATION（按规则 id）
            TriggerEventType.REMOVED -> {
                val byPkg = current.onPosted(event.packageName)
                val byRule = event.ruleId?.let { current.onMatched(it) }.orEmpty()
                if (byRule.isEmpty()) byPkg else byPkg + byRule
            }
        }
        if (ids.isEmpty()) return
        val now = System.currentTimeMillis()
        for (ruleId in ids) {
            evaluate(ruleId, event, Source.NOTIFICATION, now)
        }
    }

    /** 设备状态变化（屏幕亮灭 / 插拔电）。 */
    private suspend fun onDeviceState() {
        val current = index
        if (current.deviceSensitive.isEmpty()) return
        val now = System.currentTimeMillis()
        for (ruleId in current.deviceSensitive) {
            evaluate(ruleId, null, Source.DEVICE, now)
        }
    }

    /**
     * 前台 App 变化。
     *
     * 注意除桶内规则外还要**再求值「当前正显示窗口的 owner 规则」**：
     * 「打开支付宝显示 → 退出就消失」的退出事件发生在**别的包名**上，
     * 那个包名不在该规则的桶里（桶只记支付宝），不带上当前 owner 就会永远不消失。
     * V12：窗口可能开在多扇（不同目标规则），故把 [shownKeys] 里所有 owner 都带上。
     */
    private suspend fun onForeground(pkg: String?) {
        val current = index
        val ids = LinkedHashSet<Long>()
        ids += current.onForeground(pkg)
        shownKeys.values.forEach { ownerId -> if (ownerId in current.rules) ids += ownerId }
        if (ids.isEmpty()) return
        val now = System.currentTimeMillis()
        for (ruleId in ids) {
            evaluate(ruleId, null, Source.FOREGROUND, now)
        }
    }

    /** 分钟到点（整分钟对齐，见 [runMinuteTicker]）。 */
    private suspend fun onClockTick() {
        val current = index
        if (current.clockSensitive.isEmpty()) return
        val now = System.currentTimeMillis()
        for (ruleId in current.clockSensitive) {
            evaluate(ruleId, null, Source.CLOCK, now)
        }
    }

    /**
     * 对**一条**规则做一次求值并落地决策。
     *
     * @param ruleId 目标规则
     * @param event 本次事件（仅 [Source.NOTIFICATION] 会进上下文）
     * @param source 事件来源（决定哪些条件参与投票，见 [triggersFor]）
     * @param now 求值时刻（epoch 毫秒，注入而非内部取，便于对齐分钟边界）
     */
    private suspend fun evaluate(
        ruleId: Long,
        event: TriggerEvent?,
        source: Source,
        now: Long,
    ) {
        val current = index
        val rule = current.rules[ruleId] ?: return
        val config = rule.actions.floatTrigger ?: return
        val triggers = triggersFor(config.effective, source)
        if (triggers.isEmpty()) return

        val context = TriggerContext(
            deviceState = DeviceStateProvider.current,
            now = now,
            lastEvent = if (source == Source.NOTIFICATION) event else null,
            // 无障碍服务未连接 / 未授权时为 null：`FOREGROUND_APP` 条件因此「不可判定」→
            // 不投票（既不误显示也不误隐藏），与架构 §3.5 口径一致，无需额外告警
            foregroundPackage = ForegroundAppHolder.current,
            firedClockKeys = clockKeysOf(ruleId, now),
        )
        // 求值上下文（含 firedClockKeys 快照）已在上面取好，这里再登记「本分钟已触发」：
        // 上下文是不可变快照，登记不会影响本次求值；而同一分钟的后续 tick 会被挡掉，
        // 于是 CLOCK_AT 每分钟只生效一次（顺序反了才会被自己挡掉）。
        markClockFired(ruleId, triggers, now)

        // V12：按「目标规则（V12.1 起可多选）」分组独立求值——同一规则的多条条件可以
        // 分别作用于不同规则的悬浮窗（目标为空 = 本条规则自己的），各组互不影响、可同时显示；
        // V12.1：一条条件可同时进多个目标组（allTargetRuleIds 多选）。
        // 组内多条仍是 OR、HIDE 优先（由 OverlayTriggerEvaluator.decide 保证）。
        val byTarget = LinkedHashMap<Long, MutableList<OverlayTrigger>>()
        for (trigger in triggers) {
            val targetIds = trigger.allTargetRuleIds.ifEmpty { setOf(ruleId) }
            for (targetId in targetIds) {
                byTarget.getOrPut(targetId) { mutableListOf() }.add(trigger)
            }
        }
        for ((targetId, subset) in byTarget) {
            val subsetDecision = OverlayTriggerEvaluator.decide(subset, context)
            when {
                subsetDecision.shouldHide -> hideCard(ruleId, targetId)
                subsetDecision.shouldShow -> applyShow(rule, targetId)
                else -> Unit // isNoop：保持现状
            }
        }
    }

    /**
     * 按事件来源过滤参与投票的条件。
     *
     * 为什么必须过滤：`OverlayTriggerEvaluator` 对「没有事件上下文」的通知类条件返回
     * `false`（**不是** `null`），而 `hideWhenFalse = true` 的条件会把 `false` 变成 HIDE 投票。
     * 若不按来源裁剪，一次「屏幕亮起」就会让「微信通知消失→隐藏」的条件投出 HIDE，
     * 把卡片误撤下。裁剪后每个来源只让「它能判定」的条件投票。
     *
     * - [Source.NOTIFICATION]：全部条件（设备 / 前台条件此时也是可判定的真实状态，
     *   参与投票才能实现「在时间段内且收到某 App 通知才显示」这种组合门槛）；
     * - [Source.DEVICE]：只 `DEVICE_STATE`；
     * - [Source.CLOCK]：`CLOCK_AT` + `DEVICE_STATE`（时间窗口进出必须靠 tick 复判）；
     * - [Source.FOREGROUND]：只 `FOREGROUND_APP`。
     */
    private fun triggersFor(
        triggers: List<OverlayTrigger>,
        source: Source,
    ): List<OverlayTrigger> = when (source) {
        Source.NOTIFICATION -> triggers
        Source.DEVICE -> triggers.filter { it.kindEnum == OverlayTriggerKind.DEVICE_STATE }
        Source.CLOCK -> triggers.filter {
            it.kindEnum == OverlayTriggerKind.CLOCK_AT ||
                it.kindEnum == OverlayTriggerKind.DEVICE_STATE
        }

        Source.FOREGROUND -> triggers.filter { it.kindEnum == OverlayTriggerKind.FOREGROUND_APP }
    }

    // ==================== 决策落地 ====================

    /**
     * 显示 / 原地更新某个目标规则的悬浮窗（V12）。
     *
     * 「同 key 同样式 → 原地更新」的判定刻意放在 `OverlayController.showFloat` 内部：
     * 只有那里同时持有窗口与 Compose 状态，判断不会跨线程失真；本类重复调用它是幂等的，
     * 也不会重播入场动画（架构 §3.4 红线 4）。
     *
     * @param owner 条件所属的规则（求值入口那条）
     * @param targetId 目标规则 id：等于 [owner] 的 id = 作用于本条规则自己的悬浮窗；
     *   否则从 [TriggerIndex.targets] 取目标规则本体（用它的样式与内容开窗）
     */
    private suspend fun applyShow(owner: Rule, targetId: Long) {
        val target = if (targetId == owner.id) {
            owner
        } else {
            index.targets[targetId] ?: run {
                NfLog.w(this, "条件目标规则不存在或未配置悬浮 target=$targetId owner=${owner.id}")
                return
            }
        }
        val style = target.actions.float ?: return
        val key = target.id.toString()
        val payload = buildPayload(target) ?: return
        // V12：样式只做**分隔符修补**——渲染层用「标题 + 分隔符 + 正文」拼文案，正文为空
        // 时会渲染成「规则名 · 」这种半截字符串。其余字段（含 touchable）原样透传：
        // 业务悬浮已统一走普通窗口口径，不再由本类推导窗口差异。
        val fixedStyle = style.copy(
            separator = if (payload.text.isBlank()) "" else style.separator,
        )
        val shown = OverlayController.showFloat(key, payload, fixedStyle)
        if (shown) {
            shownKeys[key] = owner.id
        } else {
            NfLog.w(this, "条件触发显示悬浮失败（无悬浮窗权限或未初始化）rule=${target.id}")
        }
    }

    /**
     * 立即清除某个目标规则的悬浮窗（V12）。
     *
     * 用户原话：「设定了条件指定某个 APP 的通知消失，那么悬浮就清除，就立即执行清除」。
     * V12 起一规则一窗，这里按 key 精确撤下**目标规则**的那一扇，
     * 不会波及同屏其他规则的悬浮窗。
     *
     * @param ownerRuleId 条件所属的规则（日志用）
     * @param targetId 目标规则 id（= 要撤下的窗口 key）
     */
    private fun hideCard(ownerRuleId: Long, targetId: Long) {
        val key = targetId.toString()
        NfLog.d(this, "条件命中，立即清除悬浮 owner=$ownerRuleId target=$targetId")
        OverlayController.dismissFloat(key)
        shownKeys.remove(key)
    }

    /**
     * 组装卡片内容载荷。
     *
     * **渲染不在这里做**：模板解析与 `{itemsN}` 的唯一实现是
     * [com.local.notificationfilter.actions.ActionExecutor.buildOverlayPayload]（经 [payloadBuilder]
     * 注入）。本方法只负责它该负责的三件事：
     * 1. 准备输入：该规则当前聚合列表（读库一次）+ 触发它的通知快照（没有则合成）；
     * 2. 决定模板优先级：`FloatStyle.titleTemplate/textTemplate`（卡片自己的样式配置）优先，
     *    留空回落 `AggregationConfig.titleTemplate/textTemplate`（聚合组合模板）；
     * 3. 卡片口径的两点修补（**不重新渲染**，只改空值 / 重复文案）：
     *    - 标题渲染为空（典型：模板只写了 `{items1}` 而列表还没攒到条目）→ 回落快照标题 / 规则名，
     *      否则会出现一张只有边框的空白卡片；
     *    - 正文与标题相同（典型：没有任何真实通知内容）→ 正文置空，避免「取件码 · 取件码」。
     *
     * @return 载荷；没有卡片样式 / 渲染抛异常时返回 null（调用方跳过本次显示）
     */
    private suspend fun buildPayload(rule: Rule): OverlayPayload? {
        val style = rule.actions.float ?: return null
        val aggregation = rule.actions.aggregation?.takeIf { it.enabled }
        val lists: AggregationLists =
            if (aggregation != null) recorder.lists(rule) else AggregationLists.EMPTY
        val snapshot = lastSnapshots[rule.id] ?: syntheticSnapshot(rule)

        val titleTemplate = style.titleTemplate.ifBlank { aggregation?.titleTemplate.orEmpty() }
        val textTemplate = style.textTemplate.ifBlank { aggregation?.textTemplate.orEmpty() }
        val built = runCatching {
            payloadBuilder.build(rule, snapshot, titleTemplate, textTemplate, lists)
        }.onFailure {
            NfLog.w(this, "渲染悬浮窗载荷失败 rule=${rule.id}: ${it.message}")
        }.getOrNull() ?: return null

        val title = built.title.ifBlank {
            snapshot.displayTitle.ifBlank { rule.name }
        }
        val text = if (built.text.isBlank() || built.text == title) "" else built.text
        return built.copy(title = title, text = text)
    }

    /**
     * 没有「本次命中的通知」时的合成快照（例如「打开支付宝 → 显示」在进程启动后
     * 第一次触发时，还没有任何通知命中过这条规则）。
     *
     * 用 `appName = rule.name` 是为了让 `displayTitle` 至少是规则名（否则会出现一张空白卡片）；
     * `packageName` 取规则作用范围里唯一的包名，卡片图标才有得显示（取不到就回落首字母）。
     */
    private fun syntheticSnapshot(rule: Rule): NotificationSnapshot {
        val singlePackage = rule.scope.packages.singleOrNull().orEmpty()
        return NotificationSnapshot(
            key = "",
            notifId = 0,
            packageName = singlePackage,
            appName = rule.name,
        )
    }

    // ==================== CLOCK_AT 去重 ====================

    /**
     * 本规则今日已触发过的 `CLOCK_AT` 分钟键（跨天自动复位）。
     *
     * 键的语义与 `OverlayTriggerEvaluator.clockKey` 完全一致（`时 × 60 + 分`）：
     * 求值器发现某个键已在集合里就返回 `null`（不再投票），于是「22:00 隐藏」
     * 在同一分钟内只生效一次；过了这一分钟，后续的新通知仍可正常显示卡片。
     */
    private fun clockKeysOf(ruleId: Long, now: Long): Set<Int> {
        val state = clockStates[ruleId] ?: return emptySet()
        val day = epochDayOf(now)
        if (state.epochDay != day) {
            // 跨天：只清自己的键（另一个线程可能同时在清别的规则，故用单键操作）
            state.epochDay = day
            state.keys.clear()
        }
        return state.keys
    }

    /**
     * 本次求值里「到点成立」的 `CLOCK_AT` 条件登记为已触发。
     *
     * 只在条件真的进入了本次求值列表时才登记（[triggers] 已是按来源裁剪后的列表）：
     * 否则「屏幕亮起」这类与时钟无关的事件会把 22:00 的触发提前标记成已用掉。
     */
    private fun markClockFired(ruleId: Long, triggers: List<OverlayTrigger>, now: Long) {
        if (triggers.none { it.kindEnum == OverlayTriggerKind.CLOCK_AT }) return
        val minute = OverlayTriggerEvaluator.minuteOfDay(now) ?: return
        val day = epochDayOf(now)
        val state = clockStates.computeIfAbsent(ruleId) { ClockFireState() }
        if (state.epochDay != day) {
            state.epochDay = day
            state.keys.clear()
        }
        for (trigger in triggers) {
            if (trigger.kindEnum != OverlayTriggerKind.CLOCK_AT) continue
            val key = OverlayTriggerEvaluator.clockKey(trigger.clockAt) ?: continue
            if (key == minute) state.keys.add(key)
        }
    }

    // ==================== 分钟 ticker ====================

    /**
     * 分钟 ticker：**对齐到下一个整分钟**再求值（`delay` 到边界，不用 AlarmManager）。
     *
     * 为什么对齐：`CLOCK_AT` 的粒度是「分钟」，若从任意时刻起每秒/每 60 秒跳一次，
     * 22:00 这个点可能整分钟都被跳过（例如 21:59:40 起跳 + 60s = 22:00:40 命中，
     * 但若起跳点在 22:00:05 则会直接跳到 22:01）。对齐边界后每次 tick 都落在整分钟内，
     * 一定覆盖到配置的那一分钟。
     *
     * 复杂度：一次 tick 只遍历 [TriggerIndex.clockSensitive]（通常 ≤ 5 条规则）。
     */
    private suspend fun runMinuteTicker() {
        while (currentCoroutineContext().isActive) {
            val now = System.currentTimeMillis()
            val untilNextMinute = MINUTE_MS - (now % MINUTE_MS)
            // 至少等 200ms：避免极端情况下算出 0 / 负值导致忙循环
            delay(untilNextMinute.coerceIn(MIN_DELAY_MS, MINUTE_MS))
            runCatching { onClockTick() }
                .onFailure { NfLog.w(this, "分钟 tick 求值失败: ${it.message}") }
        }
    }

    // ==================== 内部工具 ====================

    /** 修剪运行时状态：规则被删 / 改成无条件后，其快照与时钟状态不再保留。 */
    private fun pruneRuntimeState(built: TriggerIndex) {
        // V12：有效 id = 条件规则 ∪ 目标规则（两者的快照 / 窗口都可能还开着）
        val valid = built.rules.keys + built.targets.keys
        // ConcurrentHashMap 不支持跨线程遍历式删除的场景，逐个单键操作最稳
        for (ruleId in lastSnapshots.keys.toList()) {
            if (ruleId !in valid) lastSnapshots.remove(ruleId)
        }
        for (ruleId in clockStates.keys.toList()) {
            if (ruleId !in valid) clockStates.remove(ruleId)
        }
        // shownKeys 里引用的目标规则已失效（被删 / 被改成没有悬浮样式）→ 立刻撤下对应窗口，
        // 否则它会永久留在屏幕上（没有别的路径会来撤它）
        for (key in shownKeys.keys.toList()) {
            val targetId = key.toLongOrNull()
            if (targetId == null || targetId !in valid) {
                OverlayController.dismissFloat(key)
                shownKeys.remove(key)
            }
        }
    }

    private fun clearRuntimeState() {
        clockStates.clear()
        lastSnapshots.clear()
        shownKeys.clear()
    }

    /** epoch 毫秒 → 本地日序号（判断 `CLOCK_AT` 的「今日」是否翻篇）。 */
    private fun epochDayOf(nowMillis: Long): Long =
        Instant.ofEpochMilli(nowMillis).atZone(ZoneId.systemDefault()).toLocalDate().toEpochDay()

    /** 求值来源（决定哪些条件参与投票，见 [triggersFor]）。 */
    private enum class Source {
        /** 通知出现 / 命中 / 消失。 */
        NOTIFICATION,

        /** 设备状态（屏幕亮灭 / 充电）。 */
        DEVICE,

        /** 分钟到点。 */
        CLOCK,

        /** 前台 App 变化。 */
        FOREGROUND,
    }

    /** 一条规则的 `CLOCK_AT` 去重状态（只在单线程 scope 与 [compile] 锁内访问）。 */
    private class ClockFireState {
        /** 最近的本地日序号（变了就清空 [keys]）。 */
        var epochDay: Long = Long.MIN_VALUE

        /** 今日已触发过的分钟键。 */
        val keys: MutableSet<Int> = HashSet()
    }

    private companion object {
        /** 一分钟毫秒数（ticker 对齐用）。 */
        const val MINUTE_MS: Long = 60_000L

        /** ticker 单次最小等待（防忙循环）。 */
        const val MIN_DELAY_MS: Long = 200L

        /** 「规则 id → 快照」表的容量上限（规则数量级极小，超限即视为脏数据）。 */
        const val MAX_SNAPSHOTS: Int = 64
    }
}
