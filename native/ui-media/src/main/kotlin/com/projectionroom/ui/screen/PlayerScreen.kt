package com.projectionroom.ui.screen

import android.app.Activity
import android.app.PictureInPictureParams
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.ActivityInfo
import android.media.AudioManager
import android.os.Build
import android.provider.Settings
import android.util.Rational
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.border
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.launch
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.projectionroom.shared.model.Vod
import com.projectionroom.ui.component.BufferDots
import com.projectionroom.ui.component.PlayerIcons
import com.projectionroom.ui.component.TabIcons
import com.projectionroom.ui.theme.ProomColor
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/**
 * 播放页：Media3 硬解 + 自绘控制层。
 *
 * 不用 `PlayerView` 那套系统控制器 —— 它在横屏下会把按钮排成一竖条、
 * 还没有倍速/线路入口。这里照主流播放器（B 站/爱奇艺）的形状重画：
 *
 * ```
 * 顶部：← 标题 / 第 N 集 · 线路            画中画 投屏
 * 中间：左半屏上下滑 = 亮度，右半屏上下滑 = 音量；右侧锁屏
 * 底部：进度条  →  时间 · 上一集/播放/下一集 · 倍速/线路
 * ```
 *
 * 另外三件事在这里闭环（都是 Web 版打磨过的体验）：
 *  1. 断点续播：按线路 URL 记进度，重进同一集从上次位置开始；
 *  2. 一集播完自动接下一集，最后一集才停下；
 *  3. 缓冲用波点而不是转圈（关掉系统 spinner）。
 */
/** 采集站挑 UA，空 UA 直接 403；和 OkHttpHttp 保持一致 */
private const val PLAYER_UA =
    "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) " +
        "Chrome/120.0 Mobile Safari/537.36"

/** 进程内的初始朝向：进播放器前记一次，退出时还原到它 */
private object OrientationMemory {
    var initial: Int? = null
}

private fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}

/** 屏幕亮度：0.05~1，退出播放器要还原，否则整个 App 会一直暗着 */
private fun applyBrightness(activity: Activity?, v: Float) {
    val w = activity?.window ?: return
    val lp = w.attributes
    lp.screenBrightness = v.coerceIn(0.05f, 1f)
    w.attributes = lp
}

private fun currentBrightness(activity: Activity?): Float {
    val v = activity?.window?.attributes?.screenBrightness ?: -1f
    // -1 表示跟随系统，取个中间偏亮的起点
    return if (v < 0f) 0.7f else v
}

private fun audioManager(context: Context): AudioManager =
    context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

private fun currentVolume(context: Context): Float {
    val am = audioManager(context)
    val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
    return am.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / max
}

private fun applyVolume(context: Context, v: Float) {
    val am = audioManager(context)
    val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
    runCatching {
        am.setStreamVolume(AudioManager.STREAM_MUSIC, (v * max).roundToInt(), 0)
    }
}

private fun formatTime(ms: Long): String {
    if (ms <= 0) return "00:00"
    val total = ms / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) {
        "%d:%02d:%02d".format(h, m, s)
    } else {
        "%02d:%02d".format(m, s)
    }
}

private enum class Panel { None, Speed, Line }

