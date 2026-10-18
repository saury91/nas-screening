# -*- coding: utf-8 -*-
"""片库服务：跨源搜索聚合、详情、分类浏览、首页推荐。"""
import concurrent.futures
import json
import re
import threading
import time

from ..cache import cache_age, cache_peek, cache_put, cache_valid
from ..config import (BROWSE_SITES, CAT_ORDER, CATMAP_TTL, FALLBACK_HOT,
                      RECOMMEND_TTL, SEARCH_DEADLINE, TIMEOUT)
from ..db import dbrun
from ..log import log
from ..net import http_get, raw_get
from ..providers import active_sites
from ..providers.cms import (_by_type, _cms_query, _extract_meta,
                             _latest, _parse_play_url, _qs)
from ..text import (_desc_better, _norm_key, _relevance, _score_max,
                    Q_RANK, guess_quality)

# 全局搜索池：以前每次搜索都新建 13 个线程再 shutdown(wait=False)，
# 连续搜几次就有一堆线程还在跑。复用池子能自然形成背压。
_SEARCH_POOL = concurrent.futures.ThreadPoolExecutor(
    max_workers=16, thread_name_prefix="cms-search")


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
        key = (k, str(it.get("year") or "").strip())
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
                           active_sites()[:8]):
            latest.extend(part)
    # ② 按分类 ID 补（电影/纪录片在「最近更新」里几乎没有，必须这样拉）
    pool = latest + _by_type(active_sites()[:4], range(3, 21))
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





def detect_catmap(site):
    """探测某站的分类 ID 各自属于哪个大类。

    各站 t 的排法完全不同（暴风 t=20 才是电影，别家 t=6 就是），而且会变。
    所以不写死映射——探一次记下来缓存一天，浏览时才知道该拉哪个 t。

    优先走 MacCMS 的 `ac=list`：它直接把 class 表（type_id + type_name）返回，
    一次请求就够。早期版本盲探 30 个 ID × 每个源，首点「电影」实测 26 秒。
    """
    key = "catmap|" + site["name"]
    c = cache_peek(key)
    if c and cache_valid(key, CATMAP_TTL):
        return c

    try:
        api = site["api"]
        sep = "&" if "?" in api else "?"
        d = json.loads(http_get(api + sep + "ac=list", timeout=8))
        classes = []
        if isinstance(d, dict):
            classes = d.get("class") or d.get("classes") or []
        out = {}
        for x in classes:
            if not isinstance(x, dict):
                continue
            tid = x.get("type_id", x.get("id"))
            c2 = _cat(x.get("type_name") or x.get("name") or "")
            if tid is not None and c2:
                out[str(tid)] = c2
        if out:
            cache_put(key, out)
            log("分类映射 %s：ac=list 拿到 %d 个分类" % (site["name"], len(out)))
            return out
    except Exception:
        pass                     # 有的站不支持 ac=list，退回盲探

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
    """启动时后台把分类映射预热掉，免得第一次点「浏览」要干等。

    各站之间并发，站内 ac=list 一次搞定；不支持 ac=list 的站才走盲探。
    """
    def one(s):
        try:
            detect_catmap(s)
        except Exception:
            pass
    sites = active_sites()[:BROWSE_SITES]
    if not sites:
        return
    with concurrent.futures.ThreadPoolExecutor(max_workers=len(sites)) as ex:
        list(ex.map(one, sites))


def browse_catalog(cat="", year="", area="", pg=1, limit=60, site=None):
    """按大类 / 年份 / 地区浏览片库。

    做法：用每一站自己的分类 ID 拉一页（pg 直接透传给采集站），
    回来统一按 type_name 归一、再按年份地区过滤。
    这样不用管各站 ID 怎么排，也不会因为某个站的 ID 变了就整个失效。
    """
    # 一次要打 4 个站 × 4 个分类，缓存住，翻回来才不会又等 5 秒
    ck = "browse|%s|%s|%s|%d|%s" % (cat, year, area, pg, site or "")
    c = cache_peek(ck)
    if c is not None and cache_valid(ck, 600):
        return c

    tasks, nsite, out = [], 0, []
    pool = active_sites()
    if site:
        pool = [x for x in pool if x["name"] == site]
    else:
        pool = pool[:BROWSE_SITES]
    for s in pool:
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
    futs = [_SEARCH_POOL.submit(one, s) for s in active_sites()]
    try:
        done, _ = concurrent.futures.wait(futs, timeout=deadline)
        for f in done:
            try:
                rows.extend(f.result())
            except Exception:
                pass
    except Exception:
        pass

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
        first_url = (r["eps"][0].get("url") if r["eps"] else "") or ""
        m["sources"].append({
            "site": r["site"], "remarks": r["remarks"],
            "eps": r["eps"], "count": len(r["eps"]),
            # 画质从备注 / 播放地址 / 站名里认，认不出就留空
            "q": guess_quality(r["remarks"], first_url, r["site"]),
        })

    items = list(merged.values())
    for it in items:
        # 画质高的排前面（默认就会选中最高画质的线路），同画质再看站点与集数
        it["sources"].sort(key=lambda s: (Q_RANK.get(s.get("q") or "", 9),
                                          s["site"] != "暴风资源", -s["count"]))
        it["ep_count"] = max(s["count"] for s in it["sources"])
        it["site_count"] = len(it["sources"])
        it["rel"] = _relevance(it["name"], kws)
    # 相关度优先，其次源多、集数全
    items.sort(key=lambda x: (-x["rel"], -x["site_count"], -x["ep_count"]))
    return items[:60]


def get_play_url(url):
    """校验并返回可播放地址（顺带探测是主清单还是子清单）"""
    try:
        st, body, _, _ = raw_get(url, ref=url, timeout=12)
        txt = body.decode("utf-8", "replace")
        if "#EXTM3U" not in txt:
            return None
        return {"url": url, "master": "#EXT-X-STREAM-INF" in txt, "size": len(body)}
    except Exception:
        return None


