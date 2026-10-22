package com.projectionroom.ui.screen

import kotlinx.coroutines.flow.Flow

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.projectionroom.shared.catalog.NasApi
import com.projectionroom.ui.component.ChannelTile
import com.projectionroom.ui.component.SkeletonBlock
import com.projectionroom.ui.component.TabIcons
import com.projectionroom.ui.theme.ProomColor
import com.projectionroom.ui.theme.ProomMotion
import com.projectionroom.ui.theme.ProomShape
import com.projectionroom.ui.theme.ProomSpace
import com.projectionroom.ui.theme.ProomType
import kotlin.time.Duration.Companion.minutes

/**
 * 电视直播。
 *
 * 布局换过一版：原来是「大标题 + 24 个分组 chip 平铺 8 行 + 名字压在色块上的卡片」，
 * 首屏 40% 被筛选用掉，一屏只看得到 8 个台，而且卡片里除了名字什么也没有。
 *
 * 现在按「片上首页」的骨架来：
 *  - 固定顶栏（标题 + 频道总数 + 搜频道），和影视页顶栏同高同字重；
 *  - 分组只占**一行**横滑，滚到第 300 个台也随时能换组；
 *  - 频道卡改成「台标块 + 名字 + 分组」两段式，和海报卡（图 + 标题）同构，
 *    名字不再被压进色块里挤成一行省略号。
 *
 * 没有台标图 —— 采集源基本不给 logo，用频道名的识别字做水印（CCTV5 → 5，
 * 湖南卫视 → 湖），比清一色的「C」有辨识度。
 */
@Composable
fun LiveScreen(
    /**
     * 频道表来源由壳注入：优先用本地配置包（NAS 下发过一次就够），
     * 本地没有才去 NAS 问。直播表基本不变，没必要每次进页面都打 NAS。
     */
    /** 频道表，流式：先磁盘缓存，再源站 */
    load: () -> Flow<NasApi.LiveFeed>,
    /**
     * 点台：把**当前过滤后的整张表**和索引一起交出去。
     *
     * 播放器要在台上/台下直接换台，就得知道「这一屏有哪些台、我在第几个」；
     * 只给一个频道的话，进了播放器就换不了台，只能退回来重点。
     */
    onPlay: (channels: List<NasApi.LiveChannel>, index: Int) -> Unit,
    /** 返回。手机上从 tab 进来，电视上是遥控器 BACK 之外的兜底入口 */
    onBack: (() -> Unit)? = null,
    topInset: androidx.compose.ui.unit.Dp = 0.dp,
) {
    // 切 tab 会把整页销毁重建，没有缓存的话每次回来都要重新拉一遍频道表，
    // 中间闪一次骨架 —— 频道表几分钟内不会变，进程内留一份就够
    var feed by remember { mutableStateOf(LiveCache.fresh()) }
    var loading by remember { mutableStateOf(feed == null) }
    var err by remember { mutableStateOf<String?>(null) }
    var group by remember { mutableStateOf("") }
    var query by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }

    LaunchedEffect(reload) {
        if (LiveCache.fresh() != null && reload == 0) {
            // 命中缓存：先用旧的把页面立起来，不闪骨架
            feed = LiveCache.fresh()
            loading = false
            return@LaunchedEffect
        }
        loading = true
        err = null
        runCatching {
            load().collect { f ->
                // 先给磁盘缓存（秒出），源站结果到了再替换
                feed = LiveCache.put(f)
                loading = false
            }
        }.onFailure { err = it.message }
        loading = false
    }

    val all = feed?.channels.orEmpty()
    // 分组名带采集源的 emoji（📺🇨🇳🍎…），各家字体渲染不一致，
    // 在暗色影院这套克制的排版里特别扎眼，统一剥掉。
    val groups = remember(feed) {
        feed?.groups.orEmpty().map { it.copy(name = plainLabel(it.name)) }
    }
    val q = query.trim()
    val shown = remember(all, group, q) {
        // 保持源给的顺序：央视、卫视这些是采集源特意排在前面的，
        // 整体重排会把「打开就能看到 CCTV」变成「一屏 A 开头的境外台」。
        orderChannels(
            all.filter { ch ->
                val g = plainLabel(ch.group)
                (group.isBlank() || g == group) &&
                    (q.isBlank() || ch.name.contains(q, ignoreCase = true) ||
                        g.contains(q, ignoreCase = true))
            }
        )
    }

    val gridState = rememberLazyGridState()

    // 换筛选条件回到顶部：在第 20 屏点了个分组却停在原地，会以为「没切过去」
    LaunchedEffect(group, q) {
        if (gridState.firstVisibleItemIndex > 0) gridState.scrollToItem(0)
    }

    BoxWithConstraints(
        Modifier.fillMaxSize().background(ProomColor.bg).imePadding()
    ) {
        // 手机竖屏落在 3 列；横屏/电视按 180dp 一列铺，别让一张卡片撑满半个屏
        val columns = (maxWidth / 180.dp).toInt().coerceIn(3, 8)

        Column(Modifier.fillMaxSize()) {
            onBack?.let {
                    com.projectionroom.ui.component.PageBackBar(
                        onBack = it,
                        modifier = Modifier.padding(start = ProomSpace.lg),
                        topInset = topInset,
                    )
            }
            LiveHeader(
                total = all.size,
                groupCount = groups.size,
                searching = searching,
                query = query,
                onQuery = { query = it },
                onOpenSearch = { searching = true },
                onCloseSearch = {
                    searching = false
                    query = ""
                },
            )

            GroupRail(
                groups = groups,
                selected = group,
                total = all.size,
                onSelect = { group = it },
            )

            LazyVerticalGrid(
                columns = GridCells.Fixed(columns),
                state = gridState,
                modifier = Modifier.fillMaxSize().weight(1f),
                contentPadding = PaddingValues(
                    start = 20.dp, end = 20.dp, top = 14.dp, bottom = ProomSpace.xxl,
                ),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                when {
                    loading && all.isEmpty() -> items(12) { ChannelSkeleton() }

                    err != null && all.isEmpty() -> item(span = { GridItemSpan(maxLineSpan) }) {
                        LiveMessage(
                            title = "频道表读取失败",
                            desc = err ?: "",
                            action = "重试",
                            onAction = { reload += 1 },
                        )
                    }

                    shown.isEmpty() -> item(span = { GridItemSpan(maxLineSpan) }) {
                        LiveMessage(
                            title = if (q.isNotBlank()) "没有匹配「$q」的频道" else "这个分组还没有频道",
                            desc = "换个关键词，或者看全部 ${all.size} 个频道",
                            action = "清除筛选",
                            onAction = {
                                query = ""
                                group = ""
                            },
                        )
                    }

                    else -> itemsIndexed(shown) { idx, ch ->
                        // 已经在某个分组里时，每张卡再写一遍组名就是纯噪声
                        ChannelCard(ch, showGroup = group.isBlank()) { onPlay(shown, idx) }
                    }
                }
            }
        }
    }
}

