#!/usr/bin/env bash
# CodeFlow v2 一键部署:seekdb + 应用(前端随 jar 内嵌,自动建表)。
#
# 用法:
#   ./deploy.sh up        # 首次部署/更新(生成密钥 → 构建 → 启动 → 等健康)
#   ./deploy.sh down      # 停止并移除容器(数据卷保留)
#   ./deploy.sh restart   # 重启应用
#   ./deploy.sh status    # 查看状态
#   ./deploy.sh logs      # 跟踪日志
#
# 可覆盖:APP_PORT(默认 7001)、PUBLIC_BASE_URL(默认 http://localhost:$APP_PORT)
set -euo pipefail
cd "$(dirname "$0")"

ENV_FILE=".env"
APP_PORT="${APP_PORT:-7001}"
export APP_PORT
ACTION="${1:-up}"

die() { echo "错误:$*" >&2; exit 1; }

need() { command -v "$1" >/dev/null 2>&1 || die "缺少依赖:$1"; }

gen_env() {
  if [ -f "$ENV_FILE" ]; then
    echo "==> 复用已有 $ENV_FILE(密钥不会重置)"
    return
  fi
  need openssl
  echo "==> 首次部署:生成随机密钥写入 $ENV_FILE(chmod 600,勿提交)"
  umask 077
  cat > "$ENV_FILE" <<EOF
# CodeFlow v2 部署密钥 —— 一经生成不要更换:换掉后已加密的凭据解不开
AINATIVE_JWT_SECRET=$(openssl rand -base64 48 | tr -d '\n')
AINATIVE_SECRET_MASTER_KEY=$(openssl rand -base64 32 | tr -d '\n')
DOWNLOAD_SECRET=$(openssl rand -hex 16)
PUBLIC_BASE_URL=${PUBLIC_BASE_URL:-http://localhost:${APP_PORT}}
EOF
  chmod 600 "$ENV_FILE"
}

wait_healthy() {
  local url="http://localhost:${APP_PORT}/"
  echo "==> 等待应用就绪($url)…"
  for i in $(seq 1 90); do
    code=$(curl -s -o /dev/null -w '%{http_code}' --max-time 3 "$url" || true)
    if [ "$code" = "200" ]; then
      echo "==> 就绪:${url}register  (首个注册用户即平台管理员)"
      return 0
    fi
    sleep 3
  done
  echo "!! 应用未在预期时间内就绪,查看日志:./deploy.sh logs" >&2
  return 1
}

case "$ACTION" in
  up)
    need docker
    docker compose version >/dev/null 2>&1 || die "需要 Docker Compose v2(docker compose)"
    gen_env
    echo "==> 构建并启动(seekdb + 应用,首次约 5 分钟)"
    docker compose up -d --build
    wait_healthy
    ;;
  down)
    need docker
    docker compose down
    echo "==> 已停止(数据卷 seekdb-data 保留;彻底清理加 -v)"
    ;;
  restart)
    need docker
    docker compose restart app
    wait_healthy
    ;;
  status)
    need docker
    docker compose ps
    ;;
  logs)
    need docker
    docker compose logs -f --tail=100 app
    ;;
  *)
    sed -n '2,14p' "$0" | sed 's/^# \{0,1\}//'
    exit 1
    ;;
esac
