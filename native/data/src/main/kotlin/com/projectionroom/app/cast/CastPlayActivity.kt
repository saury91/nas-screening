package com.projectionroom.app.cast

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.projectionroom.app.data.R

/**
 * 接收端播放页：把推过来的地址放出来，并充当手机遥控器的执行端。
 *
 * 刻意不复用点播播放器：那套是 Vod/线路/选集驱动的，投屏只有一个裸地址，
 * 套进去要造一堆假数据。
 *
 * 两条投屏通道（局域网 DMR、NAS 桥接）最终都落到这里，播放页不关心来路。
 */
class CastPlayActivity : ComponentActivity() {

    private var exo: ExoPlayer? = null
    private var playerView: PlayerView? = null

    /** Compose 侧读这个。播放进度每秒一变，用 State 才不用整页重建 */
    private val ui = mutableStateOf(CastUiState())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val url = intent.getStringExtra(EXTRA_URL).orEmpty()
        if (url.isBlank()) { finish(); return }

        val pv = layoutInflater.inflate(R.layout.cast_player, null) as PlayerView
        // 明确按比例完整显示：电视只有一块屏，画面被裁掉一部分就等于看不全。
        // 布局里已经写了，这里再设一次是防以后有人改 XML 把 resize_mode 带偏。
        pv.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
        pv.useController = false   // 控制条改由 Compose 画，media3 自带那套太糙
        playerView = pv

        setContent {
            CastPlayerScreen(
                state = ui.value,
                playerView = { pv },
                player = { exo },
                onTogglePlay = { togglePlay() },
                onSeekBy = { delta -> seekTo((exo?.currentPosition ?: 0L) + delta) },
                onSeekTo = { ms -> seekTo(ms) },
                onRate = { applyRate(it) },
                onVolume = { applyVolume(it) },
                onExit = { finish() },
            )
        }

