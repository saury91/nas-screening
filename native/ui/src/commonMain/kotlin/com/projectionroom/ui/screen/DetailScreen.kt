package com.projectionroom.ui.screen

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.derivedStateOf
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraintsScope
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.projectionroom.shared.model.Vod
import com.projectionroom.ui.component.TvRow
import com.projectionroom.ui.theme.LocalProomTv
import com.projectionroom.ui.theme.ProomColor
import com.projectionroom.ui.theme.ProomMotion
import com.projectionroom.ui.theme.ProomShape
import com.projectionroom.ui.theme.ProomSpace
import com.projectionroom.ui.theme.ProomType

/**
 * 详情页：左海报信息，右线路 + 选集。
 *
 * 遥控器语义刻意收紧 —— 只让「播放 / 线路 / 选集」可聚焦，
 * 海报和简介不参与焦点循环，否则方向键要多按好几下才能到想按的地方。
 */
@Composable
fun DetailScreen(
    vod: Vod,
    isFavorite: Boolean = false,
    onToggleFavorite: () -> Unit = {},
    onPlay: (lineIndex: Int, epIndex: Int) -> Unit,
    onBack: () -> Unit = {},
    topInset: androidx.compose.ui.unit.Dp = 0.dp,
) {
    var lineIndex by remember(vod.name) {
        mutableIntStateOf(0)
    }
    val line = vod.lines.getOrNull(lineIndex)
    val isTv = LocalProomTv.current
    val firstFocus = remember { FocusRequester() }

    LaunchedEffect(vod.name) {
        // 节点可能还没挂上，重试几次
        repeat(20) {
            if (runCatching { firstFocus.requestFocus() }.isSuccess) return@LaunchedEffect
            kotlinx.coroutines.delay(16)
        }
    }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(ProomColor.bg)
            .padding(top = topInset)
    ) {
    if (!isTv) {
        PhoneDetail(vod, lineIndex, isFavorite, onToggleFavorite, onSelectLine = { lineIndex = it }, onPlay = onPlay, onBack = onBack)
        return@BoxWithConstraints
    }
    // 电视的逻辑尺寸差别很大（960x540 / 1280x720 / 1920x1080 都有），
    // 海报宽度按可用高度取比例，避免在 4K 盒子上显得只有一小块
    val posterW = if (isTv) (maxHeight * 0.26f).coerceIn(110.dp, 280.dp) else 108.dp
    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = ProomSpace.xxl, vertical = ProomSpace.xl)
    ) {
        Row(Modifier.fillMaxWidth()) {
            Poster(vod, Modifier.width(posterW))
            Spacer(Modifier.width(ProomSpace.lg))
            Column(Modifier.weight(1f)) {
                Text(vod.name, style = ProomType.headline, color = ProomColor.text, maxLines = 2)
                Spacer(Modifier.height(6.dp))
                Text(
                    text = buildString {
                        append(
                            listOf(vod.year, vod.type, vod.area, vod.lang)
                                .filter { it.isNotBlank() }
                                .joinToString(" · ")
                        )
                        if (vod.lines.isNotEmpty()) append(" · ${vod.lines.size} 条线路")
                    },
                    style = ProomType.meta,
                    color = ProomColor.muted,
                )
                if (vod.desc.isNotBlank()) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        vod.desc,
                        style = ProomType.meta,
                        color = ProomColor.dim,
                        maxLines = if (isTv) 3 else 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    FocusBox(
                        background = ProomColor.accent,
                        onClick = { if (line?.episodes?.isNotEmpty() == true) onPlay(lineIndex, 0) },
                        contentPadding = Padding(20, 11),
                        label = "立即播放",
                        labelColor = Color(0xFF1B1206),
                        modifier = Modifier.focusRequester(firstFocus),
                        focusBorderColor = Color.White,
                    )
                    FocusBox(
                        background = if (isFavorite) ProomColor.accentSoft else ProomColor.surface,
                        onClick = onToggleFavorite,
                        contentPadding = Padding(20, 11),
                        label = if (isFavorite) "已收藏" else "收藏",
                        labelColor = if (isFavorite) ProomColor.accent else ProomColor.text,
                    )
                    FocusBox(
                        background = ProomColor.surface,
                        onClick = onBack,
                        contentPadding = Padding(20, 11),
                        label = "返回",
                        labelColor = ProomColor.text,
                    )
                }
            }
        }

        Spacer(Modifier.height(ProomSpace.xl))

        Text("播放线路", style = ProomType.cardTitle, color = ProomColor.dim)
        Spacer(Modifier.height(8.dp))
        TvRow(
            items = vod.lines,
            itemWidth = 150.dp,
            gap = ProomSpace.sm,
        ) { l, focused ->
            val idx = vod.lines.indexOf(l)
            LineChip(
                site = l.site,
                quality = l.quality,
                count = l.count,
                selected = idx == lineIndex,
                focused = focused,
                onClick = { lineIndex = idx },
            )
        }

        Spacer(Modifier.height(ProomSpace.lg))

        Text(
            text = line?.let { "选集 · ${it.count} 集 · ${it.site}" } ?: "选集",
            style = ProomType.cardTitle,
            color = ProomColor.dim,
        )
        Spacer(Modifier.height(8.dp))
        if (line != null) {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(if (isTv) 80.dp else 66.dp),
                modifier = Modifier.fillMaxWidth().weight(1f),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                itemsIndexed(line.episodes) { i, ep ->
                    EpButton(ep.name) { onPlay(lineIndex, i) }
                }
            }
        }
    }
    }
}

