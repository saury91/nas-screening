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
import urllib.request

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
              b"TYER": "year", b"TDRC": "year", b"TRCK": "track",
              b"TLEN": "duration_ms"}
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
        key = wanted.get(fid)
        if key:
            data = body[pos + 10:pos + 10 + fsz]
            if data:
                tags[key] = _decode_text(data[1:], data[0])
        elif fid == b"APIC":
            # 只记「有没有封面」，真正的图在请求封面时才读，避免扫库时搬运几 MB
            tags["has_art"] = "1"
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


def _flac_duration(path):
    """FLAC 的时长藏在 STREAMINFO 里：采样率 20 bit + 总采样数 36 bit。
    标准库没有解码器，但这两个字段够用了。"""
    try:
        with open(path, "rb") as f:
            head = f.read(42)
    except Exception:
        return 0
    if len(head) < 42 or not head.startswith(b"fLaC"):
        return 0
    if (head[4] & 0x7F) != 0:          # 第一个 metadata block 必须是 STREAMINFO
        return 0
    payload = head[8:42]
    bits = int.from_bytes(payload[10:18], "big")
    rate = (bits >> 44) & 0xFFFFF
    total = bits & 0xFFFFFFFFF
    if not rate:
        return 0
    return int(total / rate)


# 同目录下的封面图：本地曲库最常见、也最省事的封面来源
_COVER_NAMES = ("cover.jpg", "cover.jpeg", "cover.png", "folder.jpg", "folder.png",
                "front.jpg", "front.png", "album.jpg", "album.png", "poster.jpg")
_COVER_CACHE = {}
_COVER_LOCK = threading.Lock()


def _folder_cover(path):
    d = os.path.dirname(path)
    try:
        names = os.listdir(d)
    except Exception:
        return None
    for want in _COVER_NAMES:
        for real in names:
            if real.lower() != want:
                continue
            full = os.path.join(d, real)
            try:
                if os.path.getsize(full) > 4 * 1024 * 1024:
                    continue
                mime = "image/png" if real.lower().endswith(".png") else "image/jpeg"
                with open(full, "rb") as f:
                    return mime, f.read()
            except Exception:
                continue
    return None


def _read_apic(path):
    """从 ID3v2 里取内嵌封面（APIC 帧）。"""
    try:
        with open(path, "rb") as f:
            head = f.read(10)
            if len(head) < 10 or head[:3] != b"ID3":
                return None
            body = f.read(min(_syncsafe(head[6:10]), 12 * 1024 * 1024))
    except Exception:
        return None
    ver = head[3]
    pos = 0
    while pos + 10 <= len(body):
        fid = body[pos:pos + 4]
        if fid.strip(b"\x00") == b"":
            break
        fsz = (_syncsafe(body[pos + 4:pos + 8]) if ver == 4
               else int.from_bytes(body[pos + 4:pos + 8], "big"))
        if fsz <= 0 or pos + 10 + fsz > len(body):
            break
        if fid == b"APIC":
            data = body[pos + 10:pos + 10 + fsz]
            try:
                enc = data[0]
                rest = data[1:]
                end = rest.find(b"\x00")
                if end < 0:
                    return None
                mime = rest[:end].decode("latin-1", "replace") or "image/jpeg"
                rest = rest[end + 1:]
                if enc in (1, 2):          # UTF-16 描述以双字节 0 结尾
                    i = 0
                    while i + 1 < len(rest):
                        if rest[i] == 0 and rest[i + 1] == 0:
                            i += 2
                            break
                        i += 2
                    rest = rest[i:]
                else:
                    j = rest.find(b"\x00")
                    rest = rest[j + 1:] if j >= 0 else rest
                if len(rest) > 64:
                    return mime, rest[:4 * 1024 * 1024]
            except Exception:
                return None
        pos += 10 + fsz
    return None


