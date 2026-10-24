package com.projectionroom.ui.music

import com.projectionroom.shared.catalog.NasApi

/**
 * 歌单详情的进程内缓存。
 *
 * 为什么需要它：从歌单点进全屏播放器再返回，或者退出后再点同一张封面，
 * `remember(id)` 会随页面重建一起丢掉，`loading` 又从 true 起步 ——
 * 于是明明数据就在手上，页面还是先闪一次骨架屏。
 *
 * 只放内存（一个歌单几百条曲目，对象很小），命中就先出内容，网络回来再覆盖。
 * 冷启动第一次进入仍然会有一次骨架 —— 那是真的没有数据。
 */
object PlaylistCache {

    /**
     * 多久算「新鲜」：和磁盘缓存（`PlaylistStore.FRESH_MS`）用同一个量级。
     * 新鲜期内直接上屏、不打网络 —— 详情那个上游要 0.3~12 秒，不是白等的。
     */
    const val FRESH_MS = 30 * 60 * 1000L

    private const val MAX = 8

    /** accessOrder=true：最近看过的排到队尾，淘汰最久没打开的 */
    private val map = object : LinkedHashMap<String, NasApi.PlaylistDetail>(16, 0.75f, true) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<String, NasApi.PlaylistDetail>?,
        ): Boolean {
            if (size <= MAX) return false
            // 淘汰时把时间戳一起清掉，否则 at 会一直涨、还留着已经没数据的 id
            eldest?.key?.let { at.remove(it) }
            return true
        }
    }

    /** 每份详情的写入时间，用来判断要不要后台刷新 */
    private val at = HashMap<String, Long>()

    @Synchronized
    fun get(id: String): NasApi.PlaylistDetail? =
        map[id]?.takeIf { it.tracks.isNotEmpty() }

    /** 写入时间（0 = 没有这份数据） */
    @Synchronized
    fun atOf(id: String): Long = at[id] ?: 0L

    @Synchronized
    fun isFresh(id: String): Boolean = isFresh(atOf(id))

    fun isFresh(at: Long): Boolean =
        at > 0 && System.currentTimeMillis() - at < FRESH_MS

    @Synchronized
    fun put(id: String, detail: NasApi.PlaylistDetail, at: Long = System.currentTimeMillis()) {
        // 空结果不写：一次网络抽风别把好好的缓存顶掉
        if (detail.tracks.isEmpty()) return
        map[id] = detail
        this.at[id] = at
        dead.remove(id)
    }

    /**
     * 打开过、但一首曲目都拿不到的歌单。
     *
     * 上游对一部分歌单只给 `trackCount`、`tracks` 是空的（版权受限或歌单被删），
     * 列表上写着「20 首」，点进去一片空白 —— 用户只能一个个试。
     * 记录在进程内，列表渲染时把它们筛掉：点开过一次，下次就不再出现在列表里。
     * 不落盘：换一次网络环境（或上游修好）可能就正常，重启后会重新试。
     */
    private val dead = HashSet<String>()

    @Synchronized
    fun markDead(id: String) {
        dead.add(id)
        map.remove(id)
        at.remove(id)
    }

    @Synchronized
    fun isDead(id: String): Boolean = id in dead
}
