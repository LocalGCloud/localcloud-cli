#!/bin/bash
# CA Certificate Auto-Import Tests
# Tests both manual mount and auto-detect modes.
#
# Usage:
#   bash test-ca-import.sh              # Runs against localcloud container
#
# Prerequisites:
#   - LocalCloud container image built: docker build -t localcloud/localcloud:latest .
#   - openssl CLI available on host
set -uo pipefail

PASS=0
FAIL=0
IMAGE="localcloud/localcloud:latest"
TEST_DIR="$(mktemp -d)"
CONTAINER=""

pass() { echo "  PASS: $1"; PASS=$((PASS + 1)); }
fail() { echo "  FAIL: $1 — $2"; FAIL=$((FAIL + 1)); }

# keytool returns non-zero on JVM warnings; capture output first to avoid pipefail
keytool_has_alias() {
    local output
    output=$(docker exec "$1" /opt/java/bin/keytool -list \
        -keystore /opt/java/lib/security/cacerts -storepass changeit \
        -alias "$2" 2>&1) || true
    echo "$output" | grep -q "trustedCertEntry"
}

# Wait for entrypoint to finish (supervisord starts after cert import)
wait_ready() {
    local c="$1"
    for i in $(seq 1 30); do
        if docker logs "$c" 2>&1 | grep -q "supervisord started"; then
            return 0
        fi
        sleep 1
    done
    echo "  WARNING: Timed out waiting for entrypoint (30s)" >&2
}

# Start container with unique name
start_container() {
    CONTAINER="lc-ca-$$-$RANDOM"
    docker rm -f "$CONTAINER" 2>/dev/null || true
    docker run -d --name "$CONTAINER" "$@" "$IMAGE" >/dev/null
    wait_ready "$CONTAINER"
}

stop_container() {
    [ -n "$CONTAINER" ] && docker rm -f "$CONTAINER" >/dev/null 2>&1 || true
    CONTAINER=""
}

cleanup() {
    stop_container
    rm -rf "$TEST_DIR"
}
trap cleanup EXIT

echo "=== CA Certificate Import Tests ==="
echo ""

# ---------------------------------------------------------------
# Setup: Generate test certificates
# ---------------------------------------------------------------
echo "--- Setup: generating test certificates ---"
openssl req -x509 -newkey rsa:2048 -nodes \
    -keyout "$TEST_DIR/test-ca.key" \
    -out "$TEST_DIR/test-ca.pem" \
    -days 1 \
    -subj "/CN=LocalCloud Test CA/O=LocalCloud Test" \
    -addext "basicConstraints=critical,CA:TRUE" \
    2>/dev/null

openssl req -x509 -newkey rsa:2048 -nodes \
    -keyout "$TEST_DIR/second-ca.key" \
    -out "$TEST_DIR/second-ca.crt" \
    -days 1 \
    -subj "/CN=LocalCloud Test CA 2/O=LocalCloud Test" \
    -addext "basicConstraints=critical,CA:TRUE" \
    2>/dev/null

echo "  Generated: test-ca.pem, second-ca.crt"
echo ""

# ---------------------------------------------------------------
# Test 1: Manual mount — single PEM file imported
# ---------------------------------------------------------------
echo "--- Test 1: Manual mount — single PEM file ---"

start_container \
    -v "$TEST_DIR/test-ca.pem:/etc/localcloud/certs/test-ca.pem:ro" \
    -e LOCALCLOUD_AUTO_DETECT_CA=false \
    -e LOCALCLOUD_SERVICES="secretmanager" \
    -m 2g

# Check Java truststore
if keytool_has_alias "$CONTAINER" "localcloud-test-ca"; then
    pass "test-ca.pem imported into Java truststore"
else
    fail "test-ca.pem not found in Java truststore" "alias localcloud-test-ca missing"
fi

# Check system CA bundle (cert copied to /usr/local/share/ca-certificates/ then update-ca-certificates runs)
if docker exec "$CONTAINER" test -f /usr/local/share/ca-certificates/localcloud-test-ca.crt 2>/dev/null; then
    pass "test-ca.pem copied to system CA directory"
else
    fail "test-ca.pem not in /usr/local/share/ca-certificates/" ""
fi

# Check logs
LOG_OUT=$(docker logs "$CONTAINER" 2>&1 || true)
if echo "$LOG_OUT" | grep -qF "Imported 1 CA certificate(s)"; then
    pass "Log shows correct import count (1)"
else
    fail "Log missing import count" "$(echo "$LOG_OUT" | grep -iF imported || echo 'none')"
fi

stop_container

# ---------------------------------------------------------------
# Test 2: Manual mount — directory with multiple certs
# ---------------------------------------------------------------
echo "--- Test 2: Manual mount — directory with .pem and .crt ---"

