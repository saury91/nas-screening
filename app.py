#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
NAS 影视下载助手 v2
搜片名 → 出结果（带做种数预览）→ 一键下载

只用 Python 标准库，无第三方依赖。
接口：
  GET  /                    网页界面
  GET  /api/search?kw=      搜索
  POST /api/add             添加下载
  GET  /api/tasks           任务列表
  GET  /api/torrent         任务文件列表
  POST /api/torrent         任务操作（暂停/继续/删除）
  GET  /api/status          自检
"""
import base64
import concurrent.futures
import gzip
import html as html_mod
import http.cookiejar
import json
import os
import re
import sqlite3
import sys
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
import zlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

# ───────────────────────── 配置 ─────────────────────────
# 全部从环境变量注入，仓库里不保留任何默认账号/密码。
# 部署时用 -e 传入，例如：
#   -e QBT_URL=http://<qbittorrent 地址>:8080 -e QBT_USER=<用户名> -e QBT_PASS=<密码>
_HERE    = os.path.dirname(os.path.abspath(__file__))
PORT     = int(os.environ.get("PORT", "8899"))
QBT_URL  = os.environ.get("QBT_URL", "http://127.0.0.1:8080")
QBT_USER = os.environ.get("QBT_USER", "")
QBT_PASS = os.environ.get("QBT_PASS", "")
# 路径默认跟着脚本走，这样 clone 下来直接 `python3 app.py` 也能跑；
# 容器里用 -e DB_PATH=/data/media.db 覆盖成挂载的数据卷
DB_PATH  = os.environ.get("DB_PATH") or os.path.join(_HERE, "data", "media.db")
STATIC   = os.environ.get("STATIC_DIR") or os.path.join(_HERE, "static")
UA       = ("Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) "
            "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36")
TIMEOUT    = 20
MAX_DETAIL = 14
CATEGORIES = ["电影", "剧集", "动漫"]
# 聚合十几个站时，不让个别慢站拖住整体：到点就把已回来的先用上
SEARCH_DEADLINE = 7.0


# ═══════════════════════ SQLite 数据层 ═══════════════════════
# 为高阶功能打底：播放历史、收藏、源配置、偏好、搜索统计（用于推荐）
_INIT_LOCK = threading.Lock()


def db_init():
    d = os.path.dirname(DB_PATH)
    if d and not os.path.isdir(d):
        try:
            os.makedirs(d, exist_ok=True)
        except Exception:
            pass
    with _INIT_LOCK:
        con = sqlite3.connect(DB_PATH, timeout=15, check_same_thread=False)
        con.executescript("""
    PRAGMA journal_mode=WAL;
    CREATE TABLE IF NOT EXISTS history(
      id INTEGER PRIMARY KEY AUTOINCREMENT,
      kind TEXT NOT NULL, key TEXT NOT NULL,
      title TEXT, cover TEXT, source TEXT, ep TEXT, url TEXT,
      position REAL DEFAULT 0, duration REAL DEFAULT 0,
      plays INTEGER DEFAULT 1, genre TEXT, year TEXT, updated_at INTEGER,
      UNIQUE(kind, key));
    CREATE INDEX IF NOT EXISTS ix_hist_t ON history(updated_at DESC);

    CREATE TABLE IF NOT EXISTS fav(
      id INTEGER PRIMARY KEY AUTOINCREMENT,
      kind TEXT NOT NULL, key TEXT NOT NULL,
      title TEXT, cover TEXT, payload TEXT, added_at INTEGER,
      UNIQUE(kind, key));
    CREATE INDEX IF NOT EXISTS ix_fav_t ON fav(added_at DESC);

    CREATE TABLE IF NOT EXISTS sources(
      id INTEGER PRIMARY KEY AUTOINCREMENT,
      kind TEXT NOT NULL, name TEXT NOT NULL, url TEXT,
      enabled INTEGER DEFAULT 1, pri INTEGER DEFAULT 5,
      meta TEXT, added_at INTEGER,
      UNIQUE(kind, name));

    CREATE TABLE IF NOT EXISTS prefs(k TEXT PRIMARY KEY, v TEXT);

    CREATE TABLE IF NOT EXISTS searches(
      kw TEXT PRIMARY KEY, cnt INTEGER DEFAULT 1, last_at INTEGER);

    CREATE TABLE IF NOT EXISTS live(
      id INTEGER PRIMARY KEY AUTOINCREMENT,
      grp TEXT, name TEXT NOT NULL, url TEXT NOT NULL,
      src TEXT, added_at INTEGER,
      UNIQUE(name, url));

    CREATE TABLE IF NOT EXISTS cache(
      k TEXT PRIMARY KEY, val TEXT, ts INTEGER);
        """)
        con.commit()
    return con


# ThreadingHTTPServer 每请求一个线程，sqlite3 连接默认不能跨线程用。
# 所以每个线程各持一个连接（原来的全局单连接会直接 500）。
_TLS = threading.local()


def db():
    con = getattr(_TLS, "con", None)
    if con is None:
        con = db_init()
        _TLS.con = con
    return con


def dbrun(sql, args=(), fetch=False):
    for attempt in range(2):
        try:
            con = db()
            cur = con.execute(sql, args)
            rows = cur.fetchall() if fetch else None
            con.commit()
            return rows
        except Exception:
            try:
                _TLS.con = None
            except Exception:
                pass
            if attempt == 0:
                time.sleep(0.08)
    return [] if fetch else None


def log(msg):
    try:
        print("[%s] %s" % (time.strftime("%H:%M:%S"), msg), flush=True)
    except Exception:
        pass


# ───────────────────────── 缓存层 ─────────────────────────
# 首页/详情页的数据都来自十几个采集站，实时聚合要十几秒。
# 策略：内存缓存 + 落库持久化，容器重启后首页立刻有内容，绝不白板。
_CACHE = {}
_CACHE_LK = threading.Lock()


def cache_load():
    """启动时把库里的缓存读进内存（这样重启后首页不白板）"""
    try:
        rows = dbrun("SELECT k,val,ts FROM cache", fetch=True) or []
        n = 0
        for k, v, ts in rows:
            try:
                _CACHE[k] = (ts or 0, json.loads(v))
                n += 1
            except Exception:
                pass
        if n:
            log("缓存载入 %d 项" % n)
    except Exception:
        pass


def cache_peek(key):
    """不管过不过期都返回——首页用它先出画面"""
    with _CACHE_LK:
        e = _CACHE.get(key)
    return e[1] if e else None


def cache_age(key):
    with _CACHE_LK:
        e = _CACHE.get(key)
    return int(time.time() - e[0]) if e else None


def cache_put(key, data):
    try:
        body = json.dumps(data, ensure_ascii=False)
    except Exception:
        return
    with _CACHE_LK:
        _CACHE[key] = (int(time.time()), data)
    dbrun("INSERT OR REPLACE INTO cache(k,val,ts) VALUES(?,?,?)",
          (key, body, int(time.time())))


def cache_valid(key, ttl):
    a = cache_age(key)
    return a is not None and a < ttl


def note_search(kw):
    k = (kw or "").strip()
    if not k:
        return
    dbrun("""INSERT INTO searches(kw,cnt,last_at) VALUES(?,1,?)
             ON CONFLICT(kw) DO UPDATE SET cnt=cnt+1, last_at=excluded.last_at""",
          (k, int(time.time())))


def touch_history(kind, key, **kw):
    now = int(time.time())
    dbrun("""INSERT INTO history(kind,key,title,cover,source,ep,url,position,duration,
             genre,year,updated_at)
             VALUES(?,?,?,?,?,?,?,?,?,?,?,?)
             ON CONFLICT(kind,key) DO UPDATE SET
               title=COALESCE(excluded.title,history.title),
               cover=COALESCE(excluded.cover,history.cover),
               source=COALESCE(excluded.source,history.source),
               ep=COALESCE(excluded.ep,history.ep),
               url=COALESCE(excluded.url,history.url),
               position=excluded.position, duration=excluded.duration,
               genre=COALESCE(excluded.genre,history.genre),
               year=COALESCE(excluded.year,history.year),
               plays=history.plays+1, updated_at=excluded.updated_at""",
          (kind, key, kw.get("title"), kw.get("cover"), kw.get("source"),
           kw.get("ep"), kw.get("url"), float(kw.get("position") or 0),
           float(kw.get("duration") or 0), kw.get("genre"), kw.get("year"), now))


def toggle_fav(kind, key, title="", cover="", payload=""):
    rows = dbrun("SELECT id FROM fav WHERE kind=? AND key=?", (kind, key), fetch=True)
    if rows:
        dbrun("DELETE FROM fav WHERE kind=? AND key=?", (kind, key))
        return False
    dbrun("INSERT INTO fav(kind,key,title,cover,payload,added_at) VALUES(?,?,?,?,?,?)",
          (kind, key, title, cover, payload, int(time.time())))
    return True


# ═══════════════════════ 直播源（M3U / TXT）═══════════════════════
# 说明：运营商的 IPTV 源（ottrrs.hl.chinamobile.com 等）只在专网内可达，
# 普通宽带访问不通，所以这里做成「可自定义导入」，由用户自己贴能用的源。
DEFAULT_LIVE_SOURCES = [
    ("gao/list.txt", "https://source.example.invalid/gaotianliuyun/gao/master/list.txt"),
    ("YanG/Gather.m3u", "https://source.example.invalid/YanG-1989/m3u/main/Gather.m3u"),
]


def parse_live(text):
    """兼容两种常见格式：
       TXT : 频道名,http://xxx.m3u8   以及  分组名,#genre#
       M3U : #EXTINF:-1 tvg-name="xx" group-title="yy",频道名 \\n http://..."""
    out = []
    grp = "未分组"
    for line in (text or "").splitlines():
        s = line.strip()
        if not s:
            continue
        if s.endswith("#genre#"):
            grp = s[:-7].strip() or "未分组"
            continue
        if s.startswith("#EXTINF"):
            m = re.search(r'group-title="([^"]*)"', s)
            g = m.group(1).strip() if m and m.group(1).strip() else grp
            nm = s.split(",", 1)[1].strip() if "," in s else ""
            out.append(["meta", g, nm])
            continue
        m = re.match(r"^([^,#]+),\s*(https?://\S+)$", s)
        if m:
            out.append(["ch", grp, m.group(1).strip(), m.group(2).strip()])
            continue
        if s.startswith("http") and out and out[-1][0] == "meta":
            k = out.pop()
            out.append(["ch", k[1], k[2], s])
    chans = []
    for it in out:
        if it[0] == "ch":
            chans.append({"group": it[1][:20] or "未分组",
                          "name": it[2][:40], "url": it[3]})
    return chans


def refresh_live(src_name=None, url=None):
    """抓取一个直播源并写入库"""
    rows = []
    if url:
        rows = [(src_name or "自定义", url)]
    else:
        rows = dbrun("SELECT name,url FROM sources WHERE kind='live' AND enabled=1",
                     fetch=True) or DEFAULT_LIVE_SOURCES
    total = 0
    for nm, u in rows:
        try:
            st, body, _, _ = _raw(u, timeout=25)
            txt = body.decode("utf-8", "replace")
        except Exception:
            continue
        chans = parse_live(txt)
        for c in chans:
            dbrun("""INSERT OR IGNORE INTO live(grp,name,url,src,added_at)
                     VALUES(?,?,?,?,?)""",
                  (c["group"], c["name"], c["url"], nm, int(time.time())))
        total += len(chans)
    return total


def live_groups():
    rows = dbrun("""SELECT grp, COUNT(*) c FROM live GROUP BY grp
                    ORDER BY c DESC LIMIT 40""", fetch=True) or []
    return [{"group": r[0], "count": r[1]} for r in rows]


def live_list(grp=None, kw=None, limit=500):
    sql = "SELECT grp,name,url FROM live WHERE 1=1"
    args = []
    if grp:
        sql += " AND grp=?"
        args.append(grp)
    if kw:
        sql += " AND name LIKE ?"
        args.append("%" + kw + "%")
    sql += " ORDER BY grp,name LIMIT ?"
    args.append(limit)
    rows = dbrun(sql, args, fetch=True) or []
    # 同名频道合并源
    seen = {}
    order = []
    for g, n, u in rows:
        k = (g, n)
        if k not in seen:
            seen[k] = {"group": g, "name": n, "urls": []}
            order.append(k)
        if len(seen[k]["urls"]) < 6:
            seen[k]["urls"].append(u)
    return [seen[k] for k in order]


# ═══════════════════════ 推荐 ═══════════════════════
def _taste():
    """从搜索记录与观看历史里提取口味，返回关键词列表（按权重）"""
    words = []
    rows = dbrun("""SELECT kw FROM searches ORDER BY cnt DESC, last_at DESC LIMIT 8""",
                 fetch=True) or []
    words += [r[0] for r in rows]
    rows = dbrun("""SELECT DISTINCT genre FROM history
                    WHERE genre IS NOT NULL AND genre<>'' LIMIT 6""", fetch=True) or []
    words += [r[0] for r in rows]
    rows = dbrun("""SELECT DISTINCT title FROM history ORDER BY updated_at DESC LIMIT 6""",
                 fetch=True) or []
    words += [r[0] for r in rows]
    out, seen = [], set()
    for w in words:
        w = (w or "").strip()
        if len(w) < 2 or w in seen:
            continue
        seen.add(w)
        out.append(w)
    return out[:6]


def _mk_items(site, d):
    """把采集站返回的 list 转成统一结构（有播放地址才要）"""
    out = []
    for v in (d.get("list") or []):
        eps = _parse_play_url(v.get("vod_play_url"))
        if not eps:
            continue
        rec = {
            "name": (v.get("vod_name") or "").strip(),
            "year": str(v.get("vod_year") or "").strip(),
            "pic": (v.get("vod_pic") or "").strip(),
            "type": (v.get("type_name") or "").strip(),
            "site": site["name"],
            "eps": eps,
            "count": len(eps),
            "remarks": (v.get("vod_remarks") or "").strip(),
        }
        rec.update(_row_meta(v))
        out.append(rec)
    return out


def _qs(site, qs):
    """取某站某个查询串的结果"""
    url = site["api"] + "?" + qs
    try:
        req = urllib.request.Request(url, headers={
            "User-Agent": UA, "Accept": "application/json,text/plain,*/*"})
        with urllib.request.urlopen(req, timeout=12) as r:
            raw = r.read()
    except Exception:
        return []
    d = None
    for cs in ("utf-8", "gb18030"):
        try:
            d = json.loads(raw.decode(cs, "replace"))
            break
        except Exception:
            continue
    return _mk_items(site, d) if isinstance(d, dict) else []


def _by_type(sites, ts, workers=12, deadline=15):
    """按分类 ID 批量取。

    采集站首页那 20 条几乎全是剧集/综艺/动漫，电影（剧情片/动作片…）
    要按分类 ID 才拉得到。各家 ID 排法不一样，所以不猜 ID 含义，
    而是把拉回来的条目按 type_name 重新归类，怎么排都不会错。
    """
    out = []
    ex = concurrent.futures.ThreadPoolExecutor(max_workers=workers)
    try:
        futs = [ex.submit(_qs, s, "ac=detail&t=%d" % t)
                for s in sites for t in ts]
        done, _ = concurrent.futures.wait(futs, timeout=deadline)
        for f in done:
            try:
                out.extend(f.result())
            except Exception:
                pass
    finally:
        ex.shutdown(wait=False)
    return out


def _latest(site, hours=48, limit=18, pages=1):
    """取某站最近更新的条目。

    pages>1 时翻页多拉几批——采集站首页那 20 条几乎全是剧集/综艺/动漫，
    电影（剧情片/动作片…）要往后翻才有，否则「电影」分类永远只有零星几部。
    """
    def one(pg):
        url = site["api"] + "?ac=detail&h=%d&pg=%d" % (hours, pg)
        try:
            req = urllib.request.Request(url, headers={
                "User-Agent": UA, "Accept": "application/json,text/plain,*/*"})
            with urllib.request.urlopen(req, timeout=12) as r:
                raw = r.read()
        except Exception:
            return []
        d = None
        for cs in ("utf-8", "gb18030"):
            try:
                d = json.loads(raw.decode(cs, "replace"))
                break
            except Exception:
                continue
        if not isinstance(d, dict):
            return []
        out = []
        for v in (d.get("list") or []):
            eps = _parse_play_url(v.get("vod_play_url"))
            if not eps:
                continue
            rec = {
                "name": (v.get("vod_name") or "").strip(),
                "year": str(v.get("vod_year") or "").strip(),
                "pic": (v.get("vod_pic") or "").strip(),
                "type": (v.get("type_name") or "").strip(),
                "site": site["name"],
                "eps": eps,
                "count": len(eps),
                "remarks": (v.get("vod_remarks") or "").strip(),
            }
            rec.update(_row_meta(v))
            out.append(rec)
        return out

    if pages <= 1:
        return one(1)[:limit]
    out = []
    with concurrent.futures.ThreadPoolExecutor(max_workers=pages) as ex:
        for part in ex.map(one, range(1, pages + 1)):
            out.extend(part)
    return out[:limit]


CAT_ORDER = ["电影", "剧集", "动漫", "综艺", "纪录片"]


def _cat(t):
    """把采集站五花八门的 type_name 归一成几个大类"""
    t = (t or "").strip()
    if not t:
        return ""
    if "动漫" in t or "动画" in t or "番剧" in t:
        return "动漫"
    # 站里「纪录片」「记录片」两种写法都有，漏了就会掉进「电影」
    if "纪录" in t or "记录" in t:
        return "纪录片"
    if "综艺" in t:
        return "综艺"
    # 电影类型名一律是「X片」（剧情片/动作片/恐怖片…），
    # 但「剧情片」里有个「剧」字——必须先于剧集判断，否则会被归成剧集
    if t.endswith("片"):
        return "电影"
    if "剧" in t:
        return "剧集"
    if "电影" in t:
        return "电影"
    return ""


def _dedup(items, limit=0):
    """同名同年份只留一条。

    片名里带不带年份、括号打不打，各家源都不一样（「夺命狂花」vs「夺命狂花 2026」），
    所以把名字尾部的年份剥掉再比，年份统一看 year 字段——
    这样既不漏合并，也不会把「沙丘 1984」和「沙丘 2021」当成同一部。
    """
    seen, out = set(), []
    for it in items:
        k = _norm_key(it.get("name", ""))
        k = re.sub(r"(19|20)\d{2}$", "", k) or k
        key = (k, str(it.get("year") or ""))
        if k and key not in seen:
            seen.add(key)
            out.append(it)
    return out[:limit] if limit else out


def build_recommend(limit=18):
    """真正去采集站拉数据——慢（十几秒），只由后台线程调用"""
    taste = _taste()
    liked = []
    if taste:
        def one(k):
            try:
                return search_online(k, deadline=SEARCH_DEADLINE)[:6]
            except Exception:
                return []
        with concurrent.futures.ThreadPoolExecutor(max_workers=3) as ex:
            for part in ex.map(one, taste[:3]):
                liked.extend(part)
        liked = _dedup(liked, limit)

    # ① 最近更新（快，剧集/动漫/综艺主要靠它）
    latest = []
    with concurrent.futures.ThreadPoolExecutor(max_workers=8) as ex:
        for part in ex.map(lambda s: _latest(s, hours=72, limit=60, pages=5),
                           ONLINE_SITES[:8]):
            latest.extend(part)
    # ② 按分类 ID 补（电影/纪录片在「最近更新」里几乎没有，必须这样拉）
    pool = latest + _by_type(ONLINE_SITES[:4], range(3, 21))
    latest = _dedup(latest)
    pool = _dedup(pool)

    groups = {}
    for it in pool:
        c = _cat(it.get("type"))
        if c:
            groups.setdefault(c, []).append(it)
    cats = [{"cat": c, "count": len(groups[c]), "items": groups[c][:limit]}
            for c in CAT_ORDER if groups.get(c)]
    return {"taste": taste, "liked": liked, "latest": latest[:limit],
            "cats": cats, "at": int(time.time())}


RECOMMEND_TTL = 1800          # 推荐缓存 30 分钟
# 搜索框没历史时的兜底热词，别让下拉面板一开始就是空的
FALLBACK_HOT = ["沙丘", "流浪地球", "奥本海默", "繁花", "漫长的季节",
                "狂飙", "三体", "宇宙探索编辑部", "怒海战记", "第二十条"]

CATMAP_TTL = 86400            # 分类映射一天探一次就够
BROWSE_SITES = 5              # 浏览用几个站（越多越慢，5 个够铺满一屏）


def detect_catmap(site):
    """探测某站的分类 ID 各自属于哪个大类。

    各站 t 的排法完全不同（暴风 t=20 才是电影，别家 t=6 就是），而且会变。
    所以不写死映射——探一次记下来缓存一天，浏览时才知道该拉哪个 t。
    """
    key = "catmap|" + site["name"]
    c = cache_peek(key)
    if c and cache_valid(key, CATMAP_TTL):
        return c

    def probe(t):
        try:
            L = _qs(site, "ac=detail&t=%d" % t)
        except Exception:
            return (t, "")
        names = [_cat(x.get("type")) for x in L[:14]]
        names = [n for n in names if n]
        if len(names) < 3:            # 样本太少，判不出来
            return (t, "")
        return (t, max(set(names), key=names.count))

    out = {}
    with concurrent.futures.ThreadPoolExecutor(max_workers=10) as ex:
        for t, c2 in ex.map(probe, range(1, 31)):
            if c2:
                out[str(t)] = c2
    if out:
        cache_put(key, out)
    return out


def catmap_warm():
    """启动时后台把分类映射预热掉，免得第一次点「浏览」要干等十几秒"""
    for s in ONLINE_SITES[:BROWSE_SITES]:
        try:
            detect_catmap(s)
        except Exception:
            pass


def browse_catalog(cat="", year="", area="", pg=1, limit=60):
    """按大类 / 年份 / 地区浏览片库。

    做法：用每一站自己的分类 ID 拉一页（pg 直接透传给采集站），
    回来统一按 type_name 归一、再按年份地区过滤。
    这样不用管各站 ID 怎么排，也不会因为某个站的 ID 变了就整个失效。
    """
    # 一次要打 4 个站 × 4 个分类，缓存住，翻回来才不会又等 5 秒
    ck = "browse|%s|%s|%s|%d" % (cat, year, area, pg)
    c = cache_peek(ck)
    if c is not None and cache_valid(ck, 600):
        return c

    tasks, nsite, out = [], 0, []
    for s in ONLINE_SITES[:BROWSE_SITES]:
        cm = detect_catmap(s)
        ts = [t for t, c in cm.items() if not cat or c == cat]
        if ts:
            nsite += 1
            tasks += [(s, t) for t in ts[:4]]   # 每站最多 4 个分类，控制请求数

    def fetch(job):
        s, t = job
        try:
            return _qs(s, "ac=detail&t=%s&pg=%d" % (t, pg))
        except Exception:
            return []

    if tasks:
        with concurrent.futures.ThreadPoolExecutor(max_workers=10) as ex:
            for part in ex.map(fetch, tasks):
                out.extend(part)

    if year:
        out = [x for x in out if str(x.get("year") or "").startswith(year)]
    if area:
        out = [x for x in out if area in (x.get("area") or "")]

    items = _dedup(out, limit)
    # 新片优先；同年份里信息全的（有简介）优先
    items.sort(key=lambda x: (str(x.get("year") or "0"),
                              1 if x.get("desc") else 0), reverse=True)
    res = {"items": items, "pg": pg, "cat": cat, "year": year, "area": area,
           "more": len(out) > limit, "total": len(out),
           "sites": nsite, "at": int(time.time())}
    cache_put(ck, res)
    return res


