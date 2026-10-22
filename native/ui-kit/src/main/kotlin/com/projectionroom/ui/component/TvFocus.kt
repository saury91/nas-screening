package com.projectionroom.ui.component

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 电视端焦点环：白环 + 轻微放大。
 *
 * 遥控器只有一个输入货币 —— 焦点，所以「这一项现在是不是被选中」必须一眼看得出来。
 * 之前每次都是手写 `var focused + onFocusChanged + border + scale`，20 多个地方
 * 各写一遍，后来出现了描边颜色不同、有的干脆忘了加（片库网格整格没有反馈）。
 *
 * 用法：`Modifier.tvFocusRing(CircleShape).clickable { ... }`。
 * 手机触摸不会产生焦点，这些修饰符在手机上完全不生效，可以无条件写。
 *
 * @param shape 焦点环的圆角形状，必须和元素自身的裁剪形状一致，否则环会切角
 * @param zoom 聚焦时放大比例；1f 表示只描边不放大
 * @param color 环色。压在亮画面上时用白色，压在深色页面上可以传强调色
 */
@Composable
fun Modifier.tvFocusRing(
    shape: Shape = RoundedCornerShape(50),
    ringWidth: Dp = 3.dp,
    zoom: Float = 1.08f,
    color: Color = Color.White,
): Modifier {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (focused) zoom else 1f,
        animationSpec = tween(130),
        label = "tv-focus-zoom",
    )
    return this
        .onFocusChanged { focused = it.isFocused }
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
        // 放在 graphicsLayer 之后（内层）：焦点环跟着一起放大，不会出现「环比按钮小一圈」
        .then(if (focused) Modifier.border(ringWidth, color, shape) else Modifier)
}
