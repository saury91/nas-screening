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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.projectionroom.ui.theme.ProomColor
import com.projectionroom.ui.theme.ProomMotion
import com.projectionroom.ui.theme.ProomShape
import com.projectionroom.ui.theme.ProomType
import com.projectionroom.ui.component.RemoteImage

/**
 * 直播频道卡：16:9 台标块 + 名字 +（可选）副标题。
 *
 * 采集源基本不给台标图，所以台标位放「识别字水印」：
 * CCTV5 → 5、湖南卫视 → 湖南、ABC News → ABC。
 * 不做这层处理的话，一屏 17 个 CCTV 全是同一个「C」，等于没有台标。
 *
 * 直播页和「我的」的收藏/记录共用这一个 —— 两处各写一份的话，
 * 台标字号、圆角、行高迟早会走样。
 */
@Composable
fun ChannelTile(
    name: String,
    subtitle: String = "",
    /** 台标地址（M3U 的 tvg-logo）；为空则用生成的占位块 */
    logo: String = "",
    /** 右下角角标，比如「4 源」；空则不显示 */
    badge: String = "",
    onClick: () -> Unit = {},
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(ProomShape.md)
    val scale by animateFloatAsState(
        targetValue = if (focused && true) ProomMotion.focusScaleTv else 1f,
        animationSpec = tween(ProomMotion.short, easing = ProomMotion.ease),
        label = "chScale",
    )

    Column(
        Modifier
            .fillMaxWidth()
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .scale(scale)
                .aspectRatio(16f / 9f)
                .clip(shape)
                // 没有台标时用「按频道名生成的深色调」打底：
                // 一屏几十个格子如果都是同一个黑灰，看起来就是"大面积空白"，
                // 每个台给一点自己的色相，既统一又不再是一片死黑。
                .background(
                    Brush.linearGradient(tileColors(name))
                )
                .then(
                    if (focused && true) Modifier.border(2.dp, ProomColor.accent, shape) else Modifier
                ),
        ) {
            if (logo.isNotBlank()) {
                // 有台标优先显示台标（内边距留一圈，避免贴边）
                RemoteImage(
                    model = logo,
                    contentDescription = name,
                    contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().padding(10.dp),
                )
            } else {
                val mark = channelMark(name)
                Text(
                    text = mark,
                    color = Color.White.copy(alpha = 0.18f),
                    fontSize = markSize(mark),
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    modifier = Modifier.align(Alignment.Center),
                )
            }
            if (badge.isNotBlank()) {
                Text(
                    badge,
                    style = ProomType.label,
                    color = ProomColor.text,
                    maxLines = 1,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(6.dp)
                        .clip(RoundedCornerShape(ProomShape.sm))
                        .background(ProomColor.surface2)
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }
        Text(
            text = name,
            style = ProomType.cardTitle,
            color = ProomColor.text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 7.dp),
        )
        // 行高固定：副标题即使不显示也占位，切换筛选时整页不会上下跳。
        // 高度跟着字阶走 —— 写死 16dp 时电视上的 13sp 副标题会被裁掉半个字。
        val subLine = with(LocalDensity.current) { ProomType.label.lineHeight.toDp() }
        Box(Modifier.height(subLine)) {
            if (subtitle.isNotBlank()) {
                Text(
                    text = subtitle,
                    style = ProomType.label,
                    color = ProomColor.muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 1.dp),
                )
            }
        }
    }
}

/** 按频道名生成一对深色（同色相、不同明度），保证每个台的底色有细微差别 */
private fun tileColors(name: String): List<Color> {
    val h = (name.hashCode() and 0x7FFFFFFF) % 360
    return listOf(
        Color.hsl(h.toFloat(), 0.22f, 0.16f),
        Color.hsl(h.toFloat(), 0.18f, 0.09f),
    )
}

/** CCTV5 / CCTV-5 / CCTV 5+ 取数字，其余取前几个字符 */
internal fun channelMark(name: String): String {
    val n = name.trim()
    if (n.isEmpty()) return "·"
    CCTV_MARK.find(n)?.let { return it.groupValues[1] }

    val first = n[0]
    if (first.code < 128 && first.isLetterOrDigit()) {
        return n.takeWhile { it.code < 128 && (it.isLetterOrDigit() || it == '&' || it == '.') }
            .take(5)
    }
    return n.take(2)
}

/** 字号随字数收档：字母少的可以大，多了必须缩，否则会溢出块外 */
internal fun markSize(mark: String) = when {
    mark.length <= 1 -> 34.sp
    mark.length == 2 -> 28.sp
    mark.length == 3 -> 24.sp
    else -> 19.sp
}

private val CCTV_MARK = Regex("^CCTV[-\\s]?(\\d{1,2}\\+?)$", RegexOption.IGNORE_CASE)
