package com.projectionroom.shared.catalog

import com.projectionroom.shared.net.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 在线歌单 —— App 自己直连，不经过 NAS。
 *
 * 用的是第三方公开的聚合接口（Meting 风格），**不需要登录、没有签名**：
 *   GET  /netease/playlist/recommend?limit=N      推荐歌单
 *   GET  /getPlaylistCategory                     分类树
 *   GET  /netease/playlist/category?type=&limit=  分类歌单
 *   GET  /meting/?type=playlist&id=              歌单详情（含曲目 id）
 *   GET  /meting/?type=url&id=                   播放地址（302 到 CDN）
 *
 * 只有聚合接口校验 Referer，上游 CDN 本身不校验 ——
 * 所以播放地址可以「现取现给」，播放器直连 CDN，NAS 完全不参与。
 *
 * [base] / [referer] / [lyricBase] 全部由配置包（`bundle.music`）给出。
 * **仓库里不留任何默认地址**：没下发就是关闭状态，一个请求都不发。
 * 接口域名换人、被封、挂掉的时候，改 NAS 的环境变量重发一次配置就能修，
 * 不用等下一个 APK 版本。
 */
class MusicApi(
    private val http: Http,
    private val base: String = DEFAULT_BASE,
    private val referer: String = REFERER,
    /** 歌词端点，同理由配置包下发；空 = 不请求，界面显示「暂无歌词」 */
    private val lyricBase: String = "",
    private val lyricReferer: String = "",
    /**
     * 歌单详情用单独一条连接、单独的超时。
     *
     * 详情是最慢的一个接口：中转站缓存没命中时要现去上游逐首解析，
     * 实测同一台机器上 0.3~6 秒，千首大歌单 8~12 秒 —— 卡在别的接口
     * 共用的 12 秒超时上，表现就是「转了半天最后说取不到」。
     */
    private val detailHttp: Http = http,
) {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** 没配接口地址时整块关闭：所有请求在这里短路，不发出去再失败 */
    private val ready: Boolean get() = base.isNotBlank()

    /**
     * 解析放后台。
     *
     * 歌单详情一次返回 178 首（含封面 URL），JSON 几百 KB；
     * 调用方是 LaunchedEffect（主线程），在这里解析会白白掉帧。
     */
    private suspend fun <T> parse(block: () -> T): T = withContext(Dispatchers.Default) { block() }

    // ── 推荐歌单 ──────────────────────────────────────────────
    suspend fun recommend(limit: Int = 14): List<NasApi.Playlist> {
        if (!ready) return emptyList()
        val txt = http.get("$base/netease/playlist/recommend?limit=${limit.coerceIn(1, 30)}", referer)
        return parse { runCatching { json.decodeFromString<List<PlRaw>>(txt) }.getOrDefault(emptyList()) }
            .mapNotNull { it.toPlaylist() }
    }

    // ── 分类树 ────────────────────────────────────────────────
    suspend fun categories(): List<NasApi.MusicCatGroup> {
        if (!ready) return emptyList()
        val txt = http.get("$base/getPlaylistCategory", referer)
        val roots = parse { runCatching { json.decodeFromString<List<CatRoot>>(txt) }.getOrDefault(emptyList()) }
        // 上游会返回多个音源，只取第一个（netease）
        val groups = roots.firstOrNull()?.category.orEmpty()
        return groups.mapNotNull { g ->
            val subs = g.sub.orEmpty()
                .filter { it.name.isNotBlank() }
                .map { NasApi.MusicCat(it.name, it.id.ifBlank { it.name }) }
            if (subs.isEmpty()) null else NasApi.MusicCatGroup(g.name, subs)
        }
    }

    // ── 分类下的歌单 ──────────────────────────────────────────
    suspend fun categoryPlaylists(cat: String, limit: Int = 24): List<NasApi.Playlist> {
        if (cat.isBlank() || !ready) return emptyList()
        val txt = http.get(
            "$base/netease/playlist/category?type=${cat.encodeQuery()}&limit=${limit.coerceIn(1, 60)}",
            referer,
        )
        return parse { runCatching { json.decodeFromString<List<PlRaw>>(txt) }.getOrDefault(emptyList()) }
            .mapNotNull { it.toPlaylist() }
    }

    // ── 搜歌 ─────────────────────────────────────────────────
    /**
     * 搜歌。
     *
     * 参数名必须是 `keywords`：换成 `keyword` 或随手加个 `limit`，上游都不报错，
     * 直接回 `{}` —— 看着跟「没搜到」一模一样，很容易当成接口没数据。
     * 返回是 meting 的 song 结构，id 就是上游 songId，播放和歌单里的曲目同一条路。
     */
    suspend fun search(keyword: String, limit: Int = 40): List<NasApi.Track> {
        val kw = keyword.trim()
        if (kw.isBlank() || !ready) return emptyList()
        val txt = http.get("$base/netease/search/song?keywords=${kw.encodeQuery()}", referer)
        return parse { runCatching { json.decodeFromString<List<TrackRaw>>(txt) }.getOrDefault(emptyList()) }
            .asSequence()
            .mapNotNull { t ->
                val sid = t.id.trim()
                if (sid.isEmpty() || t.name.isBlank()) null
                else NasApi.Track(sid, t.name.trim(), t.artist.trim(), t.pic.trim())
            }
            .distinctBy { it.id }
            .take(limit)
            .toList()
    }

    // ── 歌单详情 ──────────────────────────────────────────────
    suspend fun playlist(id: String, limit: Int = 400): NasApi.PlaylistDetail {
        if (id.isBlank() || !ready) return NasApi.PlaylistDetail(id = id)
        val txt = detailHttp.get("$base/meting/?server=netease&type=playlist&id=${id.encodeQuery()}", referer)
        val d = parse { runCatching { json.decodeFromString<PlDetailRaw>(txt) }.getOrNull() }
            ?: return NasApi.PlaylistDetail(id = id)
        val seen = HashSet<String>()
        val tracks = d.tracks
            .mapNotNull { t ->
                val sid = t.id.trim()
                if (sid.isEmpty() || !seen.add(sid)) null
                else NasApi.Track(
                    id = sid,
                    title = t.name.trim(),
                    artist = t.artist.trim(),
                    cover = t.pic.trim(),
                )
            }
            .filter { it.title.isNotBlank() }
        return NasApi.PlaylistDetail(
            id = id,
            name = d.name.trim(),
            cover = d.coverImgUrl.trim(),
            desc = (d.description.ifBlank { d.desc }).trim(),
            count = if (d.trackCount > 0) d.trackCount else tracks.size,
            tracks = tracks.take(limit),
        )
    }

    /**
     * 播放地址。
     *
     * 接口 302 到上游 CDN，这里把跳转**解开**再交给播放器：
     * 地址有时效（十几分钟），必须点击时现取；解开后还能避免播放器跟 302 时丢请求头。
     */
    suspend fun trackUrl(sid: String): String {
        if (sid.isBlank() || !ready) return ""
        val meting = "$base/meting/?server=netease&type=url&id=${sid.encodeQuery()}"
        return http.resolveRedirect(meting, referer).takeIf { it.startsWith("http") }.orEmpty()
    }

    /**
     * 歌词。
     *
     * 聚合接口的 `type=lyric` 实测 20 秒都不返回（上游被限），
     * 所以歌词走单独一条端点，地址由配置包（`bundle.music.lyricApi`）下发。
     * **App 里不留默认地址**：没下发就不请求，界面上显「暂无歌词」。
     */
    suspend fun lyric(sid: String): List<LrcLine> {
        if (sid.isBlank() || !ready || lyricBase.isBlank()) return emptyList()
        val txt = runCatching {
            http.get("$lyricBase?id=${sid.encodeQuery()}&lv=1&kv=1&tv=-1", lyricReferer)
        }.getOrNull() ?: return emptyList()
        val d = parse { runCatching { json.decodeFromString<LrcRaw>(txt) }.getOrNull() } ?: return emptyList()
        return Lrc.parse(d.lrc?.lyric.orEmpty())
    }

    /**
     * 判断是不是「试听片段」：整曲基本都在 1.2MB 以上，片段约 480KB。
     *
     * **拿不到长度时一律当完整曲目放行**。HEAD 被拒（405）、CDN 走 chunked
     * 都会让 [Http.contentLength] 返回 0，若把 0 也当成片段，整张歌单会被
     * 误判成「全部不可播」而逐首跳过 —— 宁可偶尔放过一首 30 秒的，
     * 也不能把正常曲目拦掉。
     */
    suspend fun isFullTrack(cdnUrl: String): Boolean {
        if (!ready) return true
        val size = http.contentLength(cdnUrl)
        return size <= 0L || size >= FULL_MIN
    }

    // ── 上游 JSON ─────────────────────────────────────────────

    @Serializable
    private data class LrcRaw(val lrc: LrcBody? = null)

    @Serializable
    private data class LrcBody(val lyric: String = "")

    @Serializable
    private data class PlRaw(
        val id: String = "",
        val name: String = "",
        val coverImgUrl: String = "",
        val trackCount: Int = 0,
        val playCount: Long = 0,
    ) {
        fun toPlaylist(): NasApi.Playlist? {
            if (id.isBlank() || name.isBlank()) return null
            return NasApi.Playlist(
                id = id, name = name, cover = coverImgUrl,
                count = trackCount, play = playCount,
            )
        }
    }

    @Serializable
    private data class CatRoot(val category: List<CatGroupRaw> = emptyList())

    @Serializable
    private data class CatGroupRaw(val name: String = "", val sub: List<CatSubRaw> = emptyList())

    @Serializable
    private data class CatSubRaw(val name: String = "", val id: String = "")

    @Serializable
    private data class PlDetailRaw(
        val name: String = "",
        val coverImgUrl: String = "",
        val description: String = "",
        val desc: String = "",
        val trackCount: Int = 0,
        val tracks: List<TrackRaw> = emptyList(),
    )

    @Serializable
    private data class TrackRaw(
        val id: String = "",
        val name: String = "",
        val artist: String = "",
        val pic: String = "",
    )

    companion object {
        /**
         * 在线音乐聚合接口。**默认空**：仓库不自带任何在线音源。
         *
         * 地址由 NAS 下发的配置包（music.api）填进来；没配就整块关闭，
         * 音乐页只显示本地目录与 Subsonic，不访问任何上游。
         */
        const val DEFAULT_BASE = ""

        /** 聚合接口对 Referer 校验很死，缺了直接 403；同样由配置包下发 */
        const val REFERER = ""

        /** 整曲最短 1.2MB；试听片段固定 30 秒、约 480KB */
        const val FULL_MIN = 1200L * 1024L
    }
}
