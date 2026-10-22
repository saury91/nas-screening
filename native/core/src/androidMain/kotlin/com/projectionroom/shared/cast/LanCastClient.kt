package com.projectionroom.shared.cast

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.concurrent.TimeUnit

/**
 * 手机端：PROOM-CAST/1 的「手」。
 *
 * 和 [DlnaClient] 一样，协议内容全在 [LanCast] 里（纯字符串，能单测），
 * 这里只开 socket 和发 HTTP。okhttp 不暴露到方法签名上：ui-media 只
 * `implementation` 了 core，看不到 okhttp 的类型。
 *
 * 发现是**单播应答**（我们发广播，电视回到我们的临时端口），
 * 所以不需要 MulticastLock —— 那个锁只管组播接收。
 */
class LanCastClient {

    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(4, TimeUnit.SECONDS)
        .build()

    private val media = "application/json; charset=utf-8".toMediaType()

    /**
     * 广播找电视，收到应答就返回。
     *
     * 最多等 [timeoutMs]，收到第一台后只再等 [graceMs]。
     *
     * 自家电视收到 DISCOVER 就是一个 HTTP 应答，几十毫秒的事；
     * 等满 1.5 秒纯属干等。留 [graceMs] 只是为了让同一网段的第二台电视也能挤进来。
     * 一台都没有时才真等满 [timeoutMs]（这时用户看到的是「正在搜索」）。
     */
    suspend fun discover(
        timeoutMs: Long = 1_500,
        graceMs: Long = 120,
    ): List<LanCastTarget> = withContext(Dispatchers.IO) {
        val found = LinkedHashMap<String, LanCastTarget>()
        val payload = LanCast.DISCOVER.toByteArray()
        // 255.255.255.255 会被不少家用路由/AP 直接丢掉（尤其访客网络和开了
        // 「隔离」的 5G 频段），只发全网广播就会出现「手填地址能连、搜不到设备」。
        // 补发各网卡的子网广播（192.168.1.255 这种）能救回一大半这类环境。
        val targets = broadcastTargets()
        DatagramSocket().use { sock ->
            runCatching { sock.broadcast = true }
            // 150ms 的收包超时：电视应答是十几毫秒级，等长了只是把
            // 「收到第一台后多久能返回」拖到超时那一步
            sock.soTimeout = 150
            val deadline = System.currentTimeMillis() + timeoutMs
            var nextSend = 0L
            var graceEnd = 0L
            val buf = ByteArray(2048)
            while (System.currentTimeMillis() < deadline) {
                val now = System.currentTimeMillis()
                if (graceEnd > 0 && now >= graceEnd) break
                if (now >= nextSend) {
                    for (addr in targets) {
                        runCatching {
                            sock.send(
                                DatagramPacket(payload, payload.size, addr, LanCast.DISCOVER_PORT),
                            )
                        }
                    }
                    nextSend = now + 600
                }
                val pkt = DatagramPacket(buf, buf.size)
                if (!runCatching { sock.receive(pkt) }.isSuccess) continue
                val text = String(pkt.data, 0, pkt.length)
                val offer = LanCast.parseOffer(text) ?: continue
                val ip = pkt.address?.hostAddress ?: continue
                val t = LanCast.offerDevice(offer, ip)
                found[t.base] = t
                if (graceEnd == 0L) graceEnd = now + graceMs
            }
        }
        found.values.toList()
    }

    /** 全网广播 + 每个网卡的子网广播，去重后作为发现目标 */
    private fun broadcastTargets(): List<InetAddress> {
        val out = LinkedHashSet<InetAddress>()
        runCatching { out += InetAddress.getByName("255.255.255.255") }
        runCatching {
            for (ni in NetworkInterface.getNetworkInterfaces()) {
                if (!ni.isUp || ni.isLoopback) continue
                for (ia in ni.interfaceAddresses) {
                    ia.broadcast?.let { out += it }
                }
            }
        }
        return out.toList()
    }

    /** 确认对面是 PROOM-CAST/1；手填地址时靠它判断该走自定义协议还是 DLNA */
    suspend fun info(base: String): LanCast.Info? = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder().url(base.trimEnd('/') + LanCast.PATH_INFO).get().build()
            http.newCall(req).execute().use { r ->
                if (!r.isSuccessful) return@use null
                LanCast.parseInfo(r.body?.string().orEmpty())
            }
        }.getOrNull()
    }

    /** 电视的真实进度。拿不到就是 null —— 遥控页据此判定掉线 */
    suspend fun state(base: String): CastApi.State? = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder().url(base.trimEnd('/') + LanCast.PATH_STATE).get().build()
            http.newCall(req).execute().use { r ->
                if (!r.isSuccessful) return@use null
                LanCast.parseState(r.body?.string().orEmpty())
            }
        }.getOrNull()
    }

    suspend fun send(base: String, cmd: CastApi.Command): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder()
                .url(base.trimEnd('/') + LanCast.PATH_CMD)
                .post(LanCast.cmdBody(cmd).toRequestBody(media))
                .build()
            http.newCall(req).execute().use { r ->
                r.isSuccessful && (LanCast.parseAck(r.body?.string().orEmpty())?.ok ?: false)
            }
        }.getOrDefault(false)
    }
}
