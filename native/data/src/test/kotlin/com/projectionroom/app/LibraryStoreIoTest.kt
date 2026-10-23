package com.projectionroom.app

import com.projectionroom.shared.model.Vod
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * `library.json` 的读写与本地改动。
 *
 * 这份文件是「NAS 关机时的唯一记忆」，写坏了就等于收藏和记录全丢，
 * 所以原子写（先 .tmp 再改名）和坏文件兜底都要锁住。
 */
class LibraryStoreIoTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val file: File get() = File(tmp.root, "library.json")

    @Test
    fun `写读往返保留全部字段`() {
        val lib = LibFile(
            listOf(
                LibItem(name = "片", pic = "p.jpg", type = "电影", year = "2024", ep = "第1集", position = 12, duration = 100),
                LibItem(name = "剧", fav = true, pendingFav = true),
            )
        )
        LibraryJsonFile.write(file, lib)
        val back = LibraryJsonFile.read(file)
        assertEquals(2, back.items.size)
        assertEquals("第1集", back.find("片")!!.ep)
        assertEquals(12L, back.find("片")!!.position)
        assertTrue(back.find("剧")!!.fav)
        assertTrue(back.find("剧")!!.pendingFav)
    }

    @Test
    fun `文件不存在时返回空库`() {
        assertEquals(0, LibraryJsonFile.read(file).items.size)
    }

    @Test
    fun `坏 JSON 退回空库而不是崩`() {
        file.writeText("{ 这不是 json")
        assertNotNull(LibraryJsonFile.read(file))
        assertEquals(0, LibraryJsonFile.read(file).items.size)
    }

    @Test
    fun `写完不留 tmp 残骸`() {
        LibraryJsonFile.write(file, LibFile(listOf(LibItem(name = "片"))))
        assertTrue(file.exists())
        assertFalse(File(tmp.root, "library.json.tmp").exists())
    }

    @Test
    fun `新收藏置 fav 与 pendingFav`() {
        val next = LibraryOps.toggleFav(LibFile(), Vod(name = "片", pic = "p.jpg"), 100L)
        val item = next.find("片")!!
        assertTrue(item.fav)
        assertTrue(item.pendingFav)
        assertEquals(100L, item.at)
        assertEquals("p.jpg", item.pic)
    }

    @Test
    fun `再次收藏是取消且保留观看进度`() {
        val lib = LibFile(
            listOf(LibItem(name = "片", fav = true, pendingFav = false, position = 500, duration = 1000, ep = "第3集"))
        )
        val next = LibraryOps.toggleFav(lib, Vod(name = "片"), 200L)
        val item = next.find("片")!!
        assertFalse(item.fav)
        assertTrue(item.pendingFav)
        assertEquals(500L, item.position)
        assertEquals("第3集", item.ep)
    }

    @Test
    fun `记录进度只改动这一条并置 pendingHis`() {
        val lib = LibFile(listOf(LibItem(name = "别的", position = 1), LibItem(name = "片")))
        val next = LibraryOps.touchHistory(lib, Vod(name = "片", pic = "p.jpg"), "第2集", 800L, 1000L, 300L)
        val item = next.find("片")!!
        assertEquals("第2集", item.ep)
        assertEquals(800L, item.position)
        assertTrue(item.pendingHis)
        assertEquals(1L, next.find("别的")!!.position)
    }

    @Test
    fun `记录进度补空封面但不动已有封面`() {
        val lib = LibFile(listOf(LibItem(name = "有封面", pic = "old.jpg"), LibItem(name = "没封面")))
        val next = LibraryOps.touchHistory(lib, Vod(name = "有封面", pic = "new.jpg"), "第1集", 1, 2, 3)
        val next2 = LibraryOps.touchHistory(next, Vod(name = "没封面", pic = "new.jpg"), "第1集", 1, 2, 3)
        assertEquals("old.jpg", next2.find("有封面")!!.pic)
        assertEquals("new.jpg", next2.find("没封面")!!.pic)
    }

    @Test
    fun `markSynced 只清指定类型与指定片名`() {
        val lib = LibFile(
            listOf(
                LibItem(name = "a", pendingFav = true, pendingHis = true),
                LibItem(name = "b", pendingFav = true, pendingHis = true),
            )
        )
        val next = LibraryOps.markSynced(lib, setOf("a"), fav = true, his = false)
        assertFalse(next.find("a")!!.pendingFav)
        assertTrue(next.find("a")!!.pendingHis)
        assertTrue(next.find("b")!!.pendingFav)
    }

    @Test
    fun `find 找不到时返回 null`() {
        assertNull(LibraryOps.toggleFav(LibFile(), Vod(name = "x"), 1L).find("y"))
    }
}
