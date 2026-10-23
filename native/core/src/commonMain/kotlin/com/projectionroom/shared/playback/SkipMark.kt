package com.projectionroom.shared.playback

/**
 * 一部剧的片头 / 片尾标记。
 *
 * 按剧记而不是按集：同一部剧每集片头长度一致，标一次全局生效 ——
 * 这也是 Plex / Jellyfin 做片头检测时的前提假设。按集记的话，
 * 用户每看一集都要重新设一次，等于没做。
 *
 * 两个字段都用毫秒，0 表示「不跳过 / 不提示」。
 */
data class SkipMark(
    val introMs: Long = SkipRule.DEFAULT_INTRO,
    val outroMs: Long = SkipRule.DEFAULT_OUTRO,
) {

    /** 两个都是 0 等于没设过，存下来只是垃圾数据 */
    val isOff: Boolean
        get() = introMs <= 0L && outroMs <= 0L

    companion object {

        private const val SEP = "\u0001"

        /**
         * 解析存下来的字符串（`"90000\u000112000"`）。
         *
         * 格式不对、负数、缺字段一律返回 null，让调用方退回全局默认 ——
         * 存储层被写坏时宁可丢标记，也不能给出一个离谱的片头时长把正片切掉。
         */
        fun parse(raw: String?): SkipMark? {
            if (raw.isNullOrBlank()) return null
            val parts = raw.split(SEP)
            if (parts.size != 2) return null
            val intro = parts[0].toLongOrNull() ?: return null
            val outro = parts[1].toLongOrNull() ?: return null
            if (intro < 0L || outro < 0L) return null
            return SkipMark(intro, outro)
        }

        fun format(mark: SkipMark): String =
            "${mark.introMs.coerceAtLeast(0L)}$SEP${mark.outroMs.coerceAtLeast(0L)}"
    }
}
