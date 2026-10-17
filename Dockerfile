FROM python:3.12-alpine

WORKDIR /app

# 只用标准库，无需 pip install；前端也没有构建步骤
COPY app.py          /app/app.py
COPY server          /app/server
COPY static          /app/static

ENV DB_PATH=/data/media.db \
    PORT=8899

EXPOSE 8899

# 需要访问外网片源站与宿主机 qBittorrent，建议 host 网络：
#   docker run -d --name projection-room --restart unless-stopped --network host \
#     -e QBT_URL=http://127.0.0.1:8080 -e QBT_USER=<用户名> -e QBT_PASS=<密码> \
#     -e DB_PATH=/data/media.db -v <宿主数据目录>:/data \
#     -v <宿主音乐目录>:/music:ro -e MUSIC_DIR=/music \
#     projection-room
CMD ["python3", "/app/app.py"]
