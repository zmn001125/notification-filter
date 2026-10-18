package com.local.notificationfilter.core.backup

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.Collections
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [WebDavTarget] 端到端单测（V11.1）。
 *
 * **这是整个 V11.1 最重要的测试**：V11.0 的 WebDAV 通道在真机上从未通过一次
 * （方法被 HttpURLConnection 白名单拒掉），靠的就是这类「协议层问题编译期看不出来」。
 * 这里起一个进程内的迷你 WebDAV 服务器（支持 PROPFIND / MKCOL / PUT / GET / DELETE，
 * PROPFIND 响应用 **chunked + ns0: 前缀**，刻意贴近 wsgidav 的真实行为），
 * 让 WebDavTarget 走完整流程：探测 → 上传 → 列表 → 读取 → 删除。
 */
class WebDavTargetTest {

    private lateinit var server: FakeDavServer
    private lateinit var target: WebDavTarget

    @Before
    fun setUp() {
        server = FakeDavServer()
        server.start()
        target = WebDavTarget(
            baseUrl = server.baseUrl,
            account = "test@example.com",
            password = "app-password",
            remoteFilePath = "notification-filter/nf-backup.json",
        )
    }

    @After
    fun tearDown() {
        server.stop()
    }

    // ==================== 连接自检 ====================

    @Test
    fun `probe 成功 且会建好远程目录`() = runBlocking {
        val result = target.probe()
        assertTrue("probe 应成功，实际：${result.exceptionOrNull()}", result.isSuccess)
        assertTrue(server.collectionExists("/dav/notification-filter/"))
        // 两步都要走到：PROPFIND 根 + MKCOL 目录
        assertEquals(listOf("PROPFIND", "MKCOL"), server.methodsSeen())
    }

    @Test
    fun `probe 对已存在目录 405 也算成功`() = runBlocking {
        assertTrue(target.probe().isSuccess)
        val again = target.probe()
        assertTrue("第二次 probe 也应成功（MKCOL 405 是正常态）：${again.exceptionOrNull()}", again.isSuccess)
    }

    @Test
    fun `probe 认证失败时提示应用专用密码`() = runBlocking {
        server.unauthorized = true
        val result = target.probe()
        assertTrue(result.isFailure)
        val message = result.exceptionOrNull()?.message.orEmpty()
        assertTrue("提示应引导用应用专用密码，实际：$message", message.contains("应用专用密码"))
    }

