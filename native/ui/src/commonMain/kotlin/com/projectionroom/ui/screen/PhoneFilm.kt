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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.projectionroom.shared.catalog.NasApi
import com.projectionroom.shared.model.Vod
import com.projectionroom.ui.component.PhoneHero
import com.projectionroom.ui.component.PosterCard
import com.projectionroom.ui.component.PosterSkeleton
import com.projectionroom.ui.component.SkeletonBlock
import com.projectionroom.ui.component.WideSkeleton
import com.projectionroom.ui.component.TabIcons
import com.projectionroom.ui.component.WideCard
import com.projectionroom.ui.theme.ProomColor
import com.projectionroom.ui.theme.ProomShape
import com.projectionroom.ui.theme.ProomSpace
import com.projectionroom.ui.theme.ProomType
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 「影视」页：一个页面 + 六个二级 tab。
 *
 * 之前点「电影」会整页跳到一个独立的 BrowseScreen（自带大标题和筛选条），
 * 视觉语言和首页完全两套。现在分类只是本页的一个 tab：
 * 同一个顶栏、同一条 tab、同一套海报网格，只有内容换掉。
 */

private val FILM_TABS = listOf(
    "首页" to "",
    "电影" to "movie",
    "剧集" to "series",
    "动漫" to "anime",
    "综艺" to "variety",
    "纪录片" to "doc",
)

private val TopBarH = 56.dp
private val TabBarH = 42.dp

@Composable
fun PhoneFilm(
    api: NasApi,
    feed: NasApi.HomeFeed?,
    loading: Boolean,
    onOpen: (Vod) -> Unit,
    onSearch: () -> Unit,
    /** 「立即播放」：直接进播放器（壳层会先把线路补全），别再绕一遍详情页 */
    onPlay: (Vod) -> Unit = onOpen,
    /** 状态栏高度：顶栏画在它下面，Hero 背景通到屏幕最顶 */
    statusInset: Dp = 0.dp,
    /** 下拉刷新：首页重拉 feed；分类 tab 由内部重拉第一页 */
    onRefresh: () -> Unit = {},
) {
    var tab by remember { mutableIntStateOf(0) }
    var refreshing by remember { mutableStateOf(false) }
    var refreshTick by remember { mutableIntStateOf(0) }
    var homeScrolled by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun pull() {
        if (refreshing) return
        refreshing = true
        // 下拉 = 明确要最新数据，让缓存层这次跳过本地
        com.projectionroom.shared.net.HttpCache.forceNext = true
        refreshTick += 1
        onRefresh()
        scope.launch {
            delay(800)
            refreshing = false
        }
    }

    val solid = tab != 0 || homeScrolled

    Box(Modifier.fillMaxSize().background(ProomColor.bg)) {
        if (tab == 0) {
            HomePane(
                feed = feed,
                loading = loading,
                refreshing = refreshing,
                onRefresh = ::pull,
                onOpen = onOpen,
                onPlay = onPlay,
                onMore = { cat ->
                    val i = FILM_TABS.indexOfFirst { it.second == cat }
                    if (i > 0) tab = i
                },
                statusInset = statusInset,
                onScrolled = { homeScrolled = it },
            )
        } else {
            CategoryPane(
                api = api,
                slug = FILM_TABS[tab].second,
                name = FILM_TABS[tab].first,
                refreshTick = refreshTick,
                refreshing = refreshing,
                topInset = statusInset,
                onRefresh = ::pull,
                onOpen = onOpen,
            )
        }

        // 统一顶栏：品牌 + 搜索图标 + 二级 tab。首页时浮在 Hero 上，滚动后转实底
        Column(
            Modifier
                .fillMaxWidth()
                .background(
                    if (solid) SolidColor(ProomColor.bg)
                    else Brush.verticalGradient(
                        0f to ProomColor.bg.copy(alpha = 0.72f),
                        1f to Color.Transparent,
                    )
                )
                .padding(top = statusInset, bottom = if (solid) 0.dp else 8.dp),
        ) {
            FilmTopBar(onSearch)
            FilmTabRow(current = tab, onSelect = { tab = it })
        }
    }
}

/* ───────────────────────── 顶栏 ───────────────────────── */

