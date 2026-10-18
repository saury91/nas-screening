package com.projectionroom.tv

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import javax.net.ssl.SSLSocketFactory

/**
 * 极简 HTTP/1.1 客户端，只给本地服务的反代用。
 *
 * 为什么不用 HttpURLConnection：
 * 它会悄悄加 Accept-Encoding、透明解压、复用连接池、自动重定向……
 * 在"壳里再代理一层"这种场景下，这些行为会让上游收到半截请求，
 * 服务端抛异常（实测表现为 NAS 返回 500 "cannot unpack ..."），
 * 排查成本远高于自己写 60 行。
 *
 * 只支持：http / https、GET/POST、Content-Length 或 chunked 响应。
 */
object HttpMini {

    data class Response(val code: Int, val ctype: String, val body: ByteArray)

    /** 直接按绝对地址请求，可带 Referer（媒体代理用） */
    fun fetch(url: String, referer: String?, userAgent: String?, timeoutMs: Int = 15_000): Response {
        val u = URI(url)
        val extra = StringBuilder()
        if (!referer.isNullOrBlank()) extra.append("Referer: $referer\r\n")
        if (!userAgent.isNullOrBlank()) extra.append("User-Agent: $userAgent\r\n")
        return request(u.scheme + "://" + u.host + (if (u.port > 0) ":" + u.port else ""),
            "GET", u.rawPath ?: "/", u.rawQuery ?: "",
            emptyMap(), ByteArray(0), timeoutMs, extra.toString())
    }

    fun request(
        base: String,
        method: String,
        path: String,
        query: String,
        headers: Map<String, String>,
        body: ByteArray,
        timeoutMs: Int = 12_000,
        extraHeaders: String = "",
    ): Response {
        val uri = URI(base)
        val https = uri.scheme.equals("https", true)
        val host = uri.host ?: throw IllegalArgumentException("地址不合法")
        val port = if (uri.port > 0) uri.port else if (https) 443 else 80

        val socket: Socket = if (https) {
            val raw = Socket()
            raw.connect(InetSocketAddress(host, port), 2500)
            (SSLSocketFactory.getDefault() as SSLSocketFactory)
                .createSocket(raw, host, port, true)
        } else {
            Socket().apply { connect(InetSocketAddress(host, port), 2500) }
        }
        socket.soTimeout = timeoutMs

        val target = path + (if (query.isNotEmpty()) "?$query" else "")
        val sb = StringBuilder()
        sb.append("$method $target HTTP/1.1\r\n")
        sb.append("Host: $host")
        if (port != 80 && port != 443) sb.append(":$port")
        sb.append("\r\n")
        sb.append("Connection: close\r\n")     // 一次一条连接，绝不复用
        sb.append("Accept: */*\r\n")
        if (extraHeaders.isNotEmpty()) sb.append(extraHeaders)
        headers["cookie"]?.let { sb.append("Cookie: $it\r\n") }
        headers["x-token"]?.let { sb.append("X-Token: $it\r\n") }
        headers["range"]?.let { sb.append("Range: $it\r\n") }
        if (body.isNotEmpty()) {
            sb.append("Content-Type: ${headers["content-type"] ?: "application/json"}\r\n")
            sb.append("Content-Length: ${body.size}\r\n")
        }
        sb.append("\r\n")

        socket.use { s ->
            val out: OutputStream = s.getOutputStream()
            out.write(sb.toString().toByteArray(Charsets.UTF_8))
            if (body.isNotEmpty()) out.write(body)
            out.flush()

            val input = BufferedInputStream(s.getInputStream())
            val statusLine = readLine(input) ?: throw IllegalStateException("上游没响应")
            val code = statusLine.split(" ").getOrNull(1)?.toIntOrNull() ?: 502

            var ctype = "application/octet-stream"
            var contentLength = -1L
            var chunked = false
            while (true) {
                val line = readLine(input) ?: break
                if (line.isEmpty()) break
                val i = line.indexOf(':')
                if (i <= 0) continue
                val k = line.substring(0, i).trim().lowercase()
                val v = line.substring(i + 1).trim()
                when (k) {
                    "content-type" -> ctype = v
                    "content-length" -> contentLength = v.toLongOrNull() ?: -1
                    "transfer-encoding" -> chunked = v.contains("chunked", true)
                }
            }

            val data = when {
                chunked -> readChunked(input)
                contentLength >= 0 -> readN(input, contentLength.toInt())
                else -> input.readBytes()
            }
            return Response(code, ctype, data)
        }
    }

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b < 0) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(b.toChar())
        }
    }

    private fun readN(input: InputStream, n: Int): ByteArray {
        val out = ByteArrayOutputStream(n.coerceAtLeast(16))
        val buf = ByteArray(16 * 1024)
        var left = n
        while (left > 0) {
            val r = input.read(buf, 0, minOf(buf.size, left))
            if (r <= 0) break
            out.write(buf, 0, r)
            left -= r
        }
        return out.toByteArray()
    }

    private fun readChunked(input: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        while (true) {
            val sizeLine = readLine(input) ?: break
            val size = sizeLine.substringBefore(';').trim().toIntOrNull(16) ?: break
            if (size == 0) {
                readLine(input)   // 尾部空行
                break
            }
            out.write(readN(input, size))
            readLine(input)       // 每块后面的 CRLF
        }
        return out.toByteArray()
    }
}
