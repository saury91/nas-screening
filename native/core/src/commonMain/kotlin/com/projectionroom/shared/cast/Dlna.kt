package com.projectionroom.shared.cast

/**
 * DLNA / UPnP 投屏协议层（纯字符串处理，不碰网络）。
 *
 * 只做投屏真正需要的三件事：
 *  1. SSDP 搜索报文 + 从响应里取设备描述地址（LOCATION）
 *  2. 解析设备描述 XML，拿到 AVTransport 的控制地址
 *  3. 生成 / 解析 AVTransport 的 SOAP 报文（SetAVTransportURI、Play、Seek…）
 *
 * 网络部分放在 app 模块（[com.projectionroom.app.cast.DlnaClient]）：
 * UDP 组播和 HTTP 都得用平台 API，放在 commonMain 反而写不出可测的代码。
 */
data class CastDevice(
    /** 设备描述 XML 的地址，形如 http://192.168.1.8:49152/description.xml */
    val location: String,
    val name: String,
    val model: String = "",
    /** AVTransport 控制地址；为空说明这台设备不能推流（比如纯 MediaServer） */
    val controlUrl: String = "",
    val udn: String = "",
    val manufacturer: String = "",
) {
    val canCast: Boolean get() = controlUrl.isNotBlank()

    /**
     * 是不是自家的电视端。
     *
     * 认这个是为了置顶 —— 一个客厅里能搜出七八台设备：电视、机顶盒、路由器上的
     * 媒体服务、隔壁的电视。用户要投的永远是那台装着放映室的。
     * 另外自家电视能读 `x-proom-*` 请求头，投屏成功率也更高。
     */
    val isOwn: Boolean
        get() = manufacturer.contains(OWN_MANUFACTURER, ignoreCase = true) ||
            model.contains("放映室", ignoreCase = true)

    companion object {
        const val OWN_MANUFACTURER = "Projection Room"
    }
}

object Ssdp {

    const val MULTICAST = "239.255.255.250"
    const val PORT = 1900

    /**
     * M-SEARCH 报文。
     *
     * `MAN: "ssdp:discover"` 的双引号是协议要求，漏了设备直接不理你；
     * MX 是"最多几秒内回"，写 2 秒能在 3 秒内收齐局域网里的电视。
     */
    fun mSearch(mx: Int = 2): String = buildString {
        append("M-SEARCH * HTTP/1.1\r\n")
        append("HOST: $MULTICAST:$PORT\r\n")
        append("MAN: \"ssdp:discover\"\r\n")
        append("MX: $mx\r\n")
        append("ST: urn:schemas-upnp-org:device:MediaRenderer:1\r\n")
        append("\r\n")
    }

    /** 从单条 SSDP 响应里取 LOCATION；不是 200 OK 或没有该头就返回 null */
    fun locationOf(response: String): String? {
        val lines = response.lineSequence().map { it.trim() }.toList()
        if (lines.isEmpty() || !lines.first().uppercase().contains("200 OK")) return null
        return lines.firstOrNull { it.uppercase().startsWith("LOCATION:") }
            ?.substringAfter(':')?.trim()?.takeIf { it.isNotEmpty() }
    }
}

object UpnpDesc {

    private val FRIENDLY = Regex("<friendlyName>([^<]*)</friendlyName>", RegexOption.IGNORE_CASE)
    private val MODEL = Regex("<modelName>([^<]*)</modelName>", RegexOption.IGNORE_CASE)
    private val VENDOR = Regex("<manufacturer>([^<]*)</manufacturer>", RegexOption.IGNORE_CASE)
    private val UDN = Regex("<UDN>([^<]*)</UDN>", RegexOption.IGNORE_CASE)
    private val SERVICE = Regex("<service>(.*?)</service>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val CONTROL = Regex("<controlURL>([^<]*)</controlURL>", RegexOption.IGNORE_CASE)

    /**
     * 解析设备描述。
     *
     * 关键点：一个设备里往往有 ContentDirectory / ConnectionManager / AVTransport
     * 好几个服务，只有 AVTransport 能推流。所以必须**按 service 块**找，
     * 不能全文搜第一个 controlURL —— 那样会拿到浏览目录用的地址。
     */
    fun parse(xml: String, location: String): CastDevice? {
        if (xml.isBlank()) return null
        val av = SERVICE.findAll(xml).firstOrNull { m ->
            m.groupValues[1].contains("AVTransport", ignoreCase = true)
        }?.groupValues?.get(1)
        val control = av?.let { CONTROL.find(it)?.groupValues?.get(1)?.trim() }.orEmpty()
        return CastDevice(
            location = location,
            name = FRIENDLY.find(xml)?.groupValues?.get(1)?.trim()?.ifBlank { null } ?: "未命名设备",
            model = MODEL.find(xml)?.groupValues?.get(1)?.trim().orEmpty(),
            controlUrl = if (control.isBlank()) "" else resolve(location, control),
            udn = UDN.find(xml)?.groupValues?.get(1)?.trim().orEmpty(),
            manufacturer = VENDOR.find(xml)?.groupValues?.get(1)?.trim().orEmpty(),
        )
    }

    /** 把相对路径拼成绝对地址（设备常给 `/upnp/control/AVTransport1`） */
    fun resolve(base: String, path: String): String {
        if (path.startsWith("http://") || path.startsWith("https://")) return path
        // 手写解析而不是 java.net.URI：shared 是 commonMain，尽量不依赖 JVM 专有 API
        val scheme = base.substringBefore("://", "")
        val rest = base.substringAfter("://", "")
        val origin = if (scheme.isEmpty()) base else "$scheme://${rest.substringBefore('/')}"
        return if (path.startsWith("/")) origin + path else "$origin/$path"
    }
}

object AvTransport {

