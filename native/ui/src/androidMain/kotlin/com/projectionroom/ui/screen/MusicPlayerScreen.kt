package com.projectionroom.ui.screen

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.projectionroom.ui.component.PlayerIcons
import com.projectionroom.ui.component.TabIcons
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import com.projectionroom.shared.catalog.LrcLine
import com.projectionroom.ui.music.AudioFx
import com.projectionroom.ui.music.MusicBox
import com.projectionroom.ui.music.RepeatMode
import com.projectionroom.ui.theme.ProomColor
import com.projectionroom.ui.theme.ProomShape
import com.projectionroom.ui.theme.ProomSpace
import com.projectionroom.ui.theme.ProomType
import kotlin.math.roundToInt

/**
 * 全屏音乐播放器。
 *
 * 和视频播放器**不是同一个东西**：视频是「全屏画面 + 一层薄薄的控件」，
 * 音乐没有画面，主体是封面和歌名。这里按唱片页做：
 *
 * ```
 * 顶栏：返回 / 正在播放 / 收藏 · 音效
 * 模糊封面做底 → 大封面 → 歌名/歌手 → 歌词 → 进度
 * 底部一行：循环 · 上一首 · 播放 · 下一首 · 播放列表
 * ```
 *
 * 状态全部来自 [MusicBox] —— 退出这一页歌不会停，底部迷你条接着显示；
 * 收藏也挂在 MusicBox 上，通知栏点了收藏这一页立刻跟着变。
 */
