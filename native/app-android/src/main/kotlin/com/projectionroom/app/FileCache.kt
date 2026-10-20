package com.projectionroom.app

import com.projectionroom.shared.net.DiskCache
import java.io.File

/**
 * 简易磁盘缓存：一个 key 一个文件，内容是原始响应。
 *
 * **不设 TTL**：缓存就是 App 的本地数据本体。
 * NAS 关着、网络断了，界面照样出内容 —— 这是「NAS 只是内容更新源」的前提。
 * 「要不要刷新」交给 [com.projectionroom.shared.net.CachingHttp] 按 [ageOf] 判断。
 */
class FileCache(private val dir: File) : DiskCache {

    // 不在构造时 mkdirs：CachingHttp 是在 composition 里 new 的，
    // 那样等于在主线程上做一次目录检查。放到读写里（调用方已切 IO 线程）。
    private fun ensureDir() {
        if (!dir.exists()) dir.mkdirs()
    }

    override fun read(key: String): String? {
        ensureDir()
        val f = File(dir, key)
        if (!f.exists()) return null
        return runCatching { f.readText() }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    override fun write(key: String, value: String) {
        ensureDir()
        runCatching {
            // 先写临时文件再改名：进程被杀时不会留下半截 JSON 把页面搞崩
            val tmp = File(dir, "$key.tmp")
            tmp.writeText(value)
            tmp.renameTo(File(dir, key))
        }
    }

    override fun ageOf(key: String): Long {
        val f = File(dir, key)
        if (!f.exists()) return Long.MAX_VALUE
        return (System.currentTimeMillis() - f.lastModified()).coerceAtLeast(0L)
    }
}