@Composable
fun PlayerScreen(
    vod: Vod,
    startLine: Int = 0,
    startEp: Int = 0,
    rotateLandscape: Boolean = false,
    /**
     * 直播模式。
     *
     * 直播和点播不是同一种东西，硬套同一套控制层会出现一堆假按钮：
     * 没有时长却画进度条、拖了没反应；双击快进退会把你拽回直播边缘；
     * 倍速对直播流没意义；「选集」只有一集；断点续播会把上一次的位置
     * 当成「看到了这里」，一进去就卡在黑屏。
     * 所以直播走同一套壳、但只保留真正有效的控件。
     */
    live: Boolean = false,
    /**
     * 直播每一台的备用源（和 episodes 一一对应）。
     *
     * 采集站一个台常常给 3~4 条地址，某条挂了要能自动跳下一条 ——
     * 点播是靠「多线路」做的，直播里线路维度已经被「换台」占了，
     * 所以单独传一张表：下标 = 台号，值是这一台的所有地址。
     */
    liveUrls: List<List<String>> = emptyList(),
    /** 把源站直链转成可播地址（壳会传 NAS 代理，本地预览就用原样） */
    resolveUrl: (String) -> String = { it },
    /** 退出/换集时上报进度：壳用它写本地记录库（离线也能记） */
    onProgress: (episode: String, positionMs: Long, durationMs: Long) -> Unit = { _, _, _ -> },
    /**
     * 直播：当前这一台是否已收藏 / 切换收藏。
     *
     * 传的是「按台名」的回调而不是一个 Boolean：换台是在播放器内部发生的，
     * 壳不知道现在停在哪一台，收藏状态必须按当前的台名现查。
     */
    liveFavOf: (name: String) -> Boolean = { false },
    onToggleLiveFav: (name: String) -> Unit = {},
    /** 电视端：收掉锁/画中画/投屏这三个对电视无意义的按钮 */
    isTv: Boolean = false,
    /** 联调用：进播放页就展开投屏面板（只在 debuggable 包由壳开启） */
    openCastOnEnter: Boolean = false,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val prefs = remember { context.getSharedPreferences("proom_progress", Context.MODE_PRIVATE) }

    // 手机竖屏看 16:9 会上下两条大黑边，进播放页转横屏，退出还原。
    // 注意：不能记「进播放器那一刻」的朝向——如果上次异常退出留下了横屏，
    // 记下来的就是横屏，回来永远转不回竖屏。只在**进程内第一次**进播放器时记。
    DisposableEffect(rotateLandscape) {
        val act = context.findActivity()
        if (OrientationMemory.initial == null) OrientationMemory.initial = act?.requestedOrientation
        if (rotateLandscape && act != null) {
            act.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
        onDispose {
            // 手机上直接锁回竖屏：比"还原 UNSPECIFIED"确定得多——
            // 系统旋转被锁住时 UNSPECIFIED 不会自己转回来，界面就卡在横屏
            if (rotateLandscape) act?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            // 退出播放器把亮度交回系统，不然整个 App 会一直停在播放时调暗的值
            runCatching {
                val w = (act ?: activity)?.window ?: return@runCatching
                val lp = w.attributes
                lp.screenBrightness = android.view.WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                w.attributes = lp
            }
        }
    }

    var lineIndex by remember { mutableStateOf(startLine.coerceIn(0, (vod.lines.size - 1).coerceAtLeast(0))) }
    var epIndex by remember { mutableStateOf(startEp) }
    val line = vod.lines.getOrNull(lineIndex)
    val episode = line?.episodes?.getOrNull(epIndex)
    // 直播：这一台的所有源；换台时回到第一个源
    val sources = remember(epIndex, liveUrls) {
        liveUrls.getOrNull(epIndex).orEmpty().ifEmpty { listOfNotNull(episode?.url) }
    }
    var srcIndex by remember(epIndex) { mutableStateOf(0) }
    val playUrl = if (live) sources.getOrNull(srcIndex) ?: episode?.url else episode?.url
    val exo = remember { ExoPlayer.Builder(context).build() }
    var buffering by remember { mutableStateOf(true) }
    var playbackError by remember(playUrl) { mutableStateOf<String?>(null) }
    var failedLines by remember { mutableStateOf(0) }

    // ── 控制层状态 ──
    var controlsVisible by remember { mutableStateOf(true) }
    var locked by remember { mutableStateOf(false) }
    // 直播收藏：换台就重新查一次（初始值也要查，不能默认 false）
    var liveFav by remember(epIndex) {
        mutableStateOf(
            if (live) liveFavOf(vod.lines.getOrNull(lineIndex)?.episodes?.getOrNull(epIndex)?.name.orEmpty())
            else false
        )
    }
    var panel by remember { mutableStateOf(Panel.None) }
    // 右侧设置抽屉：线路 + 选集都放这儿，播放器上不再挂一排按钮
    var sidePanel by remember { mutableStateOf(false) }
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var speed by remember { mutableFloatStateOf(1f) }
    var hint by remember { mutableStateOf<String?>(null) }

    // ── 投屏（DLNA）──
    // 设备发现、SOAP 控制在 shared 的 DlnaClient 里；这里只管界面状态
    val dlna = remember { com.projectionroom.shared.cast.DlnaClient() }
    val castScope = rememberCoroutineScope()
    var castOpen by remember { mutableStateOf(false) }
    var castScanning by remember { mutableStateOf(false) }
    var castDevices by remember { mutableStateOf<List<com.projectionroom.shared.cast.CastDevice>>(emptyList()) }
    var castTarget by remember { mutableStateOf<com.projectionroom.shared.cast.CastDevice?>(null) }
    // 电视那边当前是不是在播：手机当遥控器时要靠它决定发 pause 还是 play
    var casting by remember { mutableStateOf(false) }
    fun scanCast() {
        if (castScanning) return
        castScope.launch {
            castScanning = true
            castDevices = runCatching { dlna.discover() }.getOrDefault(emptyList())
            castScanning = false
        }
    }

    // 联调入口：横屏播放器里 adb 的点击坐标经常对不上（见顶栏那段注释），
    // 投屏面板点不开就等于没法验版式。壳用 `-e screen cast` 置这个开关，
    // 进播放页就展开面板并顺手搜一次。
    LaunchedEffect(openCastOnEnter) {
        if (openCastOnEnter) {
            castOpen = true
            scanCast()
        }
    }

    /** 把当前地址推到选中的接收端：手机退回遥控器 */
    fun doCast(d: com.projectionroom.shared.cast.CastDevice) {
        castOpen = false
        castScope.launch {
            val r = dlna.cast(d, playUrl.orEmpty(), vod.name)
            if (r.isSuccess) {
                castTarget = d
                casting = true
                // 本机暂停，声音只从电视出
                exo.pause()
                hint = "已投屏到 ${d.name}"
            } else {
                hint = "投屏失败：${r.exceptionOrNull()?.message ?: "电视没响应"}"
            }
        }
    }

    /**
     * 手动地址投屏。
     *
     * 电视不答 SSDP 的情况真实存在：路由开了 AP 隔离、电视和手机在
     * 不同 VLAN、老电视的 SSDP 只回广播不回组播。这时候填一句
     * `192.168.1.8:49520` 就能连上，比「找不到设备」干瞪眼强。
     */
    fun castManual(raw: String) {
        val addr = raw.trim()
        if (addr.isBlank()) return
        val loc = when {
            addr.startsWith("http://") || addr.startsWith("https://") ->
                if (addr.endsWith(".xml")) addr else addr.trimEnd('/') + "/description.xml"
            else -> "http://" + addr.trimEnd('/') + "/description.xml"
        }
        castScope.launch {
            castScanning = true
            val d = dlna.describe(loc)
            castScanning = false
            when {
                d == null -> hint = "连不上 $addr"
                !d.canCast -> hint = "这台设备不支持投屏"
                else -> doCast(d)
            }
        }
    }
    // 拖动时屏幕两侧的竖条（亮度在左、音量在右），比中间一个小胶囊直观
    var gauge by remember { mutableStateOf<Gauge?>(null) }
    val brightness = remember { mutableFloatStateOf(currentBrightness(activity)) }
    val volume = remember { mutableFloatStateOf(currentVolume(context)) }

    // 换集/换线路：重新准备，有进度就从上次位置续播
    DisposableEffect(playUrl) {
        val url = playUrl
        if (!url.isNullOrBlank()) {
            // 直播不读续播点：上次记的「位置」对直播流没有意义，
            // seek 到那儿只会得到一个黑屏或者直接跳回直播边缘。
            val saved = if (live) 0L else prefs.getLong(url, 0L)
            // 走代理后路径是 /api/proxy?u=…，没有 .m3u8 后缀，
            // ExoPlayer 只能靠 MIME 判断是 HLS —— 不显式指定会当普通文件解析，永远起不来。
            val isHls = url.contains(".m3u8", ignoreCase = true)
            val item = MediaItem.Builder()
                .setUri(resolveUrl(url))
                .apply { if (isHls) setMimeType(androidx.media3.common.MimeTypes.APPLICATION_M3U8) }
                .build()
            // 直连源站：采集站的 m3u8 大多校验 Referer/UA，不带头会 403。
            // 以前靠 NAS 的 /api/proxy 代补，现在播放器自己带 —— NAS 关机也能播。
            val origin = runCatching {
                val u = java.net.URI(url)
                "${u.scheme}://${u.host}/"
            }.getOrNull().orEmpty()
            val factory = DefaultHttpDataSource.Factory()
                .setAllowCrossProtocolRedirects(true)
                .setDefaultRequestProperties(
                    buildMap {
                        put("User-Agent", PLAYER_UA)
                        if (origin.isNotBlank()) put("Referer", origin)
                    }
                )
            val source = DefaultMediaSourceFactory(factory).createMediaSource(item)
            exo.setMediaSource(source, if (saved > 5_000L) saved else 0L)
            exo.prepare()
            exo.playWhenReady = true
        }
        onDispose {
            // 直播不记进度、不写观看记录：看完一个台不该出现在「继续观看」里
            if (!live && !url.isNullOrBlank() && exo.currentPosition > 5_000L) {
                // 这张表是「每个 URL 一条」，看几百集后会有几千条；
                // XML 越大，每次 apply 序列化和下次启动加载都越慢 —— 到阈值直接清空重来
                if (prefs.all.size > 400) prefs.edit().clear().apply()
                prefs.edit().putLong(url, exo.currentPosition).apply()
                // 顺手喂给本地记录库：NAS 关机也记得看到哪了
                runCatching {
                    onProgress(episode?.name.orEmpty(), exo.currentPosition, exo.duration)
                }
            }
        }
    }

    // 监听器只挂一次：之前 keyed on `line`，换线时 onDispose 会 release 播放器，
    // 后面再用同一个已销毁的实例 prepare，结果就是死循环重试旧地址。
    val curLineIndex by rememberUpdatedState(lineIndex)
    val curEps by rememberUpdatedState(line?.episodes.orEmpty())
    val curSrcs by rememberUpdatedState(sources)
    val curSrc by rememberUpdatedState(srcIndex)

    DisposableEffect(exo) {
        val listener = object : Player.Listener {
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                buffering = false
                // 同一个台的备用源先试：采集站一个台常给 3~4 条地址，
                // 第一条挂了不等于这个台看不了
                if (live && curSrc + 1 < curSrcs.size) {
                    srcIndex = curSrc + 1
                    playbackError = null
                    return
                }
                // 点播：这条线路挂了就自动换下一条（Web 版一直是这么做的）：
                // 采集站给的直链经常过期，用户不该自己一条条试
                if (!live && curLineIndex + 1 < vod.lines.size) {
                    failedLines += 1
                    lineIndex = curLineIndex + 1
                    epIndex = 0
                    playbackError = null
                } else {
                    playbackError = if (live) "这个台的源都播不动" else if (failedLines > 0) "所有线路都播不动" else "这条线路播不动"
                }
            }

            override fun onPlaybackStateChanged(state: Int) {
                buffering = state == Player.STATE_BUFFERING
                // 直播流结束多半是源断了，自动跳下一个台会让人莫名其妙
                if (state == Player.STATE_ENDED && !live) {
                    val eps = curEps
                    if (epIndex + 1 < eps.size) {
                        prefs.edit().remove(eps[epIndex].url).apply()
                        epIndex += 1
                    }
                }
            }
        }
        exo.addListener(listener)
        onDispose {
            exo.removeListener(listener)
            exo.release()
        }
    }

    // 每 5 秒记一次进度（直播不记）
    LaunchedEffect(episode?.url) {
        val url = episode?.url ?: return@LaunchedEffect
        if (live) return@LaunchedEffect
        while (true) {
            delay(5_000)
            if (exo.isPlaying) prefs.edit().putLong(url, exo.currentPosition).apply()
        }
    }

    // 控制层可见时才刷新进度：一屏静止时别每 300ms 唤醒一次
    LaunchedEffect(controlsVisible, locked, panel) {
        while (true) {
            position = exo.currentPosition
            duration = exo.duration.coerceAtLeast(0L)
            if (!controlsVisible && !locked) break
            delay(350)
        }
    }

    // 4 秒无操作自动收起（锁定时只留锁图标）
    // key 必须带上 sidePanel：否则打开抽屉时计时器还在跑，
    // 4 秒后照样把整个控制层（含抽屉）一起收掉。
    LaunchedEffect(controlsVisible, locked, panel, sidePanel, castOpen) {
        if (controlsVisible && !locked && panel == Panel.None && !sidePanel && !castOpen) {
            delay(5_000)
            controlsVisible = false
        }
    }

    LaunchedEffect(gauge) {
        if (gauge != null) {
            delay(1_200)
            gauge = null
        }
    }

    // 亮度/音量提示 1.2 秒后消失
    LaunchedEffect(hint) {
        if (hint != null) {
            delay(1_200)
            hint = null
        }
    }

    BackHandler(enabled = !locked) { onBack() }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                PlayerView(ctx).apply {
                    player = exo
                    useController = false          // 控制层自己画
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
                    keepScreenOn = true
                }
            },
        )

        // ── 上下滑：亮度（左半屏）/ 音量（右半屏）──
        // 这一层常驻，但**只认拖动**，不认点击 —— 点击照旧穿透到上层控制层，
        // 所以控制层展开时也能直接上下滑调节。
        if (!locked) {
            Box(
                Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        var base = 0f
                        // 必须累计每一帧的位移：`amount.y` 只是**这一帧**动了多少，
                        // 直接 `base + amount` 等于每帧都把进度重置回起点，
                        // 手指滑到底也只变几个百分点。
                        var acc = 0f
                        var isBrightness = true
                        detectDragGestures(
                            onDragStart = { off ->
                                isBrightness = off.x < size.width / 2f
                                base = if (isBrightness) brightness.floatValue else volume.floatValue
                                acc = 0f
                            },
                            onDrag = { change, amount ->
                                change.consume()
                                // 上滑变大：屏幕高度的 55% 走完全程，和主流播放器一致
                                acc += -amount.y / (size.height * 0.55f)
                                if (isBrightness) {
                                    brightness.floatValue = (base + acc).coerceIn(0.05f, 1f)
                                    applyBrightness(activity, brightness.floatValue)
                                    gauge = Gauge(isBrightness = true, value = brightness.floatValue)
                                    hint = "亮度 ${(brightness.floatValue * 100).roundToInt()}%"
                                } else {
                                    volume.floatValue = (base + acc).coerceIn(0f, 1f)
                                    applyVolume(context, volume.floatValue)
                                    gauge = Gauge(isBrightness = false, value = volume.floatValue)
                                    hint = "音量 ${(volume.floatValue * 100).roundToInt()}%"
                                }
                            },
                        )
                    }
                    // 点击/双击：只在控制层收起时接管。
                    // 必须挂在**同一个** Box 上 —— 拆成两个 Box 时，
                    // 上层这个只会收到 down，tap 全被下层吃掉。
                    .pointerInput(controlsVisible) {
                        if (controlsVisible) return@pointerInput
                        detectTapGestures(
                            onTap = { controlsVisible = true },
                            onDoubleTap = { off ->
                                // 直播没有「快进 10 秒」这回事
                                if (live) return@detectTapGestures
                                val half = size.width / 2f
                                val step = 10_000L
                                if (off.x < half) {
                                    exo.seekTo((exo.currentPosition - step).coerceAtLeast(0L))
                                    hint = "« 10 秒"
                                } else {
                                    val max = exo.duration.coerceAtLeast(0L)
                                    exo.seekTo((exo.currentPosition + step).let {
                                        if (max > 0) it.coerceAtMost(max) else it
                                    })
                                    hint = "10 秒 »"
                                }
                                position = exo.currentPosition
                            },
                        )
                    }
            )
        }

        // ── 左右两侧的音量/亮度竖条 ──
        gauge?.let { g ->
            SideGauge(
                icon = if (g.isBrightness) PlayerIcons.Brightness else PlayerIcons.Volume,
                value = g.value,
                modifier = Modifier
                    .align(if (g.isBrightness) Alignment.CenterStart else Alignment.CenterEnd)
                    .padding(horizontal = 32.dp),
            )
        }

        // ── 中间提示（快进/快退/投屏）──
        AnimatedVisibility(
            visible = hint != null,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.Center),
        ) {
            Box(
                Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color.Black.copy(alpha = 0.62f))
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Text(hint ?: "", color = Color.White, fontSize = 13.sp)
            }
        }

        // ── 缓冲：波点，别用系统转圈 ──
        AnimatedVisibility(
            visible = buffering && playbackError == null,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.Center),
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                BufferDots()
                Text(
                    text = if (live) {
                        "正在连接" + (line?.site?.let { " · $it" } ?: "")
                    } else {
                        line?.let { "第 ${epIndex + 1} 集 · ${it.site}" } ?: ""
                    },
                    color = Color.White.copy(alpha = 0.55f),
                    fontSize = 11.sp,
                    modifier = Modifier.padding(top = 10.dp),
                )
            }
        }

        playbackError?.let { msg ->
            Column(
                Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(msg, color = Color.White.copy(alpha = 0.75f), fontSize = 14.sp)
                Text(
                    if (live) "换个台，或者点右下角换一条线路" else "点左上角返回，换一条线路",
                    color = Color.White.copy(alpha = 0.45f),
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }

        // ── 控制层 ──
        // 这里**不能**套 zIndex / graphicsLayer / AnimatedVisibility：
        // 试过三种，横屏下顶栏那两个按钮的命中区域都会和绘制位置错开
        // （底栏能点、顶栏点不动）。直接条件渲染，位置和命中就是一致的。
        if (controlsVisible || locked) {
            Box(Modifier.fillMaxSize()) {
                if (!locked) {
                    PlayerTopBar(
                        // 直播时标题放到左下角：电视台的台标几乎都在左上角，
                        // 我们的标题压上去会和台标叠字（CCTV 那排尤其明显）
                        title = if (live) null else vod.name,
                        subtitle = if (live) "" else line?.let { "第 ${epIndex + 1} 集 · ${it.site}" }.orEmpty(),
                        locked = locked,
                        onToggleLock = {
                            locked = !locked
                            if (locked) controlsVisible = false
                        },
                        // 直播才有「收藏这个台」，点播的收藏在详情页
                        liveChannel = if (live) episode?.name.orEmpty() else null,
                        favOn = liveFav,
                        onToggleFav = {
                            val name = episode?.name.orEmpty()
                            if (name.isNotBlank()) {
                                onToggleLiveFav(name)
                                liveFav = liveFavOf(name)
                            }
                        },
                        onBack = onBack,
                        onPip = {
                            if (Build.VERSION.SDK_INT >= 26) {
                                runCatching {
                                    activity?.enterPictureInPictureMode(
                                        PictureInPictureParams.Builder()
                                            .setAspectRatio(Rational(16, 9))
                                            .build()
                                    )
                                }
                            }
                        },
                        onCast = {
                            castOpen = true
                            scanCast()
                        },
                        isTv = isTv,
                    )
                }

                // 锁定时顶栏整条都收起来了，得单独留一个锁图标，否则解不开
                // 电视端没有锁定入口（isTv 收掉了那个按钮），这块自然也不会出现
                if (locked && !isTv) {
                    LockButton(
                        locked = true,
                        modifier = Modifier.align(Alignment.CenterEnd).padding(end = 20.dp),
                        onToggle = {
                            locked = false
                            controlsVisible = true
                        },
                    )
                }

                if (!locked) {
                    PlayerBottomBar(
                        position = position,
                        duration = duration,
                        speed = speed,
                        live = live,
                        liveTitle = episode?.name.orEmpty(),
                        liveCounter = if (live) "${epIndex + 1} / ${line?.episodes?.size ?: 0} 台" else "",
                        hasPrev = epIndex > 0,
                        hasNext = epIndex + 1 < (line?.episodes?.size ?: 0),
                        isPlaying = exo.isPlaying,
                        modifier = Modifier.align(Alignment.BottomCenter),
                        onSeek = {
                            // 投屏中把进度也发给电视：本机在放的是同一路流，
                            // 只改本机会出现"手机跳了电视没动"的诡异状态
                            castTarget?.let { d ->
                                castScope.launch { dlna.seek(d, it / 1000) }
                            }
                            exo.seekTo(it)
                            position = it
                        },
                        onPrev = { if (epIndex > 0) { epIndex -= 1; controlsVisible = true } },
                        onNext = {
                            if (epIndex + 1 < (line?.episodes?.size ?: 0)) {
                                epIndex += 1
                                controlsVisible = true
                            }
                        },
                        onTogglePlay = {
                            val d = castTarget
                            if (d != null) {
                                // 投屏中：手机变成遥控器，本机保持暂停
                                val wasPlaying = casting
                                castScope.launch {
                                    if (wasPlaying) dlna.pause(d) else dlna.resume(d)
                                    casting = !wasPlaying
                                }
                            } else {
                                if (exo.isPlaying) exo.pause() else exo.play()
                            }
                        },
                        onSpeed = { panel = Panel.Speed },
                        onSettings = { sidePanel = true },
                    )
                }

                // 右侧设置抽屉。
                // 不做滑入动画、也不铺全屏遮罩：动画期间面板还在屏幕外，
                // 点击容易落到遮罩上把它自己关掉。这里直接挂在右侧，用 ✕ 关闭。
                if (sidePanel) {
                    SideSettingsPanel(
                        title = if (live) "换台 / 换源" else "播放设置",
                        lineTab = if (live) "线路" else "线路",
                        epTab = if (live) "频道" else "选集",
                        lines = if (live) {
                            // 直播：线路 = 当前这一台的几个备用源
                            sources.mapIndexed { i, _ -> "源 ${i + 1}" }
                        } else {
                            vod.lines.map { l ->
                                if (l.quality.isBlank()) l.site else "${l.site} · ${l.quality}"
                            }
                        },
                        selectedLine = if (live) srcIndex else lineIndex,
                        episodes = line?.episodes.orEmpty().map { it.name },
                        selectedEp = epIndex,
                        onSelectLine = {
                            if (live) {
                                srcIndex = it
                            } else if (it != lineIndex) {
                                lineIndex = it
                                epIndex = 0
                            }
                            sidePanel = false
                        },
                        onSelectEp = {
                            epIndex = it
                            sidePanel = false
                        },
                        onClose = { sidePanel = false },
                        modifier = Modifier.align(Alignment.CenterEnd),
                    )
                }

                // 倍速 / 线路面板
                if (panel != Panel.None) {
                    PlayerPanel(
                        title = if (panel == Panel.Speed) "倍速" else "播放线路",
                        speed = speed,
                        lines = vod.lines.map { l ->
                            if (l.quality.isBlank()) l.site else "${l.site} · ${l.quality}"
                        },
                        selectedLine = lineIndex,
                        modifier = Modifier.align(Alignment.BottomCenter),
                        onSpeed = {
                            speed = it
                            exo.setPlaybackSpeed(it)
                            panel = Panel.None
                        },
                        onLine = {
                            lineIndex = it
                            epIndex = 0
                            panel = Panel.None
                        },
                        onDismiss = { panel = Panel.None },
                    )
                }
            }
        }

        // ── 投屏面板 ──
        // 挂在控制层**外面**：控制层 5 秒自动收起时，面板不该跟着一起消失。
        if (castOpen) {
            CastSheet(
                devices = castDevices,
                scanning = castScanning,
                current = castTarget?.name.orEmpty(),
                casting = casting,
                onRescan = { scanCast() },
                onPick = { d -> doCast(d) },
                onManual = { addr -> castManual(addr) },
                onStop = {
                    castTarget?.let { d -> castScope.launch { dlna.stop(d) } }
                    castTarget = null
                    casting = false
                    castOpen = false
                    hint = "已停止投屏"
                },
                onClose = { castOpen = false },
            )
        }
    }
}

