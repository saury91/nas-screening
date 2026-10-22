package com.projectionroom.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.projectionroom.ui.music.MusicBox
import com.projectionroom.ui.music.MusicTrack
import com.projectionroom.ui.music.RepeatMode
import com.projectionroom.ui.theme.ProomColor
import com.projectionroom.ui.theme.ProomShape
import com.projectionroom.ui.theme.ProomSp

/**
 * 歌单页左栏底部的播放器。
 *
 * 和手机端 [com.projectionroom.ui.music.MiniPlayerBar] 是两套东西，别合并：
 * 那条是「浮在全宽页面底部的迷你条」，底色必须实（否则歌单字会透上来）。
 * 这里塞在左栏里、下面是同色留白，再给一块 bgSoft 实底 + 圆角，
 * 立刻变成贴在页面上的一张便签 —— 左栏一大片空 + 底部一块深色，割裂感就是这么来的。
 *
 * 所以这里反着做：**不要容器**。只有一条渐隐细线当作「这里开始是播放器」的分界，
 * 剩下的直接用背景色，让它看起来是左栏内容的一部分而不是浮层。
 *
 * ## 空态也是完整的一套控件
 *
 * 还没开始播时不能整块消失 —— 那会让左栏下方空出一大块，而且用户看不出
 * 这块地方是干什么的。所以空态照样画出封面位、标题位、四颗按钮和进度条，
 * 只是信息位显示「未在播放」，点播放键就从第一首开始。
 *
 * 空态下的上一首/下一首没有「当前曲目」可参照，按歌单边界来兜：
 * 上一首 = 最后一首（环形回绕的自然结果），播放/下一首 = 第一首。
 */
