package com.local.notificationfilter.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore

/**
 * 全应用唯一的 DataStore 实例（`nf_settings`）。
 *
 * **为什么单独抽一个文件**：`preferencesDataStore(...)` 委托在一个进程内对同一个
 * 文件名只能声明**一次**（重复声明会在运行时抛
 * `IllegalStateException: There are multiple DataStores active for the same file`）。
 * V11 的备份功能需要与 [SettingsStore] 共用同一份配置文件（备份设置 / WebDAV 配置
 * 都是应用设置的一部分，分文件只会带来"两套键空间"的维护负担），因此把委托收到这里，
 * 由 [SettingsStore] 与 [BackupStore] 共同 `context.nfDataStore` 访问。
 *
 * `internal` 可见性：只允许本模块的两个 Store 使用，UI 层不应直接碰 DataStore
 * （否则会绕过各自的 `setXxx` 封装，破坏"写入口唯一"的约定）。
 */
internal val Context.nfDataStore: DataStore<Preferences> by preferencesDataStore(
    name = NF_DATASTORE_NAME,
)

/** DataStore 文件名（保持 V1 以来的 `nf_settings`，老用户升级后配置不丢）。 */
internal const val NF_DATASTORE_NAME = "nf_settings"
