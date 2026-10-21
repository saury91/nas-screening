plugins {
    id("com.android.application")
    kotlin("android")
    id("org.jetbrains.kotlin.plugin.compose")
    kotlin("plugin.serialization")
    // Glide 的 @GlideModule 靠注解处理器生成 GeneratedAppGlideModuleImpl，
    // 生成类必须落在 applicationId 同名包里，所以只能在 app 模块跑 kapt。
    kotlin("kapt")
}

android {
    // 电视端独立包名：可以和手机端装在同一台设备上，各自发版互不覆盖
    namespace = "com.projectionroom.tv"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.projectionroom.tv"
        minSdk = 21
        targetSdk = 35
        versionCode = 1
        versionName = "0.13.0"
    }

    buildFeatures { compose = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions { jvmTarget = "17" }

    // 仍用老工程那把钥匙（native/keystore/proom.jks）。包名变了，所以电视盒上
    // 已装的 com.projectionroom.app 不会原地升级——需要卸载重装，配对/配置可导出导入。
    signingConfigs {
        create("release") {
            val ks = rootProject.file("keystore/proom.jks")
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
    implementation(project(":ui-tv"))

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
    // 图片：Glide + 官方 Compose 封装 + 官方 OkHttp 集成（复用 ImageHttp 的 UA/Referer/超时/重试）
    implementation("com.github.bumptech.glide:glide:4.16.0")
    implementation("com.github.bumptech.glide:compose:1.0.0-beta06")
    implementation("com.github.bumptech.glide:okhttp3-integration:4.16.0")
    kapt("com.github.bumptech.glide:compiler:4.16.0")
}
