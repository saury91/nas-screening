package com.projectionroom.shared.catalog

import com.projectionroom.shared.model.Episode
import com.projectionroom.shared.model.Line
import com.projectionroom.shared.model.Quality
import com.projectionroom.shared.model.Source
import com.projectionroom.shared.model.Vod
import com.projectionroom.shared.net.Http
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.coroutineContext
import kotlin.time.TimeSource

/**
 * 跨源聚合搜索。
 *
 * 这是整个 App 最核心、也最容易被写坏的一段：几十个源并发查、同一部片要合并、
 * 结果还要按「像不像用户要找的」排序。所以它被抽成纯逻辑，网络由 [Http] 注入，
 * 单测里可以完全离线跑。
 */
class Catalog(
    private val http: Http,
    /**
     * 连不上的封面图床（NAS 探测后随配置包下发）。
     *
     * 片库/搜索是 App 直连采集站拉的，同一部片在不同源上封面域名不同。
     * 某个图床整体连不上时，
     * 合并多源数据时避开它，就不至于整页海报都是空白。
     */
    badPicHosts: List<String> = emptyList(),
) {

    private val badHosts: Set<String> = badPicHosts.asSequence()
        .map { it.trim().lowercase() }
        .filter { it.isNotEmpty() }
        .toSet()

    /** 取 URL 的 host[:port]，不是绝对地址就返回空串 */
    private fun hostOf(url: String): String {
        val i = url.indexOf("://")
        if (i < 0) return ""
        val rest = url.substring(i + 3)
        val j = rest.indexOf('/')
        return (if (j < 0) rest else rest.substring(0, j)).lowercase()
    }

    /** 同片多源的封面里挑一个图床没被判定为坏的，都没得挑就用第一个 */
    private fun pickPic(group: List<Pair<Source, MacCms.Raw>>): String {
        val all = group.mapNotNull { it.second.pic.trim().ifBlank { null } }.distinct()
        if (all.isEmpty()) return ""
        if (badHosts.isEmpty()) return all.first()
        return all.firstOrNull { hostOf(it) !in badHosts } ?: all.first()
    }

    /**
     * 一个源都没查到东西。
     *
     * 和「搜不到这部片」是两回事：前者该提示「片源没响应，重试」，
     * 后者才是「换个片名」。上层靠这个异常分开这两句话。
     */
    class SourcesUnavailable(message: String) : Exception(message)

    /**
     * @param deadlineMs 整体限时。总有源会超时，到点先用已经回来的，不等慢的。
     *
     * @throws SourcesUnavailable 所有源都失败（连不上/超时），一个都没回来。
     *     注意：源回来了但没这部片，返回空列表、不抛异常。
     */
    suspend fun search(
        keyword: String,
        sources: List<Source>,
        deadlineMs: Long = 8_000,
    ): List<Vod> {
        val kw = keyword.trim()
        if (kw.isBlank() || sources.isEmpty()) return emptyList()
        val key = normKey(kw)

        // 结果先落到「每个源自己的槽位」上，而不是共享的 MutableList：
        // deadline 一到就读已完成的那批立刻返回，此时慢源还在后台跑，
        // 共享 List 会被并发写（JVM 上是数据竞争，native 上更糟）。
        val slots = sources.map { src ->
            src to CompletableDeferred<List<Pair<Source, MacCms.Raw>>?>()
        }

        // 不用 coroutineScope：它离开作用域时会**等所有子协程结束**，而慢源卡在
        // 阻塞的 execute() 上、取消掐不断，于是「12 秒 deadline」实际要等到
        // 12 秒 + callTimeout(10 秒)。这里用独立 scope：到点读快照、cancel、
        // 马上返回，慢源在后台自己收尾（结果丢弃）。
        //
        // 继承调用方的 dispatcher 而不是写死 Default：调用方（MainActivity 的
        // doSearch）本来就在 withContext(Dispatchers.Default) 里；而虚拟时间的
        // 单测里写死 Default 会让 launch 跑到测试调度器之外，deadline 直接判空。
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        try {
            val jobs = slots.map { (src, slot) ->
                scope.launch {
                    // 成功 = 拿到列表（哪怕空的）；失败 complete(null)，
                    // 与「还没回来」一样不计入「源有响应」
                    slot.complete(runCatching { fetchOne(src, kw, key) }.getOrNull())
                }
            }
            withTimeoutOrNull(deadlineMs) { jobs.joinAll() }

            val arrived = slots.mapNotNull { (_, slot) ->
                if (slot.isCompleted) slot.getCompleted() else null
            }
            // 一个源都没回来 ≠ 搜不到这部片：上层据此说「片源没响应，重试」
            if (arrived.isEmpty()) {
                throw SourcesUnavailable("${sources.size} 个源都没响应")
            }
            return merge(arrived.flatten(), kw)
        } finally {
            scope.cancel()
        }
    }

    /**
     * 本地详情：只用片名 + 年份，把全部源搜一遍再合并。
     *
     * 语义和 NAS 的 `/api/detail` 一致（先按名搜，再挑年份对得上的那条），
     * 但它**不经过 NAS** —— NAS 关着也能拿到完整线路和选集。
     * 搜索结果本来就带着 lines，所以从搜索页进来时不需要再调这里。
     */
    suspend fun detail(
        name: String,
        year: String = "",
        sources: List<Source>,
        deadlineMs: Long = 10_000,
    ): Vod? {
        if (name.isBlank()) return null
        // 详情页不该因为「源全挂了」而崩：这里把 SourcesUnavailable 也当空处理，
        // 让调用方照常显示空态（详情是资源加载，不再向 NAS 兜底）
        val list = runCatching { search(name, sources, deadlineMs) }.getOrDefault(emptyList())
        if (list.isEmpty()) return null
        val key = normKey(name)
        return list.firstOrNull { normKey(it.name) == key && year.isNotBlank() && it.year == year }
            ?: list.firstOrNull { normKey(it.name) == key }
            ?: list.first()
    }

    /**
     * 流式详情：**每个源一回来就发一版**，线路只往后长，不重排。
     *
     * 为什么需要它：[detail] 要等所有源到齐（或到 deadline）才返回，线路多的片在
     * 电视上就是「一直骨架，几秒后线路突然全冒出来」。详情页要的其实是「先看到片子，
     * 线路一条条出现」。
     *
     * 顺序固定为到达顺序而不是质量排序 —— UI 侧线路是 LazyRow + index 状态，
     * 往中间插一条会让已渲染的 chip 全换位置、遥控器焦点也跟着跳。
     *
     * 空结果不 emit：调用方拿不到任何快照就说明「这部片没有可播线路」。
     */
    fun detailStream(
        name: String,
        year: String = "",
        sources: List<Source>,
        deadlineMs: Long = 10_000,
    ): Flow<Vod> = channelFlow {
        if (name.isBlank() || sources.isEmpty()) {
            close()
            return@channelFlow
        }
        val key = normKey(name)
        val group = mutableListOf<Pair<Source, MacCms.Raw>>()
        val mutex = Mutex()
        val start = TimeSource.Monotonic.markNow()

        for (src in sources) {
            launch(Dispatchers.Default) {
                // 到点了就不再发新请求：总有些源要拖很久，不能让它拖着整页
                if (start.elapsedNow().inWholeMilliseconds > deadlineMs) return@launch
                val text = runCatching { http.get(MacCms.searchUrl(src.api, name)) }.getOrNull()
                    ?: return@launch
                val hits = MacCms.parseList(text)
                    .filter { normKey(it.name) == key }
                    .filter { MacCms.parseLines(it.playUrl).isNotEmpty() }
                if (hits.isEmpty()) return@launch
                // 同名多条时优先年份对得上的，和 [detail] 的挑选规则保持一致
                val pick = (if (year.isNotBlank()) hits.firstOrNull { it.year == year } else null)
                    ?: hits.first()
                val snap = mutex.withLock {
                    group.add(src to pick)
                    toVod(group, sort = false)
                }
                send(snap)
            }
        }
    }

    private suspend fun fetchOne(src: Source, kw: String, key: String): List<Pair<Source, MacCms.Raw>> {
        val text = http.get(MacCms.searchUrl(src.api, kw))
        return MacCms.parseList(text)
            .filter { key.isEmpty() || normKey(it.name).contains(key) }
            .filter { MacCms.parseLines(it.playUrl).isNotEmpty() }
            .map { src to it }
    }

    /**
     * 把「同一个归一化片名」的一组多源记录合成一条 [Vod]。
     *
     * 线路跨源合并、每个字段取第一个非空。这里**不负责排序**：顺序由调用方定 ——
     * 搜索结果按相关度排，片库按「首次到达顺序」排（见 [browseStream]）。
     */
    private fun toVod(group: List<Pair<Source, MacCms.Raw>>, sort: Boolean = true): Vod {
        val first = group.first().second
        val lines = group.flatMap { (src, raw) ->
            // 一个源的一条记录里可能塞了好几条线路（`$$$` 分隔），逐个展开，
            // 否则「线路」列表会少一半，用户没得选
            MacCms.parseLines(raw.playUrl).mapIndexed { i, eps ->
                Line(
                    site = if (i == 0) src.name else "${src.name} · 线路${i + 1}",
                    remarks = raw.remarks,
                    quality = Quality.from(raw.remarks, eps.firstOrNull()?.url.orEmpty(), src.name),
                    episodes = eps,
                )
            }
        }.let { lines ->
            // 流式详情不能排序：线路是「来一条追加一条」，重排会让已经画出来的
            // chip 全部换位置，遥控器焦点也跟着飘。浏览/搜索走排序，详情走到达顺序。
            if (sort) {
                lines.sortedWith(compareBy({ Quality.rank(it.quality) }, { -it.count }))
            } else {
                lines
            }
        }

        return Vod(
            name = first.name,
            year = group.firstNotNullOfOrNull { it.second.year.ifBlank { null } }.orEmpty(),
            pic = pickPic(group),
            type = group.firstNotNullOfOrNull { it.second.type.ifBlank { null } }.orEmpty(),
            area = group.firstNotNullOfOrNull { it.second.area.ifBlank { null } }.orEmpty(),
            lang = group.firstNotNullOfOrNull { it.second.lang.ifBlank { null } }.orEmpty(),
            score = group.mapNotNull { it.second.score.toDoubleOrNull() }.maxOrNull()?.toString().orEmpty(),
            desc = group.map { it.second.desc }.maxByOrNull { it.length }.orEmpty(),
            lines = lines,
        )
    }

    private fun merge(
        rows: List<Pair<Source, MacCms.Raw>>,
        keyword: String,
    ): List<Vod> {
        val grouped = LinkedHashMap<String, MutableList<Pair<Source, MacCms.Raw>>>()
        for (row in rows) {
            grouped.getOrPut(normKey(row.second.name)) { mutableListOf() }.add(row)
        }

        return grouped.values.map { toVod(it) }.sortedWith(
            compareByDescending<Vod> { relevance(it.name, keyword) }
                .thenByDescending { it.lines.size }
                .thenByDescending { it.episodeCount }
        )
    }

    /**
     * 本地浏览：按大类拉各站的最新列表，合并去重。
     *
     * 分类 ID 来自配置包里的 `Source.cats`（NAS 探好的）；
     * 没有映射的源直接跳过 —— 宁可少几个源，也不要瞎猜 ID 拉回一堆无关片。
     */
    suspend fun browse(
        cat: String,
        page: Int = 1,
        year: String = "",
        area: String = "",
        sources: List<Source>,
        deadlineMs: Long = 9_000,
        /** 每个源最多用几个 type_id：分类映射里一个「电影」可能对应 20 个 id，全拉会炸 */
        typeIdsPerSource: Int = 3,
    ): List<Vod> = browseStream(
        cat, page, year, area, sources, deadlineMs, typeIdsPerSource,
    ).last()

    /**
     * 流式浏览：**每有一个源回来就发一次**「目前已拿到的全部内容的合并版」。
     *
     * 以前用的是 `awaitAll`：十几个源里最慢那个要等满 9 秒超时，用户就得对着
     * 骨架屏干等 9 秒 —— 尽管第一个源 300 毫秒就回来了。现在第一个源一到先画
     * 一屏海报，其余的陆续往网格里补，观感和「本地已缓存」一样。
     *
     * 每次发的都是**完整快照**而不是增量，UI 侧直接替换即可，不用自己合并去重；
     * 而且快照之间是「只往后追加」的关系：已上屏的条目位置永远不变。
     */
    fun browseStream(
        cat: String,
        page: Int = 1,
        year: String = "",
        area: String = "",
        sources: List<Source>,
        deadlineMs: Long = 9_000,
        /** 每个源最多用几个 type_id：分类映射里一个「电影」可能对应 20 个 id，全拉会炸 */
        typeIdsPerSource: Int = 3,
        /**
         * 两次刷新之间的最小间隔。源经常在几百毫秒内一起回来，
         * 不节流的话 20 个结果会让网格重排 20 次（电视上看得出来在抖）。
         */
        minEmitGapMs: Long = 320,
    ): Flow<List<Vod>> = channelFlow {
        val jobs = sources.mapNotNull { src ->
            val ids = src.typeIdsOf(cat)
            if (ids.isEmpty()) null else src to ids.take(typeIdsPerSource)
        }
        if (jobs.isEmpty()) {
            send(emptyList())
            return@channelFlow
        }

        // 追加式累积：key（归一化片名）按**首次出现**排队，后到的源只往队尾加。
        //
        // 以前是每回来一个源就把全量结果按年份重排一次，后果是第二个源一到，
        // 屏幕上已经出现过的卡片整体被挤走 —— 用户看到的是页面自己在抖。
        // 现在顺序 = 首次到达顺序：先到的那批位置永远不动，后来的只往后补。
        val slots = LinkedHashMap<String, MutableList<Pair<Source, MacCms.Raw>>>()
        // 和 slots 同序的已构建结果 + 反查表：新数据只重建它命中的那一条，
        // 其余 Vod 实例原样复用。这很重要 —— 每次从零重建 500 条的话，
        // UI 侧拿到的全是新对象，data class 相等比较失效，整屏都要重新组合。
        val built = ArrayList<Vod>()
        val index = HashMap<String, Int>()
        val mutex = Mutex()
        val clock = TimeSource.Monotonic.markNow()
        var lastSent = -minEmitGapMs

        coroutineScope {
            jobs.forEach { (src, ids) ->
                ids.forEach { tid ->
                    launch {
                        // 单个请求各自限时，不再「一个整体超时把全部结果丢掉」——
                        // 超时的那个只是缺席，不影响已经回来的
                        val rows = withTimeoutOrNull(deadlineMs) {
                            runCatching {
                                val text = http.get(MacCms.listUrl(src.api, tid, page))
                                MacCms.parseList(text)
                                    .filter { MacCms.parseLines(it.playUrl).isNotEmpty() }
                                    .map { src to it }
                            }.getOrDefault(emptyList())
                        }.orEmpty()
                            // 年份/地区筛选放在入队之前：入过的槽位就不再动，
                            // 免得筛掉之后队尾又冒出新条目
                            .filter { (_, r) ->
                                (year.isBlank() || r.year.trim().startsWith(year)) &&
                                    areaMatches(r.area, area)
                            }
                        if (rows.isEmpty()) return@launch

                        // 先在锁里把快照算出来，再在锁外 send：
                        // 持锁 send 一旦因为背压挂起，会把其它源也堵住
                        val snapshot = mutex.withLock {
                            for (row in rows) {
                                val key = normKey(row.second.name)
                                val group = slots.getOrPut(key) { mutableListOf() }
                                group.add(row)
                                val at = index[key]
                                if (at == null) {
                                    index[key] = built.size
                                    built.add(toVod(group))
                                } else {
                                    built[at] = toVod(group)
                                }
                            }
                            val now = clock.elapsedNow().inWholeMilliseconds
                            if (rows.isEmpty() || now - lastSent < minEmitGapMs) {
                                null
                            } else {
                                lastSent = now
                                built.toList()
                            }
                        }
                        if (snapshot != null) send(snapshot)
                    }
                }
            }
        }

        // 收尾补一次：被上面节流跳过的结果必须完整地发出去，
        // 否则用户会看到「刷新到一半就停住」
        val tail = mutex.withLock { built.toList() }
        if (tail.isNotEmpty()) {
            send(tail)
        } else {
            // 全部被年份/地区筛掉了：这里必须发一个空快照。
            // 不发的话调用方收不到任何数据，界面会一直挂着上一次筛选的结果 ——
            // 用户看到的就是「点了年份/地区，下面一点没变」。
            send(emptyList())
        }
    }

    /**
     * 地区筛选匹配。
     *
     * 各采集站对同一地区的写法不统一：有的写「内地」，有的写「中国大陆」；
     * 香港台湾也各写各的（「香港」/「中国香港」）。只做 contains 会漏掉一半。
     */
    private fun areaMatches(raw: String, want: String): Boolean {
        if (want.isBlank()) return true
        if (raw.isBlank()) return false
        if (raw.contains(want)) return true
        val aliases = when (want) {
            "内地" -> listOf("大陆")
            "中国香港" -> listOf("香港")
            "中国台湾" -> listOf("台湾")
            else -> emptyList()
        }
        return aliases.any { raw.contains(it) }
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
