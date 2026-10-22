package com.projectionroom.ui.music

import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.projectionroom.ui.component.PlayerIcons
import com.projectionroom.ui.component.TabIcons
import com.projectionroom.ui.theme.ProomColor
import com.projectionroom.ui.theme.ProomShape
import com.projectionroom.ui.theme.LocalProomTv
import com.projectionroom.ui.theme.ProomSp
import com.projectionroom.ui.theme.ProomSpace
import com.projectionroom.ui.component.RemoteImage

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
    /**
     * 电视端：把左侧「打开播放页」那一格暴露给上层。
     *
     * 播放条排在 LazyColumn 之后，遥控器只能一路按过整张歌单才够得着它；
     * 上层（顶栏「播放中」胶囊、媒体键）拿到这个 requester 就能一步跳进来。
     */
    firstActionFocus: FocusRequester? = null,
) {
    val track = MusicBox.current ?: return
    // 电视上 56dp/40dp/13sp 的迷你条只有屏幕高度 1/10，字小到要凑近看：
    // 整体放大一档（72dp 高、56dp 封面、52dp 按钮）
    val tv = LocalProomTv.current
    val barH = if (tv) 72.dp else 56.dp
    val cover = if (tv) 56.dp else 40.dp

    Column(
        modifier
            .fillMaxWidth()
            .background(ProomColor.bgSoft)
    ) {
        // 顶部一条进度：比在 56dp 里塞进度条更省地方，也不挡文字。
        // 单独抽成 composable：进度每秒变一次，只让这一条重组，
        // 不要把封面/标题/按钮跟着重画一遍（静置时那是白烧的帧）
        ProgressLine()
        // 电视上不能让「整条」当焦点目标：整条可聚焦时遥控器只停在外沿，
        // 里面 4 颗按钮永远选不到（原来就是这样），而且它会连同 LazyColumn
        // 一起把焦点串到列表末尾。现在拆成 5 个独立目标：
        // 左侧「打开播放页」+ 4 颗按钮，左右键在本组内循环。
        var openFocused by remember { mutableStateOf(false) }
        Row(
            Modifier
                .fillMaxWidth()
                .height(barH)
                .then(if (tv) Modifier.focusGroup() else Modifier)
                // 手机上整条仍是热区；电视上由内部 5 个目标各自接管
                .then(if (tv) Modifier else Modifier.clickable(onClick = onOpen))
                .padding(start = if (tv) 18.dp else 10.dp, end = if (tv) 10.dp else 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                Modifier
                    .weight(1f)
                    .then(
                        if (tv) {
                            Modifier
                                .clip(RoundedCornerShape(ProomShape.sm))
                                .then(
                                    if (openFocused) {
                                        Modifier.border(
                                            2.dp,
                                            ProomColor.accent,
                                            RoundedCornerShape(ProomShape.sm),
                                        )
                                    } else {
                                        Modifier
                                    }
                                )
                                // requester 要挂在 focusable 之前，否则绑不到节点
                                .then(
                                    if (firstActionFocus != null) {
                                        Modifier.focusRequester(firstActionFocus)
                                    } else {
                                        Modifier
                                    }
                                )
                                .onFocusChanged { openFocused = it.isFocused }
                                .clickable(onClick = onOpen)
                        } else {
                            Modifier
                        }
                    )
                    // 焦点环画在格子外沿，别贴着封面和文字
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
            Box(
                Modifier
                    .size(cover)
                    .clip(RoundedCornerShape(ProomShape.sm))
                    .background(ProomColor.card),
            ) {
                if (track.cover.isNotBlank()) {
                    RemoteImage(
                        model = track.cover,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(cover).clip(RoundedCornerShape(ProomShape.sm)),
                    )
                }
            }
            Spacer(Modifier.width(if (tv) 14.dp else 10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    track.title,
                    color = ProomColor.text,
                    fontSize = ProomSp(13.sp),
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
                    fontSize = ProomSp(11.sp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            } // 左侧「打开播放页」格子结束：与 4 颗按钮平级
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

/**
 * 顶部进度条：既能看，也能拖。
 *
 * 视觉只有 1.5dp，但**触控高度给到 20dp**（线在中间），否则手指根本按不住。
 * 点一下跳转，按住左右拖是连续 seek —— 与全屏播放页的 MusicSeekBar 行为一致。
 */
@Composable
private fun ProgressLine() {
    val p = if (MusicBox.duration > 0) {
        (MusicBox.position.toFloat() / MusicBox.duration).coerceIn(0f, 1f)
    } else 0f

    // 拖动时先跟随手指，松手才真正 seek，避免每秒 60 次 seek 打满解码器
    var local by remember { mutableFloatStateOf(-1f) }
    val shown = if (local >= 0f) local else p

    fun seekTo(r: Float) {
        val d = MusicBox.duration
        if (d > 0) MusicBox.seek((r.coerceIn(0f, 1f) * d).toLong())
    }

    Box(
        Modifier
            .fillMaxWidth()
            .height(20.dp)
            .pointerInput(Unit) {
                detectTapGestures { off ->
                    seekTo(off.x / size.width)
                }
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { off ->
                        local = (off.x / size.width).coerceIn(0f, 1f)
                    },
                    onDragEnd = {
                        seekTo(local)
                        local = -1f
                    },
                    onDragCancel = { local = -1f },
                    onHorizontalDrag = { change, _ ->
                        change.consume()
                        local = (change.position.x / size.width).coerceIn(0f, 1f)
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.fillMaxWidth().height(1.5.dp).background(ProomColor.line)) {
            Box(Modifier.fillMaxWidth(shown).height(1.5.dp).background(ProomColor.accent))
        }
    }
}

@Composable
private fun MiniBtn(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    desc: String,
    onClick: () -> Unit,
) {
    // 38dp/18dp 的图标在电视上既难点也难认，48dp/22dp 才够
    val tv = LocalProomTv.current
    // 电视上这几个圆钮原来完全没有焦点态：遥控器停在哪一颗上只能靠按下去听声音。
    var focused by remember { mutableStateOf(false) }
    val shape = CircleShape
    Box(
        Modifier
            .size(if (tv) 48.dp else 38.dp)
            .clip(shape)
            // 手机上这些按钮是纯触摸目标，套焦点环只会显脏
            .then(if (tv && focused) Modifier.background(ProomColor.surface2) else Modifier)
            .then(if (tv && focused) Modifier.border(2.dp, ProomColor.accent, shape) else Modifier)
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = desc,
            tint = if (tv && focused) ProomColor.accent else Color.White.copy(alpha = 0.88f),
            modifier = Modifier.size(if (tv) 22.dp else 18.dp),
        )
    }
}

@Composable
private fun CircleBtn(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    desc: String,
    onClick: () -> Unit,
) {
    val tv = LocalProomTv.current
    Box(
        Modifier.size(if (tv) 48.dp else 40.dp).clip(CircleShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = desc,
            tint = Color.White.copy(alpha = 0.9f),
            modifier = Modifier.size(if (tv) 22.dp else 19.dp),
        )
    }
}
