package com.projectionroom.app

import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * NAS 熔断器。
 *
 * 这台 NAS 第一次同步完配置后可能永远不再开机，所以「失败一次就静默 60 秒」
 * 是手感的关键：不熔断的话每次启动都要白等 2.5 秒超时。
 */
class NasGateTest {

    @Before
    fun reset() {
        NasGate.markUp()
    }

    @Test
    fun `默认可用`() {
        assertTrue(NasGate.available())
    }

    @Test
    fun `拉黑后不可用 放行后恢复`() {
        NasGate.markDown()
        assertFalse(NasGate.available())
        NasGate.markUp()
        assertTrue(NasGate.available())
    }

    @Test
    fun `成功调用返回值并保持可用`() = runTest {
        assertEquals(7, NasGate.tryCall { 7 })
        assertTrue(NasGate.available())
    }

    @Test
    fun `返回 null 视为失败并熔断`() = runTest {
        assertNull(NasGate.tryCall { null })
        assertFalse(NasGate.available())
    }

    @Test
    fun `抛异常视为失败并熔断`() = runTest {
        assertNull(NasGate.tryCall<Int> { error("boom") })
        assertFalse(NasGate.available())
    }

    @Test
    fun `超时视为失败并熔断`() = runTest {
        assertNull(
            NasGate.tryCall(timeoutMs = 50) {
                delay(5_000)
                1
            }
        )
        assertFalse(NasGate.available())
    }

    @Test
    fun `熔断期间不再执行调用`() = runTest {
        NasGate.markDown()
        var ran = false
        assertNull(NasGate.tryCall { ran = true; 1 })
        assertFalse(ran)
    }
}
