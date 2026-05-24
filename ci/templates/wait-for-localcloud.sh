#!/usr/bin/env sh
set -eu

BASE_URL="${LOCALCLOUD_URL:-http://localhost:8080}"
SERVICES="${LOCALCLOUD_WAIT_SERVICES:-}"
TIMEOUT_SECONDS="${LOCALCLOUD_WAIT_TIMEOUT:-120}"
STARTED_AT="$(date +%s)"

while :; do
  if [ -n "$SERVICES" ]; then
    URL="$BASE_URL/readiness?services=$SERVICES"
  else
    URL="$BASE_URL/readiness"
  fi

  if curl -fsS "$URL" >/tmp/localcloud-readiness.json 2>/tmp/localcloud-readiness.err; then
    cat /tmp/localcloud-readiness.json
    exit 0
  fi

  NOW="$(date +%s)"
  if [ "$((NOW - STARTED_AT))" -ge "$TIMEOUT_SECONDS" ]; then
    echo "LocalCloud did not become ready within ${TIMEOUT_SECONDS}s" >&2
    cat /tmp/localcloud-readiness.err >&2 || true
    curl -fsS "$BASE_URL/diagnostics?limit=50" > localcloud-diagnostics.json 2>/dev/null || true
    curl -fsS "$BASE_URL/diagnostics/archive?limit=50" > localcloud-diagnostics.zip 2>/dev/null || true
    exit 1
  fi

  sleep 2
done
