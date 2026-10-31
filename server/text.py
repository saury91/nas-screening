# -*- coding: utf-8 -*-
"""片名归一、简介清洗、元数据合并等纯函数。"""
import html as html_mod
import re
import urllib.parse


def _norm_key(name):
    """归一化片名用于跨站合并"""
    s = re.sub(r"[\s·:：\-—_·]+", "", name or "")
    s = re.sub(r"[（(【\[].*?[）)】\]]", "", s)
    return s.lower()


def _relevance(name, kw):
    """片名与关键词的相关度，用于排序"""
    n, k = _norm_key(name), _norm_key(kw)
    if not k:
        return 0
    if n == k:
        return 100
    if n.startswith(k):
        return 85
    if n.endswith(k):
        return 70
    if k in n:
        return 55
    return 10


def _clean_desc(s, n=560):
    """采集站的 vod_content 带 html 标签和一堆换行，清干净再截断"""
    if not s:
        return ""
    s = re.sub(r"<br\s*/?>", " ", str(s), flags=re.I)
    # 段落结束也转空格：直接删会把上下段粘成一串
    s = re.sub(r"</p\s*>", " ", s, flags=re.I)
    s = re.sub(r"<[^>]+>", "", s)
    # 采集站的简介常是二次转义的（"&amp;#039;"）：unescape 一趟只解到
    # "&#039;"，撇号、引号就会以字面量露在页面上。解到不再变化为止。
    for _ in range(3):
        t = html_mod.unescape(s)
        if t == s:
            break
        s = t
    s = re.sub(r"\s+", " ", s).strip()
    return s[:n]


def _zh_count(s):
    """简介里有多少个汉字。
    跨站合并时会撞上英文简介（有的源直接扒了 TMDB 的），
    按「汉字多者优先」挑，就不会把中文源的中文简介挤掉。"""
    return sum(1 for ch in (s or "") if "\u4e00" <= ch <= "\u9fff")


def _desc_better(a, b):
    """a 是否比 b 更值得保留：先看汉字数，再比长度"""
    return (_zh_count(a), len(a or "")) > (_zh_count(b), len(b or ""))


def _join_names(s, n=6):
    """演员/导演：原字段是几十个名字用逗号串起来的，只留前几个"""
    if not s:
        return ""
    parts = [x.strip() for x in re.split(r"[,，/、]", str(s)) if x.strip()]
    out = " / ".join(parts[:n])
    if len(parts) > n:
        out += " 等"
    return out


# 采集站 ac=detail 其实返回了完整字段，之前只取片名/年份/封面/类型，
# 简介、演员、导演、地区、语言、评分全被丢掉了 —— 详情页因此永远是空的。
META_FIELDS = ("desc", "actor", "director", "area", "lang",
               "score", "pubdate", "total")


def _extract_meta(v):
    return {
        # vod_blurb 是短简介，vod_content 是全的；前者常常更规整，作兜底
        "desc": _clean_desc(v.get("vod_content") or v.get("vod_blurb")),
        "actor": _join_names(v.get("vod_actor")),
        "director": _join_names(v.get("vod_director"), 3),
        "area": (v.get("vod_area") or "").strip(),
        "lang": (v.get("vod_lang") or "").strip(),
        "score": str(v.get("vod_score") or "").strip(),
        "pubdate": (v.get("vod_pubdate") or "").strip(),
        "total": str(v.get("vod_total") or "").strip(),
    }


def _row_meta(v, dlen=200):
    """列表/推荐用的精简版——首页 Hero 只显示一两行简介，
    截短一点，几百条一起进缓存也不会太占地方。"""
    d = _clean_desc(v.get("vod_content") or v.get("vod_blurb"), dlen)
    if _zh_count(d) < 4:
        # 有的源直接扒了 TMDB 的英文简介，放进首页 Hero 会很突兀；
        # 详情页仍然保留（那里信息越全越好）
        d = ""
    return {
        "desc": d,
        "score": str(v.get("vod_score") or "").strip(),
        "actor": _join_names(v.get("vod_actor"), 3),
        "director": _join_names(v.get("vod_director"), 2),
        "area": (v.get("vod_area") or "").strip(),
    }


def _score_max(a, b):
    """评分取大值：有的源评分残缺（整部剧给 5.0），
    取最大比「第一个非空」更接近真实分。"""
    def f(x):
        try:
            v = float(x)
        except (TypeError, ValueError):
            return None
        return v if 0 < v <= 10 else None
    fa, fb = f(a), f(b)
    if fa is None:
        return b or ""
    if fb is None:
        return a or ""
    return ("%.1f" % max(fa, fb))


# ── 清晰度识别 ────────────────────────────────────────────────
# 画质信息藏得很散：有的在 vod_remarks（"1080P"、"HD中字"），
# 有的只在 m3u8 路径里（/20250219/xxx/1080/index.m3u8），有的在站名上。
# 注意：绝不能用裸 "hd"/"sd" 匹配整个地址（域名里带 sd 的会被误判成 480P）。
_Q_NUM = [
    ("4K",    re.compile(r"(?<![a-z0-9])(4k|2160p?|uhd)(?![a-z0-9])")),
    ("1080P", re.compile(r"(?<![a-z0-9])(1080p?|fhd)(?![a-z0-9])")),
    ("720P",  re.compile(r"(?<![a-z0-9])(720p?)(?![a-z0-9])")),
    ("480P",  re.compile(r"(?<![a-z0-9])(480p?)(?![a-z0-9])")),
]
_Q_WORD = [
    ("1080P", ("蓝光", "原盘", "remux", "bluray", "bdrip")),
    ("HD",    ("高清", "hd中字", "hd国语", "高码", "hd高清")),
    ("480P",  ("标清",)),
]

Q_RANK = {"4K": 0, "1080P": 1, "720P": 2, "HD": 3, "480P": 4, "": 9}


def guess_quality(*texts):
    """从备注 / 播放地址 / 站名猜清晰度，认不出返回空串。

    宁缺毋滥：猜不到就留空，不硬塞「HD」，否则排序会失去意义。
    """
    parts = []
    for t in texts:
        s = str(t or "").strip()
        if not s:
            continue
        if s.startswith("http"):
            # 只看路径和查询串，域名里的 sd/hd 不算
            try:
                u = urllib.parse.urlsplit(s)
                s = u.path + " " + u.query
            except Exception:
                pass
        parts.append(s)
    blob = " ".join(parts).lower()
    if not blob.strip():
        return ""
    for label, pat in _Q_NUM:
        if pat.search(blob):
            return label
    for label, words in _Q_WORD:
        if any(w in blob for w in words):
            return label
    return ""
