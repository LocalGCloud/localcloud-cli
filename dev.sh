#!/bin/bash
# LocalCloud — Full dev rebuild: build JARs + console, rebuild Docker, restart, tail logs
set -eo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR"

echo "=== Building server JAR ==="
cd localcloud-server && ./gradlew clean shadowJar -q && cd ..

echo "=== Building console ==="
cd localcloud-console && npm run build && cd ..

echo "=== Building Docker image ==="
BUILD_HASH=$(git rev-parse --short HEAD 2>/dev/null || echo "dev")
BUILD_DATE=$(date -u +%Y%m%d)
docker build \
  --build-arg BUILD_HASH="$BUILD_HASH" \
  --build-arg BUILD_DATE="$BUILD_DATE" \
  -t localcloud/localcloud:latest .

echo "=== Removing existing container ==="
docker rm -f $(docker ps -a -q --filter "name=localcloud") 2>/dev/null || true

echo "=== Starting container ==="
./start.sh

echo "=== Tailing logs (Ctrl+C to stop) ==="
docker logs -f $(docker ps -a -q --filter "name=localcloud")
