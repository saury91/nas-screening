package com.projectionroom.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.projectionroom.shared.catalog.NasApi
import com.projectionroom.shared.model.Vod
import com.projectionroom.ui.component.PosterCard
import com.projectionroom.ui.util.metaLine
import com.projectionroom.ui.theme.ProomColor
import com.projectionroom.ui.theme.ProomSpace
import com.projectionroom.ui.theme.ProomSp
import com.projectionroom.ui.theme.ProomType
import com.projectionroom.ui.theme.TvLayout
import kotlinx.coroutines.delay

/**
 * 搜索页。
 *
 * 输入防抖 400ms：采集站一次查询要打十几个源，每敲一个字就发一次会把 NAS 打满。
 * 电视上用遥控器输入很痛苦，所以这里同时支持「按确认立刻搜」（不等防抖）。
 */
@Composable
fun SearchScreen(
    /**
     * 搜索入口由壳注入：优先本地聚合（直连源站），NAS 可用时再补一次。
     * 页面不关心数据从哪来 —— 这也是 NAS 关机还能搜的前提。
     */
    search: suspend (String) -> List<Vod>,
    onOpen: (Vod) -> Unit,
    initialQuery: String = "",
    onBack: () -> Unit = {},
    /** 本机搜索历史（最近 10 条） */
    history: List<String> = emptyList(),
    onHistoryCommit: (String) -> Unit = {},
    onHistoryRemove: (String) -> Unit = {},
    onHistoryClear: () -> Unit = {},
    /** 状态栏高度：本页不是沉浸式，顶部要让开 */
    topInset: androidx.compose.ui.unit.Dp = 0.dp,
) {
    var q by remember { mutableStateOf(initialQuery) }
    var committed by remember { mutableStateOf(initialQuery) }
    var loading by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf<List<Vod>>(emptyList()) }
    var err by remember { mutableStateOf<String?>(null) }
    var searched by remember { mutableStateOf(false) }
    // 只有「按确认 / 点热词」才算一次真正的搜索，才进历史。
    // 防抖搜索是逐字触发的，原来每次防抖都记一条历史 —— 打 tyson 会留下
    // t / ty / tys … 一串半截词，最近搜索列表基本没法用。
    var explicitSearch by remember { mutableStateOf(false) }
    // 点热词/历史时不希望软键盘弹出来：chip 行消失后焦点会掉回输入框，
    // 输入框一拿到焦点就开软键盘，正好盖住刚出来的结果。
    var fromChip by remember { mutableStateOf(false) }
    val firstResult = remember { FocusRequester() }

    // 结果到了就把焦点交给第一张海报（只在「点词进来」时这么做，
    // 手动打字时必须留在输入框里，否则打一半焦点被抢走）
    LaunchedEffect(results, fromChip) {
        if (!fromChip || results.isEmpty()) return@LaunchedEffect
        repeat(10) {
            if (runCatching { firstResult.requestFocus() }.isSuccess) {
                fromChip = false
                return@LaunchedEffect
            }
            delay(40)
        }
    }

    LaunchedEffect(committed) {
        val kw = committed.trim()
        if (kw.isEmpty()) {
            results = emptyList()
            searched = false
            return@LaunchedEffect
        }
        delay(400)                      // 停下再查，别打满 NAS
        if (explicitSearch) {
            onHistoryCommit(kw)
            explicitSearch = false
        }
        loading = true
        err = null
        runCatching { search(kw) }
            .onSuccess { results = it }
            .onFailure { err = it.message }
        loading = false
        searched = true
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(ProomColor.bg)
    ) {
        // 统一顶栏：这一屏不需要标题，返回提示与其它页同一位置/字号
        com.projectionroom.ui.component.TvPageHeader(
            onBack = onBack,
            topInset = topInset,
        )
        Row(
            Modifier
                .fillMaxWidth()
                // 返回胶囊和搜索框同色、同一条左边线，贴在一起像两个输入框
                .padding(
                    start = com.projectionroom.ui.component.TvPageHeaderSpec.inset,
                    top = 10.dp,
                    end = com.projectionroom.ui.component.TvPageHeaderSpec.inset,
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1f)) {
                SearchField(
                    value = q,
                    // 删空时立刻回到空态：等 LaunchedEffect 重启要过一帧，
                    // 中间那一帧会闪出「没找到「」，换个片名试试」
                    onValueChange = {
                        q = it
                        committed = it
                        if (it.isBlank()) searched = false
                    },
                    onSearch = { explicitSearch = true; committed = q },
                )
            }
        }

        // 历史：每条右侧一个 ×，右上角「清空」
        if (!searched && q.isBlank() && history.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().padding(start = TvLayout.insetX, end = TvLayout.insetX, top = ProomSpace.md),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("最近搜索", style = ProomType.label, color = ProomColor.muted)
            }
            LazyRow(
                Modifier.fillMaxWidth().padding(top = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(ProomSpace.sm),
                contentPadding = PaddingValues(start = TvLayout.insetX, end = TvLayout.insetX),
            ) {
                items(history) { w ->
                    HistoryChip(
                        w,
                        onPick = { explicitSearch = true; fromChip = true; q = w; committed = w },
                        onRemove = { onHistoryRemove(w) },
                    )
                }
                // 「清空」挂在最后一个历史词后面，而不是右上角：
                // 右上角那颗离 chip 行是斜上方，遥控器按上会先回搜索框，根本走不到它。
                item { ClearChip(onHistoryClear) }
            }
        }

        // 电视上遥控器打字太痛苦，给几个热门词，点一下直接搜
        if (!searched && q.isBlank()) {
            // 用 LazyRow：5 个词在窄屏上放不下，横滑而不是被裁掉
            Row(
                Modifier.fillMaxWidth().padding(start = TvLayout.insetX, end = TvLayout.insetX, top = ProomSpace.md),
            ) {
                Text("热门", style = ProomType.label, color = ProomColor.muted)
            }
            LazyRow(
                Modifier.fillMaxWidth().padding(top = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(ProomSpace.sm),
                contentPadding = PaddingValues(start = TvLayout.insetX, end = TvLayout.insetX),
            ) {
                items(HOT_WORDS) { w ->
                    HotChip(w) { explicitSearch = true; fromChip = true; q = w; committed = w }
                }
            }
        }

        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f).padding(top = ProomSpace.lg)) {
            // 和片库页同一条算法：列数从安全区内的可用宽度反算。
            // 原来这里写死 3 列 + 20dp 边距，搜索框比它多缩进 28dp，
            // 一屏里两个左边线对不齐，而且 960dp 上 3 列的大卡片远看只有 3 张。
            val gap = 14.dp
            val columns = ((maxWidth - TvLayout.insetX * 2) / 130.dp).toInt().coerceIn(3, 8)
            when {
                // 骨架屏而不是转圈：网格形状先立住，结果回来直接换内容，不会整页跳
                loading -> LazyVerticalGrid(
                    columns = GridCells.Fixed(columns),
                    contentPadding = PaddingValues(start = TvLayout.insetX, end = TvLayout.insetX, top = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(gap),
                    verticalArrangement = Arrangement.spacedBy(ProomSpace.lg),
                ) {
                    items(9) {
                        com.projectionroom.ui.component.PosterSkeleton()
                    }
                }

                err != null -> {
                    // 取一次再判断：err 是 Compose state，两次读之间可能被置空
                    val e = err.orEmpty()
                    // 「源都没响应」和「没这部片」不是一句话，别混着说
                    Center(
                        when {
                            e.contains("没响应") || e.contains("SourcesUnavailable") ->
                                "片源都没响应，检查下网络再试"
                            e.contains("NAS", true) -> "连不上 NAS"
                            else -> e
                        },
                    )
                }

                searched && results.isEmpty() -> Center("没找到「$committed」，换个片名试试")

                !searched -> Center("输入片名后按确认，会同时问所有片源")

                else -> LazyVerticalGrid(
                    columns = GridCells.Fixed(columns),
                    horizontalArrangement = Arrangement.spacedBy(gap),
                    verticalArrangement = Arrangement.spacedBy(ProomSpace.lg),
                    contentPadding = PaddingValues(start = TvLayout.insetX, end = TvLayout.insetX, bottom = ProomSpace.xxl),
                ) {
                    itemsIndexed(results) { i, vod ->
                        PosterCard(
                            title = vod.name,
                            meta = metaLine(vod.year, vod.type),
                            poster = vod.pic,
                            // 点词搜索后把焦点交给第一张，避免焦点掉回输入框弹软键盘
                            modifier = if (i == 0) Modifier.focusRequester(firstResult) else Modifier,
                            onClick = { onOpen(vod) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchField(value: String, onValueChange: (String) -> Unit, onSearch: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(50)

    // 聚焦时不再画一圈 2dp 亮金边 —— 那是「输入框报错」的视觉语言。
    // 改成「底色抬一档 + 1dp 半透明描边」，安静但看得见。
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (focused) ProomColor.surface2 else ProomColor.surface)
            .border(
                1.dp,
                if (focused) ProomColor.accent.copy(alpha = 0.55f) else ProomColor.line,
                shape,
            )
            .onFocusChanged { focused = it.isFocused }
            .heightIn(min = 56.dp)
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 左边一个小放大镜：形状上是「搜索」，比占位文字更早被认出来
        androidx.compose.foundation.Canvas(Modifier.size(20.dp)) {
            val w = 1.6.dp.toPx()
            val c = androidx.compose.ui.geometry.Offset(size.width * 0.42f, size.height * 0.42f)
            val r = size.minDimension * 0.31f
            val tint = if (focused) ProomColor.accent else ProomColor.muted
            drawCircle(tint, r, c, style = androidx.compose.ui.graphics.drawscope.Stroke(width = w))
            drawLine(
                tint,
                start = androidx.compose.ui.geometry.Offset(c.x + r * 0.7f, c.y + r * 0.7f),
                end = androidx.compose.ui.geometry.Offset(size.width * 0.93f, size.height * 0.93f),
                strokeWidth = w,
                cap = androidx.compose.ui.graphics.StrokeCap.Round,
            )
        }
        Spacer(Modifier.width(10.dp))

        // 选中文本时不画那个黄色「水滴手柄」：搜索框里几乎用不到，
        // 但它会在聚焦瞬间跳出来，很脏。只保留半透明的高亮底色。
        androidx.compose.runtime.CompositionLocalProvider(
            androidx.compose.foundation.text.selection.LocalTextSelectionColors provides
                androidx.compose.foundation.text.selection.TextSelectionColors(
                    handleColor = androidx.compose.ui.graphics.Color.Transparent,
                    backgroundColor = ProomColor.accent.copy(alpha = 0.25f),
                )
        ) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = ProomType.body.copy(color = ProomColor.text),
                cursorBrush = SolidColor(ProomColor.accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSearch() }),
                modifier = Modifier.weight(1f).padding(vertical = 13.dp),
                decorationBox = { inner ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (value.isEmpty()) {
                            Text("搜片名、演员、导演…", style = ProomType.body, color = ProomColor.muted)
                        }
                        inner()
                    }
                },
            )
        }
    }
}

private val HOT_WORDS = listOf("繁花", "沙丘", "庆余年", "奥本海默", "狂飙")

/** 历史词：点正文搜，点 × 删这一条 —— 不用长按，也不藏进二级菜单 */
@Composable
private fun HistoryChip(label: String, onPick: () -> Unit, onRemove: () -> Unit) {
    val shape = RoundedCornerShape(ProomSpace.md)
    var pickFocused by remember { mutableStateOf(false) }
    var removeFocused by remember { mutableStateOf(false) }
    // 正文和 × 是两个独立焦点目标，各自都要看得见 —— 原来这整颗 chip
    // 在遥控器上完全静默，22dp 的 × 更是既看不见也点不准
    Row(
        Modifier
            .heightIn(min = 44.dp)
            .clip(shape)
            .background(if (pickFocused || removeFocused) ProomColor.surface2 else ProomColor.surface)
            .then(if (pickFocused) Modifier.border(2.dp, Color.White, shape) else Modifier)
            .padding(start = 15.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = ProomType.meta,
            color = ProomColor.text,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier
                .onFocusChanged { pickFocused = it.isFocused }
                .clickable(onClick = onPick)
                .padding(vertical = 10.dp),
        )
        Box(
            Modifier
                // 和历史词同一行的删除钮：40dp 的圆圈在电视上得瞄，48dp 才够
                .size(TvLayout.minTouch)
                .clip(CircleShape)
                .then(if (removeFocused) Modifier.background(ProomColor.accentSoft) else Modifier)
                .then(if (removeFocused) Modifier.border(2.dp, Color.White, CircleShape) else Modifier)
                .onFocusChanged { removeFocused = it.isFocused }
                .clickable(onClick = onRemove),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "×",
                color = if (removeFocused) ProomColor.accent else ProomColor.muted,
                fontSize = ProomSp(18.sp),
            )
        }
    }
}

