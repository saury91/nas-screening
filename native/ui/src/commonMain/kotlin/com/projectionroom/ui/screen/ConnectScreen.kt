package com.projectionroom.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.projectionroom.ui.theme.ProomColor
import com.projectionroom.ui.theme.ProomShape
import com.projectionroom.ui.theme.ProomSpace
import com.projectionroom.ui.theme.ProomType

/**
 * 「连上 NAS」页。
 *
 * 两种情况共用一张脸：
 *  - 首次启动：正常路径是自动发现（局域网 UDP 广播），找不到才露出输入框；
 *  - 已经连上、想换一台：从「我的」或电视顶栏进来，`current` 非空，显示当前地址并可改。
 *
 * 电视上打地址很痛苦，所以输入框永远是兜底，不是主路径。
 */
@Composable
fun ConnectScreen(
    isTv: Boolean,
    searching: Boolean,
    hint: String?,
    onRetry: () -> Unit,
    onConnect: (String) -> Unit,
    /** 已连接的地址；非空表示「切换」而不是「首次连接」 */
    current: String? = null,
    /** 版本号，显示在最下面；不传就不显示 */
    version: String? = null,
    /** 传了才显示返回箭头 */
    onBack: (() -> Unit)? = null,
    /** 传了才显示「先跳过」：没有 NAS 也能进 App，地址以后在设置里填 */
    onSkip: (() -> Unit)? = null,
    topInset: androidx.compose.ui.unit.Dp = 0.dp,
) {
    // 预填当前地址（去掉协议头，用户看到的是 ip:port）
    var text by remember(current) {
        mutableStateOf(current?.removePrefix("http://")?.removePrefix("https://").orEmpty())
    }

    Box(
        Modifier.fillMaxSize().background(ProomColor.bg).padding(top = topInset),
        contentAlignment = Alignment.Center,
    ) {
        if (onBack != null) {
            // 电视端：面包屑式提示，不进焦点环（遥控器 BACK 键才是主路径）
            if (isTv) {
                com.projectionroom.ui.component.TvBackHint(
                    onBack = onBack,
                    modifier = Modifier.align(Alignment.TopStart)
                        .padding(start = ProomSpace.lg, top = ProomSpace.sm),
                )
            } else {
                BackChip(Modifier.align(Alignment.TopStart), onBack)
            }
        }

        Column(
            Modifier.widthIn(max = if (isTv) 560.dp else 340.dp)
                .padding(horizontal = ProomSpace.xl, vertical = if (isTv) ProomSpace.md else ProomSpace.xl),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("放映室", style = ProomType.eyebrow, color = ProomColor.accent)
            Spacer(Modifier.height(ProomSpace.sm))
            Text(
                if (current != null) "换一台 NAS" else "连上你的 NAS",
                style = ProomType.headline,
                color = ProomColor.text,
            )
            if (current != null) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "当前 · ${current.removePrefix("http://").removePrefix("https://")}",
                    style = ProomType.meta,
                    color = ProomColor.muted,
                )
            }
            Spacer(Modifier.height(ProomSpace.md))

            if (searching) {
                CircularProgressIndicator(color = ProomColor.accent, strokeWidth = 2.dp)
                Spacer(Modifier.height(ProomSpace.md))
                Text("正在局域网里找放映室…", style = ProomType.body, color = ProomColor.dim)
            } else {
                Text(
                    hint ?: if (current != null) "改完立即生效；也可以让它在局域网里重新找一台"
                    else "没找到放映室",
                    style = ProomType.body,
                    color = ProomColor.muted,
                )
                Spacer(Modifier.height(if (isTv) ProomSpace.sm else ProomSpace.lg))

                // 手动地址：只在自动发现失败时才出现。
                // 电视上不用系统的软键盘（遥控器在软键盘上挪一个字母要按十几次），
                // 换成只读展示 + 下面的数字键盘。
                if (isTv) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .background(ProomColor.surface, RoundedCornerShape(ProomShape.sm))
                            .border(1.dp, ProomColor.line, RoundedCornerShape(ProomShape.sm))
                            .padding(horizontal = ProomSpace.md, vertical = 12.dp),
                    ) {
                        Text(
                            text.ifEmpty { "192.0.2.10:8899" },
                            style = ProomType.body,
                            color = if (text.isEmpty()) ProomColor.muted else ProomColor.text,
                        )
                    }
                } else {
                    // 和搜索框一致：不要那个黄色「水滴」选择手柄
                    androidx.compose.runtime.CompositionLocalProvider(
                        androidx.compose.foundation.text.selection.LocalTextSelectionColors provides
                            androidx.compose.foundation.text.selection.TextSelectionColors(
                                handleColor = Color.Transparent,
                                backgroundColor = ProomColor.accent.copy(alpha = 0.25f),
                            )
                    ) {
                        BasicTextField(
                            value = text,
                            onValueChange = { text = it },
                            singleLine = true,
                            textStyle = ProomType.body.copy(color = ProomColor.text),
                            cursorBrush = SolidColor(ProomColor.accent),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = {
                                if (text.isNotBlank()) onConnect(text)
                            }),
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(ProomColor.surface, RoundedCornerShape(ProomShape.sm))
                                .border(1.dp, ProomColor.line, RoundedCornerShape(ProomShape.sm))
                                .padding(horizontal = ProomSpace.md, vertical = 12.dp),
                            decorationBox = { inner ->
                                Box {
                                    if (text.isEmpty()) {
                                        Text("192.0.2.10:8899", style = ProomType.body, color = ProomColor.muted)
                                    }
                                    inner()
                                }
                            },
                        )
                    }
                }

                if (isTv) {
                    Spacer(Modifier.height(ProomSpace.md))
                    NumberPad(
                        onKey = { ch ->
                            text = when (ch) {
                                "\b" -> text.dropLast(1)
                                else -> text + ch
                            }
                        },
                    )
                }

                Spacer(Modifier.height(ProomSpace.md))

                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(ProomSpace.md),
                ) {
                    ConnectButton("再找一次", primary = false, Modifier.weight(1f), onRetry)
                    ConnectButton("连接", primary = true, Modifier.weight(1f)) {
                        if (text.isNotBlank()) onConnect(text)
                    }
                }

                // 没有 NAS 也能用：片源清单存在本地，直接进 App。
                // 地址入口留在「我的 → 设置」，不需要每次启动都被拦在这里。
                if (onSkip != null) {
                    Spacer(Modifier.height(ProomSpace.md))
                    Text(
                        "先跳过，稍后在「设置」里填地址 ›",
                        style = ProomType.meta,
                        color = ProomColor.muted,
                        modifier = Modifier
                            .clip(RoundedCornerShape(ProomShape.sm))
                            .clickable(onClick = onSkip)
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }
            }

            if (!version.isNullOrBlank()) {
                Spacer(Modifier.height(if (isTv) ProomSpace.md else ProomSpace.xl))
                Text(
                    "v$version · 片源与设置都在 NAS 上",
                    style = ProomType.label,
                    color = ProomColor.muted,
                )
            }
        }
    }
}

