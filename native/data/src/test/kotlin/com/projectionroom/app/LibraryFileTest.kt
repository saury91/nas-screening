package com.projectionroom.app

import com.projectionroom.shared.model.Vod
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 本地主库的取数规则。
 *
 * 这几条看起来像 getter，实际都是「界面上会看到什么」：收藏页的顺序、
 * 继续观看里哪些条目该消失、进度条画到哪儿。
 */
class LibraryFileTest {

    private fun item(
        name: String,
        at: Long = 0,
        fav: Boolean = false,
        ep: String = "",
        type: String = "",
        position: Long = 0,
        duration: Long = 0,
        pic: String = "",
    ) = LibItem(
        name = name, pic = pic, type = type, ep = ep,
        position = position, duration = duration, at = at, fav = fav,
    )

    @Test
    fun `收藏按最近更新时间倒序`() {
        val lib = LibFile(
            listOf(
                item("旧", at = 100, fav = true),
                item("新", at = 300, fav = true),
                item("中", at = 200, fav = true),
                item("没收藏", at = 400),
            )
        )
        assertEquals(listOf("新", "中", "旧"), lib.favorites().map { it.name })
    }

    @Test
    fun `观看记录清掉直播与空条目`() {
        val lib = LibFile(
            listOf(
                item("正常剧", at = 3, ep = "第2集", position = 500, duration = 1000),
                // 老版本把「直播」写在 type 上、ep 留空
                item("CCTV10", at = 5, type = "直播"),
                // 新版本写在 ep 上
                item("CCTV1", at = 6, ep = "直播", position = 100),
                // 既没集数也没进度：只是点过收藏，不该出现在「继续观看」
                item("刚收藏", at = 4, fav = true),
            )
        )
        assertEquals(listOf("正常剧"), lib.history().map { it.name })
    }

    @Test
    fun `观看记录把集数写进 type 给卡片用`() {
        val lib = LibFile(listOf(item("剧", at = 1, ep = "第8集", position = 10, duration = 100)))
        assertEquals("第8集", lib.history().single().type)
    }

    @Test
    fun `progress 夹在 0 到 1 之间`() {
        assertEquals(0.5f, item("a", position = 50, duration = 100).progress, 0.0001f)
        assertEquals(1f, item("b", position = 200, duration = 100).progress, 0.0001f)
        assertEquals(0f, item("c", position = 10, duration = 0).progress, 0.0001f)
    }

    @Test
    fun `find 按片名精确匹配`() {
        val lib = LibFile(listOf(item("沙丘"), item("沙丘2")))
        assertEquals("沙丘2", lib.find("沙丘2")?.name)
        assertNull(lib.find("沙丘3"))
    }

    @Test
    fun `收藏条目带上封面与分类`() {
        val lib = LibFile(listOf(item("片", at = 1, fav = true, type = "电影", pic = "http://p/1.jpg")))
        val v: Vod = lib.favorites().single()
        assertEquals("http://p/1.jpg", v.pic)
        assertEquals("电影", v.type)
    }
}
