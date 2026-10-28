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

    /**
     * 设置：齿轮。
     *
     * 外圈 + 内圈 + 8 根齿。手写而不是拿 material 的 settings 图标，
     * 是为了和这套 1.7dp 圆头笔触保持一致 —— 混一套别人的图标，
     * 线条粗细和圆角立刻能看出来不一样。
     */
    val Settings: ImageVector = icon("settings", false) {
        circle(12f, 12f, 6.4f)
        circle(12f, 12f, 2.3f)
        val teeth = listOf(
            0f to -1f, 0.71f to -0.71f, 1f to 0f, 0.71f to 0.71f,
            0f to 1f, -0.71f to 0.71f, -1f to 0f, -0.71f to -0.71f,
        )
        teeth.forEach { (dx, dy) ->
            moveTo(12f + dx * 7.4f, 12f + dy * 7.4f)
            lineTo(12f + dx * 9.4f, 12f + dy * 9.4f)
        }
    }

    /** 加号：新建歌单 / 收进歌单 */
    val Plus: ImageVector = icon("plus", false) {
        moveTo(12f, 5.6f); lineTo(12f, 18.4f)
        moveTo(5.6f, 12f); lineTo(18.4f, 12f)
    }

    /** 收藏（空心）：详情页图标按钮 */
    val Heart: ImageVector = icon("heart", false) { heartPath() }

    /** 收藏（实心）：已收藏状态 */
    val HeartOn: ImageVector = icon("heart_on", true) { heartPath() }

    /**
     * 心形轮廓：从下方尖角起笔，左右各三段贝塞尔。
     *
     * 手写而不是引 material-icons —— 那套包 4MB+，这里只需要一个形状。
     */
    private fun PathBuilder.heartPath() {
        moveTo(12f, 20.4f)
        curveTo(10.5f, 19.1f, 3.7f, 14f, 3.7f, 9.2f)
        curveTo(3.7f, 6.3f, 5.9f, 4.2f, 8.4f, 4.2f)
        curveTo(10.1f, 4.2f, 11.3f, 5.1f, 12f, 6.2f)
        curveTo(12.7f, 5.1f, 13.9f, 4.2f, 15.6f, 4.2f)
        curveTo(18.1f, 4.2f, 20.3f, 6.3f, 20.3f, 9.2f)
        curveTo(20.3f, 14f, 13.5f, 19.1f, 12f, 20.4f)
        close()
    }

    /** 放大镜：顶栏搜索入口（统一成一个图标，不再用文字按钮） */
    val Search: ImageVector = ImageVector.Builder(
        name = "search", defaultWidth = 24.dp, defaultHeight = 24.dp,
        viewportWidth = 24f, viewportHeight = 24f,
    ).apply {
        path(
            fill = null,
            stroke = SolidColor(Color.White),
            strokeLineWidth = 1.9f,
            strokeLineCap = StrokeCap.Round,
        ) {
            moveTo(11f, 4.2f)
            arcTo(6.8f, 6.8f, 0f, true, true, 10.98f, 4.2f)
            close()
            moveTo(16.2f, 16.2f); lineTo(20.4f, 20.4f)
        }
    }.build()

    /** 关闭：搜索态的「收起」，两笔交叉 */
    val Close: ImageVector = ImageVector.Builder(
        name = "close", defaultWidth = 24.dp, defaultHeight = 24.dp,
        viewportWidth = 24f, viewportHeight = 24f,
    ).apply {
        path(
            fill = null,
            stroke = SolidColor(Color.White),
            strokeLineWidth = 1.9f,
            strokeLineCap = StrokeCap.Round,
        ) {
            moveTo(6.4f, 6.4f); lineTo(17.6f, 17.6f)
            moveTo(17.6f, 6.4f); lineTo(6.4f, 17.6f)
        }
    }.build()

    /** 返回：24dp 描边箭头，放在 48dp 可点区里（标准 App Bar 规范） */
    val Back: ImageVector = ImageVector.Builder(
        name = "back", defaultWidth = 24.dp, defaultHeight = 24.dp,
        viewportWidth = 24f, viewportHeight = 24f,
    ).apply {
        path(
            fill = null,
            stroke = SolidColor(Color.White),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        ) {
            moveTo(14.6f, 5.4f); lineTo(8f, 12f); lineTo(14.6f, 18.6f)
        }
    }.build()

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
