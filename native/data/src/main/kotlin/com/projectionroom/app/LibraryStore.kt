package com.projectionroom.app

import android.content.Context
import com.projectionroom.shared.model.Vod
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * 收藏 + 播放记录的**本地主库**。
 *
 * 离线优先：所有读写先落本地，界面立刻有反馈；
 * NAS 只是「同步对象」—— 在线时把本地改动推上去，顺便把别的端的改动拉下来。
 *
 * 所以每条数据带两个 pending 标记：改动还没送达 NAS 时置位，
 * 送达后清掉。NAS 关一个月也不会丢东西，开机后自动补齐。
 */
@Serializable
data class LibItem(
    /** 片名是唯一键：NAS 那边的 key 也是片名（同一部片跨端的识别依据） */
    val name: String,
    val pic: String = "",
    val type: String = "",
    val year: String = "",
    /** 播放记录：看到第几集 */
    val ep: String = "",
    val position: Long = 0,
    val duration: Long = 0,
    /** 最后一次本地更新时间，合并时取新的 */
    val at: Long = 0,
    val fav: Boolean = false,
    /** 收藏状态还没同步到 NAS */
    val pendingFav: Boolean = false,
    /** 播放进度还没同步到 NAS */
    val pendingHis: Boolean = false,
) {
    val progress: Float
        get() = if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
}

@Serializable
data class LibFile(val items: List<LibItem> = emptyList()) {

    fun favorites(): List<Vod> = items.filter { it.fav }
        .sortedByDescending { it.at }
        .map { Vod(name = it.name, pic = it.pic, type = it.type, year = it.year) }

    fun history(): List<Vod> = items
        // 直播不写观看记录（进了播放器就会记，但「继续观看 CCTV10」没有意义，
        // 也没法从中间接着看）。这里是清掉旧版本留下的那几条 ——
        // 老记录把「直播」记在 type 上、ep 是空的，两个字段都要判。
        .filter { (it.position > 0 || it.ep.isNotBlank()) && it.ep != "直播" && it.type != "直播" }
        .sortedByDescending { it.at }
        .map {
            Vod(
                name = it.name, pic = it.pic, year = it.year,
                type = if (it.ep.isNotBlank()) it.ep else it.type,
                progress = it.progress,
            )
        }

    fun find(name: String): LibItem? = items.firstOrNull { it.name == name }
}

object LibraryStore {

    private const val FILE = "library.json"

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    fun load(ctx: Context): LibFile {
        val f = File(ctx.filesDir, FILE)
        if (!f.exists()) return LibFile()
        return runCatching { json.decodeFromString<LibFile>(f.readText()) }.getOrDefault(LibFile())
    }

    fun save(ctx: Context, lib: LibFile) {
        runCatching {
            val tmp = File(ctx.filesDir, "$FILE.tmp")
            tmp.writeText(json.encodeToString(lib))
            tmp.renameTo(File(ctx.filesDir, FILE))
        }
    }

    /** 本地切换收藏，返回新库；`pendingFav` 留给同步器清 */
    fun toggleFav(ctx: Context, vod: Vod): LibFile {
        val lib = load(ctx)
        val now = System.currentTimeMillis()
        val old = lib.find(vod.name)
        val next = if (old != null) {
            old.copy(fav = !old.fav, pendingFav = true, at = now)
        } else {
            LibItem(
                name = vod.name, pic = vod.pic, type = vod.type, year = vod.year,
                fav = true, pendingFav = true, at = now,
            )
        }
        val items = if (old != null) lib.items.map { if (it.name == vod.name) next else it }
        else lib.items + next
        return LibFile(items).also { save(ctx, it) }
    }

    /** 播放进度落本地（播完/退出时调用），同样留 pending */
    fun touchHistory(
        ctx: Context,
        vod: Vod,
        ep: String,
        position: Long,
        duration: Long,
    ): LibFile {
        val lib = load(ctx)
        val now = System.currentTimeMillis()
        val old = lib.find(vod.name)
        val base = old ?: LibItem(name = vod.name, pic = vod.pic, type = vod.type, year = vod.year)
        val next = base.copy(
            pic = base.pic.ifBlank { vod.pic },
            year = base.year.ifBlank { vod.year },
            ep = ep,
            position = position,
            duration = duration,
            at = now,
            pendingHis = true,
        )
        val items = if (old != null) lib.items.map { if (it.name == vod.name) next else it }
        else lib.items + next
        return LibFile(items).also { save(ctx, it) }
    }

    /** 同步器用：标记某些条目的某类改动已经送达 */
    fun markSynced(ctx: Context, lib: LibFile, names: Set<String>, fav: Boolean, his: Boolean): LibFile {
        val items = lib.items.map {
            if (it.name in names) {
                it.copy(
                    pendingFav = if (fav) false else it.pendingFav,
                    pendingHis = if (his) false else it.pendingHis,
                )
            } else it
        }
        return LibFile(items).also { save(ctx, it) }
    }

    fun replace(ctx: Context, lib: LibFile): LibFile =
        lib.also { save(ctx, it) }
}
