package com.projectionroom.shared.net

/**
 * 缓存优先的 Http 装饰器。
 *
 * 规则：
 *  1. 同一个 URL 第二次进来，先吐本地缓存 —— **永远可用**，不看年龄；
 *  2. 缓存超过 [CachingHttp.FRESH_MS] 才在后台顺便刷一次；
 *  3. 没缓存、或者用户主动刷新，才真的等网络；网络失败时回落旧缓存。
 *
 * 第 3 条是这次的修正重点：以前缓存 10 分钟就作废，NAS 一关，首页直接空白。
 * 现在的语义是「NAS 只是内容更新源，不是运行时依赖」—— 拿不到新的就一直用旧的。
 */
object HttpCache {
    /** 下拉刷新时置 true：下一次 get 跳过缓存直连网络（失败仍回落旧缓存） */
    var forceNext: Boolean = false
}

/** 平台层的落盘实现：一个 key 一个文件即可 */
interface DiskCache {
    fun read(key: String): String?
    fun write(key: String, value: String)

    /** 这份缓存多久没更新了（毫秒）；拿不到就当「很旧」，会触发后台刷新 */
    fun ageOf(key: String): Long = Long.MAX_VALUE
}
