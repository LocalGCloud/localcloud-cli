#!/bin/bash
# LocalCloud — Full dev rebuild: build JARs + console, rebuild Docker, restart, tail logs
set -eo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR"

echo "=== Building local development image ==="
./build.sh --clean --skip-tests --image localcloud/localcloud:latest

echo "=== Removing existing container ==="
docker rm -f $(docker ps -a -q --filter "name=localcloud") 2>/dev/null || true

echo "=== Starting container ==="
./start.sh

echo "=== Tailing logs (Ctrl+C to stop) ==="
docker logs $(docker ps -a -q --filter "name=localcloud")
