package com.local.notificationfilter.core.backup

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.Base64
import javax.net.ssl.SSLSocketFactory

/**
 * 极简原始 HTTP/1.1 客户端（V11.1 新增）。
 *
 * **为什么不用 `HttpURLConnection`**（踩过的坑，改这里前先读）：
 * Android 的 `HttpURLConnection` 和桌面 JDK 一样，把请求方法写死成
 * `GET / POST / HEAD / OPTIONS / PUT / DELETE / TRACE` 七个，其它方法直接抛
 * `ProtocolException: Expected one of [...] but was PROPFIND`。
 * WebDAV 的 PROPFIND / MKCOL **发不出去** —— V11.0 的整个 WebDAV 通道
 * （探测 / 列表 / 上传）在真机上一次都没通过，报错就是这个异常。
 * 原生 Socket 手写请求没有方法白名单，也没有「受限请求头」名单
 * （JDK 还会静默吞掉 Depth 之类的头），行为完全可预期。
 *
 * 刻意保持极简，只覆盖本项目用到的场景：
 * - 任意方法 + 任意请求头 + 可选的字符串 body（UTF-8）；
 * - `Content-Length` 明写（不用 chunked 请求，避免部分代理挑刺）；
 * - 响应解析支持 `Content-Length`、`Transfer-Encoding: chunked`、
 *   以及「无长度字段就读到 EOF」（因为我们固定发 `Connection: close`）；
 * - 自动跳过 `1xx` 中间响应（如 100 Continue）；
 * - 301/302/303/307/308 跟随 Location 重定向（最多 [maxRedirects] 次），
 *   303/302 按惯例降级为 GET。
 *
 * **线程**：纯阻塞调用，不内部切线程。调用方必须自己放 IO 线程
 * （本项目所有调用点都包了 `withContext(Dispatchers.IO)`）。
 */
