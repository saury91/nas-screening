plugins {
    id("com.android.application")
    kotlin("android")
    id("org.jetbrains.kotlin.plugin.compose")
    kotlin("plugin.serialization")
    kotlin("kapt")
}

android {
    // applicationId 保持 com.projectionroom.app：手机上已装的 0.12.0 能原地升级，
    // 配对信息和观看记录不断链。namespace 另起，避免和 data 模块撞。
    namespace = "com.projectionroom.phone"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.projectionroom.app"
        minSdk = 21
        targetSdk = 35
        // 发版时由 CI/脚本注入（tag 里解析），本地构建用默认值
        versionCode = System.getenv("PROOM_VERSION_CODE")?.toIntOrNull() ?: 3
        versionName = System.getenv("PROOM_VERSION_NAME") ?: "0.15.0"
    }

    buildFeatures { compose = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions { jvmTarget = "17" }

    // 默认用仓库里那把公用钥匙，谁构建都是同一条升级链；CI 或私有构建
    // 可以用环境变量换成自己的钥匙，不需要改代码。
    signingConfigs {
        create("release") {
            val ks = rootProject.file(
                System.getenv("PROOM_KEYSTORE") ?: "keystore/proom.jks")
            if (ks.exists()) {
                storeFile = ks
                storePassword = System.getenv("PROOM_KEYSTORE_PASS") ?: "proom-release"
                keyAlias = System.getenv("PROOM_KEY_ALIAS") ?: "proom"
                keyPassword = System.getenv("PROOM_KEY_PASS")
                    ?: System.getenv("PROOM_KEYSTORE_PASS") ?: "proom-release"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
        debug {
            signingConfig = signingConfigs.getByName("release")
        }
    }
}

dependencies {
    implementation(project(":core"))
    implementation(project(":data"))
    implementation(project(":ui-kit"))
    implementation(project(":ui-media"))
    implementation(project(":ui-phone"))

    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")

    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("androidx.media:media:1.7.0")
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")
    implementation("com.google.zxing:core:3.5.3")
    implementation("androidx.camera:camera-core:1.4.1")
    implementation("androidx.camera:camera-camera2:1.4.1")
    implementation("androidx.camera:camera-lifecycle:1.4.1")
    implementation("androidx.camera:camera-view:1.4.1")
    // 图片：Glide + 官方 Compose 封装 + 官方 OkHttp 集成（复用 ImageHttp 的 UA/Referer/超时/重试）
    implementation("com.github.bumptech.glide:glide:4.16.0")
    implementation("com.github.bumptech.glide:compose:1.0.0-beta06")
    implementation("com.github.bumptech.glide:okhttp3-integration:4.16.0")
    kapt("com.github.bumptech.glide:compiler:4.16.0")
}
