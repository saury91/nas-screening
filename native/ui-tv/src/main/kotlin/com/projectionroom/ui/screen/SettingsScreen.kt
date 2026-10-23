package com.projectionroom.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import kotlinx.coroutines.delay
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.projectionroom.shared.playback.SkipRule
import com.projectionroom.ui.component.TabIcons
import com.projectionroom.ui.theme.ProomColor
import com.projectionroom.ui.theme.ProomShape
import com.projectionroom.ui.theme.ProomSpace
import com.projectionroom.ui.theme.ProomType
import com.projectionroom.ui.theme.ProomSp

/** 检查更新的结果 */
data class AppUpdate(
    val versionName: String,
    val versionCode: Long,
    val sizeMb: String,
    val notes: String = "",
    /** false = 已经是最新，界面上只显示一句话 */
    val newer: Boolean = false,
)

/** 一条订阅在设置页里的展示形态 */
data class SubRow(
    val url: String,
    val name: String,
    val info: String,
    val ok: Boolean = true,
)

/** 设置页要显示的全部状态，由壳组装后传进来 */
data class SettingsState(
    val nasUrl: String = "",
    val configVersion: String = "",
    val configSources: Int = 0,
    /** 配置包什么时候拉的，人话（「3 分钟前」/「昨天」） */
    val configAt: String = "",
    val versionName: String = "",
    val versionCode: Long = 0,
    val cacheSize: String = "",
    /** 分享出去的包里有多少东西（源数 / 台数），空表示还没有可分享的配置 */
    val shareInfo: String = "",
    val subs: List<SubRow> = emptyList(),
    val update: AppUpdate? = null,
    /** 上一次操作的结果，显示在底部一行 */
    val message: String? = null,
    val busy: Boolean = false,
)

/**
 * 设置页。
 *
 * 之前这些内容挤在「我的」底部（NAS 地址 + 版本两行），既看不到全貌，
 * 也放不下「手动同步」「检查更新」这类动作。现在独立成页，分三组：
 *
 * ```
 * NAS     地址 / 配置版本 / 立即同步配置 / 重新发现
 * 存储    缓存占用 / 清理
 * 关于    版本 / 检查更新 / 说明
 * ```
 *
 * 不在这里做片源管理：片源、直播源、下载任务全在 NAS 的网页端，
 * App 里造一套只会和网页端打架。
 */
