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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.projectionroom.ui.component.PlayerIcons
import com.projectionroom.ui.component.tvFocusRing
import com.projectionroom.ui.playback.TrackOption
import com.projectionroom.ui.theme.ProomColor
import com.projectionroom.ui.theme.ProomSp
import kotlinx.coroutines.delay

/**
 * 「字幕与音轨」面板。
 *
 * 三段：字幕（关闭 / 内嵌轨 / 本地文件）、音轨、字号。
 * 内嵌轨是即时切换（改 trackSelectionParameters），本地字幕要重建媒体项，
 * 但两者都保持播放位置、不打断播放。
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
    onPickText: (TrackOption?) -> Unit,
    onPickAudio: (TrackOption?) -> Unit,
    onPickFile: () -> Unit,
    onRemoveFile: () -> Unit,
    onPickScale: (Float) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(16.dp)
    // 横屏只有 390dp 高：面板必须能滚，不能像倍速面板那样直接排下去
    val maxH = (LocalConfiguration.current.screenHeightDp * 0.82f).dp
    val rowWidth = if (isTv) 300.dp else 214.dp
    val firstFocus = remember { FocusRequester() }

    // 打开就把焦点领进面板：遥控器上不这样做，用户要先按方向键才「进得来」
    LaunchedEffect(Unit) {
        repeat(3) {
            delay(80)
            runCatching { firstFocus.requestFocus() }
        }
    }

    Box(
        modifier
            .fillMaxSize()
            .focusProperties { canFocus = false }
            .clickable(onClick = onClose),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .width(if (isTv) 400.dp else 300.dp)
                .heightIn(max = maxH)
                .clip(shape)
                .background(Color.Black.copy(alpha = 0.93f))
                .border(1.dp, Color.White.copy(alpha = 0.14f), shape)
                .clickable(enabled = false) {}
                .padding(vertical = if (isTv) 16.dp else 10.dp),
        ) {
            Row(
                Modifier.padding(start = 14.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "字幕与音轨",
                    color = Color.White,
                    fontSize = if (isTv) 22.sp else ProomSp(14.sp),
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.weight(1f))
                Box(
                    Modifier.size(if (isTv) 44.dp else 30.dp)
                        .clip(CircleShape)
                        .tvFocusRing(CircleShape)
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
                    modifier = Modifier.focusRequester(firstFocus),
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

@Composable
private fun ScaleChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(8.dp)
    Box(
        Modifier
            .clip(shape)
            .background(if (selected) ProomColor.accent else Color.White.copy(alpha = 0.10f))
            .tvFocusRing(shape, zoom = 1f)
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
