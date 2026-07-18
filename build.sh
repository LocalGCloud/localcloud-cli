#!/bin/bash
# LocalCloud Build Script
# Builds all components and creates the Docker image.
#
# Default mode builds a local single-architecture image for development:
#   ./build.sh
#
# Production mode builds and pushes multi-architecture dependency images first,
# then builds and pushes the LocalCloud multi-architecture image:
#   ./build.sh --prod
#
# Common overrides:
#   LOCALCLOUD_IMAGE              LocalCloud image tag
#   SPANNER_EMULATOR_IMAGE        Spanner dependency image tag
#   BIGQUERY_EMULATOR_IMAGE       BigQuery dependency image tag
#   LOCALCLOUD_DEPENDENCIES_DIR   Directory containing dependency repos
#   PLATFORMS                     Production platforms (default: linux/amd64,linux/arm64)
set -eo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR"

BUILD_MODE="development"
SKIP_TESTS=false
CLEAN=false
SKIP_DEPENDENCIES=false

if [ -d "/src/AI/local_cloud_dependencies" ]; then
    DEFAULT_DEPENDENCIES_DIR="/src/AI/local_cloud_dependencies"
else
    DEFAULT_DEPENDENCIES_DIR="$SCRIPT_DIR/../local_cloud_dependencies"
fi

DEPENDENCIES_DIR="${LOCALCLOUD_DEPENDENCIES_DIR:-$DEFAULT_DEPENDENCIES_DIR}"
SPANNER_CONTEXT="${SPANNER_EMULATOR_CONTEXT:-}"
BIGQUERY_CONTEXT="${BIGQUERY_EMULATOR_CONTEXT:-}"
SPANNER_DOCKERFILE="${SPANNER_EMULATOR_DOCKERFILE:-}"
BIGQUERY_DOCKERFILE="${BIGQUERY_EMULATOR_DOCKERFILE:-}"
PLATFORMS="${PLATFORMS:-linux/amd64,linux/arm64}"

usage() {
    cat <<'USAGE'
Usage: ./build.sh [options]

Options:
  --prod, --production       Build and push multi-arch production images.
  --skip-tests               Skip Java tests.
  --skip-dependencies        In prod mode, do not build dependency images.
  --clean                    Run Gradle clean before shadowJar.
  --image IMAGE              LocalCloud image tag.
  --platforms LIST           Buildx platforms for prod mode.
  --dependencies-dir DIR     Directory containing dependency repos.
  --spanner-context DIR      Spanner emulator Docker build context.
  --bigquery-context DIR     BigQuery emulator Docker build context.
  --help                     Show this help.

Environment overrides:
  LOCALCLOUD_IMAGE
  SPANNER_EMULATOR_IMAGE
  BIGQUERY_EMULATOR_IMAGE
  LOCALCLOUD_DEPENDENCIES_DIR
  SPANNER_EMULATOR_CONTEXT
  BIGQUERY_EMULATOR_CONTEXT
  SPANNER_EMULATOR_DOCKERFILE
  BIGQUERY_EMULATOR_DOCKERFILE
  PLATFORMS

Examples:
  ./build.sh --skip-tests
  ./build.sh --prod
  LOCALCLOUD_IMAGE=jaysen2apache/localcloud:latest ./build.sh --prod
USAGE
}

require_value() {
    if [ $# -lt 2 ]; then
        echo "ERROR: Missing value for $1"
        usage
        exit 1
    fi
}

while [ $# -gt 0 ]; do
    case "$1" in
        --prod|--production)
            BUILD_MODE="production"
            shift
            ;;
        --skip-tests)
            SKIP_TESTS=true
            shift
            ;;
        --skip-dependencies)
            SKIP_DEPENDENCIES=true
            shift
            ;;
        --clean)
            CLEAN=true
            shift
            ;;
        --image)
            require_value "$@"
            LOCALCLOUD_IMAGE="$2"
            shift 2
            ;;
        --platforms)
            require_value "$@"
            PLATFORMS="$2"
            shift 2
            ;;
        --dependencies-dir)
            require_value "$@"
            DEPENDENCIES_DIR="$2"
            shift 2
            ;;
        --spanner-context)
            require_value "$@"
            SPANNER_CONTEXT="$2"
            shift 2
            ;;
        --bigquery-context)
            require_value "$@"
            BIGQUERY_CONTEXT="$2"
            shift 2
            ;;
        --help|-h)
            usage
            exit 0
            ;;
        *)
            echo "ERROR: Unknown option: $1"
            usage
            exit 1
            ;;
    esac
