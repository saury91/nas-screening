package com.projectionroom.shared.cast

/**
 * DLNA **接收端**（MediaRenderer）的协议层。
 *
 * 手机端用 [DlnaClient] 推流，电视端用这里应答：
 *  - 描述文档（手机靠它找到 AVTransport 控制地址）
 *  - SOAP 动作解析（SetAVTransportURI / Play / Pause / Stop / Seek / Get*）
 *  - 状态回读（手机问"电视在播吗、播到哪了"）
 *
 * 同样是纯字符串处理，网络在 app 模块的 [com.projectionroom.app.cast.DmrServer]。
 */
object Dmr {

    const val SERVICE = "urn:schemas-upnp-org:service:AVTransport:1"
    const val DEVICE_TYPE = "urn:schemas-upnp-org:device:MediaRenderer:1"

    /** 设备描述。UDN 用固定名字生成，重启后不变，手机端才不会重复出现两台。 */
    fun description(friendlyName: String, uuid: String): String = """
        <?xml version="1.0" encoding="utf-8"?>
        <root xmlns="urn:schemas-upnp-org:device-1-0">
          <specVersion><major>1</major><minor>0</minor></specVersion>
          <device>
            <deviceType>$DEVICE_TYPE</deviceType>
            <friendlyName>${esc(friendlyName)}</friendlyName>
            <manufacturer>Projection Room</manufacturer>
            <modelName>放映室接收端</modelName>
            <modelNumber>1.0</modelNumber>
            <UDN>$uuid</UDN>
            <serviceList>
              <service>
                <serviceType>$SERVICE</serviceType>
                <serviceId>urn:upnp-org:serviceId:AVTransport</serviceId>
                <SCPDURL>/scpd.xml</SCPDURL>
                <controlURL>/control</controlURL>
                <eventSubURL>/event</eventSubURL>
              </service>
            </serviceList>
          </device>
        </root>
    """.trimIndent()

    /** 服务描述：不少手机/投屏 App 会先拉它再发指令，缺了会直接放弃这台设备 */
    fun scpd(): String = """
        <?xml version="1.0" encoding="utf-8"?>
        <scpd xmlns="urn:schemas-upnp-org:service-1-0">
          <specVersion><major>1</major><minor>0</minor></specVersion>
          <actionList>
            <action><name>SetAVTransportURI</name></action>
            <action><name>Play</name></action>
            <action><name>Pause</name></action>
            <action><name>Stop</name></action>
            <action><name>Seek</name></action>
            <action><name>GetTransportInfo</name></action>
            <action><name>GetPositionInfo</name></action>
          </actionList>
        </scpd>
    """.trimIndent()

    /** 从 `SOAPACTION: "urn:...:AVTransport:1#Play"` 里取出动作名 */
    fun actionOf(soapAction: String?): String =
        soapAction.orEmpty().trim().trim('"').substringAfterLast('#').trim()

