# =============================================================================
# LocalCloud Terraform Integration Test — ALL Services
# =============================================================================
#
# Tests infrastructure creation across ALL LocalCloud-emulated services.
# Usage:
#   eval $(curl -s 'http://localhost:8080/env?format=terraform')
#   cd terraform/examples/full-test
#   terraform init
#   terraform plan
#   terraform apply -auto-approve
#   terraform destroy -auto-approve
# =============================================================================

terraform {
  required_version = ">= 1.5"
  required_providers {
    google = {
      source  = "hashicorp/google"
      version = "~> 7.0"
    }
  }
}

provider "google" {
  project = var.project_id
  region  = var.region
  zone    = var.zone
}

variable "project_id" {
  description = "GCP project ID (LocalCloud defaults to local-project)"
  default     = "tf-local-project"
}

variable "region" {
  description = "GCP region"
  default     = "us-west1"
}

variable "zone" {
  description = "GCP zone"
  default     = "us-west1-a"
}

# ======================================================================
# 0. Project (Cloud Resource Manager)
# ======================================================================

resource "google_project" "tf_project" {
  name       = "tf-local-project"
  project_id = "tf-local-project"
  deletion_policy = "DELETE"

  labels = {
    managed_by = "terraform"
    env        = "dev"
  }
}

# ======================================================================
# 1. Cloud Storage (GCS)
# ======================================================================

resource "google_storage_bucket" "data_lake" {
  name          = "tf-data-lake"
  location      = var.region
  force_destroy = true

  labels = {
    env        = "dev"
    managed_by = "terraform"
  }
}

resource "google_storage_bucket" "artifacts" {
  name          = "tf-build-artifacts"
  location      = var.region
  force_destroy = true
}

resource "google_storage_bucket_object" "readme" {
  name    = "README.md"
  bucket  = google_storage_bucket.data_lake.name
  content = "# LocalCloud Data Lake\nCreated by Terraform."
}

# ======================================================================
# 2. Pub/Sub
# ======================================================================

resource "google_pubsub_topic" "events" {
  name = "tf-events"

  message_retention_duration = "86400s"
}

resource "google_pubsub_topic" "notifications" {
  name = "tf-notifications"
}

resource "google_pubsub_topic" "audit_log" {
  name = "tf-audit-log"
}

resource "google_pubsub_subscription" "events_worker" {
  name  = "tf-events-worker"
  topic = google_pubsub_topic.events.name

  ack_deadline_seconds = 20

  retry_policy {
    minimum_backoff = "10s"
    maximum_backoff = "600s"
  }
}

resource "google_pubsub_subscription" "notifications_email" {
  name  = "tf-notifications-email"
  topic = google_pubsub_topic.notifications.name

  ack_deadline_seconds = 10
}

# ======================================================================
# 3. BigQuery
# ======================================================================

resource "google_bigquery_dataset" "analytics" {
  dataset_id = "tf_analytics"
  location   = var.region

  labels = {
    env = "dev"
  }
}

resource "google_bigquery_dataset" "staging" {
  dataset_id = "tf_staging"
  location   = var.region
}

resource "google_bigquery_table" "events" {
  dataset_id          = google_bigquery_dataset.analytics.dataset_id
  table_id            = "events"
  deletion_protection = false

  schema = jsonencode([
    { name = "event_id", type = "STRING", mode = "REQUIRED" },
    { name = "event_type", type = "STRING", mode = "REQUIRED" },
    { name = "user_id", type = "STRING", mode = "NULLABLE" },
    { name = "payload", type = "JSON", mode = "NULLABLE" },
    { name = "ts", type = "TIMESTAMP", mode = "REQUIRED" },
  ])
}

resource "google_bigquery_table" "users" {
  dataset_id          = google_bigquery_dataset.analytics.dataset_id
  table_id            = "users"
  deletion_protection = false

  schema = jsonencode([
    { name = "user_id", type = "STRING", mode = "REQUIRED" },
    { name = "email", type = "STRING", mode = "REQUIRED" },
    { name = "name", type = "STRING", mode = "NULLABLE" },
    { name = "created_at", type = "TIMESTAMP", mode = "REQUIRED" },
  ])
}

