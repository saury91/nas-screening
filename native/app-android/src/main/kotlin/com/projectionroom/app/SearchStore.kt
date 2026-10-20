package com.projectionroom.app

import android.content.Context

/**
 * 搜索历史：只存最近 10 条，本机保存（不出设备）。
 * 存字符串列表用 SharedPreferences 的 StringSet 会丢顺序，所以用 "|||" 拼串。
 */
object SearchStore {

    /** 预热：首次读 SharedPreferences 会在调用线程解析 XML，启动时先让它后台加载 */
    fun warmUp(ctx: android.content.Context) {
        runCatching { ctx.getSharedPreferences(FILE, android.content.Context.MODE_PRIVATE) }
    }

    private const val FILE = "proom_search"
    private const val KEY = "history"
    private const val SEP = "\u0001"
    private const val MAX = 10

    fun list(ctx: Context): List<String> =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getString(KEY, "")
            ?.split(SEP)
            ?.filter { it.isNotBlank() }
            ?: emptyList()

    fun add(ctx: Context, kw: String): List<String> {
        val k = kw.trim()
        if (k.isEmpty()) return list(ctx)
        val next = (listOf(k) + list(ctx).filter { it != k }).take(MAX)
        save(ctx, next)
        return next
    }

    fun remove(ctx: Context, kw: String): List<String> {
        val next = list(ctx).filter { it != kw }
        save(ctx, next)
        return next
    }

    fun clear(ctx: Context): List<String> {
        save(ctx, emptyList())
        return emptyList()
    }

    private fun save(ctx: Context, items: List<String>) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit().putString(KEY, items.joinToString(SEP)).apply()
    }
}
