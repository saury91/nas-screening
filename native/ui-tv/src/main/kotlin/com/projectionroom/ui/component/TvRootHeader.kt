package com.projectionroom.ui.component

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.projectionroom.ui.music.MusicBox
import com.projectionroom.ui.theme.ProomColor
import com.projectionroom.ui.theme.ProomShape
import com.projectionroom.ui.theme.TvIcon
import com.projectionroom.ui.theme.TvLayout
import com.projectionroom.ui.theme.TvSpace
import com.projectionroom.ui.theme.ProomType
import com.projectionroom.ui.theme.ProomSp
import kotlinx.coroutines.isActive

/**
 * 电视端**一级导航**：这 8 项是 tab，不是页面。
 *
 * 之前每一次点击都会 push 一个 `Screen.Browse`，于是每个 tab 都得配一条
 * 「‹ 返回 电影」的顶栏 —— 用户看着一排 tab 却被告知要用返回键退出去，
 * 这本身就是矛盾的。
 *
 * 现在只有一个顶栏（[TvRootHeader]），tab 切换只换下面的内容区：
 *  - 首页：顶栏浮在 Hero 上（`floating = true`，纯渐变底）
 *  - 片库 / 直播 / 音乐：顶栏固定在内容上方（实底，不随列表滚动）
 *  - 这些页面**不再有返回**，遥控器 BACK 一律回首页
 */
object TvRootHeaderSpec {
    /** 顶栏高度（不含状态栏） */
    val height = 64.dp
    /** 内容左边缘：和 [TvPageHeaderSpec.inset] 同一个数（48dp = 960dp 画布的 5% 安全区） */
    val inset = TvLayout.insetX
    /**
     * logo 区固定宽度，保证 8 个 tab 在三个页面上横向位置完全一致。
     *
     * 168 = 副标「PRIVATE SCREENING」在 13sp + 1sp 字距下的实测宽度（约 153dp）
     * 加 15dp 呼吸。写成 150 时副标会溢出列宽、贴在第一个 tab 的底色上 ——
     * 文字溢出不会报错，只会在真机上显得顶栏很挤。
     */
    val logoWidth = 168.dp
    /** tab 胶囊之间的间距 */
    val gap = TvSpace.s8
}

/** 顶部分类。顺序和 Web 版一致，slug 对应 `/api/browse?cat=`。 */
val NAV_TABS = listOf(
    "首页" to null,
    "电影" to "movie",
    "剧集" to "series",
    "动漫" to "anime",
    "综艺" to "variety",
    "纪录片" to "doc",
    "直播" to "live",
    "音乐" to "music",
)

/**
 * 一级导航的运行时状态：由壳（MainActivity）提供，页面只负责画。
 *
 * [index] 是 [NAV_TABS] 的下标 —— 用下标而不是 slug，是因为「首页」的 slug 是 null，
 * 用 null 表示「没有选中」会丢状态。-1 表示「不在顶栏里的分类」，此时不点亮任何 tab。
 */
class TvRootNav(
    val index: Int,
    val onSelect: (label: String, slug: String?) -> Unit,
    val onSearch: () -> Unit,
    val onNas: () -> Unit,
    val onMine: () -> Unit,
    /**
     * 顶栏「正在播放」唱片被点中时去哪（全屏播放页）。
     *
     * 有队列才画这颗唱片，所以不需要再传「播放状态」进来 ——
     * 转不转由组件自己读 [MusicBox.playing] 决定，壳只管导航。
     */
    val onNowPlaying: (() -> Unit)? = null,
) {
    /**
     * 换掉「搜索」这一颗图标的行为。
     *
     * 搜索是**跟着当前 tab 走**的：在电影里搜片名，在直播里搜频道，在音乐里搜歌。
     * 页面自己知道该搜什么，所以由页面接管这颗图标 —— 之前直播页在旁边又挂了
     * 一颗写着「搜索」的胶囊，同一个功能两个入口，还各搜各的。
     */
    fun withSearch(action: () -> Unit): TvRootNav =
        TvRootNav(index, onSelect, action, onNas, onMine, onNowPlaying)
}

