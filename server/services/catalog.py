# -*- coding: utf-8 -*-
"""片库服务：跨源搜索聚合、详情、分类浏览、首页推荐。"""
import concurrent.futures
import json
import re
import threading
import time
import urllib.error
import urllib.request

from ..cache import cache_age, cache_drop, cache_peek, cache_put, cache_valid
from ..config import (BROWSE_DEADLINE, BROWSE_SITES, CAT_ORDER, CATMAP_TTL,
                      FALLBACK_HOT, RECOMMEND_TTL, SEARCH_DEADLINE, TIMEOUT)
from ..db import dbrun, pref_json, pref_set_json
from ..log import log
from ..net import http_get, raw_get
from ..providers import active_sites
from ..providers.cms import (_by_type, _cms_query, _extract_meta,
                             _latest, _parse_play_url, _qs, parse_lines)
from ..text import (_desc_better, _norm_key, _relevance, _score_max,
                    Q_RANK, guess_quality)

# 全局搜索池：以前每次搜索都新建 13 个线程再 shutdown(wait=False)，
# 连续搜几次就有一堆线程还在跑。复用池子能自然形成背压。
# 源有 70+ 个，worker 少于源数就要排队：16 个 worker 跑 70 个源，光排队
# 就吃掉好几秒，「到点返回」也等着最慢那一轮。放宽到 32，基本一轮打完。
_SEARCH_POOL = concurrent.futures.ThreadPoolExecutor(
    max_workers=32, thread_name_prefix="cms-search")
# 浏览单独一个池：搜索和翻页互不挤占，慢站也不会把搜索的 worker 占满。
# 一次浏览是 BROWSE_SITES(8) × 最多 4 个子分类 = 32 个请求，10 个 worker 要排
# 3~4 轮、每轮 3~9 秒，到点（deadline）基本一个都没回来 —— 剧集/动漫/综艺
# 因此长期显示「暂时没货」。worker 提到 32，一轮并发打完。
_BROWSE_POOL = concurrent.futures.ThreadPoolExecutor(
    max_workers=32, thread_name_prefix="cms-browse")
# 分类映射探测单独一个池：它经常要等超时，混进浏览池会把 worker 占满
_CM_POOL = concurrent.futures.ThreadPoolExecutor(
    max_workers=12, thread_name_prefix="cms-catmap")
# detect_catmap_full 自己是跑在 _CM_POOL 里的，它内部的父级分类/盲探必须另开池：
# 同一个池再提交就会「worker 全在等自己排的队」，直接死锁。
_CM_SUB_POOL = concurrent.futures.ThreadPoolExecutor(
    max_workers=4, thread_name_prefix="cms-catmap-sub")
_CM_BLIND_POOL = concurrent.futures.ThreadPoolExecutor(
    max_workers=6, thread_name_prefix="cms-catmap-blind")
_CM_WARM_POOL = concurrent.futures.ThreadPoolExecutor(
    max_workers=12, thread_name_prefix="cms-catmap-warm")
# 推荐构建里的两段并发：以前每次刷新都新建池，线程数随刷新次数涨
_TASTE_POOL = concurrent.futures.ThreadPoolExecutor(
    max_workers=3, thread_name_prefix="cms-taste")
_LATEST_POOL = concurrent.futures.ThreadPoolExecutor(
    max_workers=8, thread_name_prefix="cms-latest")
# 浏览时最多等分类映射多久。到点先拿已回来的站出画面，剩下的继续在后台探，
# 探完写缓存，第二次点击就是全的 —— 绝不能让一个慢站决定整页等多久。
_BROWSE_CM_WAIT = 4.0
# 片库列表变化很慢：半小时内直接复用，别每次都重新聚合
_BROWSE_TTL = 1800
# 空结果只压一会儿：多半是慢站没赶上 deadline，缓存太久用户点一次空一次
_BROWSE_EMPTY_TTL = 60
# 探测失败的站点记 10 分钟：新浪/索尼/暴风/天堂的 ac=list 总是空，
# 不记账的话每次点分类都要重新等它们各 8 秒
_CATMAP_FAIL_TTL = 600


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


