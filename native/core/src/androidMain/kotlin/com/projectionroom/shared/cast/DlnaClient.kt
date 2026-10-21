package com.projectionroom.shared.cast

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.TimeUnit

/**
 * DLNA 投屏的「手」：负责 UDP 搜索、拉设备描述、发 SOAP 控制。
 *
 * 协议内容全在 [Ssdp] / [UpnpDesc] / [AvTransport] 里（纯字符串，可单测），
 * 这里只做三件平台相关的事：开 socket、发 HTTP、把它们串起来。
 *
 * 为什么不引 cling / jupnp：这两个库一个停更十年、一个要 Android 4.x 兼容层，
 * 体积几十万行，而我们只用 AVTransport 的 5 个动作。
 */
class DlnaClient {

    // 不放在构造函数参数里：ui-media 调 DlnaClient() 时会去解析签名，
    // 而咱们没把 okhttp 暴露给上层模块（implementation 不传递），
    // 「Cannot access class okhttp3.OkHttpClient」就是这么来的。
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    private val xml = "text/xml; charset=\"utf-8\"".toMediaType()

    /**
     * 搜索局域网里的接收端。
     *
     * 只等 [timeoutMs]，收齐就返回 —— 电视回包通常在 200ms 内，2.5 秒足够。
     * 收包是**单播**回给我们的临时端口，所以不需要 MulticastLock（那个锁只影响组播接收）。
     */
    suspend fun discover(timeoutMs: Long = 2_500): List<CastDevice> = withContext(Dispatchers.IO) {
        val locations = linkedSetOf<String>()
        val payload = Ssdp.mSearch().toByteArray()
        DatagramSocket().use { sock ->
            sock.soTimeout = 300
            val targets = listOf(
                InetAddress.getByName(Ssdp.MULTICAST),
                InetAddress.getByName("255.255.255.255"),
            )
            val deadline = System.currentTimeMillis() + timeoutMs
            var nextSend = 0L
            val buf = ByteArray(2048)
            while (System.currentTimeMillis() < deadline) {
                val now = System.currentTimeMillis()
                if (now >= nextSend) {
                    targets.forEach { addr ->
                        runCatching { sock.send(DatagramPacket(payload, payload.size, addr, Ssdp.PORT)) }
                    }
                    nextSend = now + 1_000
                }
                val pkt = DatagramPacket(buf, buf.size)
                val ok = runCatching { sock.receive(pkt) }.isSuccess
                if (ok) {
                    val text = String(pkt.data, 0, pkt.length)
                    Ssdp.locationOf(text)?.let { locations.add(it) }
                }
            }
        }
        // 只为拿到名字：描述文件通常几十 KB，并发拉，3 秒超时的不等
        coroutineScope {
            locations.map { loc -> async { describe(loc) } }.awaitAll()
        }
            .filterNotNull()
            .filter { it.canCast }
            // 自己回自己也算发现成功（同一份 APK 两端都在跑）。
            // 按 UDN 剔，不按 IP：同机双实例、手机双网卡时 IP 会撞。
            .filterNot { d ->
                val self = CastIdentity.udn
                self.isNotBlank() && d.udn == self
            }
            .distinctBy { it.udn.ifBlank { it.location } }
            // 自家电视置顶：一个客厅里能搜出七八台设备，用户要投的永远是装着
            // 放映室的那台；排在第一位就少一次辨认
            .sortedByDescending { it.isOwn }
    }

    /** 拉设备描述并解析出 AVTransport 控制地址 */
    suspend fun describe(location: String): CastDevice? = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder().url(location).get().build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                UpnpDesc.parse(resp.body?.string().orEmpty(), location)
            }
        }.getOrNull()
    }

    suspend fun cast(
        device: CastDevice,
        url: String,
        title: String,
        headers: Map<String, String> = emptyMap(),
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            // 先设地址再播：顺序反了电视会播上一个内容
            soap(device, "SetAVTransportURI", AvTransport.setUri(url, title, headers)).getOrThrow()
            soap(device, "Play", AvTransport.play()).getOrThrow()
            Unit
        }
    }

    suspend fun pause(device: CastDevice) = soap(device, "Pause", AvTransport.pause())
    suspend fun resume(device: CastDevice) = soap(device, "Play", AvTransport.play())
    suspend fun stop(device: CastDevice) = soap(device, "Stop", AvTransport.stop())
    suspend fun seek(device: CastDevice, seconds: Long) =
        soap(device, "Seek", AvTransport.seek(seconds))

    /** PLAYING / PAUSED_PLAYBACK / STOPPED；拿不到返回空串 */
    suspend fun state(device: CastDevice): String = withContext(Dispatchers.IO) {
        runCatching {
            soap(device, "GetTransportInfo", AvTransport.getTransportInfo())
                .map { AvTransport.parseState(it) }
                .getOrDefault("")
        }.getOrDefault("")
    }

    private fun soap(device: CastDevice, action: String, body: String): Result<String> = runCatching {
        val req = Request.Builder()
            .url(device.controlUrl)
            .header("SOAPACTION", "\"${AvTransport.SERVICE}#$action\"")
            .header("Content-Type", "text/xml; charset=\"utf-8\"")
            .post(body.toRequestBody(xml))
            .build()
        http.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            // 500 也可能是「设备不支持这个动作」，把正文带上便于排查
            if (!resp.isSuccessful && text.isBlank()) error("HTTP ${resp.code}")
            text
        }
    }
}
