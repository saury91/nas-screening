package com.projectionroom.ui.playback

import android.content.Context

/** 一条外挂字幕：持久化后的 SAF URI + MIME + 显示名 */
data class SideSubtitle(val uri: String, val mime: String, val label: String)

/**
 * 按「片名 + 第几集」记住用户挂的外挂字幕。
 *
 * 为什么按集记而不是按片记：同一部剧每集各一个 srt，只记片名的话第二集会
 * 拿第一集的字幕去对轴，比没有字幕更难受。
 *
 * 用独立 SharedPreferences 文件（键前缀 `sub_`），条目数有上限：这是纯缓存，
 * 攒到 200 条就整体清空重来 —— 比写一套 LRU 便宜，用户也感知不到。
 */
object SubtitleStore {

    private const val FILE = "proom_subtitles"
    private const val PREFIX = "sub_"
    private const val SEP = "\u0002"
    private const val FIELD_SEP = "\u0001"
    private const val MAX = 200

    private fun key(vod: String, epIndex: Int) = PREFIX + vod + FIELD_SEP + epIndex

    private fun sp(ctx: Context) = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun get(ctx: Context, vod: String, epIndex: Int): SideSubtitle? {
        if (vod.isBlank() || epIndex < 0) return null
        val raw = sp(ctx).getString(key(vod, epIndex), null) ?: return null
        val parts = raw.split(SEP)
        if (parts.size < 3 || parts[0].isBlank()) return null
        return SideSubtitle(parts[0], parts[1], parts[2])
    }

    fun remember(ctx: Context, vod: String, epIndex: Int, sub: SideSubtitle) {
        if (vod.isBlank() || epIndex < 0) return
        val sp = sp(ctx)
        if (sp.all.keys.count { it.startsWith(PREFIX) } >= MAX) {
            sp.edit().clear().apply()
        }
        sp.edit().putString(key(vod, epIndex), listOf(sub.uri, sub.mime, sub.label).joinToString(SEP)).apply()
    }

    fun clear(ctx: Context, vod: String, epIndex: Int) {
        if (vod.isBlank() || epIndex < 0) return
        sp(ctx).edit().remove(key(vod, epIndex)).apply()
    }
}
