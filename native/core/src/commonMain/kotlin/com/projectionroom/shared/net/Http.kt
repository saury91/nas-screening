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

    /**
     * 只取跳转目标，不跟随。
     *
     * 上游的播放地址是先 302 到 CDN：把最终的 CDN 地址交给播放器，
     * 比让播放器自己跟一遍跳转稳（ExoPlayer 跟 302 时偶发丢掉请求头）。
     */
    suspend fun resolveRedirect(url: String, referer: String? = null): String = url

    /** HEAD 取内容长度；拿不到返回 0。用来判断是不是「试听片段」 */
    suspend fun contentLength(url: String, referer: String? = null): Long = 0L
}

class HttpException(val status: Int, message: String) : Exception(message)
