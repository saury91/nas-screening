package com.projectionroom.app

import android.content.Context

/**
 * NAS 地址的本地存储。
 *
 * 只有这一项配置存在客户端——片源、直播表、升级包都从 NAS 拉。
 * 存 SharedPreferences 而不是 DataStore：一个字符串，不值得引入协程流。
 */
object NasStore {

    private const val FILE = "proom"
    private const val KEY = "nas_url"

    /**
     * 预热 SharedPreferences。
     *
     * 首次 `getString` 会在**调用线程**上等整个 XML 解析完（awaitLoadedLocked），
     * 而我们的第一次读取就在 composition 里 —— 主线程直接多出一次磁盘读。
     * 在 onCreate 里先拿一次引用，解析就跑到后台线程上，等界面真正要值时通常已经好了。
     */
    fun warmUp(ctx: Context) {
        runCatching { ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE) }
    }

    fun url(ctx: Context): String? =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getString(KEY, null)
            ?.takeIf { it.isNotBlank() }

    fun save(ctx: Context, url: String) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit().putString(KEY, url).apply()
    }

    /**
     * 用户主动跳过连接（暂时没 NAS，或想先用本地片源）。
     *
     * 记住它，下次启动才不会再弹一次输入地址 —— 地址入口只留在设置页。
     */
    fun skipped(ctx: Context): Boolean =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean("nas_skipped", false)

    fun setSkipped(ctx: Context, v: Boolean) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putBoolean("nas_skipped", v).apply()
    }

    fun clear(ctx: Context) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().remove(KEY).apply()
    }

    /**
     * 用户可能只输 `192.0.2.10:8899`，也可能整个粘贴 `http://.../`。
     * 统一成 `http://host:port`，非法输入返回 null。
     */
    fun normalize(raw: String): String? {
        var s = raw.trim()
        if (s.isEmpty()) return null
        if (!s.startsWith("http://") && !s.startsWith("https://")) s = "http://$s"
        s = s.trimEnd('/')
        val hostPort = s.removePrefix("http://").removePrefix("https://")
        if (hostPort.isEmpty()) return null
        // 没写端口就补默认 8899（NAS 的默认端口）
        val hasPort = hostPort.substringAfterLast(':', "").toIntOrNull() != null
        return if (hasPort) s else "$s:8899"
    }
}
