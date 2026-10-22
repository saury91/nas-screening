package com.projectionroom.ui.component

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.projectionroom.ui.theme.ProomColor
import com.projectionroom.ui.theme.ProomMotion
import com.projectionroom.ui.theme.ProomType
import com.projectionroom.ui.theme.TvLayout

/**
 * 统一的「返回」条：左上角一个 chevron，手机点、遥控器也能聚焦。
 *
 * 为什么抽成组件：分类/音乐/直播这三个页面是照手机的 tab 结构做的，
 * 搬到电视壳里复用时谁也没给它们补返回，结果只有遥控器的 BACK 键能退
 * （遥控器没返回键、或者用触摸电视盒的时候就是死路）。
 * 现在所有非根页面共用这一条，位置/形态/焦点表现都不再各写各的。
 *
 * 注意：
 *  - 这里**故意不抢初始焦点**。进页面就该落在内容上（第一张卡/第一个筛选项），
 *    而不是「返回」——不然遥控器第一下按 OK 直接退出去了。
 *  - 高度固定 44dp，和详情页顶栏、播放器顶栏同一档。
 */
@Composable
fun PageBackBar(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    /** 状态栏高度：有状态栏的设备（模拟器/部分盒子）要让开，否则 chevron 压在时钟上 */
    topInset: Dp = 0.dp,
) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        if (focused) 1.08f else 1f,
        tween(ProomMotion.short, easing = ProomMotion.ease),
        label = "page-back-scale",
    )
    Row(
        modifier.fillMaxWidth().padding(top = topInset).height(44.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .scale(scale)
                .size(44.dp)
                .clip(CircleShape)
                .then(if (focused) Modifier.border(2.dp, ProomColor.accent, CircleShape) else Modifier)
                .onFocusChanged { focused = it.isFocused }
                .clickable(onClick = onBack),
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
}

/**
 * 电视端页面顶栏的**唯一**规格。所有非根页面（分类 / 直播 / 音乐 / 歌单 /
 * 我的 / 设置 / 配对 / 换 NAS / 搜索 / 详情 / 错误页）都只走 [TvPageHeader]，
 * 不允许再各写各的 Row —— 之前就是各写各的，出现了 6 种左边距
 * （4 / 12 / 20 / 40 + 绝对定位）、3 种字号、有的有文字有的只有箭头，
 * 甚至两个页面完全没有返回入口。
 */
object TvPageHeaderSpec {
    /** 内容左边缘 = 安全区 48dp（960dp 画布下的 5%），和首页 rail、分类页、详情页同一个数 */
    val inset = TvLayout.insetX
    /** 顶栏高度（不含状态栏）：返回提示与标题垂直居中；48dp 的返回热区塞得下 */
    val height = 64.dp
    /** 返回提示与标题之间的水平间距 */
    val gap = 20.dp
}

/**
 * 电视端页面顶栏：`‹ 返回` 按钮 + 页面标题 + 右侧动作，规格固定。
 *
 * 返回**进焦点环**，和右侧动作一样是「按 OK 会发生什么」的候选项。
 *
 * 曾经两次走偏，都记在这里：
 *  1. 做成裸的灰字面包屑（小字 + 小 chevron）——读起来像标题的注脚，
 *     3 米外看不见，遥控器也选不中；
 *  2. 做成胶囊却 `canFocus = false` —— 长得像按钮、聚焦时毫无反应，
 *     用户会以为坏了。
 *
 * 可聚焦的代价是它抢初始焦点（进页面第一下 OK 直接退回去），
 * 所以在 [TvBackHint] 里首帧让位、页面自己的首项再 `requestFocus`。
 *
 * @param title 页面标题；传 null 表示这一屏不需要标题（搜索 / 详情 / 错误页）
 * @param titleAlpha 标题透明度，歌单页滚动时淡入用
 * @param trailing 右侧动作（「我的」的两个胶囊、设置页的 loading 圈）
 */
@Composable
fun TvPageHeader(
    onBack: (() -> Unit)?,
    title: String? = null,
    topInset: Dp = 0.dp,
    modifier: Modifier = Modifier,
    titleAlpha: Float = 1f,
    /** 左边距。父容器已经给了 40dp 内边距时传 0，避免叠成 80 */
    inset: Dp = TvPageHeaderSpec.inset,
    /** 返回胶囊的文字/箭头颜色：压在视频画面上时传亮色 */
    hintColor: Color = ProomColor.text,
    /** 返回提示的半透明底：视频左上角常有台标，光靠颜色救不回来 */
    hintBackdrop: Color? = null,
    trailing: (@Composable androidx.compose.foundation.layout.RowScope.() -> Unit)? = null,
) {
    Row(
        modifier
            .fillMaxWidth()
            .padding(top = topInset)
            .height(TvPageHeaderSpec.height)
            .padding(horizontal = inset),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            TvBackHint(onBack = onBack, color = hintColor, backdrop = hintBackdrop)
            if (title != null) Spacer(Modifier.width(TvPageHeaderSpec.gap))
        }
        if (title != null) {
            Text(
                text = title,
                style = ProomType.headline,
                color = ProomColor.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.graphicsLayer { alpha = titleAlpha },
            )
        }
        Spacer(Modifier.weight(1f))
        if (trailing != null) trailing()
    }
}

