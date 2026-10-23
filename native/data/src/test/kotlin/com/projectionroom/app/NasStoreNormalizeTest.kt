package com.projectionroom.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * NAS 地址归一化。
 *
 * 用户是在电视遥控器上填这个地址的，少写 `http://`、多写一个 `/`、
 * 忘了端口都是常态 —— 这些都得在这里吃掉，否则设置页保存成功、同步全失败。
 */
class NasStoreNormalizeTest {

    @Test
    fun `裸地址补协议与默认端口`() {
        assertEquals("http://192.168.1.10:8899", NasStore.normalize("192.168.1.10"))
    }

    @Test
    fun `带端口的保持原样`() {
        assertEquals("http://192.168.1.10:9000", NasStore.normalize("192.168.1.10:9000"))
    }

    @Test
    fun `https 保留协议并补端口`() {
        assertEquals("https://nas.local:8899", NasStore.normalize("https://nas.local"))
    }

    @Test
    fun `去掉尾部斜杠与首尾空白`() {
        assertEquals("https://nas.local:8443", NasStore.normalize("  https://nas.local:8443/  "))
    }

    @Test
    fun `空串返回 null`() {
        assertNull(NasStore.normalize(""))
        assertNull(NasStore.normalize("   "))
    }
}