/* ───────────────────────── 顶栏 ───────────────────────── */

@Composable
private fun LiveHeader(
    total: Int,
    groupCount: Int,
    searching: Boolean,
    query: String,
    onQuery: (String) -> Unit,
    onOpenSearch: () -> Unit,
    onCloseSearch: () -> Unit,
) {
    // 电视上离得远，标题要大一档、也没有触摸搜频道的需求
    val h = 56.dp

    Row(
        Modifier
            .fillMaxWidth()
            .height(h)
            .padding(start = 20.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (searching) {
            SearchField(
                query = query,
                onQuery = onQuery,
                onClose = onCloseSearch,
                modifier = Modifier.weight(1f),
            )
        } else {
            Box(Modifier.size(6.dp).clip(CircleShape).background(ProomColor.accent))
            Spacer(Modifier.width(8.dp))
            Text(
                "直播",
                color = ProomColor.text,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.2.sp,
            )
            if (total > 0) {
                // 数量给语境：不是干巴巴一个数字，后面跟着单位
                Text(
                    "$total 个频道 · $groupCount 组",
                    style = ProomType.meta,
                    color = ProomColor.muted,
                    modifier = Modifier.padding(start = 10.dp),
                    maxLines = 1,
                )
            }
            Spacer(Modifier.weight(1f))
                Box(
                    Modifier.size(48.dp).clip(CircleShape).clickable(onClick = onOpenSearch),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = TabIcons.Search,
                        contentDescription = "搜索频道",
                        tint = ProomColor.text,
                        modifier = Modifier.size(21.dp),
                    )
                }
        }
    }
}

