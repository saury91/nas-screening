package com.projectionroom.ui.screen

import android.app.Activity
import android.app.PictureInPictureParams
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.ActivityInfo
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.provider.Settings
import android.util.Rational
import android.util.TypedValue
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.projectionroom.shared.cast.CastApi
import com.projectionroom.shared.cast.CastHub
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.foundation.gestures.waitForUpOrCancellation
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import com.projectionroom.ui.music.AudioFx
import com.projectionroom.ui.music.SleepTimer
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.focusable
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import com.projectionroom.shared.model.Vod
import com.projectionroom.shared.playback.SkipMark
import com.projectionroom.shared.playback.SkipRule
import com.projectionroom.ui.playback.MediaStats
import com.projectionroom.ui.playback.MediaStatsTracker
import com.projectionroom.ui.playback.PlaybackEngine
import com.projectionroom.ui.playback.SideSubtitle
import com.projectionroom.ui.playback.SkipStore
import com.projectionroom.ui.playback.SubtitleStore
import com.projectionroom.ui.playback.TrackOption
import com.projectionroom.ui.playback.TrackPicker
import com.projectionroom.ui.component.BufferDots
import com.projectionroom.ui.component.PlayerIcons
import com.projectionroom.ui.component.TabIcons
import com.projectionroom.ui.component.tvFocusRing
import com.projectionroom.ui.theme.LocalProomTv
import com.projectionroom.ui.theme.ProomColor
import com.projectionroom.ui.theme.ProomSp
import com.projectionroom.ui.theme.TvLayout
import kotlinx.coroutines.delay
import java.util.Locale
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
internal const val PLAYER_UA =
    "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) " +
        "Chrome/120.0 Mobile Safari/537.36"

/** 进程内的初始朝向：进播放器前记一次，退出时还原到它 */
private object OrientationMemory {
    var initial: Int? = null
}

/** 从 SAF 的 URI 上取显示名（用来判断字幕格式）；拿不到就退回 URI 本身 */
private fun displayNameOf(context: Context, uri: Uri): String? = runCatching {
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
}.getOrNull()

private fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}

/**
 * 沉浸式开关：贴着屏幕边缘画，把状态栏和导航条藏掉。
 *
 * 没用 WindowInsetsController —— 那套是 API 30 起才有的，而这个 App 的 minSdk 是 21。
 * 老的 systemUiVisibility 在 21～34 上都有效，缺的只是新 API 的便利。
 * IMMERSIVE_STICKY 的语义是「从边缘划一下能临时唤出，过一会儿自己退回去」，
 * 正好是看片时要的：不挡画面，但想调亮度时条还在。
 */
