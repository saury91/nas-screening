package com.projectionroom.app

import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.projectionroom.shared.catalog.NasApi
import com.projectionroom.shared.model.Vod
import com.projectionroom.shared.net.OkHttpHttp
import com.projectionroom.ui.screen.BrowseScreen
import com.projectionroom.ui.screen.ConnectScreen
import com.projectionroom.ui.screen.LiveScreen
import com.projectionroom.ui.screen.MusicScreen
import com.projectionroom.ui.screen.PlaylistScreen
import com.projectionroom.ui.screen.DetailScreen
import com.projectionroom.ui.screen.PlayerScreen
import com.projectionroom.ui.screen.PhoneHome
import com.projectionroom.ui.screen.MineScreen
import com.projectionroom.ui.screen.PhoneShell
import com.projectionroom.ui.screen.TvHome
import com.projectionroom.ui.screen.SearchScreen
import com.projectionroom.ui.theme.ProomColor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.projectionroom.ui.theme.ProomRoot
import com.projectionroom.ui.theme.ProomType

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
    data class Play(val vod: Vod, val lineIndex: Int, val epIndex: Int, val from: Screen) : Screen
}

class MainActivity : ComponentActivity() {

    /** 电视判定：优先看系统特性，比按屏幕尺寸猜准。 */
    private fun isTvDevice(): Boolean {
        val pm = packageManager
        return pm.hasSystemFeature(PackageManager.FEATURE_LEANBACK) ||
            pm.hasSystemFeature("android.hardware.type.television")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 调试用：adb ... --ez forcePhone true 可在电视上预览手机布局
        val onTv = (isTvDevice() || intent.getBooleanExtra("forceTv", false)) &&
            !intent.getBooleanExtra("forcePhone", false)
        // 电视锁横屏；手机跟随系统（竖屏为主，播放器再单独转）
        if (onTv) requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE

        setContent {
            ProomRoot(isTv = onTv) {
                val scopeTop = rememberCoroutineScope()
                // NAS 地址存本地；第一次启动先在局域网里找，找不到才让用户手填
                var baseUrl by remember { mutableStateOf(NasStore.url(this@MainActivity)) }
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

                if (baseUrl == null) {
                    ConnectScreen(
                        isTv = onTv,
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
                        onConnect = { raw ->
                            val url = NasStore.normalize(raw)
                            if (url == null) {
                                findErr = "地址格式不对，示例：192.168.1.10:8899"
                            } else {
                                NasStore.save(this@MainActivity, url)
                                baseUrl = url
                            }
                        },
                    )
                    return@ProomRoot
                }

                // 超时给 30 秒：一次搜索要问 60+ 个源，实测 13~15 秒，10 秒会直接 timeout
                val api = remember(baseUrl) {
                    NasApi(OkHttpHttp(timeoutSeconds = 30), baseUrl = baseUrl!!)
                }
                var screen by remember { mutableStateOf<Screen>(Screen.Home) }

                // 首页数据只拉一次，TV 与手机共用同一份
                var feed by remember { mutableStateOf<NasApi.HomeFeed?>(null) }
                var loading by remember { mutableStateOf(true) }
                LaunchedEffect(Unit) {
                    runCatching { api.home() }.onSuccess { feed = it }
                    loading = false
                }

                var favs by remember { mutableStateOf<List<Vod>>(emptyList()) }
                var hist by remember { mutableStateOf<List<Vod>>(emptyList()) }
                LaunchedEffect(Unit) {   // 只在进入时拉一次；收藏后本地增减，避免自我触发
                    runCatching { api.favorites() }.onSuccess { favs = it }
                    runCatching { api.history() }.onSuccess { hist = it }
                }
                val scope = rememberCoroutineScope()

                // 系统返回键：逐屏回退，只在首页才真正退出
                BackHandler(enabled = screen != Screen.Home) {
                    screen = when (val cur = screen) {
                        is Screen.Detail -> cur.from
                        is Screen.Play -> cur.from
                        is Screen.Search -> cur.from
                        is Screen.Browse -> cur.from
                        is Screen.Playlist -> cur.from
                        else -> Screen.Home
                    }
                }

                // 电视首页的滚动位置挂在这里：从详情页返回时位置不丢，焦点也接得上
                val tvHomeState = androidx.compose.foundation.lazy.rememberLazyListState()

                Box(Modifier.fillMaxSize().background(ProomColor.bg)) {
                    when (val s = screen) {
                        is Screen.Home -> if (onTv) {
                            TvHome(
                                continueWatching = feed?.continueWatching.orEmpty(),
                                latest = feed?.latest.orEmpty(),
                                cats = feed?.cats.orEmpty(),
                                loading = loading,
                                listState = tvHomeState,
                                onOpen = { vod -> screen = Screen.Detail(vod = vod, from = s) },
                                onSearch = { screen = Screen.Search(from = s) },
                                onNav = { _, slug -> if (slug != null) screen = Screen.Browse(slug, s) },
                                // 货架上的「查看全部」要带着**这个分类**走，写死 movie 会让动漫板块跳到电影
                                onMore = { cat, _ -> screen = Screen.Browse(cat, s) },
                            )
                        } else {
                            // 手机：底部 tab 壳（搜索与「我的」都在壳里）
                            PhoneShell(
                                home = {
                                    PhoneHome(
                                        continueWatching = (feed?.continueWatching.orEmpty() + feed?.liked.orEmpty()).take(8),
                                        hot = feed?.latest.orEmpty(),
                                        cats = feed?.cats.orEmpty(),
                                        onOpen = { vod -> screen = Screen.Detail(vod = vod, from = s) },
                                        onSearch = { screen = Screen.Search(from = s) },
                                        onNav = { slug -> if (slug != null) screen = Screen.Browse(slug, s) },
                                        onMore = { cat -> screen = Screen.Browse(cat, s) },
                                    )
                                },
                                live = {
                                    LiveScreen(
                                        api = api,
                                        onPlay = { ch ->
                                            val vod = Vod(
                                                name = ch.name,
                                                lines = listOf(
                                                    com.projectionroom.shared.model.Line(
                                                        site = "直播",
                                                        episodes = listOf(
                                                            com.projectionroom.shared.model.Episode("直播", ch.urls.first())
                                                        ),
                                                    )
                                                ),
                                            )
                                            screen = Screen.Play(vod, 0, 0, s)
                                        },
                                    )
                                },
                                music = {
                                    MusicScreen(
                                        api = api,
                                        onOpenPlaylist = { p -> screen = Screen.Playlist(p.id, s) },
                                    )
                                },
                                mine = {
                                    MineScreen(
                                        favorites = favs,
                                        history = hist,
                                        onOpen = { vod -> screen = Screen.Detail(vod = vod, from = s) },
                                    )
                                },
                            )
                        }

                        is Screen.Browse -> if (s.slug == "music") {
                            MusicScreen(
                                api = api,
                                onOpenPlaylist = { p -> screen = Screen.Playlist(p.id, s) },
                            )
                        } else if (s.slug == "live") {
                            LiveScreen(
                                api = api,
                                onPlay = { ch ->
                                    val vod = Vod(
                                        name = ch.name,
                                        lines = listOf(
                                            com.projectionroom.shared.model.Line(
                                                site = "直播",
                                                episodes = listOf(
                                                    com.projectionroom.shared.model.Episode("直播", ch.urls.first())
                                                ),
                                            )
                                        ),
                                    )
                                    screen = Screen.Play(vod, 0, 0, s)
                                },
                            )
                        } else {
                            BrowseScreen(
                                api = api,
                                slug = s.slug,
                                onOpen = { vod -> screen = Screen.Detail(vod = vod, from = s) },
                            )
                        }

                        is Screen.Playlist -> PlaylistScreen(
                            api = api,
                            id = s.id,
                            onPlayTrack = { detail, index ->
                                scope.launch {
                                    runCatching { api.musicTrackUrl(detail.tracks[index].id) }
                                        .onSuccess { url ->
                                            if (url.isNotBlank()) {
                                                val t = detail.tracks[index]
                                                val vod = Vod(
                                                    name = "${t.title} · ${t.artist}",
                                                    pic = t.cover,
                                                    lines = listOf(
                                                        com.projectionroom.shared.model.Line(
                                                            site = "音乐",
                                                            episodes = listOf(
                                                                com.projectionroom.shared.model.Episode(t.title, url)
                                                            ),
                                                        )
                                                    ),
                                                )
                                                screen = Screen.Play(vod, 0, 0, s)
                                            }
                                        }
                                }
                            },
                        )

                        is Screen.Search -> SearchScreen(
                            api = api,
                            onOpen = { vod -> screen = Screen.Detail(vod = vod, from = s) },
                        )

                        is Screen.Detail -> {
                            var loading by remember(s.vod.name) { mutableStateOf(true) }
                            var err by remember(s.vod.name) { mutableStateOf<String?>(null) }
                            var full by remember(s.vod.name) { mutableStateOf<Vod?>(null) }

                            LaunchedEffect(s.vod.name) {
                                loading = true
                                err = null
                                runCatching { api.detail(s.vod.name, s.vod.year) }
                                    .onSuccess { full = it ?: s.vod }
                                    .onFailure { err = it.message }
                                loading = false
                            }

                            val vod = full
                            when {
                                loading && vod == null -> CenterHint("正在向 NAS 要线路…", loading = true)
                                err != null -> CenterHint("读取失败：$err")
                                vod != null -> DetailScreen(
                                    vod = vod,
                                    isFavorite = favs.any { it.name == vod.name },
                                    onToggleFavorite = {
                                        scope.launch {
                                            runCatching { api.toggleFavorite(vod) }.onSuccess { on ->
                                                favs = if (on) favs + vod else favs.filter { it.name != vod.name }
                                            }
                                        }
                                    },
                                    onPlay = { li, ei ->
                                        screen = Screen.Play(vod, li, ei, s)
                                    },
                                    onBack = { screen = s.from },
                                )
                                else -> CenterHint("这部片没有可播线路")
                            }
                        }

                        is Screen.Play -> PlayerScreen(
                            vod = s.vod,
                            startLine = s.lineIndex,
                            startEp = s.epIndex,
                            rotateLandscape = !onTv,
                            // 源站直链要经 NAS 代理：补 Referer、重写 m3u8
                            resolveUrl = { api.proxied(it) },
                            onBack = { screen = s.from },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CenterHint(text: String, loading: Boolean = false) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (loading) {
                CircularProgressIndicator(color = ProomColor.accent)
                Spacer(Modifier.height(12.dp))
            }
            Text(text, style = ProomType.meta, color = ProomColor.muted)
        }
    }
}