        // 返回键分两段：控制层开着时先把它收起来，再按一次才退出。
        // 电视上误按一次就把正在看的片子关掉，用户得重新投一遍
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { finish() }
        })

        applyIntent(intent)
        startTicker()
    }

    /**
     * 新建一个播放器并挂好监听。
     *
     * 换片时必须换新的：同一个 ExoPlayer 换 MediaSource，渲染器会经历
     * videoSize 归零再恢复，输出矩阵留着上一次的缩放 —— 实测投第二部片时，
     * 画面被压进屏幕下方 1920x970 那块地方，上面 110px 纯黑、下面贴底，
     * 而 View 层级全都还是 1920x1080（dump 确认过），只有渲染是错的。
     * 重建播放器是唯一能把这套状态彻底清掉的做法。
     */
    private fun newPlayer(): ExoPlayer = ExoPlayer.Builder(this).build().apply {
        addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) {
                    CastReceiver.state = "STOPPED"
                    ui.value = ui.value.copy(playing = false)
                }
                ui.value = ui.value.copy(
                    buffering = state == Player.STATE_BUFFERING,
                    playing = isPlaying,
                )
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                ui.value = ui.value.copy(playing = isPlaying)
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                android.util.Log.i(
                    "CASTDBG",
                    "videoSize=${videoSize.width}x${videoSize.height} par=${videoSize.pixelWidthHeightRatio}",
                )
            }

            override fun onPlayerError(error: PlaybackException) {
                // 手机上能播、这里播不了，十有八九是源站鉴权或分片被拦。
                // 把原因打出来，比在电视上盯着黑屏猜有用。
                android.util.Log.e("CASTDBG", "cast playback failed url=${CastReceiver.uri}", error)
                ui.value = ui.value.copy(
                    error = "这一集在电视上播不了：" + (error.errorCodeName ?: "未知错误"),
                    buffering = false,
                )
            }
        })
    }

    /**
     * 播放页已经开着时又被投了一次（SINGLE_TOP 复用，不会走 onCreate）。
     *
     * 不加这个，手机上点「下一集」以后电视还在放上一集 —— 界面、片名、
     * 进度条全是旧的，用户会以为没投上去。
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getStringExtra(EXTRA_URL).orEmpty().isBlank()) return
        applyIntent(intent)
    }

    private fun applyIntent(intent: Intent) {
        val url = intent.getStringExtra(EXTRA_URL).orEmpty()
        if (url.isBlank()) return
        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty().ifBlank { "投屏内容" }
        val ua = intent.getStringExtra(EXTRA_UA).orEmpty()
        val referer = intent.getStringExtra(EXTRA_REF).orEmpty()
        val line = intent.getIntExtra(EXTRA_LINE, 0)
        val ep = intent.getIntExtra(EXTRA_EP, 0)
        val episode = intent.getStringExtra(EXTRA_EPISODE).orEmpty()

        // 采集站的 m3u8 大多校验 Referer/UA：手机端播放器自己带了才播得动，
        // 电视端以前裸拉地址，同一集在手机上有画面、投上来就 403。头是推流方
        // 写进 DIDL 的 x-proom-* 属性、或者 NAS 指令里的 headers 带过来的。
        val headers = buildMap {
            if (ua.isNotBlank()) put("User-Agent", ua)
            if (referer.isNotBlank()) put("Referer", referer)
        }
        val factory = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setDefaultRequestProperties(headers)

        // m3u8 靠后缀判断不可靠（很多地址带 ?token= 之类的参数），显式指定
        val item = MediaItem.Builder()
            .setUri(url)
            .apply { if (url.contains(".m3u8", true)) setMimeType(MimeTypes.APPLICATION_M3U8) }
            .build()

        // 换播放器，不是换 MediaSource —— 原因见 newPlayer() 的注释
        val old = exo
        val fresh = newPlayer()
        exo = fresh
        playerView?.player = fresh
        old?.release()

        // 新片沿用上一部设定的倍速/音量：用户调到 1.5 倍看剧，切下一集
        // 自动掉回 1 倍会很突兀
        fresh.setPlaybackSpeed(CastReceiver.rate)
        fresh.volume = CastReceiver.volume

        fresh.setMediaSource(DefaultMediaSourceFactory(factory).createMediaSource(item))
        fresh.prepare()
        fresh.playWhenReady = true

        CastReceiver.state = "PLAYING"
        CastReceiver.uri = url
        CastReceiver.title = title
        CastReceiver.positionMs = 0
        CastReceiver.durationMs = 0
        CastReceiver.uriHeaders = headers
        CastReceiver.line = line
        CastReceiver.ep = ep
        CastReceiver.episode = episode

        ui.value = CastUiState(
            title = title,
            episode = episode,
            line = line,
            ep = ep,
            playing = true,
            buffering = true,
            rate = CastReceiver.rate,
            volume = CastReceiver.volume,
        )
    }

    private fun togglePlay() {
        val p = exo ?: return
        if (p.isPlaying) {
            p.pause()
            CastReceiver.state = "PAUSED_PLAYBACK"
        } else {
            p.play()
            CastReceiver.state = "PLAYING"
        }
        ui.value = ui.value.copy(playing = p.isPlaying)
    }

    private fun seekTo(ms: Long) {
        val p = exo ?: return
        val target = ms.coerceIn(0L, if (p.duration > 0) p.duration else Long.MAX_VALUE)
        p.seekTo(target)
        CastReceiver.positionMs = target
        ui.value = ui.value.copy(positionMs = target)
    }

    private fun applyRate(rate: Float) {
        if (rate <= 0f) return
        exo?.setPlaybackSpeed(rate)
        CastReceiver.rate = rate
        ui.value = ui.value.copy(rate = rate)
    }

    private fun applyVolume(v: Float) {
        val vol = v.coerceIn(0f, 1f)
        exo?.volume = vol
        CastReceiver.volume = vol
        ui.value = ui.value.copy(volume = vol)
    }

    private fun startTicker() {
        val t = android.os.Handler(mainLooper)
        val r = object : Runnable {
            override fun run() {
                exo?.let {
                    val pos = it.currentPosition.coerceAtLeast(0)
                    CastReceiver.positionMs = pos
                    CastReceiver.durationMs = it.duration.coerceAtLeast(0)
                    if (it.isPlaying) CastReceiver.state = "PLAYING"
                    // 进度条每秒走一格。用 copy 而不是整体重建，Compose 只重画变了的字段
                    ui.value = ui.value.copy(
                        positionMs = pos,
                        durationMs = it.duration.coerceAtLeast(0),
                        playing = it.isPlaying,
                        buffering = it.playbackState == Player.STATE_BUFFERING,
                    )
                }
                t.postDelayed(this, 1_000)
            }
        }
        t.postDelayed(r, 500)
    }

    override fun onStart() {
        super.onStart()
        // 手机端发来的控制指令落到播放器上
        CastReceiver.onPause = { exo?.pause(); CastReceiver.state = "PAUSED_PLAYBACK" }
        CastReceiver.onResume = { exo?.play(); CastReceiver.state = "PLAYING" }
        CastReceiver.onSeek = { ms -> seekTo(ms) }
        CastReceiver.onRate = { applyRate(it) }
        CastReceiver.onVolume = { applyVolume(it) }
        CastReceiver.onStop = { finish() }
    }

    override fun onStop() {
        CastReceiver.onPause = null
        CastReceiver.onResume = null
        CastReceiver.onSeek = null
        CastReceiver.onRate = null
        CastReceiver.onVolume = null
        CastReceiver.onStop = null
        super.onStop()
    }

    override fun onDestroy() {
        exo?.release()
        exo = null
        if (CastReceiver.state != "STOPPED") CastReceiver.reset()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_URL = "cast_url"
        const val EXTRA_TITLE = "cast_title"
        const val EXTRA_UA = "cast_ua"
        const val EXTRA_REF = "cast_ref"
        const val EXTRA_LINE = "cast_line"
        const val EXTRA_EP = "cast_ep"
        const val EXTRA_EPISODE = "cast_episode"

        fun intent(
            ctx: Context,
            url: String,
            title: String,
            headers: Map<String, String> = emptyMap(),
            line: Int = 0,
            ep: Int = 0,
            episode: String = "",
        ): Intent = Intent(ctx, CastPlayActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(EXTRA_URL, url)
            .putExtra(EXTRA_TITLE, title)
            .putExtra(EXTRA_UA, headers["User-Agent"].orEmpty())
            .putExtra(EXTRA_REF, headers["Referer"].orEmpty())
            .putExtra(EXTRA_LINE, line)
            .putExtra(EXTRA_EP, ep)
            .putExtra(EXTRA_EPISODE, episode)

        /**
         * 拉起播放页；进程不在前台时改走全屏通知。
         *
         * 电视端被投屏的那一刻，用户大半正停在系统桌面或别的应用上 —— App 退到
         * 后台后，Android 10+ 会直接丢掉 startActivity，而且**不抛异常**：
         * 手机上显示「已投屏」，电视一片安静。所以这里不能靠 try/catch 判断成败，
         * 得先问清楚自己是不是在前台。
         *
         * 前台就正常启动；不在前台就走带 fullScreenIntent 的通知，由 SystemUI
         * 代劳拉起（系统发起的启动不受后台限制）。
         */
        fun launch(
            ctx: Context,
            url: String,
            title: String,
            headers: Map<String, String> = emptyMap(),
            line: Int = 0,
            ep: Int = 0,
            episode: String = "",
        ) {
            if (url.isBlank()) return
            val i = intent(ctx, url, title, headers, line, ep, episode)
            if (isAppVisible(ctx)) {
                runCatching { ctx.startActivity(i) }
                    .onFailure { android.util.Log.w("CASTDBG", "cast startActivity failed: ${it.message}") }
                return
            }
            // 兜底也要能看见：通知被用户关了的话只能退化成日志
            android.util.Log.i("CASTDBG", "cast: 进程在后台，改走全屏通知拉起播放页")
            CastService.promptCast(ctx, i, title)
        }

        /**
         * 本进程当前有没有可见的界面。
         *
         * 用 importance 而不是自己维护 Activity 计数：投屏播到一半、用户又切去
         * 别处，计数容易漏减；importance 是系统给的实时值，没有这个顾虑。
         * 有前台服务时是 FOREGROUND_SERVICE，有界面才是 FOREGROUND。
         */
        private fun isAppVisible(ctx: Context): Boolean = runCatching {
            val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            am.runningAppProcesses.orEmpty().any {
                it.pid == android.os.Process.myPid() &&
                    it.importance == android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
            }
        }.getOrDefault(false)
    }
}
