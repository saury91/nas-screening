package com.projectionroom.app

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 搜索历史的去重与上限。
 *
 * 上限不是洁癖：这份列表会画在电视首页的搜索框下面，
 * 攒到几百条既没人用，又让 SharedPreferences 越来越大。
 */
class SearchHistoryTest {

    @Test
    fun `新词插到最前`() {
        assertEquals(listOf("b", "a"), SearchStore.merged(listOf("a"), "b"))
    }

    @Test
    fun `重复词移到最前且不重复`() {
        assertEquals(listOf("a", "c", "b"), SearchStore.merged(listOf("c", "a", "b"), "a"))
    }

    @Test
    fun `超过上限时丢掉最旧的`() {
        val cur = (1..10).map { "k$it" }
        val next = SearchStore.merged(cur, "new")
        assertEquals(10, next.size)
        assertEquals("new", next.first())
        // 列表尾部是最旧的，被挤出去的是 k10
        assertEquals(false, next.contains("k10"))
    }

    @Test
    fun `空白输入不改变列表`() {
        val cur = listOf("a")
        assertEquals(cur, SearchStore.merged(cur, "   "))
    }

    @Test
    fun `首尾空白会被裁掉`() {
        assertEquals(listOf("沙丘"), SearchStore.merged(emptyList(), "  沙丘  "))
    }
}
