package com.local.notificationfilter

import android.app.Application
import android.content.Context
import androidx.compose.runtime.staticCompositionLocalOf
import com.local.notificationfilter.actions.ActionExecutor
import com.local.notificationfilter.actions.AggregationRecorder
import com.local.notificationfilter.actions.ChannelSilencer
import com.local.notificationfilter.actions.NotificationReposter
import com.local.notificationfilter.actions.RepostScheduler
import com.local.notificationfilter.actions.TtsManager
import com.local.notificationfilter.core.ruleengine.RuleEngine
import com.local.notificationfilter.data.db.NfDatabase
import com.local.notificationfilter.data.prefs.ChannelSilenceStore
import com.local.notificationfilter.data.prefs.SettingsStore
import com.local.notificationfilter.data.repo.AggregationRepository
import com.local.notificationfilter.data.repo.BlacklistRepository
import com.local.notificationfilter.data.repo.RecordRepository
import com.local.notificationfilter.data.repo.RepostRepository
import com.local.notificationfilter.data.repo.RuleRepository
import com.local.notificationfilter.data.repo.SettingsRepository
import com.local.notificationfilter.data.repo.StatsRepository
import com.local.notificationfilter.service.AccessibilityGate
import com.local.notificationfilter.service.NfChannels
import com.local.notificationfilter.service.OverlayConditionArbiter
import com.local.notificationfilter.service.OverlayController
import com.local.notificationfilter.util.NfLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 应用入口：持有唯一装配点 [AppContainer]（架构共享约定 10，不引入 Hilt）。
 */
class NfApplication : Application() {

    val container: AppContainer by lazy { AppContainer(this) }

    /**
     * V2T04：启动一次性旧规则迁移作用域（架构 §2.6）。
     * IO + Supervisor：不阻塞主线程、单条失败不牵连启动；写回失败下次启动重试
     * （读路径即时迁移幂等兜底，见 RuleRepository.toRule）。
     */
    private val migrationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        // 常驻状态通知渠道尽早创建（重发渠道按源 App 懒创建，见 NotificationReposter）。
        NfChannels.ensureStatusChannel(this)
        // V4：叠加层控制器（弹幕 / 悬浮窗）持有 applicationContext
        OverlayController.attach(this)
        // 旧规则（V1 JSON）一次性迁移写回：无旧规则时为 0 条空转。
        migrationScope.launch {
            val migrated = runCatching { container.ruleRepository.migrateAllIfNeeded() }
                .onFailure {
                    NfLog.w(this@NfApplication, "旧规则迁移失败: ${it.message}")
                }
                .getOrDefault(0)
            if (migrated > 0) {
                NfLog.i(this@NfApplication, "旧规则迁移写回 $migrated 条（迁移前备份 nf_backup_v1.db）")
            }
            // V7 T05：启动时的两件只读初始化（不启停任何服务，Q2 定案）
            // 1) 无障碍需求标记：决定授权页是否展示「无障碍服务」项（存在 FOREGROUND_APP 条件才需要）；
            // 2) 条件索引预编译：让「服务还没连上时就已经有规则」的场景不必等首次通知才建索引。
            //    真正的订阅在 NfNotificationListener.onListenerConnected 的 attach() 里开始。
            runCatching { AccessibilityGate.syncFromDb(container.ruleRepository) }
                .onFailure { NfLog.w(this@NfApplication, "无障碍需求标记初始化失败: ${it.message}") }
            runCatching { container.overlayConditionArbiter.compileFromDb() }
                .onFailure { NfLog.w(this@NfApplication, "条件索引预编译失败: ${it.message}") }
        }
    }
}

/**
 * 手工 DI 容器：数据库 / DataStore / 7 个 Repository / 规则引擎 / 动作执行 / 延时调度。
 * 全部懒加载单例，供监听服务（系统进程回调）与 ViewModel（UI 层）共用同一实例。
 */
class AppContainer(context: Context) {

    private val appContext: Context = context.applicationContext

    /**
     * 清理类副作用的作用域（规则删除后的延时任务清理）。
     * 无 UI 生命周期可依附，跟随进程；IO 线程 + Supervisor 保证不互相牵连。
     */
    private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val database: NfDatabase by lazy { NfDatabase.get(appContext) }

    val settingsStore: SettingsStore by lazy { SettingsStore(appContext) }

    val ruleRepository: RuleRepository by lazy {
        RuleRepository(database.ruleDao(), appContext) { ruleId ->
            // 规则删除：清理其产生的未触发延时重发任务（架构 §1.3(8)）
            // 以及 V7 的聚合暂存条目（需求 8：作用域随规则消亡，避免孤儿行永久占用 DB）。
            // cancelByRule 是 suspend（内部有 Room 读写），这里投递到清理协程执行。
            cleanupScope.launch {
                runCatching { repostScheduler.cancelByRule(ruleId) }
                    .onFailure {
                        NfLog.w(this@AppContainer, "清理规则 $ruleId 的延时任务失败: ${it.message}")
                    }
                runCatching { aggregationRepository.deleteByRule(ruleId) }
                    .onFailure {
                        NfLog.w(this@AppContainer, "清理规则 $ruleId 的聚合条目失败: ${it.message}")
                    }
            }
        }
    }

    val blacklistRepository: BlacklistRepository by lazy { BlacklistRepository(database.blacklistDao()) }

