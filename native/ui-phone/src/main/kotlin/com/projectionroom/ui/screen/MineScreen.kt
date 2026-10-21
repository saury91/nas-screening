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
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.projectionroom.shared.model.Vod
import com.projectionroom.ui.component.ChannelTile
import com.projectionroom.ui.component.PlayerIcons
import com.projectionroom.ui.component.PosterCard
import com.projectionroom.ui.component.TabIcons
import com.projectionroom.ui.theme.ProomColor
import com.projectionroom.ui.theme.ProomShape
import com.projectionroom.ui.theme.ProomSpace
import com.projectionroom.ui.theme.ProomType
import com.projectionroom.ui.component.RemoteImage

/** 「我的」的四个筛选档：全部 / 影视 / 直播 / 音乐 */
private enum class MineKind(val label: String) {
    All("全部"), Vod("影视"), Live("直播"), Music("音乐")
}

/**
 * 「我的」：收藏 + 播放记录，跨影视 / 直播 / 音乐三类。
 *
 * 三类内容形状不同（2:3 海报、16:9 台标块、1:1 专辑封面），
 * 所以「全部」时不是把它们混在同一条网格里 —— 那会排出一行里
 * 三种高度参差的卡片。改成按类各出一条横滑轨；选定单个类型时才用网格。
 *
 * 顶栏右侧是搜索和设置：数量、开关、NAS 地址这些都属于「配置」，
 * 留在页面上会把内容挤下去，所以统一收进独立设置页。
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun MineScreen(
    favorites: List<Vod>,
    history: List<Vod>,
    /** 直播收藏 / 最近观看 */
    channelFavs: List<MineChannel> = emptyList(),
    channelHistory: List<MineChannel> = emptyList(),
    /** 音乐收藏 / 播放记录 */
    trackFavs: List<MineTrack> = emptyList(),
    trackHistory: List<MineTrack> = emptyList(),
    loading: Boolean = false,
    onOpen: (Vod) -> Unit,
    onPlay: (Vod) -> Unit = {},
    onOpenChannel: (MineChannel) -> Unit = {},
    onPlayChannel: (MineChannel) -> Unit = {},
    /** 点某首歌：把整张列表交出去，从第 index 首开始播（和歌单页一致） */
    onPlayTrack: (List<MineTrack>, Int) -> Unit = { _, _ -> },
    onRefresh: () -> Unit = {},
    onSettings: () -> Unit = {},
) {
    var kind by remember { mutableStateOf(MineKind.All) }
    var searching by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    val q = query.trim()

    // 搜索只管名字，但会同时过滤三类 —— 用户不一定记得某个东西是"影视"还是"音乐"
    val vodFavs = remember(favorites, q) { favorites.filter { match(it.name, q) } }
    val vodHis = remember(history, q) { history.filter { match(it.name, q) } }
    val chFavs = remember(channelFavs, q) {
        channelFavs.filter { match(it.name, q) || match(it.group, q) }
    }
    val chHis = remember(channelHistory, q) {
        channelHistory.filter { match(it.name, q) || match(it.group, q) }
    }
    val tkFavs = remember(trackFavs, q) {
        trackFavs.filter { match(it.title, q) || match(it.artist, q) }
    }
    val tkHis = remember(trackHistory, q) {
        trackHistory.filter { match(it.title, q) || match(it.artist, q) }
    }

    val showVod = kind == MineKind.All || kind == MineKind.Vod
    val showLive = kind == MineKind.All || kind == MineKind.Live
    val showMusic = kind == MineKind.All || kind == MineKind.Music

    // 收藏/记录的总数（顶栏那行语境用，按当前筛选口径算）
    val favCount = (if (showVod) vodFavs.size else 0) +
        (if (showLive) chFavs.size else 0) + (if (showMusic) tkFavs.size else 0)
    val hisCount = (if (showVod) vodHis.size else 0) +
        (if (showLive) chHis.size else 0) + (if (showMusic) tkHis.size else 0)

    BoxWithConstraints(Modifier.fillMaxSize().background(ProomColor.bg)) {
        // 和片库/直播同一套：按 180dp 一列铺，手机竖屏落在 3 列
        val cols = (maxWidth / 180.dp).toInt().coerceIn(3, 8)

        Column(Modifier.fillMaxSize()) {
            MineHeader(
                favCount = favCount,
                hisCount = hisCount,
                loading = loading,
                searching = searching,
                query = query,
                onQuery = { query = it },
                onOpenSearch = { searching = true },
                onCloseSearch = {
                    searching = false
                    query = ""
                },
                onSettings = onSettings,
            )

            // 分类只占一行：三类内容混在一起时，找「上次那首歌」要翻穿 18 条影视
            if (!searching) {
                KindRail(
                    selected = kind,
                    counts = mapOf(
                        MineKind.All to favorites.size + channelFavs.size + trackFavs.size +
                            history.size + channelHistory.size + trackHistory.size,
                        MineKind.Vod to favorites.size + history.size,
                        MineKind.Live to channelFavs.size + channelHistory.size,
                        MineKind.Music to trackFavs.size + trackHistory.size,
                    ),
                    onSelect = { kind = it },
                )
            }

            androidx.compose.material3.pulltorefresh.PullToRefreshBox(
                isRefreshing = loading,
                onRefresh = onRefresh,
                modifier = Modifier.fillMaxSize().weight(1f),
            ) {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(cols),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = 20.dp, end = 20.dp, top = 6.dp, bottom = ProomSpace.xxl,
                    ),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                ) {
                    val nothing = favCount == 0 && hisCount == 0
                    if (nothing) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            EmptyHint(
                                loading = loading,
                                searching = q.isNotBlank(),
                                query = q,
                            )
                        }
                    }

                    /* ── 收藏 ── */
                    if (favCount > 0) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            SectionHead("收藏", "$favCount 项 · 点开继续看")
                        }
                        // 全部：三类各一条横滑轨（形状不同，不能混排到一条网格里）
                        if (kind == MineKind.All) {
                            if (vodFavs.isNotEmpty()) {
                                item(span = { GridItemSpan(maxLineSpan) }) {
                                    railOfVods("影视", vodFavs, onOpen)
                                }
                            }
                            if (chFavs.isNotEmpty()) {
                                item(span = { GridItemSpan(maxLineSpan) }) {
                                    railOfChannels("直播", chFavs, onOpenChannel)
                                }
                            }
                            if (tkFavs.isNotEmpty()) {
                                item(span = { GridItemSpan(maxLineSpan) }) {
                                    railOfTracks("音乐", tkFavs) { i -> onPlayTrack(tkFavs, i) }
                                }
                            }
                        } else {
                            // 单选某一类：用网格，一屏看更多
                            when (kind) {
                                MineKind.Vod -> items(vodFavs) { v ->
                                    PosterCard(
                                        title = v.name,
                                        meta = v.year.ifBlank { v.type },
                                        poster = v.pic,
                                        progress = v.progress,
                                        onClick = { onOpen(v) },
                                    )
                                }
                                MineKind.Live -> items(chFavs) { c ->
                                    ChannelTile(
                                        name = c.name,
                                        subtitle = c.group.ifBlank { "直播" },
                                        badge = if (c.urls.size > 1) "${c.urls.size} 源" else "",
                                        onClick = { onOpenChannel(c) },
                                    )
                                }
                                MineKind.Music -> itemsIndexed(tkFavs) { i, t ->
                                    TrackCard(t, onClick = { onPlayTrack(tkFavs, i) })
                                }
                                MineKind.All -> Unit
                            }
                        }
                    }

                    /* ── 播放记录 ── */
                    if (hisCount > 0) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            SectionHead("播放记录", "$hisCount 条 · 点右下角接着看", top = ProomSpace.sm)
                        }
                        if (showVod && vodHis.isNotEmpty()) {
                            if (kind == MineKind.All) {
                                item(span = { GridItemSpan(maxLineSpan) }) { TypeLabel("影视") }
                            }
                            vodHis.forEach { v ->
                                item(span = { GridItemSpan(maxLineSpan) }) {
                                    VodHistoryCard(
                                        v = v,
                                        onOpen = { onOpen(v) },
                                        onPlay = { onPlay(v) },
                                    )
                                }
                            }
                        }
                        if (showLive && chHis.isNotEmpty()) {
                            if (kind == MineKind.All) {
                                item(span = { GridItemSpan(maxLineSpan) }) { TypeLabel("直播") }
                            }
                            chHis.forEach { c ->
                                item(span = { GridItemSpan(maxLineSpan) }) {
                                    LiveHistoryCard(
                                        c = c,
                                        onOpen = { onOpenChannel(c) },
                                        onPlay = { onPlayChannel(c) },
                                    )
                                }
                            }
                        }
                        if (showMusic && tkHis.isNotEmpty()) {
                            if (kind == MineKind.All) {
                                item(span = { GridItemSpan(maxLineSpan) }) { TypeLabel("音乐") }
                            }
                            tkHis.forEachIndexed { i, t ->
                                item(span = { GridItemSpan(maxLineSpan) }) {
                                    TrackHistoryCard(t, onPlay = { onPlayTrack(tkHis, i) })
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 名字/分组名匹配：空查询视为全命中 */
private fun match(text: String, q: String): Boolean =
    q.isBlank() || text.contains(q, ignoreCase = true)

/* ───────────────────────── 顶栏 ───────────────────────── */

@Composable
private fun MineHeader(
    favCount: Int,
    hisCount: Int,
    loading: Boolean,
    searching: Boolean,
    query: String,
    onQuery: (String) -> Unit,
    onOpenSearch: () -> Unit,
    onCloseSearch: () -> Unit,
    onSettings: () -> Unit,
) {
    val h = 56.dp
    Row(
        Modifier.fillMaxWidth().height(h).padding(start = 20.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (searching) {
            MineSearchField(query, onQuery, onCloseSearch, Modifier.weight(1f))
            return@Row
        }
        Box(Modifier.size(6.dp).clip(CircleShape).background(ProomColor.accent))
        Spacer(Modifier.width(8.dp))
        Text(
            "我的",
            color = ProomColor.text,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.2.sp,
        )
        if (favCount + hisCount > 0) {
            Text(
                "收藏 $favCount · 记录 $hisCount",
                style = ProomType.meta,
                color = ProomColor.muted,
                maxLines = 1,
                modifier = Modifier.padding(start = 10.dp),
            )
        }
        Spacer(Modifier.weight(1f))
        if (loading) {
            Text("同步中…", style = ProomType.label, color = ProomColor.accent)
            Spacer(Modifier.width(6.dp))
        }
        HeaderIcon(TabIcons.Search, "搜索收藏和记录", onOpenSearch)
        HeaderIcon(TabIcons.Settings, "设置", onSettings)
    }
}

@Composable
private fun HeaderIcon(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    desc: String,
    onClick: () -> Unit,
) {
    Box(
        Modifier.size(40.dp).clip(CircleShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = desc, tint = ProomColor.text, modifier = Modifier.size(21.dp))
    }
}

@Composable
private fun MineSearchField(
    query: String,
    onQuery: (String) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val fr = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) {
        fr.requestFocus()
        keyboard?.show()
    }
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = TabIcons.Search,
            contentDescription = null,
            tint = ProomColor.muted,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(10.dp))
        BasicTextField(
            value = query,
            onValueChange = onQuery,
            singleLine = true,
            textStyle = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Medium, color = ProomColor.text),
            cursorBrush = SolidColor(ProomColor.accent),
            modifier = Modifier.weight(1f).focusRequester(fr),
            decorationBox = { inner ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (query.isEmpty()) {
                        Text("搜收藏或记录里的片名/歌名", style = ProomType.body, color = ProomColor.muted)
                    }
                    inner()
                }
            },
        )
        Box(
            Modifier.size(36.dp).clip(CircleShape).clickable(onClick = onClose),
            contentAlignment = Alignment.Center,
        ) {
            Icon(TabIcons.Close, "关闭搜索", tint = ProomColor.muted, modifier = Modifier.size(18.dp))
        }
    }
}

