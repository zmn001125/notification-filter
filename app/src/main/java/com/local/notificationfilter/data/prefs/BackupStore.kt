package com.local.notificationfilter.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.local.notificationfilter.core.backup.CredentialCipher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * 备份设置存储（V11）：本地文件夹授权、WebDAV 凭据、自动备份计划、最近一次结果。
 *
 * 与 [SettingsStore] **共用同一个 `nf_settings` DataStore**（见 `NfDataStore.kt`）——
 * 备份设置本来就是应用设置的一部分，另开一个文件只会带来两套键空间的维护负担。
 *
 * 安全说明：WebDAV 密码**不落明文**。[setWebDavPassword] 先经
 * [CredentialCipher]（Android Keystore AES/GCM）加密再写 DataStore；
 * [getWebDavPassword] 解密失败（换机 / 密钥被清）返回 null，UI 侧按「密码失效，
 * 请重新输入」处理，绝不静默用空密码去请求（那会得到一个更迷惑的 401）。
 */
class BackupStore(private val context: Context) {

    // ==================== 本地备份文件夹 ====================

    private val keyFolderUri = stringPreferencesKey("backup_folder_uri")
    private val keyAutoFileName = stringPreferencesKey("backup_auto_file_name")

    /** 用户选定的备份文件夹（SAF 树 Uri 字符串）；null = 未选择。 */
    val folderUri: Flow<String?> = context.nfDataStore.data.map { it[keyFolderUri] }

    /** 自动备份写入本地时使用的固定文件名（每次覆盖，避免堆一堆文件）。 */
    val autoFileName: Flow<String> =
        context.nfDataStore.data.map { it[keyAutoFileName] ?: DEFAULT_AUTO_FILE_NAME }

    suspend fun setFolderUri(value: String?) {
        context.nfDataStore.edit { prefs ->
            if (value.isNullOrBlank()) prefs.remove(keyFolderUri) else prefs[keyFolderUri] = value
        }
    }

    suspend fun setAutoFileName(value: String) {
        context.nfDataStore.edit { it[keyAutoFileName] = value.ifBlank { DEFAULT_AUTO_FILE_NAME } }
    }

    // ==================== WebDAV（坚果云） ====================

    private val keyWebDavEnabled = booleanPreferencesKey("backup_webdav_enabled")
    private val keyWebDavUrl = stringPreferencesKey("backup_webdav_url")
    private val keyWebDavAccount = stringPreferencesKey("backup_webdav_account")
    private val keyWebDavPassword = stringPreferencesKey("backup_webdav_password_enc")
    private val keyWebDavRemotePath = stringPreferencesKey("backup_webdav_remote_path")

    val webDavEnabled: Flow<Boolean> = context.nfDataStore.data.map { it[keyWebDavEnabled] ?: false }

    /** 服务器根地址；默认坚果云。注意**末尾斜杠**（拼路径时直接相接）。 */
    val webDavUrl: Flow<String> =
        context.nfDataStore.data.map { it[keyWebDavUrl] ?: DEFAULT_WEBDAV_URL }

    val webDavAccount: Flow<String> = context.nfDataStore.data.map { it[keyWebDavAccount].orEmpty() }

    /**
     * 远程**目录**（相对服务器根），如 `notification-filter/`。
     * V11.1 起文件名由备份时按当天时间自动生成，这里只决定放在哪个目录；
     * 目录不存在时上传前会自动 MKCOL 创建。
     */
    val webDavRemotePath: Flow<String> =
        context.nfDataStore.data.map { it[keyWebDavRemotePath] ?: DEFAULT_WEBDAV_PATH }

    /** 是否已保存过密码（只回布尔，密码本身不出存储层）。 */
    val webDavPasswordSaved: Flow<Boolean> =
        context.nfDataStore.data.map { !it[keyWebDavPassword].isNullOrBlank() }

    suspend fun setWebDavEnabled(value: Boolean) {
        context.nfDataStore.edit { it[keyWebDavEnabled] = value }
    }

    suspend fun setWebDavUrl(value: String) {
        context.nfDataStore.edit { it[keyWebDavUrl] = value.trim() }
    }

    suspend fun setWebDavAccount(value: String) {
        context.nfDataStore.edit { it[keyWebDavAccount] = value.trim() }
    }

    suspend fun setWebDavRemotePath(value: String) {
        context.nfDataStore.edit { it[keyWebDavRemotePath] = value.trim() }
    }

