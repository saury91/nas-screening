package com.projectionroom.shared.playback

/**
 * 片头 / 片尾的判定规则。
 *
 * 放在 core 而不是 ui-media：这几个判断决定「按钮什么时候出现」，
 * 一旦写错就是两种难查的故障 —— 正片被当成片头跳过，或者按钮永远不出现。
 * 抽成纯函数才能被单测钉住。
 *
 * 播放位置一律用毫秒。
 */
object SkipRule {

    /** 片头档位：0 = 不跳过 */
    val introOptions: List<Long> = listOf(0L, 30_000L, 60_000L, 90_000L, 120_000L, 180_000L)

    /** 片尾档位：0 = 不提示 */
    val outroOptions: List<Long> = listOf(0L, 15_000L, 30_000L, 60_000L, 90_000L)

    /** 首次遇到一部新剧时用的片头时长。国产剧常规片头在 90 秒上下 */
    const val DEFAULT_INTRO = 90_000L

    /** 片尾默认不提示：多数人更愿意看完片尾曲 */
    const val DEFAULT_OUTRO = 0L

    /**
     * 该不该浮出「跳过片头」。
     *
     * [position] 为 0 时不算：`setMediaSource` 之后、起播之前 position 就是 0，
     * 这时冒按钮会在每次起播时闪一下。真正起播后 position 会立刻大于 0。
     */
    fun shouldShowIntro(position: Long, introMs: Long, live: Boolean): Boolean =
        !live && introMs > 0L && position > 0L && position < introMs

    /**
     * 该不该提示「下一集」。
     *
     * [duration] 未知时不提示：HLS 起播阶段 duration 是 `C.TIME_UNSET`（一个很大的负数），
     * 直接拿它减 position 会得到负数，白白命中「快播完了」的条件。
     */
    fun shouldShowOutro(
        position: Long,
        duration: Long,
        outroMs: Long,
        hasNext: Boolean,
        live: Boolean,
    ): Boolean {
        if (live || !hasNext || outroMs <= 0L) return false
        if (duration <= 0L || position <= 0L) return false
        // position 超过 duration 说明数据异常，同样不提示
        if (position >= duration) return false
        return duration - position <= outroMs
    }

    /** 片尾提示里的剩余秒数：向上取整，且至少 1 秒（显示「还剩 0 秒」等于没在倒计时） */
    fun remainingSec(position: Long, duration: Long): Int {
        val left = duration - position
        if (left <= 0L) return 0
        return ((left + 999L) / 1000L).toInt()
    }

    /** 档位文案。0 一律显示「关闭」，其余按 分/秒 展开 */
    fun label(ms: Long): String {
        if (ms <= 0L) return "关闭"
        val total = ms / 1000L
        if (total < 60L) return "${total} 秒"
        val m = total / 60L
        val s = total % 60L
        return if (s == 0L) "${m} 分钟" else "${m} 分 ${s} 秒"
    }
}
