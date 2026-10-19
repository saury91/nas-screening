# -*- coding: utf-8 -*-
"""苹果 CMS（MacCMS）采集协议适配。

目前覆盖两种最常见的接口：
  * type=0  XML  : ?ac=videolist&wd=片名  -> <rss><list><video>
  * type=1  JSON : ?ac=detail&wd=片名     -> {"list":[{vod_name,...}]}
两种都先归一成「vod_* 字段字典」，后面的合并逻辑完全复用。
"""
import concurrent.futures
import json
import re
import urllib.parse
import urllib.request
import xml.etree.ElementTree as ET

from ..config import UA, TIMEOUT
from ..text import (_clean_desc, _desc_better, _extract_meta, _join_names,
                    _norm_key, _relevance, _row_meta, _score_max, _zh_count)


def fetch_vod(site, params, timeout=12):
    """按参数拉一个采集站，统一返回 vod 字段字典列表。

    type=0（XML）与 type=1（JSON）在这里分流，上层完全不用关心。
    """
    api = (site.get("api") or "").strip()
    if not api.startswith("http"):
        return []
    sep = "&" if "?" in api else "?"
    url = api + sep + params
    try:
        req = urllib.request.Request(url, headers={
            "User-Agent": UA, "Accept": "application/json,text/plain,*/*"})
        with urllib.request.urlopen(req, timeout=timeout) as r:
            raw = r.read()
    except Exception:
        return []
    txt = None
    for cs in ("utf-8", "gb18030"):
        try:
            txt = raw.decode(cs)
            break
        except Exception:
            continue
    if txt is None:
        txt = raw.decode("utf-8", "replace")
    if txt.lstrip().startswith("<"):
        return _parse_xml(txt)
    try:
        d = json.loads(txt)
    except Exception:
        return []
    if isinstance(d, dict):
        lst = d.get("list") or d.get("data") or []
    elif isinstance(d, list):
        lst = d
    else:
        lst = []
    return [x for x in lst if isinstance(x, dict)]


def _parse_xml(txt):
    """把 XML 采集接口的 <video> 转成与 JSON 接口一致的 vod_* 结构。"""
    try:
        root = ET.fromstring(txt)
    except Exception:
        return []
    out = []
    for v in root.iter("video"):
        def t(tag):
            e = v.find(tag)
            return (e.text or "").strip() if e is not None and e.text else ""
        play = []
        for dd in v.findall(".//dd"):
            if dd.text and dd.text.strip():
                play.append(dd.text.strip())
        out.append({
            "vod_name": t("name"),
            "vod_year": t("year"),
            "vod_pic": t("pic"),
            "type_name": t("type"),
            "vod_remarks": t("note") or t("state"),
            "vod_actor": t("actor"),
            "vod_director": t("director"),
            "vod_area": t("area"),
            "vod_lang": t("lang"),
            "vod_score": t("score"),
            "vod_content": t("des") or t("content"),
            "vod_play_url": "$$$".join(play),
        })
    return out


def _cms_query(site, kw, timeout=12):
    """搜一个采集站，返回原始条目列表（最多 8 条，控制聚合体积）"""
    return fetch_vod(site, "ac=detail&wd=" + urllib.parse.quote(kw), timeout)[:8]


def _parse_play_url(pu):
    """把 vod_play_url 解析成 [{name,url}]

    各家的格式其实不一样，踩过的坑：
      标准    : 第01集$https://a/index.m3u8
      非凡/电影天堂: https://share页$$$HD中字$https://b/index.m3u8
    所以不能简单按第一个 $ 切 —— 要「取出本段里所有 http 地址，取最后一个」，
    那才是真正的播放地址。（早期版本按 $ 切，导致这批源全被误判为「解析型」丢掉）
    """
    eps = []
    for seg in (pu or "").split("#"):
        seg = seg.strip()
        if not seg:
            continue
        URL_RE = r"https?://[^\s$#'\"<>]+"
        us = re.findall(URL_RE, seg)
        if not us:
            continue
        url = us[-1]
        if not re.search(r"\.(m3u8|mp4)(\?|$)", url):
            continue
        # 集名必须取「紧挨着最终地址前面的那个 $ 之后」的文本。
        # 有些站会把两组地址用 $$$ 粘在同一段里，例如：
        #   第30集$.../share/xxx$$$第01集$.../90153_xxx/index.m3u8
        # 这时真正的集名是「第01集」而不是段首的「第30集」。
        pos = seg.rfind(url)
        before = re.sub(URL_RE, "", seg[:pos]).rstrip("$")
        nm = before.rsplit("$", 1)[-1].strip() if before else ""
        nm = re.sub(r"[\s$]+", " ", nm).strip()
        if not nm or len(nm) > 30:
            nm = "第%d集" % (len(eps) + 1)
        eps.append({"name": nm[:24], "url": url})
    # 有些采集站的顺序是乱的（出现过第一段写着「第30集」），
    # 若集名都是「第N集」这种可解析格式，就按数字重排一次
    def _num(x):
        mm = re.search(r"第\s*(\d+)\s*[集话期]", x["name"])
        return int(mm.group(1)) if mm else None
    nums = [_num(e) for e in eps]
    if len(eps) > 2 and all(n is not None for n in nums) and nums != sorted(nums):
        eps = [e for _, e in sorted(zip(nums, eps), key=lambda t: t[0])]
    return eps


