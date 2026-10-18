package com.projectionroom.tv

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.util.concurrent.Executors

/**
 * 壳内置的本地 HTTP 服务。
 *
 * 为什么不是直接加载 NAS 页面：NAS 一关机就白屏。这里把前端放在本地，
 * WebView 打开 http://127.0.0.1:<port>/，同源策略、Cookie、localStorage 全都正常，
 * 而且 127.0.0.1 属于安全上下文，以后想上 Service Worker 也不用再改架构。
 *
 * 三个职责：
 *   1. /            → 本地前端（filesDir/www 优先，回落 assets/www）
 *   2. /api/...     → 反向代理到 NAS（Cookie/X-Token/Range 原样透传）
 *   3. /__local/... → 壳自己的状态接口（在线/离线、版本）
 */
class LocalServer(
    private val ctx: Context,
    private val nasUrl: () -> String,
) {
    companion object {
        private const val TAG = "ProomLocal"
        private const val ASSET_ROOT = "www"
    }

    @Volatile private var server: ServerSocket? = null

    // 简单计数：用来判断页面到底有没有把请求打到本地服务
    private val hits = java.util.concurrent.atomic.AtomicInteger(0)
    private val apiHits = java.util.concurrent.atomic.AtomicInteger(0)
    private val notFounds = java.util.concurrent.atomic.AtomicInteger(0)
    private val recent = java.util.Collections.synchronizedList(ArrayList<String>())
    val port: Int get() = server?.localPort ?: 0

    private val pool = Executors.newFixedThreadPool(8)

    /** 只绑回环地址，不对局域网暴露 */
    fun start(): Int {
        if (server != null) return port
        // 固定端口：只绑回环，冲突概率低；固定下来方便 adb forward 调试
        val port = try {
            ServerSocket(8891, 50, InetAddress.getByName("127.0.0.1"))
        } catch (e: Exception) {
            ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        }
        val ss = port
        server = ss
        Thread({
            while (!ss.isClosed) {
                val client = try { ss.accept() } catch (_: Exception) { break }
                pool.execute { handle(client) }
            }
        }, "local-server").apply { isDaemon = true }.start()
        return ss.localPort
    }

    fun stop() {
        try { server?.close() } catch (_: Exception) {}
        server = null
    }

    // ── 请求处理 ──────────────────────────────────────────────

    private fun handle(client: Socket) {
        try {
            client.use { sock ->
                val input = BufferedInputStream(sock.getInputStream())
                val head = readHead(input) ?: return
                val lines = head.split("\r\n")
                val parts = lines.firstOrNull()?.split(" ") ?: return
                if (parts.size < 2) return
                val method = parts[0]
                val target = parts[1]
                val path = target.substringBefore('?')
                val query = target.substringAfter('?', "")

                var contentLength = 0
                val headers = mutableMapOf<String, String>()
                for (l in lines.drop(1)) {
                    val i = l.indexOf(':')
                    if (i <= 0) continue
                    val k = l.substring(0, i).trim().lowercase()
                    val v = l.substring(i + 1).trim()
                    headers[k] = v
                    if (k == "content-length") contentLength = v.toIntOrNull() ?: 0
                }

                hits.incrementAndGet()
                if (path.startsWith("/api/")) apiHits.incrementAndGet()
                synchronized(recent) {
                    recent.add("$method $path")
                    // 不记录请求头：里面可能带 Cookie/Token
                    if (recent.size > 20) recent.removeAt(0)
                }
                val body: ByteArray = if (contentLength > 0) {
                    val buf = ByteArray(contentLength)
                    var read = 0
                    while (read < contentLength) {
                        val n = input.read(buf, read, contentLength - read)
                        if (n <= 0) break
                        read += n
                    }
                    buf
                } else ByteArray(0)

                when {
                    path == "/__local/status" -> json(sock, statusJson())
                    path == "/__local/log" -> json(sock,
                        recent.joinToString(",", "[", "]") { "\"$it\"" })
                    path == "/__local/stats" -> json(sock,
                        """{"hits":${hits.get()},"api":${apiHits.get()},"404":${notFounds.get()}}""")
                    path.startsWith("/api/") -> proxy(sock, method, path, query, headers, body)
                    else -> serveStatic(sock, path)
                }
                // 写完立刻 close 可能触发 RST，把还在飞的数据掐掉；
                // 先半关并给内核一点时间，浏览器才拿得到完整响应
                try {
                    sock.shutdownOutput()
                    Thread.sleep(15)
                } catch (_: Exception) {}
            }
        } catch (e: Exception) {
            Log.w(TAG, "请求失败: ${e.message}")
        }
    }

    private fun readHead(input: InputStream): String? {
        val sb = StringBuilder()
        var last4 = 0
        while (true) {
            val b = input.read()
            if (b < 0) return if (sb.isEmpty()) null else sb.toString()
            sb.append(b.toChar())
            last4 = (last4 shl 8) or b
            if (last4 == 0x0d0a0d0a) return sb.toString()   // \r\n\r\n
            if (sb.length > 32_768) return null
        }
    }

    private fun statusJson(): String {
        val nas = nasUrl().trimEnd('/')
        return """{"ok":true,"nas":"$nas","version":"${BuildConfig.VERSION_NAME}",""" +
            """"local":true}"""
    }

    // ── 静态文件 ──────────────────────────────────────────────

    private fun serveStatic(sock: Socket, path: String) {
        val rel = when {
            path == "/" || path.isEmpty() -> "index.html"
            // 页面里的资源是 /static/xxx；打包时 static/ 的内容直接放在 assets/www 下
            path.startsWith("/static/") -> path.removePrefix("/static/")
            else -> path.removePrefix("/")
        }
        // 防目录穿越
        if (rel.contains("..")) { notFound(sock); return }

        val file = File(File(ctx.filesDir, ASSET_ROOT), rel)
        val bytes: ByteArray? = if (file.isFile) file.readBytes() else assetOrNull(rel)
        if (bytes == null) {
            notFounds.incrementAndGet()
            notFound(sock); return
        }

        val ctype = mimeOf(rel)
        val out = sock.getOutputStream()
        // 前端代码不缓存：升级后刷新即生效（与 NAS 端策略一致）
        val cache = if (rel.startsWith("app/") || rel.endsWith(".html"))
            "no-cache" else "public, max-age=86400"
        writeHead(out, 200, ctype, bytes.size, cache)
        if (out !is java.io.ByteArrayOutputStream) out.write(bytes)
        out.flush()
    }

    private fun assetOrNull(rel: String): ByteArray? = try {
        ctx.assets.open("$ASSET_ROOT/$rel").use { it.readBytes() }
    } catch (e: Exception) {
        null
    }

    // ── 反代到 NAS ────────────────────────────────────────────

    private fun proxy(
        sock: Socket,
        method: String,
        path: String,
        query: String,
        headers: Map<String, String>,
        body: ByteArray,
    ) {
        val base = nasUrl().trimEnd('/')
        if (base.isBlank()) {
            json(sock, """{"ok":false,"offline":true,"msg":"还没设置 NAS 地址"}""", 503)
            return
        }
        val resp = try {
            HttpMini.request(base, method, path, query, headers, body)
        } catch (e: Exception) {
            json(sock, """{"ok":false,"offline":true,"msg":"连不上 NAS"}""", 503)
            return
        }

        val out = sock.getOutputStream()
        val ctype = if (resp.ctype.isBlank() || resp.code >= 400) {
            if (path.startsWith("/api/")) "application/json; charset=utf-8" else resp.ctype
        } else resp.ctype

        val head = "HTTP/1.1 ${resp.code} OK\r\n" +
            "Content-Type: $ctype\r\n" +
            "Content-Length: ${resp.body.size}\r\n" +
            "Cache-Control: no-store\r\n" +
            "Connection: close\r\n\r\n"
        out.write(head.toByteArray())
        out.write(resp.body)
        out.flush()

        synchronized(recent) {
            val snippet = if (resp.code >= 400)
                String(resp.body, Charsets.UTF_8).take(120).replace("\n", " ") else ""
            recent.add("-> $path ${resp.code} ${resp.body.size}b $snippet")
            if (recent.size > 30) recent.removeAt(0)
        }
    }

    // ── 工具 ──────────────────────────────────────────────────

    private fun notFound(sock: Socket) {
        val body = """{"error":"not found"}""".toByteArray()
        writeHead(sock.getOutputStream(), 404, "application/json", body.size, "no-store")
        sock.getOutputStream().write(body)
    }

    private fun json(sock: Socket, s: String, code: Int = 200) {
        val body = s.toByteArray()
        val out = sock.getOutputStream()
        writeHead(out, code, "application/json; charset=utf-8", body.size, "no-store")
        out.write(body); out.flush()
    }

    private fun writeHead(
        out: OutputStream, code: Int, ctype: String, len: Int, cache: String,
    ) {
        val head = "HTTP/1.1 $code OK\r\nContent-Type: $ctype\r\n" +
            "Content-Length: $len\r\nCache-Control: $cache\r\nConnection: close\r\n\r\n"
        out.write(head.toByteArray())
    }

    private fun mimeOf(path: String): String = when {
        path.endsWith(".html") -> "text/html; charset=utf-8"
        path.endsWith(".css") -> "text/css; charset=utf-8"
        path.endsWith(".js") || path.endsWith(".mjs") -> "text/javascript; charset=utf-8"
        path.endsWith(".json") -> "application/json; charset=utf-8"
        path.endsWith(".svg") -> "image/svg+xml"
        path.endsWith(".png") -> "image/png"
        path.endsWith(".jpg") || path.endsWith(".jpeg") -> "image/jpeg"
        path.endsWith(".webp") -> "image/webp"
        path.endsWith(".woff2") -> "font/woff2"
        path.endsWith(".woff") -> "font/woff"
        path.endsWith(".ttf") -> "font/ttf"
        path.endsWith(".m3u8") -> "application/vnd.apple.mpegurl"
        else -> "application/octet-stream"
    }
}
