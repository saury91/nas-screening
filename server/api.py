# -*- coding: utf-8 -*-
"""HTTP 接口层。

只做三件事：解析参数 → 调服务层 → 序列化响应。
业务逻辑一律在 services / providers 里，新增接口不需要动服务代码。
"""
import json
import mimetypes
import os
import re
import sys
import time
import urllib.parse
from http.server import BaseHTTPRequestHandler

from .cache import cache_peek
from .config import (CATEGORIES, CAT_ORDER, CAT_SLUG, SLUG_CAT, STATIC)
from .db import (dbrun, note_search, pref_json, source_get, toggle_fav,
                 touch_history)
from .log import log
from .providers import (active_sites, add_source, all_sites, import_config,
                        remove_source, set_cat, set_enabled, set_pri,
                        test_source)
from .providers import magnet
from .providers.cms import fetch_vod
from .services import catalog, live, music, probe, proxy
from .services.qbt import QBT

MIME = {
    ".html": "text/html; charset=utf-8",
    ".js": "text/javascript; charset=utf-8",
    ".mjs": "text/javascript; charset=utf-8",
    ".css": "text/css; charset=utf-8",
    ".json": "application/json; charset=utf-8",
    ".svg": "image/svg+xml",
    ".png": "image/png",
    ".jpg": "image/jpeg",
    ".jpeg": "image/jpeg",
    ".webp": "image/webp",
    ".gif": "image/gif",
    ".ico": "image/x-icon",
    ".woff2": "font/woff2",
    ".mp4": "video/mp4",
}


def _json(obj, code=200):
    return code, json.dumps(obj, ensure_ascii=False).encode("utf-8"), \
        "application/json; charset=utf-8"