@Composable
fun MusicPlayerScreen(
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val track = MusicBox.current ?: run {
        // 队列被清空了（比如播放失败停止）：直接退回上一页
        LaunchedEffect(Unit) { onBack() }
        return
    }
    var queueOpen by remember { mutableStateOf(false) }
    var fxOpen by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { MusicBox.bind(context) }
    BackHandler(enabled = !queueOpen) { onBack() }

    Box(Modifier.fillMaxSize().background(ProomColor.bg)) {
        // 背景：封面重度模糊，只当氛围光。先放大 1.2 再模糊，否则边缘会露出一圈硬边
        if (track.cover.isNotBlank()) {
            AsyncImage(
                model = track.cover,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { scaleX = 1.2f; scaleY = 1.2f }
                    .blur(60.dp),
            )
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(
                        0f to ProomColor.bg.copy(alpha = 0.62f),
                        0.55f to ProomColor.bg.copy(alpha = 0.86f),
                        1f to ProomColor.bg,
                    )
                )
            )
        }

        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 26.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // ── 顶栏：返回 / 正在播放 / 收藏 + 音效 ──
            //
            // 用 Box 三向对齐，而不是 Row + 两个 weight：
            // 右边现在有两个按钮，weight 会把「正在播放」推得偏左。
            Box(Modifier.fillMaxWidth().height(52.dp)) {
                Box(
                    Modifier
                        .align(Alignment.CenterStart)
                        .size(40.dp)
                        .clip(CircleShape)
                        .clickable(onClick = onBack),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = TabIcons.Back,
                        contentDescription = "返回",
                        tint = ProomColor.text,
                        modifier = Modifier.size(22.dp),
                    )
                }
                // 中间不是固定文字：加载中转文字，播放中是一条律动的音柱，
                // 暂停时才是「已暂停」。状态全放在这一处，页面就不会因为
                // 「正在加载…」那一行出现/消失而上下跳。
                when {
                    MusicBox.loading -> Text(
                        "正在加载中",
                        color = ProomColor.muted,
                        fontSize = 12.sp,
                        letterSpacing = 1.4.sp,
                        modifier = Modifier.align(Alignment.Center),
                    )

                    MusicBox.playing -> NowPlayingBars(
                        modifier = Modifier.align(Alignment.Center),
                    )

                    else -> Text(
                        "已暂停",
                        color = ProomColor.muted,
                        fontSize = 12.sp,
                        letterSpacing = 1.4.sp,
                        modifier = Modifier.align(Alignment.Center),
                    )
                }
                // 收藏 / 音效：都是「对当前这首」的设置，放顶栏比堆在底部省一整行
                Row(
                    Modifier.align(Alignment.CenterEnd),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SmallBtn(
                        icon = if (MusicBox.fav) TabIcons.HeartOn else TabIcons.Heart,
                        desc = if (MusicBox.fav) "取消收藏" else "收藏",
                        active = MusicBox.fav,
                        boxSize = 40.dp,
                        onClick = { MusicBox.toggleFav() },
                    )
                    SmallBtn(
                        icon = PlayerIcons.Sliders,
                        desc = "音效",
                        active = fxOpen,
                        boxSize = 40.dp,
                    ) { fxOpen = true }
                }
            }

            Spacer(Modifier.height(ProomSpace.md))

            // ── 封面：屏宽的 56%（封顶 230dp）。
            // 底部的「播放列表 / 音效」按钮挪走以后空出一整行，
            // 把这份空间还给封面 —— 但别回到 78%，那会把歌词挤成 0 高度
            val coverSize = (LocalConfiguration.current.screenWidthDp.dp * 0.56f).coerceAtMost(230.dp)
            Box(
                Modifier
                    .size(coverSize)
                    .clip(RoundedCornerShape(ProomShape.lg))
                    .background(ProomColor.card),
            ) {
                if (track.cover.isNotBlank()) {
                    AsyncImage(
                        model = track.cover,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }

            Spacer(Modifier.height(ProomSpace.lg))

            Text(
                track.title,
                color = ProomColor.text,
                fontSize = 22.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                track.artist.ifBlank { "未知歌手" },
                color = ProomColor.muted,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 6.dp),
            )

            Spacer(Modifier.height(ProomSpace.md))

            // ── 歌词：吃掉中间剩余空间，当前行高亮并自动滚到中间 ──
            LyricsPane(
                lines = MusicBox.lyric,
                current = MusicBox.lyricAt,
                modifier = Modifier
                    .fillMaxWidth()
                    // 至少留 3 行歌词的高度：weight 在空间不够时会给 0，
                    // 那样「暂无歌词」也会被裁掉，看起来像没做这个功能
                    .heightIn(min = 96.dp)
                    .weight(1f),
            )

            Spacer(Modifier.height(ProomSpace.sm))

            // ── 进度 ──
            var dragging by remember { mutableStateOf(false) }
            var dragValue by remember { mutableFloatStateOf(0f) }
            val ratio = if (dragValue >= 0f) dragValue else {
                if (MusicBox.duration > 0) {
                    (MusicBox.position.toFloat() / MusicBox.duration).coerceIn(0f, 1f)
                } else 0f
            }

            MusicSeekBar(
                ratio = ratio,
                onDrag = {
                    dragging = true
                    dragValue = it
                },
                onCommit = {
                    dragging = false
                    if (MusicBox.duration > 0) MusicBox.seek((it * MusicBox.duration).toLong())
                    dragValue = -1f
                },
            )
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    clock(if (dragging) (ratio * MusicBox.duration).toLong() else MusicBox.position),
                    color = ProomColor.muted,
                    fontSize = 11.sp,
                )
                Text(
                    if (MusicBox.duration > 0) clock(MusicBox.duration) else "--:--",
                    color = ProomColor.muted,
                    fontSize = 11.sp,
                )
            }

            Spacer(Modifier.height(ProomSpace.lg))

            // ── 主控：循环 / 上一首 / 播放 / 下一首 / 播放列表 ──
            // 五种操作刚好铺满一行；收藏和音效在顶栏，不再在底部抢位置
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SmallBtn(
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
                ) { MusicBox.cycleRepeat() }

                SmallBtn(PlayerIcons.Prev, "上一首") { MusicBox.prev() }

                // 主按钮：金色实心，整套界面唯一的实心圆
                Box(
                    Modifier
                        .size(66.dp)
                        .clip(CircleShape)
                        .background(ProomColor.accent)
                        .clickable { MusicBox.toggle() },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = if (MusicBox.playing) PlayerIcons.Pause else PlayerIcons.Play,
                        contentDescription = if (MusicBox.playing) "暂停" else "播放",
                        tint = Color(0xFF1B1206),
                        modifier = Modifier.size(28.dp),
                    )
                }

                SmallBtn(PlayerIcons.Next, "下一首") { MusicBox.next() }

                // 播放列表占了原收藏的位置：它是这一排里最常用的一个
                SmallBtn(
                    icon = PlayerIcons.Queue,
                    desc = "播放列表",
                    active = queueOpen,
                ) { queueOpen = true }
            }

            Spacer(Modifier.height(ProomSpace.md))

            // 状态行只在出错 / 加载时出现：
            // 「6 / 61 首」这种信息播放列表里已经写着，占一整行不值
            // 加载状态已经挪到顶栏的律动动画里，这里只在出错时出现
            val status = MusicBox.error
            if (status != null) {
                Text(
                    text = status,
                    color = if (MusicBox.error != null) ProomColor.danger else ProomColor.muted,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(ProomSpace.sm))
            }

            Spacer(Modifier.height(ProomSpace.md))
        }

        // ── 音效：三档预设，点完立即生效 ──
        if (fxOpen) {
            Box(
                Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.5f))
                    .clickable { fxOpen = false }
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
                Text("音效", style = ProomType.title, color = ProomColor.text, fontSize = 16.sp)
                Text(
                    "均衡器预设，按设备频段自动适配",
                    style = ProomType.label,
                    color = ProomColor.muted,
                    modifier = Modifier.padding(top = 4.dp, bottom = 14.dp),
                )
                AudioFx.Preset.entries.forEach { p ->
                    val on = AudioFx.current == p
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(ProomShape.sm))
                            .background(if (on) ProomColor.accentSoft else Color.Transparent)
                            .clickable { AudioFx.select(p) }
                            .padding(horizontal = 10.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            p.label,
                            color = if (on) ProomColor.accent else ProomColor.text,
                            fontSize = 14.sp,
                            modifier = Modifier.weight(1f),
                        )
                        if (on) Text("✓", color = ProomColor.accent, fontSize = 14.sp)
                    }
                }
            }
        }

        // ── 播放列表（从底部盖上来，不做动画：动画期间点击容易落到遮罩上）──
        if (queueOpen) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)).clickable { queueOpen = false })
            Column(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .fillMaxHeight(0.68f)
                    .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
                    .background(ProomColor.bgSoft)
                    .navigationBarsPadding()
                    .padding(horizontal = 20.dp, vertical = 10.dp),
            ) {
                Row(Modifier.fillMaxWidth().height(44.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("播放列表", style = ProomType.title, color = ProomColor.text, fontSize = 16.sp)
                    Text(
                        "  ${MusicBox.queue.size} 首",
                        style = ProomType.label,
                        color = ProomColor.muted,
                    )
                    Spacer(Modifier.weight(1f))
                    Box(
                        Modifier.size(34.dp).clip(CircleShape).clickable { queueOpen = false },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("✕", color = ProomColor.muted, fontSize = 15.sp)
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
                                .clickable { MusicBox.playAt(context, i) }
                                .padding(horizontal = 10.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                if (on && MusicBox.playing) "♪" else (i + 1).toString().padStart(2, '0'),
                                color = if (on) ProomColor.accent else ProomColor.muted,
                                fontSize = 11.sp,
                                modifier = Modifier.width(30.dp),
                            )
                            Column(Modifier.weight(1f)) {
                                Text(
                                    t.title,
                                    color = if (on) ProomColor.accent else ProomColor.text,
                                    fontSize = 14.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    t.artist,
                                    color = ProomColor.muted,
                                    fontSize = 11.sp,
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
}

/* ─────────────────── 小组件 ─────────────────── */

/** 播放中的律动：三根音柱错峰跳动（高度 4~14dp），比一行"正在播放"更有生命力 */
@Composable
private fun NowPlayingBars(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "bars")
    Row(
        modifier,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        repeat(3) { i ->
            val h by transition.animateFloat(
                initialValue = 0.30f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(
                        durationMillis = 520,
                        delayMillis = i * 130,
                        easing = FastOutSlowInEasing,
                    ),
                    repeatMode = androidx.compose.animation.core.RepeatMode.Reverse,
                ),
                label = "bar$i",
            )
            Box(
                Modifier
                    .width(3.dp)
                    .height((14f * h).dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(ProomColor.accent)
            )
        }
    }
}

@Composable
private fun SmallBtn(
    icon: ImageVector,
    desc: String,
    active: Boolean = false,
    /** 触控块尺寸：底部主控 44dp（大一点好按），顶栏 40dp（和返回键等高） */
    boxSize: androidx.compose.ui.unit.Dp = 44.dp,
    onClick: () -> Unit,
) {
    Box(
        Modifier.size(boxSize).clip(CircleShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = desc,
            tint = if (active) ProomColor.accent else Color.White.copy(alpha = 0.78f),
            modifier = Modifier.size(22.dp),
        )
    }
}

/**
 * 自绘进度条：3dp 细线 + 拖拽时放大圆点。
 *
 * 不用 material Slider —— 它的拇指和轨道在深色磨砂背景上很难看，
 * 而且 48dp 的最小触控高度会把上下间距撑开。
 */
@Composable
private fun MusicSeekBar(
    ratio: Float,
    onDrag: (Float) -> Unit,
    onCommit: (Float) -> Unit,
) {
    var local by remember { mutableFloatStateOf(-1f) }
    val shown = if (local >= 0f) local else ratio
    Box(
        Modifier
            .fillMaxWidth()
            .height(28.dp)
            .pointerInput(Unit) {
                detectTapGestures { off ->
                    val r = (off.x / size.width).coerceIn(0f, 1f)
                    onCommit(r)
                }
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { off ->
                        local = (off.x / size.width).coerceIn(0f, 1f)
                        onDrag(local)
                    },
                    onDragEnd = {
                        onCommit(local.coerceIn(0f, 1f))
                        local = -1f
                    },
                    onHorizontalDrag = { change, _ ->
                        change.consume()
                        local = (change.position.x / size.width).coerceIn(0f, 1f)
                        onDrag(local)
                    },
                )
            },
    ) {
        androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
            val cy = size.height / 2f
            val h = 3.dp.toPx()
            val x = size.width * shown
            // 底轨
            drawLine(
                color = Color.White.copy(alpha = 0.16f),
                start = Offset(0f, cy),
                end = Offset(size.width, cy),
                strokeWidth = h,
                cap = StrokeCap.Round,
            )
            // 已播放：金色 → 暖橙的渐变，比一根纯色线有「在走」的感觉
            if (x > 0.5f) {
                drawLine(
                    brush = androidx.compose.ui.graphics.Brush.horizontalGradient(
                        0f to ProomColor.accent,
                        1f to ProomColor.accent2,
                    ),
                    start = Offset(0f, cy),
                    end = Offset(x, cy),
                    strokeWidth = h,
                    cap = StrokeCap.Round,
                )
            }
            // 拖拽时拇指放大 + 一圈柔光，手指压上去有反馈
            val dragging = local >= 0f
            val r = (if (dragging) 6.5f else 4.5f).dp.toPx()
            if (dragging) {
                drawCircle(
                    color = ProomColor.accent.copy(alpha = 0.22f),
                    radius = r * 2.4f,
                    center = Offset(x, cy),
                )
            }
            // 拇指本体带一点渐变，和小尺寸下的纯色点区分开
            drawCircle(
                brush = androidx.compose.ui.graphics.Brush.verticalGradient(
                    listOf(Color.White, ProomColor.accent)
                ),
                radius = r,
                center = Offset(x, cy),
            )
        }
    }
}

private fun clock(ms: Long): String {
    if (ms <= 0) return "00:00"
    val total = ms / 1000
    val m = total / 60
    val s = total % 60
    return "%d:%02d".format(m, s)
}


/**
 * 歌词面板。
 *
 * 当前行金色加粗，其余压暗；每次换行自动把当前行滚到屏幕中部偏上，
 * 这样上下都能看到前后文 —— 直接 scrollToItem 会把它顶到最上面。
 */
@Composable
private fun LyricsPane(lines: List<LrcLine>, current: Int, modifier: Modifier = Modifier) {
    if (lines.isEmpty()) {
        Box(modifier, contentAlignment = Alignment.Center) {
            Text("暂无歌词", color = ProomColor.muted, fontSize = 12.sp)
        }
        return
    }
    val state = rememberLazyListState()
    LaunchedEffect(current, lines) {
        if (current >= 0) {
            state.animateScrollToItem((current - 2).coerceAtLeast(0))
        }
    }
    LazyColumn(
        state = state,
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 8.dp),
    ) {
        itemsIndexed(lines) { i, l ->
            val on = i == current
            Text(
                text = l.text,
                color = if (on) ProomColor.accent else Color.White.copy(alpha = 0.38f),
                fontSize = if (on) 15.sp else 13.sp,
                fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = if (on) 7.dp else 6.dp),
            )
        }
    }
}
