package com.local.notificationfilter.ui.screens.backup

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.local.notificationfilter.NfApplication
import com.local.notificationfilter.core.backup.BackupWriteResult
import com.local.notificationfilter.core.backup.RestoreMode
import com.local.notificationfilter.core.backup.RestoreReport
import com.local.notificationfilter.core.backup.RestoreSource
import com.local.notificationfilter.core.transfer.BackupImport
import com.local.notificationfilter.core.transfer.BackupPreview
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 备份与恢复页 ViewModel（V11）。
 *
 * 设计要点：
 * - **所有耗时操作（文件 / 网络 / Keystore）都在 `viewModelScope` + IO**，UI 只读
 *   [uiState]，靠 [BackupUiState.busy] 禁用按钮防连点；
 * - 恢复是**两步式**：先 [requestRestoreLocal] / [requestRestoreWebDav] 拿到预览并
 *   弹确认框，用户点「确认恢复」才 [confirmRestore] 真正写库 —— 备份恢复是不可逆操作，
 *   不允许一键直达；
 * - 恢复前快照由 `BackupRepository` 自动写，这里只负责把结果转述给用户。
 */
class BackupViewModel(application: Application) : AndroidViewModel(application) {

    data class BackupUiState(
        val folderUri: String? = null,
        val autoFileName: String = "",
        // WebDAV
        val webDavEnabled: Boolean = false,
        val webDavUrl: String = "",
        val webDavAccount: String = "",
        val webDavRemotePath: String = "",
        val passwordSaved: Boolean = false,
        // 自动备份
        val autoEnabled: Boolean = false,
        val intervalHours: Int = 24,
        val autoHour: Int = 3,
        val autoMinute: Int = 0,
        val autoToLocal: Boolean = true,
        val autoToWebDav: Boolean = false,
        // 最近一次
        val lastRunAt: Long = 0L,
        val lastRunOk: Boolean = false,
        val lastRunMessage: String = "",
        // 交互态
        val busy: Boolean = false,
        val message: String? = null,
        val localFiles: List<String> = emptyList(),
        val remoteFiles: List<String> = emptyList(),
        /** 非空 = 正在等待用户确认恢复。 */
        val pendingRestore: PendingRestore? = null,
        /** 非空 = 正在等待用户确认删除备份文件。 */
        val pendingDelete: PendingDelete? = null,
    ) {
        val autoSummary: String
            get() {
                val interval = when (intervalHours) {
                    24 -> "每天"
                    72 -> "每 3 天"
                    168 -> "每周"
                    else -> "每 $intervalHours 小时"
                }
                val hh = autoHour.toString().padStart(2, '0')
                val mm = autoMinute.toString().padStart(2, '0')
                val targets = buildList {
                    if (autoToLocal) add("本地")
                    if (autoToWebDav) add("WebDAV")
                }.joinToString(" + ").ifBlank { "未选目标" }
                return "$interval $hh:$mm · $targets"
            }
    }

    /** 待确认的恢复：预览 + 来源 + 已解析好的数据。 */
    data class PendingRestore(
        val preview: BackupPreview,
        val sourceLabel: String,
        val source: RestoreSource,
    )

    /** 待确认的删除：删哪个文件、在本地还是云端。 */
    data class PendingDelete(
        val fileName: String,
        val isLocal: Boolean,
    ) {
        val sourceLabel: String get() = if (isLocal) "本地" else "云端"
    }

    private val container = (application as NfApplication).container
    private val repo = container.backupRepository
    private val store = container.backupStore
    private val scheduler = container.backupScheduler

    private val _uiState = MutableStateFlow(BackupUiState())
    val uiState: StateFlow<BackupUiState> = _uiState.asStateFlow()

    /** 已解析好、等用户确认的导入数据（不进 UI state，避免把整份备份塞进 Compose 状态）。 */
    private var pendingImport: BackupImport? = null

