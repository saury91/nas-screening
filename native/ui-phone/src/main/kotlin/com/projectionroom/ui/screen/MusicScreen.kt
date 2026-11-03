package com.projectionroom.ui.screen

import kotlinx.coroutines.flow.Flow

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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.projectionroom.shared.catalog.NasApi
import com.projectionroom.ui.component.ProomPullRefresh
import com.projectionroom.ui.component.SkeletonBlock
import com.projectionroom.ui.component.TabIcons
import com.projectionroom.ui.theme.ProomColor
import com.projectionroom.ui.theme.ProomShape
import com.projectionroom.ui.theme.ProomSpace
import com.projectionroom.ui.theme.ProomType
import com.projectionroom.ui.music.PlaylistCache
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.minutes
import com.projectionroom.ui.component.RemoteImage

/**
 * 音乐：推荐歌单 + 分类歌单。
 *
 * 结构和网页端对齐：上面是编辑挑的 14 张（横滑），下面是分类 ——
 * 一级「主题 / 语种 / 风格 / 场景 / 情感」，二级是该组下的具体分类。
 *
 * 之前把分类拍平成一行 chip，层级全丢了：用户在「华语」和「主题」之间
 * 其实隔着一层，平铺之后既看不出有哪些大类，也没法按大类找。
 */
