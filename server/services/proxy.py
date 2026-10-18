# -*- coding: utf-8 -*-
"""HLS 同源代理。

为什么需要：采集站的 m3u8 大多没有 CORS 头，浏览器直连会被拦，
表现为「线路播不动」。这里把清单和分片都代理到同源，
顺便绕过 Referer 校验与混合内容限制。

只代理 http/https，拒绝回环与本机元数据地址，避免被片源当成内网探针。
"""
import http.client
import ipaddress
import re
import socket
import ssl
import threading
import urllib.error
import urllib.parse
import urllib.request

from ..config import PROXY_ALLOW_PRIVATE
from ..net import UA, idna, raw_get

ALLOW_SCHEMES = ("http", "https")
MAX_BODY = 24 * 1024 * 1024      # 单个分片上限，防止一个坏源把内存吃光
_GUARDED = {}          # host -> 校验时间，避免每个分片都做一次 DNS
_GUARD_TTL = 600

# ── 上游连接池 ────────────────────────────────────────────────
# 一个 3 秒的分片如果每次都新建 TLS 连接，握手 + 慢启动的开销比传输本身还大
# （实测同一条片源：新建连接 0.43s，复用连接 0.10s）。
# 这里按 host 维护少量空闲连接，HTTP/1.1 keep-alive 复用。
_POOL = {}
_POOL_LOCK = threading.Lock()
_POOL_MAX = 4                  # 每个 host 最多留 4 条空闲连接
_POOL_IDLE = 45                # 空闲超过 45 秒就丢弃：服务端 keep-alive 通常会先关
_SSL_CTX = ssl.create_default_context()


def _conn_key(scheme, host, port):
    return (scheme, host, port or (443 if scheme == "https" else 80))


def _new_conn(key, timeout=30):
    scheme, host, port = key
    if scheme == "https":
        return http.client.HTTPSConnection(host, port, timeout=timeout, context=_SSL_CTX)
    return http.client.HTTPConnection(host, port, timeout=timeout)


def _take(key, timeout=30):
    """取一条空闲连接；闲置太久的不给（上游多半已经关了）。"""
    import time
    with _POOL_LOCK:
        lst = _POOL.get(key)
        while lst:
            ts, conn = lst.pop()
            if time.time() - ts < _POOL_IDLE:
                return conn
            try:
                conn.close()
            except Exception:
                pass
    return _new_conn(key, timeout)


def _put(key, conn):
    import time
    with _POOL_LOCK:
        lst = _POOL.setdefault(key, [])
        if len(lst) < _POOL_MAX:
            lst.append((time.time(), conn))
            return
    try:
        conn.close()
    except Exception:
        pass


class Upstream:
    """一条已建立的上游响应；读完后连接回池，没读完就关掉。"""

    def __init__(self, key, conn, resp, final=""):
        self.key = key
        self.conn = conn
        self.resp = resp
        self.status = resp.status
        self.headers = resp.headers
        self.final = final

    def read(self, n=65536):
        return self.resp.read(n)

    def geturl(self):
        return self.final

    def close(self):
        reusable = False
        try:
            reusable = self.resp.isclosed() and not self.resp.will_close
        except Exception:
            reusable = False
        if reusable:
            _put(self.key, self.conn)
        else:
            try:
                self.conn.close()
            except Exception:
                pass


def _guard_retry(url):
    """DNS 偶发抖动会让播放直接 502，这里多给一次机会。"""
    try:
        return _guard(url)
    except ValueError:
        import time
        time.sleep(0.25)
        return _guard(url)


def _open(url, range_header=None, timeout=30, extra_headers=None):
    """打开上游请求：复用连接、跟随重定向，连接过期时重建一次。"""
    cur = url
    for _hop in range(6):
        _guard_retry(cur)
        p = urllib.parse.urlsplit(idna(cur))
        key = _conn_key(p.scheme, p.hostname, p.port)
        target = p.path + (("?" + p.query) if p.query else "")
        hdr = {"User-Agent": UA, "Accept": "*/*",
               "Referer": "%s://%s/" % (p.scheme, p.netloc)}
        # 自定义头只加在第一跳：重定向到 CDN 之后再带原始站点的 Referer
        # 有可能被对方判成盗链，反而失败。
        if extra_headers and _hop == 0:
            hdr.update(extra_headers)
        if range_header:
            hdr["Range"] = range_header

        resp = None
        for attempt in range(2):
            # 第二次一定新建连接：池里那条可能本身就是坏的，
            # 再拿一条空闲的还是会失败。
            conn = _take(key, timeout) if attempt == 0 else _new_conn(key, timeout)
            try:
                conn.request("GET", target, headers=hdr)
                resp = conn.getresponse()
                break
            except Exception:
                # 池里的连接可能已经被上游关掉了（keep-alive 超时），
                # 换一条新的再试一次。这里不再挑异常类型：
                # 漏掉一种就会变成「偶发 502」，排查成本比多一次重试高得多。
                try:
                    conn.close()
                except Exception:
                    pass
                if attempt:
                    raise
        if resp is None:
            raise RuntimeError("上游连接失败")

        if resp.status in (301, 302, 303, 307, 308):
            loc = resp.headers.get("Location") or ""
            try:
                resp.read()
            except Exception:
                pass
            up = Upstream(key, conn, resp, cur)
            up.close()
            if not loc:
                raise RuntimeError("上游重定向缺少 Location")
            cur = urllib.parse.urljoin(cur, loc)
            continue
        return Upstream(key, conn, resp, cur)
    raise RuntimeError("重定向次数过多")


