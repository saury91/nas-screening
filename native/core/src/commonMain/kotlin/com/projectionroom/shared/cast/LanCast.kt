package com.projectionroom.shared.cast

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 自研局域网直投协议 **PROOM-CAST/1**（手机 ↔ 电视直连）。
 *
 * 为什么还要第三条腿：
 *  - DLNA/DMR 是标准协议，但只认地址和播放/暂停/seek —— 倍速、音量、
 *    「第几集」这些我们自己的概念传不过去，请求头也只能塞进 DIDL 的私有属性里；
 *  - NAS 中转能做全功能，可它有个前提：手机得能访问家里的 NAS。NAS 关机是常态
 *    （见 README），这条链路就整条断掉。
 *
 * 所以这里用「两端都是我们的 App」这个事实，定一套最小协议：UDP 广播发现 +
 * JSON over HTTP。指令体直接复用 [CastApi.Command]，电视端就能复用 NAS 桥接
 * 里那套 [CastReceiver] 状态总线，两端行为不会分叉。
 *
 * ```
 * 手机  --UDP "PROOM-CAST/1 DISCOVER"--> 255.255.255.255:49522
 * 电视  --单播 Offer{id,name,port,title,...}--> 手机的临时端口
 * 手机  --POST /proom/cmd  (play/pause/seek/rate/volume/stop)--> 电视:49521
 * 手机  --GET  /proom/state (1s 轮询)--> 电视:49521
 * ```
 *
 * 端口刻意避开 DMR 的 49520：两条腿同时跑，绑同一个端口会有一个起不来。
 */
object LanCast {

    const val PROTOCOL = "PROOM-CAST/1"
    const val VERSION = 1

    /** 控制口（HTTP + JSON） */
    const val PORT = 49521

    /** 发现口（UDP 广播） */
    const val DISCOVER_PORT = 49522

    const val DISCOVER = "$PROTOCOL DISCOVER"

