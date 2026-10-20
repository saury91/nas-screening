package com.projectionroom.ui.screen

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.unit.dp
import com.projectionroom.shared.model.Vod
import com.projectionroom.ui.component.PosterCard
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.projectionroom.ui.component.NAV_TABS
import com.projectionroom.ui.component.TvHero
import com.projectionroom.ui.component.WideCard
import com.projectionroom.shared.catalog.NasApi
import androidx.compose.ui.unit.Dp
import com.projectionroom.ui.component.TvNavBar
import com.projectionroom.ui.component.TvRow
import com.projectionroom.ui.theme.ProomColor
import com.projectionroom.ui.theme.ProomSpace
import com.projectionroom.ui.theme.ProomType

/**
 * 电视首页：搜索入口 + 「继续观看」+「最近更新」两条货架。
 *
 * 数据全部来自 NAS 的 `/api/home`，没有硬编码样例。
 */
@Composable
fun TvHome(
    continueWatching: List<Vod> = emptyList(),
    latest: List<Vod> = emptyList(),
    loading: Boolean = false,
    cats: List<NasApi.CatGroup> = emptyList(),
    navIndex: Int = 0,
    onNav: (String, String?) -> Unit = { _, _ -> },
    onMore: (String, String?) -> Unit = { _, _ -> },
    onOpen: (Vod) -> Unit = {},
    /** 「立即播放」：直接进播放器，不经过详情页 */
    onPlay: (Vod) -> Unit = onOpen,
    onSearch: () -> Unit = {},
    onNas: () -> Unit = {},
    onMine: () -> Unit = {},
    /** 状态栏高度：电视一般没有，个别盒子/模拟器有，顶栏内容要避开 */
    topInset: Dp = 0.dp,
    listState: androidx.compose.foundation.lazy.LazyListState = rememberLazyListState(),
) {
    // 从详情页返回时，整个 TvHome 会重新组合，Compose 不会帮你把焦点找回来 ——
    // 遥控器上表现为「方向键全都没反应」。所以每次进场都主动把焦点打到导航首项。
    val landing = remember { FocusRequester() }
    // 顶栏是浮层（不在 LazyColumn 的焦点树里），几何寻路找不到下面的 Hero 按钮，
    // 所以给两个方向各钉一个锚点：胶囊 ↓ → 立即播放，Hero 按钮 ↑ → 胶囊。
    val heroPlay = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        // 等一帧，LazyColumn 的项还没测量时 requestFocus 会静默失败
        withFrameNanos { }
        runCatching { landing.requestFocus() }
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(ProomColor.bg)) {
        // 卡片宽度同时受宽高约束：宽度管「一排几张」，高度管「标题塞不塞得进屏幕」
        val byWidth = maxWidth * 0.135f
        val byHeight = maxHeight * 0.22f
        val cardW = minOf(byWidth, byHeight).coerceIn(104.dp, 240.dp)
        // 横版卡更宽：一屏 4~5 张比较接近 Web 版
        // 海报源图只有 270x385，裁成 16:9 后横向只剩 270px；卡片别超过 300dp
        val wideCardW = (maxWidth * 0.16f).coerceIn(160.dp, 300.dp)

        // Hero 已经展示了前 6 条，下面的「最近更新」不再重复
        val heroItems = latest.take(6)
        val latestRest = latest.drop(heroItems.size)

        androidx.compose.foundation.lazy.LazyColumn(
            Modifier.fillMaxSize(),
            state = listState,
            contentPadding = PaddingValues(bottom = ProomSpace.xxl),
        ) {
            // Hero 顶到屏幕上沿、导航浮在它上面，首屏才是一整块画面。
            // 高度从 0.66 提到 0.76：原来那 0.1 是被顶栏吃掉的空间。
            item {
                if (latest.isNotEmpty()) {
                    TvHero(
                        items = heroItems,
                        height = maxHeight * 0.76f,
                        topInset = TOP_BAR_HEIGHT + topInset,
                        playFocusRequester = if (latest.isNotEmpty()) heroPlay else null,
                        navFocusRequester = landing,
                        onPlay = onPlay,
                        onDetail = { v -> onOpen(v) },
                    )
                }
            }

            if (loading) {
                item {
                    Text(
                        text = "正在读 NAS…",
                        style = ProomType.label,
                        color = ProomColor.muted,
                        modifier = Modifier.padding(horizontal = ProomSpace.xxl, vertical = ProomSpace.sm),
                    )
                }
            }

            if (continueWatching.isNotEmpty()) {
                item {
                    SectionTitle("继续观看", "从上次停下的地方接着看")
                    TvRow(
                        items = continueWatching.take(10),
                        itemWidth = wideCardW,
                        gap = ProomSpace.md,
                        contentPadding = PaddingValues(horizontal = ProomSpace.xxl, vertical = ProomSpace.sm),
                    ) { v, focused ->
                        WideCard(
                            vod = v,
                            width = wideCardW,
                            focused = focused,
                            onClick = { onOpen(v) },
                        )
                    }
                    Spacer(Modifier.height(ProomSpace.lg))
                }
            }

            if (latestRest.isNotEmpty()) {
                item {
                    SectionTitle("最近更新", "")
                    TvRow(
                        items = latestRest.take(14),
                        itemWidth = cardW,
                        gap = ProomSpace.md,
                        contentPadding = PaddingValues(horizontal = ProomSpace.xxl, vertical = ProomSpace.sm),
                    ) { v, focused ->
                        PosterCard(
                            title = v.name,
                            meta = listOf(v.year, v.type).filter { it.isNotBlank() }.joinToString(" · "),
                            poster = v.pic,
                            focused = focused,
                            onClick = { onOpen(v) },
                        )
                    }
                }
            }

            cats.forEach { group ->
                item {
                    CategoryShelf(
                        group = group,
                        cardW = cardW,
                        onOpen = onOpen,
                        onMore = { onMore(group.cat, null) },
                    )
                }
            }

            if (!loading && continueWatching.isEmpty() && latest.isEmpty()) {
                item {
                Box(Modifier.fillMaxWidth().height(300.dp), contentAlignment = Alignment.Center) {
                    Text("NAS 里还没有可展示的内容", style = ProomType.meta, color = ProomColor.muted)
                }
                }
            }
        }

        // 顶栏浮在 Hero 上：不再单独占一条高度，首屏就是一整块画面。
        // 滚动后货架会从它下面穿过，所以垫一层极淡的竖向渐变把字压住（在暗色 Hero 上几乎看不出来）。
        TvTopBar(
            navIndex = navIndex,
            landing = landing,
            downTarget = if (latest.isNotEmpty()) heroPlay else null,
            onNav = onNav,
            onSearch = onSearch,
            onNas = onNas,
            onMine = onMine,
            topInset = topInset,
        )
    }
}

