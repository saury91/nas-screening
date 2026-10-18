# -*- coding: utf-8 -*-
"""点播源自动发现。

思路来自 awesome-zhuiju-free（人工精选 + 每日检测）：
它提供一份资源目录，里面有一批「影视仓配置地址」。这些配置本质上就是
TVBox 的 JSON，里面 `sites` 就是一家家 CMS 采集接口。

我们做的事：
  目录 → 配置 → 抽出直连 CMS 接口 → 逐个发一次真实搜索验证 → 只把能用的入库。

和直播自动汇总的区别：直播是「每天重建」，点播源是「只增不减」——
因为删源会影响用户的收藏和观看记录，宁可留着让用户手动停用。
"""
import json
import threading
import time
import urllib.parse

from ..config import CMS_SEEDS
from ..db import pref_json, pref_set_json, pref_write
from ..log import log
from ..net import http_get
from ..providers import add_source, all_sites
from ..providers import tvbox
from ..providers.cms import fetch_vod

_RUN = {"running": False, "since": 0, "result": {}}


def catalog_configs():
    """从资源目录里取出「影视仓配置」类地址。"""
    out, seen = [], set()
    for u in CMS_SEEDS:
        try:
            d = json.loads(http_get(u, timeout=20))
            for r in d.get("resources") or []:
                url = str(r.get("url") or "").strip()
                if r.get("category") != "tvbox_config" or not url.startswith("http"):
                    continue
                if url in seen:
                    continue
                seen.add(url)
                out.append((str(r.get("name") or "未命名").strip(), url))
        except Exception as e:
            log("资源目录读取失败 %s：%s" % (u[:48], str(e)[:60]))
    return out


def _sites_from(url, depth=0):
    """取一个配置里的直连站点；多仓递归展开一层。"""
    txt = http_get(url, timeout=15)
    payload = tvbox.clean_json(txt)
    subs = tvbox.multi_urls(payload) if depth < 2 else []
    if subs:
        out = []
        for _nm, su in subs[:12]:
            try:
                out.extend(_sites_from(su, depth + 1))
            except Exception:
                pass
        return out
    if payload.startswith("{") or payload.startswith("["):
        sites, _lives, _skipped, _meta = tvbox.parse_config(payload)
    else:
        sites, _lives, _skipped, _meta = tvbox.parse_single(url)
    return sites


def _norm_api(u):
    """去掉 TVBox 配置里常见的 `?ac=list` 之类，只保留真正的接口地址。"""
    p = urllib.parse.urlsplit(u.strip())
    q = [(k, v) for k, v in urllib.parse.parse_qsl(p.query, keep_blank_values=True)
         if k.lower() != "ac"]
    return urllib.parse.urlunsplit((p.scheme, p.netloc, p.path.rstrip("/"),
                                    urllib.parse.urlencode(q), ""))


def _verify(api, timeout=8):
    """发一次真实搜索：能返回条目才算活着。"""
    try:
        # 关键词必须编码：中文直接拼进 URL 会让 urllib 抛 UnicodeEncodeError
        kw = urllib.parse.quote("沙丘")
        rows = fetch_vod({"api": api}, "ac=detail&wd=" + kw, timeout=timeout)
        return len(rows)
    except Exception:
        return 0


def discover(max_configs=10, max_new=8, workers=8, timeout=8):
    """目录 → 配置 → 候选接口 → 验证 → 入库（只增不减）。"""
    from concurrent.futures import ThreadPoolExecutor

    t0 = time.time()
    cfgs = catalog_configs()[:max_configs]
    exist_sites = all_sites()
    existing = {_norm_api(s.get("url") or "") for s in exist_sites}
    existing_names = {s.get("name") or "" for s in exist_sites}
    cand = {}
    for nm, u in cfgs:
        try:
            for s in _sites_from(u):
                api = (s.get("api") or "").strip()
                if not api.startswith("http"):
                    continue
                api = _norm_api(api)
                if not api or api in existing or api in cand:
                    continue
                s["_from"] = nm
                cand[api] = s
        except Exception as e:
            log("配置解析失败 %s：%s" % (u[:48], str(e)[:60]))

    if not cand:
        return {"configs": len(cfgs), "candidates": 0, "alive": 0, "added": 0,
                "seconds": 0}

    with ThreadPoolExecutor(max_workers=workers) as ex:
        hits = list(ex.map(lambda kv: (kv[0], kv[1], _verify(kv[0], timeout)),
                           cand.items()))

    alive = [(api, s, n) for api, s, n in hits if n > 0]
    alive.sort(key=lambda x: -x[2])

    added = []
    for api, s, n in alive[:max_new]:
        name = (s.get("name") or api)[:30]
        # 重名会覆盖已有片源（包括内置源），加来源后缀区分
        if name in existing_names:
            name = ("%s · %s" % (name, s.get("_from") or "发现"))[:36]
        existing_names.add(name)
        add_source("cms", name, api, type_=s.get("type", 1), pri=20,
                   cat=s.get("cat") or "综合")
        added.append({"name": name, "api": api, "hits": n, "from": s.get("_from", "")})

    res = {
        "at": int(time.time()),
        "configs": len(cfgs),
        "candidates": len(cand),
        "alive": len(alive),
        "added": len(added),
        "items": added,
        "seconds": int(time.time() - t0),
    }
    pref_write("cms.discover.last", str(int(time.time())))
    pref_set_json("cms.discover.result", res)
    log("片源发现：%d 个配置 / %d 个候选 → 可用 %d → 新增 %d（%ds）" % (
        len(cfgs), len(cand), len(alive), len(added), res["seconds"]))
    return res


def status():
    return {"running": bool(_RUN["running"]), "since": int(_RUN["since"]),
            "last": int(pref_json("cms.discover.last", 0) or 0),
            "result": pref_json("cms.discover.result", {}) or {},
            "catalogs": list(CMS_SEEDS)}


def run_async():
    """后台跑，接口立刻返回。"""
    if _RUN["running"]:
        return False
    _RUN["running"] = True
    _RUN["since"] = int(time.time())

    def work():
        try:
            discover()
        except Exception as e:
            log("片源发现失败：%s" % str(e)[:160])
        finally:
            _RUN["running"] = False

    threading.Thread(target=work, daemon=True).start()
    return True


def auto_maybe(days=7):
    """每周最多跑一次，避免每天往源列表里塞东西。"""
    last = int(pref_json("cms.discover.last", 0) or 0)
    if last and time.time() - last < days * 86400:
        return {"skipped": True}
    return discover()