    private val UA_ATTR = Regex("""x-proom-ua="([^"]*)"""", RegexOption.IGNORE_CASE)
    private val REF_ATTR = Regex("""x-proom-ref="([^"]*)"""", RegexOption.IGNORE_CASE)

    /**
     * 从推流方给的 DIDL 里读回 `x-proom-*` 请求头。
     *
     * 标准 DLNA 没有「传请求头」这回事，但采集站的 m3u8 校验 Referer/UA，
     * 不带就是 403（手机上正常、投到电视黑屏）。这两个属性是 [AvTransport.metadata]
     * 写的，只有自家接收端认识，第三方设备直接忽略。
     *
     * 只认这两个，其它一律不传 —— 推流方能往 DIDL 里塞任意属性，
     * 没有白名单就等于让电视替它发任意请求。
     */
    fun headersOf(didl: String): Map<String, String> = buildMap {
        UA_ATTR.find(didl)?.groupValues?.get(1)?.let { put("User-Agent", unesc(it)) }
        REF_ATTR.find(didl)?.groupValues?.get(1)?.let { put("Referer", unesc(it)) }
    }

    /** 取一个参数值（<CurrentURI>…</CurrentURI>）；没有返回 null */
    fun arg(soap: String, name: String): String? {
        val m = Regex("<$name>(.*?)</$name>", RegexOption.DOT_MATCHES_ALL).find(soap) ?: return null
        return unesc(m.groupValues[1].trim())
    }

    /** `HH:MM:SS` → 秒 */
    fun parseClock(v: String?): Long {
        if (v.isNullOrBlank()) return 0
        val p = v.split(':')
        if (p.size != 3) return 0
        return (p[0].toLongOrNull() ?: 0) * 3600 + (p[1].toLongOrNull() ?: 0) * 60 +
            (p[2].substringBefore('.').toLongOrNull() ?: 0)
    }

    fun clock(sec: Long): String {
        val v = sec.coerceAtLeast(0)
        return "${(v / 3600).toString().padStart(2, '0')}:" +
            "${((v % 3600) / 60).toString().padStart(2, '0')}:" +
            "${(v % 60).toString().padStart(2, '0')}"
    }

    private fun envelope(action: String, inner: String): String = """
        <?xml version="1.0" encoding="utf-8"?>
        <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
          <s:Body><u:${action}Response xmlns:u="$SERVICE">$inner</u:${action}Response></s:Body>
        </s:Envelope>
    """.trimIndent()

    fun setUriResponse(): String = envelope("SetAVTransportURI", "")
    fun playResponse(): String = envelope("Play", "")
    fun pauseResponse(): String = envelope("Pause", "")
    fun stopResponse(): String = envelope("Stop", "")
    fun seekResponse(): String = envelope("Seek", "")

    fun transportInfoResponse(state: String, speed: String = "1"): String = envelope(
        "GetTransportInfo",
        "<CurrentTransportState>${esc(state)}</CurrentTransportState>" +
            "<CurrentTransportStatus>OK</CurrentTransportStatus>" +
            "<CurrentSpeed>$speed</CurrentSpeed>",
    )

    fun positionInfoResponse(
        uri: String,
        relSec: Long,
        durSec: Long,
    ): String = envelope(
        "GetPositionInfo",
        "<Track>1</Track><TrackDuration>${clock(durSec)}</TrackDuration>" +
            "<TrackMetaData></TrackMetaData><TrackURI>${esc(uri)}</TrackURI>" +
            "<RelTime>${clock(relSec)}</RelTime><AbsTime>${clock(relSec)}</AbsTime><RelCount>0</RelCount>",
    )

    fun error(code: Int, desc: String): String = """
        <?xml version="1.0" encoding="utf-8"?>
        <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/">
          <s:Body><s:Fault>
            <faultcode>s:Client</faultcode><faultstring>UPnPError</faultstring>
            <detail><UPnPError xmlns="urn:schemas-upnp-org:control-1-0">
              <errorCode>$code</errorCode><errorDescription>${esc(desc)}</errorDescription>
            </UPnPError></detail>
          </s:Fault></s:Body>
        </s:Envelope>
    """.trimIndent()

    fun esc(s: String): String = s
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;").replace("'", "&apos;")

    fun unesc(s: String): String = s
        .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
        .replace("&apos;", "'").replace("&amp;", "&")

    /** SSDP 搜索的应答报文（手机靠 LOCATION 来拉描述） */
    fun ssdpResponse(location: String, uuid: String): String = buildString {
        append("HTTP/1.1 200 OK\r\n")
        append("CACHE-CONTROL: max-age=1800\r\n")
        append("EXT:\r\n")
        append("LOCATION: ").append(location).append("\r\n")
        append("SERVER: Android/1.0 UPnP/1.0 ProjectionRoom/1.0\r\n")
        append("ST: ").append(DEVICE_TYPE).append("\r\n")
        append("USN: ").append(uuid).append("::").append(DEVICE_TYPE).append("\r\n")
        append("\r\n")
    }

    /** 收到的报文是不是在找 MediaRenderer（手机也可能找 rootdevice） */
    fun matchesSearch(request: String): Boolean {
        if (!request.startsWith("M-SEARCH", ignoreCase = true)) return false
        val st = Regex("(?im)^ST:\\s*(.+)$").find(request)?.groupValues?.get(1)?.trim()
            ?: Regex("(?im)^MAN:\\s*(.+)$").find(request)?.groupValues?.get(1)?.trim()
        return st == null || st.contains("MediaRenderer", true) || st == "ssdp:all" ||
            st.contains("rootdevice", true)
    }
}
