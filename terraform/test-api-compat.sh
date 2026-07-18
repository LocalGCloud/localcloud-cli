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
RESULTS=()

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
        RESULTS+=("{\"description\":\"$desc\",\"status\":\"pass\",\"expected_http\":$expect_code,\"actual_http\":$actual_code}")
    else
        echo "  FAIL  $desc (expected $expect_code, got $actual_code)"
        FAIL=$((FAIL + 1))
        RESULTS+=("{\"description\":\"$desc\",\"status\":\"fail\",\"expected_http\":$expect_code,\"actual_http\":$actual_code}")
    fi
}

test_check() {
    local desc="$1"
    local ok="$2"
    local expected="${3:-true}"
    local actual="${4:-$ok}"

    if [ "$ok" = "true" ]; then
        echo "  PASS  $desc"
        PASS=$((PASS + 1))
        RESULTS+=("{\"description\":\"$desc\",\"status\":\"pass\",\"expected\":\"$expected\",\"actual\":\"$actual\"}")
    else
        echo "  FAIL  $desc (expected $expected, got $actual)"
        FAIL=$((FAIL + 1))
        RESULTS+=("{\"description\":\"$desc\",\"status\":\"fail\",\"expected\":\"$expected\",\"actual\":\"$actual\"}")
    fi
}

api_call() {
    local method="$1"
    local url="$2"
    local data="${3:-}"
    local out="${TMPDIR:-/tmp}/localcloud-api-compat-response-$$.json"

    if [ "$method" = "GET" ]; then
        API_STATUS=$(curl -s -o "$out" -w "%{http_code}" "$url")
    elif [ "$method" = "DELETE" ]; then
        API_STATUS=$(curl -s -o "$out" -w "%{http_code}" -X DELETE "$url")
    elif [ "$method" = "PUT" ]; then
        API_STATUS=$(curl -s -o "$out" -w "%{http_code}" -X PUT -H "Content-Type: application/json" -d "$data" "$url")
    else
        API_STATUS=$(curl -s -o "$out" -w "%{http_code}" -X POST -H "Content-Type: application/json" -d "$data" "$url")
    fi
    API_BODY=$(cat "$out")
    rm -f "$out"
}

json_get() {
    local expr="$1"
    JSON="$API_BODY" EXPR="$expr" python3 - <<'PY'
import json, os
try:
    data = json.loads(os.environ.get("JSON", "") or "{}")
    value = eval(os.environ["EXPR"], {"__builtins__": {}, "len": len}, {"data": data})
    if value is None:
        print("")
    elif isinstance(value, bool):
        print("true" if value else "false")
    else:
        print(value)
except Exception:
    print("")
PY
}

echo "============================================"
echo "  Terraform API Compatibility Test"
echo "============================================"
echo ""

# Wait for health
echo "Waiting for LocalCloud..."
for i in $(seq 1 30); do
    if curl -sf "$BASE/health" > /dev/null 2>&1; then
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

echo "--- Pub/Sub advanced delivery ---"
ADV_SUFFIX="$(date +%s)-$$"
SCHEMA_ID="tf-compat-schema-$ADV_SUFFIX"
SCHEMA_TOPIC="tf-compat-schema-topic-$ADV_SUFFIX"
SEEK_TOPIC="tf-compat-seek-topic-$ADV_SUFFIX"
SEEK_SUB="tf-compat-seek-sub-$ADV_SUFFIX"
SNAPSHOT_ID="tf-compat-snapshot-$ADV_SUFFIX"
DLQ_TOPIC="tf-compat-dlq-topic-$ADV_SUFFIX"
DLQ_SUB="tf-compat-dlq-sub-$ADV_SUFFIX"
DLQ_MAIN_TOPIC="tf-compat-dlq-main-$ADV_SUFFIX"
DLQ_MAIN_SUB="tf-compat-dlq-main-sub-$ADV_SUFFIX"
AVRO_DEF='{"type":"record","name":"CompatMessage","fields":[{"name":"id","type":"string"}]}'
SCHEMA_BODY="{\"type\":\"AVRO\",\"definition\":\"$(printf '%s' "$AVRO_DEF" | python3 -c 'import json,sys; print(json.dumps(sys.stdin.read())[1:-1])')\"}"
VALID_SCHEMA_DATA="$(printf '{"id":"ok"}' | base64 | tr -d '\n')"
INVALID_SCHEMA_DATA="$(printf '{"id":123}' | base64 | tr -d '\n')"
SEEK_DATA="$(printf 'seek-payload' | base64 | tr -d '\n')"
DLQ_DATA="$(printf 'dlq-payload' | base64 | tr -d '\n')"

