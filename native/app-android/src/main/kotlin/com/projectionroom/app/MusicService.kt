package com.projectionroom.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import com.projectionroom.ui.music.MusicBox
import com.projectionroom.ui.music.RepeatMode

/**
 * 音乐的后台服务：前台通知 + 媒体会话。
 *
 * **为什么不用 MediaStyle**：Android 11 以后通知栏里的媒体卡片是系统画的，
 * 布局固定成「应用图标 + 歌名 + 歌手 + 三个按钮」—— 封面放不进去，
 * 循环和收藏也没有位置。这里改用自定义 RemoteViews，布局和 App 内的迷你条同构：
 *
 * ```
 * [封面] 歌名
 *        当前歌词
 * ────────────────────  进度
 *  ↻   ⏮   ⏸   ⏭   ♡
 * ```
 *
 * 代价是失去系统的「输出设备」切换芯片和锁屏媒体卡。MediaSession 仍然保留，
 * 蓝牙耳机/车机的播放键照常工作。
 */
class MusicService : Service() {

    companion object {
        const val ACTION_TOGGLE = "com.projectionroom.action.TOGGLE"
        const val ACTION_NEXT = "com.projectionroom.action.NEXT"
        const val ACTION_PREV = "com.projectionroom.action.PREV"
        const val ACTION_REPEAT = "com.projectionroom.action.REPEAT"
        const val ACTION_FAV = "com.projectionroom.action.FAV"
        const val ACTION_STOP = "com.projectionroom.action.STOP"

        /** 点通知进全屏播放页；MainActivity 读这个 extra 决定落地页 */
        const val EXTRA_OPEN_PLAYER = "open_music_player"

        private const val CHANNEL = "proom_music"
        private const val NOTIF_ID = 0x4D01

        /** 封面 CDN 会挑 UA，和 MusicBox 保持一致 */
        private const val UA =
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/120.0 Mobile Safari/537.36"

        fun start(ctx: Context) {
            runCatching {
                ctx.startService(Intent(ctx, MusicService::class.java))
            }
        }
    }

    private var session: MediaSessionCompat? = null
    private val handler = Handler(Looper.getMainLooper())
    private var foreground = false
    /** 上一次同步给通知的状态，没变就不重复 build（每秒重发一次很费电） */
    private var lastKey = ""

    /** 当前这首的封面（已切圆角），通知模板里直接用 */
    private var cover: Bitmap? = null
    /** 封面正在为哪首歌下载 —— 用它丢弃「切歌后上一首的封面才到」的迟到结果 */
    private var coverFor = ""

