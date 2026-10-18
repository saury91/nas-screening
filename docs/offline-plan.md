# 离线方案：本地页面 + 离线投屏

> 目标：NAS 关机时，手机 / 电视 App 仍能打开界面、看缓存内容，并把当前这一集推到电视。
> NAS 恢复后自动更新到最新前端，用户无感。
>
> **⚠️ 先看文末第 7~9 节**：那里是修订后的选型（本地 HTTP 服务 + 手机直连电视），
> 比第 1~3 节的初版更优，实施以修订版为准。

---

## 0. 现状（为什么现在做不到）

| 事实 | 位置 |
|---|---|
| APK 里没有任何前端文件 | `android/app/src/main/assets/` 不存在 |
| WebView 打开的是 NAS 地址 | `MainActivity.loadHome()` → `http://<nas>:8899/#/home` |
| 没有 Service Worker | `http://` 源不允许注册 SW，这条路直接堵死 |
| `/api/proxy` 的图片是 `no-store` | 海报不会被 WebView 缓存 |
| 页面代码是 `no-cache` | 每次都要回 NAS 校验，离线即失败 |

结论：现在的客户端是个"空壳浏览器"，NAS 一关连界面都打不开。

---

## 1. 方案 A：前端本地化 + 增量更新

### 1.1 加载方式：WebViewAssetLoader（不用 file://）

```
assets/www/            ← 出厂种子（构建时打包）
filesDir/www/          ← 运行版本（可更新，优先使用）
        ↓
https://appassets.androidplatform.net/index.html
        ↓
WebView 加载这个 https 地址
```

为什么必须走 `WebViewAssetLoader`：

- `file://` 下 `fetch('http://nas:8899/api/...')` 会被 CORS 拦（origin 是 `null`）；
- 映射成 https 域后，页面是"正常源"，调 NAS 的接口、加载远程图都没问题；
- `androidx.webkit` 已经在依赖里（`1.11.0`），不需要新增库。

加载顺序：

```
启动 → 有 filesDir/www ？ → 有：加载它
                         → 没有：加载 assets/www（首次安装）
```

### 1.2 更新流程：清单 + 差异下载

NAS 新增一个接口（复用现有静态目录，构建时生成清单）：

```
GET /api/app/web/manifest
{
  "version": "20261018-1",
  "files": [
    {"path": "index.html", "sha256": "…", "size": 3211},
    {"path": "app/main.js", "sha256": "…", "size": 18422},
    …
  ]
}
```

客户端启动后（不阻塞首屏）：

1. 请求 manifest；
2. `version` 和本地一致 → 什么都不做；
3. 不一致 → **只下载 sha256 变了的文件**到 `www.new/`；
4. 全部校验通过 → `www` → `www.old`，`www.new` → `www`（原子换）；
5. 下次冷启动生效（不热切，避免运行中被换掉）。

失败（NAS 不可达 / 校验不过）→ 保留旧版本，顶部显示一条「离线模式」横幅。

### 1.3 接口地址：从"同源"改成"显式 base"

本地页面和 NAS 不同源，必须显式指定：

```js
// App 注入（在页面脚本执行前）
window.__PROOM_API__ = 'http://192.0.2.22:8899';
```

`core/api.js` 里统一拼前缀；没有注入时（浏览器直接访问）留空，保持同源行为，**不破坏现有网页端**。

### 1.4 离线数据：stale-while-revalidate

缓存到 IndexedDB：

| key | 内容 | 用途 |
|---|---|---|
| `home` | 首页卡片、继续观看 | 离线首页 |
| `library` | 片库列表（分类、分页第 1 页） | 离线浏览 |
| `detail:<name>` | 最近打开的 30 部详情 | 离线看简介/选集 |
| `cfg` | 片源/直播配置的只读快照 | 离线设置页可看 |

策略：

```
渲染先用缓存（0ms 出画面） → 同时发请求 → 成功则更新缓存并重渲染 → 失败静默
```

### 1.5 离线时能播什么（必须说清）

| 场景 | 能否播 |
|---|---|
| 上次看过的剧，直链还有效 | ✅ |
| 源需要特定 Referer | ⚠️ 大概率 403（没有 NAS 代理兜底） |
| 直播源 | ⚠️ 看源是否公开 |
| 本地音乐（NAS 上的文件） | ❌ NAS 关机文件就没了 |

真要提升离线播放成功率，得在手机上跑一个**本地小代理**（补 Referer/UA），那是 P4 的事。

### 1.6 验收

- [ ] 断电 NAS → 冷启动 App，3 秒内出首页（带缓存内容）+「离线」横幅
- [ ] 恢复 NAS → 10 秒内横幅消失，下次冷启动前端已更新
- [ ] 网页端（浏览器直接打开 NAS）行为完全不变