@Composable
private fun SearchField(
    query: String,
    onQuery: (String) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val fr = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    LaunchedEffect(Unit) {
        fr.requestFocus()
        keyboard?.show()
    }

    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = TabIcons.Search,
            contentDescription = null,
            tint = ProomColor.muted,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(10.dp))
        BasicTextField(
            value = query,
            onValueChange = onQuery,
            singleLine = true,
            textStyle = TextStyle(
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                color = ProomColor.text,
            ),
            cursorBrush = SolidColor(ProomColor.accent),
            modifier = Modifier.weight(1f).focusRequester(fr),
            decorationBox = { inner ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (query.isEmpty()) {
                        Text("搜频道或分组", style = ProomType.body, color = ProomColor.muted)
                    }
                    inner()
                }
            },
        )
        Box(
            Modifier.size(48.dp).clip(CircleShape).clickable(onClick = onClose),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = TabIcons.Close,
                contentDescription = "关闭搜索",
                tint = ProomColor.muted,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/* ───────────────────── 分组：固定一行横滑 ───────────────────── */

@Composable
private fun GroupRail(
    groups: List<NasApi.LiveGroup>,
    selected: String,
    total: Int,
    onSelect: (String) -> Unit,
) {
    // 之前把 40 个分组平铺成 8 行 chip，首屏直接被筛选用掉 40%。
    // 这里收敛成一行横滑：分组再多也只占一行，滚动时始终可见。
    Box(Modifier.fillMaxWidth()) {
        LazyRow(
            contentPadding = PaddingValues(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            // 和 chip 同高：行比 chip 矮会把 48dp 的热区裁回 44dp
            modifier = Modifier.fillMaxWidth().height(48.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            item {
                LiveChip("全部 $total", on = selected.isBlank()) { onSelect("") }
            }
            items(groups) { g ->
                LiveChip(g.name, on = g.name == selected) { onSelect(g.name) }
            }
        }
        // 右边缘渐隐：告诉用户这一行还能往右滑，而不是「只有这些」
        Box(
            Modifier
                .align(Alignment.CenterEnd)
                .width(28.dp)
                .height(48.dp)
                .background(
                    Brush.horizontalGradient(
                        listOf(Color.Transparent, ProomColor.bg)
                    )
                )
        )
    }
}

@Composable
private fun LiveChip(label: String, on: Boolean, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(50)
    Box(
        Modifier
            // 48dp：低于它的触控目标手指一定会点错，而分组是横着排的一行，
            // 高度差 14dp 只影响这一行，不会挤掉下面的台标
            .height(48.dp)
            .clip(shape)
            .background(if (on) ProomColor.accentSoft else ProomColor.surface)
            .then(
                when {
                    focused -> Modifier.border(2.dp, ProomColor.accent, shape)
                    on -> Modifier.border(1.dp, ProomColor.accent.copy(alpha = 0.55f), shape)
                    else -> Modifier
                }
            )
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            fontSize = 13.sp,
            fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium,
            color = if (on) ProomColor.accent else ProomColor.dim,
            maxLines = 1,
            softWrap = false,
        )
    }
}

/* ───────────────────────── 频道卡 ───────────────────────── */

/**
 * 源里有些台的名字就是个数字（"3"、"4"），列表上看就是光秃秃一个数，
 * 用户不知道点进去是什么。补个「频道」前缀，至少读得出这是个台。
 */
private fun displayName(raw: String): String {
    val n = raw.trim()
    return if (n.isNotEmpty() && n.all { it.isDigit() }) "频道 $n" else n
}

@Composable
private fun ChannelCard(ch: NasApi.LiveChannel, showGroup: Boolean, onClick: () -> Unit) {
    // 台标块 + 名字 + 分组，和「我的」里的收藏/记录共用同一个组件
    ChannelTile(
        name = displayName(ch.name),
        subtitle = if (showGroup) plainLabel(ch.group).ifBlank { "未分组" } else "",
        logo = ch.logo,
        // 多线路是可用性信号（这条挂了换下一条），单线路就不用标了
        badge = if (ch.urls.size > 1) "${ch.urls.size} 源" else "",
        onClick = onClick,
    )
}

@Composable
private fun ChannelSkeleton() {
    Column(Modifier.fillMaxWidth()) {
        SkeletonBlock(Modifier.fillMaxWidth().aspectRatio(16f / 9f), radius = ProomShape.md)
        SkeletonBlock(
            Modifier.fillMaxWidth(0.78f).padding(top = 9.dp),
            height = 11.dp,
            radius = 5.dp,
        )
        SkeletonBlock(
            Modifier.fillMaxWidth(0.42f).padding(top = 5.dp),
            height = 9.dp,
            radius = 4.dp,
        )
    }
}

/** 空态 / 错误态：成块出现（图标 + 一句解释 + 一个动作），不要孤零零一行字 */
@Composable
private fun LiveMessage(title: String, desc: String, action: String, onAction: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(top = 72.dp, bottom = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(46.dp).clip(CircleShape).background(ProomColor.surface),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = TabIcons.Live,
                contentDescription = null,
                tint = ProomColor.muted,
                modifier = Modifier.size(22.dp),
            )
        }
        Spacer(Modifier.height(14.dp))
        Text(title, style = ProomType.body, color = ProomColor.dim)
        if (desc.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(
                desc,
                style = ProomType.label,
                color = ProomColor.muted,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 32.dp),
            )
        }
        Spacer(Modifier.height(16.dp))
        Box(
            Modifier
                .clip(RoundedCornerShape(50))
                .background(ProomColor.surface)
                .clickable(onClick = onAction)
                .padding(horizontal = 18.dp, vertical = 9.dp),
        ) {
            Text(action, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = ProomColor.text)
        }
    }
}

