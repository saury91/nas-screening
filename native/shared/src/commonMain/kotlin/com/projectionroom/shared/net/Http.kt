package com.projectionroom.shared.net

/**
 * 一次 GET。放在 shared 里是为了让聚合逻辑不绑死平台：
 * Android 用 OkHttp，iOS 用 NSURLSession，单测用假的。
 */
interface Http {
    suspend fun get(url: String, referer: String? = null): String

    /** 默认不支持写操作：单测用的假实现不用为此改一遍 */
    suspend fun post(url: String, json: String): String =
        throw UnsupportedOperationException("这个 Http 实现不支持 POST")
}

class HttpException(val status: Int, message: String) : Exception(message)
