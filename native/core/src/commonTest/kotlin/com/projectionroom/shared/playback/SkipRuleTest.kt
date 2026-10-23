package com.projectionroom.shared.playback

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SkipRuleTest {

    @Test
    fun `片头区间内才浮按钮`() {
        assertTrue(SkipRule.shouldShowIntro(5_000L, 90_000L, false))
        assertTrue(SkipRule.shouldShowIntro(89_999L, 90_000L, false))
        // 正好到 90 秒：已经是正片，按钮必须消失
        assertFalse(SkipRule.shouldShowIntro(90_000L, 90_000L, false))
        assertFalse(SkipRule.shouldShowIntro(120_000L, 90_000L, false))
    }

    @Test
    fun `起播瞬间不浮按钮`() {
        // setMediaSource 之后 position 还是 0，这时候弹按钮会在每次起播闪一下
        assertFalse(SkipRule.shouldShowIntro(0L, 90_000L, false))
    }

    @Test
    fun `关掉片头与直播都不浮按钮`() {
        assertFalse(SkipRule.shouldShowIntro(5_000L, 0L, false))
        assertFalse(SkipRule.shouldShowIntro(5_000L, 90_000L, live = true))
    }

    @Test
    fun `片尾只在快播完且有下一集时提示`() {
        assertTrue(
            SkipRule.shouldShowOutro(
                position = 2_950_000L, duration = 3_000_000L,
                outroMs = 60_000L, hasNext = true, live = false,
            )
        )
        // 还在中间
        assertFalse(
            SkipRule.shouldShowOutro(
                position = 1_000_000L, duration = 3_000_000L,
                outroMs = 60_000L, hasNext = true, live = false,
            )
        )
        // 最后一集
        assertFalse(
            SkipRule.shouldShowOutro(
                position = 2_950_000L, duration = 3_000_000L,
                outroMs = 60_000L, hasNext = false, live = false,
            )
        )
        // 片尾关
        assertFalse(
            SkipRule.shouldShowOutro(
                position = 2_950_000L, duration = 3_000_000L,
                outroMs = 0L, hasNext = true, live = false,
            )
        )
        // 直播
        assertFalse(
            SkipRule.shouldShowOutro(
                position = 2_950_000L, duration = 3_000_000L,
                outroMs = 60_000L, hasNext = true, live = true,
            )
        )
    }

    @Test
    fun `duration 未知时不提示片尾`() {
        // C.TIME_UNSET 是个很大的负数。老实现直接减 position 会算出负数，
        // 于是一起播就提示「下一集」，这里必须挡住。
        val unset = -9_223_372_036_854_775_807L
        assertFalse(
            SkipRule.shouldShowOutro(
                position = 5_000L, duration = unset,
                outroMs = 60_000L, hasNext = true, live = false,
            )
        )
        assertFalse(
            SkipRule.shouldShowOutro(
                position = 5_000L, duration = 0L,
                outroMs = 60_000L, hasNext = true, live = false,
            )
        )
    }

    @Test
    fun `位置超过总长不提示`() {
        assertFalse(
            SkipRule.shouldShowOutro(
                position = 3_100_000L, duration = 3_000_000L,
                outroMs = 60_000L, hasNext = true, live = false,
            )
        )
    }

    @Test
    fun `剩余秒数向上取整`() {
        assertEquals(30, SkipRule.remainingSec(2_970_000L, 3_000_000L))
        // 29950ms 剩 50ms：显示「1 秒」而不是「0 秒」
        assertEquals(1, SkipRule.remainingSec(2_999_950L, 3_000_000L))
        assertEquals(0, SkipRule.remainingSec(3_000_000L, 3_000_000L))
        assertEquals(0, SkipRule.remainingSec(3_100_000L, 3_000_000L))
    }

    @Test
    fun `档位文案`() {
        assertEquals("关闭", SkipRule.label(0L))
        assertEquals("30 秒", SkipRule.label(30_000L))
        assertEquals("1 分钟", SkipRule.label(60_000L))
        assertEquals("1 分 30 秒", SkipRule.label(90_000L))
        assertEquals("3 分钟", SkipRule.label(180_000L))
    }

    @Test
    fun `默认值落在可选档位里`() {
        // 默认值必须是合法档位，否则设置页找不到对应的高亮项
        assertTrue(SkipRule.DEFAULT_INTRO in SkipRule.introOptions)
        assertTrue(SkipRule.DEFAULT_OUTRO in SkipRule.outroOptions)
        assertTrue(SkipRule.introOptions.all { it >= 0L })
        assertTrue(SkipRule.outroOptions.all { it >= 0L })
    }
}
