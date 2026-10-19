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
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("proom_progress", Context.MODE_PRIVATE) }

    // 手机竖屏看 16:9 会上下两条大黑边，进播放页转横屏，退出还原
    DisposableEffect(rotateLandscape) {
        val act = context.findActivity()
        val prev = act?.requestedOrientation
        if (rotateLandscape && act != null) {
            act.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
        onDispose {
            act?.requestedOrientation = prev ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    var lineIndex by remember { mutableStateOf(startLine.coerceIn(0, (vod.lines.size - 1).coerceAtLeast(0))) }
    var epIndex by remember { mutableStateOf(startEp) }
    val line = vod.lines.getOrNull(lineIndex)
    val episode = line?.episodes?.getOrNull(epIndex)
    val exo = remember { ExoPlayer.Builder(context).build() }
    var buffering by remember { mutableStateOf(true) }

    // 换集/换线路：重新准备，有进度就从上次位置续播
    DisposableEffect(episode?.url) {
        val url = episode?.url
        if (!url.isNullOrBlank()) {
            val saved = prefs.getLong(url, 0L)
            exo.setMediaItem(MediaItem.fromUri(url), if (saved > 5_000L) saved else 0L)
            exo.prepare()
            exo.playWhenReady = true
        }
        onDispose {
            if (!url.isNullOrBlank() && exo.currentPosition > 5_000L) {
                prefs.edit().putLong(url, exo.currentPosition).apply()
            }
        }
    }

    DisposableEffect(exo, line) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                buffering = state == Player.STATE_BUFFERING
                if (state == Player.STATE_ENDED) {
                    val eps = line?.episodes.orEmpty()
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

        AnimatedVisibility(
            visible = buffering,
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
