package com.projectionroom.app.cast

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.Player
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay

/** 投屏播放页的可观察状态。进度每秒一变，所以整块用 State 驱动 */
data class CastUiState(
    val title: String = "",
    val episode: String = "",
    val line: Int = 0,
    val ep: Int = 0,
    val playing: Boolean = false,
    val buffering: Boolean = true,
    val error: String? = null,
    val rate: Float = 1f,
    val volume: Float = 1f,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
)

/**
 * 投屏页专用的冷色强调。
 *
 * 手机遥控页与电视控制条共用同一套值：两边是同一个动作的两个入口，
 * 颜色不一致会让人以为按的是两件事。暖金留给封面/评分这类内容侧，
 * 播放控制一律走青，画面本身的暖调就不会和控制层抢注意力。
 */
internal val CastCyan = Color(0xFF33C1DA)
internal val CastCyanLight = Color(0xFF96EEF9)
internal val CastCyanInk = Color(0xFF06222A)

/** 玻璃层：半透明 + 一线描边。电视上不加真模糊（低端解码器吃不消），靠明度差做质感 */
private val GlassFill = Color.White.copy(alpha = 0.10f)
private val GlassLine = Color.White.copy(alpha = 0.16f)

/**
 * 接收端播放页：ExoPlayer 画面铺满，控制条浮在下方。
 *
 * 电视上没有触控，所有操作都靠 D-pad 焦点：初始焦点给「播放/暂停」，
 * 左右移动就能到换速度、调音量、退出。6 秒无操作控制条自动淡出。
 */
@Composable
fun CastPlayerScreen(
    state: CastUiState,
    playerView: () -> PlayerView,
    player: () -> Player?,
    onTogglePlay: () -> Unit,
    onSeekBy: (Long) -> Unit,
    onSeekTo: (Long) -> Unit,
    onRate: (Float) -> Unit,
    onVolume: (Float) -> Unit,
    onExit: () -> Unit,
) {
    var showControls by remember { mutableStateOf(true) }
    var tick by remember { mutableIntStateOf(0) }
    val playFocus = remember { FocusRequester() }

    // 任何按键都算"用户在操作"：重新亮出控制条并把 6 秒倒计时清零
    LaunchedEffect(tick) {
        if (!showControls) return@LaunchedEffect
        delay(6_000)
        showControls = false
    }
    LaunchedEffect(Unit) { runCatching { playFocus.requestFocus() } }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onPreviewKeyEvent {
                showControls = true
                tick++
                false
            }
    ) {
        AndroidView(factory = { playerView() }, modifier = Modifier.fillMaxSize())

        // 缓冲：只在画面上方压一行字，不挡内容
        if (state.buffering && state.error == null) {
            Text(
                "缓冲中…",
                color = Color.White.copy(alpha = 0.75f),
                fontSize = 14.sp,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(24.dp),
            )
        }

        state.error?.let { msg ->
            Text(
                msg,
                color = Color(0xFFFF8A80),
                fontSize = 16.sp,
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(horizontal = 48.dp),
            )
        }

        if (showControls) {
            Column(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            0f to Color.Transparent,
                            0.5f to Color.Black.copy(alpha = 0.32f),
                            1f to Color.Black.copy(alpha = 0.86f),
                        )
                    )
                    .padding(horizontal = 48.dp, vertical = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Progress(state)
                Spacer(Modifier.height(18.dp))

                // 整条控制收进一颗胶囊：片名在左，操作用无底色图标向右排。
                // 之前 7 个灰底圆按钮平铺，电视上一排看过去全是同重量的色块，
                // 眼睛找不到「按哪个」，主键也被稀释掉了。
                Row(
                    Modifier
                        .clip(RoundedCornerShape(50))
                        .background(GlassFill)
                        .border(1.dp, GlassLine, RoundedCornerShape(50))
                        .padding(start = 24.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.widthIn(max = 320.dp).padding(end = 20.dp)) {
                        Text(
                            state.title.ifBlank { "投屏内容" },
                            color = Color.White,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            listOfNotNull(
                                state.episode.ifBlank { null },
                                "线路 ${state.line + 1}",
                            ).joinToString(" · "),
                            color = Color.White.copy(alpha = 0.45f),
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 3.dp),
                        )
                    }

                    Box(
                        Modifier
                            .width(1.dp)
                            .height(30.dp)
                            .background(Color.White.copy(alpha = 0.14f))
                    )

                    Row(
                        Modifier.padding(start = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CastKey("−10 秒") { onSeekBy(-10_000) }
                        PlayKey(
                            playing = state.playing,
                            modifier = Modifier.focusRequester(playFocus),
                            onClick = onTogglePlay,
                        )
                        CastKey("+10 秒") { onSeekBy(10_000) }
                        CastKey(speedLabel(state.rate)) { onRate(nextRate(state.rate)) }
                        CastKey("音量 −") { onVolume(state.volume - 0.1f) }
                        CastKey("音量 +") { onVolume(state.volume + 0.1f) }
                        CastKey("退出") { onExit() }
                    }
                }
            }
        }
    }
}

