package com.projectionroom.ui.screen

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.projectionroom.ui.theme.ProomColor
import com.projectionroom.ui.theme.ProomShape
import com.projectionroom.ui.theme.ProomSp

@Composable
internal fun SecondaryControls(
    enabled: Boolean,
    rate: Float,
    volume: Float,
    canRate: Boolean,
    canVolume: Boolean,
    canSeek: Boolean,
    onSeekBy: (Long) -> Unit,
    onRate: (Float) -> Unit,
    onVolume: (Float) -> Unit,
) {
    var rateOpen by remember { mutableStateOf(false) }
    var volOpen by remember { mutableStateOf(false) }

    Column {
        // 等宽四格：长度不一致的文字如果按内容排版，整行会歪
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            ToolPill("−10s", Modifier.weight(1f), enabled && canSeek) { onSeekBy(-10_000) }
            ToolPill("+10s", Modifier.weight(1f), enabled && canSeek) { onSeekBy(10_000) }
            ToolPill(
                text = trimRate(rate).removeSuffix(".0") + "×",
                modifier = Modifier.weight(1f),
                enabled = enabled && canRate,
                active = canRate && kotlin.math.abs(rate - 1f) > 0.01f,
            ) {
                rateOpen = !rateOpen
                volOpen = false
            }
            ToolPill(
                text = "音量",
                modifier = Modifier.weight(1f),
                enabled = enabled && canVolume,
                active = volOpen,
            ) {
                volOpen = !volOpen
                rateOpen = false
            }
        }

        AnimatedVisibility(
            visible = rateOpen && canRate && enabled,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp)
                    .clip(RoundedCornerShape(ProomShape.md))
                    .background(Color.White.copy(alpha = 0.05f))
                    .border(1.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(ProomShape.md))
                    .padding(horizontal = 14.dp, vertical = 13.dp),
            ) {
                PanelLabel("播放速度")
                Spacer(Modifier.height(10.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f).forEach { r ->
                        val sel = kotlin.math.abs(rate - r) < 0.01f
                        Box(
                            Modifier
                                .weight(1f)
                                .height(38.dp)
                                .clip(RoundedCornerShape(ProomShape.sm))
                                .background(
                                    if (sel) ProomColor.cast.copy(alpha = 0.16f) else Color.White.copy(alpha = 0.04f)
                                )
                                .border(
                                    1.dp,
                                    if (sel) ProomColor.cast.copy(alpha = 0.7f) else ProomColor.line,
                                    RoundedCornerShape(ProomShape.sm),
                                )
                                .clickable { onRate(r) },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                trimRate(r),
                                color = if (sel) ProomColor.cast else ProomColor.dim,
                                fontSize = ProomSp(12.sp),
                                fontWeight = if (sel) FontWeight.Medium else FontWeight.Normal,
                            )
                        }
                    }
                }
            }
        }

        AnimatedVisibility(
            visible = volOpen && canVolume && enabled,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp)
                    .clip(RoundedCornerShape(ProomShape.md))
                    .background(Color.White.copy(alpha = 0.05f))
                    .border(1.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(ProomShape.md))
                    .padding(horizontal = 14.dp, vertical = 13.dp),
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    PanelLabel("电视音量")
                    Spacer(Modifier.weight(1f))
                    Text(
                        "${(volume * 100).toInt()}%",
                        color = ProomColor.cast,
                        fontSize = ProomSp(11.5.sp),
                        fontFamily = clockFont,
                    )
                }
                Spacer(Modifier.height(6.dp))
                LevelSlider(
                    value = volume,
                    enabled = enabled,
                    onChange = onVolume,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/** 灰掉的键仍然可点：点了会提示「电视不支持」，比默不作声强 */
@Composable
private fun ToolPill(
    text: String,
    modifier: Modifier = Modifier,
    enabled: Boolean,
    active: Boolean = false,
    onClick: () -> Unit,
) {
    // 不画底色：四个方块并排时，底色会把「当前生效的那一个」也压平。
    // 现在只有被选中的项亮起一根青色短线，其余是纯文字。
    Column(
        modifier
            .height(42.dp)
            .clip(RoundedCornerShape(ProomShape.sm + 2.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 2.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text,
            color = when {
                !enabled -> ProomColor.muted.copy(alpha = 0.4f)
                active -> ProomColor.cast
                else -> ProomColor.dim
            },
            fontSize = ProomSp(12.5.sp),
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(5.dp))
        Box(
            Modifier
                .width(16.dp)
                .height(1.5.dp)
                .clip(RoundedCornerShape(1.dp))
                .background(if (active && enabled) ProomColor.cast else Color.Transparent)
        )
    }
}

@Composable
private fun PanelLabel(text: String) {
    Text(
        text,
        color = ProomColor.muted,
        fontSize = ProomSp(10.5.sp),
        fontWeight = FontWeight.Medium,
        letterSpacing = 1.6.sp,
    )
}

/** 音量条。和进度条同一套画法，只是没有时间标签 */
@Composable
private fun LevelSlider(
    value: Float,
    enabled: Boolean,
    onChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    var w by remember { mutableIntStateOf(1) }
    Box(
        modifier
            .height(34.dp)
            .onSizeChanged { w = it.width.coerceAtLeast(1) }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectHorizontalDragGestures { change, _ ->
                    onChange((change.position.x / w).coerceIn(0f, 1f))
                }
            }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectTapGestures { off -> onChange((off.x / w).coerceIn(0f, 1f)) }
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
            val filled = size.width * value.coerceIn(0f, 1f)
            if (filled > 0.5f) {
                drawRoundRect(
                    brush = Brush.horizontalGradient(listOf(ProomColor.castSoft, ProomColor.cast)),
                    topLeft = Offset(0f, cy - r),
                    size = Size(filled, r * 2),
                    cornerRadius = CornerRadius(r),
                )
            }
            drawCircle(
                color = ProomColor.castSoft,
                radius = 4.5.dp.toPx(),
                center = Offset(filled, cy),
            )
        }
    }
}

/**
 * 底部的入口行：选集 / 换源。
 *
 * 做成「标题 + 一行说明 + 箭头」的行，而不是两块大方块：
 * 方块的正文挤在左上角，点开之后才知道里面是什么；行式能先把信息说清楚。
 */
@Composable
internal fun PanelRow(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    // 去掉方块外框，改成两行行式 + 一根细线：一眼能读出「这是什么、里面有几种」，
    // 而不是先看见两块和播放键同样重的灰色矩形。
    Column(modifier) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 2.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    color = ProomColor.text,
                    fontSize = ProomSp(14.sp),
                    fontWeight = FontWeight.Medium,
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    subtitle,
                    color = ProomColor.muted,
                    fontSize = ProomSp(11.5.sp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(
                "›",
                color = ProomColor.cast.copy(alpha = 0.7f),
                fontSize = ProomSp(19.sp),
            )
        }
        Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(ProomColor.line.copy(alpha = 0.55f))
        )
    }
}