    const val SERVICE = "urn:schemas-upnp-org:service:AVTransport:1"

    /** SOAP 信封。设备对 namespace 前缀不敏感，但 s:/u: 这两种必须成对出现 */
    private fun envelope(action: String, inner: String): String = buildString {
        append("""<?xml version="1.0" encoding="utf-8"?>""")
        append("""<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" """)
        append("""s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">""")
        append("<s:Body>")
        append("<u:").append(action).append(" xmlns:u=\"").append(SERVICE).append("\">")
        append("<InstanceID>0</InstanceID>")
        append(inner)
        append("</u:").append(action).append(">")
        append("</s:Body></s:Envelope>")
    }

    fun setUri(
        uri: String,
        title: String,
        headers: Map<String, String> = emptyMap(),
    ): String =
        envelope("SetAVTransportURI", "<CurrentURI>${esc(uri)}</CurrentURI>" +
            "<CurrentURIMetaData>${esc(metadata(uri, title, headers))}</CurrentURIMetaData>")

    fun play(): String = envelope("Play", "<Speed>1</Speed>")
    fun pause(): String = envelope("Pause", "")
    fun stop(): String = envelope("Stop", "")

    /** 拖动进度：REL_TIME 用 `HH:MM:SS` */
    fun seek(seconds: Long): String =
        envelope("Seek", "<Unit>REL_TIME</Unit><Target>${clock(seconds)}</Target>")

    fun getTransportInfo(): String = envelope("GetTransportInfo", "")
    fun getPositionInfo(): String = envelope("GetPositionInfo", "")

    /**
     * DIDL 元数据。
     *
     * 部分电视要求带它才显示片名；不带也能播，但电视上会显示成一串 URL。
     *
     * [headers] 会写成 `x-proom-*` 扩展属性跟着 `<res>` 一起过去。标准 DLNA 没有
     * 「传请求头」这个概念，但采集站的 m3u8 大多校验 Referer/UA，不带就是 403 ——
     * 手机能播、投到电视黑屏，绝大多数是这个原因。自家接收端会读这几个属性，
     * 第三方设备不认识就忽略（它们本来也不校验这些头）。
     */
    fun metadata(uri: String, title: String, headers: Map<String, String> = emptyMap()): String =
        buildString {
            append("""<DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/" """)
            append("""xmlns:dc="http://purl.org/dc/elements/1.1/" """)
            append("""xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/">""")
            append("""<item id="0" parentID="-1" restricted="1">""")
            append("<dc:title>").append(esc(title)).append("</dc:title>")
            append("<upnp:class>object.item.videoItem</upnp:class>")
            append("""<res protocolInfo="http-get:*:${mimeOf(uri)}:*"""")
            headers["User-Agent"]?.let { append(""" x-proom-ua="${esc(it)}"""") }
            headers["Referer"]?.let { append(""" x-proom-ref="${esc(it)}"""") }
            append(">").append(esc(uri)).append("</res>")
            append("</item></DIDL-Lite>")
        }

    /**
     * 按地址猜 MIME。
     *
     * 以前写死 `video/mp4`，可实际推出去的十有八九是 m3u8。严格的接收端会照着
     * protocolInfo 选解码器，声明成 mp4 就成了「能连上、放不出来」。
     */
    fun mimeOf(uri: String): String {
        val path = uri.substringBefore('?').substringBefore('#').lowercase()
        return when {
            path.endsWith(".m3u8") -> "application/vnd.apple.mpegurl"
            path.endsWith(".mpd") -> "application/dash+xml"
            path.endsWith(".m4v") || path.endsWith(".mp4") -> "video/mp4"
            path.endsWith(".mkv") -> "video/x-matroska"
            path.endsWith(".flv") -> "video/x-flv"
            path.endsWith(".ts") -> "video/mp2t"
            path.endsWith(".webm") -> "video/webm"
            else -> "video/mp4"
        }
    }



    private val STATE = Regex("<CurrentTransportState>([^<]*)</CurrentTransportState>", RegexOption.IGNORE_CASE)
    private val REL = Regex("<RelTime>([^<]*)</RelTime>", RegexOption.IGNORE_CASE)

    /** PLAYING / PAUSED_PLAYBACK / STOPPED / TRANSITIONING */
    fun parseState(soap: String): String =
        STATE.find(soap)?.groupValues?.get(1)?.trim().orEmpty()

    /** `00:01:23` → 83；解析不出来返回 -1 */
    fun parseRelTime(soap: String): Long {
        val raw = REL.find(soap)?.groupValues?.get(1)?.trim().orEmpty()
        if (raw.isBlank()) return -1
        val parts = raw.split(':')
        if (parts.size != 3) return -1
        val h = parts[0].toLongOrNull() ?: return -1
        val m = parts[1].toLongOrNull() ?: return -1
        val s = parts[2].substringBefore('.').toLongOrNull() ?: return -1
        return h * 3600 + m * 60 + s
    }

    fun clock(sec: Long): String {
        val v = sec.coerceAtLeast(0)
        return "${(v / 3600).toString().padStart(2, '0')}:" +
            "${((v % 3600) / 60).toString().padStart(2, '0')}:" +
            "${(v % 60).toString().padStart(2, '0')}"
    }

    fun esc(s: String): String = s
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;").replace("'", "&apos;")

    /** 只反转义 [esc] 会产出的那五个，顺序与 [esc] 相反 */
    fun unesc(s: String): String = s
        .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
        .replace("&apos;", "'").replace("&amp;", "&")
}
