# -*- coding: utf-8 -*-
"""HTTP 抓取与二进制读取。

采集站 / 磁力站 / 直播源都用同一套 UA 与超时，方便统一限流和排障。
"""
import gzip
import urllib.parse
import urllib.request
import zlib

from .config import UA, TIMEOUT


def idna(url):
    """中文域名转 punycode —— urllib 只认 ASCII 主机名。
    TVBox 生态里大量用中文域名（http://www.饭太硬.net/tv），不转直接失败。
    """
    try:
        p = urllib.parse.urlsplit(url)
        host = p.hostname or ""
        if not host or host.isascii():
            return url
        ascii_host = host.encode("idna").decode("ascii")
        netloc = ascii_host
        if p.port:
            netloc += ":%d" % p.port
        if p.username:
            auth = p.username + (":" + p.password if p.password else "") + "@"
            netloc = auth + netloc
        return urllib.parse.urlunsplit(
            (p.scheme, netloc, p.path, p.query, p.fragment))
    except Exception:
        return url


def http_get(url, referer=None, extra=None, data=None, timeout=TIMEOUT):
    """GET 一个页面，返回解码后的文本（自动处理 gzip）。"""
    hdr = {"User-Agent": UA,
           "Accept": "text/html,application/xhtml+xml,application/json,*/*",
           "Accept-Language": "zh-CN,zh;q=0.9,en;q=0.8"}
    if referer:
        hdr["Referer"] = referer
    if extra:
        hdr.update(extra)
    req = urllib.request.Request(idna(url), headers=hdr, data=data)
    with urllib.request.urlopen(req, timeout=timeout) as r:
        raw = r.read()
        enc = (r.headers.get("Content-Encoding") or "").lower()
        if enc == "gzip":
            raw = gzip.decompress(raw)
        elif enc == "deflate":
            try:
                raw = zlib.decompress(raw)
            except Exception:
                raw = zlib.decompress(raw, -zlib.MAX_WBITS)
        for cs in ("utf-8-sig", "utf-8", "gb18030"):
            try:
                return raw.decode(cs)
            except Exception:
                continue
        return raw.decode("utf-8", "replace")


def raw_get(url, ref=None, timeout=15, extra=None):
    """返回 (status, body_bytes, headers_dict, final_url)。流媒体探测用。"""
    hdr = {"User-Agent": UA, "Accept": "*/*"}
    if ref:
        hdr["Referer"] = ref
    if extra:
        hdr.update(extra)
    req = urllib.request.Request(idna(url), headers=hdr)
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return r.status, r.read(), dict(r.headers), r.geturl()


# 兼容旧调用名
_raw = raw_get


def read_text(url, timeout=25):
    """按 UTF-8 读文本（直播源列表等）。"""
    st, body, _, _ = raw_get(url, timeout=timeout)
    for cs in ("utf-8-sig", "utf-8", "gb18030"):
        try:
            return body.decode(cs)
        except Exception:
            continue
    return body.decode("utf-8", "replace")


def urljoin(base, path):
    return urllib.parse.urljoin(base, path)
