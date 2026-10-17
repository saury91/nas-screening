# -*- coding: utf-8 -*-
"""统一的日志输出（时间戳 + 立即 flush，容器里能实时看到）。"""
import time


def log(msg):
    try:
        print("[%s] %s" % (time.strftime("%H:%M:%S"), msg), flush=True)
    except Exception:
        pass
