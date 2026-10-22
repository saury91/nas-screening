package com.projectionroom.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.projectionroom.shared.model.Vod
import com.projectionroom.ui.theme.ProomColor
import com.projectionroom.ui.theme.ProomShape
import com.projectionroom.ui.theme.ProomType
import com.projectionroom.ui.util.plainText
import com.projectionroom.ui.component.RemoteImage

/**
 * 手机首页 Hero。
 *
 * 和电视版是两套东西，不是同一个组件缩一缩：
 * - 电视是横屏 16:9，左边文字右边海报，靠遥控器左右切；
 * - 手机是竖屏，铺满宽度、内容压在底部，靠**手指左右滑**切 ——
 *   所以这里用 HorizontalPager，滑到一半就能看到下一张，跟手感来自「跟手」而不是动画。
 */
@Composable
fun PhoneHero(
    items: List<Vod>,
    onPlay: (Vod) -> Unit,
    onDetail: (Vod) -> Unit,
    /** 浮动顶栏高度：Hero 内容整体让开它，否则会和 tab 行撞在一起 */
    topInset: Dp = 0.dp,
    modifier: Modifier = Modifier,
) {
    if (items.isEmpty()) return

    BoxWithConstraints(modifier.fillMaxWidth()) {
        val state = rememberPagerState(pageCount = { items.size })

        // 自动轮播：手在滑的时候这一拍跳过（不打断拖动），滑完重新计时
        LaunchedEffect(state.pageCount) {
            if (state.pageCount < 2) return@LaunchedEffect
            while (true) {
                delay(6_000)
                if (state.isScrollInProgress) continue
                state.animateScrollToPage((state.currentPage + 1) % state.pageCount)
            }
        }

        // 高度按**当前页内容**给，不再是一个固定值。
        // 内容压在底部对齐，所以固定高度在「没有简介」的那几页会空出一大条 ——
        // 简介占两行（约 58dp），没简介就该把这段收回去。
        // 用 animateDpAsState 而不是直接换高度：翻页是手滑结束才换的，
        // 硬跳会让整页内容上下弹一下。
        val hasDesc = items.getOrNull(state.currentPage)?.desc?.isNotBlank() == true
        val target = minOf((if (hasDesc) 300f else 248f) + topInset.value,
                           maxHeight.value * 0.62f).dp
        val h by androidx.compose.animation.core.animateDpAsState(
            targetValue = target,
            animationSpec = androidx.compose.animation.core.tween(220),
            label = "heroHeight",
        )

        Box(Modifier.fillMaxWidth().height(h)) {
            HorizontalPager(
                state = state,
                beyondViewportPageCount = 1,
                modifier = Modifier.fillMaxSize(),
            ) { page ->
                val vod = items[page]
                Box(Modifier.fillMaxSize()) {
                    if (vod.pic.isNotBlank()) {
                        // 源图只有 270×385，铺满 1080 宽要放大 4 倍。
                        // 背景不当照片看，当**氛围光**：只留颜色情绪，
                        // 真正的"照片"是下面那张清晰的小海报。
                        // 用 SoftBackdrop 而不是 Modifier.blur —— 后者在 API<31 是空操作，
                        // 手机上会变成「270px 海报拉大 4 倍」的脏糊图。
                        SoftBackdrop(
                            model = vod.pic,
                            modifier = Modifier.fillMaxSize(),
                            decodeWidth = 22.dp,
                            brightness = 0.62f,
                        )
                        // 不再额外盖一层 34% 黑：SoftBackdrop 的 brightness 已经把图压暗，
                        // 再叠一层只会把颜色压成灰，毛玻璃的色相就没了
                    }
                    // 左下角再压一层径向暗角，文字压在光上才立得住（对应 Web 的 hero::before）
                    Box(
                        Modifier.fillMaxSize().background(
                            Brush.radialGradient(
                                colors = listOf(ProomColor.bg.copy(alpha = 0.86f), Color.Transparent),
                                center = androidx.compose.ui.geometry.Offset(0.12f, 0.92f),
                                radius = 1400f,
                            )
                        )
                    )
                    // 顶部压暗：让状态栏/时间在图片上也读得清
                    Box(
                        Modifier.fillMaxSize().background(
                            Brush.verticalGradient(
                                0f to Color.Black.copy(alpha = 0.58f),
                                0.30f to Color.Transparent,
                            )
                        )
                    )
                    // 底部渐隐到页面底色
                    Box(
                        Modifier.fillMaxSize().background(
                            Brush.verticalGradient(
                                0.45f to Color.Transparent,
                                1f to ProomColor.bg,
                            )
                        )
                    )

                    Column(
                        Modifier
                            .align(Alignment.BottomStart)
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp)
                            .padding(top = topInset, bottom = 34.dp),
                    ) {
                        Row(verticalAlignment = Alignment.Bottom) {
                            // 清晰竖版海报：这才是视觉锚点，背景只是它的颜色延伸
                            if (vod.pic.isNotBlank()) {
                                RemoteImage(
                                    model = vod.pic,
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier
                                        .width(96.dp)
                                        .height(144.dp)
                                        .clip(RoundedCornerShape(14.dp))
                                        .background(ProomColor.card),
                                )
                                Spacer(Modifier.width(14.dp))
                            }
                            Column(Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(
                                        Modifier.width(5.dp).height(5.dp)
                                            .clip(CircleShape).background(ProomColor.accent)
                                    )
                                    Text(
                                        "今日推荐",
                                        style = ProomType.eyebrow,
                                        color = ProomColor.accent,
                                        modifier = Modifier.padding(start = 7.dp),
                                    )
                                }
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    vod.name,
                                    color = ProomColor.text,
                                    fontSize = 23.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    lineHeight = 28.sp,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Spacer(Modifier.height(5.dp))
                                Text(
                                    listOf(vod.score, vod.year, vod.area, vod.type)
                                        .filter { it.isNotBlank() }.joinToString(" · "),
                                    style = ProomType.meta,
                                    color = ProomColor.dim,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        // 源站简介常是二次转义（"&amp;#039;"），不洗会原样露出来：
                        // 第一轮只把 &amp; 解成 &，剩下的 &#039; 得再解一次。
                        // 用 remember 是因为 pager 翻页会反复重组，没必要每次重洗几百字。
                        val desc = remember(vod.desc) { vod.desc.plainText() }
                        if (desc.isNotBlank()) {
                            Spacer(Modifier.height(10.dp))
                            Text(
                                desc,
                                style = ProomType.body,
                                color = ProomColor.dim,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Spacer(Modifier.height(14.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            PhoneHeroButton(
                                "立即播放",
                                primary = true,
                                modifier = Modifier.weight(1f),
                                onClick = { onPlay(vod) },
                            )
                            PhoneHeroButton("详情", modifier = Modifier.width(96.dp)) { onDetail(vod) }
                        }
                    }
                }
            }

            // 页码点：跟随手势实时走，不做动画插值
            if (items.size > 1) {
                Row(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    repeat(items.size.coerceAtMost(8)) { i ->
                        val on = i == state.currentPage
                        Box(
                            Modifier
                                .height(3.dp)
                                .width(if (on) 18.dp else 6.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(if (on) ProomColor.accent else Color.White.copy(alpha = 0.26f))
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PhoneHeroButton(
    label: String,
    primary: Boolean = false,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier
            .height(44.dp)
            .clip(RoundedCornerShape(ProomShape.sm))
            .background(if (primary) ProomColor.accent else ProomColor.surface2)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = ProomType.body,
            fontWeight = FontWeight.SemiBold,
            color = if (primary) Color(0xFF1B1206) else ProomColor.text,
        )
    }
}
