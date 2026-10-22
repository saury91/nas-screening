package com.projectionroom.ui.screen

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.derivedStateOf
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.BlurredEdgeTreatment
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
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.projectionroom.shared.model.Vod
import com.projectionroom.ui.theme.ProomColor
import com.projectionroom.ui.theme.ProomMotion
import com.projectionroom.ui.theme.ProomShape
import com.projectionroom.ui.theme.ProomSpace
import com.projectionroom.ui.theme.ProomType
import com.projectionroom.ui.component.RemoteImage

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
    /** 下拉刷新中：壳层负责真正重拉，这里只画指示器 */
    refreshing: Boolean = false,
    onRefresh: (() -> Unit)? = null,
    /** 线路还在流式加载中（一个源都还没回来）：线路区给个占位而不是空白 */
    loadingLines: Boolean = false,
) {
    var lineIndex by remember(vod.name) {
        mutableIntStateOf(0)
    }
    val line = vod.lines.getOrNull(lineIndex)
    val firstFocus = remember { FocusRequester() }

    LaunchedEffect(vod.name) {
        // 节点可能还没挂上，重试几次
        repeat(20) {
            if (runCatching { firstFocus.requestFocus() }.isSuccess) return@LaunchedEffect
            kotlinx.coroutines.delay(16)
        }
    }

    // 注意：根容器**不能**加 top padding —— 那会把氛围背景一起推下去，
    // 状态栏后面只剩 window 底色（一条黑边）。inset 交给各自的顶栏，
    // 顶栏高度里算上 topInset，内容自然落在状态栏下方。
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(ProomColor.bg)
    ) {
        PhoneDetail(
            vod, lineIndex, isFavorite, onToggleFavorite,
            onSelectLine = { lineIndex = it },
            onPlay = onPlay,
            onBack = onBack,
            // 之前漏了这个参数：顶栏高度只有 56dp，标题和返回键直接顶进了状态栏
            topInset = topInset,
            refreshing = refreshing,
            onRefresh = onRefresh,
            loadingLines = loadingLines,
        )
    }
}