@Composable
fun TvNowPlayingBar(
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
    /**
     * 队列为空时用来顶上的歌单。
     *
     * 空态按钮必须有东西可播，而「当前歌单」只有页面自己知道 ——
     * 所以由调用方把曲目带进来，播放器不自己去找数据。
     */
    idleTracks: List<MusicTrack> = emptyList(),
    /** 空态开播：传下标（0 = 第一首，lastIndex = 最后一首） */
    onPlayIdle: (Int) -> Unit = {},
    /** 供顶栏胶囊 / 媒体键一步跳进来 */
    firstActionFocus: FocusRequester? = null,
) {
    val track = MusicBox.current
    val idle = track == null
    val canStart = idleTracks.isNotEmpty()

    Column(modifier.fillMaxWidth()) {
        // 分界线两端淡出：一根通栏实线会像表格边框，把左栏硬生生切断。
        // 空态也画 —— 它是版面的分节符，不是「有歌才出现」的装饰
        Spacer(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(
                    Brush.horizontalGradient(
                        listOf(Color.Transparent, ProomColor.surface2, Color.Transparent)
                    )
                )
        )
        Spacer(Modifier.height(10.dp))

        // 拆成 5 个独立焦点目标：左格 + 4 颗按钮，左右键在本组内循环。
        // 整条可聚焦的话遥控器只停在外沿，里面按钮永远选不到。
        var openFocused by remember { mutableStateOf(false) }
        val startFirst = { if (canStart) onPlayIdle(0) }

        Row(
            Modifier.fillMaxWidth().focusGroup(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(ProomShape.sm))
                    // 焦点态用淡底 + 细边，不用实色翻白：这块区域很大，
                    // 整块翻白会把左栏撕成两个色块，比没有反馈更割裂
                    .then(
                        if (openFocused) {
                            Modifier
                                .background(ProomColor.surface)
                                .border(2.dp, ProomColor.accent, RoundedCornerShape(ProomShape.sm))
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
                    // 空态点这里没有播放页可进，直接当作「从第一首开始」
                    .clickable(onClick = { if (idle) startFirst() else onOpen() })
                    .padding(vertical = 6.dp, horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(ProomShape.sm))
                        .background(ProomColor.card),
                    contentAlignment = Alignment.Center,
                ) {
                    if (track != null && track.cover.isNotBlank()) {
                        RemoteImage(
                            model = track.cover,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(44.dp)
                                .clip(RoundedCornerShape(ProomShape.sm)),
                        )
                    } else {
                        // 空态封面位：一个音符，说明「这里放封面」
                        Icon(
                            imageVector = TabIcons.Music,
                            contentDescription = null,
                            tint = ProomColor.muted.copy(alpha = 0.55f),
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        track?.title ?: "未在播放",
                        color = if (track == null) ProomColor.dim else ProomColor.text,
                        fontSize = ProomSp(14.sp),
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    // 第二行的三档：加载提示 / 歌词 / 歌手。
                    // 空态只给「有几首」—— 那才是这个位置真正的信息。
                    // 不写「按 OK 播放」之类的操作指引：遥控器的播放键就在旁边，
                    // 一行字教用户按哪个键，是后台视角不是观众视角
                    val sub = when {
                        track == null ->
                            if (canStart) "共 ${idleTracks.size} 首" else "这个歌单还没有歌"
                        MusicBox.loading -> "正在加载…"
                        MusicBox.lyric.isNotEmpty() -> {
                            MusicBox.lyric.getOrNull(MusicBox.lyricAt)?.text ?: track.artist
                        }
                        else -> track.artist
                    }
                    Text(
                        text = sub,
                        color = if (!idle && MusicBox.lyric.isNotEmpty() && MusicBox.lyricAt >= 0) {
                            ProomColor.accent.copy(alpha = 0.82f)
                        } else {
                            ProomColor.muted
                        },
                        // 12sp 是电视上辅助信息的下限：11sp 在 3 米外糊成一条
                        fontSize = ProomSp(12.sp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            NowBtn(PlayerIcons.Prev, "上一首") {
                // 空态：环形回绕到最后一首
                if (idle) {
                    if (canStart) onPlayIdle(idleTracks.lastIndex)
                } else {
                    MusicBox.prev()
                }
            }
            val playing = !idle && MusicBox.playing
            NowBtn(
                icon = if (playing) PlayerIcons.Pause else PlayerIcons.Play,
                desc = if (playing) "暂停" else "播放",
            ) {
                if (idle) startFirst() else MusicBox.toggle()
            }
            NowBtn(PlayerIcons.Next, "下一首") {
                if (idle) startFirst() else MusicBox.next()
            }
            // 原来是「关闭播放器」（X）。在歌单页里点它只会把歌停掉、
            // 播放条缩成空壳，用户还得自己再点一次播放 —— 不如换成循环模式，
            // 它才是这块版面里真正缺的那颗（单曲循环 / 列表循环 / 随机）
            val mode = MusicBox.repeat
            NowBtn(repeatIcon(mode), repeatLabel(mode)) { MusicBox.cycleRepeat() }
        }

        Spacer(Modifier.height(8.dp))
        PlayProgress(idle)
        // 留出电视的安全底边：原来贴到屏幕最下方，
        // 进度条几乎压在画面边缘，像被裁掉了一截
        Spacer(Modifier.height(20.dp))
    }
}

/**
 * 进度 + 时间。
 *
 * 单独抽出来：播放中每秒都会改一次 position，让它只重组这一行，
 * 别把封面 / 标题 / 4 颗按钮跟着重画（静置时那是纯白烧的帧）。
 */
@Composable
private fun PlayProgress(idle: Boolean) {
    val dur = if (idle) 0L else MusicBox.duration
    val pos = if (idle) 0L else MusicBox.position
    val frac = if (dur > 0) (pos.toFloat() / dur).coerceIn(0f, 1f) else 0f

    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .weight(1f)
                .height(2.dp)
                .clip(RoundedCornerShape(1.dp))
                .background(ProomColor.line),
        ) {
            Box(
                Modifier
                    .fillMaxWidth(frac)
                    .height(2.dp)
                    .background(ProomColor.accent)
            )
        }
        Spacer(Modifier.width(10.dp))
        // 空态写 --:-- 而不是 0:00/0:00：后者看起来像「一首 0 秒的歌」
        Text(
            if (idle) "0:00 / --:--" else "${mmss(pos)} / ${mmss(dur)}",
            color = ProomColor.muted,
            fontSize = ProomSp(12.sp),
        )
    }
}

/** 48dp 圆钮：电视上 38dp 既难点也难认。焦点态用淡底 + 金环，和顶栏胶囊同一语言。 */
@Composable
private fun NowBtn(
    icon: ImageVector,
    desc: String,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    Box(
        Modifier
            .size(48.dp)
            .clip(CircleShape)
            .then(if (focused) Modifier.background(ProomColor.surface2) else Modifier)
            .then(if (focused) Modifier.border(2.dp, ProomColor.accent, CircleShape) else Modifier)
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = desc,
            tint = if (focused) ProomColor.accent else Color.White.copy(alpha = 0.88f),
            modifier = Modifier.size(22.dp),
        )
    }
}

private fun repeatIcon(mode: RepeatMode): ImageVector = when (mode) {
    RepeatMode.All -> PlayerIcons.Repeat
    RepeatMode.One -> PlayerIcons.RepeatOne
    RepeatMode.Shuffle -> PlayerIcons.Shuffle
}

private fun repeatLabel(mode: RepeatMode): String = when (mode) {
    RepeatMode.All -> "列表循环"
    RepeatMode.One -> "单曲循环"
    RepeatMode.Shuffle -> "随机播放"
}

private fun mmss(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    return "${total / 60}:${(total % 60).toString().padStart(2, '0')}"
}
