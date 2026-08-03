#!/usr/bin/env bash
# 停掉并删除 cloakserve 容器
set -uo pipefi
NAME="cloakserve"
if docker inspect -f '{{.State.Running}}' "$NAME" 2>/dev/null | grep -q true; then
  echo "[cloak_docker] stopping $NAME ..."
  docker stop "$NAME"
fi
docker rm -f "$NAME" >/dev/null 2>&1 && echo "[cloak_docker] removed $NAME"
echo "[cloak_docker] done"
