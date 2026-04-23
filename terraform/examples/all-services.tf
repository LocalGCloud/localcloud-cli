# =============================================================================
# LocalCloud Terraform Test — All Supported Services
# =============================================================================
#
# Tests infrastructure creation across all LocalCloud services.
# Usage:
#   eval $(curl -s 'http://localhost:8080/_localcloud/env?format=terraform')
#   cd terraform/examples
#   terraform init
#   terraform plan
#   terraform apply -auto-approve
#   terraform destroy -auto-approve
# =============================================================================

terraform {
  required_providers {
    google = {
      source  = "hashicorp/google"
      version = ">= 5.0"
    }
  }
}

provider "google" {
  project = var.project_id
  region  = var.region
  zone    = var.zone
}

variable "project_id" {
  default = "local-project"
}

variable "region" {
  default = "us-central1"
}

variable "zone" {
  default = "us-central1-a"
}

# ─── Cloud Storage ────────────────────────────────────────────────────

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

# ─── Pub/Sub ──────────────────────────────────────────────────────────

resource "google_pubsub_topic" "events" {
  name = "tf-events"
}

resource "google_pubsub_topic" "notifications" {
  name = "tf-notifications"
}

resource "google_pubsub_subscription" "events_worker" {
  name  = "tf-events-worker"
  topic = google_pubsub_topic.events.id

  ack_deadline_seconds = 20

  retry_policy {
    minimum_backoff = "10s"
    maximum_backoff = "600s"
  }
}

resource "google_pubsub_subscription" "notifications_email" {
  name  = "tf-notifications-email"
  topic = google_pubsub_topic.notifications.id

  ack_deadline_seconds = 10
}

# ─── BigQuery ─────────────────────────────────────────────────────────

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
    { name = "timestamp", type = "TIMESTAMP", mode = "REQUIRED" },
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

# ─── Spanner ──────────────────────────────────────────────────────────

# Note: Spanner instance is pre-created by LocalCloud seed data.
# Terraform can create databases within the existing instance.

# ─── Secret Manager ──────────────────────────────────────────────────

resource "google_secret_manager_secret" "api_key" {
  secret_id = "tf-api-key"

  replication {
    auto {}
  }
}

resource "google_secret_manager_secret" "db_password" {
  secret_id = "tf-db-password"

  replication {
    auto {}
  }
}

# ─── Outputs ─────────────────────────────────────────────────────────

output "storage_buckets" {
  value = [
    google_storage_bucket.data_lake.name,
    google_storage_bucket.artifacts.name,
  ]
}

output "pubsub_topics" {
  value = [
    google_pubsub_topic.events.name,
    google_pubsub_topic.notifications.name,
  ]
}

output "bigquery_datasets" {
  value = [
    google_bigquery_dataset.analytics.dataset_id,
    google_bigquery_dataset.staging.dataset_id,
  ]
}

output "bigquery_tables" {
  value = [
    "${google_bigquery_dataset.analytics.dataset_id}.${google_bigquery_table.events.table_id}",
    "${google_bigquery_dataset.analytics.dataset_id}.${google_bigquery_table.users.table_id}",
  ]
}

output "secrets" {
  value = [
    google_secret_manager_secret.api_key.secret_id,
    google_secret_manager_secret.db_password.secret_id,
  ]
}