def _tags(path):
    low = path.lower()
    if low.endswith(".mp3"):
        t = _read_id3(path)
    elif low.endswith(".flac"):
        t = _read_flac_tags(path)
    else:
        t = {}
    if low.endswith(".flac"):
        t["duration"] = _flac_duration(path)
    else:
        try:
            t["duration"] = int(int(t.get("duration_ms") or 0) / 1000)
        except Exception:
            t["duration"] = 0
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
        # 这一层目录有没有 cover.jpg / folder.jpg，一个 listdir 的代价，每个目录只判一次
        dir_cover = any(f.lower() in _COVER_NAMES for f in files)
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
                "year": (tags.get("year") or "")[:4],
                # 有没有封面要等扫完再定：没有的话给 None，前端就不会去请求 404
                "cover": None,
                "provider": "local", "tracks": [],
            })
            # 有内嵌封面或同目录封面图，才挂 cover 地址
            if tags.get("has_art") or dir_cover:
                a["has_cover"] = True
            tid = base64.urlsafe_b64encode(rel.encode("utf-8")).decode("ascii")
            a["tracks"].append({
                "id": tid, "title": tags.get("title") or fn,
                "artist": tags.get("artist") or artist,
                "album": album, "albumId": aid, "provider": "local",
                "duration": tags.get("duration") or 0,
                "track": int(re.sub(r"\D", "", tags.get("track") or "0") or 0),
                "cover": None,
            })
    out = []
    for a in albums.values():
        if a.pop("has_cover", False):
            a["cover"] = "/api/music/cover?provider=local&id=" + a["id"]
            for t in a["tracks"]:
                t["cover"] = a["cover"]
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


def local_cover(aid):
    """专辑封面：先读 ID3 内嵌 APIC，再找同目录的 cover.jpg。
    返回 (bytes, mime)，与 cover_bytes 的契约一致。结果缓存 1 小时。"""
    now = time.time()
    with _COVER_LOCK:
        hit = _COVER_CACHE.get(aid)
    if hit and now - hit[0] < 3600:
        return hit[2], hit[1] or "image/jpeg"
    mime, data = None, None
    for a in local_index()["albums"]:
        if a["id"] != aid:
            continue
        tracks = a.get("tracks") or []
        if tracks:
            p = local_track_path(tracks[0]["id"])
            if p:
                got = _read_apic(p) or _folder_cover(p)
                if got:
                    mime, data = got
        break
    if len(_COVER_CACHE) > 128:
        _COVER_CACHE.clear()
    with _COVER_LOCK:
        _COVER_CACHE[aid] = (now, mime, data)
    return data, mime or "image/jpeg"


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
    provider = cfg.get("provider") or ""
    if provider == "local":
        return local_cover(cid)
    if provider != "subsonic":
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


# ═══════════════════════ 在线音源（酷我）═══════════════════════
# 公开接口、无需签名：搜索走 search.kuwo.cn，播放地址走 antiserver 转换。
# 只是「把第三方的公开接口接进来」，不下载、不缓存音频文件。
_KUWO_UA = ("Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) "
            "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36")


def online_search(kw, limit=30):
    """在线搜歌，返回统一 track 结构（provider=kuwo）。"""
    kw = (kw or "").strip()
    if not kw:
        return {"items": []}
    q = {
        "pn": "0", "rn": str(max(1, min(50, limit))), "all": kw, "ft": "music",
        "newsearch": "1", "alflac": "1", "itemset": "web_2013", "client": "kt",
        "cluster": "0", "vermerge": "1", "rformat": "json", "encoding": "utf8",
        "show_copyright_off": "1", "pcmp4": "1", "ver": "mbox", "plat": "pc",
        "vipver": "MUSIC_9.2.0.0_W6", "devid": "11404450", "newver": "1",
        "issubtitle": "1", "pcjson": "1",
    }
    url = "http://search.kuwo.cn/r.s?" + urlencode(q)
    txt = http_get(url, timeout=12, extra={"User-Agent": _KUWO_UA})
    try:
        d = json.loads(txt)
    except Exception:
        return {"items": []}
    out = []
    for s in d.get("abslist") or []:
        rid = str(s.get("DC_TARGETID") or "").strip()
        if not rid:
            continue
        pic = (s.get("web_albumpic_short") or "").strip()
        cover = ("https://img4.kuwo.cn/star/albumcover/" + pic.replace("120", "300", 1)) if pic else ""
        out.append({
            "id": rid,
            "title": (s.get("SONGNAME") or s.get("NAME") or "").strip(),
            "artist": (s.get("ARTIST") or "").strip(),
            "album": (s.get("ALBUM") or "").strip(),
            "duration": int(s.get("DURATION") or 0),
            "cover": cover,
            "provider": "kuwo",
        })
        if len(out) >= limit:
            break
    return {"items": out}


