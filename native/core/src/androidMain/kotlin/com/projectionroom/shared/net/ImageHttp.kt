package com.projectionroom.shared.net

import okhttp3.Dispatcher
import okhttp3.Dns
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import java.io.IOException
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.util.concurrent.TimeUnit

/**
 * 海报专用的 OkHttp（通过 OkHttpUrlLoader 交给 Glide 用）。
 *
 * 为什么不复用 [OkHttpHttp] 那个 client：
 *
 *  1. **并发**：一屏几十张海报、滚动时几百张，走默认 Dispatcher 会瞬间打满
 *     连接池，把采集接口的请求也挤在后面。这里单独限并发，图片自己排队。
 *  2. **防盗链**：采集站的海报大多挂在 CDN 上，空 UA / 无 Referer 直接 403，
 *     表现就是「封面永远不出来，只剩首字兜底」。
 *  3. **超时与重试**：读超时 10 秒够大图传完；死链 5 秒就放弃，别占着并发槽。
 *     图床抽风（DNS/TLS/5xx）由 [RetryInterceptor] 兜住。
 *  4. **IPv4 优先**：见 [Ipv4FirstDns]。
 */
object ImageHttp {

    private const val UA =
        "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/120.0 Mobile Safari/537.36"

    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            // 采集站的死链/半死链很多（连不上、TLS 握手挂住）：超时给短一点，
            // 让它们尽快让出并发槽位，别拖着一屏图一起等满十几秒
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .dns(Ipv4FirstDns)
            .dispatcher(
                // 海报基本都在同一两台图床域名下，单域名并发才是真正的瓶颈：
                // 8 条时一屏 20 张要排队三轮，一张 1 秒就是三秒起步
                Dispatcher().apply {
                    maxRequests = 32
                    maxRequestsPerHost = 12
                }
            )
            .addInterceptor(RetryInterceptor())
            .addInterceptor { chain ->
                val req = chain.request()
                val u = req.url
                chain.proceed(
                    req.newBuilder()
                        .header("User-Agent", UA)
                        // 没给 Referer 就补同源域名：白名单站点认自己域名，
                        // 不校验的站点给什么都行，空着反而更容易被拒
                        .apply {
                            if (req.header("Referer") == null) {
                                header("Referer", "${u.scheme}://${u.host}/")
                            }
                        }
                        .build()
                )
            }
            .build()
    }

    /**
     * IPv4 记录排到前面。
     *
     * 采集站图床大量挂在 Cloudflare 后面，DNS 会同时给出 A 和 AAAA。但电视盒子、
     * 老路由器的 IPv6 经常是「有地址、没出口」，于是连接卡在 IPv6 黑洞里直到超时，
     * 日志就是 `Failed to connect to [2606:4700:...]:443` —— 表现是部分封面永远不出来。
     * 把 A 记录排前面，能连就直接连上，连不上再回落到 IPv6。
     */
    private object Ipv4FirstDns : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            val resolved = Dns.SYSTEM.lookup(hostname)
            if (resolved.size < 2) return resolved
            val v4 = resolved.filterIsInstance<Inet4Address>()
            if (v4.isEmpty()) return resolved
            return v4 + resolved.filterIsInstance<Inet6Address>()
        }
    }

    /**
     * 图片请求重试。
     *
     * 图床失败绝大多数是瞬时的（DNS 抖动、TLS 握手被打断、502/503、被限流），
     * Glide 默认不重试 —— 错一次这张封面就永远停在首字兜底上，
     * 用户看到的就是「列表早就出来了，图半天不出来」。
     *
     * 放在网络层而不是 Compose 层：一次改动对所有图片生效（分类、首页、详情、封面）。
     * 不要在组合里为「第几次重试」写状态 —— 那样会把在途请求反复取消重建。
     *
     * 只重试 IO 异常和 5xx/429：404 是死链，重试几次还是 404。
     */
    private class RetryInterceptor(private val maxTries: Int = 3) : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            var attempt = 0
            while (true) {
                // 用户已经划走、Glide 取消了这个请求：别把线程耗在没人要的图上
                if (chain.call().isCanceled()) throw IOException("canceled")
                attempt++
                val resp = try {
                    chain.proceed(chain.request())
                } catch (e: IOException) {
                    if (attempt >= maxTries) throw e
                    Thread.sleep(250L * attempt)
                    continue
                }
                if (!(resp.code >= 500 || resp.code == 429) || attempt >= maxTries) return resp
                // 5xx 的 body 没用了，先关掉再重试，否则连接一直占着
                resp.close()
                Thread.sleep(250L * attempt)
            }
        }
    }
}
