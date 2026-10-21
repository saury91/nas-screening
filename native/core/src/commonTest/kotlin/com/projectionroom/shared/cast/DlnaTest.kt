package com.projectionroom.shared.cast

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DlnaTest {

    @Test
    fun `ssdp 报文符合协议要求`() {
        val req = Ssdp.mSearch()
        assertTrue(req.startsWith("M-SEARCH * HTTP/1.1"))
        assertTrue(req.contains("HOST: 239.255.255.250:1900"))
        // 双引号不能少，少了设备直接忽略
        assertTrue(req.contains("MAN: \"ssdp:discover\""))
        assertTrue(req.contains("ST: urn:schemas-upnp-org:device:MediaRenderer:1"))
        assertTrue(req.endsWith("\r\n\r\n"))
    }

    @Test
    fun `从响应里取 LOCATION`() {
        val resp = """
            HTTP/1.1 200 OK
            CACHE-CONTROL: max-age=1800
            LOCATION: http://192.168.1.8:49152/description.xml
            ST: urn:schemas-upnp-org:device:MediaRenderer:1

        """.trimIndent()
        assertEquals("http://192.168.1.8:49152/description.xml", Ssdp.locationOf(resp))
    }

    @Test
    fun `非 200 响应返回空`() {
        assertNull(Ssdp.locationOf("HTTP/1.1 404 Not Found\r\nLOCATION: x"))
        assertNull(Ssdp.locationOf("HTTP/1.1 200 OK\r\nST: xxx"))
    }

    private val desc = """
        <?xml version="1.0"?>
        <root xmlns="urn:schemas-upnp-org:device-1-0">
          <device>
            <deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</deviceType>
            <friendlyName>客厅电视</friendlyName>
            <modelName>MiTV-4A</modelName>
            <UDN>uuid:1234-abcd</UDN>
            <serviceList>
              <service>
                <serviceType>urn:schemas-upnp-org:service:ContentDirectory:1</serviceType>
                <controlURL>/upnp/control/CD</controlURL>
              </service>
              <service>
                <serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType>
                <controlURL>/upnp/control/AVTransport1</controlURL>
              </service>
            </serviceList>
          </device>
        </root>
    """.trimIndent()

    @Test
    fun `解析设备描述时取 AVTransport 而不是别的服务`() {
        val d = UpnpDesc.parse(desc, "http://192.168.1.8:49152/description.xml")!!
        assertEquals("客厅电视", d.name)
        assertEquals("MiTV-4A", d.model)
        assertEquals("uuid:1234-abcd", d.udn)
        // 关键：不能拿到 ContentDirectory 的 /upnp/control/CD
        assertEquals("http://192.168.1.8:49152/upnp/control/AVTransport1", d.controlUrl)
        assertTrue(d.canCast)
    }

    @Test
    fun `没有 AVTransport 的设备不能投屏`() {
        val onlyCd = desc.replace("AVTransport", "ContentDirectory")
        val d = UpnpDesc.parse(onlyCd, "http://1.2.3.4/x.xml")!!
        assertTrue(d.controlUrl.isBlank())
        assertTrue(!d.canCast)
    }

    @Test
    fun `相对地址补全`() {
        assertEquals("http://1.2.3.4:80/a/b", UpnpDesc.resolve("http://1.2.3.4:80/desc.xml", "/a/b"))
        assertEquals("http://1.2.3.4:80/a/b", UpnpDesc.resolve("http://1.2.3.4:80/desc.xml", "a/b"))
        assertEquals("http://x/y", UpnpDesc.resolve("http://1.2.3.4/desc.xml", "http://x/y"))
    }

    @Test
    fun `SetAVTransportURI 的 XML 转义`() {
        val body = AvTransport.setUri("http://nas/v?a=1&b=2", "繁花 & 结局")
        assertTrue(body.contains("http://nas/v?a=1&amp;b=2"))
        // 元数据是「XML 套 XML」，所以标题在正文里是 &amp;amp;：
        // 设备先拆一层信封得到 <dc:title>繁花 &amp; 结局</dc:title>，再按 XML 解析才是原文
        assertTrue(body.contains("繁花 &amp;amp; 结局"))
        // 元数据整体又被转义一层
        assertTrue(body.contains("&lt;DIDL-Lite"))
        assertTrue(body.contains("SetAVTransportURI"))
        // 命名空间必须带等号与引号（曾经漏掉，是 SoapXmlTest 用真解析器抓出来的）
        assertTrue(body.contains("xmlns:u=\"urn:schemas-upnp-org:service:AVTransport:1\""))
    }

    @Test
    fun `进度与状态解析`() {
        assertEquals("PLAYING", AvTransport.parseState("<CurrentTransportState>PLAYING</CurrentTransportState>"))
        assertEquals("PAUSED_PLAYBACK", AvTransport.parseState("<CurrentTransportState>PAUSED_PLAYBACK</CurrentTransportState>"))
        assertEquals(83L, AvTransport.parseRelTime("<RelTime>00:01:23</RelTime>"))
        assertEquals(3723L, AvTransport.parseRelTime("<RelTime>01:02:03.456</RelTime>"))
        assertEquals(-1L, AvTransport.parseRelTime("<RelTime>NOT_IMPLEMENTED</RelTime>"))
    }

    @Test
    fun `seek 的时钟格式`() {
        assertEquals("00:01:23", AvTransport.clock(83))
        assertEquals("01:00:00", AvTransport.clock(3600))
        assertEquals("00:00:00", AvTransport.clock(-5))
        assertTrue(AvTransport.seek(83).contains("<Target>00:01:23</Target>"))
    }

    @Test
    fun `protocolInfo 按地址猜类型`() {
        // 以前写死 video/mp4，实际推的多半是 m3u8：严格的接收端照着选解码器，
        // 就成了「连得上、放不出来」
        assertEquals("application/vnd.apple.mpegurl", AvTransport.mimeOf("http://a/b.m3u8"))
        assertEquals("application/vnd.apple.mpegurl", AvTransport.mimeOf("http://a/b.m3u8?token=1&x=2"))
        assertEquals("video/mp4", AvTransport.mimeOf("http://a/b.mp4?sign=abc"))
        assertEquals("application/dash+xml", AvTransport.mimeOf("http://a/b.mpd"))
        assertEquals("video/mp4", AvTransport.mimeOf("http://a/noext"))
        assertTrue(AvTransport.setUri("http://a/b.m3u8", "x").contains("application/vnd.apple.mpegurl"))
    }

    @Test
    fun `推流方的请求头能原样传到接收端`() {
        // 采集站校验 Referer/UA：手机上带了才播得动，电视端以前裸拉就是 403。
        // 头藏在 DIDL 的 x-proom-* 属性里，走「XML 套 XML」两层转义
        val body = AvTransport.setUri(
            "https://cdn.example.com/a/b.m3u8",
            "繁花",
            mapOf(
                "User-Agent" to "Mozilla/5.0 & more",
                "Referer" to "https://cdn.example.com/",
            ),
        )
        // 接收端就是这么拆的：先剥一层信封，再从 DIDL 里读属性
        val didl = Dmr.arg(body, "CurrentURIMetaData")!!
        val h = Dmr.headersOf(didl)
        assertEquals("Mozilla/5.0 & more", h["User-Agent"])
        assertEquals("https://cdn.example.com/", h["Referer"])
    }

    @Test
    fun `没有头时不会凭空造出来`() {
        val didl = Dmr.arg(AvTransport.setUri("http://a/b.mp4", "x"), "CurrentURIMetaData")!!
        assertTrue(Dmr.headersOf(didl).isEmpty())
    }

    @Test
    fun `认得自家的电视`() {
        val own = desc
            .replace("<modelName>MiTV-4A</modelName>", "<modelName>放映室接收端</modelName>")
            .replace("<UDN>", "<manufacturer>Projection Room</manufacturer><UDN>")
        assertTrue(UpnpDesc.parse(own, "http://1.2.3.4/x.xml")!!.isOwn)
        // 别人家的电视不能误判成自家的（会影响置顶和「支持直投」的标注）
        assertTrue(!UpnpDesc.parse(desc, "http://1.2.3.4/x.xml")!!.isOwn)
    }
}
