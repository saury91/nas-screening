# -*- coding: utf-8 -*-
"""配置导出 / 导入（跨版本兼容）。

目标有三个：
  1. 开源仓库里只有代码，配置留在一份 JSON 里，谁拿到都能还原；
  2. 这份 JSON 可以丢给别人，导入即用；
  3. **改版本不能让它失效** —— 所以带 schema 版本号 + 迁移表 + 宽容解析。

兼容策略（顺序很重要）：
  * 先看 `schema`（也接受老写法 `version`），比当前版本新 → 直接拒绝，并说明原因；
  * 比当前版本旧 → 依次套用 MIGRATIONS 升到当前版本；
  * 缺字段 → 用默认值补；多出来的字段 → 忽略（前向兼容：老程序读新文件不炸）；
  * 单条数据坏掉 → 丢掉这一条并记 warning，不整体失败。

安全：
  * 只导出白名单里的偏好项，凭据类（password / token / secret）一律不出去；
  * 导入前先把当前配置导出到 data/backups/，导坏了还能导回来；
  * 写库在单个事务里，中途失败整体回滚。
"""
import json
import os
import time

from ..config import DB_PATH
from ..db import db, dbrun

KIND = "projection-room.config"
SCHEMA = 1
APP = "3.0"

# key 必须写清楚：以后加版本时，只需要往这里加一个 from->to 的函数
# 例：MIGRATIONS[1] = _v1_to_v2
MIGRATIONS = {}

MAX_ITEM = 20000          # 单个资源字符串长度上限，防止塞爆
MAX_PREFS_BYTES = 262144


class ConfigError(Exception):
    def __init__(self, msg, code=400):
        super().__init__(msg)
        self.msg = msg
        self.code = code


# ═══════════════════════ 导出 ═══════════════════════
def _rows(sql, args=()):
    return dbrun(sql, args, fetch=True) or []


def export_bundle(include_channels=True):
    """把当前配置导出成可分享的 dict。"""
    sources = []
    for r in _rows("""SELECT kind,name,url,enabled,pri,meta,type,cat
                      FROM sources ORDER BY kind, pri, name"""):
        kind, name, url, enabled, pri, meta, typ, cat = r
        sources.append({
            "kind": kind or "cms",
            "name": name or "",
            "url": url or "",
            "enabled": 1 if enabled else 0,
            "pri": int(pri or 5),
            "type": int(typ or 1),
            "cat": cat,
            "meta": _loads(meta),
        })

    channels = []
    if include_channels:
        for r in _rows("""SELECT grp,name,url,src FROM live
                          ORDER BY grp, name LIMIT 20000"""):
            channels.append({"grp": r[0] or "", "name": r[1] or "",
                             "url": r[2] or "", "src": r[3] or ""})

    return {
        "kind": KIND,
        "schema": SCHEMA,
        "app": APP,
        "exportedAt": int(time.time()),
        "sections": {
            "sources": sources,
            "liveChannels": channels,
            "prefs": _export_prefs(),
        },
        "counts": {"sources": len(sources), "liveChannels": len(channels)},
    }


def _loads(s):
    if not s:
        return {}
    if isinstance(s, dict):
        return s
    try:
        v = json.loads(s)
        return v if isinstance(v, dict) else {}
    except Exception:
        return {}


# 只导这些：来源种子、音乐/界面偏好。
# 反过来列黑名单很危险——以后新增一个带密码的 key 就会被顺手导出。
_PREF_ALLOW = ("live.seeds", "music.", "ui.", "play.", "subtitle.")
_PREF_DENY_HINT = ("pass", "secret", "token", "cookie", "key", "auth")


def _export_prefs():
    out = {}
    for k, v in _rows("SELECT k,v FROM prefs"):
        if not k:
            continue
        if not any(k == a or k.startswith(a) for a in _PREF_ALLOW):
            continue
        if k.endswith(".result") or k.endswith(".last"):
            continue                      # 运行结果，不是配置
        if any(h in k.lower() for h in _PREF_DENY_HINT):
            continue                      # 宁可不导，也不外泄
        try:
            val = json.loads(v)
        except Exception:
            val = v
        out[k] = val
    return out


