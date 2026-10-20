package com.projectionroom.app

import android.content.Context
import com.projectionroom.shared.catalog.LocalHome
import com.projectionroom.shared.catalog.NasApi
import com.projectionroom.shared.model.Vod
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * 首页快照的本地缓存。
 *
 * 目的只有一个：**开机就有东西看**，哪怕 NAS 一年没开、哪怕此刻源站全超时。
 * 所以它没有有效期 —— 旧的也先渲染出来，然后后台再刷。
 */
@Serializable
data class HomeSnapshot(
    val at: Long = 0,
    val latest: List<Vod> = emptyList(),
    val cats: List<NasApi.CatGroup> = emptyList(),
) {
    fun toFeed(history: List<Vod>, liked: List<Vod>): NasApi.HomeFeed = NasApi.HomeFeed(
        continueWatching = history,
        latest = latest,
        liked = liked,
        cats = cats,
    )
}

object HomeCache {

    private const val FILE = "home.json"

    /** 多久算「值得再刷一次」。只影响要不要刷新，不影响能不能用 */
    const val FRESH_MS = 15 * 60 * 1000L

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    fun read(ctx: Context): HomeSnapshot? {
        val f = File(ctx.filesDir, FILE)
        if (!f.exists()) return null
        return runCatching { json.decodeFromString<HomeSnapshot>(f.readText()) }
            .getOrNull()
            ?.takeIf { it.latest.isNotEmpty() || it.cats.isNotEmpty() }
    }

    fun save(ctx: Context, feed: LocalHome.Feed) {
        runCatching {
            // 只存展示需要的字段：Vod 里带着全部线路和选集，
            // 90 多部片的完整 lines 能把快照撑到 1.8MB，启动解析白白卡几百毫秒。
            // 点进详情时会重新查（本地聚合），不依赖这份快照。
            fun slim(v: Vod) = v.copy(lines = emptyList())
            val snap = HomeSnapshot(
                at = System.currentTimeMillis(),
                latest = feed.latest.map { slim(it) },
                cats = feed.cats.map { it.copy(items = it.items.map { v -> slim(v) }) },
            )
            val tmp = File(ctx.filesDir, "$FILE.tmp")
            tmp.writeText(json.encodeToString(snap))
            tmp.renameTo(File(ctx.filesDir, FILE))
        }
    }

    fun isFresh(snap: HomeSnapshot?): Boolean =
        snap != null && System.currentTimeMillis() - snap.at < FRESH_MS
}
