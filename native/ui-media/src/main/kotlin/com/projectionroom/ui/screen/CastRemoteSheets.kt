package com.projectionroom.ui.screen

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.projectionroom.shared.model.Vod
import com.projectionroom.ui.theme.ProomColor
import com.projectionroom.ui.theme.ProomShape
import com.projectionroom.ui.theme.ProomSp
import com.projectionroom.ui.theme.ProomType

/**
 * 选集 / 换源抽屉。
 *
 * 自绘而不用 ModalBottomSheet：后者自带的拖动条、圆角和遮罩都要重写一遍
 * 才贴合这套视觉，重写完剩下能用的部分还不如自己搭。
 */
@Composable
internal fun CastSheetHost(
    sheet: CastSheet,
    vod: Vod,
    lineIndex: Int,
    epIndex: Int,
    onDismiss: () -> Unit,
    onPickEp: (Int) -> Unit,
    onPickLine: (Int) -> Unit,
) {
    AnimatedVisibility(visible = sheet != CastSheet.None, enter = fadeIn(), exit = fadeOut()) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color(0xC408090C))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss,
                ),
        )
    }

    AnimatedVisibility(
        visible = sheet != CastSheet.None,
        enter = slideInVertically { it },
        exit = slideOutVertically { it },
        modifier = Modifier.fillMaxSize(),
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp))
                    .background(ProomColor.bgSoft)
                    .border(
                        width = 1.dp,
                        brush = Brush.verticalGradient(
                            listOf(ProomColor.lineStrong, Color.Transparent),
                        ),
                        shape = RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp),
                    )
                    .navigationBarsPadding()
                    .padding(horizontal = 20.dp)
                    .padding(top = 10.dp, bottom = 22.dp),
            ) {
                // 抓手的视觉暗示：让人知道这块是从下面推上来的
                Box(
                    Modifier
                        .align(Alignment.CenterHorizontally)
                        .width(34.dp)
                        .height(3.dp)
                        .clip(CircleShape)
                        .background(ProomColor.lineStrong),
                )

                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 18.dp),
                    verticalAlignment = Alignment.Bottom,
                ) {
                    Text(
                        if (sheet == CastSheet.Episodes) "选集" else "换条线路",
                        color = ProomColor.text,
                        style = ProomType.title,
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        if (sheet == CastSheet.Episodes) {
                            "第 ${epIndex + 1} 集 / 共 ${vod.lines.getOrNull(lineIndex)?.episodes?.size ?: 0} 集"
                        } else {
                            "${vod.lines.size} 条线路"
                        },
                        color = ProomColor.muted,
                        style = ProomType.meta,
                        modifier = Modifier.padding(bottom = 3.dp),
                    )
                    Spacer(Modifier.weight(1f))
                    ClosePill(onDismiss)
                }

                Spacer(Modifier.height(16.dp))

                when (sheet) {
                    CastSheet.Episodes -> EpisodeGrid(vod, lineIndex, epIndex, onPickEp)
                    CastSheet.Lines -> LineList(vod, lineIndex, onPickLine)
                    CastSheet.None -> Unit
                }
            }
        }
    }
}

@Composable
private fun ClosePill(onDismiss: () -> Unit) {
    Box(
        Modifier
            .size(30.dp)
            .clip(CircleShape)
            .background(ProomColor.surface)
            .border(1.dp, ProomColor.line, CircleShape)
            .clickable(onClick = onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "✕",
            color = ProomColor.dim,
            fontSize = ProomSp(13.sp),
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun EpisodeGrid(vod: Vod, lineIndex: Int, epIndex: Int, onPick: (Int) -> Unit) {
    val eps = vod.lines.getOrNull(lineIndex)?.episodes.orEmpty()
    if (eps.isEmpty()) {
        Text("这一条线路没有分集", color = ProomColor.muted, style = ProomType.body)
        return
    }
    // 按行数算高度：18 集只有 5 行，写死 320dp 会在网格下面留一大块空；
    // 集数多的时候封顶再滚动
    val rows = ((eps.size + 3) / 4).coerceAtMost(6)
    val gridHeight = (rows * 46 + (rows - 1).coerceAtLeast(0) * 9).dp

    LazyVerticalGrid(
        columns = GridCells.Fixed(4),
        modifier = Modifier.height(gridHeight.coerceAtMost(320.dp)),
        horizontalArrangement = Arrangement.spacedBy(9.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        itemsIndexed(eps) { i, ep ->
            val sel = i == epIndex
            Box(
                Modifier
                    .height(46.dp)
                    .clip(RoundedCornerShape(ProomShape.sm + 2.dp))
                    .background(if (sel) ProomColor.accentSoft else ProomColor.surface)
                    .border(
                        1.dp,
                        if (sel) ProomColor.accent.copy(alpha = 0.75f) else ProomColor.line,
                        RoundedCornerShape(ProomShape.sm + 2.dp),
                    )
                    .clickable { onPick(i) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    ep.name.ifBlank { (i + 1).toString() },
                    color = if (sel) ProomColor.accent else ProomColor.dim,
                    fontSize = ProomSp(12.5.sp),
                    fontWeight = if (sel) FontWeight.Medium else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * 线路列表。
 *
 * 用普通 Column 而不是 LazyColumn：一部片的线路通常个位数，滚动复用的收益为零，
 * 而 LazyColumn 的 itemsIndexed 和上面选集网格那个同名扩展在同一个文件里会互相盖住。
 */
@Composable
private fun LineList(vod: Vod, lineIndex: Int, onPick: (Int) -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            // heightIn 而不是 height：线路少时贴合内容，多了才滚动。
            // 写死高度会让最后一条卡在边框上，看着像被截断
            .heightIn(max = 320.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        vod.lines.forEachIndexed { i, ln ->
            val sel = i == lineIndex
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(bottom = 9.dp)
                    .clip(RoundedCornerShape(ProomShape.md))
                    .background(if (sel) ProomColor.accentSoft else ProomColor.surface)
                    .border(
                        1.dp,
                        if (sel) ProomColor.accent.copy(alpha = 0.75f) else ProomColor.line,
                        RoundedCornerShape(ProomShape.md),
                    )
                    .clickable { onPick(i) }
                    .padding(horizontal = 15.dp, vertical = 13.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        ln.site.ifBlank { "线路 ${i + 1}" },
                        color = if (sel) ProomColor.accent else ProomColor.text,
                        fontSize = ProomSp(13.5.sp),
                        fontWeight = FontWeight.Medium,
                    )
                    if (ln.remarks.isNotBlank()) {
                        Spacer(Modifier.height(2.dp))
                        Text(ln.remarks, color = ProomColor.muted, fontSize = ProomSp(11.sp))
                    }
                }
                Text(
                    "${ln.count} 集",
                    color = ProomColor.muted,
                    fontSize = ProomSp(11.5.sp),
                )
            }
        }
    }
}

internal fun fmtClock(ms: Long): String {
    if (ms <= 0) return "00:00"
    val total = ms / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}

internal fun trimRate(r: Float): String =
    if (kotlin.math.abs(r - r.toInt()) < 0.01f) "${r.toInt()}.0"
    else r.toString().trimEnd('0').trimEnd('.')
