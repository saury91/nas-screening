package com.projectionroom.ui.component

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 氛围背景：把封面**按很小的分辨率解码**再放大铺满。
 *
 * 为什么不用 `Modifier.blur`：
 *  它在 API 31 以下是空操作。手机模拟器是 34 所以看着正常，而电视盒子
 *  一大半跑在 28~30 —— 那边「模糊背景」其实是一张 270px 的海报被拉大 7 倍，
 *  屏幕上就是一块看得清五官、却处处是糊斑的脏图。
 *
 * 这里的做法绕开了平台差异：先让图片只按 [decodeWidth] 这么宽去解码，
 * 再用 graphicsLayer 放回全屏 —— 放大交给 GPU 的双线性插值，
 * 任何 API 版本上都是同一种又软又干净的背景。
 *
 * ── 为什么不是 6dp ──
 * 最早这里用的 6dp（1080p 上约 12px），放大后只剩一团没有结构的色雾，
 * 和 Web 端 `filter:blur(46px) saturate(150%) brightness(.72)` 的毛玻璃
 * 质感差很远：网页那边还留着大块的色团、光晕和层次，我们这边是一块平涂。
 * 现在按 Web 的参数对齐：
 *  - 解码宽度 [decodeWidth] 取 20~34dp（相当于 blur 30~92px 的低频结构）；
 *  - [saturation] 提饱和（Web 用 150~160%）；
 *  - [brightness] 压亮度（Web 用 .44~.72）。
 * 三者叠起来才是「毛玻璃」，只看模糊不看色彩的话永远是灰雾。
 *
 * 不再用 alpha 把图淡进底色：半透明混出来的颜色是往灰里走，
 * 而 brightness 是整体压暗、色相不变 —— 这也是 Web 端的效果来源。
 *
 * 必须显式指定尺寸与独立的缓存（踩过的坑）：
 *  竖版海报卡会先按 270px 把同一张图解进内存缓存，之后这里再请求小尺寸时，
 *  加载器认为「更大的缓存位图也能用」，直接把 270px 那张返回 ——
 *  而 painter 会**按位图自身的尺寸**绘制，于是那张大图被放大上百倍，
 *  屏幕上就是一张看得清五官的糊图。症状正好是：首次进入正常
 *  （此时缓存还空，真的按小尺寸解了码），滚下去再回来就变清晰。
 */
@Composable
fun SoftBackdrop(
    model: Any?,
    modifier: Modifier = Modifier,
    /**
     * 解码宽度。越小越像纯色雾，越大越接近 CSS blur：
     * 20dp 对应 `blur(30px)`，26dp 约 `blur(46px)`，34dp 约 `blur(92px)`。
     * 超过 40dp 就开始能看出封面构图了 —— 那是「糊」，不是「模糊背景」。
     */
    decodeWidth: Dp = 22.dp,
    saturation: Float = 1.55f,
    brightness: Float = 0.66f,
) {
    if (model == null || model == "") return

    // 先提饱和再整体压暗：饱和度矩阵的 RGB 输出乘以 brightness，
    // 色相不变、只是变暗，和 CSS 的 saturate() + brightness() 等价
    val filter = remember(saturation, brightness) {
        val cm = ColorMatrix().apply { setToSaturation(saturation) }
        for (i in 0 until 15) cm.values[i] *= brightness
        ColorFilter.colorMatrix(cm)
    }

    BoxWithConstraints(modifier.clipToBounds()) {
        val w = decodeWidth.coerceAtMost(maxWidth)
        if (w <= 0.dp || maxWidth <= 0.dp) return@BoxWithConstraints
        val ratio = maxHeight / maxWidth
        val h = w * ratio
        val scale = maxWidth / w

        Box(
            Modifier
                .size(w, h)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    transformOrigin = TransformOrigin(0f, 0f)
                }
        ) {
            // 再裁一层：万一位图和框不等大，也只留这个几十像素的角，
            // 放大后依然是大块色团，不会露出构图
            Box(Modifier.fillMaxSize().clipToBounds()) {
                // 小图上再抹一遍：解码尺寸被加载器量化到 2 的幂（请求 60px 实际给 128px），
                // 光靠放大还会剩下能认出来的字和脸。在小图上做 blur 几乎不花钱，
                // 放大后等效于一次全屏大半径模糊；API 31 以下这个修饰符是空操作，
                // 行为和原来纯放大一致，不会更差
                RemoteImage(
                    model = model,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    colorFilter = filter,
                    modifier = Modifier
                        .fillMaxSize()
                        .blur(decodeWidth * 0.16f, BlurredEdgeTreatment.Unbounded),
                )
            }
        }
    }
}

/** 铺满父容器的便捷写法 */
@Composable
fun SoftBackdropFill(
    model: Any?,
    decodeWidth: Dp = 22.dp,
    saturation: Float = 1.55f,
    brightness: Float = 0.66f,
) = SoftBackdrop(model, Modifier.fillMaxSize(), decodeWidth, saturation, brightness)