# ═══════════════════════ 解析 / 迁移 ═══════════════════════
def _as_doc(doc):
    if isinstance(doc, (dict, list)):
        return doc
    if isinstance(doc, (bytes, bytearray)):
        doc = doc.decode("utf-8", "replace")
    if isinstance(doc, str):
        doc = doc.strip()
        if not doc:
            raise ConfigError("文件是空的")
        try:
            return json.loads(doc)
        except Exception as e:
            raise ConfigError("不是合法的 JSON：%s" % str(e)[:80])
    raise ConfigError("配置格式不对")


def normalize(doc):
    """校验 + 迁移 + 清洗。返回 (data, meta, warnings)。"""
    d = _as_doc(doc)
    if isinstance(d, list):
        # 早期可能有人只存了一个源数组，兜一下
        d = {"sections": {"sources": d}}
    if not isinstance(d, dict):
        raise ConfigError("配置顶层应该是一个对象")

    warnings = []
    kind = d.get("kind")
    if kind and kind != KIND:
        raise ConfigError("这不是本应用的配置文件（kind=%s）" % str(kind)[:40], 400)
    if not kind:
        warnings.append("文件缺少 kind 标记，按本应用配置尝试解析")

    ver = d.get("schema", d.get("version", 1))
    try:
        ver = int(ver)
    except Exception:
        ver = 1
    if ver < 1:
        ver = 1
    if ver > SCHEMA:
        raise ConfigError(
            "这个配置来自更新的版本（v%d，当前支持到 v%d）。请先升级应用再导入。"
            % (ver, SCHEMA), 409)

    cur = dict(d)
    applied = []
    while ver < SCHEMA:
        fn = MIGRATIONS.get(ver)
        if not fn:
            warnings.append("缺少 v%d→v%d 的迁移规则，按当前结构尽力解析" % (ver, ver + 1))
            break
        cur = fn(cur) or cur
        applied.append("v%d→v%d" % (ver, ver + 1))
        ver += 1

    sec = cur.get("sections")
    if not isinstance(sec, dict):
        # 也接受扁平写法：{sources: [...], prefs: {...}}
        sec = {k: cur.get(k) for k in ("sources", "liveChannels", "prefs")
               if k in cur}
        if not sec:
            raise ConfigError("文件里没有可导入的配置段")

    data = {}
    if "sources" in sec:
        data["sources"], w = _clean_sources(sec.get("sources"))
        warnings += w
    if "liveChannels" in sec:
        data["liveChannels"], w = _clean_live(sec.get("liveChannels"))
        warnings += w
    if "prefs" in sec:
        data["prefs"], w = _clean_prefs(sec.get("prefs"))
        warnings += w

    meta = {
        "schema": SCHEMA,
        "fromSchema": int(d.get("schema", d.get("version", 1)) or 1),
        "app": str(d.get("app") or ""),
        "exportedAt": d.get("exportedAt") or 0,
        "migrated": applied,
    }
    return data, meta, warnings


def _clean_sources(arr):
    out, warns = [], []
    if arr is None:
        return out, warns
    if not isinstance(arr, list):
        return out, ["sources 应该是数组，已忽略"]
    for i, o in enumerate(arr):
        if not isinstance(o, dict):
            warns.append("第 %d 条片源不是对象，跳过" % (i + 1))
            continue
        name = str(o.get("name") or "").strip()[:120]
        url = str(o.get("url") or "").strip()
        if not name:
            warns.append("第 %d 条片源没有名字，跳过" % (i + 1))
            continue
        if not (url.startswith("http://") or url.startswith("https://")):
            warns.append("「%s」的地址不是 http(s)，跳过" % name[:24])
            continue
        if len(url) > MAX_ITEM:
            warns.append("「%s」的地址过长，跳过" % name[:24])
            continue
        try:
            pri = max(1, min(99, int(o.get("pri") or 5)))
        except Exception:
            pri = 5
        try:
            typ = int(o.get("type") or 1)
        except Exception:
            typ = 1
        out.append({
            "kind": str(o.get("kind") or "cms")[:16],
            "name": name,
            "url": url,
            "enabled": 1 if o.get("enabled", 1) else 0,
            "pri": pri,
            "type": typ,
            "cat": (str(o.get("cat"))[:32] if o.get("cat") else None),
            "meta": _loads(o.get("meta")) if o.get("meta") is not None else {},
        })
    return out, warns


