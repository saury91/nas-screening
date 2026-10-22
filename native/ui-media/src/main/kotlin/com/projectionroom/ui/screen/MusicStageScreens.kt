package com.projectionroom.ui.screen

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.projectionroom.shared.catalog.LrcLine
import com.projectionroom.ui.component.PlayerIcons
import com.projectionroom.ui.component.RemoteImage
import com.projectionroom.ui.component.SoftBackdrop
import com.projectionroom.ui.component.TabIcons
import com.projectionroom.ui.component.TvPageHeader
import com.projectionroom.ui.component.TvPageHeaderSpec
import com.projectionroom.ui.component.tvFocusRing
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import com.projectionroom.ui.music.AudioFx
import com.projectionroom.ui.music.MusicBox
import com.projectionroom.ui.music.RepeatMode
import com.projectionroom.ui.music.SleepTimer
import com.projectionroom.ui.theme.LocalProomTv
import com.projectionroom.ui.theme.ProomColor
import com.projectionroom.ui.theme.ProomShape
import com.projectionroom.ui.theme.ProomSpace
import com.projectionroom.ui.theme.ProomSp
import com.projectionroom.ui.theme.ProomType
import com.projectionroom.ui.theme.TvLayout
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 音乐播放页（定稿版）。
 *
 * 手机走 A 黑胶：唱盘 + 唱臂，播放/暂停在唱片转动和唱臂落抬上各有一次反馈。
 * 电视走 B 沉浸封面：左边一张大封面，右边大标题 + 滚动歌词 ——
 * 歌词从下方淡入、向上淡出，正中那一句是当前句。
 *
 * 两版共用 [StageHeader] / [StageTransport] 和队列、音效两个弹层。
 */

/* ─────────────────── 外壳 ─────────────────── */

@Composable
internal fun MusicStageScreen(isTv: Boolean, wide: Boolean, onBack: () -> Unit) {
    val context = LocalContext.current
    val track = MusicBox.current ?: return
    var queueOpen by remember { mutableStateOf(false) }
    var fxOpen by remember { mutableStateOf(false) }
    var sleepOpen by remember { mutableStateOf(false) }
    // 焦点是否已在本页。遥控器进来的第一下按键会选「第一个可聚焦元素」，
    // 顶部那两个图标按组合顺序排在主体前面，会把焦点抢走，
    // 所以页面还没焦点时先关掉它们的可聚焦性，让焦点直接落到播放键上。
    var pageFocused by remember { mutableStateOf(false) }
    // 主动把焦点请到播放键上。本页一开始没有任何焦点时，遥控器的第一下会被系统
    // 拿去「找第一个可聚焦元素」，这一下不会进到任何 onPreviewKeyEvent ——
    // 表现就是进页面先按「菜单」没反应，得先按一下方向键才行。
    val playFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { MusicBox.bind(context) }
    // 页面切换动画期间 requestFocus 常常「返回成功但焦点没落上」，所以不设次数上限：
    // 只要还没拿到焦点就一直补，拿到就退出。弹层开着时让位。
    LaunchedEffect(isTv) {
        if (!isTv) return@LaunchedEffect
        while (true) {
            if (!pageFocused && !queueOpen && !fxOpen && !sleepOpen) runCatching { playFocus.requestFocus() }
            delay(120)
        }
    }
    BackHandler(enabled = !queueOpen && !fxOpen && !sleepOpen) { onBack() }

    Box(
        Modifier
            .fillMaxSize()
            .background(ProomColor.bg)
            .onFocusChanged { pageFocused = it.hasFocus }
            // 媒体键/菜单键直接生效，不必先把焦点挪到对应按钮上
            .onPreviewKeyEvent { e ->
                if (!isTv || e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (e.key) {
                    Key.MediaPlayPause -> { MusicBox.toggle(); true }
                    Key.MediaNext -> { MusicBox.next(); true }
                    Key.MediaPrevious -> { MusicBox.prev(); true }
                    // 遥控器「菜单」键直达播放列表（音效面板走顶部图标）
                    Key.Menu -> { queueOpen = true; true }
                    else -> false
                }
            },
    ) {
        if (isTv) {
            StageBackdrop(true, track.cover)
            Column(
                Modifier
                    .fillMaxSize()
                    // 弹层打开时整块让位，否则遥控器一按上键焦点会跑到背后的收藏按钮上
                    .focusProperties { canFocus = !queueOpen && !fxOpen && !sleepOpen }
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .padding(horizontal = TvLayout.insetX),
            ) {
                EditorialStage(track.title, track.artist, track.cover, onBack, { fxOpen = true }, { queueOpen = true }, pageFocused, playFocus, { sleepOpen = true })
                StageErrorLine()
            }
        } else {
            // 手机：整页交给竖向翻页（上下滑切歌），顶栏和传输条作为固定层压在上面
            PhoneStage(wide, onBack, { fxOpen = true }, { queueOpen = true }, { sleepOpen = true })
        }
        if (queueOpen) StageQueueSheet(wide) { queueOpen = false }
        if (fxOpen) StageFxSheet { fxOpen = false }
        if (sleepOpen) StageSleepSheet { sleepOpen = false }
    }
}

@Composable
private fun StageBackdrop(isTv: Boolean, cover: String) {
    if (cover.isNotBlank()) {
        SoftBackdrop(
            model = cover,
            modifier = Modifier.fillMaxSize(),
            decodeWidth = 34.dp,
            brightness = if (isTv) 0.62f else 0.46f,
        )
    }
    val brush = if (isTv) {
        Brush.verticalGradient(
            0f to ProomColor.bg.copy(alpha = 0.10f),
            0.45f to ProomColor.bg.copy(alpha = 0.45f),
            1f to ProomColor.bg.copy(alpha = 0.94f),
        )
    } else {
        Brush.verticalGradient(
            0f to ProomColor.bg.copy(alpha = 0.30f),
            0.55f to ProomColor.bg.copy(alpha = 0.64f),
            1f to ProomColor.bg,
        )
    }
    Box(Modifier.fillMaxSize().background(brush))
}

/* ─────────────────── 手机：上下滑切歌 ─────────────────── */

/**
 * 手机竖屏的舞台。
 *
 * 手势照主流音乐 App 的「上下滑切歌」来：背景 + 黑胶 + 歌名 + 歌词是一张整体，
 * 向上滑下一首、向下滑上一首；两张卡一进一出，翻页途中外面那张缩一点，
 * 中间露出缝，读起来像卡片交替，而不是整块画面平移。
 * 顶栏、进度条、上一首/下一首这些是固定层，始终不动。
 *
 * 内核用 [VerticalPager]（抖音/小红书那套翻页组件）：跟手位移、甩动速度、
 * 回弹、贴边吸附、多指取消都是系统滚动曲线，比自己算拖拽偏移稳得多。
 */
@Composable
private fun PhoneStage(
    wide: Boolean,
    onBack: () -> Unit,
    onFx: () -> Unit,
    onQueue: () -> Unit,
    onSleep: () -> Unit = {},
) {
    val context = LocalContext.current
    val track = MusicBox.current ?: return

    // 横屏保留原来的左右版式：横向空间够，竖向翻页反而别扭
    if (wide) {
        StageBackdrop(false, track.cover)
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 22.dp),
        ) {
            VinylStage(true, track.title, track.artist, track.cover, onBack, onFx, onQueue, onSleep = onSleep)
            StageErrorLine()
        }
        return
    }

    val queue = MusicBox.queue
    val index = MusicBox.index
    val pager = rememberPagerState(
        initialPage = index.coerceIn(0, queue.lastIndex.coerceAtLeast(0)),
    ) { queue.size }
    val density = LocalDensity.current
    // 传输条实际多高，卡片区就留多高；量一次之后两层的分割线严丝合缝
    var transportH by remember { mutableStateOf(0.dp) }

    // 翻页落定 → 真正切歌。只认「已经停下」的那一页：
    // 拖到一半就切会把音源/歌词请求打成一串，也浪费流量。
    LaunchedEffect(pager) {
        snapshotFlow { pager.currentPage to pager.isScrollInProgress }
            .collect { (page, scrolling) ->
                if (!scrolling && page != MusicBox.index && page in MusicBox.queue.indices) {
                    MusicBox.playAt(context, page)
                }
            }
    }
    // 反方向同步：播完自动下一首、通知栏/锁屏按键、点列表换歌，页面都要自己滑过去。
    // 换了整张歌单（下标跳得很远）就直接跳过去，别一页页刷几分钟。
    LaunchedEffect(index, queue.size) {
        if (index in queue.indices && pager.currentPage != index && !pager.isScrollInProgress) {
            if (abs(index - pager.currentPage) > 2) {
                pager.scrollToPage(index)
            } else {
                pager.animateScrollToPage(index)
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
        VerticalPager(
            state = pager,
            modifier = Modifier.fillMaxSize(),
            // 前后各预排一页：下一首的背景/封面在手指抬起前就解码好了，滑过去不会先黑一下
            beyondViewportPageCount = 1,
            // 松手判定的位移门槛从默认的一半屏降到 1/4：
            // 半屏太费劲，手指轻轻一划就该过一张（抖音那种手感），甩得快则靠速度过页
            flingBehavior = PagerDefaults.flingBehavior(
                state = pager,
                snapPositionalThreshold = 0.25f,
            ),
        ) { page ->
            val t = queue.getOrNull(page) ?: return@VerticalPager
            val live = page == MusicBox.index
            StageCard(
                title = t.title,
                artist = t.artist,
                cover = t.cover,
                // 歌词只有当前这首有：相邻页先只显示唱片和歌名，切过去歌词随后到
                lyric = if (live) MusicBox.lyric else emptyList(),
                lyricAt = if (live) MusicBox.lyricAt else -1,
                playing = live && MusicBox.playing,
                bottomReserve = transportH,
                // 0 = 正中；-1/1 = 上下一张；拖拽时是小数
                offsetFraction = (pager.currentPage - page) + pager.currentPageOffsetFraction,
            )
        }

        // 固定层：顶栏 + 传输条。压在翻页之上，手指落在按钮/进度条上时翻页收不到事件
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 22.dp),
        ) {
            StageHeader(isTv = false, onBack, onFx, "正在播放", onSleep = onSleep) { NowPlayingBadge() }
            Spacer(Modifier.weight(1f))
            Column(
                Modifier
                    .fillMaxWidth()
                    .onGloballyPositioned {
                        val h = with(density) { it.size.height.toDp() }
                        if (h != transportH) transportH = h
                    },
            ) {
                StageTransport(wide = false, onQueue = onQueue)
                Spacer(Modifier.height(ProomSpace.md))
                StageErrorLine()
            }
        }
    }
}

