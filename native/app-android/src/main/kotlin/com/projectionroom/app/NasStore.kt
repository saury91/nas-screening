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

    fun url(ctx: Context): String? =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getString(KEY, null)
            ?.takeIf { it.isNotBlank() }

    fun save(ctx: Context, url: String) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit().putString(KEY, url).apply()
    }

    fun clear(ctx: Context) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().remove(KEY).apply()
    }

    /**
     * 用户可能只输 `192.168.1.10:8899`，也可能整个粘贴 `http://.../`。
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