/**
 * 电视端一级导航条。
 *
 * @param nav 导航状态（选中项 + 四个点击回调）
 * @param topInset 状态栏高度
 * @param floating 浮在内容上（首页 Hero）：用渐变压底，不画实底色
 * @param landing 初始焦点（首页给选中 tab，其它页不给 → 焦点落在内容第一项）
 * @param downTarget 遥控器按「下」跳到哪里（首页跳 Hero 的播放按钮）
 * @param trailing 右侧附加信息，排在三个图标之前（一般不用：tab 行的原则是只留导航）
 */
@Composable
fun TvRootHeader(
    nav: TvRootNav,
    topInset: Dp = 0.dp,
    floating: Boolean = false,
    landing: FocusRequester? = null,
    downTarget: FocusRequester? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    // 页面自己没给初始焦点时，兜底把焦点放到「当前选中的 tab」上。
    // 不给的话，切到直播/纪录片的瞬间焦点会掉到整棵树第一个可聚焦的节点 ——
    // 也就是「首页」那颗 tab：光标停在首页、再按一下 OK 又跳回首页。
    val fallbackLanding = remember { FocusRequester() }
    val target = landing ?: fallbackLanding
    if (landing == null) {
        LaunchedEffect(nav.index) {
            withFrameNanos { }
            runCatching { fallbackLanding.requestFocus() }
        }
    }
    Box(
        modifier
            .fillMaxWidth()
            .height(TvRootHeaderSpec.height + topInset)
            .background(
                if (floating) {
                    Brush.verticalGradient(
                        0f to ProomColor.bg.copy(alpha = 0.80f),
                        0.55f to ProomColor.bg.copy(alpha = 0.34f),
                        1f to Color.Transparent,
                    )
                } else {
                    Brush.verticalGradient(listOf(ProomColor.bg, ProomColor.bg))
                }
            )
            .padding(start = TvRootHeaderSpec.inset, end = TvRootHeaderSpec.inset, top = topInset),
        contentAlignment = Alignment.CenterStart,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // logo：白字 + 极小字距的英文副标，和 Web 版一致
            Column(Modifier.width(TvRootHeaderSpec.logoWidth)) {
                Text(
                    "放映室",
                    color = ProomColor.text,
                    // 字标用 title 档（24sp）：17sp 在 3 米外只剩个白块
                    style = ProomType.title.copy(letterSpacing = 2.sp),
                )
                Text(
                    "PRIVATE SCREENING",
                    color = ProomColor.muted,
                    // 9sp 是手机上的装饰字，电视上彻底看不见；label 档是弱一级的下限
                    style = ProomType.label.copy(letterSpacing = 1.sp),
                    maxLines = 1,
                    softWrap = false,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(TvRootHeaderSpec.gap)) {
                NAV_TABS.forEachIndexed { i, (label, slug) ->
                    RootTab(
                        label = label,
                        selected = i == nav.index,
                        focusRequester = if (i == nav.index) target else null,
                        downTarget = downTarget,
                    ) { nav.onSelect(label, slug) }
                }
            }
            Spacer(Modifier.weight(1f))
            if (trailing != null) {
                trailing()
                Spacer(Modifier.width(TvSpace.s20))
            }
            // 正在播放的唱片：离开播放页之后唯一的「歌还在响」的可见证据。
            // 放在「我的」左边而不是塞进 tab 行 —— tab 是导航，这个是有状态的控件，
            // 混在一排文字里会被当成第九个分类
            if (nav.onNowPlaying != null && MusicBox.hasQueue) {
                NowPlayingDisc(
                    onClick = nav.onNowPlaying,
                    downTarget = downTarget,
                )
                Spacer(Modifier.width(TvRootHeaderSpec.gap))
            }
            HeaderIcon(HeaderIconKind.Mine, downTarget, nav.onMine)
            Spacer(Modifier.width(TvRootHeaderSpec.gap))
            HeaderIcon(HeaderIconKind.Server, downTarget, nav.onNas)
            Spacer(Modifier.width(TvRootHeaderSpec.gap))
            HeaderIcon(HeaderIconKind.Search, downTarget, nav.onSearch)
        }
    }
}

