package com.projectionroom.ui.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.projectionroom.ui.theme.ProomColor
import com.projectionroom.ui.theme.ProomType

/**
 * 顶栏搜索态：把整条顶栏让给一个输入框。
 *
 * 直播和音乐原先各写了一份（音乐根本没有），而顶栏那颗搜索图标现在按当前
 * tab 分流 —— 一处写完，两个页面共用同一副样子、同一套焦点行为。
 *
 * 进入即抢焦点并弹软键盘：电视上软键盘基本用不到（遥控器输入），
 * 但手机上这条顶栏同样会用，不弹的话用户还得先点一下输入框。
 */
@Composable
fun TvSearchField(
    query: String,
    onQuery: (String) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "搜索",
    /**
     * 按下方向键时想把焦点交给下面列表，就在这里接住并返回 true。
     *
     * 单行输入框自己吃掉方向键（挪光标），遥控器上「打完字想下楼选一条」
     * 会卡在输入框里出不来。
     */
    onDown: (() -> Boolean)? = null,
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
            modifier = Modifier
                .weight(1f)
                .focusRequester(fr)
                .onPreviewKeyEvent { e ->
                    onDown != null && e.type == KeyEventType.KeyDown &&
                        e.key == Key.DirectionDown && onDown.invoke()
                },
            decorationBox = { inner ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (query.isEmpty()) {
                        Text(placeholder, style = ProomType.body, color = ProomColor.muted)
                    }
                    inner()
                }
            },
        )
        Box(
            Modifier.size(40.dp).clip(CircleShape).clickable(onClick = onClose),
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
