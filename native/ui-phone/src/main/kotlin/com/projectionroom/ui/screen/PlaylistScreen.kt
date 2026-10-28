package com.projectionroom.ui.screen

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import com.projectionroom.ui.music.MusicShelf
import com.projectionroom.ui.music.PlaylistCache
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val BarH = 56.dp

/**
 * 磁盘上那份歌单：`at` 是写入时间，页面据此决定「直接用」还是「先上屏再静默刷新」。
 *
 * 这里只放一个纯数据类，不让 ui 模块去依赖 data 模块的缓存实现 ——
 * 具体读哪个文件由壳（MainActivity）注入。
 */
class CachedPlaylist(val at: Long, val detail: NasApi.PlaylistDetail)

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
    /** 磁盘缓存读取：冷启动也能先上屏，不用对着骨架屏等上游那十来秒 */
    readCache: suspend (id: String) -> CachedPlaylist? = { null },
    /** 磁盘缓存写入：拉到了就存一份 */
    writeCache: suspend (id: String, detail: NasApi.PlaylistDetail) -> Unit = { _, _ -> },
    id: String,
    /** 状态栏高度：顶栏画在它下面，背景图通到屏幕最顶 */
    topInset: Dp = 0.dp,
    /** 正在播放的歌（id）：列表里高亮那一行 */
    nowPlayingId: String = "",
    isPlaying: Boolean = false,
    onPlayTrack: (detail: NasApi.PlaylistDetail, index: Int, shuffle: Boolean) -> Unit,
    /** 这首歌是否已被收进某个歌单（默认收藏歌单或自建歌单），决定心形亮不亮 */
    isTrackFav: (NasApi.Track) -> Boolean = { false },
    /**
     * 点曲目行右边的心形 / 长按整行：弹「收藏到歌单」。
     *
     * 不做「点一下就塞进默认歌单」：收藏是个有归属的动作，进哪个歌单要当场能选，
     * 弹层里也能新建一个。
     */
    onAddToPlaylist: ((NasApi.Track) -> Unit)? = null,
    /** 歌单本身是否已收藏；null = 不显示这颗心（自建歌单页） */
    fav: Boolean? = null,
    onToggleFav: (() -> Unit)? = null,
    /**
     * 本地歌单（自建 / 我喜欢的音乐）：不走网络、不写缓存，改一次靠 [reload] 重读。
     * 这类数据在本地已经是最新的，再去请求一遍上游只会慢。
     */
    local: Boolean = false,
    /** 本地歌单的刷新代数：加歌/删歌之后 +1 */
    reload: Int = 0,
    onBack: () -> Unit = {},
    /** 底部常驻层（迷你播放条），由壳决定放什么 */
    footer: @Composable () -> Unit = {},
) {
    // 先看进程内缓存：从播放页返回、或者刚看过的歌单再点一次，
    // 有数据就直接出内容 —— 不再先闪一屏骨架
    var d by remember(id) { mutableStateOf(if (local) null else PlaylistCache.get(id)) }
    var loading by remember(id) { mutableStateOf(!local && d == null) }
    var err by remember(id) { mutableStateOf<String?>(null) }
    // 重试代数：0 首时给一个「重新加载」的入口，不然用户只能退了再进
    var retry by remember(id) { mutableIntStateOf(0) }
    // 转太久给一句提示 + 手动重试：接口偶尔慢到 10 秒开外，
    // 光一个骨架屏转着不动，看着就像「点进去卡死了」
    var slow by remember(id) { mutableStateOf(false) }

    LaunchedEffect(id, retry, reload) {
        // ⓪ 本地歌单：数据就在内存里，直接读，连骨架屏都不用闪
        if (local) {
            d = runCatching { load(id) }.getOrNull() ?: d
            loading = false
            err = null
            slow = false
            return@LaunchedEffect
        }

        // ① 进程内刚拉过：30 分钟内直接信它，不再为一份几乎不变的数据等上游
        //   （详情接口实测 0.3~12 秒，是这一页唯一慢的东西）
        if (retry == 0 && d != null && PlaylistCache.isFresh(id)) {
            loading = false
            return@LaunchedEffect
        }

        err = null
        slow = false

        // ② 落盘缓存：冷启动第一次进来也先上屏，别把上游那十来秒全砸在骨架屏上
        if (d == null) {
            val c = runCatching { readCache(id) }.getOrNull()
            if (c != null && c.detail.tracks.isNotEmpty()) {
                d = c.detail
                PlaylistCache.put(id, c.detail, c.at)
                // 立刻收骨架：下面的静默刷新不该再把已有内容挡住
                loading = false
                // 新鲜期内到此为止；旧数据先上屏，下面继续静默重拉（失败也不打扰）
                if (retry == 0 && PlaylistCache.isFresh(c.at)) {
                    return@LaunchedEffect
                }
            }
        }

        // 只有手里没东西时才亮骨架、才给「慢」提示
        if (d == null) loading = true
        val slowTimer = launch {
            delay(4500)
            if (loading) slow = true
        }

        // 详情接口偶发一次拉空/超时（上游抽风、连接被掐），
        // 自动再试一次比让用户点「重试」体感好得多；两次都空才算这个歌单有问题。
        // 但屏上已经有内容时只试一次：后台刷新失败就失败，别把老内容换成错误页。
        val attempts = if (d == null) 2 else 1
        var got: NasApi.PlaylistDetail? = null
        var failure: Throwable? = null
        for (attempt in 0 until attempts) {
            val r = runCatching { load(id) }
            failure = r.exceptionOrNull()
            got = r.getOrNull()
            if (got != null && got.tracks.isNotEmpty()) break
            if (attempt == 0) delay(350)
        }

        val detail = got
        // 空壳（id 对、曲目 0 条）不能当成「这个歌单是空的」：
        // 上游偶尔会返回 trackCount=20 但 tracks 为空的响应（版权受限、抽风），
        // 之前直接吃下就成了「外面 20 首、点进来 0 首」。
        if (detail != null && detail.tracks.isNotEmpty()) {
            d = detail
            PlaylistCache.put(id, detail)
            // 存一份到磁盘：下次点进来（哪怕冷启动）直接出内容
            runCatching { writeCache(id, detail) }
        } else if (d == null) {
            // 连 name 都没有 = 这次请求本身没通（断网、上游 502），
            // 不能当成「歌单是空的」，更不能进失效名单 —— 否则网络抖一下，
            // 一个好歌单就被列表永久藏起来了。
            if (detail == null || detail.name.isBlank()) {
                err = friendlyError(failure)
            } else {
                err = "这个歌单暂时取不到曲目"
                // 记一笔：回到列表后把它剔掉，别让人反复点进空页面
                PlaylistCache.markDead(id)
            }
        }
        slowTimer.cancel()
        slow = false
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
                        PlaylistSkeleton(
                            topInset = topInset,
                            barH = BarH,
                            padX = padX,
                            wide = wide,
                            slow = slow,
                            onRetry = { retry += 1 },
                            onBack = onBack,
                        )
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
                                    // 本地歌单可以被清空，此时点播放会取 0 号曲目崩掉
                                    enabled = detail.tracks.isNotEmpty(),
                                    big = wide,
                                ) {
                                    onPlayTrack(detail, 0, false)
                                }
                                // 随机做成图标按钮：这一格和右边的收藏心形并排，
                                // 三个动作（播放全部 / 随机 / 收藏歌单）里只有一个需要文字
                                IconBtn(
                                    size = if (wide) 52.dp else 42.dp,
                                    icon = PlayerIcons.Shuffle,
                                    desc = "随机播放",
                                    tint = ProomColor.text,
                                    // 空歌单点「随机」会抛 NoSuchElement：先判一下
                                    enabled = detail.tracks.isNotEmpty(),
                                ) {
                                    onPlayTrack(detail, detail.tracks.indices.random(), true)
                                }
                                // 收藏这颗歌单：自建歌单不显示（它本来就是「我的」）。
                                // 点一下直接收 / 取消，不弹层 —— 歌单没有「要放进哪个歌单」的问题
                                if (fav != null && onToggleFav != null) {
                                    val on = fav
                                    IconBtn(
                                        size = if (wide) 52.dp else 42.dp,
                                        icon = if (on) TabIcons.HeartOn else TabIcons.Heart,
                                        desc = if (on) "取消收藏歌单" else "收藏歌单",
                                        tint = if (on) ProomColor.accent else ProomColor.text,
                                    ) { onToggleFav() }
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
                                if (detail.tracks.isNotEmpty()) {
                                    Text(
                                        "  ${detail.tracks.size}",
                                        style = ProomType.meta,
                                        color = ProomColor.muted,
                                        modifier = Modifier.padding(bottom = 3.dp),
                                    )
                                }
                            }
                        }

                        // 本地歌单会被清空（取消掉最后一颗心、自建歌单删光），
                        // 与其只剩一行「曲目」标题，不如告诉用户歌从哪儿加进来。
                        if (local && detail.tracks.isEmpty()) {
                            item {
                                EmptyShelf(
                                    liked = id == MusicShelf.LIKED_ID,
                                    wide = wide,
                                    padX = padX,
                                )
                            }
                        } else if (wide) {
                            // 宽屏两列：一列 960px 的曲目列表，一屏只能看到 8 首，
                            // 80 首的歌单要滚十屏。横屏拆成左右两列，一屏 16 首，
                            // 每行也更短、更好扫视。
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
                                            fav = isTrackFav(t),
                                            onFav = onAddToPlaylist?.let { f -> { f(t) } },
                                            onLongPress = onAddToPlaylist?.let { f -> { f(t) } },
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
                                    fav = isTrackFav(t),
                                    onFav = onAddToPlaylist?.let { f -> { f(t) } },
                                    onLongPress = onAddToPlaylist?.let { f -> { f(t) } },
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

/**
 * 本地歌单的空态。
 *
 * 「我喜欢的音乐」和自建歌单的加歌方式不一样：前者靠心形，后者靠长按/播放页的心形，
 * 所以文案分两套；在线歌单为空是上游抽风，不归这里管。
 */
@Composable
private fun EmptyShelf(liked: Boolean, wide: Boolean, padX: Dp) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = padX, vertical = 10.dp)
            .clip(RoundedCornerShape(ProomShape.md))
            // 注意别写 surface.copy(alpha = 0.5f)：那是把半透明白设成 50% 不透明，
            // 会变成一块 #808080 的灰砖；surface 本身就是 5.5% 白
            .background(ProomColor.card)
            .padding(
                horizontal = if (wide) 32.dp else 22.dp,
                vertical = if (wide) 30.dp else 24.dp,
            ),
    ) {
        Icon(
            imageVector = TabIcons.Heart,
            contentDescription = null,
            tint = ProomColor.dim,
            modifier = Modifier.size(if (wide) 26.dp else 22.dp),
        )
        Text(
            if (liked) "还没有收藏的歌" else "这个歌单还是空的",
            color = ProomColor.text,
            fontSize = if (wide) 19.sp else 16.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(top = 12.dp),
        )
        Text(
            if (liked) "在歌单里点歌曲右边的心形，歌就会收进这里"
            else "在歌单里长按歌曲，或点播放页右上角的心形，就能把歌加进来",
            style = ProomType.label,
            color = ProomColor.muted,
            modifier = Modifier.padding(top = 6.dp).widthIn(max = if (wide) 560.dp else Dp.Unspecified),
        )
    }
}

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
            // 禁用态必须看得出来：空歌单上那颗金色「播放全部」按下去没反应会很怪
            .graphicsLayer { alpha = if (enabled) 1f else 0.38f }
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