@Composable
private fun RootTab(
    label: String,
    selected: Boolean,
    focusRequester: FocusRequester? = null,
    downTarget: FocusRequester? = null,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    // 10dp 圆角块，不是 50% 胶囊。两个字（「首页」）在 48dp 高时用 50% 半径会算成
    // 一颗 54×48 的金椭圆 —— 顶栏里最重的一块，和最轻的 15sp 纯文字 tab 差了一个量级。
    val shape = RoundedCornerShape(ProomShape.sm)
    Box(
        Modifier
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .then(
                if (downTarget != null) Modifier.focusProperties { down = downTarget } else Modifier
            )
            // 焦点环 2dp：和全站一致（原来 3dp，顶栏这一颗比页面里所有环都粗）
            .tvFocusRing(shape, ringWidth = 2.dp, zoom = 1f, color = Color.White)
            .clip(shape)
            .background(if (focused) ProomColor.surface2 else Color.Transparent)
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick)
            // 内边距 + 下面的 heightIn 兜住 48dp 可点目标：只靠内边距会随字阶漂
            .heightIn(min = TvLayout.minTouch)
            .padding(horizontal = TvSpace.s12),
        contentAlignment = Alignment.Center,
    ) {
        // 当前页不再画成一块金砖：金底 + 白环同时亮时，顶栏上有两个「按下去」的东西，
        // 分不清哪个是位置、哪个是光标。改成「金字 + 一条金线」——
        // 位置靠颜色和线，焦点靠环，两套语言不打架，整排 tab 的重量也一致了。
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = label,
                style = ProomType.meta,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = when {
                    selected -> ProomColor.accent
                    focused -> ProomColor.text
                    else -> ProomColor.muted
                },
            )
            Spacer(Modifier.height(TvSpace.s4))
            Box(
                Modifier
                    .width(if (selected) 18.dp else 0.dp)
                    .height(3.dp)
                    .clip(RoundedCornerShape(ProomShape.xs))
                    .background(if (selected) ProomColor.accent else Color.Transparent)
            )
        }
    }
}

private enum class HeaderIconKind { Mine, Server, Search }

/**
 * 顶栏的「正在播放」唱片。
 *
 * 用户离开播放页之后，这个 App 里就再没有任何地方能看出「歌还在放」——
 * 迷你条只活在歌单页左栏，切到首页或电影就消失了。所以顶栏要有一个常驻入口，
 * 而且它必须是**会动的**：静止的圆形图标和「我的」「搜索」没有区别，没人会去点它。
 *
 * 旋转用 [Animatable] 而不是 `rememberInfiniteTransition`：后者在 `playing`
 * 变 false 时把角度归零，唱片会「倒转回去」一下再停住；Animatable 的动画
 * 被取消时停在当前角度，暂停就是暂停。
 */
@Composable
private fun NowPlayingDisc(
    onClick: () -> Unit,
    downTarget: FocusRequester? = null,
) {
    val track = MusicBox.current ?: return
    var focused by remember { mutableStateOf(false) }
    val playing = MusicBox.playing

    val angle = remember { Animatable(0f) }
    LaunchedEffect(playing) {
        if (!playing) return@LaunchedEffect
        while (isActive) {
            angle.animateTo(
                targetValue = angle.value + 360f,
                // 9 秒一圈：再快就成了加载指示器，转盘该是慢的
                animationSpec = tween(9000, easing = LinearEasing),
            )
        }
    }

    Box(
        Modifier
            .then(
                if (downTarget != null) Modifier.focusProperties { down = downTarget } else Modifier
            )
            .size(TvLayout.minTouch)
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(TvIcon.display)
                // 焦点环画在旋转层外面：跟着唱片一起转会像碟片在打滑
                .tvFocusRing(CircleShape, ringWidth = 2.dp, zoom = 1f)
                .clip(CircleShape)
                .background(ProomColor.card),
            contentAlignment = Alignment.Center,
        ) {
            // 封面上留一圈黑胶边（26 在 34 里）：整块铺满的话，
            // 旋转时只有画面在动，读不出「这是张唱片」
            Box(
                Modifier.fillMaxSize().graphicsLayer { rotationZ = angle.value },
                contentAlignment = Alignment.Center,
            ) {
                if (track.cover.isNotBlank()) {
                    RemoteImage(
                        model = track.cover,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(TvIcon.toolbar).clip(CircleShape),
                    )
                } else {
                    Icon(
                        imageVector = TabIcons.Music,
                        contentDescription = null,
                        tint = ProomColor.dim,
                        modifier = Modifier.size(TvIcon.inline),
                    )
                }
            }
            // 中心孔 + 一小段金色弧。
            //
            // 光靠封面转是不够的：专辑封面常常是纯色块或浅色图，圆形裁切后
            // 转起来和静止的图标没有区别（实测两帧只差 1% 像素，肉眼看不见）。
            // 这条 62° 的弧给「在转」提供了一个固定的参照物 —— 暂停时它停在原地，
            // 用户一眼就能分辨这张唱片是转着的还是停着的。
            Box(
                Modifier
                    .size(TvSpace.s8)
                    .clip(CircleShape)
                    .background(ProomColor.bg.copy(alpha = 0.92f))
            )
            Canvas(Modifier.fillMaxSize()) {
                rotate(angle.value) {
                    val inset = 1.5.dp.toPx()
                    val side = size.minDimension - inset * 2
                    drawArc(
                        color = ProomColor.accent,
                        startAngle = 0f,
                        sweepAngle = 62f,
                        useCenter = false,
                        topLeft = Offset(inset, inset),
                        size = Size(side, side),
                        style = Stroke(width = 1.8.dp.toPx(), cap = StrokeCap.Round),
                    )
                }
            }
        }
    }
}

