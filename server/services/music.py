# -*- coding: utf-8 -*-
"""音乐库。

两条路子，设置页里二选一：
  * local     —— 扫 NAS 上挂载的音乐目录（无外部依赖，元数据取自 ID3/FLAC 或文件名）
  * subsonic  —— 接 Navidrome / Airsonic / Gonic 等 Subsonic 兼容服务

对外统一成 album / track 两种结构，前端不用区分后端是谁。
"""
import base64
import hashlib
import json
import os
import re
import threading
import time
from urllib.parse import quote, urlencode

from ..db import pref_json, pref_set_json
from ..log import log
from ..net import http_get, raw_get

AUDIO_EXT = (".mp3", ".flac", ".m4a", ".aac", ".ogg", ".opus", ".wav", ".wma")
_INDEX_LOCK = threading.Lock()
_INDEX = {"root": "", "at": 0, "albums": []}


# ═══════════════════════ 配置 ═══════════════════════
def get_config(mask=True):
    cfg = pref_json("music.config", {}) or {}
    out = {"provider": cfg.get("provider") or "",
           "subsonic": dict(cfg.get("subsonic") or {}),
           "local": dict(cfg.get("local") or {})}
    if mask and out["subsonic"].get("pass"):
        out["subsonic"]["pass"] = "******"
        out["subsonic"]["has_pass"] = True
    return out


def save_config(body):
    cfg = pref_json("music.config", {}) or {}
    provider = (body.get("provider") or "").strip()
    if provider not in ("", "local", "subsonic"):
        raise ValueError("provider 只能是 local / subsonic")

    if "subsonic" in body:
        sub = dict(cfg.get("subsonic") or {})
        for k in ("url", "user", "name"):
            if k in body["subsonic"]:
                sub[k] = str(body["subsonic"][k]).strip()
        pwd = body["subsonic"].get("pass")
        if pwd and pwd != "******":           # 前端回传掩码时不覆盖真密码
            sub["pass"] = str(pwd)
        sub["url"] = (sub.get("url") or "").rstrip("/")
        cfg["subsonic"] = sub

    if "local" in body:
        loc = dict(cfg.get("local") or {})
        if "dir" in body["local"]:
            loc["dir"] = str(body["local"]["dir"]).strip()
        cfg["local"] = loc

    cfg["provider"] = provider
    pref_set_json("music.config", cfg)
    with _INDEX_LOCK:
        _INDEX["at"] = 0
        _INDEX["root"] = ""
    return get_config()


# ═══════════════════════ 本地曲库 ═══════════════════════
def _syncsafe(b):
    n = 0
    for x in b:
        n = (n << 7) | (x & 0x7F)
    return n


def _decode_text(raw, enc):
    if not raw:
        return ""
    try:
        if enc == 1:
            return raw.decode("utf-16", "replace").strip("\x00").strip()
        if enc == 2:
            return raw.decode("utf-16-be", "replace").strip("\x00").strip()
        if enc == 3:
            return raw.decode("utf-8", "replace").strip("\x00").strip()
        return raw.decode("latin-1", "replace").strip("\x00").strip()
    except Exception:
        return ""


def _read_id3(path):
    """极简 ID3v2 读取：标题/歌手/专辑/年份/音轨。读不到就返回空。"""
    tags = {}
    try:
        with open(path, "rb") as f:
            head = f.read(10)
            if len(head) < 10 or head[:3] != b"ID3":
                return tags
            size = _syncsafe(head[6:10])
            body = f.read(min(size, 4 * 1024 * 1024))
    except Exception:
        return tags
    ver = head[3]
    pos = 0
    wanted = {b"TIT2": "title", b"TPE1": "artist", b"TALB": "album",
              b"TYER": "year", b"TDRC": "year", b"TRCK": "track"}
    while pos + 10 <= len(body):
        fid = body[pos:pos + 4]
        if fid.strip(b"\x00") == b"":
            break
        if ver == 4:
            fsz = _syncsafe(body[pos + 4:pos + 8])
        else:
            fsz = int.from_bytes(body[pos + 4:pos + 8], "big")
        if fsz <= 0 or pos + 10 + fsz > len(body):
            break
        data = body[pos + 10:pos + 10 + fsz]
        key = wanted.get(fid)
        if key and data:
            tags[key] = _decode_text(data[1:], data[0])
        pos += 10 + fsz
    return tags


