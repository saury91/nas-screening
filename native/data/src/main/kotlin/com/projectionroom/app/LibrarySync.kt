package com.projectionroom.app

import android.content.Context
import com.projectionroom.shared.catalog.NasApi
import com.projectionroom.shared.model.Vod

/**
 * 收藏 / 播放记录的同步器。
 *
 * 方向：**本地永远是主库**，NAS 是备份与跨端交换站。
 *  - 先推：把带 pending 的改动发给 NAS（在线才可能成功，失败就留着下次再试）；
 *  - 再拉：把 NAS 上别的端写入的收藏/记录并进来（本机有 pending 的不覆盖）。
 *
 * 收藏用 NAS 的 `/api/fav` 是「切换」语义，所以在推之前先拉一次现状，
 * 已经一致就不调用 —— 否则会把状态翻反（这是最容易写出的坑）。
 */
object LibrarySync {

    suspend fun sync(ctx: Context, api: NasApi, lib: LibFile): LibFile {
        // 整个同步（网络 + 合并 + 落盘）都放 IO：
        // 调用方是 LaunchedEffect / 点击回调，都在主线程上，
        // 里面的 LibraryStore.replace 会写整个 library.json（StrictMode 抓到的就是它）
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            var cur = lib
            runCatching { cur = pushFavorites(ctx, api, cur) }
            runCatching { cur = pushHistory(ctx, api, cur) }
            runCatching { cur = pull(ctx, api, cur) }
            cur
        }
    }

    private suspend fun pushFavorites(ctx: Context, api: NasApi, lib: LibFile): LibFile {
        val pending = lib.items.filter { it.pendingFav }
        if (pending.isEmpty()) return lib
        // 拿不到现状就不动，避免把「加收藏」翻成「取消收藏」
        val nas = runCatching { api.favorites().map { it.name }.toSet() }.getOrElse { return lib }
        val done = mutableSetOf<String>()
        for (item in pending) {
            if (item.name in nas == item.fav) {
                done += item.name          // 已经一致，只清标记
                continue
            }
            runCatching {
                api.toggleFavorite(Vod(name = item.name, pic = item.pic, type = item.type, year = item.year))
            }.onSuccess { on ->
                if (on == item.fav) done += item.name
            }
        }
        return if (done.isEmpty()) lib
        else LibraryStore.markSynced(ctx, lib, done, fav = true, his = false)
    }

    private suspend fun pushHistory(ctx: Context, api: NasApi, lib: LibFile): LibFile {
        val pending = lib.items.filter { it.pendingHis }
        if (pending.isEmpty()) return lib
        val done = mutableSetOf<String>()
        for (item in pending) {
            runCatching {
                api.writeHistory(
                    Vod(name = item.name, pic = item.pic, year = item.year, type = item.type),
                    item.ep, item.position, item.duration,
                )
            }.onSuccess { if (it) done += item.name }
        }
        return if (done.isEmpty()) lib
        else LibraryStore.markSynced(ctx, lib, done, fav = false, his = true)
    }

    private suspend fun pull(ctx: Context, api: NasApi, lib: LibFile): LibFile {
        val nasFavs = runCatching { api.favorites() }.getOrNull()
        val nasHist = runCatching { api.history() }.getOrNull()
        if (nasFavs == null && nasHist == null) return lib

        val byName = lib.items.associateBy { it.name }.toMutableMap()
        val now = System.currentTimeMillis()

        nasFavs.orEmpty().forEach { v ->
            val old = byName[v.name]
            when {
                old == null -> byName[v.name] = LibItem(
                    name = v.name, pic = v.pic, type = v.type, year = v.year,
                    fav = true, at = now,
                )
                // 本机有未推送的改动就以本机为准
                old.pendingFav -> Unit
                !old.fav -> byName[v.name] = old.copy(fav = true)
            }
        }

        nasHist.orEmpty().forEach { v ->
            val old = byName[v.name]
            when {
                old == null -> byName[v.name] = LibItem(
                    name = v.name, pic = v.pic, year = v.year, type = v.type,
                    position = (v.progress * 10000).toLong(), duration = 10000L, at = now,
                )
                old.pendingHis -> Unit
                // 本机没在播这个，就用 NAS 上的位置（别端可能看到更后面）
                old.position == 0L -> byName[v.name] = old.copy(
                    position = (v.progress * 10000).toLong(), duration = 10000L,
                    pic = old.pic.ifBlank { v.pic }, at = now,
                )
            }
        }

        val merged = LibFile(byName.values.toList())
        return LibraryStore.replace(ctx, merged)
    }
}
