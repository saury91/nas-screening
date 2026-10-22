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
import com.projectionroom.shared.model.Vod
import com.projectionroom.ui.component.PosterCard
import com.projectionroom.ui.component.TvRow
import com.projectionroom.ui.theme.ProomColor
import com.projectionroom.ui.theme.ProomShape
import com.projectionroom.ui.theme.ProomSpace
import com.projectionroom.ui.theme.ProomType
import com.projectionroom.ui.theme.TvLayout
import com.projectionroom.ui.component.RemoteImage

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
    /** 打开设置页（NAS 地址 / 同步配置 / 恢复出厂 / 检查更新） */
    onSettings: (() -> Unit)? = null,
    topInset: Dp = 0.dp,
) {
    BoxWithConstraints(Modifier.fillMaxSize().background(ProomColor.bg)) {
        // 海报源图 270 宽：卡片别超过 ~128dp（256px），再大就糊。
        // 一屏 6 张、左右留安全区 —— 和首页货架的卡片尺寸保持一致
        val gap = ProomSpace.md
        val avail = maxWidth - TvLayout.insetX * 2
        val cardW = ((avail - gap * 5) / 6).coerceIn(96.dp, 136.dp)

        // 收藏为空时，第一条记录才是这一屏「第一个能按的东西」。
        // 不接住的话焦点会落到顶栏的「返回」上（那也是可聚焦的），一按就退出去。
        val historyFirst = remember { FocusRequester() }
        LaunchedEffect(favorites.size, history.size, loading) {
            if (loading || favorites.isNotEmpty() || history.isEmpty()) return@LaunchedEffect
            repeat(20) {
                if (runCatching { historyFirst.requestFocus() }.isSuccess) return@LaunchedEffect
                kotlinx.coroutines.delay(16)
            }
        }

        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = ProomSpace.xxl),
        ) {
            item {
                // 和其它页面同一条顶栏：左边距 40、返回提示与标题同高、右侧动作靠右
                com.projectionroom.ui.component.TvPageHeader(
                    onBack = onBack,
                    title = "我的",
                    topInset = topInset,
                    trailing = {
                        if (loading) {
                            Text(
                                "同步中…",
                                style = ProomType.label,
                                color = ProomColor.muted,
                                modifier = Modifier.padding(end = 10.dp),
                            )
                        }
                        // 电视上填地址是酷刑：给一个「手机推过来」的入口
                        if (onImportFromPhone != null) {
                            com.projectionroom.ui.component.TvPillButton("从手机导入配置", onImportFromPhone)
                            Spacer(Modifier.width(ProomSpace.md))
                        }
                        if (onSettings != null) {
                            com.projectionroom.ui.component.TvPillButton("设置", onSettings)
                        }
                    },
                )
            }

            if (favorites.isNotEmpty()) {
                item { MineSectionTitle("收藏", "${favorites.size} 部") }
                item {
                    TvRow(
                        items = favorites,
                        itemWidth = cardW,
                        gap = ProomSpace.md,
                        contentPadding = PaddingValues(horizontal = TvLayout.insetX),
                        // 数据是异步来的：首帧列表还是空的，焦点无处可去就会落到
                        // 顶栏的「返回」上，按一下直接退出去。列表一到就抢回来。
                        autoFocusFirst = true,
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
                history.forEachIndexed { i, v ->
                    item {
                        Box(Modifier.padding(horizontal = TvLayout.insetX, vertical = 5.dp)) {
                            HistoryBar(
                                v,
                                modifier = if (i == 0) Modifier.focusRequester(historyFirst) else Modifier,
                            ) { onOpen(v) }
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
private fun MineSectionTitle(title: String, sub: String) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = TvLayout.insetX, vertical = ProomSpace.sm),
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
private fun HistoryBar(v: Vod, modifier: Modifier = Modifier, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(ProomShape.sm)
    Row(
        modifier
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
                RemoteImage(
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
                // 进度条拉满文字列：0.52 这种"半条"宽度既读不出进度、又和右端的百分比对不上
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp)
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(ProomColor.lineStrong)
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth(v.progress.coerceIn(0f, 1f))
                            .height(4.dp)
                            .background(ProomColor.accent)
                    )
                }
            }
        }
    }
}
