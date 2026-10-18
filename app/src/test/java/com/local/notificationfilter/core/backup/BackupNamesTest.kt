package com.local.notificationfilter.core.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 备份文件名 / 保留策略单测（V11.1）。
 *
 * 为什么必须测：文件名规则错了不会崩溃，只会**悄悄地**该删的没删、不该删的删了
 * （比如把用户手动备份当自动备份清掉）。字典序 = 时间序这个前提是整个清理的地基。
 */
class BackupNamesTest {

    private val t = 1_792_000_000_000L // 2026-10-18 前后（具体时分无所谓，格式才重要）

    @Test
    fun `手动备份名带时间戳与 json 后缀`() {
        val name = BackupNames.manual(t)
        assertTrue(name.startsWith("nf-backup-"))
        assertTrue(name.endsWith(".json"))
        assertTrue(Regex("^nf-backup-\\d{8}-\\d{6}\\.json$").matches(name))
    }

    @Test
    fun `自动备份与快照用各自前缀`() {
        assertTrue(BackupNames.auto(t).startsWith("nf-auto-"))
        assertTrue(BackupNames.beforeRestore(t).startsWith("nf-before-restore-"))
        // 前缀互不为前缀关系：清理时才不会误伤
        assertFalse(BackupNames.isAuto(BackupNames.manual(t)))
        assertFalse(BackupNames.isBeforeRestore(BackupNames.manual(t)))
        assertFalse(BackupNames.isManual(BackupNames.auto(t)))
        assertFalse(BackupNames.isManual(BackupNames.beforeRestore(t)))
        assertFalse(BackupNames.isAuto(BackupNames.beforeRestore(t)))
    }

    @Test
    fun `时间戳定宽补零 字典序等于时间序`() {
        val early = BackupNames.auto(t)
        val late = BackupNames.auto(t + 3_600_000L)
        assertTrue("字典序应当等于时间序：$early vs $late", early < late)
        // 月份/日期补零：1 月必须排在 10 月之前
        val jan = BackupNames.auto(1_767_225_600_000L) // 2026-01-01 00:00 附近
        val oct = BackupNames.auto(t)
        assertTrue(jan < oct)
    }

    @Test
    fun `清理只挑同类前缀 且保持最近若干份`() {
        val existing = listOf(
            "nf-auto-20260101-0300.json",
            "nf-auto-20260102-0300.json",
            "nf-auto-20260103-0300.json",
            "nf-auto-20260104-0300.json",
            "nf-backup-20260101-1605.json", // 手动备份，不删
            "nf-before-restore-20260105-1000.json", // 快照，不删
            "other.json", // 无关文件，不删
        )
        // 保持最近 2 份（0103 / 0104），该删的是更早的 2 份（新的在前，与列表顺序一致）
        val stale = BackupNames.staleFiles(existing, BackupNames.AUTO_PREFIX, keep = 2)
        assertEquals(
            listOf("nf-auto-20260102-0300.json", "nf-auto-20260101-0300.json"),
            stale,
        )
    }

    @Test
    fun `份数未超限时一个都不删`() {
        val existing = listOf("nf-auto-20260101-0300.json", "nf-auto-20260102-0300.json")
        assertTrue(BackupNames.staleFiles(existing, BackupNames.AUTO_PREFIX, keep = 30).isEmpty())
        assertTrue(BackupNames.staleFiles(existing, BackupNames.AUTO_PREFIX, keep = 2).isEmpty())
    }

    @Test
    fun `输入无序也能正确清理`() {
        val existing = listOf(
            "nf-auto-20260103-0300.json",
            "nf-auto-20260101-0300.json",
            "nf-auto-20260102-0300.json",
        )
        assertEquals(
            listOf("nf-auto-20260101-0300.json"),
            BackupNames.staleFiles(existing, BackupNames.AUTO_PREFIX, keep = 2),
        )
    }
}
