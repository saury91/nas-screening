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

/**
 * 本机已删除、但还没推送到 NAS 的条目。
 *
 * 没有这层墓碑，删除只是把本机那条清空：下一次同步从 NAS 拉回旧数据，
 * 删掉的收藏/记录又会长出来。同步成功后墓碑才移除。
 */
@Serializable
data class RemovedItem(
    val name: String,
    /** 取消收藏还没送达 NAS */
    val fav: Boolean = false,
    /** 删除播放记录还没送达 NAS */
    val his: Boolean = false,
    val at: Long = 0,
)

@Serializable
data class LibFile(
    val items: List<LibItem> = emptyList(),
    val removed: List<RemovedItem> = emptyList(),
) {

    fun favorites(): List<Vod> {
        val blocked = removed.filter { it.fav }.mapTo(HashSet()) { it.name }
        return items.filter { it.fav && it.name !in blocked }
            .sortedByDescending { it.at }
            .map { Vod(name = it.name, pic = it.pic, type = it.type, year = it.year) }
    }

    fun history(): List<Vod> {
        val blocked = removed.filter { it.his }.mapTo(HashSet()) { it.name }
        return items
            // 直播不写观看记录（进了播放器就会记，但「继续观看 CCTV10」没有意义，
            // 也没法从中间接着看）。这里是清掉旧版本留下的那几条 ——
            // 老记录把「直播」记在 type 上、ep 是空的，两个字段都要判。
            .filter {
                (it.position > 0 || it.ep.isNotBlank()) &&
                    it.ep != "直播" && it.type != "直播" && it.name !in blocked
            }
            .sortedByDescending { it.at }
            .map {
                Vod(
                    name = it.name, pic = it.pic, year = it.year,
                    type = if (it.ep.isNotBlank()) it.ep else it.type,
                    progress = it.progress,
                )
            }
    }

    fun find(name: String): LibItem? = items.firstOrNull { it.name == name }
}

object LibraryStore {

    private const val FILE = "library.json"

    fun load(ctx: Context): LibFile = LibraryJsonFile.read(File(ctx.filesDir, FILE))

    fun save(ctx: Context, lib: LibFile) = LibraryJsonFile.write(File(ctx.filesDir, FILE), lib)

    /** 本地切换收藏，返回新库；`pendingFav` 留给同步器清 */
    fun toggleFav(ctx: Context, vod: Vod): LibFile =
        LibraryOps.toggleFav(load(ctx), vod, System.currentTimeMillis()).also { save(ctx, it) }

    /** 播放进度落本地（播完/退出时调用），同样留 pending */
    fun touchHistory(
        ctx: Context,
        vod: Vod,
        ep: String,
        position: Long,
        duration: Long,
    ): LibFile = LibraryOps
        .touchHistory(load(ctx), vod, ep, position, duration, System.currentTimeMillis())
        .also { save(ctx, it) }

    /** 取消收藏：本地立刻消失，同步器随后把删除推到 NAS */
    fun removeFav(ctx: Context, name: String): LibFile =
        LibraryOps.removeFav(load(ctx), name, System.currentTimeMillis()).also { save(ctx, it) }

    /** 删除播放记录：收藏若还在，条目保留；同步器随后把记录从 NAS 删掉 */
    fun removeHistory(ctx: Context, name: String): LibFile =
        LibraryOps.removeHistory(load(ctx), name, System.currentTimeMillis()).also { save(ctx, it) }

    /** 同步器用：标记某些条目的某类改动已经送达 */
    fun markSynced(ctx: Context, lib: LibFile, names: Set<String>, fav: Boolean, his: Boolean): LibFile =
        LibraryOps.markSynced(lib, names, fav, his).also { save(ctx, it) }

    fun replace(ctx: Context, lib: LibFile): LibFile =
        lib.also { save(ctx, it) }
}

/**
 * `library.json` 的纯读写：不碰 Context，测试可以直接对着临时目录跑。
 */