/** 翻页里的一张卡：背景 + 黑胶 + 歌名/歌手 + 歌词，整张跟着手指走 */
@Composable
private fun StageCard(
    title: String,
    artist: String,
    cover: String,
    lyric: List<LrcLine>,
    lyricAt: Int,
    playing: Boolean,
    bottomReserve: Dp,
    offsetFraction: Float,
) {
    val away = abs(offsetFraction).coerceIn(0f, 1f)
    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer {
                // 离开正中时缩一点：两张卡之间闪出一道缝，翻页像「卡片交替」；
                // 缩太多会像弹窗，0.94 这档在真机上刚好能看出层次又不掉画面
                val s = 1f - 0.06f * away
                scaleX = s
                scaleY = s
            },
    ) {
        StageBackdrop(false, cover)
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(horizontal = 22.dp),
        ) {
            // 顶栏是固定层，卡片内容从它下面开始
            Spacer(Modifier.height(56.dp))
            BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
                val disc = minOf(maxWidth * 0.78f, maxHeight * 0.56f)
                Column(
                    Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    VinylDisc(cover, disc, playing)
                    Spacer(Modifier.height(ProomSpace.lg))
                    Text(
                        title,
                        color = ProomColor.text,
                        fontSize = ProomSp(23.sp),
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                    )
                    Text(
                        artist.ifBlank { "未知歌手" },
                        color = ProomColor.muted,
                        fontSize = ProomSp(12.sp),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                    Spacer(Modifier.height(ProomSpace.md))
                    LyricFocus(lyric, lyricAt, rows = 3, modifier = Modifier.fillMaxWidth())
                }
            }
            // 给进度条和控制条让出同样高度，卡片不会被压在那层玻璃胶囊底下
            Spacer(Modifier.height(bottomReserve))
        }
    }
}

/** 「● 正在播放」小标 */
@Composable
private fun NowPlayingBadge() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.size(5.dp)) { drawCircle(ProomColor.accent) }
        Text(
            "正在播放",
            color = ProomColor.muted,
            fontSize = ProomSp(11.sp),
            letterSpacing = 2.sp,
            modifier = Modifier.padding(start = 7.dp),
        )
    }
}