# ── 封面图床可用性 ───────────────────────────────────────────
# 各采集站有各自的图床（域名互不相同），偶尔某个图床会整体连不上（实测
# disabled.invalid 的 443 被 RST），那一站的封面就全是空白。同一部片在别的站也有，封面域名
# 不同，所以这里记住每个图床还能不能用，选封面时优先挑能连上的。
#
# 判定用 TLS 握手而不是 HTTP 请求：图床根路径 404 也算可用，握手失败才是
# 真的连不上。结论缓存半小时并落库，重启后不用重新等。

_PIC_TTL = 1800
_PIC_STATE = {}          # host -> {"ok": bool, "at": ts}
_PIC_LK = threading.Lock()
_PIC_LOADED = False
_PIC_POOL = concurrent.futures.ThreadPoolExecutor(
    max_workers=8, thread_name_prefix="pic-probe")


def _pic_host(url):
    """取 URL 的 host[:port]，空串表示不是绝对地址"""
    if "://" not in url:
        return ""
    rest = url.split("://", 1)[1]
    return rest.split("/", 1)[0].strip().lower()


def _pic_known(host):
    """已知结论返回 True/False；没探测过或结论过期返回 None"""
    if not host:
        return None
    st = _PIC_STATE.get(host)
    if not st:
        return None
    if time.time() - st.get("at", 0) > _PIC_TTL:
        return None
    return st.get("ok")


def _pic_load():
    global _PIC_LOADED
    if _PIC_LOADED:
        return
    _PIC_LOADED = True
    try:
        d = pref_json("pic.hosts", {}) or {}
        for h, st in d.items():
            if isinstance(st, dict) and "ok" in st:
                _PIC_STATE[h] = {"ok": bool(st["ok"]), "at": float(st.get("at") or 0)}
    except Exception:
        pass


def _pic_probe(host):
    """对图床发一个真实请求：只要有 HTTP 响应（哪怕 404）就算可用。

    不能用 TLS 握手判断 —— 实测 disabled.invalid 这类 Cloudflare 站点握手能过，
    但随后的 HTTP 请求被 RST，用户看到的还是空封面。
    """
    try:
        port = int(host.split(":")[1]) if ":" in host else 443
        scheme = "https" if port == 443 else "http"
        req = urllib.request.Request(
            f"{scheme}://{host}/",
            headers={"User-Agent": "Mozilla/5.0", "Accept": "image/*,*/*"},
        )
        with urllib.request.urlopen(req, timeout=4) as r:
            r.read(64)
        return True
    except urllib.error.HTTPError:
        return True       # 403/404 也说明这台机器连得上
    except Exception:
        return False


def _pic_store(host, ok):
    with _PIC_LK:
        _PIC_STATE[host] = {"ok": bool(ok), "at": time.time()}
        snapshot = dict(_PIC_STATE)
    try:
        pref_set_json("pic.hosts", snapshot)
    except Exception:
        pass


_PIC_INFLIGHT = set()


def _pic_probe_store(host):
    """探测并落状态。放进线程池执行，所以「谁等在这」和「结不结果」解耦。"""
    try:
        _pic_store(host, _pic_probe(host))
    except Exception:
        pass
    finally:
        with _PIC_LK:
            _PIC_INFLIGHT.discard(host)


def _pic_warm(hosts, timeout=3.0):
    """并发探测一批未知域名。

    timeout=0 表示不等结果、纯后台探：以前这里会把 pending 的任务 cancel 掉，
    等于什么都没探；现在探测和落状态绑在同一个任务里，等不等都能有结论。
    """
    todo = []
    with _PIC_LK:
        for h in dict.fromkeys(hosts):
            if not h or _pic_known(h) is not None or h in _PIC_INFLIGHT:
                continue
            _PIC_INFLIGHT.add(h)
            todo.append(h)
    if not todo:
        return
    futs = [_PIC_POOL.submit(_pic_probe_store, h) for h in todo]
    if timeout > 0:
        concurrent.futures.wait(futs, timeout=timeout)


