package com.local.notificationfilter.service

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.local.notificationfilter.AppContainer
import com.local.notificationfilter.core.model.ActionType
import com.local.notificationfilter.core.model.FieldRef
import com.local.notificationfilter.core.model.NfJson
import com.local.notificationfilter.core.model.NotificationRecord
import com.local.notificationfilter.core.model.NotificationSnapshot
import com.local.notificationfilter.core.model.ResultKind
import com.local.notificationfilter.core.model.Rule
import com.local.notificationfilter.core.ruleengine.FieldExtractor
import com.local.notificationfilter.core.ruleengine.RuleEngine
import com.local.notificationfilter.util.AppUtils
import com.local.notificationfilter.util.NfLog
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.PairSerializer
import kotlinx.serialization.builtins.serializer

/**
 * 通知处理管线（T04）：全量记流水 → 总开关 → 黑名单 → 规则匹配 → 动作执行 → 统计。
 *
 * 关键约定：
 * - 自身包名通知直接跳过（防递归：重发出去的通知会再次被监听收到）；
 * - 处理串行化（单线程 Dispatcher + Mutex），保证同一 key 的顺序性；
 * - 系统 snooze 到点以相同 key 重新到达：只把已有流水标记「已恢复」，
 *   不重复匹配规则、不重复计数（DB 判定，进程重启后依然成立）；
 * - 同一 key 的**内容未变**重复投递（音乐/下载/导航/计时器的进度刷新）在内存指纹层直接
 *   丢弃（P1-6）：不写流水、不计统计、不跑规则、不执行动作；
 * - V10.11：同一 key 的**内容有变**的连续更新（进度百分比在跳的下载 / 备份 / 音乐通知）
 *   在流水层**就地合并**——一条存活通知始终只占一行，内容随更新刷新、统计只计一次；
 *   只有收到 `onRemoved`（这条通知真的没了）之后同 key 再来，才另起一行。
 *   这是「记录页被同一条通知刷屏」的根因修复，详见 [liveRecords]；
 * - V7（需求 7/8）：命中点向 `NotificationEventBus` 发射 `Posted / Matched / Removed` 三类事件、
 *   把命中快照交给 `OverlayConditionArbiter`、并在动作执行**之前**完成一次聚合录制
 *   （顺序见 [handle] 内的注释；这四处是条件卡片与聚合汇总的唯一输入口）；
 * - 所有系统调用失败只记日志并在 executedActions 留痕，服务不崩溃（约定 8）。
 */
