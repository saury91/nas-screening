plugins {
    id("com.android.library")
    kotlin("android")
    id("org.jetbrains.kotlin.plugin.compose")
    // 缓存（首页/详情/搜索）与配置都落 JSON
    kotlin("plugin.serialization")
}

android {
    // R 类落在 com.projectionroom.app.data，源码包名仍是 com.projectionroom.app
    namespace = "com.projectionroom.app.data"
    compileSdk = 35

    defaultConfig { minSdk = 21 }

    buildFeatures { compose = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions { jvmTarget = "17" }
}

// Android 侧的数据与系统能力：存储、发现、配对、投屏接收（DLNA）、音乐前台服务、通知。
// 只依赖 core + ui-media 的数据层，不依赖任何一端的页面模块。
dependencies {
    api(project(":core"))
    api(project(":ui-media"))

    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")

    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.media:media:1.7.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")

    // 配对二维码
    implementation("com.google.zxing:core:3.5.3")
}