/** 顶栏高度。Hero 的内容靠它避让，改这里就够了。 */
private val TOP_BAR_HEIGHT = 60.dp

@Composable
private fun TvTopBar(
    navIndex: Int,
    landing: FocusRequester,
    downTarget: FocusRequester?,
    onNav: (String, String?) -> Unit,
    onSearch: () -> Unit,
    onNas: () -> Unit,
    onMine: () -> Unit,
    topInset: Dp = 0.dp,
) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(TOP_BAR_HEIGHT + topInset)
            .background(
                androidx.compose.ui.graphics.Brush.verticalGradient(
                    0f to ProomColor.bg.copy(alpha = 0.80f),
                    0.55f to ProomColor.bg.copy(alpha = 0.34f),
                    1f to Color.Transparent,
                )
            )
            .padding(start = ProomSpace.xxl, end = ProomSpace.xxl, top = topInset),
        contentAlignment = Alignment.CenterStart,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // logo：白字 + 极小字距的英文副标，和 Web 版一致
            Column(Modifier.width(150.dp)) {
                Text(
                    "放映室",
                    color = ProomColor.text,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 2.sp,
                )
                Text(
                    "PRIVATE SCREENING",
                    color = ProomColor.muted,
                    fontSize = 9.sp,
                    lineHeight = 11.sp,
                    letterSpacing = 1.8.sp,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                NAV_TABS.forEachIndexed { i, (label, slug) ->
                    NavPillPublic(
                        label = label,
                        selected = i == navIndex,
                        focusRequester = if (i == navIndex) landing else null,
                        downTarget = downTarget,
                    ) { onNav(label, slug) }
                }
            }
            Spacer(Modifier.weight(1f))
            // 只有两个入口：换 NAS（低频）+ 搜索（高频）。
            // 「管理」类的东西一律不在电视上做 —— 产品边界。
            MineIcon(downTarget = downTarget, onClick = onMine)
            Spacer(Modifier.width(6.dp))
            ServerIcon(downTarget = downTarget, onClick = onNas)
            Spacer(Modifier.width(6.dp))
            SearchIcon(downTarget = downTarget, onClick = onSearch)
        }
    }
}

