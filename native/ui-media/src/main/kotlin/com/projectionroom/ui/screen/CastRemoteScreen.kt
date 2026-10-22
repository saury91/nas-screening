package com.projectionroom.ui.screen

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.projectionroom.shared.cast.CastApi
import com.projectionroom.shared.cast.CastDevice
import com.projectionroom.shared.cast.CastHub
import com.projectionroom.shared.cast.DlnaClient
import com.projectionroom.shared.model.Vod
import com.projectionroom.ui.theme.ProomColor
import com.projectionroom.ui.theme.ProomSp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 抽屉的三种状态。放在文件级，选集与换源共用一套宿主 */
internal enum class CastSheet { None, Episodes, Lines }

/**
 * 投屏遥控页。
 *
 * 为什么单独一屏而不是留在播放器浮层里：投上去以后手机的职责变了 —— 本地那份
 * 播放器该停掉（声音只从电视出），界面也就不该再留进度手势、亮度、锁定这些
 * 「看片」的东西，剩下的是一件遥控器该有的按键。
 *
 * 三条通道能力不同，按键按能力置灰：
 *  - NAS：电视端是自家 App，收得到倍速/音量，也会把真实进度回报上来
 *  - 自定义协议（PROOM-CAST/1）：同样全功能，但不经过 NAS —— 家里只开 Wi-Fi、
 *    NAS 关着的时候走这条
 *  - DLNA：协议只有 SetAVTransportURI/Play/Pause/Seek/Stop，没有倍速和音量，
 *    也问不到电视播到第几秒 —— 这些键只能灰掉，点了给提示
 */
