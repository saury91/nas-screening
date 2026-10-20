package com.projectionroom.ui.component

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage

/**
 * 氛围背景：把封面**按很小的分辨率解码**再放大铺满。
 *
 * 为什么不用 `Modifier.blur`：
 *  它在 API 31 以下**是空操作**。手机模拟器是 37 所以看着正常，而电视盒子
 *  一大半跑在 28~30 —— 那边「模糊背景」其实是一张 270px 的海报被拉大 7 倍，
 *  屏幕上就是一块看得清五官、却处处是糊斑的脏图。这是「电视上特别糊」的真正原因，
 *  跟把 blur 调大调小没关系（调了也不生效）。
 *
 * 这里的做法绕开了平台差异：
 *  先让 AsyncImage 只按 [decodeWidth] 这么宽去解码（布局约束就是请求尺寸），
 *  再用 graphicsLayer 放回全屏 —— 放大由 GPU 的双线性插值完成，
 *  任何 API 版本上都是同一种又软又干净的色雾，不会露出原图的像素颗粒。
 *
 * 代价是背景里不再有可辨认的构图，这正是 10-foot UI 想要的：
 * 背景只提供色温，不跟内容抢注意力。
 */
@Composable
fun SoftBackdrop(
    model: Any?,
    modifier: Modifier = Modifier,
    /** 解码宽度。越小越像纯色雾；80dp 左右还能留住一点明暗结构 */
    decodeWidth: Dp = 80.dp,
    alpha: Float = 0.55f,
) {
    if (model == null || model == "") return
    BoxWithConstraints(modifier.clipToBounds()) {
        val w = decodeWidth.coerceAtMost(maxWidth)
        if (w <= 0.dp || maxWidth <= 0.dp) return@BoxWithConstraints
        val ratio = maxHeight / maxWidth
        val h = w * ratio
        val scale = maxWidth / w
        AsyncImage(
            model = model,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            alpha = alpha,
            modifier = Modifier
                .size(w, h)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    transformOrigin = TransformOrigin(0f, 0f)
                },
        )
    }
}

/** 铺满父容器的便捷写法 */
@Composable
fun SoftBackdropFill(
    model: Any?,
    decodeWidth: Dp = 80.dp,
    alpha: Float = 0.55f,
) = SoftBackdrop(model, Modifier.fillMaxSize(), decodeWidth, alpha)
