package com.projectionroom.ui.component

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.projectionroom.ui.theme.ProomColor
import com.projectionroom.ui.theme.TvRatio
import com.projectionroom.ui.theme.TvSpace
import com.projectionroom.ui.theme.ProomMotion
import com.projectionroom.ui.theme.ProomShape
import com.projectionroom.ui.theme.ProomType
import com.projectionroom.ui.theme.TvType
import com.projectionroom.ui.theme.textBlockHeight
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
        targetValue = if (focused) ProomMotion.focusScaleTv else 1f,
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
                .aspectRatio(TvRatio.wide)
                // 频道格是内容卡，没有自身边框，焦点走白环
                .then(
                    Modifier.tvFocusBloom(
                        shape,
                        bloom = if (focused) 1f else 0f,
                        ringWidth = 2.dp,
                    )
                )
                .clip(shape)
                // 没有台标时用**中性底色**打底。
                //
                // 曾经按频道名 hash 出一对彩色渐变（hsl 随机色相）：设计意图是「一屏
                // 别全是一片死黑」，实际是一个 5 列的直播页出现 20 种色相 —— 绿/红/棕/紫
                // 的方块排在一起，既不像台标也不像占位，更像一半封面加载失败。
                // 台标缺失是常态，占位就该安静：surface2 → card 的明度差已经够把格子从
                // 背景里分出来（规范 §9：分层靠明度，不靠色相）。
                .background(
                    Brush.linearGradient(listOf(ProomColor.surface2, ProomColor.card))
                ),
        ) {
            if (logo.isNotBlank()) {
                // 有台标优先显示台标（内边距留一圈，避免贴边）
                RemoteImage(
                    model = logo,
                    contentDescription = name,
                    contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().padding(TvSpace.s12),
                )
            } else {
                val mark = channelMark(name)
                BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    // 水印字号随格子高度走（规范 §3 唯一的展示型例外），但必须钳在字阶
                    // 区间里：之前是 32/28/24/20 四档裸 sp，「CCTV16」的 16 只有 20sp，
                    // 在 92dp 高的格子里比下方的卡片标题还小，等于没有台标。
                    val ratio = when {
                        mark.length <= 1 -> 0.46f
                        mark.length == 2 -> 0.36f
                        mark.length == 3 -> 0.28f
                        else -> 0.22f
                    }
                    val density = LocalDensity.current
                    val minSp = TvType.title.fontSize.value
                    val maxSp = TvType.display.fontSize.value
                    val markSp = with(density) { (maxHeight * ratio).toSp().value }
                        .coerceIn(minSp, maxSp)
                    Text(
                        text = mark,
                        color = ProomColor.text.copy(alpha = 0.16f),
                        fontSize = markSp.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                    )
                }
            }
            if (badge.isNotBlank()) {
                Text(
                    badge,
                    style = ProomType.label,
                    color = ProomColor.text,
                    maxLines = 1,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(TvSpace.s8)
                        .clip(RoundedCornerShape(ProomShape.sm))
                        .background(ProomColor.surface2)
                        .padding(horizontal = TvSpace.s8, vertical = TvSpace.s4),
                )
            }
        }
        Text(
            text = name,
            style = ProomType.cardTitle,
            color = ProomColor.text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = TvSpace.s8),
        )
        // 行高固定：副标题即使不显示也占位，切换筛选时整页不会上下跳。
        // 高度 = 一行文字 + 上间距，**间距要在高度里让出来** ——
        // 之前 Box 只有 18dp 行高、里面再 padding(top=4dp)，实际只剩 14dp，
        // 13sp 的副标题被压掉小半个字。
        val subLine = textBlockHeight(ProomType.label, 1)
        Box(Modifier.height(subLine + TvSpace.s4)) {
            if (subtitle.isNotBlank()) {
                Text(
                    text = subtitle,
                    style = ProomType.label,
                    color = ProomColor.muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = TvSpace.s4),
                )
            }
        }
    }
}

/** CCTV5 / CCTV-5 / CCTV 5+ 取数字，其余取前几个字符 */
internal fun channelMark(name: String): String {
    val n = name.trim()
    if (n.isEmpty()) return "·"
    CCTV_MARK.find(n)?.let { return it.groupValues[1] }
    // 「频道 3」是给裸数字台名补的展示名，台标块要的仍是那个 3
    FALLBACK_MARK.find(n)?.let { return it.groupValues[1] }

    val first = n[0]
    if (first.code < 128 && first.isLetterOrDigit()) {
        return n.takeWhile { it.code < 128 && (it.isLetterOrDigit() || it == '&' || it == '.') }
            .take(5)
    }
    return n.take(2)
}



// 「CCTV-16 HD (1080p)」这种后面还挂着清晰度/画质的写法也要取到 16：
// 原来的正则要求整串以数字结尾，匹配不到就退化成 takeWhile，20 个格子全写 "CCTV"
private val CCTV_MARK = Regex("^CCTV[-\\s]?(\\d{1,2}\\+?)(?=$|[\\s(（\\-])", RegexOption.IGNORE_CASE)

/** 直播页给裸数字台名补的展示名：「频道 3」 */
private val FALLBACK_MARK = Regex("^频道\\s*(\\d+)$")
