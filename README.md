# 放映室 · NAS 个人娱乐中心

一个跑在 NAS 上的自托管影视聚合入口。**只用 Python 标准库**，无第三方依赖，单文件即可运行。

聚合公开的苹果 CMS 采集接口，把「搜索 → 挑源 → 播放」压缩成一步：搜到片名直接点开就看，不用下载、不用等待。

---

## 功能

**在线观影**
- 聚合 13 个公开采集源，一次搜索同时问所有站，按片名合并去重，同一部片列出全部可用线路
- 每个源站会标注集数与推荐度，播不动可以随时切线路
- 详情页含简介、导演、主演、评分、地区、语言；演职员可点击，直接搜这个人的作品
- 片库浏览：按类型（电影/剧集/动漫/综艺/纪录片）和年份翻，不必先想好片名
- 搜索框下拉显示「最近搜过」和「热门」，数据来自本地搜索记录

**播放器**
- 左侧播放器 + 右侧边栏：线路切换、选集网格、上/下集导航都在同一屏，随时可返回
- 基于 ArtPlayer + hls.js，支持 m3u8 直连串流（采集源多带 CORS 头，浏览器可直连，不需要后端代理）
- 记录播放进度，下次从「继续观看」直接续播

**直播**
- 支持导入 M3U / TXT 直播源，自动按分组归类

**BT 下载（可选）**
- 接 qBittorrent WebUI，搜索结果带做种数预览，避免下到死种
- 磁力链接一键提交，任务状态实时刷新

**本地数据**
- SQLite 存观看历史、收藏、搜索统计；搜索记录还会用来生成首页口味推荐
- 推荐位按电影/剧集/动漫等分类展示，有缓存兜底，冷启动不白屏

---

## 快速开始

### 本地直接跑

```bash
git clone https://github.com/saury91/nas-screening.git
cd nas-screening
python3 app.py
```

打开 `http://127.0.0.1:8899`。不需要装任何依赖，数据库会自动建在 `./data/media.db`。

> 静态资源压缩版（`static/*.gz`）没有提交，首次访问会自动回退到未压缩版本，功能不受影响。
> 想要更快的首屏可以自己生成：
> ```bash
> gzip -9 -k static/hls.js static/artplayer.js
> ```

### Docker

```bash
docker build -t nas-screening .

docker run -d --name media-tool --restart unless-stopped --network host \
  -e DB_PATH=/data/media.db \
  -v /你的数据目录:/data \
  nas-screening
```

需要 BT 下载功能时，再补上 qBittorrent 的连接信息：

```bash
docker run -d --name media-tool --restart unless-stopped --network host \
  -e QBT_URL=http://127.0.0.1:8080 \
  -e QBT_USER=<你的用户名> \
  -e QBT_PASS=<你的密码> \
  -e DB_PATH=/data/media.db \
  -v /你的数据目录:/data \
  nas-screening
```

---

## 环境变量

| 变量 | 默认值 | 说明 |
|---|---|---|
| `PORT` | `8899` | 监听端口 |
| `DB_PATH` | `./data/media.db` | SQLite 路径。容器里建议用 `-e DB_PATH=/data/media.db` 指向挂载卷 |
| `STATIC_DIR` | `./static` | 静态资源目录 |
| `QBT_URL` | `http://127.0.0.1:8080` | qBittorrent 地址。不用下载功能可不配 |
| `QBT_USER` | 空 | qBittorrent 用户名。**必须通过环境变量传入** |
| `QBT_PASS` | 空 | qBittorrent 密码。**必须通过环境变量传入** |

账号密码一律走环境变量，代码里没有任何默认凭据。没配置时 `/api/status` 会明确告诉你缺什么，而不是只报一句「登录失败」。

---

