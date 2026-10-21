package com.projectionroom.app

import android.content.Context
import com.projectionroom.shared.model.Vod
import kotlinx.serialization.builtins.ListSerializer
import java.util.concurrent.Executors

/**
 * 详情页缓存：**两段**。
 *
 * L1（内存，5 分钟新鲜期）：决定「这一次还要不要再问 30 个源」。
 * L2（磁盘，不过期）：决定「冷启动进来能不能先看到内容」。
 *
 * 之前只有 L1，所以杀掉进程再点开同一部片，详情页还是空白 + 骨架，
 * 一路等十几个源。现在的顺序是：
 *
 *   1. 列表页那份 `Vod` 先上屏（片名/海报/简介，本来就有）；
 *   2. L1 命中且新鲜 → 连线路都直接可用，一个请求都不发；
 *   3. L1 不新鲜 → 先拿 L2 的内容显示（线路可能旧，但能点），后台流式刷新覆盖。
 */
object DetailCache {

    /** 多久算新鲜。只影响「要不要重新拉」，不影响「能不能先显示」 */
    private const val TTL_MS = 5 * 60 * 1000L

    /** 内存里最多记住几部片：够覆盖「点开、播放、返回」这条来回路径就行 */
    private const val MAX = 8

    private class Entry(val vod: Vod, val at: Long)

    private val map = object : LinkedHashMap<String, Entry>(MAX, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>): Boolean =
            size > MAX
    }

    /** 写盘放单线程：详情是个几百毫秒的 JSON，没必要占主线程 */
    private val writer = Executors.newSingleThreadExecutor { r ->
        Thread(r, "detail-cache").apply { isDaemon = true }
    }

    private fun key(name: String, year: String) = "$name|$year"

    private fun diskKey(name: String, year: String) = "detail:${key(name, year)}"

    /** 只返回**新鲜**的：过期的当没有，交给调用方决定要不要重新拉 */
    fun get(name: String, year: String): Vod? {
        val k = key(name, year)
        return synchronized(map) {
            val e = map[k] ?: return@synchronized null
            if (System.currentTimeMillis() - e.at > TTL_MS) {
                map.remove(k)
                null
            } else {
                e.vod
            }
        }
    }

    fun isFresh(name: String, year: String): Boolean = synchronized(map) {
        val e = map[key(name, year)] ?: return@synchronized false
        System.currentTimeMillis() - e.at < TTL_MS
    }

    /**
     * 取「手上有的」详情：内存优先，其次磁盘，**不判新鲜度**。
     * 调用方应当把它当作「先顶上的旧内容」，随后继续刷新。
     */
    fun hydrate(ctx: Context, name: String, year: String): Vod? {
        val k = key(name, year)
        synchronized(map) { map[k] }?.let { return it.vod }
        val e = FeedCache.read(ctx, diskKey(name, year), ListSerializer(Vod.serializer()))
            ?: return null
        val vod = e.value.firstOrNull() ?: return null
        synchronized(map) { map[k] = Entry(vod, e.at) }
        return vod
    }

    fun put(ctx: Context, name: String, year: String, vod: Vod) {
        val k = key(name, year)
        val now = System.currentTimeMillis()
        synchronized(map) { map[k] = Entry(vod, now) }
        val app = ctx.applicationContext
        writer.execute {
            runCatching {
                FeedCache.write(app, diskKey(name, year), now, listOf(vod), ListSerializer(Vod.serializer()))
            }
        }
    }

    /** 下拉刷新时用：把这个 key 作废，下一次强制重新拉 */
    fun invalidate(name: String, year: String) {
        synchronized(map) { map.remove(key(name, year)) }
    }
}
