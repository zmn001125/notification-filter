package com.local.notificationfilter.core.backup

import com.local.notificationfilter.util.NfLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * WebDAV 备份目标（V11 新建，V11.1 重写传输层）。**默认面向坚果云**
 * （`https://dav.jianguoyun.com/dav/`），但只用标准 WebDAV 动词，其它服务
 * （Nextcloud / 群晖 / ownCloud）同样可用。
 *
 * ## V11.1 为什么整层重写
 * V11.0 用 `HttpURLConnection` 发 PROPFIND / MKCOL，而 **Android 的
 * HttpURLConnection 与桌面 JDK 一样只放行七个标准方法**
 * （GET/POST/HEAD/OPTIONS/PUT/DELETE/TRACE），WebDAV 动词直接抛
 * `ProtocolException: Expected one of [...] but was PROPFIND`。
 * 也就是说旧实现在真机上**一次都没成功过**（用户报的错误就是这条）。
 * 现在全部改走 [RawHttp]（原生 Socket 手写请求）：任意方法、任意头、
 * 无 chunked 请求、显式 Content-Length，实测坚果云 PROPFIND/MKCOL/PUT 均正常接收。
 *
 * 坚果云的实际行为（踩过的坑，改这里前先读）：
 * 1. **不能直接 PUT 到不存在的目录** → 409 Conflict。必须先逐级 MKCOL。
 *    MKCOL 对已存在目录返回 **405**，这是正常情况不是错误。
 * 2. 认证失败返回 **401**（账号或应用专用密码错）；权限不足是 **403**。
 * 3. 坚果云要求**应用专用密码**（网页版「账户信息 → 安全选项 → 添加应用密码」），
 *    直接填登录密码一定 401 —— 这一点必须写进 UI 提示。
 * 4. 有用户反馈个别情况下 MKCOL 建目录不灵，需要先在网页版建好目录再填路径；
 *    所以 409 的报错文案要引导用户去网页版建目录。
 * 5. 路径里的中文/空格必须逐段 URL 编码且把 `+` 换回 `%20`。
 *
 * 线程：所有网络调用都在 [Dispatchers.IO]，超时各 15 秒。
 */
