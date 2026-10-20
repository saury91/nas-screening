package com.projectionroom.ui.component

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxSize
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
    // 注意：surface 本身是「白 5.5%」，直接 copy(alpha=a) 是**替换**掉那 5.5%，
    // 结果变成 45%~85% 的白 —— 在暗色页面上就是一排白板。
    // 这里自己乘一遍，闪动范围落在「白 5%~11%」，才是暗色主题该有的骨架。
    Box(
        modifier
            .then(if (height != null) Modifier.height(height) else Modifier)
            .clip(RoundedCornerShape(radius))
            .background(androidx.compose.ui.graphics.Color.White.copy(alpha = 0.11f * a))
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

/** 详情页骨架：海报 + 标题行 + 简介 + 线路 + 集数网格 */
@Composable
fun DetailSkeleton(
    modifier: Modifier = Modifier,
    /** 状态栏高度：骨架也要让开，否则会顶到状态栏后面 */
    topInset: Dp = 0.dp,
    onBack: () -> Unit = {},
) {
    androidx.compose.foundation.layout.Column(
        modifier.fillMaxSize().background(ProomColor.bg)
    ) {
        // 和真实详情页同一套顶栏：56dp + 状态栏，返回键可点
        androidx.compose.foundation.layout.Row(
            Modifier.fillMaxWidth().height(56.dp + topInset)
                .padding(start = 8.dp, end = 20.dp, top = topInset),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            androidx.compose.foundation.layout.Box(
                Modifier.size(48.dp).clip(RoundedCornerShape(24.dp))
                    .clickable(onClick = onBack),
                contentAlignment = androidx.compose.ui.Alignment.Center,
            ) {
                androidx.compose.material3.Icon(
                    imageVector = TabIcons.Back,
                    contentDescription = "返回",
                    tint = ProomColor.text,
                    modifier = Modifier.size(24.dp),
                )
            }
        }
        androidx.compose.foundation.layout.Column(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)
        ) {
        androidx.compose.foundation.layout.Row {
            SkeletonBlock(Modifier.width(104.dp).aspectRatio(2f / 3f))
            androidx.compose.foundation.layout.Spacer(Modifier.width(14.dp))
            androidx.compose.foundation.layout.Column(Modifier.fillMaxWidth()) {
                SkeletonBlock(Modifier.fillMaxWidth(0.8f), height = 22.dp, radius = 8.dp)
                SkeletonBlock(Modifier.fillMaxWidth(0.45f).padding(top = 10.dp), height = 13.dp, radius = 7.dp)
                SkeletonBlock(Modifier.fillMaxWidth(0.35f).padding(top = 8.dp), height = 13.dp, radius = 7.dp)
                SkeletonBlock(Modifier.fillMaxWidth().padding(top = 16.dp), height = 40.dp, radius = 12.dp)
            }
        }
        SkeletonBlock(Modifier.fillMaxWidth().padding(top = 20.dp), height = 12.dp, radius = 6.dp)
        SkeletonBlock(Modifier.fillMaxWidth(0.9f).padding(top = 8.dp), height = 12.dp, radius = 6.dp)
        SkeletonBlock(Modifier.fillMaxWidth(0.6f).padding(top = 8.dp), height = 12.dp, radius = 6.dp)

        SkeletonBlock(Modifier.fillMaxWidth(0.3f).padding(top = 24.dp), height = 14.dp, radius = 7.dp)
        androidx.compose.foundation.layout.Row(Modifier.padding(top = 10.dp)) {
            SkeletonBlock(Modifier.weight(1f).height(56.dp), radius = 12.dp)
            androidx.compose.foundation.layout.Spacer(Modifier.width(10.dp))
            SkeletonBlock(Modifier.weight(1f).height(56.dp), radius = 12.dp)
            androidx.compose.foundation.layout.Spacer(Modifier.width(10.dp))
            SkeletonBlock(Modifier.weight(1f).height(56.dp), radius = 12.dp)
        }

        SkeletonBlock(Modifier.fillMaxWidth(0.28f).padding(top = 24.dp), height = 14.dp, radius = 7.dp)
        androidx.compose.foundation.layout.Column(Modifier.padding(top = 10.dp)) {
            repeat(3) {
                androidx.compose.foundation.layout.Row(Modifier.padding(bottom = 8.dp)) {
                    repeat(4) {
                        SkeletonBlock(Modifier.weight(1f).height(40.dp), radius = 10.dp)
                        androidx.compose.foundation.layout.Spacer(Modifier.width(8.dp))
                    }
                }
            }
        }
    }
    }
}
