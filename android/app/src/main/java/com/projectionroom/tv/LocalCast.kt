package com.projectionroom.tv

import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException

/**
 * 手机端：在局域网里找装了放映室的电视，并直接把整部片推过去。
 *
 * 不依赖 NAS —— NAS 关机时也能投屏。
 * 发现用 UDP 广播（8893），推送用 HTTP（电视的 8892/cast）。
 */
object LocalCast {

    private const val TAG = "ProomCastPhone"
    private const val DISCOVER_PORT = 8893
    private const val MAGIC = "PROOM_TV_DISCOVER"
    private const val PREFIX = "PROOM_TV "

    /** 返回 [{"name":"192.0.2.6:8892","url":"http://192.0.2.6:8892"}] 的 JSON 数组 */
    fun discover(timeoutMs: Long = 1800): String {
        val found = LinkedHashMap<String, String>()   // host -> url
        var socket: DatagramSocket? = null
        try {
            socket = DatagramSocket().apply {
                broadcast = true
                soTimeout = 350
            }
            val payload = MAGIC.toByteArray()
            val target = InetAddress.getByName("255.255.255.255")
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                socket.send(DatagramPacket(payload, payload.size, target, DISCOVER_PORT))
                try {
                    val buf = ByteArray(256)
                    val p = DatagramPacket(buf, buf.size)
                    socket.receive(p)
                    val text = String(p.data, 0, p.length)
                    if (text.startsWith(PREFIX)) {
                        val body = text.removePrefix(PREFIX).trim()
                        // 格式：http://ip:port|设备名（设备名可缺省）
                        val url = body.substringBefore('|').trim().trimEnd('/')
                        val name = body.substringAfter('|', "").trim().ifEmpty { url.substringAfter("//") }
                        val host = url.substringAfter("//").substringBefore(":")
                        found[host] = "$url|$name"
                    }
                } catch (_: SocketTimeoutException) {
                    // 没回应就再广播一次，直到超时
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "发现失败: ${e.message}")
        } finally {
            try { socket?.close() } catch (_: Exception) {}
        }

        return found.values.joinToString(",", "[", "]") { item ->
            val url = item.substringBefore('|')
            val name = item.substringAfter('|', url.substringAfter("//"))
            """{"name":"$name","url":"$url"}"""
        }
    }

    /** 推送：payload 是整个 item 的 JSON（含线路与集数） */
    fun cast(url: String, payload: String): Boolean {
        return try {
            val base = url.trimEnd('/')
            val resp = HttpMini.request(base, "POST", "/cast", "", emptyMap(), payload.toByteArray())
            resp.code in 200..299
        } catch (e: Exception) {
            Log.w(TAG, "推送失败: ${e.message}")
            false
        }
    }
}