/** 只在出错时出现一行，正常播放不占位置 */
@Composable
private fun StageErrorLine() {
    MusicBox.error?.let { err ->
        Text(
            err,
            color = ProomColor.danger,
            fontSize = ProomSp(11.sp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(bottom = ProomSpace.sm),
        )
    }
}

@Composable
private fun StageHeader(
    isTv: Boolean,
    onBack: () -> Unit,
    onFx: () -> Unit,
    title: String,
    // 电视：页面还没拿到焦点时先不让这几个图标可聚焦，避免它们抢走遥控器的第一下
    trailingFocusable: Boolean = true,
    onSleep: () -> Unit = {},
    center: @Composable () -> Unit,
) {
    if (isTv) {
        TvPageHeader(
            onBack = onBack,
            title = title,
            inset = 0.dp,
            trailing = {
                FavBtn(40.dp, trailingFocusable)
                SleepBtn(40.dp, onSleep, trailingFocusable)
                FxBtn(40.dp, onFx, trailingFocusable)
                StopBtn(40.dp, trailingFocusable) { onBack(); MusicBox.stop() }
            },
        )
        return
    }
    Box(Modifier.fillMaxWidth().height(56.dp)) {
        Box(
            Modifier.align(Alignment.CenterStart).size(48.dp).clip(CircleShape).clickable(onClick = onBack),
            contentAlignment = Alignment.Center,
        ) {
            Icon(TabIcons.Back, "返回", tint = ProomColor.text, modifier = Modifier.size(24.dp))
        }
        Box(Modifier.align(Alignment.Center), contentAlignment = Alignment.Center) { center() }
        Row(Modifier.align(Alignment.CenterEnd), verticalAlignment = Alignment.CenterVertically) {
            FavBtn(40.dp)
            SleepBtn(40.dp, onSleep)
            FxBtn(40.dp, onFx)
            StopBtn(40.dp) { onBack(); MusicBox.stop() }
        }
    }
}

/** 睡眠定时：开着时图标变金，旁边挂剩余时间 */
@Composable
private fun SleepBtn(size: Dp, onClick: () -> Unit, focusable: Boolean = true) {
    val on = SleepTimer.mode != SleepTimer.Mode.Off
    Box(
        Modifier
            .size(size)
            .focusProperties { canFocus = focusable }
            .clip(CircleShape)
            .tvFocusRing(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            PlayerIcons.Timer,
            if (on) "睡眠定时 · ${SleepTimer.remainingText()}" else "睡眠定时",
            tint = if (on) ProomColor.accent else Color.White.copy(alpha = 0.78f),
            modifier = Modifier.size(21.dp),
        )
    }
}

@Composable
private fun FavBtn(size: Dp, focusable: Boolean = true) {
    Box(
        Modifier
            .size(size)
            .focusProperties { canFocus = focusable }
            .clip(CircleShape)
            .tvFocusRing(CircleShape)
            .clickable { MusicBox.toggleFav() },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            if (MusicBox.fav) TabIcons.HeartOn else TabIcons.Heart,
            if (MusicBox.fav) "取消收藏" else "收藏",
            tint = if (MusicBox.fav) ProomColor.accent else Color.White.copy(alpha = 0.78f),
            modifier = Modifier.size(21.dp),
        )
    }
}

@Composable
private fun FxBtn(size: Dp, onFx: () -> Unit, focusable: Boolean = true) {
    Box(
        Modifier
            .size(size)
            .focusProperties { canFocus = focusable }
            .clip(CircleShape)
            .tvFocusRing(CircleShape)
            .clickable(onClick = onFx),
        contentAlignment = Alignment.Center,
    ) {
        Icon(PlayerIcons.Sliders, "音效", tint = Color.White.copy(alpha = 0.78f), modifier = Modifier.size(21.dp))
    }
}

/**
 * 结束播放：停播 + 清空队列 + 退回上一页。
 *
 * 歌单页左栏第四颗按钮改成循环模式之后，整个 App 就再没有「关掉播放器」的地方了
 * —— 队列会一直挂到退出 App 为止。全屏页是它该待的位置：这一整块版面只属于
 * 当前这首歌，在这里关掉不会让人意外；而左栏那块是歌单页的一部分，
 * 在那儿点个 X 把歌停了，用户还得自己再点一次播放。
 */
@Composable
private fun StopBtn(size: Dp, focusable: Boolean = true, onClick: () -> Unit) {
    Box(
        Modifier
            .size(size)
            .focusProperties { canFocus = focusable }
            .clip(CircleShape)
            .tvFocusRing(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(PlayerIcons.Close, "结束播放", tint = Color.White.copy(alpha = 0.78f), modifier = Modifier.size(21.dp))
    }
}

/* ─────────────────── A / 黑胶 ─────────────────── */

@Composable
private fun ColumnScope.VinylStage(
    wide: Boolean,
    title: String,
    artist: String,
    cover: String,
    onBack: () -> Unit,
    onFx: () -> Unit,
    onQueue: () -> Unit,
    tv: Boolean = false,
    onSleep: () -> Unit = {},
) {
    StageHeader(isTv = false, onBack, onFx, "正在播放", onSleep = onSleep) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Canvas(Modifier.size(5.dp)) { drawCircle(ProomColor.accent) }
            Text("正在播放", color = ProomColor.muted, fontSize = ProomSp(11.sp), letterSpacing = 2.sp, modifier = Modifier.padding(start = 7.dp))
        }
    }

    if (wide) {
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
            val disc = minOf(maxHeight * 0.92f, maxWidth * 0.46f)
            Row(
                Modifier.fillMaxSize(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(ProomSpace.xxl),
            ) {
                Box(Modifier.width(disc), contentAlignment = Alignment.Center) {
                    VinylDisc(cover, disc, MusicBox.playing)
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                    Text(title, style = ProomType.display, color = ProomColor.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(artist.ifBlank { "未知歌手" }, color = ProomColor.muted, fontSize = ProomSp(15.sp), modifier = Modifier.padding(top = 10.dp))
                    Spacer(Modifier.height(18.dp))
                    LyricFocus(MusicBox.lyric, MusicBox.lyricAt, rows = 5, currentSize = 20, normalSize = 15, modifier = Modifier.fillMaxWidth())
                }
            }
        }
        StageTransport(wide = true, onQueue = onQueue, tv = true)
        Spacer(Modifier.height(ProomSpace.md))
        return
    }

    BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
        val disc = minOf(maxWidth * 0.78f, maxHeight * 0.56f)
        Column(
            Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            VinylDisc(cover, disc, MusicBox.playing)
            Spacer(Modifier.height(ProomSpace.lg))
            Text(
                title,
                color = ProomColor.text,
                fontSize = ProomSp(23.sp),
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
            Text(artist.ifBlank { "未知歌手" }, color = ProomColor.muted, fontSize = ProomSp(12.sp), textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp))
            Spacer(Modifier.height(ProomSpace.md))
            LyricFocus(MusicBox.lyric, MusicBox.lyricAt, rows = 3, modifier = Modifier.fillMaxWidth())
        }
    }
    StageTransport(wide = false, onQueue = onQueue)
    Spacer(Modifier.height(ProomSpace.md))
}

