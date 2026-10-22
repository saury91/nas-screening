# -*- coding: utf-8 -*-
"""BT 磁力资源：站点搜索 + 详情页磁力提取 + 做种数探测。

站点地址来自 MAGNET_SITE 环境变量，仓库不预置任何 BT 资源站；
没配就整块关闭（搜索直接返回空数组，不访问任何上游）。
"""
import base64
import concurrent.futures
import html as html_mod
import json
import re
import urllib.parse
import urllib.request

from ..config import MAGNET_SITE, MAX_DETAIL, TIMEOUT, TRACKERS, UA
from ..net import http_get


# 全部经 NAS 实测 UDP 可达；注入磁力能明显提高找到 peer 的成功率
SCRAPE_HOSTS = [
    ("tracker.opentrackr.org", 1337),
    ("open.tracker.cl", 1337),
    ("tracker.dler.org", 6969),
    ("opentracker.xyz", 80),
]


# ───────────────────────── HTTP 工具 ─────────────────────────
def strip_tags(s):
    s = re.sub(r"<script.*?</script>", " ", s, flags=re.S | re.I)
    s = re.sub(r"<[^>]+>", " ", s)
    return re.sub(r"\s+", " ", html_mod.unescape(s)).strip()


def btih_to_hex(h):
    h = h.strip()
    if len(h) == 40:
        return h.lower()
    if len(h) == 32:
        try:
            return base64.b32decode(h.upper()).hex()
        except Exception:
            return h
    return h


# ───────────────────────── 磁力工具 ─────────────────────────
def build_magnet(ih, name=None):
    """裸磁力体验很差（qBittorrent 里只显示一串 hash）。
    这里补上 dn（显示名）与 tr（tracker 列表）。"""
    m = "magnet:?xt=urn:btih:" + ih
    if name:
        m += "&dn=" + urllib.parse.quote(name[:120], safe="")
    m += "".join("&tr=" + urllib.parse.quote(t, safe="") for t in TRACKERS[:8])
    return m


def scrape_health(ih_hex):
    """查种子健康度。并发问几个 tracker，取做得最多的那个结果。"""
    try:
        ih = bytes.fromhex(ih_hex)
    except Exception:
        return None
    q = urllib.parse.quote(ih, safe="")

    def one(host, port):
        try:
            req = urllib.request.Request(
                "http://%s:%d/scrape?info_hash=%s" % (host, port, q),
                headers={"User-Agent": "qBittorrent/5.1.4"})
            with urllib.request.urlopen(req, timeout=6) as r:
                d = r.read()
            if b"completei" not in d:
                return None

            def num(key):
                s = d.find(key)
                if s < 0:
                    return None
                s += len(key)
                e = d.find(b"e", s)
                try:
                    return int(d[s:e])
                except Exception:
                    return None

            return {"s": num(b"completei"), "c": num(b"downloadedi"), "l": num(b"incompletei")}
        except Exception:
            return None

    best = None
    with concurrent.futures.ThreadPoolExecutor(max_workers=4) as ex:
        for r in ex.map(lambda a: one(*a), SCRAPE_HOSTS):
            if not r:
                continue
            if best is None or (r.get("s") or 0) > (best.get("s") or 0):
                best = r
    return best


