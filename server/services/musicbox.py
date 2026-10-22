# -*- coding: utf-8 -*-
"""在线歌单推荐（公开接口，无需登录）。

为什么换成歌单：之前是按关键词去搜歌，搜出来的是「热门单曲」，
热门单曲恰好是会员试听的重灾区（实测某歌手搜 10 首，10 首全是 30 秒片段）。
换成品类歌单后情况反过来——精选歌单里大量是独立音乐、纯音乐、氛围乐，
这些本身对所有人开放，实测抽检 12 首有 9~11 首能整曲播放。

只做三件事：拉歌单、读歌单里的曲目、把播放地址转成本机同源代理。
不下载音频、不缓存文件、不做任何转码。
"""
import json
import urllib.parse

from ..config import MUSICBOX_API, MUSICBOX_REFERER, MUSICBOX_SERVER, MUSIC_FULL_MIN, UA
from ..db import pref_read
from ..net import raw_get
from . import proxy

_SERVER = MUSICBOX_SERVER
_TIMEOUT = 15


def api_base():
    """聚合接口地址：设置/seed 里填过的优先，其次环境变量。

    两者都没有就是「没配」——仓库不内置任何在线音乐服务。
    """
    return (pref_read("music.api") or MUSICBOX_API or "").rstrip("/")


def referer():
    """接口要求带的 Referer；没配就是空，调用方据此判断能不能用。"""
    return pref_read("music.referer") or MUSICBOX_REFERER or ""


def configured():
    return bool(api_base())


def _hdr():
    # 这个接口对 Referer 校验比较死，缺了直接 403
    return {"Referer": referer(), "User-Agent": UA, "Accept": "application/json,*/*"}


def _json(path, timeout=_TIMEOUT):
    base = api_base()
    if not base:
        raise RuntimeError("未配置在线音乐接口")
    st, body, _, _ = raw_get(base + path, timeout=timeout, extra=_hdr())
    if st >= 400:
        raise RuntimeError("上游 %d" % st)
    return json.loads(body.decode("utf-8", "replace"))


def _track(t):
    """统一曲目结构：直接带本机可播的 url，前端不用再问一次。"""
    sid = str(t.get("id") or "").strip()
    if not sid:
        return None
    return {
        "id": sid,
        "title": (t.get("name") or "").strip(),
        "artist": (t.get("artist") or "").strip(),
        "cover": (t.get("pic") or "").strip(),
        "lrc": (t.get("lrc") or "").strip(),
        "provider": "netease",
        "url": "/api/music/online/stream?id=" + sid,
    }


def _pl(p):
    """歌单对象归一。上游各接口字段一致，统一在这里收口。"""
    pid = str(p.get("id") or "").strip()
    if not pid:
        return None
    return {
        "id": pid,
        "name": (p.get("name") or "").strip(),
        "cover": (p.get("coverImgUrl") or "").strip(),
        "count": int(p.get("trackCount") or 0),
        "play": int(p.get("playCount") or 0),
    }


_REC = {"at": 0, "data": None}


def recommend(limit=10):
    """推荐歌单：[{id,name,cover,count,play}]（缓存 15 分钟，首屏直接读）"""
    import time as _t

    if not configured():
        return []
    now = _t.time()
    hit = _REC["data"]
    if hit and now - _REC["at"] < 900 and len(hit) >= min(limit, 10):
        return hit[:limit]
    d = _json("/%s/playlist/recommend?limit=%d" % (_SERVER, max(1, min(30, limit))))
    out = []
    for p in d if isinstance(d, list) else []:
        n = _pl(p)
        if n:
            out.append(n)
    if out:
        _REC.update({"at": now, "data": out})
    return out


