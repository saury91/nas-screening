package com.projectionroom.shared.catalog

import com.projectionroom.shared.model.Episode
import com.projectionroom.shared.model.Line
import com.projectionroom.shared.model.Quality
import com.projectionroom.shared.model.Source
import com.projectionroom.shared.model.Vod
import com.projectionroom.shared.net.Http
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * NAS 端的接口客户端。
 *
 * App 不直接聚合源站，而是走 NAS 的 `/api/online` 与 `/api/detail`：
 * 那边已经做过跨源合并、去重、画质探测，App 只需要渲染。
 * （NAS 不在时才回落到本地聚合，那是后面才接的能力。）
 */
class NasApi(private val http: Http, baseUrl: String) {

    private val base = baseUrl.trimEnd('/')

    /**
     * 解析也切到后台。
     *
     * http.get 内部已经切了 IO，但**返回后协程回到调用方线程** ——
     * 调用方基本是 LaunchedEffect（主线程），于是反序列化留在主线程上。
     * bundle 有 271KB~1.2MB，这一下足够让主线程掉几十帧。
     */
    private suspend fun <T> parse(block: suspend () -> T): T =
        withContext(Dispatchers.Default) { block() }

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    /**
     * 把源站直链包成 NAS 代理地址。
     *
     * 采集站的 m3u8 大多校验 Referer，Media3 直接拉会 403 或一直缓冲；
     * NAS 的 /api/proxy 会补 Referer 并重写播放列表里的分片地址。
     */
    fun proxied(raw: String): String = "$base/api/proxy?u=${raw.encodeQuery()}"

    @Serializable
    private data class SearchResp(val items: List<SearchItem> = emptyList())

    @Serializable
    private data class SearchItem(
        val name: String = "",
        val year: String = "",
        val pic: String = "",
        val type: String = "",
        @SerialName("site_count") val siteCount: Int = 0,
    )

    @Serializable
    private data class DetailResp(
        val name: String = "",
        val year: String = "",
        val pic: String = "",
        val type: String = "",
        @SerialName("area") val area: String = "",
        @SerialName("lang") val lang: String = "",
        @SerialName("score") val score: String = "",
        @SerialName("desc") val desc: String = "",
        val sources: List<DetailSource> = emptyList(),
    )

    @Serializable
    private data class DetailSource(
        val site: String = "",
        val remarks: String = "",
        val q: String = "",
        val count: Int = 0,
        val eps: List<DetailEp> = emptyList(),
    )

    @Serializable
    private data class DetailEp(val name: String = "", val url: String = "")

    /** 跨源搜索。NAS 那边已经合并过同名，这里拿到的就是最终列表。 */
    suspend fun search(keyword: String): List<Vod> {
        val kw = keyword.trim()
        if (kw.isEmpty()) return emptyList()
        val url = "$base/api/online?kw=${kw.encodeQuery()}"
        val text = http.get(url)
        val resp = parse { json.decodeFromString<SearchResp>(text) }
        return resp.items.map {
            Vod(
                name = it.name,
                year = it.year,
                pic = it.pic,
                type = it.type,
            )
        }
    }

    /** 详情：全部线路与选集。画质标签直接用 NAS 探好的结果，不再自己猜。 */
    suspend fun detail(name: String, year: String = ""): Vod? {
        if (name.isBlank()) return null
        val url = "$base/api/detail?n=${name.encodeQuery()}&y=${year.encodeQuery()}"
        val text = http.get(url)
        val d = parse { json.decodeFromString<DetailResp>(text) }
        if (d.name.isBlank()) return null
        return Vod(
            name = d.name,
            year = d.year,
            pic = d.pic,
            type = d.type,
            area = d.area,
            lang = d.lang,
            score = d.score,
            desc = d.desc,
            lines = d.sources.map { s ->
                Line(
                    site = s.site,
                    remarks = s.remarks,
                    // 服务端给过就信它，没给才自己认一次
                    quality = s.q.ifBlank {
                        Quality.from(s.remarks, s.eps.firstOrNull()?.url.orEmpty(), s.site)
                    },
                    episodes = s.eps.map { Episode(it.name, it.url) },
                )
            }.sortedWith(compareBy({ Quality.rank(it.quality) }, { -it.count })),
        )
    }

    /** 首页三块数据：继续观看 + 最近更新 + 按口味推荐。 */
    data class HomeFeed(
        val continueWatching: List<Vod> = emptyList(),
        val latest: List<Vod> = emptyList(),
        val liked: List<Vod> = emptyList(),
        val cats: List<CatGroup> = emptyList(),
    )

