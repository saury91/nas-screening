package com.projectionroom.app

import android.content.Context
import com.projectionroom.shared.catalog.ConfigBundle
import com.projectionroom.shared.catalog.ConfigWire
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

    /** APK 里那份内置配置（和 NAS 下发的是同一种文件） */
    private const val ASSET = "config/bundle.json"

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
        // NAS 里的源是网页后台手工填的，字段缺一半是常态：
        // 实测导出的配置包里有 `"cat": null`，而模型里 cat 是非空且带默认值的，
        // 不开这两个开关会直接抛 JsonDecodingException —— 整个配置包都读不出来。
        coerceInputValues = true
        explicitNulls = false
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

    /**
     * 首启播种：APK 里内置一份配置包（`assets/config/bundle.json`）。
     *
     * 本地还没有配置时把它落盘，并把「连 NAS」标记成已跳过 —— 装完就能直接看片，
     * 不需要先有一台开机的 NAS。用户之后在设置里填地址，从 NAS 拉到的新配置
     * 会正常覆盖这份内置的（体积、字段完全一致，来源不同而已）。
     */
    fun seedFromAssets(ctx: Context): Boolean {
        if (read(ctx) != null) return false
        val ok = runCatching {
            ctx.assets.open(ASSET).bufferedReader().use { r ->
                // 走 ConfigWire：线上格式的直播台是 items，直接解 ConfigBundle 会全丢
                val bundle = ConfigWire.decode(r.readText())
                require(bundle.sources.isNotEmpty()) { "内置配置没有片源" }
                save(ctx, bundle)
            }
        }.isSuccess
        if (ok) NasStore.setSkipped(ctx, true)
        return ok
    }

    /**
     * 设置里的「恢复内置配置」：**不看**本地现状，直接用 APK 里那份覆盖。
     *
     * 和首启播种只差一个判断，但用途不同：播种是「本地空着才补」，
     * 这里是「本地被改坏了（导错包、填错地址拉到空配置）要退回出厂」。
     * 旧文件会由 [save] 自动留成 `.bak`，恢复错了还能手工找回来。
     */
    fun importBuiltIn(ctx: Context): Boolean {
        val bundle = runCatching {
            ctx.assets.open(ASSET).bufferedReader().use { ConfigWire.decode(it.readText()) }
        }.getOrNull()?.takeIf { it.sources.isNotEmpty() } ?: return false
        save(ctx, bundle)
        NasStore.setSkipped(ctx, true)
        return true
    }

    /** 版本号只用来判断「要不要更新」，不代表能不能用 */
    fun versionOf(bundle: ConfigBundle?): String = bundle?.version.orEmpty()
}
