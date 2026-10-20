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
import androidx.core.app.NotificationCompat
import com.projectionroom.ui.music.MusicBox

/**
 * 音乐的后台服务：前台通知 + 媒体会话。
 *
 * **通知用系统的 MediaStyle**，不再自己画 RemoteViews。
 *
 * 早先的版本走自定义视图，理由是"系统媒体卡布局固定，封面和循环/收藏放不进去"。
 * 代价是真机上的结果：Android 12+ 会把自定义通知压成很矮的一条，
 * 我们自己那排按钮被裁掉，系统也不认为这是媒体通知 ——
 * 通知栏看不到上一首/播放/下一首，快速设置的媒体轮播和锁屏控制也没有。
 *
 * 所以现在交给系统画：封面（大图）、歌名、歌手 + 三个按钮（上一首 / 播放暂停 / 下一首），
 * 并且绑定 [session] 的 token —— 这是通知栏媒体卡、QS 轮播、锁屏控制共用的入口。
 * 循环与收藏留在 App 内：它们不是"播放控制"，塞进这排反而把三个主按钮挤没了。
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
                val i = Intent(ctx, MusicService::class.java)
                // 8.0 之后 startService 在后台会被直接拒；startForegroundService
                // 至少给我们 5 秒把 startForeground 调起来（onStartCommand 里第一件事就是它）
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    ctx.startForegroundService(i)
                } else {
                    ctx.startService(i)
                }
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
    /** 封面还没到时的兜底大图（深底 + 金色音符），保证卡片不会空着一块 */
    private var fallbackLarge: Bitmap? = null

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
        // 点整张卡片进全屏播放页
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(EXTRA_OPEN_PLAYER, true),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        fun act(action: String, icon: Int, title: String): NotificationCompat.Action =
            NotificationCompat.Action(
                icon, title,
                PendingIntent.getService(
                    this, action.hashCode(),
                    Intent(this, MusicService::class.java).setAction(action),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )

        val prev = act(ACTION_PREV, R.drawable.ic_n_prev, "上一首")
        val toggle = act(
            ACTION_TOGGLE,
            if (playing) R.drawable.ic_n_pause else R.drawable.ic_n_play,
            if (playing) "暂停" else "播放",
        )
        val next = act(ACTION_NEXT, R.drawable.ic_n_next, "下一首")

        val style = androidx.media.app.NotificationCompat.MediaStyle()
            // 绑 token 才有 QS 媒体轮播 / 锁屏控制；不绑就只是一张普通通知
            .setMediaSession(session?.sessionToken)
            // 三个主按钮放进收起态；不加这一行系统只会显示一个「播放」
            .setShowActionsInCompactView(0, 1, 2)

        return NotificationCompat.Builder(this, CHANNEL)
            // 状态栏最左边那颗图标：音符，不用播放三角 ——
            // 三角和 App 图标（@android:drawable/ic_media_play）一模一样，分不清是应用还是歌
            // 状态栏那颗：与 App 图标同一个字形（矢量，系统按 alpha 上色）
            .setSmallIcon(R.drawable.ic_n_music)
            // 主题色：Android 5~11 会用它给图标底色上色，跟界面里的金色保持一致
            .setColor(0xFFFFCF7A.toInt())
            .setContentTitle(track?.title ?: "放映室")
            .setContentText(track?.artist.orEmpty())
            // 歌词当前句走 subText：媒体卡里它显示在歌名下方，不占按钮位置
            .setSubText(subtitle().takeIf { it.isNotBlank() && it != track?.artist })
            // 封面是异步下载的，没到之前先给兜底图 —— 否则媒体卡的大图位置是空的，
            // 看起来就像"两个不同的通知栏样式"
            .setLargeIcon(cover ?: fallbackIcon())
            .setContentIntent(open)
            .addAction(prev)
            .addAction(toggle)
            .addAction(next)
            .setStyle(style)
            .setOngoing(playing)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
    }

    /**
     * 兜底大图：深色圆角底 + 金色音符（与 App 图标同一个字形）。
     * 只在封面下载完成前用，尺寸给 256 够系统各种展示位。
     */
    private fun fallbackIcon(): Bitmap {
        fallbackLarge?.let { return it }
        val size = 256
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF1B1D24.toInt() }
        c.drawRoundRect(RectF(0f, 0f, size.toFloat(), size.toFloat()), 24f, 24f, bg)
        runCatching {
            androidx.core.content.ContextCompat.getDrawable(this, R.drawable.ic_n_music)?.apply {
                setTint(0xFFFFCF7A.toInt())
                setBounds(size / 4, size / 4, size * 3 / 4, size * 3 / 4)
                draw(c)
            }
        }
        fallbackLarge = bmp
        return bmp
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

    /**
     * 拉封面并圆角化。
     *
     * 系统媒体卡的大图只吃 Bitmap（不能给 URL），
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
