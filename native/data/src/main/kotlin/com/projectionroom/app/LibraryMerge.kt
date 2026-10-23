package com.projectionroom.app

import com.projectionroom.shared.model.Vod

/**
 * 把 NAS 上的收藏 / 记录并进本地主库。
 *
 * 三条规则，缺一条都会出线上事故：
 *  1. 本机有未推送的改动（pending）→ **以本机为准**，否则你在 NAS 关机期间
 *     取消的收藏，会在开机后又被 NAS 的老数据加回来；
 *  2. 本机 `position == 0` 说明本机没在播这部，采用 NAS 上的进度（别的端可能更靠后）；
 *  3. 新条目用 NAS 的元信息补齐封面 / 年份，避免首页出现一排空封面。
 *
 * 纯函数：`now` 由 [LibrarySync] 传进来，测试才不用跟时钟赛跑。
 */
internal object LibraryMerge {

    fun pull(lib: LibFile, nasFavs: List<Vod>?, nasHist: List<Vod>?, now: Long): LibFile {
        if (nasFavs == null && nasHist == null) return lib

        val byName = lib.items.associateBy { it.name }.toMutableMap()

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
                // 本机没在播这个，就用 NAS 上的位置（别的端可能看到更后面）
                old.position == 0L -> byName[v.name] = old.copy(
                    position = (v.progress * 10000).toLong(), duration = 10000L,
                    pic = old.pic.ifBlank { v.pic }, at = now,
                )
            }
        }

        return LibFile(byName.values.toList())
    }
}
