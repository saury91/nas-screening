package com.projectionroom.ui.component

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.projectionroom.ui.theme.ProomColor
import com.projectionroom.ui.theme.ProomShape

/**
 * 骨架屏。
 *
 * 首屏什么都还没有时，给「和真实内容同尺寸」的灰块 —— 页面形状先立住，
 * 数据到了直接换内容，不会像转圈那样让整页跳一下。
 */
@Composable
private fun blockAlpha(): Float {
    val t = rememberInfiniteTransition(label = "skl")
    val a by t.animateFloat(
        initialValue = 0.45f,
        targetValue = 0.85f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "sklAlpha",
    )
    return a
}

@Composable
fun SkeletonBlock(
    modifier: Modifier = Modifier,
    height: Dp? = null,
    radius: Dp = ProomShape.md,
) {
    val a = blockAlpha()
    Box(
        modifier
            .then(if (height != null) Modifier.height(height) else Modifier)
            .clip(RoundedCornerShape(radius))
            .background(ProomColor.surface.copy(alpha = a))
    )
}

/** 海报骨架：2:3，和 PosterCard 同尺寸 */
@Composable
fun PosterSkeleton(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth()) {
        androidx.compose.foundation.layout.Column {
            SkeletonBlock(Modifier.fillMaxWidth().aspectRatio(2f / 3f), radius = ProomShape.md)
            SkeletonBlock(Modifier.fillMaxWidth().padding(top = 8.dp), height = 12.dp, radius = 6.dp)
            SkeletonBlock(
                Modifier.fillMaxWidth(0.6f).padding(top = 6.dp),
                height = 10.dp,
                radius = 5.dp,
            )
        }
    }
}

/** 横向宽卡骨架：16:9，用于「继续观看」 */
@Composable
fun WideSkeleton(modifier: Modifier = Modifier) {
    SkeletonBlock(modifier.aspectRatio(16f / 9f), radius = ProomShape.md)
}
