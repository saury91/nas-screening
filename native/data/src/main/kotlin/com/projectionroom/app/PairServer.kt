package com.projectionroom.app

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket

/**
 * 电视端的配对接收器：一个只干一件事的极小 HTTP 服务。
 *
 * 为什么自己写而不引框架：整个服务只有「收一个 POST，验一个 token，回一句话」，
 * 引 nanohttpd 那种要几百 KB。这里用 ServerSocket 手撸，规格也写得死：
 *
 * ```
 * POST /pair?token=1234  HTTP/1.1
 * Content-Length: N
 *
 * {配置 JSON}
 * ```
 *
 * 只监听局域网；收到一次合法配置就回调 [onPayload]，由壳负责落盘并停掉服务。
 * token 只在配对页打开时有效，页面一关就作废 —— 防的是「同一个 Wi-Fi 下
 * 别人随手往电视里写配置」。
 */
class PairServer(
    private val token: String,
    /** 收到正文：返回给手机的提示语；抛异常表示这份配置不可用 */
    private val onPayload: suspend (String) -> String,
) {

    private var server: ServerSocket? = null
    private var job: Job? = null

    /** 实际监听的端口（0 = 让系统分配，测试时用） */
    var port: Int = 0
        private set

    fun start(scope: CoroutineScope, preferredPort: Int = Pair.PORT): Boolean {
        if (job != null) return true
        val ss = runCatching { ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(preferredPort)) } }
            .getOrNull() ?: runCatching {
                // 8898 被占了就随便给一个，二维码里的端口跟着变，手机照样能连
                ServerSocket(0)
            }.getOrNull() ?: return false
        server = ss
        port = ss.localPort
        job = scope.launch(Dispatchers.IO) {
            while (isActive) {
                val client = runCatching { ss.accept() }.getOrNull() ?: break
                runCatching { handle(client.getInputStream(), client.getOutputStream()) }
                runCatching { client.close() }
            }
        }
        return true
    }

    fun stop() {
        job?.cancel()
        job = null
        runCatching { server?.close() }
        server = null
    }

    private suspend fun handle(input: InputStream, output: OutputStream) {
        val reader = BufferedInputStream(input)
        val head = readHead(reader) ?: return respond(output, 400, "bad request")
        val lines = head.split("\r\n")
        val requestLine = lines.firstOrNull().orEmpty()
        val parts = requestLine.split(' ')
        if (parts.size < 2) return respond(output, 400, "bad request")
        val method = parts[0]
        val path = parts[1]
        if (method != "POST" || !path.startsWith("/pair")) {
            return respond(output, 404, "not found")
        }
        val query = path.substringAfter('?', "")
        val got = query.split('&')
            .firstOrNull { it.startsWith("token=") }
            ?.substringAfter('=')
            .orEmpty()
        if (got != token) return respond(output, 403, "配对码不对")

        val len = lines.firstOrNull { it.startsWith("Content-Length:", ignoreCase = true) }
            ?.substringAfter(':')?.trim()?.toIntOrNull() ?: 0
        if (len <= 0 || len > 8 * 1024 * 1024) return respond(output, 413, "内容为空或过大")

        val body = ByteArray(len)
        var read = 0
        while (read < len) {
            val n = reader.read(body, read, len - read)
            if (n <= 0) break
            read += n
        }
        if (read <= 0) return respond(output, 400, "读不到内容")

        val text = String(body, 0, read, Charsets.UTF_8)
        val msg = runCatching { onPayload(text) }.getOrElse { it.message ?: "配置不可用" }
        respond(output, 200, msg)
    }

    /** 读到空行为止（HTTP 头）。只取头部，不碰正文 */
    private fun readHead(input: InputStream): String? {
        val sb = StringBuilder()
        while (sb.length < 16 * 1024) {
            val c = input.read()
            if (c < 0) return if (sb.isEmpty()) null else sb.toString()
            sb.append(c.toChar())
            if (sb.endsWith("\r\n\r\n")) return sb.toString().removeSuffix("\r\n\r\n")
        }
        return sb.toString()
    }

    private fun respond(out: OutputStream, code: Int, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val head = "HTTP/1.1 $code OK\r\n" +
            "Content-Type: text/plain; charset=utf-8\r\n" +
            "Content-Length: ${bytes.size}\r\n" +
            "Connection: close\r\n\r\n"
        runCatching {
            out.write(head.toByteArray(Charsets.UTF_8))
            out.write(bytes)
            out.flush()
        }
    }
}
