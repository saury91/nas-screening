FROM python:3.12-alpine

WORKDIR /app

# 时区：每日直播维护按本地钟点（LIVE_AUTO_HOUR）触发，镜像里不装 tzdata
# （alpine 源在国内拉不动），改为把宿主的时区文件挂进 /etc/localtime：
#   -v /usr/share/zoneinfo/Asia/Shanghai:/etc/localtime:ro  -e TZ=Asia/Shanghai

# 只用标准库，无需 pip install；前端也没有构建步骤
COPY app.py          /app/app.py
COPY server          /app/server
COPY static          /app/static

ENV DB_PATH=/data/media.db \
    PORT=8899

EXPOSE 8899

# 容器健康检查：只打 /healthz（不查远程片源，永远秒回）
HEALTHCHECK --interval=30s --timeout=3s --start-period=20s --retries=3 \
  CMD wget -qO- http://127.0.0.1:8899/healthz || exit 1

# 需要访问外网片源站与宿主机 qBittorrent，建议 host 网络：
#   docker run -d --name projection-room --restart unless-stopped --network host \
#     -e QBT_URL=http://127.0.0.1:8080 -e QBT_USER=<用户名> -e QBT_PASS=<密码> \
#     -e DB_PATH=/data/media.db -v <宿主数据目录>:/data \
#     -v <宿主音乐目录>:/music:ro -e MUSIC_DIR=/music \
#     projection-room
CMD ["python3", "/app/app.py"]
