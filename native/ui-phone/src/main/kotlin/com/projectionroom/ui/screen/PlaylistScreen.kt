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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.projectionroom.shared.catalog.NasApi
import com.projectionroom.ui.component.PlayerIcons
import com.projectionroom.ui.component.SkeletonBlock
import com.projectionroom.ui.component.TabIcons
import com.projectionroom.ui.theme.ProomColor
import com.projectionroom.ui.theme.ProomShape
import com.projectionroom.ui.theme.ProomSpace
import com.projectionroom.ui.theme.ProomType
import com.projectionroom.ui.component.RemoteImage
import com.projectionroom.ui.music.PlaylistCache

private val BarH = 56.dp

/**
 * 歌单详情：沉浸式头图 + 曲目列表。
 *
 * 之前只有一块「封面 + 简介」，连返回键都没有（靠系统返回手势），
 * 页面上半截是纯黑 —— 和电影详情页那套沉浸式完全两个世界。
 *
 * 现在照电影详情页做：
 *  - 封面放大模糊铺顶，深色渐变落到底色；
 *  - 固定顶栏，滚动 120dp 内是透明渐变，之后转实底并浮出歌单名；
 *  - 头部是封面 + 标题 + 数量 + 简介，下面「播放全部 / 随机」两颗按钮；
 *  - 点某首歌不会跳走，只在底部弹迷你播放条（footer 由壳注入）。
 */
