package com.local.notificationfilter.core.backup

import android.content.Context
import com.local.notificationfilter.core.transfer.BackupCodec
import com.local.notificationfilter.core.transfer.BackupImport
import com.local.notificationfilter.core.transfer.BackupPreview
import com.local.notificationfilter.data.prefs.BackupStore
import com.local.notificationfilter.data.prefs.SettingsStore
import com.local.notificationfilter.data.repo.BlacklistRepository
import com.local.notificationfilter.data.repo.RuleRepository
import com.local.notificationfilter.util.AppUtils
import com.local.notificationfilter.util.NfLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/** 恢复模式。 */
enum class RestoreMode {
    /** 只把备份里的规则**追加**到现有规则末尾（黑名单与设置不动）。 */
    MERGE_RULES_ONLY,

    /** 规则追加 + 黑名单**覆盖** + 设置**覆盖**。 */
    FULL,
}

/** 恢复结果（全部是给人看的中文）。 */
data class RestoreReport(
    val rulesAdded: Int = 0,
    val rulesSkipped: Int = 0,
    val blacklistCount: Int = 0,
    val settingsApplied: List<String> = emptyList(),
    /** 恢复前自动快照的文件名；null = 没写成（未配置目标）。 */
    val snapshotName: String? = null,
    val errorMessage: String? = null,
) {
    val ok: Boolean get() = errorMessage == null

    /** 一句话摘要，给 Snackbar 用。 */
    fun summary(): String = if (!ok) {
        "恢复失败：$errorMessage"
    } else {
        buildString {
            append("恢复完成：规则 +$rulesAdded")
            if (rulesSkipped > 0) append("（跳过 $rulesSkipped 条）")
            if (blacklistCount > 0) append(" ｜ 黑名单 $blacklistCount 条")
            if (settingsApplied.isNotEmpty()) append(" ｜ 设置：${settingsApplied.joinToString("、")}")
            if (snapshotName != null) append(" ｜ 恢复前快照：$snapshotName")
        }
    }
}

/**
 * 备份/恢复编排层（V11）：**唯一对外的备份入口**。
 *
 * 职责边界：
 * - [BackupCodec] 只管「对象 ↔ JSON 文本」；
 * - [BackupTarget] 只管「把一段文本写到某个地方」；
 * - 本类负责「采集当前数据 → 交给 codec → 交给 target」，以及反向的恢复流程。
 *
 * 两条不可妥协的规则：
 * 1. **恢复前一定先存快照**：黑名单与设置是覆盖式写入，没有快照就没有退路。
 *    快照写失败**不阻断**恢复（否则备份文件夹权限失效就永远恢复不了），
 *    但会在 [RestoreReport.snapshotName] 里明确告诉你「没有快照」。
 * 2. **规则永远是追加**：哪怕选 FULL 也不删现有规则 —— 用户手滑导入一个旧备份
 *    不应该丢掉当前规则。「想清掉再恢复」是另一件事，不该由恢复按钮偷偷做。
 */
