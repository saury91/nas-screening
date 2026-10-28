package com.projectionroom.ui.music

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * 自建歌单。
 *
 * 曲目复用 [MusicItem]（id / 歌名 / 歌手 / 封面），和收藏库同一套字段，
 * 「我的歌单」里点开就能直接播，不用再从接口补一次详情。
 */
@Serializable
data class UserPlaylist(
    val id: String = "",
    val name: String = "",
    val cover: String = "",
    /** 最后一次改动时间：列表按它倒序，刚建/刚加歌的排最前 */
    val at: Long = 0,
    val tracks: List<MusicItem> = emptyList(),
)

/**
 * 收藏的在线歌单（上游那种别人建的）。
 *
 * 只存卡片要用的那几样：id 能重新拉详情，名字/封面/数量用于离线时也能显示，
 * 整份曲目不再存第二遍 —— 详情本身已经有磁盘缓存（`PlaylistStore`）。
 */
@Serializable
data class FavPlaylist(
    val id: String = "",
    val name: String = "",
    val cover: String = "",
    val count: Int = 0,
    val at: Long = 0,
)

/**
 * 歌单库（本地）：自建歌单 + 收藏的在线歌单。
 *
 * 和 [MusicLib] 分开两个文件，是因为它们改写的时机完全不同：
 * 心跳（播放记录）几乎每首歌都在动 `music_lib.json`，
 * 歌单是低频操作，混在一起会让每次心跳都重写一遍歌单结构。
 *
 * 读写策略与 [MusicLib] 一致：进程内缓存 + IO 线程全量重写，
 * 点一下就变色的操作不能同步等磁盘。
 */
object MusicShelf {

    /** 「我喜欢的音乐」：不落库，它就是 [MusicLib] 里 fav = true 的那批歌 */
    const val LIKED_ID = "liked"
    const val LIKED_NAME = "我喜欢的音乐"

    private const val FILE = "music_shelf.json"
    private const val MAX_PLAYLISTS = 40
    private const val MAX_TRACKS = 500

