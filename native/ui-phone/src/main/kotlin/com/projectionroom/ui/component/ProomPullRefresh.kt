package com.projectionroom.ui.component

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import com.projectionroom.ui.theme.ProomColor
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * 全站统一的下拉刷新容器（首页、分类、直播、音乐共用）。
 *
 * 没有用 material3 的 `PullToRefreshBox`：它在这些页面上有三个绕不过去的毛病。
 * 1. 指示器固定画在 y=0，会被浮在上面的固定顶栏整块盖住，
 *    手指拖半天屏幕毫无变化 —— 于是「很难触发、一点都不跟手」；
 * 2. 只在松手后由外部状态决定转不转，晚一帧就是「箭头定格、不转」；
 * 3. `isRefreshing` 掉回 false 时它不会把指示器收回去，圆环就卡在屏幕中间。
 *
 * 这里自己接 nested scroll：手指走多少内容就下移多少（跟手），
 * 越过阈值松手就回调 `onRefresh` 并停在原地转圈，等 `refreshing` 落回 false 再回弹。
 *
 * @param indicatorTop 指示器相对**本容器**顶部的偏移。
 *   顶栏固定在容器外面时传 0；顶栏浮在内容上面（影视页）时传顶栏总高，
 *   否则指示器会被顶栏压住、看不见。
 */
@Composable
fun ProomPullRefresh(
    refreshing: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    indicatorTop: Dp = 8.dp,
    content: @Composable BoxScope.() -> Unit,
) {
    val density = LocalDensity.current
    val threshold = with(density) { 60.dp.toPx() }
    val maxPull = with(density) { 150.dp.toPx() }

    // 累计下拉距离（px）。nested scroll 回调是同步的，必须当场改，
    // 绕一层状态机/协程就慢一帧，手感立刻散掉
    var pull by remember { mutableFloatStateOf(0f) }
    var settleJob by remember { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()
    val refreshingNow by rememberUpdatedState(refreshing)
    val onRefreshNow by rememberUpdatedState(onRefresh)

    fun settleTo(target: Float) {
        settleJob?.cancel()
        settleJob = scope.launch {
            animate(
                initialValue = pull,
                targetValue = target,
                animationSpec = spring(stiffness = Spring.StiffnessMediumLow, dampingRatio = 1f),
            ) { value, _ -> pull = value }
        }
    }

    val connection = remember(threshold, maxPull) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                // 刷新中不接管：否则往回推会把「停在阈值」的内容顶上去，
                // 而刷新态又不会再回弹，列表就整体错位了
                if (source != NestedScrollSource.UserInput || refreshingNow) return Offset.Zero
                // 往回推：先把已经拉出来的距离吃掉，内容跟着手指回去
                if (available.y < 0f && pull > 0f) {
                    val next = (pull + available.y).coerceAtLeast(0f)
                    val consumed = next - pull
                    pull = next
                    return Offset(0f, consumed)
                }
                return Offset.Zero
            }

            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (source != NestedScrollSource.UserInput || refreshingNow) return Offset.Zero
                if (available.y <= 0f) return Offset.Zero
                settleJob?.cancel()
                // 阻尼：越拉越沉，免得一下拖出半屏黑
                val next = pull + available.y * (1f - pull / (maxPull * 2f))
                pull = next.coerceAtMost(maxPull)
                return Offset(0f, available.y)
            }

            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                if (refreshingNow) return Velocity.Zero
                if (pull >= threshold) {
                    pull = threshold
                    onRefreshNow()
                } else if (pull > 0f) {
                    settleTo(0f)
                }
                return Velocity.Zero
            }
        }
    }

    // 刷新结束必须自己收回去：状态从 true 掉回 false 时没人会动这个 offset
    LaunchedEffect(refreshing) {
        if (refreshing) pull = threshold else settleTo(0f)
    }

    Box(modifier.fillMaxSize().nestedScroll(connection)) {
        // 内容整体跟手下移（graphicsLayer 只影响绘制，不触发重组）
        Box(Modifier.fillMaxSize().graphicsLayer { translationY = pull }) {
            content()
        }

        if (pull > 0f) {
            val progress = (pull / threshold).coerceIn(0f, 1f)
            Box(
                Modifier
                    .align(Alignment.TopCenter)
                    // 钉在指示位、压在第一行内容上：顶栏是不透明的，
                    // 从它下面往上「钻出来」等于起步这几帧根本看不见
                    .offset { IntOffset(0, indicatorTop.roundToPx()) }
                    .alpha(progress)
                    .size(40.dp)
                    .clip(CircleShape)
                    // 半透明 surface 压在图上会糊，用不透明卡片色
                    .background(ProomColor.card),
                contentAlignment = Alignment.Center,
            ) {
                if (refreshing) {
                    // 固定 270° 弧匀速转：M3 的 indeterminate 会「伸缩到几乎看不见」，
                    // 截帧时就是一会儿有圈一会儿空着，观感在闪
                    val transition = rememberInfiniteTransition(label = "refresh")
                    val angle by transition.animateFloat(
                        initialValue = 0f,
                        targetValue = 360f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(900, easing = LinearEasing),
                            repeatMode = RepeatMode.Restart,
                        ),
                        label = "angle",
                    )
                    Canvas(Modifier.size(22.dp).rotate(angle)) {
                        drawArc(
                            color = ProomColor.accent,
                            startAngle = 0f,
                            sweepAngle = 270f,
                            useCenter = false,
                            style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round),
                        )
                    }
                } else {
                    // 还没到阈值：圆弧就是进度，走到满才开始转
                    Canvas(Modifier.size(22.dp)) {
                        drawArc(
                            color = ProomColor.accent,
                            startAngle = -90f,
                            sweepAngle = 270f * progress,
                            useCenter = false,
                            style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round),
                        )
                    }
                }
            }
        }
    }
}
