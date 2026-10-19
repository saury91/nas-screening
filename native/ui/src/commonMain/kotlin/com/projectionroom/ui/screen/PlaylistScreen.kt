package com.projectionroom.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.projectionroom.shared.catalog.NasApi
import com.projectionroom.ui.theme.ProomColor
import com.projectionroom.ui.theme.ProomShape
import com.projectionroom.ui.theme.ProomSpace
import com.projectionroom.ui.theme.ProomType

/**
 * 歌单详情：封面 + 简介 + 曲目列表。
 *
 * 点任意一首就从这首开始播（取地址是点击时才做的 —— 播放地址带时效，预取会过期）。
 */
@Composable
fun PlaylistScreen(
    api: NasApi,
    id: String,
    onPlayTrack: (detail: NasApi.PlaylistDetail, index: Int) -> Unit,
) {
    var d by remember(id) { mutableStateOf<NasApi.PlaylistDetail?>(null) }
    var loading by remember(id) { mutableStateOf(true) }
    var err by remember(id) { mutableStateOf<String?>(null) }

    LaunchedEffect(id) {
        loading = true
        runCatching { api.musicPlaylist(id) }
            .onSuccess { d = it }
            .onFailure { err = it.message }
        loading = false
    }

    val detail = d
    Box(Modifier.fillMaxSize().background(ProomColor.bg)) {
        if (loading || err != null || detail == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                if (loading) CircularProgressIndicator(color = ProomColor.accent)
                else Text("读取失败：${err ?: "空歌单"}", style = ProomType.meta, color = ProomColor.muted)
            }
            return@Box
        }

        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = ProomSpace.xxl),
        ) {
            item {
                Row(Modifier.padding(ProomSpace.xxl)) {
                    Box(
                        Modifier
                            .width(150.dp)
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(ProomShape.md))
                            .background(ProomColor.card)
                    ) {
                        if (detail.cover.isNotBlank()) {
                            AsyncImage(
                                model = detail.cover,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                    Spacer(Modifier.width(ProomSpace.lg))
                    Column(Modifier.weight(1f)) {
                        Text("歌单", style = ProomType.eyebrow, color = ProomColor.accent)
                        Text(
                            detail.name,
                            style = ProomType.title,
                            color = ProomColor.text,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                        Text(
                            "${detail.count} 首",
                            style = ProomType.meta,
                            color = ProomColor.muted,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                        if (detail.desc.isNotBlank()) {
                            Text(
                                detail.desc,
                                style = ProomType.label,
                                color = ProomColor.dim,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(top = 8.dp),
                            )
                        }
                    }
                }
            }

            itemsIndexed(detail.tracks) { i, t ->
                TrackRow(index = i + 1, track = t) { onPlayTrack(detail, i) }
            }
        }
    }
}

@Composable
private fun TrackRow(index: Int, track: NasApi.Track, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(ProomShape.sm)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = ProomSpace.xxl, vertical = 3.dp)
            .clip(shape)
            .background(if (focused) ProomColor.surface2 else Color.Transparent)
            .then(if (focused) Modifier.border(2.dp, ProomColor.accent, shape) else Modifier)
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = index.toString().padStart(2, '0'),
            style = ProomType.label,
            color = if (focused) ProomColor.accent else ProomColor.muted,
            modifier = Modifier.width(34.dp),
        )
        Column(Modifier.weight(1f)) {
            Text(
                track.title,
                style = ProomType.body,
                color = ProomColor.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                track.artist,
                style = ProomType.label,
                color = ProomColor.muted,
                maxLines = 1,
            )
        }
    }
}
