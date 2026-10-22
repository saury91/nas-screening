package com.projectionroom.ui.screen

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

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
 * 目前只有一项「双击快进秒数」：默认 10 秒是主流播放器的习惯，但看剧和看
 * 教学视频对步长的需求差很远 —— 前者嫌短、后者嫌长，所以做成可选。
 *
 * 和 [com.projectionroom.ui.music.AudioFx] 同一套写法：`object` + SharedPreferences，
 * 值用 snapshot state，设置页改完播放页立刻生效，不用重启也不用回传。
 */
object PlaybackPrefs {

    private const val FILE = "proom_playback"
    private const val K_STEP = "double_tap_ms"
    private const val K_DECODER = "decoder_mode"

    /** 可选的步长：5 秒适合精确定位，30 秒适合快进跳过片头 */
    val steps: List<Long> = listOf(5_000L, 10_000L, 30_000L)

    /** 双击左右半屏快退/快进的毫秒数 */
    var doubleTapMs by mutableLongStateOf(10_000L)
        private set

    /** 解码方式：改它会让播放页重建播放器，从当前位置续播 */
    var decoder by mutableStateOf(DecoderMode.Auto)
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
        }
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
