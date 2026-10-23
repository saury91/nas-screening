# 离线方案（已被取代）

> 这份文档描述的是**上一代 WebView 壳**的离线方案：APK 内嵌 `static/` 页面、
> 壳内起本地 HTTP 服务、投屏走「手机直连电视」。
>
> 那个壳已经删掉了。现在的原生端是 Compose + 离线优先的本地主库，架构见
> [`standalone-arch.md`](./standalone-arch.md)，离线能力的现状见
> [`../native/README.md`](../native/README.md)。

## 为什么不再适用

| 旧方案 | 现在 |
|---|---|
| WebView 加载打进 APK 的前端页面 | Compose 原生页面，不加载网页 |
| 壳内 HTTP 服务提供静态资源 | 无本地服务；配置读本地 `config/bundle.json` |
| 前端直连 NAS 取数据 | 搜索/详情/播放直连采集站，NAS 只做同步/投射/更新/配置 |
| 投屏靠手机直连电视 | `CastClient` 三通道（NAS 中转 / 局域网直投 / DLNA） |

历史内容留在 git 记录里，需要考古时用 `git log -- docs/offline-plan.md`。