/** 竖屏详情：海报居左偏小、信息占满宽，选集多列铺开。 */
@Composable
private fun PhoneDetail(
    vod: Vod,
    lineIndex: Int,
    isFavorite: Boolean,
    onToggleFavorite: () -> Unit,
    onSelectLine: (Int) -> Unit,
    onPlay: (lineIndex: Int, epIndex: Int) -> Unit,
    onBack: () -> Unit,
    topInset: androidx.compose.ui.unit.Dp = 0.dp,
) {    val line = vod.lines.getOrNull(lineIndex)
    val scroll = rememberScrollState()
    // 滚过内容标题后顶栏才显示标题，避免与内容片名重复
    // 两段式：背景先变实（浅滚），标题等内容标题滚出视野后才淡入 —— 避免同一屏出现两个片名
    val scrolled by remember { derivedStateOf { scroll.value > 16 } }
    val titleIn by remember { derivedStateOf { scroll.value > 150 } }
    val barSolid by animateFloatAsState(if (scrolled) 1f else 0f, tween(180), label = "detailBar")
    val titleAlpha by animateFloatAsState(if (titleIn) 1f else 0f, tween(220), label = "detailTitle")

    Box(Modifier.fillMaxSize().background(ProomColor.bg)) {
        // 氛围层：海报自身的模糊光，向下渐隐到页面底色
        if (vod.pic.isNotBlank()) {
            androidx.compose.foundation.layout.Box(
                Modifier.fillMaxWidth().height(420.dp).background(ProomColor.bg)
            ) {
                AsyncImage(
                    model = vod.pic,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    alpha = 0.72f,
                    modifier = Modifier.fillMaxSize().blur(46.dp),
                )
                Box(
                    Modifier.fillMaxSize().background(
                        Brush.verticalGradient(
                            0f to ProomColor.bg.copy(alpha = 0.10f),
                            0.45f to ProomColor.bg.copy(alpha = 0.62f),
                            1f to ProomColor.bg,
                        )
                    )
                )
            }
        }

        Column(Modifier.fillMaxSize()) {
            // 顶栏：透明 → 实底，标题淡入（滚动后才出现）
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(56.dp + topInset)
                    .background(ProomColor.bg.copy(alpha = barSolid))
                    .padding(start = 8.dp, end = 20.dp, top = topInset),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.size(48.dp).clip(CircleShape).clickable(onClick = onBack),
                    contentAlignment = Alignment.Center,
                ) {
                    androidx.compose.material3.Icon(
                        imageVector = com.projectionroom.ui.component.TabIcons.Back,
                        contentDescription = "返回",
                        tint = ProomColor.text,
                        modifier = Modifier.size(24.dp),
                    )
                }
                Text(
                    vod.name,
                    style = ProomType.title,
                    color = ProomColor.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 4.dp).alpha(titleAlpha),
                )
            }

            Column(
                Modifier
                    .weight(1f)
                    .verticalScroll(scroll)
                    .padding(horizontal = ProomSpace.lg)
                    .padding(top = ProomSpace.lg, bottom = ProomSpace.lg)
            ) {

            Spacer(Modifier.height(4.dp))
        Row {
            Poster(vod, Modifier.width(104.dp))
            Spacer(Modifier.width(ProomSpace.md))
            Column(Modifier.weight(1f)) {
                Text(vod.name, style = ProomType.title, color = ProomColor.text, maxLines = 2)
                Spacer(Modifier.height(6.dp))
                Text(
                    listOf(vod.year, vod.type, vod.area).filter { it.isNotBlank() }.joinToString(" · "),
                    style = ProomType.label,
                    color = ProomColor.muted,
                )
                if (vod.score.isNotBlank()) {
                    Text("评分 ${vod.score}", style = ProomType.label, color = ProomColor.accent)
                }
            }
        }
        Spacer(Modifier.height(ProomSpace.md))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            FocusBox(
                background = ProomColor.accent,
                onClick = { if (line?.episodes?.isNotEmpty() == true) onPlay(lineIndex, 0) },
                contentPadding = Padding(18, 10),
                label = "立即播放",
                labelColor = Color(0xFF1B1206),
            )
            FocusBox(
                background = if (isFavorite) ProomColor.accentSoft else ProomColor.surface,
                onClick = onToggleFavorite,
                contentPadding = Padding(18, 10),
                label = if (isFavorite) "已收藏" else "收藏",
                labelColor = if (isFavorite) ProomColor.accent else ProomColor.text,
            )
            // 返回已经在顶部标题栏，这里不再重复
        }
        if (vod.desc.isNotBlank()) {
            Spacer(Modifier.height(ProomSpace.md))
            Text(vod.desc, style = ProomType.meta, color = ProomColor.dim, maxLines = 4, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.height(ProomSpace.lg))
        Text("播放线路", style = ProomType.cardTitle, color = ProomColor.dim)
        Spacer(Modifier.height(8.dp))
        // 线路全展开，每行 3 个。之前 take(3) 把 7 条线路藏成 3 条，等于丢了选择权
        vod.lines.chunked(3).forEachIndexed { rowIdx, row ->
            Row(horizontalArrangement = Arrangement.spacedBy(ProomSpace.sm), modifier = Modifier.fillMaxWidth()) {
                row.forEachIndexed { i, l ->
                    val idx = rowIdx * 3 + i
                    LineChip(
                        site = l.site,
                        quality = l.quality,
                        count = l.count,
                        selected = idx == lineIndex,
                        focused = false,
                        onClick = { onSelectLine(idx) },
                        modifier = Modifier.weight(1f),
                    )
                }
                repeat(3 - row.size) { Box(Modifier.weight(1f)) {} }
            }
            Spacer(Modifier.height(8.dp))
        }
        Spacer(Modifier.height(ProomSpace.lg))
        Text("选集 · ${line?.count ?: 0} 集", style = ProomType.cardTitle, color = ProomColor.dim)
        Spacer(Modifier.height(8.dp))
        line?.episodes?.chunked(4)?.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                row.forEachIndexed { idx, ep ->
                    val globalIndex = line.episodes.indexOf(ep)
                    Box(Modifier.weight(1f)) {
                        EpButton(ep.name) { onPlay(lineIndex, globalIndex) }
                    }
                }
                repeat(4 - row.size) { Box(Modifier.weight(1f)) {} }
            }
            Spacer(Modifier.height(8.dp))
        }
            }
        }
    }
}

