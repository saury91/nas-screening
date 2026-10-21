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
import androidx.compose.foundation.layout.widthIn
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
import com.projectionroom.ui.component.TvRow
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
    // 电视的逻辑尺寸差别很大（960x540 / 1280x720 / 1920x1080 都有），
    // 海报宽度按可用高度取比例，避免在 4K 盒子上显得只有一小块
    val posterW = (maxHeight * 0.26f).coerceIn(110.dp, 280.dp)

    // ── 氛围层 ──
    // `Modifier.blur` 在 API<31 是空操作，很多电视盒子正好跑在 30，靠它只会得到
    // 「270px 海报拉大 7 倍」的脏糊。SoftBackdrop 用小尺寸解码 + GPU 放大绕开平台差异。
    // decodeWidth 必须和首页（TvHero）保持一致：之前这里是 38dp，比首页「化得开」的
    // 6dp 清晰得多，屏幕上还能认出五官，跟首页并排看就是两套视觉。
    if (vod.pic.isNotBlank()) {
        // clipToBounds 不能省：放大后的图层若不裁剪，会溢出到这块 Box 下面的
        // 选集区，那里没有渐变盖着，看起来就是下半屏突然冒出一块亮的图
        Box(
            Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.72f)
                .align(Alignment.TopStart)
                .clipToBounds()
        ) {
            com.projectionroom.ui.component.SoftBackdrop(
                model = vod.pic,
                modifier = Modifier.fillMaxSize(),
                decodeWidth = 24.dp,
                brightness = 0.72f,
            )
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(
                        // 顶部压得更狠一点：返回提示和标题都压在这张模糊封面上，
                        // 遇上亮封面（白墙、雪景、人脸特写）0.30 的暗度根本不够，
                        // 11sp 的小字会直接糊进背景
                        0f to ProomColor.bg.copy(alpha = 0.52f),
                        0.32f to ProomColor.bg.copy(alpha = 0.42f),
                        0.68f to ProomColor.bg.copy(alpha = 0.78f),
                        1f to ProomColor.bg.copy(alpha = 1f),
                    )
                )
            )
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = ProomSpace.xxl)
            .padding(bottom = ProomSpace.xl)
    ) {
        // 顶栏只留返回提示：收藏是「对这部片的动作」，跟立即播放放一起才对，
        // 挂到右上角会离它要作用的对象太远，读起来像个页面级的设置开关。
        // 父级已经给了 40dp 左右内边距，所以 inset 传 0；顶部让开状态栏（原来 8dp 起步，
        // 会把返回提示压在时钟那一行上）。
        com.projectionroom.ui.component.TvPageHeader(
            onBack = onBack,
            topInset = topInset,
            inset = 0.dp,
        )

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
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(14.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FocusBox(
                        background = ProomColor.accent,
                        onClick = { if (line?.episodes?.isNotEmpty() == true) onPlay(lineIndex, 0) },
                        contentPadding = Padding(20, 11),
                        label = "立即播放",
                        labelColor = Color(0xFF1B1206),
                        modifier = Modifier.focusRequester(firstFocus),
                        focusBorderColor = Color.White,
                    )
                    // 收藏紧跟主操作：它是「对这部片」的第二个动作，不是页面级开关。
                    // 图标而不是文字：状态型操作，靠形状就能读（实心=已收），
                    // 也不会和主按钮抢视觉权重
                    FocusIcon(
                        icon = if (isFavorite) {
                            com.projectionroom.ui.component.TabIcons.HeartOn
                        } else {
                            com.projectionroom.ui.component.TabIcons.Heart
                        },
                        desc = if (isFavorite) "取消收藏" else "收藏",
                        active = isFavorite,
                        onClick = onToggleFavorite,
                    )
                }
            }
        }

        Spacer(Modifier.height(ProomSpace.xl))

        Text("播放线路", style = ProomType.cardTitle, color = ProomColor.dim)
        Spacer(Modifier.height(12.dp))
        if (vod.lines.isEmpty()) {
            // 线路是逐个源回来的：先给一句话，别让这里空着像「没有资源」
            Text(
                if (loadingLines) "正在获取线路…" else "这部片暂时没有可播线路",
                style = ProomType.meta,
                color = ProomColor.dim.copy(alpha = 0.7f),
                modifier = Modifier.padding(vertical = 18.dp),
            )
        }
        // 排成网格，每行 5 张。
        //
        // 之前是一整条横向滑轨：9 条线路在电视上只放得下 4 张半，
        // 第 5 张永远被屏幕右边缘切掉一半 —— 看着像没画完。现在常见条数一眼看全，
        // 既不用滑，也不会再出现「半张卡」。
        Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
            vod.lines.chunked(5).forEach { row ->
                TvRow(
                    items = row,
                    itemWidth = 164.dp,
                    gap = 9.dp,
                ) { l, focused ->
                    val idx = vod.lines.indexOf(l)
                    LineChip(
                        index = idx,
                        site = l.site,
                        quality = l.quality,
                        count = l.count,
                        selected = idx == lineIndex,
                        focused = focused,
                        onClick = { lineIndex = idx },
                    )
                }
            }
        }

        Spacer(Modifier.height(ProomSpace.lg))

        Text(
            text = line?.let { "选集 · ${it.count} 集 · ${cleanSiteName(it.site)}" } ?: "选集",
            style = ProomType.cardTitle,
            color = ProomColor.dim,
        )
        Spacer(Modifier.height(8.dp))
        if (line != null) {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(80.dp),
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


/** 收藏图标按钮：实心 + 金色 = 已收藏，描边 + 灰 = 未收藏 */
@Composable
private fun FavIconButton(active: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(40.dp)
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
            modifier = Modifier.size(19.dp),
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

/**
 * 线路卡片。
 *
 * 之前是固定 150dp 的小方块，两行字塞在里面：名字一长就被省略成「极速资源…」，
 * 六张排在一起只剩标点不一样；选中态只有 1dp 半透明描边，坐远了根本看不出选的是哪条。
 * 现在定宽、高度统一，左边补序号 —— 名字再像，序号也能分开；一排排满时右边缘也是齐的。
 */
@Composable
private fun LineChip(
    index: Int,
    site: String,
    quality: String,
    count: Int,
    selected: Boolean,
    focused: Boolean,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(ProomShape.sm)
    Row(
        Modifier
            .fillMaxWidth()
            .height(54.dp)
            .clip(shape)
            .background(if (selected) ProomColor.accentSoft else ProomColor.surface)
            .then(
                when {
                    focused -> Modifier.border(2.dp, ProomColor.accent, shape)
                    // 选中的描边要看得见：1dp 半透明在电视上等于没有
                    selected -> Modifier.border(1.5.dp, ProomColor.accent.copy(alpha = 0.7f), shape)
                    else -> Modifier
                }
            )
            // TvRow 已经负责 focusable/焦点态，这里只处理点击
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            (index + 1).toString().padStart(2, '0'),
            style = ProomType.label,
            color = if (selected) ProomColor.accent else ProomColor.muted.copy(alpha = 0.45f),
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                cleanSiteName(site),
                style = ProomType.cardTitle,
                color = if (selected) ProomColor.accent else ProomColor.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                buildString {
                    if (quality.isNotBlank()) append(quality).append(" · ")
                    append("$count 集")
                },
                style = ProomType.label,
                color = ProomColor.muted,
                maxLines = 1,
            )
        }
    }
}

