package com.projectionroom.ui.component

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.projectionroom.ui.theme.ProomColor
import com.projectionroom.ui.theme.TvRatio
import com.projectionroom.ui.theme.TvSpace
import com.projectionroom.ui.theme.ProomMotion
import com.projectionroom.ui.theme.ProomShape
import com.projectionroom.ui.theme.ProomType
import com.projectionroom.ui.theme.textBlockHeight
import com.projectionroom.ui.component.RemoteImage

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
    // 电视要有明显的聚焦反馈；手机靠触摸态，不放大。
    // 焦点默认自己跟：以前靠调用方传 focused，结果片库网格、搜索结果都忘了传，
    // 遥控器移到海报上整格没有任何变化，只能靠标题文字猜选中的是哪张。
    var selfFocused by remember { mutableStateOf(false) }
    val effective = focused || selfFocused
    val target = if (effective) {
        ProomMotion.focusScaleTv
    } else 1f
    val scale by animateFloatAsState(target, tween(ProomMotion.short, easing = ProomMotion.ease))
    // 环的展开进度单独一条曲线：弹性带过冲，才有「鼓起来」而不是「描边突然出现」
    val bloom by animateFloatAsState(
        targetValue = if (effective) 1f else 0f,
        animationSpec = if (effective) {
            spring(dampingRatio = 0.52f, stiffness = 420f)
        } else {
            tween(durationMillis = 150)
        },
        label = "poster-bloom",
    )

    Column(
        modifier = modifier
            .fillMaxWidth()
            .onFocusChanged { selfFocused = it.isFocused }
            .clickable(onClick = onClick)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .scale(scale)          // 焦点放大只作用于海报，文字不跟着变大
                .aspectRatio(TvRatio.poster)
                .clip(RoundedCornerShape(ProomShape.md))
                .background(ProomColor.card)
                .then(
                    if (effective) {
                        // 白环 + 同色柔晕：白色只表示「遥控器停在这」，金色留给「已选中」
                        Modifier.tvFocusBloom(RoundedCornerShape(ProomShape.md), bloom, ringWidth = 2.dp)
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
                RemoteImage(
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
                        .height(TvSpace.s4)
                        .background(ProomColor.lineStrong)
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth(progress.coerceIn(0f, 1f))
                            .height(TvSpace.s4)
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
                        .padding(TvSpace.s8)
                        .clip(RoundedCornerShape(ProomShape.sm))
                        .background(ProomColor.surface2)
                        .padding(horizontal = TvSpace.s8, vertical = TvSpace.s4),
                )
            }
        }
        // 标题固定占两行高度：Web 版是靠 min-height:2.8em 做的，
        // Compose 里 minLines 在部分版本上不稳定，直接用定高 Box 更保险。
        // 高度走 textBlockHeight：行高 × 2 会差 2dp（includeFontPadding），
        // 结果两行永远排不满、第一行末尾就打省略号。
        Box(
            modifier = Modifier
                .padding(top = TvSpace.s8)
                .height(textBlockHeight(ProomType.cardTitle, 2)),
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