@Composable
private fun FilmTopBar(onSearch: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(TopBarH).padding(start = 20.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 品牌：一个小金点 + 字标，唯一的身份锚
        Box(Modifier.size(6.dp).clip(CircleShape).background(ProomColor.accent))
        Spacer(Modifier.width(8.dp))
        Text(
            "放映室",
            color = ProomColor.text,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.2.sp,
        )
        Spacer(Modifier.weight(1f))

        // 搜索统一成一个图标 + 40dp 圆底，不再用文字按钮
        Box(
            Modifier.size(40.dp).clip(CircleShape).clickable(onClick = onSearch),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = TabIcons.Search,
                contentDescription = "搜索",
                tint = ProomColor.text,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

@Composable
private fun FilmTabRow(current: Int, onSelect: (Int) -> Unit) {
    // 六个 tab 平分一行：等分 + 居中，最右的「纪录片」永远完整可见
    // （之前用 LazyRow + 26dp 间距，第 6 个被切在屏幕外）
    Row(
        Modifier.fillMaxWidth().height(TabBarH).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        FILM_TABS.forEachIndexed { index, _ ->
            val label = FILM_TABS[index].first
            val on = index == current
            Column(
                Modifier
                    .weight(1f)
                    // selectable + Role.Tab：读屏能念「已选中」，自动化也能拿到边界
                    .selectable(selected = on, role = Role.Tab, onClick = { onSelect(index) })
                    .padding(bottom = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    label,
                    color = if (on) ProomColor.text else ProomColor.muted,
                    fontSize = 15.sp,
                    fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1,
                    softWrap = false,
                )
                Spacer(Modifier.height(6.dp))
                Box(
                    Modifier
                        .height(2.dp)
                        .width(if (on) 18.dp else 0.dp)
                        .clip(RoundedCornerShape(1.dp))
                        .background(ProomColor.accent)
                )
            }
        }
    }
}

/* ───────────────────────── 首页 ───────────────────────── */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomePane(
    feed: NasApi.HomeFeed?,
    loading: Boolean,
    refreshing: Boolean,
    statusInset: Dp,
    onRefresh: () -> Unit,
    onOpen: (Vod) -> Unit,
    onPlay: (Vod) -> Unit,
    onMore: (String) -> Unit,
    onScrolled: (Boolean) -> Unit,
) {
    val continueWatching = (feed?.continueWatching.orEmpty() + feed?.liked.orEmpty()).take(8)
    val hot = feed?.latest.orEmpty()
    // Hero 已经把它们轮播展示了，下面「热门推荐」就不该再从第一条开始重复一遍。
    // 前 6 条给 Hero，剩下的进网格。
    val heroItems = hot.take(6)
    val hotRest = hot.drop(heroItems.size)
    val cats = feed?.cats.orEmpty()
    val state = rememberLazyGridState()

    val scrolled by remember {
        derivedStateOf { state.firstVisibleItemIndex > 0 || state.firstVisibleItemScrollOffset > 40 }
    }
    LaunchedEffect(scrolled) { onScrolled(scrolled) }

    PullToRefreshBox(
        isRefreshing = refreshing,
        onRefresh = onRefresh,
        modifier = Modifier.fillMaxSize(),
    ) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            // 62% 太占屏：一张就吃掉大半行高，改成 46%（约 180dp）
            val wideW = (maxWidth * 0.46f).coerceIn(150.dp, 220.dp)
            val railW = (maxWidth * 0.28f).coerceIn(96.dp, 150.dp)

            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                state = state,
                modifier = Modifier.fillMaxSize(),
                // 左右各留 20dp：之前只有 bottom，三列海报直接顶到屏幕边
                // （Hero 例外，它要通栏，所以单独出血，见 bleedHorizontal）
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 28.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(ProomSpace.lg),
            ) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    if (heroItems.isNotEmpty()) {
                        PhoneHero(
                            items = heroItems,
                            onPlay = onPlay,
                            onDetail = onOpen,
                            // 让开浮动顶栏，否则海报会顶到 tab 行上
                            topInset = TopBarH + TabBarH + statusInset,
                            // Hero 是首屏的底图，必须通栏：把网格的内边距抵回去
                            modifier = Modifier.bleedHorizontal(20.dp),
                        )
                    } else if (!(loading && hot.isEmpty())) {
                        // 骨架期不放这段占位：下面已经有 HeroSkeleton 了，
                        // 再垫 180dp 会把整块往下推，首屏多出一条黑带
                        Spacer(Modifier.height(180.dp))
                    }
                }

                if (continueWatching.isNotEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Column {
                            SectionTitle("继续观看", "接着看", hPad = 0.dp)
                            LazyRow(
                                contentPadding = PaddingValues(horizontal = 20.dp),
                                horizontalArrangement = Arrangement.spacedBy(ProomSpace.sm),
                            ) {
                                items(continueWatching) { v ->
                                    WideCard(vod = v, width = wideW, onClick = { onOpen(v) })
                                }
                            }
                        }
                    }
                }

                if (hotRest.isNotEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) { SectionTitle("热门推荐", "", hPad = 0.dp) }
                    items(hotRest.take(18)) { v ->
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
                            hPad = 0.dp,
                            onOpen = onOpen,
                            onMore = { onMore(group.cat) },
                        )
                    }
                }

                // 首屏骨架：形状按真实内容摆好，数据一到直接替换
                if (loading && hot.isEmpty()) {
                    // Hero 也要占位，否则一进来第一屏是「一大坨空白」，
                    // 等内容到了再整块跳出来，比骨架本身更难看。
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        HeroSkeleton(
                            topInset = TopBarH + TabBarH + statusInset,
                            modifier = Modifier.bleedHorizontal(20.dp),
                        )
                    }
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        // 别再自己加 20dp：网格已经给了左右边距，
                        // 再叠一层标题就比下面「热门推荐」多缩进 20dp。
                        Column {
                            SectionTitle("继续观看", "", hPad = 0.dp)
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(ProomSpace.sm)) {
                                items(3) { WideSkeleton(Modifier.width(wideW)) }
                            }
                        }
                    }
                    item(span = { GridItemSpan(maxLineSpan) }) { SectionTitle("热门推荐", "", hPad = 0.dp) }
                    items(9) { PosterSkeleton() }
                } else if (loading) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Box(
                            Modifier.fillMaxWidth().height(72.dp),
                            contentAlignment = Alignment.Center,
                        ) { CircularProgressIndicator(color = ProomColor.accent, strokeWidth = 2.dp) }
                    }
                }
            }
        }
    }
}