    @Serializable
    data class CatGroup(
        val cat: String = "",
        val count: Int = 0,
        val items: List<Vod> = emptyList(),
    )

    /** 片库一页 */
    data class BrowsePage(
        val items: List<Vod> = emptyList(),
        val total: Int = 0,
        val sites: Int = 0,
        val more: Boolean = false,
    )

    @Serializable
    private data class HomeResp(
        val history: List<Hist> = emptyList(),
        val rec: Rec = Rec(),
    )

    @Serializable
    private data class Hist(
        val kind: String = "",
        val title: String = "",
        val cover: String = "",
        val ep: String = "",
        val position: Double = 0.0,
        val duration: Double = 0.0,
    )

    @Serializable
    private data class Rec(
        val latest: List<RecItem> = emptyList(),
        val liked: List<RecItem> = emptyList(),
        val cats: List<Cat> = emptyList(),
    )

    @Serializable
    private data class Cat(
        val cat: String = "",
        val count: Int = 0,
        val items: List<RecItem> = emptyList(),
    )

    @Serializable
    private data class BrowseResp(
        val items: List<RecItem> = emptyList(),
        val total: Int = 0,
        val sites: Int = 0,
        val more: Boolean = false,
    )

    @Serializable
    private data class RecItem(
        val name: String = "",
        val year: String = "",
        val pic: String = "",
        val type: String = "",
        val desc: String = "",
        val score: String = "",
        val area: String = "",
    )

    /**
     * 配置包：**这是 App 唯一真正依赖 NAS 的东西**。
     *
     * 里面是片源清单 + 直播频道表 + 版本号，拉一次存本地就能长期用：
     * 之后搜索、详情、播放全部直连源站，NAS 关不关机都不影响。
     * 版本号只用来判断「要不要更新」，不是用来判断「能不能用」。
     */
    suspend fun bundle(): ConfigBundle {
        val d = parse { json.decodeFromString<BundleResp>(http.get("$base/api/config/bundle")) }
        return ConfigBundle(
            version = d.version,
            sources = d.sources.filter { it.api.startsWith("http") },
            live = LiveFeed(
                groups = d.live.groups.map { LiveGroup(it.group, it.count) },
                channels = d.live.items
                    .filter { it.name.isNotBlank() && it.urls.isNotEmpty() }
                    .map { LiveChannel(it.group, it.name, it.urls) },
            ),
            // 音乐接口段要原样带过去：漏了它就等于配置里下发了个没人读的字段
            music = d.music,
        )
    }

    @Serializable
    private data class BundleResp(
        val version: String = "",
        val generatedAt: Long = 0,
        val sources: List<Source> = emptyList(),
        val live: LiveResp = LiveResp(),
        val music: MusicCfg = MusicCfg(),
    )

    suspend fun home(): HomeFeed {
        val d = parse { json.decodeFromString<HomeResp>(http.get("$base/api/home")) }
        return HomeFeed(
            // 只取点播记录；直播/音乐不进「继续观看」
            continueWatching = d.history
                .filter { it.kind == "online" && it.title.isNotBlank() }
                .distinctBy { it.title }
                .map { h ->
                    Vod(
                        name = h.title,
                        pic = h.cover,
                        type = h.ep,
                        progress = if (h.duration > 0) (h.position / h.duration).toFloat().coerceIn(0f, 1f) else 0f,
                    )
                },
            latest = d.rec.latest.map { it.toVod() },
            liked = d.rec.liked.map { it.toVod() },
            cats = d.rec.cats.map { c ->
                CatGroup(c.cat, c.count, c.items.map { it.toVod() })
            },
        )
    }

    private fun RecItem.toVod() = Vod(
        name = name,
        year = year,
        pic = pic,
        type = type,
        area = area,
        score = score,
        desc = desc,
    )

    @Serializable
    data class LiveChannel(
        val group: String = "",
        val name: String = "",
        val urls: List<String> = emptyList(),
    )

    @Serializable
    data class LiveGroup(val name: String = "", val count: Int = 0)

    @Serializable
    data class LiveFeed(
        val groups: List<LiveGroup> = emptyList(),
        val channels: List<LiveChannel> = emptyList(),
    )

    @Serializable
    private data class LiveGroupRaw(val group: String = "", val count: Int = 0)

    @Serializable
    private data class LiveResp(
        val groups: List<LiveGroupRaw> = emptyList(),
        val items: List<LiveCh> = emptyList(),
    )