/**
 * 清洗采集站的站名。
 *
 * 这些名字是站点写给自己看的：`🎥┃光速┃资源`、`🚀小马 · 影视 | 天涯`、`🍯PG线路 · 🍯PG线路 ·`。
 * 竖线是刷出来的边框（半角 | 与全角 ｜ ┃ 都有），`·` 是「站点 · 线路」的分隔要留。
 */
private fun cleanSiteName(raw: String): String {
    // 采集站的站名是给自己看的，装饰符号一堆：`🎥┃光速┃资源`、`🚀小马 · 影视 | 天涯`。
    // 竖线是刷出来的边框（半角 | 和全角 ｜ ┃ 都有），统一换成空格再压平。
    val flat = raw.replace(Regex("[┃｜|]"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()
    // 也有把名字写两遍的：`🍯PG线路 · 🍯PG线路 ·`
    val parts = flat.split(" · ").map { it.trim() }.filter { it.isNotEmpty() }.distinct()
    return parts.joinToString(" · ").ifBlank { "线路" }
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

/**
 * 圆形图标按钮（返回 / 收藏）。
 *
 * 和 [FocusBox] 的区别：这里表达的是**状态**或**导航**，不是一条文案，
 * 所以用图标 + 圆形热区；焦点态靠放大和金色描边，不换底色 ——
 * 在模糊封面的背景上，换底色反而看不清焦点在哪。
 */
@Composable
private fun FocusIcon(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    desc: String,
    active: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: androidx.compose.ui.unit.Dp = 44.dp,
    /** 返回键不需要底色（压在模糊封面上会多一块圆斑），收藏需要 */
    background: Color = ProomColor.surface.copy(alpha = 0.55f),
) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        if (focused) 1.08f else 1f,
        tween(ProomMotion.short, easing = ProomMotion.ease),
        label = "icon-scale",
    )
    Box(
        modifier
            .scale(scale)
            .size(size)
            .clip(CircleShape)
            .background(if (active) ProomColor.accentSoft else background)
            .then(if (focused) Modifier.border(2.dp, ProomColor.accent, CircleShape) else Modifier)
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.material3.Icon(
            imageVector = icon,
            contentDescription = desc,
            tint = if (active) ProomColor.accent else ProomColor.text,
            modifier = Modifier.size(21.dp),
        )
    }
}


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
