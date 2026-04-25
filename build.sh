#!/bin/bash
# LocalCloud Build Script
# Builds all components and creates the Docker image
#
# Configurable emulator images (override at build time):
#   SPANNER_EMULATOR_IMAGE  - Spanner emulator binary source (default: jaysen2apache/spanner-emulator-extended:latest)
#   BIGQUERY_EMULATOR_IMAGE - BigQuery emulator source (default: jaysen2apache/bigquery-emulator-on-duckdb)
#   GCS_EMULATOR_IMAGE      - GCS emulator source (default: fsouza/fake-gcs-server:1.54.0)
#   GCLOUD_SDK_IMAGE        - gcloud SDK for Firestore/PubSub/Bigtable (default: gcr.io/google.com/cloudsdktool/google-cloud-cli:emulators)
#   DOCKER_CLI_IMAGE        - Docker CLI binary source (default: docker:27.1-cli)
#   JDK_IMAGE               - JDK for jlink custom JRE (default: eclipse-temurin:25-jdk)
#
# Example: use custom Spanner image:
#   SPANNER_EMULATOR_IMAGE=my-registry/spanner:v2 ./build.sh
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

# Configurable emulator images — Dockerfile defaults used when unset
BUILD_ARGS=""
[ -n "$SPANNER_EMULATOR_IMAGE" ]  && BUILD_ARGS="$BUILD_ARGS --build-arg SPANNER_EMULATOR_IMAGE=$SPANNER_EMULATOR_IMAGE"
[ -n "$BIGQUERY_EMULATOR_IMAGE" ] && BUILD_ARGS="$BUILD_ARGS --build-arg BIGQUERY_EMULATOR_IMAGE=$BIGQUERY_EMULATOR_IMAGE"
[ -n "$GCS_EMULATOR_IMAGE" ]      && BUILD_ARGS="$BUILD_ARGS --build-arg GCS_EMULATOR_IMAGE=$GCS_EMULATOR_IMAGE"
[ -n "$GCLOUD_SDK_IMAGE" ]        && BUILD_ARGS="$BUILD_ARGS --build-arg GCLOUD_SDK_IMAGE=$GCLOUD_SDK_IMAGE"
[ -n "$DOCKER_CLI_IMAGE" ]        && BUILD_ARGS="$BUILD_ARGS --build-arg DOCKER_CLI_IMAGE=$DOCKER_CLI_IMAGE"
[ -n "$JDK_IMAGE" ]               && BUILD_ARGS="$BUILD_ARGS --build-arg JDK_IMAGE=$JDK_IMAGE"

if ! docker build $BUILD_ARGS -t localcloud/localcloud:latest .; then
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
echo "  Start:   ./start.sh"
echo "  Health:  curl localhost:8080/_localcloud/health"
echo "  Console: http://localhost:8080"
echo "============================================"
