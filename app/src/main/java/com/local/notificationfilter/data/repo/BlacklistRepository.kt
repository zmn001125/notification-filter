package com.local.notificationfilter.data.repo

import com.local.notificationfilter.core.model.BlacklistEntry
import com.local.notificationfilter.data.db.BlacklistDao
import com.local.notificationfilter.data.db.BlacklistEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** 黑名单仓库：无条件消除、优先于一切规则（管线每次实时读库）。 */
class BlacklistRepository(private val dao: BlacklistDao) {

    fun observeAll(): Flow<List<BlacklistEntry>> =
        dao.observeAll().map { list -> list.map { BlacklistEntry(it.packageName, it.appName, it.addedAt) } }

    fun observeCount(): Flow<Int> = dao.observeCount()

    suspend fun contains(pkg: String): Boolean = dao.contains(pkg)

    suspend fun add(pkg: String, appName: String) {
        dao.add(BlacklistEntity(packageName = pkg, appName = appName, addedAt = System.currentTimeMillis()))
    }

    suspend fun remove(pkg: String) {
        dao.remove(pkg)
    }

    /**
     * V11 备份恢复：**覆盖式**写入整份黑名单。
     *
     * `appName` 由调用方解析（Repository 没有 Context，不想为此引入依赖）——
     * 传 `{ it }` 也能用，只是列表里显示包名而不是应用名。
     *
     * 顺序：先清空再逐条插入。中间失败会留下「部分恢复」的黑名单，
     * 这正是调用方必须在**恢复前**写好快照的原因（见 `BackupRepository.restore`）。
     */
    suspend fun replaceAll(packages: List<String>, nameOf: (String) -> String) {
        dao.clearAll()
        val now = System.currentTimeMillis()
        packages.distinct().forEach { pkg ->
            dao.add(
                BlacklistEntity(
                    packageName = pkg,
                    appName = nameOf(pkg),
                    addedAt = now,
                ),
            )
        }
    }
}