class NotificationPipeline(
    private val container: AppContainer,
    private val service: NotificationListenerService,
) {

    private val appContext = service.applicationContext

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1))

    private val mutex = Mutex()

    /**
     * 同一 key 的「内容指纹」表（P1-6）：key → 上次处理时的内容指纹。
     *
     * 用途：`onNotificationPosted` 在通知**内容更新**时也会回调（音乐进度每秒变、下载进度、
     * 导航距离、计时器），这些更新与首达完全同一条流水，若不区分会导致流水被同一 key 刷屏、
     * 命中弹幕规则时每秒弹一条、每条都跑 5 次 DB 写 + `5 × 规则数` 次 JSON 解析。
     *
     * 实现：`accessOrder = true` 的 LRU，容量上限 [MAX_FINGERPRINTS]（超限自动淘汰最久未用）；
     * 纯内存、不落库、无迁移，进程重启即清空（重启后首条通知按「新通知」处理，行为正确）。
     *
     * **线程安全前提**：本表只在 [handle]（[mutex] 之内）与 [onRemoved]（同一 [scope]）中读写，
     * 而 [scope] 是 `Dispatchers.Default.limitedParallelism(1)`——同一时刻最多一个协程在跑，
     * 故无需额外加锁。**新增访问点必须同样投递到 [scope]，切勿在主线程直接读写本表。**
     */
    private val recentFingerprints = object : LinkedHashMap<String, Long>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?): Boolean =
            size > MAX_FINGERPRINTS
    }


    /**
     * V10.7 耗电修复：同 key **最近一次真正进入管线**的时刻（ms）。
     *
     * 与 [recentFingerprints] 分工不同：指纹管"内容有没有变"，这里管"变得太频繁就先不处理"。
     * 指纹在 V10.2 把 `progress` 纳入后，音乐/下载类通知每秒都算"内容变了"，
     * 去重形同失效 → 每秒一次完整链路（3 事务 / 7 SQL + 匹配 + 可能新建弹幕窗口）。
     */
    private val recentHandledAt = object : LinkedHashMap<String, Long>(64, 0.75f, true) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<String, Long>?,
        ): Boolean = size > 128
    }

    /**
     * V10.11 刷屏修复：**「这个 key 当前占着哪一行流水」** —— key → (recordId, 最后写入时刻)。
     *
     * 为什么还需要它（指纹 + 节流都挡不住刷屏）：
     * - [recentFingerprints] 只在**内容完全没变**时才跳过。下载 / 备份 / 音乐 / 导航这类通知的
     *   进度就在变，于是每一条更新都被判为「内容变了」→ 每一条都走完整链路 → 每一条都
     *   `insertRecord()` 插新行。指纹去重的语义是「同一次投递去重」，不是「同一条通知合并」。
     * - [recentHandledAt] 的 [MIN_HANDLE_INTERVAL_MS] 只是把频率从「1 秒 1 行」压到
     *   「1.2 秒 1 行」。一个跑 1 小时的下载 = 3000 行，`RecordRepository.KEEP` 才 1000 条 →
     *   **一条通知就能把整个流水表（和记录页最近 100 条）刷满**，用户看到的就是"刷屏"。
     *
     * 真正正确的语义是：**一条存活通知在流水里只占一行，内容随更新刷新**。
     * 于是这里记住「key → 行号」，后续更新直接 `updateSnapshot()` 改写那一行。
     *
     * 什么时候不再合并（[LiveRow.lastWriteAt] 窗口 + 显式失效两条保险）：
     * 1. [onRemoved] 收到该 key 的移除广播 → `remove(key)`。通知真的消失了，同 key 再来
     *    就是一条**新通知**（例如看完一条微信后对方又发来一条，key 完全相同）。
     * 2. 距上次写入超过 [RecordCoalescer.WINDOW_MS] → 保守地认为这是新的一轮，另起一行。
     *    这是给「onRemoved 丢失」（服务被系统重启、广播漏发）兜底，避免把三天前的同 key
     *    记录一直改写下去（那行会永远沉在列表底部，用户看不到新通知）。
     *
     * 容量 [MAX_LIVE_RECORDS]：只需覆盖"同时在刷新的通知条数"，128 足够，内存占用可忽略。
     * **线程安全前提与 [recentFingerprints] 完全一致**：只在 [handle] / [reprocessActive]（[mutex] 内）
     * 与 [onRemoved]（同一单线程 [scope]）中读写。
     */
    private val liveRecords = object : LinkedHashMap<String, LiveRow>(64, 0.75f, true) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<String, LiveRow>?,
        ): Boolean = size > MAX_LIVE_RECORDS
    }

    /** [liveRecords] 的值：该 key 当前占用的流水行号 + 最后一次写入时刻。 */
    private class LiveRow(val recordId: Long, var lastWriteAt: Long) {
        /**
         * 总开关暂停期间是否已把这一行的结论拉回「放行」。
         *
         * 存在的理由：暂停时 `handle()` 只记流水、不跑规则，但**合并路径会保留这一行原来的
         * `result`**（比如暂停前它是「消除」）。那样用户会看到一条明明是放行的通知挂着
         * 「消除」，是个会误导人的陈旧状态。这里记一个「已拉回」的标记，
         * 保证每条通知在暂停期间只多做**一次** UPDATE，而不是每次刷新都写。
         */
        var pausedReset: Boolean = false
    }

    /** 异步入口：系统回调（主线程）只负责投递。 */
    fun process(sbn: StatusBarNotification) {
        scope.launch { handle(sbn) }
    }

    /**
     * V10 任务 A：**「规则保存成功 → 立即对通知栏里已有的通知重跑一遍」的异步入口**。
     *
     * 调用方（`NfNotificationListener` / `RuleEditViewModel`）只投递，不等待结果；
     * 真正的批量处理在 [scope]（单线程）里与 [handle] 串行执行，天然满足「同 key 顺序性」。
     * 管线已被 [shutdown]（断连 / 销毁）时 `scope` 已取消，`launch` 不会有任何副作用。
     *
     * @param reason 触发原因（只用于日志，便于真机排查「这次补处理是谁发起的」）
     */
    fun reprocessActiveAfterRuleChange(rule: Rule, reason: String = "规则保存") {
        // V10.3：**只带刚保存的这一条规则**。
        // 之前传的是"全部规则"，于是保存一条新规则会拿整套规则重跑通知栏，
        // 既做了用户没要求的处理，也可能把别的规则已处理过的通知重复处理一遍。
        scope.launch { reprocessActive(activeStatusBarNotifications(), reason, listOf(rule)) }
    }

    /** 读通知栏存量快照；任何失败（服务未连接 / binder 死）→ 空列表，不抛异常。 */
    private fun activeStatusBarNotifications(): List<StatusBarNotification> =
        runCatching { service.activeNotifications.orEmpty().toList() }
            .onFailure { NfLog.w(this, "读取通知栏存量失败: ${it.message}") }
            .getOrDefault(emptyList())

    /**
     * V10 任务 A 核心：对**通知栏里已经存在的**通知批量重跑「黑名单 → 规则 → 动作」。
     *
     * 与 [handle] 的**四条关键差异**（每条都是本功能的正确性前提）：
     * 1. **绕过指纹去重**：这里不读也不写 [recentFingerprints] —— 存量通知的内容早就被
     *    `handle()` 记成「已处理过」，若走 `process()` 会被 `NotificationFingerprint.shouldSkip`
     *    全部丢掉，功能等于没做。故本函数是**独立入口**，只复用 [evaluate] 判定主体。
     * 2. **不发 `Posted` 边沿**：存量通知不是「新出现的通知」，重复发会让条件卡片（需求 7）
     *    反复重播动画；命中边沿 `Matched` 仍然发（它就是「这条通知现在归这条规则管」的正解）。
     * 3. **不写重复流水**：同 key 已有流水 → 就地改写结论（`updateResult`），不再插一行。
     * 4. **弹幕 / 悬浮限额**：整批最多补发 [MAX_REPROCESS_OVERLAY] 条屏幕显示，
     *    超出后 `evaluate(allowOverlay = false)` 把这两类动作从计划里剔掉并记日志——
     *    否则用户存一条「命中即弹幕」的规则会被几十条存量通知糊屏。
     *
     * 其余口径与正常路径完全一致：防递归（排除本 App 包名）、批内同 key 去重、
     * 总开关关闭直接跳过、动作执行与结果归类共用同一份 [evaluate] / [classify]。
     *
     * @param sbns 存量通知（来源 `NotificationListenerService.activeNotifications`）
     * @param reason 触发原因（日志用）
     */
    suspend fun reprocessActive(
        sbns: List<StatusBarNotification>,
        reason: String = "规则保存",
        /**
         * 非空时**只用这些规则**判定。
         *
         * V10.3：保存规则后的补处理传 `listOf(刚保存的规则)` —— 用户明确要求
         * 「只执行刚刚保存的规则，不要执行之前保存的规则」。
         */
        rulesOverride: List<Rule>? = null,
    ) = mutex.withLock {
            if (sbns.isEmpty()) {
                NfLog.d(this, "补处理跳过：通知栏为空（$reason）")
                return@withLock
            }
            // 总开关关闭 → 一次都不执行（口径与正常路径一致：暂停时不拦截任何通知）
            if (!container.settingsRepository.isMasterEnabled()) {
                NfLog.i(this, "补处理跳过：总开关已关闭（$reason）")
                return@withLock
            }

            val now = System.currentTimeMillis()
            val epochDay = LocalDate.now(ZoneId.systemDefault()).toEpochDay().toInt()
            // 规则集：补处理可以只带一条（保存后立即生效），否则读全量
            val rules = rulesOverride ?: container.ruleRepository.getAll()
            val seenKeys = HashSet<String>(sbns.size)
            var overlayLeft = MAX_REPROCESS_OVERLAY
            var handled = 0

            for (sbn in sbns) {
                // —— 批内去重：同一条通知（同 key）一次批量只处理一次 ——
                if (!seenKeys.add(sbn.key)) continue
                // —— 防递归：本 App 自己重发出去的通知不参与补处理 ——
                if (sbn.packageName == appContext.packageName) continue

                val appName = AppUtils.appNameOf(appContext, sbn.packageName)
                val snapshot = FieldExtractor.fromStatusBar(sbn, appName)
                val existing = container.recordRepository.findByKey(snapshot.key)

                val allowOverlay = overlayLeft > 0
                val evaluation = evaluate(sbn, snapshot, rules, allowOverlay)

                // V10.3：**只处理命中**。
                // 补处理现在只跑"刚保存的那一条规则"，未命中 ≠ 这条通知被放行 ——
                // 它可能正被别的规则正常管理着。此时既不插流水、也不改写已有结论，
                // 否则用户每保存一次规则就会把历史记录污染一遍（原本"消除"的记录被改成"放行"）。
                if (evaluation !is Evaluation.Matched) continue

                // 已有流水 → 就地改写（绝不新增第二条）；没有流水（服务断开期间漏掉的）→ 命中后补插一条
                val recordId = existing?.recordId
                    ?: insertRecord(
                        snapshot,
                        appName,
                        if (snapshot.postTime > 0) snapshot.postTime else now,
                    ).also { newId ->
                        // V10.11：补插的新行同样登记到 liveRecords，后续这条通知的内容更新
                        // 才会合并进这一行，而不是再插一条。已有流水**不登记**——它可能是一条
                        // 早就存在的老记录（同 key 复用的场景），登记进去会把新通知写进老行。
                        liveRecords[snapshot.key] = LiveRow(newId, now)
                    }
                // 额度只在「这次判定本来要弹、且真的允许弹」时才扣减：
                // 超过额度后 allowOverlay=false，不再扣，日志里能看到被跳过的条数
                if (allowOverlay && evaluation.overlayUsed()) {
                    overlayLeft--
                    if (overlayLeft == 0) {
                        NfLog.i(
                            this,
                            "补处理屏幕显示额度已用尽（上限 $MAX_REPROCESS_OVERLAY 条），" +
                                "后续命中只执行消除/推迟/替换/朗读（$reason）",
                        )
                    }
                }
                persistEvaluation(
                    recordId = recordId,
                    snapshot = snapshot,
                    appName = appName,
                    epochDay = epochDay,
                    evaluation = evaluation,
                    alreadyCounted = existing != null,
                )
                handled++
            }
            NfLog.i(
                this,
                "补处理通知栏存量完成（$reason）：候选 ${sbns.size} 条，实际处理 $handled 条，" +
                    "剩余屏幕显示额度 $overlayLeft",
            )
        }

    fun onRemoved(sbn: StatusBarNotification) {
        // 待明确 #6（默认决议）：原通知被源 App 主动取消时，延时重发任务仍按计划执行，
        // 因此这里不清理 pending_reposts，仅记录日志便于排查。
        //
        // P1-6：同时丢弃该 key 的指纹——「内容未变则跳过」只应抑制**存活通知的内容更新**，
        // 不能吞掉「取消后重新投递的同内容通知」（那是一条真实的新通知，例如同一个人又发了
        // 一条一模一样的消息）。通过管线自己的单线程 scope 串行执行，与 handle() 同线程。
        scope.launch {
            recentFingerprints.remove(sbn.key)
            // V10.11：通知真的消失了 → 摘掉「key → 流水行」的映射。
            // 同 key 再投递就是一条**新通知**（微信同一个人连发两条、下载任务重开等 key 完全相同的场景），
            // 必须另起一行，否则新通知会被合并进旧行、永远沉在列表底部。
            liveRecords.remove(sbn.key)
        }
        // V7 需求 7：把「通知消失」广播给条件编排器（条件 ④ / ⑤ 的 REMOVE 边沿）。
        // 发射侧自带「无订阅者立即返回」，没有条件规则时零成本，故放在 scope 之外同步发射。
        NotificationEventBus.emitRemoved(sbn.packageName, sbn.key)
        NfLog.d(this, "通知移除 key=${sbn.key} pkg=${sbn.packageName}")
    }

    fun shutdown() {
        scope.cancel()
    }

    // ==================== 主流程 ====================

    private suspend fun handle(sbn: StatusBarNotification) = mutex.withLock {
        val packageName = sbn.packageName
        if (packageName == appContext.packageName) return@withLock // 防递归

        val now = System.currentTimeMillis()
        val appName = AppUtils.appNameOf(appContext, packageName)
        val snapshot = FieldExtractor.fromStatusBar(sbn, appName)
        // 天序号：领域模型 / DAO / 表中该列统一为 Int（与 StatsViewModel 一致），
        // 故 toEpochDay() 的 Long 结果在此一次性收敛（天数永不溢出 Int）。
        val epochDay = LocalDate.now(ZoneId.systemDefault()).toEpochDay().toInt()

        // —— 系统 snooze 到点恢复：同 key 再到达，仅标记恢复 ——
        val existing = container.recordRepository.findByKey(snapshot.key)
        if (existing != null && existing.restoredAt == null &&
            existing.result == ResultKind.SNOOZE &&
            existing.executedActions.contains(ActionType.SNOOZE)
        ) {
            container.recordRepository.markRestored(existing.recordId, now)
            NfLog.i(this, "snooze 恢复 key=${snapshot.key}")
            return@withLock
        }

        // —— V10.2 缺陷 D：字段全空的可诊断日志（release 也可见） ——
        // 自定义布局（RemoteViews）/ 极简通知常常只有 extras，没有 title / text / textLines。
        // 这类通知即使做了 A/B 两处回落也可能抓不到内容（规则「正文包含」永远不会命中），
        // 因此这里留一条 NfLog.w（而非 NfLog.d，避免 release 下被日志级别开关吞掉），
        // 让用户排查「某 App 的通知没抓到」时有据可依。
        // **刻意不改变处理流程**：只记日志，照常往下走（该写流水还写、该计数还计）。
        if (snapshot.title.isNullOrBlank() &&
            snapshot.text.isNullOrBlank() &&
            snapshot.textLines.isEmpty()
        ) {
            NfLog.w(
                this,
                "通知内容为空（疑似自定义布局/RemoteViews）key=${snapshot.key} pkg=$packageName",
            )
        }

        // —— 同 key 内容指纹去重（P1-6）：进度类通知的重复刷新在此丢弃 ——
        // 位置刻意放在 snooze 恢复判定**之后**：系统 snooze 到点重投的是「内容完全相同」的
        // 同 key 通知，若先去重会跳过 markRestored，流水永远停在「未恢复」。
        // 重复刷新的成本由此降为「1 次主键索引查询（上面的 findByKey）」，
        // 不再有 4 次写 + 1 次全表读 + `5 × 规则数` 次 JSON 解析 + 动作执行。
        //
        // —— V10.2 缺陷 C：指纹口径补上「会变但不在正文里」的字段 ——
        // 百度网盘的传输 / 备份类通知复用同一个 key 反复重发，进度只体现在 EXTRA_PROGRESS
        // （或 RemoteViews 里的进度条）上，title / text / textLines 完全不变 → 修复前从第二条起
        // 全部被下面这行 `return@withLock` 静默丢弃。
        // 把 subText / summaryText / 进度拼成附加签名后，这类更新会被识别为「内容变了」。
        // **去重语义不变**：仍然是「与上一次完全相同才跳过」，只是「哪些算相同」更准了。
        val extraSignature = buildString {
            append(snapshot.subText.orEmpty())
            append(SIGNATURE_SEPARATOR)
            append(snapshot.summaryText.orEmpty())
            append(SIGNATURE_SEPARATOR)
            append(progressSignature(snapshot.extras))
        }
        val fingerprint = NotificationFingerprint.of(
            title = snapshot.title,
            text = snapshot.text,
            textLines = snapshot.textLines,
            extraSignature = extraSignature,
        )
        if (NotificationFingerprint.shouldSkip(recentFingerprints[snapshot.key], fingerprint)) {
            NfLog.d(this, "同 key 内容未变，跳过 key=${snapshot.key} pkg=$packageName")
            return@withLock
        }
        recentFingerprints[snapshot.key] = fingerprint

        // —— V10.7 耗电修复：高频更新的「同 key 时间窗节流」——
        // 指纹里含 subText / summaryText / android.progress（V10.2 为修百度网盘加的），
        // 副作用是音乐/下载通知每秒改进度 → 每秒都被判为"新内容"→ 去重失效 → 每秒全链路。
        // 这里加一层时间窗：同 key 在 [MIN_HANDLE_INTERVAL_MS] 内只处理一次，
        // 进度更新被合并到下一个窗口；"进度能进流水"保留，但不会每秒刷。
        val lastHandled = recentHandledAt[snapshot.key]
        if (lastHandled != null && now - lastHandled < MIN_HANDLE_INTERVAL_MS) {
            NfLog.d(this, "同 key 高频更新，节流跳过 key=${snapshot.key} pkg=$packageName")
            return@withLock
        }
        recentHandledAt[snapshot.key] = now

        // —— V7 需求 7：通知「出现」边沿 ——
        // 位置刻意放在指纹去重**之后**：进度类通知的重复刷新（内容未变）不是"新的出现"，
        // 不能让它反复触发「指定 App 有通知 → 显示卡片」。
        NotificationEventBus.emitPosted(snapshot)

        // —— 全量记流水（result=PASS 占位，后续按结论更新） ——
        // V10：构造细节抽到 [insertRecord]，保证「正常路径」与「存量补处理」字段口径完全一致。
        // V10.11：「插新行」还是「改写这一条通知已有的那一行」由 [upsertRecord] 按 [liveRecords] 决定。
        val masterEnabled = container.settingsRepository.isMasterEnabled()
        val slot = upsertRecord(snapshot, appName, now, paused = !masterEnabled)

        // —— 总开关：暂停时全部放行 ——
        if (!masterEnabled) {
            // V10.11：合并路径下这条通知在插入首行时已经计过一次，统计口径是「通知条数」，
            // 一次通知只能计一次，故内容更新不再重复 increment（否则进度类通知会把统计刷虚高）。
            if (!slot.coalesced) {
                container.statsRepository.increment(packageName, appName, epochDay, filtered = false)
            }
            NfLog.d(this, "总开关暂停，放行 pkg=$packageName")
            return@withLock
        }

        // —— 黑名单 / 规则匹配 / 动作执行 ——
        // V10 任务 A：这一整段抽成 [evaluate] + [persistEvaluation]，与「存量补处理」
        // （[reprocessActive]）**共用同一份实现**——两条入口只有「要不要去重 / 要不要计统计」
        // 不同，判定与执行逻辑绝不允许各写一份（否则补处理的行为会慢慢和正常路径漂移）。
        val evaluation = evaluate(
            sbn = sbn,
            snapshot = snapshot,
            rules = container.ruleRepository.getAll(),
            allowOverlay = true,
        )
        persistEvaluation(
            recordId = slot.recordId,
            snapshot = snapshot,
            appName = appName,
            epochDay = epochDay,
            evaluation = evaluation,
            // V10.11：合并到既有行时，这条通知已经计过一次统计（首行插入那一刻），不再重复计
            alreadyCounted = slot.coalesced,
        )
    }

    // ==================== 判定主体（正常路径与存量补处理共用） ====================

    /**
     * 一条通知的判定结果（V10 任务 A 抽出的中间态）。
     *
     * 抽出它的理由：正常路径要「插流水 + 计统计」，补处理要「改写已有流水 + 不重复计数」，
     * 两者的**判定**（黑名单 → 规则 → 动作）完全一样，只有**落库口径**不同。
     * 于是判定返回本类型，落库由 [persistEvaluation] 按入口决定。
     */
    private sealed interface Evaluation {
        /** 命中黑名单（[cancelled] = 是否真的消掉了，false 时正常路径不计统计、不写结论）。 */
        data class Blacklist(val cancelled: Boolean) : Evaluation

        /** 未命中任何规则（放行）。 */
        data object Unmatched : Evaluation

        /**
         * 命中某条规则并已执行动作。
         *
         * @param usesOverlay 该规则**本来**是否要用屏幕显示（弹幕 / 悬浮）——
         *   补处理据此扣减限额，即便因为限额被剔掉了动作，也算「消耗了一个额度」
         *   （否则限额永远扣不满，后面的通知会继续弹）
         */
        data class Matched(
            val rule: Rule,
            val hitFields: Set<FieldRef>,
            val executed: List<ActionType>,
            val kind: ResultKind,
            val usesOverlay: Boolean,
        ) : Evaluation
    }

    /**
     * 取「这次判定是否本来要用屏幕显示」——黑名单 / 未命中恒为 false。
     *
     * 用显式函数而不是扩展属性：`Evaluation.Matched` 自己就有同名成员，
     * 扩展属性里再写 `this.usesOverlay` 极易被误读成自我递归。
     */
    private fun Evaluation.overlayUsed(): Boolean =
        (this as? Evaluation.Matched)?.usesOverlay == true

    /**
     * 判定一条通知：黑名单（优先于一切规则）→ 规则匹配（按序首个命中）→ 动作执行。
     *
     * V7 需求 7/8 的四处接线全部保留在本函数内（顺序见下方注释），因此正常路径与补处理
     * 在「条件卡片 / 聚合录制 / 命中边沿」上的行为完全一致。
     *
     * @param rules 规则全集（调用方一次读全，批处理时不重复读）
     * @param allowOverlay false = 把弹幕 / 悬浮从计划动作里剔掉（补处理的刷屏保护）
     */
    private suspend fun evaluate(
        sbn: StatusBarNotification,
        snapshot: NotificationSnapshot,
        rules: List<Rule>,
        allowOverlay: Boolean,
    ): Evaluation {
        // —— 黑名单：无条件消除，优先于一切规则 ——
        if (container.blacklistRepository.contains(snapshot.packageName)) {
            val cancelled = container.actionExecutor.cancelDirect(snapshot.key)
            if (cancelled) {
                // 已消除 → 该通知已不在通知栏，指纹立即失效（与 onRemoved 双保险）
                recentFingerprints.remove(snapshot.key)
                NfLog.i(this, "黑名单消除 pkg=${snapshot.packageName} key=${snapshot.key}")
            }
            return Evaluation.Blacklist(cancelled)
        }

        // —— 规则匹配（按序首个命中） ——
        val matched = RuleEngine.matchFirstRule(rules, snapshot, DeviceStateProvider.current)
            ?: return Evaluation.Unmatched
        val (rule, match) = matched

        // —— V7 需求 7/8：命中点的四处接线 ——
        // 1) 先把快照交给条件编排器：条件卡片的内容（标题 / 正文 / 占位符）来自**这条**通知，
        //    而事件总线只带包名与规则 id（不含 snapshot）。无任何条件规则时本调用立即返回。
        container.overlayConditionArbiter.rememberSnapshot(rule.id, snapshot)
        // 2) 聚合录制（需求 8）：**放在「命中事件」与动作执行之前**——
        //    编排器一收到命中事件就会读列表渲染卡片，先写完库才能让卡片第一次就带上新条目
        //    （否则首次显示总会少一条，要等第二次刷新才补上）。
        if (rule.actions.aggregation?.enabled == true) {
            container.aggregationRecorder.record(rule, snapshot)
        }
        // 3) 命中边沿：条件 ⑤「满足某规则的通知出现」由它驱动
        NotificationEventBus.emitMatched(snapshot, rule.id)

        // —— 刷屏保护（V10 任务 A）：超出额度后剔除屏幕显示类动作 ——
        val willUseOverlay = match.plannedActions.any {
            it == ActionType.DANMAKU || it == ActionType.FLOAT
        }
        val plannedActions = if (allowOverlay) {
            match.plannedActions
        } else {
            match.plannedActions.filterNot { it == ActionType.DANMAKU || it == ActionType.FLOAT }
                .also {
                    if (willUseOverlay) {
                        NfLog.i(
                            this,
                            "补处理屏幕显示额度已用尽，跳过弹幕/悬浮 rule=${rule.id} " +
                                "key=${snapshot.key}",
                        )
                    }
                }
        }

        // 4) 动作执行（T06 负责 `floatIsConditional` 分支；V10 静音消除在 BLOCK 分支内）
        val executed = container.actionExecutor.execute(rule, snapshot, plannedActions, sbn)
        // 消除类动作已让通知离开通知栏 → 指纹立即失效（与 onRemoved 双保险）：源 App 若在同一
        // 瞬间重投同内容通知，必须重新判定并再次消除，不能因「内容未变」而留在通知栏
        if (ActionType.BLOCK in executed) recentFingerprints.remove(snapshot.key)
        return Evaluation.Matched(
            rule = rule,
            hitFields = match.hitFields,
            executed = executed,
            kind = classify(executed),
            usesOverlay = willUseOverlay,
        )
    }

    /**
     * 把判定结果落库（流水结论 + 统计），两条入口只差 [alreadyCounted] 一个开关。
     *
     * @param alreadyCounted true = 这条通知**之前已经被计过一次统计**（补处理命中已有流水时）：
     *   统计口径是「通知条数」，一条通知只能计一次，故此时只改写流水结论、不再 `increment`，
     *   否则「新增一条规则」会让当天总数凭空翻倍（用户会看到统计虚高）。
     */
    private suspend fun persistEvaluation(
        recordId: Long,
        snapshot: NotificationSnapshot,
        appName: String,
        epochDay: Int,
        evaluation: Evaluation,
        alreadyCounted: Boolean,
    ) {
        val packageName = snapshot.packageName
        when (evaluation) {
            is Evaluation.Blacklist -> {
                if (!evaluation.cancelled) return
                container.recordRepository.updateResult(
                    recordId = recordId,
                    result = ResultKind.BLOCK,
                    actions = listOf(ActionType.BLOCK),
                    hitFields = emptySet(),
                    ruleId = null,
                    ruleName = null,
                    blacklistHit = true,
                )
                if (!alreadyCounted) {
                    container.statsRepository.increment(packageName, appName, epochDay, filtered = true)
                }
            }

            Evaluation.Unmatched -> {
                if (!alreadyCounted) {
                    container.statsRepository.increment(packageName, appName, epochDay, filtered = false)
                }
                NfLog.d(this, "未命中规则，放行 pkg=$packageName key=${snapshot.key}")
            }

            is Evaluation.Matched -> {
                container.recordRepository.updateResult(
                    recordId = recordId,
                    result = evaluation.kind,
                    actions = evaluation.executed,
                    hitFields = evaluation.hitFields,
                    ruleId = evaluation.rule.id,
                    ruleName = evaluation.rule.name,
                    blacklistHit = false,
                )
                val filtered = evaluation.kind == ResultKind.BLOCK ||
                    evaluation.kind == ResultKind.SNOOZE ||
                    evaluation.kind == ResultKind.REPLACE
                if (!alreadyCounted) {
                    container.statsRepository.increment(packageName, appName, epochDay, filtered = filtered)
                }
                NfLog.i(
                    this,
                    "规则命中「${evaluation.rule.name}」pkg=$packageName " +
                        "result=${evaluation.kind} actions=${evaluation.executed}" +
                        if (alreadyCounted) "（存量补处理，统计不重复计）" else "",
                )
            }
        }
    }

    /**
     * 「记流水」的唯一入口（V10.11）：同一条**存活**通知就地合并，新通知才插新行。
     *
     * 判定只看 [liveRecords]：
     * - 该 key 还记着行号，且距上次写入不超过 [COALESCE_WINDOW_MS] → **改写那一行**；
     * - 否则（首次见到 / 已被 [onRemoved] 摘掉 / 超出时间窗）→ 插新行并记住行号。
     *
     * 这样「一条下载通知跑 1 小时」在流水里始终是**一行**（内容随进度刷新），
     * 而不是 1000 行把记录页刷满。列表排序按 recordId DESC，合并时不改 recordId，
     * 所以正在刷新的那条会**原地更新**，不会每 1.2 秒跳到列表顶部。
     *
     * @return [RecordSlot]：行号 + 本次是否为「合并到既有行」（决定统计要不要重复计）
     */
    private suspend fun upsertRecord(
        snapshot: NotificationSnapshot,
        appName: String,
        now: Long,
        /** 总开关是否处于暂停：暂停时不跑规则，需要把合并行的陈旧结论拉回「放行」。 */
        paused: Boolean,
    ): RecordSlot {
        val live = liveRecords[snapshot.key]
        // 注意顺序：`live != null` 必须先判，再取 lastWriteAt ——
        // 这样既避免 `!!`，也与 shouldCoalesce(null, …) = false 的语义完全等价。
        if (live != null && RecordCoalescer.shouldCoalesce(live.lastWriteAt, now)) {
            container.recordRepository.updateSnapshot(live.recordId, snapshot, appName, now)
            live.lastWriteAt = now
            if (paused && !live.pausedReset) {
                // 暂停期间不跑规则 → 这一行不该继续挂着暂停前的「消除 / 推迟 / 替换」
                container.recordRepository.updateResult(
                    recordId = live.recordId,
                    result = ResultKind.PASS,
                    actions = emptyList(),
                    hitFields = emptySet(),
                    ruleId = null,
                    ruleName = null,
                    blacklistHit = false,
                )
                live.pausedReset = true
            } else if (!paused) {
                // 恢复运行：允许下次暂停时再拉回一次
                live.pausedReset = false
            }
            NfLog.d(this, "同 key 就地合并 key=${snapshot.key} recordId=${live.recordId}")
            return RecordSlot(live.recordId, coalesced = true)
        }
        val recordId = insertRecord(snapshot, appName, now)
        liveRecords[snapshot.key] = LiveRow(recordId, now)
        return RecordSlot(recordId, coalesced = false)
    }

    /** [upsertRecord] 的结果：占用哪一行 + 是否合并到既有行。 */
    private class RecordSlot(val recordId: Long, val coalesced: Boolean)

    /**
     * 插一条流水（`result = PASS` 占位，判定后由 [persistEvaluation] 改写）。
     *
     * 抽出来是为了让补处理在「这条通知在服务断开期间漏记」时也能补一条，
     * 且两个入口的字段口径严格一致（extrasJson / 摘要 / 渠道 / flags 全部同源）。
     */
    private suspend fun insertRecord(
        snapshot: NotificationSnapshot,
        appName: String,
        now: Long,
    ): Long = container.recordRepository.insert(
        NotificationRecord(
            key = snapshot.key,
            notifId = snapshot.notifId,
            packageName = snapshot.packageName,
            appName = appName,
            postTime = if (snapshot.postTime > 0) snapshot.postTime else now,
            result = ResultKind.PASS,
            extrasJson = encodeExtras(snapshot),
            titleSummary = snapshot.displayTitle,
            textSummary = snapshot.displayText,
            userId = snapshot.userId,
            channelId = snapshot.channelId,
            isOngoing = snapshot.isOngoing,
            flags = snapshot.flags,
        ),
    )

    /**
     * 结果归类：消除 > 替换（含推迟×替换） > 推迟 > 屏幕显示 > 朗读 > 放行。
     *
     * V4：屏幕显示类（弹幕 / 悬浮）单独归为 [ResultKind.SHOW]；
     * 它们**不动原通知**，故不算过滤（下方 filtered 判定只看 BLOCK/SNOOZE/REPLACE）。
     */
    private fun classify(actions: List<ActionType>): ResultKind = when {
        ActionType.BLOCK in actions -> ResultKind.BLOCK
        ActionType.REPLACE in actions -> ResultKind.REPLACE
        ActionType.SNOOZE in actions -> ResultKind.SNOOZE
        ActionType.DANMAKU in actions || ActionType.FLOAT in actions -> ResultKind.SHOW

        ActionType.READ_ALOUD in actions -> ResultKind.READ
        else -> ResultKind.PASS
    }

    private fun encodeExtras(snapshot: NotificationSnapshot): String = runCatching {
        NfJson.encodeToString(
            ListSerializer(PairSerializer(String.serializer(), String.serializer())),
            snapshot.extras,
        )
    }.getOrDefault("[]")

    /** 首页总开关切换时由服务调用，刷新常驻通知文案。 */
    suspend fun refreshOngoingNotification() {
        val filtering = container.settingsRepository.isMasterEnabled()
        OngoingStatusNotification(appContext).show(filtering)
    }

    private companion object {
        /**
         * 内容指纹表容量上限（LRU）。128 条足够覆盖「同时在通知栏里刷新的进度类通知」
         * （音乐 + 下载 + 导航 + 计时器 + 少量聊天），且内存占用可忽略（key 长度级）。
         */
        const val MAX_FINGERPRINTS = 128

        /**
         * 一次「规则保存后补处理」中允许补发的屏幕显示（弹幕 / 悬浮）条数上限（V10 任务 A）。
         *
         * 为什么需要：保存一条「命中即弹幕」的规则时，通知栏里可能同时躺着几十条命中通知，
         * 全部补发会直接把屏幕糊死（用户完全无法操作）。3 条足够让用户确认「规则真的生效了」，
         * 且不会打断他接下来要继续调规则的操作。
         *
         * 只限制**屏幕显示**类；消除 / 推迟 / 替换 / 朗读是用户明确想要的过滤结果，
         * 反倒应该一次性全部补做（否则「保存后通知没消失」又成了新的 bug）。
         */
        const val MAX_REPROCESS_OVERLAY = 3
    }
}