/** 方形图标按钮：随机 / 收藏歌单共用，尺寸和 [PlayAllButton] 对齐 */
@Composable
private fun IconBtn(
    size: Dp,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    desc: String,
    tint: Color,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .size(size)
            .graphicsLayer { alpha = if (enabled) 1f else 0.38f }
            .clip(RoundedCornerShape(ProomShape.sm))
            .background(ProomColor.surface)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = desc,
            tint = tint,
            modifier = Modifier.size(if (size >= 48.dp) 22.dp else 19.dp),
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
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
    /** 已被收进某个歌单（心形实心） */
    fav: Boolean = false,
    /** 点右边心形：弹「收藏到歌单」 */
    onFav: (() -> Unit)? = null,
    /** 长按整行：同 [onFav] */
    onLongPress: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 1.dp)
            .clip(RoundedCornerShape(ProomShape.sm))
            .background(if (current) ProomColor.accentSoft else Color.Transparent)
            .combinedClickable(onClick = onClick, onLongClick = onLongPress)
            .padding(start = 10.dp, end = if (onFav != null) 2.dp else 10.dp, top = if (big) 13.dp else 10.dp, bottom = if (big) 13.dp else 10.dp),
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
        // 右边的心形：点一下弹「收藏到歌单」，和长按是同一件事
        if (onFav != null) {
            Box(
                Modifier
                    .size(if (big) 44.dp else 40.dp)
                    .clip(RoundedCornerShape(ProomShape.sm))
                    .clickable(onClick = onFav),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = if (fav) TabIcons.HeartOn else TabIcons.Heart,
                    contentDescription = "收藏到歌单",
                    tint = if (fav) ProomColor.accent else ProomColor.dim,
                    modifier = Modifier.size(if (big) 20.dp else 18.dp),
                )
            }
        }
    }
}

