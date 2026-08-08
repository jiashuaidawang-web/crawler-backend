# akshare-bridge：financial 财报数据桥接服务（Python FastAPI）。
# 仅在需要回填 financial 表时启用（compose profile: financial）。
# 使用腾讯云 PyPI 镜像安装 akshare（公网 PyPI 在本环境不通）。
FROM python:3.11-slim

WORKDIR /app

COPY requirements.txt .
RUN pip install --no-cache-dir -i https://mirrors.cloud.tencent.com/pypi/simple \
    -r requirements.txt

COPY akshare_bridge.py .

ENV TZ=Asia/Shanghai
RUN ln -snf /usr/share/zoneinfo/$TZ /etc/localtime && echo $TZ > /etc/timezone

EXPOSE 8800

CMD ["uvicorn", "akshare_bridge:app", "--host", "0.0.0.0", "--port", "8800"]
