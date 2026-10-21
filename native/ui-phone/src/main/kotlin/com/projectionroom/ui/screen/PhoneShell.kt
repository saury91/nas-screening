package com.projectionroom.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.projectionroom.ui.component.TabIcons
import com.projectionroom.ui.theme.ProomColor
import com.projectionroom.ui.theme.ProomShape
import com.projectionroom.ui.theme.ProomSpace
import com.projectionroom.ui.theme.ProomType

/**
 * 手机壳：底部三个 tab。
 *
 * 和 TV 刻意不同 —— 电视是「遥控器 + 焦点」，手机是「拇指 + 触摸」。
 * TV 那边靠货架横向滑动，手机这边用网格，一屏能看到更多、手指不用来回甩。
 *
 * 四个 tab：影视 / 直播 / 音乐 / 我的。
 * 搜索不占 tab —— 顶栏那个「搜索」按钮就是入口，键盘弹出即用。
 * 没有「设置」—— 片源、直播源、备份都在 PC 上做，这是产品定死的边界。
 */
@Composable
fun PhoneShell(
    /** 状态栏高度：影视页的 Hero 要通栏到顶，其余 tab 需要让开 */
    statusInset: androidx.compose.ui.unit.Dp = 0.dp,
    /** 手势条/导航栏高度：底栏要垫高，否则文字压在系统手势条上 */
    bottomInset: androidx.compose.ui.unit.Dp = 0.dp,
    /**
     * 当前 tab 由外壳持有：从「我的」进 NAS 页再返回时，
     * 如果状态留在这里，PhoneShell 会被重建、tab 又跳回「影视」。
     */
    tab: Int = 0,
    onTab: (Int) -> Unit = {},
    home: @Composable () -> Unit,
    live: @Composable () -> Unit,
    music: @Composable () -> Unit,
    mine: @Composable () -> Unit,
    /** 底部常驻的迷你播放条：有歌在放时才由壳注入内容 */
    miniPlayer: @Composable () -> Unit = {},
) {
    Column(Modifier.fillMaxSize().background(ProomColor.bg)) {
        Box(Modifier.weight(1f)) {
            when (tab) {
                0 -> home()
                // 影视页自己处理（Hero 通栏），其余 tab 统一让开状态栏
                1 -> Box(Modifier.padding(top = statusInset)) { live() }
                2 -> Box(Modifier.padding(top = statusInset)) { music() }
                else -> Box(Modifier.padding(top = statusInset)) { mine() }
            }
        }
        miniPlayer()
        PhoneTabBar(current = tab, bottomInset = bottomInset, onSelect = onTab)
    }
}

/**
 * 底部导航条。
 *
 * 尺寸按触控规范来：整项 56dp 高（≥48dp 可点区），图标 22dp，文字 11sp。
 * 选中态**不只靠颜色**（色弱用户要分得清）：图标从描边变实心 + 文字变金色。
 */
@Composable
private fun PhoneTabBar(current: Int, bottomInset: androidx.compose.ui.unit.Dp = 0.dp, onSelect: (Int) -> Unit) {
    val items = listOf(
        "影视" to (TabIcons.Movie to TabIcons.MovieOn),
        "直播" to (TabIcons.Live to TabIcons.LiveOn),
        "音乐" to (TabIcons.Music to TabIcons.MusicOn),
        "我的" to (TabIcons.Mine to TabIcons.MineOn),
    )
    Column(Modifier.fillMaxWidth()) {
        // 极细的一条分隔：不用描边，靠明度差
        Box(Modifier.fillMaxWidth().height(0.5.dp).background(ProomColor.line))
        Row(
            Modifier
                .fillMaxWidth()
                .background(ProomColor.bgSoft)
                .padding(top = 8.dp, bottom = 10.dp + bottomInset)
        ) {
            items.forEachIndexed { i, (label, vectors) ->
                val on = i == current
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .clip(RoundedCornerShape(ProomShape.sm))
                        .clickable(onClick = { onSelect(i) }),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Icon(
                        imageVector = if (on) vectors.second else vectors.first,
                        contentDescription = label,
                        tint = if (on) ProomColor.accent else ProomColor.muted,
                        modifier = Modifier.size(22.dp),
                    )
                    Text(
                        text = label,
                        style = ProomType.label,
                        color = if (on) ProomColor.accent else ProomColor.muted,
                        modifier = Modifier.padding(top = 3.dp),
                    )
                }
            }
        }
    }
}
