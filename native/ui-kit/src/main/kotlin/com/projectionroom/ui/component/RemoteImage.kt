package com.projectionroom.ui.component

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import com.bumptech.glide.integration.compose.ExperimentalGlideComposeApi
import com.bumptech.glide.integration.compose.GlideImage
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.Target
import android.graphics.drawable.Drawable

/**
 * 连不上的封面图床，加载失败时现学一份。
 *
 * 采集站的图床会整站连不上（实测 disabled.invalid、disabled.invalid 都被 RST），
 * 而同一部片在别的源上封面域名不同。记住坏域名，合片时避开，就不会整页空白。
 * NAS 下发的 `badPicHosts` 是另一条来源（服务端主动探测过的），两边取并集。
 *
 * 阈值为 2：单张图偶发失败（超时、解码失败）不至于把整个图床拉黑。
 */
object BadPicHosts {

    private const val THRESHOLD = 2
    private val fails = java.util.Collections.synchronizedMap(mutableMapOf<String, Int>())
    private val bad = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    /** 加载失败一次记一笔，同域名累计到阈值才判定整站不可用 */
    fun markFailed(url: String) {
        val h = hostOf(url)
        if (h.isBlank()) return
        val n = (fails[h] ?: 0) + 1
        fails[h] = n
        if (n >= THRESHOLD) bad.add(h)
    }

    /** 直接判死（比如服务端已确认该图床不可达） */
    fun markBad(url: String) {
        val h = hostOf(url)
        if (h.isNotBlank()) bad.add(h)
    }

    fun all(): Set<String> = bad.toSet()

    private fun hostOf(url: String): String {
        val i = url.indexOf("://")
        if (i < 0) return ""
        val rest = url.substring(i + 3)
        val j = rest.indexOf('/')
        return (if (j < 0) rest else rest.substring(0, j)).trim().lowercase()
    }
}

/**
 * 全站唯一的远程图片入口：Glide（官方 Compose 封装 + 官方 OkHttp 集成）。
 *
 * 统一在这里是为了保证每个调用点都用同一套东西：
 *  - 下载走 [com.projectionroom.shared.net.ImageHttp] 那把 OkHttp（UA / Referer /
 *    超时 / 重试 / 并发），见各 app 模块的 ProomGlideModule；
 *  - 磁盘缓存策略在 ProomGlideModule 里全局设为 ALL（远程图也存原图），
 *    图床把 Cache-Control 写成 no-cache 也照样落盘；
 *  - 解码尺寸交给 Glide 按控件实际尺寸自动算，不手写 override。
 *
 * 不要再传 RequestBuilder 进来 —— glide-compose 只接受「数据模型」，传 builder
 * 会直接报 NoModelLoaderAvailableException。
 */
@OptIn(ExperimentalGlideComposeApi::class)
@Composable
fun RemoteImage(
    model: Any?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    alignment: Alignment = Alignment.Center,
    alpha: Float = 1f,
    colorFilter: ColorFilter? = null,
) {
    val src = model?.toString().orEmpty()
    if (src.isBlank()) return
    // 失败时记下域名：同一图床反复失败后，合片时会避开它（见 BadPicHosts）
    val failListener = remember(src) {
        object : RequestListener<Drawable> {
            override fun onLoadFailed(
                e: GlideException?,
                model: Any?,
                target: Target<Drawable>,
                isFirstResource: Boolean,
            ): Boolean {
                BadPicHosts.markFailed(src)
                return false
            }

            override fun onResourceReady(
                resource: Drawable,
                model: Any,
                target: Target<Drawable>,
                dataSource: DataSource,
                isFirstResource: Boolean,
            ): Boolean = false
        }
    }
    GlideImage(
        model = src,
        contentDescription = contentDescription,
        modifier = modifier,
        contentScale = contentScale,
        alignment = alignment,
        alpha = alpha,
        colorFilter = colorFilter,
        requestBuilderTransform = { it.addListener(failListener) },
    )
}
