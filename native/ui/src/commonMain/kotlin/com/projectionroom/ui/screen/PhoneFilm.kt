package com.projectionroom.ui.screen

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import com.projectionroom.shared.catalog.NasApi
import com.projectionroom.shared.model.Vod
import com.projectionroom.ui.component.PhoneHero
import com.projectionroom.ui.component.PosterCard
import com.projectionroom.ui.component.TabIcons
import com.projectionroom.ui.component.WideCard
import com.projectionroom.ui.theme.ProomColor
import com.projectionroom.ui.theme.ProomShape
import com.projectionroom.ui.theme.ProomSpace
import com.projectionroom.ui.theme.ProomType

/**
 * 「影视」页：一个页面 + 六个二级 tab。
 *
 * 之前点「电影」是整页跳到一个独立的 BrowseScreen（带自己的大标题和筛选条），
 * 视觉语言和首页完全两套 —— 这是这次重做的核心。
 *
 * 现在：顶栏（品牌 + 搜索图标）与 tab 条固定，内容区按 tab 切换，
 * 首页是沉浸式 Hero，分类页是同一套海报网格，卡片尺寸、间距、圆角全部一致。
 */

private val FILM_TABS = listOf(
    "首页" to "",
    "电影" to "movie",
    "剧集" to "series",
    "动漫" to "anime",
    "综艺" to "variety",
    "纪录片" to "documentary",
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
) {
    var tab by remember { mutableIntStateOf(0) }
    // 首页滚过一屏后顶栏变实底；分类 tab 本身就是实底
    var homeScrolled by remember { mutableStateOf(false) }
    val solid = tab != 0 || homeScrolled

    Box(Modifier.fillMaxSize().background(ProomColor.bg)) {
        if (tab == 0) {
            HomePane(
                feed = feed,
                loading = loading,
                onOpen = onOpen,
                onMore = { cat ->
                    val i = FILM_TABS.indexOfFirst { it.second == cat }
                    if (i > 0) tab = i
                },
                onScrolled = { homeScrolled = it },
            )
        } else {
            CategoryPane(
                api = api,
                slug = FILM_TABS[tab].second,
                name = FILM_TABS[tab].first,
                onOpen = onOpen,
            )
        }

        // 统一顶栏：品牌 + 搜索图标 + 二级 tab。首页时浮在 Hero 上，滚动后转实底
        Column(
            Modifier
                .fillMaxWidth()
                .background(
                    if (solid) {
                        androidx.compose.ui.graphics.SolidColor(ProomColor.bg)
                    } else {
                        Brush.verticalGradient(
                            0f to ProomColor.bg.copy(alpha = 0.72f),
                            1f to Color.Transparent,
                        )
                    }
                )
                .padding(bottom = if (solid) 0.dp else 8.dp),
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
        Modifier
            .fillMaxWidth()
            .height(TopBarH)
            .padding(start = 20.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 品牌：一个小金点 + 字标。不是英文副标那种噪声，是唯一身份锚
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

        // 搜索统一成一个图标 + 可点圆底，不再用文字按钮（文字在不同页面宽度会飘）
        Box(
            Modifier
                .size(40.dp)
                .clip(CircleShape)
                .clickable(onClick = onSearch),
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
    LazyRow(
        Modifier.fillMaxWidth().height(TabBarH),
        contentPadding = PaddingValues(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(26.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        items(FILM_TABS) { (label, _) ->
            val index = FILM_TABS.indexOfFirst { it.first == label }
            val on = index == current
            Column(
                Modifier
                    // 用 selectable + Role.Tab：读屏能念出「已选中」，
                    // 自动化测试也能拿到每个 tab 的边界（之前只有纯 Text，抓不到）
                    .selectable(
                        selected = on,
                        role = androidx.compose.ui.semantics.Role.Tab,
                        onClick = { onSelect(index) },
                    )
                    .padding(bottom = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    label,
                    color = if (on) ProomColor.text else ProomColor.muted,
                    fontSize = 16.sp,
                    fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                )
                Spacer(Modifier.height(6.dp))
                // 选中指示：2dp 短横线，比胶囊更「tab」，也不会和筛选 chip 混淆
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

@Composable
private fun HomePane(
    feed: NasApi.HomeFeed?,
    loading: Boolean,
    onOpen: (Vod) -> Unit,
    onMore: (String) -> Unit,
    onScrolled: (Boolean) -> Unit,
) {
    val continueWatching = (feed?.continueWatching.orEmpty() + feed?.liked.orEmpty()).take(8)
    val hot = feed?.latest.orEmpty()
    val cats = feed?.cats.orEmpty()
    val state = rememberLazyGridState()

    val scrolled by remember {
        derivedStateOf { state.firstVisibleItemIndex > 0 || state.firstVisibleItemScrollOffset > 40 }
    }
    LaunchedEffect(scrolled) { onScrolled(scrolled) }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wideW = (maxWidth * 0.62f).coerceIn(180.dp, 300.dp)
        val railW = (maxWidth * 0.28f).coerceIn(96.dp, 150.dp)

        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            state = state,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 28.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(ProomSpace.lg),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                if (hot.isNotEmpty()) {
                    PhoneHero(
                        items = hot.take(6),
                        onPlay = onOpen,
                        onDetail = onOpen,
                    )
                } else {
                    Spacer(Modifier.height(160.dp))
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
                            items(continueWatching) { v ->
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

            if (loading) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = ProomColor.accent, strokeWidth = 2.dp)
                    }
                }
            }
        }
    }
}

/* ─────────────────────── 分类（同一套网格） ─────────────────────── */

@Composable
private fun CategoryPane(
    api: NasApi,
    slug: String,
    name: String,
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

    LaunchedEffect(slug) { load(1, append = false) }

    val state = rememberLazyGridState()
    // 留 6 格余量：还没滑到底，下一页就已经在路上
    LaunchedEffect(state, items.size, more) {
        snapshotFlow { state.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
            .collect { last ->
                if (more && !loading && !loadingMore && items.isNotEmpty() &&
                    last >= items.size - 6
                ) {
                    load(page + 1, append = true)
                }
            }
    }

    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        state = state,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 20.dp, end = 20.dp,
            top = TopBarH + TabBarH + 8.dp,
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

        if (loading || loadingMore) {
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
                    ) { Text("重试", style = ProomType.meta, color = ProomColor.text) }
                }
            }
        }

        if (!loading && items.isEmpty() && err == null) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Text(
                    "这个分类暂时没有内容",
                    style = ProomType.body,
                    color = ProomColor.muted,
                    modifier = Modifier.padding(vertical = 40.dp),
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
                "  $sub",
                style = ProomType.label,
                color = ProomColor.muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/* ── 分类货架（首页用，带「全部 ›」） ── */

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
