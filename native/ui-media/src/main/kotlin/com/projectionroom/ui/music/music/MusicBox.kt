package com.projectionroom.ui.music

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.projectionroom.shared.catalog.Lrc
import com.projectionroom.shared.catalog.LrcLine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 一首歌：接口只给 id，播放地址是点击时才取的（带时效，不能预取） */
data class MusicTrack(
    val id: String,
    val title: String,
    val artist: String,
    val cover: String = "",
    val album: String = "",
)

enum class RepeatMode { All, One, Shuffle }

/**
 * 全局音乐播放器。
 *
 * 之前是「点一首歌 → 弹一个全屏播放页 → 页面里 new 一个 ExoPlayer」，
 * 退出页面播放器就 release，歌立刻断。而且没法做「底部播放条」这种常驻入口。
 *
 * 现在改成进程内单例：播放器、队列、进度都挂在这里，
 * 歌单页、迷你条、全屏页读的是同一份状态 —— 关掉全屏页歌还在放。
 *
 * 不做前台服务/通知栏：这版只保证 App 在前台时正常，退到后台被系统回收属于预期。
 */
object MusicBox {

    /** 接口挑 UA，和 MusicApi 一致；不带头某些 CDN 会 403 */
    private const val UA =
        "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/120.0 Mobile Safari/537.36"

    var queue: List<MusicTrack> by mutableStateOf(emptyList())
        private set
    var index: Int by mutableStateOf(0)
        private set
    var playing: Boolean by mutableStateOf(false)
        private set
    var loading: Boolean by mutableStateOf(false)
        private set
    var position: Long by mutableStateOf(0L)
        private set
    var duration: Long by mutableStateOf(0L)
        private set
    var repeat: RepeatMode by mutableStateOf(RepeatMode.All)
        private set
    var error: String? by mutableStateOf(null)
        private set

    /** 歌单里正在播/刚播过的那首，用来在列表里打高亮 */
    var playingId: String by mutableStateOf("")
        private set

    /** 当前这首的歌词（没版权/没歌词就是空表） */
    var lyric: List<LrcLine> by mutableStateOf(emptyList())
        private set

    /** 当前唱到第几行；-1 = 还没开始 */
    var lyricAt: Int by mutableStateOf(-1)
        private set

    /**
     * 当前这首是否已收藏。
     *
     * 放在 MusicBox 而不是各页面自己查偏好：通知栏和全屏页要读同一份，
     * 在通知栏点了收藏，回到页面必须已经变了 —— 各查各的做不到。
     */
    var fav: Boolean by mutableStateOf(false)
        private set

    val current: MusicTrack? get() = queue.getOrNull(index)
    val hasQueue: Boolean get() = queue.isNotEmpty()

    private var exo: ExoPlayer? = null
    private var resolve: (suspend (String) -> String)? = null
    private var resolveLyric: (suspend (String) -> List<LrcLine>)? = null
    private var lyricJob: Job? = null
    private var loadJob: Job? = null
    private var ticker: Job? = null
    /** 连续失败计数：整张歌单都没音源时不能无限自动「下一首」 */
    private var failStreak = 0
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    /** 没装 resolver 之前的最后一次请求，装上后补播 */
    private var pending: Triple<Context, List<MusicTrack>, Int>? = null

    /** 由壳注入「取播放地址 / 取歌词」的能力（要问外部接口，不能写死在 UI 层） */
    fun install(
        resolve: suspend (String) -> String,
        lyric: suspend (String) -> List<LrcLine> = { emptyList() },
    ) {
        this.resolve = resolve
        this.resolveLyric = lyric
        pending?.let { (ctx, q, i) ->
            pending = null
            playAll(ctx, q, i)
        }
    }