/* ─────────────────────── 分类（同一套网格） ─────────────────────── */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CategoryPane(
    api: NasApi,
    slug: String,
    name: String,
    refreshTick: Int,
    refreshing: Boolean,
    topInset: Dp,
    onRefresh: () -> Unit,
    onOpen: (Vod) -> Unit,
) {
    var items by remember(slug) { mutableStateOf<List<Vod>>(emptyList()) }
    var page by remember(slug) { mutableIntStateOf(1) }
    var more by remember(slug) { mutableStateOf(false) }
    var loading by remember(slug) { mutableStateOf(true) }
    var loadingMore by remember(slug) { mutableStateOf(false) }
    var err by remember(slug) { mutableStateOf<String?>(null) }
    var total by remember(slug) { mutableIntStateOf(0) }
    var sites by remember(slug) { mutableIntStateOf(0) }

    suspend fun load(p: Int, append: Boolean) {
        if (append) loadingMore = true else loading = true
        err = null
        runCatching { api.browse(cat = slug, page = p) }
            .onSuccess { d ->
                items = if (append) items + d.items else d.items
                more = d.more
                total = d.total
                sites = d.sites
                page = p
            }
            .onFailure { err = it.message }
        loading = false
        loadingMore = false
    }

    LaunchedEffect(slug, refreshTick) { load(1, append = false) }

    val state = rememberLazyGridState()
    LaunchedEffect(state, items.size, more) {
        snapshotFlow { state.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
            .collect { last ->
                if (more && !loading && !loadingMore && items.isNotEmpty() && last >= items.size - 6) {
                    load(page + 1, append = true)
                }
            }
    }

    PullToRefreshBox(
        isRefreshing = refreshing,
        onRefresh = onRefresh,
        modifier = Modifier.fillMaxSize(),
    ) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            state = state,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 20.dp, end = 20.dp,
                top = TopBarH + TabBarH + 8.dp + topInset,
                bottom = 28.dp,
            ),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Text(
                    if (total > 0) "$name · 共 $total 部" else name,
                    style = ProomType.meta,
                    color = ProomColor.muted,
                    modifier = Modifier.padding(bottom = 2.dp),
                )
            }

            items(items) { v ->
                PosterCard(
                    title = v.name,
                    meta = listOf(v.year, v.type).filter { it.isNotBlank() }.joinToString(" · "),
                    poster = v.pic,
                    onClick = { onOpen(v) },
                )
            }

            if (loading && items.isEmpty()) {
                // 首次进入：骨架占位，不要一个孤零零的圈
                items(9) { PosterSkeleton() }
            } else if (loadingMore) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Box(Modifier.fillMaxWidth().height(72.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = ProomColor.accent, strokeWidth = 2.dp)
                    }
                }
            }

            if (err != null && items.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Column(
                        Modifier.fillMaxWidth().padding(vertical = 40.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(err ?: "", style = ProomType.body, color = ProomColor.dim)
                        Spacer(Modifier.height(12.dp))
                        Box(
                            Modifier
                                .clip(RoundedCornerShape(ProomShape.sm))
                                .background(ProomColor.surface)
                                .clickable { }
                                .padding(horizontal = 18.dp, vertical = 10.dp),
                        ) { Text("下拉重试", style = ProomType.meta, color = ProomColor.text) }
                    }
                }
            }

            if (!loading && items.isEmpty() && err == null) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    // 空态要成块：图标 + 一句解释 + 下一步怎么做，不要孤零零一行文字
                    Column(
                        Modifier.fillMaxWidth().padding(top = 96.dp, bottom = 40.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Box(
                            Modifier.size(44.dp).clip(CircleShape).background(ProomColor.surface),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("○", color = ProomColor.muted, fontSize = 20.sp)
                        }
                        Spacer(Modifier.height(14.dp))
                        Text(
                            "「$name」暂时没有内容",
                            style = ProomType.body,
                            color = ProomColor.dim,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "下拉可以重新拉取，或者回首页看看推荐",
                            style = ProomType.label,
                            color = ProomColor.muted,
                        )
                    }
                }
            }
        }
    }
}