/* ─────────────────────── 顶栏 ─────────────────────── */

@Composable
private fun PlayerTopBar(
    title: String?,
    subtitle: String,
    locked: Boolean,
    /** 非空表示直播：右上角多一个收藏这个台的按钮 */
    liveChannel: String? = null,
    favOn: Boolean = false,
    onToggleFav: () -> Unit = {},
    onToggleLock: () -> Unit,
    onBack: () -> Unit,
    onPip: () -> Unit,
    onCast: () -> Unit,
    /**
     * 电视端：右上角那三个（锁 / 画中画 / 投屏）全部不画。
     *
     * 电视是**接收端**，投屏按钮点了只能投自己；画中画在电视上系统不支持；
     * 锁屏是为了防手指误触，而电视根本没有触摸。留着就是三个点不动的假按钮
     * —— 遥控器一按过去就卡在那里，比没有更糟。
     */
    isTv: Boolean = false,
) {
    // 电视端：顶栏不再另写一套 —— 直接复用其它页的 TvPageHeader（返回提示 + 片名），
    // 位置、字号、间距、高度全部单点定义，避免播放页又长出一个 40dp 圆钮。
    if (isTv) {
        Column(
            Modifier
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Black.copy(alpha = 0.62f), Color.Transparent)
                    )
                )
                .statusBarsPadding()
                .padding(bottom = 14.dp),
        ) {
            com.projectionroom.ui.component.TvPageHeader(
                onBack = onBack,
                title = listOfNotNull(title, subtitle.takeIf { it.isNotBlank() })
                    .joinToString(" · ")
                    .ifBlank { null },
                // 视频画面不是暗底：文字用亮色，再垫一层半透明底，
                // 否则直播左上角的台标会和「返回」重叠到看不清（CCTV 那排尤其明显）
                hintColor = Color.White.copy(alpha = 0.92f),
                hintBackdrop = Color.Black.copy(alpha = 0.45f),
                trailing = {
                    if (!liveChannel.isNullOrBlank()) {
                        Box(
                            Modifier.size(40.dp).clip(CircleShape).clickable(onClick = onToggleFav),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = if (favOn) TabIcons.HeartOn else TabIcons.Heart,
                                contentDescription = if (favOn) "取消收藏这个台" else "收藏这个台",
                                tint = if (favOn) ProomColor.accent else Color.White,
                                modifier = Modifier.size(21.dp),
                            )
                        }
                    }
                },
            )
        }
        return
    }

    Row(
        Modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    listOf(Color.Black.copy(alpha = 0.62f), Color.Transparent)
                )
            )
            .statusBarsPadding()
            .padding(start = 4.dp, end = 8.dp, top = 6.dp, bottom = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircleIcon(com.projectionroom.ui.component.TabIcons.Back, "返回", onBack)
        if (title.isNullOrBlank()) {
            // 直播：左上空出来给台标，标题挪到左下角
            Spacer(Modifier.weight(1f))
        } else {
            Column(
                Modifier
                    .weight(1f)
                    .padding(start = 6.dp)
            ) {
                Text(
                    title,
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (subtitle.isNotBlank()) {
                    Text(
                        subtitle,
                        color = Color.White.copy(alpha = 0.55f),
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
        }
        // 锁和画中画/投屏并排放在右上：之前挂在屏幕右中，横屏下
        // 和手势/内容层叠在一起，点起来很飘
        if (!liveChannel.isNullOrBlank()) {
            Box(
                Modifier.size(40.dp).clip(CircleShape).clickable(onClick = onToggleFav),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = if (favOn) TabIcons.HeartOn else TabIcons.Heart,
                    contentDescription = if (favOn) "取消收藏这个台" else "收藏这个台",
                    tint = if (favOn) ProomColor.accent else Color.White,
                    modifier = Modifier.size(21.dp),
                )
            }
        }
        // 锁和画中画/投屏并排放在右上：之前挂在屏幕右中，横屏下
        // 和手势/内容层叠在一起，点起来很飘。
        // 这里只剩手机端：电视端在上面已经提前 return 走了 TvPageHeader。
        CircleIcon(
            if (locked) PlayerIcons.Lock else PlayerIcons.Unlock,
            if (locked) "解锁" else "锁定",
            onToggleLock,
        )
        CircleIcon(PlayerIcons.Pip, "画中画", onPip)
        CircleIcon(PlayerIcons.Cast, "投屏", onCast)
    }
}

/* ─────────────────────── 底栏 ─────────────────────── */

@Composable
private fun PlayerBottomBar(
    position: Long,
    duration: Long,
    speed: Float,
    live: Boolean,
    /** 直播：台名与「第几台 / 共几台」 */
    liveTitle: String = "",
    liveCounter: String = "",
    hasPrev: Boolean,
    hasNext: Boolean,
    isPlaying: Boolean,
    modifier: Modifier = Modifier,
    onSeek: (Long) -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onTogglePlay: () -> Unit,
    onSpeed: () -> Unit,
    onSettings: () -> Unit,
) {
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(0f) }
    val ratio = if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f

    Column(
        modifier
            .fillMaxWidth()
            .background(
                // 三段式而不是两端直落：台名/时间贴着下沿，只给 0.72 的黑
                // 在明亮的雪景、白墙镜头上会糊成一片看不清
                Brush.verticalGradient(
                    0f to Color.Transparent,
                    0.42f to Color.Black.copy(alpha = 0.45f),
                    1f to Color.Black.copy(alpha = 0.85f),
                )
            )
            .navigationBarsPadding()
            .padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 10.dp),
    ) {
        if (live) {
            Column(Modifier.padding(bottom = 10.dp)) {
                Text(
                    liveTitle,
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(
                    Modifier.padding(top = 3.dp).height(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // 金色而不是红点：整套界面只有暖金一个强调色，
                    // 红色在这里是「出错/危险」的语义，会和播放失败混淆
                    Box(
                        Modifier.size(5.dp).clip(CircleShape).background(ProomColor.accent)
                    )
                    Text(
                        if (liveCounter.isBlank()) "直播中" else "直播中 · $liveCounter",
                        color = Color.White.copy(alpha = 0.62f),
                        fontSize = 11.sp,
                        modifier = Modifier.padding(start = 6.dp),
                    )
                }
            }
        } else {
            // 自绘进度条，不用 material Slider：
            // Slider 的触摸区会被 `minimumInteractiveComponentSize` 扩到 48dp 高，
            // 正好盖住下面那一行按钮，导致「倍速 / 线路」怎么点都没反应。
            PlayerSeekBar(
                position = position,
                duration = duration,
                modifier = Modifier.fillMaxWidth(),
                onProgress = {
                    dragging = true
                    dragValue = it
                },
                onSeek = {
                    dragging = false
                    onSeek(it)
                },
            )
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            if (!live) {
                Text(
                    text = "${formatTime(if (dragging) (dragValue * duration).toLong() else position)} / ${formatTime(duration)}",
                    color = Color.White.copy(alpha = 0.72f),
                    fontSize = 11.sp,
                )
            }

            Spacer(Modifier.weight(1f))

            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircleIcon(
                    PlayerIcons.Prev, if (live) "上一台" else "上一集", onPrev,
                    enabled = hasPrev,
                )
                // 主按钮：白色实心圆，比两侧大一号
                Box(
                    Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.92f))
                        .clickable(onClick = onTogglePlay),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = if (isPlaying) PlayerIcons.Pause else PlayerIcons.Play,
                        contentDescription = if (isPlaying) "暂停" else "播放",
                        tint = Color.Black,
                        modifier = Modifier.size(22.dp),
                    )
                }
                CircleIcon(
                    PlayerIcons.Next, if (live) "下一台" else "下一集", onNext,
                    enabled = hasNext,
                )
            }

            Spacer(Modifier.weight(1f))

            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // 倍速对直播流没有意义（只会让音频越拖越远），直接不出现
                if (!live) {
                    CircleTextChip(if (speed == 1f) "1×" else "${speed}×", onSpeed)
                }
                CircleIcon(
                    PlayerIcons.More,
                    if (live) "换源" else "选集与线路",
                    onSettings,
                )
            }
        }
    }
}

