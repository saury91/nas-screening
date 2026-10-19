# 原生客户端（KMP）

WebView 版的下一步：用 Compose 重写 UI，业务层用 Kotlin Multiplatform 共享。

## 现状

| 模块 | 状态 |
|---|---|
| `shared` | ✅ 数据层：MacCMS 解析、跨源合并、片名归一、画质分档；12 个单测全绿 |
| `ui` | 🚧 首页 / 片库 / 详情 / 搜索 / 直播 / 音乐 / 我的 + **首次连接页** |
| `app-android` | 🚧 可装可跑：局域网自动发现 NAS、地址本地保存、Media3 硬解播放 |
| `app-ios` | 待建（只出代码，不联调） |

## 连上 NAS（首次启动）

原生端不再写死地址，开机流程是：

```
读本地地址（SharedPreferences）
  ├─ 有 → 直接进首页
  └─ 没有 → UDP 广播 PROOM_DISCOVER 到 8897
              ├─ 收到 PROOM_OFFER → 存下来，进首页
              └─ 3.5 秒没回 → 显示「连上你的 NAS」，可手动填地址（自动补 :8899）
```

协议与 WebView 版电视端完全一致，NAS 端（`app.py` 的 `discovery_loop`）不用改。

## 构建

需要 JDK 17 + Android SDK（`compileSdk 35`）。`local.properties` 里写自己的 SDK 路径：

```properties
sdk.dir=/Users/<you>/Library/Android/sdk
```

```bash
cd native
./gradlew :shared:jvmTest              # 数据层单测，约 6 秒
./gradlew :app-android:assembleDebug   # 出 debug APK
RENDER=/tmp/ui.png ./gradlew :ui:run   # 离屏渲三张图（手机/TV/连接页），不需要模拟器
```

首次会自动下载 Gradle 与依赖（约 300MB，已指向腾讯镜像）。

## 为什么是 KMP 而不是纯 Android

`shared` 里全是纯 Kotlin，没有任何 Android 依赖：Android 直接引用，
将来 iOS 出 framework 也是同一份代码。UI 才分平台写——
TV 要 D-pad 焦点、手机要手势转场，共用一套响应式布局两边都会别扭。

## 已知缺口

- 手机端还没有「切换 NAS 地址」的入口（只能在首次启动时配）
- applicationId 还是 `com.projectionroom.app`，接不进现有 `com.projectionroom.tv` 的升级链
- 旧的 `android/`（WebView 版）仍在，等原生版覆盖主要功能后再清理