test_api "Create Pub/Sub schema" POST "$PUBSUB/v1/projects/$PROJECT/schemas?schemaId=$SCHEMA_ID" "$SCHEMA_BODY"
test_api "Get Pub/Sub schema" GET "$PUBSUB/v1/projects/$PROJECT/schemas/$SCHEMA_ID"
test_api "Create schema-bound topic" PUT "$PUBSUB/v1/projects/$PROJECT/topics/$SCHEMA_TOPIC" "{\"schemaSettings\":{\"schema\":\"projects/$PROJECT/schemas/$SCHEMA_ID\",\"encoding\":\"JSON\"}}"
test_api "Publish schema-valid message" POST "$PUBSUB/v1/projects/$PROJECT/topics/$SCHEMA_TOPIC:publish" "{\"messages\":[{\"data\":\"$VALID_SCHEMA_DATA\"}]}"
test_api "Reject schema-invalid message" POST "$PUBSUB/v1/projects/$PROJECT/topics/$SCHEMA_TOPIC:publish" "{\"messages\":[{\"data\":\"$INVALID_SCHEMA_DATA\"}]}" "400"

test_api "Create seek topic" PUT "$PUBSUB/v1/projects/$PROJECT/topics/$SEEK_TOPIC" '{}'
test_api "Create seek subscription" PUT "$PUBSUB/v1/projects/$PROJECT/subscriptions/$SEEK_SUB" "{\"topic\":\"projects/$PROJECT/topics/$SEEK_TOPIC\"}"
test_api "Publish seek message" POST "$PUBSUB/v1/projects/$PROJECT/topics/$SEEK_TOPIC:publish" "{\"messages\":[{\"data\":\"$SEEK_DATA\"}]}"
api_call POST "$PUBSUB/v1/projects/$PROJECT/subscriptions/$SEEK_SUB:pull" '{"maxMessages":1,"returnImmediately":true}'
SEEK_ACK_ID="$(json_get 'data.get("receivedMessages", [{}])[0].get("ackId", "")')"
SEEK_MESSAGE_ID="$(json_get 'data.get("receivedMessages", [{}])[0].get("message", {}).get("messageId", "")')"
test_check "Pull seek source message" "$([ "$API_STATUS" = "200" ] && [ -n "$SEEK_ACK_ID" ] && echo true || echo false)" "HTTP 200 with ackId" "HTTP $API_STATUS"
test_api "Create Pub/Sub snapshot" PUT "$PUBSUB/v1/projects/$PROJECT/snapshots/$SNAPSHOT_ID" "{\"subscription\":\"projects/$PROJECT/subscriptions/$SEEK_SUB\"}"
test_api "Acknowledge seek source message" POST "$PUBSUB/v1/projects/$PROJECT/subscriptions/$SEEK_SUB:acknowledge" "{\"ackIds\":[\"$SEEK_ACK_ID\"]}"
api_call POST "$PUBSUB/v1/projects/$PROJECT/subscriptions/$SEEK_SUB:pull" '{"maxMessages":1,"returnImmediately":true}'
SEEK_EMPTY_AFTER_ACK="$(json_get 'len(data.get("receivedMessages", [])) == 0')"
test_check "Acked message is not immediately pullable" "$([ "$API_STATUS" = "200" ] && [ "$SEEK_EMPTY_AFTER_ACK" = "true" ] && echo true || echo false)" "empty pull after ack" "HTTP $API_STATUS"
test_api "Seek subscription to snapshot" POST "$PUBSUB/v1/projects/$PROJECT/subscriptions/$SEEK_SUB:seek" "{\"snapshot\":\"projects/$PROJECT/snapshots/$SNAPSHOT_ID\"}"
api_call POST "$PUBSUB/v1/projects/$PROJECT/subscriptions/$SEEK_SUB:pull" '{"maxMessages":1,"returnImmediately":true}'
SEEK_REPLAYED_ID="$(json_get 'data.get("receivedMessages", [{}])[0].get("message", {}).get("messageId", "")')"
test_check "Seek replays snapshotted message" "$([ "$API_STATUS" = "200" ] && [ "$SEEK_REPLAYED_ID" = "$SEEK_MESSAGE_ID" ] && [ -n "$SEEK_REPLAYED_ID" ] && echo true || echo false)" "same messageId after seek" "HTTP $API_STATUS"