def _read_flac_tags(path):
    tags = {}
    try:
        with open(path, "rb") as f:
            head = f.read(262144)
    except Exception:
        return tags
    if not head.startswith(b"fLaC"):
        return tags
    txt = head.decode("utf-8", "ignore")
    for key, field in (("TITLE", "title"), ("ARTIST", "artist"),
                       ("ALBUM", "album"), ("DATE", "year"),
                       ("TRACKNUMBER", "track")):
        m = re.search(key + r"=([^\x00\r\n]{1,120})", txt)
        if m:
            tags[field] = m.group(1).strip()
    return tags


def _tags(path):
    low = path.lower()
    if low.endswith(".mp3"):
        t = _read_id3(path)
    elif low.endswith(".flac"):
        t = _read_flac_tags(path)
    else:
        t = {}
    stem = os.path.splitext(os.path.basename(path))[0]
    # 文件名兜底：「01 - 歌名」「歌名 - 歌手」
    if not t.get("title"):
        m = re.match(r"^\s*(\d{1,3})[\s._-]+(.+)$", stem)
        t["title"] = (m.group(2) if m else stem).strip()
        if m and not t.get("track"):
            t["track"] = m.group(1)
    return t


def _root():
    cfg = get_config(mask=False)
    return (cfg.get("local") or {}).get("dir") or os.environ.get("MUSIC_DIR") or ""


def local_index(force=False, root=None):
    """扫目录建索引。结果按根目录缓存 10 分钟，避免每次进页面都扫盘。

    root 传值时只做一次预览扫描，不写入配置。
    """
    root = root or _root()
    if not root:
        return {"root": "", "albums": [], "error": "还没配置本地音乐目录"}
    if not os.path.isdir(root):
        return {"root": root, "albums": [], "error": "目录不存在：%s" % root}
    with _INDEX_LOCK:
        if (not force and _INDEX["root"] == root and _INDEX["albums"]
                and time.time() - _INDEX["at"] < 600):
            return {"root": root, "albums": _INDEX["albums"]}

    albums = {}
    count = 0
    for base, dirs, files in os.walk(root):
        dirs[:] = [d for d in dirs if not d.startswith(".")][:40]
        if base.count(os.sep) - root.count(os.sep) > 4:
            dirs[:] = []
            continue
        for fn in files:
            if not fn.lower().endswith(AUDIO_EXT):
                continue
            full = os.path.join(base, fn)
            try:
                size = os.path.getsize(full)
            except Exception:
                continue
            if size < 64 * 1024:              # 采样/占位文件，跳过
                continue
            count += 1
            if count > 20000:
                break
            tags = _tags(full)
            rel = os.path.relpath(full, root)
            folder = os.path.basename(base) or "未分类"
            artist = tags.get("artist") or os.path.basename(os.path.dirname(base)) or "未知歌手"
            album = tags.get("album") or folder
            aid = hashlib.sha1((album + "|" + artist).encode("utf-8")).hexdigest()[:12]
            a = albums.setdefault(aid, {
                "id": aid, "title": album, "artist": artist,
                "year": (tags.get("year") or "")[:4], "cover": None,
                "provider": "local", "tracks": [],
            })
            tid = base64.urlsafe_b64encode(rel.encode("utf-8")).decode("ascii")
            a["tracks"].append({
                "id": tid, "title": tags.get("title") or fn,
                "artist": tags.get("artist") or artist,
                "album": album, "albumId": aid, "provider": "local",
                "duration": 0, "track": int(re.sub(r"\D", "", tags.get("track") or "0") or 0),
                "cover": None,
            })
    out = []
    for a in albums.values():
        a["tracks"].sort(key=lambda t: (t["track"] or 999, t["title"]))
        a["songs"] = len(a["tracks"])
        out.append(a)
    out.sort(key=lambda a: a["title"])
    with _INDEX_LOCK:
        _INDEX.update({"root": root, "at": time.time(), "albums": out})
    log("本地音乐库：%d 张专辑 / %d 首" % (len(out), sum(a["songs"] for a in out)))
    return {"root": root, "albums": out}


