package com.projectionroom.ui.component

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.projectionroom.ui.theme.ProomColor
import com.projectionroom.ui.theme.ProomType
import com.projectionroom.ui.theme.TvLayout

/**
 * 顶栏「正在播放」指示：三根小竖条 + 文字。
 *
 * 原来是一颗静态胶囊：除了当入口，没有传递任何「声音还在响」的信息 ——
 * 用户切到别的页面之后，唯一能看出还在播的就只有这四个字。
 * 现在竖条跟着播放起伏、暂停就趴平，和全屏播放页共享同一条时间线。
 *
 * 竖条高度用 [RepeatMode.Reverse] 的三条不同周期动画错开相位，
 * 不需要额外相位参数，视觉上就是三个不同步的呼吸节奏 —— 恒定周期同频会像心跳，不像音乐。
 */
@Composable
fun TvPlayingBadge(
    playing: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    text: String = "播放中",
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(50)
    Box(
        modifier
            // 和返回胶囊同一档最小可点高度，顶栏左右两端才是一条水平线
            .heightIn(min = TvLayout.minTouch)
            .clip(shape)
            .background(if (focused) ProomColor.accentSoft else ProomColor.surface)
            .then(if (focused) Modifier.border(2.dp, ProomColor.accent, shape) else Modifier)
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 9.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (playing) AnimatedBars() else StaticBars()
            Spacer(Modifier.width(10.dp))
            Text(
                text,
                style = ProomType.meta,
                color = if (focused) ProomColor.accent else ProomColor.text,
            )
        }
    }
}

/**
 * 播放中的竖条。
 *
 * 分支写成两个 composable 而不是「一个动画乘个系数」：暂停时那三条
 * 就完全不该有动画在跑 —— 电视盒子的合成能力有限，一个常驻的无限动画
 * 会让整棵顶栏持续重组，为了几根静止的小条白烧帧不值。
 */
@Composable
private fun AnimatedBars() {
    val t = rememberInfiniteTransition(label = "playing-bars")
    // 三个不同周期：430 / 310 / 560ms。用 Reverse 而不是 Restart，
    // 条子才会「呼吸」而不是「抽筋」
    val a by t.animateFloat(
        initialValue = BAR_MIN,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(430, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "bar-a",
    )
    val b by t.animateFloat(
        initialValue = BAR_MIN,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(310, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "bar-b",
    )
    val c by t.animateFloat(
        initialValue = BAR_MIN,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(560, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "bar-c",
    )
    BarsRow(listOf(a, b, c), ProomColor.accent)
}

/**
 * 暂停：三条齐平趴下，颜色退到 muted —— 一眼能分辨「在播」和「停了」。
 *
 * 趴平不等于贴地：压到动画的最低档（0.18）会灰得只剩三个点，
 * 从这个距离看像是渲染漏了，所以停在中间高度。
 */
@Composable
private fun StaticBars() {
    BarsRow(List(3) { 0.42f }, ProomColor.muted)
}

@Composable
private fun BarsRow(values: List<Float>, tint: Color) {
    Row(
        Modifier.height(BAR_BOX),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        values.forEach { v ->
            Box(
                Modifier
                    .width(3.dp)
                    .height(4.dp + 14.dp * v)
                    .clip(RoundedCornerShape(2.dp))
                    .background(tint)
            )
        }
    }
}

/** 条子最低到最高的一条占位：4dp 起跳，最高 18dp，正好和 meta 字高齐平 */
private val BAR_BOX = 18.dp
private const val BAR_MIN = 0.18f
