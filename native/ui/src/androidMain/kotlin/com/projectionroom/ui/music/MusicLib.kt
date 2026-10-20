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
 * 一首歌在本地库里的记录。
 *
 * 带 id 和封面是为了「我的」里能直接点开重播 —— 旧的 [Legacy] 版本只存了
 * 「歌名 · 歌手」字符串，没有 id 就取不到播放地址，收藏列表只能看不能播。
 */
@Serializable
data class MusicItem(
    val id: String = "",
    val title: String,
    val artist: String = "",
    val cover: String = "",
    /** 最后一次交互时间：收藏列表和播放记录都按它倒序 */
    val at: Long = 0,
    val fav: Boolean = false,
    /** 播过的次数。>0 就算进过播放记录 */
    val plays: Int = 0,
)

/**
 * 音乐收藏 + 播放记录（本地）。
 *
 * 为什么不并进 `LibraryStore`：那是**影视**的主库，会同步到 NAS，
 * 歌名混进去之后网页版/电视端的「我的收藏」里会出现一堆查不到详情的条目。
 * 音乐这一版只做本地，跨端以后再说。
 *
 * 读写策略：全部走进程内缓存，落盘放 IO 线程。
 * 收藏是点一下就要变色的操作，同步等一次磁盘写会掉帧。
 */
object MusicLib {

    private const val FILE = "music_lib.json"
    /** 旧版的落点：SharedPreferences 里的字符串集合，只在迁移时读一次 */
    private const val LEGACY_PREF = "proom_music_fav"

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var cache: List<MusicItem>? = null

    /** 启动时预热：解析 JSON 和迁移旧数据都不该摊到第一次点击上 */
    fun warmUp(ctx: Context) {
        val app = ctx.applicationContext
        scope.launch { items(app) }
    }

    fun favorites(ctx: Context): List<MusicItem> =
        items(ctx).filter { it.fav }.sortedByDescending { it.at }

    fun history(ctx: Context): List<MusicItem> =
        items(ctx).filter { it.plays > 0 }.sortedByDescending { it.at }

    fun isFav(ctx: Context, id: String, title: String, artist: String): Boolean =
        items(ctx).any { it.fav && sameItem(it, id, title, artist) }

    /** 切换收藏，返回新状态 */
    fun toggle(ctx: Context, track: MusicTrack): Boolean {
        val app = ctx.applicationContext
        val cur = items(app)
        val hit = cur.firstOrNull { sameItem(it, track.id, track.title, track.artist) }
        val now = System.currentTimeMillis()
        val on = !(hit?.fav ?: false)
        val next = if (hit != null) {
            cur.map {
                if (it === hit) it.copy(fav = on, at = now, cover = it.cover.ifBlank { track.cover })
                else it
            }
        } else {
            cur + MusicItem(
                id = track.id, title = track.title, artist = track.artist,
                cover = track.cover, at = now, fav = on,
            )
        }
        put(app, next)
        return on
    }

    /** 播放记录：一首歌开始播就写一条（不区分是否收藏） */
    fun touch(ctx: Context, track: MusicTrack) {
        val app = ctx.applicationContext
        val cur = items(app)
        val now = System.currentTimeMillis()
        val hit = cur.firstOrNull { sameItem(it, track.id, track.title, track.artist) }
        val next = if (hit != null) {
            cur.map {
                if (it === hit) {
                    it.copy(
                        at = now,
                        plays = it.plays + 1,
                        // 接口偶尔换封面地址，用最新的
                        cover = track.cover.ifBlank { it.cover },
                        artist = it.artist.ifBlank { track.artist },
                    )
                } else it
            }
        } else {
            cur + MusicItem(
                id = track.id, title = track.title, artist = track.artist,
                cover = track.cover, at = now, plays = 1,
            )
        }
        put(app, next)
    }

    /* ── 内部 ── */

    /**
     * 同一个条目判定：优先 id（最可靠），迁过来的老数据没有 id，退回「歌名 + 歌手」。
     */
    private fun sameItem(it: MusicItem, id: String, title: String, artist: String): Boolean {
        if (id.isNotBlank() && it.id.isNotBlank()) return it.id == id
        return it.title == title && it.artist == artist
    }

    private fun items(ctx: Context): List<MusicItem> {
        cache?.let { return it }
        val loaded = runCatching {
            val f = File(ctx.filesDir, FILE)
            if (f.exists()) json.decodeFromString<List<MusicItem>>(f.readText()) else emptyList()
        }.getOrDefault(emptyList())
        val merged = if (loaded.isEmpty()) migrate(ctx) else loaded
        cache = merged
        return merged
    }

    private fun put(ctx: Context, list: List<MusicItem>) {
        // 记录会越攒越多，超过 300 条只留最近的，否则 JSON 会一直长
        val capped = list.sortedByDescending { it.at }.take(300)
        cache = capped
        scope.launch {
            runCatching {
                val tmp = File(ctx.filesDir, "$FILE.tmp")
                tmp.writeText(json.encodeToString(capped))
                tmp.renameTo(File(ctx.filesDir, FILE))
            }
        }
    }

    /**
     * 从旧版 SharedPreferences 迁移。
     *
     * 旧数据只有「歌名 · 歌手」字符串，没有 id 和封面 —— 迁移后能显示、不能直接播。
     * 之所以还迁：用户收藏过的东西凭空消失比不能播更糟。
     */
    private fun migrate(ctx: Context): List<MusicItem> {
        val old = runCatching {
            ctx.getSharedPreferences(LEGACY_PREF, Context.MODE_PRIVATE)
                .getStringSet("keys", emptySet())
                .orEmpty()
        }.getOrDefault(emptySet())
        if (old.isEmpty()) return emptyList()
        val list = old.map { raw ->
            val parts = raw.split(" · ", limit = 2)
            MusicItem(
                title = parts.firstOrNull().orEmpty(),
                artist = parts.getOrNull(1).orEmpty(),
                fav = true,
                at = 0,
            )
        }
        cache = list
        scope.launch {
            runCatching {
                File(ctx.filesDir, FILE).writeText(json.encodeToString(list))
                ctx.getSharedPreferences(LEGACY_PREF, Context.MODE_PRIVATE).edit().clear().apply()
            }
        }
        return list
    }
}
