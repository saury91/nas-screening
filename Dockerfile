FROM python:3.12-alpine

WORKDIR /app
COPY app.py /app/app.py

# 只用标准库，无需 pip install
EXPOSE 8899

# 需要访问外网片源站与宿主机上的 qBittorrent，故用 host 网络模式启动。
# 账号密码一律通过环境变量传入，不要写进镜像或仓库：
#   docker run -d --name media-tool --restart unless-stopped --network host \
#     -e QBT_URL=http://<qbittorrent-host>:8080 \
#     -e QBT_USER=<用户名> -e QBT_PASS=<密码> \
#     -e DB_PATH=/data/media.db \
#     -v <宿主数据目录>:/data \
#     nas-media-tool
CMD ["python3", "/app/app.py"]
