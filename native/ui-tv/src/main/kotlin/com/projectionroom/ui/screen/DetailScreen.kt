package com.projectionroom.ui.screen

import androidx.compose.animation.core.animateFloatAsState
import com.projectionroom.ui.component.tvFocusRing
import com.projectionroom.ui.component.tvFocusBloom
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
import androidx.compose.ui.draw.drawWithContent
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
import androidx.compose.ui.unit.sp
import com.projectionroom.shared.model.Vod
import com.projectionroom.ui.component.TvRow
import com.projectionroom.ui.theme.ProomColor
import com.projectionroom.ui.theme.TvRatio
import com.projectionroom.ui.theme.TvIcon
import com.projectionroom.ui.theme.TvSpace
import com.projectionroom.ui.util.metaLine
import com.projectionroom.ui.util.plainText
import com.projectionroom.ui.theme.ProomMotion
import com.projectionroom.ui.theme.ProomShape
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
    val scrollState = rememberScrollState()

    LaunchedEffect(vod.name) {
        // 节点可能还没挂上，重试几次
        repeat(20) {
            if (runCatching { firstFocus.requestFocus() }.isSuccess) {
                // 焦点落定后把页面拉回顶部。焦点系统的 bringIntoView 会把「立即播放」
                // 往上顶，一进页面就自动滚掉 50dp 左右 —— 40sp 的片名正好滑到顶栏底下，
                // 页面上从「2027 · 国产剧…」开始，看起来像根本没有片名。
                scrollState.scrollTo(0)
                return@LaunchedEffect
            }
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
    // 右侧信息列的宽度：海报之外全部给它。这里必须算出来 —— DSL 作用域里的
    // maxWidth 一进嵌套的 Row/Column 就取不到了
    val rightW = maxWidth - posterW - TvSpace.s20 - TvLayout.insetX * 2
    // 线路卡 4 列：宽度按可用宽度算，不写死 200dp ——
    // 写死时 4×200+3×12=836，容器 864，右边多出 28dp，整排看起来是歪的。
    // 列间距 16dp 而不是 12dp：聚焦卡的白环要往外鼓 3dp + 一圈柔光，
    // 12dp 的缝一填满，环就贴在邻居的金色选中框上，两圈线叠成一道。
    val lineGap = TvSpace.s16
    val lineCardW = (maxWidth - TvLayout.insetX * 2 - lineGap * 3) / 4

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
                // 遮罩必须画在 verticalScroll **外层**：写在内层的话坐标系跟着内容滚，
                // 那道渐隐会一起被推到屏幕外，等于没画（第一版就是这么翻的车）。
                //
                // 而且 alpha 要跟着滚动进度起：静止时标题、海报顶就压在这条带子上，
                // 常亮的话首屏片名会比下面的字灰一档（看着像没加载完）。
                .drawWithContent {
                    drawContent()
                    // 滚过 1/3 屏才完全遮住，滚动量本身就是渐变进度
                    val mask = (scrollState.value / 160f).coerceIn(0f, 1f)
                    if (mask > 0.01f) {
                        drawRect(
                            brush = Brush.verticalGradient(
                                0f to ProomColor.bg,
                                1f to Color.Transparent,
                                startY = 0f,
                                endY = 72.dp.toPx(),
                            ),
                            alpha = mask,
                        )
                    }
                }
                .verticalScroll(scrollState)
                .padding(horizontal = TvLayout.insetX)
                .padding(bottom = TvSpace.s24)
        ) {
        Spacer(Modifier.height(TvSpace.s8))

        Row(Modifier.fillMaxWidth()) {
            Poster(vod, Modifier.width(posterW))
            Spacer(Modifier.width(TvSpace.s20))
            // 右侧这一列**给死宽度**，既不用 weight 也不套 BoxWithConstraints：
            // 在 verticalScroll 里，这两种写法都会把文字量成 0 高 —— 40sp 的片名
            // 整行消失，界面上直接从「2028 · 国产剧…」那行开始，片名像根本不存在
            // （只剩海报上那个占位首字）。宽度本来就已知，算出来最稳。
            Column(Modifier.width(rightW)) {
                // 片名是这一页的唯一主角：40sp 才压得住下面的 24sp 区块标题。
                // 都用 30sp 时，片名和「播放线路」看起来是同级的两行字。
                Text(
                    vod.name,
                    // 档位取自 display（40/48），但用「参数」而不是 style 传：
                    // 在 verticalScroll 里 style 会带 lineHeightStyle，字被按行高重新
                    // 量成一条比字形还窄的盒子，整行裁掉 —— 只剩一片空白（真机复现多次）。
                    fontSize = ProomType.display.fontSize,
                    lineHeight = ProomType.display.lineHeight,
                    fontWeight = ProomType.display.fontWeight,
                    color = ProomColor.text,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(TvSpace.s8))
                Text(
                    text = buildString {
                        append(metaLine(vod.year, vod.type, vod.area, vod.lang))
                        if (vod.lines.isNotEmpty()) append(" · ${vod.lines.size} 条线路")
                    },
                    style = ProomType.meta,
                    color = ProomColor.muted,
                )
                if (vod.desc.plainText().isNotBlank()) {
                    Spacer(Modifier.height(TvSpace.s12))
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
                // 简介排满 3 行时，末行离下面的金色按钮只有 16dp，
                // 字贴着按钮上沿像压上去；拉到 20dp 才读得出一段话结束了
                Spacer(Modifier.height(TvSpace.s20))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(TvSpace.s12),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FocusBox(
                        background = ProomColor.accent,
                        onClick = { if (line?.episodes?.isNotEmpty() == true) onPlay(lineIndex, 0) },
                        contentPadding = Padding(20, 11),
                        label = "立即播放",
                        labelColor = Color(0xFF1B1206),
                        modifier = Modifier.focusRequester(firstFocus),
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

        Spacer(Modifier.height(TvSpace.s24))

        Text("播放线路", style = ProomType.title, color = ProomColor.text)
        Spacer(Modifier.height(TvSpace.s12))
        if (vod.lines.isEmpty()) {
            // 线路是逐个源回来的：先给一句话，别让这里空着像「没有资源」
            Text(
                if (loadingLines) "正在获取线路…" else "这部片暂时没有可播线路",
                style = ProomType.meta,
                color = ProomColor.dim.copy(alpha = 0.7f),
                modifier = Modifier.padding(vertical = TvSpace.s20),
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
        Column(verticalArrangement = Arrangement.spacedBy(TvSpace.s8)) {
            vod.lines.chunked(4).forEach { row ->
                TvRow(
                    items = row,
                    itemWidth = lineCardW,
                    gap = lineGap,
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

        Spacer(Modifier.height(TvSpace.s20))

        Text(
            text = line?.let { "选集 · ${it.count} 集 · ${cleanSiteName(it.site)}" } ?: "选集",
            style = ProomType.title,
            color = ProomColor.text,
        )
        Spacer(Modifier.height(TvSpace.s8))
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
            Spacer(Modifier.height(TvSpace.s20))
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
            // 原来这颗收藏钮完全无焦点态，遥控器停在上面和不停在上面长得一样
            .tvFocusRing(CircleShape, ringWidth = 2.dp, zoom = 1f)
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
            modifier = Modifier.size(TvIcon.toolbar),
        )
    }
}

@Composable
private fun Poster(vod: Vod, modifier: Modifier) {
    Box(
        modifier
            .aspectRatio(TvRatio.poster)
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
            .height(TvLayout.minTouch)
            // 焦点环必须画在 clip 之前：clip 会把它剪掉，而它本来就要鼓到格子外面。
            // 环走外扩而不是内收 —— 内收的那 3dp 正好压在序号和集数上，
            // 截图里「18」被白环穿过去，读起来像描边画歪了。格子之间横 12dp、
            // 竖 8dp 的缝足够环外扩（3dp），不需要牺牲内容。
            .then(
                if (focused) {
                    Modifier.tvFocusBloom(shape, bloom = 1f, ringWidth = 2.dp, color = Color.White)
                } else {
                    Modifier
                }
            )
            .clip(shape)
            .background(
                when {
                    selected -> ProomColor.accentSoft
                    focused -> ProomColor.surface2
                    else -> ProomColor.surface
                }
            )
            // 选中描边只在没焦点时画：聚焦时已经有白环，再留一圈金边就是「双框」，
            // 那块地方看起来像两个控件叠在一起。金色的选中语义由底色 + 金序号承担。
            .then(
                if (selected && !focused) {
                    Modifier.border(1.dp, ProomColor.accent.copy(alpha = 0.7f), shape)
                } else {
                    Modifier
                }
            )
            // TvRow 已经负责 focusable/焦点态，这里只处理点击
            .clickable(onClick = onClick)
            .padding(horizontal = TvSpace.s12),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            (index + 1).toString().padStart(2, '0'),
            style = ProomType.label,
            color = if (selected) ProomColor.accent else ProomColor.muted.copy(alpha = 0.45f),
        )
        Spacer(Modifier.width(TvSpace.s12))
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
            Spacer(Modifier.height(TvSpace.s4))
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
            // 集号方块相邻排布，环内收才不会压到隔壁
            .tvFocusRing(shape, ringWidth = 2.dp, zoom = 1f, inset = true)
            .clip(shape)
            .background(if (focused) ProomColor.surface2 else ProomColor.surface)
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick)
            // 38dp 的集号方块在沙发上按十次有两次按空，52dp 才是遥控器友好尺寸
            .heightIn(min = 52.dp)
            .padding(vertical = TvSpace.s12),
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
 * 所以用图标 + 圆形热区；焦点态靠白环，不换底色 ——
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
    // 焦点态只靠白环，不缩放：48dp 的圆钮放大 8% 就是 52dp，
    // 和左边「立即播放」的柔光在同一片区域里互相叠，看起来像两个控件抢位
    Box(
        modifier
            .size(size)
            .tvFocusRing(CircleShape, ringWidth = 2.dp, zoom = 1f)
            .clip(CircleShape)
            .background(if (active) ProomColor.accentSoft else background)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.material3.Icon(
            imageVector = icon,
            contentDescription = desc,
            tint = if (active) ProomColor.accent else ProomColor.text,
            modifier = Modifier.size(TvIcon.toolbar),
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
) {
    val shape = RoundedCornerShape(ProomShape.md)
    // 聚焦只加白环，不再叠 1.04 缩放：
    // 环本身已经往外鼓了 3dp，再整体放大就是把「鼓」画了两遍 ——
    // 主按钮离收藏钮只有 12dp，放大后的柔光正好压到那颗心形上，两颗按钮粘在一起。
    Box(
        modifier
            // 白环外扩落在页面底色上，中间是深缝，金底白环不会糊在一起
            .tvFocusRing(shape, ringWidth = 2.dp, zoom = 1f)
            .clip(shape)
            .background(background)
            .clickable(onClick = onClick)
            .padding(horizontal = contentPadding.h.dp, vertical = contentPadding.v.dp),
    ) {
        Text(label, style = ProomType.body, color = labelColor)
    }
}
