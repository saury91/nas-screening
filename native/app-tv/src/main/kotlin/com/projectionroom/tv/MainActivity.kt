package com.projectionroom.tv

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.togetherWith
import androidx.compose.ui.unit.IntOffset
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import com.projectionroom.shared.catalog.ConfigBundle
import com.projectionroom.shared.catalog.NasApi
import com.projectionroom.shared.model.Episode
import com.projectionroom.shared.model.Line
import com.projectionroom.shared.model.Vod
import com.projectionroom.shared.net.CachingHttp
import com.projectionroom.shared.net.OkHttpHttp
import com.projectionroom.ui.screen.BrowseScreen
import com.projectionroom.ui.screen.ConnectScreen
import com.projectionroom.ui.screen.LiveScreen
import com.projectionroom.ui.screen.MusicScreen
import com.projectionroom.ui.screen.PlaylistScreen
import com.projectionroom.ui.screen.DetailScreen
import com.projectionroom.ui.screen.PlayerScreen
import com.projectionroom.ui.screen.AppUpdate
import com.projectionroom.ui.screen.MineChannel
import com.projectionroom.ui.screen.PairScreen
import com.projectionroom.ui.screen.PairState
import com.projectionroom.ui.screen.MineTrack
import com.projectionroom.ui.screen.SettingsScreen
import com.projectionroom.ui.screen.SettingsState
import com.projectionroom.ui.screen.TvHome
import com.projectionroom.ui.screen.TvMineScreen
import com.projectionroom.ui.screen.SearchScreen
import com.projectionroom.ui.theme.ProomColor
import com.projectionroom.app.FeedCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.onEmpty
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.projectionroom.ui.theme.ProomRoot
import com.projectionroom.ui.theme.ProomType
import com.projectionroom.app.ConfigStore
import com.projectionroom.app.DetailCache
import com.projectionroom.app.Discovery
import com.projectionroom.app.ExtraConfig
import com.projectionroom.app.FileCache
import com.projectionroom.app.HomeCache
import com.projectionroom.app.LibraryStore
import com.projectionroom.app.LibrarySync
import com.projectionroom.app.MusicService
import com.projectionroom.app.NasGate
import com.projectionroom.app.NasStore
import com.projectionroom.app.Pair
import com.projectionroom.app.PairServer
import com.projectionroom.app.SearchStore
import com.projectionroom.app.ExtraStore
import com.projectionroom.app.HomeSnapshot
import com.projectionroom.app.LibFile
import com.projectionroom.app.PairTarget
import com.projectionroom.app.ShareConfig
import com.projectionroom.app.SubEntry
import com.projectionroom.app.SubResolver
import com.projectionroom.app.mergeLive

/**
 * 原生壳的第一版导航：首页 → 详情 → 播放。
 *
 * 只有三个状态，先用最简单的 sealed class 顶着 —— 等页面多到五六屏、
 * 需要返回栈和深链接时再换 Navigation，不值得为三屏引入一整套依赖。
 */
private sealed interface Screen {
    data object Home : Screen
    data class Search(val from: Screen = Home) : Screen
    data class Browse(val slug: String, val from: Screen = Home) : Screen
    data class Playlist(val id: String, val from: Screen = Home) : Screen
    data class Detail(val vod: Vod, val from: Screen = Home) : Screen
    data class Play(
        val vod: Vod,
        val lineIndex: Int,
        val epIndex: Int,
        val from: Screen,
        /** 直播专用：每一台的备用源，空表示这是点播 */
        val liveUrls: List<List<String>> = emptyList(),
        /** 联调用：进去就展开投屏面板（`-e screen cast`，仅 debuggable 包） */
        val openCast: Boolean = false,
    ) : Screen
    /** 全屏音乐播放器：内容全部来自 MusicBox，这里只记「从哪来」 */
    data class MusicPlayer(val from: Screen = Home) : Screen
    /** 换 NAS 地址：App 里唯一能改的设置 */
    data class Connect(val from: Screen = Home) : Screen
    /** 收藏 + 播放记录（电视端从顶栏人形图标进） */
    data class Mine(val from: Screen = Home) : Screen
    /** 设置：NAS / 同步 / 存储 / 版本 */
    data class Settings(val from: Screen = Home) : Screen
    /** 电视端：显示配对二维码，等手机把配置推过来（原来的「扫码页」是手机端的，
     *  电视上渲染成一屏空白，已经删掉；深链扫一扫直接落到这里） */
    data class Pair(val from: Screen = Home) : Screen
}

private data class LivePlay(
    /** 一台一集：给播放器做「上一台 / 下一台」 */
    val vod: Vod,
    /** 每一台的备用源（下标 = 台号），第一条挂了自动换下一条 */
    val urls: List<List<String>>,
    /** 起始台号 */
    val index: Int,
)

/**
 * 直播的「片子」模型：一条线路，每个台就是一集。
 *
 * 这样不用给播放器另造一套数据结构 —— 它的「上一集/下一集」天然就是
 * 「上一台/下一台」，右侧抽屉的「频道」tab 也就是整张频道表。
 * 索引必须和频道表一一对应（不过滤），否则点了第 5 个台却播第 4 个。
 */
private fun livePlay(chs: List<NasApi.LiveChannel>, index: Int): LivePlay = LivePlay(
    vod = Vod(
        name = "电视直播",
        lines = listOf(
            Line(
                site = "直播",
                episodes = chs.map { Episode(it.name, it.urls.firstOrNull().orEmpty()) },
            )
        ),
    ),
    urls = chs.map { it.urls },
    index = index,
)

/**
 * 一级 tab 的内容区：首页 / 片库（含电影、剧集、动漫、综艺、纪录片、直播、音乐）。
 *
 * 这几种之间切换**不是**「前进」，所以转场用淡入淡出而不是横滑 ——
 * 横滑会让人以为进了一个新页面，然后又找不到返回。
 */
private fun isTabScreen(s: Screen): Boolean = s is Screen.Home || s is Screen.Browse

/** 页面层级：决定转场方向（变大=前进） */
private fun depth(s: Screen): Int = when (s) {
    is Screen.Home -> 0
    is Screen.Browse, is Screen.Search, is Screen.Playlist, is Screen.Connect, is Screen.Mine -> 1
    is Screen.Settings, is Screen.Pair -> 2
    is Screen.Detail -> 2
    is Screen.Play, is Screen.MusicPlayer -> 3
}

/** 没配置 NAS 时的占位地址：本地片源用不到它，只是为了构造对象不崩 */
private const val OFFLINE_BASE = "http://127.0.0.1:1"


class MainActivity : ComponentActivity() {

