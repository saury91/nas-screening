package com.projectionroom.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.projectionroom.shared.model.Vod
import com.projectionroom.ui.component.PosterCard
import com.projectionroom.ui.component.TvRow
import com.projectionroom.ui.theme.ProomColor
import com.projectionroom.ui.theme.ProomShape
import com.projectionroom.ui.theme.ProomSpace
import com.projectionroom.ui.theme.ProomType

/**
 * 电视端的「我的」：收藏走横滑货架，播放记录走竖排宽条。
 *
 * 和手机版是两套版式：电视不能靠上下滑长列表翻，第一屏就得把「收藏了哪些」
 * 摊开给人看；记录则是「上次看到第几集」，一条一行、一行一条进度。
 */
@Composable
fun TvMineScreen(
    favorites: List<Vod>,
    history: List<Vod>,
    loading: Boolean = false,
    onOpen: (Vod) -> Unit,
    onBack: () -> Unit,
    /** 打开「用手机导入配置」的配对页；不传就不显示这个入口 */
    onImportFromPhone: (() -> Unit)? = null,
    topInset: Dp = 0.dp,
) {
    val backFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { backFocus.requestFocus() }
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(ProomColor.bg)) {
        // 海报源图 270 宽：卡片别超过 ~120dp（240px），再大就糊
        val cardW = (maxWidth * 0.105f).coerceIn(88.dp, 128.dp)

        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = ProomSpace.xxl),
        ) {
            item {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(start = ProomSpace.lg, end = ProomSpace.xxl, top = topInset + ProomSpace.md, bottom = ProomSpace.sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BackPill(backFocus, onBack)
                    Spacer(Modifier.width(ProomSpace.lg))
                    Text("我的", style = ProomType.headline, color = ProomColor.text)
                    if (loading) {
                        Text(
                            "同步中…",
                            style = ProomType.label,
                            color = ProomColor.muted,
                            modifier = Modifier.padding(start = 10.dp),
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    // 电视上填地址是酷刑：给一个「手机推过来」的入口
                    if (onImportFromPhone != null) {
                        PillButton2("从手机导入配置", onImportFromPhone)
                    }
                }
            }

            if (favorites.isNotEmpty()) {
                item { MineSectionTitle("收藏", "${favorites.size} 部") }
                item {
                    TvRow(
                        items = favorites,
                        itemWidth = cardW,
                        gap = ProomSpace.md,
                        contentPadding = PaddingValues(horizontal = ProomSpace.xxl),
                    ) { v, focused ->
                        PosterCard(
                            title = v.name,
                            meta = v.type,
                            poster = v.pic,
                            focused = focused,
                            onClick = { onOpen(v) },
                        )
                    }
                }
            }

            if (history.isNotEmpty()) {
                item { MineSectionTitle("播放记录", "${history.size} 条") }
                history.forEach { v ->
                    item {
                        Box(Modifier.padding(horizontal = ProomSpace.xxl, vertical = 5.dp)) {
                            HistoryBar(v) { onOpen(v) }
                        }
                    }
                }
            }

            if (!loading && favorites.isEmpty() && history.isEmpty()) {
                item {
                    Box(Modifier.fillMaxWidth().height(220.dp), contentAlignment = Alignment.Center) {
                        Text(
                            "还没有收藏和记录，在详情页按「收藏」就会出现在这里",
                            style = ProomType.body,
                            color = ProomColor.muted,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PillButton2(text: String, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(50)
    Box(
        Modifier
            .clip(shape)
            .background(if (focused) ProomColor.accentSoft else ProomColor.surface)
            .then(if (focused) Modifier.border(2.dp, ProomColor.accent, shape) else Modifier)
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Text(
            text,
            style = ProomType.meta,
            color = if (focused) ProomColor.accent else ProomColor.text,
        )
    }
}

@Composable
private fun BackPill(focus: FocusRequester, onBack: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(50)
    Box(
        Modifier
            .focusRequester(focus)
            .clip(shape)
            .background(if (focused) ProomColor.surface2 else ProomColor.surface)
            .then(if (focused) Modifier.border(2.dp, ProomColor.accent, shape) else Modifier)
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onBack)
            .padding(horizontal = 14.dp, vertical = 7.dp),
    ) {
        Text(
            "‹ 返回",
            style = ProomType.meta,
            color = if (focused) ProomColor.text else ProomColor.dim,
        )
    }
}

@Composable
private fun MineSectionTitle(title: String, sub: String) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = ProomSpace.xxl, vertical = ProomSpace.sm),
        verticalAlignment = Alignment.Bottom,
    ) {
        Text(title, style = ProomType.title, color = ProomColor.text)
        Text(
            sub,
            style = ProomType.meta,
            color = ProomColor.muted,
            modifier = Modifier.padding(start = 10.dp, bottom = 2.dp),
        )
    }
}

/** 一条播放记录：16:9 缩略图 + 片名 + 第几集 + 进度 */
@Composable
private fun HistoryBar(v: Vod, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(ProomShape.sm)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (focused) ProomColor.surface2 else ProomColor.surface)
            .then(if (focused) Modifier.border(2.dp, ProomColor.accent, shape) else Modifier)
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .clickable(onClick = onClick)
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .width(126.dp)
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(6.dp))
                .background(ProomColor.card),
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
        Column(Modifier.weight(1f).padding(start = 14.dp)) {
            // 片名和百分比同一行：百分比贴着右端，进度条就不用拉满整屏
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    v.name,
                    style = ProomType.cardTitle,
                    color = ProomColor.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (v.progress > 0f) {
                    Text(
                        "${(v.progress * 100).toInt()}%",
                        style = ProomType.label,
                        color = ProomColor.accent,
                        modifier = Modifier.padding(start = 10.dp),
                    )
                }
            }
            Text(
                v.type.ifBlank { "上次看到这里" },
                style = ProomType.label,
                color = ProomColor.muted,
                modifier = Modifier.padding(top = 2.dp),
            )
            if (v.progress > 0f) {
                Box(
                    Modifier
                        .fillMaxWidth(0.52f)
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
