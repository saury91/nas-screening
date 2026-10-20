package com.projectionroom.ui.screen

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.projectionroom.ui.theme.ProomColor
import com.projectionroom.ui.theme.ProomShape
import com.projectionroom.ui.theme.ProomSpace
import com.projectionroom.ui.theme.ProomType

/** 配对页的状态 */
sealed interface PairState {
    data object Waiting : PairState
    /** 已经收下并写入本地 */
    data class Done(val message: String) : PairState
    data class Failed(val message: String) : PairState
    /** 服务起不来（端口被占、没有局域网地址） */
    data class Unavailable(val message: String) : PairState
}

/**
 * 电视端的「用手机导入配置」。
 *
 * 电视上填地址是酷刑：遥控器逐字敲 IP，还要区分 `:` 和 `.`。
 * 所以让电视主动开一个临时 HTTP 服务、把地址和配对码编成二维码，
 * 手机扫一下就把整套配置推过来。
 *
 * 二维码里装的是 `proom://pair?host=…&port=…&token=…`，
 * token 就是屏幕上那个四位码 —— 扫码失败时还能手动输入。
 */
@Composable
fun PairScreen(
    qr: ImageBitmap?,
    code: String,
    address: String,
    state: PairState,
    topInset: Dp = 0.dp,
    onBack: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .background(ProomColor.bg)
            .padding(top = topInset),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(start = ProomSpace.lg, end = ProomSpace.xxl, top = ProomSpace.md),
        ) {
            // 电视端返回统一成面包屑提示：不进焦点环，也不抢初始焦点
            // （原来这里是一颗「‹ 返回」胶囊，进页面按 OK 会直接退出去）
            com.projectionroom.ui.component.TvBackHint(onBack = onBack)
            Spacer(Modifier.height(2.dp))
            Text("用手机导入配置", style = ProomType.headline, color = ProomColor.text)
        }

        Row(
            Modifier.fillMaxWidth().weight(1f).padding(horizontal = 48.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            // 二维码必须白底：电视是暗色界面，反色二维码很多相机认不出来
            Box(
                Modifier
                    .size(300.dp)
                    .clip(RoundedCornerShape(ProomShape.md))
                    .background(Color.White)
                    .padding(12.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (qr != null) {
                    Image(qr, contentDescription = "配对二维码", modifier = Modifier.fillMaxSize())
                } else {
                    Text("二维码生成失败", color = Color.Black, fontSize = 14.sp)
                }
            }

            Spacer(Modifier.width(48.dp))

            Column(Modifier.width(360.dp)) {
                Text(
                    "用手机上的「放映室」扫这个码",
                    style = ProomType.title,
                    color = ProomColor.text,
                )
                Text(
                    "手机和电视要在同一个局域网。手机路径：我的 → 设置 → 扫码从电视导入。",
                    style = ProomType.body,
                    color = ProomColor.muted,
                    modifier = Modifier.padding(top = 8.dp),
                )
                Spacer(Modifier.height(ProomSpace.lg))
                Text("配对码", style = ProomType.label, color = ProomColor.muted)
                Text(
                    code.chunked(2).joinToString(" "),
                    color = ProomColor.accent,
                    fontSize = 34.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 2.sp,
                )
                Text(
                    if (address.isBlank()) "没拿到局域网地址" else address,
                    style = ProomType.label,
                    color = ProomColor.muted,
                    modifier = Modifier.padding(top = 2.dp),
                )
                Spacer(Modifier.height(ProomSpace.lg))

                val (line, color) = when (state) {
                    PairState.Waiting -> "等待手机连接…" to ProomColor.muted
                    is PairState.Done -> state.message to ProomColor.ok
                    is PairState.Failed -> state.message to ProomColor.danger
                    is PairState.Unavailable -> state.message to ProomColor.danger
                }
                Text(line, style = ProomType.body, color = color)
                if (state is PairState.Waiting) {
                    Text(
                        "配对码只在这个页面有效，退出即作废",
                        style = ProomType.label,
                        color = ProomColor.muted,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
        }
    }
}

/** 电视端的返回胶囊（和 TvMineScreen 里的观感一致） */
@Composable
private fun BackPill2(fr: FocusRequester, onBack: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Box(
        Modifier
            .clip(CircleShape)
            .background(if (focused) ProomColor.accentSoft else ProomColor.surface)
            .focusRequester(fr)
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .clickable(onClick = onBack)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Text(
            "‹ 返回",
            style = ProomType.cardTitle,
            color = if (focused) ProomColor.accent else ProomColor.text,
            textAlign = TextAlign.Center,
        )
    }
}
