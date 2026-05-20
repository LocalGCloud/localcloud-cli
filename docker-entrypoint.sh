#!/bin/bash
set -e

# ---------------------------------------------------------------------------
# Custom CA Certificates — two modes:
#
# 1. MANUAL: Mount certs to /etc/localcloud/certs/ (read-only)
#    docker run -v /path/to/certs:/etc/localcloud/certs:ro ...
#
# 2. AUTO-DETECT: Probes googleapis.com on startup. If a corporate proxy
#    or VPN intercepts TLS, extracts the proxy CA from the connection
#    chain and imports it. Zero config needed.
#
# Both import into Java truststore + system CA bundle (Python/curl).
# Disable auto-detect: -e LOCALCLOUD_AUTO_DETECT_CA=false
# ---------------------------------------------------------------------------

import_cert() {
    local cert="$1" alias_name="$2"
    /opt/java/bin/keytool -importcert -trustcacerts \
        -keystore /opt/java/lib/security/cacerts \
        -storepass changeit -noprompt \
        -alias "$alias_name" -file "$cert" 2>/dev/null || return 1
    # Copy to Debian's extra CA dir so update-ca-certificates includes it
    cp "$cert" "/usr/local/share/ca-certificates/${alias_name}.crt"
    return 0
}

ca_imported=0

