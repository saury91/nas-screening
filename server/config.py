# -*- coding: utf-8 -*-
"""全局配置。

所有可调项集中在这里，其它模块只读不写。
线上部署用环境变量覆盖，代码里不保留任何默认凭据。
"""
import os

# 项目根目录（app.py 所在目录）
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

PORT = int(os.environ.get("PORT", "8899"))
HOST = os.environ.get("HOST", "0.0.0.0")

# ── 数据与静态资源 ─────────────────────────────────────────────
DB_PATH = os.environ.get("DB_PATH") or os.path.join(ROOT, "data", "media.db")
STATIC = os.environ.get("STATIC_DIR") or os.path.join(ROOT, "static")
MUSIC_DIR = os.environ.get("MUSIC_DIR", "")

# ── 账户模式 ─────────────────────────────────────────────────
# 局域网自用：不需要账户与 PIN。NAS 只作为「配置分发 + UI 升级」的服务器，
# 手机/电视 App 与浏览器都直接可用。
# 想恢复多账户：NO_AUTH=0 启动即可。
NO_AUTH = os.environ.get("NO_AUTH", "1").strip().lower() not in ("0", "false", "no", "off")

# ── qBittorrent ───────────────────────────────────────────────
QBT_URL = os.environ.get("QBT_URL", "http://127.0.0.1:8080")
QBT_USER = os.environ.get("QBT_USER", "")
QBT_PASS = os.environ.get("QBT_PASS", "")

# ── HLS 代理 ──────────────────────────────────────────────────
# 默认拒绝代理内网地址：/api/proxy?u= 是个公开入口，不设限就变成
# 「用 NAS 的网卡去访问你家路由器后台」的跳板。
# 如果确实有内网 IPTV 源（192.0.2.x:8080 之类），把它打开。
PROXY_ALLOW_PRIVATE = (os.environ.get("PROXY_ALLOW_PRIVATE", "").strip().lower()
                       in ("1", "true", "yes", "on"))

# ── 直播自动维护 ─────────────────────────────────────────────
# 每天固定钟点重新抓取直播源、探活并清理连续失效的频道。
# 关掉：LIVE_AUTO=0；改时间：LIVE_AUTO_HOUR=5（24 小时制）
LIVE_AUTO = os.environ.get("LIVE_AUTO", "1").strip().lower() not in ("0", "false", "no", "off")
LIVE_AUTO_HOUR = int(os.environ.get("LIVE_AUTO_HOUR", "4"))
LIVE_PRUNE_HITS = int(os.environ.get("LIVE_PRUNE_HITS", "3"))

# 每日自动汇总的候选源：可以理解成「订阅列表」，每天全部拉一遍再合并去重。
# 用逗号分隔覆盖：LIVE_SEEDS="https://a/x.m3u,https://b/y.txt"
# **默认空**：仓库不内置任何直播源，没配就不抓。
LIVE_SEEDS = [u.strip() for u in os.environ.get("LIVE_SEEDS", "").split(",") if u.strip()]
# 一次汇总最多探测多少条地址：太多会拖很久，超出的留给下一次
LIVE_PROBE_LIMIT = int(os.environ.get("LIVE_PROBE_LIMIT", "1500"))

# 点播源自动发现：资源目录（JSON）。
# **默认空**：不指定目录就不去「发现」，片源只能手动加或导入配置包。
CMS_SEEDS = [u.strip() for u in os.environ.get("CMS_SEEDS", "").split(",") if u.strip()]
CMS_AUTO_DAYS = int(os.environ.get("CMS_AUTO_DAYS", "7"))

# ── 在线音乐 ──────────────────────────────────────────────────
# 歌单推荐用的聚合接口。**默认空**：不填就不请求任何在线音乐服务，
# 音乐页只剩本地目录与 Subsonic。
# 接口对 Referer 有校验，转发音频时必须带上站点的 Referer。
MUSICBOX_API = os.environ.get("MUSICBOX_API", "").rstrip("/")
MUSICBOX_REFERER = os.environ.get("MUSICBOX_REFERER", "")
MUSICBOX_SERVER = os.environ.get("MUSICBOX_SERVER", "netease")

