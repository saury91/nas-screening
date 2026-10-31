# -*- coding: utf-8 -*-
"""最小的 .env 读取。

只给入口程序（app.py）用，**不放在 config.py 里自动执行**：
测试要的是「什么都没配」的干净环境，否则本地 .env 会把断言带偏。

格式：KEY=VALUE 逐行，支持 # 注释与成对引号；已存在的环境变量优先。
不依赖第三方库。
"""
import os

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


def load(path=None):
    """读 .env 进 os.environ。文件不存在就什么也不做。"""
    path = path or os.path.join(ROOT, ".env")
    try:
        with open(path, "r", encoding="utf-8") as f:
            for line in f:
                s = line.strip()
                if not s or s.startswith("#") or "=" not in s:
                    continue
                k, v = s.split("=", 1)
                k = k.strip()
                v = v.strip().strip('"').strip("'")
                if k and k not in os.environ:
                    os.environ[k] = v
    except FileNotFoundError:
        pass
    except Exception:
        pass          # 配置读错不该拦启动
