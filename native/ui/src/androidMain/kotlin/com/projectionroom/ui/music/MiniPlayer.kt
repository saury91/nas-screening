package com.projectionroom.ui.music

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.projectionroom.ui.component.PlayerIcons
import com.projectionroom.ui.component.TabIcons
import com.projectionroom.ui.theme.ProomColor
import com.projectionroom.ui.theme.ProomShape
import com.projectionroom.ui.theme.ProomSpace

/**
 * 底部迷你播放条。
 *
 * 点歌之后不该直接把人推进全屏页 —— 用户往往只想在歌单里再挑两首。
 * 所以先落一条 56dp 的播放条：点封面/标题进全屏，点右侧按钮就地暂停、切歌。
 *
 * 高度按拇指区来定：整条可点，右侧两个 40dp 圆钮不抢行高。
 */
@Composable
fun MiniPlayerBar(
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val track = MusicBox.current ?: return

    Column(
        modifier
            .fillMaxWidth()
            .background(ProomColor.bgSoft)
    ) {
        // 顶部一条进度：比在 56dp 里塞进度条更省地方，也不挡文字。
        // 单独抽成 composable：进度每秒变一次，只让这一条重组，
        // 不要把封面/标题/按钮跟着重画一遍（静置时那是白烧的帧）
        ProgressLine()
        Row(
            Modifier
                .fillMaxWidth()
                .height(56.dp)
                .clickable(onClick = onOpen)
                .padding(start = 10.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(ProomShape.sm))
                    .background(ProomColor.card),
            ) {
                if (track.cover.isNotBlank()) {
                    AsyncImage(
                        model = track.cover,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(40.dp).clip(RoundedCornerShape(ProomShape.sm)),
                    )
                }
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    track.title,
                    color = ProomColor.text,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                // 第二行优先显示当前歌词：迷你条的可用信息只有这一行，
                // 显示歌手名是浪费 —— 歌手在封面和全屏页都能看到
                Text(
                    text = when {
                        MusicBox.loading -> "正在加载…"
                        MusicBox.lyric.isNotEmpty() -> {
                            val at = MusicBox.lyricAt
                            MusicBox.lyric.getOrNull(at)?.text ?: track.artist
                        }
                        else -> track.artist
                    },
                    color = if (MusicBox.lyric.isNotEmpty() && MusicBox.lyricAt >= 0) {
                        ProomColor.accent.copy(alpha = 0.82f)
                    } else {
                        ProomColor.muted
                    },
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            // 四个按钮：上一首 / 播放暂停 / 下一首 / 关闭。
            // 之前只有「暂停 + 下一首」，想回上一首或收掉播放条都没有入口
            MiniBtn(PlayerIcons.Prev, "上一首") { MusicBox.prev() }
            MiniBtn(
                icon = if (MusicBox.playing) PlayerIcons.Pause else PlayerIcons.Play,
                desc = if (MusicBox.playing) "暂停" else "播放",
            ) { MusicBox.toggle() }
            MiniBtn(PlayerIcons.Next, "下一首") { MusicBox.next() }
            MiniBtn(TabIcons.Close, "关闭播放条") { MusicBox.stop() }
        }
    }
}

@Composable
private fun ProgressLine() {
    val p = if (MusicBox.duration > 0) {
        (MusicBox.position.toFloat() / MusicBox.duration).coerceIn(0f, 1f)
    } else 0f
    Box(Modifier.fillMaxWidth().height(1.5.dp).background(ProomColor.line)) {
        Box(Modifier.fillMaxWidth(p).height(1.5.dp).background(ProomColor.accent))
    }
}

@Composable
private fun MiniBtn(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    desc: String,
    onClick: () -> Unit,
) {
    Box(
        Modifier.size(38.dp).clip(CircleShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = desc,
            tint = Color.White.copy(alpha = 0.88f),
            modifier = Modifier.size(18.dp),
        )
    }
}

@Composable
private fun CircleBtn(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    desc: String,
    onClick: () -> Unit,
) {
    Box(
        Modifier.size(40.dp).clip(CircleShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = desc,
            tint = Color.White.copy(alpha = 0.9f),
            modifier = Modifier.size(19.dp),
        )
    }
}