/**
 * 顶栏右侧的圆形图标位（我的 / 换 NAS / 搜索）。
 *
 * 只画图标不写字：图标是通用语言，还省掉一块横向空间，
 * 顶栏越安静，Hero 的画面感越强。
 */
@Composable
private fun HeaderIcon(
    kind: HeaderIconKind,
    downTarget: FocusRequester? = null,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    // 焦点只提亮到主文字色，不变金
    val tint = if (focused) ProomColor.text else ProomColor.dim
    Box(
        Modifier
            .then(
                if (downTarget != null) Modifier.focusProperties { down = downTarget } else Modifier
            )
            // 40dp 的圆形图标位小于 10-foot UI 的可点目标下限
            .size(TvLayout.minTouch)
            .tvFocusRing(RoundedCornerShape(50), ringWidth = 2.dp, zoom = 1f)
            .clip(RoundedCornerShape(50))
            .background(if (focused) ProomColor.surface2 else Color.Transparent)
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(TvIcon.control)) {
            when (kind) {
                HeaderIconKind.Mine -> {
                    val w = 1.8.dp.toPx()
                    val stroke = Stroke(width = w, cap = StrokeCap.Round)
                    drawCircle(
                        color = tint,
                        radius = size.minDimension * 0.175f,
                        center = Offset(size.width * 0.5f, size.height * 0.28f),
                        style = stroke,
                    )
                    drawArc(
                        color = tint,
                        startAngle = 195f,
                        sweepAngle = 150f,
                        useCenter = false,
                        topLeft = Offset(size.width * 0.17f, size.height * 0.52f),
                        size = Size(size.width * 0.66f, size.height * 0.58f),
                        style = stroke,
                    )
                }

                HeaderIconKind.Server -> {
                    val w = 1.8.dp.toPx()
                    val corner = CornerRadius(2.dp.toPx())
                    val barH = size.height * 0.30f
                    val stroke = Stroke(width = w)
                    drawRoundRect(
                        color = tint,
                        topLeft = Offset(w * 0.5f, size.height * 0.10f),
                        size = Size(size.width - w, barH),
                        cornerRadius = corner,
                        style = stroke,
                    )
                    drawRoundRect(
                        color = tint,
                        topLeft = Offset(w * 0.5f, size.height * 0.58f),
                        size = Size(size.width - w, barH),
                        cornerRadius = corner,
                        style = stroke,
                    )
                    // 每块的左侧指示灯
                    drawCircle(
                        tint,
                        radius = w * 0.75f,
                        center = Offset(size.width * 0.20f, size.height * 0.25f),
                    )
                    drawCircle(
                        tint,
                        radius = w * 0.75f,
                        center = Offset(size.width * 0.20f, size.height * 0.73f),
                    )
                }

                HeaderIconKind.Search -> {
                    val w = 1.8.dp.toPx()
                    val c = Offset(size.width * 0.42f, size.height * 0.42f)
                    val r = size.minDimension * 0.31f
                    drawCircle(color = tint, radius = r, center = c, style = Stroke(width = w))
                    drawLine(
                        color = tint,
                        start = Offset(c.x + r * 0.70f, c.y + r * 0.70f),
                        end = Offset(size.width * 0.93f, size.height * 0.93f),
                        strokeWidth = w,
                        cap = StrokeCap.Round,
                    )
                }
            }
        }
    }
}