/**
 * 通知内容指纹（P1-6 去重判定）：**纯 Kotlin、无 Android 依赖、无副作用**，便于单测覆盖。
 *
 * 指纹口径：标题 + 正文 + 消息列表文本（+ 可选的附加签名：subText / summaryText / 进度）。
 * 任一项变化即视为「内容变了」（不抑制）。
 * 拼接一律用 `\u0000` 分隔（含列表元素之间），避免「`"ab"+"c"` 与 `"a"+"bc"` 同值」这类
 * 拼接歧义（与 extras 字段选择器的分隔口径一致）。
 *
 * 为什么用 FNV-1a 64 位而不是 `String.hashCode()`：后者只有 32 位，LRU 里同时存在 128 个
 * 指纹时，一次「误判为未变」就会**丢掉一条真实通知**；FNV-1a 64 位把碰撞概率降到可忽略
 * （≈ 128 / 2^64），代价只是一次短字符串遍历（微秒级）。
 */
object NotificationFingerprint {

    /** 字段分隔符：不可见控制字符，正常通知文本里不会出现。 */
    private const val SEPARATOR = '\u0000'

    /** FNV-1a 64 位偏移基（无符号值 0xCBF29CE484222325）。 */
    private const val FNV_OFFSET_BASIS: Long = -3750763034362895579L

