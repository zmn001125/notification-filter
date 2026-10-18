package com.local.notificationfilter.core.backup

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.Collections
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [RawHttp] 单测（V11.1）。
 *
 * 为什么必须测：V11.0 的 WebDAV 整个废掉，就是因为「请求方法发不出去」这种
 * **协议层错误在编译期完全看不出来**。这里用一个进程内的假 HTTP 服务器，
 * 直接校验**线上字节**（方法行 / Host / 自定义头 / Content-Length / 请求体）
 * 与响应解析（Content-Length、chunked、1xx 跳过、重定向）。
 *
 * 假服务器每接受一个连接就按脚本回应一次，请求原文被捕获供断言。
 */
class RawHttpTest {

    private lateinit var server: FakeHttpServer

    @Before
    fun setUp() {
        server = FakeHttpServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.stop()
    }

    private val baseUrl: String get() = "http://127.0.0.1:${server.port}/dav/sub"

    // ==================== 请求线格式 ====================

    @Test
    fun `PROPFIND 原样发出不携带方法白名单`() {
        server.respond(
            "HTTP/1.1 207 Multi-Status\r\n" +
                "Content-Length: 2\r\n" +
                "Connection: close\r\n\r\nok",
        )
        val response = RawHttp().execute(
            method = "PROPFIND",
            url = baseUrl,
            headers = mapOf("Depth" to "1", "Content-Type" to "application/xml; charset=utf-8"),
            body = "<propfind/>",
        )
        assertEquals(207, response.statusCode)
        assertTrue(response.ok)
        assertEquals("ok", response.body)

        val request = server.firstRequest()
        assertTrue("方法行应原样包含 PROPFIND：${request.lines().first()}", request.startsWith("PROPFIND /dav/sub HTTP/1.1\r\n"))
        assertTrue("Host 头必须存在：$request", request.contains("\r\nHost: 127.0.0.1:${server.port}\r\n"))
        assertTrue("Depth 自定义头必须透传：$request", request.contains("\r\nDepth: 1\r\n"))
        assertTrue("Content-Type 必须透传：$request", request.contains("Content-Type: application/xml; charset=utf-8"))
        assertTrue("Connection: close 必须发送：$request", request.contains("\r\nConnection: close\r\n"))
        assertTrue("Content-Length 必须按字节数：$request", request.contains("\r\nContent-Length: 11\r\n"))
        assertTrue("请求体必须发出：$request", request.endsWith("<propfind/>"))
    }

