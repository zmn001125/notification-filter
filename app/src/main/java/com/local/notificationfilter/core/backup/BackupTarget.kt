package com.local.notificationfilter.core.backup

/**
 * 备份目标的写入结果。
 *
 * 刻意不用 `Result<T>`：失败时的 [Fail.message] 是**给用户看的中文原因**
 * （「认证失败，请检查账号与应用密码」而不是「IOException: 401」），
 * 这类文案需要携带上下文，所以独立建模。
 */
sealed interface BackupWriteResult {
    /** 成功；[location] 是给人看的位置（本地文件名 / WebDAV 完整 URL）。 */
    data class Ok(val location: String) : BackupWriteResult

    /** 失败；[message] 必须是可直接展示的中文。 */
    data class Fail(val message: String) : BackupWriteResult
}

/**
 * 一个可读写的备份目标（本地文件夹 / WebDAV 目录）。
 *
 * 三个方法都必须**自己切 IO 线程**（实现内部 `withContext(Dispatchers.IO)`），
 * 调用方（ViewModel / 广播接收器）不需要关心线程。
 *
 * 只操作**单层目录**：本地是 SAF 树 Uri 的根，WebDAV 是 [webDavPath] 所在的目录，
 * 不递归。备份文件本来就该平铺，递归只会让「恢复时选哪个」变复杂。
 */
interface BackupTarget {

    /** 写入（同名覆盖）。 */
    suspend fun write(fileName: String, content: String): BackupWriteResult

    /**
     * 读取。
     * @return 成功时 `Result.success(内容)`；失败时 `Result.failure`，
     *   异常的 message 是中文可读原因。
     */
    suspend fun read(fileName: String): Result<String>

    /** 列出该目录下可恢复的备份文件名（后缀 .json）；失败返回空列表。 */
    suspend fun list(): List<String>

    /**
     * 删除一个备份文件。
     *
     * @return true = 已删除（或本来就不在）；false = 删除失败（权限 / 网络 / 密码）。
     *   失败时实现方应记日志，UI 只提示「删除失败」这种用户能懂的话。
     */
    suspend fun delete(fileName: String): Boolean

    companion object {
        /** 备份文件扩展名（列表过滤用）。 */
        const val EXTENSION = ".json"
    }
}