/** 分类横滑：全部 / 影视 / 直播 / 音乐，带数量语境 */
@Composable
private fun KindRail(
    selected: MineKind,
    counts: Map<MineKind, Int>,
    onSelect: (MineKind) -> Unit,
) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
    ) {
        items(MineKind.entries.toList()) { k ->
            val on = k == selected
            val n = counts[k] ?: 0
            Row(
                Modifier
                    .clip(RoundedCornerShape(50))
                    .background(if (on) ProomColor.accentSoft else ProomColor.surface)
                    .clickable { onSelect(k) }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    k.label,
                    style = ProomType.label,
                    color = if (on) ProomColor.accent else ProomColor.dim,
                )
                if (n > 0) {
                    Text(
                        " $n",
                        style = ProomType.label,
                        color = if (on) ProomColor.accent.copy(alpha = 0.7f) else ProomColor.muted,
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionHead(title: String, sub: String, top: Dp = 0.dp) {
    Row(
        Modifier.fillMaxWidth().padding(top = top, bottom = ProomSpace.xs),
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

/** 「全部」模式下每类内容前面的小标签，11sp，弱化到不抢标题 */
@Composable
private fun TypeLabel(text: String) {
    Text(
        text,
        style = ProomType.label,
        color = ProomColor.muted,
        modifier = Modifier.padding(top = ProomSpace.sm, bottom = 2.dp),
    )
}

/* ───────────────────────── 横向轨（全部模式） ───────────────────────── */

@Composable
private fun RailHead(title: String, count: Int) {
    Row(Modifier.fillMaxWidth().padding(top = ProomSpace.sm, bottom = 8.dp), verticalAlignment = Alignment.Bottom) {
        Text(title, style = ProomType.cardTitle, color = ProomColor.dim)
        Spacer(Modifier.width(6.dp))
        Text("$count", style = ProomType.label, color = ProomColor.muted)
    }
}

@Composable
private fun railOfVods(title: String, list: List<Vod>, onOpen: (Vod) -> Unit) {
    Column {
        RailHead(title, list.size)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(list) { v ->
                Box(Modifier.width(104.dp)) {
                    PosterCard(
                        title = v.name,
                        meta = v.year.ifBlank { v.type },
                        poster = v.pic,
                        onClick = { onOpen(v) },
                    )
                }
            }
        }
    }
}

@Composable
private fun railOfChannels(title: String, list: List<MineChannel>, onOpen: (MineChannel) -> Unit) {
    Column {
        RailHead(title, list.size)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(list) { c ->
                Box(Modifier.width(150.dp)) {
                    ChannelTile(
                        name = c.name,
                        subtitle = c.group.ifBlank { "直播" },
                        onClick = { onOpen(c) },
                    )
                }
            }
        }
    }
}

@Composable
private fun railOfTracks(title: String, list: List<MineTrack>, onPlay: (Int) -> Unit) {
    Column {
        RailHead(title, list.size)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            itemsIndexed(list) { i, t ->
                Box(Modifier.width(104.dp)) { TrackCard(t, onClick = { onPlay(i) }) }
            }
        }
    }
}

/* ───────────────────────── 卡片 ───────────────────────── */

/** 专辑卡：1:1 封面 + 歌名 + 歌手 */
@Composable
private fun TrackCard(t: MineTrack, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(ProomShape.md))
                .background(ProomColor.card),
        ) {
            Box(
                Modifier.fillMaxSize().background(
                    Brush.linearGradient(listOf(ProomColor.card, ProomColor.bgSoft))
                ),
                contentAlignment = Alignment.Center,
            ) {
                Text(t.title.take(1), style = ProomType.body, color = ProomColor.lineStrong)
            }
            if (t.cover.isNotBlank()) {
                RemoteImage(
                    model = t.cover,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        Text(
            t.title,
            style = ProomType.cardTitle,
            color = ProomColor.text,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 7.dp).height(40.dp),
        )
        Text(
            t.artist,
            style = ProomType.label,
            color = ProomColor.muted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 影视记录卡：2:3 小海报 + 进度线贴底 */
@Composable
private fun VodHistoryCard(v: Vod, onOpen: () -> Unit, onPlay: () -> Unit) {
    val p = v.progress.coerceIn(0f, 1f)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(ProomShape.sm))
            .background(ProomColor.surface)
            .clickable(onClick = onOpen)
    ) {
        Row(
            Modifier.padding(start = 10.dp, end = 10.dp, top = 10.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PosterThumb(v.pic, v.name, Modifier.width(46.dp), 2f / 3.05f)
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(v.name, style = ProomType.cardTitle, color = ProomColor.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(Modifier.padding(top = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (v.type.isBlank()) "上次看到这里" else "看到 ${v.type}",
                        style = ProomType.label, color = ProomColor.muted,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    PercentText(p)
                }
            }
            PlayButton("接着看", onPlay)
        }
        ProgressLine(p)
    }
}

/** 直播记录卡：16:9 小块 + 台名 + 分组 */
@Composable
private fun LiveHistoryCard(c: MineChannel, onOpen: () -> Unit, onPlay: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(ProomShape.sm))
            .background(ProomColor.surface)
            .clickable(onClick = onOpen)
    ) {
        Row(
            Modifier.padding(start = 10.dp, end = 10.dp, top = 10.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .width(64.dp)
                    .aspectRatio(16f / 9f)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Brush.linearGradient(listOf(Color(0xFF191D26), Color(0xFF0D0F14)))),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    com.projectionroom.ui.component.channelMark(c.name),
                    color = Color.White.copy(alpha = 0.16f),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                )
            }
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(c.name, style = ProomType.cardTitle, color = ProomColor.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    c.group.ifBlank { "直播" },
                    style = ProomType.label, color = ProomColor.muted,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 3.dp),
                )
            }
            PlayButton("继续看", onPlay)
        }
        // 直播没有进度，但底边留一条线，卡片高度和影视记录一致
        ProgressLine(0f)
    }
}