    init {
        // 分别订阅三组设置再合并 —— kotlinx.coroutines 的 combine 最多 5 个「异类型」流，
        // 这里 7 + 6 个键，硬拼会退化成 Array<Any>，类型安全和可读性都会丢。
        val basicPart = combine(
            store.folderUri,
            store.autoFileName,
            store.webDavEnabled,
            store.webDavUrl,
            store.webDavAccount,
        ) { folder, name, enabled, url, account ->
            BasicPart(folder, name, enabled, url, account)
        }
        val webDavPart = combine(
            store.webDavRemotePath,
            store.webDavPasswordSaved,
        ) { path, pwdSaved -> path to pwdSaved }

        viewModelScope.launch {
            combine(basicPart, webDavPart) { basic, (path, pwdSaved) ->
                BasicPart2(basic, path, pwdSaved)
            }.collect { p ->
                _uiState.value = _uiState.value.copy(
                    folderUri = p.basic.folderUri,
                    autoFileName = p.basic.autoFileName,
                    webDavEnabled = p.basic.webDavEnabled,
                    webDavUrl = p.basic.webDavUrl,
                    webDavAccount = p.basic.webDavAccount,
                    webDavRemotePath = p.path,
                    passwordSaved = p.pwdSaved,
                )
            }
        }

        val autoPartA = combine(
            store.autoEnabled,
            store.autoIntervalHours,
            store.autoHour,
        ) { enabled, interval, hour -> Triple(enabled, interval, hour) }
        val autoPartB = combine(
            store.autoMinute,
            store.autoToLocal,
            store.autoToWebDav,
        ) { minute, toLocal, toWebDav -> Triple(minute, toLocal, toWebDav) }

        viewModelScope.launch {
            combine(autoPartA, autoPartB) { a, b ->
                AutoPrefs(
                    enabled = a.first,
                    interval = a.second,
                    hour = a.third,
                    minute = b.first,
                    toLocal = b.second,
                    toWebDav = b.third,
                )
            }.collect { p ->
                _uiState.value = _uiState.value.copy(
                    autoEnabled = p.enabled,
                    intervalHours = p.interval,
                    autoHour = p.hour,
                    autoMinute = p.minute,
                    autoToLocal = p.toLocal,
                    autoToWebDav = p.toWebDav,
                )
            }
        }

        viewModelScope.launch {
            combine(store.lastRunAt, store.lastRunOk, store.lastRunMessage) { at, ok, msg ->
                Triple(at, ok, msg)
            }.collect { (at, ok, msg) ->
                _uiState.value = _uiState.value.copy(
                    lastRunAt = at,
                    lastRunOk = ok,
                    lastRunMessage = msg,
                )
            }
        }
        refreshLists()
    }

    private data class BasicPart(
        val folderUri: String?,
        val autoFileName: String,
        val webDavEnabled: Boolean,
        val webDavUrl: String,
        val webDavAccount: String,
    )

    private data class BasicPart2(
        val basic: BasicPart,
        val path: String,
        val pwdSaved: Boolean,
    )

    private data class AutoPrefs(
        val enabled: Boolean,
        val interval: Int,
        val hour: Int,
        val minute: Int,
        val toLocal: Boolean,
        val toWebDav: Boolean,
    )

    // ==================== 本地文件夹 ====================

    /**
     * 用户选了文件夹：**必须**先 takePersistableUriPermission，否则进程重启后授权失效，
     * 自动备份会在后台静默失败（这是 SAF 最常见的一个坑）。
     */
    fun selectFolder(uri: Uri?) {
        if (uri == null) return
        viewModelScope.launch {
            val ok = runCatching {
                getApplication<Application>().contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }.isSuccess
            if (!ok) {
                _uiState.value = _uiState.value.copy(message = "无法获得该文件夹的长期写入权限，请换一个文件夹")
                return@launch
            }
            store.setFolderUri(uri.toString())
            _uiState.value = _uiState.value.copy(message = "备份文件夹已设置")
            refreshLists()
        }
    }

    fun clearFolder() {
        viewModelScope.launch {
            store.setFolderUri(null)
            _uiState.value = _uiState.value.copy(message = "已取消备份文件夹", localFiles = emptyList())
        }
    }

    // ==================== 备份 ====================

    fun backupToLocal() = runBusy {
        when (val r = repo.backupToLocal()) {
            is BackupWriteResult.Ok -> "本地备份完成：${r.location}"
            is BackupWriteResult.Fail -> "本地备份失败：${r.message}"
        }.also { if (it.contains("完成")) refreshLists() }
    }

    fun backupToWebDav() = runBusy {
        when (val r = repo.backupToWebDav()) {
            is BackupWriteResult.Ok -> "WebDAV 备份完成"
            is BackupWriteResult.Fail -> "WebDAV 备份失败：${r.message}"
        }.also { if (it.contains("完成")) refreshLists() }
    }

    // ==================== WebDAV 配置 ====================

    fun setWebDavEnabled(value: Boolean) {
        viewModelScope.launch {
            store.setWebDavEnabled(value)
            if (value) scheduler.reschedule()
        }
    }

    fun saveWebDav(
        url: String,
        account: String,
        password: String,
        remotePath: String,
    ) = runBusy {
        store.setWebDavUrl(url)
        store.setWebDavAccount(account)
        store.setWebDavRemotePath(remotePath)
        if (password.isNotEmpty()) {
            val saved = withContext(Dispatchers.IO) { store.setWebDavPassword(password) }
            if (!saved) return@runBusy "密码加密失败，未能保存（请重试或重启应用）"
        }
        scheduler.reschedule()
        "WebDAV 设置已保存"
    }

    fun testWebDav() = runBusy {
        val result = withContext(Dispatchers.IO) { repo.testWebDav() }
        when (result) {
            is BackupWriteResult.Ok -> "连接成功：${result.location}"
            is BackupWriteResult.Fail -> "连接失败：${result.message}"
        }
    }

    // ==================== 自动备份 ====================

