package com.projectionroom.shared.net

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 缓存优先的 Http：
 *  - 有缓存 → 立刻返回缓存，后台刷新；
 *  - 没缓存 → 走网络并写缓存；
 *  - [HttpCache.forceNext] 为 true → 跳过缓存（下拉刷新、重试）。
 */
class CachingHttp(
    private val delegate: Http,
    private val cache: DiskCache,
    private val scope: CoroutineScope,
) : Http {

    override suspend fun get(url: String, referer: String?): String {
        val force = HttpCache.forceNext
        if (force) HttpCache.forceNext = false

        val key = keyOf(url)
        if (!force) {
            val hit = runCatching { cache.read(key) }.getOrNull()
            if (!hit.isNullOrBlank()) {
                // 后台更新，不阻塞这次调用
                scope.launch {
                    runCatching { delegate.get(url, referer) }
                        .onSuccess { cache.write(key, it) }
                }
                return hit
            }
        }

        val fresh = delegate.get(url, referer)
        runCatching { cache.write(key, fresh) }
        return fresh
    }

    override suspend fun post(url: String, json: String): String = delegate.post(url, json)

    /** URL 里的字符做 key 不安全（长度 + 特殊字符），用简单哈希 */
    private fun keyOf(url: String): String {
        var h = 1125899906842597L
        for (c in url) h = 31 * h + c.code
        return "u" + h.toString(16) + "_" + url.length
    }
}
