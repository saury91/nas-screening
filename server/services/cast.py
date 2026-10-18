# -*- coding: utf-8 -*-
"""投屏：把「在哪儿播」这件事从手机交给电视。

设计成一个极简的指令中转，不碰视频流：
  * 电视端（WebView 里的同一套前端）每 15 秒注册一次心跳，顺带取指令；
  * 手机/电脑把「播这个地址 + 从第几秒」放进队列；
  * 电视捞到就自己从 NAS 拉流播——带宽走电视，手机可以直接锁屏。

为什么不走 DLNA/Cast：那要电视那边实现 DMR 或认证 Receiver，国内盒子基本没有；
这里电视本来就是我们的客户端，用它自己的登录态最省事。

状态全在内存：NAS 重启后设备重新注册即可，不需要持久化。
"""
import threading
import time

ONLINE_TTL = 45          # 超过 45 秒没心跳就算离线
MAX_COMMANDS = 5

_lock = threading.Lock()
_devices = {}            # id -> {id,name,kind,at,ua}
_commands = {}           # id -> [command]


def register(dev_id, name="", kind="tv", ua=""):
    """设备上线/续命。同名设备保留原 id 的元信息。"""
    if not dev_id:
        raise ValueError("缺少设备 id")
    now = time.time()
    with _lock:
        old = _devices.get(dev_id) or {}
        _devices[dev_id] = {
            "id": dev_id,
            "name": (name or old.get("name") or "").strip()[:32] or ("电视" if kind == "tv" else "设备"),
            "kind": kind if kind in ("tv", "phone", "web") else "tv",
            "at": now,
            "ua": (ua or "")[:120],
        }
        _prune_locked(now)
        return dict(_devices[dev_id])


def _prune_locked(now):
    for k, v in list(_devices.items()):
        if now - v["at"] > ONLINE_TTL:
            _devices.pop(k, None)
            _commands.pop(k, None)


def targets(exclude=""):
    now = time.time()
    with _lock:
        _prune_locked(now)
        out = [dict(v) for k, v in _devices.items() if k != exclude]
    out.sort(key=lambda d: (d["kind"] != "tv", d["name"]))
    return out


def send(target, payload):
    """payload 是前端给的整包：url/title/position + 整部片的线路与集数"""
    if not target or not payload:
        raise ValueError("缺目标或内容")
    url = payload.get("url") or ""
    if not url:
        raise ValueError("缺播放地址")
    now = time.time()
    with _lock:
        dev = _devices.get(target)
        if not dev:
            raise KeyError("设备不在线")
        if now - dev["at"] > ONLINE_TTL:
            raise KeyError("设备不在线")
        q = _commands.setdefault(target, [])
        item = dict(payload)
        item.pop("target", None)
        item["kind"] = "play"
        item["at"] = now
        q.append(item)
        del q[:-MAX_COMMANDS]
    return True


def poll(dev_id):
    """取走并清空该设备的指令队列；顺带续一次心跳。"""
    now = time.time()
    with _lock:
        if dev_id in _devices:
            _devices[dev_id]["at"] = now
        return _commands.pop(dev_id, [])


def reset():
    """测试用：清空设备与指令。"""
    with _lock:
        _devices.clear()
        _commands.clear()
