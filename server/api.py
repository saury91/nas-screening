# -*- coding: utf-8 -*-
"""HTTP 接口层。

只做三件事：解析参数 → 调服务层 → 序列化响应。
业务逻辑一律在 services / providers 里，新增接口不需要动服务代码。
"""
import gzip
import json
import mimetypes
import os
import re
import sys
import time
import urllib.parse
from http.server import BaseHTTPRequestHandler

from .cache import cache_peek
from .config import (CATEGORIES, CAT_ORDER, CAT_SLUG, DB_PATH, NO_AUTH, SLUG_CAT, STATIC)
from .db import (dbrun, note_search, pref_json, source_get, sources_list,
                 toggle_fav, touch_history)
from .log import log
from .providers import (active_sites, add_source, all_sites, import_config,
                        remove_source, set_cat, set_enabled, set_pri,
                        test_source)
from .providers import magnet
from .providers.cms import fetch_vod
from .services import auth, cast, catalog, config_io, discover, live, music, musicbox, probe, proxy
from .services.qbt import QBT

# 只有管理员能碰的路由前缀：改配置、管账户、动下载。
# 其余 /api/* 登录后任何人可用（看片、记录、收藏都是按人隔离的）。
# 无账户模式下的「本机用户」：权限等于管理员，但没有任何 PIN/会话
LOCAL_USER = {"id": "local", "name": "本机", "avatar": "", "role": "admin",
              "hasPin": False, "createdAt": 0, "lastSeen": 0}

_ADMIN_PREFIX = (
    "/api/sources", "/api/config/", "/api/profiles", "/api/backup",
    "/api/live/add", "/api/live/refresh", "/api/live/collect",
    "/api/live/seeds", "/api/live/check", "/api/live/auto",
    "/api/music/config", "/api/music/test",
    "/api/add", "/api/torrent", "/api/tasks",
)

# 内网升级包目录：data/apps/tv.apk + tv.json
APPS_DIR = os.path.join(os.path.dirname(DB_PATH), "apps")

