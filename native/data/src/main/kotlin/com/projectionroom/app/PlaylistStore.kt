package com.projectionroom.app

import android.content.Context
import com.projectionroom.shared.catalog.NasApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest

/**
 * 歌单详情的落盘缓存。
 *
 * 为什么非要落盘：详情走的是第三方中转接口，缓存没命中时它要现去网易云
 * 逐首解析。实测同一个接口 0.3~6 秒，千首大歌单 8~12 秒 —— 而
 * `PlaylistCache` 只在内存里，杀进程就空，等于每次冷启动点进去都要重等
 * 这一遍上游。落盘之后第二次进来 0 帧出内容，后台再刷新。
 *
 * 「新鲜」的定义：30 分钟内写过的直接信，不再发请求（歌单本来就变得很慢）；
 * 更旧的先上屏、再后台静默重拉，失败也不打扰用户 —— 屏上已经有内容了。
 */
object PlaylistStore {

    /** 30 分钟：这个窗口内直接信磁盘，不为一份几乎不变的数据去等 10 秒的上游 */
    const val FRESH_MS = 30 * 60 * 1000L

    /**
     * 最多留几份：一份千首歌单将近 100KB，全留着很快就上兆。
     * 24 份 ≈ 2MB，够覆盖「最近看过的」那一批。
     */
    private const val MAX_FILES = 24

    class Cached(val at: Long, val detail: NasApi.PlaylistDetail)

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /** 读不到（没写过 / 文件坏了 / 被系统清了）一律返回 null，调用方当没缓存处理 */
    suspend fun read(ctx: Context, id: String): Cached? = withContext(Dispatchers.IO) {
        if (id.isBlank()) return@withContext null
        runCatching {
            val f = fileOf(ctx, id)
            if (!f.isFile) return@runCatching null
            // 首行是写入时间，之后才是 JSON：和 FeedCache 同一套写法
            val text = f.readText()
            val nl = text.indexOf('\n')
            if (nl <= 0) return@runCatching null
            val at = text.substring(0, nl).trim().toLongOrNull() ?: return@runCatching null
            val detail = json.decodeFromString(
                NasApi.PlaylistDetail.serializer(),
                text.substring(nl + 1),
            )
            // 空曲目的缓存等于没缓存：宁可走网络，也别让页面直接进「取不到曲目」态
            if (detail.tracks.isEmpty()) null else Cached(at, detail)
        }.getOrNull()
    }

    suspend fun save(ctx: Context, id: String, detail: NasApi.PlaylistDetail) =
        withContext(Dispatchers.IO) {
            if (id.isBlank() || detail.tracks.isEmpty()) return@withContext
            runCatching {
                val dir = dirOf(ctx).apply { mkdirs() }
                // 先写临时文件再改名：写到一半被系统杀掉也不会留下半份 JSON
                val tmp = File(dir, hash(id) + ".tmp")
                tmp.writeText(
                    "${System.currentTimeMillis()}\n" +
                        json.encodeToString(NasApi.PlaylistDetail.serializer(), detail),
                )
                val dst = File(dir, hash(id) + ".json")
                if (!tmp.renameTo(dst)) {
                    dst.delete()
                    tmp.renameTo(dst)
                }
                trim(dir)
            }
            Unit
        }

    suspend fun clear(ctx: Context) = withContext(Dispatchers.IO) {
        runCatching { dirOf(ctx).listFiles()?.forEach { it.delete() } }
        Unit
    }

    private fun trim(dir: File) {
        val files = dir.listFiles()?.filter { it.name.endsWith(".json") } ?: return
        if (files.size <= MAX_FILES) return
        files.sortedBy { it.lastModified() }
            .take(files.size - MAX_FILES)
            .forEach { it.delete() }
    }

    private fun dirOf(ctx: Context) = File(ctx.applicationContext.filesDir, "playlist")

    private fun fileOf(ctx: Context, id: String) = File(dirOf(ctx), hash(id) + ".json")

    private fun hash(key: String): String =
        MessageDigest.getInstance("MD5")
            .digest(key.toByteArray())
            .joinToString("") { "%02x".format(it) }
}
