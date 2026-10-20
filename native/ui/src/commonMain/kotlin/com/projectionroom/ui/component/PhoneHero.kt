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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.projectionroom.shared.model.Vod
import com.projectionroom.ui.theme.ProomColor
import com.projectionroom.ui.theme.ProomShape
import com.projectionroom.ui.theme.ProomType

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
        // 竖屏 Hero 取「宽度 × 1.02」，再用屏幕高度兜一次底：
        // 小屏（iPhone SE 那档，~667dp 高）也不能让 Hero 一口吃掉整屏
        // 高度按「内容 + 顶栏」算，不再按屏幕宽度乘系数 ——
        // 之前 1.02×宽 会在 tab 与海报之间留出一大条空白
        // 用 minOf 而不是 coerceIn(min, max)：横屏或极小的可用高度下
        // maxHeight*0.62 会小于 300+topInset，coerceIn 收到 min > max 会直接抛异常。
        val h = minOf(300f + topInset.value, maxHeight.value * 0.62f).dp

        val state = rememberPagerState(pageCount = { items.size })

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
                        // Web 版的做法是：背景不当照片看，当**氛围光** —— 重度模糊 + 提饱和 + 压暗，
                        // 只留颜色情绪；真正的"照片"是下面那张清晰的小海报。
                        AsyncImage(
                            model = vod.pic,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .fillMaxSize()
                                .blur(38.dp)
                                .background(Color.Black.copy(alpha = 0.34f)),
                        )
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
                                0f to Color.Black.copy(alpha = 0.55f),
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
                                AsyncImage(
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
                        if (vod.desc.isNotBlank()) {
                            Spacer(Modifier.height(10.dp))
                            Text(
                                vod.desc,
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