class WebDavTarget(
    private val baseUrl: String,
    private val account: String,
    private val password: String,
    /** 远程文件路径（相对 baseUrl），例如 `notification-filter/nf-backup.json`；
     *  **只有目录部分生效**，文件名由调用方（BackupRepository）按时间生成。 */
    private val remoteFilePath: String,
) : BackupTarget {

    private val http = RawHttp()

    private fun authHeader(): Map<String, String> = mapOf(
        "Authorization" to RawHttp.basicAuthValue(account, password),
        "User-Agent" to USER_AGENT,
    )

    /** 统一发一次 WebDAV 请求（带认证头 + 可选 Depth + 可选请求体）。 */
    private fun request(
        method: String,
        url: String,
        depth: String? = null,
        body: String? = null,
        contentType: String? = null,
    ): RawHttp.Response {
        val headers = LinkedHashMap<String, String>()
        headers += authHeader()
        if (depth != null) headers["Depth"] = depth
        if (contentType != null) headers["Content-Type"] = contentType
        return http.execute(method, url, headers, body)
    }

    // ==================== 写入 ====================

    override suspend fun write(fileName: String, content: String): BackupWriteResult =
        withContext(Dispatchers.IO) {
            runCatching {
                // 先确保目录存在（幂等，已存在返回 405 直接忽略）
                ensureParentDirs()
                var code = put(fileName, content)
                // 409 = 父目录还是不在（被外部删掉 / MKCOL 没建上）：补建再试一次
                if (code == 409) {
                    ensureParentDirs()
                    code = put(fileName, content)
                }
                when {
                    code in 200..299 -> BackupWriteResult.Ok(fileUrl(fileName))
                    code == 401 -> BackupWriteResult.Fail("认证失败，请检查账号与应用专用密码")
                    code == 403 -> BackupWriteResult.Fail("没有写入权限，请检查远程路径")
                    code == 409 -> BackupWriteResult.Fail(
                        "远程目录不存在且无法自动创建（HTTP 409），请先在坚果云网页版建好目录",
                    )
                    code == 507 -> BackupWriteResult.Fail("云端空间不足")
                    else -> BackupWriteResult.Fail("上传失败（HTTP $code）")
                }
            }.getOrElse {
                BackupWriteResult.Fail("上传失败：${it.message ?: it.javaClass.simpleName}")
            }
        }

    private fun put(fileName: String, content: String): Int =
        request("PUT", fileUrl(fileName), body = content, contentType = "application/json; charset=utf-8").statusCode

    // ==================== 读取 / 列表 ====================

    override suspend fun read(fileName: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val response = request("GET", fileUrl(fileName))
            when {
                response.ok -> response.body
                response.statusCode == 401 -> error("认证失败，请检查账号与应用专用密码")
                response.statusCode == 404 -> error("云端找不到 $fileName")
                else -> error("下载失败（HTTP ${response.statusCode}）")
            }
        }
    }

    override suspend fun list(): List<String> = withContext(Dispatchers.IO) {
        runCatching {
            val response = request("PROPFIND", dirUrl(), depth = "1", body = PROPFIND_BODY, contentType = XML_CONTENT_TYPE)
            if (!response.ok) return@withContext emptyList()
            parseBackupNames(response.body)
        }.getOrDefault(emptyList())
    }

    /**
     * 连接自检：两步，都只针对「我们自己的备份目录」，不会碰用户其它文件。
     *
     * 1. `PROPFIND Depth:0` 探**服务器根目录**（一定存在）→ 验证地址 + 账号密码；
     * 2. `MKCOL` 目标目录（已存在返回 405，正常）→ 验证写权限，顺带把目录建好，
     *    免得之后 PROPFIND 列表撞 404。
     *
     * 返回成功时给的是目录 URL（给人看的）。
     */
    suspend fun probe(): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val root = baseUrlWithSlash()
            val rootResponse = request("PROPFIND", root, depth = "0", body = PROPFIND_BODY, contentType = XML_CONTENT_TYPE)
            when {
                rootResponse.ok -> Unit
                rootResponse.statusCode == 401 -> error(
                    "认证失败：请确认使用的是坚果云「应用专用密码」而不是登录密码",
                )
                rootResponse.statusCode == 403 -> error("没有访问权限，请检查服务器地址")
                else -> error("无法连接服务器（HTTP ${rootResponse.statusCode}）：$root")
            }
            if (remotePrefix().isNotEmpty()) {
                val mk = request("MKCOL", dirUrl())
                when {
                    // 201 新建成功 / 405 已存在 —— 都算通过
                    mk.ok || mk.statusCode == 405 -> Unit
                    mk.statusCode == 401 -> error("认证失败，请检查账号与应用专用密码")
                    else -> error(
                        "无法创建远程目录（HTTP ${mk.statusCode}）：" +
                            "请先在坚果云网页版建好目录，或检查「远程目录」填写是否有误",
                    )
                }
            }
            dirUrl()
        }
    }

    // ==================== 删除 ====================

    /**
     * 删除云端备份文件。
     * 204/200 = 删掉了；404 = 本来就不在，也算达成目的；
     * 其它状态码（401 等）= 没删掉，返回 false 让 UI 提示。
     */
    override suspend fun delete(fileName: String): Boolean = withContext(Dispatchers.IO) {
        val response = runCatching { request("DELETE", fileUrl(fileName)) }.getOrNull()
        val ok = response != null && (response.ok || response.statusCode == 404)
        if (!ok) {
            NfLog.w(
                this@WebDavTarget,
                "删除云端备份失败：$fileName（HTTP ${response?.statusCode ?: "无响应"}）",
            )
        }
        ok
    }

    // ==================== 路径拼装 ====================

    private fun baseUrlWithSlash(): String =
        baseUrl.trim().let { if (it.endsWith("/")) it else "$it/" }

    /** `remoteFilePath` 的目录部分（可能为空串 = 直接在根目录）。 */
    private fun remotePrefix(): String =
        remoteFilePath.trim().trimStart('/').substringBeforeLast('/', "")

    /** 目录/集合 URL（无末尾斜杠）。 */
    private fun collectionUrl(path: String): String =
        if (path.isEmpty()) baseUrlWithSlash() else baseUrlWithSlash() + encodePath(path)

    private fun dirUrl(): String {
        val prefix = remotePrefix()
        return if (prefix.isEmpty()) baseUrlWithSlash() else collectionUrl(prefix) + "/"
    }

    private fun fileUrl(fileName: String): String {
        val prefix = remotePrefix()
        val dirPart = if (prefix.isEmpty()) "" else encodePath(prefix) + "/"
        return baseUrlWithSlash() + dirPart + encodeSegment(fileName)
    }

    /**
     * 逐级 MKCOL 创建父目录。
     *
     * 每次都从根往后逐级调用（幂等）：目录已存在时服务端返回 405，直接忽略。
     * 这样即使目录被外部删掉也能自愈，不需要额外状态。
     */
    private fun ensureParentDirs() {
        val prefix = remotePrefix()
        if (prefix.isEmpty()) return
        var acc = ""
        prefix.split('/').filter { it.isNotBlank() }.forEach { seg ->
            acc = if (acc.isEmpty()) seg else "$acc/$seg"
            runCatching {
                val code = request("MKCOL", collectionUrl(acc)).statusCode
                // 201 新建成功 / 405 已存在 / 409 父目录还没建好（下一轮会补）—— 都不是致命错误
                if (code == 401) error("认证失败，请检查账号与应用专用密码")
            }
        }
    }

    private fun encodePath(path: String): String =
        path.split('/').filter { it.isNotBlank() }.joinToString("/") { encodeSegment(it) }

    private fun encodeSegment(seg: String): String =
        java.net.URLEncoder.encode(seg, "UTF-8").replace("+", "%20")

    // ==================== PROPFIND 响应解析 ====================

    /**
     * 从 multistatus XML 里抠备份文件名。
     *
     * 命名空间前缀**不能写死**：wsgidav 用 `ns0:`、坚果云用 `d:`、有的服务干脆
     * 不带前缀，所以正则对前缀通吃；`displayname` 拿不到时退一路用 `href` 的最后一段。
     * 集合自身的 displayname 是目录名（不以 .json 结尾），天然被扩展名过滤掉。
     */
    internal fun parseBackupNames(xml: String): List<String> {
        val displayNames = Regex(
            "<[A-Za-z0-9_-]*:?displayname[^>]*>(.*?)</[A-Za-z0-9_-]*:?displayname>",
            RegexOption.DOT_MATCHES_ALL,
        ).findAll(xml).map { it.groupValues[1].trim() }
        val names = if (displayNames.any()) displayNames else Regex(
            "<[A-Za-z0-9_-]*:?href[^>]*>(.*?)</[A-Za-z0-9_-]*:?href>",
            RegexOption.DOT_MATCHES_ALL,
        ).findAll(xml).map { it.groupValues[1].trim().substringAfterLast('/') }
        return names
            .filter { it.endsWith(BackupTarget.EXTENSION, ignoreCase = true) }
            .filter { it.isNotBlank() }
            .distinct()
            .sortedDescending()
            .toList()
    }

    private companion object {
        const val USER_AGENT = "notification-filter/1.1"

        /** PROPFIND 请求体的 Content-Type。 */
        const val XML_CONTENT_TYPE = "application/xml; charset=utf-8"

        /** PROPFIND 请求体：只要 displayname / resourcetype，够列文件名了。 */
        const val PROPFIND_BODY: String =
            "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
                "<d:propfind xmlns:d=\"DAV:\"><d:prop>" +
                "<d:displayname/><d:resourcetype/>" +
                "</d:prop></d:propfind>"
    }
}
