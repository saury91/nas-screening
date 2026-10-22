package com.projectionroom.app

import android.content.Context
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest

/**
 * 列表类数据的落盘缓存：首页/片库/音乐分类/直播，一份 key 一个文件。
 *
 * 只存「最后一次成功拉到的整份列表 + 写入时间」，读回来由调用方按新鲜度决定
 * 是直接上屏还是先上屏再刷新。key 用 MD5 落成文件名，避免分类名里的 `:`、`/`
 * 变成非法路径。
 */
object FeedCache {

    /** 首页/音乐这类「变得不快」的列表：10 分钟内不再打源站 */
    const val FRESH_MS = 10 * 60 * 1000L

    /** 片库这种翻一页要问十几个站的：给更长的新鲜期，否则每次进都卡在骨架屏 */
    const val BROWSE_FRESH_MS = 30 * 60 * 1000L

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /** 读出来的结果。`at` 是写入时间，调用方配合 [isFresh] 判断要不要再拉一次 */
    class Entry<T>(val at: Long, val value: List<T>)

    fun isFresh(at: Long, ttl: Long = FRESH_MS): Boolean =
        at > 0 && System.currentTimeMillis() - at < ttl

    fun <T> read(ctx: Context, key: String, ser: KSerializer<List<T>>): Entry<T>? {
        val f = fileOf(ctx, key)
        if (!f.isFile) return null
        return runCatching {
            // 首行是时间戳，之后才是 JSON：这样不必为「带时间的列表」再拼一个包装序列化器
            val text = f.readText()
            val nl = text.indexOf('\n')
            if (nl <= 0) return@runCatching null
            val at = text.substring(0, nl).trim().toLongOrNull() ?: return@runCatching null
            Entry(at, json.decodeFromString(ser, text.substring(nl + 1)))
        }.getOrNull()
    }

    fun <T> write(ctx: Context, key: String, at: Long, value: List<T>, ser: KSerializer<List<T>>) {
        runCatching {
            val dir = dirOf(ctx).apply { mkdirs() }
            // 先写临时文件再改名：写到一半被系统杀掉，也不会留下半份 JSON
            val tmp = File(dir, hash(key) + ".tmp")
            tmp.writeText("$at\n" + json.encodeToString(ser, value))
            val dst = File(dir, hash(key) + ".json")
            if (!tmp.renameTo(dst)) {
                dst.delete()
                tmp.renameTo(dst)
            }
        }
    }

    /** 设置里「清空缓存」用 */
    fun clear(ctx: Context) {
        runCatching { dirOf(ctx).listFiles()?.forEach { it.delete() } }
    }

    private fun dirOf(ctx: Context) = File(ctx.applicationContext.filesDir, "feed")

    private fun fileOf(ctx: Context, key: String) = File(dirOf(ctx), hash(key) + ".json")

    private fun hash(key: String): String =
        MessageDigest.getInstance("MD5")
            .digest(key.toByteArray())
            .joinToString("") { "%02x".format(it) }
}