    private val json = Json { ignoreUnknownKeys = true; isLenient = true; encodeDefaults = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var cache: Shelf? = null

    @Serializable
    private data class Shelf(
        val playlists: List<UserPlaylist> = emptyList(),
        val favs: List<FavPlaylist> = emptyList(),
    )

    /** 启动时预热：别把解析 JSON 摊到第一次点心形上 */
    fun warmUp(ctx: Context) {
        val app = ctx.applicationContext
        scope.launch { shelfOf(app) }
    }

    /* ── 自建歌单 ── */

    fun playlists(ctx: Context): List<UserPlaylist> =
        shelfOf(ctx).playlists.sortedByDescending { it.at }

    fun playlist(ctx: Context, id: String): UserPlaylist? =
        shelfOf(ctx).playlists.firstOrNull { it.id == id }

    /** 新建（名字为空时给个默认名，返回新歌单） */
    fun create(ctx: Context, name: String): UserPlaylist {
        val app = ctx.applicationContext
        val cur = shelfOf(app)
        val now = System.currentTimeMillis()
        val item = UserPlaylist(
            id = "p" + now.toString(36),
            name = name.trim().ifBlank { "新建歌单" },
            at = now,
        )
        // 新歌单插到最前；超过上限砍掉最老的（空的先砍）
        val next = (listOf(item) + cur.playlists).take(MAX_PLAYLISTS)
        put(app, cur.copy(playlists = next))
        return item
    }

    fun rename(ctx: Context, id: String, name: String) {
        val app = ctx.applicationContext
        val cur = shelfOf(app)
        val clean = name.trim()
        if (clean.isBlank()) return
        put(
            app,
            cur.copy(
                playlists = cur.playlists.map {
                    if (it.id == id) it.copy(name = clean, at = System.currentTimeMillis()) else it
                },
            ),
        )
    }

    fun remove(ctx: Context, id: String) {
        val app = ctx.applicationContext
        val cur = shelfOf(app)
        put(app, cur.copy(playlists = cur.playlists.filterNot { it.id == id }))
    }

    /* ── 歌单里的歌 ── */

    fun contains(ctx: Context, playlistId: String, track: MusicTrack): Boolean {
        val p = playlist(ctx, playlistId) ?: return false
        return p.tracks.any { same(it, track) }
    }

    /**
     * 这首歌在哪些自建歌单里（不含「我喜欢的音乐」）。
     *
     * 播放页那颗心点下去要按「在几个歌单里」分流：只在一个时点它就是取消，
     * 直接删掉比再弹一层让人确认快；多处都有才需要挑一个。
     */
    fun playlistsWith(ctx: Context, track: MusicTrack): List<UserPlaylist> =
        playlists(ctx).filter { contains(ctx, it.id, track) }

    /** 收进歌单；已经在里面就不重复加，返回是否真的加进去了 */
    fun add(ctx: Context, playlistId: String, track: MusicTrack): Boolean {
        val app = ctx.applicationContext
        val cur = shelfOf(app)
        val p = cur.playlists.firstOrNull { it.id == playlistId } ?: return false
        if (p.tracks.any { same(it, track) }) return false
        val item = MusicItem(
            id = track.id, title = track.title, artist = track.artist,
            cover = track.cover, at = System.currentTimeMillis(),
        )
        // 最新的排最前：刚收进来的那首应该在列表顶上
        val tracks = (listOf(item) + p.tracks).take(MAX_TRACKS)
        put(
            app,
            cur.copy(
                playlists = cur.playlists.map {
                    if (it.id == playlistId) {
                        it.copy(
                            tracks = tracks,
                            at = System.currentTimeMillis(),
                            // 歌单还没封面时，用第一首歌的封面顶上
                            cover = it.cover.ifBlank { track.cover },
                        )
                    } else it
                },
            ),
        )
        return true
    }

    fun removeTrack(ctx: Context, playlistId: String, track: MusicTrack) {
        val app = ctx.applicationContext
        val cur = shelfOf(app)
        put(
            app,
            cur.copy(
                playlists = cur.playlists.map {
                    if (it.id == playlistId) {
                        it.copy(tracks = it.tracks.filterNot { t -> same(t, track) })
                    } else it
                },
            ),
        )
    }

    /* ── 收藏的在线歌单 ── */

    fun favs(ctx: Context): List<FavPlaylist> = shelfOf(ctx).favs.sortedByDescending { it.at }

    fun isFav(ctx: Context, id: String): Boolean = shelfOf(ctx).favs.any { it.id == id }

    /** 收藏 / 取消收藏一个在线歌单，返回新状态 */
    fun toggleFav(ctx: Context, id: String, name: String, cover: String, count: Int): Boolean {
        val app = ctx.applicationContext
        val cur = shelfOf(app)
        val hit = cur.favs.firstOrNull { it.id == id }
        val on = hit == null
        val next = if (on) {
            listOf(
                FavPlaylist(id = id, name = name, cover = cover, count = count, at = System.currentTimeMillis()),
            ) + cur.favs
        } else {
            cur.favs.filterNot { it.id == id }
        }
        put(app, cur.copy(favs = next.take(MAX_PLAYLISTS)))
        return on
    }

    /* ── 内部 ── */

    /** 同一首歌：有 id 比 id，没 id 退回「歌名 + 歌手」（老数据没有 id） */
    private fun same(a: MusicItem, t: MusicTrack): Boolean {
        if (a.id.isNotBlank() && t.id.isNotBlank()) return a.id == t.id
        return a.title == t.title && a.artist == t.artist
    }

    private fun shelfOf(ctx: Context): Shelf {
        cache?.let { return it }
        val loaded = runCatching {
            val f = File(ctx.filesDir, FILE)
            if (f.exists()) json.decodeFromString<Shelf>(f.readText()) else Shelf()
        }.getOrDefault(Shelf())
        cache = loaded
        return loaded
    }

    private fun put(ctx: Context, shelf: Shelf) {
        cache = shelf
        scope.launch {
            runCatching {
                val tmp = File(ctx.filesDir, "$FILE.tmp")
                tmp.writeText(json.encodeToString(shelf))
                tmp.renameTo(File(ctx.filesDir, FILE))
            }
        }
    }
}