/** 黑胶盘：纹路 + 高光 + 中心封面，暂停时角度冻结 */
@Composable
private fun VinylDisc(cover: String, size: Dp, playing: Boolean) {
    var angle by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(playing) {
        if (!playing) return@LaunchedEffect
        var last = withFrameNanos { it }
        while (true) {
            withFrameNanos { now ->
                val dt = (now - last) / 1_000_000_000f
                last = now
                angle = (angle + dt * 12f) % 360f
            }
        }
    }
    val arm by animateFloatAsState(
        targetValue = if (playing) -30f else -9f,
        animationSpec = tween(620, easing = FastOutSlowInEasing),
        label = "arm",
    )

    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize().graphicsLayer { rotationZ = angle }) {
            val r = this.size.minDimension / 2f
            drawCircle(
                brush = Brush.radialGradient(listOf(Color(0xFF1C1D22), Color(0xFF0B0C10), Color(0xFF040507)), center = center, radius = r),
                radius = r,
            )
            var rr = r * 0.99f
            var i = 0
            while (rr > r * 0.33f) {
                drawCircle(Color.White.copy(alpha = if (i % 4 == 0) 0.070f else 0.032f), radius = rr, style = Stroke(1.dp.toPx()))
                rr -= r * 0.022f
                i++
            }
            rotate(-38f) {
                drawArc(
                    brush = Brush.sweepGradient(listOf(Color.Transparent, Color.White.copy(alpha = 0.10f), Color.Transparent), center = center),
                    startAngle = 0f,
                    sweepAngle = 90f,
                    useCenter = true,
                    topLeft = Offset.Zero,
                    size = this.size,
                )
            }
        }
        Box(
            Modifier.size(size * 0.60f).graphicsLayer { rotationZ = angle }.clip(CircleShape).background(ProomColor.card),
        ) {
            if (cover.isNotBlank()) {
                RemoteImage(cover, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Box(Modifier.size(size * 0.028f).clip(CircleShape).background(ProomColor.bg))
            }
        }
        Canvas(
            Modifier.fillMaxSize().graphicsLayer {
                rotationZ = arm
                transformOrigin = TransformOrigin(0.86f, 0.10f)
            },
        ) {
            val s = this.size.minDimension
            val pivot = Offset(s * 0.80f, s * 0.14f)
            val tip = Offset(s * 0.62f, s * 0.42f)
            drawCircle(Color(0xFF2A2C33), radius = s * 0.035f, center = pivot)
            drawCircle(Color(0xFF4A4E58), radius = s * 0.014f, center = pivot)
            drawLine(Color(0xFFB9BEC9), start = pivot, end = tip, strokeWidth = s * 0.012f, cap = StrokeCap.Round)
            drawCircle(Color(0xFFE7EAF0), radius = s * 0.016f, center = tip)
        }
    }
}

/* ─────────────────── B / 沉浸封面 ─────────────────── */

@Composable
private fun ColumnScope.EditorialStage(
    title: String,
    artist: String,
    cover: String,
    onBack: () -> Unit,
    onFx: () -> Unit,
    onQueue: () -> Unit,
    pageFocused: Boolean = true,
    playFocus: FocusRequester? = null,
    onSleep: () -> Unit = {},
) {
    StageHeader(isTv = true, onBack, onFx, "正在播放", trailingFocusable = pageFocused, onSleep = onSleep) {
        Text("正在播放", color = ProomColor.muted, style = ProomType.eyebrow)
    }

    BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
        // 唱片最多占屏高 72%：原来 0.9 倍高时唱片接近 300dp，在 55 寸以上会显得「只有一张大图」，
        // 挤掉歌词行数；现在收敛到 ~240dp，右侧能多看两行词，整页密度才像电视应用。
        val art = minOf(maxHeight * 0.72f, maxWidth * 0.28f)
        Row(
            Modifier.fillMaxSize(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ProomSpace.xxl),
        ) {
            VinylDisc(cover, art, MusicBox.playing)
            Column(Modifier.weight(1f).fillMaxHeight().padding(vertical = 6.dp)) {
                Text(title, style = ProomType.display, color = ProomColor.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    artist.ifBlank { "未知歌手" },
                    style = ProomType.body,
                    color = ProomColor.dim,
                    modifier = Modifier.padding(top = 6.dp),
                )
                Spacer(Modifier.height(ProomSpace.md))
                // 行数按剩余高度算，而不是写死：歌名换两行时剩余空间变小，写死 7 行会被压扁，
                // 当前那句也偏离中线。
                BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
                    val rowH = 44.dp
                    val rows = (maxHeight / rowH).toInt().coerceAtLeast(3)
                    LyricRoll(
                        lines = MusicBox.lyric,
                        current = MusicBox.lyricAt,
                        modifier = Modifier.fillMaxWidth(),
                        rows = rows,
                        rowHeight = rowH,
                        fontSize = 20,
                    )
                }
            }
        }
    }
    StageTransport(wide = true, onQueue = onQueue, tv = true, pageFocused = pageFocused, playFocus = playFocus)
    Spacer(Modifier.height(ProomSpace.md))
}

/**
 * 滚动歌词（电视）。
 *
 * 整列随播放进度向上走：新的一句从底部淡入升到正中，旧的一句继续上浮、淡出。
 * 正中那行金色加粗，并随距离做透明度衰减 —— 所以看起来是「浮上来 / 飘上去」，
 * 而不是整块跳一行。
 */
