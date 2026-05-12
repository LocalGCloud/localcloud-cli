#!/bin/bash
# Runs BEFORE supervisord. Exits 1 to abort container startup on license failure.
# Called from docker-entrypoint.sh

set -e

API_KEY="${LOCALCLOUD_API_KEY:-}"
LICENSE_SERVER="${LOCALCLOUD_LICENSE_SERVER:-none}"
BUILD_MODE=$(cat /opt/localcloud/BUILD_MODE 2>/dev/null || echo "development")
CLASSPATH="/opt/localcloud/server.jar"
JAVA_BIN="${JAVA_BIN:-/opt/java/bin/java}"

# Dev mode: no key + no server = bypass allowed only in development builds
if [ -z "$API_KEY" ] && { [ "$LICENSE_SERVER" = "none" ] || [ -z "$LICENSE_SERVER" ]; }; then
    if [ "$BUILD_MODE" = "production" ]; then
        echo "ERROR: LOCALCLOUD_API_KEY is required in production builds." >&2
        echo "       Get a key at https://localcloud.dev" >&2
        exit 1
    fi
    echo "License: development bypass (no key required)"
    echo "development" > /tmp/localcloud-tier
    exit 0
fi

# Validate using LicenseGateMain
DEVICE_ID=$(cat /tmp/localcloud-device-id 2>/dev/null || echo "")

"$JAVA_BIN" -cp "$CLASSPATH" \
    com.localcloud.licensing.LicenseGateMain \
    --key "${API_KEY:-}" \
    --server "${LICENSE_SERVER:-none}" \
    --device-id "$DEVICE_ID" \
    --out /tmp/localcloud-tier

exit $?
