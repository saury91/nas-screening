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
import androidx.compose.ui.graphics.Brush
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
    val maxH = (LocalConfiguration.current.screenHeightDp * 0.84f).dp
    val sheetW = if (isTv) 380.dp else 300.dp
    val rowWidth = if (isTv) 280.dp else 214.dp
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
                .width(sheetW)
                .heightIn(max = maxH)
                // 面板自成一「组」：方向键在面板内走完自己的一圈，
                // 不会从最上面的行一路跳到面板外背后的控件上
                .focusGroup()
                .clip(shape)
                .background(Color.Black.copy(alpha = 0.93f))
                .border(1.dp, Color.White.copy(alpha = 0.14f), shape)
                // 只拦点击、不进焦点树：clickable 会让面板本体成为没有环的可聚焦节点
                .pointerInput(Unit) { detectTapGestures { } },
        ) {
            // 直播没有片头片尾，面板里只剩字幕/音轨/字号，标题跟着内容走
            PlayerSheetHeader(if (live) "字幕与音轨" else "播放设置", isTv, closeFocus, firstFocus, onClose)

            // 这一段一定比一屏长（字幕+音轨+字号+片头尾），底部压一层渐隐：
            // 没有它，面板看起来像在「跳过片头」处被硬切断了，而不是还能往下滚
            val scroll = rememberScrollState()
            Box(
                Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false),
            ) {
                Column(
                    Modifier
                        .verticalScroll(scroll)
                        .padding(bottom = if (isTv) 12.dp else 8.dp),
                ) {
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
                    // 四档等宽铺满一行：原来的左对齐小方块右侧留一大片空，行也长短不齐
                    ChipGrid(
                        items = PlaybackPrefs.subtitleScales,
                        columns = 4,
                        label = { PlaybackPrefs.scaleLabel(it) },
                        selected = { subtitleScale == it },
                        onPick = onPickScale,
                    )

                    // 片头片尾是按「剧」记的，不是按集：同一部剧每集片头一样长，
                    // 所以这里改一次，之后所有集都跟着变。
                    // 直播没有片头片尾：整段不出现，也不写字解释「不适用」——
                    // 一屏就能放下，比留一段灰字强
                    if (canSkip) {
                        Divider()
                        SectionLabel("跳过片头（秒）")
                        ChipGrid(
                            items = SkipRule.introOptions,
                            columns = 3,
                            label = { skipLabel(it) },
                            selected = { skipIntroMs == it },
                            onPick = onPickIntro,
                        )
                        SectionLabel("片尾提示（秒）")
                        ChipGrid(
                            items = SkipRule.outroOptions,
                            columns = 3,
                            label = { skipLabel(it) },
                            selected = { skipOutroMs == it },
                            onPick = onPickOutro,
                        )
                        Text0("设「关」即不跳；这里改的是本剧，下一集同样生效")
                    }
                }

                if (scroll.canScrollForward) {
                    Box(
                        Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .height(if (isTv) 24.dp else 18.dp)
                            .background(
                                Brush.verticalGradient(
                                    listOf(Color.Transparent, Color.Black.copy(alpha = 0.93f)),
                                ),
                            ),
                    )
                }
            }
        }
    }
}

/**
 * 面板标题栏。播放设置、睡眠定时共用：切换面板时宽度、字号、✕ 落点都一样，
 * 视觉上不该像两个不同的应用弹出来的东西。
 *
 * ✕ 在标题行、和下面那列行不在同一条轴上，方向键的几何搜索找不到它，
 * 所以要显式接一条：首行 ↑ 到 ✕，✕ ↓ 回首行。
 */
@Composable
internal fun PlayerSheetHeader(
    title: String,
    isTv: Boolean,
    closeFocus: FocusRequester,
    firstFocus: FocusRequester,
    onClose: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            color = Color.White,
            fontSize = if (isTv) 19.sp else ProomSp(14.sp),
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.weight(1f))
        Box(
            Modifier.size(if (isTv) 40.dp else 32.dp)
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
                modifier = Modifier.size(if (isTv) 18.dp else 15.dp),
            )
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        color = Color.White.copy(alpha = 0.45f),
        fontSize = ProomSp(11.sp),
        modifier = Modifier.padding(start = 16.dp, top = 10.dp, bottom = 5.dp),
    )
}

/** 一行说明文字：和可点的行区分开（不可聚焦、灰一档） */
@Composable
private fun Text0(text: String, color: Color = Color.White.copy(alpha = 0.38f)) {
    Text(
        text,
        color = color,
        fontSize = ProomSp(10.5.sp),
        modifier = Modifier.padding(start = 16.dp, top = 1.dp, bottom = 3.dp, end = 14.dp),
    )
}

@Composable
private fun Divider() {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 4.dp)
            .height(1.dp)
            .background(Color.White.copy(alpha = 0.08f))
    )
}

private fun skipLabel(ms: Long): String = if (ms == 0L) "关" else "${ms / 1000}"

/**
 * 等宽档位网格。
 *
 * 每行固定 [columns] 格、每格等分宽度，一行不满时用同宽空格补上 ——
 * 「关 30 60 / 90 120 180」这种两行长度不一的排法右侧会参差，
 * 看着像没对齐；等宽后左边缘和右边缘都是一条直线。
 */
@Composable
internal fun <T> ChipGrid(
    items: List<T>,
    columns: Int,
    label: (T) -> String,
    selected: (T) -> Boolean,
    onPick: (T) -> Unit,
    firstModifier: Modifier = Modifier,
) {
    items.chunked(columns).forEachIndexed { rowIndex, row ->
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 3.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            row.forEachIndexed { i, item ->
                ScaleChip(
                    label = label(item),
                    selected = selected(item),
                    modifier = if (rowIndex == 0 && i == 0) firstModifier.weight(1f) else Modifier.weight(1f),
                ) { onPick(item) }
            }
            repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
        }
    }
}

@Composable
private fun ScaleChip(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(9.dp)
    Box(
        // 外部 modifier 放最前：weight 是 Row 作用域里的，必须挂在外层
        modifier
            .tvFocusRing(shape, ringWidth = 2.dp, zoom = 1f, inset = true)
            .clip(shape)
            .background(if (selected) ProomColor.accent else Color.White.copy(alpha = 0.10f))
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = if (selected) Color.Black else Color.White.copy(alpha = 0.82f),
            fontSize = ProomSp(11.5.sp),
            maxLines = 1,
        )
    }
}
