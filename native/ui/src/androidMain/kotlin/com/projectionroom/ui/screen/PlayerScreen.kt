package com.projectionroom.ui.screen

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.projectionroom.shared.model.Vod
import com.projectionroom.ui.component.BufferDots
import com.projectionroom.ui.theme.ProomColor
import com.projectionroom.ui.theme.ProomType
import kotlinx.coroutines.delay

/**
 * 播放页：Media3 硬解 + 自绘缓冲态。
 *
 * 三件事在这里闭环（都是 Web 版打磨过的体验）：
 *  1. 断点续播：按线路 URL 记进度，重进同一集从上次位置开始；
 *  2. 一集播完自动接下一集，最后一集才停下；
 *  3. 缓冲用波点而不是转圈（关掉系统 spinner）。
 *
 * 走 ExoPlayer 硬解，不在 WebView 里软解 —— 老电视软解 4K 会卡死。
 */
/** 进程内的初始朝向：进播放器前记一次，退出时还原到它 */
private object OrientationMemory {
    var initial: Int? = null
}

private fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}

@Composable
fun PlayerScreen(
    vod: Vod,
    startLine: Int = 0,
    startEp: Int = 0,
    rotateLandscape: Boolean = false,
    /** 把源站直链转成可播地址（壳会传 NAS 代理，本地预览就用原样） */
    resolveUrl: (String) -> String = { it },
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("proom_progress", Context.MODE_PRIVATE) }

    // 手机竖屏看 16:9 会上下两条大黑边，进播放页转横屏，退出还原。
    // 注意：不能记「进播放器那一刻」的朝向——如果上次异常退出留下了横屏，
    // 记下来的就是横屏，回来永远转不回竖屏。只在**进程内第一次**进播放器时记。
    DisposableEffect(rotateLandscape) {
        val act = context.findActivity()
        if (OrientationMemory.initial == null) OrientationMemory.initial = act?.requestedOrientation
        if (rotateLandscape && act != null) {
            act.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
        onDispose {
            // 手机上直接锁回竖屏：比"还原 UNSPECIFIED"确定得多——
            // 系统旋转被锁住时 UNSPECIFIED 不会自己转回来，界面就卡在横屏
            if (rotateLandscape) act?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
    }

    var lineIndex by remember { mutableStateOf(startLine.coerceIn(0, (vod.lines.size - 1).coerceAtLeast(0))) }
    var epIndex by remember { mutableStateOf(startEp) }
    val line = vod.lines.getOrNull(lineIndex)
    val episode = line?.episodes?.getOrNull(epIndex)
    val exo = remember { ExoPlayer.Builder(context).build() }
    var buffering by remember { mutableStateOf(true) }
    var playbackError by remember(episode?.url) { mutableStateOf<String?>(null) }
    var failedLines by remember { mutableStateOf(0) }

    // 换集/换线路：重新准备，有进度就从上次位置续播
    DisposableEffect(episode?.url) {
        val url = episode?.url
        if (!url.isNullOrBlank()) {
            val saved = prefs.getLong(url, 0L)
            // 走代理后路径是 /api/proxy?u=…，没有 .m3u8 后缀，
            // ExoPlayer 只能靠 MIME 判断是 HLS —— 不显式指定会当普通文件解析，永远起不来。
            val isHls = url.contains(".m3u8", ignoreCase = true)
            val item = MediaItem.Builder()
                .setUri(resolveUrl(url))
                .apply { if (isHls) setMimeType(androidx.media3.common.MimeTypes.APPLICATION_M3U8) }
                .build()
            exo.setMediaItem(item, if (saved > 5_000L) saved else 0L)
            exo.prepare()
            exo.playWhenReady = true
        }
        onDispose {
            if (!url.isNullOrBlank() && exo.currentPosition > 5_000L) {
                prefs.edit().putLong(url, exo.currentPosition).apply()
            }
        }
    }

    // 监听器只挂一次：之前 keyed on `line`，换线时 onDispose 会 release 播放器，
    // 后面再用同一个已销毁的实例 prepare，结果就是死循环重试旧地址。
    val curLineIndex by rememberUpdatedState(lineIndex)
    val curEps by rememberUpdatedState(line?.episodes.orEmpty())

    DisposableEffect(exo) {
        val listener = object : Player.Listener {
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                buffering = false
                // 这条线路挂了就自动换下一条（Web 版一直是这么做的）：
                // 采集站给的直链经常过期，用户不该自己一条条试
                if (curLineIndex + 1 < vod.lines.size) {
                    failedLines += 1
                    lineIndex = curLineIndex + 1
                    epIndex = 0
                    playbackError = null
                } else {
                    playbackError = if (failedLines > 0) "所有线路都播不动" else "这条线路播不动"
                }
            }

            override fun onPlaybackStateChanged(state: Int) {
                buffering = state == Player.STATE_BUFFERING
                if (state == Player.STATE_ENDED) {
                    val eps = curEps
                    if (epIndex + 1 < eps.size) {
                        prefs.edit().remove(eps[epIndex].url).apply()
                        epIndex += 1
                    }
                }
            }
        }
        exo.addListener(listener)
        onDispose {
            exo.removeListener(listener)
            exo.release()
        }
    }

    // 每 5 秒记一次进度
    LaunchedEffect(episode?.url) {
        val url = episode?.url ?: return@LaunchedEffect
        while (true) {
            delay(5_000)
            if (exo.isPlaying) prefs.edit().putLong(url, exo.currentPosition).apply()
        }
    }

    BackHandler { onBack() }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                PlayerView(ctx).apply {
                    player = exo
                    useController = true
                    setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
                    keepScreenOn = true
                }
            },
        )

        playbackError?.let { msg ->
            Column(
                Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(msg, style = ProomType.body, color = ProomColor.dim)
                Text(
                    "点左上角返回，换一条线路",
                    style = ProomType.label,
                    color = ProomColor.muted,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }

        AnimatedVisibility(
            visible = buffering && playbackError == null,
            enter = fadeIn(),
            exit = fadeOut(),
            // 下移避开 PlayerView 中央的播放/暂停按钮
            modifier = Modifier.align(Alignment.Center).padding(top = 120.dp),
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                BufferDots()
                Text(
                    text = line?.let { "第 ${epIndex + 1} 集 · ${it.site}" } ?: "",
                    style = ProomType.label,
                    color = ProomColor.muted,
                    modifier = Modifier.padding(top = 10.dp),
                )
            }
        }
    }
}
