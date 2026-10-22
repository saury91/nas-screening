package com.projectionroom.app.cast

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.projectionroom.app.data.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * DLNA 接收端的宿主：前台服务 + [DmrServer]。
 *
 * 为什么必须是服务而不是挂在 Activity 上：电视端一按 Home 回到桌面，Activity 就
 * 停了，手机再搜这台电视就搜不到 —— 而「投屏」恰恰发生在用户已经离开 App 的时候。
 */
class CastService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var server: DmrServer? = null
    private var nas: NasCastBridge? = null
    private var lan: LanCastServer? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel(this)
        // 前台服务必须先亮通知，否则 Android 8+ 会直接抛异常把进程带走
        runCatching { startForeground(FG_ID, foregroundNotification(this)) }

        server = DmrServer(this) { url, title, headers ->
            // 真正播放交给播放页；这里只负责把地址递过去。
            // launch() 内部判断自己是否在前台，后台时改走全屏通知。
            CastPlayActivity.launch(this, url, title, headers)
        }.also { it.start(scope) }

        // 第二条腿：NAS 名录。没配地址时 start() 自己返回，纯局域网用户无感
        nas = NasCastBridge(this) { url, title, headers ->
            CastPlayActivity.launch(this, url, title, headers)
        }.also { it.start(scope) }

        // 第三条腿：自定义协议（PROOM-CAST/1）。局域网直连，不看 NAS 在不在，
        // 还能把倍速/音量/第几集/请求头原样带过来
        lan = LanCastServer(this) { url, title, headers, line, ep, episode ->
            CastPlayActivity.launch(this, url, title, headers, line, ep, episode)
        }.also { it.start(scope) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        server?.stop()
        server = null
        nas?.stop()
        nas = null
        lan?.stop()
        lan = null
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL = "proom-cast"
        private const val FG_ID = 4201
        private const val CAST_ID = 4202

        /** App 启动时调一次；重复调用无副作用（服务自己会复用实例） */
        fun start(ctx: Context) {
            runCatching {
                ContextCompat.startForegroundService(ctx, Intent(ctx, CastService::class.java))
            }
        }

        /**
         * 进程在后台、系统不让直接 startActivity 时的兜底：发一条带 fullScreenIntent
         * 的通知，由 SystemUI 代劳拉起播放页。
         */
        fun promptCast(ctx: Context, intent: Intent, title: String) {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            ensureChannel(ctx)
            val pi = PendingIntent.getActivity(
                ctx,
                CAST_ID,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val n = NotificationCompat.Builder(ctx, CHANNEL)
                .setSmallIcon(R.drawable.ic_launcher_mono)
                .setContentTitle("正在投屏")
                .setContentText(title)
                .setContentIntent(pi)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .setAutoCancel(true)
                .setFullScreenIntent(pi, true)
                .build()
            runCatching { nm.notify(CAST_ID, n) }
        }

        private fun ensureChannel(ctx: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(CHANNEL) != null) return
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "投屏接收", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "手机把片子投到这台电视时提醒"
                    setShowBadge(false)
                }
            )
        }

        private fun foregroundNotification(ctx: Context): Notification =
            NotificationCompat.Builder(ctx, CHANNEL)
                .setSmallIcon(R.drawable.ic_launcher_mono)
                .setContentTitle("放映室")
                .setContentText("投屏接收已就绪")
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .build()
    }
}
