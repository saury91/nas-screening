package com.projectionroom.ui.component

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 电视端焦点环：锐利白环 + 向外晕开的同色柔光 + 轻微放大。
 *
 * 遥控器只有「焦点」一种输入货币，所以这一项现在有没有被指着，必须一眼看得出来。
 * 视觉上用「锐环锚定 + 柔晕发散」两层：锐环保证轮廓在 3 米外仍然清楚，
 * 柔晕负责让它像被顶起来的一块，而不是画了一圈死线；再配一点弹性放大，
 * 整块就从背景里浮出来了。
 *
 * 柔光用多层同心描边模拟，而不是 `BlurMaskFilter` / `RenderEffect`：
 * TV 盒子 GPU 弱，每帧重建模糊会掉帧，而多层矢量环在机顶盒上几乎零成本，
 * 3 米观看距离也看不出分层。动画只驱动 alpha 与 scale，描边宽度固定，不触发重绘放大。
 *
 * 用法：`Modifier.tvFocusRing(CircleShape).clickable { ... }`。
 * 手机触摸不会产生焦点，这些修饰符在手机上完全不生效，可以无条件写。
 *
 * @param shape 焦点环形状，必须和元素自身裁剪形状一致，否则环会切角
 * @param ringWidth 锐环宽度；全局 2dp，真机上 3dp 会吃掉相邻元素之间的缝
 * @param zoom 聚焦时放大比例；默认 1f，靠外扩环本身产生「鼓」感，
 *   再叠缩放会顶到邻居，只在独立卡片上显式传大于 1 的值
 * @param color 环色。默认白色；不要再传品牌金，金色已经承担「已选中」语义
 * @param inset 紧贴堆叠的元素（列表行、chip、键盘键）传 true，环压进元素内；
 *   卡片之间有空隙，保持 false 让环外扩
 */
@Composable
fun Modifier.tvFocusRing(
    shape: Shape = RoundedCornerShape(50),
    ringWidth: Dp = 2.dp,
    zoom: Float = 1f,
    color: Color = Color.White,
    inset: Boolean = false,
): Modifier {
    var focused by remember { mutableStateOf(false) }
    val bloom by animateFloatAsState(
        targetValue = if (focused) 1f else 0f,
        // 进：弹性带 3% 过冲，就是「鼓起来」的手感；出：直线收回，不拖泥带水
        animationSpec = if (focused) {
            spring(dampingRatio = 0.52f, stiffness = 420f)
        } else {
            tween(durationMillis = 150)
        },
        label = "tv-focus-bloom",
    )
    val zoomScale = 1f + (zoom - 1f) * bloom
    return this
        .onFocusChanged { focused = it.isFocused }
        .graphicsLayer {
            scaleX = zoomScale
            scaleY = zoomScale
        }
        .then(Modifier.tvFocusBloom(shape, bloom, ringWidth, color, inset))
}

/**
 * 无状态的发泡绘制：组件自己已经知道 `focused` 时用这个，避免再注册一次焦点监听
 * 导致「外层的 Column 拿着焦点、里层的 Box 收不到」而画不出环。
 *
 * @param bloom 0~1 的展开进度，通常由 `animateFloatAsState` 驱动，可略大于 1 形成过冲
 */
fun Modifier.tvFocusBloom(
    shape: Shape,
    bloom: Float,
    ringWidth: Dp = 2.dp,
    color: Color = Color.White,
    inset: Boolean = false,
): Modifier = drawWithContent {
    drawContent()
    // 必须画在内容之上：行类元素的内收环如果先于自身背景绘制，
    // 会被不透明的 bgSoft 整块盖住，真机上什么都不剩
    if (bloom < 0.01f) return@drawWithContent
    val base = ringWidth.toPx()
    // 环整体偏移一段缝隙：让「元素 → 深色缝隙 → 环」三段分开。
    // 描边是以中心线为轴画的，所以偏移距离要含住半个环宽，剩下的 2dp 才是真正的缝隙。
    val gap = base / 2f + 2.dp.toPx()
    // 卡片之间有留白，环向外鼓；列表行是紧贴堆叠的，向外鼓会被上下邻居的底色吃掉
    // （真机上只剩一条底边露在缝里），所以行类元素传 inset = true，环压在行内 padding 上。
    val dir = if (inset) -1f else 1f
    // 锐环内沿到元素边缘的距离。所有层都以这条线为「靠元素那一侧」的起点，
    // 只朝远离元素的方向长。
    val edge = gap - base / 2f

    // 每层都用「自己的轮廓 + 自己的平移」画，而不是共用一个轮廓再加宽描边。
    //
    // 描边是以轮廓线为轴、两侧各长 width/2 的。共用轮廓时，3dp 的缝里塞一条 7dp 的描边，
    // 就有 3.5dp 压在金色按钮上 —— 金底边缘被蒙成灰的，按钮看着「脏」；
    // 外侧那半边又是一道硬边。叠几层，屏幕上就是「一圈套一圈」。
    //
    // 现在每层的中心线跟着自己的宽度一起外移，内沿永远钉在 edge 上，只朝远离元素的方向长。
    fun layer(width: Float, alpha: Float, tint: Color) {
        if (alpha <= 0.004f || width <= 0f) return
        // 中心线离元素边缘的距离（inset 时是负值，往元素里面退）
        val centerDist = dir * (edge + width / 2f)
        val w = size.width + centerDist * 2f
        val h = size.height + centerDist * 2f
        if (w <= 1f || h <= 1f) return
        val outline = shape.createOutline(Size(w, h), layoutDirection, this)
        translate(left = -centerDist, top = -centerDist) {
            drawOutline(
                outline = outline,
                color = tint,
                alpha = alpha.coerceIn(0f, 1f),
                style = Stroke(width = width),
            )
        }
    }

    // 暗衬：播放器控制条、全屏音乐页的环站在视频画面上，画面一亮（白墙、雪景）
    // 白环就没了底。压一圈极淡的黑，深色页面里看不出来，亮画面下刚好托住白环。
    layer(base * 3f, 0.10f * bloom, Color.Black)
    // 白环之外不再叠白色柔光。试过分 2~5 层同心描边模拟外发光：
    // 16% 的白落在 #000 上就是 #292929，紧挨着纯白环，屏幕上是一条清晰的第二条线 ——
    // 观众看到的是「两个框」，不是「一个发光的框」。深色界面里柔光只会把环弄脏。
    layer(base, 1f * bloom, color)
}
