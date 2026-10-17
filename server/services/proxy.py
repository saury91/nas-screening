# -*- coding: utf-8 -*-
"""HLS 同源代理。

为什么需要：采集站的 m3u8 大多没有 CORS 头，浏览器直连会被拦，
表现为「线路播不动」。这里把清单和分片都代理到同源，
顺便绕过 Referer 校验与混合内容限制。

只代理 http/https，拒绝回环与本机元数据地址，避免被片源当成内网探针。
"""
import ipaddress
import re
import socket
import urllib.error
import urllib.parse
import urllib.request

from ..net import UA, raw_get

ALLOW_SCHEMES = ("http", "https")
_GUARDED = {}          # host -> 校验时间，避免每个分片都做一次 DNS
_GUARD_TTL = 600


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
        if ip.is_loopback or ip.is_link_local or ip.is_unspecified:
            raise ValueError("拒绝代理本机地址")
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


def fetch(url, range_header=None, timeout=30):
    """返回 (status, body, ctype, extra_headers)。

    清单会被改写；分片原样透传，保留 Range 支持。
    """
    _guard(url)
    hdr = {"User-Agent": UA, "Accept": "*/*"}
    ref = "%s://%s/" % (urllib.parse.urlparse(url).scheme, urllib.parse.urlparse(url).netloc)
    hdr["Referer"] = ref
    if range_header:
        hdr["Range"] = range_header

    req = urllib.request.Request(url, headers=hdr)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            body = r.read()
            status = r.status
            ctype = r.headers.get("Content-Type") or ""
            final = r.geturl()
            crange = r.headers.get("Content-Range")
    except urllib.error.HTTPError as e:
        return e.code, b"", "text/plain", {}
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
