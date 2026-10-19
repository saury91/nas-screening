package com.projectionroom.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.projectionroom.ui.theme.ProomColor
import com.projectionroom.ui.theme.ProomSpace
import com.projectionroom.ui.theme.ProomType

/** 顶部分类。顺序和 Web 版一致，slug 对应 `/api/browse?cat=`。 */
val NAV_TABS = listOf(
    "首页" to null,
    "电影" to "movie",
    "剧集" to "series",
    "动漫" to "anime",
    "综艺" to "variety",
    "纪录片" to "doc",
    "直播" to "live",
    "音乐" to "music",
)

/**
 * 手机端分类胶囊：只有内容分类。
 * 「首页」由底部 tab 承担，「直播 / 音乐」也各自是 tab，这里再列一遍就是重复。
 */
val PHONE_CATS = listOf(
    "电影" to "movie",
    "剧集" to "series",
    "动漫" to "anime",
    "综艺" to "variety",
    "纪录片" to "doc",
)

/**
 * 顶部分类导航。选中项是金色胶囊（和 Web 版一致），
 * 未选中项在聚焦时才浮出背景 —— 干净，但遥控器不会找不到焦点。
 */
@Composable
fun TvNavBar(
    current: Int = 0,
    onSelect: (label: String, slug: String?) -> Unit = { _, _ -> },
) {
    Row(
        Modifier.padding(horizontal = ProomSpace.xxl, vertical = ProomSpace.md),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NAV_TABS.forEachIndexed { i, (label, slug) ->
            NavPill(
                label = label,
                selected = i == current,
                onClick = { onSelect(label, slug) },
            )
        }
    }
}

@Composable
private fun NavPill(label: String, selected: Boolean, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(50)
    Box(
        Modifier
            .clip(shape)
            .background(
                when {
                    selected -> ProomColor.accent
                    focused -> ProomColor.surface2
                    else -> Color.Transparent
                }
            )
            .then(if (focused && !selected) Modifier.border(2.dp, ProomColor.accent, shape) else Modifier)
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Text(
            text = label,
            style = ProomType.meta,
            color = when {
                selected -> Color(0xFF1B1206)
                focused -> ProomColor.text
                else -> ProomColor.muted
            },
        )
    }
}
