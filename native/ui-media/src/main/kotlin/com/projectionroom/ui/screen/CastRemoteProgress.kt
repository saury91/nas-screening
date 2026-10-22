package com.projectionroom.ui.screen

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.projectionroom.ui.component.PlayerIcons
import com.projectionroom.ui.theme.ProomColor
import com.projectionroom.ui.theme.ProomSp

/** 数字统一等宽：跳秒时右边的总时长不会左右抖 */
internal val clockFont = FontFamily.Monospace

/**
 * 进度。
 *
 * 自绘而不用 Material Slider：它的拇指是固定尺寸的圆 + 光环，压在 3dp
 * 细轨上比例失调；这里要的是「细线 + 一颗小亮点」，但触摸区仍给足 30dp。
 */
@Composable
internal fun RemoteProgress(
    live: Boolean,
    enabled: Boolean,
    position: Long,
    duration: Long,
    onSeek: (Long) -> Unit,
) {
    var drag by remember { mutableFloatStateOf(-1f) }
    var trackW by remember { mutableIntStateOf(1) }

    if (live) {
        Column {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(30.dp),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(2.dp)
                        .clip(CircleShape)
                        .background(ProomColor.line),
                )
            }
            Text("直播流不支持拖动进度", color = ProomColor.muted, fontSize = ProomSp(11.5.sp))
        }
        return
    }

    val total = if (duration > 0) duration.toFloat() else 0f
    val frac = if (total > 0f) {
        (if (drag >= 0f) drag else position.toFloat() / total).coerceIn(0f, 1f)
    } else 0f
    val shown = if (drag >= 0f && total > 0f) (drag * total).toLong() else position

    // 拖动时拇指放大：给「抓住了」的即时反馈
    val thumbScale by animateFloatAsState(
        targetValue = if (drag >= 0f) 1.35f else 1f,
        animationSpec = tween(120),
        label = "thumb",
    )

    Column {
        Box(
            Modifier
                .fillMaxWidth()
                .height(30.dp)
                .onSizeChanged { trackW = it.width.coerceAtLeast(1) }
                .pointerInput(enabled, total) {
                    if (!enabled || total <= 0f) return@pointerInput
                    detectHorizontalDragGestures(
                        onDragStart = { off -> drag = (off.x / trackW).coerceIn(0f, 1f) },
                        onDragEnd = {
                            if (drag >= 0f) onSeek((drag * total).toLong())
                            drag = -1f
                        },
                        onDragCancel = { drag = -1f },
                        onHorizontalDrag = { change, _ ->
                            drag = (change.position.x / trackW).coerceIn(0f, 1f)
                        },
                    )
                }
                .pointerInput(enabled, total) {
                    if (!enabled || total <= 0f) return@pointerInput
                    detectTapGestures { off -> onSeek((off.x / trackW * total).toLong()) }
                },
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val cy = size.height / 2f
                val r = 1.6.dp.toPx()

                drawRoundRect(
                    color = ProomColor.lineStrong,
                    topLeft = Offset(0f, cy - r),
                    size = Size(size.width, r * 2),
                    cornerRadius = CornerRadius(r),
                )

                val w = size.width * frac
                if (w > 0.5f) {
                    drawRoundRect(
                        brush = Brush.horizontalGradient(
                            listOf(ProomColor.castSoft, ProomColor.cast),
                        ),
                        topLeft = Offset(0f, cy - r),
                        size = Size(w, r * 2),
                        cornerRadius = CornerRadius(r),
                    )
                }

                val tone = if (enabled) 1f else 0.45f
                drawCircle(
                    color = ProomColor.cast.copy(alpha = 0.22f * tone),
                    radius = 9.dp.toPx() * thumbScale,
                    center = Offset(w, cy),
                )
                drawCircle(
                    color = ProomColor.castSoft.copy(alpha = tone),
                    radius = 4.5.dp.toPx() * thumbScale,
                    center = Offset(w, cy),
                )
            }
        }

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                fmtClock(shown),
                color = if (drag >= 0f) ProomColor.cast else ProomColor.dim,
                fontSize = ProomSp(11.5.sp),
                fontFamily = clockFont,
            )
            Text(
                if (duration > 0) fmtClock(duration) else "--:--",
                color = ProomColor.muted,
                fontSize = ProomSp(11.5.sp),
                fontFamily = clockFont,
            )
        }
    }
}

@Composable
internal fun MainControls(
    enabled: Boolean,
    playing: Boolean,
    hasPrev: Boolean,
    hasNext: Boolean,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onToggle: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SideSkipKey(PlayerIcons.Prev, "上一集", enabled && hasPrev, onPrev)
        Spacer(Modifier.width(40.dp))
        PlayToggle(enabled = enabled, playing = playing, onClick = onToggle)
        Spacer(Modifier.width(40.dp))
        SideSkipKey(PlayerIcons.Next, "下一集", enabled && hasNext, onNext)
    }
}

@Composable
private fun SideSkipKey(icon: ImageVector, label: String, enabled: Boolean, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(48.dp)
                .clip(CircleShape)
                .clickable(enabled = enabled, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon,
                contentDescription = label,
                tint = if (enabled) ProomColor.dim else ProomColor.muted.copy(alpha = 0.35f),
                modifier = Modifier.size(18.dp),
            )
        }
        Spacer(Modifier.height(9.dp))
        Text(
            label,
            color = if (enabled) ProomColor.muted else ProomColor.muted.copy(alpha = 0.35f),
            fontSize = ProomSp(10.5.sp),
            letterSpacing = 0.3.sp,
        )
    }
}

@Composable
private fun PlayToggle(enabled: Boolean, playing: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(84.dp)
            // 光晕垫底：纯色圆直接放黑底上会显得很「贴」，
            // 一圈淡暖光把它托起来，也是整页唯一的发光点
            .drawBehind {
                if (enabled) {
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                ProomColor.castGlow,
                                ProomColor.cast.copy(alpha = 0.12f),
                                Color.Transparent,
                            ),
                            center = center,
                            radius = size.minDimension / 1.85f,
                        ),
                    )
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(68.dp)
                .clip(CircleShape)
                .background(
                    if (enabled) {
                        Brush.verticalGradient(listOf(ProomColor.castSoft, ProomColor.cast))
                    } else {
                        Brush.verticalGradient(listOf(ProomColor.surface2, ProomColor.surface2))
                    },
                )
                .clickable(enabled = enabled, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (playing) PlayerIcons.Pause else PlayerIcons.Play,
                contentDescription = if (playing) "暂停" else "播放",
                tint = if (enabled) ProomColor.castInk else ProomColor.muted,
                modifier = Modifier.size(27.dp),
            )
        }
    }
}