    @Serializable
    private data class LiveCh(
        val group: String = "",
        val name: String = "",
        val urls: List<String> = emptyList(),
    )

    /** 直播频道表（分组 + 频道，同名频道已由服务端合并多线路） */
    suspend fun live(): LiveFeed {
        val d = parse { json.decodeFromString<LiveResp>(http.get("$base/api/live")) }
        return LiveFeed(
            groups = d.groups.map { LiveGroup(it.group, it.count) },
            channels = d.items.filter { it.name.isNotBlank() && it.urls.isNotEmpty() }
                .map { LiveChannel(it.group, it.name, it.urls) },
        )
    }

    /** 片库：cat 用 slug（movie/series/anime/variety/documentary） */
    suspend fun browse(cat: String, page: Int = 1, year: String = "", area: String = ""): BrowsePage {
        val q = buildString {
            append("?cat=").append(cat.encodeQuery()).append("&pg=").append(page)
            if (year.isNotBlank()) append("&year=").append(year.encodeQuery())
            if (area.isNotBlank()) append("&area=").append(area.encodeQuery())
        }
        val d = parse { json.decodeFromString<BrowseResp>(http.get("$base/api/browse$q")) }
        return BrowsePage(
            items = d.items.map { it.toVod() },
            total = d.total,
            sites = d.sites,
            more = d.more,
        )
    }

    @Serializable
    private data class LibResp(val items: List<Hist> = emptyList())

    @Serializable
    private data class FavResp(val ok: Boolean = false, val fav: Boolean = false)

    /** 我的收藏 */
    suspend fun favorites(): List<Vod> {
        val d = parse { json.decodeFromString<LibResp>(http.get("$base/api/library?kind=fav")) }
        return d.items.filter { it.title.isNotBlank() }.map {
            Vod(name = it.title, pic = it.cover, type = it.ep)
        }
    }

    /** 播放记录（带进度） */
    suspend fun history(): List<Vod> {
        val d = parse { json.decodeFromString<LibResp>(http.get("$base/api/library?kind=history")) }
        return d.items.filter { it.kind == "online" && it.title.isNotBlank() }
            .distinctBy { it.title }
            .map {
                Vod(
                    name = it.title,
                    pic = it.cover,
                    type = it.ep,
                    progress = if (it.duration > 0) (it.position / it.duration).toFloat().coerceIn(0f, 1f) else 0f,
                )
            }
    }

    /** 上报播放进度（NAS 在线时的同步用；离线时由 App 本地库暂存） */
    suspend fun writeHistory(
        vod: Vod,
        ep: String,
        position: Long,
        duration: Long,
    ): Boolean {
        val body = buildString {
            append("{\"kind\":\"online\",\"key\":\"").append(vod.name.escapeJson())
            append("\",\"title\":\"").append(vod.name.escapeJson())
            append("\",\"cover\":\"").append(vod.pic.escapeJson())
            append("\",\"ep\":\"").append(ep.escapeJson())
            append("\",\"year\":\"").append(vod.year.escapeJson())
            append("\",\"position\":").append(position)
            append(",\"duration\":").append(duration)
            append("}")
        }
        val d = parse { json.decodeFromString<OkResp>(http.post("$base/api/history", body)) }
        return d.ok
    }

    @Serializable
    private data class OkResp(val ok: Boolean = false, val fav: Boolean = false)

    /** 切换收藏，返回切换后的状态 */
    suspend fun toggleFavorite(vod: Vod): Boolean {
        val body = buildString {
            append("{\"kind\":\"online\",\"key\":\"").append(vod.name.escapeJson())
            append("\",\"title\":\"").append(vod.name.escapeJson())
            append("\",\"cover\":\"").append(vod.pic.escapeJson())
            append("\"}")
        }
        val d = parse { json.decodeFromString<FavResp>(http.post("$base/api/fav", body)) }
        return d.fav
    }

