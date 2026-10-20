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

    var onPlay: ((String, String) -> Unit)? = null
    var onPause: (() -> Unit)? = null
    var onResume: (() -> Unit)? = null
    var onStop: (() -> Unit)? = null
    var onSeek: ((Long) -> Unit)? = null

    fun reset() {
        state = "STOPPED"
        uri = ""
        title = ""
        positionMs = 0
        durationMs = 0
    }
}
