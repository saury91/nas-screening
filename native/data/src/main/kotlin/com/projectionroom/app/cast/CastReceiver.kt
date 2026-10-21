package com.projectionroom.app.cast

/**
 * 接收端与播放页之间的状态总线。
 *
 * DLNA 服务是长活的（Activity 销毁了也要应答），播放页只在"有内容要播"时存在，
 * 所以状态放在这个单例里：服务读写状态，播放页注册回调接收指令。
 */
object CastReceiver {

    @Volatile var state: String = "STOPPED"      // STOPPED / PLAYING / PAUSED_PLAYBACK
    @Volatile var uri: String = ""
    @Volatile var title: String = ""
    @Volatile var positionMs: Long = 0L
    @Volatile var durationMs: Long = 0L

    /** 当前在第几条线路的第几集。遥控页靠它高亮选集、判断上一集/下一集还有没有 */
    @Volatile var line: Int = 0
    @Volatile var ep: Int = 0
    @Volatile var episode: String = ""

    /** 倍速与音量。电视播到一半被手机改过之后，要能让遥控页拉回来显示真实值 */
    @Volatile var rate: Float = 1f
    @Volatile var volume: Float = 1f

    /**
     * 拉流时要补的请求头（User-Agent / Referer）。
     *
     * 采集站的 m3u8 大多校验这两个头，手机端播放器自己带了才播得动。投屏推的是
     * 裸地址，头得靠 DIDL 里的 `x-proom-*` 属性捎过来，否则电视上就是 403 黑屏。
     */
    @Volatile var uriHeaders: Map<String, String> = emptyMap()

    var onPlay: ((String, String, Map<String, String>) -> Unit)? = null
    var onPause: (() -> Unit)? = null
    var onResume: (() -> Unit)? = null
    var onStop: (() -> Unit)? = null
    var onSeek: ((Long) -> Unit)? = null

    /** 倍速 / 音量：不换片，只调参数 */
    var onRate: ((Float) -> Unit)? = null
    var onVolume: ((Float) -> Unit)? = null

    fun reset() {
        state = "STOPPED"
        uri = ""
        title = ""
        positionMs = 0
        durationMs = 0
        line = 0
        ep = 0
        episode = ""
        rate = 1f
        volume = 1f
        uriHeaders = emptyMap()
    }
}
