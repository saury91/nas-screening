package com.projectionroom.ui.playback

import android.content.Context
import com.projectionroom.shared.playback.SkipMark

/**
 * 按剧名记住片头 / 片尾时长。
 *
 * 和 [SubtitleStore] 同一套做法，但这里**按剧**而不是按集：片头是整部剧共用的。
 *
 * 存不下时整体清空而不是淘汰最旧的一条：这是纯缓存，清掉只意味着
 * 下次进这部剧要重新用全局默认，用户几乎感知不到，比写 LRU 便宜得多。
 */
object SkipStore {

    private const val FILE = "proom_skip"
    private const val PREFIX = "skip_"
    private const val MAX = 300

    private fun key(vod: String) = PREFIX + vod

    private fun sp(ctx: Context) = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** 这部剧有没有单独设过；没设过返回 null，调用方退回全局默认 */
    fun get(ctx: Context, vod: String): SkipMark? {
        if (vod.isBlank()) return null
        return SkipMark.parse(sp(ctx).getString(key(vod), null))
    }

    /**
     * 记下这部剧的标记。
     *
     * 两个都为 0（全关）时直接删键，不留 `"0\u00010"` 这种等同默认值的垃圾条目。
     */
    fun remember(ctx: Context, vod: String, mark: SkipMark) {
        if (vod.isBlank()) return
        val sp = sp(ctx)
        if (mark.isOff) {
            sp.edit().remove(key(vod)).apply()
            return
        }
        if (sp.all.keys.count { it.startsWith(PREFIX) } >= MAX) {
            sp.edit().clear().apply()
        }
        sp.edit().putString(key(vod), SkipMark.format(mark)).apply()
    }

    fun clear(ctx: Context, vod: String) {
        if (vod.isBlank()) return
        sp(ctx).edit().remove(key(vod)).apply()
    }
}