def _pick_cover(pics, wait=3.0):
    """同一部片的多个源各有封面，挑一个图床能连上的。

    顺序：已确认可用 → 还没探测过（乐观沿用原行为）→ 已知不可用。
    wait 是同步等待图床探测的上限：浏览列表传 0（一页几十部片，等不起），
    详情/搜索保持 3 秒。
    """
    pics = [p for p in dict.fromkeys(pics) if (p or "").strip()]
    if not pics:
        return ""
    _pic_load()
    if len(pics) > 1:
        _pic_warm([_pic_host(p) for p in pics], timeout=wait)
    for p in pics:
        if _pic_known(_pic_host(p)) is True:
            return p
    for p in pics:
        if _pic_known(_pic_host(p)) is None:
            return p
    return pics[0]


def bad_pic_hosts():
    """当前判定为连不上的封面图床，随配置包下发。

    App 的片库是直连采集站拉的（不走 /api/browse），拿不到这边择优过的封面，
    所以把结论一并给它：同一个域名下它就不用再去试一遍了。
    """
    _pic_load()
    return sorted(h for h, st in _PIC_STATE.items() if st.get("ok") is False)


def refresh_pic_hosts(timeout=40.0):
    """把各采集站实际在用的封面图床都探一遍。

    App 直连采集站时，黑名单必须由服务端给全 —— 只靠 /api/browse 沿途碰到的
    那几个域名不够，容易被某个只在 App 侧出现的图床漏掉。
    各站拉一页样本即可拿到它们的图床域名，几十个域名并发探测，几十秒就能跑完。
    """
    def one(s):
        try:
            items = _qs(s, "ac=detail&pg=1")
        except Exception:
            return set()
        out = set()
        for v in items[:20]:
            h = _pic_host((v.get("pic") or "").strip())
            if h:
                out.add(h)
        return out

    hosts = set()
    try:
        for hs in _PIC_POOL.map(one, active_sites()):
            hosts |= hs
    except Exception:
        pass
    if hosts:
        _pic_warm(sorted(hosts), timeout=timeout)
    return bad_pic_hosts()