def parse_lines(v):
    """把一个 vod 的播放地址拆成若干线路：[(线路名, [{name,url}])]

    采集站的 vod_play_from / vod_play_url 都是 $$$ 分隔的多线路结构。
    不拆开就会把四条线路压成一条，集名重复（第01集 ×4）。
    """
    raw = (v.get("vod_play_url") or "")
    froms = [x.strip() for x in (v.get("vod_play_from") or "").split("$$$")]
    chunks = raw.split("$$$")
    pairs = list(zip(froms, chunks)) if len(froms) == len(chunks) else \
        [("", c) for c in chunks]
    out = []
    for line_name, chunk in pairs:
        eps = _dedupe(_parse_play_url(chunk))
        if eps:
            out.append((line_name, eps))
    return out


def _mk_items(site, d):
    """把采集站返回的 list 转成统一结构（有播放地址才要）。

    关键：`vod_play_from` / `vod_play_url` 是**多线路**结构，用 $$$ 分隔——
        "线路1$$$线路2" 对应 "第01集$url#...#$$$第01集$url#..."
    以前直接把整串丢进 _parse_play_url，四条线路会被压成一条，
    集名就变成「第01集 第01集 第01集 第01集」这种重复。
    现在按线路拆开，每条线路一条记录。
    """
    out = []
    for v in (d.get("list") or []):
        raw = (v.get("vod_play_url") or "")
        froms = [x.strip() for x in (v.get("vod_play_from") or "").split("$$$")]
        chunks = raw.split("$$$")
        pairs = list(zip(froms, chunks)) if len(froms) == len(chunks) else             [("", c) for c in chunks]
        for line_name, chunk in pairs:
            eps = _dedupe(_parse_play_url(chunk))
            if not eps:
                continue
            rec = {
                "name": (v.get("vod_name") or "").strip(),
                "year": str(v.get("vod_year") or "").strip(),
                "pic": (v.get("vod_pic") or "").strip(),
                "type": (v.get("type_name") or "").strip(),
                "site": site["name"],
                "line": line_name,
                "eps": eps,
                "count": len(eps),
                "remarks": (v.get("vod_remarks") or "").strip(),
            }
            rec.update(_row_meta(v))
            out.append(rec)
    return out


def _dedupe(eps):
    """同一条线路里集名重复时只留第一个（部分站会把备用地址也拼进来）"""
    seen, out = set(), []
    for e in eps:
        key = e.get("name") or ""
        if key in seen:
            continue
        seen.add(key)
        out.append(e)
    return out


def _qs(site, qs, timeout=12):
    """取某站某个查询串的结果，直接归一成条目"""
    return _mk_items(site, {"list": fetch_vod(site, qs, timeout)})


def _by_type(sites, ts, workers=12, deadline=15):
    """按分类 ID 批量取。

    采集站首页那 20 条几乎全是剧集/综艺/动漫，电影（剧情片/动作片…）
    要按分类 ID 才拉得到。各家 ID 排法不一样，所以不猜 ID 含义，
    而是把拉回来的条目按 type_name 重新归类，怎么排都不会错。
    """
    out = []
    ex = concurrent.futures.ThreadPoolExecutor(max_workers=workers)
    try:
        futs = [ex.submit(_qs, s, "ac=detail&t=%d" % t)
                for s in sites for t in ts]
        done, _ = concurrent.futures.wait(futs, timeout=deadline)
        for f in done:
            try:
                out.extend(f.result())
            except Exception:
                pass
    finally:
        ex.shutdown(wait=False)
    return out


def _latest(site, hours=48, limit=18, pages=1):
    """取某站最近更新的条目。

    pages>1 时翻页多拉几批——采集站首页那 20 条几乎全是剧集/综艺/动漫，
    电影（剧情片/动作片…）要往后翻才有，否则「电影」分类永远只有零星几部。
    """
    def one(pg):
        return _mk_items(site, {"list": fetch_vod(
            site, "ac=detail&h=%d&pg=%d" % (hours, pg))})

    if pages <= 1:
        return one(1)[:limit]
    out = []
    with concurrent.futures.ThreadPoolExecutor(max_workers=pages) as ex:
        for part in ex.map(one, range(1, pages + 1)):
            out.extend(part)
    return out[:limit]
