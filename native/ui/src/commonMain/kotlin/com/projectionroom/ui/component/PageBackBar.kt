package com.projectionroom.ui.component

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.projectionroom.ui.theme.ProomColor
import com.projectionroom.ui.theme.ProomMotion

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
 * 电视端的返回「提示」——**不是按钮**。
 *
 * 为什么电视上不能是一个按钮（这是上一版做错的地方）：
 *  1. 遥控器只有一个输入货币：**焦点**。焦点环里每一项都应该是「按 OK 会发生什么」
 *     的候选。返回不在这套语言里，它本来就是遥控器上的一个硬键。
 *  2. 把返回做成可聚焦项，等于在用户从内容往左上移动的路上埋一个「一按就退出」
 *     的坑；还抢初始焦点 —— 进页面第一下 OK 直接退回去。
 *  3. 圆形悬浮按钮是手机 FAB 的语言。10-foot UI 里没有「热区」这个概念，
 *     只有排版层级。孤零零一个小圆点飘在标题上方，就是「和页面格格不入」的来源。
 *
 * 所以这里做成**面包屑式弱提示**：和 eyebrow 同级的小字，压在标题上方，
 * 读起来是「你在哪一层」，而不是「这是颗按钮」。
 * 鼠标 / 触摸电视盒 / 手机投屏过去仍然能点（`canFocus = false` 只影响 D-pad 焦点环）。
 */
@Composable
fun TvBackHint(
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
    topInset: Dp = 0.dp,
    label: String = "返回",
) {
    if (onBack == null) return
    Row(
        modifier
            .padding(top = topInset)
            .height(26.dp)
            // 必须在 clickable 之前：只关掉焦点，不关掉点击
            .focusProperties { canFocus = false }
            .clip(RoundedCornerShape(6.dp))
            .clickable(onClick = onBack)
            .padding(horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = TabIcons.Back,
            contentDescription = null,
            tint = ProomColor.muted,
            modifier = Modifier.size(12.dp),
        )
        Text(
            text = label,
            color = ProomColor.muted,
            fontSize = 11.sp,
            modifier = Modifier.padding(start = 3.dp),
        )
    }
}
