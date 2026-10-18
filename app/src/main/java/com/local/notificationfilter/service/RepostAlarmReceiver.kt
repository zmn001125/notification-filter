package com.local.notificationfilter.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.local.notificationfilter.AppContainer
import com.local.notificationfilter.NfApplication
import com.local.notificationfilter.core.model.AggregationConfig
import com.local.notificationfilter.core.model.AggregationLists
import com.local.notificationfilter.core.model.NfJson
import com.local.notificationfilter.core.model.PendingRepost
import com.local.notificationfilter.util.NfLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 延时重发闹钟到点（T06）：读任务 → 重发改写版通知 → 删除任务。
 *
 * 注意：重发出去的通知会再次被监听服务收到，由管线的「跳过自身包名」约定防递归。
 *
 * V7（需求 8 / 架构 §3.8，D15）：`task.renderSource == AGGREGATION` 时**不再**原样重发
 * 调度时定型的文案，而是「到点那一刻的最新聚合列表」重新渲染——推迟期间新到的条目
 * （例如又一条取件码）自然带上。三条硬约束：
 * 1. **绝不在主线程读库**：[onReceive] 是 BroadcastReceiver，一律 `goAsync()` +
 *    `Dispatchers.IO` 协程，`finally { pendingResult.finish() }` 保证系统回收；
 * 2. **超时保护**：整条链路包在 [DELIVERY_TIMEOUT_MS] 内，防止 DB 卡死导致
 *    BroadcastReceiver 超时被系统 ANR；
 * 3. **异常必须回落**：配置坏 / 读库失败 / 列表为空 / 渲染为空 → 一律回落调度时文案
 *    （`task.repostTitle/repostText`），绝不发空通知，也绝不因异常丢掉整条任务。
 *
 * `renderSource == PLAIN`（V6 落库的旧任务）走 [NotificationReposter.postFromTask] 单参重载，
 * 与 V6 行为**逐字一致**。
 */
class RepostAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_REPOST) return
        val taskId = intent.getLongExtra(EXTRA_TASK_ID, -1L)
        if (taskId <= 0L) return

        val container = (context.applicationContext as? NfApplication)?.container ?: return
        val pendingResult = goAsync()
        // IO 线程 + SupervisorJob：读库/Room 一律不碰主线程；单条失败不牵连进程内其它协程。
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val finished = withTimeoutOrNull(DELIVERY_TIMEOUT_MS) {
                    deliver(container, taskId)
                }
                if (finished == null) {
                    // 超时：保留任务（下次开机的 rescheduleAll 或用户重试时再发），不删库。
                    NfLog.e(this, "延时重发超时（${DELIVERY_TIMEOUT_MS}ms），保留任务 id=$taskId")
                }
            } catch (t: Throwable) {
                // 兜底：绝不让异常逃出 onReceive（那会让系统认为广播处理失败）。
                NfLog.e(this, "延时重发异常 id=$taskId: ${t.message}", t)
            } finally {
                pendingResult.finish()
            }
        }
    }

    /**
     * 真正干活（可被超时取消）。
     *
     * @return 无论成功与否都返回 true（返回 Unit 即可，这里返回 Boolean 仅为 [withTimeoutOrNull]
     *   区分「超时（null）」与「正常结束」）
     */
    private suspend fun deliver(container: AppContainer, taskId: Long): Boolean {
        // 注：PendingRepost 没有 `getById`，沿用 V6 的 allPending() 查找口径（未触发任务量很小）。
        val task = container.repostRepository.allPending().firstOrNull { it.id == taskId }
        if (task == null) {
            NfLog.w(this, "延时任务不存在（可能已取消）id=$taskId")
            return true
        }
        val ok = if (task.isAggregated) {
            postAggregated(container, task)
        } else {
            // PLAIN：V6 原样重发（一行未变）
            container.notificationReposter.postFromTask(task)
        }
        if (ok) {
            container.repostRepository.delete(task.id)
            NfLog.i(this, "延时重发完成 id=${task.id} pkg=${task.packageName} src=${task.renderSource}")
        } else {
            NfLog.e(this, "延时重发失败，保留任务 id=${task.id}")
        }
        return true
    }

    /**
     * `AGGREGATION` 路径：反序列化配置 → 读最新列表 → 重渲染 → 发。
     *
     * 任何一步不可用都**回落 PLAIN**（调度时文案），因此本方法永不返回"发出空通知"。
     */
    private suspend fun postAggregated(container: AppContainer, task: PendingRepost): Boolean {
        val reposter = container.notificationReposter
        val config = decodeAggregation(task)
        val ruleId = task.ruleId
        if (config == null || ruleId == null || ruleId <= 0L) {
            NfLog.w(this, "聚合配置缺失/规则已不存在，回落调度时文案 id=${task.id}")
            return reposter.postFromTask(task)
        }

        // 每个 listIndex 的读取上限 = 该提取器配置的 limit（与管线写入时的汰旧口径一致）。
        val limits: Map<Int, Int> = config.active.associate { it.listIndex to it.limit }
        if (limits.isEmpty()) {
            // 提取器全被删/全关 → 没有可渲染的数据源，回落调度时文案。
            NfLog.w(this, "聚合无可用提取器，回落调度时文案 id=${task.id}")
            return reposter.postFromTask(task)
        }

        val lists: AggregationLists = runCatching {
            container.aggregationRepository.listsOf(ruleId, limits)
        }.onFailure {
            NfLog.e(this, "读取聚合列表失败，回落调度时文案 id=${task.id}: ${it.message}")
        }.getOrDefault(AggregationLists.EMPTY)

        if (lists.totalItems == 0) {
            // 推迟期间没攒到条目（或列表被用户清空）→ 回落，**绝不发空通知**。
            NfLog.i(this, "聚合列表为空，回落调度时文案 id=${task.id} rule=$ruleId")
            return reposter.postFromTask(task)
        }

        val rendered = runCatching { reposter.renderAggregated(task, config, lists) }
            .onFailure { NfLog.e(this, "聚合渲染失败，回落调度时文案 id=${task.id}: ${it.message}") }
            .getOrNull()
            ?: return reposter.postFromTask(task)

        NfLog.i(
            this,
            "聚合重渲染 id=${task.id} rule=$ruleId items=${lists.totalItems} " +
                "title=${rendered.title}",
        )
        // 渲染结果为空串时由 postFromTask 内部再兜一次 task.repostTitle/repostText
        return reposter.postFromTask(task, rendered.title, rendered.text)
    }

    /** `aggregationJson` → [AggregationConfig]；缺失/损坏 → null（调用方回落 PLAIN）。 */
    private fun decodeAggregation(task: PendingRepost): AggregationConfig? {
        val json = task.aggregationJson ?: return null
        return runCatching { NfJson.decodeFromString<AggregationConfig>(json) }
            .onFailure {
                NfLog.e(this, "聚合配置解析失败 id=${task.id}，回落调度时文案: ${it.message}")
            }
            .getOrNull()
    }

    companion object {
        const val ACTION_REPOST = "com.local.notificationfilter.action.REPOST"
        const val EXTRA_TASK_ID = "task_id"

        /**
         * 整条链路（读库 + 渲染 + 发通知）的超时上限。
         * 选 10s：远小于广播处理 60s 上限，足够 Room 冷启动 + 一次小查询，又能在 DB 卡死时兜住。
         */
        private const val DELIVERY_TIMEOUT_MS = 10_000L
    }
}