@Composable
private fun PlaylistSkeleton(
    topInset: Dp,
    barH: Dp,
    padX: Dp,
    wide: Boolean,
    slow: Boolean = false,
    onRetry: () -> Unit = {},
    onBack: () -> Unit = {},
) {
    Box(Modifier.fillMaxSize()) {
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
            // 随机 + 收藏歌单都是方形图标按钮
            repeat(2) {
                SkeletonBlock(
                    Modifier.size(if (wide) 52.dp else 42.dp),
                    radius = ProomShape.sm,
                )
            }
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

    // 转了好几秒还没出来：给句话 + 两个出口，别让人对着骨架屏干等
    if (slow) {
        Column(
            Modifier.align(Alignment.BottomCenter).padding(bottom = 48.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("接口有点慢，还在加载…", style = ProomType.label, color = ProomColor.muted)
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PillButton("重试", filled = true, onClick = onRetry)
                PillButton("返回", filled = false, onClick = onBack)
            }
        }
    }
    }
}

/**
 * 把底层异常翻译成人话。
 *
 * OkHttp 抛的是 "timeout"、"Unable to resolve host ..." 这类英文串，
 * 直接扔到界面上用户看不懂；但也不能全压成同一句，否则分不清是断网、
 * 接口挂了还是真超时 —— 这三种该做的事不一样。
 */
private fun friendlyError(t: Throwable?): String {
    val m = t?.message?.trim().orEmpty()
    if (m.isBlank()) return "接口没连上，检查一下网络"
    val low = m.lowercase()
    return when {
        "timeout" in low || "timed out" in low -> "请求超时了，稍后重试"
        "unable to resolve host" in low || "unknownhost" in low -> "域名解析失败，检查一下网络"
        "refused" in low || "unreachable" in low -> "连不上接口，检查一下网络"
        m.startsWith("HTTP") -> "接口返回异常（$m）"
        else -> m
    }
}

/** 胶囊按钮：错误页和「加载慢」提示共用，保持两处手感一致 */
@Composable
private fun PillButton(text: String, filled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(if (filled) ProomColor.accent else ProomColor.surface)
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 9.dp),
    ) {
        Text(
            text,
            fontSize = 13.sp,
            color = if (filled) ProomColor.bg else ProomColor.text,
            fontWeight = if (filled) FontWeight.Medium else FontWeight.Normal,
        )
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
            PillButton("重试", filled = true, onClick = onRetry)
            PillButton("返回", filled = false, onClick = onBack)
        }
    }
}
