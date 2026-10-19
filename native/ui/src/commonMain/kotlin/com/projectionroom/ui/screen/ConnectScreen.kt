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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.projectionroom.ui.theme.ProomColor
import com.projectionroom.ui.theme.ProomShape
import com.projectionroom.ui.theme.ProomSpace
import com.projectionroom.ui.theme.ProomType

/**
 * 首次启动的「连上 NAS」页。
 *
 * 正常路径是自动发现（局域网 UDP 广播），用户什么都不用做；
 * 找不到才露出输入框——电视上打地址很痛苦，所以它必须是兜底而不是主路径。
 */
@Composable
fun ConnectScreen(
    isTv: Boolean,
    searching: Boolean,
    hint: String?,
    onRetry: () -> Unit,
    onConnect: (String) -> Unit,
) {
    var text by remember { mutableStateOf("") }

    Box(
        Modifier.fillMaxSize().background(ProomColor.bg),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.widthIn(max = if (isTv) 560.dp else 340.dp).padding(ProomSpace.xl),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("放映室", style = ProomType.eyebrow, color = ProomColor.accent)
            Spacer(Modifier.height(ProomSpace.sm))
            Text("连上你的 NAS", style = ProomType.headline, color = ProomColor.text)
            Spacer(Modifier.height(ProomSpace.md))

            if (searching) {
                CircularProgressIndicator(color = ProomColor.accent, strokeWidth = 2.dp)
                Spacer(Modifier.height(ProomSpace.md))
                Text("正在局域网里找放映室…", style = ProomType.body, color = ProomColor.dim)
            } else {
                Text(hint ?: "没找到放映室", style = ProomType.body, color = ProomColor.muted)
                Spacer(Modifier.height(ProomSpace.lg))

                // 手动地址：只在自动发现失败时才出现
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
                                Text("192.168.1.10:8899", style = ProomType.body, color = ProomColor.muted)
                            }
                            inner()
                        }
                    },
                )
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
    Box(
        modifier
            .background(
                if (primary) ProomColor.accent else ProomColor.surface,
                RoundedCornerShape(ProomShape.sm),
            )
            .border(
                1.dp,
                if (primary) ProomColor.accent else ProomColor.line,
                RoundedCornerShape(ProomShape.sm),
            )
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