@Composable
fun PlaylistScreen(
    /** 歌单详情来源（App 直连，不经 NAS） */
    load: suspend (id: String) -> NasApi.PlaylistDetail,
    id: String,
    /** 状态栏高度：顶栏画在它下面，背景图通到屏幕最顶 */
    topInset: Dp = 0.dp,
    /** 正在播放的歌（id）：列表里高亮那一行 */
    nowPlayingId: String = "",
    isPlaying: Boolean = false,
    onPlayTrack: (detail: NasApi.PlaylistDetail, index: Int, shuffle: Boolean) -> Unit,
    onBack: () -> Unit = {},
    /** 底部常驻层（迷你播放条），由壳决定放什么 */
    footer: @Composable () -> Unit = {},
) {
    // 先看进程内缓存：从播放页返回、或者刚看过的歌单再点一次，
    // 有数据就直接出内容 —— 不再先闪一屏骨架
    var d by remember(id) { mutableStateOf(PlaylistCache.get(id)) }
    var loading by remember(id) { mutableStateOf(d == null) }
    var err by remember(id) { mutableStateOf<String?>(null) }
    // 重试代数：0 首时给一个「重新加载」的入口，不然用户只能退了再进
    var retry by remember(id) { mutableIntStateOf(0) }

    LaunchedEffect(id, retry) {
        // 只有手里没东西时才亮骨架
        if (d == null) loading = true
        err = null
        runCatching { load(id) }
            .onSuccess {
                // 空壳（id 对、曲目 0 条）不能当成「这个歌单是空的」：
                // 上游偶尔会返回 trackCount=20 但 tracks 为空的响应（版权受限、抽风），
                // 之前直接吃下就成了「外面 20 首、点进来 0 首」。
                if (it.tracks.isNotEmpty()) {
                    d = it
                    PlaylistCache.put(id, it)
                } else if (d == null) {
                    // 连 name 都没有 = 这次请求本身没通（断网、上游 502），
                    // 不能当成「歌单是空的」，更不能进失效名单 —— 否则网络抖一下，
                    // 一个好歌单就被列表永久藏起来了。
                    if (it.name.isBlank()) {
                        err = "接口没连上，检查一下网络"
                    } else {
                        err = "这个歌单暂时取不到曲目"
                        // 记一笔：回到列表后把它剔掉，别让人反复点进空页面
                        PlaylistCache.markDead(id)
                    }
                }
            }
            .onFailure {
                if (d == null) err = it.message ?: "网络有点问题"
            }
        loading = false
    }

    val detail = d
    val state = rememberLazyListState()
    // 滚动量：顶栏从透明渐变转实底，标题跟着浮出来
    val scrolled by remember {
        derivedStateOf { state.firstVisibleItemScrollOffset + (state.firstVisibleItemIndex * 200) }
    }
    val barAlpha = (scrolled / 150f).coerceIn(0f, 1f)
    val titleAlpha = ((scrolled - 70f) / 80f).coerceIn(0f, 1f)

    // 电视是 16:9 横屏、手机是竖屏：同一份内容两套排法。
    // 按宽高比判断而不是「是不是 TV」—— 手机横过来也该拿到宽屏版式。
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth > maxHeight * 1.15f
        val padX = if (wide) 32.dp else 20.dp
        val headerCover = if (wide) 176.dp else 132.dp

        Box(Modifier.fillMaxSize().background(ProomColor.bg)) {
            // ── 背景：整页一层封面色雾 ──
            // 不用 Modifier.blur：它在 API<31 是空操作，电视盒子上会退化成
            // 「280px 封面 Crop 拉大 7 倍」的图 —— 又糊又看得清封面构图，正是要避免的。
            // SoftBackdrop 只按 6dp 解码再由 GPU 放大，任何 API 上都是同一层软色雾，
            // 和首页 Hero / 影视详情页 / 音乐播放页是同一套写法。
            if (detail != null && detail.cover.isNotBlank()) {
                com.projectionroom.ui.component.SoftBackdrop(
                    model = detail.cover,
                    modifier = Modifier.fillMaxSize(),
                    decodeWidth = 26.dp,
                    brightness = 0.66f,
                )
                // 渐变要一路压到底色：曲目列表的白字直接压在色雾上，
                // 浅色封面（白底专辑图很常见）不打够暗根本读不出来
                Box(
                    Modifier.fillMaxSize().background(
                        Brush.verticalGradient(
                            0f to ProomColor.bg.copy(alpha = 0.46f),
                            0.30f to ProomColor.bg.copy(alpha = 0.70f),
                            0.62f to ProomColor.bg.copy(alpha = 0.92f),
                            1f to ProomColor.bg,
                        )
                    )
                )
            }

            when {
                loading || detail == null -> {
                    if (err != null) {
                        PlaylistError(
                            msg = err ?: "",
                            onBack = onBack,
                            onRetry = { retry += 1 },
                        )
                    } else {
                        PlaylistSkeleton(topInset, BarH, padX, wide)
                    }
                }

                else -> {
                    LazyColumn(
                        state = state,
                        modifier = Modifier.fillMaxSize(),
                        // 底部留白：迷你播放条 + 导航栏，否则最后几首会被压住
                        contentPadding = PaddingValues(bottom = 120.dp),
                    ) {
                        item { Spacer(Modifier.height(topInset + BarH + 8.dp)) }

                        item {
                            Row(Modifier.padding(start = padX, end = padX, top = 4.dp, bottom = 18.dp)) {
                                Box(
                                    Modifier
                                        .width(headerCover)
                                        .aspectRatio(1f)
                                        .clip(RoundedCornerShape(ProomShape.md))
                                        .background(ProomColor.card),
                                ) {
                                    if (detail.cover.isNotBlank()) {
                                        RemoteImage(
                                            model = detail.cover,
                                            contentDescription = null,
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier.fillMaxSize(),
                                        )
                                    }
                                }
                                Spacer(Modifier.width(if (wide) 22.dp else 16.dp))
                                Column(Modifier.weight(1f).padding(top = 2.dp)) {
                                    Text("歌单", style = ProomType.eyebrow, color = ProomColor.accent)
                                    Text(
                                        detail.name,
                                        color = ProomColor.text,
                                        fontSize = if (wide) 26.sp else 20.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.padding(top = 6.dp),
                                    )
                                    Text(
                                        "${detail.count} 首",
                                        style = ProomType.meta,
                                        color = ProomColor.muted,
                                        modifier = Modifier.padding(top = 6.dp),
                                    )
                                    if (detail.desc.isNotBlank()) {
                                        Text(
                                            detail.desc,
                                            style = ProomType.label,
                                            color = ProomColor.dim,
                                            // 横屏一行能放 60 个字，正文拉满整行会失去视线落点
                                            maxLines = if (wide) 2 else 3,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier
                                                .padding(top = 8.dp)
                                                .widthIn(max = if (wide) 680.dp else Dp.Unspecified),
                                        )
                                    }
                                }
                            }
                        }

                        // ── 播放全部 / 随机 ──
                        item {
                            Row(
                                Modifier.fillMaxWidth().padding(horizontal = padX, vertical = 4.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                // 横屏别用 weight(1f)：1920px 上一根通栏金条有一米多长，
                                // 中间只蹲一个播放三角，力距太长也看不出这是一组操作。
                                // 宽屏改回按钮自身宽度、左对齐并放大到 52dp 高（10-foot 视距）。
                                PlayAllButton(
                                    text = "播放全部",
                                    filled = true,
                                    modifier = if (wide) Modifier.width(180.dp) else Modifier.weight(1f),
                                    big = wide,
                                ) {
                                    onPlayTrack(detail, 0, false)
                                }
                                PlayAllButton(
                                    text = "随机",
                                    filled = false,
                                    modifier = Modifier.width(if (wide) 132.dp else 96.dp),
                                    // 空歌单点「随机」会抛 NoSuchElement：先判一下
                                    enabled = detail.tracks.isNotEmpty(),
                                    big = wide,
                                ) {
                                    onPlayTrack(detail, detail.tracks.indices.random(), true)
                                }
                            }
                        }

                        item {
                            Row(
                                Modifier.fillMaxWidth().padding(start = padX, end = padX, top = 18.dp, bottom = 6.dp),
                                verticalAlignment = Alignment.Bottom,
                            ) {
                                Text(
                                    "曲目",
                                    style = ProomType.title,
                                    fontSize = if (wide) 24.sp else 21.sp,
                                    color = ProomColor.text,
                                )
                                Text(
                                    "  ${detail.tracks.size}",
                                    style = ProomType.meta,
                                    color = ProomColor.muted,
                                    modifier = Modifier.padding(bottom = 3.dp),
                                )
                            }
                        }

                        // 宽屏两列：一列 960px 的曲目列表，一屏只能看到 8 首，
                        // 80 首的歌单要滚十屏。横屏拆成左右两列，一屏 16 首，
                        // 每行也更短、更好扫视。
                        if (wide) {
                            itemsIndexed(detail.tracks.chunked(2)) { rowIdx, pair ->
                                Row(Modifier.fillMaxWidth().padding(horizontal = padX - 12.dp)) {
                                    pair.forEachIndexed { col, t ->
                                        val i = rowIdx * 2 + col
                                        TrackRow(
                                            index = i + 1,
                                            track = t,
                                            current = t.id == nowPlayingId,
                                            playing = t.id == nowPlayingId && isPlaying,
                                            modifier = Modifier.weight(1f),
                                            big = true,
                                        ) { onPlayTrack(detail, i, false) }
                                    }
                                    // 最后一行只有一首时占位，别让那一行被拉宽
                                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                                }
                            }
                        } else {
                            itemsIndexed(detail.tracks) { i, t ->
                                TrackRow(
                                    index = i + 1,
                                    track = t,
                                    // 高亮：正在播的那首（暂停也保持高亮，只是不跳音符）
                                    current = t.id == nowPlayingId,
                                    playing = t.id == nowPlayingId && isPlaying,
                                ) { onPlayTrack(detail, i, false) }
                            }
                        }
                    }
                }
            }

            // ── 顶栏：透明 → 实底，标题随后浮现 ──
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(
                        if (barAlpha > 0.02f) SolidColor(ProomColor.bg.copy(alpha = barAlpha))
                        else Brush.verticalGradient(
                            0f to ProomColor.bg.copy(alpha = 0.42f),
                            1f to Color.Transparent,
                        )
                    )
                    .statusBarsPadding()
                    .height(BarH)
                    .padding(start = 4.dp, end = 12.dp),
            ) {
                Row(
                    Modifier.fillMaxSize(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // 统一规格：48dp 热区 + 24dp 图标（左缘 4 + 12 = 16dp）
                    // 电视端换成面包屑提示：返回交给遥控器 BACK 键，不占焦点位
                        Box(
                            Modifier.size(48.dp).clip(CircleShape).clickable(onClick = onBack),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = TabIcons.Back,
                                contentDescription = "返回",
                                tint = ProomColor.text,
                                modifier = Modifier.size(22.dp),
                            )
                        }
                    Text(
                        text = detail?.name.orEmpty(),
                        color = ProomColor.text,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .padding(start = 4.dp)
                            .graphicsLayer { alpha = titleAlpha },
                    )
                }
            }

            // ── 底部常驻层（迷你播放条）──
            Box(Modifier.align(Alignment.BottomCenter)) { footer() }
        }
    }
}