    const val PATH_INFO = "/proom/info"
    const val PATH_STATE = "/proom/state"
    const val PATH_CMD = "/proom/cmd"

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        // 指令里 rate/volume 的默认值是 NO_CHANGE(-1)：不显式编码的话，
        // 电视收到 JSON 缺字段就用默认值，看起来一样；但显式发出去调试时更好读
        encodeDefaults = true
    }

    /**
     * 电视应答发现的报文。带一眼就能看的播放状态，面板不用再逐个请求。
     *
     * [proom] **故意不给默认值**：给默认值的话，任意一个 `{"port":49521}` 的
     * JSON 都能被认成我们的电视（解析器会拿默认值补齐），发现列表就会混进
     * 不相干的广播服务。
     */
    @Serializable
    data class Offer(
        val proom: String,
        val id: String = "",
        val name: String = "",
        val port: Int = PORT,
        val title: String = "",
        val playing: Boolean = false,
        val position: Long = 0L,
        val duration: Long = 0L,
    )

    /** GET /proom/info：确认对面真是 PROOM-CAST/1，顺手拿到名字和状态 */
    @Serializable
    data class Info(
        val ok: Boolean = true,
        val id: String = "",
        val name: String = "",
        val version: Int = VERSION,
        val state: CastApi.State? = null,
    )

    @Serializable
    data class StateResp(val ok: Boolean = true, val state: CastApi.State? = null)

    @Serializable
    data class Ack(val ok: Boolean = true, val error: String = "")

    /** 电视端拼应答用：proom 字段只在协议内部出现一次 */
    fun offer(
        id: String,
        name: String,
        title: String,
        playing: Boolean,
        position: Long,
        duration: Long,
        port: Int = PORT,
    ): Offer = Offer(PROTOCOL, id, name, port, title, playing, position, duration)

    fun offerLine(o: Offer): String = json.encodeToString(Offer.serializer(), o)

    /** 不是本协议的报文（SSDP、别的广播）一律返回 null，发现循环直接忽略 */
    fun parseOffer(text: String): Offer? = runCatching {
        json.decodeFromString(Offer.serializer(), text.trim())
    }.getOrNull()?.takeIf { it.proom == PROTOCOL && it.port in 1..65535 }

    fun isDiscover(text: String): Boolean = text.trim() == DISCOVER

    fun cmdBody(cmd: CastApi.Command): String =
        json.encodeToString(CastApi.Command.serializer(), cmd)

    fun parseCommand(body: String): CastApi.Command? = runCatching {
        json.decodeFromString(CastApi.Command.serializer(), body)
    }.getOrNull()

    fun infoBody(id: String, name: String, state: CastApi.State?): String =
        json.encodeToString(Info.serializer(), Info(ok = true, id = id, name = name, version = VERSION, state = state))

    fun parseInfo(body: String): Info? = runCatching {
        json.decodeFromString(Info.serializer(), body)
    }.getOrNull()?.takeIf { it.ok }

    fun stateBody(state: CastApi.State?): String =
        json.encodeToString(StateResp.serializer(), StateResp(ok = true, state = state))

    fun parseState(body: String): CastApi.State? = runCatching {
        json.decodeFromString(StateResp.serializer(), body)
    }.getOrNull()?.takeIf { it.ok }?.state

    fun ackBody(ok: Boolean = true, error: String = ""): String =
        json.encodeToString(Ack.serializer(), Ack(ok = ok, error = error))

    fun parseAck(body: String): Ack? = runCatching {
        json.decodeFromString(Ack.serializer(), body)
    }.getOrNull()

    /**
     * 把用户填的一行地址规整成 `http://host:port`。
     *
     * 认三种写法：`192.0.2.8`、`192.0.2.8:14953`、`proom://192.0.2.8:49521`。
     * 没写端口就补 49521 —— **不补 49520**：那个口是 DMR，走 DLNA 的兜底逻辑
     * 自己会拼 `description.xml`，两边不能混。
     */
    fun normalizeBase(raw: String): String? {
        var t = raw.trim()
        if (t.isEmpty()) return null
        t = t.removePrefix("proom-cast://").removePrefix("PROOM-CAST://")
        t = t.removePrefix("proom://").removePrefix("PROOM://")
        val https = t.startsWith("https://", true)
        if (https || t.startsWith("http://", true)) t = t.substringAfter("://")
        // 允许用户直接粘贴带路径/参数的地址，只取 authority
        t = t.substringBefore('/').substringBefore('?')
        if (t.isBlank()) return null
        val host = t.substringBefore(':').trim()
        if (host.isBlank() || host.any { it.isWhitespace() }) return null
        val port = if (t.contains(':')) {
            t.substringAfter(':').trim().toIntOrNull() ?: return null
        } else {
            PORT
        }
        if (port !in 1..65535) return null
        return (if (https) "https://" else "http://") + "$host:$port"
    }

    fun offerDevice(offer: Offer, ip: String): LanCastTarget = LanCastTarget(
        base = "http://$ip:${offer.port}",
        name = offer.name,
        id = offer.id,
        title = offer.title,
        playing = offer.playing,
        position = offer.position,
        duration = offer.duration,
    )

    /** 二维码 scheme：`proom://cast?host=…&port=…&name=…` */
    const val CODE_PREFIX = "proom://cast"

    /**
     * 扫码投屏：从二维码文本里取出电视的直连基址。
     *
     * 认三种码，都是用户可能真扫到的东西：
     *  - `proom://cast?host=…&port=…&name=…`：专用投屏码；
     *  - `proom://pair?host=…&port=8898&token=1234`：电视「显示配对二维码」那张，
     *    同一台电视、同一个局域网，没有理由逼用户再去找另一个码；
     *  - 纯地址 `192.0.2.8` / `192.0.2.8:49521`：别的工具生成的地址码。
     *
     * **配对码里的 port 不能直接当控制口**：那是 PairServer 的 8898，
     * 拿它当 base 会 POST 到配对服务上。所以非 `cast` 的码一律回落 [PORT]。
     */
    fun codeOf(raw: String): CastCode? {
        val t = raw.trim()
        if (t.isEmpty()) return null
        if (t.startsWith("proom://", true) || t.startsWith("proom-cast://", true)) {
            val rest = t.substringAfter("://", "")
            val head = rest.substringBefore('?').substringBefore('/').lowercase()
            val q = queryMap(rest.substringAfter('?', ""))
            val host = q["host"].orEmpty()
            if (host.isBlank() || host.any { it.isWhitespace() }) return null
            val isCast = head == "cast"
            val port = if (isCast) {
                q["port"]?.toIntOrNull()?.takeIf { it in 1..65535 } ?: PORT
            } else {
                PORT
            }
            return CastCode("http://$host:$port", q["name"].orEmpty())
        }
        // 纯地址码：只认 http/裸 IP，不认 https。
        // 电视的直连口是明文 HTTP，扫到一张 https 网址（宣传页、公众号）时
        // 把它当电视去连只会白等超时；没有端口又不是 IPv4 的主机名同理。
        if (t.startsWith("https://", true)) return null
        val authority = t.removePrefix("http://").removePrefix("HTTP://")
            .substringBefore('/').substringBefore('?')
        val host = authority.substringBefore(':')
        val looksIp = host.count { it == '.' } == 3 && host.all { it.isDigit() || it == '.' }
        if (!looksIp && !authority.contains(':')) return null
        val base = normalizeBase(t) ?: return null
        return CastCode(base)
    }

    private fun queryMap(q: String): Map<String, String> =
        q.split('&')
            .mapNotNull { kv ->
                val k = kv.substringBefore('=', "").trim()
                if (k.isEmpty()) null else k to kv.substringAfter('=', "").trim()
            }
            .toMap()
}

/**
 * 扫到的投屏码：`base` 直接喂给 [LanCastClient]，
 * `name` 只有专用投屏码带（配对码里没有设备名，交给 `/proom/info` 补）。
 */
data class CastCode(val base: String, val name: String = "")

/**
 * 手机端眼里的「一台走自定义协议的电视」。
 *
 * 只带 base（`http://ip:port`）而不是拆成 ip/port：请求都是 base + 固定路径，
 * 拆开以后每个调用点都要拼一次，迟早拼错。
 */
data class LanCastTarget(
    val base: String,
    val name: String = "",
    val id: String = "",
    /** 电视上正在播什么。发现报文里顺带回来的，面板直接显示，不用再请求 */
    val title: String = "",
    val playing: Boolean = false,
    val position: Long = 0L,
    val duration: Long = 0L,
)
