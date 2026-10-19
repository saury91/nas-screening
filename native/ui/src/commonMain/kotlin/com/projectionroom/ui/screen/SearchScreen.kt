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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.projectionroom.shared.catalog.NasApi
import com.projectionroom.shared.model.Vod
import com.projectionroom.ui.component.PosterCard
import com.projectionroom.ui.theme.ProomColor
import com.projectionroom.ui.theme.ProomSpace
import com.projectionroom.ui.theme.ProomType
import kotlinx.coroutines.delay

/**
 * 搜索页。
 *
 * 输入防抖 400ms：采集站一次查询要打十几个源，每敲一个字就发一次会把 NAS 打满。
 * 电视上用遥控器输入很痛苦，所以这里同时支持「按确认立刻搜」（不等防抖）。
 */
@Composable
fun SearchScreen(
    api: NasApi,
    onOpen: (Vod) -> Unit,
    initialQuery: String = "",
    onBack: () -> Unit = {},
) {
    var q by remember { mutableStateOf(initialQuery) }
    var committed by remember { mutableStateOf(initialQuery) }
    var loading by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf<List<Vod>>(emptyList()) }
    var err by remember { mutableStateOf<String?>(null) }
    var searched by remember { mutableStateOf(false) }

    LaunchedEffect(committed) {
        val kw = committed.trim()
        if (kw.isEmpty()) {
            results = emptyList()
            searched = false
            return@LaunchedEffect
        }
        delay(400)                      // 停下再查，别打满 NAS
        loading = true
        err = null
        runCatching { api.search(kw) }
            .onSuccess { results = it }
            .onFailure { err = it.message }
        loading = false
        searched = true
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(ProomColor.bg)
            .padding(horizontal = 20.dp)
            .padding(top = ProomSpace.sm)
    ) {
        // 从任何页面点搜索图标进来，都能一眼退回（不只靠系统返回键）
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onBack),
                contentAlignment = Alignment.Center,
            ) {
                Text("‹", color = ProomColor.text, fontSize = 26.sp)
            }
            Spacer(Modifier.width(8.dp))
            Box(Modifier.weight(1f)) {
                SearchField(
                    value = q,
                    onValueChange = { q = it; committed = it },
                    onSearch = { committed = q },
                )
            }
        }

        // 电视上遥控器打字太痛苦，给几个热门词，点一下直接搜
        if (!searched && q.isBlank()) {
            // 用 LazyRow：5 个词在窄屏上放不下，横滑而不是被裁掉
            LazyRow(
                Modifier.fillMaxWidth().padding(top = ProomSpace.md),
                horizontalArrangement = Arrangement.spacedBy(ProomSpace.sm),
                contentPadding = PaddingValues(end = ProomSpace.sm),
            ) {
                items(HOT_WORDS) { w ->
                    HotChip(w) { q = w; committed = w }
                }
            }
        }

        Box(Modifier.fillMaxWidth().weight(1f).padding(top = ProomSpace.lg)) {
            when {
                loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = ProomColor.accent)
                }

                err != null -> Center(if (err!!.contains("NAS", true)) "连不上 NAS" else err!!)

                searched && results.isEmpty() -> Center("没找到「$committed」，换个片名试试")

                !searched -> Center("输入片名后按确认，会同时问所有片源")

                else -> LazyVerticalGrid(
                    // 与片库页保持同一套网格：3 列、10dp 横距、18dp 纵距
                    columns = GridCells.Fixed(3),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                    contentPadding = PaddingValues(bottom = 28.dp),
                ) {
                    items(results) { vod ->
                        PosterCard(
                            title = vod.name,
                            meta = listOf(vod.year, vod.type).filter { it.isNotBlank() }.joinToString(" · "),
                            poster = vod.pic,
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
    val shape = RoundedCornerShape(ProomSpace.md)
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = ProomType.body.copy(color = ProomColor.text),
        cursorBrush = SolidColor(ProomColor.accent),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { onSearch() }),
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(ProomColor.surface)
            .then(if (focused) Modifier.border(2.dp, ProomColor.accent, shape) else Modifier)
            .onFocusChanged { focused = it.isFocused }
            .padding(horizontal = 16.dp, vertical = 14.dp),
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

private val HOT_WORDS = listOf("繁花", "沙丘", "庆余年", "奥本海默", "狂飙")

@Composable
private fun HotChip(label: String, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(ProomSpace.md)
    Box(
        Modifier
            .clip(shape)
            .background(if (focused) ProomColor.surface2 else ProomColor.surface)
            .then(if (focused) Modifier.border(2.dp, ProomColor.accent, shape) else Modifier)
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Text(label, style = ProomType.body, color = ProomColor.text)
    }
}

@Composable
private fun Center(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, style = ProomType.meta, color = ProomColor.muted)
    }
}