/** 音乐记录卡：1:1 小封面 + 歌名 + 歌手 */
@Composable
private fun TrackHistoryCard(t: MineTrack, onPlay: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(ProomShape.sm))
            .background(ProomColor.surface)
            .clickable(onClick = onPlay)
    ) {
        Row(
            Modifier.padding(start = 10.dp, end = 10.dp, top = 10.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PosterThumb(t.cover, t.title, Modifier.width(46.dp), 1f)
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(t.title, style = ProomType.cardTitle, color = ProomColor.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    t.artist.ifBlank { "未知歌手" },
                    style = ProomType.label, color = ProomColor.muted,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 3.dp),
                )
            }
            PlayButton("播放", onPlay)
        }
        ProgressLine(0f)
    }
}

@Composable
private fun PercentText(p: Float) {
    val pct = (p * 100).toInt()
    when {
        pct >= 99 -> Text(" · 已看完", style = ProomType.label, color = ProomColor.ok.copy(alpha = 0.85f))
        pct >= 1 -> Text(" · $pct%", style = ProomType.label, color = ProomColor.accent.copy(alpha = 0.75f))
    }
}

@Composable
private fun PlayButton(desc: String, onClick: () -> Unit) {
    Box(
        Modifier.size(34.dp).clip(CircleShape).background(ProomColor.accent).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = PlayerIcons.Play,
            contentDescription = desc,
            tint = Color(0xFF1B1206),
            modifier = Modifier.size(15.dp),
        )
    }
}

