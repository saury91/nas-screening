package com.projectionroom.app.cast

import android.content.Context
import android.net.wifi.WifiManager
import com.projectionroom.shared.cast.Dmr
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedInputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.ServerSocket

/**
 * 电视端 DLNA 接收器：SSDP 应答 + AVTransport 控制。
 *
 * 两件事：
 *  1. 监听 1900 的 M-SEARCH，回一个带 LOCATION 的 200 OK（手机靠它发现这台电视）
 *  2. 在 [port] 上跑一个极小的 HTTP 服务：/description.xml、/scpd.xml、/control
 *
 * 播放本身交给 [CastPlayActivity]，服务只改 [CastReceiver] 里的状态。
 * 不用 nanohttpd/cling：这里只有三个路由和五个动作，手写更小也更好排查。
 */
class DmrServer(
    private val ctx: Context,
    private val friendlyName: String = "放映室 TV",
    private val port: Int = 49520,
) {

    private var http: ServerSocket? = null
    private var ssdp: MulticastSocket? = null
    private var lock: WifiManager.MulticastLock? = null
    private var job: Job? = null
    private var job2: Job? = null

    /** ExoPlayer / startActivity 都要求主线程：SOAP 处理在 IO 线程，必须转过去 */
    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    private val uuid = "uuid:9a1c3f40-0001-4a00-9000-${(port * 37).toString().padStart(12, '0')}"

    /** 本机局域网 IPv4：描述里的 LOCATION 必须是对方能访问的地址，不能写 127.0.0.1 */
    private fun localIp(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { !it.isLoopbackAddress && it.address.size == 4 && it is InetAddress }
            ?.hostAddress
    }.getOrNull()

    fun start(scope: CoroutineScope) {
        if (job != null) return
        val ip = localIp() ?: return
        val location = "http://$ip:$port/description.xml"

        // 收到 SetAVTransportURI 就直接拉起播放页（电视端没有"先确认再播"的交互）
        CastReceiver.onPlay = { url, title ->
            if (url.isNotBlank()) {
                runCatching {
                    ctx.startActivity(CastPlayActivity.intent(ctx, url, title))
                }
            }
        }
        job = scope.launch(Dispatchers.IO) { serveHttp() }
        job2 = scope.launch(Dispatchers.IO) { answerSearch(location) }
    }

    fun stop() {
        job?.cancel(); job2?.cancel()
        runCatching { http?.close() }
        runCatching { ssdp?.close() }
        runCatching { lock?.release() }
        http = null; ssdp = null; job = null; job2 = null
    }

    private fun serveHttp() {
        val srv = runCatching { ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(port)) } }
            .getOrNull() ?: return
        http = srv
        while (job?.isActive == true && !srv.isClosed) {
            val c = runCatching { srv.accept() }.getOrNull() ?: continue
            runCatching {
                c.use { sock ->
                    val inp = BufferedInputStream(sock.getInputStream())
                    val head = readHead(inp) ?: return@use
                    val firstLine = head.lineSequence().firstOrNull().orEmpty()
                    val path = firstLine.split(' ').getOrNull(1).orEmpty()
                    val body = readBody(inp, head)
                    val soapAction = head.lineSequence()
                        .firstOrNull { it.startsWith("SOAPACTION", true) }
                        ?.substringAfter(':')?.trim()

                    val (code, payload) = route(path, soapAction, body)
                    val out: OutputStream = sock.getOutputStream()
                    val bytes = payload.toByteArray()
                    val header = buildString {
                        append("HTTP/1.1 $code OK\r\n")
                        append("Content-Type: text/xml; charset=\"utf-8\"\r\n")
                        append("Content-Length: ${bytes.size}\r\n")
                        append("Connection: close\r\n\r\n")
                    }
                    out.write(header.toByteArray()); out.write(bytes); out.flush()
                }
            }
        }
    }

    /**
     * 读到空行为止。
     *
     * 注意必须同时认 `\r\n\r\n`（标准 HTTP）和 `\n\n`：
     * 只认后者会让所有真实客户端卡死 —— 之前就是这样，服务在监听但请求永远不返回。
     */
    private fun readHead(inp: BufferedInputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = inp.read()
            if (b < 0) return if (sb.isEmpty()) null else sb.toString()
            sb.append(b.toChar())
            if (sb.endsWith("\r\n\r\n") || sb.endsWith("\n\n")) break
            if (sb.length > 16_384) break
        }
        return sb.toString()
    }

    private fun readBody(inp: BufferedInputStream, head: String): String {
        val len = head.lineSequence()
            .firstOrNull { it.startsWith("Content-Length", true) }
            ?.substringAfter(':')?.trim()?.toIntOrNull() ?: 0
        if (len <= 0) return ""
        val buf = ByteArray(len)
        var read = 0
        while (read < len) {
            val n = inp.read(buf, read, len - read)
            if (n <= 0) break
            read += n
        }
        return String(buf, 0, read)
    }

    private fun route(path: String, soapAction: String?, body: String): Pair<Int, String> {
        return when {
            path.startsWith("/description.xml") -> 200 to Dmr.description(friendlyName, uuid)
            path.startsWith("/scpd.xml") -> 200 to Dmr.scpd()
            path.startsWith("/control") -> 200 to handleSoap(Dmr.actionOf(soapAction), body)
            else -> 404 to "not found"
        }
    }

    private fun handleSoap(action: String, body: String): String = when (action) {
        "SetAVTransportURI" -> {
            CastReceiver.uri = Dmr.arg(body, "CurrentURI").orEmpty()
            CastReceiver.title = Dmr.arg(body, "CurrentURIMetaData")
                ?.let { Regex("<dc:title>(.*?)</dc:title>").find(it)?.groupValues?.get(1) }
                ?.let { Dmr.unesc(it) }
                ?.takeIf { it.isNotBlank() }
                ?: "投屏内容"
            // 只设地址不播：紧接着的 Play 才是"开始"
            CastReceiver.state = "STOPPED"
            val u = CastReceiver.uri
            val t = CastReceiver.title
            main.post { CastReceiver.onPlay?.invoke(u, t) }
            Dmr.setUriResponse()
        }

        "Play" -> {
            if (CastReceiver.state == "PAUSED_PLAYBACK") main.post { CastReceiver.onResume?.invoke() }
            CastReceiver.state = "PLAYING"
            Dmr.playResponse()
        }

        "Pause" -> {
            main.post { CastReceiver.onPause?.invoke() }
            CastReceiver.state = "PAUSED_PLAYBACK"
            Dmr.pauseResponse()
        }

        "Stop" -> {
            main.post { CastReceiver.onStop?.invoke() }
            CastReceiver.reset()
            Dmr.stopResponse()
        }

        "Seek" -> {
            val sec = Dmr.parseClock(Dmr.arg(body, "Target"))
            CastReceiver.positionMs = sec * 1000
            main.post { CastReceiver.onSeek?.invoke(sec * 1000) }
            Dmr.seekResponse()
        }

        "GetTransportInfo" -> Dmr.transportInfoResponse(CastReceiver.state)

        "GetPositionInfo" -> Dmr.positionInfoResponse(
            CastReceiver.uri,
            CastReceiver.positionMs / 1000,
            CastReceiver.durationMs / 1000,
        )

        else -> Dmr.error(401, "Invalid Action")
    }

    private fun answerSearch(location: String) {
        runCatching {
            val wifi = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            lock = wifi.createMulticastLock("proom-dmr").apply { setReferenceCounted(false); acquire() }
        }
        val sock = runCatching {
            MulticastSocket(1900).apply {
                reuseAddress = true
                joinGroup(InetSocketAddress(InetAddress.getByName("239.255.255.250"), 1900), null)
                soTimeout = 1_000
            }
        }.getOrNull() ?: return
        ssdp = sock
        val buf = ByteArray(2048)
        while (job2?.isActive == true && !sock.isClosed) {
            val pkt = DatagramPacket(buf, buf.size)
            val ok = runCatching { sock.receive(pkt) }.isSuccess
            if (!ok) continue
            val text = String(pkt.data, 0, pkt.length)
            if (!Dmr.matchesSearch(text)) continue
            val reply = Dmr.ssdpResponse(location, uuid).toByteArray()
            runCatching {
                sock.send(DatagramPacket(reply, reply.size, pkt.address, pkt.port))
            }
        }
    }
}
