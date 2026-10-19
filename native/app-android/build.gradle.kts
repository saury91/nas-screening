plugins {
    id("com.android.application")
    kotlin("android")
    id("org.jetbrains.kotlin.plugin.compose")
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

    buildTypes {
        release {
            isMinifyEnabled = false
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
    // Coil 的网络层：Android 侧走 OkHttp
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.0.4")
}