@Composable
fun SettingsScreen(
    state: SettingsState,
    topInset: Dp = 0.dp,
    onBack: () -> Unit,
    onChangeNas: () -> Unit,
    onSyncConfig: () -> Unit,
    /** 用 APK 自带的那份配置覆盖本地（导错包、拉到空配置时退回出厂） */
    onImportBuiltIn: () -> Unit,
    onClearCache: () -> Unit,
    onCheckUpdate: () -> Unit,
    onDownloadUpdate: () -> Unit,
    /** 本机显示二维码，让另一台设备来取配置（电视是「被扫」的那一端） */
    onShowPair: () -> Unit,
    /** 导出配置并分享（写成文件交给系统分享面板） */
    onExport: () -> Unit,
    /** 从文件导入 */
    onImportFile: () -> Unit,
    /** 从剪贴板导入 */
    onImportClipboard: () -> Unit,
    onAddSub: (String) -> Unit,
    onRemoveSub: (String) -> Unit,
    onRefreshSubs: () -> Unit,
) {
    var addSubOpen by remember { mutableStateOf(false) }
    var subText by remember { mutableStateOf("") }
    // 进页即焦点：设置页全是整行条目，进来没焦点的话第一次按方向键只是滚动，
    // 看起来像「遥控器没反应」。首帧节点还没挂上，重试几次。
    val firstRow = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        repeat(10) {
            if (runCatching { firstRow.requestFocus() }.isSuccess) return@LaunchedEffect
            delay(40)
        }
    }
    Column(
        Modifier
            .fillMaxSize()
            .background(ProomColor.bg)
    ) {
        // 顶栏：全项目唯一规格（见 TvPageHeader），左边距/字号/返回提示都不再各写各的
        com.projectionroom.ui.component.TvPageHeader(
            onBack = onBack,
            title = "设置",
            topInset = topInset,
            trailing = {
                if (state.busy) {
                    CircularProgressIndicator(
                        color = ProomColor.accent,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(16.dp),
                    )
                }
            },
        )

        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f),
            contentPadding = PaddingValues(
                start = com.projectionroom.ui.component.TvPageHeaderSpec.inset,
                end = com.projectionroom.ui.component.TvPageHeaderSpec.inset,
                bottom = ProomSpace.xxl,
            ),
            verticalArrangement = Arrangement.spacedBy(ProomSpace.sm),
        ) {
            item { GroupTitle("NAS") }
            item {
                Card {
                    SettingRow(
                        title = "地址",
                        value = state.nasUrl.removePrefix("http://").removePrefix("https://"),
                        hint = "局域网内自动发现，也可以手动填",
                        action = "更换",
                        focusRequester = firstRow,
                        onClick = onChangeNas,
                    )
                    Divider()
                    SettingRow(
                        title = "配置版本",
                        value = if (state.configVersion.isNotBlank()) {
                            "${state.configVersion.withV()} · ${state.configSources} 个源"
                        } else "尚未同步",
                        hint = state.configAt.ifBlank { "配置包只在 NAS 更新时才变" },
                    )
                    Divider()
                    SettingRow(
                        title = "立即同步配置",
                        value = "",
                        hint = "片源地址存在本地，NAS 关机也能用；这里只是拉一次最新的",
                        action = if (state.busy) "同步中" else "同步",
                        enabled = !state.busy,
                        onClick = onSyncConfig,
                    )
                    Divider()
                    SettingRow(
                        title = "清空并恢复出厂",
                        value = "",
                        hint = "安装包不带任何源：这一步会把本地配置清空，回到刚装好的状态",
                        action = if (state.busy) "恢复中" else "恢复",
                        enabled = !state.busy,
                        onClick = onImportBuiltIn,
                    )
                    Divider()
                    SettingRow(
                        title = "重新发现 NAS",
                        value = "",
                        hint = "换了网段、改了 IP 时用",
                        action = "去发现",
                        onClick = onChangeNas,
                    )
                }
            }

            item { GroupTitle("配置分享") }
            item {
                Card {
                    // 「扫码从电视导入」是手机端的入口：这里本来就是电视，
                    // 电视去扫自己那一屏没有意义，所以整行删掉，保留「显示二维码」。
                    SettingRow(
                        title = "显示配对二维码",
                        value = "",
                        hint = "让另一台设备扫码取走这份配置（手机给手机、手机给电视都行）",
                        action = "显示",
                        onClick = onShowPair,
                    )
                    Divider()
                    SettingRow(
                        title = "导出发给朋友",
                        value = state.shareInfo,
                        hint = "导出的文件里有片源地址、分类和直播表；对方导入后不连 NAS 也能用",
                        action = "导出",
                        onClick = onExport,
                    )
                    Divider()
                    SettingRow(
                        title = "从文件导入",
                        value = "",
                        hint = "选一个 proom-config 配置文件",
                        action = "选择文件",
                        onClick = onImportFile,
                    )
                    Divider()
                    SettingRow(
                        title = "从剪贴板导入",
                        value = "",
                        hint = "对方把配置文本发在聊天里，复制后点这里",
                        action = "粘贴",
                        onClick = onImportClipboard,
                    )
                }
            }

            item { GroupTitle("订阅源") }
            item {
                Card {
                    if (state.subs.isEmpty()) {
                        SettingRow(
                            title = "还没有订阅",
                            value = "",
                            hint = "支持 TVBox 配置地址，也支持直接的 txt / m3u 直播列表",
                        )
                    }
                    state.subs.forEach { sub ->
                        SettingRow(
                            title = if (sub.ok) sub.name else "${sub.name}（失败）",
                            value = sub.info,
                            hint = sub.url,
                            action = "删除",
                            onClick = { onRemoveSub(sub.url) },
                        )
                        Divider()
                    }
                    SettingRow(
                        title = "添加订阅",
                        value = "",
                        hint = "填 TVBox 配置地址（http…/tvbox.json）或直播列表地址",
                        action = "添加",
                        enabled = !state.busy,
                        onClick = { addSubOpen = true },
                    )
                    Divider()
                    SettingRow(
                        title = "刷新全部订阅",
                        value = "",
                        hint = "重新拉一遍，解析出的源和频道会合并进本地",
                        action = if (state.busy) "刷新中" else "刷新",
                        enabled = !state.busy && state.subs.isNotEmpty(),
                        onClick = onRefreshSubs,
                    )
                }
            }

            item { GroupTitle("播放") }
            item {
                Card {
                    PlaybackSetting()
                }
            }

            item { GroupTitle("存储") }
            item {
                Card {
                    SettingRow(
                        title = "缓存占用",
                        value = state.cacheSize.ifBlank { "—" },
                        hint = "接口响应和图片。清掉只是下次要重新下载，不缺内容",
                        action = "清理",
                        onClick = onClearCache,
                    )
                }
            }

            item { GroupTitle("关于") }
            item {
                Card {
                    SettingRow(
                        title = "放映室",
                        value = "v${state.versionName} (${state.versionCode})",
                        hint = "电视端。也可以从 NAS 网页端下载安装包",
                    )
                    Divider()
                    val up = state.update
                    SettingRow(
                        title = "检查更新",
                        value = when {
                            up == null -> ""
                            up.newer -> "发现 v${up.versionName} · ${up.sizeMb}"
                            else -> "已是最新"
                        },
                        hint = when {
                            up == null -> "从 NAS 上取最新发布的安装包信息"
                            up.newer -> up.notes.ifBlank { "点右侧从 NAS 下载" }
                            else -> "NAS 上还没有更新的版本"
                        },
                        action = when {
                            up == null -> "检查"
                            up.newer -> "去更新"
                            else -> "再查"
                        },
                        enabled = !state.busy,
                        onClick = {
                            if (up?.newer == true) onDownloadUpdate() else onCheckUpdate()
                        },
                    )
                }
            }

            item {
                Text(
                    "片源、直播源、下载任务都在 NAS 的网页端配置，App 只负责播放。\n" +
                        "收藏和播放记录优先存本地，NAS 在线时后台同步。",
                    style = ProomType.label,
                    color = ProomColor.muted,
                    modifier = Modifier.padding(top = ProomSpace.sm, start = 4.dp, end = 4.dp),
                )
            }

        }

        if (addSubOpen) {
            androidx.compose.ui.window.Dialog(onDismissRequest = { addSubOpen = false }) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(ProomShape.lg))
                        .background(ProomColor.bgSoft)
                        .padding(18.dp),
                ) {
                    Text("添加订阅", style = ProomType.title, color = ProomColor.text)
                    Text(
                        "TVBox 配置地址，或者 txt / m3u 直播列表地址",
                        style = ProomType.label,
                        color = ProomColor.muted,
                        modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
                    )
                    BasicTextField(
                        value = subText,
                        onValueChange = { subText = it },
                        singleLine = true,
                        textStyle = androidx.compose.ui.text.TextStyle(
                            fontSize = ProomSp(14.sp), color = ProomColor.text,
                        ),
                        cursorBrush = androidx.compose.ui.graphics.SolidColor(ProomColor.accent),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(ProomShape.sm))
                            .background(ProomColor.surface)
                            .padding(horizontal = 12.dp, vertical = 11.dp),
                        decorationBox = { inner ->
                            Box {
                                if (subText.isEmpty()) {
                                    Text("https://…", style = ProomType.meta, color = ProomColor.muted)
                                }
                                inner()
                            }
                        },
                    )
                    Row(
                        Modifier.fillMaxWidth().padding(top = 14.dp),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        Text(
                            "取消",
                            style = ProomType.cardTitle,
                            color = ProomColor.muted,
                            modifier = Modifier
                                .clip(RoundedCornerShape(ProomShape.sm))
                                .clickable { addSubOpen = false }
                                .padding(horizontal = 14.dp, vertical = 8.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "添加",
                            style = ProomType.cardTitle,
                            color = if (subText.isBlank()) ProomColor.muted else ProomColor.accent,
                            modifier = Modifier
                                .clip(RoundedCornerShape(ProomShape.sm))
                                .then(
                                    if (subText.isBlank()) Modifier
                                    else Modifier.clickable {
                                        val u = subText.trim()
                                        addSubOpen = false
                                        subText = ""
                                        onAddSub(u)
                                    }
                                )
                                .padding(horizontal = 14.dp, vertical = 8.dp),
                        )
                    }
                }
            }
        }

        // 操作结果固定在底部一行：放在列表末尾的话，屏幕不够长时它就在折叠线以下，
        // 点了「检查更新」看起来像没反应
        state.message?.let { msg ->
            Box(
                Modifier
                    .fillMaxWidth()
                    .background(ProomColor.bgSoft)
                    .padding(horizontal = 20.dp, vertical = 12.dp)
            ) {
                Text(msg, style = ProomType.label, color = ProomColor.accent)
            }
        }
    }
}