    /** FNV-1a 64 位质数（0x100000001B3）。 */
    private const val FNV_PRIME: Long = 1099511628211L

    /**
     * 计算一条通知的内容指纹。
     *
     * @param title 标题（缺失按空串）。
     * @param text 正文（缺失按空串）。
     * @param textLines 消息列表（如微信/QQ 的多行摘要），按 `\u0000` 连接。
     * @param extraSignature 附加签名（V10.2）：`subText` / `summaryText` / 进度等
     *   **会变但不落在 title·text·textLines 里**的字段。默认为空串，此时算出的指纹与
     *   V10.1 的口径**逐字节一致**（老调用点与老单测无需改动）。
     * @return 64 位指纹；同样的三元组（+ 同样的附加签名）必然得到同样的值，
     *   任一字段不同则极大概率不同。
     */
    fun of(
        title: String?,
        text: String?,
        textLines: List<String>,
        extraSignature: String = "",
    ): Long {
        val canonical = buildString {
            append(title.orEmpty())
            append(SEPARATOR)
            append(text.orEmpty())
            append(SEPARATOR)
            // joinToString 的 separator 需要 CharSequence（Char 不行），这里显式转换；
            // 用 \u0000 而非 ',' 是为了让 ["a, b"] 与 ["a","b"] 得到不同指纹。
            append(textLines.joinToString(SEPARATOR.toString()))
            // 附加签名只在非空时追加：这样「不传附加签名」的老调用点与 V10.1 完全同值，
            // 不会因为多一个尾部分隔符而改变历史指纹（避免老单测与运行时缓存口径漂移）。
            if (extraSignature.isNotEmpty()) {
                append(SEPARATOR)
                append(extraSignature)
            }
        }
        var hash = FNV_OFFSET_BASIS
        for (ch in canonical) {
            hash = hash xor ch.code.toLong()
            hash *= FNV_PRIME
        }
        return hash
    }

