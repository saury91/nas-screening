# 原生客户端（KMP）

WebView 版的下一步：用 Compose 重写 UI，业务层用 Kotlin Multiplatform 共享。

## 现状

| 模块 | 状态 |
|---|---|
| `core` | ✅ 数据层：MacCMS 解析、跨源合并、片名归一、画质分档；12 个单测全绿 |
| `ui-kit` | 主题 / 图标 / 骨架 / 雾化背景 —— 两端共用的视觉底座，不允许出现 `if (isTv)` |
| `ui-media` | 播放器 + 音乐库 + 直播库（现阶段两端共用） |
| `data` | Android 侧存储、NAS 闸门、库同步、配对、DLNA、通知栏服务 |
| `ui-tv` / `app-tv` | 📺 电视端独立 App：`com.projectionroom.tv` |
| `ui-phone` / `app-phone` | 📱 手机端独立 App：`com.projectionroom.app` |
| `app-ios` | 不做：签名与安装链路在自建环境里走不通，出了包也装不上，暂不投入 |

### 为什么拆成两个 App

电视和手机的设计已经分道扬镳（10-foot 焦点环 vs 手指热区），继续挤在一个
`if (onTv)` 里改一处崩一处。现在：

```
core / ui-kit  ← 平台能力与视觉底座，两端共用
   ├── ui-tv    + app-tv      applicationId com.projectionroom.tv
   └── ui-phone + app-phone   applicationId com.projectionroom.app
```

两个 applicationId 不同，**可以同机共存、各自发版**；电视盒子上已装的旧版
`com.projectionroom.app` 不会被覆盖，需要另装 TV 版并重新配对（配置可导出导入）。

## 装完就能看：配置内置在 APK 里

`app-tv` / `app-phone` 各带一份 `src/main/assets/config/bundle.json`（约 90KB：
片源清单 + 40 组直播频道表 + 音乐接口地址）。启动时 `ConfigStore.seedFromAssets()`：

```
本地还没有 bundle.json
  └─► 读 assets ──► ConfigWire.decode（线上格式 live.items → 本地 live.channels）
                └─► 原子写 files/config/bundle.json，并把「已跳过连 NAS」置上
```

所以**首启不会被「连上你的 NAS」拦住**：装完直接有片源、有 349 个直播台，
NAS 开不开机都一样。三个相关动作的区别：

| 动作 | 什么时候做 | 本地已有配置时 |
|---|---|---|
| `seedFromAssets`（首启播种） | 每次 onCreate | 直接跳过，不覆盖 |
| 设置 →「立即同步配置」 | 想让片源跟 NAS 对齐 | 从 NAS 拉，版本变了才写 |
| 设置 →「恢复内置配置」 | 导错包、拉到空配置 | **强制**用 APK 里那份覆盖 |

三条路都走同一个 `ConfigStore.save`：先留 `.bak`、再写 `.tmp`、最后改名，
恢复错了还能从 `files/config/bundle.json.bak` 手工找回来。

内置配置跟着 APK 版本走 —— 想换片源又不想发版，连 NAS；想改「出厂」配置，
改 `assets/config/bundle.json` 重新打包。

## 连上 NAS（可选）

配置已经在包里了，连 NAS 只是为了**后续更新**配置、同步收藏、下载新版本 APK。
用户主动去设置里点「更换」时才走这条路，开机流程是：

```
读本地地址（SharedPreferences）
  ├─ 有 → 直接进首页
  └─ 没有 → UDP 广播 PROOM_DISCOVER 到 8897
              ├─ 收到 PROOM_OFFER → 存下来，进首页
              └─ 3.5 秒没回 → 显示「连上你的 NAS」，可手动填地址（自动补 :8899）
```

首启播种成功后 `NasStore.setSkipped(true)`，这条流程不会在启动时触发；
「跳过」标记只影响启动拦截，不影响设置里手动换地址。

协议与 WebView 版电视端完全一致，NAS 端（`app.py` 的 `discovery_loop`）不用改。

## NAS 只是配置源，不是运行时依赖

这是整个客户端最重要的一条设计前提：**NAS 关机是常态**。

```
启动 ──► 读本地 bundle.json（片源清单 + 直播频道表 + 版本）
          ├─ 有 → 立即可用
          └─ 后台向 NAS 要新的，版本变了才重写
```

