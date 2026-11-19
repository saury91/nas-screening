# -*- coding: utf-8 -*-
"""每日数据库快照。

之前只有设置页的「下载备份」按钮 —— 硬盘真坏的那天，没人会提前记得点。
这里补一个最小可用的自动备份：

  - 用 sqlite3 的在线备份 API（`Connection.backup`）拷库，不锁表、不影响读写；
  - 每天一份，文件名带日期，只保留最近 BACKUP_KEEP 份；
  - 启动后先等一会儿再看，避免和首屏加载抢 IO；
  - BACKUP_DAILY=0 可整体关闭。

没做成 Web 里的开关，是因为多一个开关就多一种「用户以为自己开了其实没开」
的情况；默认开、留日志、能关，对自用 NAS 已经够。
"""
import os
import sqlite3
import threading
import time

from ..config import BACKUP_DAILY, BACKUP_DIR, BACKUP_KEEP, DB_PATH
from ..log import log

_LOCK = threading.Lock()
_STOP = threading.Event()


def _today_done():
    if not os.path.isdir(BACKUP_DIR):
        return False
    day = time.strftime("%Y%m%d")
    return any(f.startswith("pr-" + day) for f in os.listdir(BACKUP_DIR))


def _prune():
    """只留最近 N 份；按文件名排序即可，文件名就是时间。"""
    try:
        files = sorted(f for f in os.listdir(BACKUP_DIR) if f.endswith(".db"))
    except Exception:
        return
    for f in files[:-BACKUP_KEEP] if len(files) > BACKUP_KEEP else []:
        try:
            os.remove(os.path.join(BACKUP_DIR, f))
        except Exception:
            pass


def snapshot():
    """立刻存一份快照，返回文件路径；失败返回空串（备份失败不该影响主服务）。"""
    if not os.path.exists(DB_PATH):
        return ""
    try:
        os.makedirs(BACKUP_DIR, exist_ok=True)
        dst = os.path.join(BACKUP_DIR, "pr-%s.db" % time.strftime("%Y%m%d-%H%M"))
        if os.path.exists(dst):
            return dst
        with _LOCK:
            src = sqlite3.connect(DB_PATH, timeout=10)
            out = sqlite3.connect(dst)
            try:
                src.backup(out)
            finally:
                out.close()
                src.close()
        _prune()
        log("数据库快照：%s" % os.path.basename(dst))
        return dst
    except Exception as e:
        log("数据库快照失败：%s" % e)
        return ""


def _loop():
    # 首屏刚起来时磁盘最忙，等两分钟再动手
    if _STOP.wait(120):
        return
    while not _STOP.is_set():
        try:
            if not _today_done():
                snapshot()
        except Exception as e:
            log("自动备份异常：%s" % e)
        # 10 分钟看一次，跨天那一刻最多晚 10 分钟
        if _STOP.wait(600):
            return


def start():
    if not BACKUP_DAILY:
        return None
    t = threading.Thread(target=_loop, name="db-backup", daemon=True)
    t.start()
    return t


def stop():
    _STOP.set()