# ═══════════════════════ 分类歌单 ═══════════════════════
# 分类树固定（主题/语种/风格/场景/情感），缓存一小时；上游挂了就用内置的一份，
# 不然首页会因为一个分类接口整个空掉。
_CATS = {"at": 0, "data": None}
_CATS_TTL = 3600
_FALLBACK_CATS = [
    {"name": "语种", "subs": [{"name": n, "id": n} for n in ("华语", "欧美", "日语", "韩语", "粤语")]},
    {"name": "风格", "subs": [{"name": n, "id": n} for n in
                            ("流行", "摇滚", "民谣", "电子", "说唱", "轻音乐", "爵士", "古风")]},
    {"name": "场景", "subs": [{"name": n, "id": n} for n in
                            ("清晨", "夜晚", "学习", "工作", "驾车", "运动", "旅行", "酒吧")]},
    {"name": "情感", "subs": [{"name": n, "id": n} for n in
                            ("怀旧", "清新", "浪漫", "伤感", "治愈", "放松", "孤独", "安静")]},
]


def categories():
    """分类树：[{name, subs:[{name,id}]}]"""
    import time as _t

    # 没配接口时不给分类树：首页音乐区应当明确显示「未配置」，而不是
    # 摆一堆点了没反应的分类。_FALLBACK_CATS 只在上游临时挂掉时兜底用。
    if not configured():
        return []
    now = _t.time()
    if _CATS["data"] and now - _CATS["at"] < _CATS_TTL:
        return _CATS["data"]
    try:
        d = _json("/getPlaylistCategory")
        groups = []
        for src in (d if isinstance(d, list) else [])[:1]:
            for c in (src.get("category") or []):
                subs = [{"name": s.get("name") or "", "id": s.get("id") or s.get("name") or ""}
                        for s in (c.get("sub") or []) if s.get("name")]
                if subs:
                    groups.append({"name": c.get("name") or "", "subs": subs})
        if groups:
            _CATS.update({"at": now, "data": groups})
            return groups
    except Exception:
        pass
    return _CATS["data"] or _FALLBACK_CATS


_CAT_PL = {}


def category_playlists(cat, limit=24, fresh=False):
    """按分类取歌单。缓存 30 分钟——这类榜单变化很慢，没必要每次点都打上游。"""
    import time as _t

    cat = (cat or "").strip()
    if not cat or not configured():
        return []
    key = (cat, limit)
    hit = _CAT_PL.get(key)
    if hit and not fresh and _t.time() - hit[0] < 1800:
        return hit[1]
    try:
        d = _json("/%s/playlist/category?type=%s&limit=%d"
                  % (_SERVER, urllib.parse.quote(cat), max(1, min(60, limit))))
    except Exception:
        return hit[1] if hit else []
    out = []
    for p in d if isinstance(d, list) else []:
        n = _pl(p)
        if n:
            out.append(n)
    if out:
        if len(_CAT_PL) > 60:
            _CAT_PL.clear()
        _CAT_PL[key] = (_t.time(), out)
    return out


def playlist(pid, limit=None):
    """歌单详情：{name, cover, tags, desc, count, play, tracks:[...]}"""
    if not configured():
        return {"id": str(pid), "name": "", "cover": "", "desc": "", "play": 0,
                "count": 0, "creator": "", "tags": [], "tracks": []}
    d = _json("/meting/?server=%s&type=playlist&id=%s" % (_SERVER, urllib.parse.quote(str(pid))))
    tracks, seen = [], set()
    for t in (d.get("tracks") or [])[:limit or 400]:
        n = _track(t)
        if n and n["title"] and n["id"] not in seen:
            seen.add(n["id"])
            tracks.append(n)
    return {"id": str(pid), "name": (d.get("name") or "").strip(),
            "cover": (d.get("coverImgUrl") or "").strip(),
            "desc": (d.get("description") or d.get("desc") or "").strip(),
            "play": int(d.get("playCount") or 0),
            "count": int(d.get("trackCount") or len(tracks)),
            "creator": (d.get("creator") or d.get("nickname") or ""),
            "tags": d.get("tags") or [], "tracks": tracks}


def search(kw, limit=8):
    """按关键词搜歌（给「酷我曲目反查歌词」用）。"""
    if not configured():
        return []
    d = _json("/%s/search/song/?keywords=%s&pn=1&limit=%d"
              % (_SERVER, urllib.parse.quote(kw or ""), max(1, min(30, limit))))
    out, seen = [], set()
    for t in d if isinstance(d, list) else []:
        n = _track(t)
        if n and n["title"] and n["id"] not in seen:
            seen.add(n["id"])
            out.append(n)
    return out