@Composable
private fun LyricRoll(
    lines: List<LrcLine>,
    current: Int,
    modifier: Modifier = Modifier,
    rows: Int = 5,
    rowHeight: Dp = 52.dp,
    fontSize: Int = 24,
) {
    if (lines.isEmpty()) {
        Box(modifier.height(rowHeight * rows), contentAlignment = Alignment.Center) {
            Text("暂无歌词", color = ProomColor.muted.copy(alpha = 0.7f), fontSize = (fontSize * 0.7f).sp)
        }
        return
    }
    val at = current.coerceAtLeast(0)
    // 用行号做动画值：换句时 anim 从 at 平滑走到 at+1，所有行的位移都基于它
    val anim by animateFloatAsState(
        targetValue = at.toFloat(),
        animationSpec = tween(560, easing = FastOutSlowInEasing),
        label = "lyricRoll",
    )
    val density = LocalDensity.current
    val rowPx = with(density) { rowHeight.toPx() }
    val firstTop = with(density) { (rows / 2) * rowPx }

    Box(modifier.height(rowHeight * rows).clipToBounds()) {
        for (i in (at - rows)..(at + rows)) {
            val line = lines.getOrNull(i) ?: continue
            val on = i == at
            Text(
                line.text,
                color = if (on) ProomColor.accent else Color.White.copy(alpha = 0.80f),
                fontSize = fontSize.sp,
                fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .offset { IntOffset(0, (firstTop + (i - anim) * rowPx).roundToInt()) }
                    .graphicsLayer {
                        val d = abs(i - anim)
                        alpha = (1f - d / 2.4f).coerceIn(0f, 1f)
                        val s = 1f + (1f - d.coerceIn(0f, 1f)) * 0.05f
                        scaleX = s
                        scaleY = s
                    },
            )
        }
    }
}

/* ─────────────────── 共用：歌词 / 进度 / 控制 ─────────────────── */

/**
 * 聚焦歌词：只显示当前句和上下文，当前句金色放大。
 *
 * 和现有版式那个可滚动列表不同 —— 那里是「歌词本」，这里是「正在唱哪一句」，
 * 更像唱片机面板上的小显示窗。
 */
@Composable
private fun LyricFocus(
    lines: List<LrcLine>,
    current: Int,
    rows: Int,
    modifier: Modifier = Modifier,
    currentSize: Int = 15,
    normalSize: Int = 12,
) {
    if (lines.isEmpty()) {
        Box(modifier.height((normalSize * 2.6f).dp), contentAlignment = Alignment.Center) {
            Text("暂无歌词", color = ProomColor.muted.copy(alpha = 0.7f), fontSize = normalSize.sp)
        }
        return
    }
    val at = if (current < 0) 0 else current
    val half = rows / 2
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        for (i in (at - half)..(at + rows - 1 - half)) {
            val line = lines.getOrNull(i) ?: continue
            val on = i == at
            Text(
                line.text,
                color = if (on) ProomColor.accent else Color.White.copy(alpha = 0.34f),
                fontSize = (if (on) currentSize else normalSize).sp,
                fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth().padding(vertical = if (on) 4.dp else 3.dp),
            )
        }
    }
}

/** 进度 + 时间 + 主控。手机黑胶版套一层玻璃胶囊，电视裸排（留白就是设计） */
@Composable
private fun ColumnScope.StageTransport(
    wide: Boolean,
    onQueue: () -> Unit,
    tv: Boolean = false,
    pageFocused: Boolean = true,
    playFocus: FocusRequester? = null,
) {
    var preview by remember { mutableFloatStateOf(-1f) }
    val live = if (MusicBox.duration > 0) {
        (MusicBox.position.toFloat() / MusicBox.duration).coerceIn(0f, 1f)
    } else 0f
    val ratio = if (preview >= 0f) preview else live

    // 拖/点之后不要把预览值立刻丢掉：播放器 seek 到位要几十~几百毫秒，
    // 一丢就会先闪回旧位置再跳过去，看着像「点了没反应」。
    // 等位置追上来（或 2 秒兜底）再交还给真实进度。
    LaunchedEffect(preview) {
        val target = preview
        if (target < 0f) return@LaunchedEffect
        repeat(20) {
            if (MusicBox.duration > 0 && abs(MusicBox.position - target * MusicBox.duration) < 1500f) {
                preview = -1f
                return@LaunchedEffect
            }
            delay(100)
        }
        preview = -1f
    }

    val progress: @Composable () -> Unit = {
        HairProgress(
            ratio = ratio,
            onDrag = { preview = it },
            onCommit = {
                if (MusicBox.duration > 0) MusicBox.seek((it * MusicBox.duration).toLong())
                preview = it
            },
            // 页面还没焦点时它也让位：否则遥控器第一下会先落到这条横贯全屏的进度条上
            focusable = !tv || pageFocused,
        )
        Row(Modifier.fillMaxWidth().padding(top = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(stageClock(if (preview >= 0f) (preview * MusicBox.duration).toLong() else MusicBox.position), color = ProomColor.muted, style = ProomType.label)
            Text(if (MusicBox.duration > 0) stageClock(MusicBox.duration) else "--:--", color = ProomColor.muted, style = ProomType.label)
        }
    }
    val repeatBtn: @Composable () -> Unit = {
        StageIconBtn(
            icon = when (MusicBox.repeat) {
                RepeatMode.All -> PlayerIcons.Repeat
                RepeatMode.One -> PlayerIcons.RepeatOne
                RepeatMode.Shuffle -> PlayerIcons.Shuffle
            },
            desc = when (MusicBox.repeat) {
                RepeatMode.All -> "列表循环"
                RepeatMode.One -> "单曲循环"
                RepeatMode.Shuffle -> "随机播放"
            },
            active = MusicBox.repeat != RepeatMode.All,
            focusable = !tv || pageFocused,
        ) { MusicBox.cycleRepeat() }
    }
    val controls: @Composable () -> Unit = {
        if (wide) {
            // 电视：绝对定位摆放，但**播放键排在最前面声明** ——
            // 遥控器第一次按键拿「第一个可聚焦元素」，这样一进页面焦点就在播放键上。
            // 视觉仍按「循环 上一首 ●播放 下一首 列表」用 offset 摆开。
            Box(
                Modifier.fillMaxWidth().height(70.dp),
                contentAlignment = Alignment.Center,
            ) {
                PlayBtn(70.dp, playFocus)
                Box(Modifier.offset(x = (-100).dp)) { StageIconBtn(PlayerIcons.Prev, "上一首", focusable = pageFocused) { MusicBox.prev() } }
                Box(Modifier.offset(x = 100.dp)) { StageIconBtn(PlayerIcons.Next, "下一首", focusable = pageFocused) { MusicBox.next() } }
                Box(Modifier.offset(x = (-186).dp)) { repeatBtn() }
                Box(Modifier.offset(x = 186.dp)) { StageIconBtn(PlayerIcons.Queue, "播放列表", active = false, focusable = pageFocused, onClick = onQueue) }
            }
        } else {
            Row(
                Modifier
                    .widthIn(max = 560.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Color.White.copy(alpha = 0.055f))
                    .border(1.dp, Color.White.copy(alpha = 0.07f), RoundedCornerShape(50))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                repeatBtn()
                StageIconBtn(PlayerIcons.Prev, "上一首") { MusicBox.prev() }
                PlayBtn(66.dp)
                StageIconBtn(PlayerIcons.Next, "下一首") { MusicBox.next() }
                StageIconBtn(PlayerIcons.Queue, "播放列表", active = false, onClick = onQueue)
            }
        }
    }

    if (tv) {
        // 进度条后声明：焦点顺序里排在控制条之后，遥控器首次按键才不会先落到它身上，
        // 但视觉上仍然在控制条上方，上下键照样能到（那里左右键微调进度）
        Box(Modifier.fillMaxWidth().height(132.dp)) {
            Box(Modifier.fillMaxWidth().align(Alignment.BottomCenter)) { controls() }
            Column(Modifier.fillMaxWidth().align(Alignment.TopCenter)) { progress() }
        }
    } else {
        progress()
        Spacer(Modifier.height(14.dp))
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { controls() }
    }
}

@Composable
private fun StageIconBtn(
    icon: ImageVector,
    desc: String,
    active: Boolean = false,
    focusable: Boolean = true,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            // 44dp 在一米外的遥控器手势下偏小；50dp 既是可点区也是焦点环直径
            .size(50.dp)
            .focusProperties { canFocus = focusable }
            .clip(CircleShape)
            .tvFocusRing(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            desc,
            tint = if (active) ProomColor.accent else Color.White.copy(alpha = 0.78f),
            modifier = Modifier.size(24.dp),
        )
    }
}