    fun setAutoEnabled(value: Boolean) {
        viewModelScope.launch {
            store.setAutoEnabled(value)
            scheduler.reschedule()
            _uiState.value = _uiState.value.copy(
                message = if (value) "已开启自动备份" else "已关闭自动备份",
            )
        }
    }

    fun setInterval(hours: Int) {
        viewModelScope.launch {
            store.setAutoIntervalHours(hours)
            scheduler.reschedule()
        }
    }

    fun setAutoTime(hour: Int, minute: Int) {
        viewModelScope.launch {
            store.setAutoTime(hour, minute)
            scheduler.reschedule()
        }
    }

    fun setTargets(local: Boolean, webDav: Boolean) {
        viewModelScope.launch {
            store.setAutoTargets(local, webDav)
            scheduler.reschedule()
        }
    }

    // ==================== 列表 / 恢复 ====================

    fun refreshLists() {
        viewModelScope.launch {
            val local = withContext(Dispatchers.IO) { repo.listLocalBackups() }
            val remote = withContext(Dispatchers.IO) { repo.listWebDavBackups() }
            _uiState.value = _uiState.value.copy(localFiles = local, remoteFiles = remote)
        }
    }

    /** 第一步：读文件 + 预览，弹确认框。 */
    fun requestRestoreLocal(fileName: String) = requestRestore(RestoreSource.Local(fileName))

    fun requestRestoreWebDav(fileName: String) = requestRestore(RestoreSource.WebDav(fileName))

    private fun requestRestore(source: RestoreSource) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(busy = true, message = null)
            val data = withContext(Dispatchers.IO) { repo.loadForRestore(source) }
            _uiState.value = _uiState.value.copy(busy = false)
            if (!data.ok) {
                _uiState.value = _uiState.value.copy(
                    message = "无法读取备份：${data.errorMessage}",
                )
                return@launch
            }
            pendingImport = data
            val preview = com.local.notificationfilter.core.transfer.BackupCodec.previewOf(data)
            val label = when (source) {
                is RestoreSource.Local -> "本地：${source.fileName}"
                is RestoreSource.WebDav -> "云端：${source.fileName}"
            }
            _uiState.value = _uiState.value.copy(
                pendingRestore = PendingRestore(preview = preview, sourceLabel = label, source = source),
            )
        }
    }

    fun cancelRestore() {
        pendingImport = null
        _uiState.value = _uiState.value.copy(pendingRestore = null)
    }

    // ==================== 删除备份文件（V11.1） ====================

    fun requestDeleteLocal(fileName: String) {
        _uiState.value = _uiState.value.copy(
            pendingDelete = PendingDelete(fileName = fileName, isLocal = true),
        )
    }

    fun requestDeleteWebDav(fileName: String) {
        _uiState.value = _uiState.value.copy(
            pendingDelete = PendingDelete(fileName = fileName, isLocal = false),
        )
    }

    fun cancelDelete() {
        _uiState.value = _uiState.value.copy(pendingDelete = null)
    }

    /** 确认删除：真正删本地/云端文件，成功后刷新列表。 */
    fun confirmDelete() {
        val pending = _uiState.value.pendingDelete
        if (pending == null) {
            _uiState.value = _uiState.value.copy(pendingDelete = null)
            return
        }
        runBusy {
            val ok = withContext(Dispatchers.IO) {
                if (pending.isLocal) repo.deleteLocal(pending.fileName)
                else repo.deleteWebDav(pending.fileName)
            }
            if (ok) {
                refreshLists()
                "已删除${pending.sourceLabel}备份：${pending.fileName}"
            } else {
                "删除失败：${pending.sourceLabel}文件未能删除（权限/网络/密码可能有问题）"
            }
        }
        _uiState.value = _uiState.value.copy(pendingDelete = null)
    }

    /** 第二步：确认后真正写入。 */
    fun confirmRestore(mode: RestoreMode) {
        val data = pendingImport
        if (data == null) {
            _uiState.value = _uiState.value.copy(pendingRestore = null)
            return
        }
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                busy = true,
                pendingRestore = null,
                message = null,
            )
            val report: RestoreReport = withContext(Dispatchers.IO) { repo.applyRestore(data, mode) }
            pendingImport = null
            _uiState.value = _uiState.value.copy(busy = false, message = report.summary())
            refreshLists()
        }
    }

    fun clearMessage() {
        _uiState.value = _uiState.value.copy(message = null)
    }

    // ==================== 小工具 ====================

    /** 统一处理 busy 标记 + 异常收敛（任何异常都变成一条中文提示，不让页面崩）。 */
    private fun runBusy(block: suspend () -> String) {
        viewModelScope.launch {
            if (_uiState.value.busy) return@launch
            _uiState.value = _uiState.value.copy(busy = true, message = null)
            val message = try {
                block()
            } catch (t: Throwable) {
                "操作失败：${t.message ?: t.javaClass.simpleName}"
            }
            _uiState.value = _uiState.value.copy(busy = false, message = message)
        }
    }
}
