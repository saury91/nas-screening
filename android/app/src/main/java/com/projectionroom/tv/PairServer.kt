package com.projectionroom.tv

import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder

/**
 * 电视端的小网页：手机扫码后打开它，把 NAS 地址发过来。
 *
 * 为什么要电视自己起服务：扫码那条链接必须落在电视上，电视才知道该存什么地址。
 * 只做两件事：GET / 给表单，POST /set 收地址。没有任何文件访问，跑完就关。
 */
class PairServer(private val onUrl: (String) -> Unit) {

    @Volatile
    private var server: ServerSocket? = null

    fun start() {
        if (server != null) return
        Thread({
            try {
                val ss = ServerSocket(8898)
                server = ss
                while (!ss.isClosed) {
                    val client = try { ss.accept() } catch (_: Exception) { break }
                    try {
                        handle(client)
                    } catch (_: Exception) {
                    } finally {
                        try { client.close() } catch (_: Exception) {}
                    }
                }
            } catch (_: Exception) {
            }
        }, "pair-server").apply { isDaemon = true }.start()
    }

    fun stop() {
        try { server?.close() } catch (_: Exception) {}
        server = null
    }

    private fun handle(client: Socket) {
        val input = BufferedReader(InputStreamReader(client.getInputStream(), Charsets.UTF_8))
        val requestLine = input.readLine() ?: return
        val parts = requestLine.split(" ")
        if (parts.size < 2) return
        val method = parts[0]
        val path = parts[1].substringBefore("?")

        var length = 0
        while (true) {
            val line = input.readLine() ?: break
            if (line.isEmpty()) break
            if (line.startsWith("Content-Length:", true)) {
                length = line.substringAfter(":").trim().toIntOrNull() ?: 0
            }
        }

        when {
            method == "POST" && path == "/set" -> {
                val buf = CharArray(length.coerceAtMost(4096))
                var read = 0
                while (read < buf.size) {
                    val n = input.read(buf, read, buf.size - read)
                    if (n <= 0) break
                    read += n
                }
                val body = String(buf, 0, read)
                val url = body.split("&")
                    .map { it.split("=", limit = 2) }
                    .firstOrNull { it.size == 2 && it[0] == "url" }
                    ?.let { URLDecoder.decode(it[1], "UTF-8") }
                    ?.trim()
                    ?.trimEnd('/')

                if (url != null && (url.startsWith("http://") || url.startsWith("https://"))) {
                    onUrl(url)
                    respond(client, "已发送", "地址已发给电视，可以回到电视继续了。")
                } else {
                    respond(client, "地址不对", "要填 http:// 开头、带端口的完整地址。")
                }
            }
            else -> respond(client, "把放映室地址发给电视", page(), isForm = true)
        }
    }

    private fun page() = """
        <form method="post" action="/set">
          <label>NAS 上放映室的地址</label>
          <input name="url" inputmode="url" autofocus
                 placeholder="http://192.168.1.10:8899">
          <button type="submit">发给电视</button>
        </form>
    """.trimIndent()

    private fun respond(client: Socket, title: String, body: String, isForm: Boolean = false) {
        val html = """<!doctype html><html lang="zh-CN"><head>
          <meta charset="utf-8">
          <meta name="viewport" content="width=device-width,initial-scale=1">
          <title>$title</title>
          <style>
            body{margin:0;min-height:100vh;display:grid;place-items:center;background:#07080b;
                 color:#f2f3f6;font:16px/1.6 -apple-system,"PingFang SC",sans-serif;padding:24px}
            .card{width:min(420px,100%);background:#14161c;border:1px solid rgba(255,255,255,.12);
                  border-radius:18px;padding:26px}
            h1{margin:0 0 6px;font-size:19px}
            p{margin:0 0 18px;color:#8a919e;font-size:14px}
            label{display:block;font-size:13px;color:#8a919e;margin-bottom:8px}
            input{width:100%;box-sizing:border-box;height:48px;border-radius:12px;
                  border:1px solid rgba(255,255,255,.16);background:rgba(255,255,255,.05);
                  color:#f2f3f6;padding:0 14px;font-size:16px;outline:none}
            input:focus{border-color:#ffcf7a}
            button{width:100%;height:48px;margin-top:16px;border:0;border-radius:12px;
                   background:#ffcf7a;color:#1b1206;font-size:16px;font-weight:600}
          </style></head><body><div class="card">
          <h1>$title</h1><p>$body</p>${if (isForm) page() else ""}
          </div></body></html>"""

        val bytes = html.toByteArray(Charsets.UTF_8)
        val out: OutputStream = client.getOutputStream()
        val head = "HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\n" +
            "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
        out.write(head.toByteArray(Charsets.UTF_8))
        out.write(bytes)
        out.flush()
    }
}