MULTI_DIR="$TEST_DIR/multi"
mkdir -p "$MULTI_DIR"
cp "$TEST_DIR/test-ca.pem" "$MULTI_DIR/"
cp "$TEST_DIR/second-ca.crt" "$MULTI_DIR/"

start_container \
    -v "$MULTI_DIR:/etc/localcloud/certs:ro" \
    -e LOCALCLOUD_AUTO_DETECT_CA=false \
    -e LOCALCLOUD_SERVICES="secretmanager" \
    -m 2g

if docker logs "$CONTAINER" 2>&1 | grep -qF "Imported 2 CA certificate"; then
    pass "Both certs imported (count=2)"
else
    fail "Expected 2 certs imported" "$(docker logs "$CONTAINER" 2>&1 | grep -iF imported || echo 'none')"
fi

for alias in "localcloud-test-ca" "localcloud-second-ca"; do
    if keytool_has_alias "$CONTAINER" "$alias"; then
        pass "Alias $alias present in truststore"
    else
        fail "Alias $alias missing from truststore" ""
    fi
done

stop_container

# ---------------------------------------------------------------
# Test 3: No certs mounted — no errors, clean startup
# ---------------------------------------------------------------
echo "--- Test 3: No certs mounted — clean startup ---"

start_container \
    -e LOCALCLOUD_AUTO_DETECT_CA=false \
    -e LOCALCLOUD_SERVICES="secretmanager" \
    -m 2g

if docker logs "$CONTAINER" 2>&1 | grep -qi "imported.*CA certificate"; then
    fail "Should not log cert import when no certs mounted" ""
else
    pass "No cert import logged when no certs mounted"
fi

if docker logs "$CONTAINER" 2>&1 | grep -qi "WARNING.*Failed to import"; then
    fail "Unexpected cert warning in logs" ""
else
    pass "No cert-related warnings"
fi

stop_container

# ---------------------------------------------------------------
# Test 4: Auto-detect disabled via env var
# ---------------------------------------------------------------
echo "--- Test 4: LOCALCLOUD_AUTO_DETECT_CA=false skips probe ---"

start_container \
    -e LOCALCLOUD_AUTO_DETECT_CA=false \
    -e LOCALCLOUD_SERVICES="secretmanager" \
    -m 2g

if docker logs "$CONTAINER" 2>&1 | grep -qi "proxy.*ca\|auto.*detect\|auto.*import"; then
    fail "Auto-detect ran despite being disabled" ""
else
    pass "Auto-detect skipped when LOCALCLOUD_AUTO_DETECT_CA=false"
fi

stop_container

# ---------------------------------------------------------------
# Test 5: Invalid cert file handled gracefully
# ---------------------------------------------------------------
echo "--- Test 5: Invalid cert file handled gracefully ---"

INVALID_DIR="$TEST_DIR/invalid"
mkdir -p "$INVALID_DIR"
echo "this is not a certificate" > "$INVALID_DIR/garbage.pem"

start_container \
    -v "$INVALID_DIR:/etc/localcloud/certs:ro" \
    -e LOCALCLOUD_AUTO_DETECT_CA=false \
    -e LOCALCLOUD_SERVICES="secretmanager" \
    -m 2g

if docker logs "$CONTAINER" 2>&1 | grep -qi "WARNING.*Failed to import"; then
    pass "Invalid cert produces warning (not crash)"
else
    if ! docker logs "$CONTAINER" 2>&1 | grep -qi "Imported.*CA certificate"; then
        pass "Invalid cert silently rejected, no import logged"
    else
        fail "Unexpected behavior with invalid cert" ""
    fi
fi

if docker ps -q -f "name=$CONTAINER" | grep -q .; then
    pass "Container still running after invalid cert"
else
    fail "Container crashed on invalid cert" ""
fi

stop_container

# ---------------------------------------------------------------
# Test 6: Imported cert CN matches generated cert
# ---------------------------------------------------------------
echo "--- Test 6: Java trusts imported cert (keytool verify) ---"

start_container \
    -v "$TEST_DIR/test-ca.pem:/etc/localcloud/certs/test-ca.pem:ro" \
    -e LOCALCLOUD_AUTO_DETECT_CA=false \
    -e LOCALCLOUD_SERVICES="secretmanager" \
    -m 2g

STORE_CN=$(docker exec "$CONTAINER" /opt/java/bin/keytool -list -v \
    -keystore /opt/java/lib/security/cacerts -storepass changeit \
    -alias "localcloud-test-ca" 2>/dev/null | grep "Owner:" || echo "")

if echo "$STORE_CN" | grep -q "LocalCloud Test CA"; then
    pass "Imported cert CN matches: LocalCloud Test CA"
else
    fail "Cert CN mismatch in truststore" "$STORE_CN"
fi

stop_container

# ---------------------------------------------------------------
# Results
# ---------------------------------------------------------------
echo ""
echo "=== Results: $PASS passed, $FAIL failed ==="
if [ "$FAIL" -gt 0 ]; then
    exit 1
fi
