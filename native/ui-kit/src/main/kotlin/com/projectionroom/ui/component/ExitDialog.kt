package com.projectionroom.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.projectionroom.ui.theme.LocalProomTv
import com.projectionroom.ui.theme.ProomColor
import com.projectionroom.ui.theme.ProomShape
import com.projectionroom.ui.theme.ProomSpace
import com.projectionroom.ui.theme.ProomType
import kotlinx.coroutines.delay

/**
 * 「退出放映室？」确认框。
 *
 * 为什么要有它：电视遥控器的返回键就在拇指底下，首页再按一下就直接把 App 关了 ——
 * 连投屏接收和后台音乐一起带走，重开要等 NAS 发现 + 首屏加载。
 * 退到桌面（按 Home）才是那个「先放一边」的动作，两者的代价差太多，必须分开。
 *
 * 首焦点给「取消」：这是页面上唯一一个按错了不可逆的按钮，
 * 遥控器方向键连点两下不能把 App 点没了。
 */
@Composable
fun TvExitDialog(
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
) {
    val tv = LocalProomTv.current
    val shape = RoundedCornerShape(ProomShape.lg)
    val cancelFocus = remember { FocusRequester() }
    // 弹窗刚组合时焦点树还没稳，requestFocus 会「成功但不落地」—— 多发几帧
    LaunchedEffect(Unit) {
        repeat(6) {
            delay(80)
            runCatching { cancelFocus.requestFocus() }
        }
    }
    Column(
        Modifier
            .width(if (tv) 480.dp else 320.dp)
            .clip(shape)
            .background(ProomColor.bgSoft)
            .border(1.dp, ProomColor.line, shape)
            .padding(horizontal = if (tv) 32.dp else 22.dp, vertical = if (tv) 28.dp else 22.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "退出放映室？",
            style = ProomType.title,
            color = ProomColor.text,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(if (tv) ProomSpace.lg else ProomSpace.md))
        Row(horizontalArrangement = Arrangement.spacedBy(ProomSpace.sm)) {
            DialogButton(
                text = "取消",
                onClick = onCancel,
                // 「取消」是默认落点，退出要专门往右按一下才够得着
                modifier = Modifier.focusRequester(cancelFocus),
            )
            DialogButton(
                text = "退出应用",
                onClick = onConfirm,
                danger = true,
            )
        }
    }
}

/**
 * 确认框里的按钮：两个都靠白环表达焦点（和全站一致），
 * 危险动作用红字而不是红底 —— 红底在深色卡上会跳成整屏最亮的一块。
 */
@Composable
private fun DialogButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    danger: Boolean = false,
) {
    val tv = LocalProomTv.current
    Box(
        modifier
            .width(if (tv) 168.dp else 132.dp)
            .height(if (tv) 52.dp else 46.dp)
            .tvFocusRing(
                RoundedCornerShape(ProomShape.md),
                ringWidth = 2.dp,
                zoom = 1f,
                inset = true,
            )
            .clip(RoundedCornerShape(ProomShape.md))
            .background(
                if (danger) ProomColor.danger.copy(alpha = 0.14f)
                else Color.White.copy(alpha = 0.07f)
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            color = if (danger) ProomColor.danger else ProomColor.text,
            fontSize = ProomType.cardTitle.fontSize,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
        )
    }
}
