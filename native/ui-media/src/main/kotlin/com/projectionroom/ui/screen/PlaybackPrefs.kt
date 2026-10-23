package com.projectionroom.ui.screen

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.projectionroom.shared.playback.SkipRule

/**
 * 解码方式。
 *
 * 只有「自动」会启用 ExoPlayer 的解码器回落：硬解初始化失败时自己往下试软解。
 * 另外两档是排查用的强制项 —— 硬解花屏、声画不同步时切到软解先看能不能出画面，
 * 才能判断是片源、硬解还是网络的问题。
 */
enum class DecoderMode(val label: String, val desc: String) {
    Auto("自动", "硬解优先，失败自动回退"),
    Hardware("硬解", "只用硬件解码器，省电、4K 首选"),
    Software("软解", "只用系统软件解码器"),
}

/**
 * 播放相关的本地偏好。
 *
 * 三项：双击快进步长、解码方式、字幕开关与字号。前两项是设置页的入口，
 * 字幕两项由播放页的字幕面板改写，重进播放页仍然生效。
 *
 * 和 [com.projectionroom.ui.music.AudioFx] 同一套写法：`object` + SharedPreferences，
 * 值用 snapshot state，设置页改完播放页立刻生效，不用重启也不用回传。
 */
object PlaybackPrefs {

    private const val FILE = "proom_playback"
    private const val K_STEP = "double_tap_ms"
    private const val K_DECODER = "decoder_mode"
    private const val K_SUB_ON = "subtitle_on"
    private const val K_SUB_SCALE = "subtitle_scale"
    private const val K_INTRO = "skip_intro_ms"
    private const val K_OUTRO = "skip_outro_ms"

    /** 可选的步长：5 秒适合精确定位，30 秒适合快进跳过片头 */
    val steps: List<Long> = listOf(5_000L, 10_000L, 30_000L)

    /** 字幕字号档位：以 16sp 为 1.0 倍，电视按屏距再放大也够用 */
    val subtitleScales: List<Float> = listOf(0.85f, 1f, 1.25f, 1.5f)

    /** 双击左右半屏快退/快进的毫秒数 */
    var doubleTapMs by mutableLongStateOf(10_000L)
        private set

    /** 解码方式：改它会让播放页重建播放器，从当前位置续播 */
    var decoder by mutableStateOf(DecoderMode.Auto)
        private set

    /**
     * 字幕是否打开（全局偏好）。
     *
     * 默认关：采集站的内嵌字幕经常是硬塞的英文字幕或外挂广告轨，
     * 进来就自己弹出来比没有更烦。
     */
    var subtitleOn by mutableStateOf(false)
        private set

    /** 字幕字号倍率，取值见 [subtitleScales] */
    var subtitleScale by mutableFloatStateOf(1f)
        private set

    /**
     * 片头时长（全局默认，毫秒）。
     *
     * 只是「新剧第一次播时用什么」：一旦某部剧单独调过，就记在
     * [com.projectionroom.ui.playback.SkipStore] 里，不再跟这个值走。
     */
    var introMs by mutableLongStateOf(SkipRule.DEFAULT_INTRO)
        private set

    /** 片尾提示时长（全局默认，毫秒）。0 表示不提示下一集 */
    var outroMs by mutableLongStateOf(SkipRule.DEFAULT_OUTRO)
        private set

    /**
     * 每次读都回读一次 SharedPreferences：它本身有内存缓存，代价可以忽略，
     * 但省掉了「谁先 init、改完谁去同步」的顺序问题。
     */
    fun init(context: Context) {
        runCatching {
            val sp = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            val v = sp.getLong(K_STEP, 10_000L)
            doubleTapMs = if (v in steps) v else 10_000L
            val name = sp.getString(K_DECODER, null)
            decoder = DecoderMode.values().firstOrNull { it.name == name } ?: DecoderMode.Auto
            subtitleOn = sp.getBoolean(K_SUB_ON, false)
            val scale = sp.getFloat(K_SUB_SCALE, 1f)
            subtitleScale = if (scale in subtitleScales) scale else 1f
            val intro = sp.getLong(K_INTRO, SkipRule.DEFAULT_INTRO)
            introMs = if (intro in SkipRule.introOptions) intro else SkipRule.DEFAULT_INTRO
            val outro = sp.getLong(K_OUTRO, SkipRule.DEFAULT_OUTRO)
            outroMs = if (outro in SkipRule.outroOptions) outro else SkipRule.DEFAULT_OUTRO
        }
    }

    fun setIntroMs(context: Context, ms: Long) {
        if (ms !in SkipRule.introOptions) return
        introMs = ms
        runCatching {
            context.applicationContext
                .getSharedPreferences(FILE, Context.MODE_PRIVATE)
                .edit().putLong(K_INTRO, ms).apply()
        }
    }

    fun setOutroMs(context: Context, ms: Long) {
        if (ms !in SkipRule.outroOptions) return
        outroMs = ms
        runCatching {
            context.applicationContext
                .getSharedPreferences(FILE, Context.MODE_PRIVATE)
                .edit().putLong(K_OUTRO, ms).apply()
        }
    }

    fun setSubtitleOn(context: Context, on: Boolean) {
        subtitleOn = on
        runCatching {
            context.applicationContext
                .getSharedPreferences(FILE, Context.MODE_PRIVATE)
                .edit().putBoolean(K_SUB_ON, on).apply()
        }
    }

    fun setSubtitleScale(context: Context, scale: Float) {
        if (scale !in subtitleScales) return
        subtitleScale = scale
        runCatching {
            context.applicationContext
                .getSharedPreferences(FILE, Context.MODE_PRIVATE)
                .edit().putFloat(K_SUB_SCALE, scale).apply()
        }
    }

    /** 字号档位的展示文案 */
    fun scaleLabel(scale: Float): String = when {
        scale <= 0.9f -> "小"
        scale <= 1.05f -> "中"
        scale <= 1.3f -> "大"
        else -> "特大"
    }

    fun setDecoder(context: Context, mode: DecoderMode) {
        decoder = mode
        runCatching {
            context.applicationContext
                .getSharedPreferences(FILE, Context.MODE_PRIVATE)
                .edit().putString(K_DECODER, mode.name).apply()
        }
    }

    fun setDoubleTapMs(context: Context, ms: Long) {
        if (ms !in steps) return
        doubleTapMs = ms
        runCatching {
            context.applicationContext
                .getSharedPreferences(FILE, Context.MODE_PRIVATE)
                .edit().putLong(K_STEP, ms).apply()
        }
    }

    /** 「10 秒」这种展示文案 */
    fun label(ms: Long): String = "${ms / 1000} 秒"
}
