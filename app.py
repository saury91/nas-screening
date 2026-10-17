#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""放映室 · NAS 个人娱乐中心

只依赖 Python 标准库。启动：
    python3 app.py
"""
import threading
import time

from http.server import ThreadingHTTPServer

from server.api import Handler
from server.cache import cache_load
from server.config import (MUSIC_DIR, PORT, HOST, DB_PATH)
from server.db import db_init, seed_sources, dbrun
from server.log import log
from server.services import catalog, music
from server.services.qbt import QBT


def main():
    db_init()
    seed_sources()
    cache_load()

    print("╭─ 放映室 · Projection Room ───────────────────────────╮")
    print("│ 端口      : %-38s│" % PORT)
    print("│ 数据库    : %-38s│" % DB_PATH[:38])
    print("│ 片源      : %-38s│" % ("%d 个已启用" % len(catalog.active_sites())))
    print("│ 音乐      : %-38s│" % (music.status().get("msg") or "-"))
    print("╰──────────────────────────────────────────────────────╯")
    log("qBittorrent: %s" % ("连接成功" if QBT.login() else "未连接（下载功能不可用，其余正常）"))

    class Srv(ThreadingHTTPServer):
        daemon_threads = True
        request_queue_size = 128
        allow_reuse_address = True

    srv = Srv((HOST, PORT), Handler)

    # 首页推荐、分类映射、直播频道都在后台预热，避免第一次打开干等
    from server.services import live as live_svc
    for fn in (catalog.refresh_recommend_async, catalog.catmap_warm):
        try:
            threading.Thread(target=fn, daemon=True).start()
        except Exception:
            pass
    try:
        if not dbrun("SELECT COUNT(*) FROM live", fetch=True)[0][0]:
            threading.Thread(target=live_svc.refresh_live, daemon=True).start()
    except Exception:
        pass

    log("就绪 → http://127.0.0.1:%d" % PORT)
    try:
        srv.serve_forever()
    except KeyboardInterrupt:
        log("收到退出信号")


if __name__ == "__main__":
    main()
