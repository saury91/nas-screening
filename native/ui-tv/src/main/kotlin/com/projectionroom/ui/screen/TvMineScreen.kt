package com.projectionroom.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.projectionroom.shared.model.Vod
import com.projectionroom.ui.component.TvPillButton
import com.projectionroom.ui.component.TvRow
import com.projectionroom.ui.component.WideCard
import com.projectionroom.ui.theme.ProomColor
import com.projectionroom.ui.theme.ProomShape
import com.projectionroom.ui.theme.ProomType
import com.projectionroom.ui.theme.TvLayout
import com.projectionroom.ui.theme.TvSpace

/**
 * 「我的」页底部的设备/内容状态：纯信息，不进焦点链。
 *
 * 这几个值原来只在设置页里能看到，而观众判断「这个 App 还活着吗」靠的就是它们：
 * 连的是哪台 NAS、配置是哪一版、缓存占了多少。
 */
data class MineDevice(
    val nasUrl: String = "",
    val version: String = "",
    val configVersion: String = "",
    val sourceCount: Int = 0,
    val channelCount: Int = 0,
    val cacheSize: String = "—",
)

/**
 * 电视端的「我的」：三段式，一屏填满，不用上下翻。
 *
 * 1. 播放记录 —— 和首页「继续观看」同一个 [WideCard]，一屏 5~6 张；
 * 2. 我的收藏 —— 海报货架；为空时退化成一条带「去片库」按钮的提示条，
 *    而不是整块消失（块一消失，下面就是大片空黑，看着像页面没加载完）；
 * 3. 底部常驻「设备与内容」信息条 —— 悬浮在列表之上，任何滚动位置都在。
 *
 * 前两段都会「有没有内容」，第三段永远是满的 —— 这是这一页不会显得空的关键。
 *
 * 播放记录原来是竖排宽条（一条 96dp 高、一行一条进度条）—— 一屏只放得下 4 条，
 * 而且和首页的「继续观看」是同一批数据、同一种信息，换了个地方却换了个形态，
 * 看着像两套东西。
 */
@Composable
fun TvMineScreen(
    favorites: List<Vod>,
    history: List<Vod>,
    loading: Boolean = false,
    device: MineDevice? = null,
    onOpen: (Vod) -> Unit,
    onBack: () -> Unit,
    /** 收藏为空时那条提示里的「去片库」；不传就只留文字 */
    onBrowse: (() -> Unit)? = null,
    /** 打开「用手机导入配置」的配对页；不传就不显示这个入口 */
    onImportFromPhone: (() -> Unit)? = null,
    /** 打开设置页（NAS 地址 / 同步配置 / 恢复出厂 / 检查更新） */
    onSettings: (() -> Unit)? = null,
    topInset: Dp = 0.dp,
) {
    BoxWithConstraints(Modifier.fillMaxSize().background(ProomColor.bg)) {
        // 两排都用 16:9 宽卡。收藏原本是海报（130dp 宽、198dp 高），加上记录行
        // 是 580dp，一屏 540dp 根本放不下 —— 收藏的片名会被底部信息条压掉半行。
        // 换成同宽的横卡后两块等高，整页 488dp，一屏看全不用滚。
        val wideCardW = (maxWidth * 0.16f).coerceIn(160.dp, 300.dp)

        val empty = !loading && favorites.isEmpty() && history.isEmpty()
        // 底部信息条 56 + 上下留白：列表滚到底时最后一行不被它压住
        val footerH = if (device != null) 56.dp + TvSpace.s16 * 2 else 0.dp

        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = TvSpace.s24 + footerH),
        ) {
            item {
                // 和其它页面同一条顶栏：左边距 40、返回提示与标题同高、右侧动作靠右
                com.projectionroom.ui.component.TvPageHeader(
                    onBack = onBack,
                    title = "我的",
                    topInset = topInset,
                    trailing = {
                        if (loading) {
                            Text(
                                "同步中…",
                                style = ProomType.label,
                                color = ProomColor.muted,
                                modifier = Modifier.padding(end = TvSpace.s12),
                            )
                        }
                        // 电视上填地址是酷刑：给一个「手机推过来」的入口
                        if (onImportFromPhone != null) {
                            TvPillButton("从手机导入配置", onImportFromPhone)
                            Spacer(Modifier.width(TvSpace.s16))
                        }
                        if (onSettings != null) {
                            TvPillButton("设置", onSettings)
                        }
                    },
                )
            }

            if (history.isNotEmpty()) {
                item { MineSectionTitle("继续观看", "${history.size} 条") }
                item {
                    TvRow(
                        items = history,
                        itemWidth = wideCardW,
                        gap = TvSpace.s16,
                        contentPadding = PaddingValues(horizontal = TvLayout.insetX),
                        // 数据是异步来的：首帧列表还是空的，焦点无处可去就会落到
                        // 顶栏的「返回」上，按一下直接退出去。列表一到就抢回来。
                        // 记录行在最上面，是这一页第一个可播内容，焦点先给它
                        autoFocusFirst = true,
                    ) { v, focused ->
                        WideCard(
                            vod = v,
                            width = wideCardW,
                            focused = focused,
                            onClick = { onOpen(v) },
                        )
                    }
                }
            }

            // 收藏：有内容就是货架，没内容也要占住这一排 —— 直接不画的话，
            // 屏幕上就只剩下一行记录和大片空黑（这一页最初被吐槽的就是这个）
            if (!empty) {
                item { MineSectionTitle("我的收藏", "${favorites.size} 部") }
                if (favorites.isNotEmpty()) {
                    item {
                        TvRow(
                            items = favorites,
                            itemWidth = wideCardW,
                            gap = TvSpace.s16,
                            contentPadding = PaddingValues(horizontal = TvLayout.insetX),
                            autoFocusFirst = history.isEmpty(),
                        ) { v, focused ->
                            WideCard(
                                vod = v,
                                width = wideCardW,
                                focused = focused,
                                onClick = { onOpen(v) },
                            )
                        }
                    }
                } else {
                    item { FavoriteEmptyBar(onBrowse) }
                }
            }

            if (empty) {
                // 空态统一「一行标题 + 一行说明」，居中对齐（规范 §8.3）。
                // 之前这里是一句话 18sp 的正文，音乐页是 24sp 标题 + 15sp 说明 ——
                // 同一个 App 的两种"没内容"，观众会以为其中一个出错了
                item {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(top = TvSpace.s48, start = TvSpace.s24, end = TvSpace.s24),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text("还没有收藏和记录", style = ProomType.title, color = ProomColor.text)
                        Text(
                            "在详情页按「收藏」，看过的影片也会出现在这里",
                            style = ProomType.meta,
                            color = ProomColor.dim,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(top = TvSpace.s12),
                        )
                        if (onBrowse != null) {
                            Spacer(Modifier.height(TvSpace.s24))
                            TvPillButton("去片库挑几部", onBrowse)
                        }
                    }
                }
            }
        }

        if (device != null) {
            MineDeviceBar(
                device = device,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = TvLayout.insetX, vertical = TvSpace.s16),
            )
        }
    }
}