/**
 * 同前缀的相邻频道按编号排，跨前缀一律不动。
 *
 * 源给的是「CCTV1、CCTV10、CCTV11…CCTV2」这种字符串序，找 CCTV5 要滑半天；
 * 但源把央视排在最前面是有意为之，整体按字母重排反而更糟。
 * 所以只在「连续的同前缀段」内部修一下数字序。
 */
private fun orderChannels(list: List<NasApi.LiveChannel>): List<NasApi.LiveChannel> {
    if (list.size < 2) return list
    val out = ArrayList<NasApi.LiveChannel>(list.size)
    var i = 0
    while (i < list.size) {
        val prefix = prefixOf(list[i].name)
        var j = i + 1
        while (j < list.size && prefixOf(list[j].name) == prefix) j++
        if (j - i > 1) {
            out += list.subList(i, j).sortedWith { a, b -> naturalCompare(a.name, b.name) }
        } else {
            out += list[i]
        }
        i = j
    }
    return out
}

private fun prefixOf(name: String): String = name.takeWhile { !it.isDigit() }.lowercase()

/**
 * 自然序：把两边的数字段当数值比。
 *
 * 频道名里编号是主角（CCTV1 … CCTV17、CCTV5+），纯字符串比较会把
 * CCTV10 排在 CCTV2 前面。只在数字段上动手，其余字符照旧逐字比。
 */
internal fun naturalCompare(a: String, b: String): Int {
    var i = 0
    var j = 0
    while (i < a.length && j < b.length) {
        val ca = a[i]
        val cb = b[j]
        if (ca.isDigit() && cb.isDigit()) {
            val si = i
            while (i < a.length && a[i].isDigit()) i++
            val sj = j
            while (j < b.length && b[j].isDigit()) j++
            // 前导 0 不参与比较（CCTV007 与 CCTV7 视为同一号）
            val na = a.substring(si, i).trimStart('0')
            val nb = b.substring(sj, j).trimStart('0')
            if (na.length != nb.length) return na.length - nb.length
            val c = na.compareTo(nb)
            if (c != 0) return c
        } else {
            val c = ca.lowercaseChar().compareTo(cb.lowercaseChar())
            if (c != 0) return c
            i++
            j++
        }
    }
    return (a.length - i) - (b.length - j)
}


/* ───────────────────────── 文本处理 ───────────────────────── */


/**
 * 去掉分组名里的 emoji 与装饰符号。
 *
 * 采集源的分组名长这样：「📺 IPTV」「🇨🇳 央视频道」「🍎 央视付费频道」。
 * emoji 在暗色界面里是跳色的杂色块，而且各家 ROM 的字体不一致，
 * 会把整行的字重节奏打乱 —— 名称本身的文字信息一个字都不要丢。
 */
internal fun plainLabel(src: String): String {
    val sb = StringBuilder(src.length)
    var i = 0
    while (i < src.length) {
        val c = src[i]
        val code = c.code
        i += when {
            // 代理对：emoji / 生僻字扩展区，整对跳过（国旗、👨‍👩‍👧 都在这里）
            c.isHighSurrogate() || c.isLowSurrogate() -> 1
            code == 0xFE0F || code == 0xFE0E || code == 0x200D || code == 0x20E3 -> 1
            code in 0x2600..0x27BF -> 1   // ☀ ✅ ✂ 等杂项符号
            code in 0x2B00..0x2BFF -> 1   // ⭐ ⬆ 等
            code in 0x25A0..0x25FF -> 1   // ▶ ■ 等几何图形（有些源拿来当箭头）
            code in 0x1F000..0x1FAFF -> 1
            else -> {
                sb.append(c)
                1
            }
        }
    }
    // 剥完 emoji 常在首尾留下分隔符与多余空格
    return sb.toString().trim().trim('-', '·', '|', '｜', '/', '、', ' ').trim()
}


/**
 * 直播频道表的进程内缓存。
 *
 * 只是省掉「切 tab 就重新读一遍」的开销，不做持久化 —— 落盘的是配置包，
 * 这里再从本地读一次没有意义。5 分钟内认为够新。
 */
private object LiveCache {
    private var feed: NasApi.LiveFeed? = null
    private var mark = kotlin.time.TimeSource.Monotonic.markNow()

    fun fresh(): NasApi.LiveFeed? =
        feed?.takeIf { mark.elapsedNow() < 5.minutes }

    fun put(f: NasApi.LiveFeed): NasApi.LiveFeed {
        feed = f
        mark = kotlin.time.TimeSource.Monotonic.markNow()
        return f
    }
}
