# -*- coding: utf-8 -*-
"""直播源：M3U / TXT 解析、抓取入库、按分组查询。"""
import re
import time

from ..db import (dbrun, pref_json, pref_read, pref_set_json,
                   pref_write)
from ..log import log
from ..net import idna, raw_get

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
            # 台标：不少 IPTV 源自带 tvg-logo，直接拿来用，比空白格子强得多
            lg = re.search(r'tvg-logo="([^"]*)"', s)
            logo = lg.group(1).strip() if lg else ""
            out.append(["meta", g, nm, logo])
            continue
        m = re.match(r"^([^,#]+),\s*(https?://\S+)$", s)
        if m:
            out.append(["ch", grp, m.group(1).strip(), m.group(2).strip()])
            continue
        if s.startswith("http") and out and out[-1][0] == "meta":
            k = out.pop()
            out.append(["ch", k[1], k[2], s, k[3] if len(k) > 3 else ""])
    chans = []
    for it in out:
        if it[0] != "ch":
            continue
        url = it[3]
        # 有些源会在列表里塞一条 "更新时间: xxx -> http://127.0.0.1" 的占位行
        if _BAD_HOST.match(url) or "更新" in it[2] or len(it[2]) > 30:
            continue
        chans.append({"group": _fix_group(it[1]),
                      "name": it[2][:40], "url": url,
                      "logo": (it[4] if len(it) > 4 else "")})
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
            dbrun("""INSERT OR IGNORE INTO live(grp,name,url,src,added_at,logo)
                     VALUES(?,?,?,?,?,?)""",
                  (c["group"], c["name"], c["url"], nm, int(time.time()),
                   c.get("logo") or None))
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
    sql = "SELECT grp,name,url,alive,logo FROM live WHERE 1=1"
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
    for g, n, u, alive, logo in rows:
        k = (g, n)
        if k not in seen:
            seen[k] = {"group": g, "name": n, "urls": [], "alive": alive,
                       "logo": logo or ""}
            order.append(k)
        # 同名频道里只要有一条带台标就用它
        if not seen[k].get("logo") and logo:
            seen[k]["logo"] = logo
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
            req = urllib.request.Request(idna(url), headers={"User-Agent": UA})
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
        # 连续失效才累计：一次网络抖动不该把频道判死
        dbrun("""UPDATE live SET alive=?, probed_at=?,
                 dead_hits=CASE WHEN ?=1 THEN 0 ELSE COALESCE(dead_hits,0)+1 END
                 WHERE id=?""",
              (ok, now, 1 if ok else 0, _id))
    ok_n = sum(1 for _, o in res if o)
    log("直播探活：%d 条 → 可用 %d" % (len(res), ok_n))
    return {"checked": len(res), "ok": ok_n, "dead": len(res) - ok_n}


# ── 每日自动维护 ──────────────────────────────────────────────
# 参照 lubin776/tvbox-api-backup 的做法（GitHub Actions 每天抓源→校验→只留可用），
# 这里把同一套流程做成服务内的定时任务，不需要额外跑 CI。
def prune_dead(min_hits=3):
    """清理连续 N 次都测不通的频道。只删探活过的，不碰新导入还没测的。"""
    rows = dbrun("""SELECT COUNT(*) FROM live
                    WHERE alive=0 AND COALESCE(dead_hits,0)>=?""",
                 (min_hits,), fetch=True) or [[0]]
    n = rows[0][0]
    if n:
        dbrun("""DELETE FROM live WHERE alive=0 AND COALESCE(dead_hits,0)>=?""",
              (min_hits,))
        log("清理失效频道 %d 个（连续 %d 次不可用）" % (n, min_hits))
    return n


def auto_enabled():
    return (pref_read("live.auto.on", "1") or "1") != "0"


def set_auto(on):
    pref_write("live.auto.on", "1" if on else "0")


def auto_hour():
    from ..config import LIVE_AUTO_HOUR
    return LIVE_AUTO_HOUR


