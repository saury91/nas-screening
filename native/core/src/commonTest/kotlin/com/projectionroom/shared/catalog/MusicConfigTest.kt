package com.projectionroom.shared.catalog

import com.projectionroom.shared.net.Http
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 音乐聚合接口「可被配置包覆盖」这条链路的回归测试。
 *
 * 背景：接口地址原本是 APK 里的常量，NAS 无法改 —— 服务换域名或被封只能发版。
 * 现在 `bundle.music` 下发，缺省才回落到内置默认。
 */
class MusicConfigTest {

    /** 只记最后一次请求，够断言；[len] 模拟 HEAD 拿到的内容长度（0 = 拿不到） */
    private class RecHttp(private val body: String = "[]", private val len: Long = 0L) : Http {
        var lastUrl: String = ""
        var lastReferer: String? = null
        var calls: Int = 0
        override suspend fun get(url: String, referer: String?): String {
            calls++
            lastUrl = url
            lastReferer = referer
            return body
        }

        override suspend fun contentLength(url: String, referer: String?): Long = len
    }

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    @Test
    fun `配置包能带音乐接口地址`() {
        val b = json.decodeFromString<ConfigBundle>(
            """{"version":"v1","music":{"api":"https://mu.example","referer":"https://ref.example/"}}"""
        )
        assertEquals("https://mu.example", b.music.api)
        assertEquals("https://ref.example/", b.music.referer)
    }

    @Test
    fun `老配置包没有 music 段也不能炸`() {
        val b = json.decodeFromString<ConfigBundle>("""{"version":"v0","sources":[]}""")
        assertEquals("", b.music.api, "缺失时应为空，由 App 回落到内置默认")
        assertEquals("", b.music.referer)
    }

    /**
     * 走真实的 `NasApi.bundle()`，不是直接 decode ConfigBundle。
     *
     * 之前只测了后者，结果 `BundleResp` 里漏声明 music 字段 ——
     * 配置照发、类型照转，解析时静默丢掉，设置页一直显示「内置默认」。
     * 这类"字段在半路没了"只有走一遍实际解析路径才抓得住。
     */
    @Test
    fun `NAS 配置包解析不会丢掉音乐段`() = runTest {
        val http = RecHttp(
            """{"version":"v9","sources":[],
               "live":{"groups":[],"items":[]},
               "music":{"api":"https://mu2.example","referer":"https://r2.example/"}}"""
        )
        val b = NasApi(http, "http://nas.local").bundle()
        assertEquals("v9", b.version)
        assertEquals("https://mu2.example", b.music.api)
        assertEquals("https://r2.example/", b.music.referer)
    }

    @Test
    fun `MusicApi 用配置里的地址和 Referer`() = runTest {
        val http = RecHttp()
        val api = MusicApi(http, base = "https://mu.example", referer = "https://ref.example/")
        api.recommend(limit = 3)
        assertTrue(http.lastUrl.startsWith("https://mu.example/netease/playlist/recommend"), http.lastUrl)
        assertEquals("https://ref.example/", http.lastReferer)
    }

    @Test
    fun `没配地址时不发请求`() = runTest {
        // 仓库不自带在线音源：不传 base 就该彻底静默，一个请求都不许出去
        val http = RecHttp()
        val api = MusicApi(http)
        assertEquals("", MusicApi.DEFAULT_BASE)
        assertTrue(api.recommend(limit = 3).isEmpty())
        assertTrue(api.categories().isEmpty())
        assertTrue(api.search("x").isEmpty())
        assertEquals("", api.trackUrl("123"))
        assertTrue(api.lyric("123").isEmpty())
        // 没配接口时不做判断：判成「片段」会让播放器把整张歌单跳过
        assertTrue(api.isFullTrack("http://cdn/a.mp3"))
        assertEquals(0, http.calls)
    }

    /**
     * 试听片段判定：只有「明确拿到长度且小于阈值」才算片段。
     *
     * 反过来（拿不到长度就判片段）是这里最危险的写法：HEAD 被拒、CDN 走
     * chunked 都会返回 0，那样整张歌单会被逐首跳过，用户看到的是「一首都不放」。
     */
    @Test
    fun `只有明确拿到长度且小于阈值才算试听片段`() = runTest {
        val base = "https://mu.example"
        assertTrue(
            MusicApi(RecHttp(len = 3L * 1024 * 1024), base = base).isFullTrack("http://cdn/a.mp3"),
            "3MB 是正常整曲",
        )
        assertFalse(
            MusicApi(RecHttp(len = 480L * 1024), base = base).isFullTrack("http://cdn/b.mp3"),
            "480KB 是 30 秒片段",
        )
        assertTrue(
            MusicApi(RecHttp(len = 0L), base = base).isFullTrack("http://cdn/c.mp3"),
            "拿不到长度时必须放行，否则正常曲目会被误杀",
        )
    }
}