@Suppress("DEPRECATION")
private fun android.view.View.applyImmersive(on: Boolean) {
    systemUiVisibility = if (on) {
        android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
            android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            android.view.View.SYSTEM_UI_FLAG_FULLSCREEN or
            android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
    } else {
        0
    }
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

/**
 * 进度读数。
 *
 * 时长还没探到时（刚起播 / 直播回看 / 源没给 duration）不要印 "/ 00:00"：
 * 「24:39 / 00:00」看起来像这片只有零秒、或者进度条坏了，直接用 "--:--"。
 */
private fun progressText(position: Long, duration: Long): String =
    "${formatTime(position)} / ${if (duration > 0) formatTime(duration) else "--:--"}"

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
    /**
     * 投屏成功。
     *
     * 壳收到后把页面换成遥控器页，同时本页整个销毁 —— 本地播放器跟着释放，
     * 缓冲和声音一起停掉。这正是「投上去以后手机不该继续放着」的意思。
     *
     * 三个参数只会有一个非空：走 NAS 名录的电视给 Target，局域网 DLNA 给
     * CastDevice，自定义协议（PROOM-CAST/1）给 LanCastTarget。
     */
    onCastStarted: (
        line: Int,
        ep: Int,
        nas: CastApi.Target?,
        dlna: com.projectionroom.shared.cast.CastDevice?,
        lan: com.projectionroom.shared.cast.LanCastTarget?,
    ) -> Unit = { _, _, _, _, _ -> },
    /**
     * 投屏面板里点了「扫码连接电视」。
     *
     * 相机和路由都在壳里（ui-phone 依赖 ui-media，反过来拿不到），
     * 所以这里只发出「用户想扫码」这个动作，扫到地址后由壳走
     * [onCastStarted] 的 `lan` 分支把页面换掉。
     */
    onScanCast: () -> Unit = {},
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
        // 播放时藏掉状态栏和导航条：横屏看片时顶部那条「时间 / 电量」和底部白条
        // 都在偷画面，而且状态栏底色和视频黑边不是一个黑，看着像没全屏。
        // 用 IMMERSIVE_STICKY：从边缘划一下能临时唤出，一会儿自己退回去。
        val bars = runCatching {
            act?.window?.decorView?.apply { applyImmersive(true) }
        }.getOrNull()
        onDispose {
            // 手机上直接锁回竖屏：比"还原 UNSPECIFIED"确定得多——
            // 系统旋转被锁住时 UNSPECIFIED 不会自己转回来，界面就卡在横屏
            if (rotateLandscape) act?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            // 状态栏还得还给后面的页面，不然整个 App 都没状态栏了
            runCatching { bars?.applyImmersive(false) }
            // 退出播放器把亮度交回系统，不然整个 App 会一直停在播放时调暗的值
            runCatching {
                val w = (act ?: activity)?.window ?: return@runCatching
                val lp = w.attributes
                lp.screenBrightness = android.view.WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                w.attributes = lp
            }
        }
    }

    // 转横屏本身还要兜一层。SENSOR_LANDSCAPE 语义是「请按传感器方向横过来」，
    // 系统开着方向锁定、或厂商省电策略把它拽回竖屏时，这句请求会被无视——
    // 表现就是播放页竖着打开、2.35:1 的片子缩成中间一条。
    // 半秒后确认一次：没转成横屏就换成固定 LANDSCAPE（固定方向不吃传感器、也不吃旋转锁定）
    LaunchedEffect(rotateLandscape, activity) {
        if (!rotateLandscape) return@LaunchedEffect
        val act = activity ?: return@LaunchedEffect
        act.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        delay(500)
        val r = runCatching { act.display?.rotation }.getOrNull() ?: return@LaunchedEffect
        if (r == android.view.Surface.ROTATION_0 || r == android.view.Surface.ROTATION_180) {
            act.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
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
    // 解码方式只能在构造时定型（RenderersFactory / MediaCodecSelector 都是构造参数），
    // 所以切档位 = 换一台播放器。续播点用数组而不是 state：effect 里读写它不该触发重组
    val resumeAt = remember { LongArray(1) }
    val decoderMode = PlaybackPrefs.decoder
    val exo = remember(decoderMode) { PlaybackEngine.build(context, decoderMode) }
    var stats by remember { mutableStateOf(MediaStats()) }
    var buffering by remember { mutableStateOf(true) }
    var playbackError by remember(playUrl) { mutableStateOf<String?>(null) }
    var failedLines by remember { mutableStateOf(0) }
    /** 这一路源有没有真正出过画面（起播超时兜底用，见下面「起播看护」） */
    var readyOnce by remember { mutableStateOf(false) }

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
    var sleepOpen by remember { mutableStateOf(false) }
    // 「播放信息」：分辨率/码率/速度/解码器，排查卡顿用
    var infoOpen by remember { mutableStateOf(false) }
    // ── 字幕与音轨 ──
    var trackOpen by remember { mutableStateOf(false) }
    // 轨道列表只在打开面板时取一次快照：放进每秒 tick 里会让整页每秒重组
    var textTracks by remember { mutableStateOf<List<TrackOption>>(emptyList()) }
    var audioTracks by remember { mutableStateOf<List<TrackOption>>(emptyList()) }
    // 外挂字幕按「片名 + 第几集」记：换集不串，重进同一集自动带出
    val subKey = if (live) "" else vod.name + "\u0001" + epIndex
    var sideSub by remember(subKey) {
        mutableStateOf(if (live) null else SubtitleStore.get(context, vod.name, epIndex))
    }
    // 挂/换/去字幕都要重建 MediaItem：重建会走 onDispose，那里默认会写一条观看记录。
    // 用一次性开关抑制掉，否则「换个字幕」在记录里就多出一条 pendingHis 推给 NAS
    val skipHistory = remember { BooleanArray(1) }
    // ── 片头 / 片尾 ──
    // 按剧名记，不按集：同一部剧每集片头长度一致（Plex/Jellyfin 做片头检测也基于这个假设）。
    // 没单独设过就用设置页的全局默认；直播不参与，直接置 0。
    var skipMark by remember(vod.name) {
        mutableStateOf(
            if (live) SkipMark(0L, 0L)
            else SkipStore.get(context, vod.name)
                ?: SkipMark(PlaybackPrefs.introMs, PlaybackPrefs.outroMs)
        )
    }
    // 本集手动关掉「下一集」提示后不再弹：片尾曲里反复冒按钮比不提示更烦
    var outroDismissed by remember(playUrl) { mutableStateOf(false) }
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var speed by remember { mutableFloatStateOf(1f) }
    var hint by remember { mutableStateOf<String?>(null) }
    /** 长按临时 2 倍速：按住加速、松手还原（直播禁用） */
    var boost by remember { mutableStateOf(false) }

    /** 面板打开时现取一次轨道快照（选完再取一次，高亮才跟得上） */
    fun refreshTracks() {
        textTracks = TrackPicker.textTracks(exo)
        audioTracks = TrackPicker.audioTracks(exo)
    }

    /**
     * 挂/去外挂字幕。
     *
     * 重建 MediaItem 会让 onDispose 跑一次：先把位置存进 resumeAt，再打上「不写观看记录」标记，
     * 否则用户每换一次字幕，本地记录就多一条要推给 NAS 的 pendingHis。
     */
    fun applySideSub(sub: SideSubtitle?) {
        // 重选同一个文件不会触发重建（状态值相等），此时别把 skipHistory 立起来，
        // 否则它会被下一次真正的重建（比如退出播放页）消费掉，白丢一条观看记录
        if (sub?.uri == sideSub?.uri) return
        resumeAt[0] = exo.currentPosition
        skipHistory[0] = true
        sideSub = sub
    }

    // 系统文件选择器：电视上没有这个入口（面板里换成一行说明）
    val subPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val picked = displayNameOf(context, uri).orEmpty()
            // 很多文件管理器把 srt 报成 application/octet-stream，只能靠扩展名判断
            val mime = TrackPicker.subtitleMime(picked.ifBlank { uri.toString() })
            if (mime == null) {
                hint = "只支持 SRT / VTT / ASS / TTML 字幕"
            } else {
                // 只拿一次读权限：下次进这一集不用再选一遍文件
                runCatching {
                    context.contentResolver
                        .takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                val label = picked.ifBlank { "外挂字幕" }
                val sub = SideSubtitle(uri.toString(), mime, label)
                SubtitleStore.remember(context, vod.name, epIndex, sub)
                PlaybackPrefs.setSubtitleOn(context, true)
                applySideSub(sub)
                hint = "已加载字幕：$label"
            }
        }
    }

    // ── 遥控器 ──
    // 电视上的一切都靠焦点：控制层出现时把焦点送到主按钮，
    // 焦点在控制层里走动时重置自动收起的计时（否则正在选集，5 秒后整块消失）
    val focusManager = LocalFocusManager.current
    val playFocus = remember { FocusRequester() }
    // 进度条进不了焦点树（横贯整屏的细条，方向导航找不到、TAB 跳过、
    // requestFocus 不报错也不落焦），所以不搬焦点，改用状态：
    // 播放键上键 → seekMode=true（进度条画成聚焦态），左右调进度，OK/上下退出。
    var seekMode by remember { mutableStateOf(false) }
    // 播放器本体也是个焦点目标：控制层收起后焦点停在这里，
    // 遥控器按键才继续走 onPreviewKeyEvent（详见下面 clearFocus 那段的注释）
    val rootFocus = remember { FocusRequester() }
    var focusTick by remember { mutableIntStateOf(0) }

    fun seekBy(deltaMs: Long) {
        if (live) return
        val max = exo.duration.coerceAtLeast(0L)
        val target = (exo.currentPosition + deltaMs).coerceAtLeast(0L)
        exo.seekTo(if (max > 0) target.coerceAtMost(max) else target)
        position = exo.currentPosition
        hint = if (deltaMs > 0) "${deltaMs / 1000} 秒 »" else "« ${-deltaMs / 1000} 秒"
    }

    // ── 投屏（DLNA）──
    // 设备发现、SOAP 控制在 shared 的 DlnaClient 里；这里只管界面状态
    val dlna = remember { com.projectionroom.shared.cast.DlnaClient() }
    val castScope = rememberCoroutineScope()
    // 睡眠定时的淡出单独一条，页面销毁时一起取消
    val fadeScope = rememberCoroutineScope()
    var castOpen by remember { mutableStateOf(false) }
    var castScanning by remember { mutableStateOf(false) }
    var castDevices by remember { mutableStateOf<List<com.projectionroom.shared.cast.CastDevice>>(emptyList()) }
    var castTarget by remember { mutableStateOf<com.projectionroom.shared.cast.CastDevice?>(null) }
    // 经 NAS 名录找到的电视：手机在 4G / 公司网下也能投，靠的就是这一组
    var castNasDevices by remember {
        mutableStateOf<List<com.projectionroom.shared.cast.CastApi.Target>>(emptyList())
    }
    var castNas by remember { mutableStateOf<com.projectionroom.shared.cast.CastApi.Target?>(null) }
    // 第三条腿：自定义协议（PROOM-CAST/1）直连。不走 NAS、不依赖 DLNA，
    // 倍速/音量/第几集/请求头都能带过去 —— 面板上「同网直连」那一组就是它
    val lanCast = remember { com.projectionroom.shared.cast.LanCastClient() }
    var castLanDevices by remember {
        mutableStateOf<List<com.projectionroom.shared.cast.LanCastTarget>>(emptyList())
    }
    var castLan by remember { mutableStateOf<com.projectionroom.shared.cast.LanCastTarget?>(null) }
    // 电视那边当前是不是在播：手机当遥控器时要靠它决定发 pause 还是 play
    var casting by remember { mutableStateOf(false) }
    fun scanCast() {
        if (castScanning) return
        castScope.launch {
            castScanning = true
            // 三路一起跑，而且**谁回来谁上屏**。
            // 早前是「先等 NAS 名录、再并发扫局域网、最后一起赋值」：NAS 不在
            // 或信号差时，HTTP 要等好几秒超时，局域网那两路明明几十毫秒就有
            // 结果，却被压在 NAS 后面 —— 这就是「发现要等半天」的真正原因。
            val nasJob = launch {
                // NAS 名录覆盖跨网，NAS 不可达时返回空，不影响局域网这一路
                val nasList = runCatching {
                    com.projectionroom.shared.cast.CastHub.client
                        ?.targets(com.projectionroom.shared.cast.CastHub.selfId)
                        .orEmpty()
                }.getOrDefault(emptyList())
                castNasDevices = nasList.filter {
                    it.isTv && !com.projectionroom.shared.cast.CastHub.isMine(it)
                }
                // 退出播放页再进来：电视还在替自己播，但 castNas 是 remember，
                // 页面一换就没了 —— 不接回来的话面板写着「正在播」，手机上按暂停
                // 却只暂停了本机。owner 是自己才恢复，别人投的不碰。
                if (castNas == null) {
                    castNas = nasList.firstOrNull {
                        it.owner.isNotBlank() &&
                            it.owner == com.projectionroom.shared.cast.CastHub.selfId &&
                            it.state?.playing == true
                    }
                }
            }
            // 自定义协议只依赖一次 UDP 广播 + 一次应答，通常 100~200ms 就回来
            val lanJob = launch {
                castLanDevices = runCatching { lanCast.discover() }.getOrDefault(emptyList())
            }
            // SSDP 慢一档（多播、设备各自回包），单独一路，回来即上屏
            val dlnaJob = launch {
                castDevices = runCatching { dlna.discover() }.getOrDefault(emptyList())
            }
            // 只等局域网这两路：它们是「本机能不能直投」的答案，正常半秒内就有。
            // NAS 那路可能是几秒起步的超时（NAS 关机 / 手机在外网），
            // 让它自己回来补列表，不能拖着「还在搜…」一直转。
            lanJob.join()
            dlnaJob.join()
            castScanning = false
            nasJob.join()
        }
    }

    // 投屏中，控制条要显示电视那一份进度。本地播放器已经暂停，继续显示
    // 本机 position 就成了「电视播到 20 分钟，手机上还停在 0:27」，
    // 拖进度条时更是无从判断拖到了哪
    var nasPos by remember { mutableStateOf(0L) }
    var nasDur by remember { mutableStateOf(0L) }
    var nasPlaying by remember { mutableStateOf(false) }
    LaunchedEffect(castNas?.id) {
        val target = castNas ?: return@LaunchedEffect
        while (true) {
            val d = com.projectionroom.shared.cast.CastHub.client
                ?.targets(com.projectionroom.shared.cast.CastHub.selfId)
                ?.firstOrNull { it.id == target.id }
            d?.state?.let {
                nasPos = it.position
                nasDur = it.duration
                nasPlaying = it.playing
            }
            // 被另一台手机抢走控制权：遥控器身份作废，别继续装作还能控制
            if (d != null && d.owner.isNotBlank() &&
                d.owner != com.projectionroom.shared.cast.CastHub.selfId
            ) {
                castNas = null
                casting = false
                hint = "控制权被另一台手机接管了"
                break
            }
            kotlinx.coroutines.delay(2_000)
        }
    }

    // 自定义协议：电视是我们自己的接收端，本地轮询一次一毫秒级，直接 1 秒一拉
    LaunchedEffect(castLan?.base) {
        val target = castLan ?: return@LaunchedEffect
        var misses = 0
        while (true) {
            val s = lanCast.state(target.base)
            when {
                // 连续三次拿不到才判掉线：电视解码大码流时偶发一次超时很正常
                s == null -> if (++misses >= 3) {
                    castLan = null
                    casting = false
                    hint = "和电视的连接断了"
                    break
                }
                // 电视那头自己退出了播放页
                s.url.isBlank() -> {
                    castLan = null
                    casting = false
                    hint = "电视已停止播放"
                    break
                }
                else -> {
                    misses = 0
                    nasPos = s.position
                    nasDur = s.duration
                    nasPlaying = s.playing
                }
            }
            delay(1_000)
        }
    }

    /**
     * 采集站校验 Referer/UA，手机端播放器自己带了才播得动。推裸地址过去，
     * 电视端裸拉就是 403 —— 手机上正常、投上去黑屏，就是这个原因。
     */
    fun castHeaders(): Map<String, String> {
        val u = playUrl.orEmpty()
        val origin = runCatching { java.net.URI(u) }.getOrNull()
            ?.let { "${it.scheme}://${it.host}/" }.orEmpty()
        return buildMap {
            put("User-Agent", PLAYER_UA)
            if (origin.isNotBlank()) put("Referer", origin)
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
        val url = playUrl.orEmpty()
        // 自家接收端会从 DIDL 的 x-proom-* 属性里读回来
        val headers = castHeaders()
        castScope.launch {
            val r = dlna.cast(d, url, vod.name, headers)
            if (r.isSuccess) {
                castTarget = d
                castNas = null
                casting = true
                // 本机暂停，声音只从电视出
                exo.pause()
                onCastStarted(lineIndex, epIndex, null, d, null)
            } else {
                hint = "投屏失败：${r.exceptionOrNull()?.message ?: "电视没响应"}"
            }
        }
    }

    /**
     * 自定义协议直投（PROOM-CAST/1）。
     *
     * 和 DLNA 的差别不只是「不问 NAS」：`line/ep/episode` 和请求头是作为
     * 指令字段发过去的，电视接收端原样塞进播放页，所以「第 8 集」这种
     * 信息不会在电视上变成「投屏内容」。
     */
    fun doCastLan(t: com.projectionroom.shared.cast.LanCastTarget) {
        castOpen = false
        val url = playUrl.orEmpty()
        if (url.isBlank()) { hint = "还没有可播的地址"; return }
        castScope.launch {
            val ok = lanCast.send(
                t.base,
                CastApi.Command(
                    kind = "play",
                    url = url,
                    title = vod.name,
                    position = 0,
                    headers = castHeaders(),
                    vod = vod.name,
                    line = lineIndex,
                    ep = epIndex,
                    episode = vod.lines.getOrNull(lineIndex)?.episodes?.getOrNull(epIndex)?.name.orEmpty(),
                ),
            )
            if (ok) {
                castLan = t
                castTarget = null
                castNas = null
                casting = true
                exo.pause()
                onCastStarted(lineIndex, epIndex, null, null, t)
            } else {
                hint = "投屏失败：电视没响应"
            }
        }
    }

    /**
     * 经 NAS 投到电视。
     *
     * 和局域网那条路的区别是「跨网」：手机在 4G 下也能投。至于后台启动限制，
     * 两条路是一样的（电视 App 在后台就只能发通知等用户点），但这条路至少
     * 能确认指令送达，手机不会显示成功之后一片沉默。
     */
    fun doCastNas(t: com.projectionroom.shared.cast.CastApi.Target) {
        castOpen = false
        val url = playUrl.orEmpty()
        if (url.isBlank()) { hint = "还没有可播的地址"; return }
        val client = com.projectionroom.shared.cast.CastHub.client ?: run {
            hint = "没有连接 NAS，无法跨网投屏"
            return
        }
        castScope.launch {
            val ok = client.send(
                target = t.id,
                from = com.projectionroom.shared.cast.CastHub.selfId,
                cmd = com.projectionroom.shared.cast.CastApi.Command(
                    kind = "play",
                    url = url,
                    title = vod.name,
                    position = 0,
                    headers = castHeaders(),
                    vod = vod.name,
                    line = lineIndex,
                    ep = epIndex,
                    episode = vod.lines.getOrNull(lineIndex)?.episodes?.getOrNull(epIndex)?.name.orEmpty(),
                ),
            )
            if (ok) {
                castNas = t
                castTarget = null
                casting = true
                exo.pause()
                onCastStarted(lineIndex, epIndex, t, null, null)
            } else {
                hint = "投屏失败：电视没响应"
            }
        }
    }

    /**
     * 手动地址投屏。
     *
     * 电视不答 SSDP 的情况真实存在：路由开了 AP 隔离、电视和手机在
     * 不同 VLAN、老电视的 SSDP 只回广播不回组播。这时候填一句地址就能连，
     * 比「找不到设备」干瞪眼强。
     *
     * 先问 `/proom/info`：应答了就说明对面是我们自己的接收端，走自定义协议
     * （能带倍速/音量/请求头）；不答再退回 DLNA 的 description.xml。
     * 用户填 `192.168.1.8:49520`（DMR 端口）或 `*.xml` 时直接跳过探测，
     * 省掉两秒白等。
     */
    fun castManual(raw: String) {
        val addr = raw.trim()
        if (addr.isBlank()) return
        val base = com.projectionroom.shared.cast.LanCast.normalizeBase(addr)
        val looksDlna = addr.endsWith(".xml") || base?.endsWith(":49520") == true
        val loc = when {
            addr.startsWith("http://") || addr.startsWith("https://") ->
                if (addr.endsWith(".xml")) addr else addr.trimEnd('/') + "/description.xml"
            else -> "http://" + addr.trimEnd('/') + "/description.xml"
        }
        castScope.launch {
            castScanning = true
            if (!looksDlna && base != null) {
                val info = lanCast.info(base)
                if (info != null) {
                    castScanning = false
                    doCastLan(
                        com.projectionroom.shared.cast.LanCastTarget(
                            base = base,
                            name = info.name.ifBlank { "自定义协议电视" },
                            id = info.id,
                        ),
                    )
                    return@launch
                }
            }
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

    // 换集/换线路/换字幕：重新准备，有进度就从上次位置续播
    // 字幕配置进 MediaItem（不可变），所以换/去外挂字幕只能重建；内嵌轨切换走参数，不重建
    DisposableEffect(playUrl, decoderMode, sideSub?.uri) {
        val url = playUrl
        if (!url.isNullOrBlank()) {
            // 直播不读续播点：上次记的「位置」对直播流没有意义，
            // seek 到那儿只会得到一个黑屏或者直接跳回直播边缘。
            // 换解码器重建播放器时，位置从 resumeAt 带过来（读完即消费，别留到下一次换集）
            val carried = resumeAt[0].also { resumeAt[0] = 0L }
            val saved = if (live) 0L else if (carried > 0L) carried else prefs.getLong(url, 0L)
            // 采集站偶尔给出没有 .m3u8 后缀的 HLS 地址，ExoPlayer 只能靠 MIME 判断，
            // 不显式指定会当普通文件解析，永远起不来。
            val isHls = url.contains(".m3u8", ignoreCase = true)
            val item = MediaItem.Builder()
                .setUri(resolveUrl(url))
                .apply {
                    if (isHls) setMimeType(androidx.media3.common.MimeTypes.APPLICATION_M3U8)
                    // 外挂字幕（SAF content://）挂在这里；解析失败就当没挂，别让整集播不出来
                    sideSub?.let { sub ->
                        runCatching {
                            setSubtitleConfigurations(
                                listOf(TrackPicker.sideSubtitle(Uri.parse(sub.uri), sub.mime, sub.label))
                            )
                        }
                    }
                }
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
            // 外挂字幕来自 SAF 的 content://，DefaultHttpDataSource 读不了；
            // 用 DefaultDataSource 包一层：http(s) 仍走上面那套 UA/Referer，content/file 走本地分支。
            // 只包这一层，视频请求的行为完全不变。
            val dataSource = DefaultDataSource.Factory(context, factory)
            val source = DefaultMediaSourceFactory(dataSource).createMediaSource(item)
            exo.setMediaSource(source, if (saved > 5_000L) saved else 0L)
            // 字幕默认关：内嵌轨不会自己跳出来（海外片源自带英文字幕的概率很高）。
            // 必须在 prepare 之前设，等 STATE_READY 再设会先闪一下字幕。
            if (!PlaybackPrefs.subtitleOn) TrackPicker.disableText(exo)
            exo.prepare()
            exo.playWhenReady = true
            // 视频也吃同一套音效：对白听不清时开「人声」比调大音量有效
            AudioFx.init(context)
            AudioFx.attach(exo.audioSessionId)
        }
        onDispose {
            // 换字幕也会走到这里：这时位置已由调用方存进 resumeAt，
            // 进度表照写，但**不写观看记录** —— 否则每换一次字幕就多一条 pendingHis 推给 NAS
            val skip = skipHistory[0]
            skipHistory[0] = false
            // 直播不记进度、不写观看记录：看完一个台不该出现在「继续观看」里
            if (!live && !url.isNullOrBlank() && exo.currentPosition > 5_000L) {
                // 这张表是「每个 URL 一条」，看几百集后会有几千条；
                // XML 越大，每次 apply 序列化和下次启动加载都越慢 —— 到阈值直接清空重来
                if (prefs.all.size > 400) prefs.edit().clear().apply()
                prefs.edit().putLong(url, exo.currentPosition).apply()
                // 顺手喂给本地记录库：NAS 关机也记得看到哪了
                if (!skip) {
                    runCatching {
                        onProgress(episode?.name.orEmpty(), exo.currentPosition, exo.duration)
                    }
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
    val curSideSub by rememberUpdatedState(sideSub)

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
                if (state == Player.STATE_READY) {
                    readyOnce = true
                    // 会话 id 在 prepare 之后才真正建出来，这里补绑一次才保证音效生效
                    AudioFx.attach(exo.audioSessionId)
                    // 偏好是「字幕开」时自动挑一条：轨道要等 READY 才枚举得到，
                    // 所以这一步不能和 disableText 一起放在 prepare 之前
                    if (PlaybackPrefs.subtitleOn && curSideSub == null) {
                        val opts = TrackPicker.textTracks(exo)
                        if (opts.isNotEmpty() && opts.none { it.selected }) {
                            TrackPicker.selectText(exo, TrackPicker.preferredText(opts))
                        }
                    }
                }
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

    // ── 播放信息采样 ──
    //
    // 排查卡顿只看「转圈」是没用的：得知道此刻用的是哪颗解码器、码流多大、
    // 下载速度够不够。事件型的数由 AnalyticsListener 推，时变型的每秒采一次。
    val tracker = remember(exo) { MediaStatsTracker { stats = it } }
    DisposableEffect(tracker) {
        exo.addAnalyticsListener(tracker)
        onDispose { runCatching { exo.removeAnalyticsListener(tracker) } }
    }
    LaunchedEffect(tracker) {
        while (true) {
            delay(1_000)
            runCatching { tracker.tick(exo) }
        }
    }

    // 视频/直播一开声，音乐必须让路。
    //
    // 之前没有这行：从歌单页点进电影，电影对白和背景音乐一起响，
    // 两个音轨叠着谁也听不清。只暂停、不清队列 —— 用户从播放器退出来
    // 还能按播放键接着听，不用重新找歌。
    LaunchedEffect(Unit) {
        com.projectionroom.ui.music.MusicBox.pause()
    }

    // ── 起播看护 ──
    //
    // 源「连得上但一直不来数据」时 ExoPlayer 不会报错，只会停在 buffering：
    // 实测某个 IPTV 台点了之后 20 秒还挂在「正在连接 · 直播」，遥控器上除了返回没别的出路。
    // 这里给起播加时限，超时就走和 onPlayerError 一样的换源路径。
    //
    // 判据是「这一路有没有出过画面」而不是「现在缓不缓冲」：已经播起来过就不再管，
    // 中途卡一下是网络抖动，贸然换掉等于把用户从看到一半拽走。
    LaunchedEffect(playUrl) { readyOnce = false }
    val curBuffering by rememberUpdatedState(buffering)
    LaunchedEffect(playUrl, srcIndex, lineIndex, epIndex) {
        delay(20_000)
        if (readyOnce || !curBuffering) return@LaunchedEffect
        if (live && srcIndex + 1 < sources.size) {
            srcIndex += 1
            playbackError = null
        } else if (!live && lineIndex + 1 < vod.lines.size) {
            failedLines += 1
            lineIndex += 1
            epIndex = 0
            playbackError = null
        } else {
            playbackError = if (live) "这个台的源都播不动" else "这条线路播不动"
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

    // 无操作自动收起（锁定时只留锁图标）
    // key 必须带上 sidePanel：否则打开抽屉时计时器还在跑，几秒后照样把整个控制层（含抽屉）一起收掉。
    // 电视上再带上 focusTick：遥控器每动一下都重新计时，否则用户正在选集时整块会消失。
    LaunchedEffect(controlsVisible, locked, panel, sidePanel, castOpen, focusTick, sleepOpen, infoOpen, trackOpen) {
        if (controlsVisible && !locked && panel == Panel.None && !sidePanel && !castOpen && !sleepOpen && !infoOpen && !trackOpen) {
            delay(if (isTv) 8_000 else 5_000)
            controlsVisible = false
        }
    }

    // 睡眠定时到点：本页在播就淡出后暂停。owner 用 exo，离开播放页时解绑，
    // 之后由 SleepTimer 自己回落到「音乐在响就淡出暂停」。
    DisposableEffect(exo) {
        // 最后 30 秒的淡出由定时器统一下发系数，这里只负责把它落到播放器上
        SleepTimer.setFadeSink(exo) { k -> runCatching { exo.volume = k } }
        SleepTimer.bind(exo) {
            fadeScope.launch {
                runCatching { exo.pause() }
                runCatching { exo.volume = 1f }
                hint = "睡眠定时：已暂停"
            }
        }
        onDispose {
            SleepTimer.unbind(exo)
            SleepTimer.setFadeSink(exo, null)
            // 页面走了就别再让定时器压着这一路的音量
            runCatching { exo.volume = 1f }
        }
    }

    // 控制层出现 → 焦点落到主按钮（按 OK 就是暂停）；收起 → 焦点还给播放器根节点，
    // 否则方向键会被一层看不见的按钮吃掉，表现为「按了没反应」。
    // 收回焦点必须用 requestFocus(rootFocus) 而不是 clearFocus()：后者让整棵树
    // 都没有焦点，遥控器的按键就再也进不到 onPreviewKeyEvent 了。
    LaunchedEffect(controlsVisible) {
        if (!controlsVisible) {
            // 控制层收起时一并退出调进度模式，否则左右键会继续被它接管
            seekMode = false
            if (isTv) {
                runCatching { rootFocus.requestFocus() }
            } else {
                focusManager.clearFocus()
            }
        } else if (isTv) {
            // 不能拿 requestFocus 没抛异常当成功：控制层刚组合时焦点树还没稳，
            // 请求会"成功但没落地"。多按几次，最后一次一定落在已就绪的节点上。
            repeat(4) {
                delay(80)
                runCatching { playFocus.requestFocus() }
            }
        }
    }

    // 抽屉关掉后焦点得有个主人：留在刚被移除的节点上，遥控器会静默几秒，
    // 表现就是「按了没反应」。还回播放键，和唤出控制层时的落点一致。
    LaunchedEffect(sidePanel) {
        if (sidePanel || !controlsVisible || !isTv) return@LaunchedEffect
        // 抽屉刚被移除时焦点树还在重建，这时 requestFocus 不会报错也不生效，
        // 所以要等它重建完再多请求几次，否则遥控器按 OK 像没反应。
        repeat(5) {
            delay(90)
            runCatching { playFocus.requestFocus() }
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

    // 长按 2x：boost 只在按住期间为 true，松手立刻回到面板选的倍速。
    // 依赖 speed —— 面板改倍速时这一条会重算，两者不会互相覆盖。
    LaunchedEffect(boost, speed) {
        runCatching { exo.setPlaybackSpeed(if (boost) 2f else speed) }
    }

    // 面板开着时，返回键先关面板 —— 否则遥控器上按返回直接把整页播掉了，
    // 用户只是想退出「播放设置」
    BackHandler(enabled = !locked) {
        when {
            trackOpen -> trackOpen = false
            infoOpen -> infoOpen = false
            sidePanel -> sidePanel = false
            panel != Panel.None -> panel = Panel.None
            castOpen -> castOpen = false
            else -> onBack()
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            // 根节点自己必须可聚焦，而且控制层收起时要把焦点收回来。
            // 预览事件只沿「焦点路径」下发：一旦 clearFocus() 让整棵树都没有焦点，
            // 下面这个 onPreviewKeyEvent 就永远不会被调用 —— 表现是播放页 8 秒后
            // 控制层自动收起，遥控器怎么按都没反应（连 OK 都唤不回来）。
            .focusRequester(rootFocus)
            .focusable()
            // 电视遥控器：控制层收起时方向键直接可用，不必先「唤出再找」
            .onPreviewKeyEvent { e ->
                if (!isTv || e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                // 调进度模式：焦点仍停在播放键上（进度条本身进不了焦点树），
                // 按键在这里统一分发 —— 左右快进退、OK/上下退出，别让 OK 落成暂停
                if (seekMode) {
                    return@onPreviewKeyEvent when (e.key) {
                        Key.DirectionLeft -> { seekBy(-PlaybackPrefs.doubleTapMs); true }
                        Key.DirectionRight -> { seekBy(PlaybackPrefs.doubleTapMs); true }
                        Key.DirectionCenter, Key.Enter,
                        Key.DirectionUp, Key.DirectionDown,
                        -> { seekMode = false; true }
                        else -> false
                    }
                }
                when (e.key) {
                    // 返回键走预览事件：抽屉里点着格子时 BackHandler 收不到
                    // （实测按 BACK 一次完全没反应，得按两次），在根节点先截住
                    Key.Back -> when {
                        trackOpen -> { trackOpen = false; true }
                        infoOpen -> { infoOpen = false; true }
                        sidePanel -> { sidePanel = false; true }
                        panel != Panel.None -> { panel = Panel.None; true }
                        castOpen -> { castOpen = false; true }
                        else -> false
                    }
                    // 收起状态：左右 = 快退/快进（步长同双击设置）；上下/OK = 唤出控制层
                    Key.DirectionLeft -> if (controlsVisible) false else { seekBy(-PlaybackPrefs.doubleTapMs); true }
                    Key.DirectionRight -> if (controlsVisible) false else { seekBy(PlaybackPrefs.doubleTapMs); true }
                    Key.DirectionUp, Key.DirectionDown ->
                        if (controlsVisible) false else { controlsVisible = true; true }
                    Key.DirectionCenter, Key.Enter ->
                        if (controlsVisible) false else { controlsVisible = true; true }
                    Key.MediaPlayPause -> {
                        if (exo.isPlaying) exo.pause() else exo.play()
                        true
                    }
                    Key.MediaNext -> {
                        if (epIndex + 1 < (line?.episodes?.size ?: 0)) {
                            epIndex += 1
                            controlsVisible = true
                        }
                        true
                    }
                    Key.MediaPrevious -> {
                        if (epIndex > 0) {
                            epIndex -= 1
                            controlsVisible = true
                        }
                        true
                    }
                    // 菜单键直达「选集与线路」
                    Key.Menu -> {
                        sidePanel = true
                        controlsVisible = true
                        true
                    }
                    else -> false
                }
            },
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                PlayerView(ctx).apply {
                    player = exo
                    useController = false          // 控制层自己画
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
                    keepScreenOn = true
                    // 字幕走 PlayerView 内建的 SubtitleView：白字 + 黑描边，
                    // 亮场景（雪景、白墙）下也能看清；字号由偏好控制，见 update
                    // 这里要的是 android.graphics 的 ARGB int，不是 Compose 的 Color
                    subtitleView?.setStyle(
                        CaptionStyleCompat(
                            android.graphics.Color.WHITE,
                            android.graphics.Color.TRANSPARENT,
                            android.graphics.Color.TRANSPARENT,
                            CaptionStyleCompat.EDGE_TYPE_OUTLINE,
                            android.graphics.Color.BLACK,
                            null,
                        )
                    )
                    // 内嵌样式保留（ASS 的定位/颜色/卡拉OK都在这儿），
                    // 但字号不让片源说了算，否则「特大」档按了没反应
                    subtitleView?.setApplyEmbeddedFontSizes(false)
                }
            },
            // factory 只跑一次：换解码器会造出新的 exo，不在这里重绑画面就永远停在旧实例上
            update = { view ->
                view.player = exo
                // 字号四档在播放中改也要立刻生效，所以放在 update 而不是 factory
                view.subtitleView?.setFixedTextSize(
                    TypedValue.COMPLEX_UNIT_SP,
                    16f * PlaybackPrefs.subtitleScale,
                )
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
                            // 长按 2 倍速。手指一挪动超过 touchSlop，detectTapGestures
                            // 就自己取消长按 —— 上下滑调音量/亮度不会误触发。
                            onLongPress = {
                                if (live) return@detectTapGestures
                                boost = true
                                hint = "2x 快放中"
                            },
                            onDoubleTap = { off ->
                                // 直播没有「快进 10 秒」这回事
                                if (live) return@detectTapGestures
                                val half = size.width / 2f
                                // 步长走设置项；在回调里读，改完设置不用重建手势
                                val step = PlaybackPrefs.doubleTapMs
                                if (off.x < half) {
                                    exo.seekTo((exo.currentPosition - step).coerceAtLeast(0L))
                                    hint = "« ${PlaybackPrefs.label(step)}"
                                } else {
                                    val max = exo.duration.coerceAtLeast(0L)
                                    exo.seekTo((exo.currentPosition + step).let {
                                        if (max > 0) it.coerceAtMost(max) else it
                                    })
                                    hint = "${PlaybackPrefs.label(step)} »"
                                }
                                position = exo.currentPosition
                            },
                        )
                    }
                    // 长按的「松手还原」：detectTapGestures 的 onLongPress 只告诉我们
                    // 已经按住了，没有释放回调，所以单开一路等抬起。
                    .pointerInput(controlsVisible) {
                        if (controlsVisible) return@pointerInput
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false)
                            waitForUpOrCancellation()
                            if (boost) {
                                boost = false
                                hint = null
                            }
                        }
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
                Text(hint ?: "", color = Color.White, fontSize = ProomSp(13.sp))
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
                // 电视上点径放大到 14dp：10dp 的波点在 3 米外看不出在动
                BufferDots(
                    dot = if (isTv) 14.dp else 10.dp,
                    gap = if (isTv) 14.dp else 11.dp,
                )
                Text(
                    text = if (live) {
                        "正在连接" + (line?.site?.let { " · $it" } ?: "")
                    } else {
                        line?.let { "第 ${epIndex + 1} 集 · ${it.site}" } ?: ""
                    },
                    color = Color.White.copy(alpha = 0.55f),
                    fontSize = if (isTv) 16.sp else ProomSp(11.sp),
                    modifier = Modifier.padding(top = 10.dp),
                )
                // 转圈时最想知道的是「到底有没有在下载」：0 KB/s 和 8 MB/s
                // 在只看到一个转圈的界面上完全一样，但一个是源挂了、一个是快好了
                Text(
                    text = when {
                        stats.speedBps > 0 -> "下载 ${speedText(stats.speedBps)}"
                        stats.loadedBytes > 0 -> "等待数据…"
                        else -> "连接中…"
                    },
                    color = Color.White.copy(alpha = 0.34f),
                    fontSize = if (isTv) 14.sp else ProomSp(10.sp),
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }

        playbackError?.let { msg ->
            Column(
                Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(msg, color = Color.White.copy(alpha = 0.75f), fontSize = ProomSp(14.sp))
                Text(
                    if (live) "换个台，或者点右下角换一条线路" else "点左上角返回，换一条线路",
                    color = Color.White.copy(alpha = 0.45f),
                    fontSize = ProomSp(12.sp),
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }

        // 拖进度条和「跳过片头」都要走同一条分发：投屏中只改本机，
        // 会出现「手机跳了电视没动」的诡异状态
        fun seekTo(ms: Long) {
            castTarget?.let { d ->
                castScope.launch { dlna.seek(d, ms / 1000) }
            }
            castNas?.let { t ->
                castScope.launch {
                    CastHub.client?.send(
                        t.id, CastHub.selfId,
                        CastApi.Command(kind = "seek", position = ms),
                    )
                }
                // 先按下的位置回填，不然要等下一轮轮询进度条才跟上
                nasPos = ms
            }
            castLan?.let { t ->
                castScope.launch {
                    lanCast.send(t.base, CastApi.Command(kind = "seek", position = ms))
                }
                nasPos = ms
            }
            exo.seekTo(ms)
            position = ms
        }

        // ── 控制层 ──
        // 这里**不能**套 zIndex / graphicsLayer / AnimatedVisibility：
        // 试过三种，横屏下顶栏那两个按钮的命中区域都会和绘制位置错开
        // （底栏能点、顶栏点不动）。直接条件渲染，位置和命中就是一致的。
        if (controlsVisible || locked) {
            // hasFocus：子树里有焦点就算（焦点在任意按钮上），用来给自动收起计时续命
            Box(
                Modifier
                    .fillMaxSize()
                    .onFocusChanged { if (it.hasFocus) focusTick++ },
            ) {
                // 面板展开时把上下两条栏收起来：右侧抽屉直接压在片名和进度按钮上，
                // 「播放设置」和「狠狠骗我 · 第 1 集」叠字、暂停键被切一半
                val overlayOpen = sidePanel || panel != Panel.None || trackOpen
                if (!locked && !overlayOpen) {
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

                if (!locked && !overlayOpen) {
                    // 投屏中换成电视的进度与播放态：手机这时候是个遥控器，
                    // 显示自己的位置既没用，也会让暂停键看起来「按了没反应」
                    val castingRemote = castNas != null || castLan != null

                    PlayerBottomBar(
                        position = if (castingRemote) nasPos else position,
                        duration = if (castingRemote) nasDur else duration,
                        speed = speed,
                        live = live,
                        liveTitle = episode?.name.orEmpty(),
                        liveCounter = if (live) "${epIndex + 1} / ${line?.episodes?.size ?: 0} 台" else "",
                        casting = castNas != null || castTarget != null || castLan != null,
                        castTitle = vod.name,
                        castSub = if (live) {
                            episode?.name.orEmpty()
                        } else {
                            line?.let { "第 ${epIndex + 1} 集 · ${it.site}" }.orEmpty()
                        },
                        hasPrev = epIndex > 0,
                        hasNext = epIndex + 1 < (line?.episodes?.size ?: 0),
                        isPlaying = if (castingRemote) nasPlaying else exo.isPlaying,
                        modifier = Modifier.align(Alignment.BottomCenter),
                        playFocus = playFocus,
                        seekFocused = seekMode,
                        onSeekFocus = { seekMode = it },
                        onSeek = { seekTo(it) },
                        onPrev = { if (epIndex > 0) { epIndex -= 1; controlsVisible = true } },
                        onNext = {
                            if (epIndex + 1 < (line?.episodes?.size ?: 0)) {
                                epIndex += 1
                                controlsVisible = true
                            }
                        },
                        onTogglePlay = {
                            val dlnaT = castTarget
                            val nas = castNas
                            val lanT = castLan
                            if (dlnaT != null) {
                                // 投屏中：手机变成遥控器，本机保持暂停
                                val wasPlaying = casting
                                castScope.launch {
                                    if (wasPlaying) dlna.pause(dlnaT) else dlna.resume(dlnaT)
                                    casting = !wasPlaying
                                }
                            } else if (lanT != null) {
                                // 按电视的真实播放态发指令：本地的 casting 只表示
                                // 「有没有在投」，电视被电视遥控器暂停时它不会变
                                val wasPlaying = nasPlaying
                                castScope.launch {
                                    lanCast.send(
                                        lanT.base,
                                        CastApi.Command(kind = if (wasPlaying) "pause" else "resume"),
                                    )
                                    nasPlaying = !wasPlaying
                                }
                            } else if (nas != null) {
                                // 按钮画的是电视的播放态，指令就按它来，否则会出现
                                // 「显示暂停图标、按下去却发 resume」
                                val wasPlaying = nasPlaying
                                castScope.launch {
                                    CastHub.client?.send(
                                        nas.id, CastHub.selfId,
                                        CastApi.Command(kind = if (wasPlaying) "pause" else "resume"),
                                    )
                                    nasPlaying = !wasPlaying
                                    casting = !wasPlaying
                                }
                            } else {
                                if (exo.isPlaying) exo.pause() else exo.play()
                            }
                        },
                        // 倍速就地循环 1× → 2× → 3× → 1×：数字自己变，不用再弹面板
                        onSpeed = {
                            val next = when {
                                speed < 1.5f -> 2f
                                speed < 2.5f -> 3f
                                else -> 1f
                            }
                            speed = next
                            runCatching { exo.setPlaybackSpeed(next) }
                        },
                        onSettings = { sidePanel = true },
                        onSleep = { sleepOpen = true },
                        onInfo = { infoOpen = true },
                        subtitleOn = PlaybackPrefs.subtitleOn,
                        onTracks = {
                            refreshTracks()
                            trackOpen = true
                        },
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
                            // 抽屉里一格只有 230dp：`艾旦 | 蓝光 · 线路2` 正好差两个字被截成
                            // 「…线路2…」，而线路号正是选它的理由。压掉分隔符两侧的空格，
                            // 省出两个字刚好装下
                            vod.lines.map { l ->
                                val q = if (l.quality.isBlank()) "" else "·${l.quality}"
                                (l.site + q).replace(" · ", "·").replace(" | ", "|")
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

                // 睡眠定时（和音乐页同一套：全局一个计时器，到点淡出暂停）
                if (sleepOpen) {
                    PlayerSleepSheet(
                        modifier = Modifier.align(Alignment.Center),
                        live = live,
                        onClose = { sleepOpen = false },
                    )
                }

                // 播放信息：排查卡顿用，解码方式也放这儿 —— 看着画面切档位才方便对比
                if (infoOpen) {
                    PlayerInfoSheet(
                        stats = stats,
                        site = line?.site.orEmpty(),
                        quality = line?.quality.orEmpty(),
                        decoder = decoderMode,
                        onPickDecoder = { m ->
                            if (m != decoderMode) {
                                // 位置先存下来：新播放器 prepare 时从这里起播
                                resumeAt[0] = exo.currentPosition
                                PlaybackPrefs.setDecoder(context, m)
                            }
                        },
                        onClose = { infoOpen = false },
                        modifier = Modifier.align(Alignment.Center),
                    )
                }

                // 字幕与音轨：内嵌轨即时切换，不重建、不丢进度；
                // 外挂字幕要重建媒体项，位置由 resumeAt 带过去
                if (trackOpen) {
                    PlayerTrackSheet(
                        textTracks = textTracks,
                        audioTracks = audioTracks,
                        subtitleOn = PlaybackPrefs.subtitleOn,
                        subtitleScale = PlaybackPrefs.subtitleScale,
                        sideSubLabel = sideSub?.label,
                        live = live,
                        isTv = isTv,
                        skipIntroMs = skipMark.introMs,
                        skipOutroMs = skipMark.outroMs,
                        // 直播没有片头片尾：整块换成一句说明，比给一排点了没反应的档位好
                        canSkip = !live,
                        onPickText = { opt ->
                            PlaybackPrefs.setSubtitleOn(context, opt != null)
                            TrackPicker.selectText(exo, opt)
                            refreshTracks()
                        },
                        onPickAudio = { opt ->
                            TrackPicker.selectAudio(exo, opt)
                            refreshTracks()
                        },
                        onPickFile = {
                            // 不收窄 MIME：不少文件管理器把 .srt 报成 octet-stream
                            runCatching { subPicker.launch(arrayOf("*/*")) }
                        },
                        onRemoveFile = {
                            SubtitleStore.clear(context, vod.name, epIndex)
                            applySideSub(null)
                        },
                        onPickScale = { PlaybackPrefs.setSubtitleScale(context, it) },
                        onPickIntro = { ms ->
                            val next = skipMark.copy(introMs = ms)
                            skipMark = next
                            SkipStore.remember(context, vod.name, next)
                        },
                        onPickOutro = { ms ->
                            val next = skipMark.copy(outroMs = ms)
                            skipMark = next
                            // 改了片尾时长就重新允许提示：上一集点过的「✕」不该带到这一集
                            outroDismissed = false
                            SkipStore.remember(context, vod.name, next)
                        },
                        onClose = { trackOpen = false },
                        modifier = Modifier.align(Alignment.Center),
                    )
                }
            }
        }

        // ── 片头 / 片尾药丸 ──
        // 挂在控制层**外面**：最想按「跳过片头」的时候，控制层往往已经自动收起了。
        // 锁屏和投屏面板打开时不出现 —— 前者防误触，后者要避免和面板抢屏。
        if (!locked && !castOpen) {
            val hasNext = epIndex + 1 < (line?.episodes?.size ?: 0)
            val inOutro = !outroDismissed &&
                SkipRule.shouldShowOutro(position, duration, skipMark.outroMs, hasNext, live)
            when {
                // 片头优先：两者理论上不会同时成立，但边界上（超短片尾）留个次序更安全
                SkipRule.shouldShowIntro(position, skipMark.introMs, live) -> SkipPill(
                    text = "跳过片头",
                    onClick = { seekTo(skipMark.introMs) },
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = if (isTv) 48.dp else 20.dp, bottom = if (isTv) 132.dp else 92.dp),
                )

                inOutro -> SkipPill(
                    text = "下一集 · ${SkipRule.remainingSec(position, duration)} 秒",
                    onClick = {
                        if (epIndex + 1 < (line?.episodes?.size ?: 0)) {
                            epIndex += 1
                            controlsVisible = true
                        }
                    },
                    onDismiss = { outroDismissed = true },
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = if (isTv) 48.dp else 20.dp, bottom = if (isTv) 132.dp else 92.dp),
                )
            }
        }

        // ── 投屏面板 ──
        // 挂在控制层**外面**：控制层 5 秒自动收起时，面板不该跟着一起消失。
        if (castOpen) {
            CastSheet(
                devices = castDevices,
                nasDevices = castNasDevices,
                lanDevices = castLanDevices,
                scanning = castScanning,
                current = castTarget?.name ?: castNas?.name ?: castLan?.name.orEmpty(),
                casting = casting,
                onRescan = { scanCast() },
                onPick = { d -> doCast(d) },
                onPickNas = { t -> doCastNas(t) },
                onPickLan = { t -> doCastLan(t) },
                onManual = { addr -> castManual(addr) },
                onScan = onScanCast,
                onStop = {
                    castTarget?.let { d -> castScope.launch { dlna.stop(d) } }
                    castNas?.let { t ->
                        castScope.launch {
                            CastHub.client?.send(t.id, CastHub.selfId, CastApi.Command(kind = "stop"))
                        }
                    }
                    castLan?.let { t ->
                        castScope.launch {
                            lanCast.send(t.base, CastApi.Command(kind = "stop"))
                        }
                    }
                    castTarget = null
                    castNas = null
                    castLan = null
                    casting = false
                    castOpen = false
                    hint = "已停止投屏"
                },
                onClose = { castOpen = false },
            )
        }
    }
}

/* ─────────────────────── 投屏遥控配色 ─────────────────────── */

/**
 * 投屏态的冷色强调，与接收端 CastPlayerScreen 用同一组值。
 *
 * 播放控制走青、内容（封面/评分/收藏）留在暖金：两套颜色各管一件事，
 * 投屏时一眼就能分出「这块是遥控器，不是影片信息」。
 */
private val CastCyan = ProomColor.cast
private val CastCyanLight = ProomColor.castSoft
private val CastCyanInk = ProomColor.castInk

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
                            Modifier.size(TvLayout.minTouch).clip(CircleShape).tvFocusRing(CircleShape)
                                .clickable(onClick = onToggleFav),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = if (favOn) TabIcons.HeartOn else TabIcons.Heart,
                                contentDescription = if (favOn) "取消收藏这个台" else "收藏这个台",
                                tint = if (favOn) ProomColor.accent else Color.White,
                                modifier = Modifier.size(23.dp),
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
                    fontSize = ProomSp(15.sp),
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (subtitle.isNotBlank()) {
                    Text(
                        subtitle,
                        color = Color.White.copy(alpha = 0.55f),
                        fontSize = ProomSp(12.sp),
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
                Modifier.size(48.dp).clip(CircleShape).tvFocusRing(CircleShape).clickable(onClick = onToggleFav),
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
    /** 投屏中：底栏换成玻璃遥控条，进度改青色，与电视端控制条同一套语言 */
    casting: Boolean = false,
    castTitle: String = "",
    castSub: String = "",
    hasPrev: Boolean,
    hasNext: Boolean,
    isPlaying: Boolean,
    modifier: Modifier = Modifier,
    playFocus: FocusRequester? = null,
    /** 电视：进度条处于调节中（见 PlayerSeekBar 的 focused 注释） */
    seekFocused: Boolean = false,
    onSeekFocus: (Boolean) -> Unit = {},
    onSeek: (Long) -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onTogglePlay: () -> Unit,
    onSpeed: () -> Unit,
    onSettings: () -> Unit,
    onSleep: () -> Unit = {},
    onInfo: () -> Unit = {},
    /** 字幕开着时圆钮变金：否则「字幕有没有开」只能点进去才知道 */
    subtitleOn: Boolean = false,
    onTracks: () -> Unit = {},
) {
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(0f) }
    val ratio = if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
    // 电视端整条控制层按 10-foot UI 重排：左右让到 5% 安全区、按钮放大、进度条加粗
    val tv = LocalProomTv.current

    Column(
        modifier
            .fillMaxWidth()
            .background(
                // 三段式而不是两端直落：台名/时间贴着下沿，只给 0.72 的黑
                // 在明亮的雪景、白墙镜头上会糊成一片看不清。
                // 电视再压深一档 —— 新闻台、白墙镜头下 1× 和「…」几乎看不见
                Brush.verticalGradient(
                    0f to Color.Transparent,
                    0.42f to Color.Black.copy(alpha = if (tv) 0.62f else 0.45f),
                    1f to Color.Black.copy(alpha = if (tv) 0.92f else 0.85f),
                )
            )
            .navigationBarsPadding()
            .padding(
                start = if (tv) TvLayout.insetX else 16.dp,
                end = if (tv) TvLayout.insetX else 16.dp,
                // 下沿留 27dp：电视过扫描会把贴边的进度条/时间切掉一截
                top = if (tv) 28.dp else 24.dp,
                bottom = if (tv) TvLayout.insetY else 10.dp,
            ),
    ) {
        if (live) {
            Column(Modifier.padding(bottom = 10.dp)) {
                Text(
                    liveTitle,
                    color = Color.White,
                    fontSize = if (tv) 22.sp else ProomSp(14.sp),
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(
                    Modifier.padding(top = 3.dp),
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
                        fontSize = if (tv) 16.sp else ProomSp(11.sp),
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
                accent = casting,
                modifier = Modifier.fillMaxWidth(),
                focused = seekFocused,
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

        if (casting) {
            // 投屏时手机只是个遥控器：时间、片名、控制、选集全收进一条玻璃条，
            // 不再让「左边时间、中间三个圆钮、右边两个圆钮」散在两端。
            Spacer(Modifier.height(12.dp))
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(24.dp))
                    .background(Color.White.copy(alpha = 0.07f))
                    .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(24.dp))
                    .padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(6.dp).clip(CircleShape).background(CastCyan))
                Column(Modifier.weight(1f).padding(start = 8.dp, end = 8.dp)) {
                    Text(
                        castTitle.ifBlank { "投屏中" },
                        color = Color.White,
                        fontSize = ProomSp(13.sp),
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        listOf(castSub, progressText(position, duration))
                            .filter { it.isNotBlank() }
                            .joinToString(" · "),
                        color = Color.White.copy(alpha = 0.45f),
                        fontSize = ProomSp(10.5.sp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                CircleIcon(
                    PlayerIcons.Prev, if (live) "上一台" else "上一集", onPrev,
                    enabled = hasPrev,
                )
                Box(
                    Modifier
                        .size(52.dp)
                        .clip(CircleShape)
                        .background(Brush.linearGradient(listOf(CastCyanLight, CastCyan)))
                        .clickable(onClick = onTogglePlay),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = if (isPlaying) PlayerIcons.Pause else PlayerIcons.Play,
                        contentDescription = if (isPlaying) "暂停" else "播放",
                        tint = CastCyanInk,
                        modifier = Modifier.size(24.dp),
                    )
                }
                CircleIcon(
                    PlayerIcons.Next, if (live) "下一台" else "下一集", onNext,
                    enabled = hasNext,
                )
                Box(Modifier.width(1.dp).height(24.dp).background(Color.White.copy(alpha = 0.12f)))
                // 倍速对直播流没有意义（只会让音频越拖越远），直接不出现
                if (!live) {
                    CastTextButton(speedLabel(speed), onSpeed)
                }
                CastTextButton(if (live) "换源" else "选集", onSettings)
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (!live) {
                    Text(
                        text = progressText(
                            if (dragging) (dragValue * duration).toLong() else position,
                            duration,
                        ),
                        color = Color.White.copy(alpha = 0.72f),
                        // 电视上时间码是唯一的「进度」读数，15sp 起步，别跟着手机缩
                        fontSize = if (tv) 16.sp else ProomSp(11.sp),
                    )
                }

                Spacer(Modifier.weight(1f))

                Row(
                    horizontalArrangement = Arrangement.spacedBy(if (tv) 16.dp else 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircleIcon(
                        PlayerIcons.Prev, if (live) "上一台" else "上一集", onPrev,
                        enabled = hasPrev,
                    )
                    // 主按钮：白色实心圆，比两侧大一号；电视上它是默认焦点
                    Box(
                        Modifier
                            .size(if (tv) 72.dp else 44.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.92f))
                            .then(if (playFocus != null) Modifier.focusRequester(playFocus) else Modifier)
                            // 上键进「调进度」模式：不搬焦点（进度条进不了焦点树），
                            // 只切换一个状态，松手即回播放/暂停
                            .onPreviewKeyEvent { e ->
                                if (e.type != KeyEventType.KeyDown || e.key != Key.DirectionUp) {
                                    return@onPreviewKeyEvent false
                                }
                                onSeekFocus(true)
                                true
                            }
                            .tvFocusRing(CircleShape, color = ProomColor.accent)
                            .clickable(onClick = onTogglePlay),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = if (isPlaying) PlayerIcons.Pause else PlayerIcons.Play,
                            contentDescription = if (isPlaying) "暂停" else "播放",
                            tint = Color.Black,
                            modifier = Modifier.size(if (tv) 34.dp else 22.dp),
                        )
                    }
                    CircleIcon(
                        PlayerIcons.Next, if (live) "下一台" else "下一集", onNext,
                        enabled = hasNext,
                    )
                }

                Spacer(Modifier.weight(1f))

                Row(
                    horizontalArrangement = Arrangement.spacedBy(if (tv) 20.dp else 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // 倍速对直播流没有意义（只会让音频越拖越远），直接不出现
                    if (!live) {
                        CircleTextChip(speedLabel(speed), onSpeed)
                        // 定时开着时图标变金：不然「有没有设过」完全看不出来
                        val sleepOn = SleepTimer.mode != SleepTimer.Mode.Off
                        CircleIcon(
                            PlayerIcons.Timer,
                            if (sleepOn) "睡眠定时 · ${SleepTimer.remainingText()}" else "睡眠定时",
                            onSleep,
                            tint = if (sleepOn) ProomColor.accent else null,
                        )
                    }
                    // 直播也留这个入口：体育台的双语解说、赛事字幕都在音轨/字幕轨里
                    CircleIcon(
                        PlayerIcons.Subtitle,
                        "字幕与音轨",
                        onTracks,
                        tint = if (subtitleOn) ProomColor.accent else null,
                    )
                    // 直播也留这个入口：IPTV 卡不卡、码流多大，同样要能看见
                    CircleIcon(PlayerIcons.Info, "播放信息", onInfo)
                    CircleIcon(
                        PlayerIcons.More,
                        if (live) "换源" else "选集与线路",
                        onSettings,
                    )
                }
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
    /** 投屏态用青色，普通播放保持白色 */
    accent: Boolean = false,
    modifier: Modifier = Modifier,
    /**
     * 电视：进度条是否处于「调节中」。
     *
     * 不靠焦点表示 —— 这条横贯整屏的细条在电视上根本进不了焦点树
     * （clickable / focusable / focusTarget / focusRequester 全试过：
     * requestFocus 不抛异常也不落焦，方向键和 TAB 都跳过它）。
     * 所以由上层维护一个 seekMode，视觉上把它画成聚焦态，按键由播放器根节点分发。
     */
    focused: Boolean = false,
    onProgress: (Float) -> Unit,
    onSeek: (Long) -> Unit,
) {
    var ratio by remember { mutableFloatStateOf(-1f) }
    var dragging by remember { mutableStateOf(false) }
    val barFocused = focused
    val base = ratio.takeIf { it >= 0f }
        ?: (if (duration > 0) (position.toFloat() / duration) else 0f).coerceIn(0f, 1f)
    val shown = if (dragging) ratio.coerceIn(0f, 1f) else base
    val tv = LocalProomTv.current
    // 聚焦反馈要成组出现才看得见：光晕 + 金环 + 手柄放大 + 轨道加粗，
    // 而且是淡入不是硬切（上一版只有「轨道 28%→50% 白 + 手柄 5→7dp」，
    // 坐着 3 米看过去就是没反应）
    val halo by animateFloatAsState(if (barFocused) 1f else 0f, tween(160), label = "seek-halo")
    val handle by animateFloatAsState(
        when {
            dragging || barFocused -> if (tv) 9f else 7f
            else -> if (tv) 6f else 4.5f
        },
        tween(160), label = "seek-handle",
    )
    val trackWidth by animateFloatAsState(
        if (barFocused) (if (tv) 5.5f else 3.5f) else (if (tv) 4f else 2.5f),
        tween(160), label = "seek-track",
    )

    Box(
        // 电视上轨道更粗、可聚焦区抬到 48dp；手机上保持 24dp 细条，免得挤掉按钮
        modifier.height(if (tv) TvLayout.minTouch else 24.dp),
    ) {
        Canvas(
            Modifier
                .fillMaxSize()
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
        val trackH = trackWidth.dp.toPx()
        val center = androidx.compose.ui.geometry.Offset(size.width * shown, cy)
        drawLine(
            color = Color.White.copy(alpha = 0.28f + 0.24f * halo),
            start = androidx.compose.ui.geometry.Offset(0f, cy),
            end = androidx.compose.ui.geometry.Offset(size.width, cy),
            strokeWidth = trackH,
            cap = androidx.compose.ui.graphics.StrokeCap.Round,
        )
        drawLine(
            color = if (accent) CastCyan else Color.White,
            start = androidx.compose.ui.geometry.Offset(0f, cy),
            end = androidx.compose.ui.geometry.Offset(size.width * shown, cy),
            strokeWidth = trackH,
            cap = androidx.compose.ui.graphics.StrokeCap.Round,
        )
        // 和音乐播放器那条细线进度条同一套聚焦语言：光晕 + 一圈描边 + 手柄放大
        if (halo > 0.01f) {
            drawCircle(
                color = ProomColor.accent.copy(alpha = 0.18f * halo),
                radius = (handle + 11f).dp.toPx(),
                center = center,
            )
            drawCircle(
                color = ProomColor.accent.copy(alpha = 0.95f * halo),
                radius = (handle + 3f).dp.toPx(),
                center = center,
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.dp.toPx()),
            )
        }
        drawCircle(
            color = if (accent) CastCyanLight else Color.White,
            radius = handle.dp.toPx(),
            center = center,
        )
        }

    }
}

/** 倍速的小圆钮：尺寸/底色与 CircleIcon 完全一致，只有中间放的是文字 */
@Composable
private fun CircleTextChip(text: String, onClick: () -> Unit) {
    val tv = LocalProomTv.current
    Box(
        Modifier
            .size(if (tv) 56.dp else 48.dp)
            .tvFocusRing(CircleShape)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.32f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            color = Color.White.copy(alpha = 0.92f),
            fontSize = if (tv) 18.sp else ProomSp(12.sp),
            maxLines = 1,
        )
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
            fontSize = ProomSp(11.sp),
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
    val tv = LocalProomTv.current
    Column(
        modifier
            // 电视上抽屉要更宽：322dp 里塞 4 列选集，每格只有 60dp，18sp 的集数放不下。
            // 480dp 时扣掉左右内边距，两列线路每格只剩 196dp（约 8 个字），
            // 「艾旦|蓝光·线路2」还是差最后一个字；504dp 刚好装下
            .width(if (tv) 504.dp else 322.dp)
            .fillMaxHeight()
            .background(Color.Black.copy(alpha = 0.94f))
            // 横屏时状态栏还在顶上：不加这个，标题和 ✕ 会顶进状态栏里
            // （截图里 ✕ 和电池图标叠在一起）
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(
                start = if (tv) 28.dp else 18.dp,
                // 右侧让到 5% 安全区：贴在屏幕边上会被电视过扫描切掉一半
                end = if (tv) TvLayout.insetX else 14.dp,
                top = if (tv) 20.dp else 4.dp,
                bottom = if (tv) TvLayout.insetY else 10.dp,
            ),
    ) {
        Row(
            Modifier.fillMaxWidth().height(if (tv) 64.dp else 48.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                title,
                color = Color.White,
                fontSize = if (tv) 26.sp else ProomSp(15.sp),
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.weight(1f))
            Box(
                Modifier.size(if (tv) TvLayout.minTouch else 34.dp).clip(CircleShape)
                    .tvFocusRing(CircleShape).clickable(onClick = onClose),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "✕",
                    color = Color.White.copy(alpha = 0.65f),
                    fontSize = if (tv) 20.sp else ProomSp(15.sp),
                )
            }
        }

        // 分两个 tab：线路动辄 20~30 条，和选集堆在一列里，
        // 选集会被顶到很下面，每次换集都要滑半天。
        // 直播只有线路这一个维度，就直接铺线路，不摆一个单 tab。
        var tab by remember { mutableStateOf(0) }
        val listState = rememberLazyListState()
        val currentFocus = remember { FocusRequester() }
        // 打开抽屉时焦点必须跟着进来。打开它的是控制条上的「⋯」，焦点留在那里的话，
        // 上下左右还在「暂停 / 倍速 / 更多」之间打转，抽屉看起来就是个死面板 ——
        // 选不了、也按不到关闭。落点选当前线路那一格：线路动辄二三十条，从头按太费劲。
        LaunchedEffect(tab) {
            val perRow = if (tab == 0) 2 else 4
            val sel = (if (tab == 0) selectedLine else selectedEp).coerceAtLeast(0)
            if (sel / perRow > 2) runCatching { listState.scrollToItem(sel / perRow) }
            repeat(12) {
                if (runCatching { currentFocus.requestFocus() }.isSuccess) return@LaunchedEffect
                delay(24)
            }
        }
        if (episodes.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().padding(bottom = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SideTab("$lineTab ${lines.size}", tab == 0, Modifier.weight(1f)) { tab = 0 }
                SideTab("$epTab ${episodes.size}", tab == 1, Modifier.weight(1f)) { tab = 1 }
            }
        }

        LazyColumn(Modifier.weight(1f), state = listState) {
            if (tab == 0) {
            itemsIndexed(lines.chunked(2)) { rowIdx, row ->
                Row(
                    Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    row.forEachIndexed { i, label ->
                        val idx = rowIdx * 2 + i
                        SideChip(
                            label, idx == selectedLine,
                            Modifier.weight(1f).then(
                                if (idx == selectedLine.coerceAtLeast(0)) {
                                    Modifier.focusRequester(currentFocus)
                                } else {
                                    Modifier
                                }
                            ),
                        ) {
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
                        SideChip(
                            name, idx == selectedEp,
                            Modifier.weight(1f).then(
                                if (idx == selectedEp.coerceAtLeast(0)) {
                                    Modifier.focusRequester(currentFocus)
                                } else {
                                    Modifier
                                }
                            ),
                        ) {
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
    val tv = LocalProomTv.current
    Box(
        modifier
            .height(if (tv) 56.dp else 34.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) Color.White.copy(alpha = 0.16f) else Color.Transparent)
            .tvFocusRing(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            color = if (selected) Color.White else Color.White.copy(alpha = 0.5f),
            fontSize = if (tv) 18.sp else ProomSp(12.sp),
        )
    }
}

@Composable
private fun PanelSection(text: String) {
    Text(
        text,
        color = Color.White.copy(alpha = 0.45f),
        fontSize = ProomSp(11.sp),
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
    val tv = LocalProomTv.current
    Box(
        modifier
            .height(if (tv) 56.dp else 34.dp)
            .clip(shape)
            .background(
                if (selected) ProomColor.accentSoft else Color.White.copy(alpha = 0.08f)
            )
            .then(
                if (selected) Modifier.border(1.dp, ProomColor.accent, shape) else Modifier
            )
            .tvFocusRing(shape, ringWidth = 2.dp, zoom = 1f)
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            color = if (selected) ProomColor.accent else Color.White.copy(alpha = 0.86f),
            fontSize = if (tv) 18.sp else ProomSp(12.sp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/* ─────────────────────── 小组件 ─────────────────────── */

/** 玻璃条里的文字键：不画底色，只留 48dp 触摸高度，避免又长出一条灰胶囊 */
@Composable
private fun CastTextButton(text: String, onClick: () -> Unit) {
    Box(
        Modifier
            .height(48.dp)
            .clip(RoundedCornerShape(24.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = Color.White.copy(alpha = 0.82f), fontSize = ProomSp(12.sp), maxLines = 1)
    }
}

@Composable
private fun CircleIcon(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    tint: Color? = null,
) {
    // 电视上 40dp 的圆钮在 3 米外只有指甲盖大：抬到 56dp，图标同步放大
    val tv = LocalProomTv.current
    Box(
        Modifier
            // 手机侧原来是 40dp 的裸图标：热区不够，且压在亮画面上（星空、白底字幕）
            // 几乎看不见。48dp + 一层半透明底，和电视侧一样有「这是个按钮」的样子
            .size(if (tv) 56.dp else 48.dp)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = if (enabled) 0.32f else 0.16f))
            .tvFocusRing(CircleShape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = tint ?: Color.White.copy(alpha = if (enabled) 0.92f else 0.28f),
            modifier = Modifier.size(if (tv) 28.dp else 24.dp),
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
            .tvFocusRing(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = Color.White.copy(alpha = 0.9f),
            fontSize = ProomSp(12.sp),
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
            // 锁定态是用户唯一能点的东西，40dp 在沙发上看太飘
            .size(48.dp)
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
    Box(
        modifier
            .fillMaxWidth()
            .fillMaxHeight()
            .focusProperties { canFocus = false }
            .clickable(onClick = onDismiss),
    ) {
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
                fontSize = ProomSp(11.sp),
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

/**
 * 睡眠定时面板。
 *
 * 影视这一侧「听完这集」比「N 分钟后」更贴合场景：多数人是看完这集就睡，
 * 而不是恰好 30 分钟后睡。直播没有「这集结束」，所以那一项不给。
 */
@Composable
private fun PlayerSleepSheet(
    modifier: Modifier = Modifier,
    live: Boolean,
    onClose: () -> Unit,
) {
    val options = listOf(15, 30, 45, 60)
    Box(
        modifier
            .fillMaxSize()
            .focusProperties { canFocus = false }
            .clickable(onClick = onClose),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .width(220.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(Color.Black.copy(alpha = 0.9f))
                .clickable(enabled = false) {}
                .padding(vertical = 10.dp),
        ) {
            Text(
                "睡眠定时",
                color = Color.White.copy(alpha = 0.5f),
                fontSize = ProomSp(11.sp),
                modifier = Modifier.padding(start = 14.dp, bottom = 2.dp),
            )
            Text(
                if (SleepTimer.mode == SleepTimer.Mode.Off) "最后 30 秒渐弱，到点暂停"
                else "${SleepTimer.remainingText()} 后停止",
                color = Color.White.copy(alpha = 0.32f),
                fontSize = ProomSp(10.5.sp),
                modifier = Modifier.padding(start = 14.dp, bottom = 6.dp),
            )
            options.forEach { m ->
                PanelRow(
                    label = "$m 分钟",
                    selected = SleepTimer.mode == SleepTimer.Mode.Timer && SleepTimer.minutes == m,
                ) { SleepTimer.start(m); onClose() }
            }
            if (!live) {
                PanelRow(
                    label = "听完这集",
                    selected = SleepTimer.mode == SleepTimer.Mode.TrackEnd,
                ) { SleepTimer.startTrackEnd(); onClose() }
            }
            if (SleepTimer.mode != SleepTimer.Mode.Off) {
                PanelRow(label = "关闭定时", selected = false) { SleepTimer.cancel(); onClose() }
            }
        }
    }
}

/**
 * 「播放信息」：一屏数字 + 解码方式。
 *
 * 卡顿究竟卡在哪，只看画面是分不出来的：硬解掉帧、码率超过带宽、
 * 源站只吐 20 KB/s，表现都是转圈。这里把三件事摆在一起，
 * 顺便给了硬解/软解的强制开关 —— 切档位时位置不变，正好做 A/B 对比。
 */
@Composable
private fun PlayerInfoSheet(
    stats: MediaStats,
    site: String,
    quality: String,
    decoder: DecoderMode,
    onPickDecoder: (DecoderMode) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tv = LocalProomTv.current
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier
            .width(if (tv) 540.dp else 384.dp)
            .clip(shape)
            .background(Color.Black.copy(alpha = 0.93f))
            .border(1.dp, Color.White.copy(alpha = 0.14f), shape)
            .padding(
                horizontal = if (tv) 24.dp else 18.dp,
                vertical = if (tv) 20.dp else 12.dp,
            ),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "播放信息",
                color = Color.White,
                fontSize = if (tv) 22.sp else ProomSp(14.sp),
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.weight(1f))
            Box(
                Modifier.size(if (tv) TvLayout.minTouch else 30.dp)
                    .clip(CircleShape)
                    .tvFocusRing(CircleShape)
                    .clickable(onClick = onClose),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    PlayerIcons.Close,
                    "关闭",
                    tint = Color.White.copy(alpha = 0.6f),
                    modifier = Modifier.size(if (tv) 20.dp else 16.dp),
                )
            }
        }
        Spacer(Modifier.height(if (tv) 10.dp else 6.dp))

        // 横屏只有 390dp 高，一列排八行会顶出屏幕：左右各四行
        Row(horizontalArrangement = Arrangement.spacedBy(if (tv) 28.dp else 14.dp)) {
            Column(Modifier.weight(1f)) {
                InfoLine("分辨率", resolutionText(stats) +
                    if (stats.fps > 0) " · %.0f fps".format(Locale.US, stats.fps) else "")
                InfoLine("码率", bitrateText(stats.bitrate.toLong()))
                InfoLine("缓冲速度", speedText(stats.speedBps))
                InfoLine(
                    "已缓冲",
                    if (stats.bufferMs > 0) {
                        "${durationText(stats.bufferMs)} · ${bytesText(stats.loadedBytes)}"
                    } else {
                        bytesText(stats.loadedBytes)
                    },
                )
            }
            Column(Modifier.weight(1f)) {
                InfoLine("带宽估计", bitrateText(stats.bandwidthBps))
                InfoLine(
                    "丢帧",
                    if (stats.rendered + stats.dropped > 0) {
                        "${stats.dropped} / ${stats.rendered + stats.dropped}"
                    } else {
                        "—"
                    },
                )
                if (site.isNotBlank()) {
                    InfoLine("线路", listOf(site, quality).filter { it.isNotBlank() }.joinToString(" · "))
                }
            }
        }
        // 解码器名最长（c2.qti.hevc.decoder），单独占一整行，别被省略号吃掉
        InfoLine(
            "解码器",
            listOfNotNull(
                stats.decoderName.takeIf { it.isNotBlank() },
                stats.hardware?.let { if (it) "硬解" else "软解" },
                stats.codecs.takeIf { it.isNotBlank() },
            ).joinToString(" · ").ifBlank { "—" },
        )

        Spacer(Modifier.height(if (tv) 14.dp else 11.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = 0.1f)))
        Spacer(Modifier.height(if (tv) 13.dp else 10.dp))

        Text(
            "解码方式",
            color = Color.White.copy(alpha = 0.5f),
            fontSize = if (tv) 16.sp else ProomSp(11.sp),
        )
        Spacer(Modifier.height(if (tv) 10.dp else 8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(if (tv) 12.dp else 8.dp)) {
            DecoderMode.values().forEach { m ->
                SideChip(m.label, m == decoder, Modifier.weight(1f)) { onPickDecoder(m) }
            }
        }
        Spacer(Modifier.height(if (tv) 10.dp else 7.dp))
        Text(
            "${decoder.desc}；切换后从当前位置续播",
            color = Color.White.copy(alpha = 0.34f),
            fontSize = if (tv) 14.sp else ProomSp(10.sp),
        )
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    val tv = LocalProomTv.current
    Row(
        Modifier.fillMaxWidth().padding(vertical = if (tv) 5.dp else 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            color = Color.White.copy(alpha = 0.45f),
            fontSize = if (tv) 16.sp else ProomSp(10.5.sp),
            modifier = Modifier.width(if (tv) 96.dp else 56.dp),
        )
        Text(
            value,
            color = Color.White.copy(alpha = 0.92f),
            fontSize = if (tv) 17.sp else ProomSp(10.5.sp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun resolutionText(s: MediaStats): String = when {
    s.width <= 0 || s.height <= 0 -> "—"
    s.height >= 2160 -> "${s.width} × ${s.height} · 4K"
    s.height >= 1080 -> "${s.width} × ${s.height} · 1080P"
    else -> "${s.width} × ${s.height}"
}

/** 倍速标签：2f 要显示成「2×」而不是「2.0×」 */
private fun speedLabel(speed: Float): String =
    if (speed == speed.toInt().toFloat()) "${speed.toInt()}×" else "${speed}×"

private fun speedText(bps: Long): String = when {
    bps <= 0 -> "—"
    bps >= 1024L * 1024L -> "%.2f MB/s".format(Locale.US, bps / 1048576.0)
    else -> "%d KB/s".format(Locale.US, bps / 1024L)
}

private fun bitrateText(bps: Long): String = when {
    bps <= 0 -> "—"
    bps >= 1_000_000L -> "%.2f Mbps".format(Locale.US, bps / 1_000_000.0)
    else -> "%d kbps".format(Locale.US, bps / 1000L)
}

private fun bytesText(b: Long): String = when {
    b <= 0 -> "—"
    b >= 1024L * 1024L * 1024L -> "%.2f GB".format(Locale.US, b / 1073741824.0)
    b >= 1024L * 1024L -> "%.1f MB".format(Locale.US, b / 1048576.0)
    else -> "%d KB".format(Locale.US, b / 1024L)
}

private fun durationText(ms: Long): String =
    if (ms <= 0) "—" else "%.1f 秒".format(Locale.US, ms / 1000.0)

@Composable
internal fun PanelRow(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    width: Dp = 150.dp,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            // 面板行原本在电视上完全看不出焦点：遥控器上下按，只有选中项在变色，
            // 「光标现在停在哪儿」是隐形的。zoom 用 1f，避免整行放大后被面板裁边
            .tvFocusRing(RoundedCornerShape(8.dp), zoom = 1f, color = ProomColor.accent)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 6.dp)
            .then(modifier),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            color = if (selected) ProomColor.accent else Color.White.copy(alpha = 0.86f),
            fontSize = ProomSp(13.sp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.width(width),
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
    /** 经 NAS 名录找到的电视，NAS 不在线时为空 */
    nasDevices: List<CastApi.Target> = emptyList(),
    /** 走自定义协议（PROOM-CAST/1）被 UDP 广播发现的电视，NAS 不在线也能列出来 */
    lanDevices: List<com.projectionroom.shared.cast.LanCastTarget> = emptyList(),
    scanning: Boolean,
    current: String,
    casting: Boolean,
    onRescan: () -> Unit,
    onPick: (com.projectionroom.shared.cast.CastDevice) -> Unit,
    onPickNas: (CastApi.Target) -> Unit = {},
    onPickLan: (com.projectionroom.shared.cast.LanCastTarget) -> Unit = {},
    onManual: (String) -> Unit,
    /** 拿起相机扫电视上的码：路由器关了广播、手机和电视跨网段时，这是最省事的连接方式 */
    onScan: () -> Unit = {},
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
            // 手填地址的输入行由头部的图标按钮唤起，状态得提到这一层
            var showManual by remember { mutableStateOf(false) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        if (casting) "正在投屏" else "投屏到电视",
                        color = Color.White,
                        fontSize = ProomSp(16.sp),
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        when {
                            // 投屏中先说「投到哪」，比继续说「正在搜索」有用
                            casting && current.isNotBlank() -> "已投屏到 $current"
                            // 三路是各自回来的：局域网那台已经到了就别再写「正在搜索」，
                            // 让用户点得到，剩下的一路在后面补
                            scanning && devices.isEmpty() && nasDevices.isEmpty() && lanDevices.isEmpty() ->
                                "正在搜索电视…"
                            scanning -> "找到 ${devices.size + nasDevices.size + lanDevices.size} 台 · 还在搜…"
                            devices.isEmpty() && nasDevices.isEmpty() && lanDevices.isEmpty() ->
                                "没找到电视 · 确认手机和电视连的是同一个 Wi-Fi"
                            else -> "找到 ${devices.size + nasDevices.size + lanDevices.size} 台设备"
                        },
                        color = Color.White.copy(alpha = 0.45f),
                        fontSize = ProomSp(11.sp),
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                if (scanning) {
                    BufferDots()
                } else {
                    Text(
                        "重新搜索",
                        color = ProomColor.accent,
                        fontSize = ProomSp(12.sp),
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable(onClick = onRescan)
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                    )
                }
                // 扫码 / 手填：广播搜不到时（路由关广播、双频分网段、访客网络）
                // 这两条路照样能连上，所以常驻在「重新搜索」右边，不藏在文案里。
                // 两个图标都走白色（跟「✕」同一档），主题色留给标题和「重新搜索」
                CastIconButton(label = "扫码连接电视", onClick = onScan) { ScanGlyph(IconTint) }
                CastIconButton(
                    label = "输入电视地址",
                    onClick = { showManual = !showManual },
                ) {
                    // 输入行展开时只提亮，不换色
                    TypeGlyph(if (showManual) Color.White else IconTint)
                }
                Box(
                    Modifier
                        // 和旁边的扫码/填地址同一档热区，不然「关面板」是最难点的那个
                        .size(48.dp)
                        .clip(CircleShape)
                        .clickable(onClick = onClose),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("✕", color = Color.White.copy(alpha = 0.65f), fontSize = ProomSp(17.sp))
                }
            }

            // NAS 名录排在前面：它不受「手机和电视是否同一个 Wi-Fi」限制，
            // 而且指令是电视自己来取的，后台也能拉起播放页，成功率比 DLNA 高
            if (nasDevices.isNotEmpty()) {
                Text(
                    "NAS 在线 · 任意网络可投",
                    color = Color.White.copy(alpha = 0.35f),
                    fontSize = ProomSp(10.sp),
                    modifier = Modifier.padding(top = 12.dp, bottom = 2.dp),
                )
                LazyColumn(Modifier.heightIn(max = 210.dp)) {
                    items(nasDevices) { t ->
                        val active = casting && t.name == current
                        val st = t.state
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = 2.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(
                                    if (active) ProomColor.accentSoft else Color.White.copy(alpha = 0.06f)
                                )
                                .clickable { onPickNas(t) }
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
                                    t.name,
                                    color = Color.White,
                                    fontSize = ProomSp(14.sp),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                // 电视在播什么直接摊开：用户开口常是「刚才那集接着放」，
                                // 让他先看见进度，比投过去再回手机上找位置省事
                                val sub = when {
                                    st == null || st.title.isBlank() -> "空闲"
                                    else -> {
                                        val m = st.position / 60_000
                                        val s = (st.position / 1_000) % 60
                                        val flag = if (st.playing) "正在播" else "暂停在"
                                        val clock = "$m:" + if (s < 10) "0$s" else "$s"
                                        "$flag · ${st.title} $clock"
                                    }
                                }
                                Text(
                                    sub,
                                    color = Color.White.copy(alpha = 0.45f),
                                    fontSize = ProomSp(11.sp),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(top = 2.dp),
                                )
                            }
                            if (active || t.owner == com.projectionroom.shared.cast.CastHub.selfId) {
                                // owner 是自己但 active 为假：退出播放页又进来，
                                // castNas 还没恢复，电视其实仍在替自己播
                                Text("投屏中", color = ProomColor.accent, fontSize = ProomSp(11.sp))
                            } else if (t.owner.isNotBlank()) {
                                // 另一台手机正在控它：点上去会抢过来，先给个提示
                                Text("被占用", color = Color.White.copy(alpha = 0.35f), fontSize = ProomSp(10.sp))
                            }
                        }
                    }
                }
            }

            // 同网直连（PROOM-CAST/1）：两端都是我们的 App，所以能遥控倍速/音量，
            // 又不经过 NAS —— NAS 关机、只连着家里 Wi-Fi 时，这一组是唯一能全功能投的
            if (lanDevices.isNotEmpty()) {
                Text(
                    "同网直连 · 支持倍速/音量遥控",
                    color = Color.White.copy(alpha = 0.35f),
                    fontSize = ProomSp(10.sp),
                    modifier = Modifier.padding(top = 12.dp, bottom = 2.dp),
                )
                LazyColumn(Modifier.heightIn(max = 200.dp)) {
                    items(lanDevices) { d ->
                        val active = casting && d.name == current
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = 2.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(
                                    if (active) ProomColor.accentSoft else Color.White.copy(alpha = 0.06f)
                                )
                                .clickable { onPickLan(d) }
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
                                    fontSize = ProomSp(14.sp),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                val sub = when {
                                    d.title.isBlank() -> "空闲 · 自定义协议直连"
                                    else -> {
                                        val m = d.position / 60_000
                                        val s = (d.position / 1_000) % 60
                                        val flag = if (d.playing) "正在播" else "暂停在"
                                        val clock = "$m:" + if (s < 10) "0$s" else "$s"
                                        "$flag · ${d.title} $clock"
                                    }
                                }
                                Text(
                                    sub,
                                    color = Color.White.copy(alpha = 0.45f),
                                    fontSize = ProomSp(11.sp),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(top = 2.dp),
                                )
                            }
                            if (active) {
                                Text("投屏中", color = ProomColor.accent, fontSize = ProomSp(11.sp))
                            }
                        }
                    }
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
                                    fontSize = ProomSp(14.sp),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                // 自家电视排在前面且标明来路：它认 x-proom-* 请求头，
                                // 源站要鉴权的片子也能投；别家电视只能碰运气
                                val sub = when {
                                    d.isOwn -> "放映室 · 支持直投"
                                    else -> d.model
                                }
                                if (sub.isNotBlank()) {
                                    Text(
                                        sub,
                                        color = if (d.isOwn) ProomColor.accent.copy(alpha = 0.7f)
                                        else Color.White.copy(alpha = 0.4f),
                                        fontSize = ProomSp(11.sp),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.padding(top = 2.dp),
                                    )
                                }
                            }
                            if (active) {
                                Text("投屏中", color = ProomColor.accent, fontSize = ProomSp(11.sp))
                            }
                        }
                    }
                }
            }

            // 手动地址是**兜底**，不是正常路径：默认收起，由头部的输入图标唤起。
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
                            fontSize = ProomSp(13.sp),
                        ),
                        cursorBrush = androidx.compose.ui.graphics.SolidColor(ProomColor.accent),
                        modifier = Modifier.weight(1f).padding(vertical = 12.dp),
                        decorationBox = { inner ->
                            if (manual.isEmpty()) {
                                Text(
                                    "电视地址，如 192.168.1.8 或 192.168.1.8:49520",
                                    color = Color.White.copy(alpha = 0.3f),
                                    fontSize = ProomSp(12.sp),
                                )
                            }
                            inner()
                        },
                    )
                    Text(
                        "连接",
                        color = ProomColor.accent,
                        fontSize = ProomSp(13.sp),
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { onManual(manual) }
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                    )
                }
            }

            if (casting) {
                Text(
                    "停止投屏",
                    color = Color(0xFFFF6B6B),
                    fontSize = ProomSp(13.sp),
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

/** 投屏面板头部图标的统一色：跟「✕」同一个亮度档，不抢主题色 */
private val IconTint = Color.White.copy(alpha = 0.65f)

/**
 * 面板头部的小图标按钮：和「✕」一样的点击区，没有底色，只有图标。
 *
 * 点击区从 32dp 提到 48dp：这是个只靠图标表达的工具按钮，手指压不准就会
 * 点到旁边的「输入电视地址」上去，而两者行为完全不同（一个是开相机）。
 * 视觉尺寸没变，多出来的部分靠 padding 撑，不占额外版面。
 *
 * 图标不带文字，所以必须给 [label]：读屏用户只靠它知道这是「扫码」还是「填地址」，
 * 顺带也让自动化测试点得中（没有 text 的节点只能靠坐标猜）。
 */
@Composable
private fun CastIconButton(
    label: String,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    Box(
        Modifier
            .size(48.dp)
            .clip(CircleShape)
            .semantics { contentDescription = label }
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { content() }
}

/**
 * 扫码图标：四个取景角 + 右下角一个小方块。
 *
 * 项目里没有 material-icons-extended（那包有几 MB），为一个图标不值当，
 * 二维码取景框又简单，直接画。
 */
@Composable
private fun ScanGlyph(tint: Color, box: androidx.compose.ui.unit.Dp = 17.dp) {
    Canvas(Modifier.size(box)) {
        val w = size.width
        val sw = w * 0.11f
        val p = w * 0.07f
        val len = w * 0.30f
        fun seg(x1: Float, y1: Float, x2: Float, y2: Float) =
            drawLine(
                tint,
                androidx.compose.ui.geometry.Offset(x1, y1),
                androidx.compose.ui.geometry.Offset(x2, y2),
                sw,
                androidx.compose.ui.graphics.StrokeCap.Round,
            )
        seg(p, p + len, p, p)
        seg(p, p, p + len, p)
        seg(w - p - len, p, w - p, p)
        seg(w - p, p, w - p, p + len)
        seg(p, w - p - len, p, w - p)
        seg(p, w - p, p + len, w - p)
        val q = len * 0.85f
        drawRect(
            tint,
            topLeft = androidx.compose.ui.geometry.Offset(w - p - q, w - p - q),
            size = androidx.compose.ui.geometry.Size(q, q),
        )
    }
}

/** 手填地址图标：一个输入框 + 一根光标 —— 比「铅笔」更贴「输入 IP」这件事 */
@Composable
private fun TypeGlyph(tint: Color, box: androidx.compose.ui.unit.Dp = 17.dp) {
    Canvas(Modifier.size(box)) {
        val w = size.width
        val sw = w * 0.10f
        drawRoundRect(
            tint,
            topLeft = androidx.compose.ui.geometry.Offset(w * 0.05f, w * 0.18f),
            size = androidx.compose.ui.geometry.Size(w * 0.90f, w * 0.64f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * 0.16f, w * 0.16f),
            style = androidx.compose.ui.graphics.drawscope.Stroke(sw),
        )
        drawLine(
            tint,
            androidx.compose.ui.geometry.Offset(w * 0.28f, w * 0.34f),
            androidx.compose.ui.geometry.Offset(w * 0.28f, w * 0.66f),
            sw * 1.2f,
            androidx.compose.ui.graphics.StrokeCap.Round,
        )
        drawLine(
            tint,
            androidx.compose.ui.geometry.Offset(w * 0.42f, w * 0.42f),
            androidx.compose.ui.geometry.Offset(w * 0.72f, w * 0.42f),
            sw * 0.9f,
            androidx.compose.ui.graphics.StrokeCap.Round,
        )
        drawLine(
            tint,
            androidx.compose.ui.geometry.Offset(w * 0.42f, w * 0.58f),
            androidx.compose.ui.geometry.Offset(w * 0.60f, w * 0.58f),
            sw * 0.9f,
            androidx.compose.ui.graphics.StrokeCap.Round,
        )
    }
}

/* ─────────────────────── 片头 / 片尾药丸 ─────────────────────── */

/**
 * 浮在画面右下角的小药丸。
 *
 * 为什么不用底栏圆钮：跳过片头最想按的时刻是「片头正在放、控制层已收起」，
 * 圆钮这时候根本看不见。所以它必须独立于控制层挂在画面上。
 *
 * 电视端靠 [focusable] 进焦点链，但**不主动抢**焦点 —— 播放中按 OK 仍是播放/暂停，
 * 用户按方向键走到这里才会出现强调色边框。
 */
@Composable
private fun SkipPill(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onDismiss: (() -> Unit)? = null,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(24.dp)
    Row(
        modifier
            .clip(shape)
            .background(Color.Black.copy(alpha = 0.74f))
            .border(
                width = if (focused) 2.dp else 1.dp,
                color = if (focused) ProomColor.accent else Color.White.copy(alpha = 0.20f),
                shape = shape,
            )
            .focusable()
            .onFocusChanged { focused = it.isFocused }
            .clickable { onClick() }
            .padding(start = 18.dp, end = if (onDismiss != null) 10.dp else 18.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            color = Color.White,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
        )
        if (onDismiss != null) {
            Spacer(Modifier.width(8.dp))
            Box(
                Modifier
                    .clip(CircleShape)
                    .clickable { onDismiss() }
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            ) {
                Text(
                    text = "✕",
                    color = Color.White.copy(alpha = 0.62f),
                    fontSize = 12.sp,
                )
            }
        }
    }
}
