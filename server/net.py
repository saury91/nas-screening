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
    """把 URL 变成 urllib 能直接请求的 ASCII 形式。

    两件事：
    1. 中文域名转 punycode —— urllib 只认 ASCII 主机名。
       TVBox 生态里大量用中文域名（http://www.饭太硬.net/tv），不转直接失败。
    2. 路径/查询里的中文按 UTF-8 百分号编码 —— 采集站的播放地址经常是
       `/video/shaqiu/HD中字/index.m3u8` 这种，不转的话 urllib 组装请求行时
       会抛 UnicodeEncodeError，表现为「代理 502、播放器一直缓冲」。
    """
    try:
        p = urllib.parse.urlsplit(url)
        host = p.hostname or ""
        netloc = host
        if host and not host.isascii():
            netloc = host.encode("idna").decode("ascii")
        if p.port:
            netloc += ":%d" % p.port
        if p.username:
            auth = p.username + (":" + p.password if p.password else "") + "@"
            netloc = auth + netloc
        # 已经编码过的 %XX 保留，不要二次编码
        path = urllib.parse.quote(p.path, safe="/%:@!$&'()*+,;=-._~")
        query = urllib.parse.quote(p.query, safe="=&%:@!$'()*+,;/?-._~")
        if not netloc:
            return url
        return urllib.parse.urlunsplit(
            (p.scheme, netloc, path, query, p.fragment))
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
