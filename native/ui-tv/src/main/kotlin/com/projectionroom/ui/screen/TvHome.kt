package com.projectionroom.ui.screen

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.delay
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.unit.dp
import com.projectionroom.shared.model.Vod
import com.projectionroom.ui.component.PosterCard
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.projectionroom.ui.component.NAV_TABS
import com.projectionroom.ui.component.TvHero
import com.projectionroom.ui.component.WideCard
import com.projectionroom.shared.catalog.NasApi
import androidx.compose.ui.unit.Dp
import com.projectionroom.ui.component.TvRow
import com.projectionroom.ui.util.metaLine
import com.projectionroom.ui.theme.ProomColor
import com.projectionroom.ui.theme.ProomSpace
import com.projectionroom.ui.theme.ProomType
import com.projectionroom.ui.theme.TvLayout

/**
 * 电视首页：搜索入口 + 「继续观看」+「最近更新」两条货架。
 *
 * 数据全部来自 NAS 的 `/api/home`，没有硬编码样例。
 */
@Composable
fun TvHome(
    continueWatching: List<Vod> = emptyList(),
    latest: List<Vod> = emptyList(),
    loading: Boolean = false,
    cats: List<NasApi.CatGroup> = emptyList(),
    navIndex: Int = 0,
    onNav: (String, String?) -> Unit = { _, _ -> },
    onMore: (String, String?) -> Unit = { _, _ -> },
    onOpen: (Vod) -> Unit = {},
    /** 「立即播放」：直接进播放器，不经过详情页 */
    onPlay: (Vod) -> Unit = onOpen,
    onSearch: () -> Unit = {},
    onNas: () -> Unit = {},
    onMine: () -> Unit = {},
    /** 顶栏「正在播放」唱片：进全屏播放页。null = 不提供这个入口 */
    onNowPlaying: (() -> Unit)? = null,
    /** 状态栏高度：电视一般没有，个别盒子/模拟器有，顶栏内容要避开 */
    topInset: Dp = 0.dp,
    listState: androidx.compose.foundation.lazy.LazyListState = rememberLazyListState(),
) {
    // 从详情页返回时，整个 TvHome 会重新组合，Compose 不会帮你把焦点找回来 ——
    // 遥控器上表现为「方向键全都没反应」。所以每次进场都主动把焦点打到导航首项。
    val landing = remember { FocusRequester() }
    // 顶栏是浮层（不在 LazyColumn 的焦点树里），几何寻路找不到下面的 Hero 按钮，
    // 所以给两个方向各钉一个锚点：胶囊 ↓ → 立即播放，Hero 按钮 ↑ → 胶囊。
    val heroPlay = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        // 等一帧，LazyColumn 的项还没测量时 requestFocus 会静默失败
        withFrameNanos { }
        runCatching { landing.requestFocus() }
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(ProomColor.bg)) {
        // 卡片宽度同时受宽高约束：宽度管「一排几张」，高度管「标题塞不塞得进屏幕」
        val byWidth = maxWidth * 0.135f
        val byHeight = maxHeight * 0.22f
        val cardW = minOf(byWidth, byHeight).coerceIn(104.dp, 240.dp)
        // 横版卡更宽：一屏 4~5 张比较接近 Web 版
        // 海报源图只有 270x385，裁成 16:9 后横向只剩 270px；卡片别超过 300dp
        val wideCardW = (maxWidth * 0.16f).coerceIn(160.dp, 300.dp)

        // 首页快照是流式拼出来的：各分类陆续到达，每来一批都会重新排序。
        // Hero 直接跟着换就是「几张图快速闪一遍，最后停在第一张」。
        // 保序合并 + 300ms 静默窗口：已有的条目留在原位，新出现的补在后面。
        var heroShown by remember { mutableStateOf(emptyList<Vod>()) }
        LaunchedEffect(latest) {
            if (latest.isEmpty()) return@LaunchedEffect
            delay(300)
            val next = latest.take(6)
            heroShown = if (heroShown.isEmpty()) next else {
                val kept = heroShown.filter { o -> next.any { it.name == o.name } }
                val added = next.filter { n -> heroShown.none { it.name == n.name } }
                (kept + added).take(6)
            }
        }
        val heroItems = heroShown.ifEmpty { latest.take(6) }
        // Hero 已经展示了这些，下面的「最近更新」不再重复（按名字去重，
        // 因为 Hero 的顺序是合并后的，和 latest 前 6 条不一定一致）
        val latestRest = latest.filterNot { v -> heroItems.any { it.name == v.name } }

        androidx.compose.foundation.lazy.LazyColumn(
            Modifier.fillMaxSize(),
            state = listState,
            contentPadding = PaddingValues(bottom = ProomSpace.xxl),
        ) {
            // Hero 顶到屏幕上沿、导航浮在它上面，首屏才是一整块画面。
            // 0.76 会让第一排货架只剩半张卡片露在屏幕外（"下面还有内容"变成了"被切掉了"），
            // 0.68 正好容下 hero + 区块标题 + 一整排卡片。
            item {
                if (latest.isNotEmpty()) {
                    TvHero(
                        items = heroItems,
                        height = maxHeight * 0.68f,
                        topInset = TOP_BAR_HEIGHT + topInset,
                        playFocusRequester = if (latest.isNotEmpty()) heroPlay else null,
                        navFocusRequester = landing,
                        onPlay = onPlay,
                        onDetail = { v -> onOpen(v) },
                    )
                }
            }

            if (loading) {
                item {
                    Text(
                        text = "正在读 NAS…",
                        style = ProomType.label,
                        color = ProomColor.muted,
                        modifier = Modifier.padding(horizontal = TvLayout.insetX, vertical = ProomSpace.sm),
                    )
                }
            }

            if (continueWatching.isNotEmpty()) {
                item {
                    SectionTitle("继续观看", "从上次停下的地方接着看")
                    TvRow(
                        items = continueWatching.take(10),
                        itemWidth = wideCardW,
                        gap = ProomSpace.md,
                        contentPadding = PaddingValues(horizontal = TvLayout.insetX, vertical = ProomSpace.sm),
                    ) { v, focused ->
                        WideCard(
                            vod = v,
                            width = wideCardW,
                            focused = focused,
                            onClick = { onOpen(v) },
                        )
                    }
                    Spacer(Modifier.height(ProomSpace.lg))
                }
            }

            if (latestRest.isNotEmpty()) {
                item {
                    SectionTitle("最近更新", "")
                    TvRow(
                        items = latestRest.take(14),
                        itemWidth = cardW,
                        gap = ProomSpace.md,
                        contentPadding = PaddingValues(horizontal = TvLayout.insetX, vertical = ProomSpace.sm),
                    ) { v, focused ->
                        PosterCard(
                            title = v.name,
                            meta = metaLine(v.year, v.type),
                            poster = v.pic,
                            focused = focused,
                            onClick = { onOpen(v) },
                        )
                    }
                }
            }

            cats.forEach { group ->
                item {
                    CategoryShelf(
                        group = group,
                        cardW = cardW,
                        onOpen = onOpen,
                        onMore = { onMore(group.cat, null) },
                    )
                }
            }

            if (!loading && continueWatching.isEmpty() && latest.isEmpty()) {
                item {
                Box(Modifier.fillMaxWidth().height(300.dp), contentAlignment = Alignment.Center) {
                    Text("NAS 里还没有可展示的内容", style = ProomType.meta, color = ProomColor.muted)
                }
                }
            }
        }

        // 顶栏浮在 Hero 上：不再单独占一条高度，首屏就是一整块画面。
        // 滚动后货架会从它下面穿过 —— 渐变能压住深色海报，压不住金底的「立即播放」，
        // logo 会直接叠在按钮上。所以滚离顶部后就切成实底，滚动位置是唯一判据。
        val scrolled by remember(listState) {
            derivedStateOf {
                listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 8
            }
        }
        TvTopBar(
            navIndex = navIndex,
            landing = landing,
            downTarget = if (latest.isNotEmpty()) heroPlay else null,
            onNav = onNav,
            onSearch = onSearch,
            onNas = onNas,
            onMine = onMine,
            onNowPlaying = onNowPlaying,
            topInset = topInset,
            floating = !scrolled,
        )
    }
}

