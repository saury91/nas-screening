package com.projectionroom.shared.catalog

import com.projectionroom.shared.model.Source
import com.projectionroom.shared.model.Vod
import com.projectionroom.shared.net.Http
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 本地首页：不经过 NAS，自己按分类从各采集站拉「最新」拼出来。
 *
 * 首页以前是 NAS 生成的（`/api/home` 的推荐算法），现在改成 App 自己算：
 * NAS 一年不开机，只要本地还存着片源地址，首页照样能出内容。
 *
 * 组成：
 *  - `cats`   ：电影 / 剧集 / 动漫 / 综艺 / 纪录片，各取第一页；
 *  - `latest` ：上面这些合起来按年份倒序，去重后取前若干条，给 Hero 和「最近更新」。
 *
 * 「继续观看」「收藏」不在这里 —— 它们是本机数据，壳直接从本地库拼。
 */
object LocalHome {

    /** 首页展示的分类，顺序即货架顺序 */
    val CATS = listOf("电影", "剧集", "动漫", "综艺", "纪录片")

    /** 每个分类只用前几个源：多拉几十个源对首屏没意义，还慢 */
    private const val SOURCES_PER_CAT = 4

    data class Feed(
        val latest: List<Vod> = emptyList(),
        val cats: List<NasApi.CatGroup> = emptyList(),
    ) {
        val isEmpty: Boolean get() = latest.isEmpty() && cats.isEmpty()
    }

    suspend fun build(
        http: Http,
        sources: List<Source>,
        latestLimit: Int = 24,
        perCat: Int = 14,
        deadlineMs: Long = 10_000,
    ): Feed = buildStream(http, sources, latestLimit, perCat, deadlineMs).last()

    /**
     * 流式首页：**哪个分类先拉到就先发哪个分类的货架**，不等全部分类跑完。
     *
     * 以前是 5 个分类 `awaitAll`，最慢那个 10 秒超时，用户就得盯着空首页等 10 秒
     * （哪怕「电影」300 毫秒就已经到了 14 部）。现在第一屏内容 300 毫秒就能出来，
     * 剩下的货架陆续补上，和「本地有缓存」的观感一致。
     *
     * 每次发的都是完整 Feed 快照，UI 直接替换。
     */
    fun buildStream(
        http: Http,
        sources: List<Source>,
        latestLimit: Int = 24,
        perCat: Int = 14,
        deadlineMs: Long = 10_000,
    ): Flow<Feed> = channelFlow {
        if (sources.isEmpty()) {
            send(Feed())
            return@channelFlow
        }
        val catalog = Catalog(http)

        // 每个分类只挑「有该分类映射」的前几个源 —— 没有映射的源拉了也是错分类
        val picked = CATS.map { cat ->
            cat to sources.filter { it.typeIdsOf(cat).isNotEmpty() }.take(SOURCES_PER_CAT)
        }.filter { it.second.isNotEmpty() }
        if (picked.isEmpty()) {
            send(Feed())
            return@channelFlow
        }

        // 货架顺序必须稳定：先到哪个分类不能决定它排第几，否则每次刷新首页排版都在跳
        val order = picked.map { it.first }
        val done = mutableMapOf<String, List<Vod>>()
        val mutex = Mutex()

        coroutineScope {
            picked.forEach { (cat, srcs) ->
                launch {
                    // 分类内部再走一次流式：一个源回来就先把这个货架画出来，
                    // 而不是等本分类的 4 个源都齐
                    catalog.browseStream(
                        cat, 1, "", "", srcs,
                        deadlineMs = deadlineMs, typeIdsPerSource = 1,
                    ).collect { partial ->
                        if (partial.isEmpty()) return@collect
                        val snap = mutex.withLock {
                            done[cat] = partial
                            assemble(order, done, latestLimit, perCat)
                        }
                        send(snap)
                    }
                }
            }
        }

        // 某个分类全超时（一条都没回来）时上面不会发它的货架；
        // 这里补一次最终态，保证「拿到的都上屏了」
        val tail = mutex.withLock { assemble(order, done, latestLimit, perCat) }
        send(tail)
    }

    /** 把「已完成的分类」拼成一次完整的首页快照 */
    private fun assemble(
        order: List<String>,
        done: Map<String, List<Vod>>,
        latestLimit: Int,
        perCat: Int,
    ): Feed {
        val cats = order.mapNotNull { cat ->
            val items = done[cat].orEmpty()
            if (items.isEmpty()) null else NasApi.CatGroup(cat, items.size, items.take(perCat))
        }
        val latest = order.asSequence()
            .flatMap { done[it].orEmpty().asSequence() }
            .distinctBy { Catalog.normKey(it.name) }
            .sortedWith(compareByDescending<Vod> { it.year }.thenByDescending { it.lines.size })
            .take(latestLimit)
            .toList()
        return Feed(latest = latest, cats = cats)
    }
}