def auto_due():
    """按「每天固定钟点」判断，而不是 last+24h，避免时间漂移。"""
    from ..config import LIVE_AUTO_HOUR
    last = int(pref_read("live.auto.last", "0") or 0)
    now = time.time()
    lt = time.localtime(now)
    today = int(time.mktime((lt.tm_year, lt.tm_mon, lt.tm_mday,
                            LIVE_AUTO_HOUR, 0, 0, 0, 0, -1)))
    target = today if now >= today else today - 86400
    return last < target


def auto_status():
    r = pref_json("live.auto.result", {}) or {}
    c = pref_json("live.collect.result", {}) or {}
    return {
        "enabled": auto_enabled(),
        "hour": auto_hour(),
        "last": int(pref_read("live.auto.last", "0") or 0),
        "result": r,
        "due": bool(auto_enabled() and auto_due()),
        "seeds": seeds(),
        "collect": c,
        "running": bool(_RUN["running"]),
        "since": int(_RUN["since"]),
    }


def auto_refresh(force=False, prune_hits=3):
    """每日全流程：刷新自有源 → 汇总候选源并探活 → 清理。"""
    if not force and (not auto_enabled() or not auto_due()):
        return {"skipped": True, "reason": "还没到点或已关闭"}

    t0 = time.time()
    added = 0
    try:
        added = refresh_live()          # 用户自己配的源也同步一次
    except Exception as e:
        log("自动刷新拉取失败：%s" % str(e)[:120])

    # 核心：把候选源清单全部拉一遍、合并去重、逐条探测，只留能播的
    collected = {}
    try:
        collected = auto_collect()
    except Exception as e:
        log("自动汇总失败：%s" % str(e)[:120])

    cleaned = prune_dead(prune_hits)

    # 点播源：每周最多补一次，只增不减，避免动到用户的收藏/历史
    try:
        from . import discover
        discovered = discover.auto_maybe()
    except Exception as e:
        discovered = {}
        log("片源自动发现失败：%s" % str(e)[:120])
    res = {
        "at": int(time.time()),
        "fetched": added,
        "checked": collected.get("probed", 0),
        "ok": collected.get("ok_urls", 0),
        "dead": max(0, collected.get("probed", 0) - collected.get("ok_urls", 0)),
        "channels": collected.get("kept", 0),
        "seeds": collected.get("seeds", 0),
        "pruned": cleaned,
        "discovered": discovered.get("added", 0),
        "seconds": int(time.time() - t0),
    }
    pref_write("live.auto.last", str(int(time.time())))
    pref_set_json("live.auto.result", res)
    log("自动维护完成：抓取 %d，检测 %d，可用 %d，清理 %d，用时 %ds" % (
        added, res["checked"], res["ok"], cleaned, res["seconds"]))
    return res


# ── 候选源自动汇总 ────────────────────────────────────────────
# 这才像 Guovin/iptv-api 的做法：不是只刷新你填的那个地址，
# 而是每天把「候选源清单」全部拉一遍，合并、去重、逐条探活，只留下能播的。
AUTO_SRC = "自动汇总"
_RUN = {"running": False, "since": 0}


def seeds():
    """候选源 = 设置里的清单（没有就用内置默认值）+ 用户手动加的直播源。

    把这些 URL 当作同一个池子：每天全部拉一遍，谁有效留谁。
    """
    v = pref_json("live.seeds")
    if isinstance(v, list) and v:
        base = [str(x).strip() for x in v if str(x).strip().startswith("http")]
    else:
        from ..config import LIVE_SEEDS
        base = list(LIVE_SEEDS)
    # 手动添加的直播源也参与汇总，避免「重建后它们消失」
    from ..db import sources_list
    for s in sources_list("live", enabled_only=True):
        u = (s.get("url") or "").strip()
        if u.startswith("http") and u not in base:
            base.append(u)
    return base


def set_seeds(urls):
    clean = []
    for u in urls or []:
        u = str(u).strip()
        if u.startswith("http") and u not in clean:
            clean.append(u)
    pref_set_json("live.seeds", clean)
    return clean