done

if [ -z "${LOCALCLOUD_IMAGE:-}" ]; then
    if [ "$BUILD_MODE" = "production" ]; then
        LOCALCLOUD_IMAGE="jaysen2apache/localcloud:latest"
    else
        LOCALCLOUD_IMAGE="localcloud/localcloud:latest"
    fi
fi

#force to use local
SPANNER_EMULATOR_IMAGE=spanner-emulator-build:latest
BIGQUERY_EMULATOR_IMAGE=bigquery-emulator-on-duckdb:latest

SPANNER_EMULATOR_IMAGE="${SPANNER_EMULATOR_IMAGE:-jaysen2apache/spanner-emulator-extended:latest}"
BIGQUERY_EMULATOR_IMAGE="${BIGQUERY_EMULATOR_IMAGE:-jaysen2apache/bigquery-emulator-on-duckdb:latest}"
SPANNER_CONTEXT="${SPANNER_CONTEXT:-$DEPENDENCIES_DIR/cloud-spanner-emulator}"
BIGQUERY_CONTEXT="${BIGQUERY_CONTEXT:-$DEPENDENCIES_DIR/bigquery-emulator-on-duckdb}"
SPANNER_DOCKERFILE="${SPANNER_DOCKERFILE:-$SPANNER_CONTEXT/build/docker/Dockerfile.ubuntu}"
BIGQUERY_DOCKERFILE="${BIGQUERY_DOCKERFILE:-$BIGQUERY_CONTEXT/Dockerfile}"

echo "============================================"
echo "  LocalCloud Build ($BUILD_MODE)"
echo "============================================"
echo ""

if ! docker info >/dev/null 2>&1; then
    echo "ERROR: Docker daemon is not running."
    echo "  Start Docker Desktop or Rancher Desktop and try again."
    exit 1
fi

ensure_buildx() {
    if ! docker buildx version >/dev/null 2>&1; then
        echo "ERROR: Docker Buildx is required for --prod."
        echo "  Install a Docker version with Buildx support."
        exit 1
    fi

    if ! docker buildx inspect >/dev/null 2>&1; then
        docker buildx create --name localcloud-builder --use >/dev/null
    fi

    docker buildx inspect --bootstrap >/dev/null
}

build_dependency_image() {
    local name="$1"
    local image="$2"
    local context="$3"
    local dockerfile="$4"

    if [ ! -d "$context" ]; then
        echo "ERROR: $name dependency context not found: $context"
        echo "  Set LOCALCLOUD_DEPENDENCIES_DIR or ${name}_EMULATOR_CONTEXT."
        exit 1
    fi

    if [ ! -f "$dockerfile" ]; then
        echo "ERROR: $name dependency Dockerfile not found: $dockerfile"
        exit 1
    fi

    echo "Building $name dependency image for $PLATFORMS..."
    docker buildx build \
        --progress=plain \
        --platform "$PLATFORMS" \
        --provenance=false \
        --sbom=false \
        --push \
        -f "$dockerfile" \
        -t "$image" \
        "$context"
}

if [ "$BUILD_MODE" = "production" ]; then
    ensure_buildx
fi

echo "[1/5] Building Java server..."
cd localcloud-server
GRADLE_TASKS=()
if [ "$CLEAN" = true ]; then
    GRADLE_TASKS+=(clean)
fi
GRADLE_TASKS+=(shadowJar)
if ! ./gradlew "${GRADLE_TASKS[@]}" --quiet; then
    echo "ERROR: Java server build failed."
    exit 1
fi
cd ..
echo "  Done: localcloud-server/build/libs/localcloud-server-*-all.jar"

echo "[2/5] Building console frontend..."
cd localcloud-console
npm install --silent 2>/dev/null
if ! npm run build; then
    echo "ERROR: Console frontend build failed."
    exit 1
fi
cd ..
echo "  Done: localcloud-console/dist/"

if [ "$SKIP_TESTS" = false ]; then
    echo "[3/5] Running tests..."
    cd localcloud-server
    if ! ./gradlew test --quiet 2>/dev/null; then
        echo "ERROR: Java server tests failed."
        exit 1
    fi
    cd ..
    echo "  Done: all tests pass"
else
    echo "[3/5] Skipping tests (--skip-tests)"
fi

