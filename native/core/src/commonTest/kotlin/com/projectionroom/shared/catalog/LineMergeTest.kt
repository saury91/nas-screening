package com.projectionroom.shared.catalog

import com.projectionroom.shared.model.Episode
import com.projectionroom.shared.model.Line
import kotlin.test.Test
import kotlin.test.assertEquals

/** 用例取自《多喜一家人》的真实 `vod_play_url`（2026-11 抓的）。 */
class LineMergeTest {

    private fun line(site: String, vararg eps: String) = Line(
        site = site,
        remarks = "更新至第06集",
        quality = "",
        episodes = eps.mapIndexed { i, url -> Episode("第${i + 1}集", url) },
    )

    @Test
    fun `播放页与直链只留直链`() {
        val merged = dedupeLines(
            listOf(
                line("新浪资源 · 线路1", "https://play.xluuss.com/play/bo2AOzja"),
                line("新浪资源 · 线路2", "https://play.xluuss.com/play/bo2AOzja/index.m3u8"),
            )
        )
        assertEquals(1, merged.size)
        assertEquals("https://play.xluuss.com/play/bo2AOzja/index.m3u8", merged[0].episodes[0].url)
    }

    @Test
    fun `同站同域名的 share 页与直链算一条`() {
        val merged = dedupeLines(
            listOf(
                line("非凡资源 · 线路1", "https://super.ffzy-online6.com/share/c29b6bce"),
                line("非凡资源 · 线路2", "https://super.ffzy-online6.com/20261117/45775_c29b6bce/index.m3u8"),
            )
        )
        assertEquals(1, merged.size)
        assertEquals("https://super.ffzy-online6.com/20261117/45775_c29b6bce/index.m3u8", merged[0].episodes[0].url)
    }

    @Test
    fun `被别的站抄过去的同一批地址只留一份`() {
        val direct = "https://super.ffzy-online6.com/20261117/45775_c29b6bce/index.m3u8"
        val merged = dedupeLines(
            listOf(
                line("非凡资源 · 线路1", direct),
                line("艾旦资源 · 线路9", direct),
            )
        )
        assertEquals(1, merged.size)
        assertEquals("非凡资源 · 线路1", merged[0].site)
    }

    @Test
    fun `不同域名的镜像要保留`() {
        val merged = dedupeLines(
            listOf(
                line("艾旦资源 · 线路1", "https://v.lzcdn27.com/yyv15/x/index.m3u8"),
                line("艾旦资源 · 线路2", "https://v.ppqrrs.com/yyv15/x/index.m3u8"),
            )
        )
        // 同一份内容、不同 CDN：播不动时还得换一条，不能合并
        assertEquals(2, merged.size)
    }

    @Test
    fun `集数不同就不算重复`() {
        val merged = dedupeLines(
            listOf(
                line("猫眼资源 · 线路1", "https://v.qqqrst.com/a/1/index.m3u8", "https://v.qqqrst.com/a/2/index.m3u8"),
                line("猫眼资源 · 线路2", "https://v.qqqrst.com/a/1/index.m3u8"),
            )
        )
        assertEquals(2, merged.size)
    }

    @Test
    fun `站名去掉线路后缀`() {
        assertEquals("艾旦资源", siteBaseName("艾旦资源 · 线路12"))
        assertEquals("艾旦资源", siteBaseName("艾旦资源·线路2"))
        // 没有编号的「线路」是站名的一部分，别动
        assertEquals("PG线路", siteBaseName("PG线路"))
    }
}
