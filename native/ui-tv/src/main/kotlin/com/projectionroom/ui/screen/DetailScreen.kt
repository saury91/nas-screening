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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.projectionroom.shared.model.Vod
import com.projectionroom.ui.component.TvRow
import com.projectionroom.ui.theme.ProomColor
import com.projectionroom.ui.util.metaLine
import com.projectionroom.ui.util.plainText
import com.projectionroom.ui.theme.ProomMotion
import com.projectionroom.ui.theme.ProomShape
import com.projectionroom.ui.theme.ProomSpace
import com.projectionroom.ui.theme.ProomType
import com.projectionroom.ui.theme.TvLayout
import com.projectionroom.ui.theme.TvType
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

    Column(Modifier.fillMaxSize()) {
        // 顶栏只留返回提示：收藏是「对这部片的动作」，跟立即播放放一起才对，
        // 挂到右上角会离它要作用的对象太远，读起来像个页面级的设置开关。
        //
        // 顶栏**不能**塞进下面的滚动列：跟着一起滚走之后，遥控器的返回键看不见了，
        // 而且焦点请求会把整页顶上去（截图里标题被切掉一截、返回提示完全消失）。
        com.projectionroom.ui.component.TvPageHeader(
            onBack = onBack,
            topInset = topInset,
            inset = TvLayout.insetX,
            // 这一页顶栏压在模糊封面上：默认那层 9% 白在暖色亮图上会糊成一片，
            // 返回胶囊像浮在半空；垫一层半透明黑，和播放器同一套
            hintBackdrop = Color.Black.copy(alpha = 0.42f),
        )

        // 正文可滚：960×540 的画布上「海报 + 简介 + 两行线路 + 选集」装不下，
        // 之前用 weight(1f) 把选集网格挤在剩余高度里 —— 剩余高度是 0，选集会被整块裁掉
        // （截图里「选集 · 13 集」只剩半个标题）。电视上的详情页本来就该是滚动的。
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = TvLayout.insetX)
                .padding(bottom = ProomSpace.xl)
        ) {
        Spacer(Modifier.height(ProomSpace.sm))

        Row(Modifier.fillMaxWidth()) {
            Poster(vod, Modifier.width(posterW))
            Spacer(Modifier.width(ProomSpace.lg))
            // 显式宽度，不用 weight(1f)：滚动容器里带权重的子项会被按
            // 「剩余高度」重新测量，40sp 的片名量出来只有 11dp，上半截直接被切掉
            BoxWithConstraints(Modifier.weight(1f)) {
                Column(Modifier.width(maxWidth)) {
                // 片名是这一页的唯一主角：40sp 才压得住下面的 26sp 区块标题。
                // 都用 30sp 时，片名和「播放线路」看起来是同级的两行字。
                // maxLines 必须是 1：在这个纵向滚动容器里，2 行的行高会被算成 11dp
                // （片名只露出一条底边，像被裁掉了）。40sp 下 656dp 宽能放 16 个字，
                // 够用；再接长就省略号，不要为了两行把标题切没。
                Text(
                    vod.name,
                    fontSize = 40.sp,
                    fontWeight = FontWeight.SemiBold,
                    // 显式行高：在 verticalScroll 里文本的 lineHeight 会按「剩余高度」
                    // 重新测量，量出来比字形还矮，片名上半截被切（截图里只剩一条底边）。
                    // 40sp 配 48sp 行高，任何名字都不会切。
                    lineHeight = 48.sp,
                    color = ProomColor.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = buildString {
                        append(metaLine(vod.year, vod.type, vod.area, vod.lang))
                        if (vod.lines.isNotEmpty()) append(" · ${vod.lines.size} 条线路")
                    },
                    style = ProomType.meta,
                    color = ProomColor.muted,
                )
                if (vod.desc.plainText().isNotBlank()) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        vod.desc.plainText(),
                        // 简介是观众真正会读的一段字：用正文级 18sp，并限宽到 640dp
                        // （960dp 画布铺满的话一行 50 多个字，眼睛要来回扫）
                        style = ProomType.body,
                        color = ProomColor.dim,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 640.dp),
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
        }

        Spacer(Modifier.height(ProomSpace.xl))

        Text("播放线路", style = ProomType.title, color = ProomColor.text)
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
        // 每行 4 张、每张 200dp：164dp 时站名只剩 5 个字，
        // 「极速资源 · 线路1」被截成「极速资源 · 线…」—— 而「线路1」正是
        // 区分两条线唯一的字，截掉等于全叫「极速资源」。
        Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
            vod.lines.chunked(4).forEach { row ->
                TvRow(
                    items = row,
                    itemWidth = 200.dp,
                    gap = 10.dp,
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
            style = ProomType.title,
            color = ProomColor.text,
        )
        Spacer(Modifier.height(8.dp))
        if (line != null) {
            // 集号按钮最小 96dp：电视上一排十几个小方块根本瞄不准。
            // 这里不用 LazyVerticalGrid —— 外层已经是 verticalScroll，
            // 再套一个可滚动容器会抢方向键（进页后按↓不动，焦点在网格里自己滚）
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val cols = (maxWidth / 96.dp).toInt().coerceIn(4, 12)
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    line.episodes.chunked(cols).forEachIndexed { row, eps ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            eps.forEachIndexed { i, ep ->
                                EpButton(ep.name, Modifier.weight(1f)) {
                                    onPlay(lineIndex, row * cols + i)
                                }
                            }
                            repeat(cols - eps.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
            }
            Spacer(Modifier.height(ProomSpace.lg))
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
            // 48dp 是电视上的最小可点目标：40dp 的圆钮在 3 米外要瞄两下，
            // 而它就紧挨着「立即播放」，瞄偏一点就点进了播放
            .size(TvLayout.minTouch)
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
            .background(
                when {
                    selected -> ProomColor.accentSoft
                    focused -> ProomColor.surface2
                    else -> ProomColor.surface
                }
            )
            .then(
                when {
                    // 「金色 = 选中，白色 = 焦点」全线统一：焦点一律白边，
                    // 未选中的那颗原来用金边，和左边「已选中」的卡分不出来
                    focused -> Modifier.border(if (selected) 3.dp else 2.dp, Color.White, shape)
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
                // 这是控件标签不是海报标题：20sp 的字阶让 200dp 只能放 7 个字，
                // 降到正文级 18sp 正好多挤出一个字
                style = ProomType.body,
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
    // 分隔符两侧的空格去掉：卡片只有 8 个字的宽度，`极速资源 · 线路1` 光空格
    // 就吃掉两个位，压成 `极速资源·线路1` 才装得下「线路1」这个关键区分
    return parts.joinToString("·")
        .replace(Regex("\\s*[-–—]\\s*"), "·")
        .ifBlank { "线路" }
}

@Composable
private fun EpButton(name: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(ProomShape.sm)
    Box(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (focused) ProomColor.surface2 else ProomColor.surface)
            .then(if (focused) Modifier.border(2.dp, ProomColor.accent, shape) else Modifier)
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick)
            // 38dp 的集号方块在沙发上按十次有两次按空，52dp 才是遥控器友好尺寸
            .heightIn(min = 52.dp)
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(name, style = ProomType.meta, color = ProomColor.text, maxLines = 1)
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
    size: androidx.compose.ui.unit.Dp = TvLayout.minTouch,
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
            modifier = Modifier.size(23.dp),
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
    // 金底上描 2dp 白边在 540p 的电视机上基本看不出来 —— 描边面积太小，
    // 又被金色本身的高亮度压住。改成聚焦时整块底色翻白（文字仍用深色），
    // 一眼就能确认「现在按确认会播」，也跟全局「白色 = 焦点」对上。
    val scale by animateFloatAsState(
        if (focused) 1.04f else 1f,
        tween(ProomMotion.short, easing = ProomMotion.ease),
        label = "button-scale",
    )
    Box(
        modifier
            .scale(scale)
            .clip(shape)
            .background(if (focused) Color.White else background)
            .then(if (focused) Modifier.border(2.dp, focusBorderColor, shape) else Modifier)
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick)
            .padding(horizontal = contentPadding.h.dp, vertical = contentPadding.v.dp),
    ) {
        Text(label, style = ProomType.body, color = labelColor)
    }
}
