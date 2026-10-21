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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.projectionroom.ui.theme.ProomColor
import com.projectionroom.ui.theme.ProomMotion
import com.projectionroom.ui.theme.ProomType

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
    /** 内容左边缘，和首页 rail、分类页、详情页同一个数 */
    val inset = 40.dp
    /** 顶栏高度（不含状态栏）：返回提示与标题垂直居中 */
    val height = 64.dp
    /** 返回提示与标题之间的水平间距 */
    val gap = 20.dp
}

/**
 * 电视端页面顶栏：`‹ 返回` 提示 + 页面标题 + 右侧动作，规格固定。
 *
 * 返回在电视上**不是按钮**（这是上一版做错的地方）：
 *  1. 遥控器只有一个输入货币：焦点。焦点环里每一项都应该是「按 OK 会发生什么」
 *     的候选。返回不在这套语言里，它本来就是遥控器上的一个硬键。
 *  2. 把返回做成可聚焦项，等于在用户从内容往左上移动的路上埋一个「一按就退出」
 *     的坑；还抢初始焦点 —— 进页面第一下 OK 直接退回去。
 *  3. 圆形悬浮按钮是手机 FAB 的语言。10-foot UI 里没有「热区」这个概念，
 *     只有排版层级。
 *
 * 所以是**面包屑式弱提示**：小字 + 小 chevron，和标题同一条水平线、
 * 同一条左边距，读起来是「你在哪一层」。鼠标 / 触摸电视盒仍然能点
 * （`canFocus = false` 只影响 D-pad 焦点环）。
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
    /** 返回提示的文字/箭头颜色：压在视频画面上时传亮色 */
    hintColor: Color = ProomColor.muted,
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
            .clip(shape)
            .background(if (focused) ProomColor.accentSoft else ProomColor.surface)
            .then(if (focused) Modifier.border(2.dp, ProomColor.accent, shape) else Modifier)
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Text(
            text,
            style = ProomType.meta,
            color = if (focused) ProomColor.accent else ProomColor.text,
        )
    }
}

/**
 * 电视端的返回「提示」——**不是按钮**。
 *
 * 不再单独调用：一律通过 [TvPageHeader] 出现，否则又会各写一套位置和字号。
 */
@Composable
fun TvBackHint(
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
    label: String = "返回",
    /** 文字/箭头颜色：压在视频画面上时传亮色 */
    color: Color = ProomColor.muted,
    /** 半透明底。用 drawBehind 画、不占布局，所以「返回」两个字的位置和其它页仍然对齐 */
    backdrop: Color? = null,
) {
    if (onBack == null) return
    Row(
        modifier
            .height(28.dp)
            // 必须在 clickable 之前：只关掉焦点，不关掉点击
            .focusProperties { canFocus = false }
            // 底色必须画在 clip 之前，否则外扩的那一圈会被自己裁掉
            .then(
                if (backdrop != null) {
                    Modifier.drawBehind {
                        val padX = 8.dp.toPx()
                        val padY = 4.dp.toPx()
                        drawRoundRect(
                            color = backdrop,
                            topLeft = Offset(-padX, -padY),
                            size = Size(size.width + padX * 2, size.height + padY * 2),
                            cornerRadius = CornerRadius((size.height + padY * 2) / 2),
                        )
                    }
                } else {
                    Modifier
                }
            )
            .clip(RoundedCornerShape(6.dp))
            .clickable(onClick = onBack)
            .padding(end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = TabIcons.Back,
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(14.dp),
        )
        Text(
            text = label,
            style = ProomType.meta,
            color = color,
            modifier = Modifier.padding(start = 4.dp),
        )
    }
}
