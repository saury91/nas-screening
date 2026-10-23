package com.projectionroom.app

import com.projectionroom.shared.catalog.ConfigBundle
import com.projectionroom.shared.model.Source
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 配置包的落盘与回退。
 *
 * 这份文件丢了，App 就没片源，而它的来源（NAS）可能永远不再开机 ——
 * 所以「主文件坏了读备份」「没片源的包不算配置」必须是硬约束。
 */
class ConfigFileTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val dir: File get() = tmp.root

    private fun bundle(version: String = "1", source: String = "源A") = ConfigBundle(
        version = version,
        sources = listOf(Source(name = source, api = "https://a.example/api.php/provide/vod")),
    )

    @Test
    fun `写读往返`() {
        ConfigFile.save(dir, bundle(version = "v1"))
        val back = ConfigFile.read(dir)!!
        assertEquals("v1", back.version)
        assertEquals("源A", back.sources.single().name)
    }

    @Test
    fun `第二次保存把旧文件留成备份`() {
        ConfigFile.save(dir, bundle(version = "v1"))
        ConfigFile.save(dir, bundle(version = "v2"))
        assertEquals("v2", ConfigFile.read(dir)!!.version)
        // 主文件坏掉时能退回上一份能用的配置
        File(dir, "config/bundle.json").writeText("{ 半截")
        assertEquals("v1", ConfigFile.read(dir)!!.version)
    }

    @Test
    fun `主备都坏时返回 null`() {
        ConfigFile.save(dir, bundle())
        ConfigFile.save(dir, bundle())
        File(dir, "config/bundle.json").writeText("坏")
        File(dir, "config/bundle.json.bak").writeText("也坏")
        assertNull(ConfigFile.read(dir))
    }

    @Test
    fun `没有片源的配置视为无效`() {
        ConfigFile.save(dir, ConfigBundle(version = "空壳"))
        assertNull(ConfigFile.read(dir))
    }

    @Test
    fun `写完不留 tmp 残骸`() {
        ConfigFile.save(dir, bundle())
        assertEquals(false, File(dir, "config/bundle.json.tmp").exists())
    }

    @Test
    fun `目录不存在时读返回 null`() {
        assertNull(ConfigFile.read(File(dir, "nope")))
    }
}