    /**
     * 是否应跳过本次处理：**仅当**同一 key 上一指纹存在且与本次完全相同才跳过。
     *
     * 首次到达（[last] 为 null）一律不跳过；内容变化一律不跳过（那是用户真实关心的更新）。
     *
     * @param last 该 key 上一次记录的指纹（`recentFingerprints[key]`，无记录为 null）。
     * @param current 本次 `of(...)` 算出的指纹。
     */
    fun shouldSkip(last: Long?, current: Long): Boolean = last != null && last == current
}

// ==================== 附加签名（V10.2 缺陷 C） ====================

/** 字段分隔符：与 [NotificationFingerprint] 内部口径一致（不可见控制字符）。 */
private const val SIGNATURE_SEPARATOR = '\u0000'

/** `Notification.EXTRA_PROGRESS` 的键名（进度当前值）。 */
private const val EXTRA_KEY_PROGRESS = "android.progress"

/** `Notification.EXTRA_PROGRESS_MAX` 的键名（进度上限）。 */
private const val EXTRA_KEY_PROGRESS_MAX = "android.progressMax"

/**
 * 从 extras dump 里取「进度签名」（V10.2 缺陷 C）。
 *
 * 背景：百度网盘的传输 / 备份类通知复用同一个 `sbn.key` 反复重发，进度只体现在
 * `EXTRA_PROGRESS` / `EXTRA_PROGRESS_MAX`（或 RemoteViews 里的进度条）上，
 * `title` / `text` / `textLines` 完全不变 → 修复前从第二条起被 [NotificationFingerprint.shouldSkip]
 * 整条静默丢弃（不写流水、不计统计、不跑规则），用户在记录页一条都看不到。
 *
 * 实现：`snapshot.extras` 已是「key → 字符串化值」的有序列表（[FieldExtractor.dumpExtras]），
 * 直接 `firstOrNull` 取值即可，无需再碰 `Bundle`（本函数因此是纯 Kotlin、可单测）。
 *
 * @param extras 快照的 extras dump（键名大小写敏感）
 * @return `"<progress>/<progressMax>"`；两个键都不存在时返回空串（不污染指纹）
 */
