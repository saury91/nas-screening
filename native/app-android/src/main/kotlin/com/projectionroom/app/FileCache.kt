package com.projectionroom.app

import com.projectionroom.shared.net.DiskCache
import java.io.File

/**
 * 简易磁盘缓存：一个 key 一个文件，内容是原始 JSON。
 *
 * 带 TTL：超过 [ttlMs] 就当没有 —— 否则服务端数据修好了，App 还在吐几天前的旧响应。
 */
class FileCache(
    private val dir: File,
    private val ttlMs: Long = 10 * 60 * 1000L,
) : DiskCache {

    init { dir.mkdirs() }

    override fun read(key: String): String? {
        val f = File(dir, key)
        if (!f.exists()) return null
        if (System.currentTimeMillis() - f.lastModified() > ttlMs) return null
        return runCatching { f.readText() }.getOrNull()
    }

    override fun write(key: String, value: String) {
        runCatching { File(dir, key).writeText(value) }
    }
}
