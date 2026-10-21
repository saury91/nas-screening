# -*- coding: utf-8 -*-
"""投屏：把「在哪儿播」这件事从手机交给电视。

设计成一个极简的指令中转，不碰视频流：
  * 电视端每 15 秒注册一次心跳，同时挂一条长轮询等指令；
  * 手机/电脑把「播这个地址 + 从第几秒」放进队列；
  * 电视捞到就自己从 NAS 拉流播——带宽走电视，手机可以直接锁屏。

为什么不走 DLNA/Cast：那要电视那边实现 DMR 或认证 Receiver，国内盒子基本没有；
这里电视本来就是我们的客户端，用它自己的登录态最省事。

状态全在内存：NAS 重启后设备重新注册即可，不需要持久化。

**跨网的前提**是手机能访问到 NAS（DDNS / 端口映射 / 组网）。电视永远和 NAS
同在局域网，所以电视侧不受影响；手机在外网投不了，是 NAS 不可达，不是这里的问题。
"""
import threading
import time

ONLINE_TTL = 45          # 超过 45 秒没心跳就算离线
STALE_STATE = 30         # 超过 30 秒没新状态就不信「正在播」了
MAX_COMMANDS = 8         # 单台设备最多积压这么多条，防止手机疯狂点击撑爆内存
MAX_WAIT = 25            # 长轮询上限，必须小于 ONLINE_TTL 的一半左右

_lock = threading.Lock()
_cond = threading.Condition(_lock)   # 长轮询挂起/唤醒用，和 _lock 共用一把锁
_devices = {}            # id -> {id,name,kind,at,ua,owner,state}
_commands = {}           # id -> [command]

# 合法的控制指令。play 带内容，其余只改播放状态。
# rate / volume 是「调参数」而非「换内容」，不需要重载片子，所以单独成指令：
# 手机遥控页拖音量滑块时每一下都发 play 会把电视拖到一直重新缓冲。
KINDS = ("play", "pause", "resume", "seek", "stop", "rate", "volume")


def register(dev_id, name="", kind="tv", ua=""):
    """设备上线/续命。同名设备保留原 id 的元信息。"""
    if not dev_id:
        raise ValueError("缺少设备 id")
    now = time.time()
    with _cond:
        old = _devices.get(dev_id) or {}
        _devices[dev_id] = {
            "id": dev_id,
            "name": (name or old.get("name") or "").strip()[:32] or ("电视" if kind == "tv" else "设备"),
            "kind": kind if kind in ("tv", "phone", "web") else "tv",
            "at": now,
            "ua": (ua or "")[:120],
            "owner": old.get("owner") or "",
            # 状态跟着设备走：电视重新注册（比如 App 冷启动）时保留上一次的进度，
            # 手机刚打开面板就能看到「正在播第 3 集 12:30」，而不是一片空白
            "state": old.get("state") or {},
        }
        _prune_locked(now)
        return dict(_devices[dev_id])


def _prune_locked(now):
    for k, v in list(_devices.items()):
        if now - v["at"] > ONLINE_TTL:
            _devices.pop(k, None)
            _commands.pop(k, None)


def targets(exclude=""):
    """在线设备名录，每台都带上它当前的播放状态。"""
    now = time.time()
    with _cond:
        _prune_locked(now)
        out = [dict(v) for k, v in _devices.items() if k != exclude]
    # 状态过期兜底：电视进程被杀 / 断网时，最后一条上报会永远留在名录里，
    # 手机面板就一直是「正在播 9:40」，点开电视却是黑屏。超过 30 秒没新状态
    # 一律按「已暂停」看，至少不会骗人。
    for d in out:
        st = d.get("state") or {}
        if st and now - st.get("at", 0) > STALE_STATE:
            st = dict(st)
            st["playing"] = False
            d["state"] = st
    out.sort(key=lambda d: (d["kind"] != "tv", d["name"]))
    return out