def local_track_path(tid):
    root = os.path.realpath(_root())
    try:
        rel = base64.urlsafe_b64decode(tid.encode("ascii")).decode("utf-8")
    except Exception:
        return None
    full = os.path.realpath(os.path.join(root, rel))
    if not root or not full.startswith(root + os.sep):
        return None
    return full if os.path.isfile(full) else None


# ═══════════════════════ Subsonic ═══════════════════════
def _sub_cfg(override=None):
    if override:
        saved = (get_config(mask=False).get("subsonic") or {})
        merged = dict(saved)
        for k, v in override.items():
            if v:                      # 表单里没填的项沿用已保存的
                merged[k] = v
        return merged
    cfg = get_config(mask=False)
    return cfg.get("subsonic") or {}


def _sub_url(method, cfg=None, **params):
    c = _sub_cfg(cfg)
    base = (c.get("url") or "").rstrip("/")
    if not base:
        raise ValueError("还没配置 Subsonic 服务器地址")
    salt = hashlib.md5(os.urandom(8)).hexdigest()[:12]
    token = hashlib.md5((c.get("pass", "") + salt).encode("utf-8")).hexdigest()
    q = {"u": c.get("user", ""), "t": token, "s": salt,
         "v": "1.16.1", "c": "nas-screening", "f": "json"}
    q.update({k: v for k, v in params.items() if v not in (None, "")})
    return base + "/rest/" + method + "?" + urlencode(q)


def _sub_call(method, timeout=12, cfg=None, **params):
    txt = http_get(_sub_url(method, cfg=cfg, **params), timeout=timeout)
    d = json.loads(txt)
    sub = d.get("subsonic-response") or {}
    if sub.get("status") != "ok":
        err = (sub.get("error") or {}).get("message") or "Subsonic 返回错误"
        raise RuntimeError(err)
    return sub


def subsonic_ping(override=None):
    try:
        sub = _sub_call("ping", timeout=8, cfg=override)
        return {"ok": True, "version": sub.get("version", ""),
                "server": (get_config(mask=False).get("subsonic") or {}).get("url", "")}
    except Exception as e:
        return {"ok": False, "msg": str(e)[:160]}


def _sub_album(a):
    cid = a.get("coverArt") or a.get("id")
    return {
        "id": str(a.get("id")), "title": a.get("name") or a.get("title") or "未命名专辑",
        "artist": a.get("artist") or "未知歌手", "year": str(a.get("year") or ""),
        "cover": ("/api/music/cover?provider=subsonic&id=" + quote(str(cid))) if cid else None,
        "songs": a.get("songCount") or 0, "provider": "subsonic",
        "duration": a.get("duration") or 0,
    }


def _sub_song(s):
    cid = s.get("coverArt") or s.get("albumId")
    return {
        "id": str(s.get("id")), "title": s.get("title") or "未命名",
        "artist": s.get("artist") or "未知歌手", "album": s.get("album") or "",
        "albumId": str(s.get("albumId") or ""), "provider": "subsonic",
        "duration": s.get("duration") or 0, "track": s.get("track") or 0,
        "cover": ("/api/music/cover?provider=subsonic&id=" + quote(str(cid))) if cid else None,
    }