/** 2dp 进度线，贴卡片底边：贴在内容里会把行高压成两层 */
@Composable
private fun ProgressLine(p: Float) {
    Box(Modifier.fillMaxWidth().height(2.dp).background(ProomColor.line)) {
        if (p > 0f) Box(Modifier.fillMaxWidth(p.coerceIn(0f, 1f)).height(2.dp).background(ProomColor.accent))
    }
}

/** 小海报/小封面：图没来或加载失败时用「首字 + 渐变」垫底，不是纯黑洞 */
@Composable
private fun PosterThumb(pic: String, name: String, modifier: Modifier, ratio: Float) {
    Box(
        modifier
            .aspectRatio(ratio)
            .clip(RoundedCornerShape(6.dp))
            .background(ProomColor.card)
    ) {
        Box(
            Modifier.fillMaxSize().background(
                Brush.linearGradient(listOf(ProomColor.card, ProomColor.bgSoft))
            ),
            contentAlignment = Alignment.Center,
        ) {
            Text(name.take(1), style = ProomType.label, color = ProomColor.lineStrong)
        }
        if (pic.isNotBlank()) {
            RemoteImage(
                model = pic,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/* ───────────────────────── 空态 ───────────────────────── */

@Composable
private fun EmptyHint(loading: Boolean, searching: Boolean, query: String) {
    Column(
        Modifier.fillMaxWidth().padding(top = 56.dp, bottom = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(52.dp).clip(CircleShape).background(ProomColor.surface),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = if (searching) TabIcons.Search else TabIcons.Heart,
                contentDescription = null,
                tint = ProomColor.muted,
                modifier = Modifier.size(22.dp),
            )
        }
        Text(
            when {
                searching -> "没有匹配「$query」的内容"
                loading -> "正在同步收藏…"
                else -> "这里还是空的"
            },
            style = ProomType.title,
            color = ProomColor.text,
            modifier = Modifier.padding(top = ProomSpace.md),
        )
        Text(
            if (searching) {
                "换个关键词试试，收藏和播放记录会一起搜"
            } else {
                "影视在详情页点心形收藏；直播在播放页右上角收藏；音乐在播放器里点心形"
            },
            style = ProomType.meta,
            color = ProomColor.muted,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 6.dp, start = 24.dp, end = 24.dp),
        )
    }
}