/** 详情页顶部：返回（48dp 热区）+ 标题一行 */
@Composable
private fun DetailTopBar(title: String, onBack: () -> Unit) {
    androidx.compose.foundation.layout.Row(
        Modifier.fillMaxWidth().height(56.dp).padding(start = 8.dp, end = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(48.dp)
                .clip(androidx.compose.foundation.shape.CircleShape)
                .clickable(onClick = onBack),
            contentAlignment = Alignment.Center,
        ) {
            androidx.compose.material3.Icon(
                imageVector = com.projectionroom.ui.component.TabIcons.Back,
                contentDescription = "返回",
                tint = ProomColor.text,
                modifier = Modifier.size(24.dp),
            )
        }
        Spacer(Modifier.height(0.dp))
        Text(
            title,
            style = ProomType.title,
            color = ProomColor.text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 4.dp),
        )
    }
}

@Composable
private fun Poster(vod: Vod, modifier: Modifier) {
    Box(
        modifier
            .aspectRatio(2f / 3.05f)
            .clip(RoundedCornerShape(ProomShape.md))
            .background(ProomColor.card)
    ) {
        Box(
            Modifier.fillMaxSize().background(
                Brush.linearGradient(listOf(ProomColor.card, ProomColor.bgSoft))
            ),
            contentAlignment = Alignment.Center,
        ) {
            Text(vod.name.take(1), style = ProomType.headline, color = ProomColor.lineStrong)
        }
        if (vod.pic.isNotBlank()) {
            AsyncImage(
                model = vod.pic,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun LineChip(site: String, quality: String, count: Int, selected: Boolean, focused: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(ProomShape.sm)
    Column(
        modifier
            .clip(shape)
            .background(if (selected) ProomColor.accentSoft else ProomColor.surface)
            .then(
                when {
                    focused -> Modifier.border(2.dp, ProomColor.accent, shape)
                    selected -> Modifier.border(1.dp, ProomColor.accent.copy(alpha = 0.55f), shape)
                    else -> Modifier
                }
            )
            // TvRow 已经负责 focusable/焦点态，这里只处理点击
            .clickable(onClick = onClick)
            .padding(horizontal = 11.dp, vertical = 9.dp)
    ) {
        Text(site, style = ProomType.meta, color = ProomColor.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (quality.isNotBlank()) {
                Text(quality, style = ProomType.label, color = ProomColor.accent)
                Spacer(Modifier.width(6.dp))
            }
            Text("$count 集", style = ProomType.label, color = ProomColor.muted)
        }
    }
}

@Composable
private fun EpButton(name: String, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(ProomShape.sm)
    Box(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (focused) ProomColor.surface2 else ProomColor.surface)
            .then(if (focused) Modifier.border(2.dp, ProomColor.accent, shape) else Modifier)
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(name, style = ProomType.label, color = ProomColor.text, maxLines = 1)
    }
}

private data class Padding(val h: Int, val v: Int)

@Composable
private fun FocusBox(
    background: Color,
    label: String,
    labelColor: Color,
    contentPadding: Padding,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    focusBorderColor: Color = ProomColor.accent,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(ProomSpace.md)
    // 金色按钮上再描金边等于没描边：焦点必须换色，并配一点放大才看得清
    val scale by animateFloatAsState(
        if (focused) 1.05f else 1f,
        tween(ProomMotion.short, easing = ProomMotion.ease),
        label = "button-scale",
    )
    Box(
        modifier
            .scale(scale)
            .clip(shape)
            .background(background)
            .then(if (focused) Modifier.border(2.dp, focusBorderColor, shape) else Modifier)
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick)
            .padding(horizontal = contentPadding.h.dp, vertical = contentPadding.v.dp),
    ) {
        Text(label, style = ProomType.body, color = labelColor)
    }
}
