package com.projectionroom.ui.screen

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.setValue

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

    /** 可选的步长：5 秒适合精确定位，30 秒适合快进跳过片头 */
    val steps: List<Long> = listOf(5_000L, 10_000L, 30_000L)

    /** 双击左右半屏快退/快进的毫秒数 */
    var doubleTapMs by mutableLongStateOf(10_000L)
        private set

    /**
     * 每次读都回读一次 SharedPreferences：它本身有内存缓存，代价可以忽略，
     * 但省掉了「谁先 init、改完谁去同步」的顺序问题。
     */
    fun init(context: Context) {
        runCatching {
            val v = context.applicationContext
                .getSharedPreferences(FILE, Context.MODE_PRIVATE)
                .getLong(K_STEP, 10_000L)
            doubleTapMs = if (v in steps) v else 10_000L
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
