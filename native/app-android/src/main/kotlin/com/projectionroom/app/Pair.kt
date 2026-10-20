package com.projectionroom.app

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.net.Inet4Address
import java.net.NetworkInterface
import java.security.SecureRandom

/**
 * 手机 ↔ 电视 配对的连接信息。
 *
 * 电视端把这份信息编成二维码，手机扫到后据此把配置 POST 过去。
 * `token` 是防「同一个局域网里别人随手往电视写配置」的：
 * 只在配对页开着的时候生成，接收一次就作废。
 */
data class PairTarget(
    val host: String,
    val port: Int,
    val token: String,
) {
    /** 二维码内容。用自定义 scheme 而不是 http URL，避免被浏览器/相机 App 抢去打开 */
    fun encode(): String = "proom://pair?host=$host&port=$port&token=$token"

    companion object {
        fun parse(raw: String): PairTarget? {
            val t = raw.trim()
            if (!t.startsWith("proom://pair", ignoreCase = true)) return null
            val query = t.substringAfter('?', "")
            val map = query.split('&').mapNotNull {
                val i = it.indexOf('=')
                if (i <= 0) null else it.substring(0, i) to it.substring(i + 1)
            }.toMap()
            val host = map["host"]?.takeIf { it.isNotBlank() } ?: return null
            val port = map["port"]?.toIntOrNull() ?: return null
            val token = map["token"].orEmpty()
            return PairTarget(host, port, token)
        }
    }
}

object Pair {

    /** 配对端口。8897 是自动发现的 UDP 端口，这里用相邻的 TCP 8898 */
    const val PORT = 8898

    /** 四位数字配对码：给「扫码失败改手动输入」兜底用，也方便用户核对 */
    fun newCode(): String = "%04d".format(SecureRandom().nextInt(10_000))

    /**
     * 本机在局域网里的 IPv4。
     *
     * 不用 WifiManager（Android 12 起要位置权限，且以太网/热点场景拿不到合适的地址），
     * 直接遍历网卡：跳过 loopback 和虚拟网卡，优先取站点本地地址。
     */
    fun localIp(): String? {
        val list = runCatching {
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
        }.getOrDefault(emptyList())
        val candidates = list
            .filter { runCatching { it.isUp && !it.isLoopback }.getOrDefault(false) }
            .flatMap { nic -> nic.inetAddresses.toList().map { nic.name to it } }
            .mapNotNull { (name, addr) ->
                val v4 = addr as? Inet4Address ?: return@mapNotNull null
                if (v4.isLoopbackAddress || v4.isLinkLocalAddress) return@mapNotNull null
                Triple(name, v4.hostAddress.orEmpty(), v4.isSiteLocalAddress)
            }
            .filter { it.second.isNotBlank() }
        // wlan / eth 优先于 rmnet（蜂窝）之类
        return (candidates.firstOrNull { it.third && (it.first.startsWith("wlan") || it.first.startsWith("eth")) }
            ?: candidates.firstOrNull { it.third }
            ?: candidates.firstOrNull())?.second
    }

    /** 把配对信息画成二维码位图（电视端显示，白底黑点才扫得动） */
    fun qrBitmap(text: String, sizePx: Int = 640): Bitmap? = runCatching {
        val hints = mapOf(
            EncodeHintType.MARGIN to 1,
            EncodeHintType.CHARACTER_SET to "UTF-8",
            // 屏幕反光、拍歪了都能读出来，代价只是点阵稍密
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
        )
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
        val w = matrix.width
        val h = matrix.height
        val pixels = IntArray(w * h)
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                pixels[row + x] = if (matrix.get(x, y)) Color.BLACK else Color.WHITE
            }
        }
        Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply {
            setPixels(pixels, 0, w, 0, 0, w, h)
        }
    }.getOrNull()
}

/**
 * 手机端：把配置 POST 到电视。
 *
 * 一次请求、一次响应，不重试 —— 失败了让用户重新扫一次，
 * 比在后台偷偷重试三次要好理解。
 */
object PairClient {

    suspend fun push(target: PairTarget, payload: String): String =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val url = java.net.URL("http://${target.host}:${target.port}/pair?token=${target.token}")
            val conn = (url.openConnection() as java.net.HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = 6_000
                readTimeout = 20_000
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
            try {
                conn.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
                val code = conn.responseCode
                val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                    ?.bufferedReader()?.use { it.readText() }.orEmpty()
                if (code in 200..299) text.ifBlank { "已发送" } else "电视拒绝了：${text.ifBlank { "HTTP $code" }}"
            } finally {
                runCatching { conn.disconnect() }
            }
        }
}