/**
 * 自绘进度条：2.5dp 的白线 + 拖拽时放大圆点。
 *
 * 触摸区域只有 24dp 高，不会像 material Slider 那样按 48dp 最小触控尺寸
 * 去挤压旁边的按钮。
 */
@Composable
private fun PlayerSeekBar(
    position: Long,
    duration: Long,
    modifier: Modifier = Modifier,
    onProgress: (Float) -> Unit,
    onSeek: (Long) -> Unit,
) {
    var ratio by remember { mutableFloatStateOf(-1f) }
    var dragging by remember { mutableStateOf(false) }
    val base = ratio.takeIf { it >= 0f }
        ?: (if (duration > 0) (position.toFloat() / duration) else 0f).coerceIn(0f, 1f)
    val shown = if (dragging) ratio.coerceIn(0f, 1f) else base

    Canvas(
        modifier
            .height(24.dp)
            .pointerInput(duration) {
                detectTapGestures { off ->
                    if (duration <= 0) return@detectTapGestures
                    val r = (off.x / size.width).coerceIn(0f, 1f)
                    ratio = r
                    onSeek((r * duration).toLong())
                    ratio = -1f
                }
            }
            .pointerInput(duration) {
                detectHorizontalDragGestures(
                    onDragStart = { off ->
                        dragging = true
                        ratio = (off.x / size.width).coerceIn(0f, 1f)
                        onProgress(ratio)
                    },
                    onDragEnd = {
                        dragging = false
                        if (duration > 0) onSeek((ratio * duration).toLong())
                        ratio = -1f
                    },
                    onHorizontalDrag = { change, _ ->
                        change.consume()
                        ratio = (change.position.x / size.width).coerceIn(0f, 1f)
                        onProgress(ratio)
                    },
                )
            },
    ) {
        val cy = size.height / 2f
        val trackH = 2.5.dp.toPx()
        drawLine(
            color = Color.White.copy(alpha = 0.28f),
            start = androidx.compose.ui.geometry.Offset(0f, cy),
            end = androidx.compose.ui.geometry.Offset(size.width, cy),
            strokeWidth = trackH,
            cap = androidx.compose.ui.graphics.StrokeCap.Round,
        )
        drawLine(
            color = Color.White,
            start = androidx.compose.ui.geometry.Offset(0f, cy),
            end = androidx.compose.ui.geometry.Offset(size.width * shown, cy),
            strokeWidth = trackH,
            cap = androidx.compose.ui.graphics.StrokeCap.Round,
        )
        drawCircle(
            color = Color.White,
            radius = (if (dragging) 7f else 5f).dp.toPx(),
            center = androidx.compose.ui.geometry.Offset(size.width * shown, cy),
        )
    }
}