# 在线搜歌（直连第三方音乐平台的公开接口）。默认关闭：
# 仓库不自带、也不默认启用任何在线内容服务，需要就自己开。
MUSIC_ONLINE = os.environ.get("MUSIC_ONLINE", "0") == "1"

# 在线音源的端点。**一律空值**：仓库里不写任何真实地址，
# 谁要用谁在自己的 .env 里配，配齐才发请求，缺一项就当没开。
# 搜索接口：在 URL 后面追加查询参数，所以这里不要带问号。
ONLINE_SEARCH_URL = os.environ.get("ONLINE_SEARCH_URL", "")
# 播放地址转换接口：同样追加查询参数
ONLINE_PLAY_URL = os.environ.get("ONLINE_PLAY_URL", "")
# 封面前缀：搜索结果里的图片路径拼在它后面
ONLINE_COVER_BASE = os.environ.get("ONLINE_COVER_BASE", "").rstrip("/")
# 上游要求的 Referer，缺了会被拒
ONLINE_REFERER = os.environ.get("ONLINE_REFERER", "")

# 在线歌词（App 直连，随配置包下发）。同样默认空 = 不请求。
LYRIC_API = os.environ.get("LYRIC_API", "")
LYRIC_REFERER = os.environ.get("LYRIC_REFERER", "")

# ── BT 磁力搜索 ───────────────────────────────────────────────
# 站点地址由使用者自己填（MAGNET_SITE=https://...）。默认空 = 关闭，
# 仓库不指定、也不预置任何 BT 资源站。
MAGNET_SITE = os.environ.get("MAGNET_SITE", "").rstrip("/")
# 试听片段固定 30 秒、约 480KB；整曲最短也在 1.5MB 以上，用它来判「能不能完整播」
MUSIC_FULL_MIN = int(os.environ.get("MUSIC_FULL_MIN", str(1200 * 1024)))

# ── 抓取参数 ──────────────────────────────────────────────────
UA = ("Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) "
      "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36")
TIMEOUT = 20
MAX_DETAIL = 14

# 内容大类。顺序即前端导航顺序
CAT_ORDER = ["电影", "剧集", "动漫", "综艺", "纪录片"]
CATEGORIES = CAT_ORDER
CAT_SLUG = {"movie": "电影", "series": "剧集", "anime": "动漫",
            "variety": "综艺", "doc": "纪录片"}
SLUG_CAT = {v: k for k, v in CAT_SLUG.items()}

# 十几个源并发查询时，个别慢站不能拖住整体：到点就用已返回的
SEARCH_DEADLINE = 7.0
BROWSE_DEADLINE = 9.0     # 片库一次要打 5 站 × 4 分类，给得比搜索宽一点
BROWSE_SITES = 5          # 浏览用几个源
CATMAP_TTL = 86400        # 分类 ID 映射缓存一天
RECOMMEND_TTL = 1800      # 推荐缓存 30 分钟

# 新装环境搜索框的兜底热词
FALLBACK_HOT = ["沙丘", "流浪地球", "奥本海默", "繁花", "漫长的季节",
                "狂飙", "三体", "宇宙探索编辑部", "怒海战记", "第二十条"]

# ── 片源种子：来自本地文件，不写死 ────────────────────────────
# 仓库不内置任何采集站、直播列表或音乐接口。首启是空壳，
# 由使用者自己在控制台里添加，或导入一份配置包。
#
# 想让自己已有的配置在新环境里自动装回去：用 tools/export-seed.py
# 从旧库导出 data/seed.json（已被 .gitignore 忽略），放到新环境的同一位置即可。
SEED_FILE = os.environ.get("SEED_FILE") or os.path.join(ROOT, "data", "seed.json")

# 磁力站资源
TRACKERS = [
    "udp://tracker.opentrackr.org:1337/announce",
    "udp://open.tracker.cl:1337/announce",
    "udp://tracker.openbittorrent.com:6969/announce",
    "udp://exodus.desync.com:6969/announce",
    "udp://tracker.torrent.eu.org:451/announce",
    "udp://open.stealth.si:80/announce",
    "udp://tracker.dler.org:6969/announce",
    "udp://explodie.org:6969/announce",
    "udp://tracker1.bt.moack.co.kr:80/announce",
]