/* ───────────────────── 小组件 ───────────────────── */

@Composable
private fun PlayAllButton(
    text: String,
    filled: Boolean,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    /** 宽屏：52dp 高、15sp —— 电视三米外看 42dp/13sp 的按钮偏小 */
    big: Boolean = false,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(ProomShape.sm)
    Box(
        modifier
            .height(if (big) 52.dp else 42.dp)
            .clip(shape)
            .background(if (filled) ProomColor.accent else ProomColor.surface)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (filled) {
                Icon(
                    imageVector = PlayerIcons.Play,
                    contentDescription = null,
                    tint = Color(0xFF1B1206),
                    modifier = Modifier.size(if (big) 16.dp else 14.dp),
                )
                Spacer(Modifier.width(if (big) 8.dp else 6.dp))
            }
            Text(
                text,
                fontSize = if (big) 15.sp else 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (filled) Color(0xFF1B1206) else ProomColor.text,
            )
        }
    }
}

@Composable
private fun TrackRow(
    index: Int,
    track: NasApi.Track,
    current: Boolean,
    playing: Boolean,
    /** 两列网格时由外层给 weight */
    modifier: Modifier = Modifier,
    /** 宽屏：行更高、字号更大（10-foot 视距） */
    big: Boolean = false,
    onClick: () -> Unit,
) {
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 1.dp)
            .clip(RoundedCornerShape(ProomShape.sm))
            .background(if (current) ProomColor.accentSoft else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = if (big) 13.dp else 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(if (big) 38.dp else 32.dp), contentAlignment = Alignment.CenterStart) {
            if (current) {
                Text(
                    "♪",
                    color = if (playing) ProomColor.accent else ProomColor.accent.copy(alpha = 0.45f),
                    fontSize = 13.sp,
                )
            } else {
                Text(
                    index.toString().padStart(2, '0'),
                    style = ProomType.label,
                    color = ProomColor.muted,
                )
            }
        }
        Column(Modifier.weight(1f)) {
            Text(
                track.title,
                color = if (current) ProomColor.accent else ProomColor.text,
                fontSize = if (big) 15.sp else 14.sp,
                fontWeight = if (current) FontWeight.Medium else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                track.artist.ifBlank { "未知歌手" },
                style = ProomType.label,
                color = ProomColor.muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun PlaylistSkeleton(topInset: Dp, barH: Dp, padX: Dp, wide: Boolean) {
    Column(Modifier.fillMaxSize().padding(top = topInset + barH + 8.dp)) {
        Row(Modifier.padding(horizontal = padX)) {
            SkeletonBlock(
                Modifier.width(if (wide) 176.dp else 132.dp).aspectRatio(1f),
                radius = ProomShape.md,
            )
            Spacer(Modifier.width(if (wide) 22.dp else 16.dp))
            Column(Modifier.weight(1f)) {
                SkeletonBlock(Modifier.width(40.dp), height = 10.dp, radius = 5.dp)
                SkeletonBlock(
                    Modifier.fillMaxWidth(0.6f).padding(top = 10.dp),
                    height = if (wide) 26.dp else 20.dp,
                    radius = 6.dp,
                )
                SkeletonBlock(Modifier.fillMaxWidth(0.25f).padding(top = 8.dp), height = 11.dp, radius = 5.dp)
                SkeletonBlock(
                    Modifier.fillMaxWidth(if (wide) 0.7f else 1f).padding(top = 12.dp),
                    height = 10.dp,
                    radius = 5.dp,
                )
            }
        }
        // 按钮骨架也跟着版式走：宽屏是两个自然宽的按钮，不再是一根通栏条
        Row(
            Modifier.fillMaxWidth().padding(horizontal = padX, vertical = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SkeletonBlock(
                (if (wide) Modifier.width(180.dp) else Modifier.weight(1f))
                    .height(if (wide) 52.dp else 42.dp),
                radius = ProomShape.sm,
            )
            SkeletonBlock(
                Modifier.width(if (wide) 132.dp else 96.dp).height(if (wide) 52.dp else 42.dp),
                radius = ProomShape.sm,
            )
        }
        repeat(if (wide) 5 else 8) {
            Row(Modifier.fillMaxWidth().padding(horizontal = padX)) {
                repeat(if (wide) 2 else 1) {
                    SkeletonBlock(
                        Modifier.weight(1f).padding(horizontal = 6.dp, vertical = 6.dp).height(16.dp),
                        radius = 5.dp,
                    )
                }
            }
        }
    }
}

@Composable
private fun PlaylistError(msg: String, onBack: () -> Unit, onRetry: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            Modifier.size(46.dp).clip(CircleShape).background(ProomColor.surface),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = TabIcons.Music,
                contentDescription = null,
                tint = ProomColor.muted,
                modifier = Modifier.size(22.dp),
            )
        }
        Spacer(Modifier.height(14.dp))
        Text("歌单读取失败", style = ProomType.body, color = ProomColor.dim)
        Spacer(Modifier.height(6.dp))
        Text(msg, style = ProomType.label, color = ProomColor.muted, maxLines = 2)
        Spacer(Modifier.height(18.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            // 重试放前面：多数情况是这一次没拉到，重下一遍就有了
            Box(
                Modifier
                    .clip(RoundedCornerShape(50))
                    .background(ProomColor.accent)
                    .clickable(onClick = onRetry)
                    .padding(horizontal = 18.dp, vertical = 9.dp),
            ) {
                Text("重试", fontSize = 13.sp, color = ProomColor.bg, fontWeight = FontWeight.Medium)
            }
            Box(
                Modifier
                    .clip(RoundedCornerShape(50))
                    .background(ProomColor.surface)
                    .clickable(onClick = onBack)
                    .padding(horizontal = 18.dp, vertical = 9.dp),
            ) {
                Text("返回", fontSize = 13.sp, color = ProomColor.text)
            }
        }
    }
}