MIME = {
    ".html": "text/html; charset=utf-8",
    ".js": "text/javascript; charset=utf-8",
    ".mjs": "text/javascript; charset=utf-8",
    ".css": "text/css; charset=utf-8",
    ".json": "application/json; charset=utf-8",
    ".webmanifest": "application/manifest+json; charset=utf-8",
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


def _cookie(tok):
    """会话同时写 Cookie：<video>/<img> 这类元素请求带不了自定义头。"""
    return ("Set-Cookie",
            "pr_token=%s; Path=/; Max-Age=%d; SameSite=Lax; HttpOnly"
            % (tok, auth.TOKEN_TTL))


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

    def _cors_origin(self):
        """只在同源时回显 Origin。

        以前一律发 `*`：局域网里任何网页都能 fetch 到 /api/*，
        读走观看记录、甚至 POST 删片源。同源回显后跨站请求拿不到响应。
        """
        o = self.headers.get("Origin") or ""
        host = self.headers.get("Host") or ""
        if not o or not host:
            return None
        try:
            if urllib.parse.urlparse(o).netloc == host:
                return o
        except Exception:
            pass
        return None

    # ── 身份 ──────────────────────────────────────────────
    # token 既支持请求头（fetch），也支持 Cookie（<video>/<img> 这类元素请求带不了头）
    def _token(self):
        t = self.headers.get("X-Token") or ""
        if not t:
            for part in (self.headers.get("Cookie") or "").split(";"):
                k, _, v = part.strip().partition("=")
                if k == "pr_token":
                    t = v.strip()
                    break
        return t

    def me(self):
        # 无账户模式（NO_AUTH=1）：谁都当本机管理员，整个登录/PIN 流程不参与
        if NO_AUTH:
            return LOCAL_USER
        # 注意：Handler 实例是「每个连接」一个，keep-alive 上会复用。
        # 直接用 hasattr 缓存会把第一个请求（登录前）的 None 带给后面的请求，
        # 表现就是「刚登录完还是 401，刷新一下又好了」。所以每个请求开头都要重置。
        if not hasattr(self, "_me_ready"):
            self._me = auth.resolve(self._token())
            self._me_ready = True
        return self._me

    def _reset_auth(self):
        self.__dict__.pop("_me_ready", None)
        self._me = None

    def _is_admin(self):
        m = self.me()
        return bool(m and m.get("role") == "admin")

    def _deny(self, code, code_str, msg):
        return self._send(code, json.dumps({"ok": False, "code": code_str, "msg": msg},
                                           ensure_ascii=False))

    def _send(self, code, body, ctype="application/json; charset=utf-8",
              cache="no-store", extra=None):
        if isinstance(body, str):
            body = body.encode("utf-8")
        hdr = [("Content-Type", ctype), ("Cache-Control", cache),
               ("X-Content-Type-Options", "nosniff")]
        origin = self._cors_origin()
        if origin:
            hdr.append(("Access-Control-Allow-Origin", origin))
            hdr.append(("Vary", "Origin"))
        if extra:
            hdr.extend(extra)
        # 文本类响应压缩：浏览一页 49KB → 18KB，弱网/远端访问差别很明显
        if (len(body) > 1024
                and "gzip" in (self.headers.get("Accept-Encoding") or "").lower()
                and any(t in ctype for t in ("json", "text/", "javascript",
                                             "svg", "xml", "mpegurl"))
                and not any(k.lower() == "content-encoding" for k, _ in hdr)):
            body = gzip.compress(body, 5)
            hdr.append(("Content-Encoding", "gzip"))
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
        try:
            st = os.stat(full)
        except Exception:
            return self._send(404, b'{"error":"not found"}')
        etag = '"%x-%x"' % (int(st.st_mtime), st.st_size)
        # 304：字体/播放器这些大文件没必要每次重下
        if etag in (self.headers.get("If-None-Match") or ""):
            try:
                self.send_response(304)
                self.send_header("ETag", etag)
                self.send_header("Cache-Control", cache)
                self.send_header("Content-Length", "0")
                self.end_headers()
            except Exception:
                pass
            return
        ext = os.path.splitext(full)[1].lower()
        ctype = MIME.get(ext) or mimetypes.guess_type(full)[0] or "application/octet-stream"
        try:
            with open(full, "rb") as f:
                data = f.read()
        except Exception:
            return self._send(500, b'{"error":"read fail"}',
                              "application/json; charset=utf-8")
        hdr = [("ETag", etag),
               ("Last-Modified", self.date_time_string(st.st_mtime))]
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
               ("Content-Range", "bytes %d-%d/%d" % (start, end, size))]
        origin = self._cors_origin()
        if origin:
            hdr.append(("Access-Control-Allow-Origin", origin))
            hdr.append(("Vary", "Origin"))
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
    def _proxy_stream(self, u, range_header, extra=None):
        """分片透传：读一块发一块。

        以前是「整段读完 → 一次性发出」，首字节要等整个分片下载完；
        播放器在弱网/远距离片源上就会看起来一直在缓冲。
        """
        try:
            r = proxy.open_stream(u, range_header, extra_headers=extra)
        except Exception as e:
            # 播放器拿到 502 只会显示"播放失败"，真正原因得留在日志里
            log("代理上游失败：%s（%s）" % (str(e)[:120], u[:90]))
            return self._send(502, json.dumps({"error": str(e)[:160]},
                                              ensure_ascii=False).encode("utf-8"))
        try:
            status = r.status
            ctype = r.headers.get("Content-Type") or "application/octet-stream"
            length = r.headers.get("Content-Length")
            crange = r.headers.get("Content-Range")
            # 地址不带 .m3u8 但内容其实是清单（?channelId=xxx 这类）：
            # 必须先读出来改写，否则里面的相对分片地址会解析到我们自己的域名下。
            if "mpegurl" in ctype.lower():
                body = r.read(4 * 1024 * 1024)
                r.close()
                txt = body.decode("utf-8", "replace")
                if "#EXTM3U" in txt:
                    txt = proxy.rewrite_playlist(txt, r.geturl() or u)
                    return self._send(200, txt.encode("utf-8"),
                                      "application/vnd.apple.mpegurl", cache="no-store")
                return self._send(200, body, ctype, cache="no-store")
            self.send_response(status)
            self.send_header("Content-Type", ctype)
            self.send_header("Cache-Control", "no-store")
            self.send_header("X-Content-Type-Options", "nosniff")
            if length:
                self.send_header("Content-Length", length)
            else:
                # 没有长度就只能靠关连接标识结束
                self.send_header("Connection", "close")
                self.close_connection = True
            if crange:
                self.send_header("Content-Range", crange)
                self.send_header("Accept-Ranges", "bytes")
            self.end_headers()
            if self.command == "HEAD":
                return
            left = int(length) if length else None
            while True:
                buf = r.read(65536)
                if not buf:
                    break
                self.wfile.write(buf)
                if left is not None:
                    left -= len(buf)
                    if left <= 0:
                        break
            try:
                self.wfile.flush()
            except Exception:
                pass
        except (BrokenPipeError, ConnectionResetError):
            pass                    # 用户切集/退出，正常现象
        except Exception as e:
            log("代理分片中断：%s" % str(e)[:120])
        finally:
            try:
                r.close()
            except Exception:
                pass

    def do_HEAD(self):
        return self.do_GET()

    def do_OPTIONS(self):
        origin = self._cors_origin()
        self.send_response(204)
        if origin:
            self.send_header("Access-Control-Allow-Origin", origin)
            self.send_header("Vary", "Origin")
            self.send_header("Access-Control-Allow-Methods", "GET,POST,OPTIONS")
            self.send_header("Access-Control-Allow-Headers", "Content-Type")
        self.send_header("Content-Length", "0")
        self.end_headers()

    def do_GET(self):
        self._reset_auth()
        p = urllib.parse.urlparse(self.path)
        path = p.path
        qs = {k: v[0] for k, v in urllib.parse.parse_qs(p.query).items()}
        try:
            if (path.startswith("/api/")
                    and not path.startswith("/api/auth/")
                    and not path.startswith("/api/app/")):
                if not self.me():
                    return self._deny(401, "auth", "需要登录")
                if path.startswith(_ADMIN_PREFIX) and not self._is_admin():
                    return self._deny(403, "admin", "只有管理员能做这个")
            if path in ("/tv", "/tv/"):
                # 电视浏览器里手输地址很痛苦，给一个短路径 + 大按钮
                return self._serve_file("tv.html", cache="no-cache")
            if path.startswith("/static/"):
                rel = path[len("/static/"):]
                # 应用代码不缓存：升级后刷新即生效，避免旧 JS 和新 API 打架
                cache = "no-cache" if rel.startswith("app/") else "public, max-age=2592000"
                return self._serve_file(rel, cache=cache)
            if path == "/api/proxy":
                u = qs.get("u") or ""
                if not u:
                    return self._send(400, b'{"error":"missing u"}')
                rng = self.headers.get("Range")
                # 分片流式转发：边下边发，播放器不用等整段下载完
                if not proxy.is_playlist(u):
                    return self._proxy_stream(u, rng)
                try:
                    st, body, ctype, extra = proxy.fetch(u, rng)
                except Exception as e:
                    return self._send(502, json.dumps({"error": str(e)[:160]},
                                                     ensure_ascii=False).encode("utf-8"))
                return self._send(st, body, ctype, cache="no-store",
                                  extra=list(extra.items()))
            if path == "/healthz":
                # 给容器健康检查用：不碰远程源，永远秒回
                return self._send(200, b'{"ok":true}', "application/json; charset=utf-8")
            if path == "/api/music/online/stream":
                # 音频要边下边发（浏览器靠 Range 拖动进度条），走流式通道。
                # 和 /api/proxy 一样必须在 _api_get 之外返回：
                # 那个函数只接受 (code, body, ctype) 三元组。
                u = musicbox.stream_target(qs.get("id") or "")
                if not u:
                    return self._send(400, b'{"error":"bad id"}')
                return self._proxy_stream(u, self.headers.get("Range"),
                                          extra={"Referer": musicbox.REFERER})
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
        if path == "/api/cast/targets":
            me = self.me() or {}
            return _json({"ok": True, "items": cast.targets(exclude=qs.get("id") or me.get("id", ""))})
        if path == "/api/cast/poll":
            return _json({"ok": True, "commands": cast.poll(qs.get("id") or "")})
        if path == "/api/app/latest":
            kind = (qs.get("kind") or "tv").replace("/", "")
            meta = os.path.join(APPS_DIR, kind + ".json")
            if not os.path.exists(meta):
                return _err("NAS 上还没有发布 %s 端的安装包" % kind, 404)
            return self._send(200, open(meta, "rb").read(),
                              "application/json; charset=utf-8")
        if path == "/api/app/download":
            kind = (qs.get("kind") or "tv").replace("/", "")
            apk = os.path.join(APPS_DIR, kind + ".apk")
            if not os.path.exists(apk):
                return _err("NAS 上还没有发布 %s 端的安装包" % kind, 404)
            with open(apk, "rb") as f:
                data = f.read()
            return self._send(200, data, "application/vnd.android.package-archive",
                              extra=[("Content-Disposition",
                                      'attachment; filename="projection-room-%s.apk"' % kind)])
        if path == "/api/auth/state":
            return _json(auth.state(self.me()))
        if path == "/api/profiles":
            return _json({"ok": True, "items": auth.list_profiles()})
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
        if path == "/api/backup":
            # 一键导出：换机 / 重装前先存一份。密码类字段一律留空。
            fav = dbrun("""SELECT kind,key,title,cover,payload,added_at
                           FROM fav ORDER BY added_at DESC""", fetch=True) or []
            his = dbrun("""SELECT kind,key,title,cover,source,ep,url,position,
                           duration,plays,genre,year,updated_at
                           FROM history ORDER BY updated_at DESC""", fetch=True) or []
            prefs = {}
            for k, v in (dbrun("SELECT k,v FROM prefs", fetch=True) or []):
                if k == "music.config":
                    try:
                        c = json.loads(v)
                        if isinstance(c.get("subsonic"), dict):
                            c["subsonic"]["pass"] = ""
                        v = json.dumps(c, ensure_ascii=False)
                    except Exception:
                        v = ""
                prefs[k] = v
            return _json({
                "app": "projection-room", "version": 1, "at": int(time.time()),
                "sources": all_sites() + sources_list("live"),
                "fav": [{"kind": r[0], "key": r[1], "title": r[2], "cover": r[3],
                         "payload": r[4], "at": r[5]} for r in fav],
                "history": [{"kind": r[0], "key": r[1], "title": r[2], "cover": r[3],
                             "source": r[4], "ep": r[5], "url": r[6],
                             "position": r[7], "duration": r[8], "plays": r[9],
                             "genre": r[10], "year": r[11], "at": r[12]} for r in his],
                "prefs": prefs,
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
            if kind == "music":
                # 音乐收藏：payload 里存的是曲目 JSON，取出来直接还前端
                rows = dbrun("""SELECT key,title,cover,payload,added_at FROM fav
                                WHERE profile=? AND kind='music'
                                ORDER BY added_at DESC LIMIT 500""",
                             (self.me()["id"],), fetch=True) or []
                items = []
                for r in rows:
                    t = {}
                    try:
                        t = json.loads(r[3] or "{}")
                    except Exception:
                        t = {}
                    t.setdefault("id", r[0])
                    t.setdefault("title", r[1])
                    t.setdefault("cover", r[2])
                    items.append(t)
                return _json({"kind": "music", "items": items})
            if kind == "fav":
                rows = dbrun("""SELECT kind,key,title,cover,payload,added_at
                                FROM fav WHERE profile=?
                                ORDER BY added_at DESC LIMIT 200""",
                             (self.me()["id"],), fetch=True) or []
                return _json({"kind": "fav", "items": [
                    {"kind": r[0], "key": r[1], "title": r[2], "cover": r[3],
                     "payload": r[4], "at": r[5]} for r in rows]})
            rows = dbrun("""SELECT kind,key,title,cover,source,ep,url,position,
                            duration,plays,genre,year,updated_at
                            FROM history WHERE profile=?
                            ORDER BY updated_at DESC LIMIT 200""",
                         (self.me()["id"],), fetch=True) or []
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
                pg = int(qs.get("pg") or 1)
            except Exception:
                pg = 1
            pg = max(1, pg)
            # 超过上限直接回空：以前会悄悄夹到 30，客户端于是永远拿到"第 30 页"，
            # 越翻越重复，看起来就像分页坏了
            if pg > 30:
                return _json({"items": [], "pg": pg, "cat": cat, "year": "",
                              "area": "", "more": False, "total": 0, "sites": 0})
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
                          "sources": live.source_stats(),
                          "auto": live.auto_status()})
        if path == "/api/live/auto":
            return _json(live.auto_status())
        if path == "/api/sources":
            kind = qs.get("kind") or ""
            rows = all_sites() if kind in ("", "cms") else []
            if kind == "live":
                stats = live.source_stats()
                rows = [dict(s, api_count=stats.get(s["name"], s.get("api_count") or 0))
                        for s in sources_list("live")]
            return _json({"items": rows, "cats": CAT_ORDER,
                          "providers": {"cms": len(active_sites()),
                                        "live": len(live.source_stats())}})
        if path == "/api/sources/discover":
            return _json(discover.status())
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
            try:
                lim = max(1, min(500, int(qs.get("limit") or 120)))
            except Exception:
                lim = 120
            return _json(music.home(limit=lim))
        if path == "/api/music/album":
            try:
                return _json(music.album(qs.get("id") or ""))
            except Exception as e:
                return _err(str(e)[:160], 404)
        if path == "/api/music/search":
            return _json(music.search(qs.get("kw") or ""))
        if path == "/api/music/online/home":
            return _json(music.online_home(fresh=qs.get("fresh") == "1"))
        if path == "/api/music/playlists/cats":
            return _json({"groups": musicbox.categories()})
        if path == "/api/music/playlists/recommend":
            try:
                lim = max(1, min(30, int(qs.get("limit") or 14)))
            except Exception:
                lim = 14
            try:
                return _json({"items": musicbox.recommend(limit=lim)})
            except Exception:
                return _json({"items": []})
        if path == "/api/music/playlists":
            try:
                lim = max(1, min(60, int(qs.get("limit") or 24)))
            except Exception:
                lim = 24
            return _json({"cat": qs.get("cat") or "",
                          "items": musicbox.category_playlists(
                              qs.get("cat") or "", limit=lim,
                              fresh=qs.get("fresh") == "1")})
        if path == "/api/music/playlist":
            try:
                return _json(musicbox.playlist(qs.get("id") or ""))
            except Exception as e:
                return _err("歌单读不到：%s" % str(e)[:120], 502)
        if path == "/api/music/online":
            try:
                lim = max(1, min(50, int(qs.get("limit") or 30)))
            except Exception:
                lim = 30
            return _json(music.online_search(qs.get("kw") or "", limit=lim))
        if path == "/api/music/online/url":
            # 播放时现取，不复用探测阶段的缓存：第三方音源地址带时效
            u = music.online_url(qs.get("id") or "", src=qs.get("src") or "", use_cache=False)
            if not u:
                return _err("取不到播放地址，换一首试试", 404)
            return _json({"url": u})
        if path == "/api/music/lyrics":
            lines = music.lyrics(qs.get("id") or "", qs.get("src") or "",
                                 title=qs.get("title") or "", artist=qs.get("artist") or "")
            return _json({"lines": lines, "count": len(lines)})
        if path == "/api/config/export":
            # 导出一份可分享的配置；?channels=0 不带直播频道（只导源种子）
            doc = config_io.export_bundle(include_channels=qs.get("channels") != "0")
            name = "projection-room-config-%s.json" % time.strftime("%Y%m%d-%H%M")
            return self._send(200, json.dumps(doc, ensure_ascii=False, indent=2).encode("utf-8"),
                              "application/json; charset=utf-8", cache="no-store",
                              extra=[("Content-Disposition",
                                      'attachment; filename="%s"' % name)])
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
                if cache_valid(ck, 6 * 3600):
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
        self._reset_auth()
        path = urllib.parse.urlparse(self.path).path
        try:
            if not path.startswith("/api/auth/"):
                if not self.me():
                    return self._deny(401, "auth", "需要登录")
                if path.startswith(_ADMIN_PREFIX) and not self._is_admin():
                    return self._deny(403, "admin", "只有管理员能做这个")
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

        if path == "/api/cast/register":
            try:
                dev = cast.register(b.get("id") or "", b.get("name") or "",
                                    b.get("kind") or "tv",
                                    self.headers.get("User-Agent") or "")
            except ValueError as e:
                return send({"ok": False, "msg": str(e)})
            return send({"ok": True, "device": dev})
        if path == "/api/cast/send":
            try:
                cast.send(b.get("target") or "", b)
            except KeyError as e:
                return send({"ok": False, "msg": str(e).strip("'")})
            except ValueError as e:
                return send({"ok": False, "msg": str(e)})
            return send({"ok": True})
        if path == "/api/auth/setup":
            try:
                prof, tok = auth.setup(b.get("name") or "管理员", b.get("pin") or "")
            except auth.AuthError as e:
                return send({"ok": False, "msg": e.msg})
            return self._send(200, json.dumps(
                {"ok": True, "token": tok, "me": prof, "profiles": auth.list_profiles()},
                ensure_ascii=False), "application/json; charset=utf-8",
                extra=[_cookie(tok)])
        if path == "/api/auth/login":
            ip = (self.client_address or ("",))[0]
            try:
                # PIN 即身份：不再传账户 id，直接按 PIN 匹配
                prof, tok = auth.login(b.get("pin") or "", ip=ip)
            except auth.AuthError as e:
                return self._send(e.code, json.dumps(
                    {"ok": False, "code": "auth", "msg": e.msg}, ensure_ascii=False),
                    "application/json; charset=utf-8")
            return self._send(200, json.dumps(
                {"ok": True, "token": tok, "me": prof, "profiles": auth.list_profiles()},
                ensure_ascii=False), "application/json; charset=utf-8",
                extra=[_cookie(tok)])
        if path == "/api/auth/tv":
            # 电视端免输 PIN：固定账户「电视」/ 999999
            prof = auth.tv_profile()
            if not prof:
                return self._send(409, json.dumps(
                    {"ok": False, "code": "pin_taken",
                     "msg": "999999 已被其它账户占用，电视需要手动输 PIN"},
                    ensure_ascii=False), "application/json; charset=utf-8")
            tok = auth.issue_token(prof["id"], ua=self.headers.get("User-Agent") or "tv")
            return self._send(200, json.dumps(
                {"ok": True, "token": tok, "me": prof, "profiles": auth.list_profiles()},
                ensure_ascii=False), "application/json; charset=utf-8",
                extra=[_cookie(tok)])
        if path == "/api/auth/touch":
            # 客户端本地还留着 token 但 Cookie 丢了（WebView 重启、清缓存常见）：
            # 让他拿 X-Token 换一次 Set-Cookie，媒体元素（video/img/audio）才能继续带认证
            tok = self._token()
            if not tok or not self.me():
                return self._send(401, json.dumps(
                    {"ok": False, "code": "auth"}, ensure_ascii=False),
                    "application/json; charset=utf-8")
            return self._send(200, json.dumps({"ok": True}, ensure_ascii=False),
                              "application/json; charset=utf-8", extra=[_cookie(tok)])
        if path == "/api/auth/logout":
            auth.logout(self._token())
            return self._send(200, json.dumps({"ok": True}, ensure_ascii=False),
                              "application/json; charset=utf-8",
                              extra=[("Set-Cookie", "pr_token=; Path=/; Max-Age=0; HttpOnly")])
        if path == "/api/profiles/save":
            try:
                if b.get("id"):
                    prof = auth.update_profile(b["id"], name=b.get("name"),
                                               pin=b.get("pin") if "pin" in b else None,
                                               role=b.get("role"), avatar=b.get("avatar"))
                else:
                    prof = auth.create_profile(b.get("name") or "", b.get("pin") or "",
                                               role=b.get("role") or "member",
                                               avatar=b.get("avatar") or "")
            except auth.AuthError as e:
                return send({"ok": False, "msg": e.msg})
            return send({"ok": True, "item": prof, "items": auth.list_profiles()})
        if path == "/api/profiles/delete":
            try:
                auth.delete_profile(b.get("id") or "")
            except auth.AuthError as e:
                return send({"ok": False, "msg": e.msg})
            return send({"ok": True, "items": auth.list_profiles()})
        if path == "/api/config/import":
            doc = b.get("config") or b.get("doc")
            if doc is None:
                return send({"ok": False, "msg": "没有收到配置内容"})
            mode = b.get("mode") or "merge"
            sections = b.get("sections") or None
            try:
                if b.get("dryRun"):
                    return send(config_io.plan_import(doc, mode=mode, sections=sections))
                return send(config_io.apply_import(doc, mode=mode, sections=sections))
            except config_io.ConfigError as e:
                return self._send(e.code, json.dumps(
                    {"ok": False, "msg": e.msg}, ensure_ascii=False),
                    "application/json; charset=utf-8")

        if path == "/api/history":
            key = b.get("key") or ""
            if not key:
                return send({"ok": False, "msg": "缺 key"})
            touch_history(b.get("kind") or "online", key, profile=self.me()["id"],
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
                            b.get("cover", ""), b.get("payload", ""),
                            profile=self.me()["id"])
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
            check = bool(b.get("check"))
            n = live.refresh_live()
            if not check:
                return send({"ok": True, "msg": "已刷新 %d 个频道" % n})
            c = live.check_channels("", limit=3000, workers=24, timeout=5)
            pruned = live.prune_dead(3)
            live.pref_write("live.auto.last", str(int(time.time())))
            live.pref_set_json("live.auto.result", {
                "at": int(time.time()), "fetched": n, "checked": c["checked"],
                "ok": c["ok"], "dead": c["dead"], "pruned": pruned, "manual": True,
            })
            return send({"ok": True, "checked": c, "pruned": pruned,
                         "msg": "刷新 %d，检测 %d，可用 %d，清理 %d" % (
                             n, c["checked"], c["ok"], pruned)})
        if path == "/api/live/auto":
            live.set_auto(bool(b.get("enabled")))
            return send({"ok": True, "status": live.auto_status()})
        if path == "/api/live/collect":
            started = live.collect_async()
            return send({"ok": True, "started": started,
                         "msg": "已开始汇总，完成后会自动刷新列表" if started else "正在汇总中，请稍候"})
        if path == "/api/live/seeds":
            seeds = live.set_seeds(b.get("seeds") or [])
            return send({"ok": True, "seeds": seeds})
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
        if path == "/api/sources/discover":
            started = discover.run_async()
            return send({"ok": True, "started": started,
                         "msg": "已开始发现，约需 1 分钟" if started else "正在发现中"})
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