test_api "Create DLQ topic" PUT "$PUBSUB/v1/projects/$PROJECT/topics/$DLQ_TOPIC" '{}'
test_api "Create DLQ subscription" PUT "$PUBSUB/v1/projects/$PROJECT/subscriptions/$DLQ_SUB" "{\"topic\":\"projects/$PROJECT/topics/$DLQ_TOPIC\"}"
test_api "Create DLQ main topic" PUT "$PUBSUB/v1/projects/$PROJECT/topics/$DLQ_MAIN_TOPIC" '{}'
test_api "Create subscription with dead-letter policy" PUT "$PUBSUB/v1/projects/$PROJECT/subscriptions/$DLQ_MAIN_SUB" "{\"topic\":\"projects/$PROJECT/topics/$DLQ_MAIN_TOPIC\",\"deadLetterPolicy\":{\"deadLetterTopic\":\"projects/$PROJECT/topics/$DLQ_TOPIC\",\"maxDeliveryAttempts\":5}}"
test_api "Publish DLQ source message" POST "$PUBSUB/v1/projects/$PROJECT/topics/$DLQ_MAIN_TOPIC:publish" "{\"messages\":[{\"data\":\"$DLQ_DATA\"}]}"
DLQ_ATTEMPTS_OK=true
for attempt in 1 2 3 4 5; do
    api_call POST "$PUBSUB/v1/projects/$PROJECT/subscriptions/$DLQ_MAIN_SUB:pull" '{"maxMessages":1,"returnImmediately":true}'
    DLQ_ACK_ID="$(json_get 'data.get("receivedMessages", [{}])[0].get("ackId", "")')"
    if [ "$API_STATUS" != "200" ] || [ -z "$DLQ_ACK_ID" ]; then
        DLQ_ATTEMPTS_OK=false
        break
    fi
    test_api "Expire DLQ delivery attempt $attempt" POST "$PUBSUB/v1/projects/$PROJECT/subscriptions/$DLQ_MAIN_SUB:modifyAckDeadline" "{\"ackIds\":[\"$DLQ_ACK_ID\"],\"ackDeadlineSeconds\":0}"
done
test_check "Redelivered DLQ source message through max attempts" "$DLQ_ATTEMPTS_OK" "five pullable attempts" "$DLQ_ATTEMPTS_OK"
api_call POST "$PUBSUB/v1/projects/$PROJECT/subscriptions/$DLQ_MAIN_SUB:pull" '{"maxMessages":1,"returnImmediately":true}'
DLQ_MAIN_EMPTY="$(json_get 'len(data.get("receivedMessages", [])) == 0')"
test_check "Message leaves source subscription after max attempts" "$([ "$API_STATUS" = "200" ] && [ "$DLQ_MAIN_EMPTY" = "true" ] && echo true || echo false)" "empty source pull after DLQ forwarding" "HTTP $API_STATUS"
api_call POST "$PUBSUB/v1/projects/$PROJECT/subscriptions/$DLQ_SUB:pull" '{"maxMessages":1,"returnImmediately":true}'
DLQ_FORWARDED="$(json_get 'len(data.get("receivedMessages", [])) == 1')"
DLQ_SOURCE_ATTR="$(json_get 'data.get("receivedMessages", [{}])[0].get("message", {}).get("attributes", {}).get("CloudPubSubDeadLetterSourceSubscription", "")')"
test_check "Dead-letter policy forwards message to DLQ topic" "$([ "$API_STATUS" = "200" ] && [ "$DLQ_FORWARDED" = "true" ] && [ "$DLQ_SOURCE_ATTR" = "$DLQ_MAIN_SUB" ] && echo true || echo false)" "DLQ pull with source attribute" "HTTP $API_STATUS"

