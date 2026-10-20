package com.projectionroom.shared.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Android 侧的实现。选 OkHttp 而不是 HttpURLConnection 的原因很实在：
 * 连接池和 gzip 自动处理，几十个源并发查的时候表现稳定得多。
 */
class OkHttpHttp(
    private val timeoutSeconds: Long = 10,
) : Http {

    private val client = OkHttpClient.Builder()
        .connectTimeout(timeoutSeconds, TimeUnit.SECONDS)
        .readTimeout(timeoutSeconds, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    override suspend fun get(url: String, referer: String?): String = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", UA)
            .header("Accept", "application/json,text/plain,*/*")
            .apply { if (!referer.isNullOrBlank()) header("Referer", referer) }
            .build()

        client.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw HttpException(resp.code, "HTTP ${resp.code} · ${body.take(80)}")
            }
            body
        }
    }

    override suspend fun post(url: String, json: String): String = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", UA)
            .header("Content-Type", "application/json")
            .post(json.toByteArray().toRequestBody("application/json".toMediaType()))
            .build()
        client.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw HttpException(resp.code, "HTTP ${resp.code} · ${body.take(80)}")
            body
        }
    }

    override suspend fun resolveRedirect(url: String, referer: String?): String =
        withContext(Dispatchers.IO) {
            // 不跟随：只读 Location。跟随了就拿不到最终地址了（CDN 地址有时效，得现取现给）
            val noFollow = client.newBuilder().followRedirects(false).followSslRedirects(false).build()
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", UA)
                .apply { if (!referer.isNullOrBlank()) header("Referer", referer) }
                .build()
            noFollow.newCall(req).execute().use { resp ->
                resp.header("Location")?.takeIf { it.startsWith("http") } ?: url
            }
        }

    override suspend fun contentLength(url: String, referer: String?): Long =
        withContext(Dispatchers.IO) {
            val req = Request.Builder()
                .url(url)
                .head()
                .header("User-Agent", UA)
                .apply { if (!referer.isNullOrBlank()) header("Referer", referer) }
                .build()
            runCatching {
                client.newCall(req).execute().use { it.body?.contentLength() ?: 0L }
            }.getOrDefault(0L)
        }

    private companion object {
        // 有些采集站会挑 UA，空 UA 直接 403
        const val UA =
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/120.0 Mobile Safari/537.36"
    }
}