/** 顶栏高度。Hero 的内容靠它避让，数字本身在 [com.projectionroom.ui.component.TvRootHeaderSpec] */
private val TOP_BAR_HEIGHT =
    com.projectionroom.ui.component.TvRootHeaderSpec.height

@Composable
private fun TvTopBar(
    navIndex: Int,
    landing: FocusRequester,
    downTarget: FocusRequester?,
    onNav: (String, String?) -> Unit,
    onSearch: () -> Unit,
    onNas: () -> Unit,
    onMine: () -> Unit,
    onNowPlaying: (() -> Unit)? = null,
    topInset: Dp = 0.dp,
    floating: Boolean = true,
) {
    // 一级导航只有一份实现（见 TvRootHeader）：首页只是「浮在 Hero 上」的那一种用法，
    // 片库 / 直播 / 音乐用的是同一颗组件的实底版本，横向位置逐像素一致。
    com.projectionroom.ui.component.TvRootHeader(
        nav = com.projectionroom.ui.component.TvRootNav(
            index = navIndex,
            onSelect = onNav,
            onSearch = onSearch,
            onNas = onNas,
            onMine = onMine,
            onNowPlaying = onNowPlaying,
        ),
        topInset = topInset,
        floating = floating,
        landing = landing,
        downTarget = downTarget,
    )
}

