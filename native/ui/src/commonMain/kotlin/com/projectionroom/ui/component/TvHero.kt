package com.projectionroom.ui.component

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.projectionroom.shared.model.Vod
import com.projectionroom.ui.theme.ProomColor
import com.projectionroom.ui.theme.ProomShape
import com.projectionroom.ui.theme.ProomSpace
import com.projectionroom.ui.theme.ProomType

/**
 * 首页 Hero：模糊海报打底 + 超大标题 + 双按钮 + 右侧竖版海报。
 *
 * 这是整个 App 的门面，照 Web 版的三层遮罩做：
 *  1. 背景海报放大 + blur + 压暗（`Modifier.blur` 在 API<31 上是 no-op，
 *     所以额外叠了一层高透明黑，低版本也不会糊成一片亮图）；
 *  2. 水平渐变把左半边压暗，保证白字可读；
 *  3. 底部渐变过渡到页面背景色，和下面的货架无缝衔接。
 */
@Composable
fun TvHero(
    items: List<Vod>,
    index: Int = 0,
    height: Dp,
    topInset: Dp = 0.dp,
    playFocusRequester: FocusRequester? = null,
    navFocusRequester: FocusRequester? = null,
    onPlay: (Vod) -> Unit,
    onDetail: (Vod) -> Unit,
) {
    if (items.isEmpty()) return
    val vod = items[index.coerceIn(0, items.lastIndex)]

    Box(Modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(0.dp))) {
        // ── 背景三层 ──
        AnimatedContent(
            targetState = vod.pic,
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            label = "hero-bg",
        ) { pic ->
            Box(Modifier.fillMaxSize()) {
                if (pic.isNotBlank()) {
                    AsyncImage(
                        model = pic,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        // 源图 270px 铺满全屏要放大 7 倍，blur 不重就会看到马赛克
                        modifier = Modifier.fillMaxSize().blur(48.dp),
                    )
                }
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.52f)))
            }
        }
        // 左暗右亮：保证左侧文字始终压在深色上
        Box(
            Modifier.fillMaxSize().background(
                Brush.horizontalGradient(
                    listOf(Color.Black.copy(alpha = 0.92f), Color.Black.copy(alpha = 0.55f), Color.Transparent)
                )
            )
        )
        // 底部渐隐到页面底色
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    0f to Color.Black.copy(alpha = 0.35f),
                    0.45f to Color.Transparent,
                    1f to ProomColor.bg,
                )
            )
        )

        // ── 内容 ──
        // topInset：顶部导航是浮在 Hero 上的（不单独吃一条高度），内容要避开它
        Row(
            Modifier.fillMaxSize().padding(start = ProomSpace.xxl, end = ProomSpace.xxl, top = topInset),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    // 文字和按钮作为一个整体垂直居中；圆点指示器要占走底部一条，
                    // 所以底部留白比顶部多，视觉重心才不会被圆点拽下去
                    .padding(bottom = if (items.size > 1) 30.dp else 0.dp),
                verticalArrangement = Arrangement.Center,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.width(6.dp).height(6.dp).clip(CircleShape).background(ProomColor.accent))
                    Text(
                        text = "私人放映 · 今日推荐",
                        style = ProomType.eyebrow,
                        color = ProomColor.accent,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    text = vod.name,
                    color = ProomColor.text,
                    fontSize = heroTitleSize(height),
                    fontWeight = FontWeight.SemiBold,
                    lineHeight = heroTitleSize(height) * 1.05f,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = listOf(vod.score, vod.year, vod.area, vod.type)
                        .filter { it.isNotBlank() }.joinToString("   "),
                    style = ProomType.meta,
                    color = ProomColor.dim,
                )
                if (vod.desc.isNotBlank()) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        vod.desc,
                        style = ProomType.body,
                        color = ProomColor.dim,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth(0.72f),
                    )
                }
                Spacer(Modifier.height(20.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    HeroButton(
                        "立即播放",
                        primary = true,
                        focusRequester = playFocusRequester,
                        upTarget = navFocusRequester,
                    ) { onPlay(vod) }
                    HeroButton("详情", upTarget = navFocusRequester) { onDetail(vod) }
                }
            }

            Spacer(Modifier.width(ProomSpace.xxl))

            // 右侧竖版海报：带一点偏移，别和文字挤在同一基线
            Box(
                Modifier
                    .width(heroPosterWidth(height))
                    .aspectRatio(2f / 3.05f)
                    .clip(RoundedCornerShape(ProomShape.md))
                    .background(ProomColor.card)
            ) {
                if (vod.pic.isNotBlank()) {
                    AsyncImage(
                        model = vod.pic,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }

        // ── 圆点指示器 ──
        if (items.size > 1) {
            Row(
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 18.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                repeat(items.size.coerceAtMost(8)) { i ->
                    val on = i == index
                    Box(
                        Modifier
                            .height(3.dp)
                            .width(if (on) 26.dp else 14.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(if (on) ProomColor.accent else Color.White.copy(alpha = 0.24f))
                    )
                }
            }
        }
    }
}

private fun heroTitleSize(height: Dp): TextUnit = (height.value * 0.085f).coerceIn(28f, 64f).sp
private fun heroPosterWidth(height: Dp): Dp = (height.value * 0.30f).dp

@Composable
private fun HeroButton(
    label: String,
    primary: Boolean = false,
    focusRequester: FocusRequester? = null,
    upTarget: FocusRequester? = null,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(ProomSpace.md)
    Box(
        Modifier
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .then(
                if (upTarget != null) {
                    Modifier.focusProperties { up = upTarget }
                } else Modifier
            )
            .clip(shape)
            .background(if (primary) ProomColor.accent else Color.White.copy(alpha = 0.12f))
            .then(
                if (focused) {
                    Modifier.border(2.dp, if (primary) Color.White else ProomColor.accent, shape)
                } else Modifier
            )
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick)
            .padding(horizontal = 26.dp, vertical = 13.dp),
    ) {
        Text(
            label,
            style = ProomType.body,
            color = if (primary) Color(0xFF1B1206) else ProomColor.text,
        )
    }
}