_URL_CACHE = {}
_URL_TTL = 600
# 酷我对无版权/需会员的曲目会返回一段固定大小的试听片段（实测 181521B ≈ 11s）
_PREVIEW_BYTES = 181521


def online_url(rid, src="", use_cache=True):
    """把歌曲 ID 换成可播放地址。

    netease 的曲目不直连 CDN（上游要求 Referer），一律走本机同源代理；
    酷我走原来的 antiserver 转换，地址有时效，播放前现取。
    """
    import time as _t
    rid = str(rid or "").strip()
    if not rid.isdigit():
        return ""
    if str(src or "").strip() in ("netease", "musicbox"):
        return "/api/music/online/stream?id=" + rid
    if use_cache:
        hit = _URL_CACHE.get(rid)
        if hit and _t.time() - hit[0] < _URL_TTL:
            return hit[1]
    q = {"type": "convert_url", "format": "mp3", "response": "url", "rid": "MUSIC_" + rid}
    try:
        txt = http_get("http://antiserver.kuwo.cn/anti.s?" + urlencode(q),
                       timeout=10, extra={"User-Agent": _KUWO_UA,
                                          "Referer": "https://www.kuwo.cn"})
    except Exception:
        return ""
    txt = (txt or "").strip()
    if not txt.startswith("http"):
        return ""
    if len(_URL_CACHE) > 500:
        _URL_CACHE.clear()
    _URL_CACHE[rid] = (_t.time(), txt)
    return txt


def _content_length(url, timeout=6):
    try:
        req = urllib.request.Request(url, method="HEAD", headers={"User-Agent": _KUWO_UA})
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return int(r.headers.get("Content-Length") or 0)
    except Exception:
        return 0


def _is_full_track(track):
    """判断是不是完整曲目：试听片段只有 181KB 左右，按 128kbps 反推时长即可分辨。"""
    rid = track.get("id")
    dur = int(track.get("duration") or 0)
    url = online_url(rid)
    if not url:
        return False
    size = _content_length(url)
    if size <= 0:
        return False
    if size == _PREVIEW_BYTES:
        return False
    # 128kbps 下这段字节数最多能放多久；比标称时长还短一截，说明是片段
    max_seconds = size * 8 / 128000
    return (not dur) or max_seconds >= dur * 0.8


# 在线推荐（兜底方案）：用几个代表性关键词去搜，做成「按类型」的货架。
# 酷我没有开放的「分类歌单」接口，但搜索接口是公开的，用它拼出分类最稳。
# 关键词是实测选的：这些词搜出来的结果里「能完整播」的比例最高。
# 用明星名搜（周杰伦/陈奕迅）反而全是会员试听，做推荐位没意义。
_ONLINE_CATS = [
    ("民谣", "民谣"), ("摇滚", "摇滚"), ("电子", "电子"), ("轻音乐", "轻音乐"),
    ("钢琴", "钢琴"), ("影视原声", "原声带"), ("怀旧金曲", "怀旧"), ("抖音热歌", "抖音热歌"),
]
_ONLINE_HOME = {"at": 0, "data": None}

# 歌单推荐优先挑这些品类的歌单：独立音乐 / 纯音乐占比高，几乎都能整曲播放
_PREFER_WORDS = ("轻音乐", "纯音乐", "钢琴", "氛围", "安静", "民谣", "爵士", "lo-fi",
                 "Lo-fi", "学习", "工作", "治愈", "放松", "Chill", "夜")
# 网易云歌单标题鱼龙混杂，这类标题不适合出现在自家客厅的放映室里
_BLOCK_WORDS = ("微醺", "床上", "约", "裸", "18禁", "情色", "色气", "擦边")


