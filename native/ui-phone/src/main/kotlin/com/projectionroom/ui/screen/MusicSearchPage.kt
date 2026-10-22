package com.projectionroom.ui.screen

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.projectionroom.shared.catalog.NasApi
import com.projectionroom.ui.component.RemoteImage
import com.projectionroom.ui.component.TabIcons
import com.projectionroom.ui.theme.ProomColor
import com.projectionroom.ui.theme.ProomShape
import com.projectionroom.ui.theme.ProomSpace
import com.projectionroom.ui.theme.ProomType

/**
 * 音乐搜索（手机）。
 *
 * 音乐页右上角那颗放大镜进来 —— 和影视页的搜索保持同一个入口位置，
 * 但搜的是**歌**：影视搜索走的是片源聚合，音乐走的是另一套聚合接口
 * （[searchTracks] 由壳注入，电视端也是这么接的）。
 *
 * 交互沿用手机搜索页：左上返回 + 圆角输入框 + 结果列表；点任意一条就把
 * 整份结果当队列，从这一首开始放（歌单页也是同样语义）。
 */
@Composable
fun MusicSearchPage(
    /** 搜歌：返回空列表表示没搜到，抛异常走错误态 */
    searchTracks: suspend (String) -> List<NasApi.Track>,
    /** 点结果：整份结果 + 起始下标交给播放器 */
    onPlay: (List<NasApi.Track>, Int) -> Unit,
    onClose: () -> Unit,
    topInset: Dp = 0.dp,
) {
    var q by remember { mutableStateOf("") }
    var hits by remember { mutableStateOf<List<NasApi.Track>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf<String?>(null) }
    var searched by remember { mutableStateOf(false) }

    // 系统返回键先退出搜索，而不是把 App 退到桌面
    BackHandler { onClose() }

    // 防抖 400ms：逐字打请求会把结果刷成幻灯片（影视搜索也是这个值）
    LaunchedEffect(q) {
        val kw = q.trim()
        if (kw.isEmpty()) {
            hits = emptyList()
            searched = false
            err = null
            loading = false
            return@LaunchedEffect
        }
        kotlinx.coroutines.delay(400)
        loading = true
        err = null
        runCatching { searchTracks(kw) }
            .onSuccess { hits = it }
            .onFailure { err = it.message ?: "网络错误" }
        loading = false
        searched = true
    }

    val kb = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val focus = remember { FocusRequester() }
    // 进页面直接弹键盘：搜歌就是要打字，再让人多点一下纯属多余
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    Column(
        Modifier
            .fillMaxSize()
            .background(ProomColor.bg)
            .padding(top = topInset),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(start = 4.dp, end = 20.dp).height(56.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(48.dp).clip(CircleShape).clickable(onClick = onClose),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = TabIcons.Back,
                    contentDescription = "返回",
                    tint = ProomColor.text,
                    modifier = Modifier.size(24.dp),
                )
            }
            Spacer(Modifier.width(4.dp))
            MusicQueryField(
                value = q,
                onValueChange = { q = it },
                onSearch = { kb?.hide() },
                focus = focus,
                modifier = Modifier.weight(1f),
            )
        }

        Spacer(Modifier.height(ProomSpace.sm))

        when {
            q.isBlank() -> CenterHint("搜歌曲、歌手", "输入关键词，点结果就从这一首往下放")

            err != null -> CenterHint("搜索失败", err.orEmpty())

            loading && hits.isEmpty() -> Box(
                Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(
                    color = ProomColor.accent,
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(26.dp),
                )
            }

            searched && hits.isEmpty() -> CenterHint(
                "没有找到「${q.trim()}」",
                "换个关键词试试，或者回去翻分类歌单",
            )

            else -> LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = 20.dp, end = 20.dp, top = 4.dp, bottom = ProomSpace.xxl,
                ),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                item {
                    Text(
                        "共 ${hits.size} 首",
                        style = ProomType.label,
                        color = ProomColor.muted,
                        modifier = Modifier.padding(bottom = 6.dp),
                    )
                }
                itemsIndexed(hits) { i, t ->
                    TrackRow(t) { onPlay(hits, i) }
                }
            }
        }
    }
}

/* ───────────────────────── 输入框 ───────────────────────── */

@Composable
private fun MusicQueryField(
    value: String,
    onValueChange: (String) -> Unit,
    onSearch: () -> Unit,
    focus: FocusRequester,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(50)

    Row(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (focused) ProomColor.surface2 else ProomColor.surface)
            .border(
                1.dp,
                if (focused) ProomColor.accent.copy(alpha = 0.55f) else ProomColor.line,
                shape,
            )
            .onFocusChanged { focused = it.isFocused }
            .padding(horizontal = 15.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 左边一个小放大镜：形状上比占位文字更早被认出来
        androidx.compose.foundation.Canvas(Modifier.size(15.dp)) {
            val w = 1.6.dp.toPx()
            val c = androidx.compose.ui.geometry.Offset(size.width * 0.42f, size.height * 0.42f)
            val r = size.minDimension * 0.31f
            val tint = if (focused) ProomColor.accent else ProomColor.muted
            drawCircle(
                tint, r, c,
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = w),
            )
            drawLine(
                tint,
                start = androidx.compose.ui.geometry.Offset(c.x + r * 0.7f, c.y + r * 0.7f),
                end = androidx.compose.ui.geometry.Offset(size.width * 0.93f, size.height * 0.93f),
                strokeWidth = w,
                cap = androidx.compose.ui.graphics.StrokeCap.Round,
            )
        }
        Spacer(Modifier.width(10.dp))
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = ProomType.body.copy(color = ProomColor.text),
            cursorBrush = SolidColor(ProomColor.accent),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onSearch() }),
            modifier = Modifier.weight(1f).focusRequester(focus).padding(vertical = 13.dp),
            decorationBox = { inner ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty()) {
                        Text("搜歌曲、歌手…", style = ProomType.body, color = ProomColor.muted)
                    }
                    inner()
                }
            },
        )
    }
}

/* ───────────────────────── 结果行 ───────────────────────── */

/**
 * 一行一首：封面 + 歌名 + 歌手。整行可点，右侧不再挂播放按钮 ——
 * 结果页只有一个动作（从这里开始放），多一颗按钮反而让人犹豫点哪个。
 */
@Composable
private fun TrackRow(t: NasApi.Track, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(ProomShape.sm))
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(46.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(ProomColor.card),
        ) {
            if (t.cover.isNotBlank()) {
                RemoteImage(
                    model = t.cover,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = TabIcons.Music,
                        contentDescription = null,
                        tint = ProomColor.lineStrong,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                t.title.ifBlank { "未知歌曲" },
                color = ProomColor.text,
                fontSize = 15.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                t.artist.ifBlank { "未知歌手" },
                style = ProomType.meta,
                color = ProomColor.muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/* ───────────────────────── 提示态 ───────────────────────── */

@Composable
private fun CenterHint(title: String, desc: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = TabIcons.Search,
                contentDescription = null,
                tint = ProomColor.lineStrong,
                modifier = Modifier.size(30.dp),
            )
            Spacer(Modifier.height(ProomSpace.md))
            Text(title, color = ProomColor.text, fontSize = 15.sp)
            Spacer(Modifier.height(6.dp))
            Text(desc, style = ProomType.meta, color = ProomColor.muted)
        }
    }
}