@Composable
private fun SectionTitle(title: String, sub: String) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = TvLayout.insetX),
        verticalAlignment = Alignment.Bottom,
    ) {
        Text(title, style = ProomType.title, color = ProomColor.text)
        if (sub.isNotBlank()) {
            Text(
                sub,
                style = ProomType.meta,
                color = ProomColor.muted,
                modifier = Modifier.padding(start = 10.dp, bottom = 2.dp),
            )
        }
    }
}

@Composable
private fun SearchEntry(onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(ProomSpace.md)
    Row(
        Modifier
            .clip(shape)
            .background(ProomColor.surface)
            .then(if (focused) Modifier.border(2.dp, ProomColor.accent, shape) else Modifier)
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("搜索片名、演员…", style = ProomType.body, color = ProomColor.muted)
    }
}


/** 一个分类货架：标题带总数，右侧「查看全部」跳片库 */
@Composable
private fun CategoryShelf(
    group: NasApi.CatGroup,
    cardW: Dp,
    onOpen: (Vod) -> Unit,
    onMore: () -> Unit,
) {
    Column {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = TvLayout.insetX),
            verticalAlignment = Alignment.Bottom,
        ) {
            Text(group.cat, style = ProomType.title, color = ProomColor.text)
            Text(
                "${group.count} 部",
                style = ProomType.meta,
                color = ProomColor.muted,
                modifier = Modifier.padding(start = 10.dp, bottom = 2.dp),
            )
            Spacer(Modifier.weight(1f))
            MoreLink(onMore)
        }
        Spacer(Modifier.height(ProomSpace.sm))
        TvRow(
            items = group.items.take(14),
            itemWidth = cardW,
            gap = ProomSpace.md,
            contentPadding = PaddingValues(horizontal = TvLayout.insetX, vertical = ProomSpace.sm),
        ) { v, focused ->
            PosterCard(
                title = v.name,
                meta = metaLine(v.year, v.type),
                poster = v.pic,
                focused = focused,
                onClick = { onOpen(v) },
            )
        }
        Spacer(Modifier.height(ProomSpace.lg))
    }
}

@Composable
private fun MoreLink(onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(50)
    Box(
        Modifier
            .clip(shape)
            .background(if (focused) ProomColor.surface2 else Color.Transparent)
            .then(if (focused) Modifier.border(1.dp, ProomColor.accent, shape) else Modifier)
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text("查看全部", style = ProomType.label, color = if (focused) ProomColor.accent else ProomColor.muted)
    }
}
