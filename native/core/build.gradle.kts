import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization")
    id("com.android.library")
}

kotlin {
    jvm()
    androidTarget {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }
    // 曾经声明过 iosArm64() / iosSimulatorArm64()，但一直没有 iosMain 源集、
    // 也从未编译通过一次（Kotlin/Native 工具链都没下载过），只为「看起来支持 iOS」。
    // 真要上 iOS 时再加回来：加 target、补 Http / ImageHttp / DlnaClient / LanCastClient
    // 四个平台实现，同时别忘了本文件 androidMain 里的 okhttp 在 iOS 上没有对应物。

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
            }
        }
        val androidMain by getting {
            dependencies { implementation("com.squareup.okhttp3:okhttp:4.12.0") }
        }
        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
            }
        }
    }
}

android {
    namespace = "com.projectionroom.shared"
    compileSdk = 35
    defaultConfig { minSdk = 21 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
