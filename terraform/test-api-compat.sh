#!/bin/bash
# =============================================================================
# Terraform API Compatibility Test
# =============================================================================
# Tests the REST API endpoints that Terraform's Google provider calls.
# Run against a running LocalCloud instance.
#
# Usage:
#   docker compose up -d
#   bash terraform/test-api-compat.sh
# =============================================================================

set -e

BASE="http://localhost:8080"
GCS="http://localhost:4443"
PUBSUB="http://localhost:8085"
BQ="http://localhost:9050"
SPANNER="http://localhost:9020"
PROJECT="local-project"

PASS=0
FAIL=0

test_api() {
    local desc="$1"
    local method="$2"
    local url="$3"
    local data="$4"
    local expect_code="${5:-200}"

    local actual_code
    if [ "$method" = "GET" ]; then
        actual_code=$(curl -s -o /dev/null -w "%{http_code}" "$url")
    elif [ "$method" = "DELETE" ]; then
        actual_code=$(curl -s -o /dev/null -w "%{http_code}" -X DELETE "$url")
    elif [ "$method" = "PUT" ]; then
        actual_code=$(curl -s -o /dev/null -w "%{http_code}" -X PUT -H "Content-Type: application/json" -d "$data" "$url")
    else
        actual_code=$(curl -s -o /dev/null -w "%{http_code}" -X POST -H "Content-Type: application/json" -d "$data" "$url")
    fi

    if [ "$actual_code" = "$expect_code" ]; then
        echo "  PASS  $desc (HTTP $actual_code)"
        PASS=$((PASS + 1))
    else
        echo "  FAIL  $desc (expected $expect_code, got $actual_code)"
        FAIL=$((FAIL + 1))
    fi
}

echo "============================================"
echo "  Terraform API Compatibility Test"
echo "============================================"
echo ""

# Wait for health
echo "Waiting for LocalCloud..."
for i in $(seq 1 30); do
    if curl -sf "$BASE/_localcloud/health" > /dev/null 2>&1; then
        echo "LocalCloud is healthy"
        break
    fi
    sleep 1
done
echo ""

# ─── Phase 1: GCS ─────────────────────────────────────────────────

echo "--- Cloud Storage (GCS) ---"
test_api "Create bucket" POST "$GCS/storage/v1/b?project=$PROJECT" '{"name":"tf-compat-test"}'
test_api "Get bucket" GET "$GCS/storage/v1/b/tf-compat-test"
test_api "List buckets" GET "$GCS/storage/v1/b?project=$PROJECT"
test_api "Delete bucket" DELETE "$GCS/storage/v1/b/tf-compat-test"
test_api "Get deleted bucket" GET "$GCS/storage/v1/b/tf-compat-test" "" "404"
echo ""

# ─── Phase 1: Pub/Sub ─────────────────────────────────────────────

echo "--- Pub/Sub ---"
test_api "Create topic" PUT "$PUBSUB/v1/projects/$PROJECT/topics/tf-compat-topic" '{}'
test_api "Get topic" GET "$PUBSUB/v1/projects/$PROJECT/topics/tf-compat-topic"
test_api "List topics" GET "$PUBSUB/v1/projects/$PROJECT/topics"
test_api "Create subscription" PUT "$PUBSUB/v1/projects/$PROJECT/subscriptions/tf-compat-sub" "{\"topic\":\"projects/$PROJECT/topics/tf-compat-topic\"}"
test_api "Get subscription" GET "$PUBSUB/v1/projects/$PROJECT/subscriptions/tf-compat-sub"
test_api "Delete subscription" DELETE "$PUBSUB/v1/projects/$PROJECT/subscriptions/tf-compat-sub"
test_api "Delete topic" DELETE "$PUBSUB/v1/projects/$PROJECT/topics/tf-compat-topic"
echo ""

# ─── Phase 1: BigQuery ────────────────────────────────────────────

