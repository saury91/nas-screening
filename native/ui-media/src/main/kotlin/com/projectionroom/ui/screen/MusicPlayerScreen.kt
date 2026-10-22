package com.projectionroom.ui.screen

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration

/**
 * 全屏音乐播放器。
 *
 * 版式分两端（见 [MusicStageScreen]）：
 * - 手机：黑胶版，转动的唱片 + 唱臂，播放/暂停各有一处物理反馈
 * - 电视：沉浸封面版，大封面 + 滚动歌词，当前句常驻正中，上下行随滚动淡入淡出
 *
 * 状态全部来自 `MusicBox` —— 退出这一页歌不会停，底部迷你条接着显示；
 * 收藏也挂在 MusicBox 上，通知栏点了收藏这一页立刻跟着变。
 */
@Composable
fun MusicPlayerScreen(
    onBack: () -> Unit,
    /** 电视端：顶栏换成与其它页面同一套 TvPageHeader（返回提示 + 标题 + 右侧动作） */
    isTv: Boolean = false,
) {
    // 手机横屏仍走黑胶版的左右结构，竖屏走上下结构
    val wide = LocalConfiguration.current.let { it.screenWidthDp > it.screenHeightDp }
    MusicStageScreen(isTv = isTv, wide = wide, onBack = onBack)
}
