#!/bin/bash
cd "$(dirname "$0")"
rm -f terraform.tfstate terraform.tfstate.backup .terraform.tfstate.lock.info

# Source env vars
eval "$(curl -s 'http://localhost:8080/env?format=terraform' | grep '^export')"
export PATH="/opt/homebrew/bin:/usr/local/bin:$PATH"

terraform init -input=false -no-color > /dev/null 2>&1

RESOURCES=(
  "google_project.tf_project"
  "google_storage_bucket.data_lake"
  "google_storage_bucket.artifacts"
  "google_storage_bucket_object.readme"
  "google_pubsub_topic.events"
  "google_pubsub_topic.notifications"
  "google_pubsub_topic.audit_log"
  "google_pubsub_subscription.events_worker"
  "google_pubsub_subscription.notifications_email"
  "google_bigquery_dataset.analytics"
  "google_bigquery_dataset.staging"
  "google_bigquery_table.events"
  "google_bigquery_table.users"
  "google_spanner_instance.tf_instance"
  "google_spanner_database.tf_app_db"
  "google_secret_manager_secret.api_key"
  "google_secret_manager_secret.db_password"
  "google_secret_manager_secret_version.api_key_v1"
  "google_secret_manager_secret_version.db_password_v1"
  "google_cloud_tasks_queue.email_queue"
  "google_cloud_tasks_queue.webhook_queue"
  "google_redis_instance.cache"
  "google_sql_database_instance.tf_postgres"
  "google_sql_database.tf_app_db"
  "google_sql_user.app_user"
  "google_alloydb_cluster.tf_cluster"
  "google_alloydb_instance.tf_primary"
  "google_bigtable_instance.tf_btable"
  "google_bigtable_table.events"
  "google_bigtable_table.user_sessions"
  "google_cloudfunctions2_function.tf_hello"
  "google_cloud_scheduler_job.tf_daily_report"
  "google_cloud_scheduler_job.tf_cleanup"
  "google_dataproc_cluster.tf_cluster"
  "google_workflows_workflow.tf_data_pipeline"
)

PASS=0
FAIL=0
TIMEOUT=0
SKIP_DEP=0

for res in "${RESOURCES[@]}"; do
  echo -n "[TEST] $res ... "
  out=$(terraform apply -target="$res" -auto-approve -input=false -no-color 2>&1)
  exit_code=$?
  
  if [ $exit_code -eq 0 ]; then
    # Check if actually created or just no changes
    if echo "$out" | grep -q "Apply complete"; then
      echo "OK (created)"
      PASS=$((PASS + 1))
    elif echo "$out" | grep -q "No changes"; then
      echo "OK (no changes)"
      PASS=$((PASS + 1))
    else
      # Check for errors
      err=$(echo "$out" | grep "Error:" | head -1)
      if [ -n "$err" ]; then
        echo "FAIL: $err"
        FAIL=$((FAIL + 1))
      else
        echo "OK (unknown output)"
        PASS=$((PASS + 1))
      fi
    fi
  else
    err=$(echo "$out" | grep "Error:" | head -1)
    if [ -n "$err" ]; then
      echo "FAIL: $err"
      FAIL=$((FAIL + 1))
    else
      echo "FAIL (exit=$exit_code)"
      FAIL=$((FAIL + 1))
    fi
  fi
done

echo ""
echo "=== Summary ==="
echo "Pass: $PASS"
echo "Fail: $FAIL"
