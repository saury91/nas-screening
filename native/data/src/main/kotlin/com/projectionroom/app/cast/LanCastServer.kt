package com.projectionroom.app.cast

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import com.projectionroom.shared.cast.CastApi
import com.projectionroom.shared.cast.LanCast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedInputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.ServerSocket

/**
 * 电视端：PROOM-CAST/1 接收器。
 *
 * 与 [DmrServer] 的分工：
 *  - DMR 面向「别人家装的投屏 App」，只能说标准里那五句话；
 *  - 这里面向「装了放映室手机端的自己人」，倍速、音量、第几集、请求头
 *    都能原样带过来，**且不经过 NAS**。
 *
 * 两个监听：
 *  1. TCP [LanCast.PORT]：`/proom/info`、`/proom/state`、`/proom/cmd`
 *  2. UDP [LanCast.DISCOVER_PORT]：收到 `PROOM-CAST/1 DISCOVER` 回一行 JSON
 *
 * 播放/暂停还是走 [CastReceiver] 这套状态总线：三条链路（DMR、NAS、这里）
 * 最终都落到 [CastPlayActivity] 上，接收端的实现只有一份。
 */
class LanCastServer(
    private val ctx: Context,
    private val friendlyName: String = "放映室 TV",
    private val port: Int = LanCast.PORT,
    /**
     * 收到 play 指令后怎么起播。由 [CastService] 注入：服务里没有 Activity
     * 上下文，后台启动限制的兜底（发通知）只有宿主清楚。
     */
    private val onPlay: (url: String, title: String, headers: Map<String, String>, line: Int, ep: Int, episode: String) -> Unit,
) {

    private var http: ServerSocket? = null
    private var udp: DatagramSocket? = null
    private var lock: WifiManager.MulticastLock? = null
    private var job: Job? = null
    private var job2: Job? = null

    private val main = Handler(Looper.getMainLooper())
    private val id = castDeviceId(ctx)

    fun start(scope: CoroutineScope) {
        if (job != null) return
        android.util.Log.i("CASTDBG", "lan: start port=$port discover=${LanCast.DISCOVER_PORT}")
        job = scope.launch(Dispatchers.IO) { serveHttp() }
        job2 = scope.launch(Dispatchers.IO) { answerDiscover() }
    }

    fun stop() {
        job?.cancel(); job2?.cancel()
        runCatching { http?.close() }
        runCatching { udp?.close() }
        runCatching { lock?.release() }
        http = null; udp = null; lock = null; job = null; job2 = null
    }

    /** 当前播放状态。字段和 NAS 桥接完全一致，遥控页不用区分通道 */
    private fun state(): CastApi.State = CastApi.State(
        url = CastReceiver.uri,
        title = CastReceiver.title,
        position = CastReceiver.positionMs,
        duration = CastReceiver.durationMs,
        playing = CastReceiver.state == "PLAYING",
        rate = CastReceiver.rate,
        volume = CastReceiver.volume,
        line = CastReceiver.line,
        ep = CastReceiver.ep,
        episode = CastReceiver.episode,
    )

    private suspend fun serveHttp() {
        val srv = runCatching {
            ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(port)) }
        }.onFailure {
            android.util.Log.e("CASTDBG", "lan: bind $port 失败", it)
        }.getOrNull() ?: return
        http = srv
        android.util.Log.i("CASTDBG", "lan: http 已在 $port 监听")
        while (currentCoroutineContext().isActive && !srv.isClosed) {
            val c = runCatching { srv.accept() }.getOrNull() ?: continue
            runCatching {
                c.use { sock ->
                    val inp = BufferedInputStream(sock.getInputStream())
                    val head = readHead(inp) ?: return@use
                    val firstLine = head.lineSequence().firstOrNull().orEmpty()
                    val path = firstLine.split(' ').getOrNull(1).orEmpty()
                    val body = readBody(inp, head)
                    val (code, payload) = route(path, body)

                    val out: OutputStream = sock.getOutputStream()
                    val bytes = payload.toByteArray()
                    val header = buildString {
                        append("HTTP/1.1 $code ${if (code == 200) "OK" else "Not Found"}\r\n")
                        append("Content-Type: application/json; charset=utf-8\r\n")
                        append("Content-Length: ${bytes.size}\r\n")
                        append("Connection: close\r\n\r\n")
                    }
                    out.write(header.toByteArray()); out.write(bytes); out.flush()
                }
            }
        }
    }

    /** 必须同时认 `\r\n\r\n` 和 `\n\n`：只认后者会让某些客户端卡到超时 */
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

    private fun route(path: String, body: String): Pair<Int, String> = when {
        path.startsWith(LanCast.PATH_INFO) -> 200 to LanCast.infoBody(id, friendlyName, state())
        path.startsWith(LanCast.PATH_STATE) -> 200 to LanCast.stateBody(state())
        path.startsWith(LanCast.PATH_CMD) -> {
            val cmd = LanCast.parseCommand(body)
            if (cmd == null) {
                200 to LanCast.ackBody(false, "bad command")
            } else {
                android.util.Log.i("CASTDBG", "lan: cmd kind=${cmd.kind} url=${cmd.url} pos=${cmd.position}")
                handle(cmd)
                200 to LanCast.ackBody()
            }
        }
        else -> 404 to "not found"
    }

    private fun handle(cmd: CastApi.Command) {
        when (cmd.kind) {
            "play" -> {
                if (cmd.url.isBlank()) return
                // 先落状态再起播：播放页 onCreate 会读这些值显示标题和集数
                CastReceiver.title = cmd.title
                CastReceiver.line = cmd.line
                CastReceiver.ep = cmd.ep
                CastReceiver.episode = cmd.episode
                if (cmd.rate > 0f) CastReceiver.rate = cmd.rate
                if (cmd.volume >= 0f) CastReceiver.volume = cmd.volume
                main.post {
                    runCatching { onPlay(cmd.url, cmd.title, cmd.headers, cmd.line, cmd.ep, cmd.episode) }
                        .onFailure { android.util.Log.e("CASTDBG", "lan onPlay failed", it) }
                }
            }
            // 其余都是「改状态」：操作的是 ExoPlayer，必须回主线程
            "pause" -> {
                CastReceiver.state = "PAUSED_PLAYBACK"
                main.post { CastReceiver.onPause?.invoke() }
            }
            "resume" -> {
                CastReceiver.state = "PLAYING"
                main.post { CastReceiver.onResume?.invoke() }
            }
            "seek" -> {
                CastReceiver.positionMs = cmd.position
                main.post { CastReceiver.onSeek?.invoke(cmd.position) }
            }
            "stop" -> {
                CastReceiver.reset()
                main.post { CastReceiver.onStop?.invoke() }
            }
            "rate" -> if (cmd.rate > 0f) {
                CastReceiver.rate = cmd.rate
                main.post { CastReceiver.onRate?.invoke(cmd.rate) }
            }
            "volume" -> if (cmd.volume >= 0f) {
                CastReceiver.volume = cmd.volume
                main.post { CastReceiver.onVolume?.invoke(cmd.volume) }
            }
        }
    }

    private suspend fun answerDiscover() {
        runCatching {
            val wifi = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            lock = wifi.createMulticastLock("proom-cast-lan").apply {
                setReferenceCounted(false)
                acquire()
            }
        }
        val sock = runCatching {
            DatagramSocket(null).apply {
                reuseAddress = true
                bind(InetSocketAddress(LanCast.DISCOVER_PORT))
                broadcast = true
                soTimeout = 1_000
            }
        }.onFailure {
            android.util.Log.e("CASTDBG", "lan: 绑不了 ${LanCast.DISCOVER_PORT}", it)
        }.getOrNull() ?: return
        udp = sock
        android.util.Log.i("CASTDBG", "lan: 正在 ${LanCast.DISCOVER_PORT} 应答发现")
        val buf = ByteArray(2048)
        while (currentCoroutineContext().isActive && !sock.isClosed) {
            val pkt = DatagramPacket(buf, buf.size)
            if (!runCatching { sock.receive(pkt) }.isSuccess) continue
            val text = String(pkt.data, 0, pkt.length)
            if (!LanCast.isDiscover(text)) continue
            val offer = LanCast.offer(
                id = id,
                name = friendlyName,
                title = CastReceiver.title,
                playing = CastReceiver.state == "PLAYING",
                position = CastReceiver.positionMs,
                duration = CastReceiver.durationMs,
                port = port,
            )
            val bytes = LanCast.offerLine(offer).toByteArray()
            runCatching {
                sock.send(DatagramPacket(bytes, bytes.size, pkt.address, pkt.port))
            }
        }
    }
}
