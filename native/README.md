# 原生客户端（KMP）

WebView 版的下一步：用 Compose 重写 UI，业务层用 Kotlin Multiplatform 共享。

## 现状

| 模块 | 状态 |
|---|---|
| `shared` | ✅ 数据层完成：MacCMS 解析、跨源合并、片名归一、画质分档；12 个单测全绿 |
| `ui` | 待建（Compose Multiplatform 设计系统） |
| `app-android`（tv / phone） | 待建 |
| `app-ios` | 待建（只出代码，不联调） |

## 构建

需要 JDK 17（机器上已有 `zulu-17.jdk`，`gradle.properties` 里指定了）。

```bash
cd native
./gradlew :shared:jvmTest      # 跑数据层单测，约 6 秒
```

首次会自动下载 Gradle 与依赖（约 300MB，已指向腾讯镜像）。

## 为什么是 KMP 而不是纯 Android

`shared` 里全是纯 Kotlin，没有任何 Android 依赖：Android 直接引用，
将来 iOS 出 framework 也是同一份代码。UI 才分平台写——
TV 要 D-pad 焦点、手机要手势转场，共用一套响应式布局两边都会别扭。