def _clean_live(arr):
    out, warns = [], []
    if arr is None:
        return out, warns
    if not isinstance(arr, list):
        return out, ["liveChannels 应该是数组，已忽略"]
    seen = set()
    for i, o in enumerate(arr):
        if not isinstance(o, dict):
            continue
        name = str(o.get("name") or "").strip()[:120]
        url = str(o.get("url") or "").strip()
        if not name or not (url.startswith("http://") or url.startswith("https://")):
            warns.append("第 %d 个频道无效，跳过" % (i + 1))
            continue
        key = (name, url)
        if key in seen:
            continue
        seen.add(key)
        out.append({"grp": str(o.get("grp") or "未分组")[:60], "name": name,
                    "url": url, "src": str(o.get("src") or "导入")[:32]})
    return out, warns


def _clean_prefs(d):
    if not isinstance(d, dict):
        return {}, ([] if d is None else ["prefs 应该是对象，已忽略"])
    out = {}
    total = 0
    for k, v in d.items():
        k = str(k)
        if not any(k == a or k.startswith(a) for a in _PREF_ALLOW):
            continue
        if any(h in k.lower() for h in _PREF_DENY_HINT):
            continue
        try:
            s = json.dumps(v, ensure_ascii=False)
        except Exception:
            continue
        total += len(s)
        if total > MAX_PREFS_BYTES:
            break
        out[k] = v
    return out, []


# ═══════════════════════ 预览 / 应用 ═══════════════════════
def _existing_source_keys(con):
    return {(r[0], r[1]): r[2] for r in
            con.execute("SELECT kind,name,url FROM sources").fetchall()}


def _existing_live_keys(con):
    return {(r[0], r[1]) for r in
            con.execute("SELECT name,url FROM live").fetchall()}


def plan_import(doc, mode="merge", sections=None):
    """只算不写：告诉用户会新增多少、更新多少。"""
    data, meta, warnings = normalize(doc)
    if sections:
        keep = set(sections)
        data = {k: v for k, v in data.items() if k in keep}

    con = db()
    stats = {}
    if "sources" in data:
        ex = _existing_source_keys(con)
        add = upd = same = 0
        add_names, upd_names = [], []
        for s in data["sources"]:
            key = (s["kind"], s["name"])
            if key not in ex:
                add += 1
                add_names.append(s["name"])
            elif (ex[key] or "") != s["url"]:
                upd += 1
                upd_names.append(s["name"])
            else:
                # 地址一样，再看其它字段是否要改
                row = con.execute("""SELECT enabled,pri,type,cat FROM sources
                                     WHERE kind=? AND name=?""", key).fetchone()
                if row and (int(row[0] or 0) != s["enabled"] or int(row[1] or 5) != s["pri"]
                            or int(row[2] or 1) != s["type"] or (row[3] or None) != s["cat"]):
                    upd += 1
                    upd_names.append(s["name"])
                else:
                    same += 1
        stats["sources"] = {"total": len(data["sources"]), "add": add,
                            "update": upd, "same": same,
                            "sampleAdd": add_names[:6], "sampleUpdate": upd_names[:6]}

    if "liveChannels" in data:
        ex = _existing_live_keys(con)
        add = sum(1 for c in data["liveChannels"] if (c["name"], c["url"]) not in ex)
        stats["liveChannels"] = {"total": len(data["liveChannels"]), "add": add,
                                 "same": len(data["liveChannels"]) - add}

    if "prefs" in data:
        stats["prefs"] = {"total": len(data["prefs"]), "keys": sorted(data["prefs"])}

    return {"ok": True, "dry": True, "mode": mode, "meta": meta,
            "warnings": warnings, "plan": stats}


