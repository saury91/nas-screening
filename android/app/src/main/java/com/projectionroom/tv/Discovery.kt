package com.projectionroom.tv

import android.content.Context
import android.net.wifi.WifiManager
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketTimeoutException

/**
 * 局域网里找 NAS。
 *
 * 电视上用遥控器敲 IP 是最难受的环节，所以先广播问一嘴：
 * NAS 上的服务端在 UDP 8897 监听，收到 PROOM_DISCOVER 就回自己的地址。
 * 找到就自动填好，找不到再退回「扫码让手机发过来」。
 */
object Discovery {

    private const val PORT = 8897
    private const val MAGIC = "PROOM_DISCOVER"
    private const val OFFER = "PROOM_OFFER "

    fun find(timeoutMs: Long = 2600): String? {
        var socket: DatagramSocket? = null
        try {
            socket = DatagramSocket().apply {
                broadcast = true
                soTimeout = 400
            }
            val payload = MAGIC.toByteArray()
            val target = InetAddress.getByName("255.255.255.255")
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                socket.send(DatagramPacket(payload, payload.size, target, PORT))
                try {
                    val buf = ByteArray(512)
                    val packet = DatagramPacket(buf, buf.size)
                    socket.receive(packet)
                    val text = String(packet.data, 0, packet.length)
                    if (text.startsWith(OFFER)) {
                        return text.removePrefix(OFFER).trim().ifBlank { null }
                    }
                } catch (_: SocketTimeoutException) {
                    // 没回包就再广播一次，直到超时
                }
            }
        } catch (_: Exception) {
            return null
        } finally {
            socket?.close()
        }
        return null
    }

    /** 本机在局域网里的 IPv4，用来生成扫码地址 */
    fun localIp(ctx: Context): String? {
        try {
            for (nif in NetworkInterface.getNetworkInterfaces()) {
                if (!nif.isUp || nif.isLoopback) continue
                for (addr in nif.inetAddresses) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress && addr.isSiteLocalAddress) {
                        return addr.hostAddress
                    }
                }
            }
        } catch (_: Exception) {
        }
        // 某些电视的网卡枚举拿不到，退回 WiFi 信息
        return try {
            val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            @Suppress("DEPRECATION")
            val ip = wm.connectionInfo.ipAddress
            if (ip == 0) null else String.format(
                "%d.%d.%d.%d", ip and 0xff, ip shr 8 and 0xff, ip shr 16 and 0xff, ip shr 24 and 0xff
            )
        } catch (_: Exception) {
            null
        }
    }
}
