package com.projectionroom.ui.component

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.projectionroom.shared.model.Vod
import com.projectionroom.ui.theme.LocalProomTv
import com.projectionroom.ui.theme.ProomColor
import com.projectionroom.ui.theme.ProomMotion
import com.projectionroom.ui.theme.ProomShape
import com.projectionroom.ui.theme.ProomSpace
import com.projectionroom.ui.theme.ProomType

/**
 * 16:9 横版卡，用于「继续观看」。
 *
 * 和竖版海报卡的区别不只是比例：横版能塞下「看到第几集」这一行信息，
 * 也更容易认出画面（剧集类尤其明显）。Web 版就是这么做的。
 */
@Composable
fun WideCard(
    vod: Vod,
    width: Dp,
    focused: Boolean = false,
    onClick: () -> Unit = {},
) {
    val isTv = LocalProomTv.current
    val scale by animateFloatAsState(
        if (focused && isTv) ProomMotion.focusScaleTv else 1f,
        tween(ProomMotion.short, easing = ProomMotion.ease),
        label = "widecard",
    )
    val shape = RoundedCornerShape(ProomShape.md)

    Column(Modifier.width(width)) {
        Box(
            Modifier
                .fillMaxWidth()
                .scale(scale)
                .aspectRatio(16f / 9f)
                .clip(shape)
                .background(ProomColor.card)
                .then(if (focused && isTv) Modifier.border(2.dp, ProomColor.accent, shape) else Modifier)
                .clickable(onClick = onClick),
        ) {
            if (vod.pic.isNotBlank()) {
                AsyncImage(
                    model = vod.pic,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            // 底部压暗，保证角标和进度条在任何画面上都看得清
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(
                        0.55f to Color.Transparent,
                        1f to Color.Black.copy(alpha = 0.72f),
                    )
                )
            )
            if (vod.type.isNotBlank()) {
                Text(
                    text = vod.type,
                    style = ProomType.label,
                    color = Color.White.copy(alpha = 0.92f),
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(start = 8.dp, bottom = 8.dp),
                )
            }
            if (vod.progress > 0f) {
                Box(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(3.dp)
                        .background(Color.White.copy(alpha = 0.22f))
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth(vod.progress.coerceIn(0f, 1f))
                            .height(3.dp)
                            .background(ProomColor.accent)
                    )
                }
            }
        }
        Text(
            text = vod.name,
            style = ProomType.cardTitle,
            color = ProomColor.text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = ProomSpace.sm),
        )
    }
}
