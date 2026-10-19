package com.projectionroom.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.projectionroom.shared.model.Vod
import com.projectionroom.ui.component.PosterCard
import com.projectionroom.ui.theme.ProomColor
import com.projectionroom.ui.theme.ProomShape
import com.projectionroom.ui.theme.ProomSpace
import com.projectionroom.ui.theme.ProomType

/**
 * 「我的」：收藏 + 播放记录。
 *
 * 两块都用真实数据（`/api/library`）。记录是横条而不是网格 ——
 * 看记录时用户关心的是「我上次看到第几集、还剩多少」，封面够认出来就行。
 */
@Composable
fun MineScreen(
    favorites: List<Vod>,
    history: List<Vod>,
    loading: Boolean = false,
    onOpen: (Vod) -> Unit,
    onRefresh: () -> Unit = {},
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        modifier = Modifier.fillMaxSize().background(ProomColor.bg),
        contentPadding = PaddingValues(bottom = 88.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(ProomSpace.md),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = ProomSpace.lg, vertical = ProomSpace.lg),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("我的", style = ProomType.headline, color = ProomColor.text)
                if (loading) {
                    Text(
                        "  同步中…",
                        style = ProomType.label,
                        color = ProomColor.muted,
                    )
                }
            }
        }

        if (favorites.isNotEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) { SectionTitle("收藏 · ${favorites.size}") }
            items(favorites) { v ->
                PosterCard(
                    title = v.name,
                    meta = v.type,
                    poster = v.pic,
                    onClick = { onOpen(v) },
                )
            }
        }

        if (history.isNotEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) { SectionTitle("播放记录 · ${history.size}") }
            // 记录条要占满整行；LazyGrid 的 items() 没有 span 参数，只能逐条 item
            history.forEach { v ->
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Box(Modifier.padding(horizontal = ProomSpace.lg)) {
                        HistoryRow(v) { onOpen(v) }
                    }
                }
            }
        }

        if (!loading && favorites.isEmpty() && history.isEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Box(Modifier.fillMaxWidth().height(240.dp), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("还没有收藏和记录", style = ProomType.title, color = ProomColor.text)
                        Text(
                            "在详情页点心形就能收藏；看过的片会自动出现在这里",
                            style = ProomType.meta,
                            color = ProomColor.muted,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = ProomType.title,
        color = ProomColor.text,
        modifier = Modifier.padding(start = ProomSpace.lg, top = ProomSpace.sm, bottom = ProomSpace.sm),
    )
}

@Composable
private fun HistoryRow(v: Vod, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(ProomShape.sm))
            .background(ProomColor.surface)
            .clickable(onClick = onClick)
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .width(56.dp)
                .aspectRatio(2f / 3.05f)
                .clip(RoundedCornerShape(6.dp))
                .background(ProomColor.card)
        ) {
            Box(
                Modifier.fillMaxSize().background(
                    Brush.linearGradient(listOf(ProomColor.card, ProomColor.bgSoft))
                ),
                contentAlignment = Alignment.Center,
            ) {
                Text(v.name.take(1), style = ProomType.body, color = ProomColor.lineStrong)
            }
            if (v.pic.isNotBlank()) {
                AsyncImage(
                    model = v.pic,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(
                v.name,
                style = ProomType.cardTitle,
                color = ProomColor.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(v.type.ifBlank { "上次看到这里" }, style = ProomType.label, color = ProomColor.muted)
            if (v.progress > 0f) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                        .height(3.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(ProomColor.lineStrong)
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth(v.progress.coerceIn(0f, 1f))
                            .height(3.dp)
                            .background(ProomColor.accent)
                    )
                }
            }
        }
    }
}