    @Test
    fun `probe 无法建目录时引导去网页版建`() = runBlocking {
        server.mkcolBlocked = true
        val result = target.probe()
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("网页版"))
    }

    // ==================== 上传 / 列表 / 读取 ====================

    @Test
    fun `上传成功后能列出来并读回内容`() = runBlocking {
        val write = target.write("nf-backup-20261018-1605.json", "{\"rules\":[]}")
        assertTrue("上传应成功：$write", write is BackupWriteResult.Ok)
        assertEquals(listOf("nf-backup-20261018-1605.json"), target.list())
        assertEquals("{\"rules\":[]}", target.read("nf-backup-20261018-1605.json").getOrNull())
    }

    @Test
    fun `上传前会自动建目录`() = runBlocking {
        // 不先 probe：目录不存在，write 必须自己 MKCOL
        val write = target.write("nf-auto-20261018-0300.json", "{}")
        assertTrue("上传应成功：$write", write is BackupWriteResult.Ok)
        assertTrue(server.collectionExists("/dav/notification-filter/"))
    }

    @Test
    fun `目录建不了时 PUT 409 会重试并给出可操作提示`() = runBlocking {
        server.mkcolBlocked = true
        val write = target.write("nf-backup-x.json", "{}")
        assertTrue("应失败", write is BackupWriteResult.Fail)
        val message = (write as BackupWriteResult.Fail).message
        assertTrue("提示应引导去网页版建目录，实际：$message", message.contains("网页版"))
        // 409 重试：PUT 应发生两次
        assertEquals(2, server.methodsSeen().count { it == "PUT" })
    }

    @Test
    fun `读取不存在的文件返回 404 语义的中文错误`() = runBlocking {
        val result = target.read("nf-backup-nope.json")
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("找不到"))
    }

    @Test
    fun `列表按名字倒序 且只收 json`() = runBlocking {
        server.seedFiles(
            "/dav/notification-filter/nf-auto-20260101-0300.json" to "a",
            "/dav/notification-filter/nf-auto-20260102-0300.json" to "b",
            "/dav/notification-filter/nf-auto-20260103-0300.json" to "c",
            "/dav/notification-filter/notes.txt" to "ignore me",
        )
        assertEquals(
            listOf(
                "nf-auto-20260103-0300.json",
                "nf-auto-20260102-0300.json",
                "nf-auto-20260101-0300.json",
            ),
            target.list(),
        )
    }

    // ==================== 删除 ====================

    @Test
    fun `删除成功后列表不再包含该文件`() = runBlocking {
        target.write("nf-backup-20261018-1605.json", "{}")
        assertTrue(target.delete("nf-backup-20261018-1605.json"))
        assertEquals(emptyList<String>(), target.list())
    }

    @Test
    fun `删除不存在的文件也算达成目的`() = runBlocking {
        assertTrue(target.delete("nf-backup-ghost.json"))
    }

    @Test
    fun `删除在认证失败时返回 false`() = runBlocking {
        server.unauthorized = true
        assertFalse(target.delete("nf-backup-20261018-1605.json"))
    }

    // ==================== 纯解析 ====================

    @Test
    fun `parseBackupNames 兼容 d 前缀`() {
        val xml = """
            <?xml version="1.0" encoding="utf-8"?>
            <D:multistatus xmlns:D="DAV:">
              <D:response><D:href>/dav/notification-filter/</D:href>
                <D:propstat><D:prop><D:displayname>notification-filter</D:displayname></D:prop></D:propstat>
              </D:response>
              <D:response><D:href>/dav/notification-filter/nf-auto-20260101-0300.json</D:href>
                <D:propstat><D:prop><D:displayname>nf-auto-20260101-0300.json</D:displayname></D:prop></D:propstat>
              </D:response>
            </D:multistatus>
        """.trimIndent()
        assertEquals(listOf("nf-auto-20260101-0300.json"), target.parseBackupNames(xml))
    }

    @Test
    fun `parseBackupNames 兼容 ns0 前缀`() {
        val xml = """
            <?xml version="1.0" encoding="utf-8" ?>
            <ns0:multistatus xmlns:ns0="DAV:">
              <ns0:response><ns0:href>/</ns0:href>
                <ns0:propstat><ns0:prop><ns0:displayname>root</ns0:displayname></ns0:prop></ns0:propstat>
              </ns0:response>
              <ns0:response><ns0:href>/nf-backup-20261018-1605.json</ns0:href>
                <ns0:propstat><ns0:prop><ns0:displayname>nf-backup-20261018-1605.json</ns0:displayname></ns0:prop></ns0:propstat>
              </ns0:response>
            </ns0:multistatus>
        """.trimIndent()
        assertEquals(listOf("nf-backup-20261018-1605.json"), target.parseBackupNames(xml))
    }

    @Test
    fun `parseBackupNames 没有 displayname 时退回 href`() {
        val xml = """
            <?xml version="1.0"?>
            <multistatus xmlns="DAV:">
              <response><href>/dav/nf-auto-20260101-0300.json</href></response>
            </multistatus>
        """.trimIndent()
        assertEquals(listOf("nf-auto-20260101-0300.json"), target.parseBackupNames(xml))
    }

    @Test
    fun `parseBackupNames 忽略目录自身与非 json`() {
        val xml = """
            <d:multistatus xmlns:d="DAV:">
              <d:response><d:propstat><d:prop><d:displayname>notification-filter</d:displayname></d:prop></d:propstat></d:response>
              <d:response><d:propstat><d:prop><d:displayname>readme.txt</d:displayname></d:prop></d:propstat></d:response>
            </d:multistatus>
        """.trimIndent()
        assertEquals(emptyList<String>(), target.parseBackupNames(xml))
    }
}

/**
 * 进程内迷你 WebDAV 服务器：按真实语义维护「集合 + 文件」状态。
 *
 * - `PROPFIND` 集合 → 207 multistatus（**chunked 传输 + ns0: 前缀**，贴近 wsgidav）；
 * - `MKCOL` 已存在集合 → 405；`mkcolBlocked = true` 时一律 403（模拟建不了目录）；
 * - `PUT` 父集合不存在 → 409（WebDavTarget 应自动 MKCOL 重试）；
 * - `unauthorized = true` 时一律 401（模拟应用专用密码错）。
 */
private class FakeDavServer {

    private val serverSocket = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
    private val methods = Collections.synchronizedList(mutableListOf<String>())

    /** 已存在的集合（**规范化为无末尾斜杠**，贴近真实服务器的宽松匹配）。 */
    private val collections = Collections.synchronizedSet(mutableSetOf("/dav"))

    /** 文件：路径 → 内容。 */
    private val files = Collections.synchronizedMap(mutableMapOf<String, String>())

    @Volatile var unauthorized = false
    @Volatile var mkcolBlocked = false
    @Volatile private var closed = false

    val port: Int get() = serverSocket.localPort
    val baseUrl: String get() = "http://127.0.0.1:$port/dav/"

    fun start() {
        Thread {
            while (!closed) {
                val socket = runCatching { serverSocket.accept() }.getOrNull() ?: break
                Thread { handle(socket) }.start()
            }
        }.apply { isDaemon = true }.start()
    }

    fun stop() {
        closed = true
        runCatching { serverSocket.close() }
    }

    fun collectionExists(path: String): Boolean = collections.contains(normalize(path))

    fun methodsSeen(): List<String> = methods.toList()

