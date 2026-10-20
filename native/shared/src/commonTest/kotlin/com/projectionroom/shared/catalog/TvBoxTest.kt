package com.projectionroom.shared.catalog

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TvBoxTest {

    @Test
    fun `只收能直连的点播站`() {
        val cfg = TvBox.parse(
            """
            {
              "sites": [
                {"key":"a","name":"站点A","type":1,"api":"https://a.example/api.php/provide/vod/"},
                {"key":"b","name":"需要解析器","type":1,"api":"csp_AppYsV2"},
                {"key":"c","name":"直播站","type":3,"api":"https://c.example/live"},
                {"key":"d","name":"不参与搜索","type":1,"searchable":0,"api":"https://d.example/vod"}
              ]
            }
            """.trimIndent()
        )!!
        val srcs = TvBox.sources(cfg)
        assertEquals(1, srcs.size, "带私有解析器的、非点播的、关搜索的都不该收进来")
        assertEquals("站点A", srcs[0].name)
        assertEquals(1, srcs[0].type)
        assertTrue(srcs[0].cats.isEmpty(), "分类映射要另外探，导入时先留空")
    }

    @Test
    fun `直播源的多个地址按井号拆开`() {
        val cfg = TvBox.parse(
            """{"lives":[{"name":"央视","type":0,"url":"http://a.m3u8#http://b.m3u8"},
                        {"name":"空源","type":0,"url":"proxy://do=live"}]}"""
        )!!
        val lives = TvBox.lives(cfg)
        assertEquals(1, lives.size, "非 http 的私有协议源要丢掉")
        assertEquals(listOf("http://a.m3u8", "http://b.m3u8"), lives[0].second)
    }

    @Test
    fun `txt 直播列表按分组归类`() {
        val raw = """
            央视,#genre#
            CCTV1,http://a.m3u8#http://b.m3u8
            CCTV2,http://c.m3u8
            卫视,#genre#
            湖南卫视,http://d.m3u8
        """.trimIndent()
        val chs = LiveList.parse(raw)
        assertEquals(3, chs.size)
        assertEquals("央视", chs[0].group)
        assertEquals("CCTV1", chs[0].name)
        assertEquals(2, chs[0].urls.size, "同一个台的多条线路要保留")
        assertEquals("卫视", chs[2].group)
    }

    @Test
    fun `m3u 取 group-title 与台名`() {
        val raw = """
            #EXTM3U
            #EXTINF:-1 tvg-name="CCTV1" group-title="央视",CCTV1 综合
            http://a.m3u8
            #EXTINF:-1 tvg-name="湖南卫视",湖南卫视
            http://b.m3u8
        """.trimIndent()
        val chs = LiveList.parse(raw)
        assertEquals(2, chs.size)
        assertEquals("央视", chs[0].group)
        assertEquals("CCTV1 综合", chs[0].name)
        assertEquals("", chs[1].group)
        assertEquals("http://b.m3u8", chs[1].urls.first())
    }

    @Test
    fun `分类归一和服务端一套规则`() {
        assertEquals("电影", Cats.of("动作片"))
        assertEquals("电影", Cats.of("剧情片"), "「剧情片」里有个剧字，不能被归成剧集")
        assertEquals("剧集", Cats.of("国产剧"))
        assertEquals("动漫", Cats.of("日韩动漫"))
        assertEquals("综艺", Cats.of("大陆综艺"))
        assertEquals("纪录片", Cats.of("记录片"), "站里两种写法都有")
        assertEquals("", Cats.of(""), "空分类名不要硬塞进某一类")
    }
}
