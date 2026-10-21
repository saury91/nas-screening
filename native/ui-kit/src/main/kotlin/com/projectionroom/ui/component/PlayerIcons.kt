package com.projectionroom.ui.component

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * 播放器控制层用的图标：全部手写，不引 material-icons（那套 4MB+）。
 *
 * 24dp 视口，描边统一 1.9dp、圆头，和 `TabIcons` 保持同一套笔触。
 * 颜色一律白色，由调用方用 `Icon(tint = ...)` 改。
 */
object PlayerIcons {

    /** 播放：右向三角 */
    val Play: ImageVector = filled("play") {
        moveTo(7.5f, 4.6f); lineTo(19.2f, 12f); lineTo(7.5f, 19.4f); close()
    }

    /** 暂停：两条竖条 */
    val Pause: ImageVector = filled("pause") {
        roundRect(6.6f, 4.8f, 10.1f, 19.2f, 1.6f)
        roundRect(13.9f, 4.8f, 17.4f, 19.2f, 1.6f)
    }

    /** 上一集：竖线 + 左向三角 */
    val Prev: ImageVector = filled("prev") {
        roundRect(4.4f, 5.2f, 6.4f, 18.8f, 1.1f)
        moveTo(19.4f, 5.4f); lineTo(8.2f, 12f); lineTo(19.4f, 18.6f); close()
    }

    /** 下一集：右向三角 + 竖线 */
    val Next: ImageVector = filled("next") {
        moveTo(4.6f, 5.4f); lineTo(15.8f, 12f); lineTo(4.6f, 18.6f); close()
        roundRect(17.6f, 5.2f, 19.6f, 18.8f, 1.1f)
    }

    /** 锁：闭合的锁头（锁定状态） */
    val Lock: ImageVector = outline("lock") {
        roundRect(5.4f, 10.6f, 18.6f, 20.2f, 2.6f)
        // 锁梁
        moveTo(8.4f, 10.6f); lineTo(8.4f, 8.2f)
        curveTo(8.4f, 5.4f, 15.6f, 5.4f, 15.6f, 8.2f)
        lineTo(15.6f, 10.6f)
    }

    /** 开锁：锁梁翘起 */
    val Unlock: ImageVector = outline("unlock") {
        roundRect(5.4f, 10.6f, 18.6f, 20.2f, 2.6f)
        moveTo(8.4f, 10.6f); lineTo(8.4f, 8.2f)
        curveTo(8.4f, 5.4f, 15.6f, 5.4f, 15.6f, 8.2f)
    }

    /** 画中画：大框 + 右下角小框 */
    val Pip: ImageVector = outline("pip") {
        roundRect(3f, 5f, 21f, 19f, 2.4f)
        roundRect(12.6f, 11.6f, 19f, 17.2f, 1.4f)
    }

    /** 关闭：两根等长斜线，交点在正中 */
    val Close: ImageVector = outline("close") {
        moveTo(6.6f, 6.6f); lineTo(17.4f, 17.4f)
        moveTo(17.4f, 6.6f); lineTo(6.6f, 17.4f)
    }

    /** 投屏：屏幕 + 波纹 */
    val Cast: ImageVector = outline("cast") {
        roundRect(3f, 6.2f, 21f, 18.4f, 2.2f)
        moveTo(3.6f, 15.4f)
        curveTo(4.9f, 15.6f, 5.9f, 16.6f, 6.1f, 17.9f)
        moveTo(3.6f, 12.2f)
        curveTo(6.6f, 12.5f, 9f, 14.9f, 9.3f, 17.9f)
    }

    // 亮度图标用的 8 个方向
    private val rays = listOf(
        0f to -1f, 0.71f to -0.71f, 1f to 0f, 0.71f to 0.71f,
        0f to 1f, -0.71f to 0.71f, -1f to 0f, -0.71f to -0.71f,
    )

    /** 亮度：太阳 */
    val Brightness: ImageVector = outline("brightness") {
        circle(12f, 12f, 3.4f)
        rays.forEach { (dx, dy) ->
            moveTo(12f + dx * 6.2f, 12f + dy * 6.2f)
            lineTo(12f + dx * 8.2f, 12f + dy * 8.2f)
        }
    }

    /** 音量：喇叭 + 两道弧 */
    val Volume: ImageVector = outline("volume") {
        moveTo(4.4f, 9.4f); lineTo(7.4f, 9.4f); lineTo(11.4f, 5.8f)
        lineTo(11.4f, 18.2f); lineTo(7.4f, 14.6f); lineTo(4.4f, 14.6f); close()
        moveTo(14.2f, 9.2f); curveTo(15.8f, 10.8f, 15.8f, 13.2f, 14.2f, 14.8f)
        moveTo(16.8f, 7.4f); curveTo(19.6f, 10.2f, 19.6f, 13.8f, 16.8f, 16.6f)
    }

    /** 倍速 / 更多设置：三个点 */
    val More: ImageVector = filled("more") {
        circle(6.2f, 12f, 1.5f)
        circle(12f, 12f, 1.5f)
        circle(17.8f, 12f, 1.5f)
    }

