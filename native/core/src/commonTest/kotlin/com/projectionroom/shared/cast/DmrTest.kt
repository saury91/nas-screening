package com.projectionroom.shared.cast

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DmrTest {

    @Test
    fun `设备描述里必须有 AVTransport 与控制地址`() {
        val xml = Dmr.description("客厅电视 & <测试>", "uuid:abc")
        assertTrue(xml.contains("urn:schemas-upnp-org:device:MediaRenderer:1"))
        assertTrue(xml.contains("<controlURL>/control</controlURL>"))
        // 名字里的特殊字符要转义，否则描述文件不是合法 XML
        assertTrue(xml.contains("客厅电视 &amp; &lt;测试&gt;"))
        assertFalse(xml.contains("<测试>"))
    }

    @Test
    fun `SSDP 应答带 LOCATION 与 USN`() {
        val r = Dmr.ssdpResponse("http://192.0.2.5:49520/description.xml", "uuid:abc")
        assertTrue(r.startsWith("HTTP/1.1 200 OK"))
        assertTrue(r.contains("LOCATION: http://192.0.2.5:49520/description.xml"))
        assertTrue(r.contains("USN: uuid:abc::urn:schemas-upnp-org:device:MediaRenderer:1"))
        assertTrue(r.endsWith("\r\n\r\n"))
    }

    @Test
    fun `只回应 MediaRenderer 的搜索`() {
        val search = "M-SEARCH * HTTP/1.1\r\nST: urn:schemas-upnp-org:device:MediaRenderer:1\r\n\r\n"
        assertTrue(Dmr.matchesSearch(search))
        assertTrue(Dmr.matchesSearch("M-SEARCH * HTTP/1.1\r\nST: ssdp:all\r\n\r\n"))
        assertFalse(Dmr.matchesSearch("NOTIFY * HTTP/1.1\r\nNT: upnp:event\r\n\r\n"))
        assertFalse(Dmr.matchesSearch("M-SEARCH * HTTP/1.1\r\nST: urn:schemas-upnp-org:device:MediaServer:1\r\n\r\n"))
    }

    @Test
    fun `解析 SOAPACTION 与参数`() {
        assertEquals("SetAVTransportURI", Dmr.actionOf("\"urn:schemas-upnp-org:service:AVTransport:1#SetAVTransportURI\""))
        assertEquals("Play", Dmr.actionOf("urn:schemas-upnp-org:service:AVTransport:1#Play"))
        assertEquals("", Dmr.actionOf(null))

        val body = "<CurrentURI>http://a/b?x=1&amp;y=2</CurrentURI>"
        assertEquals("http://a/b?x=1&y=2", Dmr.arg(body, "CurrentURI"))
        assertNull(Dmr.arg(body, "Target"))
    }

    @Test
    fun `状态与位置响应结构正确`() {
        val t = Dmr.transportInfoResponse("PAUSED_PLAYBACK")
        assertTrue(t.contains("<CurrentTransportState>PAUSED_PLAYBACK</CurrentTransportState>"))
        assertTrue(t.contains("GetTransportInfoResponse"))

        val p = Dmr.positionInfoResponse("http://a/b.m3u8", 83, 3600)
        assertTrue(p.contains("<RelTime>00:01:23</RelTime>"))
        assertTrue(p.contains("<TrackDuration>01:00:00</TrackDuration>"))
        assertTrue(p.contains("<TrackURI>http://a/b.m3u8</TrackURI>"))
    }

    @Test
    fun `时钟互转`() {
        assertEquals(83L, Dmr.parseClock("00:01:23"))
        assertEquals(0L, Dmr.parseClock("NOT_IMPLEMENTED"))
        assertEquals("00:01:23", Dmr.clock(83))
    }
}