/** 倍速的小圆钮：文字塞进 48dp 圆形热区，比纯文字 chip 好点 */
@Composable
private fun CircleTextChip(text: String, onClick: () -> Unit) {
    Box(
        Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.12f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = Color.White.copy(alpha = 0.9f), fontSize = 12.sp, maxLines = 1)
    }
}

/** 拖动亮度/音量时贴在屏幕左侧或右侧的竖条 */
@Composable
private fun SideGauge(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    value: Float,
    modifier: Modifier = Modifier,
) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        // 图标给个圆形底：纯白线条压在亮的画面上经常看不见
        Box(
            Modifier
                .size(34.dp)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.38f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(19.dp),
            )
        }
        Spacer(Modifier.height(10.dp))
        Box(
            Modifier
                .width(3.dp)
                .height(110.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(Color.White.copy(alpha = 0.22f)),
        ) {
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .fillMaxHeight(value.coerceIn(0f, 1f))
                    .background(Color.White)
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "${(value.coerceIn(0f, 1f) * 100).roundToInt()}",
            color = Color.White.copy(alpha = 0.75f),
            fontSize = 11.sp,
        )
    }
}

/** 拖动中的亮度/音量状态 */
private data class Gauge(val isBrightness: Boolean, val value: Float)

/**
 * 右侧「播放设置」抽屉：线路 + 选集。
 *
 * 播放器上不再挂一排按钮，全部收进这里 —— 手机上单手够得着，
 * 集数多的时候也是一列网格，比底部横滑好找。
 */