# ======================================================================
# 4. Spanner
# ======================================================================

resource "google_spanner_instance" "tf_instance" {
  name         = "tf-spanner-instance"
  display_name = "Terraform Spanner Instance"
  config       = "regional-us-central1"
  num_nodes    = 1

  labels = {
    managed_by = "terraform"
  }
}

resource "google_spanner_database" "tf_app_db" {
  name     = "tf-app-db"
  instance = google_spanner_instance.tf_instance.name
}

# ======================================================================
# 5. Secret Manager
# ======================================================================

resource "google_secret_manager_secret" "api_key" {
  secret_id = "tf-api-key"

  replication {
    auto {}
  }

  labels = {
    env = "dev"
  }
}

resource "google_secret_manager_secret" "db_password" {
  secret_id = "tf-db-password"

  replication {
    auto {}
  }
}

resource "google_secret_manager_secret_version" "api_key_v1" {
  secret      = google_secret_manager_secret.api_key.id
  secret_data = "sk-localcloud-dev-12345"
}

resource "google_secret_manager_secret_version" "db_password_v1" {
  secret      = google_secret_manager_secret.db_password.id
  secret_data = "super-secret-db-password"
}

# ======================================================================
# 6. Cloud Tasks
# ======================================================================

resource "google_cloud_tasks_queue" "email_queue" {
  name     = "tf-email-queue"
  location = var.region

  rate_limits {
    max_dispatches_per_second = 10
    max_concurrent_dispatches = 5
  }

  retry_config {
    max_attempts       = 3
    max_retry_duration = "300s"
    max_backoff        = "60s"
  }
}

resource "google_cloud_tasks_queue" "webhook_queue" {
  name     = "tf-webhook-queue"
  location = var.region

  rate_limits {
    max_dispatches_per_second = 50
    max_concurrent_dispatches = 20
  }
}

# ======================================================================
# 7. Memorystore (Redis)
# ======================================================================

resource "google_redis_instance" "cache" {
  name           = "tf-cache"
  memory_size_gb = 1
  region         = var.region
  redis_version  = "REDIS_7_0"

  labels = {
    managed_by = "terraform"
  }
}

# ======================================================================
# 8. Cloud SQL
# ======================================================================

resource "google_sql_database_instance" "tf_postgres" {
  name             = "tf-postgres-instance"
  database_version = "POSTGRES_15"
  region           = var.region

  settings {
    tier = "db-custom-1-3840"
  }

  deletion_protection = false
}

resource "google_sql_database" "tf_app_db" {
  name     = "tf_app_db"
  instance = google_sql_database_instance.tf_postgres.name
}

resource "google_sql_user" "app_user" {
  name     = "app_user"
  instance = google_sql_database_instance.tf_postgres.name
  password = "dev-password"
}

# ======================================================================
# 9. AlloyDB
# ======================================================================

resource "google_alloydb_cluster" "tf_cluster" {
  cluster_id = "tf-alloydb-cluster"
  location   = var.region

  display_name = "Terraform AlloyDB Cluster"

  initial_user {
    user     = "postgres"
    password = "dev-password"
  }

  labels = {
    managed_by = "terraform"
  }
}

resource "google_alloydb_instance" "tf_primary" {
  cluster       = google_alloydb_cluster.tf_cluster.name
  instance_id   = "tf-alloydb-primary"
  instance_type = "PRIMARY"

  machine_config {
    cpu_count = 2
  }
}

# ======================================================================
# 10. Bigtable
# ======================================================================

resource "google_bigtable_instance" "tf_btable" {
  name = "tf-bigtable"

  cluster {
    cluster_id   = "tf-btable-cluster"
    zone         = var.zone
    num_nodes    = 1
    storage_type = "SSD"
  }

  deletion_protection = false

  labels = {
    managed_by = "terraform"
  }
}

resource "google_bigtable_table" "events" {
  name          = "events"
  instance_name = google_bigtable_instance.tf_btable.name
}