/**
 * 电视端顶栏右侧的胶囊动作（「从手机导入配置」「设置」「搜索」）。
 *
 * 之前每个页面各写一颗：有的是 `PillButton2`（私有的）、有的是 40dp 圆图标，
 * 焦点环粗细也不一样。现在只此一颗。
 */
@Composable
fun TvPillButton(text: String, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(50)
    Box(
        Modifier
            // 可点目标下限 48dp（TvLayout.minTouch），比原来的 8dp 内边距撑出来的 30dp 高一档
            .heightIn(min = TvLayout.minTouch)
            .clip(shape)
            .background(if (focused) ProomColor.accentSoft else ProomColor.surface)
            .then(if (focused) Modifier.border(2.dp, ProomColor.accent, shape) else Modifier)
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 9.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            style = ProomType.meta,
            color = if (focused) ProomColor.accent else ProomColor.text,
        )
    }
}

/**
 * 电视端的返回按钮。
 *
 * 之前是一串裸的灰字：12sp `muted`、没有容器、紧贴在 40sp 的标题左边，
 * 3 米外既看不见，读起来也像标题的注脚而不是「可以退回去」。
 * 现在给它和顶栏右侧动作（[TvPillButton]）同一套材质：surface 胶囊底 + 白字 +
 * 20dp 箭头，页头左右两端才是同一种语言。
 *
 * 它**进焦点环**：长得像按钮却选不中，遥控器往上一推光标就跳过去，
 * 用户会以为这里坏了。聚焦沿用全局规则——整块翻白 + 深色字，
 * 和首页 / 详情页 / 歌单页的主按钮是同一种反馈。
 *
 * 只剩一个例外要处理：它是顶栏里第一颗，若一进页面就能聚焦，
 * 没有显式 `requestFocus` 的页面（比如空态的「我的」）会把初始焦点给它，
 * 遥控器按 OK 就直接退出去。所以首帧先不给焦点资格，过一帧再开放。
 *
 * 不再单独调用：一律通过 [TvPageHeader] 出现，否则又会各写一套位置和字号。
 */
@Composable
fun TvBackHint(
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
    label: String = "返回",
    /** 文字/箭头颜色：压在视频画面上时传亮色 */
    color: Color = ProomColor.text,
    /** 胶囊底色：压在视频画面上时传半透明黑，默认和右侧胶囊动作同一档 surface */
    backdrop: Color? = null,
) {
    if (onBack == null) return
    var focused by remember { mutableStateOf(false) }
    var focusable by remember { mutableStateOf(false) }
    // 页面切换时焦点是被「搬」过来的，不是重新分配：只要这一刻它可聚焦，
    // 就会顺理成章地落到它身上。所以让位不能只让一帧，得让过整段切换动画
    // （350ms），等页面自己的首项把焦点接稳了再开放。
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(350)
        focusable = true
    }
    val scale by animateFloatAsState(
        if (focused) 1.05f else 1f,
        tween(ProomMotion.short, easing = ProomMotion.ease),
        label = "back-scale",
    )
    val shape = RoundedCornerShape(50)
    // 聚焦时底色是白的，字必须跟着翻深，否则白底白字
    val ink = if (focused) Color(0xFF1B1206) else color
    Row(
        modifier
            .scale(scale)
            // 可聚焦目标下限 48dp：40dp 的胶囊按不准，焦点环也贴边
            .heightIn(min = TvLayout.minTouch)
            .focusProperties { canFocus = focusable }
            .clip(shape)
            .background(
                when {
                    focused -> Color.White
                    // 压在视频/封面上时用调用方给的那层半透明黑
                    backdrop != null -> backdrop
                    // 不可聚焦的上一版靠这层提亮撑存在感；现在有聚焦态，
                    // 静态只要够看清轮廓，别和焦点态抢
                    else -> ProomColor.surface2
                }
            )
            .then(
                when {
                    focused -> Modifier.border(2.dp, ProomColor.accent, shape)
                    backdrop == null -> Modifier.border(1.dp, ProomColor.line, shape)
                    else -> Modifier
                }
            )
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onBack)
            .padding(start = 14.dp, top = 10.dp, end = 18.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = TabIcons.Back,
            contentDescription = null,
            tint = ink,
            modifier = Modifier.size(20.dp),
        )
        Text(
            text = label,
            style = ProomType.meta,
            color = ink,
            modifier = Modifier.padding(start = 6.dp),
        )
    }
}
