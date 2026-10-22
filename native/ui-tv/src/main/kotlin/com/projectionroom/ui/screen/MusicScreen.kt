package com.projectionroom.ui.screen

import kotlinx.coroutines.delay
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.projectionroom.shared.catalog.NasApi
import com.projectionroom.ui.component.SkeletonBlock
import com.projectionroom.ui.component.TabIcons
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.ui.draw.scale
import com.projectionroom.ui.theme.ProomColor
import com.projectionroom.ui.theme.ProomShape
import com.projectionroom.ui.theme.ProomSpace
import com.projectionroom.ui.theme.ProomSp
import com.projectionroom.ui.theme.ProomType
import com.projectionroom.ui.theme.ProomMotion
import com.projectionroom.ui.theme.TvLayout
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
    /** 搜歌（和歌单同一个聚合接口） */
    searchTracks: suspend (String) -> List<NasApi.Track> = { emptyList() },
    /** 点搜索结果：把整份结果当队列交给播放器 */
    onPlayTracks: (List<NasApi.Track>, Int) -> Unit = { _, _ -> },
    /** 返回。音乐现在是一级 tab，电视上不再需要返回入口；保留给其它调用方 */
    onBack: (() -> Unit)? = null,
    topInset: androidx.compose.ui.unit.Dp = 0.dp,
    /** 一级导航（tab 条）。非空时顶栏由它接管，页面自己不再画返回 */
    nav: com.projectionroom.ui.component.TvRootNav? = null,
) {
    var cats by remember { mutableStateOf(MusicCache.cats()) }
    // 推荐区
    var rec by remember { mutableStateOf(MusicCache.lists(REC_KEY).orEmpty()) }
    // 分类区：一级下标 + 组内二级下标。默认落在「语种 · 华语」（最大众的一档）
    val initGi = remember(cats) {
        cats.indexOfFirst { it.name == "语种" }.takeIf { it >= 0 } ?: 0
    }
    var gi by remember(cats) { mutableIntStateOf(initGi) }
    var si by remember(cats) {
        mutableIntStateOf(
            cats.getOrNull(initGi)?.subs?.indexOfFirst { it.name == "华语" }?.takeIf { it >= 0 } ?: 0
        )
    }
    var cards by remember { mutableStateOf<List<NasApi.Playlist>>(emptyList()) }
    var listLoading by remember { mutableStateOf(false) }
    var listErr by remember { mutableStateOf<String?>(null) }

    // 搜索：顶栏最右那颗放大镜进来（页面通过 nav.withSearch 接管它）。
    // 交互和直播页一致 —— 整条顶栏让给输入框，内容区换成结果列表。
    var searching by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var hits by remember { mutableStateOf<List<NasApi.Track>>(emptyList()) }
    var hitLoading by remember { mutableStateOf(false) }
    var hitErr by remember { mutableStateOf<String?>(null) }
    var hitSearched by remember { mutableStateOf(false) }
    // 输入框「向下」把焦点交给第一条结果：否则遥控器打完字出不来
    val firstHitFr = remember { androidx.compose.ui.focus.FocusRequester() }

    // 防抖 400ms：曲库查询打在 NAS 上，逐字发会把结果刷成幻灯片
    LaunchedEffect(query) {
        val kw = query.trim()
        if (kw.isEmpty()) {
            hits = emptyList()
            hitSearched = false
            hitErr = null
            return@LaunchedEffect
        }
        delay(400)
        hitLoading = true
        hitErr = null
        runCatching { searchTracks(kw) }
            .onSuccess {
                hits = it
                hitSearched = true
            }
            .onFailure {
                hitErr = it.message
                hitSearched = true
            }
        hitLoading = false
    }

    val group = cats.getOrNull(gi)
    val subs = group?.subs.orEmpty()
    val sub = subs.getOrNull(si)

    LaunchedEffect(Unit) {
        if (MusicCache.cats().isNotEmpty()) return@LaunchedEffect
        runCatching { loadCats().collect { cats = MusicCache.putCats(it) } }
    }
    LaunchedEffect(Unit) {
        if (rec.isNotEmpty()) return@LaunchedEffect
        runCatching { loadPlaylists("").collect { rec = MusicCache.putLists(REC_KEY, it) } }
    }
    // 二级分类变化才重新拉。命中缓存立刻出内容，不闪骨架
    LaunchedEffect(sub?.id) {
        val id = sub?.id ?: return@LaunchedEffect
        listErr = null
        MusicCache.lists(id)?.let {
            cards = it
            listLoading = false
            return@LaunchedEffect
        }
        listLoading = true
        cards = emptyList()
        runCatching {
            loadPlaylists(id).collect { p ->
                // 第一发通常是磁盘缓存：立刻撤骨架，后面源站到了再替换
                cards = MusicCache.putLists(id, p)
                listLoading = false
            }
        }.onFailure { listErr = it.message }
        listLoading = false
    }


    BoxWithConstraints(Modifier.fillMaxSize().background(ProomColor.bg)) {
        // 和影视页同一档：手机 3 列（每张约 111dp）、横距 10dp。
        // 之前 2 列每张 170dp，一屏只看到 4 个歌单，信息密度太低
        val cols = (maxWidth / 150.dp).toInt().coerceIn(4, 8)
        // 卡片宽度从列数反算：写死 152dp 时 6 列会溢出 48dp，最右边那张永远被切一半
        // 可用宽度要扣掉左右两个安全区，否则最后一列会顶出屏幕（之前就是这么被切的）
        val recGap = 12.dp
        val recW = (maxWidth - TvLayout.insetX * 2 - recGap * (cols - 1)) / cols

        Column(Modifier.fillMaxSize()) {
        when {
            // 搜索态：整条顶栏让给输入框（和直播页同一套）
            searching -> Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = topInset)
                    .height(com.projectionroom.ui.component.TvPageHeaderSpec.height)
                    .padding(horizontal = com.projectionroom.ui.component.TvPageHeaderSpec.inset),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                com.projectionroom.ui.component.TvSearchField(
                    query = query,
                    onQuery = { query = it },
                    onClose = {
                        searching = false
                        query = ""
                    },
                    placeholder = "搜歌曲或歌手",
                    modifier = Modifier.weight(1f),
                    onDown = {
                        val ok = hits.isNotEmpty()
                        if (ok) firstHitFr.requestFocus()
                        ok
                    },
                )
            }
            // 一级 tab：顶栏就是全局 tab 条，只留导航。
            // 右侧不再挂「14 个推荐 · 5 类」这类统计，也不另开搜索入口 ——
            // 最右那颗放大镜由本页接管，点了就是搜歌。
            nav != null -> com.projectionroom.ui.component.TvRootHeader(
                nav = nav.withSearch { searching = true },
                topInset = topInset,
            )
            else -> com.projectionroom.ui.component.TvPageHeader(
                onBack = onBack,
                title = "音乐",
                topInset = topInset,
                trailing = { MusicCounts(recCount = rec.size, catCount = cats.size) },
            )
        }

        if (searching) {
            HitList(
                query = query,
                hits = hits,
                loading = hitLoading,
                err = hitErr,
                searched = hitSearched,
                firstFocus = firstHitFr,
                onPlay = { i -> onPlayTracks(hits, i) },
            )
            return@Column
        }

        LazyVerticalGrid(
            columns = GridCells.Fixed(cols),
            modifier = Modifier.fillMaxWidth().weight(1f),
            contentPadding = PaddingValues(
                start = com.projectionroom.ui.component.TvPageHeaderSpec.inset,
                end = com.projectionroom.ui.component.TvPageHeaderSpec.inset,
                // 顶栏是布局元素不是浮层：网格紧贴导航时，首屏的区块标题像贴在
                // 导航底边上，滚动后还会留半截卡片悬在文字下面。留 12dp 呼吸。
                top = 12.dp,
                bottom = ProomSpace.xxl,
            ),
            horizontalArrangement = Arrangement.spacedBy(recGap),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {

            // ── 推荐歌单 ──
            if (rec.isNotEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    SectionHead("推荐歌单", "编辑挑的，点开就是整张歌单")
                }
                item(span = { GridItemSpan(maxLineSpan) }) {
                    // 横滑要通栏：网格给了 20dp 内边距，这里抵消掉
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = TvLayout.insetX),
                        horizontalArrangement = Arrangement.spacedBy(recGap),
                        modifier = Modifier.bleed(TvLayout.insetX),
                    ) {
                        items(rec) { p -> RecCard(p, recW) { onOpenPlaylist(p) } }
                    }
                }
            } else if (cats.isEmpty()) {
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
                        gi = i
                        si = 0
                    }
                }
                item(span = { GridItemSpan(maxLineSpan) }) {
                    SubChips(subs.map { it.name }, si) { i -> si = i }
                }
            }

            when {
                listErr != null -> item(span = { GridItemSpan(maxLineSpan) }) {
                    ListHint("这个分类读取失败", listErr.orEmpty())
                }

                listLoading && cards.isEmpty() -> items(count = 6) { PlSkeleton() }

                cards.isEmpty() && subs.isNotEmpty() -> item(span = { GridItemSpan(maxLineSpan) }) {
                    ListHint("这个分类暂时没有歌单", "换一个分类看看")
                }

                else -> items(cards) { p -> PlCard(p) { onOpenPlaylist(p) } }
            }
        }
        }
    }
}

