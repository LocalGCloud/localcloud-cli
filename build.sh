#!/bin/bash
# LocalCloud Build Script
# Builds all components and creates the Docker image
set -eo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR"

echo "============================================"
echo "  LocalCloud Build"
echo "============================================"
echo ""

# Pre-check: Docker daemon
if ! docker info >/dev/null 2>&1; then
    echo "ERROR: Docker daemon is not running."
    echo "  Start Docker Desktop or Rancher Desktop and try again."
    exit 1
fi

# 1. Build Java server
echo "[1/4] Building Java server..."
cd localcloud-server
if ! ./gradlew shadowJar --quiet; then
    echo "ERROR: Java server build failed."
    exit 1
fi
cd ..
echo "  Done: localcloud-server/build/libs/localcloud-server-*-all.jar"

# 2. Build console frontend
echo "[2/4] Building console frontend..."
cd localcloud-console
npm install --silent 2>/dev/null
if ! npm run build; then
    echo "ERROR: Console frontend build failed."
    exit 1
fi
cd ..
echo "  Done: localcloud-console/dist/"

# 3. Run tests (optional, skip with --skip-tests)
if [ "$1" != "--skip-tests" ]; then
    echo "[3/4] Running tests..."
    cd localcloud-server
    if ! ./gradlew test --quiet 2>/dev/null; then
        echo "ERROR: Java server tests failed."
        exit 1
    fi
    cd ..
    echo "  Done: all tests pass"
else
    echo "[3/4] Skipping tests (--skip-tests)"
fi

# 4. Build Docker image
echo "[4/4] Building Docker image..."
docker volume create localcloud-data >/dev/null 2>&1 || true

# Dockerfile is the single source of truth for image defaults.
# To override, set environment variables:
#   SPANNER_FORK_IMAGE=... BIGQUERY_EMULATOR_IMAGE=... ./build.sh
if ! docker compose build; then
    echo "ERROR: Docker image build failed."
    echo "  Check that Docker daemon is running and has enough resources."
    exit 1
fi
echo "  Done: localcloud/localcloud:latest"

# Show image size
IMAGE_SIZE=$(docker images localcloud/localcloud:latest --format "{{.Size}}" 2>/dev/null)
echo ""
echo "============================================"
echo "  Build complete! (image: ${IMAGE_SIZE:-unknown})"
echo ""
echo "  Start:   docker compose up -d"
echo "  Health:  curl localhost:8080/_localcloud/health"
echo "  Console: http://localhost:8080"
echo "============================================"
