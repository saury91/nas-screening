plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.projectionroom.tv"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.projectionroom.tv"
        // 老电视普遍是 5.x / 6.x，能装就先让它装
        minSdk = 21
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        // 自带签名，侧载直接装，不用另配 keystore
        debug {
            applicationIdSuffix = ""
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources.excludes += setOf("META-INF/*.kotlin_module")
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    // WebView 版本探测（判断能不能跑现代前端）
    implementation("androidx.webkit:webkit:1.11.0")
    // 播放交给原生：老电视 WebView 软解扛不住 4K
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")
    // 生成配对二维码（纯 Java，不依赖 Android 框架）
    implementation("com.google.zxing:core:3.5.3")
}
