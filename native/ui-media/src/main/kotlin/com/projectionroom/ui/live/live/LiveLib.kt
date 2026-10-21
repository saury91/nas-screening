package com.projectionroom.ui.live

import android.content.Context
import com.projectionroom.shared.catalog.NasApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * 一个频道在本地库里的记录。
 *
 * 存了 urls 才能从「我的」直接开播 —— 只存台名的话，点进收藏还得回去
 * 让用户自己找这个台。直播源地址会变，所以以 NAS 最新配置为准：
 * 开播时如果拿不到可用地址，再回退到本地这份。
 */
@Serializable
data class LiveItem(
    val name: String,
    val group: String = "",
    val urls: List<String> = emptyList(),
    val at: Long = 0,
    val fav: Boolean = false,
    /** 播过几次。>0 就算进过播放记录 */
    val plays: Int = 0,
)

/**
 * 直播频道：收藏 + 最近观看（本地）。
 *
 * 和音乐库同一个套路：进程内缓存 + 落盘走 IO 线程。
 * 直播不进 `LibraryStore` —— 那是影视的主库，会同步给 NAS，
 * 网页端「继续观看」里冒出一堆 CCTV 没有意义。
 */
object LiveLib {

    private const val FILE = "live_lib.json"
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var cache: List<LiveItem>? = null

    fun warmUp(ctx: Context) {
        val app = ctx.applicationContext
        scope.launch { items(app) }
    }

    fun favorites(ctx: Context): List<LiveItem> =
        items(ctx).filter { it.fav }.sortedByDescending { it.at }

    fun history(ctx: Context): List<LiveItem> =
        items(ctx).filter { it.plays > 0 }.sortedByDescending { it.at }

    fun isFav(ctx: Context, name: String): Boolean =
        items(ctx).any { it.fav && it.name == name }

    /** 切换收藏，返回新状态 */
    fun toggle(ctx: Context, ch: NasApi.LiveChannel): Boolean {
        val app = ctx.applicationContext
        val cur = items(app)
        val now = System.currentTimeMillis()
        val hit = cur.firstOrNull { it.name == ch.name }
        val on = !(hit?.fav ?: false)
        val next = if (hit != null) {
            cur.map {
                if (it.name == ch.name) {
                    it.copy(fav = on, at = now, urls = ch.urls.ifEmpty { it.urls })
                } else it
            }
        } else {
            cur + LiveItem(
                name = ch.name, group = ch.group, urls = ch.urls, at = now, fav = on,
            )
        }
        put(app, next)
        return on
    }

    /** 播台就写一条记录：频道数会累积，靠 [put] 里的上限收敛 */
    fun touch(ctx: Context, ch: NasApi.LiveChannel) {
        val app = ctx.applicationContext
        val cur = items(app)
        val now = System.currentTimeMillis()
        val hit = cur.firstOrNull { it.name == ch.name }
        val next = if (hit != null) {
            cur.map {
                if (it.name == ch.name) {
                    it.copy(
                        at = now,
                        plays = it.plays + 1,
                        group = ch.group.ifBlank { it.group },
                        urls = ch.urls.ifEmpty { it.urls },
                    )
                } else it
            }
        } else {
            cur + LiveItem(
                name = ch.name, group = ch.group, urls = ch.urls, at = now, plays = 1,
            )
        }
        put(app, next)
    }

    /* ── 内部 ── */

    private fun items(ctx: Context): List<LiveItem> {
        cache?.let { return it }
        val loaded = runCatching {
            val f = File(ctx.filesDir, FILE)
            if (f.exists()) json.decodeFromString<List<LiveItem>>(f.readText()) else emptyList()
        }.getOrDefault(emptyList())
        cache = loaded
        return loaded
    }

    private fun put(ctx: Context, list: List<LiveItem>) {
        // 几百个台看一轮就上千条了，只留最近 200 条
        val capped = list.sortedByDescending { it.at }.take(200)
        cache = capped
        scope.launch {
            runCatching {
                val tmp = File(ctx.filesDir, "$FILE.tmp")
                tmp.writeText(json.encodeToString(capped))
                tmp.renameTo(File(ctx.filesDir, FILE))
            }
        }
    }
}
