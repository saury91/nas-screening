package com.projectionroom.app.cast

import android.annotation.SuppressLint
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView

/**
 * 接收端播放页：只负责"把推过来的地址放出来"。
 *
 * 刻意不复用点播播放器：那套是 Vod/线路/选集驱动的，投屏只有一个裸地址，
 * 套进去要造一堆假数据。这里 100 行解决，也便于电视上做极简 UI。
 */
@SuppressLint("SetTextI18n")
class CastPlayActivity : Activity() {

    private var exo: ExoPlayer? = null
    private var titleView: TextView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val url = intent.getStringExtra(EXTRA_URL).orEmpty()
        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty().ifBlank { "投屏内容" }
        if (url.isBlank()) { finish(); return }

        val player = ExoPlayer.Builder(this).build()
        exo = player

        val playerView = PlayerView(this).apply {
            this.player = player
            useController = true          // 电视遥控器要能直接操作
            setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
        }

        titleView = TextView(this).apply {
            text = title
            setTextColor(Color.WHITE)
            textSize = 15f
            setShadowLayer(6f, 0f, 2f, Color.BLACK)
            setPadding(28, 28, 28, 28)
        }

        val close = TextView(this).apply {
            text = "✕"
            setTextColor(Color.WHITE)
            textSize = 20f
            setPadding(36, 24, 36, 24)
            setShadowLayer(6f, 0f, 2f, Color.BLACK)
            setOnClickListener { finish() }
        }

        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(playerView, FrameLayout.LayoutParams(-1, -1))
            addView(titleView, FrameLayout.LayoutParams(-2, -2).apply { gravity = Gravity.TOP or Gravity.START })
            addView(close, FrameLayout.LayoutParams(-2, -2).apply { gravity = Gravity.TOP or Gravity.END })
        }
        setContentView(root)

        // 播完/出错时回到 STOPPED，手机端查询才准
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) {
                    CastReceiver.state = "STOPPED"
                }
            }
        })

        player.setMediaItem(MediaItem.fromUri(url))
        player.prepare()
        player.playWhenReady = true
        CastReceiver.state = "PLAYING"
        CastReceiver.uri = url

        startTicker()
    }

    private fun startTicker() {
        val t = android.os.Handler(mainLooper)
        val r = object : Runnable {
            override fun run() {
                exo?.let {
                    CastReceiver.positionMs = it.currentPosition.coerceAtLeast(0)
                    CastReceiver.durationMs = it.duration.coerceAtLeast(0)
                    if (it.isPlaying) CastReceiver.state = "PLAYING"
                }
                t.postDelayed(this, 1_000)
            }
        }
        t.postDelayed(r, 1_000)
    }

    override fun onStart() {
        super.onStart()
        // 手机端发来的控制指令落到播放器上
        CastReceiver.onPause = { exo?.pause(); CastReceiver.state = "PAUSED_PLAYBACK" }
        CastReceiver.onResume = { exo?.play(); CastReceiver.state = "PLAYING" }
        CastReceiver.onSeek = { ms -> exo?.seekTo(ms) }
        CastReceiver.onStop = { finish() }
    }

    override fun onStop() {
        CastReceiver.onPause = null
        CastReceiver.onResume = null
        CastReceiver.onSeek = null
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

        fun intent(ctx: Context, url: String, title: String): Intent =
            Intent(ctx, CastPlayActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(EXTRA_URL, url)
                .putExtra(EXTRA_TITLE, title)
    }
}
