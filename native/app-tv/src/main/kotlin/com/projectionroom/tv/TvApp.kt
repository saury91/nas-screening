package com.projectionroom.tv

import android.app.Application

/**
 * 应用入口。
 *
 * 图片加载的全局配置在 [ProomGlideModule]（Glide 的 AppGlideModule）：
 * 下载器换成 [com.projectionroom.shared.net.ImageHttp] 那把 OkHttp
 * （UA / Referer / 超时 / 重试 / 并发都在那儿），磁盘缓存用 Glide 默认的 250MB，
 * 内存缓存按设备内存等级自适应，比手写百分比更准。
 */
class TvApp : Application()