if [ "$BUILD_MODE" = "production" ]; then
    echo "[4/5] Preparing production multi-arch dependencies..."

    if [ "$SKIP_DEPENDENCIES" = false ]; then
        build_dependency_image "SPANNER" "$SPANNER_EMULATOR_IMAGE" "$SPANNER_CONTEXT" "$SPANNER_DOCKERFILE"
        build_dependency_image "BIGQUERY" "$BIGQUERY_EMULATOR_IMAGE" "$BIGQUERY_CONTEXT" "$BIGQUERY_DOCKERFILE"
    else
        echo "  Skipping dependency image builds (--skip-dependencies)"
    fi
else
    echo "[4/5] Skipping dependency image builds (development mode)"
fi

echo "[5/5] Building Docker image..."

# Pre-pull dependency images to avoid BuildKit attestation manifest issues.
# Some OCI multi-arch images carry SBOM attestations with 'unknown/unknown'
# platform that can break BuildKit platform resolution during docker build.
# Pre-pulling ensures image content is in the local store before the build.
#
# Skip pulling if the image already exists locally — supports both previously
# pulled remote images and locally-built images (e.g. from `docker build -t`).
echo "  Resolving dependency images..."
for img in "$SPANNER_EMULATOR_IMAGE" "$BIGQUERY_EMULATOR_IMAGE"; do
    if docker image inspect "$img" >/dev/null 2>&1; then
        echo "    $img (found locally, skipping pull)"
    else
        echo "    Pulling $img..."
        docker pull "$img" 2>&1 || echo "    WARNING: pull failed — image must be available locally or build will fail"
    fi
done
echo "  Done resolving dependency images"

docker volume create localcloud-data >/dev/null 2>&1 || true

BUILD_HASH=$(git rev-parse --short HEAD 2>/dev/null || echo "dev")
BUILD_DATE=$(date -u +%Y%m%d)

DOCKER_BUILD_ARGS=(
    --build-arg "BUILD_HASH=$BUILD_HASH"
    --build-arg "BUILD_DATE=$BUILD_DATE"
    --build-arg "SPANNER_EMULATOR_IMAGE=$SPANNER_EMULATOR_IMAGE"
    --build-arg "BIGQUERY_EMULATOR_IMAGE=$BIGQUERY_EMULATOR_IMAGE"
)

[ -n "$GO_BASE_IMAGE" ]           && DOCKER_BUILD_ARGS+=(--build-arg "GO_BASE_IMAGE=$GO_BASE_IMAGE")
[ -n "$LITTLE_BIGTABLE_VERSION" ] && DOCKER_BUILD_ARGS+=(--build-arg "LITTLE_BIGTABLE_VERSION=$LITTLE_BIGTABLE_VERSION")
[ -n "$GCS_EMULATOR_IMAGE" ]      && DOCKER_BUILD_ARGS+=(--build-arg "GCS_EMULATOR_IMAGE=$GCS_EMULATOR_IMAGE")
[ -n "$GCLOUD_SDK_IMAGE" ]        && DOCKER_BUILD_ARGS+=(--build-arg "GCLOUD_SDK_IMAGE=$GCLOUD_SDK_IMAGE")
[ -n "$DOCKER_CLI_IMAGE" ]        && DOCKER_BUILD_ARGS+=(--build-arg "DOCKER_CLI_IMAGE=$DOCKER_CLI_IMAGE")
[ -n "$JDK_IMAGE" ]               && DOCKER_BUILD_ARGS+=(--build-arg "JDK_IMAGE=$JDK_IMAGE")

if [ "$BUILD_MODE" = "production" ]; then
    DOCKER_BUILD_ARGS+=(--build-arg "BUILD_MODE=production")
    docker buildx build \
        --progress=plain \
        --platform "$PLATFORMS" \
        --push \
        "${DOCKER_BUILD_ARGS[@]}" \
        -t "$LOCALCLOUD_IMAGE" \
        .
    echo "  Done: pushed $LOCALCLOUD_IMAGE ($PLATFORMS)"
else
    docker build --progress=plain "${DOCKER_BUILD_ARGS[@]}" -t "$LOCALCLOUD_IMAGE" .
    echo "  Done: $LOCALCLOUD_IMAGE"
fi

IMAGE_SIZE=$(docker images "$LOCALCLOUD_IMAGE" --format "{{.Size}}" 2>/dev/null | head -1)
echo ""
echo "============================================"
echo "  Build complete! (image: ${IMAGE_SIZE:-pushed multi-arch manifest})"
echo ""
echo "  Image:   $LOCALCLOUD_IMAGE"
echo "  Start:   ./start.sh"
echo "  Health:  curl localhost:8080/health"
echo "  Console: http://localhost:8080"
echo "============================================"
