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
        versionCode = 9
        versionName = "0.4.3"
    }

    buildFeatures {
        buildConfig = true
    }

    // 一套代码出两个包：电视端和手机端不再靠运行时猜，行为在编译期就定死
    flavorDimensions += "device"
    productFlavors {
        create("tv") {
            dimension = "device"
            applicationId = "com.projectionroom.tv"
            buildConfigField("boolean", "IS_TV", "true")
            buildConfigField("String", "KIND", "\"tv\"")
            resValue("string", "app_name", "放映室 TV")
        }
        create("phone") {
            dimension = "device"
            applicationId = "com.projectionroom.phone"
            buildConfigField("boolean", "IS_TV", "false")
            buildConfigField("String", "KIND", "\"phone\"")
            resValue("string", "app_name", "放映室")
        }
    }

    // 发布签名：仓库里带一份固定密钥，保证每次构建的签名一致，
    // 否则内网升级会因为"签名不一致"装不上。
    // 在意安全的话，用环境变量 PROOM_KEYSTORE / PROOM_KEYSTORE_PASS 换成自己的。
    val ksEnv = System.getenv("PROOM_KEYSTORE")
    val ksFile = if (ksEnv != null) file(ksEnv) else rootProject.file("keystore/proom.jks")
    val ksPass = System.getenv("PROOM_KEYSTORE_PASS") ?: "proom-release"

    signingConfigs {
        create("release") {
            if (ksFile.exists()) {
                storeFile = ksFile
                storePassword = ksPass
                keyAlias = System.getenv("PROOM_KEY_ALIAS") ?: "proom"
                keyPassword = System.getenv("PROOM_KEY_PASS") ?: ksPass
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = if (ksFile.exists()) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
        // debug 与 release 同签名，装来装去不会互相覆盖失败
        debug {
            signingConfig = if (ksFile.exists()) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
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
