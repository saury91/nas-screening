plugins {
    id("com.android.library")
    kotlin("android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.projectionroom.ui.kit"
    compileSdk = 35

    defaultConfig { minSdk = 21 }

    buildFeatures { compose = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions { jvmTarget = "17" }
}

// 只放「两端长得一样」的东西：色板/字号/动效、图标、返回条、骨架、雾化背景。
// 这里一旦出现 if (isTv)，就说明该拆成两份了，别往这儿塞。
dependencies {
    api(project(":core"))

    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")

    // 图片加载统一走 Glide（+ 官方 Compose 封装 + 官方 OkHttp 集成）。
    // 用 api 是为了让 ui-tv / ui-phone 直接拿 GlideImage 类型，不必各自重复声明。
    api("com.github.bumptech.glide:glide:4.16.0")
    api("com.github.bumptech.glide:compose:1.0.0-beta06")
}
