# -*- coding: utf-8 -*-
"""两级缓存：内存 dict + SQLite 落库。

NAS 重启后首页/详情立刻有内容，不用等十几个采集站。
"""
import hashlib
import json
import threading
import time

from .db import dbrun
from .log import log

_CACHE = {}
_LOCK = threading.Lock()

# 缓存版本：改了聚合/解析逻辑后把它 +1，旧条目自动失效。
# 否则用户会一直看到修复前的旧结果（详情按「片名+年份」缓存）。
SCHEMA = "v2"


def cache_load():
    try:
        rows = dbrun("SELECT k,val,ts FROM cache", fetch=True) or []
        n = 0
        for k, v, ts in rows:
            try:
                _CACHE[k] = (ts or 0, json.loads(v))
                n += 1
            except Exception:
                pass
        if n:
            log("缓存载入 %d 项" % n)
    except Exception:
        pass


def _k(key):
    return SCHEMA + ":" + str(key)


def cache_peek(key):
    """不管过不过期都返回，用于先出画面再后台刷新。"""
    key = _k(key)
    with _LOCK:
        e = _CACHE.get(key)
    return e[1] if e else None


def cache_age(key):
    key = _k(key)
    with _LOCK:
        e = _CACHE.get(key)
    return int(time.time() - e[0]) if e else None


def cache_put(key, data):
    key = _k(key)
    try:
        body = json.dumps(data, ensure_ascii=False)
    except Exception:
        return
    with _LOCK:
        _CACHE[key] = (int(time.time()), data)
    dbrun("INSERT OR REPLACE INTO cache(k,val,ts) VALUES(?,?,?)",
          (key, body, int(time.time())))


def cache_valid(key, ttl):
    k = _k(key)
    with _LOCK:
        e = _CACHE.get(k)
    a = int(time.time() - e[0]) if e else None
    return a is not None and a < ttl


def cache_drop(prefix=""):
    """按前缀清缓存。

    注意写入时 key 统一加了 `SCHEMA + ":"`（见 `_k`），所以这里必须先补前缀再比。
    以前直接拿原始 prefix 去 startswith，`cache_drop("catmap3|xxx")` 永远匹配不到
    "v2:catmap3|xxx"，等于什么都没清 —— 分类映射改完仍会读到旧值。
    """
    prefix = _k(prefix) if prefix else ""
    with _LOCK:
        for k in [k for k in _CACHE if k.startswith(prefix)]:
            _CACHE.pop(k, None)
    if prefix:
        dbrun("DELETE FROM cache WHERE k LIKE ?", (prefix + "%",))
    else:
        dbrun("DELETE FROM cache")
