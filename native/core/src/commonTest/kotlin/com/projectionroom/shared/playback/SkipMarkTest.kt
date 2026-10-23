package com.projectionroom.shared.playback

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SkipMarkTest {

    @Test
    fun `往返`() {
        val mark = SkipMark(introMs = 90_000L, outroMs = 60_000L)
        assertEquals(mark, SkipMark.parse(SkipMark.format(mark)))
        val off = SkipMark(0L, 0L)
        assertEquals(off, SkipMark.parse(SkipMark.format(off)))
    }

    @Test
    fun `坏数据一律返回 null`() {
        assertNull(SkipMark.parse(null))
        assertNull(SkipMark.parse(""))
        assertNull(SkipMark.parse("90000"))
        assertNull(SkipMark.parse("a\u0001b"))
        // 负数：写进去就是把正片开头切掉，必须拒绝
        assertNull(SkipMark.parse("-1\u000160000"))
        assertNull(SkipMark.parse("90000\u0001-5"))
        assertNull(SkipMark.parse("90000\u000160000\u0001extra"))
    }

    @Test
    fun `两个都为零算关闭`() {
        assertTrue(SkipMark(0L, 0L).isOff)
        assertFalse(SkipMark(90_000L, 0L).isOff)
        assertFalse(SkipMark(0L, 60_000L).isOff)
    }

    @Test
    fun `默认值与规则默认一致`() {
        assertEquals(SkipRule.DEFAULT_INTRO, SkipMark().introMs)
        assertEquals(SkipRule.DEFAULT_OUTRO, SkipMark().outroMs)
    }
}