/* ───────────────────── 顶栏与标题 ───────────────────── */

/** 顶栏右侧的计数，标题本身由 TvPageHeader 画 */
@Composable
private fun MusicCounts(recCount: Int, catCount: Int) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        val sub = buildString {
            if (recCount > 0) append("${recCount} 个推荐")
            if (catCount > 0) {
                if (isNotEmpty()) append(" · ")
                append("$catCount 类")
            }
        }
        if (sub.isNotBlank()) {
            Text(
                sub,
                style = ProomType.meta,
                color = ProomColor.muted,
                maxLines = 1,
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
        modifier = Modifier.bleed(TvLayout.insetX).height(TvLayout.minTouch),
        contentPadding = PaddingValues(horizontal = TvLayout.insetX),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        itemsIndexed(names) { i, name ->
            val on = i == current
            var focused by remember { mutableStateOf(false) }
            Column(
                Modifier
                    .onFocusChanged { focused = it.isFocused }
                    .clickable { onSelect(i) }
                    .clip(RoundedCornerShape(ProomShape.sm))
                    // 底下那条金线只表示「选中」，焦点得另有说法：
                    // 否则光标停在这一行时，五个 tab 看起来和没焦点完全一样。
                    // 只加 surface2 底色还是太安静（深底上只差一档灰），补一圈白边。
                    .background(if (focused) ProomColor.surface2 else Color.Transparent)
                    .then(
                        if (focused) {
                            Modifier.border(2.dp, Color.White, RoundedCornerShape(ProomShape.sm))
                        } else Modifier
                    )
                    // 只有文字那么高的话，遥控器聚焦时看不出这一格的范围
                    .height(TvLayout.minTouch)
                    .padding(horizontal = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    name,
                    color = if (on) ProomColor.text else ProomColor.muted,
                    fontSize = ProomSp(15.sp),
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
        modifier = Modifier.bleed(TvLayout.insetX).height(TvLayout.minTouch),
        contentPadding = PaddingValues(horizontal = TvLayout.insetX),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        itemsIndexed(names) { i, name ->
            val on = i == current
            val shape = RoundedCornerShape(50)
            var focused by remember { mutableStateOf(false) }
            Box(
                Modifier
                    .height(TvLayout.minTouch)
                    .onFocusChanged { focused = it.isFocused }
                    .clip(shape)
                    .background(if (on) ProomColor.accentSoft else ProomColor.surface)
                    .then(
                        when {
                            // 选中是半透明金边，焦点必须是白的，不然两者混在一起
                            focused -> Modifier.border(
                                if (on) 3.dp else 2.dp,
                                Color.White,
                                shape,
                            )
                            on -> Modifier.border(1.dp, ProomColor.accent.copy(alpha = 0.55f), shape)
                            else -> Modifier
                        }
                    )
                    .clickable { onSelect(i) }
                    .padding(horizontal = 14.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    name,
                    fontSize = ProomSp(13.sp),
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

/**
 * 两行标题的占位高度。
 *
 * 电视字阶的 cardTitle 是 17sp/23dp 行高，两行 46dp —— 原来写死 40dp，
 * 第二行被裁掉半截（歌单名长一点就只剩上半截字）。
 */
@Composable
private fun cardTitleHeight(): Dp =
    with(LocalDensity.current) { ProomType.cardTitle.lineHeight.toDp() * 2 }

/** 推荐卡：横滑用，封面大一些 */
@Composable
private fun RecCard(p: NasApi.Playlist, width: Dp, onClick: () -> Unit) {
    // 横滑卡原来完全没有焦点态：遥控器走到哪张封面都看不出，只能靠右边标题
    // 被滚动的方向猜。和 PlCard / PosterCard 一样：放大 + 金边
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(ProomShape.md)
    val scale by animateFloatAsState(
        if (focused) ProomMotion.focusScaleTv else 1f,
        tween(ProomMotion.short),
        label = "rec-card",
    )
    Column(
        Modifier
            .width(width)
            .scale(scale)
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick),
    ) {
        Box {
            CoverBox(p, width)
            if (focused) {
                Box(Modifier.matchParentSize().clip(shape).border(3.dp, ProomColor.accent, shape))
            }
        }
        Text(
            p.name,
            style = ProomType.cardTitle,
            color = ProomColor.text,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 7.dp).height(cardTitleHeight()),
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
            if (focused && true) {
                Box(Modifier.matchParentSize().clip(shape).border(2.dp, ProomColor.accent, shape))
            }
        }
        Text(
            p.name,
            style = ProomType.cardTitle,
            color = ProomColor.text,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 7.dp).height(cardTitleHeight()),
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
            fontSize = ProomSp(11.sp),
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

@Composable
private fun RecSkeleton(width: Dp) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = TvLayout.insetX),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth().bleed(TvLayout.insetX),
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

/* ───────────────────── 搜索结果 ───────────────────── */

/**
 * 搜曲库的直出列表，只列单曲。
 *
 * 专辑结果故意不展示：曲库的专辑 id 打不开 App 里唯一的「歌单详情」，
 * 列出来就是点了没反应的那种死结果。
 */
@Composable
private fun HitList(
    query: String,
    hits: List<NasApi.Track>,
    loading: Boolean,
    err: String?,
    searched: Boolean,
    onPlay: (Int) -> Unit,
    firstFocus: androidx.compose.ui.focus.FocusRequester? = null,
    modifier: Modifier = Modifier.fillMaxSize(),
) {
    val inset = com.projectionroom.ui.component.TvPageHeaderSpec.inset
    val listState = rememberLazyListState()

    when {
        query.isBlank() -> HintBox("搜歌名或歌手", "打下第一个字就开始找", modifier)
        loading && hits.isEmpty() -> HintBox("正在搜「${query.trim()}」", "稍等一下", modifier)
        err != null -> HintBox("搜索失败", err, modifier)
        searched && hits.isEmpty() -> HintBox("没搜到「${query.trim()}」", "换个关键词试试", modifier)
        else -> LazyColumn(
            state = listState,
            modifier = modifier,
            contentPadding = PaddingValues(start = inset, end = inset, bottom = ProomSpace.xxl),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            item {
                Text(
                    "${hits.size} 首单曲 · 按确定键播放",
                    style = ProomType.meta,
                    color = ProomColor.muted,
                    modifier = Modifier.padding(vertical = 10.dp),
                )
            }
            itemsIndexed(hits, key = { i, t -> "$i-${t.id}" }) { i, t ->
                HitRow(
                    t = t,
                    modifier = if (i == 0 && firstFocus != null) Modifier.focusRequester(firstFocus)
                    else Modifier,
                    onClick = { onPlay(i) },
                )
            }
        }
    }
}

@Composable
private fun HintBox(title: String, sub: String, modifier: Modifier = Modifier) {
    Column(
        modifier
            .fillMaxSize()
            .padding(
                start = com.projectionroom.ui.component.TvPageHeaderSpec.inset,
                top = ProomSpace.xl,
            ),
    ) {
        Text(title, style = ProomType.body, color = ProomColor.text)
        if (sub.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(sub, style = ProomType.meta, color = ProomColor.muted)
        }
    }
}

/** 一行搜索结果。电视上没有 hover，焦点得自己画 —— 否则遥控器走着没反馈 */
@Composable
private fun HitRow(
    t: NasApi.Track,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick)
            .background(if (focused) ProomColor.surface2 else Color.Transparent)
            .border(
                1.dp,
                if (focused) ProomColor.accent.copy(alpha = 0.7f) else Color.Transparent,
                RoundedCornerShape(10.dp),
            )
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            t.title.ifBlank { "未知曲目" },
            style = ProomType.body,
            color = ProomColor.text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            // 歌名占满左侧，歌手钉在行尾：原来两个都是「包内容」宽，
            // 12dp 间距在电视上根本分不开，「Something's Going On A」
            // 读起来像歌名本身带了个尾巴
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(24.dp))
        Text(
            t.artist.ifBlank { "未知歌手" },
            style = ProomType.meta,
            color = ProomColor.muted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
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
}
