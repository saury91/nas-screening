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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
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
import com.projectionroom.ui.theme.LocalProomTv
import com.projectionroom.ui.theme.ProomColor
import com.projectionroom.ui.theme.ProomMotion
import com.projectionroom.ui.theme.ProomShape
import com.projectionroom.ui.theme.ProomSpace
import com.projectionroom.ui.theme.ProomType

/**
 * 音乐：在线歌单墙。
 *
 * 本地曲库/Subsonic 需要先在 PC 上配置，所以这里直接给「在线歌单」——
 * 不配置也能听，和 Web 版的行为一致。
 */
@Composable
fun MusicScreen(
    api: NasApi,
    onOpenPlaylist: (NasApi.Playlist) -> Unit,
) {
    var cats by remember { mutableStateOf<List<NasApi.MusicCatGroup>>(emptyList()) }
    var cat by remember { mutableStateOf("") }
    var items by remember { mutableStateOf<List<NasApi.Playlist>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var err by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        runCatching { api.musicCats() }.onSuccess { cats = it }
    }
    LaunchedEffect(cat) {
        loading = true
        err = null
        runCatching { api.musicPlaylists(cat) }
            .onSuccess { items = it }
            .onFailure { err = it.message }
        loading = false
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(ProomColor.bg)) {
        // 封面源图实测只有 482x482；3 列在 4K 上会放大到 900px，必然糊。
        // 按 190dp 一列排，单张落在 240~360px，基本贴着源图分辨率走。
        val cols = (maxWidth / 190.dp).toInt().coerceIn(4, 8)
        LazyVerticalGrid(
            columns = GridCells.Fixed(cols),
            contentPadding = PaddingValues(
                start = ProomSpace.xxl, end = ProomSpace.xxl, bottom = ProomSpace.xxl,
            ),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(ProomSpace.lg),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column(Modifier.padding(top = ProomSpace.xl)) {
                    Text("音乐", style = ProomType.eyebrow, color = ProomColor.accent)
                    Text(
                        "在线歌单",
                        style = ProomType.display,
                        color = ProomColor.text,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                    Text(
                        text = when {
                            loading -> "正在取歌单…"
                            err != null -> "读取失败：$err"
                            else -> "${items.size} 个歌单 · 不需要配置音乐库也能听"
                        },
                        style = ProomType.meta,
                        color = ProomColor.muted,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }

            if (cats.isNotEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    CatBar(cats = cats, current = cat) { cat = it }
                }
            }

            if (loading && items.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Box(Modifier.fillMaxWidth().height(240.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = ProomColor.accent)
                    }
                }
            }

            items(items) { p ->
                PlaylistCard(p) { onOpenPlaylist(p) }
            }
        }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun CatBar(cats: List<NasApi.MusicCatGroup>, current: String, onSelect: (String) -> Unit) {
    // 只取第一个分组里的子类（主题），再补一个「推荐」入口；分组太多反而不知道按哪个
    val subs = cats.firstOrNull()?.subs.orEmpty().take(8)
    androidx.compose.foundation.layout.FlowRow(
        Modifier.fillMaxWidth().padding(vertical = ProomSpace.sm),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        CatChip("推荐", cat = "") { onSelect("") }
        subs.forEach { c ->
            CatChip(c.name, cat = c.id) { onSelect(c.id) }
        }
    }
}

@Composable
private fun CatChip(label: String, cat: String, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val on = false
    val shape = RoundedCornerShape(50)
    Box(
        Modifier
            .clip(shape)
            .background(if (focused) ProomColor.surface2 else ProomColor.surface)
            .then(if (focused) Modifier.border(2.dp, ProomColor.accent, shape) else Modifier)
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick)
            .padding(horizontal = 13.dp, vertical = 6.dp),
    ) {
        Text(label, style = ProomType.label, color = ProomColor.text)
    }
}

@Composable
private fun PlaylistCard(p: NasApi.Playlist, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val isTv = LocalProomTv.current
    val shape = RoundedCornerShape(ProomShape.md)

    Column {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(shape)
                .background(ProomColor.card)
                .then(if (focused && isTv) Modifier.border(2.dp, ProomColor.accent, shape) else Modifier)
                .onFocusChanged { focused = it.isFocused }
                .clickable(onClick = onClick),
        ) {
            if (p.cover.isNotBlank()) {
                AsyncImage(
                    model = p.cover,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(0.6f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.6f))
                )
            )
            Text(
                text = "${p.count} 首",
                style = ProomType.label,
                color = Color.White.copy(alpha = 0.9f),
                modifier = Modifier.align(Alignment.BottomStart).padding(8.dp),
            )
        }
        Text(
            p.name,
            style = ProomType.cardTitle,
            color = ProomColor.text,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = ProomSpace.sm).height(40.dp),
        )
        Text(
            text = fmtPlay(p.play),
            style = ProomType.label,
            color = ProomColor.muted,
        )
    }
}

private fun fmtPlay(n: Long): String = when {
    n >= 100_000_000 -> "%.1f 亿".formatSafe(n / 100_000_000.0)
    n >= 10_000 -> "${n / 10_000} 万"
    else -> "$n"
}

private fun String.formatSafe(v: Double): String = v.toString().take(3)
