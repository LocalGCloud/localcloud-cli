#!/bin/bash
# Pre-cleanup helper: scrubs all tf-* resources from LocalCloud
# Run before terraform apply to ensure clean state.
set -euo pipefail

LOCALCLOUD_URL="${LOCALCLOUD_URL:-http://localhost:8080}"
CONTAINER="${CONTAINER:-localcloud}"

echo "=== LocalCloud Pre-Cleanup ==="

# 1. PostgreSQL cleanup — delete all tf-* prefixed resources
echo -n "  DB tables... "
docker exec "$CONTAINER" psql -U localcloud -d localcloud -q -c "
  DELETE FROM pubsub_topics WHERE topic_id LIKE 'tf-%';
  DELETE FROM pubsub_subscriptions WHERE subscription_id LIKE 'tf-%';
  DELETE FROM pubsub_messages;
  DELETE FROM secrets WHERE secret_id LIKE 'tf-%';
  DELETE FROM secret_versions WHERE secret_id LIKE 'tf-%';
  DELETE FROM task_queues WHERE queue_id LIKE 'tf-%';
  DELETE FROM workflows WHERE workflow_id LIKE 'tf-%';
  DELETE FROM cloud_scheduler_jobs WHERE job_name LIKE 'tf-%';
  DELETE FROM cloud_functions WHERE function_name LIKE 'tf-%';
  DELETE FROM dataproc_clusters WHERE cluster_name LIKE 'tf-%';
  DELETE FROM alloydbi_clusters WHERE cluster_id LIKE 'tf-%';
  DELETE FROM alloydbi_instances WHERE instance_id LIKE 'tf-%';
  DELETE FROM bigtable_instances WHERE instance_id LIKE 'tf-%';
  DELETE FROM bigtable_tables WHERE table_name LIKE 'tf-%';
  DELETE FROM cloudsql_instances WHERE instance_id LIKE 'tf-%';
  DELETE FROM cloudsql_databases WHERE database_name LIKE 'tf-%';
  DELETE FROM cloudsql_users WHERE user_name LIKE 'tf-%';
  DELETE FROM spanner_instances WHERE instance_name LIKE 'tf-%';
  DELETE FROM memorystore_instances WHERE instance_id LIKE 'tf-%';
" 2>&1 | grep -c DELETE | xargs echo -n "rows cleaned"
echo ""

# 2. Filesystem cleanup
echo -n "  Filesystem... "
docker exec "$CONTAINER" sh -c '
  rm -rf /var/lib/localcloud/gcs-data/tf-* 2>/dev/null
  rm -rf /var/lib/localcloud/spanner-data/* 2>/dev/null
' && echo "done"

# 3. API reset (clears remaining data)
echo -n "  API reset... "
curl -s -o /dev/null -X POST "$LOCALCLOUD_URL/reset" \
  -H 'Content-Type: application/json' \
  -d '{"restore_seed": false}' && echo "done"

echo "=== Cleanup complete ==="
