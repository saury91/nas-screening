pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "projection-room-native"

// ── 共用层 ──────────────────────────────────────────────
// core：模型 / NAS 接口 / 协议 / 配置包（纯逻辑，无 Android 依赖）
include(":core")
// ui-kit：主题、图标、无分支的原子组件（两端视觉底座）
include(":ui-kit")
// ui-media：播放器 + 音乐库 + 直播库（现阶段两端共用，下一步按端各复制一份）
include(":ui-media")
// data：Android 侧存储、网络、配对、投屏、通知服务
include(":data")

// ── 电视端 ──────────────────────────────────────────────
// 页面只属于电视：TvHome / TvHero / TV 详情 / TV 播放器……
include(":ui-tv")
include(":app-tv")

// ── 手机端 ──────────────────────────────────────────────
// 页面只属于手机：PhoneShell / PhoneFilm / 扫码配对……
include(":ui-phone")
include(":app-phone")
