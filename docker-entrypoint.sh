#!/bin/bash
set -e

# Ensure data directories exist (Docker volumes from older images may lack them)
mkdir -p /var/lib/localcloud/spanner-data /var/lib/localcloud/gcs-data /var/lib/localcloud/pgdata /var/lib/localcloud/bigquery-data

# BigQuery emulator: persistence is write-only (doesn't reload from SQLite on startup).
# Clear stale database to avoid UNIQUE constraint errors on re-seed.
rm -f /var/lib/localcloud/bigquery-data/bigquery.db
chown -R localcloud:localcloud /var/lib/localcloud/spanner-data /var/lib/localcloud/gcs-data 2>/dev/null || true

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
            *)
                echo "WARNING: Unknown service '${service}' in LOCALCLOUD_SERVICES" >&2
                ;;
        esac
    done
fi

# Auto-seed: load seed data after services are healthy (runs in background)
SEED_FILE="${LOCALCLOUD_SEED_FILE:-/etc/localcloud/seed.yaml}"
if [ -f "$SEED_FILE" ]; then
    (
        echo "Auto-seed: waiting for services to become healthy..."
        for i in $(seq 1 60); do
            if curl -sf http://localhost:8080/_localcloud/health >/dev/null 2>&1; then
                # Wait for external emulators (Spanner, BigQuery, etc.) to be ready
                echo "Auto-seed: gateway healthy, waiting for external emulators..."
                sleep 5
                # Check Spanner readiness if enabled
                if [ "${LOCALCLOUD_ENABLE_SPANNER:-true}" = "true" ]; then
                    for j in $(seq 1 30); do
                        if curl -sf http://localhost:9020/v1/projects/local-project/instances >/dev/null 2>&1; then
                            echo "Auto-seed: Spanner emulator ready"
                            break
                        fi
                        echo "Auto-seed: waiting for Spanner emulator ($j/30)..."
                        sleep 2
                    done
                fi
                # Check BigQuery readiness if enabled
                if [ "${LOCALCLOUD_ENABLE_BIGQUERY:-true}" = "true" ]; then
                    for j in $(seq 1 30); do
                        if curl -sf "http://localhost:9050/bigquery/v2/projects/${LOCALCLOUD_PROJECT:-local-project}/datasets" >/dev/null 2>&1; then
                            echo "Auto-seed: BigQuery emulator ready"
                            break
                        fi
                        echo "Auto-seed: waiting for BigQuery emulator ($j/30)..."
                        sleep 2
                    done
                fi
                echo "Auto-seed: loading $SEED_FILE"
                RESULT=$(curl -s -X POST http://localhost:8080/_localcloud/seed \
                    -H "Content-Type: application/yaml" --data-binary "@${SEED_FILE}" 2>&1)
                echo "Auto-seed: $RESULT"
                break
            fi
            sleep 1
        done
    ) &
fi

exec "$@"
