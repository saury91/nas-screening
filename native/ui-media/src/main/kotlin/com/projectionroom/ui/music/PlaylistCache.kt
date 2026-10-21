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

    private const val MAX = 8

    /** accessOrder=true：最近看过的排到队尾，淘汰最久没打开的 */
    private val map = object : LinkedHashMap<String, NasApi.PlaylistDetail>(16, 0.75f, true) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<String, NasApi.PlaylistDetail>?,
        ): Boolean = size > MAX
    }

    @Synchronized
    fun get(id: String): NasApi.PlaylistDetail? =
        map[id]?.takeIf { it.tracks.isNotEmpty() }

    @Synchronized
    fun put(id: String, detail: NasApi.PlaylistDetail) {
        // 空结果不写：一次网络抽风别把好好的缓存顶掉
        if (detail.tracks.isEmpty()) return
        map[id] = detail
    }
}