@Composable
private fun ConnectButton(
    text: String,
    primary: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(ProomShape.sm)
    Box(
        modifier
            .background(
                when {
                    primary -> ProomColor.accent
                    focused -> ProomColor.surface2
                    else -> ProomColor.surface
                },
                shape,
            )
            .border(
                if (focused) 2.dp else 1.dp,
                when {
                    focused && primary -> Color.White
                    focused -> ProomColor.accent
                    primary -> ProomColor.accent
                    else -> ProomColor.line
                },
                shape,
            )
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            style = ProomType.cardTitle,
            color = if (primary) Color(0xFF1B1206) else ProomColor.text,
        )
    }
}

/** 手机端的「‹ 返回」胶囊；电视端换成 TvBackHint（不占焦点位） */
@Composable
private fun BackChip(modifier: Modifier = Modifier, onBack: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(50)
    Box(
        modifier
            .padding(start = ProomSpace.md, top = ProomSpace.sm)
            .clip(shape)
            .background(if (focused) ProomColor.surface2 else Color.Transparent)
            .then(if (focused) Modifier.border(2.dp, ProomColor.accent, shape) else Modifier)
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onBack)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Text("‹ 返回", style = ProomType.body, color = if (focused) ProomColor.text else ProomColor.dim)
    }
}

/**
 * 电视用的数字键盘。
 *
 * 只放地址里会出现的字符：数字、点、退格。
 * 端口不用输冒号 —— `NasStore.normalize()` 会补默认的 8899。
 */
@Composable
private fun NumberPad(onKey: (String) -> Unit) {
    val rows = listOf(
        listOf("1", "2", "3"),
        listOf("4", "5", "6"),
        listOf("7", "8", "9"),
        listOf(".", "0", "\b"),
    )
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        rows.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                row.forEach { key ->
                    PadKey(if (key == "\b") "⌫" else key) { onKey(key) }
                }
            }
        }
    }
}

@Composable
private fun PadKey(label: String, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(ProomShape.sm)
    Box(
        Modifier
            .width(74.dp)
            .height(36.dp)
            .clip(shape)
            .background(if (focused) ProomColor.accent else ProomColor.surface)
            .then(if (focused) Modifier.border(2.dp, Color.White, shape) else Modifier)
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = ProomType.cardTitle,
            color = if (focused) Color(0xFF1B1206) else ProomColor.text,
        )
    }
}