test_api "Delete schema-bound topic" DELETE "$PUBSUB/v1/projects/$PROJECT/topics/$SCHEMA_TOPIC"
test_api "Delete Pub/Sub schema" DELETE "$PUBSUB/v1/projects/$PROJECT/schemas/$SCHEMA_ID"
test_api "Delete Pub/Sub snapshot" DELETE "$PUBSUB/v1/projects/$PROJECT/snapshots/$SNAPSHOT_ID"
test_api "Delete seek subscription" DELETE "$PUBSUB/v1/projects/$PROJECT/subscriptions/$SEEK_SUB"
test_api "Delete seek topic" DELETE "$PUBSUB/v1/projects/$PROJECT/topics/$SEEK_TOPIC"
test_api "Delete DLQ main subscription" DELETE "$PUBSUB/v1/projects/$PROJECT/subscriptions/$DLQ_MAIN_SUB"
test_api "Delete DLQ subscription" DELETE "$PUBSUB/v1/projects/$PROJECT/subscriptions/$DLQ_SUB"
test_api "Delete DLQ main topic" DELETE "$PUBSUB/v1/projects/$PROJECT/topics/$DLQ_MAIN_TOPIC"
test_api "Delete DLQ topic" DELETE "$PUBSUB/v1/projects/$PROJECT/topics/$DLQ_TOPIC"
echo ""
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
curl -s -X POST "$BASE/services/compute/enable" > /dev/null 2>&1
sleep 1

echo "--- Compute Engine (REST) ---"
test_api "Create instance" POST "$BASE/compute/v1/projects/$PROJECT/zones/us-central1-a/instances" '{"name":"tf-compat-vm","machineType":"e2-micro","networkInterfaces":[{}]}'
test_api "Get instance" GET "$BASE/compute/v1/projects/$PROJECT/zones/us-central1-a/instances/tf-compat-vm"
test_api "List instances" GET "$BASE/compute/v1/projects/$PROJECT/zones/us-central1-a/instances"
test_api "Delete instance" DELETE "$BASE/compute/v1/projects/$PROJECT/zones/us-central1-a/instances/tf-compat-vm"
echo ""

# ─── Cloud Resource Manager (google_project) ─────────────────────

echo "--- Cloud Resource Manager (google_project) ---"
test_api "Create project (v3)" POST "$BASE/v3/projects" '{"projectId":"tf-compat-project","name":"TF Compat Project","labels":{"env":"test"}}'
test_api "Get project (v3)" GET "$BASE/v3/projects/tf-compat-project"
test_api "List projects (v3)" GET "$BASE/v3/projects"
test_api "Update project (v3)" PATCH "$BASE/v3/projects/tf-compat-project" '{"name":"TF Compat Updated","labels":{"env":"test","updated":"true"}}'
test_api "Get project (v1)" GET "$BASE/v1/projects/tf-compat-project"
test_api "List projects (v1)" GET "$BASE/v1/projects"
test_api "Delete project (v3)" DELETE "$BASE/v3/projects/tf-compat-project"
test_api "Get deleted project (v3)" GET "$BASE/v3/projects/tf-compat-project" "" "404"
test_api "Get deleted project (v1)" GET "$BASE/v1/projects/tf-compat-project" "" "404"
test_api "Cannot delete default project (v3)" DELETE "$BASE/v3/projects/local-project" "" "403"
test_api "Cannot delete default project (v1)" DELETE "$BASE/v1/projects/local-project" "" "403"
echo ""

# ─── Summary ─────────────────────────────────────────────────────

echo "============================================"
echo "  Results: $PASS passed, $FAIL failed"
echo "============================================"

mkdir -p build/compatibility
{
    echo "{"
    echo "  \"evidence_id\": \"terraform:api-compat-script\","
    echo "  \"generated_at\": \"$(date -u +%Y-%m-%dT%H:%M:%SZ)\","
    echo "  \"pass\": $PASS,"
    echo "  \"fail\": $FAIL,"
    echo "  \"results\": ["
    for i in "${!RESULTS[@]}"; do
        comma=","
        [ "$i" -eq "$((${#RESULTS[@]} - 1))" ] && comma=""
        echo "    ${RESULTS[$i]}$comma"
    done
    echo "  ]"
    echo "}"
} > build/compatibility/terraform-api-compat.json

if [ $FAIL -gt 0 ]; then
    exit 1
fi
