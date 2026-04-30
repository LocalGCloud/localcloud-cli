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
docker build -t localcloud/localcloud:latest .

echo "=== Removing existing container ==="
docker rm -f $(docker ps -a -q --filter "name=localcloud") 2>/dev/null || true

echo "=== Starting container ==="
./start.sh

echo "=== Tailing logs (Ctrl+C to stop) ==="
docker logs -f $(docker ps -a -q --filter "name=localcloud")
