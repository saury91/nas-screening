package com.projectionroom.shared.cast

import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 用真正的 XML 解析器校验我们生成的 SOAP 报文。
 *
 * 字符串断言只能证明"文本里有这段"，证明不了"设备能解析"。
 * 电视端拿到坏 XML 的反应是 500 或者直接装死，很难排查 ——
 * 所以在测试里先按标准 XML 解析一遍。
 */
class SoapXmlTest {

    private fun parse(xml: String) =
        DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder()
            .parse(xml.byteInputStream())

    @Test
    fun `SetAVTransportURI 是合法 XML 且能取到 URI`() {
        val body = AvTransport.setUri("http://nas:8899/api/proxy?u=http%3A%2F%2Fx%2Fa.m3u8", "繁花 第1集 & 花絮")
        val doc = parse(body)
        val uris = doc.getElementsByTagName("CurrentURI")
        assertEquals(1, uris.length)
        assertEquals(
            "http://nas:8899/api/proxy?u=http%3A%2F%2Fx%2Fa.m3u8",
            uris.item(0).textContent,
        )
        // 元数据是嵌套 XML，解析后应能再解析一次，并取到标题
        val meta = doc.getElementsByTagName("CurrentURIMetaData").item(0).textContent
        val inner = parse(meta)
        assertEquals("繁花 第1集 & 花絮", inner.getElementsByTagName("dc:title").item(0).textContent)
    }

    @Test
    fun `Play Pause Stop Seek 结构正确`() {
        listOf(
            AvTransport.play() to "Play",
            AvTransport.pause() to "Pause",
            AvTransport.stop() to "Stop",
            AvTransport.seek(83) to "Seek",
        ).forEach { (xml, action) ->
            val doc = parse(xml)
            val nodes = doc.getElementsByTagName("u:$action")
            assertEquals(1, nodes.length, "$action 节点缺失")
            // InstanceID 必须是 0：多实例的设备才用别的值，我们只推一路
            val body = doc.getElementsByTagName("InstanceID").item(0).textContent
            assertEquals("0", body)
        }
    }

    @Test
    fun `信封命名空间不能丢`() {
        val doc = parse(AvTransport.setUri("http://a/b.mp4", "x"))
        val env = doc.documentElement
        assertEquals("Envelope", env.localName)
        assertEquals("http://schemas.xmlsoap.org/soap/envelope/", env.namespaceURI)
        // 动作元素的命名空间必须是 AVTransport，写错设备会回 401 Invalid Action
        val action = doc.getElementsByTagName("u:SetAVTransportURI").item(0)
        assertEquals("urn:schemas-upnp-org:service:AVTransport:1", action.namespaceURI)
    }

    @Test
    fun `特殊字符不会破坏报文`() {
        val nasty = "a<b>&\"'c"
        val body = AvTransport.setUri("http://a/b?x=1&y=2", nasty)
        parse(body)   // 能解析就说明转义没漏
        assertTrue(body.contains("&amp;"))
    }
}
