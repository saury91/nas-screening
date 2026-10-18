package com.projectionroom.tv

import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView

/**
 * 原生播放页。
 *
 * 为什么不用 WebView 里的 <video>：老电视的 WebView 基本只能软解，
 * 1080p 都吃力，4K 直接卡死。交给 ExoPlayer 走硬件解码。
 */
@UnstableApi
class PlayerActivity : AppCompatActivity() {

    private var player: ExoPlayer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)

        val url = intent.getStringExtra("url").orEmpty()
        val title = intent.getStringExtra("title").orEmpty()
        if (url.isBlank()) {
            finish()
            return
        }
        findViewById<TextView>(R.id.title).text = title
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val http = DefaultHttpDataSource.Factory()
            .setUserAgent("ProomTV/${BuildConfig.VERSION_NAME}")
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15000)
            .setReadTimeoutMs(20000)

        // DefaultMediaSourceFactory 会按扩展名自动选 HLS / 普通文件
        val source = DefaultMediaSourceFactory(http).createMediaSource(MediaItem.fromUri(url))

        player = ExoPlayer.Builder(this)
            .build()
            .apply {
                setMediaSource(source)
                playWhenReady = true
                prepare()
            }

        findViewById<PlayerView>(R.id.view).player = player
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                player?.let {
                    if (it.isPlaying) it.pause() else it.play()
                }
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                player?.let { if (it.isPlaying) it.pause() else it.play() }
                return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                player?.let { it.seekTo((it.currentPosition - 10_000L).coerceAtLeast(0L)) }
                return true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                player?.let { it.seekTo(it.currentPosition + 10_000) }
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onPause() {
        super.onPause()
        player?.pause()
    }

    override fun onDestroy() {
        player?.release()
        player = null
        super.onDestroy()
    }
}
