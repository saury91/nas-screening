package com.projectionroom.tv

import android.util.Log
import java.io.BufferedInputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket

/**
 * 电视端：让同一局域网里的手机**不经过 NAS** 直接把片子投过来。
 *
 * 两个端口：
 *   8892/tcp  收投屏指令（POST /cast，body 是整个 item 的 JSON）
 *   8893/udp  回答手机的发现广播，回自己的 ip:8892
 *
 * 为什么不用 DLNA：DLNA 只能推一条 URL，线路和选集都丢了；
 * 我们两端都是自己的 App，直接把完整 item 传过去最省事。
 */
class CastServer(
    private val onCast: (String) -> Unit,
) {
    private companion object {
        const val TAG = "ProomCastTv"
        const val TCP_PORT = 8892
        const val UDP_PORT = 8893
    }

    @Volatile private var tcp: ServerSocket? = null
    @Volatile private var udp: DatagramSocket? = null

    fun start() {
        startTcp()
        startUdp()
    }

    private fun startTcp() {
        if (tcp != null) return
        Thread({
            try {
                val ss = ServerSocket()
                ss.reuseAddress = true
                ss.bind(InetSocketAddress("0.0.0.0", TCP_PORT))
                tcp = ss
                while (!ss.isClosed) {
                    val c = try { ss.accept() } catch (_: Exception) { break }
                    Thread { handle(c) }.apply { isDaemon = true }.start()
                }
            } catch (e: Exception) {
                Log.w(TAG, "投屏端口启动失败: ${e.message}")
            }
        }, "cast-tcp").apply { isDaemon = true }.start()
    }

    private fun handle(client: Socket) {
        try {
            client.use { s ->
                val input = BufferedInputStream(s.getInputStream())
                val head = readHead(input) ?: return
                val lines = head.split("\r\n")
                val path = lines.firstOrNull()?.split(" ")?.getOrNull(1) ?: "/"
                if (!path.startsWith("/cast")) {
                    respond(s.getOutputStream(), 404, """{"ok":false}""")
                    return
                }
                var len = 0
                for (l in lines.drop(1)) {
                    if (l.startsWith("Content-Length:", true)) {
                        len = l.substringAfter(":").trim().toIntOrNull() ?: 0
                    }
                }
                val buf = ByteArray(len.coerceAtMost(2_000_000))
                var read = 0
                while (read < buf.size) {
                    val n = input.read(buf, read, buf.size - read)
                    if (n <= 0) break
                    read += n
                }
                onCast(String(buf, 0, read, Charsets.UTF_8))
                respond(s.getOutputStream(), 200, """{"ok":true}""")
            }
        } catch (e: Exception) {
            Log.w(TAG, "投屏请求失败: ${e.message}")
        }
    }

    private fun respond(out: OutputStream, code: Int, json: String) {
        val body = json.toByteArray()
        val head = "HTTP/1.1 $code OK\r\nContent-Type: application/json; charset=utf-8\r\n" +
            "Content-Length: ${body.size}\r\nConnection: close\r\n\r\n"
        out.write(head.toByteArray())
        out.write(body)
        out.flush()
    }

    private fun startUdp() {
        if (udp != null) return
        Thread({
            try {
                val ds = DatagramSocket(null)
                ds.reuseAddress = true
                ds.bind(InetSocketAddress("0.0.0.0", UDP_PORT))
                udp = ds
                val buf = ByteArray(256)
                while (!ds.isClosed) {
                    val p = DatagramPacket(buf, buf.size)
                    ds.receive(p)
                    val msg = String(p.data, 0, p.length)
                    if (!msg.startsWith("PROOM_TV_DISCOVER")) continue
                    val ip = localIp() ?: continue
                    val model = android.os.Build.MODEL?.replace(" ", "") ?: ""
                    val name = if (model.isEmpty()) "放映室电视" else "放映室电视 · $model"
                    val reply = "PROOM_TV http://$ip:$TCP_PORT|$name".toByteArray()
                    ds.send(DatagramPacket(reply, reply.size, p.address, p.port))
                }
            } catch (e: Exception) {
                Log.w(TAG, "发现端口启动失败: ${e.message}")
            }
        }, "cast-udp").apply { isDaemon = true }.start()
    }

    /** 局域网 IP：优先非回环的 IPv4 站点地址 */
    private fun localIp(): String? {
        try {
            for (nif in NetworkInterface.getNetworkInterfaces()) {
                if (!nif.isUp || nif.isLoopback) continue
                for (a in nif.inetAddresses) {
                    if (a is java.net.Inet4Address && !a.isLoopbackAddress && a.isSiteLocalAddress) {
                        return a.hostAddress
                    }
                }
            }
        } catch (_: Exception) {}
        return null
    }

    private fun readHead(input: BufferedInputStream): String? {
        val sb = StringBuilder()
        var last = 0
        while (true) {
            val b = input.read()
            if (b < 0) return if (sb.isEmpty()) null else sb.toString()
            sb.append(b.toChar())
            last = (last shl 8) or b
            if (last == 0x0d0a0d0a) return sb.toString()
            if (sb.length > 16_384) return null
        }
    }

    fun stop() {
        try { tcp?.close() } catch (_: Exception) {}
        try { udp?.close() } catch (_: Exception) {}
        tcp = null; udp = null
    }
}
