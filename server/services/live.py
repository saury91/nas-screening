# -*- coding: utf-8 -*-
"""直播源：M3U / TXT 解析、抓取入库、按分组查询。"""
import re
import time

from ..db import dbrun
from ..log import log
from ..net import raw_get

# 运营商的 IPTV 源（ottrrs.hl.chinamobile.com 等）只在专网内可达，
# 普通宽带访问不通，所以这里只支持「自定义导入」，由用户贴能用的源。


# iptv-org 之类的源用英文分组，统一换成中文，页面分组栏才好看
_GROUP_ZH = {
    "general": "综合", "news": "新闻", "movies": "电影", "sports": "体育",
    "kids": "少儿", "music": "音乐", "education": "教育", "documentary": "纪录",
    "lifestyle": "生活", "business": "财经", "religious": "宗教",
    "entertainment": "娱乐", "family": "家庭", "cooking": "美食",
    "travel": "旅游", "weather": "天气", "undefined": "未分组", "update": "未分组",
    "culture": "文化", "science": "科教", "legislative": "政务", "classic": "经典",
    "animation": "动漫", "outdoor": "户外", "shop": "购物", "auto": "汽车",
}
_BAD_HOST = re.compile(r"^https?://(127\.0\.0\.1|localhost|0\.0\.0\.0)([:/]|$)", re.I)


def _fix_group(g):
    g = (g or "").strip()[:24]
    # iptv-org 很多分组是 "Animation;Kids" 这种多值，取第一个再映射
    first = re.split(r"[;,/]", g)[0].strip()
    return _GROUP_ZH.get(first.lower(), first) or "未分组"


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
        if it[0] != "ch":
            continue
        url = it[3]
        # 有些源会在列表里塞一条 "更新时间: xxx -> http://127.0.0.1" 的占位行
        if _BAD_HOST.match(url) or "更新" in it[2] or len(it[2]) > 30:
            continue
        chans.append({"group": _fix_group(it[1]),
                      "name": it[2][:40], "url": url})
    return chans


def refresh_live(src_name=None, url=None):
    """抓取一个直播源并写入库"""
    rows = []
    if url:
        rows = [(src_name or "自定义", url)]
    else:
        rows = dbrun("SELECT name,url FROM sources WHERE kind='live' AND enabled=1",
                     fetch=True) or []
    total = 0
    for nm, u in rows:
        try:
            st, body, _, _ = raw_get(u, timeout=25)
            txt = body.decode("utf-8", "replace")
        except Exception as e:
            # 抓不到也要留痕：设置页里能看到「失败」，而不是一片空白
            dbrun("UPDATE sources SET status=?, checked_at=?, api_count=0 "
                  "WHERE kind='live' AND name=?", ("fail", int(time.time()), nm))
            log("直播源 %s 抓取失败：%s" % (nm, str(e)[:80]))
            continue
        chans = parse_live(txt)
        if not chans:
            dbrun("UPDATE sources SET status=?, checked_at=?, api_count=0 "
                  "WHERE kind='live' AND name=?", ("fail", int(time.time()), nm))
        for c in chans:
            dbrun("""INSERT OR IGNORE INTO live(grp,name,url,src,added_at)
                     VALUES(?,?,?,?,?)""",
                  (c["group"], c["name"], c["url"], nm, int(time.time())))
        total += len(chans)
        dbrun("UPDATE sources SET status=?, checked_at=?, api_count=? "
              "WHERE kind='live' AND name=?",
              ("ok", int(time.time()), len(chans), nm))
        log("直播源 %s：%d 个频道" % (nm, len(chans)))
    return total


def live_groups():
    rows = dbrun("""SELECT grp, COUNT(*) c FROM live GROUP BY grp
                    ORDER BY c DESC LIMIT 40""", fetch=True) or []
    return [{"group": r[0], "count": r[1]} for r in rows]


def live_list(grp=None, kw=None, limit=500):
    sql = "SELECT grp,name,url,alive FROM live WHERE 1=1"
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
    for g, n, u, alive in rows:
        k = (g, n)
        if k not in seen:
            seen[k] = {"group": g, "name": n, "urls": [], "alive": alive}
            order.append(k)
        if len(seen[k]["urls"]) < 6:
            seen[k]["urls"].append(u)
        # 只要有一条线路测出来可用，整条频道就算可用
        if alive == 1:
            seen[k]["alive"] = 1
    return [seen[k] for k in order]


def source_stats():
    """每个直播源有多少频道，设置页展示用。"""
    rows = dbrun("""SELECT src, COUNT(*) FROM live WHERE src IS NOT NULL
                    GROUP BY src""", fetch=True) or []
    return {r[0]: r[1] for r in rows}


def clear_source(name):
    dbrun("DELETE FROM live WHERE src=?", (name,))


# ── 频道探活 ──────────────────────────────────────────────────
# 公开源里大量是运营商专网地址（39.134.x.x/dbiptv...），普通宽带根本连不上。
# 让用户能先测一遍，把点开必黑的频道标出来，比一个个试快得多。
def check_channels(grp="", limit=240, workers=16, timeout=4):
    from concurrent.futures import ThreadPoolExecutor
    import concurrent.futures
    import urllib.request
    from ..config import UA

    sql = "SELECT id,name,url FROM live"
    args = []
    if grp:
        sql += " WHERE grp=?"
        args.append(grp)
    sql += " LIMIT ?"
    args.append(limit)
    rows = dbrun(sql, tuple(args), fetch=True) or []
    if not rows:
        return {"checked": 0, "ok": 0, "dead": 0}

    def one(r):
        _id, _name, url = r
        try:
            req = urllib.request.Request(url, headers={"User-Agent": UA})
            with urllib.request.urlopen(req, timeout=timeout) as resp:
                body = resp.read(256)
            good = b"#EXTM3U" in body or b"#EXTINF" in body or b".ts" in body
            return _id, 1 if good else 0
        except Exception:
            return _id, 0

    with ThreadPoolExecutor(max_workers=workers) as ex:
        res = list(ex.map(one, rows))

    now = int(time.time())
    for _id, ok in res:
        dbrun("UPDATE live SET alive=?, probed_at=? WHERE id=?", (ok, now, _id))
    ok_n = sum(1 for _, o in res if o)
    log("直播探活：%d 条 → 可用 %d" % (len(res), ok_n))
    return {"checked": len(res), "ok": ok_n, "dead": len(res) - ok_n}
