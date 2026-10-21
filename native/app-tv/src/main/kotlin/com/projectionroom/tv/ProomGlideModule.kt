package com.projectionroom.tv

import android.content.Context
import com.bumptech.glide.Glide
import com.bumptech.glide.Registry
import com.bumptech.glide.GlideBuilder
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.request.RequestOptions
import com.bumptech.glide.annotation.GlideModule
import com.bumptech.glide.integration.okhttp3.OkHttpUrlLoader
import com.bumptech.glide.load.model.GlideUrl
import com.bumptech.glide.module.AppGlideModule
import com.projectionroom.shared.net.ImageHttp
import java.io.InputStream

/**
 * 把 Glide 的下载器换成项目已有的 OkHttp client。
 *
 * Glide 默认用 HttpUrlConnection：连接/读取超时写死 2.5 秒、UA 是 Dalvik、
 * 没有 Referer —— 采集站的图床不是被拒就是被超时掐掉。换成 ImageHttp 之后，
 * UA/Referer、5 秒/10 秒超时、并发上限、失败重试全部沿用同一套配置。
 *
 * 这个类必须放在 applicationId 同名包里（com.projectionroom.tv）：Glide 运行期
 * 按 `context.packageName + ".GeneratedAppGlideModuleImpl"` 反射查找生成类。
 */
@GlideModule
class ProomGlideModule : AppGlideModule() {
    override fun registerComponents(context: Context, glide: Glide, registry: Registry) {
        registry.replace(
            GlideUrl::class.java,
            InputStream::class.java,
            OkHttpUrlLoader.Factory(ImageHttp.client),
        )
    }


    override fun applyOptions(context: Context, builder: GlideBuilder) {
        builder.setDefaultRequestOptions(
            RequestOptions()
                // ALL：远程图也把原始文件落盘。采集站图床大多把 Cache-Control 写成
                // no-cache/no-store，交给默认的 AUTOMATIC 就等于每次进页面重下一遍。
                .diskCacheStrategy(DiskCacheStrategy.ALL),
        )
    }

    override fun isManifestParsingEnabled(): Boolean = false
}
