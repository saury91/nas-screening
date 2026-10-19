package com.projectionroom.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.projectionroom.shared.catalog.NasApi
import com.projectionroom.shared.model.Vod
import com.projectionroom.ui.component.PHONE_CATS
import com.projectionroom.ui.component.PhoneHero
import com.projectionroom.ui.component.PosterCard
import com.projectionroom.ui.component.WideCard
import com.projectionroom.ui.theme.ProomColor
import com.projectionroom.ui.theme.ProomSpace
import com.projectionroom.ui.theme.ProomType

/**
 * 手机首页。
 *
 * 版式按手机的逻辑重排，不是把电视首页缩一缩：
 * 固定顶栏（logo + 搜索）→ 分类胶囊 → Hero（手指左右滑）→ 继续观看横滑 →
 * 热门三列网格 → 各分类横滑货架。
 *
 * 顶栏固定在滚动区外面：手机上「搜索」是最常用的入口，不该滑两屏才够得着。
 * 网格只用在「能铺满一排」的地方，货架一律横滑 —— 竖屏下横滑比网格更好翻。
 */
@Composable
fun PhoneHome(
    continueWatching: List<Vod>,
    hot: List<Vod>,
    cats: List<NasApi.CatGroup> = emptyList(),
    onOpen: (Vod) -> Unit,
    onSearch: () -> Unit,
    onNav: (String?) -> Unit = {},
    onMore: (String) -> Unit = {},
) {
    Column(Modifier.fillMaxSize().background(ProomColor.bg)) {
        PhoneTopBar(onSearch)
        BoxWithConstraints(Modifier.fillMaxSize()) {
            // 横滑卡宽：一屏露 2.4 张，露半张才「提示」还能往右滑
            val wideW = (maxWidth * 0.62f).coerceIn(180.dp, 300.dp)
            val railW = (maxWidth * 0.28f).coerceIn(96.dp, 150.dp)

            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 28.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(ProomSpace.lg),
            ) {
                item(span = { GridItemSpan(maxLineSpan) }) { CategoryChips(onNav) }

                if (hot.isNotEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        PhoneHero(
                            items = hot.take(6),
                            onPlay = { v -> onOpen(v) },
                            onDetail = { v -> onOpen(v) },
                        )
                    }
                }

                if (continueWatching.isNotEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Column {
                            SectionTitle("继续观看", "接着看")
                            LazyRow(
                                contentPadding = PaddingValues(horizontal = 20.dp),
                                horizontalArrangement = Arrangement.spacedBy(ProomSpace.sm),
                            ) {
                                items(continueWatching.take(12)) { v ->
                                    WideCard(vod = v, width = wideW, onClick = { onOpen(v) })
                                }
                            }
                        }
                    }
                }

                if (hot.isNotEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) { SectionTitle("热门推荐", "") }
                    items(hot.take(18)) { v ->
                        PosterCard(
                            title = v.name,
                            meta = listOf(v.year, v.type).filter { it.isNotBlank() }.joinToString(" · "),
                            poster = v.pic,
                            onClick = { onOpen(v) },
                        )
                    }
                }

                cats.forEach { group ->
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        CatRail(
                            group = group,
                            cardW = railW,
                            onOpen = onOpen,
                            onMore = { onMore(group.cat) },
                        )
                    }
                }
            }
        }
    }
}

/** 固定顶栏。手机上不放英文副标 —— 那一行在小屏上纯属噪声。 */
@Composable
private fun PhoneTopBar(onSearch: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 14.dp, top = 12.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("放映室", color = ProomColor.text, fontSize = 20.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
        Spacer(Modifier.weight(1f))
        Box(
            Modifier
                .clip(RoundedCornerShape(50))
                .background(ProomColor.surface)
                .clickable(onClick = onSearch)
                .padding(horizontal = 14.dp, vertical = 8.dp),
        ) {
            Text("搜索", style = ProomType.meta, color = ProomColor.dim, maxLines = 1, softWrap = false)
        }
    }
}

/** 分类胶囊：横向可滑。首页/直播/音乐不在这里——它们是底部 tab。 */
@Composable
private fun CategoryChips(onNav: (String?) -> Unit) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(PHONE_CATS) { tab ->
            val (label, slug) = tab
            val selected = false
            Box(
                Modifier
                    .clip(RoundedCornerShape(50))
                    .background(if (selected) ProomColor.accent else ProomColor.surface)
                    .clickable { onNav(slug) }
                    .padding(horizontal = 14.dp, vertical = 7.dp),
            ) {
                Text(
                    label,
                    style = ProomType.meta,
                    color = if (selected) Color(0xFF1B1206) else ProomColor.dim,
                    maxLines = 1,
                    softWrap = false,
                )
            }
        }
    }
}

@Composable
private fun SectionTitle(title: String, sub: String) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = ProomSpace.sm),
        verticalAlignment = Alignment.Bottom,
    ) {
        Text(title, style = ProomType.title, color = ProomColor.text)
        if (sub.isNotBlank()) {
            Text(
                sub,
                style = ProomType.label,
                color = ProomColor.muted,
                modifier = Modifier.padding(start = 8.dp, bottom = 3.dp),
            )
        }
    }
}

@Composable
private fun CatRail(
    group: NasApi.CatGroup,
    cardW: Dp,
    onOpen: (Vod) -> Unit,
    onMore: () -> Unit,
) {
    Column {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            Text(group.cat, style = ProomType.title, color = ProomColor.text)
            Text(
                "${group.count} 部",
                style = ProomType.meta,
                color = ProomColor.muted,
                modifier = Modifier.padding(start = 8.dp, bottom = 3.dp),
            )
            Spacer(Modifier.weight(1f))
            Box(
                Modifier
                    .clip(RoundedCornerShape(50))
                    .clickable(onClick = onMore)
                    .padding(horizontal = 8.dp, vertical = 6.dp),
            ) {
                Text("全部 ›", style = ProomType.label, color = ProomColor.muted, maxLines = 1, softWrap = false)
            }
        }
        Spacer(Modifier.height(ProomSpace.sm))
        LazyRow(
            contentPadding = PaddingValues(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(ProomSpace.sm),
        ) {
            items(group.items.take(12)) { v ->
                PosterCard(
                    title = v.name,
                    meta = listOf(v.year, v.type).filter { it.isNotBlank() }.joinToString(" · "),
                    poster = v.pic,
                    modifier = Modifier.width(cardW),
                    onClick = { onOpen(v) },
                )
            }
        }
    }
}

/** 「我的」：收藏与记录还没接，先给一个诚实占位，不放假数据。 */
@Composable
fun PhoneMine() {
    Box(Modifier.fillMaxSize().background(ProomColor.bg), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("收藏与播放记录", style = ProomType.title, color = ProomColor.text)
            Spacer(Modifier.height(8.dp))
            Text("这块还没接数据，下一步补", style = ProomType.meta, color = ProomColor.muted)
        }
    }
}
