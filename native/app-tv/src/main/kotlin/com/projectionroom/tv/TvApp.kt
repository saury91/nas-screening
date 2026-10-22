package com.projectionroom.tv

import android.app.Application
import com.projectionroom.ui.music.AudioFx
import com.projectionroom.ui.screen.PlaybackPrefs

/**
 * 应用入口。
 *
 * 图片加载的全局配置在 [ProomGlideModule]（Glide 的 AppGlideModule）：
 * 下载器换成 [com.projectionroom.shared.net.ImageHttp] 那把 OkHttp
 * （UA / Referer / 超时 / 重试 / 并发都在那儿），磁盘缓存用 Glide 默认的 250MB，
 * 内存缓存按设备内存等级自适应，比手写百分比更准。
 */
class TvApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // 电视上的「人声」预设比调音量有效，重启后要记得上次的选择
        AudioFx.init(this)
        // 双击/方向键快进退的步长也是用户设的，播放页第一次用之前要就位
        PlaybackPrefs.init(this)
    }
}
