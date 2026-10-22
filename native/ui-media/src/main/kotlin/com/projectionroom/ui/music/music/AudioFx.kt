package com.projectionroom.ui.music

import android.content.Context
import android.media.audiofx.Equalizer
import android.media.audiofx.LoudnessEnhancer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 音效：系统均衡器 + 响度增强。
 *
 * Media3 没有内置 EQ，只能挂在系统的 [Equalizer] 上 —— 它按「音频会话」生效，
 * 所以要先拿到 ExoPlayer 的 audioSessionId。会话 id 在播放器创建后才确定，
 * 而且可能变（重建播放器、音乐与视频各有一个播放器），因此每次 attach 都要重设。
 *
 * 设备之间的频段划分不一样（有的 5 段、有的 10 段），所以预设不写死频点，
 * 而是按每段实际的中心频率套一条连续曲线，段数多少都能用。
 *
 * 只用 framework 的 audiofx、不引第三方 DSP 库：GPL 的（TarsosDSP）会传染，
 * 闭源的（Superpowered）体积大且不可控，而手机外放/普通耳机的体感差异
 * 用 10 段 EQ + 响度就已经能吃满了。
 */
object AudioFx {

    enum class Preset(val label: String) {
        Off("默认"),
        Vocal("人声"),
        Bass("低音"),
        Pop("流行"),
        Rock("摇滚"),
        Classical("古典"),
        Electronic("电子"),
        Treble("清亮"),
    }

    var current by mutableStateOf(Preset.Off)
        private set

    /**
     * 手动 EQ：每段一个 -1..1 的值，null 表示没手调过（跟随预设）。
     * 值是比例而不是 dB —— 每台设备的 dB 上限不一样，比例才能通用。
     */
    var manual by mutableStateOf<List<Float>?>(null)
        private set

    /** 响度增强：外放/小耳机在安静环境里听不清对白时用 */
    var loudness by mutableStateOf(false)
        private set

    /** 设备实际段数，界面照着它画柱子 */
    var bandCount by mutableStateOf(0)
        private set

    /** 各段中心频率（Hz），界面标频率用 */
    var bandFreqs by mutableStateOf<List<Int>>(emptyList())
        private set

    private var eq: Equalizer? = null
    private var le: LoudnessEnhancer? = null
    private var sessionId: Int = 0
    private var ctx: Context? = null

    private const val SP = "audio_fx"
    private const val K_PRESET = "preset"
    private const val K_MANUAL = "manual"
    private const val K_LOUD = "loudness"

    /** App 启动时调一次：把上次的设置在 attach 之前恢复回来 */
    fun init(context: Context) {
        if (ctx != null) return
        val app = context.applicationContext
        ctx = app
        runCatching {
            val sp = app.getSharedPreferences(SP, Context.MODE_PRIVATE)
            current = Preset.entries.firstOrNull { it.name == sp.getString(K_PRESET, null) } ?: Preset.Off
            manual = sp.getString(K_MANUAL, null)
                ?.split(',')
                ?.mapNotNull { it.toFloatOrNull() }
                ?.takeIf { it.isNotEmpty() }
            loudness = sp.getBoolean(K_LOUD, false)
        }
    }

    /** 播放器换了会话就重新绑 */
    fun attach(id: Int) {
        if (id <= 0 || id == sessionId) return
        release()
        sessionId = id
        runCatching { eq = Equalizer(0, id) }
        runCatching { le = LoudnessEnhancer(id) }
        bandCount = runCatching { eq?.numberOfBands?.toInt() ?: 0 }.getOrDefault(0)
        bandFreqs = runCatching {
            val e = eq ?: return@runCatching emptyList<Int>()
            (0 until e.numberOfBands).map { e.getCenterFreq(it.toShort()) / 1000 }
        }.getOrDefault(emptyList())
        apply()
    }

    fun select(p: Preset) {
        current = p
        manual = null
        save()
        apply()
    }

    /** 手动调某一段；第一次调的时候按当前预设生成初始值，拖动才是连续的 */
    fun setBand(index: Int, value: Float) {
        val n = bandCount
        if (n <= 0 || index !in 0 until n) return
        val base = manual?.toMutableList() ?: MutableList(n) { i ->
            // 从当前预设的曲线起步，用户只是在它上面微调
            if (current == Preset.Off) 0f else curveOf(current, bandFreqs.getOrElse(i) { 1000 })
        }
        while (base.size < n) base.add(0f)
        base[index] = value.coerceIn(-1f, 1f)
        manual = base
        save()
        apply()
    }