def apply_import(doc, mode="merge", sections=None):
    """真正写库：先备份当前配置，再在单事务里写入。"""
    if mode not in ("merge", "replace"):
        raise ConfigError("mode 只能是 merge 或 replace")
    data, meta, warnings = normalize(doc)
    if sections:
        keep = set(sections)
        data = {k: v for k, v in data.items() if k in keep}
    if not data:
        raise ConfigError("没有要导入的内容")

    backup_path = _backup_current()

    con = db()
    stats = {}
    try:
        with con:                          # 事务：失败整体回滚
            if "sources" in data:
                stats["sources"] = _apply_sources(con, data["sources"], mode)
            if "liveChannels" in data:
                stats["liveChannels"] = _apply_live(con, data["liveChannels"], mode)
            if "prefs" in data and data["prefs"]:
                stats["prefs"] = _apply_prefs(con, data["prefs"])
    except Exception as e:
        raise ConfigError("导入失败，已回滚：%s" % str(e)[:140], 500)

    return {"ok": True, "dry": False, "mode": mode, "meta": meta,
            "warnings": warnings, "stats": stats, "backup": backup_path}


def _apply_sources(con, items, mode):
    add = upd = same = 0
    kinds = sorted({i["kind"] for i in items})
    if mode == "replace" and kinds:
        con.execute("DELETE FROM sources WHERE kind IN (%s)"
                    % ",".join("?" * len(kinds)), kinds)
    for s in items:
        row = con.execute("""SELECT id,url,enabled,pri,type,cat,meta FROM sources
                             WHERE kind=? AND name=?""",
                          (s["kind"], s["name"])).fetchone()
        meta = json.dumps(s["meta"] or {}, ensure_ascii=False)
        if row:
            # 只统计真的变了的，避免「24 条全部更新」这种吓人的假数字
            if (row[1] == s["url"] and int(row[2] or 0) == s["enabled"]
                    and int(row[3] or 5) == s["pri"] and int(row[4] or 1) == s["type"]
                    and (row[5] or None) == s["cat"] and (row[6] or "{}") == meta):
                same += 1
                continue
            con.execute("""UPDATE sources SET url=?,enabled=?,pri=?,type=?,cat=?,meta=?
                           WHERE id=?""",
                        (s["url"], s["enabled"], s["pri"], s["type"], s["cat"],
                         meta, row[0]))
            upd += 1
        else:
            con.execute("""INSERT INTO sources(kind,name,url,enabled,pri,meta,type,cat,added_at)
                           VALUES(?,?,?,?,?,?,?,?,?)""",
                        (s["kind"], s["name"], s["url"], s["enabled"], s["pri"],
                         meta, s["type"], s["cat"], int(time.time())))
            add += 1
    return {"add": add, "update": upd, "same": same}


def _apply_live(con, items, mode):
    if mode == "replace":
        con.execute("DELETE FROM live")
    add = skip = 0
    for c in items:
        hit = con.execute("SELECT id FROM live WHERE name=? AND url=?",
                          (c["name"], c["url"])).fetchone()
        if hit:
            skip += 1
            continue
        con.execute("""INSERT INTO live(grp,name,url,src,added_at)
                       VALUES(?,?,?,?,?)""",
                    (c["grp"], c["name"], c["url"], c["src"], int(time.time())))
        add += 1
    return {"add": add, "same": skip}


def _apply_prefs(con, prefs):
    for k, v in prefs.items():
        con.execute("INSERT OR REPLACE INTO prefs(k,v) VALUES(?,?)",
                    (k, json.dumps(v, ensure_ascii=False)))
    return {"set": len(prefs), "keys": sorted(prefs)}


def _backup_current():
    """导入前把当前配置存一份，导坏了能原样导回来。"""
    try:
        d = os.path.join(os.path.dirname(DB_PATH), "backups")
        os.makedirs(d, exist_ok=True)
        path = os.path.join(d, "config-%s.json" % time.strftime("%Y%m%d-%H%M%S"))
        with open(path, "w", encoding="utf-8") as f:
            json.dump(export_bundle(), f, ensure_ascii=False, indent=2)
        return path
    except Exception:
        return ""
