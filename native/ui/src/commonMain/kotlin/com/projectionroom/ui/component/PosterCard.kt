package com.projectionroom.ui.component

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.projectionroom.ui.theme.LocalProomTv
import com.projectionroom.ui.theme.ProomColor
import com.projectionroom.ui.theme.ProomMotion
import com.projectionroom.ui.theme.ProomShape
import com.projectionroom.ui.theme.ProomSpace
import com.projectionroom.ui.theme.ProomType

/**
 * 海报卡。比例、圆角、标题行高全部沿用 Web 版（2:3.05、14dp、两行定高），
 * 这样同一部片在两个端上的占位高度是一致的，不会出现「手机上看是宽卡、电视上是窄卡」。
 */
@Composable
fun PosterCard(
    title: String,
    meta: String = "",
    badge: String = "",
    poster: String = "",
    progress: Float = 0f,
    modifier: Modifier = Modifier,
    focused: Boolean = false,
    onClick: () -> Unit = {},
) {
    val isTv = LocalProomTv.current
    // 电视要有明显的聚焦反馈；手机靠触摸态，不放大
    val target = if (focused) {
        if (isTv) ProomMotion.focusScaleTv else ProomMotion.focusScalePhone
    } else 1f
    val scale by animateFloatAsState(target, tween(ProomMotion.short, easing = ProomMotion.ease))

    Column(modifier = modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .scale(scale)          // 焦点放大只作用于海报，文字不跟着变大
                .aspectRatio(2f / 3.05f)
                .clip(RoundedCornerShape(ProomShape.md))
                .background(ProomColor.card)
                .then(
                    if (focused && isTv) {
                        Modifier.border(2.dp, ProomColor.accent, RoundedCornerShape(ProomShape.md))
                    } else Modifier
                ),
        ) {
            // 垫底：首字 + 渐变。图没来（或加载失败）时不会是纯黑洞
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(
                        Brush.linearGradient(listOf(ProomColor.card, ProomColor.bgSoft))
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = title.take(1),
                    style = ProomType.headline,
                    color = ProomColor.lineStrong,
                )
            }
            if (poster.isNotBlank()) {
                AsyncImage(
                    model = poster,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.matchParentSize(),
                )
            }
            if (progress > 0f) {
                // 「继续观看」的进度条：贴在卡片底边，和 Web 版一致
                Box(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(3.dp)
                        .background(ProomColor.lineStrong)
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth(progress.coerceIn(0f, 1f))
                            .height(3.dp)
                            .background(ProomColor.accent)
                    )
                }
            }
            if (badge.isNotBlank()) {
                Text(
                    text = badge,
                    style = ProomType.label,
                    color = ProomColor.text,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .clip(RoundedCornerShape(ProomShape.sm))
                        .background(ProomColor.surface2)
                        .padding(horizontal = 6.dp, vertical = 3.dp),
                )
            }
        }
        // 标题固定占两行高度：Web 版是靠 min-height:2.8em 做的，
        // Compose 里 minLines 在部分版本上不稳定，直接用定高 Box 更保险
        Box(
            modifier = Modifier
                .padding(top = ProomSpace.sm)
                .height(40.dp),
        ) {
            Text(
                text = title,
                style = ProomType.cardTitle,
                color = ProomColor.text,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (meta.isNotBlank()) {
            Text(
                text = meta,
                style = ProomType.meta,
                color = ProomColor.muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