    private fun player(ctx: Context): ExoPlayer {
        exo?.let { return it }
        val p = ExoPlayer.Builder(ctx.applicationContext).build()
        // 让系统按音乐处理：来电/别的 App 播放时自动压低或暂停
        p.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                .build(),
            true,
        )
        p.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                playing = isPlaying
            }

            override fun onPlaybackStateChanged(state: Int) {
                when (state) {
                    Player.STATE_READY -> {
                        loading = false
                        duration = p.duration.coerceAtLeast(0L)
                        // 会话 id 要等播放器真正建好才有，这里绑定并套用当前预设。
                        // 每次都调：看完视频后 eq 已经绑到视频的会话上了，回音乐得抢回来
                        AudioFx.init(ctx)
                        AudioFx.attach(p.audioSessionId)
                    }
                    Player.STATE_BUFFERING -> loading = true
                    Player.STATE_ENDED -> {
                        // 睡眠定时设的是「听完这首」：到末尾就停，不自动接下一首
                        if (SleepTimer.onTrackEnded()) {
                            playing = false
                        } else if (repeat != RepeatMode.One) {
                            // 单曲循环交给 ExoPlayer 自己；其余走「下一首」
                            next(auto = true)
                        }
                    }
                }
            }

            override fun onPlayerError(e: androidx.media3.common.PlaybackException) {
                loading = false
                // 版权/下架的曲子在接口里就是个空地址：跳下一首，别停在这儿
                skipOrStop("这首放不了，已跳过")
            }
        })
        exo = p
        // 睡眠定时的预淡出：定时器只给 0..1 的系数，落到哪一路由这里决定。
        // 注册在播放器创建时，所以全屏页关掉、只在后台放时也照样收音量。
        SleepTimer.setFadeSink(this) { k -> runCatching { p.volume = k } }
        startTicker()
        return p
    }

    private fun startTicker() {
        if (ticker != null) return
        ticker = scope.launch {
            while (true) {
                exo?.let {
                    // 暂停时也要读一次：暂停状态下拖进度条，歌词同样得跟到那一句。
                    // 值没变时 mutableStateOf 不会触发重组，所以不算白跑
                    val pos = it.currentPosition
                    if (pos != position) position = pos
                    if (it.duration > 0 && it.duration != duration) duration = it.duration
                    val at = Lrc.indexAt(lyric, pos)
                    if (at != lyricAt) lyricAt = at
                }
                // 250ms：歌词换行要跟得上，1 秒的粒度会把间隔短的句子直接跳过去
                delay(250)
            }
        }
    }

    /** 整张歌单开播 */
    fun playAll(ctx: Context, tracks: List<MusicTrack>, start: Int, shuffle: Boolean = false) {
        if (tracks.isEmpty()) return
        appContext = ctx.applicationContext
        val resolver = resolve ?: run {
            pending = Triple(ctx, tracks, start)
            return
        }
        queue = tracks
        index = start.coerceIn(0, tracks.size - 1)
        repeat = if (shuffle) RepeatMode.Shuffle else RepeatMode.All
        loadCurrent(ctx, resolver)
    }

    /** 在已排好的队列里跳某一首 */
    fun playAt(ctx: Context, i: Int) {
        if (i !in queue.indices) return
        index = i
        resolve?.let { loadCurrent(ctx, it) }
    }

    private fun loadCurrent(ctx: Context, resolver: suspend (String) -> String) {
        val t = current ?: return
        loadLyric(t)
        loadJob?.cancel()
        error = null
        loading = true
        playingId = t.id
        fav = MusicLib.isFav(ctx, t.id, t.title, t.artist)
        // 开播就算一条播放记录（「我的 - 播放记录」里的音乐页签靠它）
        MusicLib.touch(ctx, t)
        val p = player(ctx)
        loadJob = scope.launch {
            val url = runCatching { resolver(t.id) }.getOrDefault("")
            if (url.isBlank()) {
                // 没有版权/下架：直接跳下一首（整个队列都没地址时兜底停下）
                skipOrStop("「${t.title}」没有可用音源，已跳过")
                return@launch
            }
            failStreak = 0
            val factory = DefaultHttpDataSource.Factory()
                .setAllowCrossProtocolRedirects(true)
                .setDefaultRequestProperties(mapOf("User-Agent" to UA))
            p.setMediaSource(
                DefaultMediaSourceFactory(factory).createMediaSource(
                    MediaItem.Builder().setUri(url).build()
                )
            )
            p.prepare()
            p.playWhenReady = true
        }
    }

    /**
     * 拉歌词：异步、失败就算了。
     *
     * 这里必须核对「拿回来时还是不是这首」—— 用户连点两次下一首时，
     * 上一首的歌词可能后到，直接赋值就会串台。
     */
    private fun loadLyric(t: MusicTrack) {
        lyric = emptyList()
        lyricAt = -1
        lyricJob?.cancel()
        val fetch = resolveLyric ?: return
        lyricJob = scope.launch {
            val lines = runCatching { fetch(t.id) }.getOrNull().orEmpty()
            if (current?.id == t.id) {
                lyric = lines
                // 歌词是异步到的：到货时按当前进度定位，别停在第一句
                lyricAt = Lrc.indexAt(lines, position)
            }
        }
    }

    /** 一首放不了就跳下一首；整张歌单都失败就停，别无限空转 */
    private fun skipOrStop(msg: String) {
        failStreak += 1
        if (failStreak >= queue.size.coerceAtLeast(1)) {
            error = "整个歌单都放不了，可能是接口限流"
            loading = false
            exo?.pause()
            return
        }
        error = msg
        next(auto = true)
    }

    fun toggle() {
        val p = exo ?: return
        if (p.isPlaying) {
            p.pause()
        } else {
            // 刚看完视频时均衡器还挂在视频的音频会话上，这里抢回来
            appContext?.let { AudioFx.init(it) }
            AudioFx.attach(p.audioSessionId)
            p.play()
        }
    }

    fun next(auto: Boolean = false) {
        if (queue.isEmpty()) return
        val ctx = appContext ?: return
        val resolver = resolve ?: return
        index = when (repeat) {
            // 随机：换一首和当前不同的
            RepeatMode.Shuffle -> if (queue.size == 1) index else {
                var n = index
                while (n == index) n = (queue.indices).random()
                n
            }
            else -> (index + 1) % queue.size
        }
        loadCurrent(ctx, resolver)
    }

    fun prev() {
        if (queue.isEmpty()) return
        val ctx = appContext ?: return
        val resolver = resolve ?: return
        // 播了 5 秒以上按「重头放」，和主流播放器一致
        if (position > 5_000L) {
            seek(0)
            return
        }
        index = (index - 1 + queue.size) % queue.size
        loadCurrent(ctx, resolver)
    }

    fun seek(ms: Long) {
        val to = ms.coerceAtLeast(0L)
        exo?.seekTo(to)
        position = to
        // 拖动后立刻重算当前句；等 ticker 的话暂停状态下会停在上一次的位置
        val at = Lrc.indexAt(lyric, to)
        if (at != lyricAt) lyricAt = at
    }

    /**
     * 睡眠定时到点：先把声音收掉再暂停。
     *
     * 深夜戴耳机时「啪」一下静音比什么都提神，所以走 3 秒线性淡出；
     * 淡完把音量还原，下次播放不会只剩半格声。
     */
    fun fadeOutAndPause(ms: Long = 3_000) {
        val p = exo ?: return
        scope.launch {
            val steps = 30
            val gap = (ms / steps).coerceAtLeast(40L)
            // 从「当前音量」开始降：预淡出已经收掉大半时，
            // 再从 1.0 起跳会先炸一下再变小
            val from = runCatching { p.volume }.getOrDefault(1f)
            for (i in steps downTo 0) {
                runCatching { p.volume = from * i / steps }
                delay(gap)
            }
            p.pause()
            playing = false
            runCatching { p.volume = 1f }
        }
    }

    fun cycleRepeat() {
        repeat = when (repeat) {
            RepeatMode.All -> RepeatMode.One
            RepeatMode.One -> RepeatMode.Shuffle
            RepeatMode.Shuffle -> RepeatMode.All
        }
        exo?.repeatMode = if (repeat == RepeatMode.One) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
    }

    /** 收藏 / 取消收藏当前这首（全屏页和通知栏共用这一个入口） */
    fun toggleFav() {
        val t = current ?: return
        val ctx = appContext ?: return
        fav = MusicLib.toggle(ctx, t)
    }

    fun stop() {
        exo?.pause()
        exo?.clearMediaItems()
        playing = false
        loading = false
        position = 0
        duration = 0
        queue = emptyList()
        playingId = ""
        fav = false
    }

    /**
     * 让路：视频/直播要开声了，音乐必须闭麦。
     *
     * 只 pause 不 stop —— 队列留着，用户从播放器退出来还能按播放键接着听。
     * 两条音轨同时出声（一个放电影对白、一个放歌）是这类 App 最典型的翻车点。
     */
    fun pause() {
        exo?.pause()
        playing = false
    }

    /**
     * 真正退出 App 时调用：停播 + **释放** ExoPlayer。
     *
     * 不能只调 [stop]：那只是 pause + 清队列，ExoPlayer 实例和它持有的
     * 音频输出还挂在进程里。进程被系统缓存后它仍然会出声 ——
     * 用户看到的就是「App 都关了歌还在放」。
     */
    fun shutdown() {
        ticker?.cancel()
        ticker = null
        loadJob?.cancel()
        lyricJob?.cancel()
        exo?.let {
            // stop 之后再 release：release 是异步的，正在缓冲的流要先掐断，
            // 否则会有一声残响
            it.stop()
            it.release()
        }
        exo = null
        playing = false
        loading = false
        position = 0
        duration = 0
        queue = emptyList()
        index = 0
        playingId = ""
        lyric = emptyList()
        lyricAt = -1
        fav = false
        error = null
        failStreak = 0
    }

    private var appContext: Context? = null

    /**
     * 给 MediaSession 用：通知栏要拿同一个 player 实例。
     *
     * 没有播放过任何歌时也会创建一个空播放器，这是 MediaSession 的要求
     * （session 必须绑定 player 才能响应播放键）。
     */
    fun ensurePlayer(ctx: Context): Player {
        appContext = ctx.applicationContext
        return player(ctx)
    }

    /** 全屏页/迷你条点击前都要先确保播放器在（ExoPlayer 要 Context） */
    fun bind(ctx: Context) {
        appContext = ctx.applicationContext
        player(ctx)
    }
}
