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

# Ensure data directories exist (volume mounts replace build-time dirs)
mkdir -p /var/lib/localcloud/spanner-data \
         /var/lib/localcloud/gcs-data \
         /var/lib/localcloud/pgdata \
         /var/lib/localcloud/bigquery-data \
         /var/lib/localcloud/logs \
         /var/run/postgresql

# Symlink logs into data dir so bind mounts expose them on host
if [ ! -L /var/log/localcloud ]; then
    rm -rf /var/log/localcloud
    ln -sf /var/lib/localcloud/logs /var/log/localcloud
fi

# Fix ownership: try chown but don't fail — macOS Docker bind mounts don't support chown.
# On Linux with named volumes, chown works and ensures localcloud user can write.
# On macOS with bind mounts, Docker Desktop maps host UID transparently — chown unnecessary.
chown -R "$RUN_UID:$RUN_GID" /var/log/localcloud \
                              /var/run/postgresql 2>/dev/null || true
chown -R "$RUN_UID:$RUN_GID" /var/lib/localcloud 2>/dev/null || true

# Clean up stale PostgreSQL files from unclean shutdown (container kill without stop).
# postmaster.pid prevents startup; stale sockets block connections.
if [ -f "/var/lib/localcloud/pgdata/postmaster.pid" ]; then
    echo "Removing stale postmaster.pid (unclean shutdown)..."
    rm -f /var/lib/localcloud/pgdata/postmaster.pid
fi
rm -f /var/run/postgresql/.s.PGSQL.* 2>/dev/null || true

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

# Drop privileges: run CMD as the runtime user
exec gosu "$RUN_USER" "$@"
