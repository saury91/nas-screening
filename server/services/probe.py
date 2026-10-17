# -*- coding: utf-8 -*-
"""探测某条线路的真实清晰度。

采集站的 vod_remarks 大多只写「HD中字」，区分不出 720/1080/4K。
但很多源的 m3u8 是**多码率主列表**，带真实分辨率：

    #EXT-X-STREAM-INF:PROGRAM-ID=1,BANDWIDTH=800000,RESOLUTION=1080x608
    3000k/hls/mixed.m3u8

拉一次主列表就能拿到，比任何猜测都准。单码率的子列表没有这个信息，
只能返回空 —— 宁可不标，也不乱标。
"""
import re
import threading
import time

from ..net import raw_get

_TTL = 900          # 同一地址 15 分钟内不重复探测
_CACHE = {}         # url -> (ts, q, res)
_LOCK = threading.Lock()

_RES = re.compile(r"RESOLUTION=(\d+)x(\d+)", re.I)


def _label(w, h):
    """按**像素总数**分档。

    只看高度会把宽银幕（1920x800）判低；只看宽度又会把 1280x720 当成 1080P。
    像素数最接近人眼感受：
      3840x2160=8.3M / 1920x1080=2.1M / 1920x800=1.5M / 1280x720=0.92M / 1080x608=0.66M
    """
    px = max(0, w) * max(0, h)
    if px >= 6_000_000:
        return "4K"
    if px >= 1_400_000:
        return "1080P"
    if px >= 550_000:
        return "720P"
    if px >= 200_000:
        return "480P"
    return ""


def probe(url, timeout=3):
    """返回 {'q': '1080P', 'res': '1080x608'}；拿不到就两个都空。"""
    url = (url or "").strip()
    if not url.startswith("http"):
        return {"q": "", "res": ""}

    now = time.time()
    with _LOCK:
        hit = _CACHE.get(url)
    if hit and now - hit[0] < _TTL:
        return {"q": hit[1], "res": hit[2]}

    q, res = "", ""
    try:
        st, body, _, _ = raw_get(url, timeout=timeout)
        txt = ""
        for cs in ("utf-8", "gb18030"):
            try:
                txt = body.decode(cs)
                break
            except Exception:
                continue
        if not txt:
            txt = body.decode("utf-8", "replace")
        # 只认主列表里的 RESOLUTION，按高度取最大
        found = [(int(w), int(h)) for w, h in _RES.findall(txt)]
        if found:
            w, h = max(found, key=lambda t: t[0] * t[1])
            q = _label(w, h)
            res = "%dx%d" % (w, h)
    except Exception:
        pass

    with _LOCK:
        _CACHE[url] = (now, q, res)
    return {"q": q, "res": res}