@Composable
private fun PlayBtn(size: Dp, focusRequester: FocusRequester? = null) {
    var focused by remember { mutableStateOf(false) }
    // 实心金圆外面再描一圈白环会又粗又脏。聚焦反馈只用「轻微放大 + 一圈同色暖光」，
    // 边缘化到全透明，没有硬边，也仍然一眼看得出焦点在哪。
    val scale by animateFloatAsState(if (focused) 1.08f else 1f, tween(140), label = "play-scale")
    val glow by animateFloatAsState(if (focused) 1f else 0f, tween(180), label = "play-glow")
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .size(size * 0.86f)
                .graphicsLayer {
                    scaleX = scale * 1.55f
                    scaleY = scale * 1.55f
                    alpha = glow
                }
                .background(
                    Brush.radialGradient(
                        0f to ProomColor.accent.copy(alpha = 0.50f),
                        0.66f to ProomColor.accent.copy(alpha = 0.34f),
                        1f to Color.Transparent,
                    ),
                    CircleShape,
                ),
        )
        Box(
            Modifier
                .size(size * 0.86f)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }
                .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
                .onFocusChanged { focused = it.isFocused }
                .clip(CircleShape)
                .background(ProomColor.accent)
                .clickable { MusicBox.toggle() },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (MusicBox.playing) PlayerIcons.Pause else PlayerIcons.Play,
                if (MusicBox.playing) "暂停" else "播放",
                tint = Color(0xFF1B1206),
                modifier = Modifier.size(26.dp),
            )
        }
    }
}

/**
 * 细线进度：3dp 轨道 + 金点。和现有版式同一种语言，只是三版共用一份。
 *
 * 电视上不让它进焦点序列（[focusable] = false）：焦点直接落在播放键上，
 * 左右键的快退/快进由播放键处理，比"先挪到进度条再微调"顺手得多。
 * 万一被聚焦（手机外接键盘），轨道提亮 + 金点放大，不画横贯全屏的外框。
 */
@Composable
private fun HairProgress(
    ratio: Float,
    onDrag: (Float) -> Unit,
    onCommit: (Float) -> Unit,
    focusable: Boolean = true,
) {
    var focused by remember { mutableStateOf(false) }
    val dot by animateFloatAsState(if (focused) 15f else 11f, tween(160), label = "dot")
    val glow by animateFloatAsState(if (focused) 1f else 0f, tween(160), label = "glow")
    val r = ratio.coerceIn(0f, 1f)
    // 手势里只认最新一帧的回调，pointerInput 才可以只用 Unit 做 key（不反复重启手势）
    val drag by rememberUpdatedState(onDrag)
    val commit by rememberUpdatedState(onCommit)

    Box(
        Modifier
            .fillMaxWidth()
            .height(26.dp)
            .focusProperties { canFocus = focusable }
            .onFocusChanged { focused = it.isFocused }
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val step = 0.01f
                when (e.key) {
                    Key.DirectionLeft -> { commit((r - step).coerceIn(0f, 1f)); true }
                    Key.DirectionRight -> { commit((r + step).coerceIn(0f, 1f)); true }
                    else -> false
                }
            }
            // 原来的 M3 Slider 手机上只能拖拇指，点在轨道上没反应。
            // 干脆整条自己接管：按下就把圆点挪到手指处（点哪跳哪），按住继续保持跟手，
            // 松手才真正 seek —— 和拖动一套逻辑，省得两种手势各管一半。
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val w = size.width.toFloat().coerceAtLeast(1f)
                    fun at(x: Float) = (x / w).coerceIn(0f, 1f)
                    var cur = at(down.position.x)
                    drag(cur)
                    down.consume()
                    while (true) {
                        val ev = awaitPointerEvent()
                        val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                        if (!ch.pressed) {
                            ch.consume()
                            break
                        }
                        cur = at(ch.position.x)
                        drag(cur)
                        ch.consume()
                    }
                    commit(cur)
                }
            },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val cy = size.height / 2f
            val stroke = 3.dp.toPx()
            val x = size.width * r
            // 轨道：底色 + 已播
            drawLine(
                Color.White.copy(alpha = 0.16f + 0.22f * glow),
                Offset(0f, cy), Offset(size.width, cy), stroke, StrokeCap.Round,
            )
            drawLine(ProomColor.accent, Offset(0f, cy), Offset(x, cy), stroke, StrokeCap.Round)
            // 金点。焦点时放大到 15dp 并补一圈白环 —— 白 = 焦点，三米外也认得出来
            val dotPx = dot.dp.toPx()
            if (glow > 0.01f) {
                drawCircle(ProomColor.accent.copy(alpha = 0.16f * glow), (dotPx + 11.dp.toPx()) / 2f, Offset(x, cy))
            }
            drawCircle(if (glow > 0.5f) Color.White else ProomColor.accent, dotPx / 2f, Offset(x, cy))
            if (glow > 0.5f) {
                drawCircle(ProomColor.accent, dotPx / 2f - 2.dp.toPx(), Offset(x, cy))
            }
        }
    }
}

