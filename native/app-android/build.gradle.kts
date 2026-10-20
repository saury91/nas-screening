plugins {
    id("com.android.application")
    kotlin("android")
    id("org.jetbrains.kotlin.plugin.compose")
    kotlin("plugin.serialization")
}

android {
    namespace = "com.projectionroom.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.projectionroom.app"
        minSdk = 21
        targetSdk = 35
        versionCode = 2
        versionName = "0.12.0"
    }

    buildFeatures { compose = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions { jvmTarget = "17" }

    // 用老工程那把固定钥匙（android/keystore/proom.jks）签名：
    // 真机上装的 0.12.0 就是它签的，换成默认 debug key 会
    // INSTALL_FAILED_UPDATE_INCOMPATIBLE，而且升级链会断。
    signingConfigs {
        create("release") {
            val ks = rootProject.file("../android/keystore/proom.jks")
            if (ks.exists()) {
                storeFile = ks
                storePassword = "proom-release"
                keyAlias = "proom"
                keyPassword = "proom-release"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
        // debug 也走同一把钥匙：装来装去不会互相覆盖失败
        debug {
            signingConfig = signingConfigs.getByName("release")
        }
    }
}

dependencies {
    implementation(project(":ui"))
    implementation(project(":shared"))

    // Compose 版本统一由 BOM 管；ui 模块用的是 implementation，不会传递到壳里
    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")

    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.13.1")
    // 配置包落盘要序列化；serializer 由 shared 模块的编译器插件生成，这里只要运行时
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    // 通知栏媒体控制：MediaSessionCompat + MediaStyle 通知
    implementation("androidx.media:media:1.7.0")
    // Coil 的网络层：Android 侧走 OkHttp
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.0.4")
    // 手机↔电视配对：QR 生成/识别（zxing 是纯 JVM，不带 Android 依赖）
    implementation("com.google.zxing:core:3.5.3")
    // 扫码要用相机预览：CameraX 体量比自带 Camera2 封装小，行为也稳
    // 1.4.1 起 native 库按 16KB page size 对齐（Android 15+ 设备必需）；
    // 1.3.4 的 libimage_processing_util_jni.so 是 4K 对齐，装到 16KB 设备会弹兼容提示
    implementation("androidx.camera:camera-core:1.4.1")
    implementation("androidx.camera:camera-camera2:1.4.1")
    implementation("androidx.camera:camera-lifecycle:1.4.1")
    implementation("androidx.camera:camera-view:1.4.1")
}
