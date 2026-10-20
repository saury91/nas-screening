package com.projectionroom.shared.net

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 缓存优先的 Http。
 *
 * 设计前提：**NAS 关机是常态，不是故障**。
 * 所以缓存不是「加速层」，而是「数据本体」——网络只负责把本体更新到最新版本。
 *
 *  - 有缓存：立刻返回，绝不阻塞；只有缓存超过 [FRESH_MS] 才顺手在后台拉一次；
 *  - 没缓存：走网络并写缓存；
 *  - 网络失败：**回落旧缓存**（哪怕是一周前的），实在没有才把异常抛给上层。
 *
 * [HttpCache.forceNext] 只影响「是否先用缓存」，不影响失败回退 ——
 * 下拉刷新时网络挂了，界面该显示旧内容，而不是一句「读取失败」。
 *
 * **缓存读写一律切到 [io]**：缓存文件实测有 1.2MB 的（详情/搜索整包），
 * 在调用方的线程上读就是一次同步磁盘 IO。之前调用方是 `LaunchedEffect`，
 * 于是主线程直接卡住 5 秒 → Input dispatching timed out（抓 ANR trace 定的案）。
 * 所以这里不能指望调用方自觉，必须在实现里兜住。
 */
class CachingHttp(
    private val delegate: Http,
    private val cache: DiskCache,
    private val scope: CoroutineScope,
    /** 磁盘缓存用的调度器：Android 传 Dispatchers.IO */
    private val io: CoroutineDispatcher = Dispatchers.Default,
) : Http {

    override suspend fun get(url: String, referer: String?): String {
        val force = HttpCache.forceNext
        if (force) HttpCache.forceNext = false

        val key = keyOf(url)

        if (!force) {
            val hit = withContext(io) { runCatching { cache.read(key) }.getOrNull() }
            if (!hit.isNullOrBlank()) {
                // 只有「真的旧了」才打网络：几十个源一起搜时，
                // 每次命中都刷新会把 NAS 打满，也违背「少交互」
                val age = withContext(io) { cache.ageOf(key) }
                if (age > FRESH_MS) {
                    scope.launch {
                        runCatching { delegate.get(url, referer) }
                            .onSuccess { body -> withContext(io) { cache.write(key, body) } }
                    }
                }
                return hit
            }
        }

        return try {
            val fresh = delegate.get(url, referer)
            withContext(io) { cache.write(key, fresh) }
            fresh
        } catch (e: Exception) {
            // 最后一道兜底：主动刷新时网络挂了，也吐旧数据
            val stale = withContext(io) { runCatching { cache.read(key) }.getOrNull() }
            if (!stale.isNullOrBlank()) stale else throw e
        }
    }

    override suspend fun post(url: String, json: String): String = delegate.post(url, json)

    /** URL 里的字符做 key 不安全（长度 + 特殊字符），用简单哈希 */
    private fun keyOf(url: String): String {
        var h = 1125899906842597L
        for (c in url) h = 31 * h + c.code
        return "u" + h.toString(16) + "_" + url.length
    }

    companion object {
        /**
         * 多久算「值得后台刷一次」。
         *
         * 注意这和「缓存多久作废」是两件事：这里只决定要不要顺手更新，
         * 缓存本身**永不淘汰** —— 否则 NAS 一关，App 就只剩空页面。
         */
        const val FRESH_MS: Long = 10 * 60 * 1000L
    }
}