@Composable
private fun SideSettingsPanel(
    title: String = "播放设置",
    lineTab: String = "线路",
    epTab: String = "选集",
    lines: List<String>,
    selectedLine: Int,
    episodes: List<String>,
    selectedEp: Int,
    onSelectLine: (Int) -> Unit,
    onSelectEp: (Int) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .width(322.dp)
            .fillMaxHeight()
            .background(Color.Black.copy(alpha = 0.94f))
            // 横屏时状态栏还在顶上：不加这个，标题和 ✕ 会顶进状态栏里
            // （截图里 ✕ 和电池图标叠在一起）
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(start = 18.dp, end = 14.dp, top = 4.dp, bottom = 10.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().height(48.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                title,
                color = Color.White,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.weight(1f))
            Box(
                Modifier.size(34.dp).clip(CircleShape).clickable(onClick = onClose),
                contentAlignment = Alignment.Center,
            ) {
                Text("✕", color = Color.White.copy(alpha = 0.65f), fontSize = 15.sp)
            }
        }

        // 分两个 tab：线路动辄 20~30 条，和选集堆在一列里，
        // 选集会被顶到很下面，每次换集都要滑半天。
        // 直播只有线路这一个维度，就直接铺线路，不摆一个单 tab。
        var tab by remember { mutableStateOf(0) }
        if (episodes.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().padding(bottom = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SideTab("$lineTab ${lines.size}", tab == 0, Modifier.weight(1f)) { tab = 0 }
                SideTab("$epTab ${episodes.size}", tab == 1, Modifier.weight(1f)) { tab = 1 }
            }
        }

        LazyColumn(Modifier.weight(1f)) {
            if (tab == 0) {
            itemsIndexed(lines.chunked(2)) { rowIdx, row ->
                Row(
                    Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    row.forEachIndexed { i, label ->
                        val idx = rowIdx * 2 + i
                        SideChip(label, idx == selectedLine, Modifier.weight(1f)) {
                            onSelectLine(idx)
                        }
                    }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }

            } else {
            itemsIndexed(episodes.chunked(4)) { rowIdx, row ->
                Row(
                    Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    row.forEachIndexed { i, name ->
                        val idx = rowIdx * 4 + i
                        SideChip(name, idx == selectedEp, Modifier.weight(1f)) {
                            onSelectEp(idx)
                        }
                    }
                    repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
            }
        }
    }
}

@Composable
private fun SideTab(text: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .height(34.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) Color.White.copy(alpha = 0.16f) else Color.Transparent)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            color = if (selected) Color.White else Color.White.copy(alpha = 0.5f),
            fontSize = 12.sp,
        )
    }
}