/** 竖屏详情：海报居左偏小、信息占满宽，选集多列铺开。 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
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
    refreshing: Boolean = false,
    onRefresh: (() -> Unit)? = null,
    loadingLines: Boolean = false,
) {
    val line = vod.lines.getOrNull(lineIndex)
    val scroll = rememberScrollState()

    // 跟滚动位置连续变化（不是二态跳变），但把区间压到 0~72px：
    // 手指滑一下就走完，既不会「啪」地变实，也不会像以前那样
    // 滚了半天还停在半透明、灰标题压在图上。
    val barAlpha = (scroll.value / 72f).coerceIn(0f, 1f)
    // 标题比底色晚一点出来（30px 起步、90px 完成），避免刚泛白时字先糊上来
    val titleAlpha = ((scroll.value - 30f) / 60f).coerceIn(0f, 1f)

    // 下拉刷新：详情有 5 分钟缓存，想拿最新的就下拉一次。
    // 不接 onRefresh 时（比如 TV）这个 Box 等于不存在，不会拦截手势。
    androidx.compose.material3.pulltorefresh.PullToRefreshBox(
        isRefreshing = refreshing,
        onRefresh = { onRefresh?.invoke() },
        modifier = Modifier.fillMaxSize(),
    ) {
    Box(Modifier.fillMaxSize().background(ProomColor.bg)) {
        // 氛围层：和电视端、首页用同一套 SoftBackdrop（小尺寸解码 + GPU 放大）。
        // 原来这里是「原图全屏解码 + Modifier.blur(44dp)」：
        //  · blur 在 API<31 是空操作，会在老机器上退化成一张脏糊的大图；
        //  · 原图解码本身就要几十 MB，页面一开就卡一下；
        //  · 44dp 半径不够，屏幕上还能认出构图，跟首页「纯色雾」不是一回事。
        if (vod.pic.isNotBlank()) {
            androidx.compose.foundation.layout.Box(
                Modifier.fillMaxSize().background(ProomColor.bg)
            ) {
                com.projectionroom.ui.component.SoftBackdrop(
                    model = vod.pic,
                    modifier = Modifier.fillMaxSize(),
                    decodeWidth = 24.dp,
                    brightness = 0.72f,
                )
                Box(
                    Modifier.fillMaxSize().background(
                        // 只做轻微压暗：整页色调靠那张模糊海报本身，不能被渐变盖掉
                        Brush.verticalGradient(
                            0f to ProomColor.bg.copy(alpha = 0.08f),
                            0.30f to ProomColor.bg.copy(alpha = 0.34f),
                            0.65f to ProomColor.bg.copy(alpha = 0.62f),
                            1f to ProomColor.bg.copy(alpha = 0.88f),
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
                    // 只铺底色，不画分隔线、不做投影：
                    // 0.5dp 的白线在高密度屏上会渲染成 2px，看着像一条发亮的边框。
                    .drawBehind {
                        if (barAlpha <= 0.01f) return@drawBehind
                        drawRect(ProomColor.bg.copy(alpha = barAlpha))
                    }
                    .padding(start = 4.dp, end = 20.dp, top = topInset),
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
        // 整页只留「海报 + 一栏信息」两块：标题/元信息/简介/按钮全在右栏，
        // 比原来「海报一行、按钮一行、简介一行」省下两整行高度。
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            Poster(vod, Modifier.width(112.dp))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(vod.name, style = ProomType.title, color = ProomColor.text, maxLines = 2)
                Spacer(Modifier.height(4.dp))
                Text(
                    listOf(vod.year, vod.type, vod.area).filter { it.isNotBlank() }.joinToString(" · "),
                    style = ProomType.label,
                    color = ProomColor.muted,
                )
                if (vod.score.isNotBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Text("评分 ${vod.score}", style = ProomType.label, color = ProomColor.accent)
                }
                // 简介限 3 行：源站简介经常几百字，放开会把整页撑变形
                if (vod.desc.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        vod.desc,
                        style = ProomType.meta,
                        color = ProomColor.dim,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(10.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FocusBox(
                        background = ProomColor.accent,
                        onClick = { if (line?.episodes?.isNotEmpty() == true) onPlay(lineIndex, 0) },
                        contentPadding = Padding(18, 9),
                        label = "立即播放",
                        labelColor = Color(0xFF1B1206),
                    )
                    // 收藏改成图标：只占 40dp，且「已收藏」靠实心 + 金色区分，
                    // 不必再占一个「已收藏」的文字按钮宽度
                    FavIconButton(active = isFavorite, onClick = onToggleFavorite)
                    // 返回已经在顶部标题栏，这里不再重复
                }
            }
        }
        Spacer(Modifier.height(ProomSpace.lg))
        Text("播放线路", style = ProomType.cardTitle, color = ProomColor.dim)
        Spacer(Modifier.height(8.dp))
        if (vod.lines.isEmpty()) {
            Text(
                if (loadingLines) "正在获取线路…" else "这部片暂时没有可播线路",
                style = ProomType.meta,
                color = ProomColor.dim.copy(alpha = 0.7f),
                modifier = Modifier.padding(vertical = 12.dp),
            )
        }
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

}

/** 收藏图标按钮：实心 + 金色 = 已收藏，描边 + 灰 = 未收藏 */
@Composable
private fun FavIconButton(active: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            // 它就紧挨着「立即播放」：40dp 时手指偏一点就点进了播放
            .size(48.dp)
            .clip(CircleShape)
            .background(if (active) ProomColor.accentSoft else ProomColor.surface)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.material3.Icon(
            imageVector = if (active) {
                com.projectionroom.ui.component.TabIcons.HeartOn
            } else {
                com.projectionroom.ui.component.TabIcons.Heart
            },
            contentDescription = if (active) "取消收藏" else "收藏",
            tint = if (active) ProomColor.accent else ProomColor.dim,
            modifier = Modifier.size(22.dp),
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
            RemoteImage(
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