@Composable
private fun SectionTitle(title: String, sub: String) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = ProomSpace.xxl),
        verticalAlignment = Alignment.Bottom,
    ) {
        Text(title, style = ProomType.title, color = ProomColor.text)
        if (sub.isNotBlank()) {
            Text(
                sub,
                style = ProomType.meta,
                color = ProomColor.muted,
                modifier = Modifier.padding(start = 10.dp, bottom = 2.dp),
            )
        }
    }
}

@Composable
private fun SearchEntry(onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(ProomSpace.md)
    Row(
        Modifier
            .clip(shape)
            .background(ProomColor.surface)
            .then(if (focused) Modifier.border(2.dp, ProomColor.accent, shape) else Modifier)
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("搜索片名、演员…", style = ProomType.body, color = ProomColor.muted)
    }
}


@Composable
private fun NavPillPublic(
    label: String,
    selected: Boolean,
    focusRequester: FocusRequester? = null,
    downTarget: FocusRequester? = null,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(50)
    // 选中项本身就是金底，光靠描金边看不出焦点 —— 所以焦点统一给「放大 + 白边」，
    // 金色和白色在任何背景上都分得清
    val scale by animateFloatAsState(if (focused) 1.07f else 1f, tween(140))
    Box(
        Modifier
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .then(
                if (downTarget != null) Modifier.focusProperties { down = downTarget } else Modifier
            )
            .scale(scale)
            .clip(shape)
            .background(
                when {
                    selected -> ProomColor.accent
                    focused -> ProomColor.surface2
                    else -> Color.Transparent
                }
            )
            .then(
                if (focused) {
                    Modifier.border(2.dp, if (selected) Color.White else ProomColor.accent, shape)
                } else Modifier
            )
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick)
            .padding(horizontal = 11.dp, vertical = 7.dp),
    ) {
        Text(
            text = label,
            style = ProomType.meta,
            color = when {
                selected -> Color(0xFF1B1206)
                focused -> ProomColor.text
                else -> ProomColor.muted
            },
        )
    }
}

/** 顶栏右侧的搜索入口：只画一个放大镜。
 *
 * 不写「搜索」两个字 —— 图标是通用语言，还省掉一块横向空间，
 * 顶栏越安静，Hero 的画面感越强。
 */
@Composable
private fun SearchIcon(downTarget: FocusRequester? = null, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Box(
        Modifier
            .then(
                if (downTarget != null) Modifier.focusProperties { down = downTarget } else Modifier
            )
            .size(40.dp)
            .clip(RoundedCornerShape(50))
            .background(if (focused) ProomColor.surface2 else Color.Transparent)
            .then(if (focused) Modifier.border(2.dp, ProomColor.accent, RoundedCornerShape(50)) else Modifier)
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.foundation.Canvas(Modifier.size(17.dp)) {
            val w = 1.9.dp.toPx()
            val c = androidx.compose.ui.geometry.Offset(size.width * 0.42f, size.height * 0.42f)
            val r = size.minDimension * 0.31f
            drawCircle(
                color = if (focused) ProomColor.accent else ProomColor.dim,
                radius = r,
                center = c,
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = w),
            )
            drawLine(
                color = if (focused) ProomColor.accent else ProomColor.dim,
                start = androidx.compose.ui.geometry.Offset(c.x + r * 0.70f, c.y + r * 0.70f),
                end = androidx.compose.ui.geometry.Offset(size.width * 0.93f, size.height * 0.93f),
                strokeWidth = w,
                cap = androidx.compose.ui.graphics.StrokeCap.Round,
            )
        }
    }
}

/**
 * 「我的」图标：一个头 + 一道肩。
 *
 * 电视上没有底部 tab，收藏和记录只能从这里进 —— 所以它是常驻入口，不是设置。
 */
@Composable
private fun MineIcon(downTarget: FocusRequester? = null, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val tint = if (focused) ProomColor.accent else ProomColor.dim
    Box(
        Modifier
            .then(
                if (downTarget != null) Modifier.focusProperties { down = downTarget } else Modifier
            )
            .size(40.dp)
            .clip(RoundedCornerShape(50))
            .background(if (focused) ProomColor.surface2 else Color.Transparent)
            .then(if (focused) Modifier.border(2.dp, ProomColor.accent, RoundedCornerShape(50)) else Modifier)
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.foundation.Canvas(Modifier.size(17.dp)) {
            val w = 1.7.dp.toPx()
            val stroke = androidx.compose.ui.graphics.drawscope.Stroke(
                width = w,
                cap = androidx.compose.ui.graphics.StrokeCap.Round,
            )
            drawCircle(
                color = tint,
                radius = size.minDimension * 0.175f,
                center = androidx.compose.ui.geometry.Offset(size.width * 0.5f, size.height * 0.28f),
                style = stroke,
            )
            drawArc(
                color = tint,
                startAngle = 195f,
                sweepAngle = 150f,
                useCenter = false,
                topLeft = androidx.compose.ui.geometry.Offset(size.width * 0.17f, size.height * 0.52f),
                size = androidx.compose.ui.geometry.Size(size.width * 0.66f, size.height * 0.58f),
                style = stroke,
            )
        }
    }
}