def _blocked(name):
    low = (name or "").lower()
    return any(w.lower() in low for w in _BLOCK_WORDS)


def _shelf_from_playlist(pl, tracks, need):
    """把一个歌单做成货架；tracks 是已经校验过能整曲播放的那批。"""
    if len(tracks) < 4:
        return None
    return {
        "name": pl.get("name") or "",
        "sub": "网易云歌单 · %d 首" % (pl.get("count") or len(tracks)),
        "cover": pl.get("cover") or "",
        "items": tracks[:need],
    }


def _keyword(p):
    name = (p.get("name") or "").lower()
    for w in _PREFER_WORDS:
        if w.lower() in name:
            return w.lower()
    return ""


def _home_from_playlists(limit=8, want=6, per_kind=2):
    """真实歌单推荐：拉推荐歌单 → 并发读曲目 → 只留能整曲播放的。

    同类关键词最多出 per_kind 个货架，否则整页都是「纯音乐」，看着像只有一类。
    """
    from concurrent.futures import ThreadPoolExecutor

    from . import musicbox

    pls = [p for p in musicbox.recommend(limit=14) if p.get("name") and not _blocked(p["name"])]
    if not pls:
        return []

    # 名字里带偏好词的排前面，其次曲目多的排前面（候选池大更容易凑够可播曲目）
    def score(p):
        return (0 if _keyword(p) else 1, -min(p.get("count") or 0, 500))

    pls.sort(key=score)
    picked, used = [], {}
    for p in pls:
        k = _keyword(p) or ("#" + p["id"])
        if used.get(k, 0) >= per_kind:
            continue
        used[k] = used.get(k, 0) + 1
        picked.append(p)
        if len(picked) >= want:
            break

    # 第一步：并发拿歌单曲目
    def load(p):
        try:
            d = musicbox.playlist(p["id"], limit=60)
            return d["tracks"] if d.get("tracks") else []
        except Exception:
            return []

    with ThreadPoolExecutor(max_workers=5) as ex:
        lists = list(ex.map(load, picked))

    # 第二步：共用线程池，按货架轮流补足可播曲目
    good_lists = musicbox.select_full(lists, limit, workers=16)
    shelves = []
    for pl, tracks in zip(picked, good_lists):
        s = _shelf_from_playlist(pl, tracks, limit)
        if s:
            shelves.append(s)
    return shelves[:want]


def _home_from_keywords(limit=10):
    """兜底：按关键词搜歌，逐个校验能否完整播放。"""
    from concurrent.futures import ThreadPoolExecutor

    def search_one(pair):
        name, kw = pair
        try:
            return name, online_search(kw, limit=16).get("items") or []
        except Exception:
            return name, []

    with ThreadPoolExecutor(max_workers=4) as ex:
        raw = list(ex.map(search_one, _ONLINE_CATS))

    flat = [(name, t) for name, items in raw for t in items]
    with ThreadPoolExecutor(max_workers=20) as ex:
        checked = list(ex.map(lambda nt: (nt[0], nt[1], _is_full_track(nt[1])), flat))

    buckets = {}
    for name, t, ok in checked:
        if ok:
            buckets.setdefault(name, []).append(t)
    cats = [{"name": n, "sub": "按类型推荐", "cover": "", "items": buckets.get(n, [])[:limit]}
            for n, _ in _ONLINE_CATS]
    return [c for c in cats if len(c["items"]) >= 4]


def online_home(limit=10, fresh=False, ttl=1800):
    """推荐货架：优先真实歌单，歌单接口不可用时退回关键词方案。缓存 30 分钟。"""
    import time as _t

    now = _t.time()
    if not fresh and _ONLINE_HOME["data"] and now - _ONLINE_HOME["at"] < ttl:
        return _ONLINE_HOME["data"]

    cats, src = [], "playlist"
    try:
        cats = _home_from_playlists(limit=limit)
    except Exception:
        cats = []
    if not cats:
        src = "keyword"
        try:
            cats = _home_from_keywords(limit=limit)
        except Exception:
            cats = []

    data = {"cats": cats, "src": src, "at": int(now)}
    if cats:
        _ONLINE_HOME.update({"at": now, "data": data})
    return data


