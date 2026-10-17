# -*- coding: utf-8 -*-
"""qBittorrent WebUI 客户端（可选功能）。"""
import html
import http.cookiejar
import json
import urllib.parse
import urllib.request

from ..config import CATEGORIES, QBT_PASS, QBT_URL, QBT_USER, UA
from ..log import log


# ───────────────────────── qBittorrent ─────────────────────────
def state_cn(s):
    return {
        "error": "出错", "missingFiles": "文件缺失", "uploading": "做种中",
        "pausedUP": "已完成·暂停", "queuedUP": "排队做种", "stalledUP": "做种中",
        "checkingUP": "校验中", "forcedUP": "强制做种", "allocating": "分配空间",
        "downloading": "下载中", "metaDL": "获取元数据", "pausedDL": "已暂停",
        "queuedDL": "排队中", "stalledDL": "等待资源", "checkingDL": "校验中",
        "forcedDL": "强制下载", "checkingResumeData": "校验数据",
        "moving": "移动文件", "unknown": "未知",
    }.get(s, s)


class Qbt:
    def __init__(self):
        self.cj = http.cookiejar.CookieJar()
        self.op = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(self.cj))
        self.ok = False
        self.msg = ""

    def _req(self, path, fields=None, timeout=25):
        hdr = {"Referer": QBT_URL, "Origin": QBT_URL, "User-Agent": UA}
        data = urllib.parse.urlencode(fields).encode() if fields else None
        if data:
            hdr["Content-Type"] = "application/x-www-form-urlencoded"
        req = urllib.request.Request(QBT_URL + path, data=data, headers=hdr)
        with self.op.open(req, timeout=timeout) as r:
            return r.status, r.read().decode("utf-8", "replace")

    def login(self):
        # 账号密码从环境变量注入。没配就说清楚是什么没配，
        # 不然用户只能对着「登录失败」猜。
        if not QBT_USER and not QBT_PASS:
            self.ok = False
            self.msg = "未配置 qBittorrent 账号，请用 QBT_USER / QBT_PASS 环境变量注入"
            return False
        try:
            st, b = self._req("/api/v2/auth/login", {"username": QBT_USER, "password": QBT_PASS})
            self.ok = b.strip() == "Ok."
            self.msg = "" if self.ok else "qBittorrent 账号或密码不对"
        except Exception as e:
            self.ok = False
            self.msg = "连不上 qBittorrent（%s）：%s" % (QBT_URL, str(e)[:60])
        return self.ok

    def add(self, magnet, category):
        if not self.ok and not self.login():
            return False, self.msg or "qBittorrent 登录失败"
        f = {"urls": magnet}
        if category in CATEGORIES:
            f["category"] = category
        try:
            st, b = self._req("/api/v2/torrents/add", f)
            return (st == 200), (b.strip() or "已提交")
        except Exception as e:
            return False, str(e)[:80]

    def action(self, hashes, act):
        if not self.ok and not self.login():
            return False, self.msg or "未连接"
        try:
            if act == "delete":
                st, b = self._req("/api/v2/torrents/delete",
                                  {"hashes": hashes, "deleteFiles": "true"})
            elif act == "pause":
                st, b = self._req("/api/v2/torrents/stop", {"hashes": hashes})
            else:
                st, b = self._req("/api/v2/torrents/start", {"hashes": hashes})
            return st == 200, b.strip()[:60]
        except Exception as e:
            return False, str(e)[:80]

    def files(self, h):
        try:
            st, b = self._req("/api/v2/torrents/files?hash=" + h)
            arr = json.loads(b)
            return [{"n": f.get("name", ""),
                     "p": round(f.get("progress", 0) * 100, 1),
                     "s": f.get("size", 0)} for f in arr[:40]]
        except Exception:
            return []

    def snapshot(self):
        try:
            if not self.ok and not self.login():
                return []
            st, b = self._req("/api/v2/torrents/info")
            arr = json.loads(b)
        except Exception:
            return []
        out = []
        for t in arr[:30]:
            eta = t.get("eta", 8640000)
            out.append({
                "hash": t.get("hash", ""),
                "name": (t.get("name", "") or "")[:80],
                "progress": round(t.get("progress", 0) * 100, 1),
                "speed": t.get("dlspeed", 0),
                "state": t.get("state", ""),
                "size": t.get("size", 0),
                "downloaded": t.get("downloaded", 0),
                "seeds": t.get("num_seeds", 0),
                "peers": t.get("num_leechs", 0),
                "total_seeds": t.get("num_complete", 0),
                "eta": eta if 0 < eta < 8640000 else -1,
                "category": t.get("category", ""),
                "state_cn": state_cn(t.get("state", "")),
            })
        return out


QBT = Qbt()
