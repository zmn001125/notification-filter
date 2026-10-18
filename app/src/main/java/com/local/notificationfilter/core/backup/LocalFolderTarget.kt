package com.local.notificationfilter.core.backup

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.local.notificationfilter.util.NfLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 本地文件夹备份目标（V11）：基于 SAF 树 Uri（`ACTION_OPEN_DOCUMENT_TREE` 授权）。
 *
 * 为什么用 `DocumentsContract` 而不是 `DocumentFile`：项目没有
 * `androidx.documentfile` 依赖，为这一个功能加依赖不值得；`DocumentsContract`
 * 是平台 API（API 21+），能力完全够用（列目录 / 建文件 / 删文件 / 读写）。
 *
 * 权限前提：UI 侧拿到 tree Uri 后必须调
 * `contentResolver.takePersistableUriPermission(uri, READ or WRITE)`，
 * 否则进程重启后授权失效。本类每次操作前都会 `persistedUriPermissions` 复查，
 * 失效时返回可读原因而不是让 SAf 抛 `SecurityException`。
 *
 * 覆盖语义：同名文件**真正覆盖**（先试 `"wt"` 截断写，失败再删除重建），
 * 否则部分 provider 会生成 `nf-backup-auto (1).json` 这类副本，
 * 自动备份每天跑一次就会堆一堆文件。
 */
class LocalFolderTarget(
    private val context: Context,
    private val treeUriString: String,
) : BackupTarget {

    private val resolver = context.contentResolver
    private val treeUri: Uri = Uri.parse(treeUriString)

    override suspend fun write(fileName: String, content: String): BackupWriteResult =
        withContext(Dispatchers.IO) {
            val treeDocId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull()
                ?: return@withContext BackupWriteResult.Fail("备份文件夹地址无效，请重新选择")
            if (!hasWritePermission()) {
                return@withContext BackupWriteResult.Fail("备份文件夹权限已失效，请重新选择")
            }
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, treeDocId)
            val bytes = content.toByteArray(Charsets.UTF_8)
            val existingId = runCatching { findDocumentId(childrenUri, fileName) }.getOrNull()

            // 1) 已存在 → 优先截断写（保留原 documentId，不存在"副本"问题）
            if (existingId != null) {
                val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, existingId)
                val written = runCatching {
                    resolver.openOutputStream(docUri, "wt")?.use { it.write(bytes) } ?: error("null stream")
                }.isSuccess
                if (written) return@withContext BackupWriteResult.Ok(fileName)
                // 2) 截断写不支持 → 删了重建
                runCatching { DocumentsContract.deleteDocument(resolver, docUri) }
            }

            // 3) 新建
            runCatching {
                val created = DocumentsContract.createDocument(
                    resolver,
                    childrenUri,
                    "application/json",
                    fileName,
                ) ?: error("createDocument 返回 null")
                resolver.openOutputStream(created, "wt")?.use { it.write(bytes) } ?: error("null stream")
            }.fold(
                onSuccess = { BackupWriteResult.Ok(fileName) },
                onFailure = {
                    BackupWriteResult.Fail(
                        "写入失败：${it.message ?: it.javaClass.simpleName}（文件夹可能已被移动或删除）",
                    )
                },
            )
        }

    override suspend fun read(fileName: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            if (!hasWritePermission()) error("备份文件夹权限已失效，请重新选择")
            val treeDocId = DocumentsContract.getTreeDocumentId(treeUri)
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, treeDocId)
            val id = findDocumentId(childrenUri, fileName) ?: error("文件夹里找不到 $fileName")
            val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, id)
            resolver.openInputStream(docUri)?.use { it.readBytes().toString(Charsets.UTF_8) }
                ?: error("无法打开 $fileName")
        }.recoverCatching { throw IllegalStateException(it.message ?: "读取失败", it) }
    }

    override suspend fun list(): List<String> = withContext(Dispatchers.IO) {
        runCatching {
            val treeDocId = DocumentsContract.getTreeDocumentId(treeUri)
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, treeDocId)
            val names = mutableListOf<String>()
            resolver.query(
                childrenUri,
                arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                null,
                null,
                null,
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    val name = cursor.getString(0) ?: continue
                    if (name.endsWith(BackupTarget.EXTENSION, ignoreCase = true)) names += name
                }
            }
            names.sortedDescending()
        }.getOrDefault(emptyList())
    }

    /**
     * 删除本地备份文件（V11.1）。
     *
     * SAF 删除：先按显示名查 documentId，再 `deleteDocument`。
     * 权限失效时返回 false（UI 提示重新选文件夹）。
     */
    override suspend fun delete(fileName: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            if (!hasWritePermission()) {
                NfLog.w(this@LocalFolderTarget, "删除失败：文件夹写入权限已失效")
                return@withContext false
            }
            val treeDocId = DocumentsContract.getTreeDocumentId(treeUri)
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, treeDocId)
            val id = findDocumentId(childrenUri, fileName) ?: return@withContext true
            DocumentsContract.deleteDocument(resolver, DocumentsContract.buildDocumentUriUsingTree(treeUri, id))
        }.getOrDefault(false)
    }

    /** 在 children 里按显示名找 documentId；找不到返回 null。 */
    private fun findDocumentId(childrenUri: Uri, fileName: String): String? {
        resolver.query(
            childrenUri,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            ),
            null,
            null,
            null,
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val name = cursor.getString(1) ?: continue
                if (name == fileName) return cursor.getString(0)
            }
        }
        return null
    }

    private fun hasWritePermission(): Boolean =
        resolver.persistedUriPermissions.any {
            it.uri == treeUri && it.isWritePermission
        }
}
