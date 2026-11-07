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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
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
import com.projectionroom.shared.model.Vod
import com.projectionroom.ui.theme.ProomColor
import com.projectionroom.ui.theme.ProomShape
import com.projectionroom.ui.theme.TvIcon
import com.projectionroom.ui.theme.TvSpace
import com.projectionroom.ui.util.metaLine
import com.projectionroom.ui.util.plainText
import com.projectionroom.ui.theme.ProomType
import com.projectionroom.ui.theme.TvLayout
import com.projectionroom.ui.component.RemoteImage

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

    // 自动轮播：遥控器焦点停在 Hero 的按钮上时不动 ——
    // 否则用户正要去按「立即播放」，底下的片子会被换掉。
    var cur by remember { mutableIntStateOf(index.coerceAtLeast(0)) }
    var focused by remember { mutableStateOf(false) }
    LaunchedEffect(items.size) {
        if (items.size < 2) return@LaunchedEffect
        while (true) {
            delay(7_000)
            if (!focused) cur = (cur + 1) % items.size
        }
    }
    val vod = items[cur.coerceIn(0, items.lastIndex)]

    Box(Modifier.fillMaxWidth().height(height)) {
        // ── 背景三层 ──
        AnimatedContent(
            targetState = vod.pic,
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            label = "hero-bg",
        ) { pic ->
            Box(Modifier.fillMaxSize()) {
                if (pic.isNotBlank()) {
                    // 源图 270px 铺满全屏要放大 7 倍。blur 在 API<31 是空操作，
                    // 靠它只会得到马赛克；SoftBackdrop 用小尺寸解码 + GPU 放大。
                    // 12dp（约 24px 宽）：只剩色温，五官和构图全部化掉 —— 背景是氛围，
                    // 不该让人去认海报。
                    com.projectionroom.ui.component.SoftBackdrop(
                        model = pic,
                        modifier = Modifier.fillMaxSize(),
                        decodeWidth = TvIcon.toolbar,
                        brightness = 0.62f,
                    )
                }
                // 0.52 太重，背景最后只剩一块黑；左侧文字的可读性由下面那道
                // 水平渐变保证，这里只需要压掉一点亮度
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.26f)))
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
            Modifier.fillMaxSize().padding(start = TvLayout.insetX, end = TvLayout.insetX, top = topInset),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    // 文字和按钮作为一个整体垂直居中；圆点指示器要占走底部一条，
                    // 所以底部留白比顶部多，视觉重心才不会被圆点拽下去。
                    // 首页 Hero 从 0.68 收到 0.65（给底部卡片标题让安全区）之后，
                    // s24 只剩 4dp 让位，圆点会贴上按钮下沿 —— 各让一步：
                    // 内容再往上抬 8dp，圆点再往下沉 8dp。
                    .padding(bottom = if (items.size > 1) TvSpace.s32 else 0.dp),
                verticalArrangement = Arrangement.Center,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.width(TvSpace.s8).height(TvSpace.s8).clip(CircleShape).background(ProomColor.accent))
                    Text(
                        text = "私人放映 · 今日推荐",
                        style = ProomType.eyebrow,
                        color = ProomColor.accent,
                        modifier = Modifier.padding(start = TvSpace.s8),
                    )
                }
                Spacer(Modifier.height(TvSpace.s8))
                Text(
                    text = vod.name,
                    color = ProomColor.text,
                    fontSize = heroTitleSize(height),
                    fontWeight = FontWeight.SemiBold,
                    lineHeight = heroTitleSize(height) * 1.05f,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(TvSpace.s4))
                Text(
                    text = metaLine(vod.score, vod.year, vod.area, vod.type, sep = "   "),
                    style = ProomType.meta,
                    color = ProomColor.dim,
                )
                if (vod.desc.plainText().isNotBlank()) {
                    Spacer(Modifier.height(TvSpace.s8))
                    Text(
                        vod.desc.plainText(),
                        style = ProomType.body,
                        color = ProomColor.dim,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        // 正文一行 30 个汉字就到阅读极限了，再宽眼睛要来回扫
                        modifier = Modifier.fillMaxWidth(0.66f),
                    )
                }
                Spacer(Modifier.height(TvSpace.s12))
                Row(horizontalArrangement = Arrangement.spacedBy(TvSpace.s12)) {
                    HeroButton(
                        "立即播放",
                        primary = true,
                        focusRequester = playFocusRequester,
                        upTarget = navFocusRequester,
                        onFocus = { focused = it },
                    ) { onPlay(vod) }
                    HeroButton(
                        "详情",
                        upTarget = navFocusRequester,
                        onFocus = { focused = it },
                    ) { onDetail(vod) }
                }
            }

            Spacer(Modifier.width(TvSpace.s32))

            // 右侧竖版海报：带一点偏移，别和文字挤在同一基线
            Box(
                Modifier
                    .width(heroPosterWidth(height))
                    .aspectRatio(2f / 3.05f)
                    .clip(RoundedCornerShape(ProomShape.md))
                    .background(ProomColor.card)
            ) {
                if (vod.pic.isNotBlank()) {
                    // 自动轮播时海报不能硬切：和背景一样淡入淡出
                    AnimatedContent(
                        targetState = vod.pic,
                        transitionSpec = { fadeIn() togetherWith fadeOut() },
                        label = "hero-poster",
                    ) { pic ->
                        RemoteImage(
                            model = pic,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
        }

        // ── 圆点指示器 ──
        if (items.size > 1) {
            Row(
                Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = TvLayout.insetX, bottom = TvSpace.s16),
                horizontalArrangement = Arrangement.spacedBy(TvSpace.s8),
            ) {
                repeat(items.size.coerceAtMost(8)) { i ->
                    val on = i == cur
                    Box(
                        Modifier
                            // 3dp 的细条在三米外看不见，等于没有页码
                            .height(ProomShape.xs)
                            .width(if (on) 32.dp else 16.dp)
                            .clip(RoundedCornerShape(50))
                            .background(if (on) ProomColor.accent else Color.White.copy(alpha = 0.24f))
                    )
                }
            }
        }
    }
}