class BackupRepository(
    private val context: Context,
    private val ruleRepository: RuleRepository,
    private val blacklistRepository: BlacklistRepository,
    private val settingsStore: SettingsStore,
    private val backupStore: BackupStore,
) {

    // ==================== 采集 ====================

    /** 采集当前全量数据 → 备份 JSON 文本。 */
    suspend fun buildSnapshot(): String = withContext(Dispatchers.IO) {
        val rules = ruleRepository.getAll()
        val blacklist = blacklistRepository.observeAll().first().map { it.packageName }
        val settings = BackupCodec.SettingsPayload(
            masterEnabled = settingsStore.masterEnabled.first(),
            theme = settingsStore.theme.first(),
            darkMode = settingsStore.darkMode.first(),
            showOngoing = settingsStore.showOngoing.first(),
            hideFromRecents = settingsStore.hideFromRecents.first(),
        )
        BackupCodec.export(rules = rules, blacklist = blacklist, settings = settings)
    }

    // ==================== 目标 ====================

    /** 本地目标；未选择文件夹 → null。 */
    private suspend fun localTarget(): LocalFolderTarget? =
        backupStore.folderUri.first()?.takeIf { it.isNotBlank() }?.let {
            LocalFolderTarget(context, it)
        }

    /** WebDAV 目标；未启用 / 缺账号 / 缺密码 → null。 */
    private suspend fun webDavTarget(): WebDavTarget? {
        if (!backupStore.webDavEnabled.first()) return null
        val account = backupStore.webDavAccount.first()
        val password = backupStore.getWebDavPassword()
        if (account.isBlank() || password.isNullOrBlank()) return null
        return WebDavTarget(
            baseUrl = backupStore.webDavUrl.first(),
            account = account,
            password = password,
            remoteFilePath = backupStore.webDavRemotePath.first(),
        )
    }

    // ==================== 备份 ====================

    /** 手动备份到本地文件夹。 */
    suspend fun backupToLocal(fileName: String? = null): BackupWriteResult {
        val target = localTarget()
            ?: return BackupWriteResult.Fail("还没有选择备份文件夹")
        val name = fileName ?: BackupNames.manual()
        return withContext(Dispatchers.IO) {
            target.write(name, buildSnapshot())
        }
    }

    /**
     * 手动备份到 WebDAV。
     *
     * V11.1：改为**按当天时间命名**（`nf-backup-yyyyMMdd-HHmmss.json`），
     * 不再用固定名覆盖 —— 用户要的是云端能留多份、能挑一份恢复。
     * `remoteFilePath` 现在只决定**目录**，文件名由这里生成。
     */
    suspend fun backupToWebDav(fileName: String? = null): BackupWriteResult {
        val target = webDavTarget()
            ?: return BackupWriteResult.Fail("WebDAV 未启用或账号/密码不完整")
        val name = fileName ?: BackupNames.manual()
        return withContext(Dispatchers.IO) {
            target.write(name, buildSnapshot())
        }
    }

    /**
     * 自动备份：按 prefs 决定写哪些目标，并把结果写进 [BackupStore.lastRun*]。
     *
     * @return 至少一个目标成功即为 true（部分成功也算成功，摘要里会写清楚哪个失败）
     */
    suspend fun runAutoBackup(): Boolean = withContext(Dispatchers.IO) {
        val toLocal = backupStore.autoToLocal.first()
        val toWebDav = backupStore.autoToWebDav.first()
        if (!toLocal && !toWebDav) {
            backupStore.setLastRun(
                at = System.currentTimeMillis(),
                ok = false,
                message = "自动备份未执行：没有勾选任何目标",
            )
            return@withContext false
        }

        val snapshot = runCatching { buildSnapshot() }.getOrElse {
            backupStore.setLastRun(System.currentTimeMillis(), false, "采集数据失败：${it.message}")
            return@withContext false
        }

        val parts = mutableListOf<String>()
        var anyOk = false

        if (toLocal) {
            val target = localTarget()
            if (target == null) {
                parts += "本地：未选择文件夹"
            } else {
                when (val r = target.write(backupStore.autoFileName.first(), snapshot)) {
                    is BackupWriteResult.Ok -> {
                        anyOk = true
                        parts += "本地：成功"
                    }
                    is BackupWriteResult.Fail -> parts += "本地：${r.message}"
                }
            }
        }
        if (toWebDav) {
            val target = webDavTarget()
            if (target == null) {
                parts += "WebDAV：未启用或账号/密码不完整"
            } else {
                // V11.1：云端自动备份同样按时间命名保留多份，只清理更早的 nf-auto-*
                val name = BackupNames.auto()
                when (val r = target.write(name, snapshot)) {
                    is BackupWriteResult.Ok -> {
                        anyOk = true
                        parts += "WebDAV：成功（$name）"
                        pruneOldBackups(target, BackupNames.AUTO_PREFIX, BackupNames.AUTO_KEEP)
                    }
                    is BackupWriteResult.Fail -> parts += "WebDAV：${r.message}"
                }
            }
        }

        val message = parts.joinToString(" · ")
        backupStore.setLastRun(System.currentTimeMillis(), anyOk, message)
        NfLog.i(this@BackupRepository, "自动备份完成（ok=$anyOk）：$message")
        anyOk
    }

    // ==================== 列表 / 预览 ====================

    suspend fun listLocalBackups(): List<String> =
        withContext(Dispatchers.IO) { localTarget()?.list().orEmpty() }

    suspend fun listWebDavBackups(): List<String> =
        withContext(Dispatchers.IO) { webDavTarget()?.list().orEmpty() }

    /** 删除本地备份文件（V11.1）；目标不可用返回 false。 */
    suspend fun deleteLocal(fileName: String): Boolean = withContext(Dispatchers.IO) {
        localTarget()?.delete(fileName) ?: false
    }

    /** 删除云端备份文件（V11.1）；目标不可用返回 false。 */
    suspend fun deleteWebDav(fileName: String): Boolean = withContext(Dispatchers.IO) {
        webDavTarget()?.delete(fileName) ?: false
    }

    /** 连接自检（只读 PROPFIND，不在云端留垃圾）。 */
    suspend fun testWebDav(): BackupWriteResult = withContext(Dispatchers.IO) {
        val target = webDavTarget()
            ?: return@withContext BackupWriteResult.Fail("WebDAV 未启用，或账号 / 密码不完整")
        target.probe().fold(
            onSuccess = { BackupWriteResult.Ok(it) },
            onFailure = { BackupWriteResult.Fail(it.message ?: "连接失败") },
        )
    }

    /** 读本地文件并预览（不产生副作用）。 */
    suspend fun previewLocal(fileName: String): BackupPreview = withContext(Dispatchers.IO) {
        val target = localTarget() ?: return@withContext failedPreview("还没有选择备份文件夹")
        target.read(fileName).fold(
            onSuccess = { BackupCodec.preview(it) },
            onFailure = { failedPreview(it.message ?: "读取失败") },
        )
    }

    /** 读云端文件并预览。 */
    suspend fun previewWebDav(fileName: String): BackupPreview = withContext(Dispatchers.IO) {
        val target = webDavTarget() ?: return@withContext failedPreview("WebDAV 未启用或账号/密码不完整")
        target.read(fileName).fold(
            onSuccess = { BackupCodec.preview(it) },
            onFailure = { failedPreview(it.message ?: "读取失败") },
        )
    }

    // ==================== 恢复 ====================

    /**
     * 「两步式恢复」的第一步：读取 + 解析并返回 [BackupImport]，
     * 让 UI 先弹「确认恢复」再调 [applyRestore]。**不写任何数据。**
     */
    suspend fun loadForRestore(source: RestoreSource): BackupImport = withContext(Dispatchers.IO) {
        val target: BackupTarget? = when (source) {
            is RestoreSource.Local -> localTarget()
            is RestoreSource.WebDav -> webDavTarget()
        }
        if (target == null) return@withContext BackupImport.failed("备份目标不可用（未选择文件夹 / WebDAV 未配置）")
        val name = when (source) {
            is RestoreSource.Local -> source.fileName
            is RestoreSource.WebDav -> source.fileName
        }
        target.read(name).fold(
            onSuccess = { BackupCodec.import(it) },
            onFailure = { BackupImport.failed(it.message ?: "读取备份文件失败") },
        )
    }

    /**
     * 「两步式恢复」的第二步：真正写入。
     *
     * 执行顺序（顺序本身是安全设计）：
     * 1. **先写恢复前快照**（当前数据 → `nf-before-restore-<时间戳>.json`），
     *    目标优先本地、其次 WebDAV；两者都不可用则跳过并在报告里写明；
     * 2. 规则追加（`importRules` 内部会重建 order，不会打乱现有顺序）；
     * 3. FULL 模式下：黑名单覆盖 → 设置覆盖。
     *
     * 任何一步抛异常都收敛成 [RestoreReport.errorMessage]，不向外抛。
     */
    suspend fun applyRestore(data: BackupImport, mode: RestoreMode): RestoreReport =
        withContext(Dispatchers.IO) {
            if (!data.ok) return@withContext RestoreReport(errorMessage = data.errorMessage)

            // 1) 恢复前快照
            val snapshotName = runCatching { writePreRestoreSnapshot() }.getOrNull()
            if (snapshotName == null) {
                NfLog.w(this@BackupRepository, "恢复前快照未写成（无可用目标），继续恢复")
            }

            // 2) 规则（永远追加）
            val addedOrError = runCatching { ruleRepository.importRules(data.rules) }
            val added = addedOrError.getOrNull()
            if (added == null) {
                return@withContext RestoreReport(
                    snapshotName = snapshotName,
                    errorMessage = "写入规则失败：${addedOrError.exceptionOrNull()?.message}",
                )
            }

            if (mode == RestoreMode.MERGE_RULES_ONLY) {
                return@withContext RestoreReport(
                    rulesAdded = added,
                    rulesSkipped = data.skippedRuleCount,
                    snapshotName = snapshotName,
                )
            }

            // 3) FULL：黑名单覆盖
            val blacklistError = runCatching {
                blacklistRepository.replaceAll(data.blacklist) { pkg ->
                    AppUtils.appNameOf(context, pkg)
                }
            }.exceptionOrNull()
            if (blacklistError != null) {
                return@withContext RestoreReport(
                    rulesAdded = added,
                    rulesSkipped = data.skippedRuleCount,
                    snapshotName = snapshotName,
                    errorMessage = "规则已恢复，但写入黑名单失败：${blacklistError.message}",
                )
            }
            val blacklistCount = data.blacklist.size

            // 4) FULL：设置覆盖（逐项判空，缺的保持现状）
            val applied = mutableListOf<String>()
            val s = data.settings
            if (s != null) {
                val settingsError = runCatching {
                    s.masterEnabled?.let { settingsStore.setMasterEnabled(it); applied += "总开关" }
                    s.theme?.let { settingsStore.setTheme(it); applied += "主题" }
                    s.darkMode?.let { settingsStore.setDarkMode(it); applied += "深浅模式" }
                    s.showOngoing?.let { settingsStore.setShowOngoing(it); applied += "常驻通知" }
                    s.hideFromRecents?.let { settingsStore.setHideFromRecents(it); applied += "多任务中隐藏" }
                }.exceptionOrNull()
                if (settingsError != null) {
                    return@withContext RestoreReport(
                        rulesAdded = added,
                        rulesSkipped = data.skippedRuleCount,
                        blacklistCount = blacklistCount,
                        settingsApplied = applied,
                        snapshotName = snapshotName,
                        errorMessage = "规则与黑名单已恢复，但写入设置失败：${settingsError.message}",
                    )
                }
            }

            NfLog.i(
                this@BackupRepository,
                "恢复完成：规则 +$added、黑名单 $blacklistCount、设置 ${applied.size} 项、快照 $snapshotName",
            )
            RestoreReport(
                rulesAdded = added,
                rulesSkipped = data.skippedRuleCount,
                blacklistCount = blacklistCount,
                settingsApplied = applied,
                snapshotName = snapshotName,
            )
        }

    /** 把「当前数据」写成恢复前快照，返回文件名；无可用目标返回 null。 */
    private suspend fun writePreRestoreSnapshot(): String? {
        val snapshot = buildSnapshot()
        val name = BackupNames.beforeRestore()
        localTarget()?.let { target ->
            val r = target.write(name, snapshot)
            if (r is BackupWriteResult.Ok) {
                pruneOldBackups(target, BackupNames.SNAPSHOT_PREFIX, BackupNames.SNAPSHOT_KEEP)
                return name
            }
        }
        webDavTarget()?.let { target ->
            val r = target.write(name, snapshot)
            if (r is BackupWriteResult.Ok) {
                pruneOldBackups(target, BackupNames.SNAPSHOT_PREFIX, BackupNames.SNAPSHOT_KEEP)
                return name
            }
        }
        return null
    }

    /**
     * 清理某一类旧备份（V11.1）：只删 [prefix] 开头、超出保留份数的文件。
     *
     * **绝不碰用户手动备份**（前缀不同），删除失败也只记日志不抛异常 ——
     * 清理是锦上添花，不能因为它把备份主流程搞失败。
     */
    private suspend fun pruneOldBackups(target: BackupTarget, prefix: String, keep: Int) {
        val stale = runCatching {
            BackupNames.staleFiles(target.list(), prefix, keep)
        }.getOrElse {
            NfLog.w(this@BackupRepository, "清理旧备份时列目录失败：${it.message}")
            return
        }
        stale.forEach { name ->
            val ok = runCatching { target.delete(name) }.getOrDefault(false)
            if (ok) {
                NfLog.i(this@BackupRepository, "已清理旧备份：$name")
            } else {
                NfLog.w(this@BackupRepository, "清理旧备份失败：$name")
            }
        }
    }

    // ==================== 小工具 ====================

    private fun failedPreview(message: String) = BackupPreview(
        ruleCount = 0,
        skippedRuleCount = 0,
        blacklistCount = 0,
        settingsKeys = emptyList(),
        exportedAt = 0L,
        errorMessage = message,
    )
}

/** 恢复来源。 */
sealed interface RestoreSource {
    data class Local(val fileName: String) : RestoreSource
    data class WebDav(val fileName: String) : RestoreSource
}
