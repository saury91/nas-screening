package com.projectionroom.shared.cast

import com.projectionroom.shared.catalog.encodeQuery
import com.projectionroom.shared.net.Http
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * NAS 桥接投屏的客户端。
 *
 * 走 NAS 的意义不是「多一个通道」，而是**跨网**：手机在 4G/公司网络下，
 * 只要还能访问到家里的 NAS，就能把片子投到客厅电视上。这条链路在 WebView
 * 那一代就有（`server/services/cast.py`），原生重构时断了，这里把它接回来。
 *
 * 数据流：
 * ```
 * 电视  --register(15s)--> NAS           名录，供手机挑选
 * 电视  --poll(wait=8)--> NAS            长轮询，有指令立刻回来
 * 手机  --send----------> NAS            play/pause/resume/seek/stop
 * 电视  --state---------> NAS            播到第几秒，手机面板直接显示
 * ```
 *
 * 所有方法都吞掉异常返回「空」，投屏失败不该让界面崩或者弹一堆报错：
 * 调用方会根据空结果决定降级到局域网 DLNA。
 */
class CastApi(
    private val http: Http,
    baseUrl: String,
) {

    private val base = baseUrl.trimEnd('/')

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    /** 一台在线的接收端。[state] 是它当前播到哪，没在播就是 null */
    @Serializable
    data class Target(
        val id: String = "",
        val name: String = "",
        val kind: String = "tv",
        /** 谁在控制它。不是自己就说明被另一台手机接管了 */
        val owner: String = "",
        val state: State? = null,
    ) {
        val isTv: Boolean get() = kind == "tv"
    }

    @Serializable
    data class State(
        val url: String = "",
        val title: String = "",
        val position: Long = 0L,
        val duration: Long = 0L,
        val playing: Boolean = false,
        /** 电视当前的倍速与音量：遥控页要把滑块摆到真实位置上，不能凭本地记忆 */
        val rate: Float = 1f,
        val volume: Float = 1f,
        /** 电视正在播第几条线路的第几集，遥控页据此高亮选集 */
        val line: Int = 0,
        val ep: Int = 0,
        val episode: String = "",
    )

    /**
     * 下发给电视的指令。
     *
     * [vod]/[line]/[ep] 一起带过去，电视端才能在播放页里换集换线路 ——
     * 只给一个裸地址的话，「下一集」就得用户回手机上再投一次。
     */
    @Serializable
    data class Command(
        val kind: String = "play",
        val url: String = "",
        val title: String = "",
        val position: Long = 0L,
        val headers: Map<String, String> = emptyMap(),
        val vod: String = "",
        val line: Int = 0,
        val ep: Int = 0,
        /** 本集名字，电视上「第 3 集 · 第7话」要显示出来 */
        val episode: String = "",
        /**
         * 倍速。[NO_CHANGE] 表示这条指令不动倍速 ——
         * 用 0 不行：0 倍速在语义上是「停止」，以后真要做暂停就分不清了。
         */
        val rate: Float = NO_CHANGE,
        /** 音量 0~1，[NO_CHANGE] 表示不动 */
        val volume: Float = NO_CHANGE,
    )

    @Serializable
    private data class RegisterReq(val id: String, val name: String, val kind: String)

    @Serializable
    private data class SendReq(
        val target: String,
        val from: String,
        val kind: String,
        val url: String,
        val title: String,
        val position: Long,
        val headers: Map<String, String>,
        val vod: String,
        val line: Int,
        val ep: Int,
        val episode: String,
        val rate: Float,
        val volume: Float,
    )

    @Serializable
    private data class StateReq(
        val id: String,
        val url: String,
        val title: String,
        val position: Long,
        val duration: Long,
        val playing: Boolean,
        val rate: Float,
        val volume: Float,
        val line: Int,
        val ep: Int,
        val episode: String,
    )

    @Serializable
    private data class TargetsResp(val ok: Boolean = false, val items: List<Target> = emptyList())

    @Serializable
    private data class PollResp(val ok: Boolean = false, val commands: List<Command> = emptyList())

    @Serializable
    private data class OkResp(val ok: Boolean = false)

    private suspend fun post(path: String, body: String): OkResp = runCatching {
        json.decodeFromString<OkResp>(http.post("$base$path", body))
    }.getOrDefault(OkResp())

    /** 电视端：报到兼续命 */
    suspend fun register(id: String, name: String, kind: String = "tv"): Boolean = post(
        "/api/cast/register",
        json.encodeToString(RegisterReq.serializer(), RegisterReq(id, name, kind)),
    ).ok

    /** 手机端：有哪些电视在线，各自在播什么 */
    suspend fun targets(selfId: String): List<Target> = runCatching {
        json.decodeFromString<TargetsResp>(
            http.get("$base/api/cast/targets?id=${selfId.encodeQuery()}"),
        ).items
    }.getOrDefault(emptyList())

    /**
     * 电视端：取指令。
     *
     * [waitSec] > 0 时是长轮询 —— NAS 上没有指令就把连接挂住，
     * 一旦手机发来立刻返回。投屏延迟从「最多 3 秒」降到毫秒级。
     */
    suspend fun poll(id: String, waitSec: Int = 8): List<Command> = runCatching {
        json.decodeFromString<PollResp>(
            http.get("$base/api/cast/poll?id=${id.encodeQuery()}&wait=$waitSec"),
        ).commands
    }.getOrDefault(emptyList())

    /** 手机端：投过去，或者遥控 */
    suspend fun send(target: String, from: String, cmd: Command): Boolean = post(
        "/api/cast/send",
        json.encodeToString(
            SendReq.serializer(),
            SendReq(
                target = target,
                from = from,
                kind = cmd.kind,
                url = cmd.url,
                title = cmd.title,
                position = cmd.position,
                headers = cmd.headers,
                vod = cmd.vod,
                line = cmd.line,
                ep = cmd.ep,
                episode = cmd.episode,
                rate = cmd.rate,
                volume = cmd.volume,
            ),
        ),
    ).ok

    /** 电视端：播到第几秒。顺带续心跳，播放中不必再单独 register */
    suspend fun reportState(
        id: String,
        url: String,
        title: String,
        positionMs: Long,
        durationMs: Long,
        playing: Boolean,
        rate: Float = 1f,
        volume: Float = 1f,
        line: Int = 0,
        ep: Int = 0,
        episode: String = "",
    ): Boolean = post(
        "/api/cast/state",
        json.encodeToString(
            StateReq.serializer(),
            StateReq(
                id, url, title,
                positionMs.coerceAtLeast(0), durationMs.coerceAtLeast(0), playing,
                rate, volume, line, ep, episode,
            ),
        ),
    ).ok

    companion object {
        /**
         * 指令里「这一项不用改」的哨兵。
         *
         * 用一个明确的小负数，而不是 0：0 倍速/0 音量都是合法取值，
         * 用它当哨兵以后想真做「静音」「停止」就分不清了。
         */
        const val NO_CHANGE: Float = -1f
    }
}
