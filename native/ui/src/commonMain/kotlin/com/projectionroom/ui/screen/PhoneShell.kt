package com.projectionroom.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
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
import com.projectionroom.ui.theme.ProomColor
import com.projectionroom.ui.theme.ProomSpace
import com.projectionroom.ui.theme.ProomType

/**
 * 手机壳：底部三个 tab。
 *
 * 和 TV 刻意不同 —— 电视是「遥控器 + 焦点」，手机是「拇指 + 触摸」。
 * TV 那边靠货架横向滑动，手机这边用网格，一屏能看到更多、手指不用来回甩。
 *
 * tab 只有三个：首页 / 搜索 / 我的。
 * 没有「设置」—— 片源、直播源、备份都在 PC 上做，这是产品定死的边界。
 */
@Composable
fun PhoneShell(
    home: @Composable () -> Unit,
    search: @Composable () -> Unit,
    mine: @Composable () -> Unit,
) {
    var tab by remember { mutableIntStateOf(0) }

    Column(Modifier.fillMaxSize().background(ProomColor.bg)) {
        Box(Modifier.weight(1f)) {
            when (tab) {
                0 -> home()
                1 -> search()
                else -> mine()
            }
        }
        PhoneTabBar(current = tab, onSelect = { tab = it })
    }
}

@Composable
private fun PhoneTabBar(current: Int, onSelect: (Int) -> Unit) {
    val items = listOf("首页", "搜索", "我的")
    Column(Modifier.fillMaxWidth()) {
        // 极细的一条分隔：不用描边，靠明度差
        Box(Modifier.fillMaxWidth().height(0.5.dp).background(ProomColor.line))
        Row(
            Modifier
                .fillMaxWidth()
                .background(ProomColor.bgSoft)
                .padding(vertical = 10.dp)
        ) {
            items.forEachIndexed { i, label ->
                val on = i == current
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clickable(onClick = { onSelect(i) })
                        .padding(vertical = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    // 选中项上方一个小圆点：比图标省事，也比下划线安静
                    Box(
                        Modifier
                            .size(4.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(if (on) ProomColor.accent else androidx.compose.ui.graphics.Color.Transparent)
                    )
                    Text(
                        text = label,
                        style = ProomType.label,
                        color = if (on) ProomColor.accent else ProomColor.muted,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
        }
    }
}
