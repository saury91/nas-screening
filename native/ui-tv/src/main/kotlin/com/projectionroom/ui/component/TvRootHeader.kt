package com.projectionroom.ui.component

import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.scale
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.projectionroom.ui.theme.ProomColor
import com.projectionroom.ui.theme.ProomSpace
import com.projectionroom.ui.theme.ProomType
import com.projectionroom.ui.theme.TvLayout
import com.projectionroom.ui.theme.ProomSp

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
    /** logo 区固定宽度，保证 8 个 tab 在三个页面上横向位置完全一致 */
    val logoWidth = 150.dp
    /** tab 胶囊之间的间距 */
    val gap = 6.dp
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
) {
    /**
     * 换掉「搜索」这一颗图标的行为。
     *
     * 搜索是**跟着当前 tab 走**的：在电影里搜片名，在直播里搜频道，在音乐里搜歌。
     * 页面自己知道该搜什么，所以由页面接管这颗图标 —— 之前直播页在旁边又挂了
     * 一颗写着「搜索」的胶囊，同一个功能两个入口，还各搜各的。
     */
    fun withSearch(action: () -> Unit): TvRootNav =
        TvRootNav(index, onSelect, action, onNas, onMine)
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
                    // 17sp 的 logo 在 3 米外只剩个白块；20sp 才对得上 15sp 的 tab 文字
                    fontSize = ProomSp(20.sp),
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 2.sp,
                )
                Text(
                    "PRIVATE SCREENING",
                    color = ProomColor.muted,
                    // 9sp 是手机上的装饰字，电视上彻底看不见
                    fontSize = ProomSp(10.5.sp),
                    lineHeight = 13.sp,
                    // 1.6 的字距会让这一行在 150dp 内折成两行，反而更乱
                    letterSpacing = 1.0.sp,
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
                Spacer(Modifier.width(ProomSpace.lg))
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
    val shape = RoundedCornerShape(50)
    // 选中项本身就是金底，光靠描金边看不出焦点 —— 所以焦点统一给「放大 + 白边」，
    // 金色和白色在任何背景上都分得清
    val scale by animateFloatAsState(if (focused) 1.07f else 1f, tween(140), label = "root-tab")
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
                    // 焦点落在已选中的 tab 上时，2dp 白边压在金底上几乎看不出来，
                    // 3dp 才够在 3 米外认出「遥控器在这一颗」
                    Modifier.border(3.dp, if (selected) Color.White else ProomColor.accent, shape)
                } else Modifier
            )
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick)
            // 11/7 撑出来的胶囊只有 32dp 高，遥控器聚焦时几乎看不出选中范围
            .padding(horizontal = 14.dp, vertical = 9.dp),
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

private enum class HeaderIconKind { Mine, Server, Search }

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
    val tint = if (focused) ProomColor.accent else ProomColor.dim
    Box(
        Modifier
            .then(
                if (downTarget != null) Modifier.focusProperties { down = downTarget } else Modifier
            )
            // 40dp 的圆形图标位小于 10-foot UI 的可点目标下限
            .size(TvLayout.minTouch)
            .clip(RoundedCornerShape(50))
            .background(if (focused) ProomColor.surface2 else Color.Transparent)
            .then(if (focused) Modifier.border(2.dp, ProomColor.accent, RoundedCornerShape(50)) else Modifier)
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(19.dp)) {
            when (kind) {
                HeaderIconKind.Mine -> {
                    val w = 1.7.dp.toPx()
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
                    val w = 1.6.dp.toPx()
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
                    val w = 1.9.dp.toPx()
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
