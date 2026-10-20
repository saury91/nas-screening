package com.projectionroom.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
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
import coil3.compose.AsyncImage
import com.projectionroom.shared.catalog.NasApi
import com.projectionroom.ui.component.PlayerIcons
import com.projectionroom.ui.component.SkeletonBlock
import com.projectionroom.ui.component.TabIcons
import com.projectionroom.ui.theme.ProomColor
import com.projectionroom.ui.theme.ProomShape
import com.projectionroom.ui.theme.ProomSpace
import com.projectionroom.ui.theme.ProomType

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
    var d by remember(id) { mutableStateOf<NasApi.PlaylistDetail?>(null) }
    var loading by remember(id) { mutableStateOf(true) }
    var err by remember(id) { mutableStateOf<String?>(null) }

    LaunchedEffect(id) {
        loading = true
        err = null
        runCatching { load(id) }
            .onSuccess { d = it }
            .onFailure { err = it.message }
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

    Box(Modifier.fillMaxSize().background(ProomColor.bg)) {
        // ── 背景：封面放大模糊，只取顶部一段 ──
        if (detail != null && detail.cover.isNotBlank()) {
            Box(Modifier.fillMaxWidth().height(460.dp + topInset)) {
                AsyncImage(
                    model = detail.cover,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer { alpha = 0.75f }
                        // 必须 Unbounded：默认的 Rectangle 边缘处理会在图层里
                        // 留下一道亮带（滚到那儿白字全糊），电影详情页也踩过同一个坑
                        .blur(60.dp, edgeTreatment = BlurredEdgeTreatment.Unbounded),
                )
                // 渐变要在背景图结束之前就压到全黑：
                // 否则列表滚到那一行时，白字压在亮色模糊层上会糊成一片（浅色封面尤其明显）
                Box(
                    Modifier.fillMaxSize().background(
                        Brush.verticalGradient(
                            0f to ProomColor.bg.copy(alpha = 0.28f),
                            0.38f to ProomColor.bg.copy(alpha = 0.66f),
                            0.72f to ProomColor.bg.copy(alpha = 0.94f),
                            1f to ProomColor.bg,
                        )
                    )
                )
            }
        }

        when {
            loading || detail == null -> {
                if (err != null) {
                    PlaylistError(err ?: "") { onBack() }
                } else {
                    PlaylistSkeleton(topInset, BarH)
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
                        Row(Modifier.padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 18.dp)) {
                            Box(
                                Modifier
                                    .width(132.dp)
                                    .aspectRatio(1f)
                                    .clip(RoundedCornerShape(ProomShape.md))
                                    .background(ProomColor.card),
                            ) {
                                if (detail.cover.isNotBlank()) {
                                    AsyncImage(
                                        model = detail.cover,
                                        contentDescription = null,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize(),
                                    )
                                }
                            }
                            Spacer(Modifier.width(16.dp))
                            Column(Modifier.weight(1f).padding(top = 2.dp)) {
                                Text("歌单", style = ProomType.eyebrow, color = ProomColor.accent)
                                Text(
                                    detail.name,
                                    color = ProomColor.text,
                                    fontSize = 20.sp,
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
                                        maxLines = 3,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.padding(top = 8.dp),
                                    )
                                }
                            }
                        }
                    }

                    // ── 播放全部 / 随机 ──
                    item {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            PlayAllButton("播放全部", filled = true, Modifier.weight(1f)) {
                                onPlayTrack(detail, 0, false)
                            }
                            PlayAllButton(
                                "随机",
                                filled = false,
                                Modifier.width(96.dp),
                                // 空歌单点「随机」会抛 NoSuchElement：先判一下
                                enabled = detail.tracks.isNotEmpty(),
                            ) {
                                onPlayTrack(detail, detail.tracks.indices.random(), true)
                            }
                        }
                    }

                    item {
                        Row(
                            Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 6.dp),
                            verticalAlignment = Alignment.Bottom,
                        ) {
                            Text("曲目", style = ProomType.title, color = ProomColor.text)
                            Text(
                                "  ${detail.tracks.size}",
                                style = ProomType.meta,
                                color = ProomColor.muted,
                                modifier = Modifier.padding(bottom = 3.dp),
                            )
                        }
                    }

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
                if (com.projectionroom.ui.theme.LocalProomTv.current) {
                    com.projectionroom.ui.component.TvBackHint(onBack = onBack)
                } else {
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

/* ───────────────────── 小组件 ───────────────────── */

@Composable
private fun PlayAllButton(
    text: String,
    filled: Boolean,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(ProomShape.sm)
    Box(
        modifier
            .height(42.dp)
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
                    modifier = Modifier.size(14.dp),
                )
                Spacer(Modifier.width(6.dp))
            }
            Text(
                text,
                fontSize = 13.sp,
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
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 1.dp)
            .clip(RoundedCornerShape(ProomShape.sm))
            .background(if (current) ProomColor.accentSoft else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(32.dp), contentAlignment = Alignment.CenterStart) {
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
                fontSize = 14.sp,
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
private fun PlaylistSkeleton(topInset: Dp, barH: Dp) {
    Column(Modifier.fillMaxSize().padding(top = topInset + barH + 8.dp)) {
        Row(Modifier.padding(horizontal = 20.dp)) {
            SkeletonBlock(Modifier.width(132.dp).aspectRatio(1f), radius = ProomShape.md)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                SkeletonBlock(Modifier.width(40.dp), height = 10.dp, radius = 5.dp)
                SkeletonBlock(Modifier.fillMaxWidth(0.8f).padding(top = 10.dp), height = 20.dp, radius = 6.dp)
                SkeletonBlock(Modifier.fillMaxWidth(0.4f).padding(top = 8.dp), height = 11.dp, radius = 5.dp)
                SkeletonBlock(Modifier.fillMaxWidth().padding(top = 12.dp), height = 10.dp, radius = 5.dp)
            }
        }
        SkeletonBlock(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 20.dp).height(42.dp),
            radius = ProomShape.sm,
        )
        repeat(8) {
            SkeletonBlock(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp).height(16.dp),
                radius = 5.dp,
            )
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
