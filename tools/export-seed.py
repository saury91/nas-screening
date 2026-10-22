#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""把当前库里的片源 / 直播 / 音乐配置导出成一份 seed 文件（本地私用）。

导出物格式与 `/api/config/bundle` 完全一致，所以它同时是：
  * 新装环境的播种文件（放到 data/seed.json，首启写入数据库）
  * 可以直接在「配置分享」里导入的配置包

**里面是真实的采集站地址，不要提交到 GitHub。**
/data/ 已在 .gitignore 里，默认输出路径落在那里。

    python3 tools/export-seed.py                 # → data/seed.json
    python3 tools/export-seed.py ~/my-seed.json  # → 指定路径
"""
import json
import os
import sys
import time

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, ROOT)

from server.db import pref_json, sources_list           # noqa: E402
from server.services import live                       # noqa: E402
from server.config import MUSICBOX_API, MUSICBOX_REFERER  # noqa: E402


def main():
    out_path = sys.argv[1] if len(sys.argv) > 1 else os.path.join(ROOT, "data", "seed.json")

    # 片源：只导启用的，禁用通常意味着「试过不行」
    src = []
    for s in sources_list("cms", enabled_only=True):
        src.append({
            "name": s.get("name"),
            "api": s.get("url"),
            "type": s.get("type") or 0,
            "cat": s.get("cat") or None,
            "cats": {},
        })

    # 直播：频道表 + 候选源（每日自动汇总用）
    body = {
        "sources": src,
        "live": {
            "groups": live.live_groups(),
            "items": live.live_list(limit=100000),
            "seeds": pref_json("live.seeds") or [],
            "feeds": [{"name": s.get("name"), "url": s.get("url")}
                      for s in sources_list("live", enabled_only=True)],
        },
        "music": {
            "api": pref_json("music.api") or MUSICBOX_API,
            "referer": pref_json("music.referer") or MUSICBOX_REFERER,
        },
    }

    import hashlib
    ver = hashlib.sha1(
        json.dumps({"sources": body["sources"], "live": body["live"]},
                   sort_keys=True, ensure_ascii=False).encode("utf-8")
    ).hexdigest()[:12]

    bundle = {
        "version": ver,
        "generatedAt": int(time.time()),
        "sources": body["sources"],
        "live": body["live"],
        "music": body["music"],
        "ui": {"version": ""},
    }

    os.makedirs(os.path.dirname(os.path.abspath(out_path)), exist_ok=True)
    with open(out_path, "w", encoding="utf-8") as f:
        json.dump(bundle, f, ensure_ascii=False, indent=1)

    size = os.path.getsize(out_path)
    print("已导出 %s" % out_path)
    print("  片源 %d 个 / 直播 %d 个频道 · %d 组 / 候选源 %d 条"
          % (len(bundle["sources"]), len(bundle["live"]["items"]),
             len(bundle["live"]["groups"]), len(bundle["live"]["seeds"])))
    print("  %.1f KB, version %s" % (size / 1024.0, ver))
    print("  提醒：这份文件含真实源地址，不要提交到仓库。")


if __name__ == "__main__":
    main()
