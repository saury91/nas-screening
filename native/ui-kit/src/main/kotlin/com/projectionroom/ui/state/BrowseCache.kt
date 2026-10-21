package com.projectionroom.ui.state

import com.projectionroom.shared.model.Vod

/**
 * 片库页的进程内快照：**离开页面再回来时原样复原，不重拉**。
 *
 * 之前的问题是页面的 `remember` 全在 `Screen.Browse` 分支里，从详情/播放返回时
 * 整个分支退出组合再重新进入，`remember` 归零 → `LaunchedEffect(slug, year, area)`
 * 重跑一遍 → 十几个采集站又被问了一遍，屏幕上的列表从头刷新。
 *
 * 用户对这件事的直觉很准：「点返回，什么都没做，凭什么又刷一遍」。
 *
 * 所以这里把「筛选条件 + 列表 + 页码 + 滚动位置」一起存下来：
 *  - 返回页面 → 直接还原来时的样子（含滚动位置），一次请求都不发；
 *  - 切筛选 / 下拉刷新 → 正常走 [BrowseScreen] 自己的加载逻辑。
 *
 * 只存进程内：冷启动那一次还是老老实实拉（那时候本来也没有内容可显示）。
 */
object BrowseCache {

    /** 记住几个分类：电影 / 剧集 / 动漫 / 综艺 / 纪录片，够用 */
    private const val MAX = 6

    class Snapshot(
        val year: String,
        val area: String,
        val items: List<Vod>,
        val page: Int,
        val more: Boolean,
        val scroll: Int,
    )

    private val map = object : LinkedHashMap<String, Snapshot>(MAX, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Snapshot>): Boolean =
            size > MAX
    }

    fun get(slug: String): Snapshot? = synchronized(map) { map[slug] }

    fun put(slug: String, snapshot: Snapshot) {
        // 空列表不写：否则「点进片库、还没来得及出内容就返回」会把上一次的快照冲掉
        if (snapshot.items.isEmpty()) return
        synchronized(map) { map[slug] = snapshot }
    }

    /** 下拉刷新时用：让下一次进入重新拉 */
    fun invalidate(slug: String) {
        synchronized(map) { map.remove(slug) }
    }
}
