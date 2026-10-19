package com.projectionroom.app

import com.projectionroom.shared.net.DiskCache
import java.io.File

/** 简易磁盘缓存：一个 key 一个文件，内容是原始 JSON */
class FileCache(private val dir: File) : DiskCache {

    init { dir.mkdirs() }

    override fun read(key: String): String? {
        val f = File(dir, key)
        return if (f.exists()) runCatching { f.readText() }.getOrNull() else null
    }

    override fun write(key: String, value: String) {
        runCatching { File(dir, key).writeText(value) }
    }
}
