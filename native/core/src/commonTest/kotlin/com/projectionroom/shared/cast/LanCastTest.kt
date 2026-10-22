package com.projectionroom.shared.cast

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LanCastTest {

    @Test
    fun `发现报文只认本协议`() {
        assertTrue(LanCast.isDiscover("PROOM-CAST/1 DISCOVER"))
        assertTrue(LanCast.isDiscover("  PROOM-CAST/1 DISCOVER\r\n"))
        // SSDP 的 M-SEARCH 也会广播到同一个网段，不能被当成我们的发现请求
        assertFalse(LanCast.isDiscover("M-SEARCH * HTTP/1.1"))
    }

    @Test
    fun `offer 往返`() {
        val line = LanCast.offerLine(
            LanCast.offer(
                id = "tv-1",
                name = "客厅电视",
                title = "繁花",
                playing = true,
                position = 61_000,
                duration = 100_000,
            ),
        )
        val o = assertNotNull(LanCast.parseOffer(line))
        assertEquals("tv-1", o.id)
        assertEquals("客厅电视", o.name)
        assertEquals(LanCast.PORT, o.port)
        assertEquals("繁花", o.title)
        assertTrue(o.playing)
        assertEquals(61_000, o.position)
    }

    @Test
    fun `别的 JSON 和残缺报文不算设备`() {
        assertNull(LanCast.parseOffer("""{"ok":true}"""))
        assertNull(LanCast.parseOffer("garbage"))
        // 协议名不对：可能是别人做的同名端口服务
        assertNull(LanCast.parseOffer("""{"proom":"OTHER/1","port":49521}"""))
        assertNull(LanCast.parseOffer("""{"proom":"PROOM-CAST/1","port":0}"""))
    }

    @Test
    fun `指令里的请求头和集数不能丢`() {
        val body = LanCast.cmdBody(
            CastApi.Command(
                kind = "play",
                url = "https://cdn/a.m3u8",
                title = "繁花",
                headers = mapOf("User-Agent" to "UA", "Referer" to "https://cdn.example.com/"),
                vod = "繁花",
                line = 1,
                ep = 7,
                episode = "第 8 集",
            ),
        )
        val cmd = assertNotNull(LanCast.parseCommand(body))
        assertEquals("play", cmd.kind)
        assertEquals("UA", cmd.headers["User-Agent"])
        assertEquals(1, cmd.line)
        assertEquals(7, cmd.ep)
        assertEquals("第 8 集", cmd.episode)
        // 没动的参数保持哨兵值，电视端才知道「这条指令不改倍速」
        assertEquals(CastApi.NO_CHANGE, cmd.rate)
        assertEquals(CastApi.NO_CHANGE, cmd.volume)
    }

    @Test
    fun `状态往返`() {
        val st = CastApi.State(
            url = "http://v/a.mp4",
            title = "繁花",
            position = 1_000,
            duration = 9_000,
            playing = true,
            rate = 1.5f,
            volume = 0.4f,
            line = 2,
            ep = 3,
            episode = "第 4 集",
        )
        val back = assertNotNull(LanCast.parseState(LanCast.stateBody(st)))
        assertEquals(st, back)
        // 没在播时 state 为 null，手机端按「离线/空闲」处理
        assertNull(LanCast.parseState(LanCast.stateBody(null)))
    }

    @Test
    fun `info 能被手机识别`() {
        val info = assertNotNull(
            LanCast.parseInfo(LanCast.infoBody("tv-9", "卧室电视", CastApi.State(title = "x"))),
        )
        assertEquals("tv-9", info.id)
        assertEquals("卧室电视", info.name)
        assertEquals(LanCast.VERSION, info.version)
        assertEquals("x", info.state?.title)
    }

    @Test
    fun `手填地址的三种写法`() {
        assertEquals("http://192.0.2.8:49521", LanCast.normalizeBase("192.0.2.8"))
        assertEquals("http://192.0.2.8:14953", LanCast.normalizeBase("192.0.2.8:14953"))
        assertEquals("http://192.0.2.8:49521", LanCast.normalizeBase("proom://192.0.2.8/"))
        assertEquals("http://192.0.2.8:49521", LanCast.normalizeBase(" http://192.0.2.8:49521/proom/info "))
        // 端口写错就是写错，不能猜一个默认端口去连别的东西
        assertNull(LanCast.normalizeBase("192.0.2.8:abc"))
        assertNull(LanCast.normalizeBase("192.0.2.8:70000"))
        assertNull(LanCast.normalizeBase(""))
    }

    @Test
    fun `扫码投屏：配对码也能当投屏码用`() {
        // 电视「显示配对二维码」那张：port 是配对服务 8898，不是控制口
        val c1 = assertNotNull(LanCast.codeOf("proom://pair?host=192.0.2.8&port=8898&token=1234"))
        assertEquals("http://192.0.2.8:${LanCast.PORT}", c1.base)
    }

    @Test
    fun `扫码投屏：专用投屏码带名字和端口`() {
        val c = assertNotNull(LanCast.codeOf("proom://cast?host=192.0.2.9&port=14953&name=客厅电视"))
        assertEquals("http://192.0.2.9:14953", c.base)
        assertEquals("客厅电视", c.name)
        // 端口写坏就回落默认口，不要拿一个非法端口去连
        assertEquals(
            "http://192.0.2.9:${LanCast.PORT}",
            assertNotNull(LanCast.codeOf("proom://cast?host=192.0.2.9&port=0")).base,
        )
    }

    @Test
    fun `扫码投屏：别的二维码不认`() {
        // 纯地址码也认，用户可能用别的工具生成了一张地址码
        assertEquals(
            "http://192.0.2.8:${LanCast.PORT}",
            assertNotNull(LanCast.codeOf("192.0.2.8")).base,
        )
        assertNull(LanCast.codeOf("https://example.com/abc"))
        // 没有 host 的 proom 码是坏码，不能猜
        assertNull(LanCast.codeOf("proom://pair?port=8898&token=1234"))
    }

    @Test
    fun `offer 拼出请求基址`() {
        val d = LanCast.offerDevice(
            LanCast.offer(
                id = "tv-1",
                name = "电视",
                title = "",
                playing = false,
                position = 0,
                duration = 0,
                port = 14953,
            ),
            "10.0.2.2",
        )
        assertEquals("http://10.0.2.2:14953", d.base)
        assertEquals("电视", d.name)
    }
}