    /**
     * 联调用：广播直接触发投屏（仅 debuggable 包）。
     *
     * 横屏播放器里 adb 的点击坐标常对不上，端到端验证协议链路需要一条
     * 不依赖 UI 的入口：
     *   adb shell am broadcast -a com.projectionroom.app.DEBUG_CAST \
     *     --es host 10.0.2.2:49600 --es url http://a/b.m3u8 -p com.projectionroom.app
     */
    private fun registerCastDebugHook() {
        val debuggable = (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
        if (!debuggable) return
        val filter = android.content.IntentFilter("com.projectionroom.app.DEBUG_CAST")
        registerReceiver(object : android.content.BroadcastReceiver() {
            override fun onReceive(ctx: android.content.Context, intent: android.content.Intent) {
                val host = intent.getStringExtra("host")
                val pick = intent.getStringExtra("device")
                val url = intent.getStringExtra("url") ?: "http://example.com/demo.m3u8"
                kotlinx.coroutines.MainScope().launch {
                    val client = com.projectionroom.shared.cast.DlnaClient()
                    // 没有 host 就走真实 SSDP 发现（验证两台设备在同一网段）
                    val d = if (host != null) {
                        client.describe("http://$host/description.xml")
                    } else {
                        val list = client.discover()
                        android.util.Log.i(
                            "CASTDBG",
                            "scan -> " + list.joinToString { "${it.name}|${it.model}|${it.controlUrl}" },
                        )
                        list.firstOrNull { pick == null || it.name.contains(pick) }
                    }
                    android.util.Log.i("CASTDBG", "target -> $d")
                    if (d != null) {
                        val r = client.cast(d, url, "投屏联调")
                        android.util.Log.i("CASTDBG", "cast ok=${r.isSuccess} err=${r.exceptionOrNull()}")
                        android.util.Log.i("CASTDBG", "state=${client.state(d)}")
                    }
                }
            }
        }, filter, android.content.Context.RECEIVER_EXPORTED)
    }



    companion object {
        /** 落地页指令：`am start -n com.projectionroom.app/.MainActivity -e screen pair` */
        const val EXTRA_SCREEN = "screen"
    }


    override fun onCreate(savedInstanceState: Bundle?) {
        // 联调入口：`am start … -e nas http://10.0.2.2:8899` 直接指定 NAS。
        // 必须在下面的 CastService.start 之前落盘 —— 投屏服务启动时才去读地址，
        // 放到 readRoute 里就晚了一步，服务已经按旧值（或空）起来了。
        intent?.getStringExtra("nas")?.takeIf { it.isNotBlank() }?.let {
            com.projectionroom.app.NasStore.save(this, it)
        }

        // DLNA 接收端只在电视端开启。交给前台服务而不是 Activity：
        // 用户按 Home 回到桌面后，这台电视仍然要能被手机搜到
        com.projectionroom.app.cast.CastService.start(this)

        // 通知权限交给 Compose 层的说明卡去要（见下面的 askNotif）：
        // 这个权限确实关键 —— Android 13+ 没有它，投屏时「后台拉起播放页」的兜底通知
        // 发不出来，表现为手机显示已投屏、电视毫无反应 —— 但一开机就甩个系统框，
        // 用户既不知道它干什么，也没得选

        registerCastDebugHook()

        super.onCreate(savedInstanceState)
        // 内置配置：装完就有片源，不必先连 NAS（只在本地还没有配置时生效）
        ConfigStore.seedFromAssets(this)
        // Debug 包开 StrictMode：主线程一碰磁盘/网络就在 logcat 里留一条
        // "StrictMode policy violation"，不用等 ANR 才发现。
        val debuggable = (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
        if (debuggable) {
            android.os.StrictMode.setThreadPolicy(
                android.os.StrictMode.ThreadPolicy.Builder()
                    .detectDiskReads()
                    .detectDiskWrites()
                    .detectNetwork()
                    .penaltyLog()
                    .build()
            )
        }
        // 音乐通知栏：起一个 MediaSessionService，播放时自动转前台并出通知。
        // 在 onCreate 里启动属于「前台场景」，不会触发后台服务限制。
        runCatching { startService(android.content.Intent(this, MusicService::class.java)) }

        // 预热所有 SharedPreferences：首次 getString 会在主线程解析整个 XML。
        // 放后台线程做，连目录 stat 都不占主线程（StrictMode 里这几次都是 warmUp 自己）。
        Thread {
            NasStore.warmUp(this)
            SearchStore.warmUp(this)
            com.projectionroom.ui.music.MusicLib.warmUp(this)
            com.projectionroom.ui.live.LiveLib.warmUp(this)
            // 播放器的断点续播表：条目按「每集 URL」记，可能几百条，
            // 第一次进播放器才加载会卡一下，这里先让它在后台解析完
            getSharedPreferences("proom_progress", MODE_PRIVATE)
        }.start()
        // 调试用：adb ... --ez forcePhone true 可在电视上预览手机布局
        // 系统栏高度：onCreate 里 rootWindowInsets 可能还是 null，先按资源兜底，
        // 拿到真实 insets 后再校正（不然直接解引用会崩在启动）
        fun statusBarPx(): Int {
            val id = resources.getIdentifier("status_bar_height", "dimen", "android")
            return if (id > 0) resources.getDimensionPixelSize(id) else 0
        }
        val sysInsets = window.decorView.rootWindowInsets?.let {
            androidx.core.view.WindowInsetsCompat.toWindowInsetsCompat(it, window.decorView)
                .getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        }
        val d = resources.displayMetrics.density
        var barTop = ((sysInsets?.top ?: statusBarPx()) / d).dp
        var barBottom = ((sysInsets?.bottom ?: 0) / d).dp

        // 关键：让 Compose 的内容**画到系统栏后面**。
        // 只把 statusBarColor 设成透明是不够的 —— DecorView 默认仍会把内容限制在
        // 状态栏下方，那一圈露出来的是 window 背景，就成了「一条黑边」。
        // 关掉 decorFits 之后，各页面用传入的 topInset 自己避让内容。
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)
            .isAppearanceLightStatusBars = false
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = 0xFF07080B.toInt()
        // 手势条底下不再自动加一层半透明兜底，否则底部 tabbar 上会多一条灰带
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            window.isNavigationBarContrastEnforced = false
        }

        // 电视恒锁横屏
        requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE

        // 通知栏点歌名进来：直接把落地页设成全屏播放器
        readRoute(intent)

        val versionName = runCatching {
            packageManager.getPackageInfo(packageName, 0).versionName
        }.getOrNull()

        // 启动耗时打点：am start -W 在慢设备/模拟器上受系统调度影响太大，
        // 这里直接量「onCreate 到第一帧」的 App 侧开销
        val bootT0 = android.os.SystemClock.uptimeMillis()
        setContent {
            if (debuggable) {
                androidx.compose.runtime.LaunchedEffect(Unit) {
                    android.view.Choreographer.getInstance().postFrameCallback {
                        android.util.Log.i(
                            "PROOM_BOOT",
                            "首帧 ${android.os.SystemClock.uptimeMillis() - bootT0}ms",
                        )
                    }
                }
            }
            ProomRoot(isTv = true) {
                val scopeTop = rememberCoroutineScope()
                // NAS 地址存本地；第一次启动先在局域网里找，找不到才让用户手填
                var baseUrl by remember { mutableStateOf(NasStore.url(this@MainActivity)) }
                // 用户跳过过连接：不再用输入地址页拦启动，地址入口只在设置里
                var skipped by remember { mutableStateOf(NasStore.skipped(this@MainActivity)) }
                var searching by remember { mutableStateOf(baseUrl == null) }
                var findErr by remember { mutableStateOf<String?>(null) }

                LaunchedEffect(Unit) {
                    if (baseUrl != null) return@LaunchedEffect
                    searching = true
                    val found = withContext(Dispatchers.IO) { Discovery.find() }
                    searching = false
                    if (found != null) {
                        NasStore.save(this@MainActivity, found)
                        baseUrl = found
                    } else {
                        findErr = "没有在局域网里找到放映室，确认 NAS 开机后可以手动填地址"
                    }
                }

                // 通知权限：不在开机时用系统框糊脸。先给自己的卡片讲清用途
                //（这条通知负责投屏时拉起播放页、后台播放控制），用户可以「暂不开启」
                var askNotif by remember { mutableStateOf(false) }
                LaunchedEffect(Unit) {
                    if (android.os.Build.VERSION.SDK_INT < 33) return@LaunchedEffect
                    if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
                        android.content.pm.PackageManager.PERMISSION_GRANTED
                    ) {
                        return@LaunchedEffect
                    }
                    if (getSharedPreferences("proom_perm", MODE_PRIVATE)
                            .getBoolean("notif_asked", false)
                    ) {
                        return@LaunchedEffect
                    }
                    askNotif = true
                }
                if (askNotif) {
                    androidx.compose.ui.window.Dialog(onDismissRequest = { askNotif = false }) {
                        com.projectionroom.ui.component.PermissionCard(
                            title = "开个通知，投屏才叫得醒电视",
                            message = "手机往这台电视投屏时，靠它把播放页拉起来。不发广告，也不打扰。",
                            icon = { tint -> com.projectionroom.ui.component.BellGlyph(tint, 26.dp) },
                            primary = "开启通知",
                            onPrimary = {
                                askNotif = false
                                getSharedPreferences("proom_perm", MODE_PRIVATE).edit()
                                    .putBoolean("notif_asked", true).apply()
                                runCatching {
                                    requestPermissions(
                                        arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),
                                        1001,
                                    )
                                }
                            },
                            secondary = "暂不开启",
                            onSecondary = {
                                askNotif = false
                                getSharedPreferences("proom_perm", MODE_PRIVATE).edit()
                                    .putBoolean("notif_asked", true).apply()
                            },
                        )
                    }
                }

                if (baseUrl == null && !skipped) {
                    ConnectScreen(
                        searching = searching,
                        hint = findErr,
                        onRetry = {
                            scopeTop.launch {
                                searching = true
                                findErr = null
                                val found = withContext(Dispatchers.IO) { Discovery.find() }
                                searching = false
                                if (found != null) {
                                    NasStore.save(this@MainActivity, found)
                                    baseUrl = found
                                } else {
                                    findErr = "还是没找到，检查手机和 NAS 是否在同一网段"
                                }
                            }
                        },
                        onSkip = {
                            NasStore.setSkipped(this@MainActivity, true)
                            skipped = true
                        },
                        onConnect = { raw ->
                            val url = NasStore.normalize(raw)
                            if (url == null) {
                                findErr = "地址格式不对，示例：192.0.2.10:8899"
                            } else {
                                NasStore.save(this@MainActivity, url)
                                NasStore.setSkipped(this@MainActivity, false)
                                baseUrl = url
                            }
                        },
                    )
                    return@ProomRoot
                }

                // 超时给 30 秒：一次搜索要问 60+ 个源，实测 13~15 秒，10 秒会直接 timeout
                val api = remember(baseUrl) {
                    // 缓存优先：同一个接口第二次进来先用本地，后台再更新
                    val http = CachingHttp(
                        delegate = OkHttpHttp(timeoutSeconds = 30),
                        cache = FileCache(java.io.File(cacheDir, "api")),
                        scope = scopeTop,
                        // 缓存文件最大 1.2MB，读它必须是 IO 线程：
                        // 之前在 LaunchedEffect 里同步读，主线程一次卡 5 秒直接被判 ANR
                        io = Dispatchers.IO,
                    )
                    NasApi(http, baseUrl = baseUrl ?: OFFLINE_BASE)
                }
                /**
                 * 「真实」的 NAS 客户端：**不走磁盘缓存**。
                 *
                 * 配置包和收藏同步要的是「NAS 现在到底是什么」，而不是「上次看到什么」。
                 * 走 [CachingHttp] 时缓存命中就直接返回，10 分钟内根本不问网络 ——
                 * 表现就是：NAS 上改了配置（换音乐接口、改片源），手机怎么点都还是旧的那份。
                 */
                val rawApi = remember(baseUrl) {
                    NasApi(OkHttpHttp(timeoutSeconds = 12), baseUrl = baseUrl ?: OFFLINE_BASE)
                }
                // ── 本地配置包 ──
                // 片源清单、直播表、版本号都在这里。启动时先读本地（开机就能用），
                // 再在后台向 NAS 要新的；版本没变就不重写。
                // 初始为空、在 IO 线程读：这份 JSON 有两百多 KB，
                // 同步读+解析会把主线程顶住（ANR trace 里就是这条路径）
                var bundle by remember { mutableStateOf<ConfigBundle?>(null) }
                // 设置页的「立即同步配置」靠它重跑（LaunchedEffect 的 key 变了才会重新执行）
                var configTick by remember { androidx.compose.runtime.mutableIntStateOf(0) }
                // 本次启动是什么时候拿到配置的，设置页显示用
                var bundleAt by remember { mutableStateOf(0L) }
                LaunchedEffect(api, configTick) {
                    if (bundle == null) {
                        bundle = withContext(Dispatchers.IO) {
                            ConfigStore.read(this@MainActivity)
                        }
                        bundleAt = System.currentTimeMillis()
                    }
                    // NAS 可能永远不会再开机：拉不到就拉倒，本地配置照用
                    val fresh = NasGate.tryCall(timeoutMs = 8_000) {
                        runCatching { rawApi.bundle() }.getOrNull()
                    }
                    // 比对整包内容，而不是只比 version。
                    // 版本号只覆盖「NAS 那边内容变了」；还有一种情况是「App 这边解析器升级了」——
                    // 比如新版本才开始读 music 段，而本地那份是旧解析器存的、同一个 version。
                    // 只比 version 就永远补不上这个字段（真机实测踩过）。ConfigBundle 是 data class，
                    // 88KB 的结构比较是这个调用里最便宜的一步。
                    if (fresh != null && fresh.sources.isNotEmpty() && fresh != bundle) {
                        withContext(Dispatchers.IO) { ConfigStore.save(this@MainActivity, fresh) }
                        bundle = fresh
                        bundleAt = System.currentTimeMillis()
                    }
                }

                // 本地聚合用的网络：不走 NAS，直连采集站。
                // 超时给 12 秒：一次搜 60+ 个源，8 秒常在最后几个源上截断。
                val sourceHttp = remember { OkHttpHttp(timeoutSeconds = 12) }

                // NAS 之外的附加配置（TVBox 订阅 / 别人分享的配置包）：
                // 有它就不需要 NAS —— 这是「一份配置发给朋友」能成立的关键
                var extra by remember { mutableStateOf(ExtraConfig()) }
                LaunchedEffect(Unit) {
                    extra = withContext(Dispatchers.IO) { ExtraStore.load(this@MainActivity) }
                }
                // NAS 下发的源 + 订阅解析出的源：搜索/浏览/首页都用这一份
                val allSources = remember(bundle, extra) {
                    ((bundle?.sources ?: emptyList()) + extra.sources).distinctBy { it.api }
                }

                /**
                 * 给界面用的 api：**带着本地片源**。
                 *
                 * 影视页的分类（电影/剧集/纪录片…）走 [NasApi.browse]，
                 * 一旦 localSources 非空，它就直连采集站，完全不碰 NAS ——
                 * 这是"NAS 关机也能刷分类"的关键（之前这里报 Failed to connect）。
                 */
                val uiApi = remember(baseUrl, allSources) {
                    NasApi(sourceHttp, baseUrl ?: "", allSources)
                }

                /**
                 * 搜索：**先本地**（直连所有源，并发合并），本地一条都没搜到才问 NAS。
                 *
                 * 这样 NAS 关机时搜索照常能用；NAS 在线时也基本用不到它。
                 */
                val doSearch: suspend (String) -> List<com.projectionroom.shared.model.Vod> =
                    remember(allSources) {
                        { kw ->
                            val srcs = allSources
                            val local = if (srcs.isNotEmpty()) {
                                runCatching {
                                    // 必须切到 Default：解析 JSON、合并去重都是 CPU 活，
                                    // 留在 Main 上会把主线程压住（实测详情页能压出 ANR）
                                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                                        com.projectionroom.shared.catalog.Catalog(
                                            sourceHttp,
                                            (bundle?.badPicHosts.orEmpty() + com.projectionroom.ui.component.BadPicHosts.all()),
                                        )
                                            // 71 个源一起查，9 秒常在最后一批上截断，
                                            // 结果就是「明明有却搜不到」。给到 12 秒。
                                            .search(kw, srcs, deadlineMs = 12_000)
                                    }
                                }.getOrDefault(emptyList())
                            } else emptyList()
                            // 兜底问 NAS。NAS 永久离线时靠 NasGate 熔断，不会每次都白等
                            local.ifEmpty {
                                NasGate.tryCall { runCatching { api.search(kw) }.getOrNull() }
                                    ?: emptyList()
                            }
                        }
                    }