# ═══════════════════════ 歌词 ═══════════════════════
# 只做「取回 + 解析」，不存歌词文件。取不到就返回空，播放器显示「暂无歌词」。
_LRC_CACHE = {}


def parse_lrc(text):
    """LRC 文本 → [{t, text}]，按时间排序。

    支持一行多个时间戳（[00:12.00][01:30.00]副歌），也支持翻译行
    （同一时间戳的第二行，用 / 拼起来）。纯时间戳行（间奏）跳过。
    """
    import re as _re

    if not text:
        return []
    stamp = _re.compile(r"\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?\]")
    rows = []
    for raw in text.splitlines():
        s = raw.strip()
        if not s:
            continue
        hits = list(stamp.finditer(s))
        if not hits:
            continue
        body = stamp.sub("", s).strip()
        if not body:
            continue
        for m in hits:
            mm, ss = int(m.group(1)), int(m.group(2))
            frac = m.group(3) or "0"
            # .5 / .50 / .500 都要按毫秒补位
            ms = int(frac.ljust(3, "0")[:3])
            rows.append((mm * 60 + ss + ms / 1000.0, body))
    rows.sort(key=lambda x: x[0])

    # 同一时间戳多行 = 原文 + 翻译
    out = []
    for t, body in rows:
        if out and abs(out[-1]["t"] - t) < 0.05:
            if body not in out[-1]["text"]:
                out[-1]["text"] += "\n" + body
            continue
        out.append({"t": round(t, 2), "text": body})
    return out


def _norm_title(s):
    """归一歌名：去掉括号里的版本说明、空白与标点，用来判断是不是同一首。"""
    s = (s or "").lower()
    s = re.sub(r"[\(（\[【].*?[\)）\]】]", "", s)
    s = re.sub(r"[\s\-_·.,，。!！?？'\"“”‘’/|]+", "", s)
    return s


def _lyrics_from_cover(title, artist):
    """酷我那边歌词接口已经要签名了，改成按「歌名 + 歌手」去歌单源反查。

    只接受高置信匹配（歌名归一相等），否则宁可显示「暂无歌词」，
    也不想给人看一首歌配着另一首歌的词。
    """
    from . import musicbox

    title = (title or "").strip()
    if not title:
        return []
    nt = _norm_title(title)
    na = (artist or "").strip().lower()
    best, score = None, 0
    for c in musicbox.search(("%s %s" % (title, artist or "")).strip(), limit=8):
        ct = _norm_title(c.get("title"))
        s = 0
        if nt and ct == nt:
            s += 60
        elif nt and nt in ct:
            s += 25
        if na and na in (c.get("artist") or "").lower():
            s += 40
        if (c.get("title") or "").strip().lower() == title.lower():
            s += 20          # 完全没带「(Live)」「(深情版)」这种后缀
        if s > score:
            best, score = c, s
    if not best or score < 60:
        return []
    try:
        txt = http_get(musicbox.lrc_url(best["id"]), timeout=12,
                       extra={"Referer": musicbox.REFERER, "User-Agent": _KUWO_UA})
        return parse_lrc(txt)
    except Exception:
        return []


def lyrics(tid, src="", title="", artist=""):
    """取一首歌的歌词（已解析）。失败返回空列表。"""
    tid = str(tid or "").strip()
    src = (src or "").strip()
    key = (src, tid)
    if not tid:
        return []
    hit = _LRC_CACHE.get(key)
    if hit is not None:
        return hit

    lines = []
    try:
        if src in ("netease", "musicbox"):
            from . import musicbox
            txt = http_get(musicbox.lrc_url(tid), timeout=12,
                           extra={"Referer": musicbox.REFERER, "User-Agent": _KUWO_UA})
            lines = parse_lrc(txt)
        elif src == "kuwo":
            lines = _lyrics_from_cover(title, artist)
    except Exception:
        lines = []

    if len(_LRC_CACHE) > 300:
        _LRC_CACHE.clear()
    _LRC_CACHE[key] = lines
    return lines
