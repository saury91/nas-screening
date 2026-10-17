# -*- coding: utf-8 -*-
"""片源注册表。

所有片源都存在 SQLite 的 sources 表里，由用户在设置页管理：
  cms   —— 苹果 CMS 采集接口（type 0 XML / type 1 JSON）
  live  —— M3U / TXT 直播源
  magnet—— 磁力站（目前内置 66ss）

新增一种协议只要在 cms.fetch_vod 里加一个分支，上层聚合逻辑不用动。
"""
import threading
import time

from ..db import (dbrun, source_delete, source_get, source_upsert,
                  sources_list)
from ..log import log
from . import cms, tvbox


# ── 查询 ──────────────────────────────────────────────────────
def active_sites(limit=None):
    """启用中的片源，按优先级排序，转成聚合层使用的结构。"""
    out = []
    for s in sources_list("cms", enabled_only=True):
        out.append({"id": s["id"], "name": s["name"], "api": s["url"],
                    "type": s["type"], "pri": s["pri"], "cat": s["cat"]})
    return out[:limit] if limit else out


def all_sites():
    return sources_list("cms")


# ── 管理 ──────────────────────────────────────────────────────
def add_source(kind, name, url, type_=1, pri=5, cat="综合", meta=None):
    return source_upsert(kind, name, url, type_=type_, pri=pri, cat=cat, meta=meta)


def remove_source(sid):
    s = source_get(sid)
    source_delete(sid)
    return s


def set_enabled(sid, on):
    dbrun("UPDATE sources SET enabled=? WHERE id=?", (1 if on else 0, sid))


def set_pri(sid, pri):
    dbrun("UPDATE sources SET pri=? WHERE id=?", (int(pri), sid))


def set_cat(sid, cat):
    dbrun("UPDATE sources SET cat=? WHERE id=?", (cat, sid))


def test_source(site, kw="沙丘"):
    """探活：真的发一次搜索请求，返回耗时与命中条数。"""
    t0 = time.time()
    rows = cms.fetch_vod(site, "ac=detail&wd=" + kw, timeout=10)
    ms = int((time.time() - t0) * 1000)
    ok = bool(rows)
    dbrun("""UPDATE sources SET status=?, checked_at=?, api_count=? WHERE id=?""",
          ("ok" if ok else "fail", int(time.time()), len(rows), site.get("id") or 0))
    return {"ok": ok, "ms": ms, "count": len(rows),
            "msg": ("连通，返回 %d 条" % len(rows)) if ok else "接口无有效返回"}


# ── 导入 ──────────────────────────────────────────────────────
def import_config(url=None, text=None, name_prefix="", _depth=0):
    """导入 TVBox 配置。

    url 可以是一个 TVBox 配置地址（含多仓 /dc、伪装后缀 .webp、中文域名），
    也可以直接是一个采集接口。返回导入报告，前端原样展示（包括被跳过的原因）。
    """
    if text:
        payload = text.strip()
    else:
        from ..net import http_get
        payload = http_get(url, timeout=20).strip()

    # BOM / 注释 / 非法控制字符：TVBox 客户端能容忍，json 不能
    payload = tvbox.clean_json(payload)
    if not payload:
        raise ValueError("配置内容为空")

    # 多仓：一层套一层的子配置，递归展开（最多两层，避免配置互相引用绕死）
    subs = tvbox.multi_urls(payload) if _depth < 2 else []
    if subs:
        return _import_multi(subs, name_prefix, _depth)

    if payload.startswith("{") or payload.startswith("["):
        sites, lives, skipped, meta = tvbox.parse_config(payload)
    elif url:
        sites, lives, skipped, meta = tvbox.parse_single(url, name_prefix)
    else:
        raise ValueError("这段内容既不是 TVBox 配置，也不是 http 接口地址")

    if not sites and not lives:
        raise ValueError("配置里没有可用的直连片源（可能全部依赖 jar/spider）")

    added = updated = 0
    for s in sites:
        existed = dbrun("SELECT id FROM sources WHERE kind='cms' AND name=?",
                        (name_prefix + s["name"],), fetch=True)
        source_upsert("cms", name_prefix + s["name"], s["api"], type_=s["type"],
                      pri=5, cat=s["cat"],
                      meta={"type": s["type"], "key": s["key"]})
        added += 0 if existed else 1
        updated += 1 if existed else 0

    live_names = []
    for lv in lives:
        source_upsert("live", lv["name"], lv["url"], type_=lv["type"], pri=6)
        live_names.append(lv["name"])

    log("导入配置：%d 新增 / %d 更新 / %d 跳过 / %d 直播源" % (
        added, updated, len(skipped), len(lives)))

    # 直播源抓取比较慢，放后台，不卡导入响应
    if live_names:
        from ..services.live import refresh_live
        threading.Thread(target=refresh_live, daemon=True).start()

    return {"ok": True, "added": added, "updated": updated,
            "lives": len(lives), "skipped": skipped[:40],
            "total_sites": len(sites), "parses": meta.get("parses", 0),
            "msg": "导入完成：新增 %d 个片源、%d 个直播源，跳过 %d 项" % (
                added, len(lives), len(skipped))}


def _import_multi(subs, name_prefix, depth):
    """多仓：并发拉所有子配置，再串行写库（SQLite 单写，网络才是瓶颈）。

    名字加「仓名 · 」前缀避免不同仓里同名片源互相覆盖。
    """
    from concurrent.futures import ThreadPoolExecutor
    from ..net import http_get

    items = subs[:20]

    def pull(it):
        nm, su = it
        try:
            return nm, su, http_get(su, timeout=8).strip(), None
        except Exception as e:
            return nm, su, None, str(e)[:90]

    with ThreadPoolExecutor(max_workers=6) as ex:
        fetched = list(ex.map(pull, items))

    added = updated = live_total = total_sites = 0
    skipped, detail = [], []
    for nm, su, child, err in fetched:
        prefix = ("%s%s · " % (name_prefix, nm)) if nm else name_prefix
        if child is None:
            skipped.append({"name": nm or su, "reason": err or "拉取失败"})
            detail.append({"name": nm or su, "added": 0, "skipped": 1})
            continue
        try:
            r = import_config(url=su, text=child, name_prefix=prefix, _depth=depth + 1)
        except Exception as e:
            skipped.append({"name": nm or su, "reason": str(e)[:90]})
            detail.append({"name": nm or su, "added": 0, "skipped": 1})
            continue
        added += r["added"]
        updated += r["updated"]
        live_total += r["lives"]
        total_sites += r["total_sites"]
        skipped.extend(r.get("skipped") or [])
        detail.append({"name": nm or su, "added": r["added"],
                       "skipped": len(r.get("skipped") or [])})

    if not added and not updated and not live_total:
        raise ValueError("多仓里的子配置都没有可用的直连片源（多半依赖 jar/spider，或地址已失效）")

    log("导入多仓：%d 个子配置 → 新增 %d / 更新 %d / 直播 %d / 跳过 %d" % (
        len(subs), added, updated, live_total, len(skipped)))
    return {"ok": True, "added": added, "updated": updated,
            "lives": live_total, "skipped": skipped[:40],
            "total_sites": total_sites, "parses": 0, "multi": detail[:20],
            "msg": "多仓导入完成：%d 个子配置，新增 %d 个片源、%d 个直播源，跳过 %d 项" % (
                len(subs), added, live_total, len(skipped))}
