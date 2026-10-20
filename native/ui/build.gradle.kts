import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("multiplatform")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.android.library")
    // 音乐库/直播库要落 JSON（和 app 侧 LibraryStore 一致，不自己造文本格式）
    kotlin("plugin.serialization")
}

kotlin {
    // desktop 只用于本地预览：./gradlew :ui:run 开窗口，或 RENDER=... 离屏出图
    jvm("desktop")
    androidTarget {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        val commonMain by getting {
            dependencies {
                // api 而不是 implementation：壳里也要用 Vod 这些模型
                api(project(":shared"))
                implementation(compose.runtime)
                implementation(compose.foundation)
                implementation(compose.material3)
                implementation(compose.ui)
                implementation(compose.components.resources)
                implementation("io.coil-kt.coil3:coil-compose:3.0.4")
            }
        }
        val androidMain by getting {
            dependencies {
                implementation(compose.preview)
                implementation("io.coil-kt.coil3:coil-network-okhttp:3.0.4")
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
                // 播放：Media3（硬解 HLS）
                implementation("androidx.media3:media3-exoplayer:1.4.1")
                implementation("androidx.media3:media3-exoplayer-hls:1.4.1")
                implementation("androidx.media3:media3-ui:1.4.1")
                implementation("androidx.activity:activity-compose:1.9.3")
                // 手机端扫电视二维码：相机预览 + zxing 解码头
                implementation("com.google.zxing:core:3.5.3")
                implementation("androidx.camera:camera-core:1.3.4")
                implementation("androidx.camera:camera-camera2:1.3.4")
                implementation("androidx.camera:camera-lifecycle:1.3.4")
                implementation("androidx.camera:camera-view:1.3.4")
            }
        }
        val desktopMain by getting {
            dependencies {
                implementation(compose.desktop.currentOs)
                implementation("io.coil-kt.coil3:coil-network-okhttp:3.0.4")
            }
        }
        val desktopTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation(compose.desktop.uiTestJUnit4)
            }
        }
    }
}

android {
    namespace = "com.projectionroom.ui"
    compileSdk = 35
    defaultConfig { minSdk = 21 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

compose.desktop {
    application { mainClass = "com.projectionroom.ui.PreviewKt" }
}
