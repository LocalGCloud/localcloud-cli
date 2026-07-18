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

# Data directory: bind mount to host filesystem (override with LOCALCLOUD_DATA_DIR)
LOCALCLOUD_DATA_DIR="${LOCALCLOUD_DATA_DIR:-$HOME/.localcloud/data}"
mkdir -p "$LOCALCLOUD_DATA_DIR"
VOLUME_ARG="$LOCALCLOUD_DATA_DIR:/var/lib/localcloud"
echo "Data: $LOCALCLOUD_DATA_DIR"

# ── Ports ─────────────────────────────────────────────────
# Core (always needed):
#   8080  — Gateway (admin API + web console)
#
# Default services (GCS, Spanner, BigQuery):
#   4443  — Cloud Storage (REST)
#   9010  — Spanner (gRPC)
#   9020  — Spanner (REST API)
#   9050  — BigQuery (REST)
#   9060  — BigQuery (gRPC)
#
# Optional services (uncomment to enable):
#   -p 8085:8085 \   # Pub/Sub (gRPC)
#   -p 8086:8086 \   # Firestore (gRPC)
#   -p 8087:8087 \   # Bigtable (gRPC)
#   -p 6379:6379 \   # Memorystore / Redis
#
# Caddy reverse proxy + cloud.localhost DNS (uncomment to enable):
#   -p 8053:53/udp \  # DNS for *.cloud.localhost
#   -p 80:80 \        # HTTP → HTTPS redirect
#   -p 443:443 \      # HTTPS (TLS)
#
# GKE (needs docker.sock; set LOCALCLOUD_EXTRA_ARGS too):
#   -p 16443:6443 \   # Kubernetes API
#
# Set LOCALCLOUD_EXTRA_ARGS for additional docker run flags.
docker run -d --name localcloud \
  -p 8080:8080 \
  -p 4443:4443 \
  -p 9010:9010 \
  -p 9020:9020 \
  -p 9050:9050 \
  -p 9060:9060 \
  -v "$VOLUME_ARG" \
  ${LOCALCLOUD_EXTRA_ARGS:-} \
  localcloud/localcloud:latest

echo "LocalCloud running at http://localhost:8080"
echo "Health: curl http://localhost:8080/health"
echo ""
echo "For more services, uncomment port lines in start.sh or set LOCALCLOUD_EXTRA_ARGS."