private fun heroTitleSize(height: Dp): TextUnit = (height.value * 0.095f).coerceIn(30f, 64f).sp

/**
 * 竖版海报宽度。0.30 倍高时海报只有 110~125dp 宽，和 400dp 高的 Hero 不成比例；
 * 0.40 倍高能到 145~165dp，既撑得住右侧画面，也不至于把文字挤成窄条。
 */
private fun heroPosterWidth(height: Dp): Dp = (height.value * 0.40f).dp

@Composable
private fun HeroButton(
    label: String,
    primary: Boolean = false,
    focusRequester: FocusRequester? = null,
    upTarget: FocusRequester? = null,
    /** 焦点进出时通知上层：自动轮播要据此暂停，不能把用户要按的那张换掉 */
    onFocus: (Boolean) -> Unit = {},
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(ProomShape.md)
    Box(
        Modifier
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .then(
                if (upTarget != null) {
                    Modifier.focusProperties { up = upTarget }
                } else Modifier
            )
            // 主按钮底色恒定金：聚焦不再翻白 —— 翻白之后白环贴在自己的白底上就没了，
            // 而且「金=主操作、白=焦点」两套颜色同屏打架。焦点只由白环表达
            .tvFocusRing(shape, ringWidth = 2.dp, zoom = 1f)
            .clip(shape)
            .background(
                when {
                    primary -> ProomColor.accent
                    focused -> Color.White.copy(alpha = 0.24f)
                    else -> Color.White.copy(alpha = 0.12f)
                }
            )
            .onFocusChanged {
                focused = it.isFocused
                onFocus(it.isFocused)
            }
            .clickable(onClick = onClick)
            .padding(horizontal = TvSpace.s24, vertical = TvSpace.s12),
    ) {
        Text(
            label,
            style = ProomType.body,
            color = if (primary) Color(0xFF1B1206) else ProomColor.text,
        )
    }
}