| 能力 | 数据从哪来 | NAS 关机时 |
|---|---|---|
| 搜索 | 本地 `Catalog` 并发直连所有采集站 | ✅ 照常 |
| 详情 / 线路 / 选集 | 同上（同名合并，`$$$` 拆多线路） | ✅ 照常 |
| 片库 / 分类浏览 | 按配置包里的分类映射直连各站 | ✅ 照常 |
| 播放 | 直连源站，ExoPlayer 自带 Referer/UA | ✅ 照常 |
| 字幕 / 音轨 | 内嵌轨来自片源；本地字幕用 SAF 选一次、拿持久读权限，按「片名 + 集」记在 App 私有目录 | ✅ 照常 |
| 直播 | 配置包里的频道表 | ✅ 照常 |
| 收藏 / 播放记录 | 本地主库，NAS 在线时后台同步 | ✅ 离线照常记录 |
| 首页推荐 | App 自己拼（本地片源 + 快照缓存） | ✅ 照常 |
| 音乐 | App 直连网易云聚合接口 | ✅ 照常 |

配置包里每个源都带 **`cats: {"20": "电影", "21": "剧集"}`** —— 各站 `type_id`
排法完全不同（暴风 `t=20` 才是电影，别家 `t=6` 就是），客户端猜不出来，
所以由 NAS 探好后随包下发。探测在服务端后台跑（`catalog.catmap_warm`），
配置包只读缓存，**20ms 返回**，不会因为探源而卡住首启。

缓存的语义也一并改了：`FileCache` **不再设 TTL**，「多久算新鲜」只决定要不要后台刷新，
缓存本身永不淘汰 —— 以前 10 分钟就作废，NAS 一关首页直接空白。

### 接口边界（硬规则）

NAS 只承担四件事：**同步、投射、更新、配置**。

| 走 NAS | 端点 |
|---|---|
| 同步 | `/api/fav`、`/api/history`、`/api/library` |
| 投射 | 投屏桥接（`CastClient`） |
| 更新 | 版本检查、APK 下载 |
| 配置 | `/api/config/bundle`，以及配置里带下来的直播频道表 |

除此之外的一切都是**资源加载**，必须直连源站，**不许挂 NAS 兜底**：
首页、片库、搜索、详情、线路解析、播放、音乐、封面图。

落地位置：`NasApi` 的类型注释、`NasGate` 的范围说明。以后想在
`doSearch` / `doDetail` / `doBrowse` / 音乐那几条链路上加 `NasGate.tryCall`，
先问一句「这是不是上面四类」—— 不是就别加。2026-10-22 就因为这个把搜索拖到
「NAS 502 时一直转」：两层 NAS 兜底各等 2.5 秒，还没算本地 12 秒的汇总时间。

## 构建

需要 JDK 17 + Android SDK（`compileSdk 35`）。`local.properties` 里写自己的 SDK 路径：

```properties
sdk.dir=/Users/<you>/Library/Android/sdk
```

```bash
cd native
./gradlew :core:jvmTest                # 数据层单测，约 6 秒
./gradlew :app-tv:assembleDebug        # 电视端 APK（com.projectionroom.tv）
./gradlew :app-phone:assembleDebug     # 手机端 APK（com.projectionroom.app）
./gradlew :app-tv:assembleDebug :app-phone:assembleDebug   # 两个一起出
```

首次会自动下载 Gradle 与依赖（约 300MB，已指向腾讯镜像）。

## 为什么是 KMP 而不是纯 Android

`core` 的 `commonMain` 是纯 Kotlin，没有任何 Android 依赖：模型、NAS 接口、
采集站协议、投屏协议的组装与解析都在这一层，也是单测覆盖最厚的地方。
平台差异收在 `androidMain` 的四个实现里（`OkHttpHttp`、`ImageHttp`、
`DlnaClient`、`LanCastClient`）。UI 才分平台写——TV 要 D-pad 焦点、
手机要手势转场，共用一套响应式布局两边都会别扭，所以是两个 App、两套页面。

iOS 端不做：自建环境下签名与安装链路走不通，出了包也装不上，投入产出不成正比。

`core/build.gradle.kts` 里**不再声明 iOS target**。以前声明过
`iosArm64()` / `iosSimulatorArm64()`，但没有 `iosMain` 源集，Kotlin/Native
工具链从未下载，一次都没编译过——那个声明除了让人以为「已经支持 iOS」，
没有任何作用。真要上 iOS 时再加回来，代价是：补四个平台实现
（`Http` 用 NSURLSession、`ImageHttp`、`DlnaClient` 的 SSDP、`LanCastClient` 的 TCP），
并在 Info.plist 里配本地网络权限说明。注意 `androidMain` 里的 okhttp 在 iOS 上没有对应物。

## 换 NAS 地址

设置页里唯一会改变「数据从哪来」的项，两个端都只放了一个入口：

- 手机：「我的 → 设置 → 地址」
- 电视：顶栏右侧那个机箱图标（放大镜左边，一周用不到一次，所以不写字）

进的是同一张「换一台 NAS」页：显示当前地址、可以再搜一次、也可以手填。
电视上不弹系统软键盘（遥控器在软键盘上挪一个字母要按十几次），
换成只读地址栏 + 3×4 数字键盘，方向键在键位上走。

