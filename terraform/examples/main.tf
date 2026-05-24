# =============================================================================
# LocalCloud Terraform Example
# =============================================================================
#
# This Terraform config works against both real GCP and LocalCloud.
# To use with LocalCloud:
#
#   1. Start LocalCloud:
#      docker run -d --name localcloud -p 8080:8080 -p 4443:4443 \
#        -p 8085:8085 -p 8086:8086 -p 9010:9010 -p 9020:9020 \
#        -p 9050:9050 -m 4g localcloud/localcloud:latest
#
#   2. Source the Terraform env vars:
#      eval $(curl -s 'http://localhost:8080/env?format=terraform')
#
#   3. Run Terraform:
#      terraform init
#      terraform plan
#      terraform apply
#
# No changes to this file are needed. The GOOGLE_*_CUSTOM_ENDPOINT env vars
# redirect the Google provider to LocalCloud automatically.
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
}

variable "project_id" {
  description = "GCP project ID"
  default     = "local-project"
}

variable "region" {
  description = "GCP region"
  default     = "us-central1"
}

# ─── Cloud Storage ────────────────────────────────────────────────────

resource "google_storage_bucket" "terraform_test" {
  name     = "terraform-test-bucket"
  location = var.region

  force_destroy = true

  labels = {
    managed_by = "terraform"
    env        = "localcloud"
  }
}

# ─── Pub/Sub ──────────────────────────────────────────────────────────

resource "google_pubsub_topic" "terraform_test" {
  name = "terraform-test-topic"
}

resource "google_pubsub_subscription" "terraform_test" {
  name  = "terraform-test-subscription"
  topic = google_pubsub_topic.terraform_test.id

  ack_deadline_seconds = 20
}

# ─── BigQuery ─────────────────────────────────────────────────────────

resource "google_bigquery_dataset" "terraform_test" {
  dataset_id = "terraform_test_dataset"
  location   = var.region

  labels = {
    managed_by = "terraform"
  }
}

resource "google_bigquery_table" "terraform_test" {
  dataset_id = google_bigquery_dataset.terraform_test.dataset_id
  table_id   = "terraform_test_table"

  schema = jsonencode([
    { name = "id", type = "STRING", mode = "REQUIRED" },
    { name = "name", type = "STRING", mode = "NULLABLE" },
    { name = "created_at", type = "TIMESTAMP", mode = "NULLABLE" },
  ])
}

# ─── Secret Manager ──────────────────────────────────────────────────

resource "google_secret_manager_secret" "terraform_test" {
  secret_id = "terraform-test-secret"

  replication {
    auto {}
  }
}

# ─── Outputs ─────────────────────────────────────────────────────────

output "bucket_name" {
  value = google_storage_bucket.terraform_test.name
}

output "topic_name" {
  value = google_pubsub_topic.terraform_test.name
}

output "dataset_id" {
  value = google_bigquery_dataset.terraform_test.dataset_id
}
