package com.projectionroom.app

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
 * 与 app-tv 的 ProomGlideModule 同一份配置，见那边的注释。
 *
 * 注意包名：手机端 namespace 是 com.projectionroom.phone，但 applicationId 是
 * com.projectionroom.app —— Glide 按 [Context.getPackageName]（即 applicationId）
 * 找生成类，所以这里的 package 必须是 com.projectionroom.app。
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