_REFRESHING = False
_REFRESH_LK = threading.Lock()


def refresh_recommend_async(force=False):
    """后台刷新；已经在刷就直接返回"""
    global _REFRESHING
    with _REFRESH_LK:
        if _REFRESHING:
            return False
        if not force and cache_valid("home.recommend", RECOMMEND_TTL):
            return False
        _REFRESHING = True

    def run():
        global _REFRESHING
        try:
            data = build_recommend()
            if data.get("cats") or data.get("latest"):
                cache_put("home.recommend", data)
                log("推荐已刷新：%d 个分类 / %d 条" % (
                    len(data.get("cats") or []), len(data.get("latest") or [])))
        except Exception as e:
            log("推荐刷新失败：%s" % e)
        finally:
            with _REFRESH_LK:
                _REFRESHING = False

    threading.Thread(target=run, daemon=True).start()
    return True


def get_recommend(fresh=False):
    """永远秒回：有缓存就出缓存（stale 只是标记），顺便后台刷新"""
    c = cache_peek("home.recommend")
    age = cache_age("home.recommend")
    stale = age is None or age > RECOMMEND_TTL
    if fresh or stale:
        refresh_recommend_async(force=bool(fresh))
    if c is None:
        return {"taste": [], "liked": [], "latest": [], "cats": [],
                "stale": True, "age": None, "loading": True}
    out = dict(c)
    out["stale"] = stale
    out["age"] = age
    out["loading"] = False
    return out



# 全部经 NAS 实测 UDP 可达；注入磁力能明显提高找到 peer 的成功率
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
    "udp://tracker.internetwarriors.net:1337/announce",
    "udp://opentracker.xyz:80/announce",
    "udp://tracker.qu.ax:6969/announce",
    "udp://bt.ktrackers.com:6969/announce",
    "udp://tracker.skyts.net:6969/announce",
    "udp://tracker2.dler.org:80/announce",
]

# 用于查询种子健康度（只能用 HTTP 的 scrape，UDP 的没法直接查）
SCRAPE_HOSTS = [
    ("tracker.opentrackr.org", 1337),
    ("open.tracker.cl", 1337),
    ("tracker.dler.org", 6969),
    ("opentracker.xyz", 80),
]


# ───────────────────────── HTTP 工具 ─────────────────────────
def http_get(url, referer=None, extra=None, data=None, timeout=TIMEOUT):
    hdr = {
        "User-Agent": UA,
        "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language": "zh-CN,zh;q=0.9,en;q=0.8",
        "Accept-Encoding": "gzip, deflate",
    }
    if referer:
        hdr["Referer"] = referer
    if extra:
        hdr.update(extra)
    req = urllib.request.Request(url, data=data, headers=hdr)
    with urllib.request.urlopen(req, timeout=timeout) as r:
        raw = r.read()
        enc = r.headers.get("Content-Encoding", "")
        if "gzip" in enc:
            try:
                raw = gzip.decompress(raw)
            except Exception:
                pass
        elif "deflate" in enc:
            try:
                raw = zlib.decompress(raw)
            except Exception:
                raw = zlib.decompress(raw, -zlib.MAX_WBITS)
        ctype = r.headers.get("Content-Type", "")
    charset = None
    m = re.search(r"charset=([\w-]+)", ctype, re.I)
    if m:
        charset = m.group(1)
    if not charset:
        head = raw[:2000].decode("ascii", "ignore").lower()
        m = re.search(r'charset=["\']?([\w-]+)', head)
        charset = m.group(1) if m else "utf-8"
    for cs in (charset, "utf-8", "gb18030"):
        try:
            return raw.decode(cs)
        except Exception:
            continue
    return raw.decode("utf-8", "replace")


def strip_tags(s):
    s = re.sub(r"<script.*?</script>", " ", s, flags=re.S | re.I)
    s = re.sub(r"<[^>]+>", " ", s)
    return re.sub(r"\s+", " ", html_mod.unescape(s)).strip()


def btih_to_hex(h):
    h = h.strip()
    if len(h) == 40:
        return h.lower()
    if len(h) == 32:
        try:
            return base64.b32decode(h.upper()).hex()
        except Exception:
            return h
    return h


# ───────────────────────── 磁力工具 ─────────────────────────
def build_magnet(ih, name=None):
    """裸磁力体验很差（qBittorrent 里只显示一串 hash）。
    这里补上 dn（显示名）与 tr（tracker 列表）。"""
    m = "magnet:?xt=urn:btih:" + ih
    if name:
        m += "&dn=" + urllib.parse.quote(name[:120], safe="")
    m += "".join("&tr=" + urllib.parse.quote(t, safe="") for t in TRACKERS[:8])
    return m


def scrape_health(ih_hex):
    """查种子健康度。并发问几个 tracker，取做得最多的那个结果。"""
    try:
        ih = bytes.fromhex(ih_hex)
    except Exception:
        return None
    q = urllib.parse.quote(ih, safe="")

    def one(host, port):
        try:
            req = urllib.request.Request(
                "http://%s:%d/scrape?info_hash=%s" % (host, port, q),
                headers={"User-Agent": "qBittorrent/5.1.4"})
            with urllib.request.urlopen(req, timeout=6) as r:
                d = r.read()
            if b"completei" not in d:
                return None

            def num(key):
                s = d.find(key)
                if s < 0:
                    return None
                s += len(key)
                e = d.find(b"e", s)
                try:
                    return int(d[s:e])
                except Exception:
                    return None

            return {"s": num(b"completei"), "c": num(b"downloadedi"), "l": num(b"incompletei")}
        except Exception:
            return None

    best = None
    with concurrent.futures.ThreadPoolExecutor(max_workers=4) as ex:
        for r in ex.map(lambda a: one(*a), SCRAPE_HOSTS):
            if not r:
                continue
            if best is None or (r.get("s") or 0) > (best.get("s") or 0):
                best = r
    return best


def parse_tags(text):
    """从标题里抽出画质/来源/编码标签"""
    t = text or ""
    tags = []
    m = re.search(r"(2160p|4K|1080p|720p|480p|8K)", t, re.I)
    if m:
        v = m.group(1).upper()
        v = "4K" if v in ("2160P", "4K") else v
        tags.append({"t": v, "k": "res"})
    for pat, label in [(r"REMUX", "REMUX"), (r"BluRay|BDRip|Blu-ray", "蓝光"),
                       (r"WEB-DL", "WEB-DL"), (r"WEBRip", "WEBRip"),
                       (r"HDTV", "HDTV"), (r"DVDRip", "DVDRip")]:
        if re.search(pat, t, re.I):
            tags.append({"t": label, "k": "src"})
            break
    if re.search(r"x265|H\.?265|HEVC", t, re.I):
        tags.append({"t": "H265", "k": "codec"})
    elif re.search(r"x264|H\.?264|AVC", t, re.I):
        tags.append({"t": "H264", "k": "codec"})
    if re.search(r"HDR10|HDR|Dolby\s*Vision|杜比视界", t, re.I):
        tags.append({"t": "HDR", "k": "hdr"})
    if re.search(r"中字|简繁|简中|繁中|中英|国语|双语|CHS|CHT", t, re.I):
        tags.append({"t": "中字", "k": "sub"})
    if re.search(r"修复版|加长版|导演剪辑|重制|合集|全\d+集", t):
        tags.append({"t": "合集/修复", "k": "note"})
    return tags


def clean_name(s):
    """站点常把广告塞进磁力的 dn 里，清掉"""
    if not s:
        return ""
    s = re.sub(r"地址发布页.*$", "", s)
    s = re.sub(r"收藏不迷路.*$", "", s)
    s = re.sub(r"(?:www|http)[.:][a-zA-Z0-9./-]+", "", s, flags=re.I)
    s = re.sub(r"电影港|新版6v|旧版66影视|6v电影|备用地址|最新地址", "", s)
    s = re.sub(r"[\[\]（）()【】]", " ", s)
    s = re.sub(r"\s{2,}", " ", s)
    return s.strip(" .-_·|")


def guess_category(title):
    t = title or ""
    if re.search(r"第\s*\d+\s*[集话期]|S\d{1,2}E\d{1,3}|全\s*\d+\s*集|更新至", t, re.I):
        return "剧集"
    if re.search(r"动漫|动画|剧场版|TV版", t):
        return "动漫"
    return "电影"


# ───────────────────────── 站点 ─────────────────────────
def search_66ss(kw):
    base = "https://disabled.invalid"
    form = urllib.parse.urlencode({
        "show": "title", "tempid": "1", "tbname": "article",
        "mid": "1", "dopost": "search", "keyboard": kw, "submit": "",
    }).encode()
    html = http_get(base + "/e/search/so.php", referer=base + "/", data=form)
    if "没有搜索到" in html:
        return []
    links = re.findall(r'href="(/(?:[a-z]+pian|dianshiju|zongyi|donghuapian)/\d+\.html)"', html)
    if not links:
        links = re.findall(r'href="(/[a-z]+/\d+\.html)"', html)
    out, seen = [], set()
    for l in links:
        if l in seen:
            continue
        seen.add(l)
        if len(seen) > MAX_DETAIL:
            break
        out.append({"url": base + l, "source": "新版6v"})
    return out


SITES = [{"name": "新版6v", "fn": search_66ss}]


# ═══════════════════ 在线源（苹果CMS 采集站聚合）═══════════════════
# 全部实测：接口可达 + 返回真正的 m3u8 直链 + CDN 带 CORS:*
# 也就是说浏览器可以直接串流播放，无需后端中转
ONLINE_SITES = [
    {"name": "暴风资源", "api": "https://disabled.invalid/api.php/provide/vod/",             "pri": 1},
    {"name": "量子资源", "api": "https://disabled.invalid/api.php/provide/vod/",           "pri": 2},
    {"name": "最大资源", "api": "https://disabled.invalid/api.php/provide/vod/",         "pri": 3},
    {"name": "极速资源", "api": "https://disabled.invalid/api.php/provide/vod/",             "pri": 4},
    {"name": "360资源",  "api": "https://disabled.invalid/api.php/provide/vod/",           "pri": 5},
    {"name": "猫眼资源", "api": "https://disabled.invalid/api.php/provide/vod/",       "pri": 6},
    {"name": "电影天堂", "api": "https://disabled.invalid/api.php/provide/vod/",     "pri": 7},
    {"name": "非凡资源", "api": "https://disabled.invalid/api.php/provide/vod/",          "pri": 8},
    {"name": "新浪资源", "api": "https://disabled.invalid/removed.php/provide/vod/", "pri": 9},
    {"name": "光速资源", "api": "https://disabled.invalid/api.php/provide/vod/",      "pri": 10},
    {"name": "金鹰资源", "api": "https://disabled.invalid/provide/vod/",                     "pri": 11},
    {"name": "红牛资源", "api": "https://disabled.invalid/api.php/provide/vod/",      "pri": 12},
    {"name": "虎牙资源", "api": "https://disabled.invalid/api.php/provide/vod/",         "pri": 13},
]


def _cms_query(site, kw, timeout=12):
    """查一个采集站，返回原始条目列表"""
    url = site["api"] + "?ac=detail&wd=" + urllib.parse.quote(kw)
    try:
        req = urllib.request.Request(url, headers={
            "User-Agent": UA, "Accept": "application/json,text/plain,*/*"})
        with urllib.request.urlopen(req, timeout=timeout) as r:
            raw = r.read()
    except Exception:
        return []
    d = None
    for cs in ("utf-8", "gb18030"):
        try:
            d = json.loads(raw.decode(cs, "replace"))
            break
        except Exception:
            continue
    if not isinstance(d, dict):
        return []
    return (d.get("list") or [])[:8]


def _parse_play_url(pu):
    """把 vod_play_url 解析成 [{name,url}]

    各家的格式其实不一样，踩过的坑：
      标准    : 第01集$https://a/index.m3u8
      非凡/电影天堂: https://share页$$$HD中字$https://b/index.m3u8
    所以不能简单按第一个 $ 切 —— 要「取出本段里所有 http 地址，取最后一个」，
    那才是真正的播放地址。（早期版本按 $ 切，导致这批源全被误判为「解析型」丢掉）
    """
    eps = []
    for seg in (pu or "").split("#"):
        seg = seg.strip()
        if not seg:
            continue
        URL_RE = r"https?://[^\s$#'\"<>]+"
        us = re.findall(URL_RE, seg)
        if not us:
            continue
        url = us[-1]
        if not url.endswith(".m3u8"):
            continue
        # 集名必须取「紧挨着最终地址前面的那个 $ 之后」的文本。
        # 有些站会把两组地址用 $$$ 粘在同一段里，例如：
        #   第30集$.../share/xxx$$$第01集$.../90153_xxx/index.m3u8
        # 这时真正的集名是「第01集」而不是段首的「第30集」。
        pos = seg.rfind(url)
        before = re.sub(URL_RE, "", seg[:pos]).rstrip("$")
        nm = before.rsplit("$", 1)[-1].strip() if before else ""
        nm = re.sub(r"[\s$]+", " ", nm).strip()
        if not nm or len(nm) > 30:
            nm = "第%d集" % (len(eps) + 1)
        eps.append({"name": nm[:24], "url": url})
    # 有些采集站的顺序是乱的（出现过第一段写着「第30集」），
    # 若集名都是「第N集」这种可解析格式，就按数字重排一次
    def _num(x):
        mm = re.search(r"第\s*(\d+)\s*[集话期]", x["name"])
        return int(mm.group(1)) if mm else None
    nums = [_num(e) for e in eps]
    if len(eps) > 2 and all(n is not None for n in nums) and nums != sorted(nums):
        eps = [e for _, e in sorted(zip(nums, eps), key=lambda t: t[0])]
    return eps


def _norm_key(name):
    """归一化片名用于跨站合并"""
    s = re.sub(r"[\s·:：\-—_·]+", "", name or "")
    s = re.sub(r"[（(【\[].*?[）)】\]]", "", s)
    return s.lower()


def _relevance(name, kw):
    """片名与关键词的相关度，用于排序"""
    n, k = _norm_key(name), _norm_key(kw)
    if not k:
        return 0
    if n == k:
        return 100
    if n.startswith(k):
        return 85
    if n.endswith(k):
        return 70
    if k in n:
        return 55
    return 10


def _clean_desc(s, n=560):
    """采集站的 vod_content 带 html 标签和一堆换行，清干净再截断"""
    if not s:
        return ""
    s = html_mod.unescape(re.sub(r"<[^>]+>", "", str(s)))
    s = re.sub(r"\s+", " ", s).strip()
    return s[:n]


def _zh_count(s):
    """简介里有多少个汉字。
    跨站合并时会撞上英文简介（有的源直接扒了 TMDB 的），
    按「汉字多者优先」挑，就不会把中文源的中文简介挤掉。"""
    return sum(1 for ch in (s or "") if "\u4e00" <= ch <= "\u9fff")


def _desc_better(a, b):
    """a 是否比 b 更值得保留：先看汉字数，再比长度"""
    return (_zh_count(a), len(a or "")) > (_zh_count(b), len(b or ""))


def _join_names(s, n=6):
    """演员/导演：原字段是几十个名字用逗号串起来的，只留前几个"""
    if not s:
        return ""
    parts = [x.strip() for x in re.split(r"[,，/、]", str(s)) if x.strip()]
    out = " / ".join(parts[:n])
    if len(parts) > n:
        out += " 等"
    return out


# 采集站 ac=detail 其实返回了完整字段，之前只取片名/年份/封面/类型，
# 简介、演员、导演、地区、语言、评分全被丢掉了 —— 详情页因此永远是空的。
META_FIELDS = ("desc", "actor", "director", "area", "lang",
               "score", "pubdate", "total")


def _extract_meta(v):
    return {
        # vod_blurb 是短简介，vod_content 是全的；前者常常更规整，作兜底
        "desc": _clean_desc(v.get("vod_content") or v.get("vod_blurb")),
        "actor": _join_names(v.get("vod_actor")),
        "director": _join_names(v.get("vod_director"), 3),
        "area": (v.get("vod_area") or "").strip(),
        "lang": (v.get("vod_lang") or "").strip(),
        "score": str(v.get("vod_score") or "").strip(),
        "pubdate": (v.get("vod_pubdate") or "").strip(),
        "total": str(v.get("vod_total") or "").strip(),
    }


def _row_meta(v, dlen=200):
    """列表/推荐用的精简版——首页 Hero 只显示一两行简介，
    截短一点，几百条一起进缓存也不会太占地方。"""
    d = _clean_desc(v.get("vod_content") or v.get("vod_blurb"), dlen)
    if _zh_count(d) < 4:
        # 有的源直接扒了 TMDB 的英文简介，放进首页 Hero 会很突兀；
        # 详情页仍然保留（那里信息越全越好）
        d = ""
    return {
        "desc": d,
        "score": str(v.get("vod_score") or "").strip(),
        "actor": _join_names(v.get("vod_actor"), 3),
        "director": _join_names(v.get("vod_director"), 2),
        "area": (v.get("vod_area") or "").strip(),
    }


def _score_max(a, b):
    """评分取大值：有的源评分残缺（整部剧给 5.0），
    取最大比「第一个非空」更接近真实分。"""
    def f(x):
        try:
            v = float(x)
        except (TypeError, ValueError):
            return None
        return v if 0 < v <= 10 else None
    fa, fb = f(a), f(b)
    if fa is None:
        return b or ""
    if fb is None:
        return a or ""
    return ("%.1f" % max(fa, fb))


def search_online(kw, deadline=None):
    """并发查所有采集站，按片名合并，每部片列出所有可用源

    deadline：整体限时（秒）。十几个站里总有几个慢的，
    原来用 ex.map 会等最慢那个站超时才返回，实测能卡十几秒。
    现在到点就把已经回来的站先用上，不等了。
    """
    kws = (kw or "").strip()
    if not kws:
        return []
    KEY = _norm_key(kws)

    def one(site):
        out = []
        for v in _cms_query(site, kws):
            nm = (v.get("vod_name") or "").strip()
            if not nm:
                continue
            # 采集站是模糊匹配，简介/演员沾边也会返回。
            # 这里强制要求片名本身包含关键词，否则搜「沙丘」会混进一堆无关片。
            if KEY and KEY not in _norm_key(nm):
                continue
            eps = _parse_play_url(v.get("vod_play_url"))
            if not eps:
                continue
            rec = {
                "name": nm,
                "year": str(v.get("vod_year") or "").strip(),
                "pic": (v.get("vod_pic") or "").strip(),
                "type": (v.get("type_name") or "").strip(),
                "remarks": (v.get("vod_remarks") or "").strip(),
                "site": site["name"],
                "pri": site["pri"],
                "eps": eps,
            }
            rec.update(_extract_meta(v))
            out.append(rec)
        return out

    rows = []
    ex = concurrent.futures.ThreadPoolExecutor(max_workers=13)
    try:
        futs = [ex.submit(one, s) for s in ONLINE_SITES]
        done, _ = concurrent.futures.wait(futs, timeout=deadline)
        for f in done:
            try:
                rows.extend(f.result())
            except Exception:
                pass
    finally:
        ex.shutdown(wait=False)   # 不等还在跑的慢站

    merged = {}
    for r in rows:
        k = _norm_key(r["name"])
        if not k:
            continue
        if k not in merged:
            merged[k] = {
                "name": r["name"], "year": r["year"], "pic": r["pic"],
                "type": r["type"], "sources": [],
                "desc": "", "actor": "", "director": "", "area": "",
                "lang": "", "score": "", "pubdate": "", "total": "",
            }
        m = merged[k]
        # 封面/年份/类型/演员… 取第一个非空的
        for f in ("pic", "year", "type", "actor", "director",
                  "area", "lang", "pubdate", "total"):
            if not m.get(f) and r.get(f):
                m[f] = r[f]
        # 简介和评分要挑：简介优先中文（避免被英文简介挤掉），评分取最大
        if _desc_better(r.get("desc"), m.get("desc")):
            m["desc"] = r["desc"]
        m["score"] = _score_max(m.get("score"), r.get("score"))
        m["sources"].append({
            "site": r["site"], "remarks": r["remarks"],
            "eps": r["eps"], "count": len(r["eps"]),
        })

    items = list(merged.values())
    for it in items:
        it["sources"].sort(key=lambda s: (s["site"] != "暴风资源", -s["count"]))
        it["ep_count"] = max(s["count"] for s in it["sources"])
        it["site_count"] = len(it["sources"])
        it["rel"] = _relevance(it["name"], kws)
    # 相关度优先，其次源多、集数全
    items.sort(key=lambda x: (-x["rel"], -x["site_count"], -x["ep_count"]))
    return items[:60]


def get_play_url(url):
    """校验并返回可播放地址（顺带探测是主清单还是子清单）"""
    try:
        st, body, _, _ = _raw(url, ref=url, timeout=12)
        txt = body.decode("utf-8", "replace")
        if "#EXTM3U" not in txt:
            return None
        return {"url": url, "master": "#EXT-X-STREAM-INF" in txt, "size": len(body)}
    except Exception:
        return None


def _raw(url, ref=None, timeout=15, extra=None):
    hdr = {"User-Agent": UA, "Accept": "*/*"}
    if ref:
        hdr["Referer"] = ref
    if extra:
        hdr.update(extra)
    req = urllib.request.Request(url, headers=hdr)
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return r.status, r.read(), dict(r.headers), r.geturl()



