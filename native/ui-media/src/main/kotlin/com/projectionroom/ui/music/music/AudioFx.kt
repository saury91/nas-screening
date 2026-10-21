package com.projectionroom.ui.music

import android.media.audiofx.Equalizer

/**
 * 音效（均衡器）预设。
 *
 * Media3 没有内置 EQ，只能挂在系统的 [Equalizer] 上 —— 它按「音频会话」生效，
 * 所以要先拿到 ExoPlayer 的 audioSessionId。会话 id 在播放器创建后才确定，
 * 而且可能变（切歌不重新建播放器时不会变，但重建播放器会），
 * 因此每次 attach 都要重设预设。
 *
 * 只用 3 档：默认 / 人声 / 低音。设备之间的频段划分不一样，
 * 这里按 band 的中心频率「就近」改，不去假设具体有几段。
 */
object AudioFx {

    enum class Preset(val label: String) {
        Off("默认"),
        Vocal("人声"),
        Bass("低音"),
    }

    var current: Preset = Preset.Off
        private set

    private var eq: Equalizer? = null
    private var sessionId: Int = 0

    /** 播放器换了会话就重新绑 */
    fun attach(id: Int) {
        if (id <= 0 || id == sessionId) return
        release()
        sessionId = id
        runCatching {
            eq = Equalizer(0, id).apply { enabled = current != Preset.Off }
            apply(current)
        }
    }

    fun select(p: Preset) {
        current = p
        apply(p)
    }

    private fun apply(p: Preset) {
        val e = eq ?: return
        runCatching {
            e.enabled = p != Preset.Off
            if (p == Preset.Off) return
            val range = e.bandLevelRange
            val min = range[0].toInt()
            val max = range[1].toInt()
            // 以 1kHz 为中心：低于它的往上推（低音）或压（人声），高于它的反向处理
            for (i in 0 until e.numberOfBands) {
                val f = e.getCenterFreq(i.toShort()) / 1000 // mHz → Hz
                val gain = when (p) {
                    Preset.Bass -> when {
                        f < 250 -> max / 2
                        f < 1000 -> max / 4
                        else -> 0
                    }
                    Preset.Vocal -> when {
                        f in 250..4000 -> max / 3
                        f < 250 -> min / 3
                        else -> 0
                    }
                    Preset.Off -> 0
                }
                e.setBandLevel(i.toShort(), gain.coerceIn(min, max).toShort())
            }
        }
    }

    private fun release() {
        runCatching { eq?.release() }
        eq = null
        sessionId = 0
    }
}