resource "google_bigtable_table" "user_sessions" {
  name          = "user_sessions"
  instance_name = google_bigtable_instance.tf_btable.name
}

# ======================================================================
# 11. Cloud Functions 2nd gen
# ======================================================================

resource "google_cloudfunctions2_function" "tf_hello" {
  name     = "tf-hello-function"
  location = var.region

  build_config {
    runtime     = "nodejs20"
    entry_point = "helloWorld"

    source {
      storage_source {
        bucket = "tf-build-artifacts"
        object = "dummy-source.zip"
      }
    }
  }

  service_config {
    max_instance_count = 3
    min_instance_count = 0
    available_memory   = "256M"
    timeout_seconds    = 60
  }

  labels = {
    managed_by = "terraform"
  }
}

# ======================================================================
# 12. Cloud Scheduler
# ======================================================================

resource "google_cloud_scheduler_job" "tf_daily_report" {
  name      = "tf-daily-report"
  schedule  = "0 9 * * *"
  time_zone = "America/New_York"

  http_target {
    uri         = "https://example.com/daily-report"
    http_method = "POST"
  }

  retry_config {
    retry_count = 3
  }
}

resource "google_cloud_scheduler_job" "tf_cleanup" {
  name      = "tf-cleanup"
  schedule  = "0 */6 * * *"
  time_zone = "UTC"

  http_target {
    uri         = "https://example.com/cleanup"
    http_method = "POST"
  }
}

# ======================================================================
# 13. Dataproc
# ======================================================================

resource "google_dataproc_cluster" "tf_cluster" {
  name   = "tf-dataproc-cluster"
  region = var.region

  cluster_config {
    master_config {
      num_instances = 1
      machine_type  = "e2-medium"
      disk_config {
        boot_disk_size_gb = 50
      }
    }

    worker_config {
      num_instances = 2
      machine_type  = "e2-medium"
      disk_config {
        boot_disk_size_gb = 50
      }
    }
  }

  labels = {
    managed_by = "terraform"
  }
}

# ======================================================================
# 14. Workflows
# ======================================================================

resource "google_workflows_workflow" "tf_data_pipeline" {
  name            = "tf-data-pipeline"
  region          = var.region
  description     = "Terraform-managed data pipeline workflow"
  deletion_protection = false
  service_account = "tf-service@local-project.iam.gserviceaccount.com"

  source_contents = <<EOF
main:
  steps:
    - init:
        assign:
          - start_time: $${sys.now()}
    - log_start:
        call: sys.log
        args:
          text: "Data pipeline started at $${start_time}"
    - fetch_data:
        call: http.get
        args:
          url: https://example.com/api/data
        result: response
    - process:
        assign:
          - status: "completed"
    - log_end:
        call: sys.log
        args:
          text: "Data pipeline completed with status: $${status}"
EOF

  labels = {
    managed_by = "terraform"
  }
}

# ======================================================================
# 15. Cloud Run — DISABLED
# Requires *.googleapis.com DNS → localhost for auth. When enabled, ensure
# Caddy TLS cert is trusted so Go's SecTrust verifier accepts it.
# ======================================================================
# resource "google_cloud_run_v2_service" "tf_web" {
#   name     = "tf-web-service"
#   location = var.region
#
#   template {
#     containers {
#       image = "us-docker.pkg.dev/cloudrun/container/hello"
#       ports {
#         container_port = 8080
#       }
#     }
#
#     scaling {
#       min_instance_count = 0
#       max_instance_count = 10
#     }
#   }
#
#   ingress = "INGRESS_TRAFFIC_ALL"
#
#   labels = {
#     managed_by = "terraform"
#   }
# }

# ======================================================================
# 16. GKE — DISABLED
# Requires *.googleapis.com DNS → localhost for auth. When enabled, ensure
# Caddy TLS cert is trusted so Go's SecTrust verifier accepts it.
# ======================================================================
# resource "google_container_cluster" "tf_dev" {
#   name     = "tf-dev-cluster"
#   location = var.zone
#
#   remove_default_node_pool = true
#   initial_node_count       = 1
#
#   addons_config {
#     http_load_balancing {
#       disabled = false
#     }
#   }
#
#   network_policy {
#     enabled = false
#   }
# }

