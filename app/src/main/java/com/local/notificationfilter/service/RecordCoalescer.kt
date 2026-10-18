package com.local.notificationfilter.service

/**
 * V10.11 刷屏修复：**「这次内容更新该合并进既有流水行，还是另起一行」的唯一判定口径**。
 *
 * 抽成纯对象（无 Android 依赖）的理由与 [NotificationFingerprint] 完全一致：
 * 这类「合并 / 不合并」的判定一旦判错，两个方向都有代价——
 * - 该合的不合 → 一条下载通知跑一小时就把记录页刷满（本次要修的 bug）；
 * - 不该合的合了 → 用户连收两条微信，第二条被合进第一条那一行，**看起来像丢了通知**
 *   （最坏形态，且极难复现）。
 *
 * 所以把时间窗语义逐条钉死在单测里，管线侧只做「查表 + 调用」。
 *
 * 完整语义由管线侧两部分共同保证：
 * 1. **本对象**：时间窗兜底——距上次写入超过 [WINDOW_MS] 就另起一行；
 * 2. **`NotificationPipeline.onRemoved`**：权威失效信号——通知被移除即摘除映射，
 *    同 key 再投递必然另起一行。
 *
 * 只靠 2 不够（移除广播会丢：服务被系统重启、部分系统魔改）；只靠 1 不够（窗口内
 * 「移除后立刻重投」会被错合并）。两条一起才是完整口径。
 */
internal object RecordCoalescer {

    /**
     * 合并时间窗（ms）：10 分钟。
     *
     * 为什么放宽到 10 分钟：真正的失效信号是移除广播（见类注释第 2 点），窗口只是兜底。
     * 下载 / 备份 / 音乐 / 导航这类持续刷新的通知绝大多数在 10 分钟内结束或转向低频，
     * 偶发超窗多插一行远好于「一行都不合并」；反过来收得太紧（比如 60 秒）会让慢速
     * 下载仍然刷屏，等于没修。
     */
    const val WINDOW_MS: Long = 10 * 60 * 1000L

    /**
     * 是否把本次更新合并进既有流水行。
     *
     * @param lastWriteAt 该 key 上一次写入流水的时刻；`null` = 没见过 / 已被移除 → 必然不合并
     * @param now 本次时刻
     * @param windowMs 合并窗口（默认 [WINDOW_MS]；测试可传小值）
     */
    fun shouldCoalesce(
        lastWriteAt: Long?,
        now: Long,
        windowMs: Long = WINDOW_MS,
    ): Boolean {
        if (lastWriteAt == null) return false
        val elapsed = now - lastWriteAt
        // 时钟回拨（NTP 校时 / 用户手动改时间）：elapsed 为负。
        // 此时**保守地不合并**——否则 `elapsed <= window` 恒成立，映射会越写越久，
        // 用户会看到「新通知被吞进一条旧记录」。
        if (elapsed < 0) return false
        return elapsed <= windowMs
    }
}