def stream_target(sid):
    """返回上游播放地址（302 到网易云 CDN），由 api 层带 Referer 转发。"""
    sid = str(sid or "").strip()
    base = api_base()
    if not sid.isdigit() or not base:
        return ""
    return "%s/meting/?server=%s&type=url&id=%s" % (base, _SERVER, sid)


def lrc_url(sid):
    """歌词地址：直接返回 LRC 文本，不是 302。"""
    sid = str(sid or "").strip()
    base = api_base()
    if not sid.isdigit() or not base:
        return ""
    return "%s/meting/?server=%s&type=lrc&id=%s" % (base, _SERVER, sid)


_SIZE_CACHE = {}          # sid -> (时间戳, 字节数)；一首歌是否完整几乎不变，可长缓存
_SIZE_TTL = 6 * 3600


def probe_size(sid, timeout=12):
    """只取 1 个字节，从 Content-Range 里读总长度；顺便判断能否播放。"""
    import time as _t

    sid = str(sid or "").strip()
    if not sid.isdigit():
        return 0
    hit = _SIZE_CACHE.get(sid)
    if hit and _t.time() - hit[0] < _SIZE_TTL:
        return hit[1]

    u = stream_target(sid)
    size = -1
    try:
        # 走带连接池的代理通道：探测几十首时，握手开销比传输本身大得多
        # （新建 TLS 0.4s vs 复用连接 0.1s）
        up = proxy.open_stream(u, "bytes=0-0", timeout=timeout, extra_headers=_hdr())
        try:
            if up.status < 400:
                cr = up.headers.get("Content-Range") or ""
                if "/" in cr:
                    size = int(cr.rsplit("/", 1)[-1])
                else:
                    size = int(up.headers.get("Content-Length") or 0)
            # 把 1 字节读干净：连接池只回收已读到 EOF 的连接，
            # 不读干净的话每次探测都要重新握手，几十首下来就是十几秒。
            for _ in range(4):
                if not up.read(1024):
                    break
        finally:
            up.close()
    except Exception:
        size = -1
    # 失败结果只短缓存：可能只是一次网络抖动，别让一首好歌被记死 6 小时
    if size > 0:
        if len(_SIZE_CACHE) > 4000:
            _SIZE_CACHE.clear()
        _SIZE_CACHE[sid] = (_t.time(), size)
    return size


def is_full(sid):
    """能不能完整播放：试听片段约 480KB，整曲至少 1.2MB。"""
    return probe_size(sid) >= MUSIC_FULL_MIN


def select_full(groups, need, budget_ratio=1.6, workers=16):
    """多个候选列表同时挑，共用一个线程池。

    串行地「一个货架挑完再挑下一个」会把 6 个货架的时间叠起来（实测 17s）；
    这里按货架轮流发车，谁先凑够就先撤，整体压到 2~3 秒。
    """
    from collections import deque
    from concurrent.futures import FIRST_COMPLETED, ThreadPoolExecutor, wait

    states = []
    for g in groups:
        cand = [t for t in g if t.get("provider") == "netease"]
        states.append({"cand": cand[:int(need * budget_ratio) + 4], "good": []})
    if not states:
        return []

    queue = deque()
    for i, s in enumerate(states):
        queue.extend((i, t) for t in s["cand"])

    inflight = {}
    with ThreadPoolExecutor(max_workers=workers) as ex:
        while queue or inflight:
            while queue and len(inflight) < workers:
                i, t = queue.popleft()
                if len(states[i]["good"]) >= need:
                    continue
                inflight[ex.submit(is_full, t["id"])] = (i, t)
            if not inflight:
                break
            done, _ = wait(list(inflight), return_when=FIRST_COMPLETED)
            for f in done:
                i, t = inflight.pop(f)
                try:
                    ok = f.result()
                except Exception:
                    ok = False
                if ok and len(states[i]["good"]) < need:
                    states[i]["good"].append(t)
    return [s["good"] for s in states]
