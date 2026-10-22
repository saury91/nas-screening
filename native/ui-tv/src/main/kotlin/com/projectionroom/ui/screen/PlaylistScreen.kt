package com.projectionroom.ui.screen

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.projectionroom.shared.catalog.NasApi
import com.projectionroom.ui.component.PlayerIcons
import com.projectionroom.ui.component.SkeletonBlock
import com.projectionroom.ui.component.TabIcons
import com.projectionroom.ui.component.TvPageHeader
import com.projectionroom.ui.component.TvPageHeaderSpec
import com.projectionroom.ui.component.TvPillButton
import com.projectionroom.ui.theme.ProomColor
import com.projectionroom.ui.theme.ProomMotion
import com.projectionroom.ui.theme.ProomShape
import com.projectionroom.ui.theme.ProomSpace
import com.projectionroom.ui.theme.ProomType
import com.projectionroom.ui.theme.TvLayout
import com.projectionroom.ui.component.RemoteImage
import com.projectionroom.ui.music.PlaylistCache
import kotlinx.coroutines.delay


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
    /**
     * 进全屏播放页。底部迷你条的封面在手机上能点，但遥控器要按到列表最后一首
     * 之外才够得着它（LazyColumn 会一路把焦点交给下一行）—— 等于没有入口。
     * 所以在顶栏右侧补一颗「播放中」胶囊，从「播放全部」往上一下就到。
     */
    onOpenPlayer: (() -> Unit)? = null,
    /** 底部常驻层（迷你播放条），由壳决定放什么 */
    footer: @Composable () -> Unit = {},
) {
    // 先看进程内缓存：从播放页返回、或者刚看过的歌单再点一次，
    // 有数据就直接出内容 —— 不再先闪一屏骨架
    var d by remember(id) { mutableStateOf(PlaylistCache.get(id)) }
    var loading by remember(id) { mutableStateOf(d == null) }
    var err by remember(id) { mutableStateOf<String?>(null) }

    LaunchedEffect(id) {
        // 只有手里没东西时才亮骨架
        if (d == null) loading = true
        err = null
        runCatching { load(id) }
            .onSuccess {
                // 拿回来的空壳（id 对、曲目 0 条）不覆盖已有内容
                if (it.tracks.isNotEmpty() || d == null) {
                    d = it
                    PlaylistCache.put(id, it)
                }
            }
            .onFailure { if (d == null) err = it.message }
        loading = false
    }

    val detail = d
    // 歌单简介是上游 CMS 的原文，常带硬换行和上游自己截断留下的「...」。
    // 原样丢给 2 行的 Text，第二行会单独渲染出一行省略号，看着像排版坏了。
    val descText = remember(detail?.desc) {
        detail?.desc.orEmpty().lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString(" ")
    }
    val state = rememberLazyListState()
    // 滚动量：顶栏从透明渐变转实底，标题跟着浮出来
    val scrolled by remember {
        derivedStateOf { state.firstVisibleItemScrollOffset + (state.firstVisibleItemIndex * 200) }
    }
    val barAlpha = (scrolled / 150f).coerceIn(0f, 1f)
    val titleAlpha = ((scrolled - 70f) / 80f).coerceIn(0f, 1f)

    // 进页必须已经有焦点：遥控器进来第一下 OK 应该是「播放全部」，
    // 而不是完全没反应（后台看着什么都没选中，用户以为界面卡住）。
    // 内容在 LazyColumn 里，首帧前节点还没测量，requestFocus 会静默失败，所以要重试。
    val firstAction = remember { FocusRequester() }
    LaunchedEffect(detail == null) {
        if (detail == null) return@LaunchedEffect
        // 必须等首帧：测量之前 requestFocus 也能成功，但那一格还没进 LazyColumn
        // 的可见区，系统会为了「把它露出来」把列表滚到这一格顶部 ——
        // 结果一进页封面就被顶掉半张、顶栏标题已经浮出来。
        withFrameNanos { }
        repeat(12) {
            if (runCatching { firstAction.requestFocus() }.isSuccess) {
                // 兜底：真被滚走了就压回顶部（聚焦节点仍在屏内，焦点不会丢）。
                // 要连压几帧 —— 「把焦点格滚进视野」是在焦点事件之后的布局里才发生的，
                // 当场压一次会被它再顶回去。
                repeat(4) {
                    withFrameNanos { }
                    state.scrollToItem(0)
                }
                return@LaunchedEffect
            }
            delay(40)
        }
    }

    // 电视是 16:9 横屏、手机是竖屏：同一份内容两套排法。
    // 按宽高比判断而不是「是不是 TV」—— 手机横过来也该拿到宽屏版式。
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth > maxHeight * 1.15f
        // 电视 960×540dp 画布：左右留 48dp 安全区（原来 32dp 在带过扫描的电视上会贴边）
        val padX = if (wide) TvLayout.insetX else 20.dp
        // 封面不超过屏高 1/4：176dp 在 540dp 高的画布上占 1/3，头部一压，一屏只剩两三首
        val headerCover = if (wide) 148.dp else 132.dp

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
                        PlaylistError(err ?: "") { onBack() }
                    } else {
                        PlaylistSkeleton(topInset, TvPageHeaderSpec.height, padX, wide)
                    }
                }

                else -> {
                    LazyColumn(
                        state = state,
                        modifier = Modifier.fillMaxSize(),
                        // 底部留白：迷你播放条 + 导航栏，否则最后几首会被压住
                        contentPadding = PaddingValues(bottom = 120.dp),
                    ) {
                        // 顶栏高度 + 12dp 间隙：贴死在顶栏下沿时，滚动裁切的那半张封面
                        // 会正好咬住标题基线，看起来像标题压在图上
                        item { Spacer(Modifier.height(topInset + TvPageHeaderSpec.height + 12.dp)) }

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
                                    Text(
                                        "歌单",
                                        style = ProomType.eyebrow,
                                        color = ProomColor.accent,
                                    )
                                    Text(
                                        detail.name,
                                        style = ProomType.headline,
                                        color = ProomColor.text,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.padding(top = 4.dp),
                                    )
                                    Text(
                                        "${detail.count} 首",
                                        style = ProomType.meta,
                                        color = ProomColor.muted,
                                        modifier = Modifier.padding(top = 4.dp),
                                    )
                                    if (descText.isNotBlank()) {
                                        Text(
                                            descText,
                                            style = ProomType.body,
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
                                    // 进页焦点落在这颗主操作上
                                    focusRequester = firstAction,
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

            // ── 顶栏：透明 → 实底，标题随后浮现（几何规格与其它页一致）──
            Column(
                Modifier
                    .fillMaxWidth()
                    // 底色必须比顶栏本身再往下淡出 32dp，不能是一条硬边：
                    // 滚动时「播放全部」「曲目」这些标题会正好被顶栏下沿横切一刀，
                    // 半个字露在外面，看起来像布局错位。硬边改渐变后是「淡出画面」。
                    .drawBehind {
                        val extra = 32.dp.toPx()
                        val solid = 0.42f + 0.58f * barAlpha
                        drawRect(
                            brush = Brush.verticalGradient(
                                // 顶栏高度以内必须基本不透明：滚动经过的「播放全部」「曲目」
                                // 是在这里被挡掉的，淡出只发生在顶栏下沿之外的那 32dp
                                0f to ProomColor.bg.copy(alpha = solid),
                                0.66f to ProomColor.bg.copy(alpha = solid * 0.98f),
                                1f to Color.Transparent,
                                endY = size.height + extra,
                            ),
                            size = Size(size.width, size.height + extra),
                        )
                    },
                // 状态栏高度只从 topInset 进来一次：这里再叠一层 statusBarsPadding，
                // 在画出状态栏的壳上就是双份内边距，标题会比内容低 24dp
            ) {
                TvPageHeader(
                    onBack = onBack,
                    title = detail?.name.orEmpty().ifBlank { null },
                    titleAlpha = titleAlpha,
                    topInset = topInset,
                    // 和内容同一条左边距，否则返回箭头比封面少缩进 8dp
                    inset = padX,
                    trailing = if (onOpenPlayer != null && nowPlayingId.isNotBlank()) {
                        { TvPillButton("播放中", onOpenPlayer) }
                    } else {
                        null
                    },
                )
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
    /** 宽屏：48dp 高 —— 电视三米外看 42dp 的按钮偏小，但再高就会把头部撑开 */
    big: Boolean = false,
    /** 进页落焦点用；只有「播放全部」会给 */
    focusRequester: FocusRequester? = null,
    onClick: () -> Unit,
) {
    // 原来这两颗按钮能聚焦但没有任何视觉变化：遥控器停在「播放全部」上
    // 和停在一首曲目上看起来一样，只能靠猜。和详情页的主按钮同一套反馈。
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        if (focused) ProomMotion.focusScaleTv else 1f,
        tween(ProomMotion.short, easing = ProomMotion.ease),
        label = "play-all",
    )
    val shape = RoundedCornerShape(ProomShape.sm)
    Box(
        modifier
            // 48dp 高在一屏只有 540dp 的画布上占 1/11：够 3 米视距点按，又不抢标题的位置
            .height(if (big) 48.dp else 42.dp)
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .scale(scale)
            .clip(shape)
            .background(
                when {
                    // 金底 + 白环在电视上分辨不出来（和首页、详情页统一）：
                    // 主按钮聚焦时整块翻白，文字保持深色，一眼确认按下去会播
                    filled && focused -> Color.White
                    filled -> ProomColor.accent
                    focused -> ProomColor.surface2
                    else -> ProomColor.surface
                }
            )
            .then(
                if (focused) {
                    Modifier.border(if (filled) 3.dp else 2.dp, if (filled) Color.White else ProomColor.accent, shape)
                } else {
                    Modifier
                }
            )
            .onFocusChanged { focused = it.isFocused }
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (filled) {
                Icon(
                    imageVector = PlayerIcons.Play,
                    contentDescription = null,
                    tint = Color(0xFF1B1206),
                    modifier = Modifier.size(if (big) 18.dp else 14.dp),
                )
                Spacer(Modifier.width(if (big) 8.dp else 6.dp))
            }
            Text(
                text,
                style = ProomType.meta,
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
    // 曲目行是这一页唯一的高频操作目标，却只有「正在播放」的底色、没有焦点态：
    // 遥控器在 400 首歌里上下走，屏幕上毫无位移反馈。底色提亮 + 描边（不放大，
    // 两列布局里一放大就压到邻列的曲名上）。
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(ProomShape.sm)
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 1.dp)
            .clip(shape)
            .background(
                when {
                    focused -> ProomColor.surface2
                    current -> ProomColor.accentSoft
                    else -> Color.Transparent
                }
            )
            .then(
                if (focused) {
                    // 正在播的那行已经有金色语义，焦点再用金色会分不清，改用白环
                    Modifier.border(
                        if (current) 3.dp else 2.dp,
                        if (current) Color.White else ProomColor.accent,
                        shape,
                    )
                } else {
                    Modifier
                }
            )
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick)
            // 行高 ~66dp：一屏 4 行（两列 8 首），比 56dp 的行更好按，也不至于一屏只剩 2 行
            .padding(horizontal = 10.dp, vertical = if (big) 11.dp else 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(if (big) 36.dp else 32.dp), contentAlignment = Alignment.CenterStart) {
            if (current) {
                Text(
                    "♪",
                    style = ProomType.label,
                    color = if (playing) ProomColor.accent else ProomColor.accent.copy(alpha = 0.45f),
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
                style = ProomType.cardTitle,
                fontWeight = if (current) FontWeight.Medium else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                track.artist.ifBlank { "未知歌手" },
                style = ProomType.meta,
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
private fun PlaylistError(msg: String, onBack: () -> Unit) {
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
        // 错误页往往是最需要遥控器操作的一屏（读不出来 → 只能退出），
        // 这颗「返回」原来同样没有焦点态
        var focused by remember { mutableStateOf(false) }
        val shape = RoundedCornerShape(50)
        Box(
            Modifier
                .heightIn(min = TvLayout.minTouch)
                .clip(shape)
                .background(if (focused) ProomColor.surface2 else ProomColor.surface)
                .then(if (focused) Modifier.border(2.dp, ProomColor.accent, shape) else Modifier)
                .onFocusChanged { focused = it.isFocused }
                .clickable(onClick = onBack)
                .padding(horizontal = 18.dp, vertical = 9.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text("返回", style = ProomType.meta, color = ProomColor.text)
        }
    }
}