@Composable
fun CastRemoteScreen(
    vod: Vod,
    startLine: Int,
    startEp: Int,
    live: Boolean,
    liveUrls: List<List<String>>,
    /**
     * NAS 名录里的电视 id；非空即全功能。
     *
     * 这里只收 id 而不是整个 Target：设备对象带状态快照，从播放页传过来的那份
     * 十有八九已经过期，页面自己按 id 拉最新的一份才不会一直显示离线。
     */
    nasId: String,
    nasName: String,
    /** 局域网 DLNA 设备；nasId 为空时用它 */
    dlnaTarget: CastDevice?,
    /** 自定义协议直连的基址（`http://ip:port`）；非空即走 PROOM-CAST/1 */
    lanBase: String = "",
    /** 自定义协议电视的名字，仅用于首屏显示 */
    lanName: String = "",
    /** 扫码进来的：这一集还没送到电视，进页先补一脚 play */
    autoPlay: Boolean = false,
    onExitCast: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val dlna = remember { DlnaClient() }
    val lanClient = remember { com.projectionroom.shared.cast.LanCastClient() }

    val viaNas = nasId.isNotBlank()
    val viaLan = lanBase.isNotBlank()
    /** 首次拉到之前先用播放页记下的名字，界面不会先空一下 */
    var nasTarget by remember { mutableStateOf<CastApi.Target?>(null) }
    val deviceName = nasTarget?.name ?: nasName.ifBlank {
        if (viaLan) lanName.ifBlank { "电视" } else dlnaTarget?.name ?: "电视"
    }

    var lineIndex by remember {
        mutableIntStateOf(startLine.coerceIn(0, (vod.lines.size - 1).coerceAtLeast(0)))
    }
    var epIndex by remember { mutableIntStateOf(startEp) }
    val line = vod.lines.getOrNull(lineIndex)
    val episodes = line?.episodes.orEmpty()

    var playing by remember { mutableStateOf(true) }
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var rate by remember { mutableFloatStateOf(1f) }
    var volume by remember { mutableFloatStateOf(1f) }
    var online by remember { mutableStateOf(true) }
    // 电视连着但播不动（片源解不出来）：标题区和卡片都要改口，不能还写「正在播放」
    var stuck by remember { mutableStateOf(false) }
    var hint by remember { mutableStateOf<String?>(null) }
    var sheet by remember { mutableStateOf(CastSheet.None) }

    /**
     * 页面刚出来的 400ms 内屏蔽触摸。
     *
     * 这一页是「点投屏设备」那一指的 UP 事件之后才切上来的，可系统还在按原来的
     * 坐标往里分发 —— 实测那一指正好压在这页的进度条上，电视被 seek 到 27 分，
     * HLS 卡在那儿不动，看起来就像投屏坏了。挡一层比在十几个控件上逐个加锁干净。
     */
    var inputArmed by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(400)
        inputArmed = true
    }

    val canSeek = !live
    // 直播没有倍速这回事；DLNA 通道则根本没有这个指令
    val canRate = (viaNas || viaLan) && !live
    val canVolume = viaNas || viaLan

    fun urlOf(l: Int, e: Int): String {
        val ep = vod.lines.getOrNull(l)?.episodes?.getOrNull(e) ?: return ""
        return if (live) liveUrls.getOrNull(e).orEmpty().firstOrNull() ?: ep.url else ep.url
    }

    /** 采集站校验 Referer/UA：裸地址推过去电视端会 403，手机有画面上电视黑屏 */
    fun headersFor(url: String): Map<String, String> = buildMap {
        val origin = runCatching {
            val u = java.net.URI(url)
            "${u.scheme}://${u.host}/"
        }.getOrNull().orEmpty()
        put("User-Agent", PLAYER_UA)
        if (origin.isNotBlank()) put("Referer", origin)
    }

    fun send(cmd: CastApi.Command) {
        when {
            viaNas -> scope.launch {
                val ok = CastHub.client?.send(nasId, CastHub.selfId, cmd) ?: false
                if (!ok) hint = "指令没送到电视"
            }
            viaLan -> scope.launch {
                if (!lanClient.send(lanBase, cmd)) hint = "指令没送到电视"
            }
        }
    }

    // 扫码直接进来时没人发过 play：播放页那条路是 doCastLan 发的，
    // 而这一页是从扫描结果直接切过来的。不补这一下，电视停在首页、
    // 遥控器却已经显示「正在播放」，用户会以为投屏坏了。
    // 只跑一次（key 固定），之后换集走 switchTo。
    LaunchedEffect(lanBase, autoPlay) {
        if (!autoPlay || lanBase.isBlank()) return@LaunchedEffect
        val url = urlOf(lineIndex, epIndex)
        if (url.isBlank()) {
            hint = "这一集没有可用地址"
            return@LaunchedEffect
        }
        val ok = lanClient.send(
            lanBase,
            CastApi.Command(
                kind = "play",
                url = url,
                title = vod.name,
                position = 0,
                headers = headersFor(url),
                vod = vod.name,
                line = lineIndex,
                ep = epIndex,
                episode = vod.lines.getOrNull(lineIndex)?.episodes?.getOrNull(epIndex)?.name.orEmpty(),
            ),
        )
        if (!ok) hint = "电视没响应，地址可能变了"
    }

    // 「电视回报」的应用逻辑只写一份：NAS 和直连两条轮询都调它，
    // 否则 stall 计数、换源清零这些细节迟早一边改了一边没改
    // 电视那头解不出片源时上报的进度永远是 0：界面上照样写「电视正在播放」、
    // 时间停在 00:00，看着像遥控器卡死，实际是电视在放错误页。数够几次再提示
    var stalledCount by remember { mutableIntStateOf(0) }
    var warned by remember { mutableStateOf(false) }
    // 换了片源就重新开始计数：不然换到下一个源时上一次的结论还挂着
    var lastUrl by remember { mutableStateOf("") }
    fun applyRemoteState(s: CastApi.State) {
        if (s.url != lastUrl) {
            lastUrl = s.url
            stalledCount = 0
            warned = false
            stuck = false
        }
        position = s.position
        duration = s.duration
        playing = s.playing
        if (s.rate > 0f) rate = s.rate
        if (s.volume >= 0f) volume = s.volume
        if (!live && s.duration <= 0L && s.position <= 0L && s.playing) {
            stalledCount++
            if (stalledCount >= 5) {
                stuck = true
                if (!warned) {
                    warned = true
                    hint = "电视上放不出来，换个源试试"
                }
            }
        } else {
            stalledCount = 0
            // 播起来了就把话收回去，下次再坏还要能提示
            if (s.duration > 0L) {
                warned = false
                stuck = false
            }
        }
    }

    // 电视端的真实进度/倍速/音量：手机自己记一份会越看越偏 ——
    // 用户用电视遥控器把音量调了，手机滑块还停在原处
    LaunchedEffect(nasId) {
        if (nasId.isBlank()) return@LaunchedEffect
        var misses = 0
        while (true) {
            val d = CastHub.client?.targets(CastHub.selfId)?.firstOrNull { it.id == nasId }
            if (d == null) {
                // 一次拉不到是网络抖动，连着三次才认定离线：误报比不报更烦人
                misses++
                if (misses >= 3 && online) {
                    online = false
                    hint = "电视好像掉线了"
                }
            } else {
                misses = 0
                online = true
                nasTarget = d
                d.state?.let { applyRemoteState(it) }
                if (d.owner.isNotBlank() && d.owner != CastHub.selfId) {
                    hint = "控制权被另一台手机接管了"
                }
            }
            delay(2_000)
        }
    }

    // 自定义协议：直连电视，拿状态不用绕 NAS，1 秒一拉就够顺滑
    LaunchedEffect(lanBase) {
        if (lanBase.isBlank()) return@LaunchedEffect
        var misses = 0
        while (true) {
            val s = lanClient.state(lanBase)
            when {
                s == null -> {
                    misses++
                    if (misses >= 3 && online) {
                        online = false
                        hint = "电视好像掉线了"
                    }
                }
                s.url.isBlank() -> {
                    // 电视那头自己退出了播放页：再按也没东西可遥控
                    online = false
                    stuck = false
                    hint = "电视上已经停止了"
                }
                else -> {
                    misses = 0
                    online = true
                    applyRemoteState(s)
                }
            }
            delay(1_000)
        }
    }

    // DLNA 问不到进度：本地按秒补，至少让时间在走；暂停就停住
    LaunchedEffect(viaNas, viaLan, playing) {
        if (viaNas || viaLan || !playing) return@LaunchedEffect
        while (true) {
            delay(1_000)
            position += 1_000
        }
    }

    LaunchedEffect(hint) {
        if (hint != null) {
            delay(2_600)
            hint = null
        }
    }

    fun switchTo(l: Int, e: Int) {
        if (l == lineIndex && e == epIndex) return
        val url = urlOf(l, e)
        if (url.isBlank()) {
            hint = "这一集没有可用地址"
            return
        }
        lineIndex = l
        epIndex = e
        position = 0L
        duration = 0L
        playing = true
        if (viaNas || viaLan) {
            send(
                CastApi.Command(
                    kind = "play",
                    url = url,
                    title = vod.name,
                    headers = headersFor(url),
                    vod = vod.name,
                    line = l,
                    ep = e,
                    episode = vod.lines.getOrNull(l)?.episodes?.getOrNull(e)?.name.orEmpty(),
                ),
            )
        } else {
            val d = dlnaTarget ?: return
            scope.launch {
                runCatching { dlna.cast(d, url, vod.name, headersFor(url)) }
                    .onFailure { hint = "电视没响应" }
            }
        }
    }

    fun togglePlay() {
        val want = !playing
        playing = want
        if (viaNas || viaLan) {
            // 乐观更新：电视回报有 2 秒延迟，等回报再翻转按钮会让人以为没按到
            send(CastApi.Command(kind = if (want) "resume" else "pause"))
        } else {
            val d = dlnaTarget ?: return
            scope.launch {
                runCatching { if (want) dlna.resume(d) else dlna.pause(d) }
                    .onFailure { hint = "电视没响应" }
            }
        }
    }

    fun seekTo(ms: Long) {
        val target = ms.coerceIn(0L, if (duration > 0) duration else Long.MAX_VALUE)
        position = target
        if (viaNas || viaLan) {
            send(CastApi.Command(kind = "seek", position = target))
        } else {
            val d = dlnaTarget ?: return
            scope.launch { runCatching { dlna.seek(d, target / 1000) } }
        }
    }

    fun applyRate(r: Float) {
        if (!canRate) {
            hint = if (live) "直播不能调倍速" else "这台设备不支持调倍速"
            return
        }
        rate = r
        send(CastApi.Command(kind = "rate", rate = r))
    }

    fun applyVolume(v: Float) {
        if (!canVolume) {
            hint = "这台设备不支持调音量"
            return
        }
        volume = v
        send(CastApi.Command(kind = "volume", volume = v))
    }

    /** 退出投屏：先叫电视停，再离开。设备离线也照样能走 —— 不能把人困在这一页 */
    fun stopAndLeave(then: () -> Unit) {
        if (viaNas || viaLan) {
            scope.launch {
                runCatching {
                    if (viaNas) {
                        CastHub.client?.send(nasId, CastHub.selfId, CastApi.Command(kind = "stop"))
                    } else {
                        lanClient.send(lanBase, CastApi.Command(kind = "stop"))
                    }
                }
            }
        } else {
            val d = dlnaTarget
            if (d != null) scope.launch { runCatching { dlna.stop(d) } }
        }
        then()
    }

    // 返回键必须和页内的「退出」做同一件事：只退界面不叫电视停，
    // 用户会回到详情页，而客厅里的片子还在放，遥控器却没了
    BackHandler(enabled = sheet != CastSheet.None) { sheet = CastSheet.None }
    BackHandler(enabled = sheet == CastSheet.None) { stopAndLeave(onExitCast) }

    Box(
        Modifier
            .fillMaxSize()
            .background(ProomColor.bg),
    ) {
        // 顶部一层很淡的暖光：整屏纯黑会显得像加载失败
        Box(
            Modifier
                .fillMaxWidth()
                .height(240.dp)
                .background(
                    Brush.verticalGradient(
                        listOf(ProomColor.cast.copy(alpha = 0.10f), Color.Transparent),
                    ),
                ),
        )

        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp),
        ) {
            RemoteTopRow(
                deviceName = deviceName,
                fullFeature = viaNas || viaLan,
                online = online,
                onExit = { stopAndLeave(onExitCast) },
                failed = stuck,
            )

            // 设备条固定，下面整块可滚。
            // 展开倍速/音量面板时内容会比一屏高，用 weight 分配的话会把上面的
            // 内容直接挤出屏幕、底部两行入口被裁掉一半，看起来像布局坏了
            Column(
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
            ) {
                Spacer(Modifier.height(18.dp))

                // 信息、进度、主控、微调收进同一块玻璃面板。
                // 它们本来就是「一架遥控器」，拆成四个区块时视线要上下几层来回找；
                // 收进来之后，拇指只在一个区域内移动。
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(22.dp))
                        .background(Color.White.copy(alpha = 0.055f))
                        .border(1.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(22.dp))
                        .padding(horizontal = 18.dp, vertical = 18.dp),
                ) {
                    NowPlayingCard(
                        vod = vod,
                        site = line?.site.orEmpty(),
                        episodeName = episodes.getOrNull(epIndex)?.name.orEmpty(),
                        epIndex = epIndex,
                        live = live,
                        playing = playing,
                        online = online,
                        failed = stuck,
                    )

                    Spacer(Modifier.height(18.dp))
                    RemoteProgress(
                        live = live,
                        enabled = canSeek && online,
                        position = position,
                        duration = duration,
                        onSeek = ::seekTo,
                    )

                    Spacer(Modifier.height(18.dp))
                    MainControls(
                        enabled = online,
                        playing = playing,
                        hasPrev = epIndex > 0,
                        hasNext = epIndex < episodes.size - 1,
                        onPrev = { switchTo(lineIndex, epIndex - 1) },
                        onNext = { switchTo(lineIndex, epIndex + 1) },
                        onToggle = ::togglePlay,
                    )

                    Spacer(Modifier.height(20.dp))
                    SecondaryControls(
                        enabled = online,
                        rate = rate,
                        volume = volume,
                        canRate = canRate,
                        canVolume = canVolume,
                        canSeek = canSeek && online,
                        onSeekBy = { delta -> seekTo(position + delta) },
                        onRate = ::applyRate,
                        onVolume = ::applyVolume,
                    )
                }

                Spacer(Modifier.height(10.dp))

                PanelRow(
                    title = "选集",
                    subtitle = if (episodes.isEmpty()) "这条线路没有分集" else "共 ${episodes.size} 集",
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { sheet = CastSheet.Episodes },
                )
                PanelRow(
                    title = "换源",
                    subtitle = line?.site.orEmpty().ifBlank { "—" },
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { sheet = CastSheet.Lines },
                )

                Spacer(Modifier.height(18.dp))
            }
        }

        // 提示条不用 Dialog：遥控时弹窗要点一下才消失，很打断
        AnimatedVisibility(
            visible = hint != null,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(top = 72.dp),
        ) {
            Box(
                Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xF01A1D24))
                    .border(1.dp, ProomColor.lineStrong, RoundedCornerShape(12.dp))
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            ) {
                Text(hint.orEmpty(), color = ProomColor.text, fontSize = ProomSp(13.sp))
            }
        }

        // 挡掉页面切换那一下残留的触摸。放在抽屉之上，先把第一指吃掉再放行
        if (!inputArmed) {
            Box(
                Modifier
                    .fillMaxSize()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) {},
            )
        }

        CastSheetHost(
            sheet = sheet,
            vod = vod,
            lineIndex = lineIndex,
            epIndex = epIndex,
            onDismiss = { sheet = CastSheet.None },
            onPickEp = { i -> sheet = CastSheet.None; switchTo(lineIndex, i) },
            onPickLine = { i ->
                sheet = CastSheet.None
                // 换线路留在同一集：用户是想换源，不是想跳集
                val upper = (vod.lines.getOrNull(i)?.episodes?.size ?: 1) - 1
                switchTo(i, epIndex.coerceIn(0, upper.coerceAtLeast(0)))
            },
        )
    }
}