    /**
     * 保存 WebDAV 密码（传 null / 空串 = 清除）。
     *
     * 加密失败（Keystore 不可用）时**不写任何东西**并返回 false，让 UI 能提示，
     * 而不是悄悄存了明文或悄悄丢掉。
     */
    suspend fun setWebDavPassword(plain: String?): Boolean {
        if (plain.isNullOrEmpty()) {
            context.nfDataStore.edit { it.remove(keyWebDavPassword) }
            return true
        }
        val enc = CredentialCipher.encrypt(plain) ?: return false
        context.nfDataStore.edit { it[keyWebDavPassword] = enc }
        return true
    }

    /** 读取并解密密码；未设置 / 解密失败均返回 null。 */
    suspend fun getWebDavPassword(): String? {
        val enc = context.nfDataStore.data.first()[keyWebDavPassword] ?: return null
        return CredentialCipher.decrypt(enc)
    }

    // ==================== 自动备份计划 ====================

    private val keyAutoEnabled = booleanPreferencesKey("backup_auto_enabled")
    private val keyAutoInterval = intPreferencesKey("backup_auto_interval_hours")
    private val keyAutoHour = intPreferencesKey("backup_auto_hour")
    private val keyAutoMinute = intPreferencesKey("backup_auto_minute")
    private val keyAutoToLocal = booleanPreferencesKey("backup_auto_to_local")
    private val keyAutoToWebDav = booleanPreferencesKey("backup_auto_to_webdav")

    val autoEnabled: Flow<Boolean> = context.nfDataStore.data.map { it[keyAutoEnabled] ?: false }

    /** 间隔小时数：24（每天）/ 72（每 3 天）/ 168（每周）。 */
    val autoIntervalHours: Flow<Int> =
        context.nfDataStore.data.map { it[keyAutoInterval] ?: DEFAULT_INTERVAL_HOURS }

    val autoHour: Flow<Int> = context.nfDataStore.data.map { it[keyAutoHour] ?: DEFAULT_AUTO_HOUR }

    val autoMinute: Flow<Int> = context.nfDataStore.data.map { it[keyAutoMinute] ?: 0 }

    val autoToLocal: Flow<Boolean> = context.nfDataStore.data.map { it[keyAutoToLocal] ?: true }

    val autoToWebDav: Flow<Boolean> = context.nfDataStore.data.map { it[keyAutoToWebDav] ?: false }

    suspend fun setAutoEnabled(value: Boolean) {
        context.nfDataStore.edit { it[keyAutoEnabled] = value }
    }

    suspend fun setAutoIntervalHours(value: Int) {
        context.nfDataStore.edit { it[keyAutoInterval] = value }
    }

    suspend fun setAutoTime(hour: Int, minute: Int) {
        context.nfDataStore.edit {
            it[keyAutoHour] = hour.coerceIn(0, 23)
            it[keyAutoMinute] = minute.coerceIn(0, 59)
        }
    }

    suspend fun setAutoTargets(local: Boolean, webDav: Boolean) {
        context.nfDataStore.edit {
            it[keyAutoToLocal] = local
            it[keyAutoToWebDav] = webDav
        }
    }

    // ==================== 最近一次结果 ====================

    private val keyLastRunAt = longPreferencesKey("backup_last_run_at")
    private val keyLastRunOk = booleanPreferencesKey("backup_last_run_ok")
    private val keyLastRunMessage = stringPreferencesKey("backup_last_run_message")

    val lastRunAt: Flow<Long> = context.nfDataStore.data.map { it[keyLastRunAt] ?: 0L }

    val lastRunOk: Flow<Boolean> = context.nfDataStore.data.map { it[keyLastRunOk] ?: false }

    /** 中文结果摘要，如「本地：成功 · WebDAV：401 认证失败」。 */
    val lastRunMessage: Flow<String> = context.nfDataStore.data.map { it[keyLastRunMessage].orEmpty() }

    suspend fun setLastRun(at: Long, ok: Boolean, message: String) {
        context.nfDataStore.edit {
            it[keyLastRunAt] = at
            it[keyLastRunOk] = ok
            it[keyLastRunMessage] = message
        }
    }

    companion object {
        /** 默认本地文件名（自动备份覆盖写这一个，不堆垃圾）。 */
        const val DEFAULT_AUTO_FILE_NAME = "nf-backup-auto.json"

        /** 坚果云 WebDAV 根地址（官方文档口径）。 */
        const val DEFAULT_WEBDAV_URL = "https://dav.jianguoyun.com/dav/"

        /**
         * 默认远程**目录**（V11.1：文件名改为按时间自动生成，这里只决定目录）。
         * 目录不存在时上传会自动 MKCOL 创建。
         */
        const val DEFAULT_WEBDAV_PATH = "notification-filter/"

        const val DEFAULT_INTERVAL_HOURS = 24

        /** 默认凌晨 3 点（用户睡觉时跑，不打扰）。 */
        const val DEFAULT_AUTO_HOUR = 3
    }
}