def _err(msg, code=400):
    return _json({"ok": False, "error": msg, "msg": msg}, code)


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    server_version = "ProjectionRoom/3.0"

    # ── 响应工具 ──────────────────────────────────────────────
    def log_message(self, fmt, *args):
        try:
            sys.stderr.write("[%s] %-15s %s %s -> %s\n" % (
                time.strftime("%H:%M:%S"), self.client_address[0],
                self.command, self.path, (args[1] if len(args) > 1 else "")))
            sys.stderr.flush()
        except Exception:
            pass

    def _send(self, code, body, ctype="application/json; charset=utf-8",
              cache="no-store", extra=None):
        if isinstance(body, str):
            body = body.encode("utf-8")
        hdr = [("Content-Type", ctype), ("Cache-Control", cache),
               ("Access-Control-Allow-Origin", "*"),
               ("X-Content-Type-Options", "nosniff")]
        if extra:
            hdr.extend(extra)
        try:
            self.send_response(code)
            for k, v in hdr:
                self.send_header(k, v)
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            if self.command != "HEAD":
                self.wfile.write(body)
        except Exception:
            pass

    def _body(self):
        n = int(self.headers.get("Content-Length") or 0)
        raw = self.rfile.read(n) if n else b""
        if not raw:
            return {}
        ctype = (self.headers.get("Content-Type") or "").lower()
        if "application/json" in ctype:
            try:
                d = json.loads(raw.decode("utf-8", "replace"))
                return d if isinstance(d, dict) else {}
            except Exception:
                return {}
        try:
            q = urllib.parse.parse_qs(raw.decode("utf-8", "replace"))
            return {k: v[0] for k, v in q.items()}
        except Exception:
            return {}

    # ── 静态资源 ──────────────────────────────────────────────
    def _serve_file(self, path, cache="public, max-age=300", download=False):
        root = os.path.realpath(STATIC)
        full = os.path.realpath(os.path.join(root, path.lstrip("/")))
        if not full.startswith(root + os.sep) or not os.path.isfile(full):
            return self._send(404, b'{"error":"not found"}',
                              "application/json; charset=utf-8")
        ext = os.path.splitext(full)[1].lower()
        ctype = MIME.get(ext) or mimetypes.guess_type(full)[0] or "application/octet-stream"
        try:
            with open(full, "rb") as f:
                data = f.read()
        except Exception:
            return self._send(500, b'{"error":"read fail"}',
                              "application/json; charset=utf-8")
        hdr = [("Access-Control-Allow-Origin", "*")]
        if download:
            hdr.append(("Content-Disposition", "inline"))
        self._send(200, data, ctype, cache, hdr)

    def _range_file(self, full, ctype="audio/mpeg", download_name=""):
        """带 Range 的音频输出，播放器才能拖动进度条。"""
        try:
            size = os.path.getsize(full)
        except Exception:
            return self._send(404, b'{"error":"not found"}')
        start, end = 0, size - 1
        rng = self.headers.get("Range") or ""
        m = re.match(r"bytes=(\d*)-(\d*)", rng)
        partial = False
        if m:
            partial = True
            if m.group(1):
                start = int(m.group(1))
                if m.group(2):
                    end = min(int(m.group(2)), size - 1)
            elif m.group(2):
                start = max(0, size - int(m.group(2)))
        if start > end or start >= size:
            return self._send(416, b"", ctype,
                              extra=[("Content-Range", "bytes */%d" % size)])
        length = end - start + 1
        hdr = [("Accept-Ranges", "bytes"),
               ("Content-Range", "bytes %d-%d/%d" % (start, end, size)),
               ("Access-Control-Allow-Origin", "*")]
        if download_name:
            hdr.append(("Content-Disposition", 'inline; filename="%s"' %
                        urllib.parse.quote(download_name)))
        try:
            self.send_response(206 if partial else 200)
            self.send_header("Content-Type", ctype)
            self.send_header("Content-Length", str(length))
            self.send_header("Cache-Control", "no-store")
            for k, v in hdr:
                self.send_header(k, v)
            self.end_headers()
            with open(full, "rb") as f:
                f.seek(start)
                left = length
                while left > 0:
                    chunk = f.read(min(256 * 1024, left))
                    if not chunk:
                        break
                    self.wfile.write(chunk)
                    left -= len(chunk)
        except Exception:
            pass

    # ── GET ──────────────────────────────────────────────────
    def do_HEAD(self):
        return self.do_GET()

    def do_OPTIONS(self):
        self.send_response(204)
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Allow-Methods", "GET,POST,OPTIONS")
        self.send_header("Access-Control-Allow-Headers", "*")
        self.send_header("Content-Length", "0")
        self.end_headers()

    def do_GET(self):
        p = urllib.parse.urlparse(self.path)
        path = p.path
        qs = {k: v[0] for k, v in urllib.parse.parse_qs(p.query).items()}
        try:
            if path.startswith("/static/"):
                rel = path[len("/static/"):]
                # 应用代码不缓存：升级后刷新即生效，避免旧 JS 和新 API 打架
                cache = "no-cache" if rel.startswith("app/") else "public, max-age=2592000"
                return self._serve_file(rel, cache=cache)
            if path == "/api/proxy":
                u = qs.get("u") or ""
                if not u:
                    return self._send(400, b'{"error":"missing u"}')
                try:
                    st, body, ctype, extra = proxy.fetch(u, self.headers.get("Range"))
                except Exception as e:
                    return self._send(502, json.dumps({"error": str(e)[:160]},
                                                     ensure_ascii=False).encode("utf-8"))
                return self._send(st, body, ctype, cache="no-store",
                                  extra=list(extra.items()))
            if path.startswith("/api/"):
                code, body, ctype = self._api_get(path, qs)
                if code == "__RANGE__":
                    return self._range_file(body, ctype)
                return self._send(code, body, ctype)
            if path in ("/", "/index.html"):
                return self._serve_file("index.html", cache="no-cache")
            if path == "/favicon.ico":
                return self._serve_file("favicon.svg", cache="public, max-age=86400")
            # SPA 回退：/title/xxx、/settings/sources 之类都能直接打开
            return self._serve_file("index.html", cache="no-cache")
        except BrokenPipeError:
            return
        except Exception as e:
            log("GET %s 失败：%s" % (path, e))
            return self._send(500, json.dumps({"error": str(e)[:200]},
                                              ensure_ascii=False))

    def _api_get(self, path, qs):
        if path == "/api/status":
            if not QBT.ok:
                QBT.login()
            return _json({
                "qbt": QBT.ok, "qbt_msg": QBT.msg,
                "sites": len(active_sites()), "cats": CATEGORIES,
                "music": music.status(),
                "live": dbrun("SELECT COUNT(*) FROM live", fetch=True)[0][0],
                "db": True,
            })
        if path == "/api/home":
            hist = dbrun("""SELECT kind,key,title,cover,source,ep,url,position,
                            duration,plays,genre,year,updated_at
                            FROM history ORDER BY updated_at DESC LIMIT 30""",
                         fetch=True) or []
            favs = dbrun("""SELECT kind,key,title,cover,payload,added_at
                            FROM fav ORDER BY added_at DESC LIMIT 60""",
                         fetch=True) or []
            st = dbrun("SELECT COUNT(*), COALESCE(SUM(plays),0) FROM history",
                       fetch=True)
            return _json({
                "history": [{"kind": r[0], "key": r[1], "title": r[2], "cover": r[3],
                             "source": r[4], "ep": r[5], "url": r[6],
                             "position": r[7], "duration": r[8], "plays": r[9],
                             "genre": r[10], "year": r[11], "at": r[12]} for r in hist],
                "fav": [{"kind": r[0], "key": r[1], "title": r[2], "cover": r[3],
                         "payload": r[4], "at": r[5]} for r in favs],
                "stat": {"titles": st[0][0] if st else 0,
                         "plays": st[0][1] if st else 0,
                         "favs": len(favs),
                         "live": dbrun("SELECT COUNT(*) FROM live", fetch=True)[0][0],
                         "sites": len(active_sites())},
                "rec": catalog.get_recommend(),
            })
        if path == "/api/library":
            kind = qs.get("kind") or "fav"
            if kind == "fav":
                rows = dbrun("""SELECT kind,key,title,cover,payload,added_at
                                FROM fav ORDER BY added_at DESC LIMIT 200""",
                             fetch=True) or []
                return _json({"kind": "fav", "items": [
                    {"kind": r[0], "key": r[1], "title": r[2], "cover": r[3],
                     "payload": r[4], "at": r[5]} for r in rows]})
            rows = dbrun("""SELECT kind,key,title,cover,source,ep,url,position,
                            duration,plays,genre,year,updated_at
                            FROM history ORDER BY updated_at DESC LIMIT 200""",
                         fetch=True) or []
            return _json({"kind": "history", "items": [
                {"kind": r[0], "key": r[1], "title": r[2], "cover": r[3],
                 "source": r[4], "ep": r[5], "url": r[6], "position": r[7],
                 "duration": r[8], "plays": r[9], "genre": r[10],
                 "year": r[11], "at": r[12]} for r in rows]})
        if path == "/api/recommend":
            return _json(catalog.get_recommend(fresh=qs.get("fresh") == "1"))
        if path == "/api/suggest":
            recent = dbrun("SELECT kw FROM searches ORDER BY last_at DESC LIMIT 10",
                           fetch=True) or []
            hot = dbrun("SELECT kw FROM searches ORDER BY cnt DESC, last_at DESC LIMIT 10",
                        fetch=True) or []
            rk = [r[0] for r in recent]
            hk = [r[0] for r in hot if r[0] not in rk][:8]
            if not (rk or hk):
                from .config import FALLBACK_HOT
                hk = FALLBACK_HOT[:]
            return _json({"recent": rk[:8], "hot": hk})
        if path == "/api/browse":
            cat = qs.get("cat") or ""
            if cat in CAT_SLUG:
                cat = CAT_SLUG[cat]
            try:
                pg = max(1, min(30, int(qs.get("pg") or 1)))
            except Exception:
                pg = 1
            res = catalog.browse_catalog(cat, qs.get("year") or "",
                                         qs.get("area") or "", pg,
                                         site=qs.get("src") or None)
            return _json(res)
        if path == "/api/quality":
            u = qs.get("u") or ""
            if not u:
                return _json({"q": "", "res": ""})
            return _json(probe.probe(u))
        if path == "/api/detail":
            return self._detail(qs.get("n") or "", qs.get("y") or "",
                                qs.get("fresh") == "1")
        if path in ("/api/online", "/api/search"):
            kw = (qs.get("kw") or "").strip()
            if not kw:
                return _json({"items": []})
            note_search(kw)
            return _json({"items": catalog.search_online(kw)})
        if path in ("/api/magnets",):
            kw = (qs.get("kw") or "").strip()
            if not kw:
                return _json({"items": []})
            note_search(kw)
            return _json({"items": magnet.do_search(kw)})
        if path == "/api/live":
            if qs.get("refresh") == "1":
                live.refresh_live()
            return _json({"groups": live.live_groups(),
                          "items": live.live_list(qs.get("g") or None,
                                                  qs.get("kw") or None),
                          "sources": live.source_stats()})
        if path == "/api/sources":
            kind = qs.get("kind") or ""
            rows = all_sites() if kind in ("", "cms") else []
            if kind == "live":
                from .db import sources_list
                stats = live.source_stats()
                rows = [dict(s, api_count=stats.get(s["name"], s.get("api_count") or 0))
                        for s in sources_list("live")]
            return _json({"items": rows, "cats": CAT_ORDER,
                          "providers": {"cms": len(active_sites()),
                                        "live": len(live.source_stats())}})
        if path == "/api/sources/test":
            sid = int(qs.get("id") or 0)
            site = source_get(sid)
            if not site:
                return _err("片源不存在", 404)
            return _json(test_source(site))
        if path == "/api/tasks":
            return _json({"items": QBT.snapshot(), "ok": QBT.ok, "msg": QBT.msg})
        if path == "/api/torrent":
            hh = qs.get("hash") or ""
            if qs.get("act") == "files":
                return _json({"items": QBT.files(hh)})
            return _err("参数错误")
        # ── 音乐 ──
        if path == "/api/music/config":
            return _json({"config": music.get_config(), "status": music.status()})
        if path == "/api/music/status":
            return _json(music.status())
        if path == "/api/music/home":
            return _json(music.home())
        if path == "/api/music/album":
            try:
                return _json(music.album(qs.get("id") or ""))
            except Exception as e:
                return _err(str(e)[:160], 404)
        if path == "/api/music/search":
            return _json(music.search(qs.get("kw") or ""))
        if path == "/api/music/stream":
            url = music.stream_url(qs.get("id") or "")
            return _json({"url": url, "provider": (music.get_config() or {}).get("provider")})
        if path == "/api/music/file":
            full = music.local_track_path(qs.get("id") or "")
            if not full:
                return _err("音轨不存在", 404)
            ext = os.path.splitext(full)[1].lower()
            audio_mime = {".mp3": "audio/mpeg", ".flac": "audio/flac", ".m4a": "audio/mp4",
                          ".aac": "audio/aac", ".ogg": "audio/ogg", ".opus": "audio/ogg",
                          ".wav": "audio/wav"}.get(ext, "application/octet-stream")
            return ("__RANGE__", full, audio_mime)
        if path == "/api/music/cover":
            body, ctype = music.cover_bytes(qs.get("id") or "")
            if body is None:
                return _err("no cover", 404)
            return (200, body, ctype)
        return _err("not found", 404)

    def _detail(self, name, year, fresh=False):
        if not name:
            return _err("缺片名")
        ck = "detail|v5|%s|%s" % (catalog._norm_key(name), year)
        if not fresh:
            cached = cache_peek(ck)
            if cached is not None:
                from .cache import cache_valid
                if cache_valid(ck, 1800):
                    return _json(cached)
        items = catalog.search_online(name)
        KEY = catalog._norm_key(name)
        hit = None
        same = [x for x in items if catalog._norm_key(x["name"]) == KEY]
        for it in same:
            if year and it.get("year") == year:
                hit = it
                break
        if hit is None and same:
            hit = same[0]
        if hit is None:
            for it in items:
                if year and it.get("year") == year:
                    hit = it
                    break
        if hit is None and items:
            hit = items[0]
        if hit is None:
            return _err("没找到这部片子", 404)
        others = [x for x in items
                  if x is not hit and catalog._norm_key(x["name"]) == catalog._norm_key(hit["name"])]
        for o in others:
            hit["sources"].extend(o["sources"])
        best = {}
        for sc in hit["sources"]:
            k = sc["site"]
            if k not in best or sc["count"] > best[k]["count"]:
                best[k] = sc
        hit["sources"] = sorted(best.values(), key=lambda x: (x["site"], -x["count"]))
        hit["site_count"] = len(hit["sources"])
        hit["all_eps"] = max((s["count"] for s in hit["sources"]), default=0)
        hit["cached"] = False
        from .cache import cache_put
        cache_put(ck, hit)
        return _json(hit)

    # ── POST ─────────────────────────────────────────────────
    def do_POST(self):
        path = urllib.parse.urlparse(self.path).path
        try:
            b = self._body()
            return self._api_post(path, b)
        except Exception as e:
            log("POST %s 失败：%s" % (path, e))
            return self._send(500, json.dumps({"ok": False, "msg": str(e)[:200]},
                                              ensure_ascii=False))

    def _api_post(self, path, b):
        def send(obj):
            code, body, ctype = _json(obj)
            self._send(code, body, ctype)

        if path == "/api/history":
            key = b.get("key") or ""
            if not key:
                return send({"ok": False, "msg": "缺 key"})
            touch_history(b.get("kind") or "online", key,
                          title=b.get("title", ""), cover=b.get("cover", ""),
                          source=b.get("source", ""), ep=b.get("ep", ""),
                          url=b.get("url", ""), position=b.get("position", 0),
                          duration=b.get("duration", 0), genre=b.get("genre", ""),
                          year=b.get("year", ""))
            return send({"ok": True})
        if path == "/api/fav":
            key = b.get("key") or ""
            if not key:
                return send({"ok": False, "msg": "缺 key"})
            on = toggle_fav(b.get("kind") or "online", key, b.get("title", ""),
                            b.get("cover", ""), b.get("payload", ""))
            return send({"ok": True, "fav": on})
        if path == "/api/live/check":
            return send(live.check_channels(b.get("group") or ""))
        if path == "/api/live/add":
            u = (b.get("url") or "").strip()
            if not u.startswith("http"):
                return send({"ok": False, "msg": "地址无效"})
            nm = (b.get("name") or "自定义直播源").strip()
            add_source("live", nm, u, type_=0, pri=5)
            n = live.refresh_live(nm, u)
            return send({"ok": True, "msg": "已导入 %d 个频道" % n})
        if path == "/api/live/refresh":
            n = live.refresh_live()
            return send({"ok": True, "msg": "已刷新 %d 个频道" % n})
        if path == "/api/sources/import":
            url = (b.get("url") or "").strip()
            text = (b.get("json") or "").strip()
            if not url and not text:
                return send({"ok": False, "msg": "填一个配置地址，或直接粘贴 JSON"})
            try:
                res = import_config(url=url or None, text=text or None)
                return send(res)
            except Exception as e:
                return send({"ok": False, "msg": "导入失败：%s" % str(e)[:160]})
        if path == "/api/sources":
            name = (b.get("name") or "").strip()
            url = (b.get("url") or "").strip()
            if not name or not url.startswith("http"):
                return send({"ok": False, "msg": "名称和接口地址都要填"})
            kind = b.get("kind") or "cms"
            add_source(kind, name, url, type_=int(b.get("type") or 1),
                       pri=int(b.get("pri") or 5), cat=b.get("cat") or "综合")
            if kind == "live":
                live.refresh_live(name, url)
            return send({"ok": True, "msg": "已添加"})
        if path == "/api/sources/toggle":
            set_enabled(int(b.get("id") or 0), bool(b.get("enabled")))
            return send({"ok": True})
        if path == "/api/sources/pri":
            set_pri(int(b.get("id") or 0), int(b.get("pri") or 5))
            return send({"ok": True})
        if path == "/api/sources/cat":
            set_cat(int(b.get("id") or 0), b.get("cat") or "综合")
            return send({"ok": True})
        if path == "/api/sources/delete":
            sid = int(b.get("id") or 0)
            site = source_get(sid)
            if site and site["kind"] == "live":
                live.clear_source(site["name"])
            remove_source(sid)
            return send({"ok": True, "msg": "已删除"})
        if path == "/api/sources/test":
            site = source_get(int(b.get("id") or 0))
            if not site:
                return send({"ok": False, "msg": "片源不存在"})
            return send(test_source(site))
        if path == "/api/add":
            mg = b.get("magnet") or ""
            if not mg.startswith("magnet:"):
                return send({"ok": False, "msg": "没收到有效的磁力链接"})
            ok, msg = QBT.add(mg, b.get("category") or "电影")
            return send({"ok": ok, "msg": msg})
        if path == "/api/torrent":
            hh = b.get("hash") or ""
            act = b.get("act") or ""
            if not hh or act not in ("pause", "resume", "delete"):
                return send({"ok": False, "msg": "参数错误"})
            ok, msg = QBT.action(hh, act)
            return send({"ok": ok, "msg": msg})
        if path == "/api/music/config":
            try:
                music.save_config(b)
                return send({"ok": True, "config": music.get_config(),
                             "status": music.status()})
            except Exception as e:
                return send({"ok": False, "msg": str(e)[:160]})
        if path == "/api/music/test":
            if (b.get("provider") or "") == "local":
                # 只预览，不落配置：用户没点保存前不该改动现有设置
                idx = music.local_index(force=True, root=b.get("dir") or "")
                return send({"ok": not idx.get("error"),
                             "msg": idx.get("error") or "索引到 %d 张专辑 / %d 首歌" % (
                                 len(idx["albums"]), sum(a["songs"] for a in idx["albums"]))})
            return send(music.subsonic_ping(b.get("subsonic") or None))
        return send({"ok": False, "msg": "not found"})
