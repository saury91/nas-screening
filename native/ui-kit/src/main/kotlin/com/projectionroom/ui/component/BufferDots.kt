package com.projectionroom.ui.component

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.projectionroom.ui.theme.ProomColor

/**
 * 缓冲波点：三个点依次跳起，和 Web 版是同一套节奏（1.05s 一轮，错开 130ms）。
 *
 * 不用系统自带的转圈 —— 转圈在电视上既看不清「在动」，也和这套 UI 的语言不搭。
 */
@Composable
fun BufferDots(
    modifier: Modifier = Modifier,
    color: androidx.compose.ui.graphics.Color = ProomColor.accent,
    /** 点径。电视上 10dp 在 3 米外只剩一个像素点在跳，播放页要放大到 14dp */
    dot: androidx.compose.ui.unit.Dp = 10.dp,
    gap: androidx.compose.ui.unit.Dp = 11.dp,
) {
    val t = rememberInfiniteTransition(label = "buffer")
    // 起跳高度按点径等比放：点变大、跳得还是 13dp 会显得只抖了一下
    val lift = (dot.value * 1.3f)

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(gap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(3) { i ->
            val y by t.animateFloat(
                initialValue = 0f,
                targetValue = 0f,
                animationSpec = infiniteRepeatable(
                    animation = keyframes {
                        durationMillis = 1050
                        0f at 0 using LinearEasing
                        -lift at 290
                        -lift * 0.3f at 470
                        0f at 620
                        0f at 1050
                    },
                    repeatMode = RepeatMode.Restart,
                    initialStartOffset = StartOffset(i * 130),
                ),
                label = "dot$i",
            )
            Box(
                Modifier
                    .offset(y = y.dp)
                    .size(dot)
                    .clip(CircleShape)
                    .background(color)
                    .graphicsLayer {
                        // 跳得越高越亮：静止时 0.55，最高点接近满亮
                        alpha = 0.55f + (y / (-lift * 2f)).coerceIn(0f, 0.45f)
                    }
            )
        }
    }
}
