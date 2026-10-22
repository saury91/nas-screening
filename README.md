# 放映室 · Private Screening

**中文** · [English](README.en.md)

把 NAS 上的影视、电视直播、音乐收进同一个界面，同一份前端可运行在浏览器、手机和电视上。

- 当前版本 **0.13.0**（Android TV / 手机 / Web 三端同源）
- **零内置内容源**：不带任何片源、直播列表、音乐接口，装完是空壳，配置由你自己提供
- 零构建前端，`python3 app.py` 即开即用，不需要 pip、Node、打包
- NAS 关机后 App 仍可独立搜索、看详情、播放

---

## 目录

- [功能](#功能)
  - [影视](#影视)
  - [电视直播](#电视直播)
  - [音乐](#音乐)
  - [播放器](#播放器)
  - [投屏](#投屏)
  - [管理与备份](#管理与备份)
  - [独立运行](#独立运行)
- [三端](#三端)
- [截图](#截图)
- [运行方式](#运行方式)
- [快速开始](#快速开始)
  - [第一次打开：空壳是正常的](#第一次打开空壳是正常的)
  - [数据源自备](#数据源自备)
- [运行参数](#运行参数)
- [项目结构](#项目结构)
- [开发与测试](#开发与测试)
- [版权与许可](#版权与许可)

---

## 功能

### 影视

| 能力 | 说明 |
|---|---|
| 聚合搜索 | 一次同时问全部片源，同一部片自动合并，列出全部线路 |
| 多线路播放 | 每条线路标出画质（4K / 1080P / 720P / HD），播不动自动换下一条 |
| 剧集连载 | 按集排列，换线路不丢进度，一集播完自动接下一集 |
| 片库浏览 | 电影 / 剧集 / 动漫 / 综艺 / 纪录片分栏，按年份、地区、单源筛选 |
| 片源管理 | TVBox 配置地址一键导入、优先级排序、单个测试、开关（Web） |
| 下载 | 接自己的 qBittorrent，磁力搜索结果带做种数，一键提交（Web） |

### 电视直播

| 能力 | 说明 |
|---|---|
| 导入与分组 | M3U / TXT 导入，自动分组，同名频道合并多线路 |
| 探活 | 一键检测，失效频道标记保留、不自动删除 |
| 每日汇总 | 每日自动抓取候选源，合并去重并探活，只保留可播放频道 |
| 维护 | 分组查看、手动刷新、批量探活（Web） |

### 音乐

| 能力 | 说明 |
|---|---|
| 曲库 | 本地目录 ID3 / FLAC、Subsonic 兼容服务（Navidrome / Airsonic / Gonic）、在线歌单 |
| 发现 | 推荐歌单、分类歌单（主题 · 语种 · 风格 · 场景 · 情感）、专辑墙、歌手、在线搜歌 |
| 播放 | 队列、循环模式、滚动歌词、粒子进度条、播放模式记忆 |
| 音效 | Web 是 10 段均衡器 + 8 组预设；电视的音效预设按设备频段自动适配 |

三端均提供音乐播放：手机为黑胶唱片 + 逐行歌词，播放条常驻；电视为左唱片右歌词，遥控器上下切歌、左右调进度。

### 播放器

| 能力 | 说明 |
|---|---|
| 手势与遥控器 | 单击暂停 / 播放，双击全屏；电视用方向键移动焦点、回车确认、BACK 返回 |
| 断点续播 | 进度每几秒记一次，下次从停下的地方继续 |
| 缓冲态 | 模糊海报 + 三个跳动波点 + 实时网速，不转圈 |
| 画中画 | 开小窗可继续浏览、搜索、换片，关窗即结束 |

### 投屏

手机发起投屏，电视原地播放，手机随即变成遥控器。电视为接收端，进度真实回报，不镜像手机画面。

三种连接方式，按可用性自动选择，通道不支持的操作置灰：

| 通道 | 怎么连 | 能干什么 |
|---|---|---|
| NAS 中转 | 两端连接同一台 NAS | 全部功能：倍速、音量、进度回读；跨网段可用 |
| 局域网直投 | 同一局域网自动发现 | 全部功能；不经过 NAS，NAS 关机也能用 |
| DLNA | 电视作为标准 DLNA 接收端 | 仅播放 / 暂停 / 拖动 / 停止，倍速与音量不可用，不回报进度 |

局域网自动发现被路由拦截时，可扫码直连（电视显示二维码与配对码）或手动填写地址。

### 管理与备份

| 能力 | 说明 |
|---|---|
| 配置备份 | 导出完整配置或只要片源；导入有预览，写前自动备份，可回滚 |
| 配置分享 | 片源、直播源、音乐与界面偏好打包成一个 JSON，可导出导入 |
| 配置分发 | NAS 只发配置包，App 启动后台静默更新，内容没变就不重绘 |
| 下载任务 | 暂停 / 继续 / 删除，状态自动刷新 |
| 关于与自检 | 后端连通性、片源可用性、版本信息一览 |
| 内网自升级 | App 启动时检查新版本，提示并拉起系统安装器 |

### 独立运行

| 能力 | 说明 |
|---|---|
| App 独立运行 | NAS 关机也能搜、看、播，配置停在最后一次同步的版本 |
| 无账户 | 打开即用，没有登录、没有 PIN（`NO_AUTH=1` 默认） |
| 本地缓存 | 页面数据与影片索引存本地，先渲染再拉新，界面不跳 |
| 零第三方依赖 | 前端零构建、字体自托管、不引用任何 CDN |

---

## 三端

| | PC 浏览器 | 手机 App | 电视 App |
|---|---|---|---|
| 包名 | —（浏览器直接访问） | `com.projectionroom.app` | `com.projectionroom.tv` |
| 定位 | **管理台 + 消费端** | **随身消费端** | **大屏消费端** |
| 输入 | 键鼠 | 触摸 | 遥控器 |
| 浏览 / 搜索 / 播放 | ✅ | ✅ | ✅ |
| 收藏 / 播放记录 | ✅ | ✅ | ✅ |
| 音乐 | ✅ 完整 | ✅ 完整 | ✅ 完整（含音效预设） |
| 投屏 | 接收端控制 | ✅ 发射端 | 接收端 |
| 片源 / 直播源 / 下载 / 备份 | ✅ 全部入口 | ❌ 不提供 | ❌ 不提供 |

### PC 浏览器（管理台）

打开 `http://<nas>:8899` 就是最新版，不装任何东西。

- **全部**：影视、直播、音乐、搜索、播放、投屏
- **管理一切**：片源导入与排序、直播源维护、下载任务、配置备份
- 自适应版心，375–3840 宽度全覆盖

![首页](docs/screenshot-home.png)

### 手机 App

消费端。不提供配置管理，修改服务器数据请在 PC 控制台操作。

- 底部四个 tab：影视 / 直播 / 音乐 / 我的
- 「我的」把收藏和播放记录合并在一起，一屏看完
- 播放页同一套手势：单击暂停、双击全屏
- 投屏时手机就是遥控器：进度、音量、倍速、选集、换源全在手上

<p>
<img src="docs/screenshot-app-phone.png" width="240" alt="手机首页">
<img src="docs/screenshot-app-phone-detail.png" width="240" alt="手机详情">
<img src="docs/screenshot-app-phone-mine.png" width="240" alt="手机我的">
</p>

### 电视 App

遥控器操作，10-foot 界面：

- 返回只使用遥控器 BACK 键，页面不设可聚焦的返回按钮
- 初始焦点落在第一个可操作内容（首张卡片或主按钮）上
- 不提供锁屏、画中画、投屏入口
- 氛围背景在 Android 8.0 及以上表现一致

<p>
<img src="docs/screenshot-app-tv.png" width="380" alt="电视首页">
<img src="docs/screenshot-app-tv-detail.png" width="380" alt="电视详情">
</p>

---

## 截图

### 影视截图

一次搜索同时问所有片源，同一部片自动合并，列出全部线路。

![搜索](docs/screenshot-search.png)

详情页显示简介、导演、主演、评分，以及每条线路的画质。

![详情页](docs/screenshot-detail.png)

手机端是同一套逻辑竖屏重排，热门词一点即搜：

<p>
<img src="docs/screenshot-app-phone-search.png" width="240" alt="手机搜索">
</p>

### 电视直播截图

导入直播源后自动分组，一键检测可用，每天自动汇总探活。

![直播](docs/screenshot-live.png)

手机与电视共用同一份频道表：手机为三列大卡（409 个频道 · 40 组），电视为遥控器可直接落焦的频道格。

<p>
<img src="docs/screenshot-app-phone-live.png" width="240" alt="手机直播">
<img src="docs/screenshot-app-tv-live.png" width="480" alt="电视直播">
</p>

### 音乐截图

三端共用同一份曲库，「推荐歌单 + 分类歌单」结构一致，播放器各自适配屏幕。

**PC**：推荐歌单点开是独立详情页，一键「播放全部」；底部播放条带波形进度、循环模式与队列，点开是完整播放器，10 段均衡器 + 8 组预设。

![音乐](docs/screenshot-music.png)

![歌单详情](docs/screenshot-playlist.png)

![音乐播放器](docs/screenshot-music-player.png)

**手机**：黑胶旋转 + 逐行歌词，播放条常驻在任意 tab 底部——切去直播看球也不打断音乐。

<p>
<img src="docs/screenshot-app-phone-music.png" width="240" alt="手机音乐">
<img src="docs/screenshot-app-phone-playlist.png" width="240" alt="手机歌单">
<img src="docs/screenshot-app-phone-music-player.png" width="240" alt="手机音乐播放器">
</p>

**电视**：10-foot 版，左边黑胶右边歌词，遥控器上下切歌、左右调进度；音效预设按设备频段自动适配。

<p>
<img src="docs/screenshot-app-tv-music.png" width="380" alt="电视音乐">
<img src="docs/screenshot-app-tv-music-player.png" width="380" alt="电视音乐播放器">
</p>

![音效预设](docs/screenshot-app-tv-eq.png)

### 播放器截图

单击画面暂停 / 播放，双击全屏；缓冲时是模糊海报 + 波点 + 实时网速。

![播放器](docs/screenshot-player.png)

### 投屏截图

手机发起投屏，电视原地播放并回报进度，不镜像手机画面。

![投屏设备](docs/screenshot-app-phone-cast.png)

局域网发现被拦截时可用扫码配对：电视显示二维码与配对码，手机扫一次即可。

![电视端配对](docs/screenshot-app-tv-pair.png)

手机端遥控器：进度、±10s、倍速、音量、选集、换源全在手上。

<p>
<img src="docs/screenshot-app-phone-remote.png" width="300" alt="投屏遥控器">
</p>

### 管理与备份截图

片源、直播源、音乐与界面偏好打包为 JSON；导入有预览，导错可回滚。

![配置备份](docs/screenshot-backup.png)

---

## 运行方式

NAS 负责配置分发与版本更新，搜索与播放由客户端直连源站。

- **NAS 关机**：App 仍可搜索、查看详情、播放，配置停留在此前同步的版本
- **NAS 运行**：启动后后台更新配置，内容无变化时不重绘界面
- **PC**：打开 `http://<nas>:8899` 即为最新版，无需安装

详细设计见 [`docs/standalone-arch.md`](docs/standalone-arch.md)。

---

## 快速开始

### 直接运行

```bash
git clone https://github.com/saury91/nas-screening.git
cd nas-screening
python3 app.py
```

打开 `http://127.0.0.1:8899`。首次启动自动建库，首页推荐需十几秒预热。

### Docker

```bash
docker build -t projection-room .

docker run -d --name projection-room --restart unless-stopped --network host \
  -e DB_PATH=/data/media.db \
  -v /你的数据目录:/data \
  projection-room
```

接 qBittorrent 和本地音乐目录：

```bash
docker run -d --name projection-room --restart unless-stopped --network host \
  -e DB_PATH=/data/media.db \
  -e QBT_URL=http://127.0.0.1:8080 \
  -e QBT_USER=<用户名> \
  -e QBT_PASS=<密码> \
  -e MUSIC_DIR=/music \
  -v /你的数据目录:/data \
  -v /你的音乐目录:/music:ro \
  projection-room
```

### 第一次打开：空壳是正常的

安装包不含任何内容源：没有片源、没有频道、没有音乐接口。首次打开显示「还没有片源」属正常现象。

按以下顺序配置：

1. **不要把 8899 直接暴露到公网**：默认无鉴权，只应是内网服务；要外网访问请加反向代理鉴权。
2. **导入片源**：控制台 → 片源管理。粘贴一份 TVBox 配置地址批量导入（见下面的「数据源自备」）。
3. **导入直播**：控制台 → 直播源。填候选源地址，之后每天自动重新抓取、合并、探活。
4. **接音乐**（可选）：本地目录或 Navidrome / Airsonic / Gonic；在线歌单与在线搜歌需要自己填接口。

### 数据源自备

仓库里没有任何采集站地址、直播列表、音乐接口。三种方式把自己的数据装进来：

| 方式 | 做法 | 适合 |
|---|---|---|
| 配置包 | 控制台 → 配置备份 → 导入，选一个 JSON | 从别的机器搬过来 |
| 手动填 | 片源管理里逐条加，或粘 TVBox 配置地址 | 只加几个 |
| seed 文件 | 把导出的 `seed.json` 放到 `data/seed.json` 再启动 | 一次装好整套 |

`seed.json` 与配置包是同一份格式：

```bash
# 从当前库导出（里面是真实地址，只留在本地）
python3 tools/export-seed.py                 # → data/seed.json

# 换台机器：把文件拷过去，放在同样位置，启动即导入
DB_PATH=/data/media.db SEED_FILE=/data/seed.json python3 app.py
```

### 装到电视 / 手机

```bash
cd native
./gradlew :app-tv:assembleDebug      # 电视：native/app-tv/build/outputs/apk/debug/
./gradlew :app-phone:assembleDebug   # 手机：native/app-phone/build/outputs/apk/debug/
adb install -r <apk 路径>
```

JDK 17、compileSdk 35。

**签名与升级**：APK 使用固定密钥签名（`native/keystore/proom.jks`，密码 `proom-release`）。
覆盖安装要求签名一致，因此密钥随仓库提供，保证任意构建产物可相互升级。
密钥为公开信息，不提供安全强度；需要独立签名时用 `PROOM_KEYSTORE`、`PROOM_KEYSTORE_PASS` 替换。

TV 第一次装需要允许「安装未知应用」；国产 ROM 会弹「未备案/风险应用」提示，勾选「已了解风险」继续即可。

---

## 运行参数

| 变量 | 默认值 | 说明 |
|---|---|---|
| `PORT` | `8899` | 监听端口 |
| `DB_PATH` | `./data/media.db` | 数据文件位置，容器里建议指向挂载卷 |
| `SEED_FILE` | `./data/seed.json` | 启动时导入的数据文件；不存在就空启动 |
| `LIVE_SEEDS` | 空 | 直播候选源，逗号分隔，每日自动汇总用 |
| `CMS_SEEDS` | 空 | 片源「资源目录」地址，逗号分隔，自动发现用 |
| `MUSIC_ONLINE` | `0` | 为 `1` 时启用在线搜歌（直连第三方音乐接口） |
| `MUSICBOX_API` | 空 | 在线歌单/播放接口地址，空即关闭在线音乐 |
| `MUSICBOX_REFERER` | 空 | 在线歌单接口要求的 Referer |
| `MAGNET_SITE` | 空 | 磁力搜索站地址，空即关闭磁力搜索 |
| `MUSIC_DIR` | 空 | 本地音乐目录，也可稍后在设置页填写 |
| `QBT_URL` | `http://127.0.0.1:8080` | qBittorrent 地址 |
| `QBT_USER` / `QBT_PASS` | 空 | 仅通过环境变量传入，无默认凭据 |
| `NO_AUTH` | `1` | 为 `0` 时启用多账户（每人一份记录与收藏） |

标记「空即关闭」的项为内容入口，留空则不向第三方服务发起请求。

> 这是**内网**方案：没有密码、没有 HTTPS。不要把 8899 直接暴露到公网；
> 真要外网访问，请在反向代理上加一层真正的鉴权。

---

## 项目结构

```
app.py                  启动入口
server/
  api.py                路由与鉴权
  db.py                 SQLite 建表与迁移
  services/
    auth.py             账户（NO_AUTH=1 时不参与）
    config_io.py        配置包导出/导入（schema 版本 + 迁移）
    musicbox.py         在线歌单（推荐、分类、歌单详情）
    proxy.py            HLS 同源代理（含私网防护）
    catalog.py          采集站聚合：搜索 / 详情 / 片库
static/
  app/                  零构建前端：原生 ES Modules + 手写 CSS
  index.html            单页入口
native/                 Compose Multiplatform 客户端
  app-tv / app-phone    两个 App 壳（包名不同，可同机共存）
  core / data           数据层：聚合、解析、缓存
  ui-tv / ui-phone      两端各自的界面（规则不同，不共用）
  ui-kit / ui-media     共用组件与播放器
  keystore/             发布密钥（固定签名，见上文说明）
tools/                  account.py 救急命令、publish-apk.sh 发版、cdp.js 截图自检
tests/                  纯函数单测（不依赖网络与真实数据库）
docs/                   截图、配置包格式说明、审计记录
```

---

## 开发与测试

```bash
python3 -m unittest discover -s tests -v     # 纯函数单测
cd native && ./gradlew :core:jvmTest         # 数据层单测
cd native && ./gradlew :app-tv:assembleDebug # 编译即校验
```

CI 两个 workflow：

| 文件 | 触发 | 做什么 |
|---|---|---|
| `ci.yml` | push / PR | Python 编译 + 单测 + 前端 ESM 语法检查 |
| `native.yml` | `native/**` 变更 | 数据层单测 + 编译两个 debug APK，产物上传 Artifact |

发版走一条命令：

```bash
cd native && ./gradlew :app-tv:assembleRelease :app-phone:assembleRelease
cd .. && ./tools/publish-apk.sh tv && ./tools/publish-apk.sh phone
```

`publish-apk.sh` 会把 APK 和 `{versionCode, versionName, size, sha256, notes}` 一起写进
`data/apps/`，App 端会校验 size 与 sha256，坏包不会进安装器。

---

## 版权与许可

### 代码许可

本项目以 **MIT License** 发布，完整条款见仓库内 [`LICENSE`](LICENSE)。

```
Copyright (c) 2026 lsjkbz
```

简单说：你可以自由使用、修改、分发，包括商用；但必须保留版权声明与许可声明，且作者不为使用后果负责。

### 名称与界面

「放映室 / Private Screening」这个名称、界面设计、文案与图标为本项目原创，
在 MIT 条款下随代码一同授权。项目 Logo 与其他图形素材同样适用上述许可。

### 第三方组件

| 组件 | 许可 | 位置 |
|---|---|---|
| Inter | SIL Open Font License 1.1 | `static/fonts/` |
| Noto Sans SC | SIL Open Font License 1.1 | `static/fonts/` |
| Compose Multiplatform / AndroidX | Apache License 2.0 | 通过 Gradle 依赖引入 |
| Glide、OkHttp、kotlinx.serialization | Apache License 2.0 | 通过 Gradle 依赖引入 |

字体的授权原文与来源见 `static/fonts/README.md`。所有前端资源均自托管，不引用任何 CDN。

### 内容版权

**本项目不持有、不提供、不分发任何音视频内容，也不指向任何具体的内容提供方。**

- 仓库里**不存在**采集站地址、CMS 接口清单、直播源列表、音乐接口、磁力站域名——
  这些都在 `.gitignore` 覆盖的本地文件里，由使用者自己提供；
- APK 内置的配置包（`assets/config/bundle.json`）是空壳，不含任何地址；
- 影视、剧集、直播的**播放地址**在你配置之后，由你自己填写的第三方接口返回；
- 音乐来自你自建的 Navidrome / Airsonic / Gonic，或你自己填的在线接口；
- 仓库内不含任何音视频文件，也不含任何播放地址清单。

所有内容的著作权归各自权利人所有。你配置什么源、播放什么、从哪里播放，由你自行决定并自行负责。

项目中出现的第三方名称、商标、服务标识归各自所有者，仅用于说明兼容性，不构成任何关联或背书。

### 免责声明

**请在使用前完整阅读。开始使用即视为你已阅读、理解并同意以下全部内容。**

1. **本项目不提供任何内容。** 不存储、不托管、不转录、不分发、不销售任何影视、直播、音乐作品；仓库中不含任何音视频文件，也不含任何播放地址清单。

2. **内容全部来自第三方。** 搜索、详情、播放地址均由第三方公开接口返回，本项目仅做协议解析、界面展示与同源转发。第三方接口的内容、合法性、准确性、可用性，本项目无法控制也不作任何保证。

3. **与任何内容方无关。** 本项目与任何影视平台、电视台、版权方、采集站均无关联、无合作、无授权关系，也未获得任何内容授权。

4. **使用者自负全部责任。** 你需自行确认所在国家或地区的法律法规，并对自己安装、配置、访问、播放、下载的一切行为及其后果承担全部责任。因使用本项目引起的任何法律纠纷、版权索赔、行政处罚、数据丢失或设备损坏，作者与贡献者不承担任何责任。

5. **禁止商业与公开使用。** 严禁将本项目用于商业用途、收费服务、公开传播、二次分发，或部署为面向公众的公开站点。

6. **侵权处理方式。** 本项目不持有任何内容，无法删除第三方站点上的内容。若你认为某项内容侵权，请直接向该内容所在的站点主张权利；本项目不参与、也无法代为处理。

7. **不提供技术支持与可用性承诺。** 项目按「现状」提供，不保证任何接口长期可用、不保证播放质量、不保证与你的设备兼容。

8. **不同意请立即停止使用**，并删除本项目及其产生的全部数据。
