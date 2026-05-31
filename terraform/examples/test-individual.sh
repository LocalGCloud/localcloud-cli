#!/bin/bash
# =============================================================================
# Individual resource test — 10s timeout per resource, capture failures
# =============================================================================
set -euo pipefail

cd "$(dirname "$0")"
rm -rf .terraform .terraform.lock.hcl terraform.tfstate terraform.tfstate.backup 2>/dev/null

# Source env vars
eval "$(curl -sf 'http://localhost:8080/env?format=terraform' | grep '^export')"
export GOOGLE_PROJECT=tf-local-project
export TF_LOG=ERROR

terraform init -input=false -no-color > /dev/null 2>&1
echo "Terraform initialized"

# Resource test list — in dependency order
# Format: "resource_name|depends_on_previous|timeout_seconds"
RESOURCES=(
  "google_project.tf_project|none|15"
  "google_storage_bucket.data_lake|google_project|15"
  "google_storage_bucket.artifacts|google_project|15"
  "google_storage_bucket_object.readme|google_storage_bucket|10"
  "google_pubsub_topic.events|google_project|15"
  "google_pubsub_topic.notifications|google_project|15"
  "google_pubsub_topic.audit_log|google_project|15"
  "google_pubsub_subscription.events_worker|google_pubsub|15"
  "google_pubsub_subscription.notifications_email|google_pubsub|15"
  "google_bigquery_dataset.analytics|google_project|15"
  "google_bigquery_dataset.staging|google_project|15"
  "google_bigquery_table.events|google_bigquery|15"
  "google_bigquery_table.users|google_bigquery|15"
  "google_spanner_instance.tf_instance|google_project|20"
  "google_spanner_database.tf_app_db|google_spanner|15"
  "google_secret_manager_secret.api_key|google_project|15"
  "google_secret_manager_secret.db_password|google_project|15"
  "google_secret_manager_secret_version.api_key_v1|secret|15"
  "google_secret_manager_secret_version.db_password_v1|secret|15"
  "google_cloud_tasks_queue.email_queue|google_project|15"
  "google_cloud_tasks_queue.webhook_queue|google_project|15"
  "google_redis_instance.cache|google_project|15"
  "google_sql_database_instance.tf_postgres|google_project|20"
  "google_sql_database.tf_app_db|sql|15"
  "google_sql_user.app_user|sql|15"
  "google_alloydb_cluster.tf_cluster|google_project|15"
  "google_alloydb_instance.tf_primary|alloydb|15"
  "google_bigtable_instance.tf_btable|google_project|15"
  "google_bigtable_table.events|bigtable|15"
  "google_bigtable_table.user_sessions|bigtable|15"
  "google_cloudfunctions2_function.tf_hello|google_project|15"
  "google_cloud_scheduler_job.tf_daily_report|google_project|15"
  "google_cloud_scheduler_job.tf_cleanup|google_project|15"
  "google_dataproc_cluster.tf_cluster|google_project|15"
  "google_workflows_workflow.tf_data_pipeline|google_project|15"
)

PASS=0
FAIL=0
SKIP=0
TOTAL=${#RESOURCES[@]}
RESULTS_FILE="/tmp/tf_results.txt"
> "$RESULTS_FILE"

run_with_timeout() {
  local target="$1" timeout_secs="$2" outfile="$3"
  perl -e "alarm $timeout_secs; exec @ARGV" -- \
    terraform apply -target="$target" -auto-approve -input=false -no-color \
    > "$outfile" 2>&1
  local ec=$?
  if [ $ec -eq 142 ] || [ $ec -eq 124 ]; then
    echo "TIMEOUT"
    return 124
  fi
  return $ec
}

echo ""
echo "========================================="
echo "  Testing $TOTAL resources individually"
echo "========================================="
echo ""

for entry in "${RESOURCES[@]}"; do
  IFS='|' read -r resource depends timeout_secs <<< "$entry"
  
  # Check if dependency failed
  if [ "$depends" != "none" ]; then
    if grep -q "^FAIL|$depends" "$RESULTS_FILE" 2>/dev/null; then
      echo "[SKIP] $resource (depends on failed: $depends)"
      echo "SKIP|$resource|dep_fail" >> "$RESULTS_FILE"
      SKIP=$((SKIP + 1))
      continue
    fi
  fi
  
  echo -n "[TEST] $resource "
  outfile="/tmp/tf_test_${resource//\//_}.log"
  
  # Run with timeout
  run_with_timeout "$resource" "$timeout_secs" "$outfile"
  ec=$?
  
  if [ $ec -eq 124 ]; then
    echo "(TIMEOUT after ${timeout_secs}s)"
    echo "FAIL|$resource|TIMEOUT" >> "$RESULTS_FILE"
    FAIL=$((FAIL + 1))
    # Show last few lines of output
    tail -5 "$outfile" | sed 's/^/    /'
  elif [ $ec -eq 0 ]; then
    if grep -q "Apply complete\|No changes" "$outfile"; then
      created=$(grep -c "Creation complete" "$outfile" || echo "0")
      echo "OK (created=$created)"
      echo "PASS|$resource|created" >> "$RESULTS_FILE"
      PASS=$((PASS + 1))
    elif grep -q "Error:" "$outfile"; then
      err=$(grep "Error:" "$outfile" | head -1 | cut -c1-120)
      echo "FAIL: $err"
      echo "FAIL|$resource|$err" >> "$RESULTS_FILE"
      FAIL=$((FAIL + 1))
    else
      echo "OK (no changes/unknown)"
      echo "PASS|$resource|no_change" >> "$RESULTS_FILE"
      PASS=$((PASS + 1))
    fi
  else
    err=$(grep -i "Error:" "$outfile" | head -1 | cut -c1-150)
    if [ -z "$err" ]; then
      err="exit_code=$ec"
    fi
    echo "FAIL: $err"
    echo "FAIL|$resource|$err" >> "$RESULTS_FILE"
    FAIL=$((FAIL + 1))
    tail -5 "$outfile" | sed 's/^/    /'
  fi
done

echo ""
echo "========================================="
echo "  Summary"
echo "========================================="
echo "  Pass: $PASS"
echo "  Fail: $FAIL"
echo "  Skip: $SKIP"
echo "  Total: $TOTAL"
echo ""

# Show all failures
echo ""
echo "=== Failures ==="
grep "^FAIL" "$RESULTS_FILE" || echo "  None!"

# Show all passes
echo ""
echo "=== Passes ==="
grep "^PASS" "$RESULTS_FILE" | head -30
[ $(grep -c "^PASS" "$RESULTS_FILE") -gt 30 ] && echo "  ... and $(($(grep -c "^PASS" "$RESULTS_FILE") - 30)) more"