    val recordRepository: RecordRepository by lazy { RecordRepository(database.recordDao()) }

    val statsRepository: StatsRepository by lazy { StatsRepository(database.statsDao()) }

    val repostRepository: RepostRepository by lazy { RepostRepository(database.repostDao()) }

    /**
     * V7（需求 8）：聚合暂存列表仓库。
     * 与 [database] 同源（`aggregation_items` 表），供管线录制（T05）、
     * 卡片/推迟渲染（T06）与规则编辑页「当前汇总」（T07）共用。
     */
    val aggregationRepository: AggregationRepository by lazy {
        AggregationRepository(database.aggregationDao())
    }

    val settingsRepository: SettingsRepository by lazy { SettingsRepository(settingsStore) }

    // ==================== V11：备份 / 恢复 ====================

    /** 备份设置（本地文件夹授权 / WebDAV 凭据 / 自动备份计划 / 最近结果）。 */
    val backupStore: com.local.notificationfilter.data.prefs.BackupStore by lazy {
        com.local.notificationfilter.data.prefs.BackupStore(appContext)
    }

    /** 备份编排（采集 / 写入本地或 WebDAV / 两步式恢复）。 */
    val backupRepository: com.local.notificationfilter.core.backup.BackupRepository by lazy {
        com.local.notificationfilter.core.backup.BackupRepository(
            context = appContext,
            ruleRepository = ruleRepository,
            blacklistRepository = blacklistRepository,
            settingsStore = settingsStore,
            backupStore = backupStore,
        )
    }

    /** 自动备份闹钟（一次性 + 自续期，见类注释）。 */
    val backupScheduler: com.local.notificationfilter.actions.BackupScheduler by lazy {
        com.local.notificationfilter.actions.BackupScheduler(appContext, backupStore)
    }

    /** 纯 Kotlin 规则引擎，无状态，可全局共享。 */
    val ruleEngine: RuleEngine by lazy { RuleEngine }

    val ttsManager: TtsManager by lazy { TtsManager(appContext) }

    val notificationReposter: NotificationReposter by lazy { NotificationReposter(appContext) }

    val repostScheduler: RepostScheduler by lazy { RepostScheduler(appContext, repostRepository) }

    /**
     * V10 任务 B：渠道静音器（「静音消除」的落地实现）。
     *
     * 生命周期与 [actionExecutor] 一致：由 `NfNotificationListener` 在连接时 attach、
     * 拆除时 detach（静音要用 NLS 的 `getNotificationChannels` 读源 App 渠道）。
     * 未 attach 时仍可用（回落读本包渠道），因此不 attach 也不会崩。
     */
    val channelSilencer: ChannelSilencer by lazy {
        ChannelSilencer(appContext, channelSilenceStore)
    }

    /** 渠道原始设置留底（「恢复渠道」能力的数据源，V10 任务 B）。 */
    val channelSilenceStore: ChannelSilenceStore by lazy { ChannelSilenceStore(appContext) }

    val actionExecutor: ActionExecutor by lazy {
        // V7 需求 8：第 4 个参数 = 聚合列表上下文来源（T06 的接缝）。
        // 装配成 `AggregationRecorder.lists(rule)` 后，弹幕 / 悬浮 / 推迟「调度时兜底文案」
        // 里的 `{itemsN}` 才有内容；不装配也能编译运行（默认恒空，等价 V6）。
        // V10 任务 B：第 4 个参数换成渠道静音器，「静音消除」才有执行者。
        ActionExecutor(ttsManager, notificationReposter, repostScheduler, channelSilencer) { rule ->
            aggregationRecorder.lists(rule)
        }
    }

    /**
     * V7（需求 8）：聚合录制器。
     *
     * 由 `NotificationPipeline` 在「命中规则且配了聚合」时调用一次（记录点唯一，
     * 刻意不放 `ActionExecutor`，避免与 T06 的改动重叠）。
     */
    val aggregationRecorder: AggregationRecorder by lazy {
        AggregationRecorder(aggregationRepository)
    }

    /**
     * V7（需求 7/8）：条件卡片编排器。
     *
     * 生命周期由 `NfNotificationListener` 驱动（`onListenerConnected` → attach，
     * `teardown` → detach），与 `DeviceStateProvider` 同点。
     * 内部把 `AggregationRecorder.onItemsChanged` 接到自己的 `refreshCard`，
     * 因此**先**构造 Recorder 再构造本类即可，无构造环。
     *
     * 载荷渲染委托 [ActionExecutor.buildOverlayPayload]（T06 公开的同一处实现），
     * 保证「模板 + `{itemsN}`」只有一条渲染路径。
     */
    val overlayConditionArbiter: OverlayConditionArbiter by lazy {
        OverlayConditionArbiter(
            ruleRepository = ruleRepository,
            recorder = aggregationRecorder,
            payloadBuilder = { rule, snapshot, titleTemplate, textTemplate, lists ->
                actionExecutor.buildOverlayPayload(
                    rule = rule,
                    snapshot = snapshot,
                    titleTemplate = titleTemplate,
                    textTemplate = textTemplate,
                    lists = lists,
                )
            },
        )
    }
}

/** Compose 侧获取 [AppContainer] 的入口（架构共享约定 10）。 */
val LocalAppContainer = staticCompositionLocalOf<AppContainer> {
    error("AppContainer 未通过 CompositionLocalProvider 提供")
}
