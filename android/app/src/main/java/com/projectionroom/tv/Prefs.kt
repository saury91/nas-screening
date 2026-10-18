package com.projectionroom.tv

import android.content.Context

/** NAS 地址只存在本地 SharedPreferences，不涉及账号信息 */
object Prefs {
    private const val FILE = "proom"
    private const val KEY_SERVER = "server"

    fun server(ctx: Context): String =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getString(KEY_SERVER, "")?.trim().orEmpty()

    fun setServer(ctx: Context, url: String) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit().putString(KEY_SERVER, url.trim().trimEnd('/')).apply()
    }
}
