# -*- coding: utf-8 -*-
"""两级缓存：内存 dict + SQLite 落库。

NAS 重启后首页/详情立刻有内容，不用等十几个采集站。
"""
import json
import threading
import time

from .db import dbrun
from .log import log

_CACHE = {}
_LOCK = threading.Lock()


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


def cache_peek(key):
    """不管过不过期都返回，用于先出画面再后台刷新。"""
    with _LOCK:
        e = _CACHE.get(key)
    return e[1] if e else None


def cache_age(key):
    with _LOCK:
        e = _CACHE.get(key)
    return int(time.time() - e[0]) if e else None


def cache_put(key, data):
    try:
        body = json.dumps(data, ensure_ascii=False)
    except Exception:
        return
    with _LOCK:
        _CACHE[key] = (int(time.time()), data)
    dbrun("INSERT OR REPLACE INTO cache(k,val,ts) VALUES(?,?,?)",
          (key, body, int(time.time())))


def cache_valid(key, ttl):
    a = cache_age(key)
    return a is not None and a < ttl


def cache_drop(prefix=""):
    with _LOCK:
        for k in [k for k in _CACHE if k.startswith(prefix)]:
            _CACHE.pop(k, None)
    if prefix:
        dbrun("DELETE FROM cache WHERE k LIKE ?", (prefix + "%",))
    else:
        dbrun("DELETE FROM cache")