def send(target, payload, owner=""):
    """payload 是前端给的整包：kind + url/title/position + 整部片的线路与集数。

    [owner] 是发起方 id，记在设备上：两台手机同投一台电视时，先前那台
    下次拉名录会看到 owner 不是自己，就知道控制权被接管了。
    """
    if not target or not payload:
        raise ValueError("缺目标或内容")
    kind = payload.get("kind") or "play"
    if kind not in KINDS:
        raise ValueError("不认识的指令：%s" % kind)
    if kind == "play" and not payload.get("url"):
        raise ValueError("缺播放地址")
    now = time.time()
    with _cond:
        dev = _devices.get(target)
        if not dev:
            raise KeyError("设备不在线")
        if now - dev["at"] > ONLINE_TTL:
            raise KeyError("设备不在线")
        item = dict(payload)
        item.pop("target", None)
        item["kind"] = kind
        item["at"] = now
        q = _commands.setdefault(target, [])
        q.append(item)
        # 只留最后几条：积压的多半是用户连点，前面的已经没有意义
        del q[:-MAX_COMMANDS]
        if kind == "play":
            dev["owner"] = (owner or "").strip()
        elif kind == "stop":
            # 会话结束就把控制权还回去。否则这台电视在名录里一直显示
            # 「被占用」—— 换台手机投、或者自己退出播放页再进来，都会
            # 以为电视还被别人占着
            dev["owner"] = ""
        _cond.notify_all()          # 叫醒挂着长轮询的那台电视
    return True


def report_state(dev_id, data):
    """电视上报「播到哪了」。

    DLNA 那边要轮询 GetPositionInfo 才能拿到进度，还经常不准；这里电视自己报，
    手机点开面板就是实时状态。顺带续一次心跳 —— 播放中的电视本来就在发请求，
    没必要再单独发心跳。
    """
    if not dev_id:
        raise ValueError("缺少设备 id")
    now = time.time()
    with _cond:
        dev = _devices.get(dev_id)
        if not dev:
            raise KeyError("设备不在线")
        dev["at"] = now
        dev["state"] = {
            "url": (data.get("url") or "")[:600],
            "title": (data.get("title") or "")[:120],
            "position": int(data.get("position") or 0),
            "duration": int(data.get("duration") or 0),
            "playing": bool(data.get("playing")),
            # 遥控页要按电视的真实值摆滑块/高亮选集，不能凭手机自己的记忆
            "rate": _num(data.get("rate"), 1.0),
            "volume": _num(data.get("volume"), 1.0),
            "line": int(data.get("line") or 0),
            "ep": int(data.get("ep") or 0),
            "episode": (data.get("episode") or "")[:60],
            "at": now,
        }
    return True


def _num(v, default):
    """把上报值收敛成 float。老版本电视端不带这些字段，用默认值兜住。"""
    try:
        return float(v)
    except (TypeError, ValueError):
        return default


def poll(dev_id, wait=0):
    """取走并清空该设备的指令队列；顺带续一次心跳。

    [wait] 大于 0 时改成长轮询：没有指令就挂起，一有指令立刻返回。
    原来是固定 3 秒轮一次，投屏要等最多 3 秒才起播；换成挂起后是毫秒级，
    代价只是每台电视占一个线程（ThreadingHTTPServer，家里几台完全无所谓）。
    """
    try:
        wait = float(wait or 0)
    except (TypeError, ValueError):
        wait = 0
    wait = max(0.0, min(wait, MAX_WAIT))

    now = time.time()
    with _cond:
        if dev_id in _devices:
            _devices[dev_id]["at"] = now
        cmds = _commands.pop(dev_id, [])
        if cmds or wait <= 0:
            return cmds

        deadline = now + wait
        while True:
            remain = deadline - time.time()
            if remain <= 0:
                return []
            _cond.wait(remain)
            if dev_id in _devices:
                _devices[dev_id]["at"] = time.time()
            cmds = _commands.pop(dev_id, [])
            if cmds:
                return cmds


def reset():
    """测试用：清空设备与指令。"""
    with _cond:
        _devices.clear()
        _commands.clear()
        _cond.notify_all()
