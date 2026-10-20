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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.projectionroom.shared.catalog.NasApi
import com.projectionroom.shared.model.Vod
import com.projectionroom.ui.component.PosterCard
import com.projectionroom.ui.theme.ProomColor
import com.projectionroom.ui.theme.ProomSpace
import com.projectionroom.ui.theme.ProomType

/** 分类 slug → 中文，直接用数据层那份，避免界面里再抄一遍走样 */
val CAT_NAMES: Map<String, String> = com.projectionroom.shared.catalog.Cats.slugToName

private val YEARS = listOf("", "2026", "2025", "2024", "2023", "2022", "2021", "2020")
private val AREAS = listOf("", "内地", "中国香港", "中国台湾", "美国", "日本", "韩国", "英国")

/**
 * 片库：页头 + 年份/地区筛选 + 海报网格。
 *
 * 和 Web 版一样，筛选条在页头下面一行，选中项金色描边。
 *
 * 分页：一页 60 条，滚到接近底部自动接下一页（最多 30 页，服务端上限）。
 * 触发用「最后一个可见下标 ≥ 总数 - 8」而不是「到底了」—— 留 8 格提前量，
 * 手指/遥控器还没滑到头，下一批已经在路上了，滚动不会出现一次明显的等待。
 */
@Composable
fun BrowseScreen(
    slug: String,
    /**
     * 数据来源由壳注入：现在优先本地聚合（按配置包里的分类映射直连各站），
     * NAS 只是备胎。页面不再认识 NasApi，也就没法偷偷依赖它。
     */
    load: suspend (cat: String, page: Int, year: String, area: String) -> NasApi.BrowsePage,
    onOpen: (Vod) -> Unit,
) {
    var year by remember(slug) { mutableStateOf("") }
    var area by remember(slug) { mutableStateOf("") }
    var items by remember(slug) { mutableStateOf<List<Vod>>(emptyList()) }
    var total by remember(slug) { mutableStateOf(0) }
    var sites by remember(slug) { mutableStateOf(0) }
    var loading by remember(slug) { mutableStateOf(true) }
    var loadingMore by remember(slug) { mutableStateOf(false) }
    var page by remember(slug) { mutableIntStateOf(1) }
    var more by remember(slug) { mutableStateOf(false) }
    var err by remember(slug) { mutableStateOf<String?>(null) }

    LaunchedEffect(slug, year, area) {
        loading = true
        err = null
        page = 1
        runCatching { load(slug, 1, year, area) }
            .onSuccess {
                items = it.items
                total = it.total
                sites = it.sites
                more = it.more
            }
            .onFailure { err = it.message }
        loading = false
    }

    val gridState = rememberLazyGridState()

    // 触底加载。snapshotFlow 只在可见范围变化时醒一次，不会每帧空转
    LaunchedEffect(gridState, slug, year, area) {
        snapshotFlow {
            val info = gridState.layoutInfo
            (info.visibleItemsInfo.lastOrNull()?.index ?: -1) to info.totalItemsCount
        }.collect { (last, count) ->
            if (!more || loadingMore || count == 0) return@collect
            if (last < count - 8) return@collect
            loadingMore = true
            runCatching { load(slug, page + 1, year, area) }
                .onSuccess {
                    // 采集站同一页可能回重复，按名字再滤一次，避免网格里出现同款两张
                    val seen = items.mapTo(HashSet()) { it.name }
                    items = items + it.items.filter { v -> seen.add(v.name) }
                    page += 1
                    more = it.more
                }
            loadingMore = false
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(ProomColor.bg)) {
        val cardW = (maxWidth * 0.115f).coerceIn(110.dp, 200.dp)
        val columns = (maxWidth / (cardW + 14.dp)).toInt().coerceIn(3, 8)

        LazyVerticalGrid(
            state = gridState,
            columns = GridCells.Fixed(columns),
            contentPadding = PaddingValues(start = ProomSpace.xxl, end = ProomSpace.xxl, bottom = ProomSpace.xxl),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalArrangement = Arrangement.spacedBy(ProomSpace.lg),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column(Modifier.padding(top = ProomSpace.xl)) {
                    Text("片库", style = ProomType.eyebrow, color = ProomColor.accent)
                    Text(
                        CAT_NAMES[slug] ?: "片库",
                        style = ProomType.display,
                        color = ProomColor.text,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                    Text(
                        text = when {
                            loading -> "正在向片源要数据…"
                            err != null -> "读取失败：$err"
                            else -> "已载入 ${items.size} 部" +
                                if (more) " · 还有更多（第 $page 页）" else " · 共 $total 部 · $sites 个片源参与"
                        },
                        style = ProomType.meta,
                        color = ProomColor.muted,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }

            item(span = { GridItemSpan(maxLineSpan) }) {
                Column(Modifier.padding(vertical = ProomSpace.md)) {
                    ChipRow("年份", YEARS, year) { year = it }
                    Spacer(Modifier.height(8.dp))
                    ChipRow("地区", AREAS, area) { area = it }
                }
            }

            if (loading && items.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Box(Modifier.fillMaxWidth().height(240.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = ProomColor.accent)
                    }
                }
            }

            items(items) { v ->
                PosterCard(
                    title = v.name,
                    meta = listOf(v.year, v.type).filter { it.isNotBlank() }.joinToString(" · "),
                    poster = v.pic,
                    onClick = { onOpen(v) },
                )
            }

            if (loadingMore) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Box(Modifier.fillMaxWidth().height(56.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = ProomColor.accent, modifier = Modifier.size(22.dp))
                    }
                }
            }

            if (!loading && items.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Box(Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
                        Text("这个筛选下没有片子", style = ProomType.meta, color = ProomColor.muted)
                    }
                }
            }
        }
    }
}

@Composable
private fun ChipRow(label: String, values: List<String>, selected: String, onSelect: (String) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = ProomType.label, color = ProomColor.muted, modifier = Modifier.padding(end = 6.dp))
        values.forEach { v ->
            FilterChip(
                text = v.ifBlank { "全部" },
                on = v == selected,
                onClick = { onSelect(v) },
            )
        }
    }
}

@Composable
private fun FilterChip(text: String, on: Boolean, onClick: () -> Unit) {
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
            .padding(horizontal = 13.dp, vertical = 6.dp),
    ) {
        Text(
            text = text,
            style = ProomType.label,
            color = if (on) ProomColor.accent else ProomColor.text,
        )
    }
}
