#!/usr/bin/env bash
# 后端测试:在 maven 容器里跑 mvn test。
#
# 为什么用容器:
#   1) 宿主没装 Maven(开发机不污染);
#   2) 依赖拉取与编译产物都在容器内,不影响工作区(除 target/)。
#
# 数据库:脚本自己起一个**专用** MySQL 8 容器(与平台运行库完全隔离),
# 通过 TEST_JDBC_URL 传给测试;测试基类据此直连并按生产同一份 schema 建表。
# 容器内 Testcontainers(Docker-in-Docker)在 macOS 上 socket 别名不一致会失败,
# 所以这里用外部库模式,更稳也更快(复用同一容器,多个测试类不重复起库)。
#
# 用法:
#   scripts/run-tests.sh                    # 跑全部测试
#   scripts/run-tests.sh -Dtest=WorkitemDeliveryApiTest   # 只跑某个测试类
#   scripts/run-tests.sh -Dtest='*ApiTest'
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SERVER_DIR="$(cd "$HERE/.." && pwd)"
REPO_ROOT="$(cd "$SERVER_DIR/../../.." && pwd)"
MAVEN_IMAGE="${MAVEN_IMAGE:-maven:3.9.9-eclipse-temurin-21}"
M2_DIR="${M2_DIR:-$HOME/.m2}"
# 项目自带 settings(阿里云镜像 + 固定本地仓库),不依赖开发机个人配置。
SETTINGS_FILE="${SETTINGS_FILE:-$HERE/maven-settings.xml}"

DB_CONTAINER="ainative-test-db"
DB_PORT="${TEST_DB_PORT:-3399}"
MYSQL_IMAGE="${MYSQL_IMAGE:-docker.m.daocloud.io/library/mysql:8.0.36}"

cleanup() {
  docker rm -f "$DB_CONTAINER" >/dev/null 2>&1 || true
}
trap cleanup EXIT

# 每轮重建测试库:上一轮的数据(尤其是唯一键冲突)不带进这一轮
if [ "${KEEP_TEST_DB:-0}" != "1" ]; then
  docker rm -f "$DB_CONTAINER" >/dev/null 2>&1 || true
fi
if ! docker ps --format '{{.Names}}' | grep -qx "$DB_CONTAINER"; then
  docker run -d --name "$DB_CONTAINER" \
    -e MYSQL_ROOT_PASSWORD=root -e MYSQL_DATABASE=ainative_test \
    -e MYSQL_USER=test -e MYSQL_PASSWORD=test \
    -p "${DB_PORT}:3306" "$MYSQL_IMAGE" \
    --character-set-server=utf8mb4 --collation-server=utf8mb4_general_ci \
    >/dev/null
fi

echo "等待测试库就绪(MySQL ${DB_PORT})…"
for _ in $(seq 1 60); do
  if docker exec "$DB_CONTAINER" mysqladmin ping -uroot -proot --silent >/dev/null 2>&1; then
    break
  fi
  sleep 2
done
if ! docker exec "$DB_CONTAINER" mysqladmin ping -uroot -proot --silent >/dev/null 2>&1; then
  echo "✗ 测试库未就绪" >&2
  exit 1
fi

exec docker run --rm \
  --network host \
  -v "$SERVER_DIR":/workspace \
  -v "$REPO_ROOT/platform/ainative/docs":/workspace/docs:ro \
  -v "$M2_DIR":/root/.m2 \
  -v "$SETTINGS_FILE":/root/.m2/settings.xml:ro \
  -e TEST_JDBC_URL="jdbc:mysql://127.0.0.1:${DB_PORT}/ainative_test?useSSL=false&characterEncoding=utf8&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true" \
  -e TEST_JDBC_USER=test \
  -e TEST_JDBC_PASSWORD=test \
  -w /workspace \
  "$MAVEN_IMAGE" \
  mvn -B test "$@"
