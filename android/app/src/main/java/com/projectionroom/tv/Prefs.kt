package com.projectionroom.tv

import android.content.Context

/** NAS 地址只存在本地 SharedPreferences，不涉及账号信息 */
object Prefs {
    private const val FILE = "proom"
    private const val KEY_SERVER = "server"

    fun server(ctx: Context): String =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getString(KEY_SERVER, "")?.trim().orEmpty()

    /** 设备 id：存在原生侧，冷启动清 WebView 存储也不会变（投屏靠它认设备） */
    fun deviceId(ctx: Context): String {
        val sp = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        var id = sp.getString("device_id", "") ?: ""
        if (id.isBlank()) {
            id = "d" + java.util.UUID.randomUUID().toString().replace("-", "").take(9)
            sp.edit().putString("device_id", id).apply()
        }
        return id
    }

    fun token(ctx: Context): String =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString("token", "") ?: ""

    fun setToken(ctx: Context, t: String) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit().putString("token", t).apply()
    }

    fun lastWebVersion(ctx: Context): String =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString("web_ver", "") ?: ""

    fun setLastWebVersion(ctx: Context, v: String) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putString("web_ver", v).apply()
    }

    fun setServer(ctx: Context, url: String) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit().putString(KEY_SERVER, url.trim().trimEnd('/')).apply()
    }
}
