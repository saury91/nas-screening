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
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.layout.offset
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
import androidx.compose.runtime.withFrameNanos
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
import com.projectionroom.ui.component.RemoteImage

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
    /** 电视端：顶栏换成与其它页面同一套 TvPageHeader（返回提示 + 标题 + 右侧动作） */
    isTv: Boolean = false,
) {
    val context = LocalContext.current
    val track = MusicBox.current ?: run {
        // 队列被清空了（比如播放失败停止）：直接退回上一页
        LaunchedEffect(Unit) { onBack() }
        return
    }
    var queueOpen by remember { mutableStateOf(false) }
    var fxOpen by remember { mutableStateOf(false) }
    // 横屏（电视 / 手机横屏）走左右结构，竖屏手机保持上下结构
    val wide = LocalConfiguration.current.let { it.screenWidthDp > it.screenHeightDp }

    LaunchedEffect(Unit) { MusicBox.bind(context) }
    BackHandler(enabled = !queueOpen) { onBack() }

    Box(Modifier.fillMaxSize().background(ProomColor.bg)) {
        // 背景：封面只当氛围光。专辑图本身才 200~300px，铺满 1080p 屏要放大十几倍，
        // 而 Modifier.blur 在 API<31 是空操作 —— 所以改用 SoftBackdrop 的小尺寸解码，
        // 任何设备上都是同一种软色雾，不会是大头照糊斑。
        if (track.cover.isNotBlank()) {
            com.projectionroom.ui.component.SoftBackdrop(
                model = track.cover,
                modifier = Modifier.fillMaxSize(),
                decodeWidth = 34.dp,
                brightness = 0.50f,
            )
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(
                        0f to ProomColor.bg.copy(alpha = 0.30f),
                        0.55f to ProomColor.bg.copy(alpha = 0.62f),
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
                // 电视端内容左缘跟其它页统一到 40dp；手机保持 26dp（信息密度不同）
                .padding(
                    horizontal = if (isTv) {
                        com.projectionroom.ui.component.TvPageHeaderSpec.inset
                    } else {
                        26.dp
                    }
                ),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // ── 顶栏：返回 / 正在播放 / 收藏 + 音效 ──
            //
            // 电视端：整条顶栏直接复用其它页的 TvPageHeader（返回提示 + 状态标题 + 右侧动作），
            // 不再出现「48dp 热区 + 24dp 图标」这种只在播放器里存在的第三套返回。
            if (isTv) {
                com.projectionroom.ui.component.TvPageHeader(
                    onBack = onBack,
                    title = when {
                        MusicBox.loading -> "正在加载中"
                        MusicBox.playing -> "正在播放"
                        else -> "已暂停"
                    },
                    // 外层 Column 已经留了 40dp，这里必须传 0，否则叠成 80dp
                    inset = 0.dp,
                    trailing = {
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
                    },
                )
            } else {
            // 用 Box 三向对齐，而不是 Row + 两个 weight：
            // 右边现在有两个按钮，weight 会把「正在播放」推得偏左。
            Box(Modifier.fillMaxWidth().height(56.dp)) {
                // 返回键和搜索页/详情页同一条基准线：48dp 热区 + 24dp 图标，
                // 图标视觉左缘落在 16dp（与其它页面统一）。本页内容留白是 26dp，
                // 所以热区要往左挪 22dp，
                // 否则会比其它页面的返回键右移 15dp，看着"缩在内容里"。
                Box(
                    Modifier
                        .align(Alignment.CenterStart)
                        .offset(x = (-22).dp)
                        .size(48.dp)
                        .clip(CircleShape)
                        .clickable(onClick = onBack),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = TabIcons.Back,
                        contentDescription = "返回",
                        tint = ProomColor.text,
                        modifier = Modifier.size(24.dp),
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
            }

            Spacer(Modifier.height(ProomSpace.md))

            // ── 主体：横屏左右结构 / 竖屏上下结构 ──
            //
            // 竖着一列堆「封面 → 歌名 → 歌词 → 进度 → 控制」至少需要 700dp，
            // 而电视横屏只有 540dp 高 —— 控制区被顶出屏幕，底部那排按钮只剩半个。
            // 所以横屏改成「左：封面 + 歌名，右：歌词」，主体只吃中间一段，
            // 进度和控制永远留在底部安全区内；竖屏手机保持原来的上下结构。
            if (wide) {
                BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
                    // 横屏的瓶颈是高度，封面按可用高度算，别按屏宽算。
                    // 0.66 是给下面两行歌名 + 歌手留出的余量（0.72 时歌手行会被裁掉）；
                    // 再用宽度的 0.34 兜一下，超宽屏上封面才不会把歌词挤成一条缝
                    val cover = minOf(maxHeight * 0.66f, maxWidth * 0.34f)
                    Row(
                        Modifier.fillMaxSize(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(ProomSpace.xl),
                    ) {
                        Column(Modifier.width(cover)) {
                            CoverArt(track.cover, cover)
                            Spacer(Modifier.height(ProomSpace.md))
                            TrackMeta(
                                title = track.title,
                                artist = track.artist,
                                centered = false,
                            )
                        }
                        // 歌词吃掉右侧全部空间：宽屏下一屏能看到 10 行以上
                        LyricsPane(
                            lines = MusicBox.lyric,
                            current = MusicBox.lyricAt,
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                            big = true,
                        )
                    }
                }
            } else {
                Column(
                    Modifier.fillMaxWidth().weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    // 封面以屏幕最短边（竖屏就是宽度）撑满，只留两侧页边距。
                    // 原来是屏宽的 56%、封顶 230dp —— 在一台 411dp 宽的手机上只占 1/4 屏，
                    // 上下全是空，看着像缩略图。现在按「宽度减边距」算，
                    // 再用高度的 52% 兜底，避免矮屏上把歌词和进度条顶出去。
                    val cfg = LocalConfiguration.current
                    // 0.44 会把竖屏塞满：歌词区被压到 30dp 上下，两行字只剩几像素。
                    // 0.34 让出约 100dp 给歌词（3 行以上），封面仍然接近屏宽
                    val coverSize = minOf(
                        cfg.screenWidthDp.dp - 72.dp,
                        cfg.screenHeightDp.dp * 0.34f,
                    )
                    CoverArt(track.cover, coverSize)
                    Spacer(Modifier.height(ProomSpace.lg))
                    TrackMeta(
                        title = track.title,
                        artist = track.artist,
                        centered = true,
                    )
                    Spacer(Modifier.height(ProomSpace.md))
                    // 歌词：吃掉中间剩余空间，当前行高亮并自动滚到中间。
                    // 至少留 3 行高度：weight 在空间不够时会给 0，
                    // 那样「暂无歌词」也会被裁掉，看起来像没做这个功能
                    LyricsPane(
                        lines = MusicBox.lyric,
                        current = MusicBox.lyricAt,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 96.dp)
                            .weight(1f),
                    )
                }
            }

            Spacer(Modifier.height(ProomSpace.sm))

            // ── 进度 ──
            // -1f 表示"没在拖"，只有拖的时候才用它盖住实时位置。
            // 之前这里初始化成 0f，条件 dragValue >= 0f 恒成立 → 拇指永远贴在左边，
            // 时间却在走，看起来就是"进度条不同步"。
            var preview by remember { mutableFloatStateOf(-1f) }
            val live = if (MusicBox.duration > 0) {
                (MusicBox.position.toFloat() / MusicBox.duration).coerceIn(0f, 1f)
            } else 0f
            val ratio = if (preview >= 0f) preview else live

            MusicSeekBar(
                ratio = ratio,
                onDrag = { preview = it },
                onCommit = {
                    if (MusicBox.duration > 0) MusicBox.seek((it * MusicBox.duration).toLong())
                    preview = -1f
                },
            )
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    clock(if (preview >= 0f) (preview * MusicBox.duration).toLong() else MusicBox.position),
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
            // 五种操作刚好铺满一行；收藏和音效在顶栏，不再在底部抢位置。
            // 横屏不再 SpaceBetween：那是手机竖屏的排法，电视上会把「循环」和
            // 「播放列表」甩到 1920px 的两端，遥控器横移一趟要按十几下。
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = if (wide) {
                    Arrangement.spacedBy(34.dp, Alignment.CenterHorizontally)
                } else {
                    Arrangement.SpaceBetween
                },
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
                // 横屏不铺满整行：1920px 宽的一条曲目列表，每行只有歌名孤零零在最左，
                // 中间一千多像素全是空的。收成 620dp 的居中方板，扫视距离才正常。
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

/** 方形封面。尺寸由调用方按「横屏看高度 / 竖屏看宽度」算好传进来 */
@Composable
private fun CoverArt(cover: String, size: androidx.compose.ui.unit.Dp) {
    Box(
        Modifier
            .size(size)
            .clip(RoundedCornerShape(ProomShape.lg))
            .background(ProomColor.card),
    ) {
        if (cover.isNotBlank()) {
            RemoteImage(
                model = cover,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/** 歌名 + 歌手。竖屏居中，横屏左对齐到封面下沿 */
@Composable
private fun TrackMeta(title: String, artist: String, centered: Boolean) {
    Text(
        title,
        color = ProomColor.text,
        fontSize = 22.sp,
        fontWeight = FontWeight.SemiBold,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        textAlign = if (centered) TextAlign.Center else TextAlign.Start,
        modifier = Modifier.fillMaxWidth(),
    )
    Text(
        artist.ifBlank { "未知歌手" },
        color = ProomColor.muted,
        fontSize = 13.sp,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        // fillMaxWidth 之后必须显式对齐：Text 默认左对齐，
        // 撑满整行就会把歌手名甩到屏幕最左边（歌名居中、歌手贴左的错位就是这么来的）
        textAlign = if (centered) TextAlign.Center else TextAlign.Start,
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
    )
}

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
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
private fun MusicSeekBar(
    ratio: Float,
    onDrag: (Float) -> Unit,
    onCommit: (Float) -> Unit,
) {
    // 交给系统 Slider：自绘手势在真机上出现过"拖不动 / 松手位置对不上"，
    // 而 Slider 自带 48dp 热区、无障碍语义和取消处理。
    // **本组件不持有状态**：值由调用方传入（拖动时是预览值，否则是实时位置），
    // 避免"页面一份、组件一份"两套状态不一致。
    androidx.compose.material3.Slider(
        value = ratio.coerceIn(0f, 1f),
        onValueChange = { onDrag(it) },
        onValueChangeFinished = { onCommit(ratio.coerceIn(0f, 1f)) },
        colors = androidx.compose.material3.SliderDefaults.colors(
            thumbColor = ProomColor.accent,
            activeTrackColor = ProomColor.accent,
            inactiveTrackColor = Color.White.copy(alpha = 0.16f),
        ),
        thumb = {
            Box(
                Modifier
                    .size(12.dp)
                    .clip(CircleShape)
                    .background(ProomColor.accent)
            )
        },
        track = { state ->
            androidx.compose.foundation.Canvas(Modifier.fillMaxWidth().height(3.dp)) {
                val cy = size.height / 2f
                val w = 3.dp.toPx()
                drawLine(
                    color = Color.White.copy(alpha = 0.16f),
                    start = Offset(0f, cy), end = Offset(size.width, cy),
                    strokeWidth = w, cap = StrokeCap.Round,
                )
                drawLine(
                    color = ProomColor.accent,
                    start = Offset(0f, cy),
                    end = Offset(size.width * state.value.coerceIn(0f, 1f), cy),
                    strokeWidth = w, cap = StrokeCap.Round,
                )
            }
        },
        modifier = Modifier.fillMaxWidth().height(28.dp),
    )
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
private fun LyricsPane(
    lines: List<LrcLine>,
    current: Int,
    modifier: Modifier = Modifier,
    /** 宽屏：字号提到 17/15sp，行距放宽 —— 电视三米外 13sp 的歌词基本看不清 */
    big: Boolean = false,
) {
    if (lines.isEmpty()) {
        Box(modifier, contentAlignment = Alignment.Center) {
            Text("暂无歌词", color = ProomColor.muted, fontSize = if (big) 15.sp else 12.sp)
        }
        return
    }
    val state = rememberLazyListState()
    LaunchedEffect(current, lines) {
        if (current < 0 || lines.isEmpty()) return@LaunchedEffect
        // 等这一帧量完再算偏移：首帧 layoutInfo 的 viewport 高度还是 0
        withFrameNanos { }
        val vp = state.layoutInfo.viewportSize.height
        val row = state.layoutInfo.visibleItemsInfo.firstOrNull()?.size ?: 0
        // 固定「往上退 2 行」在不同面板高度下完全不是居中：面板矮时当前句会被顶出屏幕。
        // 按实际视口和行高算居中偏移。注意 scrollOffset 是正数把条目往上推，
        // 要居中得取负值，否则当前句会顶到面板上沿被切掉一半
        val offset = if (vp > 0 && row > 0) -((vp - row) / 2) else 0
        state.animateScrollToItem(current, offset)
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
                fontSize = when {
                    big && on -> 17.sp
                    big -> 15.sp
                    on -> 15.sp
                    else -> 13.sp
                },
                fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = if (big) (if (on) 9.dp else 8.dp) else (if (on) 7.dp else 6.dp)),
            )
        }
    }
}