---

## 2. 方案 B：离线投屏（DLNA）

### 2.1 为什么只能走原生

浏览器 / WebView **没有 UDP 组播**，做不了 SSDP 发现。必须在 Kotlin 侧实现，再通过桥暴露给页面。

### 2.2 发现（SSDP）

```
M-SEARCH * HTTP/1.1
HOST: 239.255.255.250:1900
MAN: "ssdp:discover"
MX: 2
ST: urn:schemas-upnp-org:device:MediaRenderer:1
```

收到回包后：

1. 取 `LOCATION` → 拉 device description XML；
2. 找到 `<serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType>` 的 `controlURL`；
3. 拼上 base URL，得到可调用的地址。

### 2.3 推送（AVTransport）

```xml
POST {controlURL}  SOAPACTION: "urn:schemas-upnp-org:service:AVTransport:1#SetAVTransportURI"
<s:Envelope><s:Body><u:SetAVTransportURI>
  <InstanceID>0</InstanceID>
  <CurrentURI>{m3u8 直链}</CurrentURI>
  <CurrentURIMetaData>{DIDL-Lite：标题、类型 video</CurrentURIMetaData>
</u:SetAVTransportURI></s:Body></s:Envelope>
```

再发一次 `Play`（`Speed=1`）。可选：`Seek` 到指定秒数。

### 2.4 桥接口

```kotlin
@JavascriptInterface fun discover()                     // 返回 JSON 数组
@JavascriptInterface fun play(controlUrl, url, title)   // 返回 ok/err
```

页面侧：投屏面板分两组显示

```
── 局域网电视（NAS 中转）──   ← 功能全：线路、选集、续播
   电视
── 标准投屏（DLNA）──        ← 单条 URL
   创维 A5F Mini
```

NAS 不可达时只显示第二组。

### 2.5 限制（不藏）

| 限制 | 影响 |
|---|---|
| 只能推单条 URL | 电视上没有选集/线路，换集要回手机 |
| 无法回传进度 | 手机不知道电视播到哪 |
| Referer 依赖的源 | 电视直连可能 403，成功率低于 NAS 中转 |
| 电视不支持 HLS | 少数老机型播不了 m3u8 |

因此 DLNA **是兜底，不是替代**。

### 2.6 验收

- [ ] NAS 关机，手机打开投屏 → 「标准投屏」列出电视 → 点击 → 电视开始播放
- [ ] NAS 在线时，两组都在，默认优先"局域网电视"

---

## 3. 分阶段实施

| 阶段 | 内容 | 估时 | 交付 |
|---|---|---|---|
| **P1** | 前端本地化：assets 种子 + manifest 增量更新 + AssetLoader 加载 + API base 注入 | 2 天 | NAS 挂了能打开界面 |
| **P2** | 离线数据：IndexedDB 缓存 + stale-while-revalidate + 离线横幅 | 1.5 天 | 离线能看到片库 |
| **P3** | 离线投屏：SSDP 发现 + AVTransport 推送 + 面板分组 | 2 天 | 离线可投单集 |
| **P4** | 打磨：本地代理补 Referer、进度同步、失败重试 | 按需 | 成功率提升 |

顺序说明：**P1 必须最先做**，否则 NAS 一关 App 都进不去，P3 的界面也没机会出现。

---

## 4. 需要改的文件

**服务端**
- `server/api.py`：新增 `/api/app/web/manifest`、`/api/app/web/file?path=`
- `tools/`：构建时生成 `web-manifest.json`

**Android**
- `MainActivity`：改用 `WebViewAssetLoader`，注入 `__PROOM_API__`
- 新增 `WebSync`（清单比对 + 下载 + 原子替换）
- 新增 `Dlna`（SSDP + SOAP）
- `NativeBridge`：暴露 `webVersion()`、`castDiscover()`、`castPlay()`

**前端**
- `core/api.js`：支持 `__PROOM_API__` 前缀
- 新增 `core/offline.js`：IndexedDB 缓存 + 离线横幅
- `core/cast.js`：投屏面板分组 + DLNA 降级

---

## 5. 风险

1. **更新把页面改坏**：必须保留上一版（`www.old`），启动失败 3 次自动回滚。
2. **版本错配**：前端和 NAS 接口必须兼容；manifest 里带 `minApp` 约束。
3. **DLNA 兼容性**：各品牌实现差异大（尤其国产电视），至少要测 2~3 个品牌。
4. **存储占用**：前端约 3~5MB，可接受；离线数据要设上限（建议 50MB）。

---

## 6. 一句话总结