/**
 * 版本串可能是 12 这种序号，也可能是 a5630… 这种 hash。
 * 只给数字版本补 v；hash 原样显示，否则会拼出「va5630」这种四不像。
 */
private fun String.withV(): String = when {
    isEmpty() -> this
    startsWith("v", ignoreCase = true) -> this
    first().isDigit() -> "v$this"
    else -> this
}

/* ───────────────────────── 零件 ───────────────────────── */

@Composable
private fun GroupTitle(text: String) {
    Text(
        text,
        style = ProomType.label,
        color = ProomColor.muted,
        modifier = Modifier.padding(top = ProomSpace.lg, bottom = ProomSpace.xs, start = 4.dp),
    )
}

/** 一组设置：圆角卡片，组内用 1px 线分隔 */
@Composable
private fun Card(content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(ProomShape.sm))
            .background(ProomColor.surface)
    ) {
        content()
    }
}

@Composable
private fun Divider() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(ProomColor.line))
}

/**
 * 播放偏好。直接读写 [PlaybackPrefs]，不经过壳层回传 ——
 * 它是个全局单例，播放页读的是同一个值，改完立刻就生效。
 *
 * 遥控器上左右键在两个 chip 之间移动，OK 选中。
 */
@Composable
private fun PlaybackSetting() {
    val ctx = LocalContext.current
    Column(Modifier.padding(horizontal = ProomSpace.md, vertical = 12.dp)) {
        Text("双击快进", style = ProomType.cardTitle, color = ProomColor.text)
        Text(
            "播放页双击左右半屏快退/快进的秒数；遥控器方向键也用这个步长",
            style = ProomType.label,
            color = ProomColor.muted,
            modifier = Modifier.padding(top = 2.dp, bottom = 10.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PlaybackPrefs.steps.forEach { ms ->
                SeekChip(
                    label = PlaybackPrefs.label(ms),
                    on = PlaybackPrefs.doubleTapMs == ms,
                    onClick = { PlaybackPrefs.setDoubleTapMs(ctx, ms) },
                )
            }
        }

        // 新剧的默认值；某部剧在播放页单独调过之后就跟那部剧走
        Text(
            "跳过片头",
            style = ProomType.cardTitle,
            color = ProomColor.text,
            modifier = Modifier.padding(top = 22.dp),
        )
        Text(
            "播放到片头区间时右下角浮出按钮，按 OK 才跳。已单独设过的剧不受这里影响",
            style = ProomType.label,
            color = ProomColor.muted,
            modifier = Modifier.padding(top = 2.dp, bottom = 10.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SkipRule.introOptions.forEach { ms ->
                SeekChip(
                    label = if (ms == 0L) "关" else "${ms / 1000}",
                    on = PlaybackPrefs.introMs == ms,
                    onClick = { PlaybackPrefs.setIntroMs(ctx, ms) },
                )
            }
        }

        Text(
            "片尾提示",
            style = ProomType.cardTitle,
            color = ProomColor.text,
            modifier = Modifier.padding(top = 22.dp),
        )
        Text(
            "最后这段时间里允许按「下一集」，设「关」就不提示",
            style = ProomType.label,
            color = ProomColor.muted,
            modifier = Modifier.padding(top = 2.dp, bottom = 10.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SkipRule.outroOptions.forEach { ms ->
                SeekChip(
                    label = if (ms == 0L) "关" else "${ms / 1000}",
                    on = PlaybackPrefs.outroMs == ms,
                    onClick = { PlaybackPrefs.setOutroMs(ctx, ms) },
                )
            }
        }
    }
}

@Composable
private fun RowScope.SeekChip(label: String, on: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(ProomShape.sm)
    var focused by remember { mutableStateOf(false) }
    Box(
        Modifier
            .weight(1f)
            .onFocusChanged { focused = it.isFocused }
            .clip(shape)
            .background(if (on) ProomColor.accentSoft else ProomColor.bgSoft)
            .then(if (focused) Modifier.border(2.dp, ProomColor.accent, shape) else Modifier)
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = ProomType.label,
            color = if (on) ProomColor.accent else ProomColor.text,
        )
    }
}

/**
 * 一行设置：标题 + 值 + 说明 + 右侧动作。
 *
 * 说明文字放第二行小字而不是塞进 value —— value 那一列是给「当前值」的，
 * 混进解释性文字就没法一眼扫出「现在的状态是什么」。
 */
@Composable
private fun SettingRow(
    title: String,
    value: String,
    hint: String = "",
    action: String? = null,
    enabled: Boolean = true,
    focusRequester: FocusRequester? = null,
    onClick: (() -> Unit)? = null,
) {
    val shape = RoundedCornerShape(ProomShape.sm)
    var focused by remember { mutableStateOf(false) }
    val actionable = onClick != null && enabled
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onFocusChanged { focused = it.isFocused }
            // 整行宽度接近满屏，不能像小控件那样放大（会顶出安全区），
            // 电视上的替代方案是「底色提亮 + 强调色描边」
            .clip(shape)
            .background(if (focused) ProomColor.bgSoft else Color.Transparent)
            .then(if (focused) Modifier.border(2.dp, ProomColor.accent, shape) else Modifier)
            .then(
                if (actionable) Modifier.clickable(onClick = onClick!!) else Modifier
            )
            .padding(horizontal = ProomSpace.md, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = ProomType.cardTitle, color = ProomColor.text)
                if (value.isNotBlank()) {
                    Text(
                        value,
                        style = ProomType.label,
                        color = if (action != null) ProomColor.muted else ProomColor.dim,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = 10.dp),
                    )
                }
            }
            if (hint.isNotBlank()) {
                Text(
                    hint,
                    style = ProomType.label,
                    color = ProomColor.muted,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 3.dp),
                )
            }
        }
        if (action != null) {
            Text(
                action,
                style = ProomType.label,
                color = if (enabled) ProomColor.accent else ProomColor.muted,
                maxLines = 1,
                modifier = Modifier.padding(start = 12.dp),
            )
        }
    }
}