    private fun String.escapeJson(): String = buildString {
        for (ch in this@escapeJson) {
            when (ch) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                else -> if (ch < ' ') append(' ') else append(ch)
            }
        }
    }

    // ── 音乐（在线歌单，不需要配置本地曲库）──────────────────

    data class Playlist(
        val id: String = "",
        val name: String = "",
        val cover: String = "",
        val count: Int = 0,
        val play: Long = 0,
    )

    data class Track(
        val id: String = "",
        val title: String = "",
        val artist: String = "",
        val cover: String = "",
    )

    data class PlaylistDetail(
        val id: String = "",
        val name: String = "",
        val cover: String = "",
        val desc: String = "",
        val count: Int = 0,
        val tracks: List<Track> = emptyList(),
    )

    data class MusicCat(val name: String = "", val id: String = "")
    data class MusicCatGroup(val name: String = "", val subs: List<MusicCat> = emptyList())

    @Serializable
    private data class PlResp(val items: List<PlItem> = emptyList())

    @Serializable
    private data class PlItem(
        val id: String = "",
        val name: String = "",
        val cover: String = "",
        val count: Int = 0,
        val play: Long = 0,
    )

    @Serializable
    private data class CatsResp(val groups: List<CatGroup2> = emptyList())

    @Serializable
    private data class CatGroup2(val name: String = "", val subs: List<Cat2> = emptyList())

    @Serializable
    private data class Cat2(val name: String = "", val id: String = "")

    @Serializable
    private data class PlDetail(
        val id: String = "",
        val name: String = "",
        val cover: String = "",
        val desc: String = "",
        val count: Int = 0,
        val tracks: List<Tr> = emptyList(),
    )

    @Serializable
    private data class Tr(
        val id: String = "",
        val title: String = "",
        val artist: String = "",
        val cover: String = "",
    )

    @Serializable
    private data class UrlResp(val url: String = "")

    suspend fun musicRecommend(): List<Playlist> {
        val d = parse { json.decodeFromString<PlResp>(http.get("$base/api/music/playlists/recommend")) }
        return d.items.filter { it.name.isNotBlank() }
            .map { Playlist(it.id, it.name, it.cover, it.count, it.play) }
    }

    suspend fun musicCats(): List<MusicCatGroup> {
        val d = parse { json.decodeFromString<CatsResp>(http.get("$base/api/music/playlists/cats")) }
        return d.groups.map { g -> MusicCatGroup(g.name, g.subs.map { MusicCat(it.name, it.id) }) }
    }

    /** 按分类取歌单（cat 为空就是推荐） */
    suspend fun musicPlaylists(cat: String = ""): List<Playlist> {
        if (cat.isBlank()) return musicRecommend()
        val url = "$base/api/music/playlists?cat=${cat.encodeQuery()}"
        val d = parse { json.decodeFromString<PlResp>(http.get(url)) }
        return d.items.filter { it.name.isNotBlank() }
            .map { Playlist(it.id, it.name, it.cover, it.count, it.play) }
    }

    suspend fun musicPlaylist(id: String): PlaylistDetail {
        val d = parse { json.decodeFromString<PlDetail>(http.get("$base/api/music/playlist?id=${id.encodeQuery()}")) }
        return PlaylistDetail(
            id = d.id, name = d.name, cover = d.cover, desc = d.desc, count = d.count,
            tracks = d.tracks.map { Track(it.id, it.title, it.artist, it.cover) },
        )
    }

    /** 播放地址带时效，每次播都要现取 */
    suspend fun musicTrackUrl(id: String): String {
        val d = json.decodeFromString<UrlResp>(
            http.get("$base/api/music/online/url?id=${id.encodeQuery()}")
        )
        return d.url
    }

    /** 自检/调试用：确认 NAS 在线，顺便拿到片源数量。 */
    suspend fun alive(): Boolean = runCatching { http.get("$base/api/status") }.isSuccess

}

/**
 * NAS 下发的配置包。
 *
 * App 真正需要从 NAS 拿的只有这一份东西：片源清单 + 直播频道表 + 版本号。
 * 存本地之后，搜索/详情/播放全部直连源站 —— NAS 关机不影响使用，
 * 它只是「配置更新源」。
 */
@Serializable
data class ConfigBundle(
    val version: String = "",
    val sources: List<Source> = emptyList(),
    val live: NasApi.LiveFeed = NasApi.LiveFeed(),
    /**
     * 音乐聚合接口（上游转发）。
     *
     * 以前这是写死在 APK 里的一行常量：接口换域名、被封，只能等下一个版本。
     * 现在跟着配置包走 —— NAS 改环境变量 `MUSICBOX_API` 重发配置即生效。
     * 老版本配置包里没有这一段，[MusicCfg.api] 为空时 App 用内置默认。
     */
    val music: MusicCfg = MusicCfg(),
)

/** 音乐聚合接口的地址与 Referer；api 为空表示「没下发，用内置默认」 */
@Serializable
data class MusicCfg(
    val api: String = "",
    val referer: String = "",
)
