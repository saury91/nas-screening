package com.projectionroom.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.Flow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.projectionroom.shared.catalog.NasApi
import com.projectionroom.shared.model.Vod
import com.projectionroom.ui.component.PosterCard
import com.projectionroom.ui.component.PosterSkeleton
import com.projectionroom.ui.theme.ProomColor
import com.projectionroom.ui.theme.ProomSpace
import com.projectionroom.ui.theme.ProomType

private val YEARS = listOf("", "2026", "2025", "2024", "2023", "2022", "2021", "2020")
private val AREAS = listOf("", "内地", "中国香港", "中国台湾", "美国", "日本", "韩国", "英国")

/**
 * 片库：页头 + 年份/地区筛选 + 海报网格。
 *
 * 和 Web 版一样，筛选条在页头下面一行，选中项金色描边。
 *
 * 分页：一页 60 条，滚到接近底部自动接下一页（最多 30 页，服务端上限）。
 * 触发用「最后一个可见下标 ≥ 总数 - 8」而不是「到底了」—— 留 8 格提前量，
 * 手指/遥控器还没滑到头，下一批已经在路上了，滚动不会出现一次明显的等待。
 */
@Composable
fun BrowseScreen(
    slug: String,
    /**
     * 数据来源由壳注入：现在优先本地聚合（按配置包里的分类映射直连各站），
     * NAS 只是备胎。页面不再认识 NasApi，也就没法偷偷依赖它。
     */
    /**
     * 一页数据，**流式**给：先可能来一条缓存（旧内容，先上屏），
     * 之后每回来一个采集源就再来一条更全的。每次都是完整快照，直接替换即可。
     */
    load: (cat: String, page: Int, year: String, area: String) -> Flow<NasApi.BrowsePage>,
    onOpen: (Vod) -> Unit,
    /** 返回。片库现在是**一级 tab**，电视上不再需要返回入口；保留给其它调用方 */
    onBack: (() -> Unit)? = null,
    topInset: androidx.compose.ui.unit.Dp = 0.dp,
    /** 一级导航（tab 条）。非空时顶栏由它接管，页面自己不再画返回 */
    nav: com.projectionroom.ui.component.TvRootNav? = null,
) {
    // 返回本页时用上次的快照原样复原（列表 + 筛选 + 滚动位置），不重拉。
    // 见 BrowseCache 的注释：页面 remember 会随 Screen 分支重建归零，
    // 不这样兜一下，从详情退回片库就等于「又刷了一遍」。
    val cached = remember(slug) { com.projectionroom.ui.state.BrowseCache.get(slug) }
    var year by remember(slug) { mutableStateOf(cached?.year ?: "") }
    var area by remember(slug) { mutableStateOf(cached?.area ?: "") }
    var items by remember(slug) { mutableStateOf(cached?.items ?: emptyList()) }
    var loading by remember(slug) { mutableStateOf(cached == null) }
    var loadingMore by remember(slug) { mutableStateOf(false) }
    var page by remember(slug) { mutableIntStateOf(cached?.page ?: 1) }
    var more by remember(slug) { mutableStateOf(cached?.more ?: false) }
    var err by remember(slug) { mutableStateOf<String?>(null) }

    // 刚从缓存复原的那一下不允许触底加载：scrollToItem 会把可见范围推到末尾，
    // 不挡的话「返回上一页」会立刻再翻一页
    var allowMore by remember(slug) { mutableStateOf(cached == null) }

    LaunchedEffect(slug, year, area) {
        // 有快照、且筛选没变 → 就是「从详情/播放返回」，什么都不用做
        if (cached != null && year == cached.year && area == cached.area) {
            loading = false
            return@LaunchedEffect
        }
        loading = true
        err = null
        page = 1
        // 换筛选条件先清空：不清的话，新条件一条都没命中时屏幕上还留着上一次的
        // 海报，用户以为按钮没反应。清掉才能露出骨架 → 空态提示。
        items = emptyList()
        runCatching {
            load(slug, 1, year, area).collect { p ->
                // 缓存或第一个源到了就撤掉骨架；后面继续到来的快照直接替换
                items = p.items
                more = p.more
                loading = false
            }
        }.onFailure { err = it.message }
        loading = false
    }

    val gridState = rememberLazyGridState()

    // 复原滚动位置；顺带把「触底加载」推迟到用户真的自己滚动之后
    LaunchedEffect(slug) {
        allowMore = false
        val target = cached?.scroll ?: 0
        if (target > 0) gridState.scrollToItem(target)
        // 给布局一点时间把可见范围稳定下来：scrollToItem 之后第一个 layout 还没算完，
        // 这时候 lastIndex 仍是 0/末尾值，最容易误触发翻页
        if (cached != null) kotlinx.coroutines.delay(800)
        allowMore = true
    }

    // 离开页面时把当前状态存下来（onDispose 里读 state 是安全的，不会触发重组）
    DisposableEffect(slug) {
        onDispose {
            com.projectionroom.ui.state.BrowseCache.put(
                slug,
                com.projectionroom.ui.state.BrowseCache.Snapshot(
                    year = year,
                    area = area,
                    items = items,
                    page = page,
                    more = more,
                    scroll = gridState.firstVisibleItemIndex,
                ),
            )
        }
    }

    // 触底加载。snapshotFlow 只在可见范围变化时醒一次，不会每帧空转
    LaunchedEffect(gridState, slug, year, area) {
        snapshotFlow {
            val info = gridState.layoutInfo
            (info.visibleItemsInfo.lastOrNull()?.index ?: -1) to info.totalItemsCount
        }.collect { (last, count) ->
            if (!allowMore) return@collect
            if (!more || loadingMore || count == 0) return@collect
            if (last < count - 8) return@collect
            loadingMore = true
            // 下一页也是流式的：每次来的都是「第 N 页」的完整快照，
            // 所以固定用进入分页时的 items 当基线，避免重复追加
            val base = items
            runCatching {
                load(slug, page + 1, year, area).collect { p ->
                    // 采集站同一页可能回重复，按名字再滤一次，避免网格里出现同款两张
                    items = (base + p.items).distinctBy { it.name }
                    more = p.more
                }
            }
            page += 1
            loadingMore = false
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(ProomColor.bg)) {
        val cardW = (maxWidth * 0.115f).coerceIn(110.dp, 200.dp)
        val columns = (maxWidth / (cardW + 14.dp)).toInt().coerceIn(3, 8)

        Column(Modifier.fillMaxSize()) {
            // 一级导航：片库不是一个「页面」，是 tab 的内容区 —— 所以这里没有返回，
            // 顶栏也不跟着网格滚走（滚到第 20 屏还能切 tab）
            if (nav != null) {
                com.projectionroom.ui.component.TvRootHeader(nav = nav, topInset = topInset)
            }

            LazyVerticalGrid(
                state = gridState,
                modifier = Modifier.fillMaxWidth().weight(1f),
                columns = GridCells.Fixed(columns),
                contentPadding = PaddingValues(start = ProomSpace.xxl, end = ProomSpace.xxl, bottom = ProomSpace.xxl),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalArrangement = Arrangement.spacedBy(ProomSpace.lg),
            ) {
                // 这一页已经在「电影 / 剧集 / 动漫…」这个 tab 下面了，再写一遍「片库 + 分类名」
                // 等于把顶栏说过的又说一次，还白占一屏 —— 直接从筛选条开始。
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Column(Modifier.padding(top = ProomSpace.xl, bottom = ProomSpace.md)) {
                        ChipRow("年份", YEARS, year) { year = it }
                        Spacer(Modifier.height(8.dp))
                        ChipRow("地区", AREAS, area) { area = it }
                    }
                }

                // 首屏没有数据时铺骨架而不是转圈：卡位和真海报同尺寸，
                // 数据到了原地替换，页面不会整块跳一下
                if (loading && items.isEmpty()) {
                    items(count = columns * 2) { PosterSkeleton() }
                }

                // key 只用片名：一次结果里片名是唯一的（Catalog 按归一化片名分组）。
                // 以前 key 里带了 year，而流式快照会随源陆续把 year 从空补全 —— 补上那一下
                // LazyGrid 认为是一张新卡片，旧卡片被销毁、在途海报请求跟着取消重下，
                // 看着就是「列表早出来了，图半天长不出来」。
                items(
                    items = items,
                    key = { it.name },
                    contentType = { "poster" },
                ) { v ->
                    PosterCard(
                        title = v.name,
                        meta = listOf(v.year, v.type).filter { it.isNotBlank() }.joinToString(" · "),
                        poster = v.pic,
                        onClick = { onOpen(v) },
                    )
                }

                // 接下一页时同样铺一行骨架，比在网格里塞一个转圈更像「正在长出内容」
                if (loadingMore) {
                    items(count = columns) { PosterSkeleton() }
                }

                if (!loading && items.isEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Box(Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
                            Text(
                                text = err?.let { "读取失败：$it" } ?: "这个筛选下没有片子",
                                style = ProomType.meta,
                                color = ProomColor.muted,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ChipRow(label: String, values: List<String>, selected: String, onSelect: (String) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = ProomType.label, color = ProomColor.muted, modifier = Modifier.padding(end = 6.dp))
        values.forEach { v ->
            FilterChip(
                text = v.ifBlank { "全部" },
                on = v == selected,
                onClick = { onSelect(v) },
            )
        }
    }
}

@Composable
private fun FilterChip(text: String, on: Boolean, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(50)
    Box(
        Modifier
            .clip(shape)
            .background(if (on) ProomColor.accentSoft else ProomColor.surface)
            .then(
                when {
                    focused -> Modifier.border(2.dp, ProomColor.accent, shape)
                    on -> Modifier.border(1.dp, ProomColor.accent.copy(alpha = 0.6f), shape)
                    else -> Modifier
                }
            )
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick)
            .padding(horizontal = 13.dp, vertical = 6.dp),
    ) {
        Text(
            text = text,
            style = ProomType.label,
            color = if (on) ProomColor.accent else ProomColor.text,
        )
    }
}