# ═══════════════════════ 统一接口 ═══════════════════════
def home(limit=24):
    cfg = get_config(mask=False)
    provider = cfg.get("provider") or ""
    if not provider:
        return {"provider": "", "configured": False, "albums": [], "artists": [],
                "msg": "还没启用音乐库，去设置 → 音乐 里配置"}
    if provider == "local":
        idx = local_index()
        albums = idx["albums"]
        artists = {}
        for a in albums:
            artists.setdefault(a["artist"], {"name": a["artist"], "count": 0})
            artists[a["artist"]]["count"] += 1
        return {"provider": "local", "configured": True,
                "root": idx["root"], "error": idx.get("error"),
                "albums": albums[:limit],
                "total": len(albums),
                "artists": sorted(artists.values(), key=lambda x: -x["count"])[:16]}
    # subsonic
    try:
        sub = _sub_call("getAlbumList2", type="newest", size=limit)
        albums = [_sub_album(a) for a in
                  ((sub.get("albumList2") or {}).get("album") or [])]
        arts = []
        try:
            ar = _sub_call("getArtists")
            for idx in (ar.get("artists") or {}).get("index") or []:
                for a in idx.get("artist") or []:
                    arts.append({"name": a.get("name"), "count": a.get("albumCount") or 0})
        except Exception:
            pass
        return {"provider": "subsonic", "configured": True,
                "albums": albums, "artists": arts[:16]}
    except Exception as e:
        return {"provider": "subsonic", "configured": True,
                "albums": [], "artists": [], "error": str(e)[:160]}


def album(aid):
    cfg = get_config(mask=False)
    if (cfg.get("provider") or "") == "local":
        for a in local_index()["albums"]:
            if a["id"] == aid:
                return a
        raise ValueError("专辑不存在")
    sub = _sub_call("getAlbum", id=aid)
    al = sub.get("album") or {}
    out = _sub_album(al)
    out["tracks"] = [_sub_song(s) for s in al.get("song") or []]
    out["desc"] = al.get("notes") or ""
    return out


def search(kw, limit=30):
    cfg = get_config(mask=False)
    provider = cfg.get("provider") or ""
    if not provider:
        return {"albums": [], "tracks": []}
    if provider == "local":
        k = (kw or "").lower()
        albums, tracks = [], []
        for a in local_index()["albums"]:
            if k in a["title"].lower() or k in a["artist"].lower():
                albums.append(a)
            for t in a["tracks"]:
                if k in t["title"].lower() or k in t["artist"].lower():
                    tracks.append(t)
        return {"albums": albums[:limit], "tracks": tracks[:limit]}
    sub = _sub_call("search3", query=kw, songCount=limit, albumCount=limit)
    r = sub.get("searchResult3") or {}
    return {"albums": [_sub_album(a) for a in r.get("album") or []],
            "tracks": [_sub_song(s) for s in r.get("song") or []]}


def stream_url(tid):
    """返回可播放地址：本地直接给 /api/music/file，Subsonic 给带签名的直链。"""
    cfg = get_config(mask=False)
    if (cfg.get("provider") or "") == "local":
        return "/api/music/file?id=" + quote(tid)
    return _sub_url("stream", id=tid)


def cover_bytes(cid):
    cfg = get_config(mask=False)
    if (cfg.get("provider") or "") != "subsonic":
        return None, "image/svg+xml"
    st, body, hdr, _ = raw_get(_sub_url("getCoverArt", id=cid, size=300), timeout=12)
    return body, hdr.get("Content-Type") or "image/jpeg"


def status():
    cfg = get_config(mask=False)
    provider = cfg.get("provider") or ""
    if provider == "subsonic":
        p = subsonic_ping()
        return {"provider": "subsonic", "configured": True, "ok": p["ok"],
                "msg": p.get("msg") or ("已连接 Subsonic " + p.get("version", "")),
                "url": (cfg.get("subsonic") or {}).get("url", "")}
    if provider == "local":
        idx = local_index()
        return {"provider": "local", "configured": True, "ok": not idx.get("error"),
                "msg": idx.get("error") or "已索引 %d 张专辑" % len(idx["albums"]),
                "root": idx.get("root")}
    return {"provider": "", "configured": False, "ok": False, "msg": "未启用音乐库"}