/** 历史词行尾的「清空」：形状与 hot chip 同高，但只用文字色区分主次 */
@Composable
private fun ClearChip(onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(ProomSpace.md)
    Box(
        Modifier
            .heightIn(min = 44.dp)
            .clip(shape)
            .then(if (focused) Modifier.background(ProomColor.surface2) else Modifier)
            .then(if (focused) Modifier.border(2.dp, Color.White, shape) else Modifier)
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "清空",
            style = ProomType.meta,
            color = if (focused) ProomColor.accent else ProomColor.muted,
            maxLines = 1,
            softWrap = false,
        )
    }
}

@Composable
private fun HotChip(label: String, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(ProomSpace.md)
    Box(
        Modifier
            .clip(shape)
            .background(if (focused) ProomColor.surface2 else ProomColor.surface)
            // 金边在这一页会和「已选中的分类」撞语义，焦点统一走白色
            .then(if (focused) Modifier.border(2.dp, Color.White, shape) else Modifier)
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick)
            // 13/8 只有 34dp 高，遥控器上下换行时很容易越过去
            .heightIn(min = 44.dp)
            .padding(horizontal = 18.dp, vertical = 9.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = ProomType.meta, color = ProomColor.text, maxLines = 1, softWrap = false)
    }
}

@Composable
private fun Center(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, style = ProomType.meta, color = ProomColor.muted)
    }
}