    private val tick = object : Runnable {
        override fun run() {
            sync()
            handler.postDelayed(this, 1_000)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        session = MediaSessionCompat(this, "ProomMusic").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() {
                    if (!MusicBox.playing) MusicBox.toggle()
                }

                override fun onPause() {
                    if (MusicBox.playing) MusicBox.toggle()
                }

                override fun onSkipToNext() = MusicBox.next()
                override fun onSkipToPrevious() = MusicBox.prev()
                override fun onSeekTo(pos: Long) = MusicBox.seek(pos)
                override fun onStop() = stopSelf()
                override fun onCustomAction(action: String?, extras: android.os.Bundle?) {
                    when (action) {
                        ACTION_REPEAT -> MusicBox.cycleRepeat()
                        ACTION_FAV -> MusicBox.toggleFav()
                    }
                }
            })
            isActive = true
        }
        handler.post(tick)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TOGGLE -> MusicBox.toggle()
            ACTION_NEXT -> MusicBox.next()
            ACTION_PREV -> MusicBox.prev()
            ACTION_REPEAT -> MusicBox.cycleRepeat()
            ACTION_FAV -> MusicBox.toggleFav()
            ACTION_STOP -> {
                MusicBox.stop()
                stopSelf()
                return START_NOT_STICKY
            }
        }
        // 起来就先挂一个前台通知（Android 8+ 后台播放必须是前台服务），
        // 没有歌在放的时候它会在 sync 里被收掉
        if (!foreground) {
            runCatching { startForeground(NOTIF_ID, build(playing = false)) }
            foreground = true
        }
        sync()
        return START_STICKY
    }

    private fun sync() {
        val track = MusicBox.current
        val playing = MusicBox.playing
        if (track == null) {
            if (foreground) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                foreground = false
            }
            lastKey = ""
            return
        }
        coverFor(track)

        // 会话仍然同步给系统：锁屏、蓝牙耳机、车机的按键都靠它
        session?.setMetadata(
            MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, track.title)
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, track.artist)
                .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, MusicBox.duration)
                .apply { cover?.let { putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, it) } }
                .build()
        )
        session?.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(
                    PlaybackStateCompat.ACTION_PLAY or
                        PlaybackStateCompat.ACTION_PAUSE or
                        PlaybackStateCompat.ACTION_PLAY_PAUSE or
                        PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                        PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                        PlaybackStateCompat.ACTION_SEEK_TO or
                        PlaybackStateCompat.ACTION_STOP
                )
                .setState(
                    if (playing) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED,
                    MusicBox.position,
                    1f,
                    android.os.SystemClock.elapsedRealtime(),
                )
                .setBufferedPosition(
                    if (MusicBox.duration > 0) MusicBox.duration else MusicBox.position
                )
                .build()
        )

        // 进度每秒变一次，所以 key 里带上进度分档（1% 一档），
        // 歌词、循环模式、收藏状态变了也要重画
        val pct = if (MusicBox.duration > 0) {
            (MusicBox.position * 100 / MusicBox.duration).toInt().coerceIn(0, 100)
        } else 0
        val key = "${track.id}|$playing|${MusicBox.repeat}|${MusicBox.fav}|" +
            "${MusicBox.lyricAt}|$pct|${if (cover != null) 1 else 0}"
        if (key == lastKey) return
        lastKey = key

        val n = build(playing)
        if (foreground) {
            runCatching {
                (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                    .notify(NOTIF_ID, n)
            }
        } else {
            runCatching { startForeground(NOTIF_ID, n) }
            foreground = true
        }
    }

    private fun build(playing: Boolean): Notification {
        val track = MusicBox.current
        // 点整张卡片（歌名也算在内）进全屏播放页
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(EXTRA_OPEN_PLAYER, true),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        fun act(action: String): PendingIntent = PendingIntent.getService(
            this, action.hashCode(),
            Intent(this, MusicService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val bar = RemoteViews(packageName, R.layout.notif_music)
        bar.setOnClickPendingIntent(R.id.n_repeat, act(ACTION_REPEAT))
        bar.setOnClickPendingIntent(R.id.n_prev, act(ACTION_PREV))
        bar.setOnClickPendingIntent(R.id.n_play, act(ACTION_TOGGLE))
        bar.setOnClickPendingIntent(R.id.n_next, act(ACTION_NEXT))
        bar.setOnClickPendingIntent(R.id.n_fav, act(ACTION_FAV))
        // 封面和歌名区域都点进播放页：整个头部可点，比只有一行文字好按
        bar.setOnClickPendingIntent(R.id.n_cover, open)
        bar.setOnClickPendingIntent(R.id.n_title, open)
        bar.setOnClickPendingIntent(R.id.n_sub, open)

        bar.setTextViewText(R.id.n_title, track?.title ?: "放映室")
        bar.setTextViewText(R.id.n_sub, subtitle())
        bar.setImageViewResource(R.id.n_prev, R.drawable.ic_n_prev)
        bar.setImageViewResource(R.id.n_next, R.drawable.ic_n_next)
        bar.setImageViewResource(R.id.n_play, if (playing) R.drawable.ic_n_pause else R.drawable.ic_n_play)
        bar.setImageViewResource(R.id.n_repeat, repeatIcon())
        bar.setImageViewResource(R.id.n_fav, if (MusicBox.fav) R.drawable.ic_n_heart_on else R.drawable.ic_n_heart)
        bar.setImageViewBitmap(R.id.n_cover, cover)

        // 自定义通知的底色由系统给（浅色主题就是白卡），
        // 所以文字/图标不能写死白色 —— 这里按系统明暗换色
        val night = (resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        val textColor = if (night) 0xFFF4F5F7.toInt() else 0xFF12141A.toInt()
        val subColor = if (night) 0xFFB3B8C3.toInt() else 0xFF5A6070.toInt()
        // 白色描边的矢量图在浅色卡片上会消失，统一染一次色。
        // 不用「仅在浅色时染色」，是因为 RemoteViews 没法清掉上一次的滤镜 ——
        // 每次都给一个明确颜色，状态就永远是确定的。
        val iconColor = if (night) 0xFFFFFFFF.toInt() else 0xFF12141A.toInt()
        val heartColor = if (MusicBox.fav) {
            if (night) 0xFFFFCF7A.toInt() else 0xFFC8871F.toInt()
        } else iconColor
        bar.setTextColor(R.id.n_title, textColor)
        bar.setTextColor(R.id.n_sub, subColor)
        listOf(R.id.n_repeat, R.id.n_prev, R.id.n_play, R.id.n_next)
            .forEach { bar.setInt(it, "setColorFilter", iconColor) }
        bar.setInt(R.id.n_fav, "setColorFilter", heartColor)

        bar.setProgressBar(
            R.id.n_progress, 1000,
            if (MusicBox.duration > 0) {
                (MusicBox.position * 1000 / MusicBox.duration).toInt().coerceIn(0, 1000)
            } else 0,
            false,
        )

        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_n_play)
            .setContentTitle(track?.title ?: "放映室")
            .setContentText(track?.artist.orEmpty())
            .setContentIntent(open)
            .setCustomContentView(bar)
            .setCustomBigContentView(bar)
            .setStyle(NotificationCompat.DecoratedCustomViewStyle())
            .setOngoing(playing)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
    }

    /** 第二行：有歌词就显示当前这句，没有才退回歌手名 */
    private fun subtitle(): String {
        val t = MusicBox.current ?: return ""
        val at = MusicBox.lyricAt
        val line = MusicBox.lyric.getOrNull(at)?.text?.trim().orEmpty()
        return when {
            MusicBox.loading -> "正在加载…"
            MusicBox.error != null -> MusicBox.error ?: ""
            line.isNotBlank() -> line
            else -> t.artist
        }
    }

    private fun repeatIcon(): Int = when (MusicBox.repeat) {
        RepeatMode.All -> R.drawable.ic_n_repeat
        RepeatMode.One -> R.drawable.ic_n_repeat_one
        RepeatMode.Shuffle -> R.drawable.ic_n_shuffle
    }

    /**
     * 拉封面并圆角化。
     *
     * 通知里的图必须是 Bitmap（RemoteViews 只能塞 bitmap/resId），
     * 所以这里自己下、自己裁 —— 不用 Coil 是因为它的 API 在 app 模块
     * 拿 imageLoader 要绕一圈，为一张 54dp 的图不值当。
     */
    private fun coverFor(track: com.projectionroom.ui.music.MusicTrack) {
        if (track.cover.isBlank() || coverFor == track.id) return
        coverFor = track.id
        cover = null
        val id = track.id
        val url = track.cover
        Thread {
            val bmp = runCatching {
                val c = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                c.connectTimeout = 8_000
                c.readTimeout = 8_000
                c.setRequestProperty("User-Agent", UA)
                c.inputStream.use { BitmapFactory.decodeStream(it) }
            }.getOrNull() ?: return@Thread
            val rounded = runCatching { round(bmp, 8f * resources.displayMetrics.density) }
                .getOrDefault(bmp)
            handler.post {
                // 期间可能已经切歌：只认还在等的那一首
                if (coverFor == id) {
                    cover = rounded
                    lastKey = ""
                    sync()
                }
            }
        }.start()
    }

    private fun round(src: Bitmap, radius: Float): Bitmap {
        val size = minOf(src.width, src.height)
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        c.drawRoundRect(RectF(0f, 0f, size.toFloat(), size.toFloat()), radius, radius, paint)
        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
        val x = (size - src.width) / 2f
        val y = (size - src.height) / 2f
        c.drawBitmap(src, x, y, paint)
        return out
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL) != null) return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "音乐播放", NotificationManager.IMPORTANCE_LOW).apply {
                description = "播放控制"
                setShowBadge(false)
            }
        )
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        handler.removeCallbacks(tick)
        session?.release()
        session = null
        super.onDestroy()
    }
}
