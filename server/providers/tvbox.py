# -*- coding: utf-8 -*-
"""TVBox 配置解析。

一个 TVBox 配置长这样：
  {
    "sites": [{"key":"...","name":"...","type":0|1|3|4,"api":"https://...","searchable":1}],
    "lives": [{"name":"...","url":"https://.../x.m3u","type":0}],
    "parses": [...]
  }

我们只能吃「直连接口」的两类：
  type 0 = XML 采集接口（MacCMS）
  type 1 = JSON 采集接口
type 3/4 依赖 jar/spider 或私有解析，纯标准库做不到，导入时明确标记跳过，
而不是静默失败让用户以为导进去了。

另外兼容两种常见简化写法：
  * {"sites":[{"name","api"}]}                —— 没写 type，按接口内容自动识别
  * "http://....../api.php/provide/vod/"       —— 单个接口地址
"""
import json
import re

from ..config import CAT_ORDER

# JSON 里不允许出现裸控制字符，但有些配置会把换行/制表符直接塞进字符串
_CTRL = re.compile(r"[\x00-\x08\x0b\x0c\x0e-\x1f]")
# TVBox 客户端自己的本地代理（http://127.0.0.1:9978/proxy?do=live&url=...），
# 只在装了 TVBox 的机器上有效，直接导进来必然抓取失败
_LOCAL = re.compile(r"^https?://(127\.0\.0\.1|localhost|0\.0\.0\.0)([:/]|$)", re.I)


def _escape_in_strings(t):
    """JSON 字符串里不允许裸换行/制表符，但很多配置就是直接换行写简介。
    逐字符扫描，把「引号内」的控制字符转义掉，引号外保持原样。
    """
    out, in_str, esc = [], False, False
    for ch in t:
        if in_str:
            if esc:
                out.append(ch); esc = False; continue
            if ch == "\\":
                out.append(ch); esc = True; continue
            if ch == '"':
                in_str = False; out.append(ch); continue
            if ch == "\n":
                out.append("\\n"); continue
            if ch == "\r":
                out.append("\\r"); continue
            if ch == "\t":
                out.append("\\t"); continue
            out.append(ch); continue
        if ch == '"':
            in_str = True
        out.append(ch)
    return "".join(out)


def clean_json(text):
    """把 TVBox 配置里那些「写给人看」的东西清掉：
    BOM、行首 // 注释、/* */ 块注释、非法控制字符。
    不处理的话 json.loads 会直接报错，而 TVBox 客户端是能容忍的。
    """
    t = (text or "").lstrip("\ufeff").strip()
    t = re.sub(r"(?m)^\s*//.*$", "", t)      # 只删行首注释，避免碰 http://
    t = re.sub(r"/\*.*?\*/", "", t, flags=re.S)
    t = _CTRL.sub("", t)
    t = _escape_in_strings(t)
    return t.strip()


def multi_urls(text):
    """多仓配置：{"urls":[{"name":"..","url":".."}]}，返回 [(name, url)]。"""
    try:
        d = json.loads(clean_json(text))
    except Exception:
        return []
    if not isinstance(d, dict):
        return []
    raw = d.get("urls")
    if not isinstance(raw, list):
        return []
    out = []
    for it in raw:
        if isinstance(it, str) and it.startswith("http"):
            out.append(("", it))
        elif isinstance(it, dict) and str(it.get("url") or "").startswith("http"):
            out.append((str(it.get("name") or "").strip(), it["url"].strip()))
    return out


def _num(v, default=0):
    try:
        return int(v)
    except Exception:
        return default


def infer_type(api, declared=None):
    """根据声明或地址猜协议类型。"""
    t = _num(declared, -1)
    if t in (0, 1):
        return t
    low = (api or "").lower()
    if "provide/vod" in low or low.endswith(".xml") or "xml" in low:
        return 0 if "xml" in low else 1
    return 1


def _cat_of(site):
    """猜一个片源的主分类，只用于设置页打标签和浏览筛源。"""
    raw = " ".join(str(site.get(k) or "") for k in ("group", "categories", "class"))
    for c in CAT_ORDER:
        if c in raw:
            return c
    if "动漫" in raw or "动画" in raw:
        return "动漫"
    if "综艺" in raw:
        return "综艺"
    if "纪录" in raw or "记录" in raw:
        return "纪录片"
    if "剧" in raw:
        return "剧集"
    if "片" in raw or "电影" in raw:
        return "电影"
    return "综合"


def parse_config(text):
    """返回 (sites, lives, skipped, meta)"""
    d = json.loads(clean_json(text))
    if isinstance(d, list):                       # 直接是站点数组
        d = {"sites": d}
    if not isinstance(d, dict):
        raise ValueError("不是合法的 TVBox 配置 JSON")

    sites, lives, skipped = [], [], []
    for i, s in enumerate(d.get("sites") or []):
        if not isinstance(s, dict):
            continue
        name = (s.get("name") or s.get("key") or "").strip()
        api = (s.get("api") or "").strip()
        t = _num(s.get("type"), -1)
        if not name:
            skipped.append({"name": "第%d项" % (i + 1), "reason": "缺少名称"})
            continue
        if t not in (0, 1) and t != -1:
            skipped.append({"name": name,
                            "reason": "type=%s 依赖 jar/spider 解析，纯 Python 后端不支持" % t})
            continue
        if not api.startswith("http"):
            skipped.append({"name": name, "reason": "接口不是 http 直链（可能是 csp_ 类型的 spider）"})
            continue
        if _LOCAL.match(api):
            skipped.append({"name": name, "reason": "指向本机 TVBox 端口（127.0.0.1），换个设备用不了"})
            continue
        if _num(s.get("searchable"), 1) == 0:
            skipped.append({"name": name, "reason": "该源声明不可搜索"})
            continue
        sites.append({
            "name": name,
            "api": api,
            "type": infer_type(api, t),
            "cat": _cat_of(s),
            "key": s.get("key") or "",
            "enabled": 1 if _num(s.get("enabled"), 1) else 1,
        })

    for i, lv in enumerate(d.get("lives") or []):
        if not isinstance(lv, dict):
            continue
        url = (lv.get("url") or "").strip()
        if not url.startswith("http") or _LOCAL.match(url):
            continue
        lives.append({"name": (lv.get("name") or ("直播源%d" % (i + 1))).strip(),
                      "url": url, "type": _num(lv.get("type"), 0)})

    meta = {"flags": d.get("flags") or [], "parses": len(d.get("parses") or [])}
    return sites, lives, skipped, meta


def parse_single(url, name=""):
    """单个接口地址直接添加。"""
    url = (url or "").strip()
    if not url.startswith("http"):
        raise ValueError("地址必须以 http 开头")
    nm = name.strip() or url.split("//")[-1].split("/")[0]
    return [{"name": nm, "api": url, "type": infer_type(url), "cat": "综合",
             "key": "", "enabled": 1}], [], [], {}
