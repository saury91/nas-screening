package com.projectionroom.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.projectionroom.shared.playback.SkipRule
import com.projectionroom.ui.component.PlayerIcons
import com.projectionroom.ui.component.tvFocusRing
import com.projectionroom.ui.playback.TrackOption
import com.projectionroom.ui.theme.ProomColor
import com.projectionroom.ui.theme.ProomSp
import kotlinx.coroutines.delay

/**
 * 「播放设置」面板。
 *
 * 四段：字幕（关闭 / 内嵌轨 / 本地文件）、音轨、字号、跳过片头片尾。
 * 前三段都是即时生效；跳过时长按剧记忆，改完只影响本剧之后的表现。
 *
 * 电视端没有文件选择器，那一行换成一句说明 —— 比放一个点了没反应的按钮好。
 */
@Composable
internal fun PlayerTrackSheet(
    textTracks: List<TrackOption>,
    audioTracks: List<TrackOption>,
    subtitleOn: Boolean,
    subtitleScale: Float,
    sideSubLabel: String?,
    live: Boolean,
    isTv: Boolean,
    skipIntroMs: Long,
    skipOutroMs: Long,
    canSkip: Boolean,
    onPickText: (TrackOption?) -> Unit,
    onPickAudio: (TrackOption?) -> Unit,
    onPickFile: () -> Unit,
    onRemoveFile: () -> Unit,
    onPickScale: (Float) -> Unit,
    onPickIntro: (Long) -> Unit,
    onPickOutro: (Long) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(16.dp)
    // 横屏只有 390dp 高：面板必须能滚，不能像倍速面板那样直接排下去
    val maxH = (LocalConfiguration.current.screenHeightDp * 0.82f).dp
    val rowWidth = if (isTv) 300.dp else 214.dp
    val firstFocus = rememberSheetFirstFocus()
    val closeFocus = remember { FocusRequester() }

    // 遮罩层只吃触摸、不进焦点树：遮罩一旦可聚焦，遥控器就会掉进这一整屏的
    // 「隐形按钮」里；电视上根本没有「点遮罩」这个动作。
    Box(
        modifier
            .fillMaxSize()
            .pointerInput(Unit) { detectTapGestures { onClose() } },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .width(if (isTv) 400.dp else 300.dp)
                .heightIn(max = maxH)
                // 面板自成一「组」：方向键在面板内走完自己的一圈，
                // 不会从最上面的行一路跳到面板外背后的控件上
                .focusGroup()
                .clip(shape)
                .background(Color.Black.copy(alpha = 0.93f))
                .border(1.dp, Color.White.copy(alpha = 0.14f), shape)
                // 只拦点击、不进焦点树：clickable 会让面板本体成为没有环的可聚焦节点
                .pointerInput(Unit) { detectTapGestures { } }
                .padding(vertical = if (isTv) 16.dp else 10.dp),
        ) {
            Row(
                Modifier.padding(start = 14.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "播放设置",
                    color = Color.White,
                    fontSize = if (isTv) 22.sp else ProomSp(14.sp),
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.weight(1f))
                Box(
                    Modifier.size(if (isTv) 44.dp else 30.dp)
                        // ✕ 在标题行、和下面那列行不在同一条轴上，方向键的几何搜索
                        // 找不到它。显式接一条：首行 ↑ 到 ✕，✕ ↓ 回首行
                        .focusRequester(closeFocus)
                        .focusProperties { down = firstFocus }
                        .tvFocusRing(CircleShape, ringWidth = 2.dp, zoom = 1f, inset = true)
                        .clip(CircleShape)
                        .clickable(onClick = onClose),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        PlayerIcons.Close,
                        "关闭",
                        tint = Color.White.copy(alpha = 0.6f),
                        modifier = Modifier.size(if (isTv) 20.dp else 16.dp),
                    )
                }
            }

            Column(Modifier.verticalScroll(rememberScrollState())) {
                SectionLabel("字幕")
                PanelRow(
                    label = "关闭",
                    selected = !subtitleOn,
                    modifier = Modifier
                        .focusProperties { up = closeFocus }
                        .focusRequester(firstFocus),
                    width = rowWidth,
                ) { onPickText(null) }
                textTracks.forEach { t ->
                    PanelRow(label = t.label, selected = subtitleOn && t.selected, width = rowWidth) {
                        onPickText(t)
                    }
                }
                if (textTracks.isEmpty()) Text0("这一路没有内嵌字幕轨")

                when {
                    live -> Text0("直播不挂本地字幕")
                    // 电视没有系统文件选择器：放一个点了没反应的按钮不如直接说清楚
                    isTv -> Text0("本地字幕请在手机上选择；电视只看内嵌字幕")
                    else -> {
                        PanelRow(
                            label = if (sideSubLabel == null) "选择本地字幕…" else "更换本地字幕…",
                            selected = false,
                            width = rowWidth,
                        ) { onPickFile() }
                        if (sideSubLabel != null) {
                            Text0("正在使用：$sideSubLabel", ProomColor.accent)
                            PanelRow(label = "移除本地字幕", selected = false, width = rowWidth) {
                                onRemoveFile()
                            }
                        }
                    }
                }

                Divider()
                SectionLabel("音轨")
                PanelRow(
                    label = "自动",
                    selected = audioTracks.none { it.selected },
                    width = rowWidth,
                ) { onPickAudio(null) }
                audioTracks.forEach { a ->
                    PanelRow(label = a.label, selected = a.selected, width = rowWidth) {
                        onPickAudio(a)
                    }
                }
                if (audioTracks.isEmpty()) Text0("这一路只有一条音轨")

                Divider()
                SectionLabel("字幕字号")
                Row(
                    Modifier.padding(horizontal = 14.dp, vertical = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    PlaybackPrefs.subtitleScales.forEach { s ->
                        ScaleChip(
                            label = PlaybackPrefs.scaleLabel(s),
                            selected = subtitleScale == s,
                        ) { onPickScale(s) }
                    }
                }

                Divider()
                // 片头片尾是按「剧」记的，不是按集：同一部剧每集片头一样长，
                // 所以这里改一次，之后所有集都跟着变
                if (canSkip) {
                    SectionLabel("跳过片头（秒）")
                    SkipChips(options = SkipRule.introOptions, current = skipIntroMs, onPick = onPickIntro)
                    SectionLabel("片尾提示（秒）")
                    SkipChips(options = SkipRule.outroOptions, current = skipOutroMs, onPick = onPickOutro)
                    Text0("设「关」即不跳；这里改的是本剧，下一集同样生效")
                } else {
                    SectionLabel("跳过片头 / 片尾")
                    Text0("直播没有片头片尾，这两项不适用")
                }
                Spacer(Modifier.height(4.dp))
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        color = Color.White.copy(alpha = 0.5f),
        fontSize = ProomSp(11.sp),
        modifier = Modifier.padding(start = 14.dp, top = 8.dp, bottom = 2.dp),
    )
}

/** 一行说明文字：和可点的行区分开（不可聚焦、灰一档） */
@Composable
private fun Text0(text: String, color: Color = Color.White.copy(alpha = 0.38f)) {
    Text(
        text,
        color = color,
        fontSize = ProomSp(10.5.sp),
        modifier = Modifier.padding(start = 14.dp, top = 1.dp, bottom = 3.dp, end = 12.dp),
    )
}

@Composable
private fun Divider() {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 6.dp)
            .height(1.dp)
            .background(Color.White.copy(alpha = 0.08f))
    )
}

/**
 * 跳过时长档位。
 *
 * 每行三个：面板在手机上只有 214dp 宽，六个档位排一行会挤成一条线。
 * 档位值直接用秒数显示，「关」单独处理 —— 单位写在段落标题里。
 */
@Composable
private fun SkipChips(options: List<Long>, current: Long, onPick: (Long) -> Unit) {
    options.chunked(3).forEach { row ->
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            row.forEach { ms ->
                ScaleChip(
                    label = if (ms == 0L) "关" else "${ms / 1000}",
                    selected = current == ms,
                ) { onPick(ms) }
            }
        }
    }
}

@Composable
private fun ScaleChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(8.dp)
    Box(
        Modifier
            .tvFocusRing(shape, ringWidth = 2.dp, zoom = 1f, inset = true)
            .clip(shape)
            .background(if (selected) ProomColor.accent else Color.White.copy(alpha = 0.10f))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Text(
            label,
            color = if (selected) Color.Black else Color.White.copy(alpha = 0.82f),
            fontSize = ProomSp(11.sp),
        )
    }
}
