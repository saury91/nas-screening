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

# ── qBittorrent ───────────────────────────────────────────────
QBT_URL = os.environ.get("QBT_URL", "http://127.0.0.1:8080")
QBT_USER = os.environ.get("QBT_USER", "")
QBT_PASS = os.environ.get("QBT_PASS", "")

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
    ("gao/list.txt", "https://source.example.invalid/gaotianliuyun/gao/master/list.txt"),
    ("YanG/Gather.m3u", "https://source.example.invalid/YanG-1989/m3u/main/Gather.m3u"),
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
