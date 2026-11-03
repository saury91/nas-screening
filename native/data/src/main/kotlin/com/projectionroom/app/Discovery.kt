package com.projectionroom.app

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException

/**
 * 局域网自动发现。
 *
 * 协议在 NAS 端（app.py 的 discovery_loop）：往 UDP 8897 广播 `PROOM_DISCOVER`，
 * 对方回 `PROOM_OFFER http://192.0.2.x:8899`。
 * 和电视端的发现走同一套，NAS 端不用改。
 */
object Discovery {

    private const val PORT = 8897
    private const val MAGIC = "PROOM_DISCOVER"
    private const val PREFIX = "PROOM_OFFER "

    /**
     * 找一台放映室。会重发几次广播（UDP 会丢包，只发一次十有八九收不到）。
     * 返回形如 `http://192.0.2.10:8899`，找不到返回 null。
     */
    fun find(timeoutMs: Int = 3500): String? {
        DatagramSocket().use { sock ->
            sock.broadcast = true
            sock.soTimeout = 600
            val payload = MAGIC.toByteArray()
            val targets = listOf(
                InetAddress.getByName("255.255.255.255"),
                InetAddress.getByName("224.0.0.1"),
            )
            val deadline = System.currentTimeMillis() + timeoutMs
            var nextSend = 0L
            val buf = ByteArray(256)

            while (System.currentTimeMillis() < deadline) {
                val now = System.currentTimeMillis()
                if (now >= nextSend) {
                    for (addr in targets) {
                        try {
                            sock.send(DatagramPacket(payload, payload.size, addr, PORT))
                        } catch (_: Exception) {
                            // 某些网络禁广播，换下一个目标继续
                        }
                    }
                    nextSend = now + 900
                }
                try {
                    val pkt = DatagramPacket(buf, buf.size)
                    sock.receive(pkt)
                    val text = String(pkt.data, 0, pkt.length).trim()
                    if (text.startsWith(PREFIX)) {
                        return text.removePrefix(PREFIX).trim()
                    }
                } catch (_: SocketTimeoutException) {
                    // 正常：等下一轮
                } catch (_: Exception) {
                    return null
                }
            }
        }
        return null
    }
}
