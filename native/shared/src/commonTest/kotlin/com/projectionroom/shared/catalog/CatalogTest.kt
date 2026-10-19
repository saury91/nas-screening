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
        // sdzyapi.com 曾是真实踩过的坑
        assertEquals("", Quality.from("http://sdzyapi.com/api.php/provide/vod"))
    }

    @Test
    fun `路径里的分辨率能被认出来`() {
        assertEquals("1080P", Quality.from("https://x.com/2025/1080/index.m3u8"))
    }

    // ── 跨源合并 ────────────────────────────────────────────────

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
}