def parse_tags(text):
    """从标题里抽出画质/来源/编码标签"""
    t = text or ""
    tags = []
    m = re.search(r"(2160p|4K|1080p|720p|480p|8K)", t, re.I)
    if m:
        v = m.group(1).upper()
        v = "4K" if v in ("2160P", "4K") else v
        tags.append({"t": v, "k": "res"})
    for pat, label in [(r"REMUX", "REMUX"), (r"BluRay|BDRip|Blu-ray", "蓝光"),
                       (r"WEB-DL", "WEB-DL"), (r"WEBRip", "WEBRip"),
                       (r"HDTV", "HDTV"), (r"DVDRip", "DVDRip")]:
        if re.search(pat, t, re.I):
            tags.append({"t": label, "k": "src"})
            break
    if re.search(r"x265|H\.?265|HEVC", t, re.I):
        tags.append({"t": "H265", "k": "codec"})
    elif re.search(r"x264|H\.?264|AVC", t, re.I):
        tags.append({"t": "H264", "k": "codec"})
    if re.search(r"HDR10|HDR|Dolby\s*Vision|杜比视界", t, re.I):
        tags.append({"t": "HDR", "k": "hdr"})
    if re.search(r"中字|简繁|简中|繁中|中英|国语|双语|CHS|CHT", t, re.I):
        tags.append({"t": "中字", "k": "sub"})
    if re.search(r"修复版|加长版|导演剪辑|重制|合集|全\d+集", t):
        tags.append({"t": "合集/修复", "k": "note"})
    return tags


def clean_name(s):
    """站点常把广告塞进磁力的 dn 里，清掉"""
    if not s:
        return ""
    s = re.sub(r"地址发布页.*$", "", s)
    s = re.sub(r"收藏不迷路.*$", "", s)
    s = re.sub(r"(?:www|http)[.:][a-zA-Z0-9./-]+", "", s, flags=re.I)
    s = re.sub(r"电影港|新版6v|旧版66影视|6v电影|备用地址|最新地址", "", s)
    s = re.sub(r"[\[\]（）()【】]", " ", s)
    s = re.sub(r"\s{2,}", " ", s)
    return s.strip(" .-_·|")


def guess_category(title):
    t = title or ""
    if re.search(r"第\s*\d+\s*[集话期]|S\d{1,2}E\d{1,3}|全\s*\d+\s*集|更新至", t, re.I):
        return "剧集"
    if re.search(r"动漫|动画|剧场版|TV版", t):
        return "动漫"
    return "电影"


# ───────────────────────── 站点 ─────────────────────────
def search_site(kw):
    base = MAGNET_SITE
    if not base:
        return []
    form = urllib.parse.urlencode({
        "show": "title", "tempid": "1", "tbname": "article",
        "mid": "1", "dopost": "search", "keyboard": kw, "submit": "",
    }).encode()
    html = http_get(base + "/e/search/so.php", referer=base + "/", data=form)
    if "没有搜索到" in html:
        return []
    links = re.findall(r'href="(/(?:[a-z]+pian|dianshiju|zongyi|donghuapian)/\d+\.html)"', html)
    if not links:
        links = re.findall(r'href="(/[a-z]+/\d+\.html)"', html)
    out, seen = [], set()
    for l in links:
        if l in seen:
            continue
        seen.add(l)
        if len(seen) > MAX_DETAIL:
            break
        out.append({"url": base + l, "source": "磁力站"})
    return out


SITES = [{"name": "磁力站", "fn": search_site}] if MAGNET_SITE else []


