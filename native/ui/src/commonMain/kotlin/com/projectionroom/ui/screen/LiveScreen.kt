package com.projectionroom.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.projectionroom.shared.catalog.NasApi
import com.projectionroom.ui.theme.LocalProomTv
import com.projectionroom.ui.theme.ProomColor
import com.projectionroom.ui.theme.ProomShape
import com.projectionroom.ui.theme.ProomSpace
import com.projectionroom.ui.theme.ProomType

/**
 * 电视直播：分组筛选 + 频道卡片。
 *
 * 卡片照 Web 版做：渐变底 + 左上大字水印（首字），右上角标（HD / N 线路），
 * 左下频道名与所属分组。没有台标图 —— 采集源基本不给 logo，用首字水印反而更整齐。
 */
@Composable
fun LiveScreen(
    api: NasApi,
    onPlay: (NasApi.LiveChannel) -> Unit,
) {
    var feed by remember { mutableStateOf<NasApi.LiveFeed?>(null) }
    var loading by remember { mutableStateOf(true) }
    var err by remember { mutableStateOf<String?>(null) }
    var group by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        loading = true
        runCatching { api.live() }
            .onSuccess { feed = it }
            .onFailure { err = it.message }
        loading = false
    }

    val all = feed?.channels.orEmpty()
    val shown = if (group.isBlank()) all else all.filter { it.group == group }

    BoxWithConstraints(Modifier.fillMaxSize().background(ProomColor.bg)) {
        val columns = (maxWidth / 210.dp).toInt().coerceIn(3, 7)
        LazyVerticalGrid(
            columns = GridCells.Fixed(columns),
            contentPadding = PaddingValues(
                start = ProomSpace.xxl, end = ProomSpace.xxl, bottom = ProomSpace.xxl,
            ),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalArrangement = Arrangement.spacedBy(ProomSpace.md),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column(Modifier.padding(top = ProomSpace.xl)) {
                    Text("直播", style = ProomType.eyebrow, color = ProomColor.accent)
                    Text(
                        "电视直播",
                        style = ProomType.display,
                        color = ProomColor.text,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                    Text(
                        text = when {
                            loading -> "正在读频道表…"
                            err != null -> "读取失败：$err"
                            else -> "${all.size} 个频道 · ${feed?.groups?.size ?: 0} 个分组"
                        },
                        style = ProomType.meta,
                        color = ProomColor.muted,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }

            item(span = { GridItemSpan(maxLineSpan) }) {
                GroupChips(
                    groups = feed?.groups.orEmpty(),
                    selected = group,
                    onSelect = { group = it },
                    total = all.size,
                )
            }

            if (loading && all.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Box(Modifier.fillMaxWidth().height(240.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = ProomColor.accent)
                    }
                }
            }

            items(shown) { ch ->
                ChannelCard(ch) { onPlay(ch) }
            }
        }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun GroupChips(groups: List<NasApi.LiveGroup>, selected: String, total: Int, onSelect: (String) -> Unit) {
    // 分组很多，这里只铺前两行（电视上横向滚动的 chip 列表反而难按）
    val visible = listOf("" to "全部 $total") +
        groups.take(23).map { "${it.name} ${it.count}" to it.name }
    androidx.compose.foundation.layout.FlowRow(
        Modifier.fillMaxWidth().padding(vertical = ProomSpace.md),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        visible.forEach { (slug, label) ->
            LiveChip(
                label = label,
                on = slug == selected,
                onClick = { onSelect(slug) },
            )
        }
    }
}

@Composable
private fun LiveChip(label: String, on: Boolean, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(50)
    Box(
        Modifier
            .clip(shape)
            .background(if (on) ProomColor.accentSoft else ProomColor.surface)
            .then(
                when {
                    focused -> Modifier.border(2.dp, ProomColor.accent, shape)
                    on -> Modifier.border(1.dp, ProomColor.accent.copy(alpha = 0.6f), shape)
                    else -> Modifier
                }
            )
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (on) {
                Box(Modifier.size(5.dp).clip(CircleShape).background(ProomColor.accent))
                Spacer(Modifier.padding(start = 5.dp))
            }
            Text(
                label,
                style = ProomType.label,
                color = if (on) ProomColor.accent else ProomColor.text,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun ChannelCard(ch: NasApi.LiveChannel, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val isTv = LocalProomTv.current
    val shape = RoundedCornerShape(ProomShape.md)

    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(16f / 9f)
            .clip(shape)
            .background(
                Brush.linearGradient(listOf(Color(0xFF171A22), Color(0xFF0E1015)))
            )
            .then(if (focused && isTv) Modifier.border(2.dp, ProomColor.accent, shape) else Modifier)
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick)
            .padding(12.dp),
    ) {
        // 台标位置放首字水印：采集源基本不给 logo
        Text(
            text = ch.name.take(1),
            color = Color.White.copy(alpha = 0.10f),
            fontSize = 44.sp,
            modifier = Modifier.align(Alignment.TopStart),
        )
        Text(
            text = if (ch.urls.size > 1) "${ch.urls.size} 线路" else "HD",
            style = ProomType.label,
            color = Color.White.copy(alpha = 0.5f),
            modifier = Modifier.align(Alignment.TopEnd),
        )
        Column(Modifier.align(Alignment.BottomStart)) {
            Text(
                ch.name,
                style = ProomType.cardTitle,
                color = ProomColor.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(4.dp).clip(CircleShape).background(ProomColor.ok))
                Text(
                    text = "  ${ch.group.ifBlank { "未分组" }}",
                    style = ProomType.label,
                    color = ProomColor.muted,
                    maxLines = 1,
                )
            }
        }
    }
}
