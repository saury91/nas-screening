package com.projectionroom.app

import android.app.Application
import com.projectionroom.ui.music.AudioFx
import com.projectionroom.ui.screen.PlaybackPrefs

/**
 * 应用入口。图片加载配置见 [ProomGlideModule]（与 app-tv 同一套）。
 */
class PhoneApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // 音效设置要在第一次打开弹层之前就读回来，否则会先显示「默认」再跳变，
        // 用户还以为设置丢了（真正的 Equalizer 实例仍要等播放器建好才绑得上）
        AudioFx.init(this)
        PlaybackPrefs.init(this)
    }
}