@Composable
fun MusicScreen(
    /** 分类树来源（现在是 App 直连上游聚合接口，不走 NAS） */
    /** 分类，流式：先磁盘缓存，再源站 */
    loadCats: () -> Flow<List<NasApi.MusicCatGroup>>,
    /** 某个分类下的歌单；传空串表示「推荐」 */
    /** 歌单（cat 为空 = 推荐），流式：先磁盘缓存，再源站 */
    loadPlaylists: (cat: String) -> Flow<List<NasApi.Playlist>>,
    onOpenPlaylist: (NasApi.Playlist) -> Unit,
    /** 搜歌（和歌单同一个聚合接口）。顶栏那颗放大镜进来就是它 */
    searchTracks: suspend (String) -> List<NasApi.Track> = { emptyList() },
    /** 点搜索结果：整份结果当队列，从这一首开始放 */
    onPlayTracks: (List<NasApi.Track>, Int) -> Unit = { _, _ -> },
    /** 返回。手机上从 tab 进来，电视上是遥控器 BACK 之外的兜底入口 */
    onBack: (() -> Unit)? = null,
    /**
     * 下拉刷新时通知壳：数据层有 5 分钟新鲜期，
     * 不打开强制窗口的话三路请求都会直接命中磁盘缓存，圈转完屏上什么都不变。
     */
    onForceRefresh: () -> Unit = {},
    topInset: androidx.compose.ui.unit.Dp = 0.dp,
) {
    // 搜索态：整页换成「输入框 + 结果」，退出搜索回到歌单网格
    var searching by remember { mutableStateOf(false) }
    var cats by remember { mutableStateOf(MusicCache.cats()) }
    // 推荐区
    var rec by remember { mutableStateOf(MusicCache.lists(REC_KEY).orEmpty()) }
    // 两路数据是否已经拉完（缓存命中也算）。没有这两位就分不清
    // 「还在拉」和「拉回空」，没配音乐接口时会一直停在骨架屏上。
    var catsLoaded by remember { mutableStateOf(MusicCache.cats().isNotEmpty()) }
    var recLoaded by remember { mutableStateOf(MusicCache.lists(REC_KEY) != null) }
    // 分类区：一级下标 + 组内二级下标。
    // 重进页面要照着上次选的那一档定位，只有在没有记录时才落默认的「语种 · 华语」——
    // 无脑回默认的话，下面整片卡片会换成另一批，看着就像「返回后整个重新加载了一遍」
    val savedPos = remember(cats) { MusicUiState.locate(cats) }
    val initGi = remember(cats) {
        savedPos?.first ?: cats.indexOfFirst { it.name == "语种" }.takeIf { it >= 0 } ?: 0
    }
    var gi by remember(cats) { mutableIntStateOf(initGi) }
    var si by remember(cats) {
        mutableIntStateOf(
            savedPos?.second
                ?: cats.getOrNull(initGi)?.subs?.indexOfFirst { it.name == "华语" }?.takeIf { it >= 0 }
                ?: 0
        )
    }
    val group = cats.getOrNull(gi)
    val subs = group?.subs.orEmpty()
    val sub = subs.getOrNull(si)
    // 卡片首帧就得有内容：先取缓存，缓存过期就用上次屏上那份快照。
    // 空着起步的话分类区高度是塌的，滚动位置会被裁掉，数据一回来再撑开，
    // 那一撑就是用户眼里的「返回后又刷新加载了一下、布局动了一下」
    var cards by remember {
        val cached = sub?.id?.let { MusicCache.lists(it) }
        val snapshot = MusicUiState.cards
            .takeIf { sub?.id != null && MusicUiState.cardsCat == sub?.id }
        mutableStateOf(cached ?: snapshot.orEmpty())
    }
    var listLoading by remember { mutableStateOf(cards.isEmpty()) }
    var listErr by remember { mutableStateOf<String?>(null) }
    var refreshing by remember { mutableStateOf(false) }
    // 刷新代数：分类树、推荐、当前分类三路都要跟着重拉一遍
    var reload by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(reload) {
        // reload > 0 是下拉刷新：缓存已被清掉，这里必须真的重拉
        if (reload == 0 && cats.isNotEmpty()) {
            catsLoaded = true
            return@LaunchedEffect
        }
        runCatching { loadCats().collect { cats = MusicCache.putCats(it) } }
        catsLoaded = true
    }
    LaunchedEffect(reload) {
        if (reload == 0 && rec.isNotEmpty()) {
            recLoaded = true
            return@LaunchedEffect
        }
        runCatching { loadPlaylists("").collect { rec = MusicCache.putLists(REC_KEY, it) } }
        recLoaded = true
    }
    // 选中的分类、屏上这批卡片都记进进程内：返回时首帧直接照着画，不重排一次
    LaunchedEffect(sub?.id) { MusicUiState.subId = sub?.id }
    LaunchedEffect(cards, sub?.id) {
        // 只记有内容的那份。骨架期间的空列表写进去，反而会把上次的快照冲掉
        if (sub?.id != null && cards.isNotEmpty()) {
            MusicUiState.cardsCat = sub?.id
            MusicUiState.cards = cards
        }
    }
    // 二级分类变化才重新拉。命中缓存立刻出内容，不闪骨架
    LaunchedEffect(sub?.id, reload) {
        val id = sub?.id ?: return@LaunchedEffect
        listErr = null
        if (reload == 0) {
            MusicCache.lists(id)?.let {
                cards = it
                listLoading = false
                return@LaunchedEffect
            }
            // 缓存过期了但屏上还有上次那批：留着静默换新。
            // 先清空再等，等于把已经画好的分类区拆掉重装一遍，位置也跟着歪
            listLoading = cards.isEmpty()
        } else if (cards.isEmpty()) {
            listLoading = true
        }
        runCatching {
            loadPlaylists(id).collect { p ->
                // 第一发通常是磁盘缓存：立刻撤骨架，源站到了再替换
                cards = MusicCache.putLists(id, p)
                listLoading = false
            }
        }.onFailure { listErr = it.message }
        listLoading = false
    }

    fun refresh() {
        if (refreshing) return
        refreshing = true
        // 进程内那份缓存必须清：不清的话三路都直接命中内存，一秒就「刷完了」
        MusicCache.clear()
        catsLoaded = false
        recLoaded = false
        onForceRefresh()
        reload += 1
        scope.launch {
            // 三路都落地才收圈，只等其中一路的话另两路还在往屏上写
            val started = System.currentTimeMillis()
            while (System.currentTimeMillis() - started < 8000) {
                val done = catsLoaded && recLoaded && (sub == null || !listLoading)
                if (done && System.currentTimeMillis() - started >= 700) break
                delay(60)
            }
            refreshing = false
        }
    }

    // 页面是会重建的（进歌单详情再返回），滚动位置只能记在进程里，
    // 不然滚到第八屏点开一张，返回时直接跳回顶部，等于让人重滚一遍。
    //
    // 位置必须走 initial*，让状态第一帧就在原位。之前的写法是 remember 从 0 起步、
    // 再由 LaunchedEffect 去 scrollToItem 追回来 —— 复位那一下用户看得见：
    // 返回瞬间页面先画在顶部（或第一张卡），然后被拽回原处，像被人硬滚了一把。
    val gridState = rememberLazyGridState(
        initialFirstVisibleItemIndex = MusicUiState.index,
        initialFirstVisibleItemScrollOffset = MusicUiState.offset,
    )
    // 推荐歌单那一行也是横滑的，道理同上：横着滑到第 9 张点进去，
    // 返回时要是弹回第 1 张，刚才翻的功夫全白费
    val recRowState = rememberLazyListState(
        initialFirstVisibleItemIndex = MusicUiState.recIndex,
        initialFirstVisibleItemScrollOffset = MusicUiState.recOffset,
    )
    // 只有「数据比首帧晚到」才需要补一次：冷启动时缓存是空的，格子还没那么多，
    // initial 的位置会被裁掉。命中缓存时这里条件不成立，一帧都不会动。
    LaunchedEffect(Unit) {
        val v = MusicUiState.index
        if (v > 0 && gridState.firstVisibleItemIndex < v) {
            withTimeoutOrNull(1500) {
                snapshotFlow { gridState.layoutInfo.totalItemsCount }.first { it > v }
            }?.let { gridState.scrollToItem(v, MusicUiState.offset) }
        }
        val h = MusicUiState.recIndex
        if (h > 0 && recRowState.firstVisibleItemIndex < h) {
            withTimeoutOrNull(1500) {
                snapshotFlow { recRowState.layoutInfo.totalItemsCount }.first { it > h }
            }?.let { recRowState.scrollToItem(h, MusicUiState.recOffset) }
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            MusicUiState.index = gridState.firstVisibleItemIndex
            MusicUiState.offset = gridState.firstVisibleItemScrollOffset
            // 推荐行没被布局过（用户已经竖着滚过去了）就别拿 0 覆盖原来记的位置
            if (recRowState.layoutInfo.totalItemsCount > 0) {
                MusicUiState.recIndex = recRowState.firstVisibleItemIndex
                MusicUiState.recOffset = recRowState.firstVisibleItemScrollOffset
            }
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(ProomColor.bg)) {
        // 和影视页同一档：手机 3 列（每张约 111dp）、横距 10dp。
        // 之前 2 列每张 170dp，一屏只看到 4 个歌单，信息密度太低
        val cols = 3
        // 推荐横滑卡比网格略大一点，用来区分「编辑推荐」和普通分类
        val recW = 122.dp

        // 搜歌：放大镜进来后整页让给搜索（和影视搜索页同一套交互），
        // 退出搜索时歌单网格还在，不用重新拉数据
        if (searching) {
            MusicSearchPage(
                searchTracks = searchTracks,
                onPlay = onPlayTracks,
                onClose = { searching = false },
                topInset = topInset,
            )
            return@BoxWithConstraints
        }

        ProomPullRefresh(
            refreshing = refreshing,
            onRefresh = ::refresh,
            // 顶栏是本页的第一行内容（跟着滚），指示器放在它上面那点留白里
            indicatorTop = topInset + 6.dp,
            modifier = Modifier.fillMaxSize(),
        ) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(cols),
            state = gridState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = ProomSpace.xxl),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column(Modifier.padding(top = topInset)) {
                    onBack?.let {
                            com.projectionroom.ui.component.PageBackBar(onBack = it)
                    }
                    MusicHeader(
                        recCount = rec.size,
                        catCount = cats.size,
                        onSearch = { searching = true },
                    )
                }
            }

            // 两路都拉完还是空 = 这台设备没配音乐接口，直接给一句话，
            // 别让人对着灰块等一个永远不来的响应
            val emptyAll = recLoaded && catsLoaded && rec.isEmpty() && cats.isEmpty()
            if (emptyAll) {
                item(span = { GridItemSpan(maxLineSpan) }) { EmptyMusic() }
            } else {
                // ── 推荐歌单 ──
                if (rec.isNotEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        SectionHead("推荐歌单", "编辑挑的，点开就是整张歌单")
                    }
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        // 横滑要通栏：网格给了 20dp 内边距，这里抵消掉
                        LazyRow(
                            state = recRowState,
                            contentPadding = PaddingValues(horizontal = 20.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.bleed(20.dp),
                        ) {
                            items(rec.filter { !PlaylistCache.isDead(it.id) }) { p ->
                                RecCard(p, recW) { onOpenPlaylist(p) }
                            }
                        }
                    }
                } else if (!recLoaded) {
                    item(span = { GridItemSpan(maxLineSpan) }) { RecSkeleton(recW) }
                }

                // ── 分类歌单 ──
                item(span = { GridItemSpan(maxLineSpan) }) {
                    SectionHead(
                        "分类歌单",
                        cats.joinToString(" · ") { it.name }
                            .ifBlank { "主题 · 语种 · 风格 · 场景 · 情感" },
                        top = ProomSpace.lg,
                    )
                }

                if (cats.isNotEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        GroupTabs(cats.map { it.name }, gi) { i ->
                            if (i == gi) return@GroupTabs
                            gi = i
                            si = 0
                            // 换大类就是换一整批卡片：旧的先在屏上等着，会被误认成新分类的结果
                            cards = emptyList()
                            listErr = null
                            listLoading = true
                        }
                    }
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        SubChips(subs.map { it.name }, si) { i ->
                            if (i == si) return@SubChips
                            si = i
                            cards = emptyList()
                            listErr = null
                            listLoading = true
                        }
                    }
                }

                // 0 首的、打开过一次却拿不到曲目的，一律不进列表：
                // 前者点进去是空页，后者是「外面 20 首、进去 0 首」，纯浪费点击
                val shown = cards.filter { it.count > 0 && !PlaylistCache.isDead(it.id) }

                when {
                    listErr != null -> item(span = { GridItemSpan(maxLineSpan) }) {
                        ListHint("这个分类读取失败", listErr.orEmpty())
                    }

                    listLoading && shown.isEmpty() -> items(count = 6) { PlSkeleton() }

                    shown.isEmpty() && subs.isNotEmpty() -> item(span = { GridItemSpan(maxLineSpan) }) {
                        ListHint(
                            "这个分类暂时没有歌单",
                            if (cards.isEmpty()) "换一个分类看看" else "可用的歌单都被筛掉了，下拉刷新试试",
                        )
                    }

                    else -> items(shown, key = { it.id }) { p -> PlCard(p) { onOpenPlaylist(p) } }
                }
            }
        }
        }
    }
}