internal fun progressSignature(extras: List<Pair<String, String>>): String {
    val progress = extras.firstOrNull { it.first == EXTRA_KEY_PROGRESS }?.second.orEmpty()
    val progressMax = extras.firstOrNull { it.first == EXTRA_KEY_PROGRESS_MAX }?.second.orEmpty()
    // 两个键都没有 → 空串：额外加一个常量后缀毫无信息量，只会让指纹更难读
    if (progress.isEmpty() && progressMax.isEmpty()) return ""
    return "$progress/$progressMax"
}

/**
 * V10.7 耗电修复：同一 key 的最短处理间隔（ms）。
 *
 * 背景：指纹在 V10.2 纳入了 `subText / summaryText / android.progress`，
 * 于是音乐、下载、导航这类每秒刷新的通知**每次都算"内容变了"**，指纹去重完全失效，
 * 每秒都会跑一遍完整链路（`findByKey` + `insert+trim` + 黑名单查询 + 规则匹配 +
 * `updateResult` + 统计 3 条 SQL，命中弹幕/悬浮时还要新建窗口）。
 *
 * 1.2 秒这个值的取舍：足够把每秒更新合并掉，又不至于让人察觉进度"卡"。
 * 只作用于**同一 key 的高频更新**，正常通知（不同 key / 低频）完全不受影响。
 */
private const val MIN_HANDLE_INTERVAL_MS = 1200L

/**
 * V10.11：`key → 流水行号` 映射表的容量上限（LRU）。
 *
 * 只需覆盖「同时正在刷新的通知条数」——音乐 + 下载 + 导航 + 计时器 + 若干聊天，
 * 128 绰绰有余；内存占用是「key 字符串 + 两个 Long」，可忽略。
 */
private const val MAX_LIVE_RECORDS = 128