@Composable
private fun PanelSection(text: String) {
    Text(
        text,
        color = Color.White.copy(alpha = 0.45f),
        fontSize = 11.sp,
        modifier = Modifier.padding(top = 8.dp, bottom = 8.dp),
    )
}

@Composable
private fun SideChip(
    text: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(8.dp)
    Box(
        modifier
            .height(34.dp)
            .clip(shape)
            .background(
                if (selected) ProomColor.accentSoft else Color.White.copy(alpha = 0.08f)
            )
            .then(
                if (selected) Modifier.border(1.dp, ProomColor.accent, shape) else Modifier
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            color = if (selected) ProomColor.accent else Color.White.copy(alpha = 0.86f),
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/* ─────────────────────── 小组件 ─────────────────────── */

@Composable
private fun CircleIcon(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    Box(
        Modifier
            .size(40.dp)
            .clip(CircleShape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = Color.White.copy(alpha = if (enabled) 0.92f else 0.28f),
            modifier = Modifier.size(21.dp),
        )
    }
}

@Composable
private fun TextChip(text: String, onClick: () -> Unit) {
    // 用 Box 包一层再 clickable：直接给 Text 加 clickable 时，
    // 命中的是文本的排版区域，实际按下去经常点不到（尤其带 padding 的短词）
    Box(
        Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Color.White.copy(alpha = 0.10f))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = Color.White.copy(alpha = 0.9f),
            fontSize = 12.sp,
            maxLines = 1,
        )
    }
}

/** 锁定态只留一个半透明的锁：点了就解锁 */
@Composable
private fun LockButton(
    locked: Boolean,
    modifier: Modifier = Modifier,
    onToggle: () -> Unit,
) {
    Box(
        modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = if (locked) 0.55f else 0.35f))
            .clickable(onClick = onToggle),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = if (locked) PlayerIcons.Lock else PlayerIcons.Unlock,
            contentDescription = if (locked) "解锁" else "锁定",
            tint = Color.White.copy(alpha = 0.9f),
            modifier = Modifier.size(19.dp),
        )
    }
}

/* ─────────────────────── 倍速 / 线路面板 ─────────────────────── */

