#!/bin/bash
# Quick individual resource test with aggressive timeouts (macOS compatible)
set -eo pipefail

cd "$(dirname "$0")"
eval "$(curl -s 'http://localhost:8080/env?format=terraform')"

TIMEOUT=45  # seconds per resource

run_with_timeout() {
  local secs=$1; shift
  perl -e 'alarm shift; exec @ARGV' "$secs" "$@"
}

test_resource() {
  local resource=$1
  printf "  %-55s " "$resource"
  
  # Clean state
  rm -f terraform.tfstate terraform.tfstate.backup .terraform.tfstate.lock.info
  
  local start=$(date +%s)
  local output
  local exit_code=0
  output=$(run_with_timeout $TIMEOUT terraform apply -target="$resource" -auto-approve -input=false -no-color 2>&1) || exit_code=$?
  local end=$(date +%s)
  local elapsed=$((end - start))
  
  if [ $exit_code -eq 0 ]; then
    if echo "$output" | grep -q "Apply complete"; then
      echo "✓ PASS (${elapsed}s)"
      # Destroy it to clean up
      run_with_timeout $TIMEOUT terraform destroy -target="$resource" -auto-approve -input=false -no-color > /dev/null 2>&1 || true
      return 0
    elif echo "$output" | grep -q "No changes"; then
      echo "✓ PASS (no changes, ${elapsed}s)"
      return 0
    fi
  fi
  
  if [ $exit_code -eq 142 ] || [ $elapsed -ge $TIMEOUT ]; then
    echo "✗ TIMEOUT (>${TIMEOUT}s)"
    echo "$output" | grep -E "Still creating|Error:" | tail -3 | sed 's/^/    /'
  else
    local err=$(echo "$output" | grep -A2 "Error:" | head -5)
    echo "✗ FAIL (${elapsed}s)"
    echo "$err" | sed 's/^/    /'
  fi
  
  # Save full output for debugging
  echo "$output" > /tmp/tf-test-${resource//./_}.log
  return 1
}

echo "=== Testing Individual Resources (${TIMEOUT}s timeout each) ==="
echo ""

PASS=0
FAIL=0
FAILED_RESOURCES=""

RESOURCES=(
  "google_project.tf_project"
  "google_storage_bucket.data_lake"
  "google_pubsub_topic.events"
  "google_bigquery_dataset.analytics"
  "google_secret_manager_secret.api_key"
  "google_cloud_tasks_queue.email_queue"
  "google_redis_instance.cache"
  "google_sql_database_instance.tf_postgres"
  "google_alloydb_cluster.tf_cluster"
  "google_bigtable_instance.tf_btable"
  "google_spanner_instance.tf_instance"
  "google_cloud_scheduler_job.tf_daily_report"
  "google_dataproc_cluster.tf_cluster"
  "google_workflows_workflow.tf_data_pipeline"
  "google_cloudfunctions2_function.tf_hello"
)

for resource in "${RESOURCES[@]}"; do
  if test_resource "$resource"; then
    ((PASS++)) || true
  else
    ((FAIL++)) || true
    FAILED_RESOURCES="$FAILED_RESOURCES\n  $resource"
  fi
done

echo ""
echo "=== Summary ==="
echo "Pass: $PASS"
echo "Fail: $FAIL"
if [ -n "$FAILED_RESOURCES" ]; then
  echo -e "Failed:$FAILED_RESOURCES"
fi
echo ""
echo "Debug logs saved to /tmp/tf-test-*.log"