    fun resetManual() {
        manual = null
        save()
        apply()
    }

    /** 当前生效的段位值（-1..1）：手调优先，否则按预设曲线算。界面画柱子用 */
    fun valueAt(index: Int): Float {
        manual?.let { return it.getOrElse(index) { 0f } }
        if (current == Preset.Off) return 0f
        return curveOf(current, bandFreqs.getOrElse(index) { 1_000 })
    }

    fun setLoudnessOn(on: Boolean) {
        loudness = on
        save()
        apply()
    }

    private fun save() {
        val sp = ctx?.getSharedPreferences(SP, Context.MODE_PRIVATE) ?: return
        runCatching {
            sp.edit()
                .putString(K_PRESET, current.name)
                // 小数点必须固定成 '.'：跟着系统语言走的话，德语/法语环境下
                // 会存成 "0,550,300"，而分隔符也是逗号，重启就恢复不回来了
                .putString(
                    K_MANUAL,
                    manual?.joinToString(",") { String.format(java.util.Locale.US, "%.3f", it) },
                )
                .putBoolean(K_LOUD, loudness)
                .apply()
        }
    }

    private fun apply() {
        val e = eq ?: return
        runCatching {
            val m = manual
            val presetOn = m != null || current != Preset.Off
            e.enabled = presetOn
            if (presetOn) {
                val range = e.bandLevelRange
                val min = range[0].toInt()
                val max = range[1].toInt()
                // 往正方向留 20% 余量，全推满容易在手机上削波
                val up = (max * 0.8f)
                val down = (-min * 0.8f)
                for (i in 0 until e.numberOfBands) {
                    val freq = bandFreqs.getOrElse(i) { 1000 }
                    val v = m?.getOrNull(i) ?: (if (current == Preset.Off) 0f else curveOf(current, freq))
                    val gain = if (v >= 0f) v * up else v * down
                    e.setBandLevel(i.toShort(), gain.toInt().coerceIn(min, max).toShort())
                }
            }
        }
        runCatching {
            val l = le ?: return
            l.enabled = loudness
            // 600 mB ≈ +6dB：够把对白抬出来，又不至于把底噪一起放大
            if (loudness) l.setTargetGain(600)
        }
    }

    /**
     * 预设曲线：横轴频率、纵轴 -1..1 的增益比例。
     * 按频率给值而不是按段序号，5 段设备和 10 段设备听到的是同一条曲线。
     */
    private fun curveOf(p: Preset, freq: Int): Float = when (p) {
        Preset.Off -> 0f
        Preset.Vocal -> when {
            freq < 200 -> -0.55f
            freq < 2_000 -> 0.55f
            freq < 6_000 -> 0.3f
            else -> 0f
        }
        Preset.Bass -> when {
            freq < 120 -> 0.9f
            freq < 300 -> 0.6f
            freq < 1_000 -> 0.2f
            else -> 0f
        }
        Preset.Pop -> when {
            freq < 150 -> 0.4f
            freq < 800 -> -0.2f
            freq < 3_000 -> 0.5f
            else -> 0.2f
        }
        Preset.Rock -> when {
            freq < 200 -> 0.7f
            freq < 800 -> -0.3f
            freq < 3_000 -> 0.6f
            freq < 6_000 -> 0f
            else -> 0.4f
        }
        Preset.Classical -> when {
            freq < 200 -> 0.3f
            freq < 2_000 -> 0f
            freq < 6_000 -> 0.4f
            else -> 0.6f
        }
        Preset.Electronic -> when {
            freq < 150 -> 0.8f
            freq < 600 -> -0.2f
            freq < 2_500 -> 0.4f
            else -> 0.6f
        }
        Preset.Treble -> when {
            freq < 1_000 -> -0.3f
            freq < 3_000 -> 0.3f
            else -> 0.8f
        }
    }

    private fun release() {
        runCatching { eq?.release() }
        runCatching { le?.release() }
        eq = null
        le = null
        sessionId = 0
    }
}