def enrich(item):
    """抓详情页 → 抽出全部磁力（保留原始的 dn 文件名与 tr tracker）"""
    try:
        html = http_get(item["url"], referer=MAGNET_SITE + "/")
    except Exception as e:
        item["error"] = str(e)[:50]
        item["torrents"] = []
        return item
    plain = strip_tags(html)
    t = re.search(r"<title>(.*?)</title>", html, re.S)
    item["title"] = html_mod.unescape(t.group(1)).split("-")[0].strip() if t else item["url"]

    # 详情页正文里的规格信息（◎ 是这类站点常用的字段标记）
    def field(*names):
        for n in names:
            m = re.search(r"[◎【]?\s*" + n + r"\s*[】]?\s*[\u3000\s]*([^\u3000\s<]{1,22})", plain)
            if m:
                v = m.group(1).strip()
                if v and v not in ("暂无", "-", "无"):
                    return v
        return ""
    item["vsize"] = field("视频尺寸", "分辨率")
    item["vfmt"] = field("文件格式", "格式")
    item["flen"] = field("片.{0,4}长", "时长")
    sz = re.search(r"[◎【]?\s*文件大小\s*[】]?\s*[\u3000\s]*([\d.]+\s*[GMKT]B)", plain, re.I)
    if not sz:
        sz = re.search(r"([\d.]+\s*[GM]B)", plain[:6000])
    item["size"] = sz.group(1).replace(" ", "") if sz else ""

    # 完整磁力（关键：保留 dn 与 tr，不要只取 hash）
    raw = re.findall(r"magnet:\?xt=urn:btih:[^\"'<>\s]+", html)
    seen, tss = set(), []
    for m in raw:
        m = html_mod.unescape(m).rstrip("&").rstrip("\\")
        h = re.search(r"btih:([A-Za-z0-9]+)", m)
        if not h:
            continue
        hx = btih_to_hex(h.group(1))
        if hx in seen:
            continue
        seen.add(hx)
        dn = ""
        q = m.split("?", 1)
        if len(q) > 1:
            try:
                dn = (urllib.parse.parse_qs(q[1]).get("dn") or [""])[0]
            except Exception:
                dn = ""
        tss.append({"hash": hx, "magnet": m, "name": dn})
    # 简介 / 演员 / 导演（详情页展示用）
    m = re.search(r"(?:剧情|简介)[:：]?\s*([^<]{20,600})", plain)
    item["desc"] = (m.group(1).strip()[:400] if m else "")
    item["torrents"] = tss
    pans = re.findall(r"https://pan\.quark\.cn/s/[a-zA-Z0-9]+", html)
    item["pans"] = list(dict.fromkeys(pans))[:3]
    return item


def do_search(kw):
    """把每个磁力展开成独立结果项 —— 同一部片的不同版本可以逐条对比挑选"""
    kws = kw.strip()
    if not kws:
        return []
    raw = []
    for s in SITES:
        try:
            raw.extend(s["fn"](kws))
        except Exception:
            pass
    if not raw:
        return []
    with concurrent.futures.ThreadPoolExecutor(max_workers=6) as ex:
        details = list(ex.map(enrich, raw))

    items = []
    used = set()
    for d in details:
        movie = d.get("title", "")
        for t in d.get("torrents", []):
            if t["hash"] in used:
                continue
            used.add(t["hash"])
            fname = clean_name(t.get("name") or "")
            display = fname or movie
            if d.get("vsize") and d["vsize"].lower() not in display.lower():
                display = display + "  [" + d["vsize"] + "]"
            mag = t["magnet"]
            if "&tr=" not in mag:
                mag = build_magnet(t["hash"], fname or movie)
            items.append({
                "hash": t["hash"],
                "title": display[:110],
                "movie": movie,
                "fname": fname,
                "magnet": mag,
                "tags": parse_tags(fname + " " + movie + " " + d.get("vsize", "")),
                "size": d.get("size", ""),
                "vsize": d.get("vsize", ""),
                "vfmt": d.get("vfmt", ""),
                "flen": d.get("flen", ""),
                "source": d.get("source", ""),
                "url": d.get("url", ""),
                "pans": d.get("pans", []),
                "category": guess_category(movie),
                "seeders": -1,
                "leechers": -1,
                "finished": -1,
            })
    if not items:
        return []

    def health(it):
        hh = scrape_health(it["hash"]) or {}
        s = hh.get("s")
        it["seeders"] = s if s is not None else -1
        it["leechers"] = hh.get("l") if hh.get("l") is not None else -1
        it["finished"] = hh.get("c") if hh.get("c") is not None else -1
        return it

    with concurrent.futures.ThreadPoolExecutor(max_workers=10) as ex:
        items = list(ex.map(health, items))

    items.sort(key=lambda x: (-(x.get("seeders") if x.get("seeders", -1) >= 0 else -1),
                              -(x.get("finished") or -1),
                              x.get("title", "")))
    return items