/** 收藏为空时占住这一排的提示条：和货架同一个左边距、同一种圆角，看着还是「一段」 */
@Composable
private fun FavoriteEmptyBar(onBrowse: (() -> Unit)?) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = TvLayout.insetX)
            .height(88.dp)
            .clip(RoundedCornerShape(ProomShape.md))
            .background(ProomColor.surface)
            .border(1.dp, ProomColor.line, RoundedCornerShape(ProomShape.md))
            .padding(start = TvSpace.s24, end = TvSpace.s24),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("还没有收藏的影片", style = ProomType.body, color = ProomColor.text)
            Text(
                "详情页按「收藏」，它会一直留在这里",
                style = ProomType.meta,
                color = ProomColor.dim,
                modifier = Modifier.padding(top = TvSpace.s4),
            )
        }
        if (onBrowse != null) TvPillButton("去片库挑几部", onBrowse)
    }
}

/**
 * 底部常驻的「设备与内容」：NAS / 配置 / 缓存 / 版本。
 *
 * 故意**不可聚焦**：这一页的焦点应该只落在能播的内容上，滚到底还要穿过一条
 * 信息带才能回顶栏，是纯负担。想把信息点开的人走顶栏的「设置」。
 */
@Composable
private fun MineDeviceBar(device: MineDevice, modifier: Modifier = Modifier) {
    val connected = device.nasUrl.isNotBlank()
    Row(
        modifier
            .fillMaxWidth()
            .height(56.dp)
            .clip(RoundedCornerShape(ProomShape.md))
            // 别写 surface.copy(alpha = 0.94f)：surface 本身就是「白 5.5%」，
            // copy 会把 alpha 整个覆盖成 94%，整条变成白底，白字直接消失
            .background(ProomColor.surface)
            .border(1.dp, ProomColor.line, RoundedCornerShape(ProomShape.md))
            .padding(horizontal = TvSpace.s24),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 连接状态用「点 + 文字」表达：金色留给主操作，不能因为「连着」就点亮一颗金点
        Box(
            Modifier
                .size(6.dp)
                .clip(RoundedCornerShape(50))
                .background(if (connected) ProomColor.dim else ProomColor.muted)
        )
        Spacer(Modifier.width(TvSpace.s8))
        DeviceItem(
            label = "NAS",
            // 「http://」在电视上没人看，占的却是最宽的那一格
            value = if (connected) {
                device.nasUrl.removePrefix("http://").removePrefix("https://")
            } else {
                "未连接"
            },
            dim = !connected,
        )
        if (device.configVersion.isNotBlank() || device.sourceCount > 0) {
            DeviceDivider()
            DeviceItem(
                label = "配置",
                value = listOfNotNull(
                    // 配置版本是 18 位 hash：整串塞进来会把后面的源数/频道数挤出去
                    device.configVersion.takeIf { it.isNotBlank() }?.let { "v" + it.take(7) },
                    "${device.sourceCount} 个源",
                    "${device.channelCount} 个频道",
                ).joinToString(" · "),
            )
        }
        DeviceDivider()
        DeviceItem(label = "缓存", value = device.cacheSize)
        if (device.version.isNotBlank()) {
            DeviceDivider()
            DeviceItem(label = "版本", value = device.version)
        }
    }
}

@Composable
private fun DeviceItem(label: String, value: String, dim: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = ProomType.label, color = ProomColor.muted)
        Text(
            value,
            style = ProomType.label,
            color = if (dim) ProomColor.muted else ProomColor.text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = TvSpace.s8),
        )
    }
}

@Composable
private fun DeviceDivider() {
    Box(
        Modifier
            .padding(horizontal = TvSpace.s16)
            .width(1.dp)
            .height(14.dp)
            .background(Color.White.copy(alpha = 0.08f))
    )
}

@Composable
private fun MineSectionTitle(title: String, sub: String) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = TvLayout.insetX, vertical = TvSpace.s8),
        verticalAlignment = Alignment.Bottom,
    ) {
        Text(title, style = ProomType.title, color = ProomColor.text)
        Text(
            sub,
            style = ProomType.meta,
            color = ProomColor.muted,
            modifier = Modifier.padding(start = TvSpace.s12, bottom = TvSpace.s4),
        )
    }
}