echo "--- BigQuery ---"
test_api "Create dataset" POST "$BQ/bigquery/v2/projects/$PROJECT/datasets" '{"datasetReference":{"datasetId":"tf_compat_ds","projectId":"local-project"}}'
test_api "Get dataset" GET "$BQ/bigquery/v2/projects/$PROJECT/datasets/tf_compat_ds"
test_api "List datasets" GET "$BQ/bigquery/v2/projects/$PROJECT/datasets"
test_api "Create table" POST "$BQ/bigquery/v2/projects/$PROJECT/datasets/tf_compat_ds/tables" '{"tableReference":{"tableId":"tf_compat_tbl"},"schema":{"fields":[{"name":"id","type":"STRING"}]}}'
test_api "Get table" GET "$BQ/bigquery/v2/projects/$PROJECT/datasets/tf_compat_ds/tables/tf_compat_tbl"
test_api "Delete table" DELETE "$BQ/bigquery/v2/projects/$PROJECT/datasets/tf_compat_ds/tables/tf_compat_tbl"
test_api "Delete dataset" DELETE "$BQ/bigquery/v2/projects/$PROJECT/datasets/tf_compat_ds?deleteContents=true"
echo ""

# ─── Phase 1: Spanner ─────────────────────────────────────────────

echo "--- Spanner ---"
test_api "List instances" GET "$SPANNER/v1/projects/$PROJECT/instances"
test_api "List databases" GET "$SPANNER/v1/projects/$PROJECT/instances/local-instance/databases"
echo ""

# ─── Phase 2: Secret Manager (gRPC transcoding) ──────────────────

echo "--- Secret Manager (REST via gRPC transcoding) ---"
test_api "Create secret" POST "$BASE/v1/projects/$PROJECT/secrets?secretId=tf-compat-secret" '{"replication":{"automatic":{}}}'
test_api "Get secret" GET "$BASE/v1/projects/$PROJECT/secrets/tf-compat-secret"
test_api "List secrets" GET "$BASE/v1/projects/$PROJECT/secrets"
test_api "Delete secret" DELETE "$BASE/v1/projects/$PROJECT/secrets/tf-compat-secret"
echo ""

# ─── Phase 2: Cloud Tasks (gRPC transcoding) ─────────────────────

echo "--- Cloud Tasks (REST via gRPC transcoding) ---"
test_api "Create queue" POST "$BASE/v2/projects/$PROJECT/locations/us-central1/queues" '{"name":"projects/'$PROJECT'/locations/us-central1/queues/tf-compat-queue"}'
test_api "Get queue" GET "$BASE/v2/projects/$PROJECT/locations/us-central1/queues/tf-compat-queue"
test_api "List queues" GET "$BASE/v2/projects/$PROJECT/locations/us-central1/queues"
test_api "Delete queue" DELETE "$BASE/v2/projects/$PROJECT/locations/us-central1/queues/tf-compat-queue"
echo ""

# ─── Phase 3: Compute Engine (REST) ──────────────────────────────

# Enable compute for testing (disabled by default)
curl -s -X POST "$BASE/_localcloud/services/compute/enable" > /dev/null 2>&1
sleep 1

echo "--- Compute Engine (REST) ---"
test_api "Create instance" POST "$BASE/compute/v1/projects/$PROJECT/zones/us-central1-a/instances" '{"name":"tf-compat-vm","machineType":"e2-micro","networkInterfaces":[{}]}'
test_api "Get instance" GET "$BASE/compute/v1/projects/$PROJECT/zones/us-central1-a/instances/tf-compat-vm"
test_api "List instances" GET "$BASE/compute/v1/projects/$PROJECT/zones/us-central1-a/instances"
test_api "Delete instance" DELETE "$BASE/compute/v1/projects/$PROJECT/zones/us-central1-a/instances/tf-compat-vm"
echo ""

# ─── Summary ─────────────────────────────────────────────────────

echo "============================================"
echo "  Results: $PASS passed, $FAIL failed"
echo "============================================"

if [ $FAIL -gt 0 ]; then
    exit 1
fi