- **本地页面**：把"NAS 现给页面"改成"客户端本地一份 + 增量同步"，NAS 关机也能进界面看缓存。
- **离线投屏**：NAS 挂了就走 DLNA 直连电视，功能降级为"只推当前这一集"。

---

# 附：方案对比与修订选型（2026-10-18）

上面第 1、2 节是**初版**。进一步比对后，有两个更好的做法，建议替换。

## 7. 页面本地化：四个方案

| 方案 | 做法 | 优点 | 缺点 |
|---|---|---|---|
| **A1** 原方案 | `WebViewAssetLoader` 映射到 `https://appassets...` | 改动最小，无额外进程 | 页面成了特例源；离线海报要另做；**SW 仍不可用** |
| **A2** 本地 HTTP 服务 ⭐ | App 内起服务绑 `127.0.0.1:随机端口`，WebView 打开 `http://127.0.0.1:PORT/` | 见下 | 多一个线程/端口；**换源会丢一次登录态** |
| A3 | 前端只随 APK 发版 | 最简单 | 改一行 CSS 都要发版（我们现在一天改好几次） |
| A4 | NAS 上 HTTPS + Service Worker | 桌面浏览器也能离线 | 内网 IP 没有有效证书，WebView 要忽略证书错误，反而更不安全 |

### 为什么 A2 更好

1. **同源**：页面、接口、媒体全在一个 origin，不存在 CORS，也不需要 `WebViewAssetLoader` 这种特例域；
2. **`http://127.0.0.1` 属于安全上下文** → Service Worker 可用，缓存策略可以走标准做法，**桌面浏览器端也能复用**；
3. **顺手解决离线播放的老大难**：本地服务可以直接做**媒体反代**（补 `Referer`/`UA`），
   等于把 NAS 上 `proxy.py` 的能力搬到手机本地 —— 这正是初版 P4 里最难的一块；
4. 排查方便，所有请求都经过自己的服务，可打日志。

### A2 的代价与对策

| 代价 | 对策 |
|---|---|
| 换 origin 导致 `localStorage` 里的登录态失效一次 | token 存进原生 SharedPreferences，启动时注入 `window.__PROOM_TOKEN__` |
| 常驻一个端口 | 只绑 127.0.0.1、随机端口、随 Activity 生命周期启停 |
| 多一层转发（多几毫秒） | 本地文件直接读磁盘，只有 `/api/*` 转发，开销可忽略 |

## 8. 离线投屏：四个方案

| 方案 | 功能完整度 | 依赖 | 结论 |
|---|---|---|---|
| **B1** NAS 中转（现状） | 满血：线路 / 选集 / 进度 | NAS 必须在线 | 保留为在线时的首选 |
| **B2** 手机直连电视 App ⭐ | **满血**（传完整 item） | 电视装了我们的 App | **新增，离线首选** |
| B3 DLNA | 单条 URL | 电视支持 DLNA（基本都有） | 降级兜底 |
| B4 Google Cast | 中等 | 需要 Cast SDK + 电视支持（国内少） | 放弃 |

### 为什么 B2 比 DLNA 好

我们两端都是自己的 App，没必要迁就 DLNA 的单 URL 限制：

```
手机 ──UDP 广播 PROOM_DISCOVER──▶ 电视（复用现有 Discovery.kt）
     ◀── PROOM_OFFER http://<tv-ip>:<port> ──
手机 ──POST /cast {item, si, ei, position}──▶ 电视本地服务
                                          └─ 桥调 JS 直接 openPlayer
```

- **功能不打折**：线路、选集、续播都能带过去；
- **不依赖 NAS**，也不依赖电视的 DLNA 兼容性；
- 电视端已经有 `PairServer` 的雏形（配地址用的那个），扩一个 `/cast` 即可；
- DLNA 保留给「电视没装我们的 App」的情况。

## 9. 修订后的分阶段

| 阶段 | 内容 | 估时 | 相对初版 |
|---|---|---|---|
| **P1** | A2：本地 HTTP 服务 + assets 种子 + token 原生注入 + 离线横幅 | 2 天 | 替换原 A1 |
| **P2** | 离线数据（IndexedDB，stale-while-revalidate） | 1.5 天 | 不变 |
| **P3** | B2：电视端 `/cast` 端点 + 手机端发现与推送 | 1.5 天 | **新增，比原 P3 更简单** |
| **P4** | B3：DLNA 兜底（给非本 App 的电视） | 1 天 | 降级为可选 |
| **P5** | 本地媒体反代（补 Referer），提升离线播放成功率 | 1.5 天 | 从原 P4 提前，因 A2 已铺好路 |

总估时比初版少约 1 天，且离线播放成功率上限更高（因为有了本地反代）。
