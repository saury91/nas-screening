package com.projectionroom.ui

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import com.projectionroom.shared.model.Vod
import com.projectionroom.ui.screen.TvHome
import com.projectionroom.ui.theme.ProomTheme
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.EncodedImageFormat
import java.io.File

/**
 * 桌面预览入口（不参与发版）。
 *
 *   ./gradlew :ui:run                      开窗口看真机之外的快速迭代
 *   RENDER=/tmp/shot.png ./gradlew :ui:run  离屏渲染 PNG
 */
fun main() {
    val out = System.getenv("RENDER")
    if (!out.isNullOrBlank()) {
        renderToPng(out.replace(".png", "-phone.png"), isTv = false)
        renderToPng(out.replace(".png", "-tv.png"), isTv = true)
        return
    }
    application {
        Window(onCloseRequest = ::exitApplication, title = "放映室 · 设计预览") {
            ProomTheme(isTv = true) { TvHome(continueWatching = SAMPLE, latest = SAMPLE) }
        }
    }
}

private val SAMPLE: List<Vod> = listOf(
    Vod(
        "繁花", "2023", "https://p.bfvp26.com/upload/vod/20261019-1/8c61489e4c41775c723f78e9dfc13f6d.webp",
        "国产剧", area = "大陆", score = "8.7",
        desc = "九十年代的上海，阿宝从一个普通工人变成宝总，在时代的浪潮里起伏。小说的骨、电影的皮，王家卫拍了一部关于时间和欲望的连续剧。",
        progress = 0.42f,
    ),
    Vod("黑暗召唤师在地下恋爱中", "2026", "https://p.bfvp26.com/upload/vod/20261005-fix/5e914399591b0fe2d238cdf655dfb78e.jpg", "动画", progress = 0.12f),
    Vod("女友的朋友", "2026", "https://p.bfvp26.com/upload/vod/20261005-1/17b6763a3d99f76e3c3717cc6bb2c884.webp", "剧情"),
    Vod("第三调解室", "2024", "https://p.bfvp26.com/upload/vod/20240621-1/146bfcbaff0032d3b1b16cb38782a074.jpg", "综艺"),
    Vod("男生女生向前冲", "2023", "https://p.bfvp26.com/upload/vod/20231228-1/41235363215605447db26c7a0c24178b.jpg", "综艺"),
    Vod("奥本海默", "2023", "", "电影"),
)

private fun warmUp() {
    val loader = SingletonImageLoader.get(PlatformContext.INSTANCE)
    runBlocking {
        SAMPLE.forEach { d ->
            if (d.pic.isBlank()) return@forEach
            runCatching {
                loader.execute(
                    ImageRequest.Builder(PlatformContext.INSTANCE).data(d.pic).size(300, 458).build()
                )
            }
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
private fun renderToPng(path: String, isTv: Boolean) {
    warmUp()
    val scene = ImageComposeScene(width = 1800, height = 1280, density = Density(2f)) {
        ProomTheme(isTv = isTv) { TvHome(continueWatching = SAMPLE, latest = SAMPLE) }
    }
    try {
        val image = scene.render()
        val data = image.encodeToData(EncodedImageFormat.PNG) ?: error("PNG 编码失败")
        File(path).writeBytes(data.bytes)
        println("rendered -> $path")
    } finally {
        scene.close()
    }
}