@Composable
private fun Progress(state: CastUiState) {
    // 电视上没有"拖动"这种输入，进度条只做显示；跳转交给 ±10 秒和手机遥控页
    val frac = if (state.durationMs > 0) {
        (state.positionMs.toFloat() / state.durationMs).coerceIn(0f, 1f)
    } else 0f

    Row(
        Modifier.fillMaxWidth(0.56f),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(fmt(state.positionMs), color = Color.White.copy(alpha = 0.55f), fontSize = 12.sp)
        Spacer(Modifier.width(12.dp))
        Box(
            Modifier
                .weight(1f)
                .height(3.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(Color.White.copy(alpha = 0.20f))
        ) {
            Box(
                Modifier
                    .fillMaxWidth(frac)
                    .height(3.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Brush.horizontalGradient(listOf(CastCyanLight, CastCyan)))
            )
        }
        Spacer(Modifier.width(12.dp))
        Text(fmt(state.durationMs), color = Color.White.copy(alpha = 0.55f), fontSize = 12.sp)
    }
}

/**
 * 胶囊里的次级键：不画底色，只在获得焦点时亮出青色描边。
 *
 * 电视是 3 米外看的，焦点框必须够亮够粗；而没有焦点时如果每个键都带底色，
 * 一排看过去就是一条灰带，等于没有主次。
 */
@Composable
private fun CastKey(
    label: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(50)
    Box(
        modifier
            .height(48.dp)
            .widthIn(min = 58.dp)
            .onFocusChanged { focused = it.isFocused }
            .clip(shape)
            .background(if (focused) Color.White.copy(alpha = 0.18f) else Color.Transparent)
            .border(2.dp, if (focused) CastCyan else Color.Transparent, shape)
            .clickable { onClick() }
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = if (focused) Color.White else Color.White.copy(alpha = 0.78f),
            fontSize = 13.sp,
        )
    }
}

/** 主键：整条控制里唯一的实心色块，青色渐变 + 深色图形，远看也认得出 */
@Composable
private fun PlayKey(
    playing: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier
            .size(60.dp)
            .onFocusChanged { focused = it.isFocused }
            .clip(CircleShape)
            .background(Brush.linearGradient(listOf(CastCyanLight, CastCyan)))
            .border(2.dp, if (focused) Color.White else Color.Transparent, CircleShape)
            .clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        if (playing) {
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                Box(Modifier.size(5.dp, 20.dp).clip(RoundedCornerShape(2.dp)).background(CastCyanInk))
                Box(Modifier.size(5.dp, 20.dp).clip(RoundedCornerShape(2.dp)).background(CastCyanInk))
            }
        } else {
            Canvas(Modifier.size(20.dp)) {
                val triangle = Path().apply {
                    moveTo(2f, 1f)
                    lineTo(size.width - 1f, size.height / 2f)
                    lineTo(2f, size.height - 1f)
                    close()
                }
                drawPath(triangle, CastCyanInk)
            }
        }
    }
}

private fun fmt(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "$h:${pad(m)}:${pad(s)}" else "$m:${pad(s)}"
}

private fun pad(v: Long): String = if (v < 10) "0$v" else "$v"

private fun speedLabel(rate: Float): String =
    if (rate == rate.toInt().toFloat()) "${rate.toInt()}x" else "${rate}x"

private fun nextRate(rate: Float): Float {
    val all = listOf(0.75f, 1f, 1.25f, 1.5f, 2f)
    val i = all.indexOfFirst { kotlin.math.abs(it - rate) < 0.01f }
    return all[(i + 1).mod(all.size)]
}