def _guard(url):
    p = urllib.parse.urlparse(url)
    if p.scheme not in ALLOW_SCHEMES or not p.hostname:
        raise ValueError("只允许 http/https 地址")
    import time
    hit = _GUARDED.get(p.hostname)
    if hit and time.time() - hit < _GUARD_TTL:
        return p
    try:
        infos = socket.getaddrinfo(p.hostname, p.port or (443 if p.scheme == "https" else 80))
    except Exception:
        raise ValueError("域名解析失败")
    for info in infos:
        ip = ipaddress.ip_address(info[4][0])
        if ip.is_loopback or ip.is_link_local or ip.is_unspecified or ip.is_reserved:
            raise ValueError("拒绝代理本机地址")
        if ip.is_private and not PROXY_ALLOW_PRIVATE:
            raise ValueError("拒绝代理内网地址（确有内网源可设 PROXY_ALLOW_PRIVATE=1）")
    if len(_GUARDED) > 512:
        _GUARDED.clear()
    _GUARDED[p.hostname] = time.time()
    return p


def _proxy_url(abs_url):
    return "/api/proxy?u=" + urllib.parse.quote(abs_url, safe="")


def rewrite_playlist(text, base_url):
    """把清单里的相对地址、密钥、子清单全部换成本机代理地址。"""
    out = []
    for line in text.splitlines():
        s = line.strip()
        if not s:
            out.append(line)
            continue
        if s.startswith("#"):
            # EXT-X-KEY / EXT-X-MAP / EXT-X-MEDIA 里的 URI="..."
            m = re.search(r'URI="([^"]+)"', s)
            if m:
                abs_u = urllib.parse.urljoin(base_url, m.group(1))
                s = s.replace(m.group(0), 'URI="%s"' % _proxy_url(abs_u))
            out.append(s)
        else:
            abs_u = urllib.parse.urljoin(base_url, s)
            out.append(_proxy_url(abs_u))
    return "\n".join(out) + "\n"


def is_playlist(url):
    return ".m3u8" in url.lower()


def open_stream(url, range_header=None, timeout=30, extra_headers=None):
    """给分片用：返回未读取的响应对象，调用方边读边写给浏览器。

    以前的写法会把整段读进内存再一次性发出，播放器要等完
    整个分片下载才开始收数据；配合连接复用后首字节明显更早。
    """
    return _open(url, range_header, timeout, extra_headers)


def fetch(url, range_header=None, timeout=30):
    """返回 (status, body, ctype, extra_headers)。清单会被改写。

    分片请用 open_stream 流式转发，不要走这里。
    """
    try:
        up = _open(url, range_header, timeout)
        try:
            status = up.status
            ctype = up.headers.get("Content-Type") or ""
            final = up.geturl() or url
            crange = up.headers.get("Content-Range")
            if status >= 400:
                return status, b"", "text/plain", {}
            chunks, total = [], 0
            while True:
                buf = up.read(65536)
                if not buf:
                    break
                total += len(buf)
                if total > MAX_BODY:
                    raise RuntimeError("分片超过 %dMB，已拒绝" % (MAX_BODY // 1048576))
                chunks.append(buf)
            body = b"".join(chunks)
        finally:
            up.close()
    except Exception as e:
        raise RuntimeError("片源连不上：%s" % str(e)[:80])

    if is_playlist(url) or "mpegurl" in ctype.lower():
        try:
            txt = body.decode("utf-8")
        except Exception:
            txt = body.decode("gb18030", "replace")
        txt = rewrite_playlist(txt, final)
        extra = {}
        return 200, txt.encode("utf-8"), "application/vnd.apple.mpegurl", extra

    extra = {}
    if crange:
        extra["Content-Range"] = crange
        extra["Accept-Ranges"] = "bytes"
    return status, body, ctype or "application/octet-stream", extra