def _dedup(items, limit=0, pic_wait=3.0):
    """同名同年份只留一条。

    片名里带不带年份、括号打不打，各家源都不一样（「夺命狂花」vs「夺命狂花 2026」），
    所以把名字尾部的年份剥掉再比，年份统一看 year 字段——
    这样既不漏合并，也不会把「沙丘 1984」和「沙丘 2021」当成同一部。

    留下的那条沿用第一份数据，但封面会在同片各源的候选里挑一个能打开的
    （见 _pick_cover）：某个图床整体连不上时，不至于整页都是空白卡。
    """
    groups, order = {}, []
    for it in items:
        k = _norm_key(it.get("name", ""))
        k = re.sub(r"(19|20)\d{2}$", "", k) or k
        if not k:
            continue
        key = (k, str(it.get("year") or "").strip())
        if key not in groups:
            groups[key] = []
            order.append(key)
        groups[key].append(it)

    out = []
    for key in order:
        g = groups[key]
        first = g[0]
        pics = [(x.get("pic") or "").strip() for x in g]
        if any(pics):
            cover = _pick_cover(pics, wait=pic_wait)
            if cover and cover != (first.get("pic") or ""):
                first = dict(first)
                first["pic"] = cover
        out.append(first)
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
        for part in _TASTE_POOL.map(one, taste[:3]):
            liked.extend(part)
        liked = _dedup(liked, limit)

    # ① 最近更新（快，剧集/动漫/综艺主要靠它）
    latest = []
    for part in _LATEST_POOL.map(lambda s: _latest(s, hours=72, limit=60, pages=5),
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





# 「电影 / 电视剧 / 动漫」这类一级分类只是目录分组，索尼、豪华、天堂等站
# 对父级 type_id 直接返回 0 条（叶子分类动作片/国产剧才有内容）。
# 不剔掉的话，App 拿它当该分类的前 3 个 id 之一，每页都白拉一趟。
_PARENT_NAMES = {"电影", "电影片", "电视剧", "连续剧", "动漫", "动漫片",
                 "综艺", "综艺片", "纪录片", "记录片"}


def _type_alive(site, tid):
    """探某个 type_id 是否真有内容。网络异常按「有」处理，别误杀正常分类。"""
    try:
        return bool(_qs(site, "ac=detail&t=%s&pg=1" % tid, timeout=8))
    except Exception:
        return True


def detect_catmap_full(site):
    """探测某站每个分类 ID 属于哪个大类，顺带记下站内的分类名。

    各站 t 的排法完全不同（暴风 t=20 才是电影，别家 t=6 就是），而且会变。
    所以不写死映射——探一次记下来缓存一天，浏览时才知道该拉哪个 t。

    优先走 MacCMS 的 `ac=list`：它直接把 class 表（type_id + type_name）返回，
    一次请求就够。早期版本盲探 30 个 ID × 每个源，首点「电影」实测 26 秒。

    拿到 class 表后还要把「空壳父级分类」剔掉：这是「索尼/豪华/天堂明明探到
    40 个分类、浏览却一条不出」的根因 —— 映射是对的，但 App 取前 3 个 id
    时把 t=1（父级 电影）排在首位，那一条永远空手而归。

    返回 (cats, names)：`cats` 是 id → 大类，`names` 是 id → 站内分类名。
    names 是给 App 选「地区」用的：猫眼「动漫」下面依次是 动画片 / 国产动漫 /
    欧美动漫 / 日韩动漫 / 里番动漫，每页只取前 3 个 id 时，「日本」永远命中
    不到日韩动漫 —— 结果就是筛完只剩几条。
    """
    key = "catmap3|" + site["name"]
    c = cache_peek(key)
    if isinstance(c, dict):
        if c.get("cats") and cache_valid(key, CATMAP_TTL):
            return c["cats"], c.get("names") or {}
        # 上一次探测失败：短时间内别再试。失败不记账的话，这种站每次点分类
        # 都要重新等它超时一遍（8 秒 × 两个阶段）。
        if c.get("failed") and cache_valid(key, _CATMAP_FAIL_TTL):
            return {}, {}

    try:
        api = site["api"]
        sep = "&" if "?" in api else "?"
        d = json.loads(http_get(api + sep + "ac=list", timeout=6))
        classes = []
        if isinstance(d, dict):
            classes = d.get("class") or d.get("classes") or []
        out, names, parents = {}, {}, []
        for x in classes:
            if not isinstance(x, dict):
                continue
            tid = x.get("type_id", x.get("id"))
            tname = (x.get("type_name") or x.get("name") or "").strip()
            c2 = _cat(tname)
            if tid is not None and c2:
                out[str(tid)] = c2
                if tname:
                    names[str(tid)] = tname
                if tname in _PARENT_NAMES:
                    parents.append(str(tid))
        # 父级分类并发探一下，空的直接从映射里删掉
        if parents:
            alive = list(_CM_SUB_POOL.map(lambda t: _type_alive(site, t), parents))
            dead = [t for t, ok in zip(parents, alive) if not ok]
            for t in dead:
                out.pop(t, None)
                names.pop(t, None)
            if dead:
                log("分类映射 %s：父级分类 %s 返回空，已剔除"
                    % (site["name"], ",".join(dead)))
        if out:
            cache_put(key, {"cats": out, "names": names})
            log("分类映射 %s：ac=list 拿到 %d 个分类" % (site["name"], len(out)))
            return out, names
    except Exception:
        pass                     # 有的站不支持 ac=list，退回盲探

    def probe(t):
        # 盲探是「赌这个 ID 有货」，请求本身要快：4 秒没响应就算死
        try:
            L = _qs(site, "ac=detail&t=%d" % t, timeout=4)
        except Exception:
            return (t, "", False)
        names = [_cat(x.get("type")) for x in L[:14]]
        names = [n for n in names if n]
        if len(names) < 3:            # 样本太少，判不出来
            return (t, "", True)
        return (t, max(set(names), key=names.count), True)

    out, fails = {}, 0
    # 盲探是 30 个请求，别开太多 worker：它在后台跑，抢带宽会把正常站拖慢。
    # 关键是不能「等全部跑完」：站点连不上时 30 个 ID 会白等好几分钟，
    # 连续失败几个就直接收手（剩下的取消掉）。
    futs = [_CM_BLIND_POOL.submit(probe, t) for t in range(1, 31)]
    try:
        for f in concurrent.futures.as_completed(futs):
            t, c2, ok = f.result()
            if not ok:
                fails += 1
                if fails >= 5:
                    break
                continue
            if c2:
                out[str(t)] = c2
    except Exception:
        pass
    finally:
        # 池是常驻的，不能 shutdown；只撤掉自己这批没跑的任务
        for f in futs:
            f.cancel()

    if out:
        cache_put(key, {"cats": out, "names": {}})
    else:
        # 记成失败，10 分钟内不再重探（含 ac=list 抛异常 / 返回空）
        cache_put(key, {"cats": {}, "names": {}, "failed": True})
    return out, {}


def detect_catmap(site):
    """只要 id → 大类的映射（服务端浏览用）"""
    return detect_catmap_full(site)[0]


_CATMAP_LK = threading.Lock()


def catmap_warm():
    """启动时后台把分类映射预热掉，免得第一次点「浏览」要干等。

    各站之间并发，站内 ac=list 一次搞定；不支持 ac=list 的站才走盲探。

    注意这里**不受 BROWSE_SITES 限制**：分类映射会随配置包下发给 App，
    App 端自己挑有映射的源来拉列表。只预热前几个的话，剩下的源在 App 里
    等于不存在 —— 片库凭空少掉一大半。
    """
    # 启动线程和片库预热都会走到这儿：第二个进来直接退出，别重复打站
    if not _CATMAP_LK.acquire(blocking=False):
        return
    try:
        _catmap_warm_inner()
    finally:
        _CATMAP_LK.release()


def _catmap_warm_inner():
    def one(s):
        try:
            return detect_catmap(s) or None
        except Exception:
            return None
    sites = active_sites()
    if not sites:
        return
    missed = [s for s, m in zip(sites, _CM_WARM_POOL.map(one, sites)) if not m]
    # 首轮拉空的隔一会儿再试一次：十几个站同时打，个别站会瞬时超时/限流，
    # 失败一次就永久留空的话，App 里那几个源等于不存在（片库直接少一半）。
    if missed:
        time.sleep(1.5)
        def again(s):
            # 先抹掉上一轮记的「失败」：不抹的话重试会被负缓存直接挡回来
            cache_drop("catmap3|" + s["name"])
            try:
                return detect_catmap(s) or None
            except Exception:
                return None
        for s, m in zip(missed, _CM_WARM_POOL.map(again, missed)):
            if not m:
                log("分类映射 %s：重试后仍为空，该源不参与浏览" % s["name"])


def _area_keys(area):
    """地区筛选的关键词（含各站常见别名）。第一个一定是界面上的原值。"""
    extra = {
        "内地": ["国产", "大陆"],
        "中国香港": ["港台", "香港"], "香港": ["港台", "香港"],
        "中国台湾": ["港台", "台湾"], "台湾": ["港台", "台湾"],
        # 「日韩」分不出具体国家，选日本/韩国时都算命中，总比整批漏掉强
        "日本": ["日韩", "日漫"], "韩国": ["日韩", "韩剧"],
        "欧美": ["美国", "英国", "法国"],
    }
    return [area] + extra.get(area, [])


def _catmaps(pool):
    """并发取各站的分类映射；到点只收已回来的。

    以前这里是 `for s in pool: detect_catmap_full(s)`：每站 ac=list 8 秒超时
    （失败还要再盲探 30 个 ID），8 个站串起来，冷缓存点一次分类实测 88 秒。
    现在并发 + 只等 2 秒：先拿已回来的站出画面，没回来的继续在后台探、
    探完自己写缓存，下一次点击就是全的。
    """
    if not pool:
        return {}
    futs = {_CM_POOL.submit(detect_catmap_full, s): s for s in pool}
    done, _pending = concurrent.futures.wait(list(futs), timeout=_BROWSE_CM_WAIT)
    out = {}
    for f in done:
        try:
            out[futs[f]["name"]] = f.result()
        except Exception:
            pass
    return out


def browse_catalog(cat="", year="", area="", pg=1, limit=60, site=None,
                   deadline=BROWSE_DEADLINE):
    """按大类 / 年份 / 地区浏览片库。

    做法：用每一站自己的分类 ID 拉一页（pg 直接透传给采集站），
    回来统一按 type_name 归一、再按年份地区过滤。
    这样不用管各站 ID 怎么排，也不会因为某个站的 ID 变了就整个失效。
    """
    ck = "browse|%s|%s|%s|%d|%s" % (cat, year, area, pg, site or "")
    c = cache_peek(ck)
    if c is not None:
        # 映射没齐时算出来的结果只短存：等后台把映射补齐，下一次重新聚合
        ttl = (60 if c.get("partial")
               else (_BROWSE_TTL if c.get("items") else _BROWSE_EMPTY_TTL))
        if cache_valid(ck, ttl):
            return c

    tasks, nsite, out = [], 0, []
    pool = active_sites()
    if site:
        pool = [x for x in pool if x["name"] == site]
    else:
        pool = pool[:BROWSE_SITES]
    maps = _catmaps(pool)
    for s in pool:
        cm, tnames = maps.get(s["name"], ({}, {}))
        ts = [t for t, c2 in cm.items() if not cat or c2 == cat]
        if ts and area:
            # 分类名里带地区的排前面：猫眼「动漫」有 5 个子分类，
            # 只取前 4 个的话「日韩动漫」经常被挤掉，筛日本就没货了
            keys = _area_keys(area)
            ts.sort(key=lambda t: 0 if any(k in tnames.get(t, "") for k in keys) else 1)
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
        # 原来用 ex.map + with：离开 with 要等所有任务结束，最慢那个站卡到
        # TIMEOUT(20s) 才返回，翻页就一直是骨架。现在到点取已回来的。
        futs = [_BROWSE_POOL.submit(fetch, t) for t in tasks]
        done, _ = concurrent.futures.wait(futs, timeout=deadline)
        for f in done:
            try:
                out.extend(f.result())
            except Exception:
                pass

    if year:
        out = [x for x in out if str(x.get("year") or "").startswith(year)]
    if area:
        out = [x for x in out if any(k in (x.get("area") or "")
                                     for k in _area_keys(area))]

    # 一页几十部片：封面图床探测不再同步等（等一次就是 3 秒），
    # 先乐观用第一个封面，探测结果下一次请求就生效
    items = _dedup(out, limit, pic_wait=0)
    # 新片优先；同年份里信息全的（有简介）优先
    items.sort(key=lambda x: (str(x.get("year") or "0"),
                              1 if x.get("desc") else 0), reverse=True)
    res = {"items": items, "pg": pg, "cat": cat, "year": year, "area": area,
           "more": len(out) > limit, "total": len(out),
           "sites": nsite, "at": int(time.time()),
           # 还有站的映射这次没赶上（超时）：结果不完整，只短存 60 秒。
           # 注意不能看 nsite：本来就没映射的坏站也在 maps 里，算「已答复」。
           "partial": len(maps) < len(pool)}
    # 空结果也存，但读取时只认 60 秒：既能挡住「连点两次等两遍」，
    # 又不至于让这个分类半小时都显示没货
    cache_put(ck, res)
    return res


_BROWSE_WARM_CATS = ["电影", "剧集", "动漫", "综艺", "纪录片"]


def browse_warm():
    """把五个分类的第一页先聚合好：用户点标签时直接命中缓存。

    串行跑（一次一个分类）：五个分类并发等于 40 个站同时打，
    采集站会限流，反而比串行更慢。
    """
    for cat in _BROWSE_WARM_CATS:
        try:
            ck = "browse|%s|||1|" % cat
            # 离过期还有 5 分钟以上就不动它，别把用户正在看的那页顶掉
            if cache_valid(ck, _BROWSE_TTL - 300):
                continue
            t0 = time.time()
            data = browse_catalog(cat=cat, pg=1)
            log("片库预热 %s：%d 条 / %.1f 秒"
                % (cat, len(data.get("items") or []), time.time() - t0))
        except Exception as e:
            log("片库预热 %s 失败：%s" % (cat, e))
        time.sleep(1.0)


def browse_warm_loop():
    """启动预热 + 常驻刷新。分类映射先建好，否则浏览会拿到半截映射。"""
    try:
        catmap_warm()
        log("片库预热启动")
    except Exception as e:
        log("片库预热启动失败：%s" % e)
    while True:
        try:
            browse_warm()
        except Exception:
            pass
        time.sleep(300)


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

def search_online_stat(kw, deadline=SEARCH_DEADLINE):
    """并发查所有采集站，按片名合并，每部片列出所有可用源

    deadline：整体限时（秒）。十几个站里总有几个慢的，到点就把已经回来的站
    先用上。默认取 SEARCH_DEADLINE —— 以前默认 None 等于 futures.wait 无限等，
    api 层两个调用点都没传，于是「搜索」会一直挂到最慢的源自己超时（TIMEOUT=20s）。
    """
    kws = (kw or "").strip()
    if not kws:
        return [], {"sites": 0, "responded": 0}
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
            # 一条 vod 可能有多条播放线路（$$$ 分隔），逐条生成记录，
            # 否则集名会重复成「第01集 第01集 第01集 第01集」
            lines = parse_lines(v)
            for line_name, eps in lines:
                label = site["name"]
                if line_name and len(lines) > 1:
                    label = "%s · %s" % (label, line_name)
                rec = {
                    "name": nm,
                    "year": str(v.get("vod_year") or "").strip(),
                    "pic": (v.get("vod_pic") or "").strip(),
                    "type": (v.get("type_name") or "").strip(),
                    "remarks": (v.get("vod_remarks") or "").strip(),
                    "site": label,
                    "pri": site["pri"],
                    "eps": eps,
                }
                rec.update(_extract_meta(v))
                out.append(rec)
        return out

    rows = []
    sites = active_sites()
    futs = [_SEARCH_POOL.submit(one, s) for s in sites]
    responded = 0
    try:
        # wait 只等 deadline；没回来的源留在池里自己收尾（结果丢弃），
        # 不能 cancel —— 阻塞在 socket 读上的线程不响应取消
        done, _ = concurrent.futures.wait(futs, timeout=deadline)
        for f in done:
            try:
                rows.extend(f.result())
                responded += 1
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
                "type": r["type"], "sources": [], "pics": [],
                "desc": "", "actor": "", "director": "", "area": "",
                "lang": "", "score": "", "pubdate": "", "total": "",
            }
        m = merged[k]
        # 每个源的封面都留着，最后统一挑一个图床能连上的
        pp = (r.get("pic") or "").strip()
        if pp and pp not in m["pics"]:
            m["pics"].append(pp)
        # 年份/类型/演员… 取第一个非空的
        for f in ("year", "type", "actor", "director",
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
        it["pic"] = _pick_cover(it.pop("pics", []) or [it.get("pic") or ""])
        it["ep_count"] = max(s["count"] for s in it["sources"])
        it["site_count"] = len(it["sources"])
        it["rel"] = _relevance(it["name"], kws)
    # 相关度优先，其次源多、集数全
    items.sort(key=lambda x: (-x["rel"], -x["site_count"], -x["ep_count"]))
    return items[:60], {"sites": len(sites), "responded": responded}


def search_online(kw, deadline=SEARCH_DEADLINE):
    """只要结果列表的旧签名（推荐流等后台任务用）"""
    return search_online_stat(kw, deadline)[0]


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