/* ─────────────────────── 小组件 ─────────────────────── */

@Composable
private fun SectionTitle(title: String, sub: String, hPad: Dp = 20.dp) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = hPad, vertical = ProomSpace.sm),
        verticalAlignment = Alignment.Bottom,
    ) {
        Text(title, style = ProomType.title, color = ProomColor.text)
        if (sub.isNotBlank()) {
            Text(
                "  $sub",
                style = ProomType.label,
                color = ProomColor.muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** 分类货架：标题 + 「全部 ›」+ 横滑海报 */
@Composable
private fun CatRail(
    group: NasApi.CatGroup,
    cardW: Dp,
    onOpen: (Vod) -> Unit,
    onMore: () -> Unit,
    hPad: Dp = 20.dp,
) {
    Column {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = hPad),
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
                Text("全部 ›", style = ProomType.label, color = ProomColor.muted, maxLines = 1)
            }
        }
        Spacer(Modifier.height(ProomSpace.sm))
        LazyRow(
            contentPadding = PaddingValues(horizontal = hPad),
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

/**
 * 首屏 Hero 的骨架。
 *
 * 形状照着 `PhoneHero` 摆：左边竖版海报、右边标题/信息两行、底下两个按钮。
 * 背景只用一层很淡的竖向渐变 —— 骨架期没有图，画模糊底反而像加载失败的脏块。
 */
@Composable
private fun HeroSkeleton(topInset: Dp, modifier: Modifier = Modifier) {
    Column(
        modifier
            .fillMaxWidth()
            .height(300.dp + topInset)
            .background(
                Brush.verticalGradient(
                    listOf(ProomColor.surface2, ProomColor.bg)
                )
            )
            .padding(start = 20.dp, end = 20.dp, top = topInset, bottom = 34.dp),
        verticalArrangement = Arrangement.Bottom,
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            SkeletonBlock(Modifier.width(96.dp).height(144.dp), radius = 14.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                SkeletonBlock(Modifier.width(56.dp), height = 10.dp, radius = 5.dp)
                Spacer(Modifier.height(12.dp))
                SkeletonBlock(Modifier.fillMaxWidth(0.92f), height = 22.dp, radius = 6.dp)
                Spacer(Modifier.height(8.dp))
                SkeletonBlock(Modifier.fillMaxWidth(0.6f), height = 22.dp, radius = 6.dp)
                Spacer(Modifier.height(12.dp))
                SkeletonBlock(Modifier.width(118.dp), height = 11.dp, radius = 5.dp)
            }
        }
        Spacer(Modifier.height(18.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SkeletonBlock(Modifier.weight(1f), height = 44.dp, radius = ProomShape.sm)
            SkeletonBlock(Modifier.width(96.dp), height = 44.dp, radius = ProomShape.sm)
        }
    }
}

/**
 * 在「左右有内边距」的网格里重新铺满整宽。
 *
 * LazyVerticalGrid 的 contentPadding 会让**每个** item 都内缩，
 * 但 Hero 这种首屏底图必须通栏 —— 测量时把约束放宽 2×pad，再整体左移 pad，
 * 画出来就是 0..全宽，且不影响行高计算。
 */
private fun Modifier.bleedHorizontal(pad: Dp): Modifier =
    this.layout { measurable, constraints ->
        val px = pad.roundToPx()
        val w = constraints.maxWidth + px * 2
        val placeable = measurable.measure(
            constraints.copy(minWidth = w, maxWidth = w)
        )
        // 只放宽、不偏移：item 的起点本来就在 0（contentPadding 只收窄了约束），
        // 再 left 移一次会把 Hero 内部的 20dp 边距也吃掉，海报就贴到屏幕边缘了。
        layout(placeable.width, placeable.height) { placeable.place(0, 0) }
    }
