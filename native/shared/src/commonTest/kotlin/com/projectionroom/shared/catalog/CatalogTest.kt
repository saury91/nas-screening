package com.projectionroom.shared.catalog

import com.projectionroom.shared.model.Quality
import com.projectionroom.shared.model.Source
import com.projectionroom.shared.net.Http
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** 全部离线：网络由 [FakeHttp] 顶替，跑起来 0.1 秒。 */
class CatalogTest {

    private class FakeHttp(private val routes: Map<String, String>) : Http {
        override suspend fun get(url: String, referer: String?): String {
            routes.entries.firstOrNull { url.contains(it.key) }?.let { return it.value }
            throw IllegalStateException("没有为这个地址准备响应：$url")
        }
    }

    // ── 片名归一化 ──────────────────────────────────────────────

    @Test
    fun `不同写法的同名片会合成一条`() {
        val keys = listOf("沙丘", "沙丘 Dune", "沙 丘", "沙丘(2021)").map { Catalog.normKey(it) }.toSet()
        assertEquals(1, keys.size, "应该归一成同一个 key：$keys")
    }

    @Test
    fun `年份是剥掉而不是全删`() {
        assertEquals("沙丘", Catalog.normKey("沙丘1984"))
        assertEquals("沙丘", Catalog.normKey("沙丘2021"))
        // 剥掉年份后两者相同才对 —— 关键是不能靠年份区分，要靠合并时的 year 字段
        assertEquals(Catalog.normKey("沙丘1984"), Catalog.normKey("沙丘2021"))
    }

    // ── 播放地址 ────────────────────────────────────────────────

    @Test
    fun `标准格式按井号切集`() {
        val eps = MacCms.parsePlayUrl("第01集\$https://a/1.m3u8#第02集\$https://a/2.m3u8")
        assertEquals(2, eps.size)
        assertEquals("第01集", eps[0].name)
        assertEquals("https://a/2.m3u8", eps[1].url)
    }

    @Test
    fun `没有集名时按顺序补`() {
        val eps = MacCms.parsePlayUrl("https://a/1.m3u8#https://a/2.m3u8")
        assertEquals(listOf("第1集", "第2集"), eps.map { it.name })
    }

    @Test
    fun `非 http 的段会被丢掉`() {
        val eps = MacCms.parsePlayUrl("第01集\$https://a/1.m3u8#第02集\$ftp://bad")
        assertEquals(1, eps.size)
    }

    @Test
    fun `美元符号分隔的是线路不是集`() {
        // 一条记录里塞两条线路：以前会被摊平成 4 集，选集面板里全是重复
        val raw = "第01集\$https://a/1.m3u8#第02集\$https://a/2.m3u8" +
            "\$\$\$第01集\$https://b/1.m3u8#第02集\$https://b/2.m3u8"
        val lines = MacCms.parseLines(raw)
        assertEquals(2, lines.size, "应该是两条线路")
        assertEquals(2, lines[0].size)
        assertEquals("https://b/1.m3u8", lines[1][0].url)
    }

    @Test
    fun `空串不会炸`() {
        assertTrue(MacCms.parsePlayUrl("").isEmpty())
    }

    // ── 画质 ────────────────────────────────────────────────────

    @Test
    fun `画质按优先级分档`() {
        assertEquals("4K", Quality.from("4K 60fps"))
        assertEquals("1080P", Quality.from("蓝光原盘"))
        assertEquals("720P", Quality.from("720P 高清"))
        assertEquals("HD", Quality.from("HD中字"))
    }

    @Test
    fun `域名里的 sd 不会被误判`() {
        // sd.example 曾是真实踩过的坑
        assertEquals("", Quality.from("http://sd.example/api.php/provide/vod"))
    }

    @Test
    fun `路径里的分辨率能被认出来`() {
        assertEquals("1080P", Quality.from("https://x.com/2025/1080/index.m3u8"))
    }

    // ── 跨源合并 ────────────────────────────────────────────────

    @Test
    fun `本地详情不用 NAS 也能凑齐线路`() = runTest {
        val http = FakeHttp(
            mapOf(
                "a.com" to """{"list":[{"vod_name":"沙丘","vod_year":"1984","vod_play_url":"第1集${'$'}https://a/1984.m3u8"}]}""",
                "b.com" to """{"list":[{"vod_name":"沙丘","vod_year":"2021","vod_play_url":"第1集${'$'}https://b/2021.m3u8"}]}""",
            )
        )
        val cat = Catalog(http)
        val srcs = listOf(Source("A", "https://a.com/api"), Source("B", "https://b.com/api"))
        val hit = cat.detail("沙丘", "2021", srcs)
        assertNotNull(hit)
        // 同名合并（和服务端一致）：两条线路都要在，播放地址不能互相覆盖
        assertEquals(2, hit.lines.size)
        assertTrue(hit.lines.any { it.episodes.any { e -> e.url.contains("a/1984") } })
        assertTrue(hit.lines.any { it.episodes.any { e -> e.url.contains("b/2021") } })
    }

