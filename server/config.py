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
# 如果确实有内网 IPTV 源（192.168.x.x:8080 之类），把它打开。
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
LIVE_SEEDS = [u.strip() for u in os.environ.get("LIVE_SEEDS", "").split(",") if u.strip()] or [
    "https://disabled.invalid/iptv-api/result.m3u",
    "https://raw.githubusercontent.com/YueChan/Live/main/GNTV.m3u",
    "https://disabled.invalid/raw.githubusercontent.com/Supprise0901/TVBox_live/main/live.txt",
]
# 一次汇总最多探测多少条地址：太多会拖很久，超出的留给下一次
LIVE_PROBE_LIMIT = int(os.environ.get("LIVE_PROBE_LIMIT", "1500"))

# 点播源自动发现：资源目录（JSON），默认用 awesome-zhuiju-free 的精选列表
CMS_SEEDS = [u.strip() for u in os.environ.get("CMS_SEEDS", "").split(",") if u.strip()] or [
    "https://raw.githubusercontent.com/laoma528/awesome-zhuiju-free/main/resources/resources.json",
]
CMS_AUTO_DAYS = int(os.environ.get("CMS_AUTO_DAYS", "7"))

# ── 在线音乐 ──────────────────────────────────────────────────
# 歌单推荐用的公开接口（网易云聚合）。只读第三方公开接口：不下载、不落盘。
# 接口对 Referer 有校验，转发音频时必须带上站点的 Referer。
MUSICBOX_API = os.environ.get("MUSICBOX_API", "https://disabled.invalid").rstrip("/")
MUSICBOX_REFERER = os.environ.get("MUSICBOX_REFERER", "https://disabled.invalid/")
MUSICBOX_SERVER = os.environ.get("MUSICBOX_SERVER", "netease")
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

# ── 内置片源种子 ──────────────────────────────────────────────
# 首次启动写入 sources 表；之后完全由用户在设置里增删改，代码不再回写。
DEFAULT_ONLINE_SITES = [
    {"name": "暴风资源", "api": "https://disabled.invalid/api.php/provide/vod/", "pri": 1},
    {"name": "量子资源", "api": "https://disabled.invalid/api.php/provide/vod/", "pri": 2},
    {"name": "最大资源", "api": "https://disabled.invalid/api.php/provide/vod/", "pri": 3},
    {"name": "极速资源", "api": "https://disabled.invalid/api.php/provide/vod/", "pri": 4},
    {"name": "360资源", "api": "https://disabled.invalid/api.php/provide/vod/", "pri": 5},
    {"name": "猫眼资源", "api": "https://disabled.invalid/api.php/provide/vod/", "pri": 6},
    {"name": "电影天堂", "api": "https://disabled.invalid/api.php/provide/vod/", "pri": 7},
    {"name": "非凡资源", "api": "https://disabled.invalid/api.php/provide/vod/", "pri": 8},
    {"name": "新浪资源", "api": "https://disabled.invalid/removed.php/provide/vod/", "pri": 9},
    {"name": "光速资源", "api": "https://disabled.invalid/api.php/provide/vod/", "pri": 10},
    {"name": "金鹰资源", "api": "https://disabled.invalid/provide/vod/", "pri": 11},
    {"name": "红牛资源", "api": "https://disabled.invalid/api.php/provide/vod/", "pri": 12},
    {"name": "虎牙资源", "api": "https://disabled.invalid/api.php/provide/vod/", "pri": 13},
]

# 直播源种子（运营商专网源在普通宽带不可达，所以只放公开聚合列表）
DEFAULT_LIVE_SOURCES = [
    ("gao/list.txt", "https://raw.githubusercontent.com/gaotianliuyun/gao/master/list.txt"),
    ("YanG/Gather.m3u", "https://raw.githubusercontent.com/YanG-1989/m3u/main/Gather.m3u"),
]

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
