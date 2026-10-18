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
from server.config import (LIVE_AUTO, MUSIC_DIR, PORT, HOST, DB_PATH)
from server.db import db_init, seed_sources, dbrun
from server.log import log
from server.services import auth, catalog, live as live_svc, music, musicbox
from server.services.qbt import QBT


def live_auto_loop():
    """每 30 分钟看一次是否到了每日维护时间。

    参考 lubin776/tvbox-api-backup 的「定时抓源 + 校验」思路，
    只是把 GitHub Actions 换成了进程内的守护线程。
    """
    time.sleep(30)          # 让启动预热先跑完
    while True:
        try:
            if LIVE_AUTO:
                live_svc.auto_refresh()
        except Exception as e:
            log("直播自动维护失败：%s" % str(e)[:160])
        time.sleep(1800)


def music_warm():
    """后台把音乐页要用的三份数据先跑一遍。

    以前预热的是「把歌单摊成歌曲」那套接口（要并发探几十首文件头，5~6 秒）；
    现在首页只展示歌单封面，改成预热推荐歌单、分类树和默认分类的歌单列表。
    """
    time.sleep(6)
    try:
        musicbox.recommend(limit=14)
        groups = musicbox.categories()
        first = None
        for g in groups:
            if g["name"] == "语种":
                first = next((s for s in g["subs"] if s["name"] == "华语"), None)
                break
        if not first and groups and groups[0]["subs"]:
            first = groups[0]["subs"][0]
        if first:
            musicbox.category_playlists(first["id"], limit=24)
    except Exception as e:
        log("在线歌单预热失败：%s" % str(e)[:160])


def main():
    db_init()
    seed_sources()
    # 首次启动直接建好 admin/adnas，网页打开就能用，不用先做初始化
    if auth.ensure_default_admin():
        log("已创建默认管理员：admin / 123456（建议在设置里改掉 PIN）")
    else:
        moved = auth.migrate_pins()
        if moved:
            log("账户体系升级：%d 个账户需要重设 PIN（管理员已重置为 123456）" % len(moved))
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

    # 每日直播维护：抓源 → 探活 → 清理连续失效的频道
    threading.Thread(target=live_auto_loop, daemon=True).start()
    # 在线歌单推荐预热
    threading.Thread(target=music_warm, daemon=True).start()

    log("就绪 → http://127.0.0.1:%d" % PORT)
    try:
        srv.serve_forever()
    except KeyboardInterrupt:
        log("收到退出信号")


if __name__ == "__main__":
    main()