    fun seedFiles(vararg entries: Pair<String, String>) {
        entries.forEach { (path, content) ->
            files[path] = content
            collections += normalize(path.substringBeforeLast('/'))
        }
    }

    private fun handle(socket: Socket) {
        socket.use {
            val request = readRequest(it.getInputStream())
            val lines = request.split("\r\n")
            val parts = lines.first().split(' ')
            val method = parts.getOrElse(0) { "" }
            val path = parts.getOrElse(1) { "/" }
            methods += method
            val body = route(method, path, request)
            it.getOutputStream().apply {
                write(body.toByteArray(StandardCharsets.UTF_8))
                flush()
            }
        }
    }

    private fun route(method: String, path: String, request: String): String {
        if (unauthorized) return respond(401, "")
        return when (method) {
            "PROPFIND" -> {
                if (collections.contains(normalize(path))) {
                    respond(207, multistatus(path), chunked = true)
                } else {
                    respond(404, "")
                }
            }
            "MKCOL" -> {
                if (mkcolBlocked) return respond(403, "")
                if (collections.contains(normalize(path))) respond(405, "") else {
                    collections += normalize(path)
                    respond(201, "")
                }
            }
            "PUT" -> {
                val parent = normalize(path.substringBeforeLast('/'))
                if (!collections.contains(parent)) {
                    respond(409, "")
                } else {
                    files[path] = request.substringAfter("\r\n\r\n")
                    respond(201, "")
                }
            }
            "GET" -> {
                val content = files[path] ?: return respond(404, "")
                respond(200, content)
            }
            "DELETE" -> {
                if (files.remove(path) != null) respond(204, "") else respond(404, "")
            }
            else -> respond(405, "")
        }
    }

    /** 生成 multistatus：集合自身 + 直属文件，命名空间用 ns0:（贴近 wsgidav）。 */
    private fun multistatus(collectionPath: String): String {
        val parent = normalize(collectionPath)
        val entries = StringBuilder()
        entries.append(
            "<ns0:response><ns0:href>$collectionPath</ns0:href><ns0:propstat>" +
                "<ns0:prop><ns0:displayname>self</ns0:displayname></ns0:prop>" +
                "</ns0:propstat></ns0:response>",
        )
        files.keys
            .filter { normalize(it.substringBeforeLast('/')) == parent }
            .sorted()
            .forEach { filePath ->
                val name = filePath.substringAfterLast('/')
                entries.append(
                    "<ns0:response><ns0:href>$filePath</ns0:href><ns0:propstat>" +
                        "<ns0:prop><ns0:displayname>$name</ns0:displayname></ns0:prop>" +
                        "</ns0:propstat></ns0:response>",
                )
            }
        return "<?xml version=\"1.0\" encoding=\"utf-8\" ?>" +
            "<ns0:multistatus xmlns:ns0=\"DAV:\">$entries</ns0:multistatus>"
    }

    /** 真实服务器对带/不带末尾斜杠的集合 URI 一视同仁，这里同样规范化。 */
    private fun normalize(path: String): String = path.trimEnd('/').ifEmpty { "/" }

    private fun respond(code: Int, body: String, chunked: Boolean = false): String {
        val head = "HTTP/1.1 $code ${reasonOf(code)}\r\n"
        return if (chunked) {
            val bytes = body.toByteArray(StandardCharsets.UTF_8)
            "$head" +
                "Transfer-Encoding: chunked\r\nConnection: close\r\n\r\n" +
                Integer.toHexString(bytes.size) + "\r\n" +
                String(bytes, StandardCharsets.UTF_8) + "\r\n" +
                "0\r\n\r\n"
        } else {
            val bytes = body.toByteArray(StandardCharsets.UTF_8)
            "$head" +
                "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n" +
                String(bytes, StandardCharsets.UTF_8)
        }
    }

    private fun reasonOf(code: Int): String = when (code) {
        200 -> "OK"
        201 -> "Created"
        204 -> "No Content"
        207 -> "Multi-Status"
        401 -> "Unauthorized"
        403 -> "Forbidden"
        404 -> "Not Found"
        405 -> "Method Not Allowed"
        409 -> "Conflict"
        else -> "Unknown"
    }

    private fun readRequest(input: InputStream): String {
        val head = StringBuilder()
        while (true) {
            val line = readLine(input) ?: break
            head.append(line).append("\r\n")
            if (line.isEmpty()) break
        }
        val headText = head.toString()
        val length = Regex("(?i)content-length:\\s*(\\d+)")
            .find(headText)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val body = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = input.read(body, read, length - read)
            if (n < 0) break
            read += n
        }
        return headText + String(body, StandardCharsets.UTF_8)
    }

    private fun readLine(input: InputStream): String? {
        val out = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) return if (out.size() == 0) null else decodeLine(out)
            if (b == 10) return decodeLine(out)
            out.write(b)
        }
    }

    private fun decodeLine(out: ByteArrayOutputStream): String =
        out.toString(StandardCharsets.UTF_8.name()).trimEnd('\r')
}