def enrich(item):
    """抓详情页 → 抽出全部磁力（保留原始的 dn 文件名与 tr tracker）"""
    try:
        html = http_get(item["url"], referer="https://disabled.invalid/")
    except Exception as e:
        item["error"] = str(e)[:50]
        item["torrents"] = []
        return item
    plain = strip_tags(html)
    t = re.search(r"<title>(.*?)</title>", html, re.S)
    item["title"] = html_mod.unescape(t.group(1)).split("-")[0].strip() if t else item["url"]

    # 详情页正文里的规格信息（◎ 是这类站点常用的字段标记）
    def field(*names):
        for n in names:
            m = re.search(r"[◎【]?\s*" + n + r"\s*[】]?\s*[\u3000\s]*([^\u3000\s<]{1,22})", plain)
            if m:
                v = m.group(1).strip()
                if v and v not in ("暂无", "-", "无"):
                    return v
        return ""
    item["vsize"] = field("视频尺寸", "分辨率")
    item["vfmt"] = field("文件格式", "格式")
    item["flen"] = field("片.{0,4}长", "时长")
    sz = re.search(r"[◎【]?\s*文件大小\s*[】]?\s*[\u3000\s]*([\d.]+\s*[GMKT]B)", plain, re.I)
    if not sz:
        sz = re.search(r"([\d.]+\s*[GM]B)", plain[:6000])
    item["size"] = sz.group(1).replace(" ", "") if sz else ""

    # 完整磁力（关键：保留 dn 与 tr，不要只取 hash）
    raw = re.findall(r"magnet:\?xt=urn:btih:[^\"'<>\s]+", html)
    seen, tss = set(), []
    for m in raw:
        m = html_mod.unescape(m).rstrip("&").rstrip("\\")
        h = re.search(r"btih:([A-Za-z0-9]+)", m)
        if not h:
            continue
        hx = btih_to_hex(h.group(1))
        if hx in seen:
            continue
        seen.add(hx)
        dn = ""
        q = m.split("?", 1)
        if len(q) > 1:
            try:
                dn = (urllib.parse.parse_qs(q[1]).get("dn") or [""])[0]
            except Exception:
                dn = ""
        tss.append({"hash": hx, "magnet": m, "name": dn})
    # 简介 / 演员 / 导演（详情页展示用）
    m = re.search(r"(?:剧情|简介)[:：]?\s*([^<]{20,600})", plain)
    item["desc"] = (m.group(1).strip()[:400] if m else "")
    item["torrents"] = tss
    pans = re.findall(r"https://pan\.quark\.cn/s/[a-zA-Z0-9]+", html)
    item["pans"] = list(dict.fromkeys(pans))[:3]
    return item


def do_search(kw):
    """把每个磁力展开成独立结果项 —— 同一部片的不同版本可以逐条对比挑选"""
    kws = kw.strip()
    if not kws:
        return []
    raw = []
    for s in SITES:
        try:
            raw.extend(s["fn"](kws))
        except Exception:
            pass
    if not raw:
        return []
    with concurrent.futures.ThreadPoolExecutor(max_workers=6) as ex:
        details = list(ex.map(enrich, raw))

    items = []
    used = set()
    for d in details:
        movie = d.get("title", "")
        for t in d.get("torrents", []):
            if t["hash"] in used:
                continue
            used.add(t["hash"])
            fname = clean_name(t.get("name") or "")
            display = fname or movie
            if d.get("vsize") and d["vsize"].lower() not in display.lower():
                display = display + "  [" + d["vsize"] + "]"
            mag = t["magnet"]
            if "&tr=" not in mag:
                mag = build_magnet(t["hash"], fname or movie)
            items.append({
                "hash": t["hash"],
                "title": display[:110],
                "movie": movie,
                "fname": fname,
                "magnet": mag,
                "tags": parse_tags(fname + " " + movie + " " + d.get("vsize", "")),
                "size": d.get("size", ""),
                "vsize": d.get("vsize", ""),
                "vfmt": d.get("vfmt", ""),
                "flen": d.get("flen", ""),
                "source": d.get("source", ""),
                "url": d.get("url", ""),
                "pans": d.get("pans", []),
                "category": guess_category(movie),
                "seeders": -1,
                "leechers": -1,
                "finished": -1,
            })
    if not items:
        return []

    def health(it):
        hh = scrape_health(it["hash"]) or {}
        s = hh.get("s")
        it["seeders"] = s if s is not None else -1
        it["leechers"] = hh.get("l") if hh.get("l") is not None else -1
        it["finished"] = hh.get("c") if hh.get("c") is not None else -1
        return it

    with concurrent.futures.ThreadPoolExecutor(max_workers=10) as ex:
        items = list(ex.map(health, items))

    items.sort(key=lambda x: (-(x.get("seeders") if x.get("seeders", -1) >= 0 else -1),
                              -(x.get("finished") or -1),
                              x.get("title", "")))
    return items


# ───────────────────────── qBittorrent ─────────────────────────
def state_cn(s):
    return {
        "error": "出错", "missingFiles": "文件缺失", "uploading": "做种中",
        "pausedUP": "已完成·暂停", "queuedUP": "排队做种", "stalledUP": "做种中",
        "checkingUP": "校验中", "forcedUP": "强制做种", "allocating": "分配空间",
        "downloading": "下载中", "metaDL": "获取元数据", "pausedDL": "已暂停",
        "queuedDL": "排队中", "stalledDL": "等待资源", "checkingDL": "校验中",
        "forcedDL": "强制下载", "checkingResumeData": "校验数据",
        "moving": "移动文件", "unknown": "未知",
    }.get(s, s)


class Qbt:
    def __init__(self):
        self.cj = http.cookiejar.CookieJar()
        self.op = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(self.cj))
        self.ok = False
        self.msg = ""

    def _req(self, path, fields=None, timeout=25):
        hdr = {"Referer": QBT_URL, "Origin": QBT_URL, "User-Agent": UA}
        data = urllib.parse.urlencode(fields).encode() if fields else None
        if data:
            hdr["Content-Type"] = "application/x-www-form-urlencoded"
        req = urllib.request.Request(QBT_URL + path, data=data, headers=hdr)
        with self.op.open(req, timeout=timeout) as r:
            return r.status, r.read().decode("utf-8", "replace")

    def login(self):
        # 账号密码从环境变量注入。没配就说清楚是什么没配，
        # 不然用户只能对着「登录失败」猜。
        if not QBT_USER and not QBT_PASS:
            self.ok = False
            self.msg = "未配置 qBittorrent 账号，请用 QBT_USER / QBT_PASS 环境变量注入"
            return False
        try:
            st, b = self._req("/api/v2/auth/login", {"username": QBT_USER, "password": QBT_PASS})
            self.ok = b.strip() == "Ok."
            self.msg = "" if self.ok else "qBittorrent 账号或密码不对"
        except Exception as e:
            self.ok = False
            self.msg = "连不上 qBittorrent（%s）：%s" % (QBT_URL, str(e)[:60])
        return self.ok

    def add(self, magnet, category):
        if not self.ok and not self.login():
            return False, self.msg or "qBittorrent 登录失败"
        f = {"urls": magnet}
        if category in CATEGORIES:
            f["category"] = category
        try:
            st, b = self._req("/api/v2/torrents/add", f)
            return (st == 200), (b.strip() or "已提交")
        except Exception as e:
            return False, str(e)[:80]

    def action(self, hashes, act):
        if not self.ok and not self.login():
            return False, self.msg or "未连接"
        try:
            if act == "delete":
                st, b = self._req("/api/v2/torrents/delete",
                                  {"hashes": hashes, "deleteFiles": "true"})
            elif act == "pause":
                st, b = self._req("/api/v2/torrents/stop", {"hashes": hashes})
            else:
                st, b = self._req("/api/v2/torrents/start", {"hashes": hashes})
            return st == 200, b.strip()[:60]
        except Exception as e:
            return False, str(e)[:80]

    def files(self, h):
        try:
            st, b = self._req("/api/v2/torrents/files?hash=" + h)
            arr = json.loads(b)
            return [{"n": f.get("name", ""),
                     "p": round(f.get("progress", 0) * 100, 1),
                     "s": f.get("size", 0)} for f in arr[:40]]
        except Exception:
            return []

    def snapshot(self):
        try:
            if not self.ok and not self.login():
                return []
            st, b = self._req("/api/v2/torrents/info")
            arr = json.loads(b)
        except Exception:
            return []
        out = []
        for t in arr[:30]:
            eta = t.get("eta", 8640000)
            out.append({
                "hash": t.get("hash", ""),
                "name": (t.get("name", "") or "")[:80],
                "progress": round(t.get("progress", 0) * 100, 1),
                "speed": t.get("dlspeed", 0),
                "state": t.get("state", ""),
                "size": t.get("size", 0),
                "downloaded": t.get("downloaded", 0),
                "seeds": t.get("num_seeds", 0),
                "peers": t.get("num_leechs", 0),
                "total_seeds": t.get("num_complete", 0),
                "eta": eta if 0 < eta < 8640000 else -1,
                "category": t.get("category", ""),
                "state_cn": state_cn(t.get("state", "")),
            })
        return out


QBT = Qbt()


