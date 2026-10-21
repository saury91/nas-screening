plugins {
    id("com.android.library")
    kotlin("android")
    id("org.jetbrains.kotlin.plugin.compose")
    // 音乐库/直播库要落 JSON
    kotlin("plugin.serialization")
}

android {
    namespace = "com.projectionroom.ui.media"
    compileSdk = 35

    defaultConfig { minSdk = 21 }

    buildFeatures { compose = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions { jvmTarget = "17" }
}

// 播放器与媒体库：ExoPlayer、音乐队列、直播收藏。
// 现在两端共用一份；等两端播放页设计分道扬镳时，这里按 ui-tv / ui-phone 各复制一份。
dependencies {
    api(project(":core"))
    api(project(":ui-kit"))

    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")

    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")

    // 图片加载统一走 ui-kit 的 RemoteImage（Glide 由 ui-kit 的 api 依赖传递过来）
}