/**
 * 「换 NAS」图标：两个叠起来的机箱（服务器）。
 *
 * 不用文字 —— 顶栏越安静，Hero 的画面感越强；这个入口一周也用不到一次。
 */
@Composable
private fun ServerIcon(downTarget: FocusRequester? = null, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val tint = if (focused) ProomColor.accent else ProomColor.dim
    Box(
        Modifier
            .then(
                if (downTarget != null) Modifier.focusProperties { down = downTarget } else Modifier
            )
            .size(40.dp)
            .clip(RoundedCornerShape(50))
            .background(if (focused) ProomColor.surface2 else Color.Transparent)
            .then(if (focused) Modifier.border(2.dp, ProomColor.accent, RoundedCornerShape(50)) else Modifier)
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.foundation.Canvas(Modifier.size(17.dp)) {
            val w = 1.6.dp.toPx()
            val corner = androidx.compose.ui.geometry.CornerRadius(2.dp.toPx())
            val barH = size.height * 0.30f
            val stroke = androidx.compose.ui.graphics.drawscope.Stroke(width = w)
            // 上下两块机箱
            drawRoundRect(
                color = tint,
                topLeft = androidx.compose.ui.geometry.Offset(w * 0.5f, size.height * 0.10f),
                size = androidx.compose.ui.geometry.Size(size.width - w, barH),
                cornerRadius = corner,
                style = stroke,
            )
            drawRoundRect(
                color = tint,
                topLeft = androidx.compose.ui.geometry.Offset(w * 0.5f, size.height * 0.58f),
                size = androidx.compose.ui.geometry.Size(size.width - w, barH),
                cornerRadius = corner,
                style = stroke,
            )
            // 每块的左侧指示灯
            drawCircle(tint, radius = w * 0.75f,
                center = androidx.compose.ui.geometry.Offset(size.width * 0.20f, size.height * 0.25f))
            drawCircle(tint, radius = w * 0.75f,
                center = androidx.compose.ui.geometry.Offset(size.width * 0.20f, size.height * 0.73f))
        }
    }
}


/** 一个分类货架：标题带总数，右侧「查看全部」跳片库 */
@Composable
private fun CategoryShelf(
    group: NasApi.CatGroup,
    cardW: Dp,
    onOpen: (Vod) -> Unit,
    onMore: () -> Unit,
) {
    Column {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = ProomSpace.xxl),
            verticalAlignment = Alignment.Bottom,
        ) {
            Text(group.cat, style = ProomType.title, color = ProomColor.text)
            Text(
                "${group.count} 部",
                style = ProomType.meta,
                color = ProomColor.muted,
                modifier = Modifier.padding(start = 10.dp, bottom = 2.dp),
            )
            Spacer(Modifier.weight(1f))
            MoreLink(onMore)
        }
        Spacer(Modifier.height(ProomSpace.sm))
        TvRow(
            items = group.items.take(14),
            itemWidth = cardW,
            gap = ProomSpace.md,
            contentPadding = PaddingValues(horizontal = ProomSpace.xxl, vertical = ProomSpace.sm),
        ) { v, focused ->
            PosterCard(
                title = v.name,
                meta = listOf(v.year, v.type).filter { it.isNotBlank() }.joinToString(" · "),
                poster = v.pic,
                focused = focused,
                onClick = { onOpen(v) },
            )
        }
        Spacer(Modifier.height(ProomSpace.lg))
    }
}

@Composable
private fun MoreLink(onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(50)
    Box(
        Modifier
            .clip(shape)
            .background(if (focused) ProomColor.surface2 else Color.Transparent)
            .then(if (focused) Modifier.border(1.dp, ProomColor.accent, shape) else Modifier)
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text("查看全部 ›", style = ProomType.label, color = if (focused) ProomColor.accent else ProomColor.muted)
    }
}