private fun stageClock(ms: Long): String {
    if (ms <= 0) return "00:00"
    val total = ms / 1000
    return "%d:%02d".format(total / 60, total % 60)
}

/* ─────────────────── 共用弹层 ─────────────────── */

@Composable
private fun StageQueueSheet(wide: Boolean, onClose: () -> Unit) {
    val context = LocalContext.current
    // 弹层打开后焦点要进到列表里，否则遥控器还停在底下的控制条上，按 OK 会误触
    val firstFocus = remember { FocusRequester() }
    BackHandler { onClose() }
    LaunchedEffect(Unit) {
        delay(120)
        runCatching { firstFocus.requestFocus() }
    }
    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.55f))
                .focusProperties { canFocus = false }
                .clickable(onClick = onClose),
        )
        Column(
            if (wide) {
                Modifier
                    .align(Alignment.Center)
                    .width(640.dp)
                    .fillMaxHeight(0.82f)
                    .clip(RoundedCornerShape(20.dp))
                    .background(ProomColor.bgSoft)
                    .padding(horizontal = 20.dp, vertical = 10.dp)
            } else {
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .fillMaxHeight(0.68f)
                    .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
                    .background(ProomColor.bgSoft)
                    .navigationBarsPadding()
                    .padding(horizontal = 20.dp, vertical = 10.dp)
            },
        ) {
            Row(Modifier.fillMaxWidth().height(if (wide) 56.dp else 44.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("播放列表", style = ProomType.title, color = ProomColor.text)
                Text("  ${MusicBox.queue.size} 首", style = ProomType.meta, color = ProomColor.muted)
                Spacer(Modifier.weight(1f))
                Box(Modifier.size(if (wide) 44.dp else 34.dp).clip(CircleShape).clickable(onClick = onClose), contentAlignment = Alignment.Center) {
                    Text("✕", style = ProomType.title, color = ProomColor.muted)
                }
            }
            LazyColumn(Modifier.weight(1f)) {
                itemsIndexed(MusicBox.queue) { i, t ->
                    val on = i == MusicBox.index
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(ProomShape.sm))
                            .background(if (on) ProomColor.accentSoft else Color.Transparent)
                            .tvFocusRing(RoundedCornerShape(ProomShape.sm), ringWidth = 2.dp, zoom = 1f)
                            .then(if (i == 0) Modifier.focusRequester(firstFocus) else Modifier)
                            .clickable { MusicBox.playAt(context, i) }
                            .padding(horizontal = if (wide) 14.dp else 10.dp, vertical = if (wide) 12.dp else 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            if (on && MusicBox.playing) "♪" else (i + 1).toString().padStart(2, '0'),
                            color = if (on) ProomColor.accent else ProomColor.muted,
                            style = ProomType.label,
                            modifier = Modifier.width(if (wide) 36.dp else 30.dp),
                        )
                        Column(Modifier.weight(1f)) {
                            Text(
                                t.title,
                                color = if (on) ProomColor.accent else ProomColor.text,
                                style = ProomType.cardTitle,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                t.artist,
                                color = ProomColor.muted,
                                style = ProomType.meta,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StageFxSheet(onClose: () -> Unit) {
    val firstFocus = remember { FocusRequester() }
    BackHandler { onClose() }
    LaunchedEffect(Unit) {
        delay(120)
        runCatching { firstFocus.requestFocus() }
    }
    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.5f))
                .focusProperties { canFocus = false }
                .clickable(onClick = onClose),
        )
        Column(
            Modifier
                .align(Alignment.Center)
                .width(260.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(ProomColor.bgSoft)
                .clickable(enabled = false) {}
                .padding(18.dp),
        ) {
            Text("音效", style = ProomType.title, color = ProomColor.text, fontSize = ProomSp(16.sp))
            Text(
                "均衡器预设，按设备频段自动适配",
                style = ProomType.label,
                color = ProomColor.muted,
                modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
            )
            // 两列：8 档单列排下来会顶出屏幕
            AudioFx.Preset.entries.chunked(2).forEachIndexed { rowIdx, pair ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    pair.forEachIndexed { colIdx, p ->
                        val on = AudioFx.current == p && AudioFx.manual == null
                        Row(
                            Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(ProomShape.sm))
                                .background(if (on) ProomColor.accentSoft else Color.Transparent)
                                .tvFocusRing(RoundedCornerShape(ProomShape.sm), ringWidth = 2.dp, zoom = 1f)
                                .then(
                                    if (rowIdx == 0 && colIdx == 0) Modifier.focusRequester(firstFocus)
                                    else Modifier
                                )
                                .clickable { AudioFx.select(p) }
                                .padding(horizontal = 10.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                p.label,
                                color = if (on) ProomColor.accent else ProomColor.text,
                                fontSize = ProomSp(13.sp),
                                modifier = Modifier.weight(1f),
                            )
                            if (on) Text("✓", color = ProomColor.accent, fontSize = ProomSp(12.sp))
                        }
                    }
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
                Spacer(Modifier.height(4.dp))
            }

            // 响度增强：手机外放/小耳机听对白时最有用
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(ProomShape.sm))
                    .clickable { AudioFx.setLoudnessOn(!AudioFx.loudness) }
                    .padding(horizontal = 10.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("响度增强", color = ProomColor.text, fontSize = ProomSp(13.sp), modifier = Modifier.weight(1f))
                Text(
                    if (AudioFx.loudness) "开" else "关",
                    color = if (AudioFx.loudness) ProomColor.accent else ProomColor.muted,
                    fontSize = ProomSp(12.sp),
                )
            }

            // 手动均衡只在手机给：遥控器调 8 根柱子是折磨
            if (!LocalProomTv.current) {
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("手动调节", color = ProomColor.text, fontSize = ProomSp(13.sp))
                    Spacer(Modifier.weight(1f))
                    if (AudioFx.manual != null) {
                        Box(
                            Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .clickable { AudioFx.resetManual() }
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                        ) {
                            Text("重置", color = ProomColor.muted, fontSize = ProomSp(11.sp))
                        }
                    }
                }
                if (AudioFx.bandCount <= 0) {
                    Text(
                        "开始播放后可调节",
                        color = ProomColor.muted,
                        fontSize = ProomSp(11.sp),
                        modifier = Modifier.padding(top = 6.dp),
                    )
                } else {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.Bottom,
                    ) {
                        for (i in 0 until AudioFx.bandCount) {
                            EqBar(i, AudioFx.bandFreqs.getOrElse(i) { 0 })
                        }
                    }
                }
            }
        }
    }
}

