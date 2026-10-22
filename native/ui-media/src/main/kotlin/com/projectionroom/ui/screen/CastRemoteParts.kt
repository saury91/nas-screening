package com.projectionroom.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.projectionroom.shared.model.Vod
import com.projectionroom.ui.component.PlayerIcons
import com.projectionroom.ui.component.RemoteImage
import com.projectionroom.ui.theme.ProomColor
import com.projectionroom.ui.theme.ProomShape
import com.projectionroom.ui.theme.ProomSp
import com.projectionroom.ui.theme.ProomType

/**
 * 遥控面板的展示件。
 *
 * 视觉沿用 Web 版那套：纯黑底、单一琥珀金、靠明度分层而不是描边堆叠。
 * 刻意不用 Material 的 Slider/Button —— 它们的拇指、水波纹和高光放在
 * 这块几乎全黑的界面上会很「系统控件」，把仪器感破坏掉。
 */

@Composable
internal fun RemoteTopRow(
    deviceName: String,
    fullFeature: Boolean,
    online: Boolean,
    onExit: () -> Unit,
    /** 电视那里解不出片源：连着也播不动，状态点不能还是绿的 */
    failed: Boolean = false,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(
                    when {
                        !online -> ProomColor.danger
                        failed -> ProomColor.danger
                        else -> ProomColor.ok
                    },
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    when {
                        !online -> "已断开"
                        failed -> "投屏异常"
                        else -> "正在投屏"
                    },
                    color = ProomColor.muted,
                    fontSize = ProomSp(10.5.sp),
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 1.8.sp,
                )
                if (fullFeature) {
                    Text(
                        "  ·  全功能",
                        color = ProomColor.cast.copy(alpha = 0.8f),
                        fontSize = ProomSp(10.5.sp),
                        fontWeight = FontWeight.Medium,
                        letterSpacing = 1.8.sp,
                    )
                }
            }
            Spacer(Modifier.height(3.dp))
            Text(
                deviceName,
                color = ProomColor.text,
                style = ProomType.title.copy(fontSize = ProomSp(18.sp), lineHeight = 23.sp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Spacer(Modifier.width(4.dp))

        // 退出是破坏性动作：图形比文字弱一档，靠一圈弱描边和 danger 色表明性质，
        // 不用写「退出」两个字占掉标题的宽度。
        // 外圈 48dp 只为撑住点击热区，视觉上仍是 34dp 的小圆
        Box(
            Modifier
                .size(48.dp)
                .clip(CircleShape)
                .clickable(onClick = onExit),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(ProomColor.danger.copy(alpha = 0.06f))
                    .border(1.dp, ProomColor.danger.copy(alpha = 0.24f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    PlayerIcons.Close,
                    contentDescription = "退出投屏",
                    tint = ProomColor.danger.copy(alpha = 0.88f),
                    modifier = Modifier.size(14.dp),
                )
            }
        }
    }
}

@Composable
internal fun StatusDot(color: Color) {
    Box(
        Modifier
            .size(5.dp)
            .clip(CircleShape)
            .background(color),
    )
}

/**
 * 正在播的内容。
 *
 * 不套卡片底色：这一页的视觉重心是下面那个播放键，再压一层灰块只会
 * 把信息堆成「又一坨方块」。改成封面贴左 + 一条极淡的细线收尾。
 */
@Composable
internal fun NowPlayingCard(
    vod: Vod,
    site: String,
    episodeName: String,
    epIndex: Int,
    live: Boolean,
    playing: Boolean,
    online: Boolean,
    /** 和 RemoteTopRow 同一个「解不出片源」判断，卡片里的话得跟着改 */
    failed: Boolean = false,
) {
    Column {
        Row {
            Box(
                Modifier
                    .width(58.dp)
                    .aspectRatio(0.72f)
                    .clip(RoundedCornerShape(ProomShape.sm))
                    .background(ProomColor.card)
                    .border(1.dp, ProomColor.line, RoundedCornerShape(ProomShape.sm)),
            ) {
                RemoteImage(
                    model = vod.pic,
                    contentDescription = vod.name,
                    modifier = Modifier.fillMaxSize(),
                )
            }

            Spacer(Modifier.width(14.dp))

            Column(Modifier.weight(1f)) {
                Text(
                    vod.name,
                    color = ProomColor.text,
                    fontSize = ProomSp(17.sp),
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = (-0.1).sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(5.dp))
                Text(
                    buildString {
                        if (site.isNotBlank()) append(site)
                        val ep = episodeName.ifBlank { if (!live) "第 ${epIndex + 1} 集" else "" }
                        if (ep.isNotBlank()) {
                            if (isNotEmpty()) append("  ·  ")
                            append(ep)
                        }
                    }.ifBlank { "—" },
                    color = ProomColor.muted,
                    fontSize = ProomSp(12.5.sp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(9.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val tone = when {
                        !online -> ProomColor.danger
                        failed -> ProomColor.danger
                        else -> ProomColor.cast
                    }
                    StatusDot(tone)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        when {
                            !online -> "连接中断"
                            live -> "电视直播中"
                            failed -> "电视上放不出来"
                            playing -> "电视正在播放"
                            else -> "已暂停"
                        },
                        color = tone.copy(alpha = 0.9f),
                        fontSize = ProomSp(11.sp),
                        fontWeight = FontWeight.Medium,
                        letterSpacing = 0.4.sp,
                    )
                }
            }
        }

        Spacer(Modifier.height(18.dp))
        HairLine()
    }
}

@Composable
internal fun HairLine(color: Color = ProomColor.line) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(color),
    )
}