    /** 列表循环：一个闭环箭头，中间空（音乐播放器的默认模式） */
    val Repeat: ImageVector = outline("repeat") {
        moveTo(4.2f, 9.4f)
        curveTo(4.2f, 6.6f, 6.4f, 5f, 9f, 5f)
        lineTo(17.4f, 5f)
        moveTo(15.4f, 3.1f); lineTo(17.6f, 5f); lineTo(15.4f, 6.9f)
        moveTo(19.8f, 14.6f)
        curveTo(19.8f, 17.4f, 17.6f, 19f, 15f, 19f)
        lineTo(6.6f, 19f)
        moveTo(8.6f, 17.1f); lineTo(6.4f, 19f); lineTo(8.6f, 20.9f)
    }

    /** 单曲循环：同一条闭环 + 中间一个 1 */
    val RepeatOne: ImageVector = outline("repeat_one") {
        moveTo(4.2f, 9.4f)
        curveTo(4.2f, 6.6f, 6.4f, 5f, 9f, 5f)
        lineTo(17.4f, 5f)
        moveTo(15.4f, 3.1f); lineTo(17.6f, 5f); lineTo(15.4f, 6.9f)
        moveTo(19.8f, 14.6f)
        curveTo(19.8f, 17.4f, 17.6f, 19f, 15f, 19f)
        lineTo(6.6f, 19f)
        moveTo(8.6f, 17.1f); lineTo(6.4f, 19f); lineTo(8.6f, 20.9f)
        // 中间的「1」
        moveTo(11.2f, 10.2f); lineTo(12.4f, 9.4f); lineTo(12.4f, 14.6f)
    }

    /** 随机：两笔交叉箭头 */
    val Shuffle: ImageVector = outline("shuffle") {
        moveTo(4.2f, 6.6f); lineTo(6.6f, 6.6f)
        curveTo(9.4f, 6.6f, 11f, 9.4f, 12.6f, 12f)
        curveTo(14.2f, 14.6f, 15.8f, 17.4f, 18.6f, 17.4f)
        lineTo(19.8f, 17.4f)
        moveTo(17.4f, 15.2f); lineTo(19.9f, 17.4f); lineTo(17.4f, 19.6f)
        moveTo(4.2f, 17.4f); lineTo(6.6f, 17.4f)
        curveTo(8.2f, 17.4f, 9.4f, 16.5f, 10.4f, 15.2f)
        moveTo(19.8f, 6.6f); lineTo(18.6f, 6.6f)
        curveTo(16.6f, 6.6f, 15.2f, 7.9f, 14f, 9.4f)
        moveTo(17.4f, 4.4f); lineTo(19.9f, 6.6f); lineTo(17.4f, 8.8f)
    }

    /** 音效：三根推子（竖线 + 滑块） */
    val Sliders: ImageVector = outline("sliders") {
        moveTo(6.5f, 4.4f); lineTo(6.5f, 19.6f)
        moveTo(12f, 4.4f); lineTo(12f, 19.6f)
        moveTo(17.5f, 4.4f); lineTo(17.5f, 19.6f)
        // 三个滑块
        moveTo(4.4f, 9f); lineTo(8.6f, 9f)
        moveTo(9.9f, 14.6f); lineTo(14.1f, 14.6f)
        moveTo(15.4f, 8f); lineTo(19.6f, 8f)
    }

    /** 播放队列：三行文字 + 右边一个小音符 */
    val Queue: ImageVector = outline("queue") {
        moveTo(4.4f, 6.6f); lineTo(14.6f, 6.6f)
        moveTo(4.4f, 12f); lineTo(14.6f, 12f)
        moveTo(4.4f, 17.4f); lineTo(11.2f, 17.4f)
        moveTo(18.4f, 5.8f); lineTo(18.4f, 15.2f)
        circle(16.6f, 16.4f, 2.0f)
    }

    // ── 构造 ──────────────────────────────────────────────
    private fun filled(name: String, block: PathBuilder.() -> Unit): ImageVector =
        build(name, filled = true, block)

    private fun outline(name: String, block: PathBuilder.() -> Unit): ImageVector =
        build(name, filled = false, block)

    private fun build(name: String, filled: Boolean, block: PathBuilder.() -> Unit): ImageVector =
        ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            path(
                pathFillType = if (filled) PathFillType.EvenOdd else PathFillType.NonZero,
                fill = if (filled) SolidColor(Color.White) else null,
                stroke = SolidColor(Color.White),
                strokeLineWidth = if (filled) 1.1f else 1.9f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
                pathBuilder = block,
            )
        }.build()

    private fun PathBuilder.roundRect(l: Float, t: Float, r: Float, b: Float, a: Float) {
        moveTo(l + a, t)
        lineTo(r - a, t); arcTo(a, a, 0f, false, true, r, t + a)
        lineTo(r, b - a); arcTo(a, a, 0f, false, true, r - a, b)
        lineTo(l + a, b); arcTo(a, a, 0f, false, true, l, b - a)
        lineTo(l, t + a); arcTo(a, a, 0f, false, true, l + a, t)
        close()
    }

    private fun PathBuilder.circle(cx: Float, cy: Float, rad: Float) {
        moveTo(cx - rad, cy)
        arcTo(rad, rad, 0f, true, true, cx + rad, cy)
        arcTo(rad, rad, 0f, true, true, cx - rad, cy)
        close()
    }
}