class RawHttp(
    private val connectTimeoutMs: Int = DEFAULT_CONNECT_TIMEOUT_MS,
    private val readTimeoutMs: Int = DEFAULT_READ_TIMEOUT_MS,
    private val maxRedirects: Int = 3,
) {

    data class Response(
        val statusCode: Int,
        val reason: String,
        val headers: Map<String, String>,
        val body: String,
    ) {
        val ok: Boolean get() = statusCode in 200..299

        /** 按小写名取响应头；不存在返回 null。 */
        fun header(name: String): String? = headers[name.lowercase()]
    }

    /**
     * 发一次请求（含重定向跟随）。
     *
     * @param method 任意 HTTP 方法（PROPFIND / MKCOL / PUT / GET / DELETE …）
     * @param url    完整 URL，支持 http / https
     * @param headers 附加请求头；`Host` / `Content-Length` / `Connection` /
     *   `Accept-Encoding` 由本类管理，调用方传了会被忽略
     * @param body    请求体文本（UTF-8）；null = 不带请求体
     */
    fun execute(
        method: String,
        url: String,
        headers: Map<String, String> = emptyMap(),
        body: String? = null,
    ): Response {
        var currentMethod = method
        var currentUrl = url
        var currentBody = body
        var hops = 0
        while (true) {
            val response = executeOnce(currentMethod, currentUrl, headers, currentBody)
            val location = response.header("Location")
            val redirect = response.statusCode in 301..308 &&
                response.statusCode != 304 &&
                !location.isNullOrBlank()
            if (!redirect || hops >= maxRedirects) return response
            hops++
            // 303 一律改 GET；302 按主流实现也降级为 GET（307/308 才保持原方法）
            if (response.statusCode == 303 ||
                (response.statusCode == 302 && !currentMethod.equals("GET", ignoreCase = true))
            ) {
                currentMethod = "GET"
                currentBody = null
            }
            currentUrl = runCatching { URL(URL(currentUrl), location).toString() }
                .getOrDefault(location)
        }
    }

    // ==================== 单次请求 ====================

    private fun executeOnce(
        method: String,
        urlString: String,
        headers: Map<String, String>,
        body: String?,
    ): Response {
        val url = runCatching { URL(urlString) }
            .getOrElse { throw IOException("地址无法解析：$urlString") }
        if (!url.protocol.equals("https", true) && !url.protocol.equals("http", true)) {
            throw IOException("仅支持 http / https 地址（当前：${url.protocol}）")
        }
        val port = if (url.port != -1) url.port else defaultPort(url.protocol)
        val path = url.file.ifEmpty { "/" }

        val socket = openSocket(url, port)
        try {
            socket.soTimeout = readTimeoutMs
            val head = buildRequestHead(method, url.host, port, path, headers, body)
            val os: OutputStream = socket.getOutputStream()
            os.write(head)
            if (body != null) os.write(body.toByteArray(StandardCharsets.UTF_8))
            os.flush()
            return parseResponse(BufferedInputStream(socket.getInputStream()))
        } finally {
            runCatching { socket.close() }
        }
    }

    private fun openSocket(url: URL, port: Int): Socket {
        val socket: Socket = if (url.protocol.equals("https", true)) {
            SSL_SOCKET_FACTORY.createSocket()
        } else {
            Socket()
        }
        socket.connect(InetSocketAddress(url.host, port), connectTimeoutMs)
        return socket
    }

    private fun defaultPort(protocol: String): Int =
        if (protocol.equals("https", true)) 443 else 80

    /**
     * 拼请求头字节。管理型头（Host / Content-Length / Connection / Accept-Encoding）
     * 以本类为准，调用方传同名的直接丢弃；其余头原样透传（值内的 CR/LF 剥掉防注入）。
     */
    private fun buildRequestHead(
        method: String,
        host: String,
        port: Int,
        path: String,
        headers: Map<String, String>,
        body: String?,
    ): ByteArray {
        val sb = StringBuilder()
        sb.append(method).append(' ').append(path).append(" HTTP/1.1\r\n")
        val hostValue = if (port == 80 || port == 443) host else "$host:$port"
        sb.append("Host: ").append(hostValue).append("\r\n")
        headers.forEach { (name, value) ->
            if (name.lowercase() !in MANAGED_HEADERS) {
                val safe = value.replace("\r", "").replace("\n", "")
                sb.append(name).append(": ").append(safe).append("\r\n")
            }
        }
        val bodyBytes = body?.toByteArray(StandardCharsets.UTF_8)
        if (bodyBytes != null) {
            // 必须是**字节**长度：含中文时字符数会小于字节数
            sb.append("Content-Length: ").append(bodyBytes.size).append("\r\n")
        }
        // 固定短连接：服务端发完即关，响应体「读到 EOF」才有定义
        sb.append("Connection: close\r\n")
        sb.append("\r\n")
        return sb.toString().toByteArray(StandardCharsets.UTF_8)
    }

    // ==================== 响应解析 ====================

    private fun parseResponse(input: InputStream): Response {
        while (true) {
            val statusLine = readLine(input) ?: throw IOException("服务器没有返回任何数据")
            if (!statusLine.startsWith("HTTP/")) {
                throw IOException("响应格式不正确（不是 HTTP）：${statusLine.take(40)}")
            }
            val parts = statusLine.split(' ', limit = 3)
            val code = parts.getOrNull(1)?.trim()?.toIntOrNull()
                ?: throw IOException("无法解析响应状态行：$statusLine")
            val reason = parts.getOrNull(2)?.trim().orEmpty()

            val headers = LinkedHashMap<String, String>()
            while (true) {
                val line = readLine(input) ?: break
                if (line.isEmpty()) break
                val idx = line.indexOf(':')
                if (idx <= 0) continue
                headers[line.substring(0, idx).trim().lowercase()] = line.substring(idx + 1).trim()
            }

            // 100 Continue 之类的中间响应：没有实体，继续读真正的响应
            if (code in 100..199) continue

            val bodyBytes = readBody(input, code, headers)
            return Response(
                statusCode = code,
                reason = reason,
                headers = headers,
                body = String(bodyBytes, StandardCharsets.UTF_8),
            )
        }
    }

    private fun readBody(input: InputStream, code: Int, headers: Map<String, String>): ByteArray =
        when {
            code == 204 || code == 304 -> ByteArray(0)
            headers["transfer-encoding"]?.contains("chunked", true) == true -> readChunked(input)
            else -> {
                val length = headers["content-length"]?.trim()?.toIntOrNull()
                if (length != null) readExactly(input, length) else readUntilEof(input)
            }
        }

    /** 逐行读（以 \n 结尾，剥掉尾部 \r）；EOF 且无数据返回 null。 */
    private fun readLine(input: InputStream): String? {
        val baos = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) return if (baos.size() == 0) null else decodeLine(baos)
            if (b == 10) return decodeLine(baos) // '\n'
            baos.write(b)
        }
    }

    private fun decodeLine(baos: ByteArrayOutputStream): String =
        baos.toString(StandardCharsets.UTF_8.name()).trimEnd('\r')

    private fun readExactly(input: InputStream, length: Int): ByteArray {
        val buffer = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = input.read(buffer, read, length - read)
            if (n < 0) throw IOException("连接被提前关闭（已读 $read / $length 字节）")
            read += n
        }
        return buffer
    }

    private fun readUntilEof(input: InputStream): ByteArray {
        val baos = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            baos.write(buffer, 0, n)
        }
        return baos.toByteArray()
    }

    private fun readChunked(input: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        while (true) {
            val sizeLine = readLine(input) ?: break
            val size = sizeLine.substringBefore(';').trim().toIntOrNull(16)
                ?: throw IOException("无法解析 chunked 长度：$sizeLine")
            if (size == 0) {
                // 读掉尾部头直到空行
                while (true) {
                    val trailer = readLine(input) ?: break
                    if (trailer.isEmpty()) break
                }
                break
            }
            out.write(readExactly(input, size))
            readLine(input) // 每个 chunk 数据后的 CRLF
        }
        return out.toByteArray()
    }

    companion object {
        const val DEFAULT_CONNECT_TIMEOUT_MS = 15_000
        const val DEFAULT_READ_TIMEOUT_MS = 15_000

        private val MANAGED_HEADERS = setOf("host", "content-length", "connection", "accept-encoding")

        // getDefault() 的静态返回类型是 SocketFactory，实际一定是 SSLSocketFactory
        private val SSL_SOCKET_FACTORY: SSLSocketFactory =
            SSLSocketFactory.getDefault() as SSLSocketFactory

        /** Basic Auth 头值（`Basic <base64(账号:密码)>`）。 */
        fun basicAuthValue(account: String, password: String): String {
            val raw = "$account:$password".toByteArray(StandardCharsets.UTF_8)
            return "Basic " + Base64.getEncoder().encodeToString(raw)
        }
    }
}
