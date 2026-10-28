package com.projectionroom.ui.screen

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.activity.compose.BackHandler
import com.projectionroom.ui.component.TabIcons
import com.projectionroom.ui.music.MusicLib
import com.projectionroom.ui.music.MusicShelf
import com.projectionroom.ui.music.MusicTrack
import com.projectionroom.ui.theme.ProomColor
import com.projectionroom.ui.theme.ProomShape
import com.projectionroom.ui.theme.ProomType

/**
 * 「收藏到歌单」弹层：默认收藏歌单 + 自建歌单，一个地方解决。
 *
 * 为什么不是点一下就进默认歌单、别的入口藏起来：
 * 用户心里只有「收藏」一个动作，进哪个歌单应该在同一屏里能选，
 * 点错了当场再点一次就取消 —— 不做「先收藏、再挪歌单」这种两步操作。
 */
@Composable
fun AddToPlaylistSheet(
    track: MusicTrack,
    onDismiss: () -> Unit,
    /** 收藏状态变了（外面据此刷新列表里那颗心） */
    onChanged: () -> Unit = {},
) {
    val ctx = LocalContext.current
    var creating by remember { mutableStateOf(false) }

    // 选完一个歌单就关弹层，所以这里不需要「改完再刷一遍」的版本号：
    // 下次打开是新的一轮组合，状态自然是最新的
    val liked = MusicLib.isFav(ctx, track.id, track.title, track.artist)
    val playlists = MusicShelf.playlists(ctx)
    val inIds = playlists.filter { MusicShelf.contains(ctx, it.id, track) }.map { it.id }.toSet()
    // 已经在几处有归属：0 → 这是「挑一个放进去」，>0 → 这是「管理已有的收藏」
    val savedCount = inIds.size + if (liked) 1 else 0

    fun toast(msg: String) = Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()

    BackHandler(enabled = true, onBack = onDismiss)

    Box(Modifier.fillMaxSize()) {
        // 遮罩：点空白关掉
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.62f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss,
                ),
        )

        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .clip(RoundedCornerShape(topStart = ProomShape.lg, topEnd = ProomShape.lg))
                .background(ProomColor.bgSoft)
                .navigationBarsPadding()
                .padding(horizontal = 18.dp, vertical = 16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    // 已经收藏过的，标题照实说是「管理收藏」：
                    // 还挂「收藏到歌单」的话，用户会以为刚才那一下没收进去
                    Text2(
                        if (savedCount > 0) "管理收藏" else "收藏到歌单",
                        17,
                        ProomColor.text,
                        FontWeight.SemiBold,
                    )
                    Spacer(Modifier.size(3.dp))
                    Text2(
                        if (savedCount > 0) {
                            "${track.title} · 已在 ${savedCount} 个歌单，点一下移出"
                        } else {
                            "${track.title} · ${track.artist.ifBlank { "未知歌手" }}"
                        },
                        12,
                        ProomColor.muted,
                        FontWeight.Normal,
                    )
                }
                Box(
                    Modifier.size(32.dp).clip(RoundedCornerShape(ProomShape.sm)).clickable(onClick = onDismiss),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = TabIcons.Close,
                        contentDescription = "关闭",
                        tint = ProomColor.muted,
                        modifier = Modifier.size(15.dp),
                    )
                }
            }

            Spacer(Modifier.size(12.dp))

            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 320.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                // 默认收藏歌单：不在库里，就是「点了心形的那些歌」
                ShelfRow(
                    name = MusicShelf.LIKED_NAME,
                    meta = if (liked) "已收藏" else "默认收藏歌单",
                    icon = true,
                    checked = liked,
                ) {
                    MusicLib.toggle(ctx, track)
                    toast(if (liked) "已移出「${MusicShelf.LIKED_NAME}」" else "已收藏到「${MusicShelf.LIKED_NAME}」")
                    onChanged()
                    // 选完就收：这层是「挑一个歌单放进去」的选择器，
                    // 留在这儿反而让人以为还有第二步要确认
                    onDismiss()
                }

                playlists.forEach { p ->
                    val inside = p.id in inIds
                    ShelfRow(
                        name = p.name,
                        meta = "${p.tracks.size} 首",
                        icon = false,
                        checked = inside,
                    ) {
                        if (inside) {
                            MusicShelf.removeTrack(ctx, p.id, track)
                            toast("已移出「${p.name}」")
                        } else {
                            MusicShelf.add(ctx, p.id, track)
                            toast("已收藏到「${p.name}」")
                        }
                        onChanged()
                        onDismiss()
                    }
                }

                Spacer(Modifier.size(6.dp))
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(ProomShape.sm))
                        .clickable { creating = true }
                        .padding(vertical = 12.dp, horizontal = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier
                            .size(34.dp)
                            .clip(RoundedCornerShape(ProomShape.sm))
                            .background(ProomColor.surface),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = TabIcons.Plus,
                            contentDescription = null,
                            tint = ProomColor.accent,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Text2("新建歌单", 14, ProomColor.text, FontWeight.Medium)
                }
            }
        }
    }

    if (creating) {
        CreatePlaylistDialog(
            onDismiss = { creating = false },
            onCreate = { name ->
                val p = MusicShelf.create(ctx, name)
                MusicShelf.add(ctx, p.id, track)
                creating = false
                onChanged()
                onDismiss()
                toast("已收藏到「${p.name}」")
            },
        )
    }
}