def _probe_url(url, timeout=4):
    import urllib.request
    from ..config import UA
    try:
        req = urllib.request.Request(url, headers={"User-Agent": UA})
        with urllib.request.urlopen(req, timeout=timeout) as r:
            b = r.read(200)
            return b"#EXTM3U" in b or (len(b) > 1 and b[0] == 0x47)
    except Exception:
        return False


def auto_collect(max_probe=None, workers=32, timeout=4):
    """拉全部候选源 → 合并（同名频道保留多线路）→ 逐条探活 → 只写可播的。"""
    from ..config import LIVE_PROBE_LIMIT
    from ..net import raw_get
    from concurrent.futures import ThreadPoolExecutor

    limit = max_probe or LIVE_PROBE_LIMIT
    t0 = time.time()
    pool, used = {}, []
    fetched = 0

    for u in seeds():
        try:
            st, body, _, _ = raw_get(u, timeout=25)
            chans = parse_live(body.decode("utf-8", "replace"))
            fetched += len(chans)
            used.append((u, len(chans)))
            for c in chans:
                k = (c["group"], c["name"])
                arr = pool.setdefault(k, [])
                if c["url"] not in arr and len(arr) < 4:
                    arr.append(c["url"])
        except Exception as e:
            log("汇总拉取失败 %s：%s" % (u[:48], str(e)[:60]))

    jobs = []
    for k, urls in pool.items():
        for u in urls:
            if len(jobs) >= limit:
                break
            jobs.append((k, u))
        if len(jobs) >= limit:
            break

    with ThreadPoolExecutor(max_workers=workers) as ex:
        results = list(ex.map(lambda j: _probe_url(j[1], timeout), jobs))

    keep = {}
    for (k, u), ok in zip(jobs, results):
        if ok:
            keep.setdefault(k, []).append(u)

    now = int(time.time())
    # 全量重建：这张表就是「自动汇总」的产物。
    # 只有真的测出足够多的可用频道才敢清空，避免一次网络故障把列表清光。
    if len(keep) >= 20:
        dbrun("DELETE FROM live")
    else:
        log("自动汇总可用频道过少（%d），保留原有数据" % len(keep))
        return {"at": now, "seeds": len(used), "fetched": fetched,
                "channels": len(pool), "probed": len(jobs),
                "ok_urls": sum(len(v) for v in keep.values()),
                "kept": len(keep), "rows": 0, "seconds": int(time.time() - t0),
                "skipped": True, "detail": used[:8]}
    rows = 0
    for (g, name), urls in keep.items():
        for u in urls:
            dbrun("""INSERT OR IGNORE INTO live
                     (grp,name,url,src,added_at,alive,probed_at,dead_hits)
                     VALUES(?,?,?,?,?,?,?,0)""",
                  (g, name, u, AUTO_SRC, now, 1, now))
            rows += 1

    res = {
        "at": now, "seeds": len(used), "fetched": fetched, "channels": len(pool),
        "probed": len(jobs), "ok_urls": sum(len(v) for v in keep.values()),
        "kept": len(keep), "rows": rows, "seconds": int(time.time() - t0),
        "detail": used[:8],
    }
    pref_write("live.collect.last", str(now))
    pref_set_json("live.collect.result", res)
    log("自动汇总：%d 源 / %d 条 → 探测 %d → 可用频道 %d（%ds）" % (
        len(used), fetched, len(jobs), len(keep), res["seconds"]))
    return res


def collect_running():
    return dict(_RUN)


def collect_async():
    """手动触发放在后台跑，接口立刻返回，前端轮询状态。"""
    if _RUN["running"]:
        return False
    _RUN["running"] = True
    _RUN["since"] = int(time.time())

    def run():
        try:
            auto_collect()
        except Exception as e:
            log("自动汇总失败：%s" % str(e)[:160])
        finally:
            _RUN["running"] = False

    import threading
    threading.Thread(target=run, daemon=True).start()
    return True