## HTTP 接口

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/` | 网页界面 |
| GET | `/api/home` | 首页数据（历史/收藏/统计/推荐缓存） |
| GET | `/api/recommend?fresh=1` | 推荐（`fresh=1` 强制重建） |
| GET | `/api/suggest` | 搜索下拉数据（最近 + 热门） |
| GET | `/api/browse?cat=&year=&pg=` | 片库浏览 |
| GET | `/api/online?kw=` | 在线片源搜索 |
| GET | `/api/detail?n=&y=` | 按片名聚合成详情（含全部线路与选集） |
| GET | `/api/live` | 直播频道列表 |
| POST | `/api/live/add` | 导入直播源 |
| GET | `/api/magnets?kw=` | BT 资源搜索（带做种数） |
| POST | `/api/add` | 提交磁力到 qBittorrent |
| GET | `/api/tasks` | 下载任务列表 |
| POST | `/api/torrent` | 任务操作（暂停/继续/删除） |
| GET | `/api/status` | 自检 |

网页支持 URL 直接定位：`?v=home|browse|live|magnet|tasks|detail`，详情页用 `?v=detail&n=片名&y=年份`。

---

## 目录结构

```
.
├── app.py            # 全部后端 + 内嵌前端（单文件）
├── ui.html           # 前端源文件（改完需要合并进 app.py，见下）
├── Dockerfile
├── static/
│   ├── hls.js        # 本地化的 hls.js
│   └── artplayer.js  # 本地化的 ArtPlayer
└── .gitignore
```

`app.py` 里的 `PAGE = r"""..."""` 是前端页面，由 `ui.html` 合并生成。改前端的流程：

```bash
# 编辑 ui.html 后，把它合并回 app.py
python3 - <<'EOF'
s = open('ui.html', encoding='utf-8').read()
assert '"""' not in s
P = open('app.py', encoding='utf-8').read().split('\n')
i = next(k for k, l in enumerate(P) if l.startswith('PAGE = r"""<!DOCTYPE html>'))
j = next(k for k in range(i + 1, len(P)) if P[k].rstrip() == '"""')
open('app.py', 'w', encoding='utf-8').write(
    '\n'.join(P[:i] + ['PAGE = r"""' + s.rstrip('\n')] + P[j:]))
EOF
```

---

## 实现上的几个点

**跨站合并**：各采集源的字段完整度差很多（有的简介是扒来的英文），所以合并时简介优先取中文、评分取最大值；去重用「剥掉尾部年份的片名 + 年份」做 key，这样「某某」和「某某 2026」能合到一起，而「沙丘 1984」和「沙丘 2021」不会。

**分类 ID 不可信**：各家采集站的分类 ID 排法不同，而且会变（同一站今天 `t=20` 是电影、明天可能就空了）。所以不写死映射，而是探测一遍各站每个 ID 属于哪个大类、缓存一天，浏览时再按映射精准抓取。

**整体限时**：十几个源并发查询时，个别慢站会把整体拖到十几秒。改成到点就先用已返回的站，不等慢的。

**背景图自适应**：采集站的封面多是 270×399 这类竖版小图，直接铺成横幅等于放大数倍、糊成一片。所以按原图真实宽高比分档处理：横图保细节，竖图重模糊当氛围色底。

**毛玻璃只在浮层**：`backdrop-filter` 只用于导航、侧栏、弹层这类"悬浮在内容之上"的层。列表卡片一律不用——首页近百张卡片各叠一个模糊层会直接把滚动拖垮，而深色半透明背景在视觉上几乎一样。

**SQLite 多线程**：每请求一个线程，所以连接用 `threading.local()` 按线程持有，不能共用一个全局连接。

---

## 说明

- 本项目不自建、不托管、不存储任何影视内容，只是把公开的采集接口聚合起来做展示和播放。所有片源来自第三方，可用性和合法性由对应站点负责。
- 仅供个人在自己设备上学习和自用，请勿用于任何商业用途。
- BT 下载功能依赖你自己部署的 qBittorrent，请遵守当地法律法规。