@Composable
private fun ShelfRow(
    name: String,
    meta: String,
    icon: Boolean,
    checked: Boolean,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(ProomShape.sm))
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp, horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(34.dp)
                .clip(RoundedCornerShape(ProomShape.sm))
                .background(if (icon) ProomColor.accentSoft else ProomColor.surface),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = if (icon) TabIcons.HeartOn else TabIcons.Music,
                contentDescription = null,
                tint = if (icon) ProomColor.accent else ProomColor.muted,
                modifier = Modifier.size(16.dp),
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text2(name, 14, ProomColor.text, FontWeight.Medium, 1)
            Text2(meta, 11, ProomColor.muted, FontWeight.Normal, 1)
        }
        Spacer(Modifier.width(10.dp))
        // 打勾用一颗小圆点：这里的语义是「已在这个歌单里」，不是「已选中待确认」
        Box(
            Modifier
                .size(20.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(if (checked) ProomColor.accent else ProomColor.surface2),
            contentAlignment = Alignment.Center,
        ) {
            if (checked) {
                Text2("✓", 12, Color(0xFF1B1206), FontWeight.SemiBold)
            }
        }
    }
}

/** 新建歌单：一个输入框，够用就行 */
@Composable
fun CreatePlaylistDialog(
    onDismiss: () -> Unit,
    onCreate: (String) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(ProomShape.lg))
                .background(ProomColor.bgSoft)
                .padding(20.dp),
        ) {
            Text2("新建歌单", 17, ProomColor.text, FontWeight.SemiBold)
            Spacer(Modifier.size(14.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(ProomShape.sm))
                    .background(ProomColor.surface)
                    .padding(horizontal = 12.dp, vertical = 12.dp),
            ) {
                if (name.isEmpty()) {
                    Text2("给歌单起个名字", 14, ProomColor.muted, FontWeight.Normal)
                }
                BasicTextField(
                    value = name,
                    onValueChange = { if (it.length <= 30) name = it },
                    singleLine = true,
                    textStyle = ProomType.body.copy(color = ProomColor.text),
                    cursorBrush = SolidColor(ProomColor.accent),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(Modifier.size(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                DialogBtn("取消", false, Modifier.weight(1f), onDismiss)
                DialogBtn(
                    "创建",
                    true,
                    Modifier.weight(1f),
                ) {
                    onCreate(name.trim().ifBlank { "新建歌单" })
                }
            }
        }
    }
}

@Composable
private fun DialogBtn(text: String, primary: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .clip(RoundedCornerShape(ProomShape.sm))
            .background(if (primary) ProomColor.accent else ProomColor.surface)
            .clickable(onClick = onClick)
            .padding(vertical = 11.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text2(
            text,
            14,
            if (primary) Color(0xFF1B1206) else ProomColor.text,
            FontWeight.SemiBold,
        )
    }
}

/** 这个文件里的小字：字号/颜色/字重/行数全部内联，免得每次都要 import 一堆 Text 参数 */
@Composable
private fun Text2(
    text: String,
    size: Int,
    color: Color,
    weight: FontWeight,
    maxLines: Int = 1,
) {
    androidx.compose.material3.Text(
        text = text,
        color = color,
        fontSize = size.sp,
        fontWeight = weight,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
    )
}
