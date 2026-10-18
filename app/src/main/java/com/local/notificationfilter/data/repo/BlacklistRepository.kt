package com.local.notificationfilter.data.repo

import com.local.notificationfilter.core.model.BlacklistEntry
import com.local.notificationfilter.data.db.BlacklistDao
import com.local.notificationfilter.data.db.BlacklistEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * 黑名单仓库：无条件消除、优先于一切规则。
 *
 * ## V13.1 耗电修复：[contains] 走进程内缓存
 * 原来每条通知都要打一次 `SELECT EXISTS(...)`：单条很便宜，但
 * - 通知密集（群聊 / 进度类每秒刷新）时是每秒几次；
 * - [com.local.notificationfilter.service.NotificationPipeline] 的「媒体变化重扫」
 *   会对通知栏**每一条**都查一次，一轮就是几十次。
 *
 * 缓存口径与 [com.local.notificationfilter.core.ruleengine] 侧规则缓存一致：
 * 黑名单只在用户在 UI 里增删改（以及备份恢复）时变化，任何写操作后调用 [invalidateCache]
 * 即可；读路径永不回源。
 */
class BlacklistRepository(private val dao: BlacklistDao) {

    /** 进程内包名缓存；`null` = 尚未加载（首次 [contains] 时惰性填充）。 */
    @Volatile
    private var cache: Set<String>? = null

    fun observeAll(): Flow<List<BlacklistEntry>> =
        dao.observeAll().map { list -> list.map { BlacklistEntry(it.packageName, it.appName, it.addedAt) } }

    fun observeCount(): Flow<Int> = dao.observeCount()

    /** 写操作后必须调用：让下一次 [contains] 重新回源。 */
    private fun invalidateCache() {
        cache = null
    }

    suspend fun contains(pkg: String): Boolean {
        cache?.let { return pkg in it }
        val fresh = runCatching { dao.allPackages().toSet() }.getOrNull() ?: return dao.contains(pkg)
        cache = fresh
        return pkg in fresh
    }

    suspend fun add(pkg: String, appName: String) {
        dao.add(BlacklistEntity(packageName = pkg, appName = appName, addedAt = System.currentTimeMillis()))
        invalidateCache()
    }

    suspend fun remove(pkg: String) {
        dao.remove(pkg)
        invalidateCache()
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
        invalidateCache()
    }
}
