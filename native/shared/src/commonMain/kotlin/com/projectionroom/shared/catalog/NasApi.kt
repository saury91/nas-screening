package com.projectionroom.shared.catalog

import com.projectionroom.shared.model.Episode
import com.projectionroom.shared.model.Line
import com.projectionroom.shared.model.Quality
import com.projectionroom.shared.model.Vod
import com.projectionroom.shared.net.Http
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
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

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

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
        val resp = json.decodeFromString<SearchResp>(text)
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
        val d = json.decodeFromString<DetailResp>(text)
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

    suspend fun home(): HomeFeed {
        val d = json.decodeFromString<HomeResp>(http.get("$base/api/home"))
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

    data class LiveChannel(
        val group: String = "",
        val name: String = "",
        val urls: List<String> = emptyList(),
    )

    data class LiveGroup(val name: String = "", val count: Int = 0)

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
        val d = json.decodeFromString<LiveResp>(http.get("$base/api/live"))
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
        val d = json.decodeFromString<BrowseResp>(http.get("$base/api/browse$q"))
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
        val d = json.decodeFromString<LibResp>(http.get("$base/api/library?kind=fav"))
        return d.items.filter { it.title.isNotBlank() }.map {
            Vod(name = it.title, pic = it.cover, type = it.ep)
        }
    }

    /** 播放记录（带进度） */
    suspend fun history(): List<Vod> {
        val d = json.decodeFromString<LibResp>(http.get("$base/api/library?kind=history"))
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

    /** 切换收藏，返回切换后的状态 */
    suspend fun toggleFavorite(vod: Vod): Boolean {
        val body = buildString {
            append("{\"kind\":\"online\",\"key\":\"").append(vod.name.escapeJson())
            append("\",\"title\":\"").append(vod.name.escapeJson())
            append("\",\"cover\":\"").append(vod.pic.escapeJson())
            append("\"}")
        }
        val d = json.decodeFromString<FavResp>(http.post("$base/api/fav", body))
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
        val d = json.decodeFromString<PlResp>(http.get("$base/api/music/playlists/recommend"))
        return d.items.filter { it.name.isNotBlank() }
            .map { Playlist(it.id, it.name, it.cover, it.count, it.play) }
    }

    suspend fun musicCats(): List<MusicCatGroup> {
        val d = json.decodeFromString<CatsResp>(http.get("$base/api/music/playlists/cats"))
        return d.groups.map { g -> MusicCatGroup(g.name, g.subs.map { MusicCat(it.name, it.id) }) }
    }

    /** 按分类取歌单（cat 为空就是推荐） */
    suspend fun musicPlaylists(cat: String = ""): List<Playlist> {
        if (cat.isBlank()) return musicRecommend()
        val url = "$base/api/music/playlists?cat=${cat.encodeQuery()}"
        val d = json.decodeFromString<PlResp>(http.get(url))
        return d.items.filter { it.name.isNotBlank() }
            .map { Playlist(it.id, it.name, it.cover, it.count, it.play) }
    }

    suspend fun musicPlaylist(id: String): PlaylistDetail {
        val d = json.decodeFromString<PlDetail>(http.get("$base/api/music/playlist?id=${id.encodeQuery()}"))
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
