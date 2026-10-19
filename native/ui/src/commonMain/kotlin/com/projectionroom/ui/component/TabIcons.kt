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
 * 底部 tab 的图标：全部手写 ImageVector，不引入 material-icons 依赖
 * （那套扩展包 4MB+，我们只需要四个）。
 *
 * 选中用「实心」、未选中用「描边」—— 状态不只靠颜色区分（UX-003），
 * 色弱用户也分得清当前在哪一页。
 */
object TabIcons {

    val Movie: ImageVector = icon("movie", false) {
        // 圆角屏 + 播放三角（三角用反向绕线，实心版会挖空）
        roundRect(3.2f, 4.4f, 20.8f, 19.6f, 4.2f)
        moveTo(10.2f, 8.9f); lineTo(15.4f, 12f); lineTo(10.2f, 15.1f); close()
    }
    val MovieOn: ImageVector = icon("movie_on", true) {
        roundRect(3.2f, 4.4f, 20.8f, 19.6f, 4.2f)
        moveTo(10.2f, 8.9f); lineTo(15.4f, 12f); lineTo(10.2f, 15.1f); close()
    }

    val Live: ImageVector = icon("live", false) {
        roundRect(2.8f, 5f, 21.2f, 17.6f, 3.4f)
        moveTo(8.6f, 20.8f); lineTo(15.4f, 20.8f)
    }
    val LiveOn: ImageVector = icon("live_on", true) {
        roundRect(2.8f, 5f, 21.2f, 17.6f, 3.4f)
        moveTo(8.6f, 20.8f); lineTo(15.4f, 20.8f)
    }

    val Music: ImageVector = icon("music", false) {
        circle(9.2f, 16.4f, 3.1f)
        moveTo(12.3f, 16.4f); lineTo(12.3f, 5.2f)
        curveTo(15.6f, 5.6f, 17.4f, 7.2f, 17.2f, 9.6f)
    }
    val MusicOn: ImageVector = icon("music_on", true) {
        circle(9.2f, 16.4f, 3.1f)
        moveTo(12.3f, 16.4f); lineTo(12.3f, 5.2f); lineTo(12.3f, 16.4f)
        curveTo(15.6f, 5.6f, 17.4f, 7.2f, 17.2f, 9.6f)
    }

    val Mine: ImageVector = icon("mine", false) {
        circle(12f, 8.4f, 3.5f)
        moveTo(5.4f, 19.6f)
        curveTo(5.4f, 16.1f, 8.3f, 14.1f, 12f, 14.1f)
        curveTo(15.7f, 14.1f, 18.6f, 16.1f, 18.6f, 19.6f)
    }
    val MineOn: ImageVector = icon("mine_on", true) {
        circle(12f, 8.4f, 3.5f)
        moveTo(5.4f, 19.6f)
        curveTo(5.4f, 16.1f, 8.3f, 14.1f, 12f, 14.1f)
        curveTo(15.7f, 14.1f, 18.6f, 16.1f, 18.6f, 19.6f)
    }

    // ── 构造 ──────────────────────────────────────────────
    private fun icon(name: String, filled: Boolean, block: PathBuilder.() -> Unit): ImageVector =
        ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            path(
                // 实心版用 EvenOdd：内层子路径（播放三角）自动挖空
                pathFillType = if (filled) PathFillType.EvenOdd else PathFillType.NonZero,
                fill = if (filled) SolidColor(Color.White) else null,
                stroke = SolidColor(Color.White),
                strokeLineWidth = if (filled) 1.2f else 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
                pathBuilder = block,
            )
        }.build()

    /** 圆角矩形：a 为圆角半径 */
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
