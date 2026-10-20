package com.projectionroom.shared.catalog

import com.projectionroom.shared.model.Source
import com.projectionroom.shared.model.Vod
import com.projectionroom.shared.net.Http
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull

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
    ): Feed {
        if (sources.isEmpty()) return Feed()
        val catalog = Catalog(http)

        // 每个分类只挑「有该分类映射」的前几个源 —— 没有映射的源拉了也是错分类
        val picked = CATS.map { cat ->
            cat to sources.filter { it.typeIdsOf(cat).isNotEmpty() }.take(SOURCES_PER_CAT)
        }.filter { it.second.isNotEmpty() }
        if (picked.isEmpty()) return Feed()

        // 每个分类各自限时，**不要**在外面再套一个整体超时：
        // 整体超时一旦触发就返回空 Feed，「5 个分类里 4 个已经拉到了」也一起丢掉，
        // 表现就是明明有网、首页却一直是骨架屏。
        val groups = coroutineScope {
            picked.map { (cat, srcs) ->
                async {
                    val items = withTimeoutOrNull(deadlineMs) {
                        runCatching {
                            // 每源只取 1 个分类 id：首屏只要「最新几条」，
                            // 一个源挂 20 个 type_id 会同时发 20 个请求，反而拖慢。
                            catalog.browse(
                                cat, 1, "", "", srcs,
                                deadlineMs = deadlineMs, typeIdsPerSource = 1,
                            )
                        }.getOrDefault(emptyList())
                    }.orEmpty()
                    NasApi.CatGroup(cat, items.size, items.take(perCat)) to items
                }
            }.awaitAll()
        }

        val cats = groups.map { it.first }.filter { it.items.isNotEmpty() }
        val latest = groups.asSequence()
            .flatMap { it.second.asSequence() }
            .distinctBy { Catalog.normKey(it.name) }
            .sortedWith(compareByDescending<Vod> { it.year }.thenByDescending { it.lines.size })
            .take(latestLimit)
            .toList()

        return Feed(latest = latest, cats = cats)
    }
}
