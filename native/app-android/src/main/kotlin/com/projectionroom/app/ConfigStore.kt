package com.projectionroom.app

import android.content.Context
import com.projectionroom.shared.catalog.ConfigBundle
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * 本地配置包：**App 唯一需要从 NAS 拿的东西**。
 *
 * 里面是片源清单、直播频道表、版本号。拉一次就落盘，之后：
 * 搜索、详情、选集、播放全部直连源站，NAS 关机不影响。
 *
 * 存成一个 JSON 文件（几十 KB），读的时候同步读 —— 启动路径上多一次 IO，
 * 换来的是「开机就有片源」，值得。
 */
object ConfigStore {

    private const val FILE = "config/bundle.json"

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    fun read(ctx: Context): ConfigBundle? {
        // 先读正式文件，坏掉再读备份 —— 这份文件没了，整个 App 就没片源了，
        // 而它的来源（NAS）可能永远不会再开机，所以必须留一手
        parse(File(ctx.filesDir, FILE))?.let { return it }
        return parse(File(ctx.filesDir, "$FILE.bak"))
    }

    private fun parse(f: File): ConfigBundle? {
        if (!f.exists()) return null
        return runCatching { json.decodeFromString<ConfigBundle>(f.readText()) }
            .getOrNull()
            ?.takeIf { it.sources.isNotEmpty() }
    }

    fun save(ctx: Context, bundle: ConfigBundle) {
        runCatching {
            val f = File(ctx.filesDir, FILE)
            f.parentFile?.mkdirs()
            // 旧的那份先留成备份：新文件写坏了还能退回去
            if (f.exists()) runCatching { f.copyTo(File(ctx.filesDir, "$FILE.bak"), overwrite = true) }
            // 先写临时文件再改名：中途被杀不会留下半截 JSON
            val tmp = File(ctx.filesDir, "$FILE.tmp")
            tmp.writeText(json.encodeToString(bundle))
            tmp.renameTo(f)
        }
    }

    /** 版本号只用来判断「要不要更新」，不代表能不能用 */
    fun versionOf(bundle: ConfigBundle?): String = bundle?.version.orEmpty()
}
