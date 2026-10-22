package com.projectionroom.ui.music

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 睡眠定时器：音乐和影视共用一个，谁在播谁注册到点要做的事。
 *
 * 为什么不做成播放器里的一个字段：定时器是跨页面的。用户设了 30 分钟，
 * 退到列表页还在走；音乐在后台播也在走。放在全局单例里，页面只是
 * 「当前是谁在响，就到点通知谁」。
 *
 * 不用 Handler 而用协程：进程被前台服务保活时协程照常跑，页面销毁也不用
 * 手动 removeCallbacks。
 */
object SleepTimer {

    enum class Mode {
        /** 没设 */
        Off,

        /** N 分钟后停 */
        Timer,

        /** 播完当前这首/这一集停 */
        TrackEnd,
    }

    var mode by mutableStateOf(Mode.Off)
        private set

    /** 选了多久（分钟），仅用于 UI 回显 */
    var minutes by mutableStateOf(0)
        private set

    /** 到点时间戳；TrackEnd 模式为 0 */
    var endAt by mutableStateOf(0L)
        private set

    /** 剩余毫秒，每秒刷新给界面看 */
    var remaining by mutableStateOf(0L)
        private set

    private var ticker: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** 最后 30 秒开始收音量：够长，不会在一句话中间断掉 */
    private const val FADE_MS = 30_000L

    /** 谁在响谁注册：把 0..1 的系数应用到自己的播放器音量上 */
    private var fadeOwner: Any? = null
    private var fadeSink: ((Float) -> Unit)? = null

    fun setFadeSink(owner: Any, sink: ((Float) -> Unit)?) {
        if (sink == null) {
            if (fadeOwner === owner) {
                fadeOwner = null
                fadeSink = null
            }
        } else {
            fadeOwner = owner
            fadeSink = sink
        }
    }

    private fun fadeTo(k: Float) {
        runCatching { fadeSink?.invoke(k.coerceIn(0f, 1f)) }
    }

    /** 到点执行的动作。用 owner 区分音乐和影视，避免互相注销掉对方 */
    private var holder: Any? = null
    private var onFire: (() -> Unit)? = null

    /** 谁在播谁注册；页面走的时候 [unbind] */
    fun bind(owner: Any, block: () -> Unit) {
        holder = owner
        onFire = block
    }

    fun unbind(owner: Any) {
        if (holder === owner) {
            holder = null
            onFire = null
        }
    }

    fun start(minutes: Int) {
        this.minutes = minutes.coerceAtLeast(1)
        mode = Mode.Timer
        endAt = System.currentTimeMillis() + this.minutes * 60_000L
        remaining = endAt - System.currentTimeMillis()
        // 上一次可能被取消在淡出中途，先把音量还回去
        fadeTo(1f)
        ticker?.cancel()
        ticker = scope.launch {
            while (true) {
                remaining = (endAt - System.currentTimeMillis()).coerceAtLeast(0L)
                if (remaining <= 0L) {
                    fire()
                    return@launch
                }
                // 最后 30 秒边倒计时边收：到点正好没声，人不会被一个硬切的静音吵醒
                if (remaining <= FADE_MS) fadeTo(remaining / FADE_MS.toFloat())
                delay(500)
            }
        }
    }

    fun startTrackEnd() {
        cancel()
        mode = Mode.TrackEnd
    }

    fun cancel() {
        ticker?.cancel()
        ticker = null
        mode = Mode.Off
        minutes = 0
        endAt = 0L
        remaining = 0L
        // 取消时可能正淡到一半，别让用户以为播放器坏了
        fadeTo(1f)
    }

    /**
     * 曲末/集末钩子：TrackEnd 模式下返回 true，调用方就别再自动接下一首了。
     * 定时器在这一刻自己关掉（一次性），用户下次要再设一遍。
     */
    fun onTrackEnded(): Boolean {
        if (mode != Mode.TrackEnd) return false
        fire()
        return true
    }

    private fun fire() {
        // 定时到点时音量已经收到 0（预淡出），无需再动；「听完这首」没走预淡出，
        // 反而必须把系数还原，否则下一首一开始就是静音的
        val byTimer = mode == Mode.Timer
        ticker?.cancel()
        ticker = null
        mode = Mode.Off
        minutes = 0
        endAt = 0L
        remaining = 0L
        if (!byTimer) fadeTo(1f)
        val f = onFire
        // 回调里要挂起（淡出），不能阻塞在这里。
        // 没有页面注册时默认交给音乐：最常见的场景就是听着歌睡着了，
        // 用户此时早就退出播放页了。
        scope.launch {
            when {
                f != null -> runCatching { f() }
                MusicBox.playing -> MusicBox.fadeOutAndPause()
                // 已经不在放了：把预淡出的音量还回去，免得下次播放没声音
                else -> fadeTo(1f)
            }
        }
    }

    /** 剩余时间的展示文本：剩 30 分以内显示 25:30，超过显示 1 小时 20 分 */
    fun remainingText(): String {
        if (mode == Mode.Off) return ""
        if (mode == Mode.TrackEnd) return "听完这首"
        val ms = remaining.coerceAtLeast(0L)
        val totalMin = (ms / 60_000L).toInt()
        val sec = ((ms % 60_000L) / 1000L).toInt()
        return if (totalMin >= 60) {
            "${totalMin / 60} 小时 ${totalMin % 60} 分"
        } else {
            "%d:%02d".format(totalMin, sec)
        }
    }
}