    @Test
    fun `MKCOL 无请求体时不发 Content-Length`() {
        server.respond("HTTP/1.1 201 Created\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
        val response = RawHttp().execute("MKCOL", baseUrl, headers = mapOf("Authorization" to "Basic x"))
        assertEquals(201, response.statusCode)
        val request = server.firstRequest()
        assertTrue(request.startsWith("MKCOL /dav/sub HTTP/1.1\r\n"))
        assertFalse("无 body 就不该有 Content-Length：$request", request.contains("Content-Length"))
        assertTrue(request.contains("\r\nAuthorization: Basic x\r\n"))
    }

    @Test
    fun `中文请求体按 UTF-8 字节数计 Content-Length`() {
        server.respond("HTTP/1.1 200 OK\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
        RawHttp().execute("PUT", baseUrl, body = "中文")
        // "中文" 2 字符 × 3 字节 = 6；若按字符数会写成 2，服务端会读不完整
        assertTrue(server.firstRequest().contains("\r\nContent-Length: 6\r\n"))
    }

    @Test
    fun `管理型头以本类为准 调用方传 Host 也会被替换`() {
        server.respond("HTTP/1.1 200 OK\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
        RawHttp().execute("GET", baseUrl, headers = mapOf("Host" to "evil.example.com"))
        val request = server.firstRequest()
        assertTrue("Host 必须由本类按真实连接生成：$request", request.contains("\r\nHost: 127.0.0.1:${server.port}\r\n"))
        assertFalse(request.contains("evil.example.com"))
    }

    // ==================== 响应解析 ====================

    @Test
    fun `按 Content-Length 读响应体`() {
        server.respond("HTTP/1.1 200 OK\r\nContent-Length: 5\r\nConnection: close\r\n\r\nhello")
        val response = RawHttp().execute("GET", baseUrl)
        assertEquals("hello", response.body)
        assertEquals("OK", response.reason)
        assertEquals("close", response.header("Connection"))
    }

    @Test
    fun `解析 chunked 响应体`() {
        server.respond(
            "HTTP/1.1 207 Multi-Status\r\n" +
                "Transfer-Encoding: chunked\r\n" +
                "Connection: close\r\n\r\n" +
                "5\r\nhello\r\n" +
                "6\r\n world\r\n" +
                "0\r\n\r\n",
        )
        val response = RawHttp().execute("PROPFIND", baseUrl)
        assertEquals(207, response.statusCode)
        assertEquals("hello world", response.body)
    }

    @Test
    fun `跳过 100 Continue 中间响应`() {
        server.respond(
            "HTTP/1.1 100 Continue\r\n\r\n" +
                "HTTP/1.1 201 Created\r\nContent-Length: 4\r\nConnection: close\r\n\r\ndone",
        )
        val response = RawHttp().execute("PUT", baseUrl, body = "x")
        assertEquals(201, response.statusCode)
        assertEquals("done", response.body)
    }

    @Test
    fun `无长度字段时读到 EOF`() {
        server.respond("HTTP/1.1 200 OK\r\nConnection: close\r\n\r\nuntil-eof")
        val response = RawHttp().execute("GET", baseUrl)
        assertEquals("until-eof", response.body)
    }

    @Test
    fun `204 没有响应体`() {
        server.respond("HTTP/1.1 204 No Content\r\nConnection: close\r\n\r\n")
        val response = RawHttp().execute("DELETE", baseUrl)
        assertEquals(204, response.statusCode)
        assertEquals("", response.body)
    }

    @Test
    fun `401 状态与响应头原样透出`() {
        server.respond(
            "HTTP/1.1 401 Unauthorized\r\n" +
                "WWW-Authenticate: Basic realm=\"nutstore\"\r\n" +
                "Content-Length: 0\r\nConnection: close\r\n\r\n",
        )
        val response = RawHttp().execute("PROPFIND", baseUrl)
        assertEquals(401, response.statusCode)
        assertFalse(response.ok)
        assertEquals("Basic realm=\"nutstore\"", response.header("www-authenticate"))
    }

    @Test
    fun `跟随 302 重定向并降级为 GET`() {
        server.respond(
            "HTTP/1.1 302 Found\r\nLocation: /dav/other\r\nContent-Length: 0\r\nConnection: close\r\n\r\n",
            "HTTP/1.1 200 OK\r\nContent-Length: 4\r\nConnection: close\r\n\r\nnext",
        )
        val response = RawHttp().execute("POST", baseUrl, body = "ignored")
        assertEquals(200, response.statusCode)
        assertEquals("next", response.body)
        assertEquals(2, server.requestCount())
        val second = server.requestAt(1)
        assertTrue("302 后应降级为 GET：$second", second.startsWith("GET /dav/other HTTP/1.1\r\n"))
        assertFalse("GET 不该带 body：$second", second.contains("Content-Length"))
    }

    @Test
    fun `307 重定向保持原方法与请求体`() {
        server.respond(
            "HTTP/1.1 307 Temporary Redirect\r\nLocation: /dav/other\r\nContent-Length: 0\r\nConnection: close\r\n\r\n",
            "HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nok",
        )
        val response = RawHttp().execute("PUT", baseUrl, body = "abc")
        assertEquals(200, response.statusCode)
        val second = server.requestAt(1)
        assertTrue(second.startsWith("PUT /dav/other HTTP/1.1\r\n"))
        assertTrue(second.endsWith("abc"))
    }

    @Test
    fun `连接被拒时抛出 IOException`() {
        val deadUrl = "http://127.0.0.1:1/dav"
        val error = runCatching { RawHttp(connectTimeoutMs = 1000).execute("GET", deadUrl) }.exceptionOrNull()
        assertTrue("应抛 IOException，实际：$error", error is java.io.IOException)
    }

    @Test
    fun `Basic Auth 头值按 Base64 编码`() {
        val expected = "Basic " + java.util.Base64.getEncoder()
            .encodeToString("user@example.com:p@ss".toByteArray(StandardCharsets.UTF_8))
        assertEquals(expected, RawHttp.basicAuthValue("user@example.com", "p@ss"))
    }
}

/**
 * 进程内假 HTTP 服务器：按脚本依次回应，捕获请求原文。
 *
 * 只支持本项目用到的子集：读请求头 + 按 Content-Length 读体，回应后关闭
 * （对应 RawHttp 固定发送的 `Connection: close`）。
 */
private class FakeHttpServer {

    private val serverSocket = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
    private val requests = Collections.synchronizedList(mutableListOf<String>())
    @Volatile private var responses: List<String> = emptyList()
    @Volatile private var closed = false
    private var worker: Thread? = null

    val port: Int get() = serverSocket.localPort

    fun start() {
        worker = Thread {
            while (!closed) {
                val socket = runCatching { serverSocket.accept() }.getOrNull() ?: break
                Thread { handle(socket) }.start()
            }
        }.apply { isDaemon = true }
        worker?.start()
    }

    fun stop() {
        closed = true
        runCatching { serverSocket.close() }
    }

    /** 预设第 index 个请求的回应；可多次调用追加。 */
    fun respond(vararg responses: String) {
        this.responses = responses.toList()
    }

    fun requestCount(): Int = requests.size

    fun requestAt(index: Int): String = requests[index]

    fun firstRequest(): String = requests.first()

    private fun handle(socket: Socket) {
        socket.use {
            val request = readRequest(it.getInputStream())
            requests += request
            val index = (requests.size - 1).coerceAtMost(responses.size - 1)
            val body = responses.getOrElse(index) { "" }
            it.getOutputStream().apply {
                write(body.toByteArray(StandardCharsets.UTF_8))
                flush()
            }
        }
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

    /** 行末的 \r 必须剥掉，否则「空行 = 头部结束」永远判不出来。 */
    private fun decodeLine(out: ByteArrayOutputStream): String =
        out.toString(StandardCharsets.UTF_8.name()).trimEnd('\r')
}
