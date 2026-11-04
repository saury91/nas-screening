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
    fun `删除收藏后 NAS 上的旧收藏不会拉回来`() {
        val lib = LibraryOps.removeFav(
            LibFile(listOf(local("片", fav = true))), "片", now,
        )
        assertTrue(lib.favorites().isEmpty())

        val merged = LibraryMerge.pull(lib, listOf(Vod(name = "片")), null, now)
        assertTrue(merged.favorites().isEmpty())
        assertEquals(1, merged.removed.count { it.fav })
    }

    @Test
    fun `删除收藏但还有记录时条目保留`() {
        val lib = LibraryOps.removeFav(
            LibFile(listOf(local("片", fav = true, position = 5_000, duration = 10_000))),
            "片", now,
        )
        assertTrue(lib.favorites().isEmpty())
        assertEquals(1, lib.history().size)
    }

    @Test
    fun `删除记录后 NAS 上的旧进度不会拉回来`() {
        val lib = LibraryOps.removeHistory(
            LibFile(listOf(local("片", position = 5_000, duration = 10_000))),
            "片", now,
        )
        assertTrue(lib.history().isEmpty())

        val merged = LibraryMerge.pull(lib, null, listOf(Vod(name = "片", progress = 0.5f)), now)
        assertTrue(merged.history().isEmpty())
        assertEquals(1, merged.removed.count { it.his })
    }

    @Test
    fun `删除记录但收藏还在时只清进度`() {
        val lib = LibraryOps.removeHistory(
            LibFile(listOf(local("片", fav = true, position = 5_000, duration = 10_000))),
            "片", now,
        )
        assertTrue(lib.history().isEmpty())
        assertEquals(1, lib.favorites().size)
    }

    @Test
    fun `重新收藏会撤销删除墓碑`() {
        val removed = LibraryOps.removeFav(
            LibFile(listOf(local("片", fav = true))), "片", now,
        )
        val back = LibraryOps.toggleFav(
            removed, Vod(name = "片"), now + 1,
        )
        assertTrue(back.favorites().size == 1)
        assertTrue(back.removed.none { it.fav })

        // 再拉 NAS 也不会被墓碑挡住（本机 pending 仍优先，但不影响收藏状态）
        val merged = LibraryMerge.pull(back, listOf(Vod(name = "片")), null, now + 2)
        assertEquals(1, merged.favorites().size)
    }

    @Test
    fun `标记同步完成不能丢掉墓碑`() {
        val removed = LibraryOps.removeHistory(
            LibFile(listOf(local("片", position = 5_000, duration = 10_000))),
            "片", now,
        )
        val synced = LibraryOps.markSynced(removed, setOf("片"), fav = false, his = true)
        assertEquals(1, synced.removed.count { it.his })
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