# ───────────────────────── 页面 ─────────────────────────
PAGE = r"""<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover">
<meta name="theme-color" content="#0f0e0c">
<title>放映室 · 个人娱乐中心</title>
<style>
:root{
  /* 底色比原来更深一档——玻璃层要有「底」才有悬浮感，压在 #0f0e0c 上是看不出来的 */
  --bg:#070607; --bg2:#0d0c0e; --surface:#16151a; --surface2:#1f1d23;
  --edge:rgba(255,255,255,.075); --edge2:rgba(255,255,255,.155);
  --text:#f6f2ec; --dim:#a69e95; --faint:#6e6760;
  --ember:#e05a3c; --ember-soft:rgba(224,90,60,.13); --gold:#d9ad56;
  --jade:#4bb894; --jade-soft:rgba(75,184,148,.12);
  --serif:"Songti SC","Noto Serif CJK SC","Source Han Serif SC",Georgia,serif;
  --sans:-apple-system,BlinkMacSystemFont,"PingFang SC","Hiragino Sans GB","Microsoft YaHei",sans-serif;
  --mono:ui-monospace,"SF Mono",Menlo,Consolas,monospace;

  /* ── 玻璃层 ──
     只给「悬浮在内容之上」的东西用：导航、侧栏、面板、弹层。
     列表卡片一律不用 backdrop-filter——几十上百个模糊层会把滚动拖垮。 */
  --glass:rgba(255,255,255,.05);
  --glass2:rgba(255,255,255,.085);
  --gbd:rgba(255,255,255,.11);
  --gbd2:rgba(255,255,255,.2);
  --gblur:blur(26px) saturate(170%);
  --gblur-sm:blur(14px) saturate(150%);
  --ghi:inset 0 1px 0 rgba(255,255,255,.075);   /* 顶边高光＝玻璃的「厚度」 */

  /* ── 高度体系：越大越远 ── */
  --e1:0 1px 2px rgba(0,0,0,.45);
  --e2:0 6px 18px -4px rgba(0,0,0,.5), var(--ghi);
  --e3:0 16px 40px -10px rgba(0,0,0,.62), var(--ghi);
  --e4:0 32px 76px -16px rgba(0,0,0,.72), var(--ghi);
  --e5:0 48px 120px -24px rgba(0,0,0,.85), var(--ghi);

  --r:10px; --r-sm:8px; --r-md:14px; --r-lg:20px; --r-xl:28px; --r-pill:999px;
  --ease:cubic-bezier(.16,1,.3,1);
  --ease-out:cubic-bezier(.22,1,.36,1);
}
*{box-sizing:border-box;-webkit-tap-highlight-color:transparent}
html,body{margin:0;padding:0}
body{background:var(--bg);color:var(--text);font-family:var(--sans);
  font-size:14.5px;line-height:1.65;-webkit-font-smoothing:antialiased;min-height:100vh}
/* ── 沉浸背景 ──
   一层固定的大面积光斑，内容全部悬浮在它上面。
   只动 transform（GPU 合成），不动背景位置，避免滚动时重绘。 */
.bgmesh{position:fixed;inset:-12%;z-index:-1;pointer-events:none;
  /* 只有顶部两处极淡的环境光，颜色只围绕品牌暖橙——
     铺满全屏的橙/蓝/紫三色渐变是最典型的 AI 配色，克制才有质感。 */
  background:
    radial-gradient(36% 24% at 13% -5%,rgba(224,90,60,.13) 0%,transparent 68%),
    radial-gradient(28% 20% at 87% -1%,rgba(120,138,178,.085) 0%,transparent 66%),
    radial-gradient(42% 28% at 50% 103%,rgba(176,112,64,.065) 0%,transparent 71%);
  animation:meshd 46s ease-in-out infinite alternate;
  will-change:transform}
@keyframes meshd{
  from{transform:translate3d(0,0,0) scale(1)}
  to{transform:translate3d(-2%,-1.6%,0) scale(1.08)}}
@media(prefers-reduced-motion:reduce){.bgmesh{animation:none;will-change:auto}}
body::after{content:'';position:fixed;inset:0;pointer-events:none;z-index:9000;opacity:.022;
  background-image:url("data:image/svg+xml;utf8,<svg xmlns='http://www.w3.org/2000/svg' width='140' height='140'><filter id='n'><feTurbulence type='fractalNoise' baseFrequency='.85' numOctaves='3'/></filter><rect width='140' height='140' filter='url(%23n)'/></svg>");
  mix-blend-mode:overlay}
::selection{background:var(--ember);color:#fff}
::-webkit-scrollbar{width:9px;height:9px}
::-webkit-scrollbar-track{background:transparent}
::-webkit-scrollbar-thumb{background:var(--edge2);border-radius:5px;border:2px solid var(--bg)}
::-webkit-scrollbar-thumb:hover{background:#544839}

/* ── 导航：悬浮玻璃浮岛 ──
   不是贴边的横条，而是浮在内容之上的一块玻璃，滚动时始终悬着。 */
header{position:sticky;top:0;z-index:200;padding:clamp(10px,1.1vw,16px) clamp(12px,2.2vw,40px) 0}
.hbar{max-width:min(1720px,95vw);margin:0 auto;
  background:var(--glass);
  backdrop-filter:var(--gblur);-webkit-backdrop-filter:var(--gblur);
  border:1px solid var(--gbd);border-radius:var(--r-lg);
  box-shadow:var(--e2);
  padding:12px clamp(14px,1.4vw,20px) 0}
.brandrow{display:flex;align-items:center;gap:14px;flex-wrap:wrap}
.brand{font-family:var(--serif);font-size:20px;letter-spacing:.24em;color:var(--text);
  text-decoration:none;white-space:nowrap;text-shadow:0 1px 12px rgba(0,0,0,.5)}
.brand em{color:var(--ember);font-style:normal}
.brandsub{font-size:10.5px;color:var(--faint);letter-spacing:.16em;font-family:var(--mono)}
.spacer{flex:1}
/* 搜索：玻璃胶囊，聚焦时整颗亮起来 */
.sbox{position:relative;flex:1;min-width:220px;max-width:440px}
.sbox input{width:100%;background:rgba(255,255,255,.045);border:1px solid var(--gbd);
  color:var(--text);padding:10px 42px 10px 15px;border-radius:var(--r-pill);
  font-size:14.5px;font-family:inherit;outline:none;
  transition:border-color .2s,background .2s,box-shadow .2s var(--ease)}
.sbox input::placeholder{color:var(--faint)}
.sbox input:hover{background:rgba(255,255,255,.07)}
.sbox input:focus{border-color:rgba(224,90,60,.55);background:rgba(255,255,255,.085);
  box-shadow:0 0 0 4px rgba(224,90,60,.13)}
.sbox .kbd{position:absolute;right:13px;top:50%;transform:translateY(-50%);
  font-family:var(--mono);font-size:10px;color:var(--faint);
  border:1px solid var(--gbd);border-radius:5px;padding:1px 6px;pointer-events:none}
.sbox input:focus~.kbd{opacity:0}
nav{display:flex;gap:2px;margin-top:10px;overflow-x:auto;scrollbar-width:none;
  padding-bottom:1px}
nav::-webkit-scrollbar{display:none}
nav button{background:none;border:none;color:var(--dim);font-family:inherit;font-size:14px;
  padding:8px 15px 11px;cursor:pointer;position:relative;white-space:nowrap;
  border-radius:var(--r-sm) var(--r-sm) 0 0;
  transition:color .18s,background .18s}
nav button:hover{color:var(--text);background:rgba(255,255,255,.04)}
nav button.on{color:var(--text)}
nav button.on::after{content:'';position:absolute;left:15px;right:15px;bottom:0;height:2px;
  background:var(--ember);border-radius:2px 2px 0 0;
  box-shadow:0 0 12px rgba(224,90,60,.6);animation:tabin .3s var(--ease)}
@keyframes tabin{from{transform:scaleX(.3);opacity:0}to{transform:scaleX(1);opacity:1}}
nav .bdg{display:inline-block;min-width:17px;padding:0 5px;margin-left:5px;background:var(--ember);
  color:#fff;font-size:10.5px;line-height:16px;border-radius:9px;font-family:var(--mono);
  vertical-align:1px}
/* 原来锁死 1200px：27"/4K 屏左右各空一大片。改成流体，宽屏真正铺开 */
main{max-width:min(1720px,95vw);margin:0 auto;
     padding:clamp(20px,2vw,34px) clamp(16px,2.2vw,40px) 110px}

.secttl{font-family:var(--serif);font-size:16px;letter-spacing:.05em;margin:0 0 3px;
  display:flex;align-items:center;gap:9px}
.secttl::before{content:'';width:3px;height:15px;background:var(--ember);border-radius:2px}
.secttl .cnt{font-family:var(--mono);font-size:11.5px;color:var(--faint);font-weight:400}
.sub{color:var(--faint);font-size:12.5px;margin:0 0 16px}
/* 分区间距随屏宽呼吸，不再是清一色 38px */
.sec{margin-top:clamp(38px,4.2vw,70px)}

/* 统计压成一行：原来 4 个方卡片又占地方又像模板 */
.statline{display:flex;gap:clamp(16px,2vw,30px);flex-wrap:wrap;align-items:baseline;
  padding:0 2px;color:var(--faint);font-size:12.5px;
  font-family:var(--mono);letter-spacing:.02em}
.statline b{font-family:var(--serif);font-size:20px;font-weight:600;
  color:var(--text);margin-right:5px;letter-spacing:0}

/* ── Hero：首页的视觉焦点（原来上来就是一行小字，全平铺） ── */
.hero{position:relative;overflow:hidden;border-radius:var(--r-xl);border:1px solid var(--gbd);
  min-height:clamp(292px,34vh,404px);display:flex;align-items:flex-end;
  margin:clamp(6px,1vw,14px) 0 clamp(30px,3.6vw,56px);
  background:var(--bg2);isolation:isolate;box-shadow:var(--e5)}
/* 背景图按「原图自身的宽高比」分三档处理——
   采集站给的多半是 270×399 这种竖版小图，铺满横幅等于放大 6 倍，不处理就是一团糊。
   竖版小图 → 重模糊做成氛围色底；真横版剧照 → 保留细节。由 fitBackdrop() 打 class。 */
/* 默认即按「竖版小图」处理（安全兜底：JS 没跑也不会是一团糊的放大图） */
.hero-bg{position:absolute;inset:0;width:100%;height:100%;object-fit:cover;
  z-index:0;background:var(--bg2);--kb:1.75;opacity:.62;
  transform:scale(var(--kb));
  filter:blur(56px) saturate(1.28) brightness(.42)}
.hero-bg.bd-mid{--kb:1.22;opacity:.6;filter:blur(22px) saturate(1.15) brightness(.5);
  animation:kburns 28s var(--ease) infinite alternate}
.hero-bg.bd-wide{--kb:1.03;opacity:.62;filter:brightness(.66) saturate(1.02);
  animation:kburns 28s var(--ease) infinite alternate}
.hero-veil{position:absolute;inset:0;z-index:1;
  /* 底色跟着 --bg 走，避免和页面底色之间出现一条色差 */
  background:
    linear-gradient(90deg,rgba(7,6,7,.9) 0%,rgba(7,6,7,.66) 38%,
      rgba(7,6,7,.2) 68%,rgba(7,6,7,.46) 100%),
    linear-gradient(0deg,rgba(7,6,7,.95) 0%,rgba(7,6,7,.06) 58%,rgba(7,6,7,.28) 100%)}
.hero-in{position:relative;z-index:2;padding:clamp(22px,2.8vw,46px);
  max-width:min(720px,calc(100% - 220px));
  animation:heroIn .7s var(--ease) backwards}
/* 右侧放一张清晰的实体图——背景已经退成氛围色了，这块才是「主角」。
   同一张 vod_pic，不占额外数据，也顺手把右半边的空白填掉。 */
.hero-art{position:absolute;z-index:2;right:clamp(26px,3.6vw,56px);
  bottom:clamp(26px,4vh,50px);width:clamp(118px,10.5vw,162px);aspect-ratio:2/3;
  object-fit:cover;border-radius:11px;border:1px solid var(--edge2);
  background:var(--surface);box-shadow:0 24px 64px rgba(0,0,0,.72);
  animation:heroIn .8s var(--ease) .08s backwards}
.hero-kicker{font-size:11.5px;letter-spacing:.22em;text-transform:uppercase;
  color:var(--ember);font-family:var(--mono);margin-bottom:12px}
.hero-ttl{font-family:var(--serif);font-weight:600;letter-spacing:-.01em;
  font-size:clamp(30px,4.6vw,62px);line-height:1.04;margin:0 0 14px;
  text-shadow:0 2px 24px rgba(0,0,0,.6)}
.hero-meta{display:flex;gap:9px;flex-wrap:wrap;align-items:center;
  font-family:var(--mono);font-size:12px;color:var(--dim)}
.hero-meta i{width:3px;height:3px;border-radius:50%;background:var(--faint)}
/* 导演/主演：Hero 里最「实」的一行内容，也顺手把横向空间铺开 */
.hero-crew{display:flex;flex-wrap:wrap;gap:6px 20px;margin:14px 0 0;
  font-size:12.5px;color:var(--dim);max-width:min(100%,640px);line-height:1.6}
.hero-crew i{font-style:normal;color:var(--faint);font-family:var(--mono);
  font-size:11.5px;letter-spacing:.08em;margin-right:8px}
.hero-desc{margin:14px 0 0;color:var(--dim);font-size:13.5px;line-height:1.7;
  max-width:56ch;display:-webkit-box;-webkit-line-clamp:2;-webkit-box-orient:vertical;
  overflow:hidden}
.hero-btns{display:flex;gap:11px;margin-top:clamp(18px,2vw,26px);flex-wrap:wrap}
.hero-btn{font-family:var(--sans);font-size:14px;padding:11px 24px;border-radius:999px;
  border:1px solid var(--ember);background:var(--ember);color:#fff;cursor:pointer;
  display:inline-flex;align-items:center;gap:8px;letter-spacing:.02em;
  transition:transform .16s var(--ease),background .16s var(--ease),box-shadow .16s var(--ease)}
.hero-btn:hover{transform:translateY(-2px);background:#e4633f;
  box-shadow:0 8px 26px rgba(212,85,58,.32)}
.hero-btn:active{transform:translateY(0)}
.hero-btn.ghost{background:rgba(20,18,16,.6);border-color:var(--edge2);color:var(--text);
  backdrop-filter:blur(6px)}
.hero-btn.ghost:hover{background:rgba(36,31,25,.9);border-color:var(--dim);box-shadow:none}
@keyframes kburns{
  from{transform:scale(var(--kb,1.05)) translate3d(0,0,0)}
  to{transform:scale(calc(var(--kb,1.05)*1.08)) translate3d(-1.6%,-1.2%,0)}}
@keyframes heroIn{from{opacity:0;transform:translateY(18px)}to{opacity:1;transform:none}}

/* ── 宽屏：卡片跟着屏幕一起长，别在 4K 上缩成一小条 ── */
@media(min-width:1440px){
  .grid{grid-template-columns:repeat(auto-fill,minmax(188px,1fr));gap:20px}
  .rs{width:170px}
  .rowscroll{gap:16px}
}
@media(min-width:1800px){
  .grid{grid-template-columns:repeat(auto-fill,minmax(204px,1fr));gap:22px}
  .rs{width:186px}
  .rowscroll{gap:18px}
  .secttl{font-size:17px}
}
@media(prefers-reduced-motion:reduce){
  .hero-bg{animation:none}
  .hero-in{animation:none}
}
.tools{display:flex;justify-content:space-between;align-items:center;gap:12px;margin:0 0 14px;
  font-size:12.5px;color:var(--dim);flex-wrap:wrap}
select,input[type=text],input[type=url]{background:rgba(255,255,255,.045);
  border:1px solid var(--gbd);color:var(--text);font-family:inherit;font-size:13px;
  padding:8px 11px;border-radius:var(--r-sm);outline:none;
  transition:border-color .18s,background .18s,box-shadow .2s var(--ease)}
select:focus,input:focus{border-color:rgba(224,90,60,.5);background:rgba(255,255,255,.075);
  box-shadow:0 0 0 4px rgba(224,90,60,.12)}
.btn{background:rgba(255,255,255,.065);border:1px solid var(--gbd);color:var(--text);
  font-family:inherit;font-size:13px;padding:8px 16px;border-radius:var(--r-pill);
  cursor:pointer;white-space:nowrap;
  transition:background .18s,border-color .18s,transform .16s var(--ease),box-shadow .2s var(--ease)}
.btn:hover{background:rgba(255,255,255,.11);border-color:var(--gbd2);transform:translateY(-1px);
  box-shadow:var(--e2)}
.btn:active{transform:translateY(0)}
.btn.pri{background:var(--ember);border-color:transparent;color:#fff;font-weight:500;
  box-shadow:0 4px 18px -5px rgba(224,90,60,.55)}
.btn.pri:hover{background:#e96a4b;box-shadow:0 9px 26px -7px rgba(224,90,60,.68)}
.btn:disabled{opacity:.45;cursor:default;transform:none;box-shadow:none}
.err{background:var(--ember-soft);border:1px solid rgba(224,90,60,.32);color:#f0a58d;
  padding:13px 16px;border-radius:var(--r-md);font-size:13.5px}
code{background:var(--surface2);border:1px solid var(--edge);border-radius:4px;padding:1px 6px;
  font-size:12px;font-family:var(--mono)}

.stats{display:grid;grid-template-columns:repeat(auto-fit,minmax(112px,1fr));gap:10px;margin:18px 0 4px}
.st{background:var(--surface);border:1px solid var(--edge);border-radius:var(--r);padding:14px 16px}
.st b{display:block;font-family:var(--serif);font-size:23px;font-weight:400;line-height:1.25}
.st small{color:var(--faint);font-size:11.5px}

.rowscroll{display:flex;gap:13px;overflow-x:auto;padding:4px 0 12px;scroll-snap-type:x proximity}
.rowscroll::-webkit-scrollbar{height:7px}
.rowscroll::-webkit-scrollbar-thumb{background:var(--edge);border-radius:4px}
.rs{flex:0 0 auto;width:148px;scroll-snap-align:start;cursor:pointer;border:none;
  background:none;padding:0;text-align:left;color:inherit;font-family:inherit;
  animation:cin .4s var(--ease) backwards;position:relative}
@keyframes cin{from{opacity:0;transform:translateY(12px)}to{opacity:1;transform:none}}
.rs .pos{position:absolute;left:7px;right:7px;bottom:7px;height:3px;background:rgba(0,0,0,.5);
  border-radius:2px;overflow:hidden;z-index:3}
.rs .pos i{display:block;height:100%;background:var(--ember);border-radius:2px}

.grid{display:grid;gap:16px;width:100%;
  grid-template-columns:repeat(auto-fill,minmax(164px,1fr));align-items:start}
.card{cursor:pointer;border:none;background:none;padding:0;text-align:left;color:inherit;
  font-family:inherit;display:block;width:100%;min-width:0;position:relative;
  animation:cin .42s var(--ease) backwards}
.poster{position:relative;width:100%;aspect-ratio:2/3;border-radius:var(--r-md);overflow:hidden;
  background:var(--surface);border:1px solid var(--edge);box-shadow:var(--e1);
  transition:border-color .24s var(--ease),transform .3s var(--ease-out),box-shadow .3s var(--ease-out)}
.card:hover .poster{border-color:var(--gbd2);transform:translateY(-6px);
  box-shadow:var(--e4),0 0 0 1px rgba(224,90,60,.15)}
.poster::after{content:'';position:absolute;inset:0;opacity:0;transition:opacity .24s;
  background:linear-gradient(180deg,transparent 42%,rgba(8,7,8,.88));pointer-events:none}
.card:hover .poster::after{opacity:1}
.poster img{width:100%;height:100%;object-fit:cover;display:block;
  transition:transform .55s var(--ease-out)}
.card:hover .poster img{transform:scale(1.05)}
.poster .ph{width:100%;height:100%;display:flex;align-items:center;justify-content:center;
  font-family:var(--serif);font-size:34px;color:var(--edge2);
  background:linear-gradient(150deg,var(--surface2),var(--surface))}
/* 这里刻意不用 backdrop-filter：每张卡片一个模糊层，首页 90 张就是 90 层，
   滚动必卡；深色半透明背景视觉几乎一样。 */
.poster .cnt{position:absolute;top:8px;right:8px;z-index:2;background:rgba(10,9,11,.85);
  border:1px solid var(--gbd);color:var(--text);font-size:10.5px;
  font-family:var(--mono);padding:2px 8px;border-radius:var(--r-pill)}
.poster .fbtn{position:absolute;top:7px;left:7px;z-index:4;width:26px;height:26px;
  border-radius:7px;background:rgba(12,11,9,.82);border:1px solid var(--edge2);color:var(--dim);
  display:flex;align-items:center;justify-content:center;cursor:pointer;padding:0;
  opacity:0;transition:.18s;font-size:13px}
.card:hover .fbtn{opacity:1}
.poster .fbtn.on{opacity:1;color:var(--gold);border-color:#4d3a18;background:rgba(51,38,15,.9)}
.poster .playic{position:absolute;left:50%;top:50%;transform:translate(-50%,-50%) scale(.85);
  width:44px;height:44px;border-radius:50%;z-index:3;background:var(--ember);
  display:flex;align-items:center;justify-content:center;opacity:0;
  transition:.22s var(--ease);box-shadow:0 6px 20px rgba(0,0,0,.5)}
.card:hover .playic{opacity:1;transform:translate(-50%,-50%) scale(1)}
.playic svg{width:16px;height:16px;fill:#fff;margin-left:2px}
.cname{margin:9px 1px 0;font-size:13.5px;line-height:1.45;display:-webkit-box;
  -webkit-line-clamp:2;-webkit-box-orient:vertical;overflow:hidden}
.cmeta{margin:3px 1px 0;font-size:11.5px;color:var(--faint);font-family:var(--mono);
  display:flex;gap:7px;flex-wrap:wrap}
.cmeta i{font-style:normal;color:var(--jade)}

.lvgrid{display:grid;grid-template-columns:repeat(auto-fill,minmax(148px,1fr));gap:9px}
.lv{background:var(--surface);border:1px solid var(--edge);border-radius:9px;padding:11px 13px;
  cursor:pointer;color:inherit;font-family:inherit;text-align:left;transition:.16s;
  display:flex;align-items:center;gap:9px;min-width:0;animation:cin .34s var(--ease) backwards}
.lv:hover{border-color:var(--ember);background:var(--surface2)}
.lv .nm{font-size:13.5px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;flex:1;min-width:0}
.lv .n{font-family:var(--mono);font-size:10.5px;color:var(--faint);background:var(--surface2);
  border:1px solid var(--edge);border-radius:4px;padding:0 5px;flex-shrink:0}
.gchips{display:flex;gap:6px;flex-wrap:wrap;margin-bottom:14px}
.gc{background:var(--surface);border:1px solid var(--edge);color:var(--dim);font-family:inherit;
  font-size:12.5px;padding:5px 12px;border-radius:20px;cursor:pointer;transition:.16s}
.gc:hover{border-color:var(--edge2);color:var(--text)}
.gc.on{background:var(--ember);border-color:var(--ember);color:#fff}

.mag{background:var(--surface);border:1px solid var(--edge);border-radius:9px;padding:11px 14px;
  margin-bottom:7px;display:flex;align-items:center;gap:14px;animation:cin .34s var(--ease) backwards;
  transition:border-color .18s,background .18s}
.mag:hover{border-color:var(--edge2);background:var(--surface2)}
.mag .info{flex:1;min-width:0}
.mag .t1{display:flex;align-items:center;gap:10px;margin-bottom:6px}
.mag .nm{font-size:14px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;flex:1;min-width:0}
.mag .hp{display:flex;align-items:center;gap:5px;font-size:11.5px;font-family:var(--mono);
  color:var(--dim);white-space:nowrap;flex-shrink:0}
.mag .hp b{color:var(--text);font-weight:500}
.hdot{width:6px;height:6px;border-radius:50%;flex-shrink:0}
.h-g{background:var(--jade);box-shadow:0 0 0 3px rgba(74,157,127,.13)}
.h-m{background:var(--gold)}.h-b{background:var(--ember)}.h-u{background:var(--faint)}
.mag .tags{display:flex;gap:4px;flex-wrap:wrap;align-items:center}
.tg{font-size:10.5px;padding:1px 6px;border-radius:4px;background:var(--surface2);
  color:var(--dim);border:1px solid var(--edge)}
.tg.res{background:#122a3d;color:#7cb7e8;border-color:#1d3f57}
.tg.sub{background:var(--jade-soft);color:#6fc4a3;border-color:#1d3a2e}
.tg.hdr{background:#33260f;color:#e0b155;border-color:#4d3a18}
.tg.hs{background:#241a33;color:#b28ee0;border-color:#3a2b52}
.tg.hh{font-family:var(--mono);opacity:.6}
.mag .ops{display:flex;align-items:center;gap:7px;flex-shrink:0}
.mag .btn{padding:6px 13px;font-size:12.5px}
.mag .lk{font-size:11.5px;color:var(--faint);text-decoration:none;white-space:nowrap}
.mag .lk:hover{color:var(--ember)}
.mag .magnet{width:100%;font-family:var(--mono);font-size:10.5px;color:var(--faint);
  word-break:break-all;line-height:1.65;max-height:0;overflow:hidden;
  transition:max-height .28s ease}
.mag.open .magnet{max-height:90px;margin-top:9px;overflow:auto}
.mag .exp{background:none;border:none;color:var(--faint);cursor:pointer;font-size:11px;
  padding:0;font-family:inherit;flex-shrink:0}
.mag .exp:hover{color:var(--dim)}

.empty{text-align:center;padding:64px 20px;color:var(--faint)}
.empty .ic{font-family:var(--serif);font-size:36px;color:var(--edge2);margin-bottom:12px;
  letter-spacing:.1em}
.empty p{margin:6px 0;font-size:13.5px}
.empty .hint{margin-top:16px;font-size:12.5px;line-height:2.1}
.empty .hint a{color:var(--dim);text-decoration:none;border-bottom:1px solid var(--edge2);
  padding-bottom:1px;margin:0 3px;transition:color .18s}
.empty .hint a:hover{color:var(--ember);border-color:var(--ember)}
.skl{border-radius:var(--r);overflow:hidden;background:var(--surface);border:1px solid var(--edge);
  aspect-ratio:2/3;position:relative}
.skl::after{content:'';position:absolute;inset:0;
  background:linear-gradient(100deg,transparent 20%,rgba(255,255,255,.035) 50%,transparent 80%);
  animation:shim 1.5s infinite}
@keyframes shim{from{transform:translateX(-100%)}to{transform:translateX(100%)}}
.msg{padding:11px 15px;border-radius:var(--r);margin:13px 0;font-size:13px;
  background:#fbf0da1a;color:var(--gold);border:1px solid #4d3a18}
.spin{display:inline-block;width:13px;height:13px;border:2px solid #3a342c;
  border-top-color:var(--ember);border-radius:50%;animation:sp .7s linear infinite;
  vertical-align:-2px;margin-right:7px}
@keyframes sp{to{transform:rotate(360deg)}}

.mask{position:fixed;inset:0;z-index:500;background:rgba(8,7,6,.84);backdrop-filter:blur(9px);
  display:flex;align-items:center;justify-content:center;padding:24px 18px;overflow:auto;
  animation:fade .22s ease-out}
@keyframes fade{from{opacity:0}to{opacity:1}}
.sheet{background:rgba(20,19,22,.82);
  backdrop-filter:var(--gblur);-webkit-backdrop-filter:var(--gblur);
  border:1px solid var(--gbd);border-radius:var(--r-xl);width:100%;
  max-width:820px;max-height:88vh;overflow:auto;animation:pop .3s var(--ease);
  box-shadow:var(--e5)}
@keyframes pop{from{opacity:0;transform:scale(.965) translateY(10px)}to{opacity:1;transform:none}}
.shead{display:flex;gap:16px;padding:20px 22px;border-bottom:1px solid var(--edge);
  position:sticky;top:0;background:var(--bg2);z-index:2}
.shead img{width:78px;aspect-ratio:2/3;object-fit:cover;border-radius:7px;
  border:1px solid var(--edge);flex-shrink:0}
.shead .ph2{width:78px;aspect-ratio:2/3;border-radius:7px;border:1px solid var(--edge);
  background:linear-gradient(150deg,var(--surface2),var(--surface));flex-shrink:0;
  display:flex;align-items:center;justify-content:center;font-family:var(--serif);
  font-size:26px;color:var(--edge2)}
.shead h3{margin:2px 0 6px;font-family:var(--serif);font-size:19px;font-weight:400;
  letter-spacing:.02em;line-height:1.4}
.shead .m{font-size:12.5px;color:var(--faint);font-family:var(--mono);display:flex;gap:10px;
  flex-wrap:wrap;align-items:center}
.close{position:absolute;right:16px;top:16px;width:32px;height:32px;border-radius:8px;
  background:var(--surface2);border:1px solid var(--edge);color:var(--dim);cursor:pointer;
  font-size:17px;line-height:1;display:flex;align-items:center;justify-content:center;
  transition:.16s}
.close:hover{background:var(--edge);color:var(--text)}
.srcgrp{padding:18px 22px}
.srclbl{font-size:11.5px;color:var(--faint);letter-spacing:.1em;font-family:var(--mono);
  margin:0 0 11px}
.src{display:flex;align-items:center;gap:13px;width:100%;text-align:left;background:var(--surface);
  border:1px solid var(--edge);border-radius:9px;padding:12px 15px;margin-bottom:8px;
  cursor:pointer;color:inherit;font-family:inherit;transition:.18s}
.src:hover{border-color:var(--ember);background:var(--surface2)}
.src.sel{border-color:var(--ember);background:var(--surface2)}
.src .sn{font-size:14px;flex:1}
.src .sc{font-size:11.5px;color:var(--faint);font-family:var(--mono)}
.eps{display:flex;flex-wrap:wrap;gap:7px;margin:4px 0 16px;max-height:290px;overflow:auto;
  padding-right:4px}
.ep{background:rgba(255,255,255,.04);border:1px solid var(--edge);color:var(--dim);
  font-family:var(--mono);font-size:12px;padding:8px 12px;border-radius:var(--r-sm);cursor:pointer;
  transition:border-color .16s,background .16s,color .16s,transform .14s var(--ease);
  min-width:54px;text-align:center}
.ep:hover{border-color:var(--ember);color:var(--text);background:rgba(224,90,60,.09)}
.ep.cur{background:var(--ember);border-color:transparent;color:#fff;
  box-shadow:0 3px 12px -3px rgba(224,90,60,.6)}
.bigplay{padding:12px 30px!important;font-size:14.5px!important;letter-spacing:.04em}


.task{background:var(--surface);border:1px solid var(--edge);border-radius:var(--r);
  padding:14px 16px;margin-bottom:9px}
.task .tn{display:flex;justify-content:space-between;gap:12px;align-items:center}
.task .tnm{font-size:13.5px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
.task .spd{font-family:var(--mono);font-size:12px;color:var(--dim);white-space:nowrap}
.pgw{height:4px;background:var(--edge);border-radius:3px;margin:10px 0 8px;overflow:hidden}
.pgw i{display:block;height:100%;background:var(--ember);border-radius:3px;
  transition:width .55s var(--ease)}
.pgw i.done{background:var(--jade)}.pgw i.stall{background:var(--gold)}
.task .mt{display:flex;gap:12px;flex-wrap:wrap;font-size:11.5px;color:var(--faint);
  font-family:var(--mono)}
.task .acts{display:flex;gap:6px;margin-top:10px;flex-wrap:wrap}
.tb{background:var(--surface2);border:1px solid var(--edge);color:var(--dim);
  font-family:inherit;font-size:12px;padding:5px 12px;border-radius:7px;cursor:pointer;
  transition:.16s}
.tb:hover{background:var(--edge);color:var(--text)}
.tb.del:hover{background:var(--ember-soft);border-color:#5c2a1e;color:#f0a58d}
.mono{font-family:var(--mono);font-size:11.5px;color:var(--faint);line-height:1.9;
  margin-top:8px;word-break:break-all}

.toast{position:fixed;left:50%;bottom:34px;transform:translate(-50%,14px);
  background:rgba(28,26,30,.8);
  backdrop-filter:var(--gblur);-webkit-backdrop-filter:var(--gblur);
  border:1px solid var(--gbd2);color:var(--text);
  padding:12px 22px;border-radius:var(--r-pill);font-size:13.5px;z-index:9500;opacity:0;
  transition:opacity .26s var(--ease),transform .26s var(--ease);pointer-events:none;
  box-shadow:var(--e4);max-width:86vw}
.toast.on{opacity:1;transform:translate(-50%,0)}
.toast.bad{border-color:rgba(224,90,60,.45);color:#f5b39c}
.toast.good{border-color:rgba(75,184,148,.45);color:#7fd0b1}

@media(prefers-reduced-motion:reduce){
  *{animation-duration:.01ms!important;transition-duration:.01ms!important}
}
@media(max-width:640px){
  header{padding:8px 10px 0}
  .hbar{padding:10px 12px 0;border-radius:var(--r-md)}
  main{padding:18px 13px 90px}
  .brand{font-size:17px;letter-spacing:.16em}
  .brandsub{display:none}
  .sbox{max-width:none;min-width:0;order:3;flex-basis:100%}
  .grid{grid-template-columns:repeat(auto-fill,minmax(104px,1fr));gap:11px}
  .rs{width:112px}
  .sheet{max-height:92vh}
  .shead{padding:16px;gap:12px}
  .shead img,.shead .ph2{width:62px}
  .srcgrp{padding:14px 16px}
  .lvgrid{grid-template-columns:repeat(auto-fill,minmax(124px,1fr))}
}
/* ═══ 详情页 ═══ */
.back{background:none;border:none;color:var(--dim);font-family:inherit;font-size:13.5px;
  cursor:pointer;padding:0 0 14px;display:inline-flex;align-items:center;gap:6px}
.back:hover{color:var(--text)}
/* .dtop 是定位容器：氛围底铺满，海报 + 片名 + 按钮叠在它上面。
   高度由内容自己撑开，不用再靠负 margin 猜数值——
   之前一改背景高度，海报就会被「糊住」或把首屏撑成一条空带。
   isolation 保证 .dhero 内的遮罩只作用在背景上，不会盖住内容。 */
.dtop{position:relative;border-radius:var(--r-xl);overflow:hidden;background:var(--bg2);
  isolation:isolate;margin-bottom:clamp(20px,2.6vw,34px);
  border:1px solid var(--gbd);box-shadow:var(--e4)}
.dhero{position:absolute;inset:0;z-index:0}
.dhero img{position:absolute;inset:0;width:100%;height:100%;object-fit:cover;
  transform:scale(var(--kb,1.75));
  filter:blur(56px) saturate(1.24) brightness(.4)}
.dhero img.bd-mid{--kb:1.22;filter:blur(22px) saturate(1.14) brightness(.48)}
.dhero img.bd-wide{--kb:1.03;filter:brightness(.64) saturate(1.02)}
.dhero::after{content:'';position:absolute;inset:0;
  /* 右上留一点点透光，但别把那块颜色照出来——模糊放大的海报色偏脏 */
  background:
    radial-gradient(104% 88% at 90% -2%,rgba(10,9,11,.08) 0%,
      rgba(10,9,11,.52) 56%,rgba(10,9,11,.86) 100%),
    linear-gradient(180deg,rgba(10,9,11,.2) 0%,rgba(10,9,11,.5) 44%,
      rgba(10,9,11,.88) 78%,var(--bg) 100%)}
.dmain{position:relative;z-index:2;display:flex;align-items:flex-start;
  gap:clamp(20px,2.2vw,34px);
  padding:clamp(62px,9.5vh,116px) clamp(22px,2.2vw,34px) clamp(24px,3.4vh,40px)}
.dposter{width:clamp(140px,12.5vw,182px);aspect-ratio:2/3;border-radius:11px;overflow:hidden;
  flex-shrink:0;border:1px solid var(--edge2);box-shadow:0 14px 44px rgba(0,0,0,.65);
  background:var(--surface)}
.dposter img{width:100%;height:100%;object-fit:cover;display:block}
.dposter .ph{width:100%;height:100%;display:flex;align-items:center;justify-content:center;
  font-family:var(--serif);font-size:38px;color:var(--edge2);
  background:linear-gradient(150deg,var(--surface2),var(--surface))}
.dinfo{flex:1;min-width:0;padding-top:4px}
.dinfo h2{font-family:var(--serif);font-size:clamp(25px,2.3vw,34px);font-weight:400;
  margin:0 0 10px;letter-spacing:.02em;line-height:1.24}
.dscore{margin-left:12px;font-family:var(--mono);font-size:15px;letter-spacing:0;
  color:var(--ember);vertical-align:4px;white-space:nowrap}
.dmeta{font-family:var(--mono);font-size:12px;color:var(--faint);display:flex;gap:9px;
  flex-wrap:wrap;margin-bottom:12px;align-items:center}
.dmeta .pill{background:rgba(255,255,255,.075);border:1px solid var(--gbd);
  border-radius:var(--r-pill);padding:3px 11px}
/* 导演 / 主演 —— 这些字段采集站一直有，之前没提取，详情页才会空荡荡 */
.dcrew{display:flex;flex-direction:column;gap:5px;margin:13px 0 0;
  font-size:12.5px;color:var(--dim);line-height:1.55}
.dcrew i{font-style:normal;color:var(--faint);font-family:var(--mono);
  font-size:11.5px;letter-spacing:.08em;margin-right:9px}
.ddesc{font-size:13.5px;color:var(--dim);line-height:1.85;margin:13px 0 16px;
  max-width:min(100%,880px);
  display:-webkit-box;-webkit-line-clamp:5;-webkit-box-orient:vertical;overflow:hidden}
.dacts{display:flex;gap:9px;flex-wrap:wrap;align-items:center}
.lines{margin-top:30px}
.line{display:flex;align-items:center;gap:12px;padding:13px 16px;
  background:rgba(255,255,255,.035);
  border:1px solid var(--edge);border-radius:var(--r-md);margin-bottom:9px;cursor:pointer;
  transition:border-color .18s,background .18s,transform .16s var(--ease);
  font-family:inherit;color:inherit;
  text-align:left;width:100%;animation:cin .32s var(--ease) backwards}
.line:hover{border-color:var(--gbd2);background:rgba(255,255,255,.06);transform:translateX(3px)}
.line.on{border-color:var(--ember);background:var(--surface2)}
.line .ln{flex:1;font-size:14px;min-width:0;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
.line .lc{font-family:var(--mono);font-size:12px;color:var(--faint);flex-shrink:0}
.line .ls{font-size:11px;padding:1px 7px;border-radius:5px;background:var(--jade-soft);
  color:#6fc4a3;border:1px solid #1d3a2e;flex-shrink:0}
.line.on .ls{background:var(--ember-soft);color:#f0a58d;border-color:#5c2a1e}
.epgrid{display:grid;grid-template-columns:repeat(auto-fill,minmax(68px,1fr));gap:8px;
  max-height:430px;overflow:auto;padding-right:4px;margin-top:12px}
.epgrid .ep{font-size:12.5px;padding:8px 6px;min-width:0;
  overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
.sec2{font-size:13px;color:var(--faint);letter-spacing:.08em;font-family:var(--mono);
  margin:26px 0 10px;display:flex;justify-content:space-between;align-items:center}
@media(max-width:640px){
  .dmain{gap:14px;padding:72px 12px 22px}
  .dposter{width:104px}
  .dinfo{padding-top:2px}
  .dinfo h2{font-size:19px}
  .ddesc{-webkit-line-clamp:3}
  .epgrid{grid-template-columns:repeat(auto-fill,minmax(58px,1fr));gap:6px}
}
/* ═══ 播放页：左播放器 + 右侧栏（不覆盖页面，随时可返回）═══ */
#plwrap{
  position:fixed;inset:0;z-index:900;background:#080706;display:none;
}
#plwrap.on{display:grid;grid-template-columns:minmax(0,1fr) 368px;
  grid-template-rows:100%;animation:pl-in .24s var(--ease)}
@keyframes pl-in{from{opacity:0}to{opacity:1}}
#plhost{position:relative;background:#000;min-width:0}
#plside{
  display:flex;flex-direction:column;min-height:0;
  background:rgba(18,17,20,.78);
  backdrop-filter:var(--gblur);-webkit-backdrop-filter:var(--gblur);
  border-left:1px solid var(--gbd);
}
#plhead{
  display:flex;align-items:center;gap:10px;padding:14px 16px;
  border-bottom:1px solid var(--edge);flex-shrink:0;
}
#plttl{flex:1;font-family:var(--serif);font-size:15px;overflow:hidden;
  text-overflow:ellipsis;white-space:nowrap}
#plsub{font-size:11px;color:var(--faint);font-family:var(--mono);white-space:nowrap}
.ib{width:30px;height:30px;border-radius:7px;background:var(--surface2);
  border:1px solid var(--edge);color:var(--dim);cursor:pointer;display:flex;
  align-items:center;justify-content:center;transition:.16s;flex-shrink:0}
.ib:hover{background:var(--edge);color:var(--text)}
.ib svg{width:15px;height:15px;fill:currentColor}
.ib.on{background:var(--ember);border-color:var(--ember);color:#fff}
/* 选集导航 */
#plnav{
  display:flex;align-items:center;gap:8px;padding:11px 16px;
  border-bottom:1px solid var(--edge);flex-shrink:0;
}
#plnav .ep-cur{flex:1;font-size:13px;color:var(--dim);min-width:0;
  overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
#plnav .ep-cur b{color:var(--text);font-weight:500}
#plnav button{background:var(--surface2);border:1px solid var(--edge);color:var(--text);
  font-family:inherit;font-size:12.5px;padding:6px 13px;border-radius:7px;cursor:pointer;
  transition:.16s;white-space:nowrap}
#plnav button:hover:not(:disabled){background:var(--ember);border-color:var(--ember);color:#fff}
#plnav button:disabled{opacity:.3;cursor:default}
/* 侧栏滚动区 */
#plbody{flex:1;overflow:auto;padding:14px 16px 20px;min-height:0}
.blk{margin-bottom:20px}
.blk h5{margin:0 0 9px;font-size:11.5px;color:var(--faint);font-family:var(--mono);
  letter-spacing:.1em;font-weight:400;display:flex;justify-content:space-between}
.blk h5 span{color:var(--faint)}
.lrow{
  display:flex;align-items:center;gap:10px;width:100%;text-align:left;
  background:var(--surface);border:1px solid var(--edge);border-radius:9px;
  padding:10px 13px;margin-bottom:7px;cursor:pointer;color:inherit;
  font-family:inherit;transition:.16s;font-size:13.5px;
}
.lrow:hover{border-color:var(--edge2)}
.lrow.on{border-color:var(--ember);background:var(--surface2)}
.lrow .nm{flex:1;min-width:0;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
.lrow .ct{font-family:var(--mono);font-size:11.5px;color:var(--faint);flex-shrink:0}
.lrow .bd{font-size:10.5px;padding:1px 6px;border-radius:4px;flex-shrink:0;
  background:var(--jade-soft);color:#6fc4a3;border:1px solid #1d3a2e}
.lrow.on .bd{background:var(--ember-soft);color:#f0a58d;border-color:#5c2a1e}
#plside .epgrid{display:grid;grid-template-columns:repeat(auto-fill,minmax(54px,1fr));
  gap:6px;max-height:none;overflow:visible;padding:0;margin:0}
#plside .epgrid .ep{font-size:11.5px;padding:7px 4px;min-width:0;
  overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
#plside .epgrid .ep.cur{background:var(--ember);border-color:var(--ember);color:#fff}
/* ── 浏览页：分类 / 年份 chips ── */
.browsebar{display:flex;flex-direction:column;gap:10px;margin:0 0 24px}
.chips{display:flex;gap:7px;overflow-x:auto;scrollbar-width:none;padding:2px 0}
.chips::-webkit-scrollbar{display:none}
.chip{flex:0 0 auto;background:rgba(255,255,255,.05);border:1px solid var(--gbd);
  color:var(--dim);font-family:inherit;font-size:13px;padding:7px 16px;
  border-radius:var(--r-pill);cursor:pointer;white-space:nowrap;
  transition:background .18s,border-color .18s,color .18s,transform .16s var(--ease)}
.chip:hover{background:rgba(255,255,255,.09);color:var(--text);border-color:var(--gbd2)}
.chip.on{background:var(--ember);border-color:transparent;color:#fff;font-weight:500;
  box-shadow:0 4px 16px -5px rgba(224,90,60,.55)}
.chips .lbl{flex:0 0 auto;align-self:center;color:var(--faint);font-size:11.5px;
  font-family:var(--mono);letter-spacing:.08em;margin-right:4px}
.bmore{display:block;margin:32px auto 0;padding:11px 36px}

/* ── 搜索下拉：最近搜过 + 热门 ── */
.sugg{position:absolute;left:0;right:0;top:calc(100% + 9px);z-index:300;
  background:rgba(20,19,22,.86);
  backdrop-filter:var(--gblur);-webkit-backdrop-filter:var(--gblur);
  border:1px solid var(--gbd);border-radius:var(--r-lg);box-shadow:var(--e4);
  padding:15px 17px;display:none;animation:pop .22s var(--ease)}
.sugg.on{display:block}
.sg-h{font-size:11px;color:var(--faint);font-family:var(--mono);letter-spacing:.12em;
  margin:0 0 10px}
.sg-h+.sg-h{margin-top:16px}
.sg-w{display:flex;flex-wrap:wrap;gap:7px}
.sg-w button{background:rgba(255,255,255,.05);border:1px solid var(--gbd);color:var(--dim);
  font-family:inherit;font-size:12.5px;padding:6px 14px;border-radius:var(--r-pill);
  cursor:pointer;transition:background .16s,color .16s,border-color .16s}
.sg-w button:hover{background:rgba(224,90,60,.16);border-color:rgba(224,90,60,.42);
  color:var(--text)}

/* ── 演职员可点 → 直接搜这个人的作品 ── */
.crl{background:none;border:none;padding:0;font-family:inherit;font-size:12.5px;
  color:var(--dim);cursor:pointer;transition:color .16s}
.crl:hover{color:var(--ember);text-decoration:underline}
.dcrew u{text-decoration:none;color:var(--faint);margin:0 6px}

/* 窄屏放不下右侧实体图，撤掉它并让文字区重新占满 */
@media(max-width:860px){
  .hero-art{display:none}
  .hero-in{max-width:100%}
}
@media(max-width:900px){
  #plwrap.on{grid-template-columns:1fr;grid-template-rows:auto 1fr}
  #plhost{aspect-ratio:16/9}
  #plside{border-left:none;border-top:1px solid var(--edge2)}
}
</style>
</head>
<body>

<div class="bgmesh" aria-hidden="true"></div>

<header><div class="hbar">
  <div class="brandrow">
    <a class="brand" href="#">放映<em>室</em></a>
    <span class="brandsub">PRIVATE SCREENING</span>
    <span class="spacer"></span>
    <div class="sbox">
      <input id="kw" placeholder="搜片名…" autocomplete="off" enterkeyhint="search">
      <span class="kbd">↵</span>
      <div class="sugg" id="sugg"></div>
    </div>
  </div>
  <nav>
    <button data-v="home" class="on">首页</button>
    <button data-v="browse">浏览</button>
    <button data-v="live">直播</button>
    <button data-v="magnet">下载收藏</button>
    <button data-v="tasks">任务<span class="bdg" id="tbdg" style="display:none"></span></button>
    <button data-v="online" id="navonline" style="display:none"></button>
  </nav>
</div></header>

<main><div id="view"></div></main>

<div id="plwrap">
  <div id="plhost"></div>
  <div id="plside">
    <div id="plhead">
      <span id="plttl"></span>
      <span id="plsub"></span>
      <button class="ib" id="plfav" title="收藏"><svg viewBox="0 0 24 24"><path d="M12 17.3l6.2 3.7-1.6-7L22 9.2l-7.2-.6L12 2 9.2 8.6 2 9.2l5.4 4.8-1.6 7z"/></svg></button>
      <button class="ib" id="plclose" title="返回"><svg viewBox="0 0 24 24"><path d="M19 6.4L17.6 5 12 10.6 6.4 5 5 6.4 10.6 12 5 17.6 6.4 19 12 13.4 17.6 19 19 17.6 13.4 12z"/></svg></button>
    </div>
    <div id="plnav">
      <span class="ep-cur" id="plnavcur"></span>
      <button id="plprev">上集</button>
      <button id="plnext">下集</button>
    </div>
    <div id="plbody">
      <div class="blk" id="blkline" style="display:none">
        <h5>播放线路 <span id="lcount"></span></h5>
        <div id="llist"></div>
      </div>
      <div class="blk" id="blkep" style="display:none">
        <h5>选集 <span id="ecount"></span></h5>
        <div class="epgrid" id="egrid"></div>
      </div>
    </div>
  </div>
</div>

<div class="toast" id="toast"></div>
<script src="/static/hls.js"></script>
<script src="/static/artplayer.js"></script>
<script>
"use strict";
var $=function(s,r){return (r||document).querySelector(s)};
var $$=function(s,r){return Array.prototype.slice.call((r||document).querySelectorAll(s))};
var kwEl=$('#kw'),viewEl=$('#view'),toastEl=$('#toast'),tbdg=$('#tbdg');
var VIEW='home', ST={}, TASKS=[], ART=null, HLS=null, CUR=null, FAVSET={};

function esc(s){return String(s==null?'':s).replace(/[&<>"']/g,function(c){
  return {'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c];});}
function q(s){return encodeURIComponent(s==null?'':s)}
function toast(m,k){toastEl.textContent=m;toastEl.className='toast on '+(k||'');
  clearTimeout(toast._t);toast._t=setTimeout(function(){toastEl.className='toast';},2400);}
function fmt(n){if(!n||n<0)return '—';
  if(n>=1073741824)return (n/1073741824).toFixed(2)+'GB';
  if(n>=1048576)return (n/1048576).toFixed(0)+'MB';
  if(n>=1024)return (n/1024).toFixed(0)+'KB';return n+'B';}
function spd(n){if(!n)return '—';
  if(n>=1048576)return (n/1048576).toFixed(2)+' MB/s';
  if(n>=1024)return (n/1024).toFixed(0)+' KB/s';return n+' B/s';}
function eta(s){if(s<0)return '—';if(s<60)return s+' 秒';
  if(s<3600)return Math.floor(s/60)+' 分';
  if(s<86400)return (s/3600).toFixed(1)+' 时';return (s/86400).toFixed(1)+' 天';}
function posterHTML(pic,name){
  if(pic)return '<img loading="lazy" src="'+esc(pic)+'" alt="" onerror="this.style.display=\'none\'">';
  return '<div class="ph">'+esc((name||'?').charAt(0))+'</div>';

}
function phFallback(img,name){
  var d=document.createElement('div');
  d.className='ph'; d.textContent=(name||'?').charAt(0);
  img.parentNode.replaceChild(d,img);
}

$$('nav button').forEach(function(b){
  b.onclick=function(){
    VIEW=b.getAttribute('data-v');
    $$('nav button').forEach(function(x){x.classList.toggle('on',x===b)});
    if(VIEW==='home'){loadHome();return;}
    if(VIEW==='browse'){loadBrowse();return;}
    if(VIEW==='live'){loadLive();return;}
    if(VIEW==='tasks'){renderTasks();return;}
    if(VIEW==='detail'){ if(ST.detail)renderDetail(ST.detail); return; }
    var s=ST[VIEW];
    if(!s||!s.items){renderIdle();return;}
    VIEW==='online'?renderOnline(s.items):renderMagnet(s.items);
  };
});
function setView(v){
  VIEW=v;
  // 「在线看」不再占一个常驻 tab：顶部搜索框就是入口，
  // 只有真的搜出结果时才临时冒出这个按钮
  var nb=document.getElementById('navonline');
  if(nb){
    nb.style.display=(v==='online')?'':'none';
    if(v==='online')nb.textContent='搜索结果';
  }
  $$('nav button').forEach(function(x){x.classList.toggle('on',x.getAttribute('data-v')===v)});
}

/* ───── 首页 ───── */
function loadHome(){
  viewEl.innerHTML='<h2 class="secttl">首页</h2><p class="sub">正在读取记录…</p>'+
    '<div class="grid">'+Array(6).fill('<div class="skl"></div>').join('')+'</div>';
  fetch('/api/home').then(function(r){return r.json()}).then(function(d){
    // 推荐缓存随首页一起回来了，直接出画面，绝不先白板
    ST.home=d; renderHome(d);
    // 缓存是旧的或还没有 → 后台拉新的，到了只替换那一块
    if(!d.rec||d.rec.loading||d.rec.stale){
      fetch('/api/recommend').then(function(r){return r.json()}).then(function(rec){
        if(ST.home)ST.home.rec=rec;
        fillRec(rec);
      }).catch(function(){});
    }
  }).catch(function(){viewEl.innerHTML='<div class="err">加载首页失败</div>';});
}
function renderHome(d){
  var st=d.stat||{}, hist=d.history||[], fav=d.fav||[], rec=d.rec||{};
  FAVSET={}; fav.forEach(function(f){FAVSET[f.kind+'|'+f.key]=1;});

  // Hero：用一个真实条目做整页的视觉焦点，每次进来随机换一部。
  // 容器常驻（没数据就放骨架），避免后面补插时把下方内容顶下去
  var hero=pickHero(rec);
  HERO=hero;
  var h=heroHTML(hero);

  // 统计压成一行（原来是 4 个方卡片，又占地方又像模板）
  h+='<div class="statline">'+
     '<span><b>'+st.titles+'</b>看过</span>'+
     '<span><b>'+st.plays+'</b>次播放</span>'+
     '<span><b>'+st.favs+'</b>收藏</span>'+
     '<span><b>'+st.live+'</b>直播频道</span>'+
     '</div>';

  var cont=hist.filter(function(x){return x.position>0;}).slice(0,18);
  if(cont.length){
    h+='<div class="sec"><h2 class="secttl">继续观看<span class="cnt">'+cont.length+'</span></h2>'+
       '<p class="sub">从上次停下的地方接着看</p><div class="rowscroll">';
    cont.forEach(function(x,i){
      var pct=x.duration>0?Math.min(100,x.position/x.duration*100):0;
      h+='<button class="rs" data-h="'+i+'" style="animation-delay:'+Math.min(i*26,300)+'ms">'+
         '<div class="poster">'+posterHTML(x.cover,x.title)+
         (pct>0?'<div class="pos"><i style="width:'+pct.toFixed(1)+'%"></i></div>':'')+
         '</div><div class="cname">'+esc(x.title)+'</div>'+
         '<div class="cmeta">'+(x.ep?'<span>'+esc(x.ep)+'</span>':'')+
         (pct>0?'<i>'+pct.toFixed(0)+'%</i>':'')+'</div></button>';
    });
    h+='</div></div>';
  }

  if(fav.length){
    h+='<div class="sec"><h2 class="secttl">我的收藏<span class="cnt">'+fav.length+'</span></h2>'+
       '<p class="sub">点 ★ 收藏的片子</p><div class="rowscroll">';
    fav.slice(0,18).forEach(function(x,i){
      h+='<button class="rs" data-f="'+i+'" style="animation-delay:'+Math.min(i*26,300)+'ms">'+
         '<div class="poster">'+posterHTML(x.cover,x.title)+
         '</div><div class="cname">'+esc(x.title)+'</div>'+
         '<div class="cmeta"><span>'+esc(x.kind)+'</span></div></button>';
    });
    h+='</div></div>';
  }

  h+='<div id="recbox"></div>';

  var hasRec=rec&&((rec.cats&&rec.cats.length)||(rec.liked&&rec.liked.length)||
                   (rec.latest&&rec.latest.length));
  if(!cont.length&&!fav.length&&!hasRec){
    h+='<div class="empty"><div class="ic">起幕</div><p>还没有观影记录</p>'+
       '<div class="hint">用顶部的搜索框搜一部片子，记录会自动出现在这里</div></div>';
  }
  viewEl.innerHTML=h;
  bindHero();

  $$('[data-h]',viewEl).forEach(function(b){
    b.onclick=function(){
      var x=cont[+b.getAttribute('data-h')];
      openFromRecord(x,x.position);      // 历史：接着上次的位置看
    };});
  $$('[data-f]',viewEl).forEach(function(b){
    b.onclick=function(){openFromRecord(fav[+b.getAttribute('data-f')],0);};});

  $$('[data-go]',viewEl).forEach(function(a){
    a.onclick=function(e){e.preventDefault();setView(a.getAttribute('data-go'));renderIdle();};});
  if(d.rec)fillRec(d.rec);
}

function cardHTML(it,attr,i,metaHTML){
  // 只有剧集类的才标集数：采集站把一部电影的多个播放地址都塞进 eps，
  // 直接显示 count 会出现「电影 · 18 集」这种很怪的东西
  var series=/剧|动漫|综艺|纪录|动画/.test(it.type||'');
  var badge=(it.site_count?'<span class="cnt">'+it.site_count+'源</span>'
            :(series&&it.count>1?'<span class="cnt">'+it.count+'集</span>':''));
  return '<button class="card" '+attr+'="'+i+'" style="animation-delay:'+
    Math.min(i*22,400)+'ms"><div class="poster">'+posterHTML(it.pic,it.name)+
    badge+
    '<span class="playic"><svg viewBox="0 0 24 24"><path d="M8 5v14l11-7z"/></svg></span>'+
    '</div><div class="cname">'+esc(it.name)+'</div>'+
    '<div class="cmeta">'+(it.year&&it.year!=='0'?'<span>'+esc(it.year)+'</span>':'')+
    (it.type?'<span>'+esc(it.type)+'</span>':'')+metaHTML+'</div></button>';
}

/* ── Hero：首页视觉焦点 ── */
var HERO=null;
function pickHero(rec){
  var pool=[];
  (rec.cats||[]).forEach(function(c){pool=pool.concat(c.items||[]);});
  (rec.liked||[]).forEach(function(x){pool.push(x);});
  (rec.latest||[]).forEach(function(x){pool.push(x);});
  pool=pool.filter(function(x){return x&&x.pic;});
  if(!pool.length)return null;
  // 有简介的优先——Hero 里那句简介是撑住版面的一行
  var rich=pool.filter(function(x){return x.desc;});
  if(rich.length>=4)pool=rich;
  return pool[Math.floor(Math.random()*Math.min(pool.length,10))];
}
function heroInner(it){
  var meta=[];
  if(it.year&&it.year!=='0')meta.push(esc(it.year));
  if(it.type)meta.push(esc(it.type));
  if(it.score&&it.score!=='0')meta.push('★ '+esc(it.score));
  if(it.count>1)meta.push(it.count+' 集');
  else if(it.site_count)meta.push(it.site_count+' 个源');
  if(it.site)meta.push(esc(it.site));
  return '<img class="hero-bg" src="'+esc(it.pic)+'" alt="" decoding="async" '+
      'fetchpriority="high" onerror="this.style.display=\'none\'">'+
    '<div class="hero-veil"></div>'+
    '<img class="hero-art" src="'+esc(it.pic)+'" alt="" decoding="async" '+
      'onerror="this.style.display=\'none\'">'+
    '<div class="hero-in">'+
      '<div class="hero-kicker">今晚放什么</div>'+
      '<h1 class="hero-ttl">'+esc(it.name)+'</h1>'+
      '<div class="hero-meta">'+
        meta.map(function(x){return '<span>'+x+'</span>';}).join('<i></i>')+
      '</div>'+
      (it.director||it.actor?'<div class="hero-crew">'+
        (it.director?'<span><i>导演</i>'+crewLinks(it.director)+'</span>':'')+
        (it.actor?'<span><i>主演</i>'+crewLinks(it.actor)+'</span>':'')+
      '</div>':'')+
      (it.desc?'<p class="hero-desc">'+esc(it.desc)+'</p>':'')+
      '<div class="hero-btns">'+
        '<button class="hero-btn" id="heroplay">立即播放</button>'+
        '<button class="hero-btn ghost" id="herodetail">查看详情</button>'+
      '</div>'+
    '</div>';
}
/* 缓存还没到时的占位：结构跟真 Hero 对齐，
   这样后面替换内容不会把下方内容顶下去（CLS） */
function heroSkeleton(){
  return '<div class="hero-veil"></div><div class="hero-in">'+
    '<div class="hero-kicker">今晚放什么</div>'+
    '<div class="skl" style="height:clamp(30px,4.6vw,58px);width:min(430px,72%);'+
      'margin:0 0 15px;border-radius:6px"></div>'+
    '<div class="skl" style="height:13px;width:190px;border-radius:4px"></div>'+
    '<div class="skl" style="height:12px;width:min(360px,72%);margin:15px 0 0;border-radius:4px"></div>'+
    '<div class="skl" style="height:12px;width:min(560px,86%);margin:16px 0 0;border-radius:4px"></div>'+
    '<div class="skl" style="height:12px;width:min(430px,64%);margin:9px 0 0;border-radius:4px"></div>'+
    '<div class="skl" style="height:40px;width:224px;margin:22px 0 0;border-radius:999px"></div>'+
    '</div>';
}
function heroHTML(it){
  return '<div class="hero" id="herobox">'+
         (it?heroInner(it):heroSkeleton())+'</div>';
}
/* ── 背景图自适应 ──
   采集站的 vod_pic 大多是 270×399 这种竖版小图，直接铺满横幅＝放大 6 倍，
   出来是一块糊掉的色斑。所以按原图真实宽高比分三档：
     ≥1.45 横版剧照 → 保留细节（轻微位移）
     ≥0.95 接近方图 → 中度模糊
     其余 竖版小图   → 重模糊，只当氛围色底
   CSS 默认就是「竖版」那一档，所以 JS 没跑到也不会退化成糊图。 */
function bdKind(im){
  var w=im.naturalWidth||0, h=im.naturalHeight||0;
  if(!w||!h)return '';
  var r=w/h;
  return r>=1.45?'bd-wide':(r>=0.95?'bd-mid':'');
}
function fitBackdrop(im){
  if(!im||im.getAttribute('data-bd'))return;
  function go(){
    if(!im.naturalWidth||im.getAttribute('data-bd'))return;
    im.setAttribute('data-bd','1');
    var k=bdKind(im);
    if(k)im.classList.add(k);
  }
  if(im.complete)go(); else im.addEventListener('load',go);
  im.addEventListener('error',function(){im.style.visibility='hidden';});
}
/* Hero 右侧那张实体图：横图就别硬裁成 2:3，换成 16:9 才不丢内容 */
function fitArt(im){
  if(!im||im.getAttribute('data-bd'))return;
  function go(){
    if(!im.naturalWidth||im.getAttribute('data-bd'))return;
    im.setAttribute('data-bd','1');
    if(im.naturalWidth/im.naturalHeight>=1.3)im.style.aspectRatio='16 / 9';
  }
  if(im.complete)go(); else im.addEventListener('load',go);
  im.addEventListener('error',function(){im.style.display='none';});
}
function fitBackdrops(root){
  var r=root||document, i, list=r.querySelectorAll('img.hero-bg,.dhero>img');
  for(i=0;i<list.length;i++)fitBackdrop(list[i]);
  list=r.querySelectorAll('img.hero-art');
  for(i=0;i<list.length;i++)fitArt(list[i]);
}

function bindHero(){
  var hp=document.getElementById('heroplay'), hd=document.getElementById('herodetail');
  if(hp&&HERO)hp.onclick=function(){goDetail(HERO.name,HERO.year||'',null,1);};
  if(hd&&HERO)hd.onclick=function(){goDetail(HERO.name,HERO.year||'');};
  fitBackdrops();
  bindCrew();
}

/* 推荐区：先出缓存，再按分类渲染（电影 / 剧集 / 动漫 / 综艺 / 纪录片） */
var RECITEM=[];
function fillRec(rec){
  var box=document.getElementById('recbox');
  if(!box||!rec)return;
  RECITEM=[];
  var h='';

  function rows(title,sub,items){
    if(!items||!items.length)return '';
    var s='<div class="sec"><h2 class="secttl">'+esc(title)+'</h2>'+
          '<p class="sub">'+esc(sub)+'</p><div class="rowscroll">';
    items.forEach(function(it){
      var idx=RECITEM.length; RECITEM.push(it);
      var badge=it.count>1?(it.count+'集'):
                (it.ep_count>1?it.ep_count+'集':
                (it.site_count?it.site_count+'源':''));
      s+='<button class="rs" data-rec="'+idx+'"><div class="poster">'+
         posterHTML(it.pic,it.name)+
         (badge?'<span class="cnt">'+badge+'</span>':'')+
         '<span class="playic"><svg viewBox="0 0 24 24"><path d="M8 5v14l11-7z"/></svg></span>'+
         '</div><div class="cname">'+esc(it.name)+'</div>'+
         '<div class="cmeta">'+
         (it.year&&it.year!=='0'?'<span>'+esc(it.year)+'</span>':'')+
         (it.site?'<span>'+esc(it.site)+'</span>':(it.type?'<span>'+esc(it.type)+'</span>':''))+
         '</div></button>';
    });
    return s+'</div></div>';
  }

  h+=rows('为你推荐',
          (rec.taste&&rec.taste.length)?('根据你的口味：'+rec.taste.slice(0,3).join('、'))
                                       :'先看看这些',
          rec.liked);
  var cats=rec.cats||[];
  cats.forEach(function(c){ h+=rows(c.cat,'最近更新 · '+c.count+' 部',c.items); });
  // 老缓存没有分类字段时，退回原来的「最近更新」整体一块
  if(!cats.length&&rec.latest&&rec.latest.length)
    h+=rows('最近更新','各源站最近更新的新片',rec.latest);

  if(rec.loading)
    h+='<div class="hint" style="padding:8px 2px">正在后台拉取最新片单，稍后自动补上…</div>';
  box.innerHTML=h;

  $$('[data-rec]',box).forEach(function(b){
    b.onclick=function(){
      var it=RECITEM[+b.getAttribute('data-rec')];
      if(!it)return;
      // 搜索结果自带线路可以直接开，最近更新条目要先聚合一次
      if(it.sources&&it.sources.length)openDetail(it);
      else goDetail(it.name,it.year||'');
    };});

  // 冷启动时缓存还没到，Hero 那会儿是骨架；现在把真内容填进去。
  // 只换内部 HTML，容器还在原位，所以不会造成布局跳动
  if(!HERO&&VIEW==='home'){
    var hv=pickHero(rec);
    var hb=document.getElementById('herobox');
    if(hv&&hb){
      HERO=hv;
      hb.innerHTML=heroInner(hv);
      bindHero();
    }
  }
}

function renderIdle(){
  if(VIEW==='online'){
    viewEl.innerHTML='<h2 class="secttl">在线看</h2>'+
      '<p class="sub">聚合多个采集源 · 直接播放，不用等下载</p>'+
      '<div class="empty"><div class="ic">放映</div><p>在上面搜一部片子，立刻就能看</p>'+
      '<div class="hint">试试：<a href="#" data-s="沙丘">沙丘</a>'+
      '<a href="#" data-s="流浪地球">流浪地球</a><a href="#" data-s="繁花">繁花</a>'+
      '<a href="#" data-s="奥本海默">奥本海默</a><a href="#" data-s="三体">三体</a></div></div>';
  }else{
    viewEl.innerHTML='<h2 class="secttl">下载收藏</h2>'+
      '<p class="sub">磁力搜索 · 带做种数预览 · 下载后进 Jellyfin 媒体库</p>'+
      '<div class="empty"><div class="ic">收藏</div>'+
      '<p>想长期保存的片子，用这里搜磁力下载</p>'+
      '<div class="hint">先用顶部搜索框搜到能播的源，再回来下磁力长期保存</div></div>';
  }
  $$('[data-s]',viewEl).forEach(function(a){
    a.onclick=function(e){e.preventDefault();kwEl.value=a.getAttribute('data-s');doSearch();};});
}
function skeleton(n){var h='<div class="grid">';
  for(var i=0;i<(n||12);i++)h+='<div class="skl" style="animation-delay:'+(i*26)+'ms"></div>';
  return h+'</div>';}
function sklGrid(n,id){var h='<div class="grid" id="'+id+'">';
  for(var i=0;i<(n||12);i++)h+='<div class="skl" style="animation-delay:'+(i*26)+'ms"></div>';
  return h+'</div>';}

/* ───── 搜索下拉：最近搜过 + 热门 ─────
   数据现成的——searches 表一直在记搜索词，之前只用来算口味，没露出来。 */
function closeSugg(){
  var e=document.getElementById('sugg'); if(e)e.classList.remove('on');
}
function sgBtn(s){return '<button data-s="'+esc(s)+'">'+esc(s)+'</button>';}
function openSugg(){
  var el=document.getElementById('sugg'); if(!el)return;
  fetch('/api/suggest').then(function(r){return r.json()}).then(function(d){
    var h='';
    if(d.recent&&d.recent.length)
      h+='<div class="sg-h">最近搜过</div><div class="sg-w">'+d.recent.map(sgBtn).join('')+'</div>';
    if(d.hot&&d.hot.length)
      h+='<div class="sg-h">热门</div><div class="sg-w">'+d.hot.map(sgBtn).join('')+'</div>';
    if(!h)return;
    el.innerHTML=h; el.classList.add('on');
    $$('.sg-w button',el).forEach(function(b){
      // 用 mousedown：它早于 input 的 blur，否则点击会被 blur 先关掉面板
      b.onmousedown=function(ev){ev.preventDefault();doSearch(b.getAttribute('data-s'));};
    });
  }).catch(function(){});
}

/* ───── 浏览片库 ─────
   后端按各站自己的分类 ID 拉、回来再归一归类，
   所以哪个站的 ID 变了都不会让这一页失效。 */
var BR={cat:'',year:'',pg:1,items:[],more:false,busy:false};
var BCATS=[['','全部'],['电影','电影'],['剧集','剧集'],['动漫','动漫'],
           ['综艺','综艺'],['纪录片','纪录片']];
var BYEARS=['','2026','2025','2024','2023','2022'];

function loadBrowse(){
  setView('browse');
  BR.pg=1;BR.items=[];BR.busy=false;
  viewEl.innerHTML=browseHead()+sklGrid(18,'bgrid');
  bindChips();
  fetchPage();
}
function browseHead(){
  var h='<h2 class="secttl">浏览片库<span class="cnt">聚合 5 个采集源</span></h2>';
  h+='<p class="sub">想不起片名也能翻——按类型和年份筛</p><div class="browsebar">';
  h+='<div class="chips"><span class="lbl">类型</span>'+BCATS.map(function(c){
      return '<button class="chip'+(c[0]===BR.cat?' on':'')+'" data-cat="'+c[0]+'">'+c[1]+'</button>';
    }).join('')+'</div>';
  h+='<div class="chips"><span class="lbl">年份</span>'+BYEARS.map(function(y){
      return '<button class="chip'+(y===BR.year?' on':'')+'" data-year="'+y+'">'+(y||'全部')+'</button>';
    }).join('')+'</div></div>';
  return h;
}
function bindChips(){
  function pick(attr,field){
    $$('.chip['+attr+']',viewEl).forEach(function(b){
      b.onclick=function(){
        BR[field]=b.getAttribute(attr); BR.pg=1; BR.items=[]; BR.busy=false;
        $$('.chip['+attr+']',viewEl).forEach(function(x){x.classList.toggle('on',x===b)});
        var g=document.getElementById('bgrid');
        if(g)g.outerHTML=sklGrid(18,'bgrid');
        var mo=document.getElementById('bmore'); if(mo)mo.remove();
        fetchPage();
      };
    });
  }
  pick('data-cat','cat'); pick('data-year','year');
}
function fetchPage(){
  if(BR.busy)return;
  BR.busy=true;
  var u='/api/browse?cat='+q(BR.cat)+'&year='+q(BR.year)+'&pg='+BR.pg;
  fetch(u).then(function(r){return r.json()}).then(function(d){
    BR.busy=false;
    var its=d.items||[]; BR.more=!!d.more;
    var g=document.getElementById('bgrid');
    if(!g)return;                       // 用户已经切走了
    if(BR.pg===1)g.innerHTML='';
    if(!its.length&&BR.pg===1){
      g.outerHTML='<div class="empty"><div class="ic">空</div><p>这一类暂时没有内容</p>'+
        '<div class="hint">换个类型或年份看看</div></div>';
      return;
    }
    var base=BR.items.length;
    its.forEach(function(it,i){
      g.insertAdjacentHTML('beforeend',cardHTML(it,'data-b',base+i,
        it.score?'<span>★'+esc(it.score)+'</span>':''));
    });
    BR.items=BR.items.concat(its);
    $$('[data-b]',g).forEach(function(b){
      b.onclick=function(){
        var it=BR.items[+b.getAttribute('data-b')];
        if(it)goDetail(it.name,it.year||'');
      };
    });
    var old=document.getElementById('bmore'); if(old)old.remove();
    if(BR.more){
      var m=document.createElement('button');
      m.className='btn bmore'; m.id='bmore'; m.textContent='加载更多';
      m.onclick=function(){BR.pg++;m.textContent='加载中…';fetchPage();};
      viewEl.appendChild(m);
    }else if(BR.items.length){
      var e2=document.createElement('p');
      e2.className='sub'; e2.id='bmore';
      e2.style.cssText='text-align:center;margin:34px 0 0';
      e2.textContent='已经到底了 · 共 '+BR.items.length+' 部';
      viewEl.appendChild(e2);
    }
  }).catch(function(){BR.busy=false;});
}

/* ───── 演职员：点名字直接搜这个人的作品 ───── */
function crewLinks(s){
  return String(s||'').split(/\s*\/\s*/).map(function(n){
    n=n.trim();
    if(!n||n==='等')return '';
    return '<button class="crl" data-q="'+esc(n)+'">'+esc(n)+'</button>';
  }).filter(Boolean).join('<u>·</u>');
}
function bindCrew(root){
  $$('.crl',root||viewEl).forEach(function(b){
    b.onclick=function(){doSearch(b.getAttribute('data-q'));};
  });
}

function doSearch(kw){
  var q0=(typeof kw==='string'?kw:kwEl.value).trim();
  if(!q0)return;
  if(kwEl.value!==q0)kwEl.value=q0;
  closeSugg();
  if(VIEW==='home'||VIEW==='live'||VIEW==='tasks'||VIEW==='browse')setView('online');
  ST[VIEW]={loading:true};
  viewEl.innerHTML='<h2 class="secttl">'+esc(q0)+'</h2>'+
    '<p class="sub">'+(VIEW==='online'?'正在聚合各源并校验播放地址…':'正在检索并检测做种数…')+'</p>'+
    skeleton(12);
  fetch(VIEW==='online'?'/api/online?kw='+q(q0):'/api/magnets?kw='+q(q0))
    .then(function(r){return r.json()}).then(function(d){
      var items=d.items||[];
      ST[VIEW]={items:items};
      if(!items.length){
        viewEl.innerHTML='<h2 class="secttl">'+esc(q0)+'</h2>'+
          '<div class="empty"><div class="ic">空</div><p>没找到结果</p>'+
          '<div class="hint">换个写法试试：去掉副标题、用短名、或者换用英文名</div></div>';
        return;
      }
      VIEW==='online'?renderOnline(items):renderMagnet(items);
      if(new URLSearchParams(location.search).get('open')&&VIEW==='online')
        setTimeout(function(){openSheet(items[0]);},240);
    }).catch(function(e){
      ST[VIEW]=null;
      viewEl.innerHTML='<div class="err">搜索出错：'+esc(e)+'</div>';
    });
}

function renderOnline(items){
  var h='<h2 class="secttl">搜索结果</h2><p class="sub">找到 '+items.length+
    ' 部 · 点海报进详情页选源播放</p><div class="grid">';
  items.forEach(function(it,i){
    var key='online|'+it.name+'|'+(it.year||'');
    var on=FAVSET[key]?' on':'';
    h+='<button class="card" data-i="'+i+'" style="animation-delay:'+Math.min(i*22,440)+'ms">'+
       '<div class="poster">'+posterHTML(it.pic,it.name)+
       '<span class="cnt">'+it.site_count+'源</span>'+
       '<span class="fbtn'+on+'" data-fk="'+esc(key)+'" data-ft="'+esc(it.name)+
         '" data-fp="'+esc(it.pic||'')+'">★</span>'+
       '<span class="playic"><svg viewBox="0 0 24 24"><path d="M8 5v14l11-7z"/></svg></span>'+
       '</div><div class="cname">'+esc(it.name)+'</div>'+
       '<div class="cmeta">'+(it.year&&it.year!=='0'?'<span>'+esc(it.year)+'</span>':'')+
       (it.type?'<span>'+esc(it.type)+'</span>':'')+
       (it.ep_count>1?'<i>'+it.ep_count+'集</i>':'')+'</div></button>';
  });
  viewEl.innerHTML=h+'</div>';
  $$('.card',viewEl).forEach(function(c){
    c.onclick=function(e){
      if(e.target.closest('.fbtn'))return;
      openDetail(items[+c.getAttribute('data-i')]);
    };});
  bindFav();
}
function bindFav(){
  $$('.fbtn',viewEl).forEach(function(b){
    b.onclick=function(e){
      e.stopPropagation();
      var k=b.getAttribute('data-fk');
      fetch('/api/fav',{method:'POST',
        headers:{'Content-Type':'application/x-www-form-urlencoded'},
        body:'kind=online&key='+q(k)+'&title='+q(b.getAttribute('data-ft'))+
             '&cover='+q(b.getAttribute('data-fp'))})
        .then(function(r){return r.json()}).then(function(r){
          b.classList.toggle('on',!!r.fav);
          FAVSET[k]=r.fav?1:0;
          toast(r.fav?'已收藏':'已取消收藏',r.fav?'good':'');
        }).catch(function(){toast('操作失败','bad');});
    };});
}

/* 从一条「记录」打开（历史 / 收藏）。
   记录里只有 title/cover/key/ep，没有线路 —— 必须先去聚合，不能直接当详情用。
   live → 跳直播并唤起该频道；magnet → 跳下载；online → 聚合后接着上次的位置播。 */
function openFromRecord(rec,pos){
  if(!rec)return;
  var kind=rec.kind||'online';
  var parts=String(rec.key||'').split('|');
  var name=rec.title||parts[1]||'';
  var year=(rec.year!==undefined&&rec.year!==null&&String(rec.year)!=='')
    ?rec.year:(parts[2]||'');

  if(kind==='live'){
    var nm=parts[2]||name;
    setView('live'); loadLive();
    var tries=0;
    var timer=setInterval(function(){
      var items=(ST.live&&ST.live.items)||[];
      var hit=items.filter(function(x){return x.name===nm;})[0];
      if(hit){clearInterval(timer);playLive(hit,0);}
      else if(++tries>25){clearInterval(timer);toast('频道「'+nm+'」不在当前列表里','bad');}
    },200);
    return;
  }
  if(kind==='magnet'){ setView('magnet'); renderIdle(); return; }
  if(!name){toast('这条记录不完整，无法定位片子','bad');return;}
  goDetail(name,year,{pos:pos||0,ep:rec.ep||''});
}

function openSheet(it){
  if(!it)return;
  if(!it.sources||!it.sources.length){
    // 兜底：万一传进来的是历史/收藏记录，自动转走聚合流程
    if(it.title||it.key){openFromRecord(it,it.position||0);return;}
    toast('这部片子暂时没有可用源','bad');return;
  }
  var m=document.createElement('div');
  m.className='mask';
  var pic=it.pic?('<img src="'+esc(it.pic)+'" alt="">')
    :('<div class="ph2">'+esc((it.name||'?').charAt(0))+'</div>');
  var h='<div class="sheet"><button class="close">✕</button>'+
    '<div class="shead">'+pic+'<div style="flex:1;min-width:0">'+
    '<h3>'+esc(it.name)+'</h3>'+
    '<div class="m">'+(it.year&&it.year!=='0'?'<span>'+esc(it.year)+'</span>':'')+
    (it.type?'<span>'+esc(it.type)+'</span>':'')+
    '<span>'+it.sources.length+' 个源</span></div></div></div>'+
    '<div class="srcgrp"><p class="srclbl">选择播放源</p>';
  it.sources.forEach(function(s,si){
    h+='<button class="src'+(si===0?' sel':'')+'" data-si="'+si+'">'+
       '<span class="sn">'+esc(s.site)+
       (s.remarks?' <span style="color:#726960;font-size:12.5px">· '+esc(s.remarks)+'</span>':'')+
       '</span><span class="sc">'+(s.count||(s.eps||[]).length)+' 集</span></button>';
  });
  h+='<p class="srclbl" style="margin-top:20px" id="epslbl">选集</p><div class="eps" id="eps"></div>'+
     '</div></div>';
  m.innerHTML=h;
  document.body.appendChild(m);
  var cur=0;
  function drawEps(){
    var s=it.sources[cur], box=$('#eps',m), lbl=$('#epslbl',m);
    if(!s.eps||!s.eps.length){box.innerHTML='<span style="color:#726960;font-size:12.5px">无可用播放地址</span>';return;}
    if(s.eps.length===1){
      if(lbl)lbl.textContent='播放';
      box.className='';
      box.innerHTML='<button class="btn pri bigplay">▶ 开始播放'+
        (s.eps[0].name&&s.eps[0].name!=='HD'?' · '+esc(s.eps[0].name):'')+'</button>';
      $('.bigplay',m).onclick=function(){close();play(s,0,it);};
    }else{
      if(lbl)lbl.textContent='选集（'+s.eps.length+'）';
      box.className='eps';
      box.innerHTML=s.eps.map(function(e,ei){
        return '<button class="ep'+(ei===0?' cur':'')+'" data-ei="'+ei+'">'+esc(e.name)+'</button>';
      }).join('');
      $$('.ep',box).forEach(function(b){
        b.onclick=function(){
          $$('.ep',box).forEach(function(x){x.classList.remove('cur')});
          b.classList.add('cur');
          close();play(s,+b.getAttribute('data-ei'),it);
        };});
    }
    $$('.src',m).forEach(function(b){
      b.classList.toggle('sel',+b.getAttribute('data-si')===cur);});
  }
  $$('.src',m).forEach(function(b){
    b.onclick=function(){cur=+b.getAttribute('data-si');drawEps();};});
  drawEps();
  function close(){m.remove();document.removeEventListener('keydown',onKey);}
  m.onclick=function(e){if(e.target===m)close();};
  $('.close',m).onclick=close;
  function onKey(e){if(e.key==='Escape')close();}
  document.addEventListener('keydown',onKey);
}


/* ───── 播放器（ArtPlayer）───── */
function mkArt(host,url,isLive,onFail){
  host.innerHTML='';
  var div=document.createElement('div');
  div.style.width='100%';div.style.height='100%';
  host.appendChild(div);
  var opt={
    container:div, url:url, type:'m3u8', autoplay:true, volume:0.8,
    theme:'#d4553a', setting:true, playbackRate:true, aspectRatio:true,
    screenshot:true, pip:true, fullscreen:true, fullscreenWeb:true,
    miniProgressBar:!isLive, autoOrientation:true, hotkey:true, lock:true,
    flip:true, fastForward:true,
    moreVideoAttr:{'webkit-playsinline':true,'playsinline':true},
    customType:{m3u8:function(video,u,art){
      if(HLS){try{HLS.destroy()}catch(e){} HLS=null;}
      if(window.Hls&&Hls.isSupported()){
        HLS=new Hls(isLive
          ?{enableWorker:true,liveDurationInfinity:true,maxLiveSyncPlaybackRate:1.5}
          :{enableWorker:true,maxBufferLength:40,backBufferLength:30,manifestLoadingTimeOut:15000});
        HLS.loadSource(u); HLS.attachMedia(video);
        HLS.on(Hls.Events.ERROR,function(e,d){
          if(d&&d.fatal){
            if(onFail){onFail();}
            else toast(d.type==='networkError'?'源连接失败，试试换一个源':'播放出错：'+d.details,'bad');
          }
        });
        art.on('destroy',function(){try{HLS&&HLS.destroy()}catch(e){} HLS=null;});
      }else if(video.canPlayType('application/vnd.apple.mpegurl')){
        video.src=u;
      }else toast('当前浏览器不支持 HLS 播放','bad');
    }}
  };
  return new Artplayer(opt);
}

function play(src,ei,it,seekTo){
  if(!src||!src.eps||!src.eps[ei]){toast('这条线路没有可播地址','bad');return;}
  seekTo=+seekTo||0;
  var url=src.eps[ei].url;
  var key='online|'+(it.name||'')+'|'+(it.year||'');
  var payload={kind:'online',key:key,title:it.name,cover:it.pic||'',
    source:src.site,ep:src.eps[ei].name,url:url,genre:it.type||'',year:it.year||''};
  CUR={payload:payload,item:it,src:src,ei:ei};

  $('#plttl').textContent=it.name;
  $('#plsub').textContent=src.site;
  $('#plwrap').classList.add('on');
  syncFavBtn();
  drawSide(it,src,ei);

  if(ART){try{ART.destroy()}catch(e){} ART=null;}
  ART=mkArt($('#plhost'),url,false);
  var lastSent=0;
  ART.on('video:timeupdate',function(){
    if(ART.duration>2&&ART.currentTime-lastSent>15){
      lastSent=ART.currentTime;
      payload.position=ART.currentTime; payload.duration=ART.duration;
      send('/api/history',payload);
    }
  });
  ART.on('video:ended',function(){
    payload.position=ART.duration||0; send('/api/history',payload);
    if(ei+1<src.eps.length){toast('即将播放下一集');setTimeout(function(){play(src,ei+1,it);},1600);}
  });
  // 续播：等元数据到位后跳到上次位置（三路兜底，避免错过事件）
  if(seekTo>0){
    var didSeek=false;
    var doSeek=function(){
      if(didSeek)return; didSeek=true;
      try{if(ART.currentTime<seekTo-1)ART.currentTime=seekTo;}catch(e){}
    };
    try{ART.on('video:loadedmetadata',doSeek);}catch(e){}
    try{ART.on('video:canplay',doSeek);}catch(e){}
    setTimeout(doSeek,3000);
    lastSent=seekTo-16;               // 首次 timeupdate 不误报
  }
  payload.position=seekTo;payload.duration=0;send('/api/history',payload);
}

/* 右侧栏：选集导航 + 线路列表 + 选集网格 */
function drawSide(it,src,ei){
  var eps=src.eps||[], srcs=it.sources||[];
  var multi=eps.length>1;

  // 选集导航（上集/下集都在这里，不再浮在画面上）
  $('#plnav').style.display=multi?'':'none';
  if(multi){
    $('#plnavcur').innerHTML='<b>'+esc(eps[ei].name)+'</b> · 第 '+(ei+1)+'/'+eps.length+' 集';
    var bp=$('#plprev'), bn=$('#plnext');
    bp.disabled=(ei<=0); bn.disabled=(ei>=eps.length-1);
    bp.onclick=function(){if(ei>0)play(src,ei-1,it);};
    bn.onclick=function(){if(ei<eps.length-1)play(src,ei+1,it);};
  }

  // 线路列表
  var bl=$('#blkline');
  if(srcs.length>1){
    bl.style.display='';
    $('#lcount').textContent=srcs.length+' 条';
    $('#llist').innerHTML=srcs.map(function(sc,i){
      return '<button class="lrow'+(sc===src?' on':'')+'" data-i="'+i+'">'+
        '<span class="nm">'+esc(sc.site)+
        (sc.remarks?' <span style="color:#726960;font-size:12px">· '+esc(sc.remarks)+'</span>':'')+
        '</span><span class="ct">'+sc.count+' 集</span>'+
        '<span class="bd">'+(sc===src?'当前':(i===0?'推荐':'可选'))+'</span></button>';
    }).join('');
    $$('.lrow',$('#llist')).forEach(function(b){
      b.onclick=function(){
        var n=srcs[+b.getAttribute('data-i')];
        var ni=Math.min(ei,n.eps.length-1);
        toast('已切到 '+n.site+' · '+n.eps[ni].name,'good');
        play(n,ni,it);
      };
    });
  }else bl.style.display='none';

  // 选集网格
  var be=$('#blkep');
  if(multi){
    be.style.display='';
    $('#ecount').textContent=src.site+' · '+eps.length+' 集';
    $('#egrid').innerHTML=eps.map(function(e,i){
      return '<button class="ep'+(i===ei?' cur':'')+'" data-i="'+i+'">'+esc(e.name)+'</button>';
    }).join('');
    $$('#egrid .ep').forEach(function(b){
      b.onclick=function(){play(src,+b.getAttribute('data-i'),it);};
    });
  }else be.style.display='none';
}

/* ───── 详情页 ───── */
function openDetail(it){
  ST.detail=it; setView('detail'); renderDetail(it); window.scrollTo(0,0);
}
function goDetail(n,y,resume,auto){
  ST.detail=null; setView('detail'); window.scrollTo(0,0);
  viewEl.innerHTML='<button class="back" id="dback">← 返回</button>'+
    '<h2 class="secttl">'+esc(n)+'</h2>'+
    '<p class="sub">正在聚合各条线路…</p>'+skeleton(6);
  var b=document.getElementById('dback');
  if(b)b.onclick=function(){setView('home');loadHome();};
  fetch('/api/detail?n='+q(n)+'&y='+q(y||''))
    .then(function(r){return r.json()}).then(function(d){
      if(d.error||!d.name){
        viewEl.innerHTML='<button class="back" id="dback">← 返回</button>'+
          '<div class="empty"><div class="ic">空</div><p>没找到这部片子的可播线路</p></div>';
        var b2=document.getElementById('dback');
        if(b2)b2.onclick=function(){setView('home');loadHome();};
        return;
      }
      ST.detail=d; renderDetail(d);
      var r=(resume&&typeof resume==='object')?resume:null;
      if(r&&(r.pos>0||r.ep)&&d.sources&&d.sources.length){
        // 优先找「同名集」，找不到就退回第一条线路的第一集
        var si=0, ei=0, found=false;
        if(r.ep){
          for(var a=0;a<d.sources.length&&!found;a++){
            var eps=d.sources[a].eps||[];
            for(var b=0;b<eps.length;b++){
              if(eps[b].name===r.ep){si=a;ei=b;found=true;break;}
            }
          }
        }
        setTimeout(function(){play(d.sources[si],ei,d,found?r.pos:0);},350);
      }else if((auto||new URLSearchParams(location.search).get('autoplay'))
               &&d.sources&&d.sources.length){
        // Hero 点「立即播放」：聚合完直接开播，不用再点一次
        setTimeout(function(){play(d.sources[0],0,d);},300);
      }
    }).catch(function(e){
      viewEl.innerHTML='<button class="back" id="dback">← 返回</button>'+
        '<div class="err">打开失败：'+esc(e)+'</div>';
    });
}
function renderDetail(it){
  var srcs=it.sources||[];
  var cur=0;
  var KEY='online|'+(it.name||'')+'|'+(it.year||'');
  var faved=!!FAVSET[KEY];
  var h='<button class="back" id="dback">← 返回</button>';
  h+='<div class="dtop"><div class="dhero">'+
     (it.pic?'<img src="'+esc(it.pic)+'" alt="">':'')+'</div>';
  h+='<div class="dmain">';
  h+='<div class="dposter">'+posterHTML(it.pic,it.name)+'</div>';
  var sc=(it.score&&it.score!=='0')?String(it.score):'';
  h+='<div class="dinfo"><h2>'+esc(it.name)+
     (sc?'<span class="dscore">★ '+esc(sc)+'</span>':'')+'</h2>';
  h+='<div class="dmeta">'+
     (it.year&&it.year!=='0'?'<span class="pill">'+esc(it.year)+'</span>':'')+
     (it.type?'<span class="pill">'+esc(it.type)+'</span>':'')+
     (it.area?'<span class="pill">'+esc(it.area)+'</span>':'')+
     (it.lang?'<span class="pill">'+esc(it.lang)+'</span>':'')+
     '<span class="pill">'+srcs.length+' 条线路</span>'+
     (it.all_eps?'<span class="pill">最多 '+it.all_eps+' 集</span>':'')+
     '</div>';
  if(it.director||it.actor){
    h+='<div class="dcrew">'+
       (it.director?'<span><i>导演</i>'+crewLinks(it.director)+'</span>':'')+
       (it.actor?'<span><i>主演</i>'+crewLinks(it.actor)+'</span>':'')+
       '</div>';
  }
  if(it.desc)h+='<p class="ddesc">'+esc(it.desc)+'</p>';
  h+='<div class="dacts">'+
     '<button class="btn pri" id="dplay" style="padding:10px 26px;font-size:14.5px">▶ 开始播放</button>'+
     '<button class="btn" id="dfav">'+(faved?'★ 已收藏':'☆ 收藏')+'</button>'+
     '</div></div></div></div>';
  h+='<div class="lines"><div class="sec2"><span>播放线路</span><span>共 '+srcs.length+' 条</span></div>';
  srcs.forEach(function(sc,i){
    h+='<button class="line'+(i===0?' on':'')+'" data-i="'+i+'" style="animation-delay:'+
       Math.min(i*26,320)+'ms">'+
       '<span class="ln">'+esc(sc.site)+
       (sc.remarks?' <span style="color:#726960;font-size:12.5px">· '+esc(sc.remarks)+'</span>':'')+
       '</span><span class="lc">'+sc.count+' 集</span>'+
       '<span class="ls">'+(i===0?'推荐':'可选')+'</span></button>';
  });
  h+='</div><div class="sec2"><span>选集</span><span id="dcnt"></span></div>'+
     '<div class="epgrid" id="deps"></div>';
  viewEl.innerHTML=h;
  fitBackdrops(viewEl);
  bindCrew(viewEl);

  document.getElementById('dback').onclick=function(){setView('home');loadHome();};
  document.getElementById('dplay').onclick=function(){play(srcs[cur],0,it);};
  document.getElementById('dfav').onclick=function(){
    fetch('/api/fav',{method:'POST',
      headers:{'Content-Type':'application/x-www-form-urlencoded'},
      body:'kind=online&key='+q(KEY)+'&title='+q(it.name)+'&cover='+q(it.pic||'')})
      .then(function(r){return r.json()}).then(function(r){
        FAVSET[KEY]=r.fav?1:0;
        document.getElementById('dfav').textContent=r.fav?'★ 已收藏':'☆ 收藏';
        toast(r.fav?'已收藏':'已取消收藏',r.fav?'good':'');
      });
  };
  function drawEps(){
    var sc=srcs[cur];
    document.getElementById('dcnt').textContent=sc.site+' · '+sc.count+' 集';
    document.getElementById('deps').innerHTML=sc.eps.map(function(e,i){
      return '<button class="ep" data-i="'+i+'">'+esc(e.name)+'</button>';
    }).join('');
    $$('#deps .ep').forEach(function(b){
      b.onclick=function(){play(sc,+b.getAttribute('data-i'),it);};});
  }
  $$('.line',viewEl).forEach(function(b){
    b.onclick=function(){
      cur=+b.getAttribute('data-i');
      $$('.line',viewEl).forEach(function(x){x.classList.remove('on')});
      b.classList.add('on');
      drawEps();
    };});
  drawEps();
}




function closePl(){
  if(ART){try{ART.destroy()}catch(e){} ART=null;}
  if(HLS){try{HLS.destroy()}catch(e){} HLS=null;}
  $('#plhost').innerHTML='';
  $('#plwrap').classList.remove('on');
  document.body.style.overflow='';
  CUR=null;
  // 关掉播放器就回到详情页（页面本来就没被覆盖，只是解锁滚动）
  if(ST.detail)renderDetail(ST.detail);
}
$('#plclose').onclick=closePl;
$('#plfav').onclick=function(){
  if(!CUR)return;
  var p=CUR.payload;
  fetch('/api/fav',{method:'POST',
    headers:{'Content-Type':'application/x-www-form-urlencoded'},
    body:'kind='+q(p.kind)+'&key='+q(p.key)+'&title='+q(p.title)+'&cover='+q(p.cover)})
    .then(function(r){return r.json()}).then(function(r){
      syncFavBtn(!!r.fav); toast(r.fav?'已收藏':'已取消收藏',r.fav?'good':'');
    }).catch(function(){toast('操作失败','bad');});
};
function syncFavBtn(force){
  if(!CUR)return;
  var k=CUR.payload.kind+'|'+CUR.payload.key;
  var btn=$('#plfav');
  if(typeof force==='boolean'){btn.classList.toggle('on',force);FAVSET[k]=force?1:0;return;}
  btn.classList.toggle('on',!!FAVSET[k]);
}
function send(path,obj){
  var body=Object.keys(obj).map(function(k){return q(k)+'='+q(obj[k]);}).join('&');
  fetch(path,{method:'POST',headers:{'Content-Type':'application/x-www-form-urlencoded'},
    body:body}).catch(function(){});
}

/* ───── 直播 ───── */
function loadLive(grp){
  viewEl.innerHTML='<h2 class="secttl">直播</h2><p class="sub">正在读取频道…</p>'+
    '<div class="lvgrid">'+Array(12).fill('<div class="skl" style="aspect-ratio:auto;height:44px"></div>').join('')+'</div>';
  fetch('/api/live'+(grp?'?g='+q(grp):'')).then(function(r){return r.json()})
    .then(function(d){ST.live=d;renderLive(d,grp);})
    .catch(function(){viewEl.innerHTML='<div class="err">读取直播源失败</div>';});
}
function renderLive(d,grp){
  var items=d.items||[], groups=d.groups||[];
  var total=0; groups.forEach(function(g){total+=g.count;});
  var h='<h2 class="secttl">直播<span class="cnt">'+(items.length||0)+'</span></h2>'+
    '<p class="sub">'+(total?('共 '+total+' 个频道 · '):'')+
    '每个频道可能有多个源，播不了会自动换下一个</p>';
  if(!items.length){
    h+='<div class="empty"><div class="ic">无源</div><p>还没有直播频道</p>'+
       '<div class="hint">内置源多为运营商 IPTV，普通宽带可能连不上。<br>'+
       '建议贴一个你自己的直播源地址导入，支持 <code>M3U</code> 和 <code>TXT</code> 两种格式</div>'+
       '<div style="display:flex;gap:8px;margin-top:20px;justify-content:center;flex-wrap:wrap">'+
       '<input type="url" id="lvurl" placeholder="http://example.com/iptv.m3u" style="min-width:280px">'+
       '<button class="btn pri" id="lvadd">导入</button>'+
       '<button class="btn" id="lvrefresh">抓取内置源</button></div></div>';
  }else{
    if(groups.length>1){
      h+='<div class="gchips"><button class="gc'+(grp?'':' on')+'" data-g="">全部</button>';
      groups.forEach(function(g){
        h+='<button class="gc'+(grp===g.group?' on':'')+'" data-g="'+esc(g.group)+'">'+
           esc(g.group)+' <span style="opacity:.6">'+g.count+'</span></button>';
      });
      h+='</div>';
    }
    h+='<div class="lvgrid">';
    items.forEach(function(it,i){
      h+='<button class="lv" data-i="'+i+'" style="animation-delay:'+Math.min(i*14,320)+'ms">'+
         '<span class="nm">'+esc(it.name)+'</span>'+
         (it.urls.length>1?'<span class="n">'+it.urls.length+'</span>':'')+'</button>';
    });
    h+='</div>';
    h+='<div style="margin-top:22px;display:flex;gap:8px;flex-wrap:wrap">'+
       '<input type="url" id="lvurl" placeholder="追加直播源地址 (m3u / txt)" style="min-width:260px">'+
       '<button class="btn pri" id="lvadd">导入</button>'+
       '<button class="btn" id="lvrefresh">重新抓取</button></div>';
  }
  viewEl.innerHTML=h;
  $$('.gc',viewEl).forEach(function(b){
    b.onclick=function(){loadLive(b.getAttribute('data-g')||undefined);};});
  $$('.lv',viewEl).forEach(function(b){
    b.onclick=function(){playLive(items[+b.getAttribute('data-i')],0);};});
  var add=$('#lvadd'),u=$('#lvurl'),rf=$('#lvrefresh');
  if(add)add.onclick=function(){
    var v=u.value.trim();
    if(!v)return;
    add.disabled=true;add.textContent='导入中…';
    var nm='自定义源';
    try{nm=new URL(v).hostname;}catch(e){}
    fetch('/api/live/add',{method:'POST',
      headers:{'Content-Type':'application/x-www-form-urlencoded'},
      body:'url='+q(v)+'&name='+q(nm)})
      .then(function(r){return r.json()}).then(function(r){
        toast(r.msg||'完成',r.ok?'good':'bad');
        setTimeout(function(){loadLive(grp);},700);
      }).catch(function(){add.disabled=false;add.textContent='导入';toast('失败','bad');});
  };
  if(rf)rf.onclick=function(){
    rf.disabled=true;rf.textContent='抓取中…';
    fetch('/api/live/refresh',{method:'POST'}).then(function(r){return r.json()})
      .then(function(r){toast(r.msg||'完成',r.ok?'good':'bad');
        setTimeout(function(){loadLive(grp);},700);})
      .catch(function(){rf.disabled=false;rf.textContent='重新抓取';});
  };
}
function playLive(it,idx){
  idx=idx||0;
  if(!it||!it.urls||idx>=it.urls.length){toast('所有源都播不了','bad');return;}
  var url=it.urls[idx];
  var payload={kind:'live',key:'live|'+(it.group||'')+'|'+it.name,title:it.name,
    source:it.group||'',ep:'',url:url,genre:'直播',year:''};
  CUR={payload:payload,item:{name:it.name}};
  $('#plttl').textContent=it.name;
  $('#plsub').textContent=(it.group||'直播')+(it.urls.length>1?' · 源 '+(idx+1)+'/'+it.urls.length:'');
  $('#plwrap').classList.add('on');
  syncFavBtn();
  // 直播：隐藏选集导航，侧栏只留「直播源」切换
  $('#plnav').style.display='none';
  $('#blkep').style.display='none';
  var bl=$('#blkline');
  if(it.urls.length>1){
    bl.style.display='';
    $('#lcount').textContent=it.urls.length+' 个源';
    $('#llist').innerHTML=it.urls.map(function(u,i){
      return '<button class="lrow'+(i===idx?' on':'')+'" data-i="'+i+'">'+
        '<span class="nm">源 '+(i+1)+'</span>'+
        '<span class="ct">'+esc(u.replace(/^https?:\/\//,'').split('/')[0])+'</span>'+
        '<span class="bd">'+(i===idx?'当前':'可选')+'</span></button>';
    }).join('');
    $$('.lrow',$('#llist')).forEach(function(b){
      b.onclick=function(){playLive(it,+b.getAttribute('data-i'));};});
  }else bl.style.display='none';
  if(ART){try{ART.destroy()}catch(e){} ART=null;}
  ART=mkArt($('#plhost'),url,true,function(){
    toast('这个源播不了，换下一个…','bad');
    setTimeout(function(){playLive(it,idx+1);},1000);
  });
  send('/api/history',payload);
}

/* ───── 下载收藏 ───── */
function renderMagnet(items){
  var h='<h2 class="secttl">下载收藏</h2><p class="sub">'+items.length+
    ' 个磁力 · 按做种数排序 · 绿色随便下，红色别浪费时间</p>';
  items.forEach(function(it,i){
    var s=it.seeders,hc=s<0?'h-u':s>=10?'h-g':s>=3?'h-m':'h-b';
    var ht=s<0?'未知':s>=10?'健康':s>=3?'一般':s>=1?'做种很少':'无做种';
    h+='<div class="mag" data-i="'+i+'" style="animation-delay:'+Math.min(i*20,380)+'ms">'+
       '<div class="info"><div class="t1"><span class="nm">'+esc(it.title)+'</span>'+
       '<span class="hp"><span class="hdot '+hc+'"></span>做种 <b>'+(s>=0?s:'?')+'</b>'+
       (it.finished>=0?' · 记录 <b>'+it.finished+'</b>':'')+
       '<span style="color:'+(s>=10?'#4a9d7f':s>=1?'#a89e91':'#d4553a')+';margin-left:4px">'+ht+'</span>'+
       '</span></div><div class="tags">';
    (it.tags||[]).forEach(function(t){
      var cls={res:'res',sub:'sub',hdr:'hdr',src:'hs',codec:'hs'}[t.k]||'';
      h+='<span class="tg '+cls+'">'+esc(t.t)+'</span>';});
    if(it.size)h+='<span class="tg">'+esc(it.size)+'</span>';
    h+='<span class="tg hh">'+esc((it.hash||'').slice(0,8))+'</span>';
    h+='<button class="exp" data-i="'+i+'">链接</button></div>'+
       '<div class="magnet">'+esc(it.magnet)+'</div></div>'+
       '<div class="ops"><select class="mcat" data-i="'+i+'">'+
       ['电影','剧集','动漫'].map(function(c){
         return '<option'+(c===(it.category||'电影')?' selected':'')+'>'+c+'</option>';}).join('')+
       '</select><button class="btn madd" data-i="'+i+'">下载</button>'+
       '<a class="lk" href="'+esc(it.url)+'" target="_blank" rel="noopener">详情</a></div></div>';
  });
  viewEl.innerHTML=h;
  $$('.exp',viewEl).forEach(function(b){
    b.onclick=function(){
      var box=b.closest('.mag');
      box.classList.toggle('open');
      b.textContent=box.classList.contains('open')?'收起':'链接';
    };});
  $$('.madd',viewEl).forEach(function(b){
    b.onclick=function(){
      var i=+b.getAttribute('data-i'),it=items[i];
      var c=$('.mcat[data-i="'+i+'"]',viewEl).value;
      b.disabled=true;b.textContent='提交中';
      fetch('/api/add',{method:'POST',
        headers:{'Content-Type':'application/x-www-form-urlencoded'},
        body:'magnet='+q(it.magnet)+'&category='+q(c)})
        .then(function(r){return r.json()}).then(function(r){
          if(r.ok){b.textContent='已加入';b.style.background='#4a9d7f';b.style.borderColor='#4a9d7f';
            toast('已加入下载：'+it.title,'good');loadTasks();}
          else{b.disabled=false;b.textContent='重试';toast('失败：'+(r.msg||''),'bad');}
        }).catch(function(){b.disabled=false;b.textContent='重试';toast('请求失败','bad');});
    };});
}

/* ───── 任务 ───── */
function renderTasks(){
  var h='<h2 class="secttl">任务</h2><p class="sub">'+
    (TASKS.length?TASKS.length+' 个进行中':'暂无任务')+' · 下载完自动进 Jellyfin 媒体库</p>';
  if(!TASKS.length){
    h+='<div class="empty"><div class="ic">静</div><p>现在没有下载任务</p>'+
       '<div class="hint">去「下载收藏」搜磁力</div></div>';
    viewEl.innerHTML=h;return;}
  TASKS.forEach(function(t){
    var done=t.progress>=100,stall=(t.state==='stalledDL'||t.state==='metaDL');
    var cls=done?'done':stall?'stall':'';
    var dot=done?'h-g':stall?'h-m':'h-g';
    h+='<div class="task"><div class="tn"><span class="tnm">'+esc(t.name)+'</span>'+
       '<span class="spd">'+(done?'已完成':spd(t.speed))+'</span></div>'+
       '<div class="pgw"><i class="'+cls+'" style="width:'+t.progress+'%"></i></div>'+
       '<div class="mt"><span><span class="hdot '+dot+'" style="margin-right:5px"></span>'+
       esc(t.state_cn)+'</span><span>'+t.progress+'%</span><span>'+fmt(t.size)+'</span>'+
       (t.seeds?'<span>种子 '+t.seeds+'</span>':'')+
       (t.peers?'<span>用户 '+t.peers+'</span>':'')+
       (t.eta>0?'<span>剩余 '+eta(t.eta)+'</span>':'')+
       (t.category?'<span>'+esc(t.category)+'</span>':'')+'</div>'+
       '<div class="acts">'+
       '<button class="tb" data-h="'+esc(t.hash)+'" data-a="files">文件</button>'+
       '<button class="tb" data-h="'+esc(t.hash)+'" data-a="pause">暂停</button>'+
       '<button class="tb" data-h="'+esc(t.hash)+'" data-a="resume">继续</button>'+
       '<button class="tb del" data-h="'+esc(t.hash)+'" data-a="delete">删除</button>'+
       '</div><div class="mono" id="fl-'+esc(t.hash)+'" style="display:none"></div></div>';
  });
  viewEl.innerHTML=h;
  $$('.tb',viewEl).forEach(function(b){
    b.onclick=function(){
      var hh=b.getAttribute('data-h'),a=b.getAttribute('data-a');
      if(a==='files'){
        var box=$('#fl-'+hh);
        if(box.style.display==='block'){box.style.display='none';return;}
        b.textContent='…';
        fetch('/api/torrent?hash='+hh+'&act=files').then(function(r){return r.json()})
          .then(function(r){
            b.textContent='文件';box.style.display='block';
            box.innerHTML=(r.items||[]).map(function(f){
              return '<div>'+esc(f.n)+'  <span style="color:#a89e91">'+f.p+'%</span></div>';
            }).join('')||'暂无文件信息';
          }).catch(function(){b.textContent='文件';});
        return;}
      if(a==='delete'&&!confirm('删除任务，并删掉已下载的文件？'))return;
      b.disabled=true;b.textContent='…';
      fetch('/api/torrent',{method:'POST',
        headers:{'Content-Type':'application/x-www-form-urlencoded'},
        body:'hash='+q(hh)+'&act='+a})
        .then(function(r){return r.json()}).then(function(r){
          toast(a==='delete'?'已删除':(a==='pause'?'已暂停':'已继续'),r.ok?'good':'bad');
          setTimeout(loadTasks,500);
        }).catch(function(){toast('操作失败','bad');loadTasks();});
    };});
}
function loadTasks(){
  fetch('/api/tasks').then(function(r){return r.json()}).then(function(d){
    TASKS=d.items||[];
    if(TASKS.length){tbdg.style.display='';tbdg.textContent=TASKS.length;}
    else tbdg.style.display='none';
    if(VIEW==='tasks')renderTasks();
  }).catch(function(){});
}

/* ───── 启动 ───── */
kwEl.addEventListener('keydown',function(e){
  if(e.key==='Enter'){doSearch();kwEl.blur();}
});
kwEl.addEventListener('focus',openSugg);
// 用 mousedown 选词时不要提前收起面板，所以延迟一点再关
kwEl.addEventListener('blur',function(){setTimeout(closeSugg,140);});
document.addEventListener('keydown',function(e){
  if((e.metaKey||e.ctrlKey)&&e.key==='k'){e.preventDefault();kwEl.focus();kwEl.select();}
  if(e.key==='Escape')closeSugg();
  if(e.key==='Escape'&&$('#plwrap').classList.contains('on'))closePl();
  // 播放中：← 上一集 / → 下一集
  if($('#plwrap').classList.contains('on')&&document.activeElement!==kwEl){
    if(e.key==='ArrowLeft'&&!$('#plprev').disabled)$('#plprev').click();
    if(e.key==='ArrowRight'&&!$('#plnext').disabled)$('#plnext').click();
  }
});
(function(){
  var p=new URLSearchParams(location.search);
  var v=p.get('v'), q0=p.get('q');
  if(v&&['home','browse','online','live','magnet','tasks','detail'].indexOf(v)>=0)setView(v);
  var dn=p.get('n');
  if(VIEW==='detail'&&dn){goDetail(dn,p.get('y')||'');return;}
  if(q0&&VIEW!=='live'&&VIEW!=='tasks'&&VIEW!=='detail'){kwEl.value=q0;doSearch();}
  else if(VIEW==='online'||VIEW==='magnet')renderIdle();
  else if(VIEW==='browse')loadBrowse();
  else if(VIEW==='live')loadLive();
  else if(VIEW==='tasks')renderTasks();
  else loadHome();
})();
loadTasks();
setInterval(loadTasks,5000);
</script>
</body>
</html>
"""


