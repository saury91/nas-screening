package com.projectionroom.shared.net

/**
 * 缓存优先的 Http 装饰器。
 *
 * 规则：同一个 URL 第二次进来，先吐本地缓存（页面立刻有东西），
 * 同时在后台把最新响应写回缓存，下次就是新的。
 *
 * 为什么放在 Http 层：上层（首页 / 片库 / 搜索 / 直播 / 音乐）全走 get()，
 * 一处改造就都有了，不用每个页面各写一份缓存逻辑。
 */
object HttpCache {
    /** 下拉刷新时置 true：下一次 get 跳过缓存直连网络 */
    var forceNext: Boolean = false
}

/** 只是一个标记接口：平台层实现，这里仅用于类型约束 */
interface DiskCache {
    fun read(key: String): String?
    fun write(key: String, value: String)
}
