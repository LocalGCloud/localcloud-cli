#!/bin/bash
# LocalCloud — Start container
set -eo pipefail

# Stop existing container if running
if docker ps -q -f name=localcloud >/dev/null 2>&1 && [ -n "$(docker ps -q -f name=localcloud)" ]; then
    echo "Stopping existing container..."
    docker stop localcloud >/dev/null 2>&1 || true
    docker rm localcloud >/dev/null 2>&1 || true
fi

# Remove stopped container with same name
docker rm localcloud >/dev/null 2>&1 || true

# Data directory: use LOCALCLOUD_DATA_DIR env var for host-accessible bind mount,
# otherwise fall back to Docker named volume (not accessible on macOS host).
LOCALCLOUD_DATA_DIR="${LOCALCLOUD_DATA_DIR:-}"

if [ -n "$LOCALCLOUD_DATA_DIR" ]; then
    mkdir -p "$LOCALCLOUD_DATA_DIR"
    VOLUME_ARG="$LOCALCLOUD_DATA_DIR:/var/lib/localcloud"
    echo "Data: $LOCALCLOUD_DATA_DIR (bind mount)"
else
    docker volume create localcloud-data >/dev/null 2>&1 || true
    VOLUME_ARG="localcloud-data:/var/lib/localcloud"
    echo "Data: localcloud-data (Docker volume)"
fi

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

docker run -d --name localcloud \
  -p 127.0.0.1:8080:8080 \
  -p 127.0.0.1:4443:4443 \
  -p 127.0.0.1:8085:8085 \
  -p 127.0.0.1:8086:8086 \
  -p 127.0.0.1:8087:8087 \
  -p 127.0.0.1:9010:9010 \
  -p 127.0.0.1:9020:9020 \
  -p 127.0.0.1:9050:9050 \
  -p 127.0.0.1:9060:9060 \
  -p 127.0.0.1:6379:6379 \
  -p 127.0.0.1:16443:6443 \
  -m 4g \
  -v "$VOLUME_ARG" \
  -v "${LOCALCLOUD_SEED_FILE:-$SCRIPT_DIR/seed.yaml}:/etc/localcloud/seed.yaml:ro" \
  -v "$SCRIPT_DIR/services.yaml:/etc/localcloud/services.yaml:ro" \
  -v /var/run/docker.sock:/var/run/docker.sock \
  -e LOCALCLOUD_PROJECT="${LOCALCLOUD_PROJECT:-local-project}" \
  -e LOCALCLOUD_SERVICES="${LOCALCLOUD_SERVICES:-gcs,pubsub,firestore,bigquery,secretmanager,cloudtasks,spanner,bigtable,logging,monitoring,memorystore,workflows,cloudscheduler,cloudfunctions,alloydb,dataproc,cloudiam}" \
  -e LOCALCLOUD_DATA_DIR="/var/lib/localcloud" \
  -e LOCALCLOUD_GCP_CREDENTIAL_SOURCE="${LOCALCLOUD_GCP_CREDENTIAL_SOURCE:-none}" \
  localcloud/localcloud:latest

echo "LocalCloud running at http://localhost:8080"
echo "Health: curl http://localhost:8080/health"
echo "cloud.localhost: add -p 80:80 to enable no-port-number URL"