    @Test
    fun `两个源的同名片合成一条并保留两条线路`() = runTest {
        val http = FakeHttp(
            mapOf(
                "site-a" to """{"code":1,"list":[{"vod_name":"沙丘","vod_year":"2021",
                    "vod_remarks":"1080P","vod_play_url":"第01集${'$'}https://a/1.m3u8"}]}""",
                "site-b" to """{"data":{"list":[{"vod_name":"沙丘 Dune","vod_year":"2021",
                    "vod_remarks":"HD中字","vod_play_url":"第01集${'$'}https://b/1.m3u8#第02集${'$'}https://b/2.m3u8"}]}}""",
            )
        )
        val sources = listOf(
            Source("A站", "http://site-a/api", 1),
            Source("B站", "http://site-b/api", 1),
        )

        val result = Catalog(http).search("沙丘", sources)

        assertEquals(1, result.size, "同名应该合并成一条")
        val vod = result.first()
        assertEquals(2, vod.lines.size, "两条线路都要保留")
        assertEquals("1080P", vod.lines[0].quality, "画质高的排前面")
        assertEquals("HD", vod.lines[1].quality)
        assertTrue(vod.episodeCount >= 2)
    }

    @Test
    fun `片名不沾边的结果会被过滤`() = runTest {
        val http = FakeHttp(
            mapOf(
                "site-a" to """{"list":[{"vod_name":"沙丘2","vod_play_url":"第01集${'$'}https://a/1.m3u8"},
                    {"vod_name":"完全无关的片","vod_play_url":"第01集${'$'}https://a/9.m3u8"}]}"""
            )
        )
        val result = Catalog(http).search("沙丘", listOf(Source("A", "http://site-a/api")))
        assertEquals(1, result.size)
        assertNotNull(result.firstOrNull { it.name == "沙丘2" })
        assertTrue(result.none { it.name == "完全无关的片" })
    }

    @Test
    fun `单个源报错不影响其它源`() = runTest {
        val http = FakeHttp(
            mapOf("good" to """{"list":[{"vod_name":"沙丘","vod_play_url":"第01集${'$'}https://a/1.m3u8"}]}""")
        )
        val result = Catalog(http).search(
            "沙丘",
            listOf(Source("坏的", "http://dead/api"), Source("好的", "http://good/api")),
        )
        assertEquals(1, result.size)
        assertEquals("好的", result.first().lines.first().site)
    }

    /**
     * 片库 slug 必须先翻成中文再匹配 `cats`。
     *
     * 不翻的症状是「看起来正常，其实全靠 NAS」：本地一条都匹配不到 → 返回空 →
     * 静默回落到 NAS。NAS 一关片库就空，而首页（走 LocalHome，用的就是中文）却是好的，
     * 极难从现象上定位。
     */
    @Test
    fun `片库 slug 能翻成配置包里的中文分类`() {
        val src = Source(
            name = "暴风资源",
            api = "https://example.com/api.php/provide/vod/",
            cats = mapOf("20" to "电影", "21" to "剧集"),
        )
        assertEquals("电影", Cats.nameOf("movie"))
        assertEquals("纪录片", Cats.nameOf("doc"))
        assertEquals("电影", Cats.nameOf("电影"), "已经是中文就原样返回")
        assertEquals(listOf("20"), src.typeIdsOf(Cats.nameOf("movie")))
        assertTrue(src.typeIdsOf("movie").isEmpty(), "拿 slug 直接当分类名是匹配不到的")
    }
}

class LrcTest {

    @kotlin.test.Test
    fun `解析普通歌词`() {
        val raw = """
            [ti:测试]
            [ar:某人]
            [00:01.00]第一行
            [00:12.50]第二行
            [01:03.123]第三行
        """.trimIndent()
        val lines = com.projectionroom.shared.catalog.Lrc.parse(raw)
        kotlin.test.assertEquals(3, lines.size, "元数据行不该被当成歌词")
        kotlin.test.assertEquals(1_000L, lines[0].timeMs)
        kotlin.test.assertEquals("第一行", lines[0].text)
        kotlin.test.assertEquals(12_500L, lines[1].timeMs)
        kotlin.test.assertEquals(63_123L, lines[2].timeMs)
    }

    @kotlin.test.Test
    fun `一行多个时间标签会展开`() {
        val lines = com.projectionroom.shared.catalog.Lrc.parse("[00:10.00][01:00.00]副歌")
        kotlin.test.assertEquals(2, lines.size)
        kotlin.test.assertEquals(10_000L, lines[0].timeMs)
        kotlin.test.assertEquals(60_000L, lines[1].timeMs)
        kotlin.test.assertTrue(lines.all { it.text == "副歌" })
    }

    @kotlin.test.Test
    fun `没有时间标签的行全部忽略`() {
        kotlin.test.assertTrue(com.projectionroom.shared.catalog.Lrc.parse("纯文本\n[by:某人]").isEmpty())
        kotlin.test.assertTrue(com.projectionroom.shared.catalog.Lrc.parse("").isEmpty())
    }

    @kotlin.test.Test
    fun `当前行按时间取`() {
        val lines = com.projectionroom.shared.catalog.Lrc.parse(
            "[00:01.00]a\n[00:05.00]b\n[00:09.00]c"
        )
        kotlin.test.assertEquals(-1, com.projectionroom.shared.catalog.Lrc.indexAt(lines, 500))
        kotlin.test.assertEquals(0, com.projectionroom.shared.catalog.Lrc.indexAt(lines, 1_000))
        kotlin.test.assertEquals(1, com.projectionroom.shared.catalog.Lrc.indexAt(lines, 7_000))
        kotlin.test.assertEquals(2, com.projectionroom.shared.catalog.Lrc.indexAt(lines, 60_000))
    }
}