# ======================================================================
# 17. Compute Engine — DISABLED
# Requires *.googleapis.com DNS → localhost for auth. When enabled, ensure
# Caddy TLS cert is trusted so Go's SecTrust verifier accepts it.
# ======================================================================
# resource "google_compute_instance" "tf_app_server" {
#   name         = "tf-app-server"
#   machine_type = "e2-medium"
#   zone         = var.zone
#
#   boot_disk {
#     initialize_params {
#       image = "debian-cloud/debian-12"
#     }
#   }
#
#   network_interface {
#     network = "default"
#     access_config {
#       # Ephemeral IP
#     }
#   }
#
#   metadata = {
#     startup-script = <<-SCRIPT
#       #!/bin/bash
#       apt-get update && apt-get install -y docker.io
#       systemctl start docker
#       systemctl enable docker
#     SCRIPT
#   }
#
#   labels = {
#     managed_by = "terraform"
#   }
# }

# ======================================================================
# Outputs
# ======================================================================

output "storage_buckets" {
  description = "GCS bucket names"
  value = [
    google_storage_bucket.data_lake.name,
    google_storage_bucket.artifacts.name,
  ]
}

output "pubsub_topics" {
  description = "Pub/Sub topic names"
  value = [
    google_pubsub_topic.events.name,
    google_pubsub_topic.notifications.name,
    google_pubsub_topic.audit_log.name,
  ]
}

output "bigquery_datasets" {
  description = "BigQuery dataset IDs"
  value = [
    google_bigquery_dataset.analytics.dataset_id,
    google_bigquery_dataset.staging.dataset_id,
  ]
}

output "bigquery_tables" {
  description = "BigQuery table IDs"
  value = [
    "${google_bigquery_dataset.analytics.dataset_id}.${google_bigquery_table.events.table_id}",
    "${google_bigquery_dataset.analytics.dataset_id}.${google_bigquery_table.users.table_id}",
  ]
}

output "secrets" {
  description = "Secret Manager secret IDs"
  value = [
    google_secret_manager_secret.api_key.secret_id,
    google_secret_manager_secret.db_password.secret_id,
  ]
}

output "cloud_tasks_queues" {
  description = "Cloud Tasks queue names"
  value = [
    google_cloud_tasks_queue.email_queue.name,
    google_cloud_tasks_queue.webhook_queue.name,
  ]
}

output "sql_instances" {
  description = "Cloud SQL instance names"
  value = [
    google_sql_database_instance.tf_postgres.name,
  ]
}

# output "cloud_run_services" {
#   description = "Cloud Run service names"
#   value = [
#     google_cloud_run_v2_service.tf_web.name,
#   ]
# }

# output "gke_clusters" {
#   description = "GKE cluster names"
#   value = [
#     google_container_cluster.tf_dev.name,
#   ]
# }

# output "compute_instances" {
#   description = "Compute Engine instance names"
#   value = [
#     google_compute_instance.tf_app_server.name,
#   ]
# }

output "alloydb_clusters" {
  description = "AlloyDB cluster names"
  value = [
    google_alloydb_cluster.tf_cluster.cluster_id,
  ]
}

output "workflows" {
  description = "Workflows workflow names"
  value = [
    google_workflows_workflow.tf_data_pipeline.name,
  ]
}

output "dataproc_clusters" {
  description = "Dataproc cluster names"
  value = [
    google_dataproc_cluster.tf_cluster.name,
  ]
}

output "cloud_scheduler_jobs" {
  description = "Cloud Scheduler job names"
  value = [
    google_cloud_scheduler_job.tf_daily_report.name,
    google_cloud_scheduler_job.tf_cleanup.name,
  ]
}

output "bigtable_instances" {
  description = "Bigtable instance names"
  value = [
    google_bigtable_instance.tf_btable.name,
  ]
}