# ───────────────────────── 服务端 ─────────────────────────
class H(BaseHTTPRequestHandler):
    # HTTP/1.1 才支持 keep-alive，浏览器能复用连接，明显更快
    protocol_version = "HTTP/1.1"

    def do_HEAD(self):
        try:
            n = int(self.headers.get("Content-Length", 0))
            if n:
                self.rfile.read(n)
        except Exception:
            pass
        p = urllib.parse.urlparse(self.path)
        try:
            if p.path.startswith("/static/"):
                name = os.path.basename(p.path)
                fp = os.path.join(STATIC, name)
                if name in ("hls.js", "artplayer.js") and os.path.exists(fp):
                    sz = os.path.getsize(fp + ".gz") if os.path.exists(fp + ".gz") else os.path.getsize(fp)
                    self.send_response(200)
                    self.send_header("Content-Type", "application/javascript; charset=utf-8")
                    self.send_header("Content-Length", str(sz))
                    self.send_header("Cache-Control", "public, max-age=2592000")
                    self.end_headers()
                    return
            self.send_response(200)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.send_header("Content-Length", "0")
            self.end_headers()
        except Exception:
            pass

    def log_message(self, fmt, *args):
        try:
            sys.stderr.write("[%s] %-15s %s %s -> %s\n" % (
                time.strftime("%H:%M:%S"), self.client_address[0],
                self.command, self.path, (args[1] if len(args) > 1 else "")))
            sys.stderr.flush()
        except Exception:
            pass

    def _send(self, code, body, ctype="application/json; charset=utf-8",
              cache=None, raw_gz=False):
        """raw_gz=True 表示 body 已是 gzip 字节流，直接发"""
        if isinstance(body, str):
            body = body.encode("utf-8")
        acc = (self.headers.get("Accept-Encoding") or "").lower()
        hdr = [("Content-Type", ctype),
               ("Cache-Control", cache or "no-store"),
               ("Access-Control-Allow-Origin", "*")]
        if raw_gz:
            if "gzip" in acc:
                hdr.append(("Content-Encoding", "gzip"))
            else:
                try:
                    body = gzip.decompress(body)
                except Exception:
                    pass
        elif len(body) > 900 and "gzip" in acc:
            body = gzip.compress(body, 6)
            hdr.append(("Content-Encoding", "gzip"))
        # 整个发送过程都要兜住：客户端可能中途断开（BrokenPipeError），
        # 原来 end_headers() 在 try 外面，异常会穿透出去把连接直接搞崩。
        try:
            self.send_response(code)
            for k, v in hdr:
                self.send_header(k, v)
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
        except Exception:
            pass

    def do_OPTIONS(self):
        self.send_response(204)
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Allow-Methods", "GET,POST,OPTIONS")
        self.send_header("Access-Control-Allow-Headers", "*")
        self.send_header("Content-Length", "0")
        self.end_headers()

    def do_GET(self):
        p = urllib.parse.urlparse(self.path)
        qs = urllib.parse.parse_qs(p.query)
        try:
            if p.path in ("/", "/index.html"):
                return self._send(200, PAGE, "text/html; charset=utf-8",
                                  cache="no-cache")
            if p.path.startswith("/static/"):
                name = os.path.basename(p.path)
                if name not in ("hls.js", "artplayer.js"):
                    return self._send(404, json.dumps({"error": "not found"}))
                fp = os.path.join(STATIC, name)
                gz = fp + ".gz"
                want_gz = "gzip" in (self.headers.get("Accept-Encoding") or "").lower()
                try:
                    if want_gz and os.path.exists(gz):
                        with open(gz, "rb") as f:
                            data = f.read()
                        return self._send(200, data,
                                          "application/javascript; charset=utf-8",
                                          cache="public, max-age=2592000", raw_gz=True)
                    with open(fp, "rb") as f:
                        data = f.read()
                    return self._send(200, data,
                                      "application/javascript; charset=utf-8",
                                      cache="public, max-age=2592000")
                except Exception:
                    return self._send(404, json.dumps({"error": "static missing"}))
            if p.path == "/api/home":
                hist = dbrun("""SELECT kind,key,title,cover,source,ep,url,position,
                                duration,plays,genre,year,updated_at
                                FROM history ORDER BY updated_at DESC LIMIT 24""",
                             fetch=True) or []
                favs = dbrun("""SELECT kind,key,title,cover,payload,added_at
                                FROM fav ORDER BY added_at DESC LIMIT 40""",
                             fetch=True) or []
                st_hist = dbrun("SELECT COUNT(*), COALESCE(SUM(plays),0) FROM history",
                                fetch=True)
                st_fav = dbrun("SELECT COUNT(*) FROM fav", fetch=True)
                st_live = dbrun("SELECT COUNT(*) FROM live", fetch=True)
                return self._send(200, json.dumps({
                    "history": [{"kind": r[0], "key": r[1], "title": r[2], "cover": r[3],
                                 "source": r[4], "ep": r[5], "url": r[6],
                                 "position": r[7], "duration": r[8], "plays": r[9],
                                 "genre": r[10], "year": r[11], "at": r[12]} for r in hist],
                    "fav": [{"kind": r[0], "key": r[1], "title": r[2], "cover": r[3],
                             "payload": r[4], "at": r[5]} for r in favs],
                    "stat": {"titles": st_hist[0][0] if st_hist else 0,
                             "plays": st_hist[0][1] if st_hist else 0,
                             "favs": st_fav[0][0] if st_fav else 0,
                             "live": st_live[0][0] if st_live else 0},
                    # 推荐直接随首页返回缓存，前端不用等第二次请求，
                    # 也就不会先白板再慢慢填充
                    "rec": get_recommend(),
                }, ensure_ascii=False))
            if p.path == "/api/detail":
                n = (qs.get("n") or [""])[0].strip()
                y = (qs.get("y") or [""])[0].strip()
                if not n:
                    return self._send(400, json.dumps({"error": "缺片名"}))
                # 聚合十几个站很慢，同名同年份的结果缓存 30 分钟，
                # 第二次点开（比如从「继续观看」回来）就是毫秒级
                # v3：聚合结果里加了简介/演员/导演，并改为「简介优先中文、评分取最大」。
                # 改聚合逻辑就升这个号，否则旧缓存会让新字段一直显示不出来
                ck = "detail|v3|%s|%s" % (_norm_key(n), y)
                cached = cache_peek(ck)
                if cached is not None and cache_valid(ck, 1800):
                    return self._send(200, json.dumps(cached, ensure_ascii=False))
                items = search_online(n, deadline=SEARCH_DEADLINE)
                KEY = _norm_key(n)
                hit = None
                # 先要求「片名完全一致」，再看年份，避免把《沙丘虫暴》当成《沙丘》
                same = [x for x in items if _norm_key(x["name"]) == KEY]
                for it in same:
                    if y and it.get("year") == y:
                        hit = it
                        break
                if hit is None and same:
                    hit = same[0]
                if hit is None:
                    for it in items:
                        if y and it.get("year") == y:
                            hit = it
                            break
                if hit is None and items:
                    hit = items[0]
                if hit is None:
                    return self._send(404, json.dumps(
                        {"error": "没找到这部片子"}, ensure_ascii=False))
                # 合并同名不同年份的其它版本，作为「备用线路」的补充
                others = [x for x in items
                          if x is not hit and _norm_key(x["name"]) == _norm_key(hit["name"])]
                for o in others:
                    hit["sources"].extend(o["sources"])
                # 按站点去重（保留集数最多的那个）
                best = {}
                for sc in hit["sources"]:
                    k = sc["site"]
                    if k not in best or sc["count"] > best[k]["count"]:
                        best[k] = sc
                hit["sources"] = sorted(best.values(),
                                        key=lambda x: (x["site"], -x["count"]))
                hit["site_count"] = len(hit["sources"])
                hit["all_eps"] = max((s["count"] for s in hit["sources"]), default=0)
                hit["cached"] = False
                cache_put(ck, hit)
                return self._send(200, json.dumps(hit, ensure_ascii=False))
            if p.path == "/api/recommend":
                return self._send(200, json.dumps(
                    get_recommend(fresh=(qs.get("fresh") or [""])[0] == "1"),
                    ensure_ascii=False))
            if p.path == "/api/suggest":
                # 搜索框聚焦时用：最近搜过的 + 搜得最多的
                recent = dbrun("SELECT kw FROM searches ORDER BY last_at DESC LIMIT 10",
                               fetch=True) or []
                hot = dbrun("SELECT kw FROM searches ORDER BY cnt DESC, last_at DESC LIMIT 10",
                            fetch=True) or []
                rk = [r[0] for r in recent]
                hk = [r[0] for r in hot if r[0] not in rk][:8]
                # 没历史的新装环境给一批兜底词，别让下拉是空的
                if not (rk or hk):
                    hk = FALLBACK_HOT[:]
                return self._send(200, json.dumps(
                    {"recent": rk[:8], "hot": hk}, ensure_ascii=False))
            if p.path == "/api/browse":
                cat = (qs.get("cat") or [""])[0].strip()
                year = (qs.get("year") or [""])[0].strip()
                area = (qs.get("area") or [""])[0].strip()
                try:
                    pg = max(1, min(20, int((qs.get("pg") or ["1"])[0])))
                except Exception:
                    pg = 1
                return self._send(200, json.dumps(
                    browse_catalog(cat, year, area, pg), ensure_ascii=False))
            if p.path == "/api/live":
                grp = (qs.get("g") or [""])[0]
                k = (qs.get("kw") or [""])[0]
                if (qs.get("refresh") or [""])[0] == "1":
                    n = refresh_live()
                return self._send(200, json.dumps({
                    "groups": live_groups(),
                    "items": live_list(grp or None, k or None),
                }, ensure_ascii=False))
            if p.path == "/api/sources":
                rows = dbrun("""SELECT kind,name,url,enabled,pri FROM sources
                                ORDER BY kind,pri,name""", fetch=True) or []
                return self._send(200, json.dumps({
                    "items": [{"kind": r[0], "name": r[1], "url": r[2],
                               "enabled": r[3], "pri": r[4]} for r in rows],
                    "builtin": {"online": [s["name"] for s in ONLINE_SITES],
                                "magnet": [s["name"] for s in SITES]},
                }, ensure_ascii=False))
            if p.path == "/api/status":
                if not QBT.ok:
                    QBT.login()
                return self._send(200, json.dumps({
                    "qbt": QBT.ok, "qbt_msg": QBT.msg,
                    "sites": len(SITES), "cats": CATEGORIES,
                    "online": len(ONLINE_SITES)}))
            if p.path == "/api/online":
                k = (qs.get("kw") or [""])[0]
                note_search(k)
                return self._send(200, json.dumps(
                    {"items": search_online(k)}, ensure_ascii=False))
            if p.path in ("/api/magnets", "/api/search"):
                k = (qs.get("kw") or [""])[0]
                note_search(k)
                return self._send(200, json.dumps(
                    {"items": do_search(k)}, ensure_ascii=False))
            if p.path == "/api/tasks":
                return self._send(200, json.dumps({"items": QBT.snapshot()}, ensure_ascii=False))
            if p.path == "/api/torrent":
                hh = (qs.get("hash") or [""])[0]
                act = (qs.get("act") or [""])[0]
                if act == "files":
                    return self._send(200, json.dumps({"items": QBT.files(hh)}, ensure_ascii=False))
            return self._send(404, json.dumps({"error": "not found"}))
        except Exception as e:
            return self._send(500, json.dumps({"error": str(e)[:200]}, ensure_ascii=False))

    def do_POST(self):
        p = urllib.parse.urlparse(self.path)
        try:
            n = int(self.headers.get("Content-Length", 0))
            raw = self.rfile.read(n).decode("utf-8", "replace")
            form = urllib.parse.parse_qs(raw)
            if p.path == "/api/history":
                kind = (form.get("kind") or ["online"])[0]
                key = (form.get("key") or [""])[0]
                if not key:
                    return self._send(400, json.dumps({"ok": False, "msg": "缺 key"}))
                touch_history(kind, key,
                              title=(form.get("title") or [""])[0],
                              cover=(form.get("cover") or [""])[0],
                              source=(form.get("source") or [""])[0],
                              ep=(form.get("ep") or [""])[0],
                              url=(form.get("url") or [""])[0],
                              position=(form.get("position") or [0])[0],
                              duration=(form.get("duration") or [0])[0],
                              genre=(form.get("genre") or [""])[0],
                              year=(form.get("year") or [""])[0])
                return self._send(200, json.dumps({"ok": True}))
            if p.path == "/api/fav":
                kind = (form.get("kind") or ["online"])[0]
                key = (form.get("key") or [""])[0]
                if not key:
                    return self._send(400, json.dumps({"ok": False, "msg": "缺 key"}))
                on = toggle_fav(kind, key,
                                (form.get("title") or [""])[0],
                                (form.get("cover") or [""])[0],
                                (form.get("payload") or [""])[0])
                return self._send(200, json.dumps({"ok": True, "fav": on}))
            if p.path == "/api/live/add":
                u = (form.get("url") or [""])[0].strip()
                nm = (form.get("name") or ["自定义源"])[0].strip() or "自定义源"
                if not u.startswith("http"):
                    return self._send(400, json.dumps({"ok": False, "msg": "地址无效"}))
                dbrun("""INSERT INTO sources(kind,name,url,enabled,pri,added_at)
                         VALUES('live',?,?,1,3,?)
                         ON CONFLICT(kind,name) DO UPDATE SET url=excluded.url,
                         enabled=1""", (nm, u, int(time.time())))
                n = refresh_live(nm, u)
                return self._send(200, json.dumps(
                    {"ok": True, "msg": "已导入 %d 个频道" % n}, ensure_ascii=False))
            if p.path == "/api/live/refresh":
                n = refresh_live()
                return self._send(200, json.dumps(
                    {"ok": True, "msg": "已刷新 %d 个频道" % n}, ensure_ascii=False))
            if p.path == "/api/sources":
                kind = (form.get("kind") or ["cms"])[0]
                nm = (form.get("name") or [""])[0].strip()
                u = (form.get("url") or [""])[0].strip()
                if not nm or not u.startswith("http"):
                    return self._send(400, json.dumps({"ok": False, "msg": "名称或地址无效"}))
                dbrun("""INSERT INTO sources(kind,name,url,enabled,pri,added_at)
                         VALUES(?,?,?,1,?,?)
                         ON CONFLICT(kind,name) DO UPDATE SET url=excluded.url,
                         enabled=1""", (kind, nm, u, 3, int(time.time())))
                return self._send(200, json.dumps({"ok": True}, ensure_ascii=False))
            if p.path == "/api/add":
                magnet = (form.get("magnet") or [""])[0]
                cat = (form.get("category") or ["电影"])[0]
                if not magnet.startswith("magnet:"):
                    mm = re.search(r"magnet:\?xt=urn:btih:[A-Za-z0-9]+(?:[^\s\"'<>]*)", raw)
                    if mm:
                        magnet = mm.group(0)
                if not magnet.startswith("magnet:"):
                    return self._send(400, json.dumps(
                        {"ok": False, "msg": "没收到有效的磁力链接"}, ensure_ascii=False))
                ok, msg = QBT.add(magnet, cat)
                return self._send(200, json.dumps({"ok": ok, "msg": msg}, ensure_ascii=False))
            if p.path == "/api/torrent":
                hh = (form.get("hash") or [""])[0]
                act = (form.get("act") or [""])[0]
                if not hh or act not in ("pause", "resume", "delete"):
                    return self._send(400, json.dumps({"ok": False, "msg": "参数错误"}))
                ok, msg = QBT.action(hh, act)
                return self._send(200, json.dumps({"ok": ok, "msg": msg}, ensure_ascii=False))
            return self._send(404, json.dumps({"error": "not found"}))
        except Exception as e:
            return self._send(500, json.dumps({"ok": False, "msg": str(e)[:200]}, ensure_ascii=False))


def main():
    print("NAS 影视下载助手 v2")
    print("  端口        : %d" % PORT)
    print("  qBittorrent : %s" % QBT_URL)
    print("  片源站      : %s" % ", ".join(s["name"] for s in SITES))
    print("  Tracker     : 注入 %d 个 | scrape 入口 %d 个" % (len(TRACKERS), len(SCRAPE_HOSTS)))
    print("  qBittorrent : %s" % ("连接成功" if QBT.login() else "连接失败（搜索仍可用）"))
    class Srv(ThreadingHTTPServer):
        daemon_threads = True
        request_queue_size = 128
        allow_reuse_address = True

    srv = Srv(("0.0.0.0", PORT), H)
    # 先把上次的缓存读回来，再开个后台线程预热，
    # 这样容器刚重启时首页也不会是白板
    cache_load()
    try:
        refresh_recommend_async()
    except Exception:
        pass
    # 分类映射也顺手预热：点「浏览」是同步等结果的，不能让它现场探测
    try:
        threading.Thread(target=catmap_warm, daemon=True).start()
    except Exception:
        pass
    print("  就绪")
    srv.serve_forever()


if __name__ == "__main__":
    main()
