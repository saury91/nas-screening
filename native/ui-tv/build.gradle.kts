plugins {
    id("com.android.library")
    kotlin("android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.projectionroom.ui.tv"
    compileSdk = 35

    defaultConfig { minSdk = 21 }

    buildFeatures { compose = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions { jvmTarget = "17" }
}

// 电视端的全部页面与组件。这里的代码只服务 10-foot UI：
// 焦点环、D-pad 导航、遥控器硬键，不再为「手指热区」做妥协。
dependencies {
    api(project(":core"))
    api(project(":ui-kit"))
    api(project(":ui-media"))

    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")

    implementation("androidx.activity:activity-compose:1.9.3")
    // 图片加载统一走 ui-kit 的 RemoteImage（Glide 由 ui-kit 的 api 依赖传递过来）
}
