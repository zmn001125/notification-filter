package com.local.notificationfilter.data.repo

import android.content.Context
import com.local.notificationfilter.core.model.IdOrder
import com.local.notificationfilter.core.model.NfJson
import com.local.notificationfilter.core.model.Rule
import com.local.notificationfilter.data.db.RuleDao
import com.local.notificationfilter.data.db.RuleEntity
import com.local.notificationfilter.util.DbBackup
import com.local.notificationfilter.util.NfLog
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString

/**
 * 规则仓库：整条规则 JSON 序列化存入单列。
 *
 * 新增规则有两条路径：[save]（插队尾，order = max + 1，编辑页新建走这条）与
 * [insertAt]（插到指定位置，V4-6 复制规则走这条，保证副本紧贴原规则）。
 *
 * V2T04（架构 §2.6）：读路径 `toRule()` 先过 [RuleJsonMigrator.migrateJsonIfNeeded]（幂等）
 * 再 decode，即使启动写回失败行为也正确；启动一次性写回见 [migrateAllIfNeeded]。
 *
 * P1-5（省电）：[getAll] 带内存缓存。原实现每调用一次都要对**每条规则**跑
 * `migrateJsonIfNeeded`（内部最多 4 次完整 JSON 解析）+ 1 次 `decodeFromString`，
 * 而通知管线每条通知都调它一次 → 每条通知 `5 × 规则数` 次解析；规则几乎不变，缓存后趋近于 0。
 * 不变量：**任何写规则的方法都必须调 [invalidate]**，否则「改完规则不生效」。
 * [observeAll] 不缓存（UI 靠它实时反映库变化）。
 *
 * @param appContext 迁移前数据库备份（[DbBackup]）用；仅取 applicationContext。
 * @param onDeleted 删除后的清理钩子（AppContainer 接线到延时任务清理，架构 §1.3(8)）。
 */