@Composable
private fun PlayerPanel(
    title: String,
    speed: Float,
    lines: List<String>,
    selectedLine: Int,
    modifier: Modifier = Modifier,
    onSpeed: (Float) -> Unit,
    onLine: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val speeds = listOf(0.75f, 1f, 1.25f, 1.5f, 2f)
    Box(modifier.fillMaxWidth().fillMaxHeight().clickable(onClick = onDismiss)) {
        Column(
            Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 16.dp, bottom = 96.dp)
                .width(200.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Color.Black.copy(alpha = 0.86f))
                .padding(vertical = 10.dp),
        ) {
            Text(
                title,
                color = Color.White.copy(alpha = 0.5f),
                fontSize = 11.sp,
                modifier = Modifier.padding(start = 14.dp, bottom = 6.dp),
            )
            if (title == "倍速") {
                speeds.forEach { s ->
                    PanelRow(
                        label = if (s == 1f) "1×（正常）" else "$s×",
                        selected = s == speed,
                    ) { onSpeed(s) }
                }
            } else {
                LazyColumn(Modifier.height(220.dp)) {
                    items(lines) { label ->
                        PanelRow(
                            label = label,
                            selected = lines.indexOf(label) == selectedLine,
                        ) { onLine(lines.indexOf(label)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun PanelRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            color = if (selected) ProomColor.accent else Color.White.copy(alpha = 0.86f),
            fontSize = 13.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.width(150.dp),
        )
    }
}

/* ─────────────────────── 投屏面板 ─────────────────────── */

/**
 * 投屏面板：列出同一网络里的电视，点一台就把当前地址推过去。
 *
 * 三条状态必须都画出来，否则用户分不清「没搜到」和「还没搜完」：
 * 正在搜索 / 搜到 N 台 / 一台都没有（附排查提示）。
 */
@Composable
private fun CastSheet(
    devices: List<com.projectionroom.shared.cast.CastDevice>,
    scanning: Boolean,
    current: String,
    casting: Boolean,
    onRescan: () -> Unit,
    onPick: (com.projectionroom.shared.cast.CastDevice) -> Unit,
    onManual: (String) -> Unit,
    onStop: () -> Unit,
    onClose: () -> Unit,
) {
    // 面板高度上限按屏幕来算，而不是写死一个 dp：
    // 横屏（约 390dp 高）放不下 500dp 的面板，设备一多就会被屏幕切掉底部，
    // 倍速/线路面板之前就是这么翻车的。
    val cfg = androidx.compose.ui.platform.LocalConfiguration.current
    val maxH = (cfg.screenHeightDp * 0.88f).dp

    Box(
        Modifier
            .fillMaxSize()
            // 遮罩只「吃掉」点击，不做关闭：点投屏图标那一下，down 落在图标上、
            // up 可能落到刚合成出来的遮罩上，遮罩一旦执行关闭就成了「开了立刻关」。
            // 关面板只认右上角那个 ✕。
            .background(Color.Black.copy(alpha = 0.55f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {},
            ),
    ) {
        Column(
            Modifier
                // 居中而不是贴底：横屏手机屏幕很宽、很矮，贴底的面板会缩在屏幕
                // 下沿一条窄带里，看起来像「没对齐」。居中后四周留白对称，
                // 也不再受导航栏高度影响（原来贴底 + navigationBarsPadding 时
                // 面板底边会在部分机型上被顶到导航栏上方，下面留一条黑缝）。
                .align(Alignment.Center)
                .widthIn(max = 460.dp)
                .fillMaxWidth()
                .heightIn(max = maxH)
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0xFF16181D))
                // 拦掉面板内部的点击，不然点标题也会把面板关掉
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {},
                )
                .padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        if (casting) "正在投屏" else "投屏到电视",
                        color = Color.White,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        when {
                            // 投屏中先说「投到哪」，比继续说「正在搜索」有用
                            casting && current.isNotBlank() -> "已投屏到 $current"
                            scanning -> "正在搜索同一网络下的电视…"
                            devices.isEmpty() -> "没找到电视 · 确认手机和电视连的是同一个 Wi-Fi"
                            else -> "找到 ${devices.size} 台设备"
                        },
                        color = Color.White.copy(alpha = 0.45f),
                        fontSize = 11.sp,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                if (scanning) {
                    BufferDots()
                } else {
                    Text(
                        "重新搜索",
                        color = ProomColor.accent,
                        fontSize = 12.sp,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable(onClick = onRescan)
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                    )
                }
                Box(
                    Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .clickable(onClick = onClose),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("✕", color = Color.White.copy(alpha = 0.65f), fontSize = 15.sp)
                }
            }

            if (devices.isNotEmpty()) {
                LazyColumn(Modifier.padding(top = 10.dp).heightIn(max = 260.dp)) {
                    items(devices) { d ->
                        val active = casting && d.name == current
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = 2.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(
                                    if (active) ProomColor.accentSoft else Color.White.copy(alpha = 0.06f)
                                )
                                .clickable { onPick(d) }
                                .padding(horizontal = 12.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                PlayerIcons.Cast,
                                contentDescription = null,
                                tint = if (active) ProomColor.accent else Color.White.copy(alpha = 0.8f),
                                modifier = Modifier.size(20.dp),
                            )
                            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                                Text(
                                    d.name,
                                    color = Color.White,
                                    fontSize = 14.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (d.model.isNotBlank()) {
                                    Text(
                                        d.model,
                                        color = Color.White.copy(alpha = 0.4f),
                                        fontSize = 11.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.padding(top = 2.dp),
                                    )
                                }
                            }
                            if (active) {
                                Text("投屏中", color = ProomColor.accent, fontSize = 11.sp)
                            }
                        }
                    }
                }
            }

            // 手动地址是**兜底**，不是正常路径：默认收起，只有搜不到才露出来。
            // 正常用户不该知道 IP 是什么东西。
            var showManual by remember { mutableStateOf(false) }
            var manual by remember { mutableStateOf("") }
            if (showManual) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color.White.copy(alpha = 0.06f))
                        .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    androidx.compose.foundation.text.BasicTextField(
                        value = manual,
                        onValueChange = { manual = it },
                        singleLine = true,
                        textStyle = androidx.compose.ui.text.TextStyle(
                            color = Color.White,
                            fontSize = 13.sp,
                        ),
                        cursorBrush = androidx.compose.ui.graphics.SolidColor(ProomColor.accent),
                        modifier = Modifier.weight(1f).padding(vertical = 12.dp),
                        decorationBox = { inner ->
                            if (manual.isEmpty()) {
                                Text(
                                    "电视地址，如 192.168.1.8:49520",
                                    color = Color.White.copy(alpha = 0.3f),
                                    fontSize = 12.sp,
                                )
                            }
                            inner()
                        },
                    )
                    Text(
                        "连接",
                        color = ProomColor.accent,
                        fontSize = 13.sp,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { onManual(manual) }
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                    )
                }
            } else if (devices.isEmpty() && !scanning && current.isBlank()) {
                Text(
                    "电视不答搜索？手动填地址 ›",
                    color = Color.White.copy(alpha = 0.4f),
                    fontSize = 11.sp,
                    modifier = Modifier
                        .padding(top = 8.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { showManual = true }
                        .padding(horizontal = 6.dp, vertical = 6.dp),
                )
            }

            if (casting) {
                Text(
                    "停止投屏",
                    color = Color(0xFFFF6B6B),
                    fontSize = 13.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .clickable(onClick = onStop)
                        .padding(vertical = 12.dp),
                )
            }
        }
    }
}