## 收藏与记录：本地是主库

```
点收藏 ──► 立刻写本地 library.json（pendingFav=true）
        └─► 后台推 NAS ──► 成功清标记 / 失败留着下次
启动   ──► 先推未同步的，再拉 NAS 上的（本机 pending 的不覆盖）
```

- NAS 关机一个月也不会丢东西，开机自动补齐；
- 收藏在 NAS 上是**切换**语义，所以推之前先拉一次真实现状，
  已经一致就跳过 —— 否则「取消收藏」会被翻成「重新收藏」；
- 同步用的 `syncApi` **不走磁盘缓存**：拿缓存里的旧快照会让上面那条判断直接失效。

## 首页不重复

Hero 轮播的前 6 条，「热门推荐 / 最近更新」里不再出现（`drop(6)`）。
同一部片不该在同一屏出现两次。

## 「NAS 只配一次」

极端情况也要成立：**第一次同步完配置，那台机器可能永远不会再开机**。

所以：
- 配置包 `bundle.json` 原子写 + 留一份 `.bak`；主文件坏了读备份（配置来源已消失，必须留一手）
- 所有「问 NAS 兜底」都走 `NasGate` 熔断：失败一次本进程 60 秒内不再试，
  否则每次搜索/详情都要白等 2.5 秒
- 启动拉配置包限时 8 秒、收藏同步限时 6 秒，都只影响后台
- 首页快照只存展示字段（砍掉 lines 后从 1.8MB 降到 53KB），冷启动 1.4 秒进首页

## 首页也是本地拼的

首页不再是 NAS 的推荐算法给的，App 自己算：

```
读 home.json ──► 立刻渲染（哪怕是昨天、上个月的快照）
              └─► 缓存超过 15 分钟就后台重拼
本地重拼：五个分类各取第一页（每类最多 8 个源，并发）
        → 合并去重、按年份倒序 → Hero / 热门推荐 / 分类货架
「继续观看」「收藏」直接来自本机记录库
```

所以只要 `bundle.json` 里的片源地址还在，**NAS 一年不开机，首页照常出新内容**。
真的一条都拼不出来（没片源或全超时）才回头问 NAS。

## 音乐：App 直连网易云

接口是公开的聚合服务（Meting 风格），**不需要登录、没有签名**：

```
GET /netease/playlist/recommend?limit=N     推荐歌单
GET /getPlaylistCategory                    分类树
GET /netease/playlist/category?type=&limit= 分类歌单
GET /meting/?type=playlist&id=             歌单详情（含曲目 id）
GET /meting/?type=url&id=                  播放地址（302 到 CDN）
```

只有聚合域名校验 Referer（`https://disabled.invalid/`），**网易云 CDN 本身不校验**。
所以流程是：App 拿一次 302 的 `Location`（不解跳转），把最终 CDN 地址交给 ExoPlayer 直接播，
NAS 完全不参与。地址有时效，点击时现取。

`MusicApi` 在 `shared` 里，和搜索/片库一样由壳注入进 `MusicScreen` / `PlaylistScreen`，
页面不认识 `NasApi`。

## 「我的」两个端各一套

- 手机：底部第 4 个 tab，收藏是 3 列网格，记录是一条一条的横条。
- 电视：顶栏最右边的人形图标。收藏走横滑货架（海报 88~128dp），
  记录是一行一条的宽条 —— 缩略图 + 片名 + 第几集 + 进度条，遥控器上下翻。
  进入时焦点落在「‹ 返回」，一路 DOWN 能走完全部记录。

## 已知缺口

- 首页推荐 / 收藏 / 播放记录仍读写 NAS（有缓存兜底，但不等于本地化）
- 音乐试听片段已接：`MusicBox` 在拿到播放地址后调 `MusicApi.isFullTrack()`，
  网易云对无版权曲目只给 30 秒片段（约 480KB），判出来直接跳下一首并说明原因。
  判定口径是「明确拿到长度且小于 1.2MB」，**拿不到长度一律放行** —— HEAD 被拒
  或 CDN 走 chunked 时若也判片段，整张歌单会被逐首跳过
- 字幕的手机 / 电视差异：电视没有系统文件选择器，**本地字幕要在手机上选好**，
  电视端只能切内嵌轨与音轨
- Web 控制台的字幕只做外挂文件（srt / vtt / ass）：hls.js 的流内嵌字幕轨要再经
  同源代理去取每条字幕分片，而分片地址是相对 playlist 解析的，穿过 `/api/proxy`
  后路径会错 —— 改 loader 的收益不抵风险，所以浏览器端不解析内嵌轨
- 上一代 WebView 壳（原 `android/`）已移除，只有它那把签名密钥留在 `native/keystore/`
