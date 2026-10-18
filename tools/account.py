#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""账户救急工具（在 NAS 上直接跑，改完即时生效）。

用法：
    python3 tools/account.py list                  # 看有哪些账户
    python3 tools/account.py reset 老爸 123789      # 重置某个账户的 PIN（6 位数字）
    python3 tools/account.py add-admin 老妈 654321  # 把某个账户提成管理员
    python3 tools/account.py kick                 # 踢掉所有登录会话（不改 PIN）

忘记管理员 PIN、又进不去设置页时用这些。只读写本地数据库，不走网络。
"""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from server.db import db_init, dbrun          # noqa: E402
from server.services import auth              # noqa: E402


def main():
    db_init()
    cmd = (sys.argv[1] if len(sys.argv) > 1 else "list").lower()

    if cmd == "list":
        rows = auth.list_profiles()
        if not rows:
            print("还没有任何账户（网页首次打开会引导你建管理员）")
            return
        for p in rows:
            print("%-10s %-6s %-8s %s" % (
                p["name"], p["role"], "有 PIN" if p["hasPin"] else "免密", p["id"]))
        return

    if cmd == "reset":
        if len(sys.argv) < 4:
            print("用法: python3 tools/account.py reset <名字> <新PIN>")
            return
        name, pin = sys.argv[2], sys.argv[3]
        target = next((p for p in auth.list_profiles() if p["name"] == name), None)
        if not target:
            print("没找到叫「%s」的账户" % name)
            return
        auth.update_profile(target["id"], pin=pin)   # 会校验 6 位数字与唯一性
        print("已重置「%s」的 PIN。建议再执行 kick 让旧会话失效。" % name)
        return

    if cmd in ("add-admin", "promote"):
        if len(sys.argv) < 4:
            print("用法: python3 tools/account.py add-admin <名字> <PIN>")
            return
        name, pin = sys.argv[2], sys.argv[3]
        target = next((p for p in auth.list_profiles() if p["name"] == name), None)
        if not target:
            print("没找到叫「%s」的账户" % name)
            return
        auth.update_profile(target["id"], role="admin", pin=pin)
        print("「%s」已是管理员" % name)
        return

    if cmd == "kick":
        n = dbrun("SELECT COUNT(*) FROM sessions", fetch=True)[0][0]
        dbrun("DELETE FROM sessions")
        print("已清掉 %d 个登录会话，所有设备需要重新输 PIN" % n)
        return

    print(__doc__)


if __name__ == "__main__":
    main()
