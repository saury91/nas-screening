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
        val cur = list(ctx)
        val next = merged(cur, kw, MAX)
        if (next != cur) save(ctx, next)
        return next
    }

    /**
     * 新词插到最前、去重、截断到 [max] 条。
     *
     * 抽成纯函数是为了能测：上限和去重顺序是这个功能全部的行为，
     * 而它们在 SharedPreferences 后面没法单独验证。
     */
    internal fun merged(cur: List<String>, kw: String, max: Int = MAX): List<String> {
        val k = kw.trim()
        if (k.isEmpty()) return cur
        return (listOf(k) + cur.filter { it != k }).take(max)
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
