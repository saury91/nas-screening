package com.projectionroom.shared.catalog

import com.projectionroom.shared.model.Episode
import com.projectionroom.shared.model.Line
import com.projectionroom.shared.model.Quality
import com.projectionroom.shared.model.Source
import com.projectionroom.shared.model.Vod
import com.projectionroom.shared.net.Http
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 跨源聚合搜索。
 *
 * 这是整个 App 最核心、也最容易被写坏的一段：几十个源并发查、同一部片要合并、
 * 结果还要按「像不像用户要找的」排序。所以它被抽成纯逻辑，网络由 [Http] 注入，
 * 单测里可以完全离线跑。
 */
class Catalog(private val http: Http) {

    /**
     * @param deadlineMs 整体限时。总有源会超时，到点先用已经回来的，不等慢的。
     */
    suspend fun search(
        keyword: String,
        sources: List<Source>,
        deadlineMs: Long = 8_000,
    ): List<Vod> {
        val kw = keyword.trim()
        if (kw.isBlank() || sources.isEmpty()) return emptyList()
        val key = normKey(kw)

        val hits = withTimeoutOrNull(deadlineMs) {
            coroutineScope {
                sources.map { src ->
                    async {
                        runCatching { fetchOne(src, kw, key) }.getOrDefault(emptyList())
                    }
                }.awaitAll()
            }
        } ?: return emptyList()

        return merge(hits.flatten(), kw)
    }

    private suspend fun fetchOne(src: Source, kw: String, key: String): List<Pair<Source, MacCms.Raw>> {
        val text = http.get(MacCms.searchUrl(src.api, kw))
        return MacCms.parseList(text)
            .filter { key.isEmpty() || normKey(it.name).contains(key) }
            .filter { MacCms.parsePlayUrl(it.playUrl).isNotEmpty() }
            .map { src to it }
    }

    private fun merge(rows: List<Pair<Source, MacCms.Raw>>, keyword: String): List<Vod> {
        val grouped = LinkedHashMap<String, MutableList<Pair<Source, MacCms.Raw>>>()
        for (row in rows) {
            grouped.getOrPut(normKey(row.second.name)) { mutableListOf() }.add(row)
        }

        return grouped.map { (_, group) ->
            val first = group.first().second
            val lines = group.map { (src, raw) ->
                val eps = MacCms.parsePlayUrl(raw.playUrl)
                Line(
                    site = src.name,
                    remarks = raw.remarks,
                    quality = Quality.from(raw.remarks, eps.firstOrNull()?.url.orEmpty(), src.name),
                    episodes = eps,
                )
            }.sortedWith(
                compareBy({ Quality.rank(it.quality) }, { -it.count })
            )

            Vod(
                name = first.name,
                year = group.firstNotNullOfOrNull { it.second.year.ifBlank { null } }.orEmpty(),
                pic = group.firstNotNullOfOrNull { it.second.pic.ifBlank { null } }.orEmpty(),
                type = group.firstNotNullOfOrNull { it.second.type.ifBlank { null } }.orEmpty(),
                area = group.firstNotNullOfOrNull { it.second.area.ifBlank { null } }.orEmpty(),
                lang = group.firstNotNullOfOrNull { it.second.lang.ifBlank { null } }.orEmpty(),
                score = group.mapNotNull { it.second.score.toDoubleOrNull() }.maxOrNull()?.toString().orEmpty(),
                desc = group.map { it.second.desc }.maxByOrNull { it.length }.orEmpty(),
                lines = lines,
            )
        }.sortedWith(
            compareByDescending<Vod> { relevance(it.name, keyword) }
                .thenByDescending { it.lines.size }
                .thenByDescending { it.episodeCount }
        )
    }

    companion object {
        private val SEP = Regex("[\\s·:：\\-—_·]+")
        private val BRACKETS = Regex("[（(【\\[].*?[）)】\\]]")
        private val TAIL_YEAR = Regex("(19|20)\\d{2}$")

        private val LATIN = Regex("[a-z]+")
        private val CJK = 0x4E00..0x9FFF

        /**
         * 归一化片名：跨站同名不同写法要能合到一起。
         *
         * 三件事：
         *   1. 去空格与标点（`沙 丘` → `沙丘`）；
         *   2. 去括注（`沙丘(2021)` → `沙丘`）；
         *   3. 剥末尾年份（`沙丘1984` 与 `沙丘2021` 各自剥掉，靠 year 字段区分）。
         *
         * 另外处理「中文名 + 英文副标题」：`沙丘 Dune` 归到 `沙丘`。
         * 但**不删数字** —— 否则 `沙丘` 和 `沙丘2` 会被合成同一部，这是两部电影。
         */
        fun normKey(name: String): String {
            val base = name.lowercase()
                .replace(SEP, "")
                .replace(BRACKETS, "")
                .replace(TAIL_YEAR, "")
            val cjk = base.replace(LATIN, "")
            // 至少两个字且含中文才采用，避免 `dune` 这种纯英文名被清空
            return if (cjk.length >= 2 && cjk.any { it.code in CJK }) cjk else base
        }

        /** 相关度只做粗排：完全相等 > 前缀 > 包含。够用，且不需要中文分词。 */
        fun relevance(name: String, kw: String): Int {
            val n = normKey(name)
            val k = normKey(kw)
            return when {
                n == k -> 3
                n.startsWith(k) -> 2
                n.contains(k) -> 1
                else -> 0
            }
        }
    }
}

/** 便利扩展：把一条线路的第一个可播地址取出来，播放器起播时用。 */
fun Line.firstUrl(): String = episodes.firstOrNull()?.url.orEmpty()
fun Vod.firstEpisode(): Episode? = lines.firstOrNull()?.episodes?.firstOrNull()
