package com.projectionroom.app

import android.content.Context
import com.projectionroom.shared.catalog.NasApi
import com.projectionroom.shared.model.Vod
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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

    /**
     * 同一时刻只跑一次同步。
     *
     * 播放器每隔几秒报一次进度、每次都会顺带 sync；启动那次同步也可能还没结束。
     * 两次交叠时，先发后至的那次会拿自己手里的旧快照去 `LibraryStore.replace`，
     * 把另一条更新的进度（甚至整条记录）写回旧值 —— 串行化最省事，也不会慢多少。
     */
    private val mutex = Mutex()

    suspend fun sync(ctx: Context, api: NasApi, lib: LibFile): LibFile = mutex.withLock {
        // 整个同步（网络 + 合并 + 落盘）都放 IO：
        // 调用方是 LaunchedEffect / 点击回调，都在主线程上，
        // 里面的 LibraryStore.replace 会写整个 library.json（StrictMode 抓到的就是它）
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            // 基准取**磁盘上的主库**，不直接用调用方给的快照：
            // 那个快照可能是启动时还没读完文件的那份空库（`remember { LibFile() }`），
            // 拿它去 pull 再 replace，等于「本机什么都没有」，会把本地记录整库覆盖成
            // NAS 上的内容 —— 电视上表现为：刚进首页有「继续观看」，一两秒后整排消失。
            // 本机所有改动都先落盘（见 LibraryStore），所以磁盘永远不比快照旧。
            var cur = runCatching { LibraryStore.load(ctx) }.getOrDefault(lib)
            // 先推删除：否则下面的 pull 会把 NAS 上还没删掉的旧条目又拉回来
            runCatching { cur = pushRemoved(ctx, api, cur) }
            runCatching { cur = pushFavorites(ctx, api, cur) }
            runCatching { cur = pushHistory(ctx, api, cur) }
            runCatching { cur = pull(ctx, api, cur) }
            cur
        }
    }

    private suspend fun pushRemoved(ctx: Context, api: NasApi, lib: LibFile): LibFile {
        if (lib.removed.isEmpty()) return lib
        val favDone = mutableSetOf<String>()
        val hisDone = mutableSetOf<String>()
        for (item in lib.removed) {
            if (item.fav) {
                runCatching { api.deleteFavorite(item.name) }
                    .onSuccess { if (it) favDone += item.name }
            }
            if (item.his) {
                runCatching { api.deleteHistory(item.name) }
                    .onSuccess { if (it) hisDone += item.name }
            }
        }
        if (favDone.isEmpty() && hisDone.isEmpty()) return lib
        val left = lib.removed.mapNotNull {
            val fav = it.fav && it.name !in favDone
            val his = it.his && it.name !in hisDone
            if (fav || his) it.copy(fav = fav, his = his) else null
        }
        return LibraryStore.replace(ctx, lib.copy(removed = left))
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

        // 合并规则本身在 LibraryMerge（纯函数、有单测）；这里只管网络与落盘
        val merged = LibraryMerge.pull(lib, nasFavs, nasHist, System.currentTimeMillis())
        return LibraryStore.replace(ctx, merged)
    }
}
