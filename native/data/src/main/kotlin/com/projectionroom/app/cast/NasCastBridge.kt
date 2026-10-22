package com.projectionroom.app.cast

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.projectionroom.app.NasStore
import com.projectionroom.shared.cast.CastApi
import com.projectionroom.shared.net.OkHttpHttp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 电视端接入 NAS 的投屏名录。
 *
 * 和 [DmrServer] 是两条独立的腿，面向两种网络：
 *  - DMR：手机和电视在同一 Wi-Fi，走局域网组播，NAS 挂了也能投
 *  - NAS：手机在 4G/公司网络，只要还能访问到家里的 NAS 就能投
 *
 * 数据流（服务端 `server/services/cast.py`）：
 * ```
 * register(15s 心跳)  -> 进名录，手机才看得见这台电视
 * poll(wait=20)       -> 长轮询，手机一发指令立刻拿到
 * reportState(2s)     -> 播到第几秒，手机遥控页显示真实进度
 * ```
 *
 * 三条循环必须分开跑：长轮询一次会挂 20 秒，和心跳塞进同一个协程的话
 * 设备会在名录里「假离线」（服务端 TTL 45 秒）。
 */
class NasCastBridge(
    private val ctx: Context,
    /** 收到 play 指令后怎么起播。宿主注入，服务里没有 Activity 上下文 */
    private val play: (String, String, Map<String, String>) -> Unit,
) {

    private var job: Job? = null
    private val main = Handler(Looper.getMainLooper())

    /** 没配 NAS 地址就什么都不做：纯局域网用户不该看到任何多余请求 */
    fun start(scope: CoroutineScope) {
        if (job != null) return
        val base = NasStore.url(ctx)?.takeIf { it.isNotBlank() } ?: return
        job = scope.launch {
            val api = CastApi(OkHttpHttp(timeoutSeconds = 20), base)
            val id = castDeviceId(ctx)
            launch { heartbeat(api, id) }
            launch { pump(api, id) }
            launch { reportLoop(api, id) }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    private suspend fun heartbeat(api: CastApi, id: String) {
        while (currentCoroutineContext().isActive) {
            api.register(id, NAME, "tv")
            delay(15_000)
        }
    }

    /** 播放在跑时两秒一次；停着就少报点，空闲的电视不该一直发请求 */
    private suspend fun reportLoop(api: CastApi, id: String) {
        while (currentCoroutineContext().isActive) {
            val active = CastReceiver.state != "STOPPED"
            if (active) {
                api.reportState(
                    id = id,
                    url = CastReceiver.uri,
                    title = CastReceiver.title,
                    positionMs = CastReceiver.positionMs,
                    durationMs = CastReceiver.durationMs,
                    playing = CastReceiver.state == "PLAYING",
                    rate = CastReceiver.rate,
                    volume = CastReceiver.volume,
                    line = CastReceiver.line,
                    ep = CastReceiver.ep,
                    episode = CastReceiver.episode,
                )
            }
            delay(if (active) 2_000 else 10_000)
        }
    }

    private suspend fun pump(api: CastApi, id: String) {
        while (currentCoroutineContext().isActive) {
            val cmds = api.poll(id, waitSec = 20)
            cmds.forEach(::handle)
            // 服务端在没指令时也会因为 wait 上限返回空，正常不用额外等；
            // 但 NAS 版本旧、wait 被忽略时会立即返回，这里兜一下避免打满 CPU
            if (cmds.isEmpty()) delay(1_000)
        }
    }

    private fun handle(cmd: CastApi.Command) {
        when (cmd.kind) {
            "play" -> {
                // 先落状态再起播：播放页 onCreate 会读这些值显示标题和集数
                CastReceiver.title = cmd.title
                CastReceiver.line = cmd.line
                CastReceiver.ep = cmd.ep
                CastReceiver.episode = cmd.episode
                play(cmd.url, cmd.title, cmd.headers)
            }
            // 其余都是「改状态」，操作的是 ExoPlayer，必须回主线程
            "pause" -> main.post { CastReceiver.onPause?.invoke() }
            "resume" -> main.post { CastReceiver.onResume?.invoke() }
            "seek" -> main.post { CastReceiver.onSeek?.invoke(cmd.position) }
            "stop" -> main.post { CastReceiver.onStop?.invoke() }
            "rate" -> main.post { CastReceiver.onRate?.invoke(cmd.rate) }
            "volume" -> main.post { CastReceiver.onVolume?.invoke(cmd.volume) }
        }
    }

    companion object {
        /** 手机投屏面板上显示的名字 */
        const val NAME = "放映室 TV"
    }
}