/**
 * 一根柱子：中间是 0dB，往上推是增益、往下是衰减。
 * 按下就跳到位再拖动，和播放页进度条同一套手感。
 */
@Composable
private fun EqBar(index: Int, freq: Int) {
    val v = AudioFx.valueAt(index)
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .width(20.dp)
                .height(82.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(Color.White.copy(alpha = 0.06f))
                .pointerInput(index) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val h = size.height.toFloat().coerceAtLeast(1f)
                        fun at(y: Float) = (1f - y / h * 2f).coerceIn(-1f, 1f)
                        AudioFx.setBand(index, at(down.position.y))
                        down.consume()
                        while (true) {
                            val ev = awaitPointerEvent()
                            val c = ev.changes.firstOrNull { it.id == down.id } ?: break
                            if (!c.pressed) break
                            AudioFx.setBand(index, at(c.position.y))
                            c.consume()
                        }
                    }
                },
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val cy = size.height / 2f
                drawLine(
                    Color.White.copy(alpha = 0.18f),
                    Offset(0f, cy), Offset(size.width, cy), 1.dp.toPx(),
                )
                val hh = (size.height / 2f) * abs(v)
                if (hh > 0.5f) {
                    drawRoundRect(
                        color = ProomColor.accent.copy(alpha = if (v >= 0f) 0.9f else 0.5f),
                        topLeft = Offset(2.dp.toPx(), if (v >= 0f) cy - hh else cy),
                        size = androidx.compose.ui.geometry.Size(
                            (size.width - 4.dp.toPx()).coerceAtLeast(1f),
                            hh,
                        ),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.dp.toPx()),
                    )
                }
            }
        }
        Text(
            if (freq >= 1000) "${freq / 1000}k" else "$freq",
            color = ProomColor.muted,
            fontSize = ProomSp(8.5.sp),
            modifier = Modifier.padding(top = 3.dp),
        )
    }
}

/**
 * 睡眠定时。
 *
 * 放在全屏播放页而不是设置里：这是「正在听、准备睡」这个瞬间的动作，
 * 走开去翻设置页会把刚酝酿的睡意弄没。
 * 「听完这首」比「N 分钟后」更常用 —— 睡着前最后一首通常想听完整。
 */
@Composable
private fun StageSleepSheet(onClose: () -> Unit) {
    val firstFocus = remember { FocusRequester() }
    BackHandler { onClose() }
    LaunchedEffect(Unit) {
        delay(120)
        runCatching { firstFocus.requestFocus() }
    }
    val options = listOf(15, 30, 45, 60)
    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.5f))
                .focusProperties { canFocus = false }
                .clickable(onClick = onClose),
        )
        Column(
            Modifier
                .align(Alignment.Center)
                .width(260.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(ProomColor.bgSoft)
                .clickable(enabled = false) {}
                .padding(18.dp),
        ) {
            Text("睡眠定时", style = ProomType.title, color = ProomColor.text, fontSize = ProomSp(16.sp))
            Text(
                if (SleepTimer.mode == SleepTimer.Mode.Off) "最后 30 秒渐弱，到点暂停"
                else "${SleepTimer.remainingText()} 后停止",
                style = ProomType.label,
                color = ProomColor.muted,
                modifier = Modifier.padding(top = 4.dp, bottom = 14.dp),
            )
            options.forEachIndexed { idx, m ->
                val on = SleepTimer.mode == SleepTimer.Mode.Timer && SleepTimer.minutes == m
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(ProomShape.sm))
                        .background(if (on) ProomColor.accentSoft else Color.Transparent)
                        .tvFocusRing(RoundedCornerShape(ProomShape.sm), ringWidth = 2.dp, zoom = 1f)
                        .then(if (idx == 0) Modifier.focusRequester(firstFocus) else Modifier)
                        .clickable { SleepTimer.start(m); onClose() }
                        .padding(horizontal = 10.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "$m 分钟",
                        color = if (on) ProomColor.accent else ProomColor.text,
                        fontSize = ProomSp(14.sp),
                        modifier = Modifier.weight(1f),
                    )
                    if (on) Text("✓", color = ProomColor.accent, fontSize = ProomSp(14.sp))
                }
            }
            val trackEnd = SleepTimer.mode == SleepTimer.Mode.TrackEnd
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(ProomShape.sm))
                    .background(if (trackEnd) ProomColor.accentSoft else Color.Transparent)
                    .tvFocusRing(RoundedCornerShape(ProomShape.sm), ringWidth = 2.dp, zoom = 1f)
                    .clickable { SleepTimer.startTrackEnd(); onClose() }
                    .padding(horizontal = 10.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "听完这首",
                    color = if (trackEnd) ProomColor.accent else ProomColor.text,
                    fontSize = ProomSp(14.sp),
                    modifier = Modifier.weight(1f),
                )
                if (trackEnd) Text("✓", color = ProomColor.accent, fontSize = ProomSp(14.sp))
            }
            if (SleepTimer.mode != SleepTimer.Mode.Off) {
                Text(
                    "关闭定时",
                    color = ProomColor.danger,
                    fontSize = ProomSp(13.sp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(ProomShape.sm))
                        .tvFocusRing(RoundedCornerShape(ProomShape.sm), ringWidth = 2.dp, zoom = 1f)
                        .clickable { SleepTimer.cancel(); onClose() }
                        .padding(horizontal = 10.dp, vertical = 12.dp),
                )
            }
        }
    }
}