internal object LibraryJsonFile {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    fun read(f: File): LibFile {
        if (!f.exists()) return LibFile()
        return runCatching { json.decodeFromString<LibFile>(f.readText()) }.getOrDefault(LibFile())
    }

    fun write(f: File, lib: LibFile) {
        runCatching {
            // 先写 .tmp 再改名：写一半被杀不会把主库留成半截 JSON
            val tmp = File(f.parentFile ?: File("."), f.name + ".tmp")
            tmp.writeText(json.encodeToString(lib))
            tmp.renameTo(f)
        }
    }
}

/**
 * 收藏 / 记录的计算本体。
 *
 * 从 [LibraryStore] 抽出来只为可测：这里全是「哪一条该覆盖哪一条」的判断，
 * store 那层只负责读文件、算完写回去。
 */
internal object LibraryOps {

    fun toggleFav(lib: LibFile, vod: Vod, now: Long): LibFile {
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
        // 重新收藏 = 撤销之前的删除，否则墓碑会一直把 NAS 上的收藏挡回去
        val removed = if (next.fav) clearRemoved(lib.removed, vod.name, fav = true, his = false)
        else lib.removed
        return LibFile(items, removed)
    }

    fun removeFav(lib: LibFile, name: String, now: Long): LibFile {
        val items = lib.items.mapNotNull {
            if (it.name != name) it
            else {
                val next = it.copy(fav = false, pendingFav = false, at = now)
                // 还有播放记录就留着（记录卡里还会出现），否则整条删掉
                if (next.position > 0 || next.ep.isNotBlank()) next else null
            }
        }
        return LibFile(items, markRemoved(lib.removed, name, now, fav = true, his = false))
    }

    fun removeHistory(lib: LibFile, name: String, now: Long): LibFile {
        val items = lib.items.mapNotNull {
            if (it.name != name) it
            else {
                val next = it.copy(
                    ep = "", position = 0, duration = 0, pendingHis = false, at = now,
                )
                // 收藏还在的话只清进度，收藏卡不能跟着消失
                if (next.fav) next else null
            }
        }
        return LibFile(items, markRemoved(lib.removed, name, now, fav = false, his = true))
    }

    fun touchHistory(
        lib: LibFile,
        vod: Vod,
        ep: String,
        position: Long,
        duration: Long,
        now: Long,
    ): LibFile {
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
        // 又看了一遍 = 撤销「删除记录」，不然刚看的东西不会出现在记录里
        return LibFile(items, clearRemoved(lib.removed, vod.name, fav = false, his = true))
    }

    fun markSynced(lib: LibFile, names: Set<String>, fav: Boolean, his: Boolean): LibFile {
        val items = lib.items.map {
            if (it.name in names) {
                it.copy(
                    pendingFav = if (fav) false else it.pendingFav,
                    pendingHis = if (his) false else it.pendingHis,
                )
            } else it
        }
        return LibFile(items, lib.removed)
    }

    /** 删除成功或用户重新操作后，把墓碑上对应的那一位清掉 */
    private fun clearRemoved(
        list: List<RemovedItem>,
        name: String,
        fav: Boolean,
        his: Boolean,
    ): List<RemovedItem> {
        if (list.none { it.name == name && ((fav && it.fav) || (his && it.his)) }) return list
        return list.mapNotNull {
            if (it.name != name) it
            else {
                val next = it.copy(
                    fav = if (fav) false else it.fav,
                    his = if (his) false else it.his,
                )
                if (next.fav || next.his) next else null
            }
        }
    }

    private fun markRemoved(
        list: List<RemovedItem>,
        name: String,
        now: Long,
        fav: Boolean,
        his: Boolean,
    ): List<RemovedItem> {
        val old = list.firstOrNull { it.name == name }
        val next = (old ?: RemovedItem(name = name)).copy(
            fav = old?.fav == true || fav,
            his = old?.his == true || his,
            at = now,
        )
        return list.filterNot { it.name == name } + next
    }
}
