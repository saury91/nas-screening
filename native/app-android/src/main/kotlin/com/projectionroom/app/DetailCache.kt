package com.projectionroom.app

import com.projectionroom.shared.model.Vod

/**
 * 详情页的进程内缓存。
 *
 * 解决的是一个很具体的体验问题：**从播放页返回详情页，整页会重新拉一遍**
 * （骨架屏闪一下，快的话 2 秒、慢的话十几秒），因为详情分支的 `remember`
 * 会随页面重建归零。
 *
 * 片源的详情（线路、选集）几小时内不会变，没必要每次都问 30 个源。
 * 这里只做进程内 LRU + 5 分钟有效期：
 *  - 返回 / 重进同一部片 → 立刻用缓存渲染，不再出骨架；
 *  - 超过 5 分钟 → 当没有，重新拉（片子更新、线路失效都能自然纠正）。
 */
object DetailCache {

    /** 多久算新鲜。只影响「要不要重新拉」，不做持久化 */
    private const val TTL_MS = 5 * 60 * 1000L

    /** 最多记住几部片：够覆盖「点开、播放、返回」这条来回路径就行 */
    private const val MAX = 8

    private class Entry(val vod: Vod, val at: Long)

    private val map = object : LinkedHashMap<String, Entry>(MAX, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>): Boolean =
            size > MAX
    }

    private fun key(name: String, year: String) = "$name|$year"

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

    fun put(name: String, year: String, vod: Vod): Unit {
        synchronized(map) {
            map[key(name, year)] = Entry(vod, System.currentTimeMillis())
        }
    }

    /** 下拉刷新时用：把这个 key 作废，下一次强制重新拉 */
    fun invalidate(name: String, year: String): Unit {
        synchronized(map) { map.remove(key(name, year)) }
    }
}
