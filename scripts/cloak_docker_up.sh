#!/usr/bin/env bash
# 启动 CloakBrowser CDP server 容器(供本机 Java worker 通过 CDP 接入)
#
# 用法:
#   scripts/cloak_docker_up.sh            # 起容器
#   scripts/cloak_docker_up.sh --proxy http://u:p@host:port  # 带代理
#   scripts/cloak_docker_down.sh          # 停容器
#
# 容器起好后:
#   CDP 端点:  http://127.0.0.1:9222
#   健康检查:  curl http://127.0.0.1:9222/json/version
#
# 注意: 需要 Docker Desktop 已运行

set -euo pipefail

CONTAINER_NAME="cloakserve"
IMAGE="cloakhq/cloakbrowser:latest"
PORT="${CLOAK_PORT:-9222}"
LICENSE_KEY="${CLOAK_LICENSE_KEY:-}"
PROXY="${1:-}"

# 已在跑则直接提示
if docker inspect -f '{{.State.Running}}' "$CONTAINER_NAME" 2>/dev/null | grep -q true; then
  echo "[cloak_docker] container '$CONTAINER_NAME' already running"
  echo "[cloak_docker] CDP endpoint: http://127.0.0.1:${PORT}"
  exit 0
fi

# 清理同名已停容器
docker rm -f "$CONTAINER_NAME" >/dev/null 2>&1 || true

echo "[cloak_docker] pulling image $IMAGE ..."
docker pull "$IMAGE"

# Mac 上 Docker Desktop 的 --network host 行为与 Linux 不同(实际走 VM 隔离),
# 默认用端口映射 -p 更可靠。Linux 原生 Docker 可设 CLOAK_NETWORK=host 走 host 网络。
NETWORK="${CLOAK_NETWORK:-}"
RUN_ARGS=(--name "$CONTAINER_NAME" -e TZ=Asia/Shanghai --restart unless-stopped)
if [[ "$NETWORK" == "host" ]]; then
  RUN_ARGS+=(--network host)
else
  RUN_ARGS+=(-p "${PORT}:${PORT}")
fi

if [[ -n "$LICENSE_KEY" ]]; then
  RUN_ARGS+=(-e "CLOAKBROWSER_LICENSE_KEY=$LICENSE_KEY")
fi

# cloakserve 子命令
CMD=(cloakserve --port "$PORT" --headless=false)

if [[ -n "$PROXY" ]]; then
  CMD+=(--proxy-server "$PROXY")
  echo "[cloak_docker] using proxy: $PROXY"
fi

echo "[cloak_docker] starting container: docker run ${RUN_ARGS[*]} $IMAGE ${CMD[*]}"
docker run -d "${RUN_ARGS[@]}" "$IMAGE" "${CMD[@]}"

# 健康检查
echo "[cloak_docker] waiting for CDP endpoint ..."
for i in $(seq 1 30); do
  if curl -sf "http://127.0.0.1:${PORT}/json/version" >/dev/null 2>&1; then
    echo "[cloak_docker] CDP ready on port ${PORT}"
    echo "[cloak_docker] endpoint: http://127.0.0.1:${PORT}"
    exit 0
  fi
  sleep 1
done

echo "[cloak_docker] ERROR: CDP did not become ready within 30s" >&2
echo "[cloak_docker] logs:" >&2
docker logs "$CONTAINER_NAME" | tail -30 >&2
exit 1
