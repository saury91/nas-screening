package com.projectionroom.ui.component

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * 横向货架，专为遥控器设计。
 *
 * 和 Web 版最大的区别：**不再手写几何寻路**。
 * 旧版 `focus.js` 要靠计算每个元素中心点的距离来猜「按右键该去谁」，
 * 这里交给 Compose 的焦点系统 —— 布局里谁在谁右边，框架自己知道，
 * 到达可视区边缘时也由 [BringIntoViewRequester] 负责滚动，不会把焦点滚丢。
 *
 * 手机上也能用：焦点只是不可见，触摸滚动照常。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun <T> TvRow(
    items: List<T>,
    itemWidth: Dp,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    gap: Dp = 0.dp,
    autoFocusFirst: Boolean = false,
    content: @Composable (item: T, focused: Boolean) -> Unit,
) {
    if (items.isEmpty()) return
    val state = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val firstFocus = remember { FocusRequester() }

    LaunchedEffect(autoFocusFirst, items.size) {
        if (!autoFocusFirst) return@LaunchedEffect
        // 首帧时焦点节点可能还没挂上，requestFocus 会抛；重试几次直到成功。
        // 这比让调用方自己算"布局完成没"要稳。
        repeat(20) {
            if (runCatching { firstFocus.requestFocus() }.isSuccess) return@LaunchedEffect
            kotlinx.coroutines.delay(16)
        }
    }

    LazyRow(
        state = state,
        modifier = modifier,
        contentPadding = contentPadding,
        horizontalArrangement = Arrangement.spacedBy(gap),
    ) {
        itemsIndexed(items) { i, item ->
            var focused by remember(i) { mutableStateOf(false) }
            val bring = remember(i) { BringIntoViewRequester() }
            Box(
                modifier = Modifier
                    .width(itemWidth)
                    .then(if (i == 0) Modifier.focusRequester(firstFocus) else Modifier)
                    .bringIntoViewRequester(bring)
                    .onFocusChanged { st ->
                        focused = st.isFocused
                        // 焦点一进来就滚到能看见的位置：遥控器快按也不会丢焦点
                        if (st.isFocused) scope.launch { bring.bringIntoView() }
                    }
                    // testTag 必须在 focusable 之后：语义合并时焦点信息才挂在同一个节点上
                    .focusable()
                    .testTag("card-$i"),
            ) {
                content(item, focused)
            }
        }
    }
}
