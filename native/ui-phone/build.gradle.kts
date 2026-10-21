plugins {
    id("com.android.library")
    kotlin("android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.projectionroom.ui.phone"
    compileSdk = 35

    defaultConfig { minSdk = 21 }

    buildFeatures { compose = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions { jvmTarget = "17" }
}

// 手机端的全部页面与组件。这里的代码只服务触摸屏：
// 底部 tab、滚动页签、下拉刷新、扫码相机，不再为「焦点环」做妥协。
dependencies {
    api(project(":core"))
    api(project(":ui-kit"))
    api(project(":ui-media"))

    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")

    implementation("androidx.activity:activity-compose:1.9.3")

    // 扫电视上的二维码：CameraX + zxing 解码头
    implementation("com.google.zxing:core:3.5.3")
    implementation("androidx.camera:camera-core:1.4.1")
    implementation("androidx.camera:camera-camera2:1.4.1")
    implementation("androidx.camera:camera-lifecycle:1.4.1")
    implementation("androidx.camera:camera-view:1.4.1")
    // ProcessCameraProvider.bindToLifecycle 返回 ListenableFuture
    implementation("com.google.guava:guava:33.3.1-android")
    // 图片加载统一走 ui-kit 的 RemoteImage（Glide 由 ui-kit 的 api 依赖传递过来）
}