                /** 详情：先本地（同名合并出线路），拿不到再问 NAS */
                val doDetail: suspend (String, String) -> com.projectionroom.shared.model.Vod? =
                    remember(allSources) {
                        { name, year ->
                            val srcs = allSources
                            val local = if (srcs.isNotEmpty()) {
                                runCatching {
                                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                                        // 详情不用问遍所有源：挑前 30 个足够「同名合并」，
                                        // 71 个全查只会让点开详情的那一下更卡
                                        com.projectionroom.shared.catalog.Catalog(
                                            sourceHttp,
                                            (bundle?.badPicHosts.orEmpty() + com.projectionroom.ui.component.BadPicHosts.all()),
                                        )
                                            .detail(name, year, srcs.take(30), deadlineMs = 8_000)
                                    }
                                }.getOrNull()
                            } else null
                            local ?: NasGate.tryCall { runCatching { api.detail(name, year) }.getOrNull() }
                        }
                    }

                /**
                 * 详情（流式）：每个源一回来就发一版，线路一条条冒出来。
                 *
                 * 和 [doDetail] 的分工：doDetail 是「一次给全」，播放页的立即播放要用它
                 * （要等线路齐才知道第 0 条能不能播）；详情页用这个，先把能显示的显示了。
                 * 本地一个源都没命中才退回 NAS，NAS 只有一版，一次性 emit。
                 */
                val doDetailStream: (String, String) -> Flow<com.projectionroom.shared.model.Vod> =
                    remember(allSources) {
                        { name, year ->
                            val srcs = allSources
                            val local: Flow<com.projectionroom.shared.model.Vod> =
                                if (srcs.isEmpty()) {
                                    kotlinx.coroutines.flow.emptyFlow()
                                } else {
                                    com.projectionroom.shared.catalog.Catalog(
                                            sourceHttp,
                                            (bundle?.badPicHosts.orEmpty() + com.projectionroom.ui.component.BadPicHosts.all()),
                                        )
                                        .detailStream(name, year, srcs.take(30), deadlineMs = 8_000)
                                        .flowOn(Dispatchers.Default)
                                }
                            local.onEmpty {
                                NasGate.tryCall { runCatching { api.detail(name, year) }.getOrNull() }
                                    ?.let { emit(it) }
                            }
                        }
                    }

                // ── 音乐（上游聚合接口，App 直连）──
                // 接口公开、无签名，只有聚合域名校验 Referer；CDN 本身不校验，
                // 所以取到 CDN 地址后播放器可以直接播，NAS 完全不参与。
                // 聚合接口地址由配置包给出（NAS 改环境变量重发配置就换域名），
                // 没下发才用内置默认 —— 所以没连过 NAS 的手机也有歌听。
                val musicApi = remember(bundle?.music?.api, bundle?.music?.referer) {
                    val cfg = bundle?.music
                    com.projectionroom.shared.catalog.MusicApi(
                        http = sourceHttp,
                        base = cfg?.api?.takeIf { it.isNotBlank() }
                            ?: com.projectionroom.shared.catalog.MusicApi.DEFAULT_BASE,
                        referer = cfg?.referer?.takeIf { it.isNotBlank() }
                            ?: com.projectionroom.shared.catalog.MusicApi.REFERER,
                    )
                }
                /**
                 * 音乐分类 / 歌单：磁盘缓存先出，再打源站。
                 * 返回 Flow 的理由和片库一样 —— 冷启动不该对着空页等网络。
                 */
                val doMusicCats: () -> Flow<List<NasApi.MusicCatGroup>> = {
                    flow {
                        val ser = ListSerializer(NasApi.MusicCatGroup.serializer())
                        val key = "music:cats"
                        val ctx = this@MainActivity
                        val cached = withContext(Dispatchers.IO) { FeedCache.read(ctx, key, ser) }
                        if (cached != null) emit(cached.value)
                        if (cached != null &&
                            FeedCache.isFresh(cached.at, FeedCache.FRESH_MS)
                        ) {
                            return@flow
                        }
                        val fresh = runCatching { musicApi.categories() }.getOrDefault(emptyList())
                            .ifEmpty {
                                NasGate.tryCall {
                                    runCatching { api.musicCats() }.getOrNull()
                                } ?: emptyList()
                            }
                        if (fresh.isNotEmpty()) {
                            emit(fresh)
                            withContext(Dispatchers.IO) {
                                FeedCache.write(ctx, key, System.currentTimeMillis(), fresh, ser)
                            }
                        }
                    }
                }
                val doMusicLists: (String) -> Flow<List<NasApi.Playlist>> = { cat ->
                    flow {
                        val ser = ListSerializer(NasApi.Playlist.serializer())
                        val key = "music:list:$cat"
                        val ctx = this@MainActivity
                        val cached = withContext(Dispatchers.IO) { FeedCache.read(ctx, key, ser) }
                        if (cached != null) emit(cached.value)
                        if (cached != null &&
                            FeedCache.isFresh(cached.at, FeedCache.FRESH_MS)
                        ) {
                            return@flow
                        }
                        // 空分类 = 首屏「推荐」：直接给推荐歌单，别拿空字符串去查
                        val local = runCatching {
                            if (cat.isBlank()) musicApi.recommend(14) else musicApi.categoryPlaylists(cat)
                        }.getOrDefault(emptyList())
                        val fresh = local.ifEmpty {
                            NasGate.tryCall { runCatching { api.musicPlaylists(cat) }.getOrNull() }
                                ?: emptyList()
                        }
                        if (fresh.isNotEmpty()) {
                            emit(fresh)
                            withContext(Dispatchers.IO) {
                                FeedCache.write(ctx, key, System.currentTimeMillis(), fresh, ser)
                            }
                        }
                    }
                }
                val doMusicPlaylist: suspend (String) -> NasApi.PlaylistDetail = { id ->
                    runCatching { musicApi.playlist(id) }.getOrNull()
                        ?.takeIf { it.tracks.isNotEmpty() }
                        ?: NasGate.tryCall { runCatching { api.musicPlaylist(id) }.getOrNull() }
                        ?: NasApi.PlaylistDetail()
                }
                val doTrackUrl: suspend (String) -> String = { id ->
                    runCatching { musicApi.trackUrl(id) }.getOrDefault("")
                        .ifEmpty { NasGate.tryCall { runCatching { api.musicTrackUrl(id) }.getOrNull() } ?: "" }
                }

                // 音乐播放器是进程内单例：把「取播放地址」的能力注入一次，
                // 之后切歌/续播都由它自己拉地址（地址带时效，不能预取）。
                // key 用 musicApi：配置包晚到会换掉实例，注入的 lambda 必须跟着换，
                // 否则播放器一直用「配置到达前」那个内置地址。
                LaunchedEffect(musicApi) {
                    com.projectionroom.ui.music.MusicBox.install(
                        resolve = { id -> doTrackUrl(id) },
                        lyric = { id -> runCatching { musicApi.lyric(id) }.getOrDefault(emptyList()) },
                    )
                }

                // 有歌在放就确保前台服务活着。
                // onCreate 里只 startService 一次，那一次要是被系统挡掉（冷启动竞态），
                // 整场播放都没有通知栏控制 —— 真机踩过，界面上还看不出异常。
                LaunchedEffect(com.projectionroom.ui.music.MusicBox.current?.id) {
                    if (com.projectionroom.ui.music.MusicBox.current != null) {
                        MusicService.start(this@MainActivity)
                    }
                }

                /** 直播：配置包里就带着整张频道表，NAS 不在也能列 */
                /** 直播：磁盘缓存 → 本地配置包 / NAS。频道表几小时不变，缓存够新就不再回源 */
                val doLive: () -> Flow<NasApi.LiveFeed> = remember(bundle, extra) {
                    {
                        flow {
                            val ser = ListSerializer(NasApi.LiveFeed.serializer())
                            val key = "live"
                            val ctx = this@MainActivity
                            val cached = withContext(Dispatchers.IO) { FeedCache.read(ctx, key, ser) }
                            cached?.value?.firstOrNull()?.let { emit(it) }
                            if (cached != null &&
                                FeedCache.isFresh(cached.at, FeedCache.FRESH_MS)
                            ) {
                                return@flow
                            }
                            val base = runCatching {
                                bundle?.live?.takeIf { it.channels.isNotEmpty() } ?: api.live()
                            }.getOrNull() ?: return@flow
                            // 订阅来的频道并进来（同名台合并线路，不重复列两次）
                            val feed = mergeLive(base, extra.live)
                            emit(feed)
                            withContext(Dispatchers.IO) {
                                FeedCache.write(
                                    ctx, key, System.currentTimeMillis(), listOf(feed), ser,
                                )
                            }
                        }
                    }
                }

                /**
                 * 片库。三条数据源按顺序喂给界面：
                 *   1. 磁盘缓存 —— 冷启动第一帧就有海报，0 等待；
                 *   2. 各站流式聚合 —— 第一个源回来就先刷一屏，不等最慢那个 9 秒；
                 *   3. NAS —— 本地一个源都没配、或者全挂时的兜底。
                 *
                 * 返回 Flow 而不是单值，就是为了「先出缓存、再一个源一个源地长出来」。
                 */
                val doBrowse: (String, Int, String, String) -> Flow<NasApi.BrowsePage> =
                    remember(allSources) {
                        { cat, pg, y, a ->
                            flow {
                                val srcs = allSources
                                // 界面传进来的是 slug（movie/series/…），配置包里 cats 的 value 是中文。
                                // 少翻这一步，本地永远匹配不到 type_id → 静默回落 NAS（NAS 一关片库就空）
                                val catName = com.projectionroom.shared.catalog.Cats.nameOf(cat)
                                val sites = srcs.count { it.typeIdsOf(catName).isNotEmpty() }
                                val key = "browse:$cat:$pg:$y:$a"
                                val ser = ListSerializer(Vod.serializer())
                                val ctx = this@MainActivity

                                fun page(items: List<Vod>) = NasApi.BrowsePage(
                                    items = items,
                                    total = items.size,
                                    sites = sites,
                                    // 本地不知道总共多少页：够一屏就认为还有下一页
                                    more = items.size >= 40,
                                )

                                // 1) 缓存先上屏
                                val cached = withContext(Dispatchers.IO) {
                                    FeedCache.read(ctx, key, ser)
                                }
                                if (cached != null) emit(page(cached.value))

                                // 缓存够新就不再打源站：进一次片库要给十几个站发请求
                                if (cached != null &&
                                    FeedCache.isFresh(cached.at, FeedCache.BROWSE_FRESH_MS)
                                ) {
                                    return@flow
                                }

                                var latest = emptyList<Vod>()
                                var lastWrite = 0L
                                if (srcs.isNotEmpty()) {
                                    // 2) 每回来一个源就发一次完整快照
                                    com.projectionroom.shared.catalog.Catalog(
                                            sourceHttp,
                                            (bundle?.badPicHosts.orEmpty() + com.projectionroom.ui.component.BadPicHosts.all()),
                                        )
                                        .browseStream(catName, pg, y, a, srcs, deadlineMs = 9_000)
                                        .flowOn(Dispatchers.Default)
                                        .collect { partial ->
                                            // 空快照也要发下去：换筛选后一条都没命中时，
                                            // 界面要显示「没有片子」，而不是留着上一次的结果
                                            if (partial.isNotEmpty()) latest = partial
                                            emit(page(partial))
                                            if (partial.isEmpty()) return@collect
                                            // 边拉边落盘：用户随时可能切走或杀进程，
                                            // 只在收尾写一次的话，那时候缓存还是空的
                                            val now = System.currentTimeMillis()
                                            if (now - lastWrite >= 1_500) {
                                                lastWrite = now
                                                withContext(Dispatchers.IO) {
                                                    FeedCache.write(ctx, key, now, partial, ser)
                                                }
                                            }
                                        }
                                    if (latest.isNotEmpty()) {
                                        withContext(Dispatchers.IO) {
                                            FeedCache.write(
                                                ctx, key, System.currentTimeMillis(), latest, ser,
                                            )
                                        }
                                    }
                                }

                                // 3) 一条都没拿到（没配源 / 全部超时）才问 NAS
                                if (latest.isEmpty() && cached == null) {
                                    NasGate.tryCall(timeoutMs = 3_000) {
                                        runCatching { api.browse(cat, pg, y, a) }.getOrNull()
                                    }?.let { emit(it) }
                                }
                            }
                        }
                    }

                // 同步也用 rawApi：收藏是「切换」语义，拿到旧快照会把
                // 「取消收藏」判成「已经一致」而跳过，NAS 上那条就永远删不掉
                val syncApi = rawApi

                var screen by remember { mutableStateOf<Screen>(Screen.Home) }
                var searchHistory by remember { mutableStateOf(SearchStore.list(this@MainActivity)) }

                // 通知栏点歌名 → 落到全屏播放页。
                // 只在队列还在的时候跳：没有歌可放的话进去就是个空页面。
                LaunchedEffect(AppRoute.musicPlayerRequest) {
                    if (AppRoute.musicPlayerRequest > 0 &&
                        com.projectionroom.ui.music.MusicBox.hasQueue
                    ) {
                        screen = Screen.MusicPlayer(from = Screen.Home)
                    }
                }
                LaunchedEffect(AppRoute.pairRequest) {
                    if (AppRoute.pairRequest > 0) screen = Screen.Pair(from = Screen.Home)
                }
                LaunchedEffect(AppRoute.scanRequest) {
                    if (AppRoute.scanRequest > 0) screen = Screen.Pair(from = Screen.Home)
                }
                // 联调：一条假片子，只有一集，地址由 intent 给（见 AppRoute.castDemoUrl）
                LaunchedEffect(AppRoute.castDemoUrl) {
                    val u = AppRoute.castDemoUrl
                    if (!u.isNullOrBlank()) {
                        screen = Screen.Play(
                            vod = Vod(
                                name = "投屏联调",
                                lines = listOf(
                                    Line(site = "测试源", episodes = listOf(Episode("正片", u)))
                                ),
                            ),
                            lineIndex = 0,
                            epIndex = 0,
                            from = Screen.Home,
                            openCast = AppRoute.castDemoOpenSheet,
                        )
                        AppRoute.castDemoUrl = null
                        AppRoute.castDemoOpenSheet = false
                    }
                }

                // 「立即播放」：首页/电视 Hero 上那颗按钮要直接进播放器。
                // 但首页卡片为了省内存把 lines 剥掉了，所以先补一次详情
                // （走 DetailCache，返回过的片子是 0ms），拿到线路再切播放页。
                var preparing by remember { mutableStateOf<Vod?>(null) }
                fun playDirect(v: Vod, from: Screen) {
                    if (preparing != null) return
                    scopeTop.launch {
                        preparing = v
                        val full = DetailCache.get(v.name, v.year)
                            ?: runCatching { doDetail(v.name, v.year) }.getOrNull()
                        preparing = null
                        if (full != null && full.lines.any { it.episodes.isNotEmpty() }) {
                            DetailCache.put(this@MainActivity, v.name, v.year, full)
                            screen = Screen.Play(full, 0, 0, from)
                        } else {
                            // 补不到线路就退回详情页，至少能手动换线路
                            screen = Screen.Detail(v, from)
                        }
                    }
                }

                // 补线路的这一两秒给个遮罩：否则点「立即播放」像是没反应
                if (preparing != null) {
                    androidx.compose.ui.window.Dialog(
                        onDismissRequest = {},
                        properties = androidx.compose.ui.window.DialogProperties(
                            usePlatformDefaultWidth = false
                        ),
                    ) {
                        Box(
                            Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.55f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                CircularProgressIndicator(color = ProomColor.accent)
                                Spacer(Modifier.height(14.dp))
                                Text(
                                    "正在打开「${preparing?.name.orEmpty()}」",
                                    style = ProomType.meta,
                                    color = ProomColor.muted,
                                )
                            }
                        }
                    }
                }

                // 首页：**本地拼**，NAS 只做兜底。
                // 片源地址存在本地 bundle 里，所以 NAS 一年不开机，首页照样能出新内容。
                var snapshot by remember { mutableStateOf<HomeSnapshot?>(null) }
                var loading by remember { mutableStateOf(true) }
                LaunchedEffect(bundle, extra, api) {
                    // 首屏快照也在 IO 上恢复：读文件 + 反序列化，不该占主线程
                    if (snapshot == null) {
                        snapshot = withContext(Dispatchers.IO) {
                            HomeCache.read(this@MainActivity)
                        }
                    }
                    // 本地配置包还没读出来时先别急着问 NAS。
                    // 冷启动这一瞬间 bundle 还在 IO 线程上解析，allSources 是空的，
                    // 直接往下走必然是「本地拼不出来 → 问 NAS」，NAS 不在就白等一次超时。
                    // 等 2 秒还不来才说明确实没有本地配置（新装 / 没导过订阅），这时才走 NAS。
                    if (bundle == null && extra.sources.isEmpty()) {
                        delay(2_000)
                        if (bundle == null && extra.sources.isEmpty()) {
                            runCatching { api.home() }.onSuccess { f ->
                                snapshot = HomeSnapshot(
                                    at = System.currentTimeMillis(),
                                    latest = f.latest,
                                    cats = f.cats,
                                )
                            }
                            loading = false
                        }
                        return@LaunchedEffect
                    }
                    val srcs = allSources
                    // 有快照就先让首屏落地，隔一会儿再打源站：
                    // 冷启动时 20 个请求和首帧渲染抢 CPU，第一屏会明显发涩
                    if (snapshot != null) delay(1_200)
                    // 缓存足够新就不重复拉：一次本地拼首页要打几十个源
                    if (srcs.isNotEmpty() && !HomeCache.isFresh(snapshot)) {
                        var got: com.projectionroom.shared.catalog.LocalHome.Feed? = null
                        runCatching {
                            // 流式：哪个分类先拉到就先画哪一排货架。以前是 5 个分类都跑完
                            // 才一次性上屏，最慢那个 10 秒超时，用户就对着空首页等 10 秒
                            com.projectionroom.shared.catalog.LocalHome
                                .buildStream(sourceHttp, srcs, deadlineMs = 10_000)
                                .flowOn(Dispatchers.Default)
                                .collect { f ->
                                    if (f.isEmpty) return@collect
                                    got = f
                                    snapshot = HomeSnapshot(
                                        at = System.currentTimeMillis(),
                                        latest = f.latest,
                                        cats = f.cats,
                                    )
                                    loading = false
                                }
                        }
                        val local = got
                        if (local != null) {
                            // 写 + 读快照都放 IO：读回来的是瘦身版（剥了 lines），
                            // 内存里不该长期留着一堆播放地址
                            snapshot = withContext(Dispatchers.IO) {
                                HomeCache.save(this@MainActivity, local)
                                HomeCache.read(this@MainActivity)
                            } ?: snapshot
                        }
                    }
                    // 本地一条都没拼出来（没片源/全超时）才问 NAS
                    if (snapshot == null) {
                        runCatching { api.home() }.onSuccess { f ->
                            snapshot = HomeSnapshot(
                                at = System.currentTimeMillis(),
                                latest = f.latest,
                                cats = f.cats,
                            )
                        }
                    }
                    loading = false
                }

                // 收藏 / 记录：本地主库，NAS 只是同步对象（见 LibraryStore / LibrarySync）
                var lib by remember { mutableStateOf(LibFile()) }
                LaunchedEffect(Unit) {
                    lib = withContext(Dispatchers.IO) { LibraryStore.load(this@MainActivity) }
                }
                val favs = lib.favorites()
                val hist = lib.history()
                // 「我的」顶栏那行「同步中…」读它。只是提示，不挡任何操作
                var syncing by remember { mutableStateOf(false) }
                // 下拉刷新靠它重跑同步（LaunchedEffect 的 key 变了才会重新执行）
                var syncTick by remember { androidx.compose.runtime.mutableIntStateOf(0) }
                LaunchedEffect(api, syncTick) {
                    // 启动先推未同步的改动，再拉 NAS 上的；离线时两边都失败，界面照旧用本地。
                    // 永久离线就不该每次启动都等它 10 秒 —— 交给熔断器。
                    syncing = true
                    val synced = NasGate.tryCall(timeoutMs = 6_000) {
                        runCatching { LibrarySync.sync(this@MainActivity, syncApi, lib) }.getOrNull()
                    }
                    if (synced != null) lib = synced
                    syncing = false
                }
                val scope = rememberCoroutineScope()

                // 一级 tab 的点击行为：只换内容区，不 push 页面。
                // from 一律给首页 —— 否则「首页→电影→直播→音乐」会攒出一条
                // 遥控器要按四次 BACK 才能退出去的历史。
                fun rootNav(index: Int) = com.projectionroom.ui.component.TvRootNav(
                    index = index,
                    onSelect = { _, slug ->
                        screen = if (slug == null) Screen.Home else Screen.Browse(slug, Screen.Home)
                    },
                    onSearch = { screen = Screen.Search(from = screen) },
                    onNas = { screen = Screen.Connect(from = screen) },
                    onMine = { screen = Screen.Mine(from = screen) },
                )

                // 系统返回键：逐屏回退，只在首页才真正退出
                BackHandler(enabled = screen != Screen.Home) {
                    screen = when (val cur = screen) {
                        is Screen.Detail -> cur.from
                        is Screen.Play -> cur.from
                        is Screen.MusicPlayer -> cur.from
                        is Screen.Search -> cur.from
                        is Screen.Browse -> cur.from
                        is Screen.Playlist -> cur.from
                        is Screen.Connect -> cur.from
                        is Screen.Mine -> cur.from
                        is Screen.Settings -> cur.from
                        is Screen.Pair -> cur.from
                        else -> Screen.Home
                    }
                }

                // UI 要的 HomeFeed：本机记录 + 本地拼出来的推荐/货架
                val homeFeed = snapshot?.toFeed(lib.history(), lib.favorites())

                // 电视首页的滚动位置挂在这里：从详情页返回时位置不丢，焦点也接得上
                val tvHomeState = androidx.compose.foundation.lazy.rememberLazyListState()
                // 手机的 tab 同理：进详情/NAS 页再返回时，别把用户踢回第一个 tab
                var phoneTab by remember { mutableStateOf(0) }

                // Android 15+ 强制 edge-to-edge：内容会画到状态栏/手势条下面，
                // 状态栏还会吃掉那块区域的点击。除播放页（要满屏视频）外统一让开。
                // 顶部让开交给各页面自己（放外层会让正在退出的首页也一起上移，沉浸感瞬间消失）
                val insetMod = Modifier.padding(bottom = barBottom)

                Box(Modifier.fillMaxSize().background(ProomColor.bg).then(insetMod)) {
                    // 页面切换动效：新页从右侧滑入淡入，旧页微左移淡出（硬切太突兀）
                androidx.compose.animation.AnimatedContent(
                    targetState = screen,
                    transitionSpec = {
                        // 页面栈语义：**下层页面永远不动**，只让"进来/出去的那一页"动。
                        //  前进：新页从右滑入，盖住不动的旧页
                        //  返回：旧页向右滑出，露出不动的新页（下层）
                        // 一级 tab 之间只是换内容：横滑会被读成「进了一个新页面」，
                        // 于是用户开始找返回键 —— 淡入淡出才符合 tab 的语义
                        val tabSwitch = isTabScreen(targetState) && isTabScreen(initialState)
                        val fwd = depth(targetState) >= depth(initialState)
                        val ms = androidx.compose.animation.core.tween<IntOffset>(
                            300, easing = androidx.compose.animation.core.FastOutSlowInEasing,
                        )
                        if (tabSwitch) {
                            androidx.compose.animation.fadeIn(
                                androidx.compose.animation.core.tween(180),
                            ) togetherWith androidx.compose.animation.fadeOut(
                                androidx.compose.animation.core.tween(140),
                            )
                        } else if (fwd) {
                            (androidx.compose.animation.slideInHorizontally(
                                initialOffsetX = { it }, animationSpec = ms,
                            ) + androidx.compose.animation.fadeIn(
                                androidx.compose.animation.core.tween(160),
                            )).togetherWith(androidx.compose.animation.ExitTransition.None)
                        } else {
                            androidx.compose.animation.EnterTransition.None togetherWith
                                androidx.compose.animation.slideOutHorizontally(
                                    targetOffsetX = { it },
                                    animationSpec = androidx.compose.animation.core.tween(240),
                                )
                        }
                    },
                    label = "screen",
                ) { s ->
                    when (s) {
                        is Screen.Home ->
                            TvHome(
                                continueWatching = homeFeed?.continueWatching.orEmpty(),
                                latest = homeFeed?.latest.orEmpty(),
                                cats = homeFeed?.cats.orEmpty(),
                                loading = loading,
                                listState = tvHomeState,
                                onOpen = { vod -> screen = Screen.Detail(vod = vod, from = s) },
                                onPlay = { vod -> playDirect(vod, s) },
                                onSearch = { screen = Screen.Search(from = s) },
                                onNas = { screen = Screen.Connect(from = s) },
                                onMine = { screen = Screen.Mine(from = s) },
                                topInset = barTop,
                                onNav = { _, slug ->
                                    screen = if (slug == null) Screen.Home else Screen.Browse(slug, Screen.Home)
                                },
                                // 货架上的「查看全部」要带着**这个分类**走，写死 movie 会让动漫板块跳到电影
                                onMore = { cat, _ -> screen = Screen.Browse(cat, Screen.Home) },
                            )
                        is Screen.Browse -> {
                            // tab 下标由 slug 反查：顺序改了这里不用跟着改
                            // -1 表示「这个分类不在顶栏里」：不点亮任何 tab，
                            // 而不是错误地高亮「首页」
                            val tabIndex = com.projectionroom.ui.component.NAV_TABS
                                .indexOfFirst { it.second == s.slug }
                            if (s.slug == "music") {
                                MusicScreen(
                                    loadCats = doMusicCats,
                                    loadPlaylists = doMusicLists,
                                    onOpenPlaylist = { p -> screen = Screen.Playlist(p.id, s) },
                                    // 顶栏那颗搜索图标在音乐 tab 上搜的就是歌，
                                    // 走和歌单同一套聚合接口（音乐页的数据源就是它，不是 NAS）
                                    searchTracks = { kw ->
                                        runCatching { musicApi.search(kw) }.getOrDefault(emptyList())
                                    },
                                    onPlayTracks = { tracks, index ->
                                        val q = tracks.map {
                                            com.projectionroom.ui.music.MusicTrack(
                                                id = it.id, title = it.title,
                                                artist = it.artist, cover = it.cover,
                                            )
                                        }
                                        com.projectionroom.ui.music.MusicBox
                                            .playAll(this@MainActivity, q, index, false)
                                        // 搜索结果页没有迷你条的位置，直接进播放页：至少能暂停/切歌
                                        screen = Screen.MusicPlayer(s)
                                    },
                                    onBack = { screen = Screen.Home },
                                    topInset = barTop,
                                    nav = rootNav(tabIndex),
                                )
                            } else if (s.slug == "live") {
                                LiveScreen(
                                    load = doLive,
                                    onPlay = { chs, idx ->
                                        val lp = livePlay(chs, idx)
                                        screen = Screen.Play(lp.vod, 0, lp.index, s, lp.urls)
                                    },
                                    onBack = { screen = Screen.Home },
                                    topInset = barTop,
                                    nav = rootNav(tabIndex),
                                )
                            } else {
                                BrowseScreen(
                                    slug = s.slug,
                                    load = doBrowse,
                                    onOpen = { vod -> screen = Screen.Detail(vod = vod, from = s) },
                                    onBack = { screen = Screen.Home },
                                    topInset = barTop,
                                    nav = rootNav(tabIndex),
                                )
                            }
                        }

                        is Screen.Playlist -> PlaylistScreen(
                            load = doMusicPlaylist,
                            id = s.id,
                            topInset = barTop,
                            nowPlayingId = com.projectionroom.ui.music.MusicBox.playingId,
                            isPlaying = com.projectionroom.ui.music.MusicBox.playing,
                            // 点歌**不跳页**：只在底部弹迷你条，想全屏再点封面
                            onPlayTrack = { detail, index, shuffle ->
                                val q = detail.tracks.map {
                                    com.projectionroom.ui.music.MusicTrack(
                                        id = it.id, title = it.title,
                                        artist = it.artist, cover = it.cover,
                                    )
                                }
                                com.projectionroom.ui.music.MusicBox
                                    .playAll(this@MainActivity, q, index, shuffle)
                            },
                            onBack = { screen = s.from },
                            // 迷你条在遥控器上要按到列表底才够得着，顶栏这颗胶囊才是入口
                            onOpenPlayer = { screen = Screen.MusicPlayer(s) },
                            footer = {
                                Box(Modifier.navigationBarsPadding()) {
                                    MiniPlayerOrEmpty { screen = Screen.MusicPlayer(s) }
                                }
                            },
                        )

                        is Screen.Mine -> TvMineScreen(
                            favorites = favs,
                            history = hist,
                            onOpen = { vod -> screen = Screen.Detail(vod = vod, from = s) },
                            onBack = { screen = s.from },
                            onImportFromPhone = { screen = Screen.Pair(from = s) },
                            // 「我的」顶栏那颗「设置」：之前没接，点了没反应（设置页整个不可达）
                            onSettings = { screen = Screen.Settings(from = s) },
                            topInset = barTop,
                        )

                        is Screen.Connect -> {
                            var connBusy by remember { mutableStateOf(false) }
                            var connHint by remember { mutableStateOf<String?>(null) }
                            ConnectScreen(
                                searching = connBusy,
                                hint = connHint,
                                current = baseUrl,
                                version = versionName,
                                topInset = barTop,
                                onBack = { screen = s.from },
                                onRetry = {
                                    scope.launch {
                                        connBusy = true
                                        connHint = null
                                        val found = withContext(Dispatchers.IO) { Discovery.find() }
                                        connBusy = false
                                        if (found != null) {
                                            NasStore.save(this@MainActivity, found)
                                            baseUrl = found
                                            screen = s.from
                                        } else {
                                            connHint = "没找到别的放映室，可以直接填地址"
                                        }
                                    }
                                },
                                onConnect = { raw ->
                                    val url = NasStore.normalize(raw)
                                    if (url == null) {
                                        connHint = "地址格式不对，示例：192.0.2.10:8899"
                                    } else {
                                        NasStore.save(this@MainActivity, url)
                                        baseUrl = url
                                        screen = s.from
                                    }
                                },
                            )
                        }

                        is Screen.Settings -> {
                            // 设置页的状态：配置版本/源数/缓存占用/版本号都在这里拼
                            var setMsg by remember { mutableStateOf<String?>(null) }
                            // 扫码推送之类的外部动作，结果带回来显示在底部那一行
                            LaunchedEffect(Unit) {
                                AppRoute.settingsMessage?.let {
                                    setMsg = it
                                    AppRoute.settingsMessage = null
                                }
                            }
                            var setBusy by remember { mutableStateOf(false) }
                            var update by remember { mutableStateOf<AppUpdate?>(null) }
                            var cacheSize by remember { mutableStateOf("—") }
                            LaunchedEffect(Unit) {
                                cacheSize = withContext(Dispatchers.IO) { cacheSizeText() }
                            }

                            /**
                             * 把一份配置文本落到本地。
                             *
                             * 导入 = 覆盖：对方给的包就是他此刻能用的全套，
                             * 合并只会产生两套互相打架的分类映射。
                             */
                            suspend fun importPack(text: String?): String {
                                if (text.isNullOrBlank()) return "没有读到内容"
                                val pack = ShareConfig.decode(text)
                                    ?: return "不是放映室的配置文件"
                                pack.bundle?.let { b ->
                                    withContext(Dispatchers.IO) { ConfigStore.save(this@MainActivity, b) }
                                    bundle = b
                                    bundleAt = System.currentTimeMillis()
                                }
                                pack.extra?.let { e ->
                                    extra = withContext(Dispatchers.IO) { ExtraStore.save(this@MainActivity, e) }
                                }
                                val n = (pack.bundle?.sources?.size ?: 0) + (pack.extra?.sources?.size ?: 0)
                                val c = (pack.bundle?.live?.channels?.size ?: 0) + (pack.extra?.live?.size ?: 0)
                                return "已导入 $n 个源 · $c 个台"
                            }

                            /** 订阅变了：重算并落盘（旧结果在算完前保持不变） */
                            suspend fun refreshSubs(next: ExtraConfig, okMsg: String) {
                                setBusy = true
                                extra = withContext(Dispatchers.IO) { ExtraStore.save(this@MainActivity, next) }
                                val resolved = runCatching {
                                    SubResolver.resolve(sourceHttp, next)
                                }.getOrNull()
                                if (resolved != null) {
                                    extra = withContext(Dispatchers.IO) {
                                        ExtraStore.save(this@MainActivity, resolved)
                                    }
                                    setMsg = "$okMsg · ${resolved.sources.size} 个源 / ${resolved.live.size} 个频道"
                                } else {
                                    setMsg = "订阅解析失败，检查地址是否可访问"
                                }
                                setBusy = false
                            }

                            val importPicker = androidx.activity.compose.rememberLauncherForActivityResult(
                                androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
                            ) { uri ->
                                if (uri != null) scopeTop.launch {
                                    setBusy = true
                                    val text = withContext(Dispatchers.IO) {
                                        runCatching {
                                            contentResolver.openInputStream(uri)
                                                ?.bufferedReader()?.readText()
                                        }.getOrNull()
                                    }
                                    setMsg = importPack(text)
                                    setBusy = false
                                }
                            }
                            SettingsScreen(
                                topInset = barTop,
                                state = SettingsState(
                                    nasUrl = baseUrl.orEmpty(),
                                    configVersion = bundle?.version.orEmpty(),
                                    configSources = bundle?.sources?.size ?: 0,
                                    configAt = if (bundleAt > 0) {
                                        "本次已同步 · ${agoText(bundleAt)}"
                                    } else "",
                                    shareInfo = if (allSources.isNotEmpty() || extra.live.isNotEmpty()) {
                                        "${allSources.size} 个源 · ${(bundle?.live?.channels?.size ?: 0) + extra.live.size} 个台"
                                    } else "",
                                    subs = extra.subs.map {
                                        com.projectionroom.ui.screen.SubRow(
                                            url = it.url,
                                            name = it.name.ifBlank {
                                                it.url.removePrefix("https://").removePrefix("http://")
                                                    .substringBefore('/')
                                            },
                                            info = it.note,
                                            ok = it.ok,
                                        )
                                    },
                                    versionName = versionName.orEmpty(),
                                    versionCode = appVersionCode(),
                                    // 缓存体积要算目录，放 IO
                                    cacheSize = cacheSize,
                                    update = update,
                                    message = setMsg,
                                    busy = setBusy,
                                ),
                                onBack = { screen = s.from },
                                onChangeNas = { screen = Screen.Connect(from = s) },
                                onSyncConfig = {
                                    setMsg = null
                                    // 版本号变了才重写文件，没变就只提示一句
                                    val before = bundle?.version
                                    configTick += 1
                                    scopeTop.launch {
                                        setBusy = true
                                        // 给 LaunchedEffect 一点时间跑完，再从同一份状态读结果
                                        kotlinx.coroutines.delay(1_500)
                                        setBusy = false
                                        setMsg = when {
                                            bundle == null -> "还没拿到配置，检查 NAS 地址"
                                            bundle?.version != before ->
                                                "配置已更新到 ${bundle?.version}"
                                            else -> "配置已是最新（$before）"
                                        }
                                    }
                                },
                                onImportBuiltIn = {
                                    setMsg = null
                                    scopeTop.launch {
                                        setBusy = true
                                        // 读 assets + 落盘都在 IO；读完立刻回读一次，
                                        // 显示的是真正落盘的那份，而不是内存里的副本
                                        val built = withContext(Dispatchers.IO) {
                                            if (ConfigStore.importBuiltIn(this@MainActivity)) {
                                                ConfigStore.read(this@MainActivity)
                                            } else null
                                        }
                                        setBusy = false
                                        if (built == null) {
                                            setMsg = "内置配置读不出来，重装一下 APK"
                                        } else {
                                            bundle = built
                                            bundleAt = System.currentTimeMillis()
                                            setMsg = "已恢复内置配置：${built.sources.size} 个源 · " +
                                                "${built.live.channels.size} 个台"
                                        }
                                    }
                                },
                                onClearCache = {
                                    scopeTop.launch {
                                        setBusy = true
                                        val freed = withContext(Dispatchers.IO) { clearCaches() }
                                        cacheSize = withContext(Dispatchers.IO) { cacheSizeText() }
                                        setBusy = false
                                        setMsg = "已清理 $freed"
                                    }
                                },
                                onCheckUpdate = {
                                    scopeTop.launch {
                                        setBusy = true
                                        setMsg = null
                                        val raw = runCatching {
                                            // 电视端要的是 tv.apk，传 phone 会把手机包装到电视上
                                            sourceHttp.get("$baseUrl/api/app/latest?kind=tv")
                                        }.getOrNull()
                                        val info = raw?.let { parseAppLatest(it, appVersionCode()) }
                                        setBusy = false
                                        if (info == null) {
                                            setMsg = "NAS 上还没有发布电视端的安装包"
                                        } else {
                                            update = info
                                            setMsg = if (info.newer) {
                                                "发现新版本 v${info.versionName}"
                                            } else "已是最新版本"
                                        }
                                    }
                                },
                                onShowPair = { screen = Screen.Pair(from = s) },
                                onExport = {
                                    val text = ShareConfig.encode(bundle, extra)
                                    scopeTop.launch {
                                        val f = withContext(Dispatchers.IO) {
                                            ShareConfig.writeTemp(this@MainActivity, text)
                                        }
                                        val uri = runCatching {
                                            androidx.core.content.FileProvider.getUriForFile(
                                                this@MainActivity,
                                                "$packageName.fileprovider",
                                                f,
                                            )
                                        }.getOrNull()
                                        if (uri == null) {
                                            setMsg = "导出失败"
                                            return@launch
                                        }
                                        val send = Intent(Intent.ACTION_SEND).apply {
                                            type = "application/json"
                                            putExtra(Intent.EXTRA_STREAM, uri)
                                            putExtra(Intent.EXTRA_SUBJECT, "放映室配置")
                                            // 有些 IM 只认文本，附一句说明至少知道这是什么
                                            putExtra(
                                                Intent.EXTRA_TEXT,
                                                "放映室的配置文件，导入后不需要 NAS 也能用",
                                            )
                                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                        }
                                        runCatching {
                                            startActivity(Intent.createChooser(send, "分享配置"))
                                        }
                                        setMsg = "已导出 ${allSources.size} 个源"
                                    }
                                },
                                onImportFile = { importPicker.launch(arrayOf("application/json", "*/*")) },
                                onImportClipboard = {
                                    val cm = getSystemService(android.content.ClipboardManager::class.java)
                                    val t = cm?.primaryClip
                                        ?.takeIf { it.itemCount > 0 }
                                        ?.getItemAt(0)?.text?.toString()
                                    scopeTop.launch {
                                        setBusy = true
                                        setMsg = importPack(t)
                                        setBusy = false
                                    }
                                },
                                onAddSub = { url ->
                                    if (!url.startsWith("http", ignoreCase = true)) {
                                        setMsg = "地址要以 http 开头"
                                    } else if (extra.subs.any { it.url == url }) {
                                        setMsg = "这条订阅已经在列表里了"
                                    } else {
                                        scopeTop.launch {
                                            refreshSubs(
                                                extra.copy(subs = extra.subs + SubEntry(url = url)),
                                                "订阅已添加",
                                            )
                                        }
                                    }
                                },
                                onRemoveSub = { url ->
                                    scopeTop.launch {
                                        refreshSubs(
                                            extra.copy(subs = extra.subs.filterNot { it.url == url }),
                                            "订阅已删除",
                                        )
                                    }
                                },
                                onRefreshSubs = {
                                    scopeTop.launch { refreshSubs(extra, "订阅已刷新") }
                                },
                                onDownloadUpdate = {
                                    // 交给系统浏览器下载 + 安装：App 内下载要额外申请
                                    // 「安装未知来源」权限，还要处理 FileProvider，不值当
                                    runCatching {
                                        startActivity(
                                            Intent(
                                                Intent.ACTION_VIEW,
                                                android.net.Uri.parse("$baseUrl/api/app/download?kind=tv"),
                                            )
                                        )
                                    }
                                },
                            )
                        }

                        is Screen.Pair -> {
                            // 电视端开一个临时 HTTP 服务等手机推配置
                            val code = remember { Pair.newCode() }
                            val ip = remember { Pair.localIp().orEmpty() }
                            var state by remember { mutableStateOf<PairState>(PairState.Waiting) }
                            var port by remember { mutableStateOf(0) }
                            var qr by remember {
                                mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null)
                            }
                            val server = remember(code) {
                                PairServer(code) { payload ->
                                    val pack = ShareConfig.decode(payload)
                                        ?: throw IllegalArgumentException("不是放映室的配置文件")
                                    withContext(Dispatchers.IO) {
                                        pack.bundle?.let { ConfigStore.save(this@MainActivity, it) }
                                        pack.extra?.let { ExtraStore.save(this@MainActivity, it) }
                                    }
                                    val n = (pack.bundle?.sources?.size ?: 0) +
                                        (pack.extra?.sources?.size ?: 0)
                                    val c = (pack.bundle?.live?.channels?.size ?: 0) +
                                        (pack.extra?.live?.size ?: 0)
                                    withContext(Dispatchers.Main) {
                                        pack.bundle?.let { bundle = it }
                                        pack.extra?.let { extra = it }
                                        bundleAt = System.currentTimeMillis()
                                        state = PairState.Done("已接收 $n 个源 · $c 个台")
                                    }
                                    "已接收，电视上可以直接用了"
                                }
                            }
                            DisposableEffect(code) {
                                if (ip.isBlank()) {
                                    state = PairState.Unavailable("电视没有连上局域网")
                                } else if (server.start(scopeTop)) {
                                    port = server.port
                                    qr = runCatching {
                                        Pair.qrBitmap(PairTarget(ip, port, code).encode())
                                            ?.asImageBitmap()
                                    }.getOrNull()
                                } else {
                                    state = PairState.Unavailable("端口被占用，重试或重启电视端")
                                }
                                onDispose { server.stop() }
                            }
                            PairScreen(
                                qr = qr,
                                code = code,
                                address = if (port > 0) "$ip:$port" else ip,
                                state = state,
                                topInset = barTop,
                                onBack = { screen = s.from },
                            )
                        }

                        is Screen.Search -> SearchScreen(
                            search = doSearch,
                            onOpen = { vod -> screen = Screen.Detail(vod = vod, from = s) },
                            onBack = { screen = s.from },
                            topInset = barTop,
                            history = searchHistory,
                            onHistoryCommit = { kw -> searchHistory = SearchStore.add(this@MainActivity, kw) },
                            onHistoryRemove = { kw -> searchHistory = SearchStore.remove(this@MainActivity, kw) },
                            onHistoryClear = { searchHistory = SearchStore.clear(this@MainActivity) },
                        )

                        is Screen.Detail -> {
                            // 详情页三层：先上屏 → 再补缓存 → 最后流式刷新。
                            //  1. 列表页那份 vod 本来就有片名/海报/简介，进来立刻能看，
                            //     不必等任何网络请求；
                            //  2. 内存/磁盘里有上次的详情（含线路）就直接替换；
                            //  3. 每个源回来一版，线路一条条长出来 —— 而不是「等 8 秒，
                            //     19 条线路突然一起出现」。
                            // 封面以列表带过来的那张为准：用户点进来看到的就是它。
                            // 详情流每回来一版都会重算 vod.pic（各源不一样、有的还是空），
                            // 换 URL 时 Glide 没有占位图、也没有过渡，必然黑一帧 —— 就是"进来闪一下"。
                            val seed = remember(s.vod.name, s.vod.year) {
                                val cached = DetailCache.get(s.vod.name, s.vod.year)
                                when {
                                    cached == null -> s.vod
                                    s.vod.pic.isBlank() -> cached
                                    else -> cached.copy(pic = s.vod.pic)
                                }
                            }
                            var full by remember(s.vod.name) { mutableStateOf<Vod?>(seed) }
                            var refreshing by remember(s.vod.name) { mutableStateOf(false) }
                            var err by remember(s.vod.name) { mutableStateOf<String?>(null) }
                            var linesLoading by remember(s.vod.name) {
                                mutableStateOf(seed.lines.isEmpty())
                            }
                            var refreshTick by remember(s.vod.name) {
                                androidx.compose.runtime.mutableIntStateOf(0)
                            }

                            LaunchedEffect(s.vod.name, refreshTick) {
                                err = null
                                val force = refreshTick > 0

                                // 冷启动：内存没有时翻一下磁盘（可能是上次进程被杀前存的）
                                if (!force && !DetailCache.isFresh(s.vod.name, s.vod.year)) {
                                    val disk = withContext(Dispatchers.IO) {
                                        DetailCache.hydrate(this@MainActivity, s.vod.name, s.vod.year)
                                    }
                                    if (disk != null && disk.lines.isNotEmpty()) {
                                        // 磁盘里存的是上次那版封面，别覆盖屏幕上已经有的那张
                                        val keep = full?.pic.orEmpty()
                                        full = if (keep.isNotBlank()) disk.copy(pic = keep) else disk
                                        linesLoading = false
                                    }
                                }
                                // 5 分钟内刚拉过：连线路都信，不再轰 30 个源
                                if (!force && DetailCache.isFresh(s.vod.name, s.vod.year)) {
                                    linesLoading = false
                                    return@LaunchedEffect
                                }

                                linesLoading = full?.lines.isNullOrEmpty()
                                var got = false
                                runCatching {
                                    doDetailStream(s.vod.name, s.vod.year).collect { v ->
                                        got = true
                                        // 同上：封面只允许「从无到有」，不允许多次改写
                                        val keep = full?.pic.orEmpty()
                                        full = if (keep.isNotBlank()) v.copy(pic = keep) else v
                                        linesLoading = false
                                        DetailCache.put(this@MainActivity, s.vod.name, s.vod.year, v)
                                    }
                                }.onFailure { e -> if (!got) err = e.message }
                                linesLoading = false
                                refreshing = false
                            }

                            val vod = full
                            when {
                                vod == null -> com.projectionroom.ui.component.DetailSkeleton(
                                    topInset = barTop,
                                    onBack = { screen = s.from },
                                    isTv = true,
                                )
                                err != null && vod.lines.isEmpty() -> CenterHint(
                                    text = "读取失败：$err",
                                    topInset = barTop,
                                    onBack = { screen = s.from },
                                )
                                vod != null -> DetailScreen(
                                    vod = vod,
                                    loadingLines = linesLoading,
                                    refreshing = refreshing,
                                    onRefresh = {
                                        refreshing = true
                                        DetailCache.invalidate(s.vod.name, s.vod.year)
                                        refreshTick += 1
                                    },
                                    isFavorite = favs.any { it.name == vod.name },
                                    onToggleFavorite = {
                                        // 先落本地（立刻有反馈），再后台推给 NAS；推不上去就留着 pending
                                        lib = LibraryStore.toggleFav(this@MainActivity, vod)
                                        scope.launch {
                                            runCatching { LibrarySync.sync(this@MainActivity, syncApi, lib) }
                                                .onSuccess { lib = it }
                                        }
                                    },
                                    onPlay = { li, ei ->
                                        screen = Screen.Play(vod, li, ei, s)
                                    },
                                    onBack = { screen = s.from },
                                    topInset = barTop,
                                )
                                else -> CenterHint(
                                    text = "这部片没有可播线路",
                                    topInset = barTop,
                                    onBack = { screen = s.from },
                                )
                            }
                        }

                        is Screen.Play -> {
                            // 视频和直播共用一套播放器（直播靠 live 标志收掉进度/倍速）。
                            // 音乐已经独立成 Screen.MusicPlayer，读的是全局 MusicBox。
                            val isLive = s.liveUrls.isNotEmpty() ||
                                s.vod.lines.firstOrNull()?.site == "直播"
                            PlayerScreen(
                                vod = s.vod,
                                startLine = s.lineIndex,
                                startEp = s.epIndex,
                                // 电视本来就在横屏，不需要再转
                                rotateLandscape = false,
                                // 收掉锁/画中画/投屏这三个电视上用不到的按钮
                                isTv = true,
                                live = isLive,
                                liveUrls = s.liveUrls,
                                // 电视是接收端：播放器右上角的锁/画中画/投屏都不该出现
                                // 联调：`-e screen cast` 进来就展开投屏面板
                                openCastOnEnter = s.openCast,
                                // 直连源站：ExoPlayer 自己带 Referer/UA，不需要 NAS 中转。
                                // NAS 关机也要能播 —— 这是把代理摘掉的原因。
                                resolveUrl = { it },
                                // 直播收藏：换台是在播放器内部发生的，只能按台名现查/现切
                                liveFavOf = { name ->
                                    com.projectionroom.ui.live.LiveLib
                                        .isFav(this@MainActivity, name)
                                },
                                onToggleLiveFav = { name ->
                                    val i = s.vod.lines.firstOrNull()?.episodes
                                        ?.indexOfFirst { it.name == name } ?: -1
                                    com.projectionroom.ui.live.LiveLib.toggle(
                                        this@MainActivity,
                                        NasApi.LiveChannel(
                                            name = name,
                                            urls = if (i >= 0) {
                                                s.liveUrls.getOrElse(i) { emptyList() }
                                            } else emptyList(),
                                        ),
                                    )
                                },
                                onProgress = { ep, pos, dur ->
                                    lib = LibraryStore.touchHistory(
                                        this@MainActivity, s.vod, ep, pos, dur,
                                    )
                                    scope.launch {
                                        runCatching {
                                            LibrarySync.sync(this@MainActivity, syncApi, lib)
                                        }.onSuccess { lib = it }
                                    }
                                },
                                onBack = { screen = s.from },
                            )
                        }

                        is Screen.MusicPlayer -> {
                            // 收藏状态在 MusicBox 里（通知栏和这一页读同一份），
                            // 不需要壳再传进来
                            com.projectionroom.ui.screen.MusicPlayerScreen(
                                onBack = { screen = s.from },
                                isTv = true,
                            )
                        }
                    }
                    }
                }
            }
        }
    }

    /**
     * 通知栏点了「歌名 / 封面」进来的路由。
     *
     * 不能通过 Intent 直接开一个播放页 Activity：播放器是进程内单例
     * （[com.projectionroom.ui.music.MusicBox]），Activity 重建就等于把歌接过来，
     * 所以只让 MainActivity 读一个标记、把落地页切成播放页。
     */
    private fun readRoute(intent: Intent?) {
        if (intent?.getBooleanExtra(MusicService.EXTRA_OPEN_PLAYER, false) == true) {
            AppRoute.musicPlayerRequest += 1
            // 清掉标记：旋转屏幕、从后台回来时不该又跳一次播放页
            intent.removeExtra(MusicService.EXTRA_OPEN_PLAYER)
        }
        // 直接落到某一页：`am start -e screen pair` / 以后的深链接都用它
        when (intent?.getStringExtra(EXTRA_SCREEN)) {
            "pair" -> AppRoute.pairRequest += 1
            "scan" -> AppRoute.scanRequest += 1
            // 联调落点：只在 debuggable 包里认，正式包忽略
            "castdemo" -> if ((applicationInfo.flags and
                    android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
            ) {
                AppRoute.castDemoUrl = intent.getStringExtra("url").orEmpty()
            }
            // 同上，但进去顺手把投屏面板展开：横屏播放器里 adb 点不准顶栏，
            // 没有这条路就没法验面板版式
            "cast" -> if ((applicationInfo.flags and
                    android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
            ) {
                AppRoute.castDemoUrl = intent.getStringExtra("url").orEmpty()
                AppRoute.castDemoOpenSheet = true
            }
        }
        intent?.removeExtra(EXTRA_SCREEN)
    }

    /** 当前安装包的 versionCode（设置页和检查更新都要用） */
    private fun appVersionCode(): Long {
        val p = packageManager.getPackageInfo(packageName, 0)
        return if (android.os.Build.VERSION.SDK_INT >= 28) p.longVersionCode
        else p.versionCode.toLong()
    }

    /** 接口缓存 + 图片缓存占了多少 */
    private fun cacheBytes(): Long {
        fun dirSize(d: java.io.File): Long =
            if (!d.exists()) 0L else d.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        return dirSize(java.io.File(cacheDir, "api")) +
            dirSize(java.io.File(cacheDir, "image_cache"))
    }

    private fun cacheSizeText(): String = humanSize(cacheBytes())

    /**
     * 清缓存。
     *
     * 只删 cache 目录下的两个子目录 —— 那是「可再生」的东西（接口响应、图片），
     * 删掉只是下次要重新下载。收藏、播放记录、配置包都在 filesDir，不碰。
     */
    private fun clearCaches(): String {
        val before = cacheBytes()
        runCatching { java.io.File(cacheDir, "api").deleteRecursively() }
        // 图片缓存：Glide 默认落在 image_manager_disk_cache；image_cache 是旧版 Coil 的目录，
        // 从老版本升上来的机器上可能还留着，一并清掉。删目录够用，不会崩。
        runCatching { java.io.File(cacheDir, "image_manager_disk_cache").deleteRecursively() }
        runCatching { java.io.File(cacheDir, "image_cache").deleteRecursively() }
        return humanSize((before - cacheBytes()).coerceAtLeast(0L))
    }

    /** /api/app/latest 的返回：{versionCode, versionName, size, notes, ...} */
    private fun parseAppLatest(raw: String, currentCode: Long): AppUpdate? {
        val o = runCatching {
            kotlinx.serialization.json.Json.parseToJsonElement(raw) as kotlinx.serialization.json.JsonObject
        }.getOrNull() ?: return null
        fun str(k: String): String =
            (o[k] as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty()
        val code = str("versionCode").toLongOrNull() ?: return null
        return AppUpdate(
            versionName = str("versionName").ifBlank { "0" },
            versionCode = code,
            sizeMb = humanSize(str("size").toLongOrNull() ?: 0L),
            notes = str("notes"),
            newer = code > currentCode,
        )
    }

    private fun humanSize(bytes: Long): String = when {
        bytes >= 1024L * 1024 -> "%.1f MB".format(bytes / 1048576.0)
        bytes >= 1024L -> "%.0f KB".format(bytes / 1024.0)
        else -> "$bytes B"
    }

    /** 「刚刚 / 3 分钟前 / 2 小时前」，比一串时间戳好读 */
    private fun agoText(at: Long): String {
        val d = System.currentTimeMillis() - at
        return when {
            d < 60_000 -> "刚刚"
            d < 3_600_000 -> "${d / 60_000} 分钟前"
            d < 86_400_000 -> "${d / 3_600_000} 小时前"
            else -> "${d / 86_400_000} 天前"
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        readRoute(intent)
    }
}

/**
 * 跨「通知 → Activity」的一次性路由请求。
 *
 * 用自增计数而不是 Boolean：连点两次通知也要能再回到播放页
 * （用户可能在播放页按了返回、又点了一次通知）。
 */
internal object AppRoute {
    var musicPlayerRequest by androidx.compose.runtime.mutableIntStateOf(0)
    /** 要求直接打开配对页（二维码） */
    var pairRequest by androidx.compose.runtime.mutableIntStateOf(0)
    /** 要求直接打开扫码页 */
    var scanRequest by androidx.compose.runtime.mutableIntStateOf(0)
    /** 扫码/推送这类「不是页面自己发起」的结果，回设置页时显示一次 */
    var settingsMessage: String? = null
    /**
     * 联调专用：直接带着一个裸地址进播放页（仅 debuggable 包）。
     *
     * 投屏联调需要「播放器里正在放一个确定可播的地址」，但片库的线路
     * 取决于采集站当下的状态，时有时无。这条路由让投屏面板可以稳定复现：
     *   adb shell am start -n com.projectionroom.app/.MainActivity \
     *     -e screen castdemo -e url http://10.0.2.2:8099/test.mp4
     */
    var castDemoUrl: String? = null
    /** 上一条路由的附加动作：进播放页就展开投屏面板（`-e screen cast`） */
    var castDemoOpenSheet: Boolean = false
}

@Composable
private fun CenterHint(
    text: String,
    loading: Boolean = false,
    /** 状态栏高度：左上角的返回要让开，否则压在时钟那一行上 */
    topInset: androidx.compose.ui.unit.Dp = 0.dp,
    /**
     * 传了就在左上角画返回按钮。
     *
     * 失败 / 无线路这类页面是**死胡同**：整页一个可聚焦项都没有，再不给返回，
     * 触摸电视盒、手机投屏过来或者遥控器没 BACK 键的用户就直接出不去了
     * （硬件 BACK 键只是碰巧可用，不能当唯一出口）。
     */
    onBack: (() -> Unit)? = null,
) {
    Box(Modifier.fillMaxSize()) {
        if (onBack != null) {
            // 和其它页完全同一套返回提示（原来这里是个 44dp 圆钮，只在死胡同时出现，
            // 和「设 置」那些页的小字提示完全是两种东西）
            com.projectionroom.ui.component.TvPageHeader(
                onBack = onBack,
                topInset = topInset,
                modifier = Modifier.align(Alignment.TopStart),
            )
        }
        Column(
            Modifier.align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (loading) {
                CircularProgressIndicator(color = ProomColor.accent)
                Spacer(Modifier.height(12.dp))
            }
            Text(text, style = ProomType.meta, color = ProomColor.muted)
        }
    }
}

/**
 * 迷你播放条：有歌在放才占位置。
 *
 * 没有队列时返回空内容而不是隐藏一个固定高度的容器 —— 否则歌单页底部
 * 会多出一条 56dp 的黑带，把最后几首挤出屏幕。
 */
@Composable
private fun MiniPlayerOrEmpty(onOpen: () -> Unit) {
    if (com.projectionroom.ui.music.MusicBox.hasQueue) {
        com.projectionroom.ui.music.MiniPlayerBar(onOpen = onOpen)
    }
}