class RuleRepository(
    private val dao: RuleDao,
    private val appContext: Context,
    private val onDeleted: (Long) -> Unit = {},
) {

    /**
     * 规则全量内存缓存（P1-5）。`null` = 未命中，需读库重建。
     *
     * 只在 [cacheMutex] 内赋值；`@Volatile` 让 [getAll] 的快速路径（无锁）也能看到最新写入。
     */
    @Volatile
    private var cache: List<Rule>? = null

    /**
     * 缓存与数据库的一致性护栏：读库（慢）与写库（[invalidate]）可能交错，故
     * 「判空 + 读库 + 回填」整段放在锁内，[invalidate] 也进锁 → 不会出现
     * 「写后失效发生在回填之前，把旧列表永久留在缓存里」的丢失效。
     */
    private val cacheMutex = Mutex()

    fun observeAll(): Flow<List<Rule>> =
        dao.observeAll().map { list -> list.map { it.toRule() } }

    /**
     * 全量规则（按 order 升序），带内存缓存（P1-5）。
     *
     * 热路径：通知管线每条通知调用一次；非热路径：详情页 / 测试面板 / 规则编辑页的一次性读取。
     * 返回的列表是**只读语义**（[Rule] 为不可变 data class，调用方一律只做 `filter` / `any` /
     * `firstOrNull`，不可原地修改）。
     */
    suspend fun getAll(): List<Rule> {
        cache?.let { return it }
        return cacheMutex.withLock {
            cache ?: dao.getAll().map { it.toRule() }.also { cache = it }
        }
    }

    /** 丢弃缓存：任何改动规则表之后必须调用（见类注释的不变量）。 */
    private suspend fun invalidate() {
        cacheMutex.withLock { cache = null }
    }

    /** 新增返回新 id；更新返回原 id。 */
    suspend fun save(rule: Rule): Long {
        val id = if (rule.id == 0L) {
            val order = dao.maxOrder() + 1
            dao.insert(rule.copy(order = order).toEntity())
        } else {
            dao.update(rule.toEntity())
            rule.id
        }
        invalidate()
        return id
    }

    /**
     * 插入到 [Rule.order] 指定的位置（V4-6 复制规则用：副本必须落在原规则正下方）。
     *
     * 与 [save] 的差别：save 对新增规则一律覆盖成 max + 1（追加末尾），复制若也走它，
     * 副本无论传什么 order 都会被扔到列表末尾。此处改为尊重调用方给的 order：
     * 先把 `order >= 目标值` 的规则**整体后移一位**腾出空位，再插入副本。
     *
     * 为什么只用「整体后移」而不是重排成 0..n-1：后移是严格单调平移，与拖拽排序
     * （[updateOrders] 直接写入 0..n-1 稠密序）语义兼容，不会打乱既有相对顺序，
     * 也不会让 [save] 的 max + 1 语义失效。
     *
     * 后移与插入是两次写库，中间态只是 order 出现一个空位（所有现有规则的相对顺序
     * 完全不变），列表流观察不到位置跳动；即使进程此时被杀，留下的空位也只影响
     * order 的连续性，不影响排序正确性。
     *
     * 越界保护：目标 order 为负按 0（插到最前）处理；大于当前最大 order 时等价于追加。
     *
     * @param rule 待插入规则副本（id 会被忽略并重新分配）。
     * @return 新规则的 id。
     */
    suspend fun insertAt(rule: Rule): Long {
        val target = rule.order.coerceAtLeast(0)
        // 仅当目标位置确实被占用时才需要腾位；空库 / 追加场景省掉一次写库
        if (dao.maxOrder() >= target) {
            val shifted = dao.getAll()
                .filter { it.order >= target }
                .map { IdOrder(id = it.id, order = it.order + 1) }
            if (shifted.isNotEmpty()) dao.updateOrders(shifted)
        }
        val id = dao.insert(rule.copy(id = 0L, order = target).toEntity())
        invalidate()
        return id
    }

    suspend fun delete(id: Long) {
        dao.deleteById(id)
        invalidate()
        onDeleted(id)
    }

    /**
     * 批量导入规则（V5 需求⑦：分享 / 备份恢复）。
     *
     * 每条以新 id 插入，order 依次追加到当前列表末尾（maxOrder + i + 1），
     * 保持导入列表内部相对顺序；**原样插入**——不加后缀、不去重（用户场景就是
     * 备份恢复 / 分享，重复与否由用户自行管理）。
     *
     * @param rules 已解析好的规则（id / order 会被忽略重建）。
     * @return 实际插入条数。
     */
    suspend fun importRules(rules: List<Rule>): Int {
        var nextOrder = dao.maxOrder()
        var inserted = 0
        rules.forEach { rule ->
            nextOrder += 1
            dao.insert(rule.copy(id = 0L, order = nextOrder).toEntity())
            inserted++
        }
        // 即便 inserted == 0 也失效：一次无副作用的缓存丢弃，远比「漏失效」的风险划算
        invalidate()
        return inserted
    }

    /** 拖动排序后批量持久化（入参即为新的显示顺序）。 */
    suspend fun updateOrders(idsInOrder: List<Long>) {
        dao.updateOrders(idsInOrder.mapIndexed { index, id -> IdOrder(id, index) })
        invalidate()
    }

    // ==================== V2T04 旧规则迁移 ====================

    /**
     * 启动时一次性迁移写回（NfApplication.onCreate 投递，架构 §2.6 / 共享约定 7）：
     * 1. 挑出 `needsWriteback(json)` 的待写回规则（V1 旧格式或含 actions.focus 死数据）；
     * 2. 有则先 [DbBackup.backup]（仅当不存在，不覆盖旧备份；失败仅告警不阻断——
     *    迁移映射本身语义保持，且读路径幂等兜底）；
     * 3. 逐条迁移 + [RuleDao.updateJson] 写回；单条失败仅记日志（旧 JSON 保留，下次重启重试）。
     *
     * @return 写回成功的条数（0 = 无需迁移或全部失败）。
     */
    suspend fun migrateAllIfNeeded(): Int {
        val migrated = migrateAllIfNeededInternal()
        // 写回了新 JSON（migrateJsonIfNeeded 的产物）→ 丢弃缓存，避免缓存里留着「旧 JSON 的
        // 即时迁移结果」与库内新 JSON 出现口径漂移（下次读取重建，成本仅一次启动）。
        if (migrated > 0) invalidate()
        return migrated
    }

    /** [migrateAllIfNeeded] 的实现体（缓存失效由调用方统一处理，保持失败路径同样生效）。 */
    private suspend fun migrateAllIfNeededInternal(): Int = try {
        // V5：筛选口径从 needsMigration（只认 V1）换成 needsWriteback（V1 或含 actions.focus
        // 死数据）——否则 V2-V4 时代落库的带 focus 规则永远不进写回清单，DB 残留死数据
        val legacy = dao.getAll().filter { RuleJsonMigrator.needsWriteback(it.json) }
        if (legacy.isEmpty()) {
            0
        } else {
            if (!DbBackup.backup(appContext)) {
                NfLog.w(RuleJsonMigrator, "迁移前备份未创建（已存在或失败），继续写回")
            }
            var migrated = 0
            legacy.forEach { entity ->
                try {
                    val newJson = RuleJsonMigrator.migrateJsonIfNeeded(entity.json)
                    dao.updateJson(entity.id, newJson)
                    migrated++
                } catch (e: Exception) {
                    // 单条失败保留旧 JSON；读路径即时迁移保证行为正确，下次启动重试写回
                    NfLog.w(RuleJsonMigrator, "规则 ${entity.id} 写回失败: ${e.message}")
                }
            }
            NfLog.i(RuleJsonMigrator, "旧规则迁移写回完成: $migrated / ${legacy.size} 条")
            migrated
        }
    } catch (e: Exception) {
        // 整体失败（如 DB 未就绪）不崩溃、不阻塞启动，下次启动重试
        NfLog.w(RuleJsonMigrator, "migrateAllIfNeeded 失败: ${e.message}")
        0
    }

    // ==================== 实体 ↔ 模型 ====================

    private fun RuleEntity.toRule(): Rule =
        NfJson.decodeFromString<Rule>(RuleJsonMigrator.migrateJsonIfNeeded(json))
            .copy(id = id, order = order)

    private fun Rule.toEntity(): RuleEntity = RuleEntity(
        id = id,
        name = name.ifBlank { "未命名规则" },
        enabled = enabled,
        order = order,
        schemaVersion = schemaVersion,
        json = NfJson.encodeToString(this),
    )
}
