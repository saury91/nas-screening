package com.projectionroom.app

import com.projectionroom.shared.model.Vod
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 跨端合并：这里每一条都对应一次真实事故。
 *
 * 收藏是「切换」语义、进度是「覆盖」语义，两条链路都必须做到
 * 「本机没送达 NAS 的改动，不被 NAS 上的旧数据抹掉」。
 */
class LibraryMergeTest {

    private val now = 1_700_000_000_000L

    private fun local(
        name: String,
        fav: Boolean = false,
        pendingFav: Boolean = false,
        position: Long = 0,
        duration: Long = 0,
        pendingHis: Boolean = false,
        pic: String = "",
    ) = LibItem(
        name = name, pic = pic, fav = fav, pendingFav = pendingFav,
        position = position, duration = duration, pendingHis = pendingHis, at = 1L,
    )

    @Test
    fun `两边都拿不到就原样返回`() {
        val lib = LibFile(listOf(local("片")))
        assertSame(lib, LibraryMerge.pull(lib, null, null, now))
    }

    @Test
    fun `NAS 上的收藏进本地库`() {
        val merged = LibraryMerge.pull(
            LibFile(),
            listOf(Vod(name = "片", pic = "p.jpg", type = "电影", year = "2024")),
            null,
            now,
        )
        val it = merged.find("片")!!
        assertTrue(it.fav)
        assertEquals("p.jpg", it.pic)
        assertEquals(now, it.at)
    }

    @Test
    fun `本机取消收藏还没推送时不被 NAS 拉回`() {
        val lib = LibFile(listOf(local("片", fav = false, pendingFav = true)))
        val merged = LibraryMerge.pull(lib, listOf(Vod(name = "片")), null, now)
        assertEquals(false, merged.find("片")!!.fav)
    }

    @Test
    fun `没有 pending 时接受 NAS 的收藏`() {
        val lib = LibFile(listOf(local("片", fav = false)))
        val merged = LibraryMerge.pull(lib, listOf(Vod(name = "片")), null, now)
        assertTrue(merged.find("片")!!.fav)
    }

    @Test
    fun `本机进度还没推送时不被 NAS 覆盖`() {
        val lib = LibFile(listOf(local("片", position = 8_000, duration = 10_000, pendingHis = true)))
        val merged = LibraryMerge.pull(lib, null, listOf(Vod(name = "片", progress = 0.1f)), now)
        assertEquals(8_000L, merged.find("片")!!.position)
    }

    @Test
    fun `本机没在播这部时采用 NAS 进度`() {
        val lib = LibFile(listOf(local("片", position = 0, pic = "")))
        val merged = LibraryMerge.pull(lib, null, listOf(Vod(name = "片", progress = 0.5f, pic = "n.jpg")), now)
        val it = merged.find("片")!!
        assertEquals(5_000L, it.position)
        assertEquals(10_000L, it.duration)
        // 封面空着就补上，否则「继续观看」会出现一排空白卡
        assertEquals("n.jpg", it.pic)
        assertEquals(now, it.at)
    }

    @Test
    fun `本机正在播的不被 NAS 的旧进度覆盖`() {
        val lib = LibFile(listOf(local("片", position = 9_000, duration = 10_000)))
        val merged = LibraryMerge.pull(lib, null, listOf(Vod(name = "片", progress = 0.2f)), now)
        assertEquals(9_000L, merged.find("片")!!.position)
    }

    @Test
    fun `收藏与记录指向同一部片时合并成一条`() {
        val merged = LibraryMerge.pull(
            LibFile(),
            listOf(Vod(name = "片", pic = "p.jpg")),
            listOf(Vod(name = "片", progress = 0.3f)),
            now,
        )
        assertEquals(1, merged.items.size)
        assertTrue(merged.find("片")!!.fav)
        assertEquals(3_000L, merged.find("片")!!.position)
    }
}