/* ───────────────────── 顶栏与标题 ───────────────────── */

@Composable
private fun MusicHeader(recCount: Int, catCount: Int, onSearch: () -> Unit = {}) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(56.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(ProomColor.accent))
        Spacer(Modifier.width(8.dp))
        val sub = buildString {
            if (recCount > 0) append("${recCount} 个推荐")
            if (catCount > 0) {
                if (isNotEmpty()) append(" · ")
                append("$catCount 类")
            }
        }
        // 标题 + 统计整体吃满剩余宽度（weight 不带 fill=false）：
        // 这样放大镜才会被顶到行的最右边。之前统计文案自己带 weight(fill=false)，
        // 它没占满的那份宽度留在原地，图标就被拽进去了 20 多 dp。
        Row(
            Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "音乐",
                color = ProomColor.text,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.2.sp,
            )
            if (sub.isNotBlank()) {
                Text(
                    sub,
                    style = ProomType.meta,
                    color = ProomColor.muted,
                    modifier = Modifier.padding(start = 10.dp).weight(1f, fill = false),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        // 搜索入口和影视页同一位置、同一尺寸（48dp 可点区 + 22dp 图标），
        // 两个 tab 之间切换时眼睛不用重新找。
        // 影视顶栏是「屏幕边缘 12dp」，音乐页这一行在网格的 20dp contentPadding 里，
        // 所以按差额外移 8dp，让两页图标中心都落在离屏幕右边 36dp 的位置。
        Box(
            Modifier
                .offset(x = 8.dp)
                .size(48.dp)
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
private fun SectionHead(title: String, sub: String, top: Dp = 0.dp) {
    Row(
        Modifier.fillMaxWidth().padding(top = top, bottom = ProomSpace.sm),
        verticalAlignment = Alignment.Bottom,
    ) {
        Text(title, style = ProomType.title, color = ProomColor.text)
        Spacer(Modifier.width(8.dp))
        Text(
            sub,
            style = ProomType.label,
            color = ProomColor.muted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(bottom = 3.dp),
        )
    }
}

/* ───────────────── 一级 tab / 二级 chip ───────────────── */

@Composable
private fun GroupTabs(names: List<String>, current: Int, onSelect: (Int) -> Unit) {
    LazyRow(
        modifier = Modifier.bleed(20.dp).height(44.dp),
        contentPadding = PaddingValues(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        itemsIndexed(names) { i, name ->
            val on = i == current
            Column(
                Modifier
                    .clickable { onSelect(i) }
                    .padding(horizontal = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    name,
                    color = if (on) ProomColor.text else ProomColor.muted,
                    fontSize = 15.sp,
                    fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1,
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

@Composable
private fun SubChips(names: List<String>, current: Int, onSelect: (Int) -> Unit) {
    LazyRow(
        modifier = Modifier.bleed(20.dp).height(44.dp),
        contentPadding = PaddingValues(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        itemsIndexed(names) { i, name ->
            val on = i == current
            val shape = RoundedCornerShape(50)
            Box(
                Modifier
                    // 48dp：32dp 的胶囊是这一页最难点的东西
                    .height(48.dp)
                    .clip(shape)
                    .background(if (on) ProomColor.accentSoft else ProomColor.surface)
                    .then(
                        if (on) Modifier.border(1.dp, ProomColor.accent.copy(alpha = 0.55f), shape)
                        else Modifier
                    )
                    .clickable { onSelect(i) }
                    .padding(horizontal = 14.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    name,
                    fontSize = 13.sp,
                    fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium,
                    color = if (on) ProomColor.accent else ProomColor.dim,
                    maxLines = 1,
                    softWrap = false,
                )
            }
        }
    }
}

/* ───────────────────── 卡片 ───────────────────── */

/** 推荐卡：横滑用，封面大一些 */
@Composable
private fun RecCard(p: NasApi.Playlist, width: Dp, onClick: () -> Unit) {
    Column(Modifier.width(width).clickable(onClick = onClick)) {
        CoverBox(p, width)
        Text(
            p.name,
            style = ProomType.cardTitle,
            color = ProomColor.text,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 7.dp).height(40.dp),
        )
        Text(
            metaOf(p),
            style = ProomType.label,
            color = ProomColor.muted,
            maxLines = 1,
        )
    }
}

/** 分类卡：网格用 */
@Composable
private fun PlCard(p: NasApi.Playlist, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(ProomShape.md)
    Column(
        Modifier
            .fillMaxWidth()
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick),
    ) {
        Box {
            CoverBox(p, null)
        }
        Text(
            p.name,
            style = ProomType.cardTitle,
            color = ProomColor.text,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 7.dp).height(40.dp),
        )
        Text(
            metaOf(p),
            style = ProomType.label,
            color = ProomColor.muted,
            maxLines = 1,
        )
    }
}

/** 封面：方图 + 底部渐变（播放量压在图上，和片库卡片一个写法） */
@Composable
private fun CoverBox(p: NasApi.Playlist, width: Dp?) {
    val shape = RoundedCornerShape(ProomShape.md)
    val base = if (width != null) Modifier.width(width) else Modifier.fillMaxWidth()
    Box(base.aspectRatio(1f).clip(shape).background(ProomColor.card)) {
        if (p.cover.isNotBlank()) {
            RemoteImage(
                model = p.cover,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = TabIcons.Music,
                    contentDescription = null,
                    tint = ProomColor.lineStrong,
                    modifier = Modifier.size(30.dp),
                )
            }
        }
        Box(
            Modifier
                .matchParentSize()
                .background(
                    Brush.verticalGradient(
                        0.62f to Color.Transparent,
                        1f to Color.Black.copy(alpha = 0.62f),
                    )
                )
        )
        // 角标只放「多少首」：播放量在封面下面那行 meta 里已经有了，
        // 同一个数字在一张卡上出现两次是干扰
        Text(
            "${p.count} 首",
            color = Color.White.copy(alpha = 0.92f),
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium,
            // 放右上角：歌单封面几乎都是模板图、文字压在中间偏下，
            // 角标放左下会直接糊在人家的标题上
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(6.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(Color.Black.copy(alpha = 0.32f))
                .padding(horizontal = 5.dp, vertical = 2.dp),
        )
    }
}

/* ───────────────────── 骨架 / 提示 ───────────────────── */

/**
 * 没配音乐接口时的空态。
 *
 * 安装包不自带任何音乐源，首启必然是空的 —— 说清楚是「没配」而不是「坏了」，
 * 顺便告诉人去哪配。
 */
@Composable
private fun EmptyMusic() {
    Column(
        Modifier.fillMaxWidth().padding(top = 90.dp, start = 12.dp, end = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("在线音乐没配置", color = ProomColor.text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Text(
            "安装包不带任何音乐接口。\n在设置里填上聚合接口地址后，推荐歌单和分类才会出现。",
            color = ProomColor.dim,
            fontSize = 13.sp,
            lineHeight = 21.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 10.dp),
        )
    }
}

@Composable
private fun RecSkeleton(width: Dp) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth().bleed(20.dp),
    ) {
        items(count = 4) {
            Column(Modifier.width(width)) {
                SkeletonBlock(Modifier.fillMaxWidth().aspectRatio(1f), radius = ProomShape.md)
                SkeletonBlock(Modifier.fillMaxWidth(0.9f).padding(top = 10.dp), height = 11.dp)
                SkeletonBlock(Modifier.fillMaxWidth(0.5f).padding(top = 6.dp), height = 10.dp)
            }
        }
    }
}

@Composable
private fun PlSkeleton() {
    Column(Modifier.fillMaxWidth()) {
        SkeletonBlock(Modifier.fillMaxWidth().aspectRatio(1f), radius = ProomShape.md)
        SkeletonBlock(Modifier.fillMaxWidth(0.86f).padding(top = 10.dp), height = 11.dp)
        SkeletonBlock(Modifier.fillMaxWidth(0.5f).padding(top = 6.dp), height = 10.dp)
    }
}

@Composable
private fun ListHint(title: String, desc: String) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(44.dp).clip(CircleShape).background(ProomColor.surface),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = TabIcons.Music,
                contentDescription = null,
                tint = ProomColor.muted,
                modifier = Modifier.size(21.dp),
            )
        }
        Spacer(Modifier.height(12.dp))
        Text(title, style = ProomType.body, color = ProomColor.dim)
        if (desc.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(desc, style = ProomType.label, color = ProomColor.muted, maxLines = 2)
        }
    }
}

/* ───────────────────── 工具 ───────────────────── */

/**
 * 在带左右内边距的网格里重新铺满整宽。
 *
 * 和 PhoneFilm 的 `bleedHorizontal` 一个套路：只放宽约束、不偏移，
 * 横滑条要能从屏幕边缘滑出去 —— 否则第一张卡贴着 20dp 内边距，看着像被切了。
 */
private fun Modifier.bleed(pad: Dp): Modifier = this.layout { measurable, constraints ->
    val px = pad.roundToPx()
    val w = constraints.maxWidth + px * 2
    val placeable = measurable.measure(constraints.copy(minWidth = w, maxWidth = w))
    layout(placeable.width, placeable.height) { placeable.place(0, 0) }
}

private fun metaOf(p: NasApi.Playlist): String =
    if (p.play > 0) "${fmtPlay(p.play)} 次播放" else if (p.count > 0) "${p.count} 首" else ""

private fun fmtPlay(n: Long): String = when {
    n >= 100_000_000 -> {
        val v = (n / 10_000_000L).toDouble() / 10
        if (v % 1.0 == 0.0) "${v.toInt()} 亿" else "$v 亿"
    }
    n >= 10_000 -> "${n / 10_000} 万"
    else -> "$n"
}

/** 推荐歌单在缓存里的 key（分类 id 都是数字，不会撞） */
private const val REC_KEY = "__rec__"

/**
 * 歌单列表的进程内缓存。
 *
 * 只是省掉「切 tab 回来重新请求 + 闪骨架」的开销，5 分钟 TTL。
 */
private object MusicCache {
    private var cats: List<NasApi.MusicCatGroup> = emptyList()
    private var catsMark = kotlin.time.TimeSource.Monotonic.markNow()
    private val lists = mutableMapOf<String, Pair<List<NasApi.Playlist>, kotlin.time.TimeMark>>()

    fun cats(): List<NasApi.MusicCatGroup> =
        cats.takeIf { catsMark.elapsedNow() < 5.minutes }.orEmpty()

    fun putCats(v: List<NasApi.MusicCatGroup>): List<NasApi.MusicCatGroup> {
        cats = v
        catsMark = kotlin.time.TimeSource.Monotonic.markNow()
        return v
    }

    fun lists(cat: String): List<NasApi.Playlist>? =
        lists[cat]?.takeIf { it.second.elapsedNow() < 5.minutes }?.first

    fun putLists(cat: String, v: List<NasApi.Playlist>): List<NasApi.Playlist> {
        lists[cat] = v to kotlin.time.TimeSource.Monotonic.markNow()
        return v
    }

    /** 下拉刷新时清掉：不清的话三路全部命中内存，圈一闪就「刷完」了 */
    fun clear() {
        cats = emptyList()
        lists.clear()
    }
}

/**
 * 音乐页的「上次那一屏」（进程内）。
 *
 * 进歌单详情时整页会被销毁重建，`remember` 里的状态一起没了 ——
 * 于是滚到第八屏点开一张，返回直接回到顶部，用户得重新滚一遍找刚才看到的位置。
 *
 * 光记滚动坐标还不够：位置是按条目序号算的，重建首帧如果分类和卡片还没回来，
 * 网格条目数对不上，序号会被裁掉，数据一到再撑开，位置和布局就会明显动一下。
 * 所以选中分类和屏上那批卡片也一起记着，重建时首帧照原样画出来。
 */
private object MusicUiState {
    var index: Int = 0
    var offset: Int = 0
    /** 推荐歌单那一行的横向滚动（和上面两个一样，只为「返回还在原地」） */
    var recIndex: Int = 0
    var recOffset: Int = 0
    /** 上次选中的二级分类 id。找不到（分类树变了）时才退回默认的「语种 · 华语」 */
    var subId: String? = null
    /** 上次屏上那批卡片属于哪个分类、内容是什么：缓存过期也靠它把首帧填满 */
    var cardsCat: String? = null
    var cards: List<NasApi.Playlist> = emptyList()

    /** 按记下的分类 id 在大类树里定位；树变了找不到就返回 null */
    fun locate(cats: List<NasApi.MusicCatGroup>): Pair<Int, Int>? {
        val id = subId ?: return null
        cats.forEachIndexed { i, g ->
            val j = g.subs.indexOfFirst { it.id == id }
            if (j >= 0) return i to j
        }
        return null
    }
}