# --- Mode 1: Manually mounted certs ---
CA_CERT_DIR="/etc/localcloud/certs"
if [ -d "$CA_CERT_DIR" ]; then
    for cert in "$CA_CERT_DIR"/*.pem "$CA_CERT_DIR"/*.crt "$CA_CERT_DIR"/*.cer; do
        [ -f "$cert" ] || continue
        alias_name="localcloud-$(basename "$cert" | sed 's/\.[^.]*$//')"
        if import_cert "$cert" "$alias_name"; then
            ca_imported=$((ca_imported + 1))
        else
            echo "WARNING: Failed to import $cert into Java truststore" >&2
        fi
    done
fi

# --- Mode 2: Auto-detect proxy/VPN CA from TLS probe ---
if [ "${LOCALCLOUD_AUTO_DETECT_CA:-true}" != "false" ]; then
    curl_exit=0
    curl -sf --max-time 5 https://storage.googleapis.com >/dev/null 2>&1 || curl_exit=$?

    if [ "$curl_exit" -eq 60 ] || [ "$curl_exit" -eq 35 ]; then
        # TLS verification failed — likely a proxy intercepting HTTPS
        probe_dir="/tmp/proxy-ca-probe"
        mkdir -p "$probe_dir"

        if timeout 5 openssl s_client -connect storage.googleapis.com:443 -showcerts \
            </dev/null 2>/dev/null | \
            awk '/BEGIN CERTIFICATE/{n++} n>0{print > "'"$probe_dir"'/cert-" sprintf("%02d",n) ".pem"}'; then

            for cert in "$probe_dir"/*.pem; do
                [ -f "$cert" ] || continue
                # Only import CA certs (skip leaf/server cert)
                openssl x509 -in "$cert" -noout -text 2>/dev/null | grep -q "CA:TRUE" || continue

                fingerprint=$(openssl x509 -in "$cert" -noout -fingerprint -sha256 2>/dev/null | \
                              cut -d= -f2 | tr -d ':' | head -c 16 | tr '[:upper:]' '[:lower:]')
                [ -n "$fingerprint" ] || continue

                if import_cert "$cert" "proxy-ca-${fingerprint}"; then
                    ca_imported=$((ca_imported + 1))
                fi
            done
        fi

        rm -rf "$probe_dir"
    fi
fi

if [ "$ca_imported" -gt 0 ]; then
    update-ca-certificates 2>/dev/null || true
    echo "Imported $ca_imported CA certificate(s) into Java truststore and system bundle"
fi

# Resolve runtime user. For bind mounts, the data dir UID may differ from the
# container's localcloud user (e.g. macOS maps host UID 502 into the container).
# Detect actual data dir owner and use that UID for PostgreSQL compatibility.
DATA_DIR_UID=$(stat -c '%u' /var/lib/localcloud 2>/dev/null || echo "")
LOCALCLOUD_UID=$(id -u localcloud 2>/dev/null || echo "999")

if [ -n "$DATA_DIR_UID" ] && [ "$DATA_DIR_UID" != "0" ] && [ "$DATA_DIR_UID" != "$LOCALCLOUD_UID" ]; then
    # Bind mount detected: data dir owned by host UID, not localcloud user.
    # Re-map localcloud user to match the bind mount UID so PostgreSQL ownership check passes.
    usermod -u "$DATA_DIR_UID" localcloud 2>/dev/null || true
    echo "Remapped localcloud user to UID $DATA_DIR_UID (bind mount owner)"
fi

RUN_USER="${LOCALCLOUD_USER:-localcloud}"
RUN_UID=$(id -u "$RUN_USER" 2>/dev/null) || RUN_UID="$RUN_USER"
RUN_GID=$(id -g "$RUN_USER" 2>/dev/null) || RUN_GID="$RUN_USER"

# Ensure data directories exist (volume mounts replace build-time dirs).
# Create data dirs under /var/lib/localcloud as the runtime user so bind-mount
# ownership is correct from the start (macOS Docker Desktop does not support
# chown on bind mounts — the subdirectory inherits the creator's UID).
gosu "$RUN_USER" mkdir -p /var/lib/localcloud/spanner-data \
                          /var/lib/localcloud/gcs-data \
                          /var/lib/localcloud/pgdata \
                          /var/lib/localcloud/bigquery-data \
                          /var/lib/localcloud/redis-data \
                          /var/lib/localcloud/logs

# /var/run/postgresql is on the container filesystem (not a bind mount),
# so root mkdir + explicit chown works fine here.
mkdir -p /var/run/postgresql
chown "$RUN_UID:$RUN_GID" /var/run/postgresql

# Symlink logs into data dir so bind mounts expose them on host
if [ ! -L /var/log/localcloud ]; then
    rm -rf /var/log/localcloud
    ln -sf /var/lib/localcloud/logs /var/log/localcloud
fi

# Clean up stale PostgreSQL files from unclean shutdown (container kill without stop).
# postmaster.pid prevents startup; stale sockets block connections.
if [ -f "/var/lib/localcloud/pgdata/postmaster.pid" ]; then
    echo "Removing stale postmaster.pid (unclean shutdown)..."
    rm -f /var/lib/localcloud/pgdata/postmaster.pid
fi
rm -f /var/run/postgresql/.s.PGSQL.* 2>/dev/null || true

# Spanner LevelDB: Validate data directory but DO NOT wipe it automatically.
# LevelDB corruption is common after unclean shutdowns, but auto-wiping
# causes permanent data loss. We now log a warning and let the user decide.
SPANNER_DATA_DIR="/var/lib/localcloud/spanner-data"
if [ "${LOCALCLOUD_ENABLE_SPANNER:-true}" = "true" ]; then
    if [ -d "$SPANNER_DATA_DIR" ]; then
        HAS_SST_FILES=$(find "$SPANNER_DATA_DIR" -maxdepth 1 -name '*.sst' -o -name '*.ldb' 2>/dev/null | head -1)
        HAS_MANIFEST=$(find "$SPANNER_DATA_DIR" -maxdepth 1 -name 'MANIFEST*' 2>/dev/null | head -1)
        HAS_CURRENT=$(test -f "$SPANNER_DATA_DIR/CURRENT" && echo "yes" || echo "no")

        if [ -n "$HAS_SST_FILES" ] && { [ -z "$HAS_MANIFEST" ] || [ "$HAS_CURRENT" = "no" ]; }; then
            echo "WARNING: Spanner LevelDB data appears incomplete (has SST files but missing MANIFEST/CURRENT)."
            echo "This usually happens after an unclean shutdown. Data might be corrupted or lost."
            echo "Manual recovery or restore from backup may be required."
        fi
        # Reset ID counters in metadata.json to 0 before starting the emulator.
        # This prevents the emulator from seeding generators to final values *before*
        # replaying schema DDL. This ensures replayed schemas receive matching IDs (starting from 0)
        # to correctly read existing LevelDB data, and then generators naturally end up
        # at correct final counters.
        METADATA_FILE="$SPANNER_DATA_DIR/metadata.json"
        if [ -f "$METADATA_FILE" ]; then
            echo "Resetting Spanner metadata ID counters to 0 to align schema replay..."
            /usr/local/bin/python3.12 -c "
import json
try:
    with open('$METADATA_FILE', 'r') as f:
        data = json.load(f)
    def reset_counters(d):
        if isinstance(d, dict):
            for k, v in d.items():
                if k == 'idCounters' and isinstance(v, dict):
                    for ck in v:
                        v[ck] = 0
                else:
                    reset_counters(v)
        elif isinstance(d, list):
            for item in d:
                reset_counters(item)
    reset_counters(data)
    with open('$METADATA_FILE', 'w') as f:
        json.dump(data, f, indent=2)
except Exception as e:
    import sys
    print('Failed to reset counters:', e, file=sys.stderr)
" 2>/dev/null || true
        fi
    fi
fi

# Initialize PostgreSQL if pgdata is empty (bind mounts don't copy build-time data).
# Named volumes auto-populate from the image; bind mounts start empty.
if [ ! -f "/var/lib/localcloud/pgdata/postgresql.conf" ]; then
    echo "Initializing PostgreSQL (first run with bind mount)..."
    gosu "$RUN_USER" /usr/lib/postgresql/17/bin/initdb -D /var/lib/localcloud/pgdata
    gosu "$RUN_USER" /usr/lib/postgresql/17/bin/pg_ctl -D /var/lib/localcloud/pgdata start
    gosu "$RUN_USER" /usr/lib/postgresql/17/bin/createdb -h /var/run/postgresql localcloud
    gosu "$RUN_USER" /usr/lib/postgresql/17/bin/pg_ctl -D /var/lib/localcloud/pgdata stop
    echo "PostgreSQL initialized."
fi

# Service names below must match keys in /etc/localcloud/services.yaml
# If LOCALCLOUD_SERVICES is set, parse it and map to individual enable flags.
# This overrides all LOCALCLOUD_ENABLE_* defaults: only listed services are enabled.
if [ -n "${LOCALCLOUD_SERVICES}" ]; then
    # Default all enable flags to false
    export LOCALCLOUD_ENABLE_GCS="false"
    export LOCALCLOUD_ENABLE_PUBSUB="false"
    export LOCALCLOUD_ENABLE_FIRESTORE="false"
    export LOCALCLOUD_ENABLE_BIGQUERY="false"
    export LOCALCLOUD_ENABLE_SPANNER="false"
    export LOCALCLOUD_ENABLE_BIGTABLE="false"
    export LOCALCLOUD_ENABLE_SECRETMANAGER="false"
    export LOCALCLOUD_ENABLE_CLOUDTASKS="false"
    export LOCALCLOUD_ENABLE_LOGGING="false"
    export LOCALCLOUD_ENABLE_MONITORING="false"
    export LOCALCLOUD_ENABLE_GKE="false"
    export LOCALCLOUD_ENABLE_COMPUTE="false"
    export LOCALCLOUD_ENABLE_CLOUDRUN="false"
    export LOCALCLOUD_ENABLE_MEMORYSTORE="false"
    export LOCALCLOUD_ENABLE_WORKFLOWS="false"
    export LOCALCLOUD_ENABLE_VERTEXAI="false"
    export LOCALCLOUD_ENABLE_KMS="false"
    export LOCALCLOUD_ENABLE_CLOUDSQL="false"

    # Parse comma-separated service list and enable matching services
    IFS=',' read -ra SERVICES <<< "${LOCALCLOUD_SERVICES}"
    for service in "${SERVICES[@]}"; do
        # Trim whitespace and convert to lowercase
        service="$(echo "${service}" | tr -d '[:space:]' | tr '[:upper:]' '[:lower:]')"
        case "${service}" in
            gcs)            export LOCALCLOUD_ENABLE_GCS="true" ;;
            pubsub)         export LOCALCLOUD_ENABLE_PUBSUB="true" ;;
            firestore)      export LOCALCLOUD_ENABLE_FIRESTORE="true" ;;
            bigquery)       export LOCALCLOUD_ENABLE_BIGQUERY="true" ;;
            spanner)        export LOCALCLOUD_ENABLE_SPANNER="true" ;;
            bigtable)       export LOCALCLOUD_ENABLE_BIGTABLE="true" ;;
            secretmanager)  export LOCALCLOUD_ENABLE_SECRETMANAGER="true" ;;
            cloudtasks)     export LOCALCLOUD_ENABLE_CLOUDTASKS="true" ;;
            logging)        export LOCALCLOUD_ENABLE_LOGGING="true" ;;
            monitoring)     export LOCALCLOUD_ENABLE_MONITORING="true" ;;
            gke)            export LOCALCLOUD_ENABLE_GKE="true" ;;
            compute)        export LOCALCLOUD_ENABLE_COMPUTE="true" ;;
            cloudrun)       export LOCALCLOUD_ENABLE_CLOUDRUN="true" ;;
            memorystore)    export LOCALCLOUD_ENABLE_MEMORYSTORE="true" ;;
            workflows)      export LOCALCLOUD_ENABLE_WORKFLOWS="true" ;;
            vertexai)       export LOCALCLOUD_ENABLE_VERTEXAI="true" ;;
            kms)            export LOCALCLOUD_ENABLE_KMS="true" ;;
            cloudsql)       export LOCALCLOUD_ENABLE_CLOUDSQL="true" ;;
            *)
                echo "WARNING: Unknown service '${service}' in LOCALCLOUD_SERVICES" >&2
                ;;
        esac
    done
fi

# Auto-seed: always seed on startup because several emulators are in-memory
# (Pub/Sub, Firestore, Bigtable lose data on restart).
# The seed endpoint uses UPSERT semantics — safe to run repeatedly.
# Strategy: seed fast services immediately, then seed BigQuery/Spanner
# in parallel as they become ready — no blocking.
SEED_FILE="${LOCALCLOUD_SEED_FILE:-/etc/localcloud/seed.yaml}"
if [ -f "$SEED_FILE" ]; then
    (
        echo "Auto-seed: waiting for gateway..."
        for i in $(seq 1 60); do
            if curl -sf http://localhost:8080/_localcloud/health >/dev/null 2>&1; then
                echo "Auto-seed: gateway healthy"

                # Check if this is a first run or a restart.
                # PostgreSQL data persists across restarts; check if seed data already exists.
                HAS_DATA=$(psql -U localcloud -t -c "SELECT CASE WHEN (SELECT count(*) FROM secrets) > 0 THEN 'yes' ELSE 'no' END" 2>/dev/null | tr -d ' ')

                if [ "$HAS_DATA" = "yes" ] && [ "${LOCALCLOUD_FORCE_SEED}" != "true" ]; then
                    # RESTART: Seed volatile (in-memory) services immediately.
                    echo "Auto-seed: restart detected — seeding volatile services (Pub/Sub, Firestore, Bigtable)..."
                    sleep 2
                    RESULT=$(curl -s -X POST "http://localhost:8080/_localcloud/seed?mode=volatile" \
                        -H "Content-Type: application/yaml" --data-binary "@${SEED_FILE}" 2>&1)
                    echo "Auto-seed: volatile seed done: $RESULT"

                    # Also check if BigQuery lost data (DuckDB may have been wiped by older entrypoint).
                    # If BigQuery has zero datasets, re-seed it in background.
                    # BigQuery: DuckDB uses wal_autocheckpoint=4KB for immediate persistence.
                    # The emulator takes ~20s to load the DuckDB file after port opens.
                    # Wait for "Initialized project" in logs before checking for data.
                    # BigQuery: emulator now persists metadata from DuckDB on startup.
                    # Check if datasets already exist before re-seeding.
                    if [ "${LOCALCLOUD_ENABLE_BIGQUERY:-true}" = "true" ]; then
                        (
                            set +e
                            for j in $(seq 1 45); do
                                BQ_RESP=$(curl -sf "http://localhost:9050/bigquery/v2/projects/${LOCALCLOUD_PROJECT:-local-project}/datasets" 2>/dev/null)
                                if [ $? -eq 0 ] && [ -n "$BQ_RESP" ]; then
                                    if echo "$BQ_RESP" | grep -q '"datasetId"'; then
                                        echo "Auto-seed: BigQuery has persistent data, skipping"
                                    else
                                        echo "Auto-seed: BigQuery has no data, seeding..."
                                        curl -s -X POST http://localhost:8080/_localcloud/seed \
                                            -H "Content-Type: application/yaml" --data-binary "@${SEED_FILE}" >/dev/null 2>&1
                                        echo "Auto-seed: BigQuery seed complete"
                                    fi
                                    break
                                fi
                                sleep 2
                            done
                        ) &
                    fi
                else
                    # FIRST RUN: Seed everything.
                    sleep 2
                    echo "Auto-seed: first run — loading all seed data..."
                    RESULT=$(curl -s -X POST http://localhost:8080/_localcloud/seed \
                        -H "Content-Type: application/yaml" --data-binary "@${SEED_FILE}" 2>&1)
                    echo "Auto-seed: phase 1 done: $RESULT"

                    # Wait for slow emulators (BigQuery, Spanner) in parallel, re-seed when ready.
                    if [ "${LOCALCLOUD_ENABLE_SPANNER:-true}" = "true" ]; then
                        (
                            for j in $(seq 1 30); do
                                if curl -sf http://localhost:9020/v1/projects/local-project/instances >/dev/null 2>&1; then
                                    echo "Auto-seed: Spanner ready, seeding..."
                                    curl -s -X POST http://localhost:8080/_localcloud/seed \
                                        -H "Content-Type: application/yaml" --data-binary "@${SEED_FILE}" >/dev/null 2>&1
                                    echo "Auto-seed: Spanner seed complete"
                                    break
                                fi
                                sleep 2
                            done
                        ) &
                    fi

                    if [ "${LOCALCLOUD_ENABLE_BIGQUERY:-true}" = "true" ]; then
                        (
                            for j in $(seq 1 45); do
                                if curl -sf "http://localhost:9050/bigquery/v2/projects/${LOCALCLOUD_PROJECT:-local-project}/datasets" >/dev/null 2>&1; then
                                    echo "Auto-seed: BigQuery ready, seeding..."
                                    curl -s -X POST http://localhost:8080/_localcloud/seed \
                                        -H "Content-Type: application/yaml" --data-binary "@${SEED_FILE}" >/dev/null 2>&1
                                    echo "Auto-seed: BigQuery seed complete"
                                    break
                                fi
                                sleep 2
                            done
                        ) &
                    fi

                    wait
                    echo "Auto-seed: all phases complete"
                fi
                break
            fi
            sleep 1
        done
    ) &
fi

# Set default telemetry API key if not overridden (not baked into image layers)
export LOCALCLOUD_EVENT_API_KEY="${LOCALCLOUD_EVENT_API_KEY:-phc_o9nQDAQjEgsPcamE8pCnhv7ekA8CmA2VQXechLju9LA9}"

# Spanner LevelDB periodic backup: snapshot data directory every 60 seconds.
# This provides a safety net against LevelDB corruption from unclean shutdowns.
# Only runs when Spanner is enabled and data directory exists.
if [ "${LOCALCLOUD_ENABLE_SPANNER:-true}" = "true" ]; then
    (
        SPANNER_DATA_DIR="/var/lib/localcloud/spanner-data"
        SPANNER_BACKUP_DIR="/var/lib/localcloud/spanner-data-backup"
        BACKUP_INTERVAL=60
        LAST_BACKUP=""
        sleep 30  # wait for emulator to start

        while true; do
            if [ -d "$SPANNER_DATA_DIR" ] && [ -f "$SPANNER_DATA_DIR/CURRENT" ]; then
                # Only backup if data has changed (compare file count)
                CUR_COUNT=$(find "$SPANNER_DATA_DIR" -maxdepth 1 -type f 2>/dev/null | wc -l)
                if [ "$CUR_COUNT" != "$LAST_BACKUP" ] && [ "$CUR_COUNT" -gt 0 ]; then
                    # Atomic backup: write to temp dir, then swap
                    TMP_BACKUP="${SPANNER_BACKUP_DIR}.tmp"
                    rm -rf "$TMP_BACKUP" 2>/dev/null
                    if cp -a "$SPANNER_DATA_DIR" "$TMP_BACKUP" 2>/dev/null; then
                        rm -rf "$SPANNER_BACKUP_DIR" 2>/dev/null
                        mv "$TMP_BACKUP" "$SPANNER_BACKUP_DIR"
                        LAST_BACKUP="$CUR_COUNT"
                    fi
                fi
            fi
            sleep $BACKUP_INTERVAL
        done
    ) &
fi

# Version update check (non-blocking background)
# Compares build-time image digest with Docker Hub latest tag.
# No external version file needed — uses Docker Hub API directly.
# Set LOCALCLOUD_SKIP_UPDATE_CHECK=true to disable.
DOCKERHUB_IMAGE="${LOCALCLOUD_DOCKERHUB_IMAGE:-localcloud/localcloud}"
if [ "${LOCALCLOUD_SKIP_UPDATE_CHECK}" != "true" ]; then
    (
        CURRENT_VERSION=$(cat /opt/localcloud/VERSION 2>/dev/null | tr -d '[:space:]')
        CURRENT_DIGEST=$(cat /opt/localcloud/DIGEST 2>/dev/null | tr -d '[:space:]')

        # Query Docker Hub for latest tag digest + last_updated
        HUB_JSON=$(curl -sf --max-time 5 \
            "https://hub.docker.com/v2/repositories/${DOCKERHUB_IMAGE}/tags/latest" 2>/dev/null) || exit 0

        REMOTE_DIGEST=$(echo "$HUB_JSON" | grep -o '"digest"[[:space:]]*:[[:space:]]*"[^"]*"' | head -1 | grep -o '"sha256:[^"]*"' | tr -d '"')
        REMOTE_UPDATED=$(echo "$HUB_JSON" | grep -o '"last_updated"[[:space:]]*:[[:space:]]*"[^"]*"' | head -1 | grep -o '"[^"]*"$' | tr -d '"')

        [ -z "$REMOTE_DIGEST" ] && exit 0

        # Compare: if no local digest baked in, or digests differ → update available
        if [ -n "$CURRENT_DIGEST" ] && [ "$CURRENT_DIGEST" = "$REMOTE_DIGEST" ]; then
            exit 0  # up to date
        fi
        # If no local digest, fall back to date comparison
        if [ -z "$CURRENT_DIGEST" ] && [ -z "$REMOTE_UPDATED" ]; then
            exit 0
        fi

        REMOTE_SHORT=$(echo "$REMOTE_UPDATED" | cut -c1-10)
        echo ""
        echo "╔══════════════════════════════════════════════════════════════╗"
        echo "║  New LocalCloud image available (updated: ${REMOTE_SHORT})"
        echo "║  Current: ${CURRENT_VERSION}"
        echo "║  Run: docker pull ${DOCKERHUB_IMAGE}:latest"
        echo "╚══════════════════════════════════════════════════════════════╝"
        echo ""
        # Write update info for the gateway to serve via health API
        echo "{\"current\":\"${CURRENT_VERSION}\",\"remote_updated\":\"${REMOTE_SHORT}\",\"pull\":\"docker pull ${DOCKERHUB_IMAGE}:latest\"}" \
            > /tmp/localcloud-update-available.json 2>/dev/null || true
    ) &
fi

# License enforcement toggle — if disabled, skip all checks
ENFORCE_LICENSE=$(cat /opt/localcloud/ENFORCE_LICENSE 2>/dev/null || echo "true")
if [ "$ENFORCE_LICENSE" = "false" ]; then
    echo "License enforcement disabled — bypassing all license checks"
    echo "development" > /tmp/localcloud-tier
    echo ""
    echo "╔══════════════════════════════════════════════════════════════════╗"
    echo "║  License enforcement is DISABLED.                               ║"
    echo "║  Build with --build-arg ENFORCE_LICENSE=true to enforce.        ║"
    echo "╚══════════════════════════════════════════════════════════════════╝"
    skip_license_gate=true
fi

# License status banner
echo ""
if [ -n "$LOCALCLOUD_API_KEY" ]; then
    case "$LOCALCLOUD_API_KEY" in
        lck_*) echo "  License: offline key provided" ;;
        lco_*) echo "  License: online key provided" ;;
        *)     echo "  License: key format not recognized" ;;
    esac
else
    if [ "$LOCALCLOUD_LICENSE_SERVER" = "none" ] || [ -z "$LOCALCLOUD_LICENSE_SERVER" ]; then
        echo "  License: development mode (no key required)"
    else
        echo "  License: no key provided — trial or cached license will be used"
    fi
fi
echo ""

# License preflight gate — must pass before supervisord starts external emulators
if [ "${skip_license_gate:-false}" != "true" ]; then
    echo "Checking license..."
    if ! bash /opt/localcloud/license-gate.sh; then
        echo ""
        echo "╔══════════════════════════════════════════════════════════════════╗"
        echo "║  License validation failed. Container will not start.           ║"
        echo "║  Set LOCALCLOUD_API_KEY or get a key at https://localcloud.dev  ║"
        echo "╚══════════════════════════════════════════════════════════════════╝"
        exit 1
    fi
fi

# Drop privileges: run CMD as the runtime user
exec gosu "$RUN_USER" "$@"
