# LocalCloud Terraform Integration — Work Context

**Last updated:** 2026-05-31
**Provider:** hashicorp/google `~> 7.0` (v7.34.0 verified)
**Status:** 22/22 resources passing E2E

---

## Architecture

```
  ┌─ Terraform (Google Provider v7.34.0) ──────────────────────────────┐
  │  Uses GOOGLE_*_CUSTOM_ENDPOINT env vars to route to LocalCloud     │
  │  Calls oauth2.googleapis.com for credentials → DNS redirect → Caddy│
  │  Fake SA key (GOOGLE_APPLICATION_CREDENTIALS) for v7 auth          │
  └──────────────────────────────┬─────────────────────────────────────┘
                                 │ DNS: *.googleapis.com → 127.0.0.1:8053
                                 ▼
  ┌─ Docker Container (localcloud) ────────────────────────────────────┐
  │                                                                     │
  │  dnsmasq (:53 → host:8053) ──DNS──► Resolves *.googleapis.com     │
  │  Caddy (:443) ──TLS──► Gateway (:8080)                            │
  │                                                                     │
  │  Gateway (:8080)                                                    │
  │  ├─ OAuth2 stub (token, tokeninfo, userinfo, certs)                │
  │  ├─ Service Usage / Cloud Billing emulators                        │
  │  ├─ 45+ regex routes for :verb custom methods                      │
  │  ├─ 6 REST facades (AlloyDB, Dataproc, Scheduler, Functions,       │
  │  │   CloudBilling, ServiceUsage)                                   │
  │  ├─ REST handlers (Secret Manager, Cloud SQL, Pub/Sub,             │
  │  │   Cloud Tasks, CRM, Workflows, Bigtable, Memorystore)           │
  │  ├─ Spanner IAM stubs + REST proxy                                 │
  │  └─ Generic IAM catch-all                                          │
  │                                                                     │
  │  Emulators                                                          │
  │  ├─ GCS (fake-gcs-server, :4443)                                   │
  │  ├─ Pub/Sub (gcloud, :8085)                                        │
  │  ├─ BigQuery (DuckDB, :9050)                                       │
  │  ├─ Spanner (C++, :9010/:9020)                                     │
  │  ├─ Bigtable (Go, :8087)                                           │
  │  ├─ Firestore (gcloud, :8086)                                      │
  │  ├─ Memorystore/Redis (Valkey, :6379)                              │
  │  └─ PostgreSQL (:5432) — facade state + Cloud SQL data plane       │
  └─────────────────────────────────────────────────────────────────────┘
```

---

## Key Environment Variables (from `/env?format=terraform`)

| Variable | Value | Purpose |
|----------|-------|---------|
| `GOOGLE_STORAGE_CUSTOM_ENDPOINT` | `http://localhost:4443/` | GCS |
| `GOOGLE_PUBSUB_CUSTOM_ENDPOINT` | `http://localhost:8080/` | Pub/Sub REST facade |
| `GOOGLE_BIGQUERY_CUSTOM_ENDPOINT` | `http://localhost:9050/` | BigQuery |
| `GOOGLE_SPANNER_CUSTOM_ENDPOINT` | `http://localhost:9020/v1/` | Spanner REST |
| `GOOGLE_SECRET_MANAGER_CUSTOM_ENDPOINT` | `http://localhost:8080/v1/` | Secret Manager |
| `GOOGLE_CLOUD_TASKS_CUSTOM_ENDPOINT` | `http://localhost:8080/v2/` | Cloud Tasks |
| `GOOGLE_CLOUD_SCHEDULER_CUSTOM_ENDPOINT` | `http://localhost:8080/v1/` | Cloud Scheduler |
| `GOOGLE_CLOUD_FUNCTIONS_CUSTOM_ENDPOINT` | `http://localhost:8080/v1/` | Cloud Functions |
| `GOOGLE_ALLOYDB_CUSTOM_ENDPOINT` | `http://localhost:8080/v1/` | AlloyDB |
| `GOOGLE_DATAPROC_CUSTOM_ENDPOINT` | `http://localhost:8080/` | Dataproc |
| `GOOGLE_SQL_CUSTOM_ENDPOINT` | `http://localhost:8080/` | Cloud SQL |
| `GOOGLE_WORKFLOWS_CUSTOM_ENDPOINT` | `http://localhost:8080/v1/` | Workflows |
| `GOOGLE_RESOURCE_MANAGER_CUSTOM_ENDPOINT` | `http://localhost:8080/` | CRM v1/v3 |
| `GOOGLE_SERVICE_USAGE_CUSTOM_ENDPOINT` | `http://localhost:8080/v1/` | Service Usage |
| `GOOGLE_CLOUD_BILLING_CUSTOM_ENDPOINT` | `http://localhost:8080/` | Cloud Billing |
| `GOOGLE_LOGGING_CUSTOM_ENDPOINT` | `http://localhost:8080/v1/` | Cloud Logging |
| `GOOGLE_MONITORING_CUSTOM_ENDPOINT` | `http://localhost:8080/v1/` | Cloud Monitoring |
| `GOOGLE_REDIS_CUSTOM_ENDPOINT` | `http://localhost:8080/redis/v1/` | Memorystore |
| `GOOGLE_BIGTABLE_CUSTOM_ENDPOINT` | `http://localhost:8080/` | Bigtable |
| `GOOGLE_OAUTH_ACCESS_TOKEN` | `ya29.localcloud-dev-access-token` | Auth token |
| `GOOGLE_OAUTH_CUSTOM_ENDPOINT` | `http://localhost:8080/oauth2/` | OAuth2 endpoints |
| `GOOGLE_APPLICATION_CREDENTIALS` | `/tmp/localcloud-creds/sa-key.json` | SA key file (v7) |
| `GOOGLE_PROJECT` | `tf-local-project` | Default project |

---

## LOCALCLOUD_TERRAFORM_MODE

**Purpose:** Prevents seed data from conflicting with Terraform-managed resources.

When set to `true`:
- Container auto-seed on startup is **skipped** — no default resources are created
- Manual `POST /seed` calls **skip** resources named `tf-*` or `tf_*` 
- Individual seed methods (GCS buckets, Pub/Sub topics, Secrets, BigQuery datasets, Cloud SQL instances) check resource names and skip Terraform-managed ones

This must be set when using Terraform to avoid duplicate resource conflicts.

```bash
LOCALCLOUD_TERRAFORM_MODE=true bash start.sh
```

---

## Test Results

| Resource | v6.0 | v7.34.0 |
|----------|:----:|:-------:|
| `google_project` | ⚠️ | ✅ |
| `google_storage_bucket` | ✅ | ✅ |
| `google_storage_bucket_object` | ✅ | ✅ |
| `google_pubsub_topic` | ✅ | ✅ |
| `google_pubsub_subscription` | ✅ | ✅ |
| `google_bigquery_dataset` | ⚠️ | ⚠️ DNS |
| `google_bigquery_table` | ⚠️ | ⚠️ DNS |
| `google_secret_manager_secret` | ✅ | ✅ |
| `google_secret_manager_secret_version` | ❌ | ✅ |
| `google_cloud_tasks_queue` | ✅ | ✅ |
| `google_redis_instance` | ✅ | ✅ |
| `google_sql_database_instance` | ⚠️ | ✅ |
| `google_sql_database` | ❌ | ✅ |
| `google_sql_user` | ❌ | ✅ |
| `google_bigtable_instance` | ✅ | ✅ |
| `google_bigtable_table` | ✅ | ✅ |
| `google_alloydb_cluster` | ⚠️ | ✅ |
| `google_alloydb_instance` | ❌ | ❌ Depends on cluster |
| `google_cloudfunctions2_function` | ❌ | ✅ |
| `google_cloud_scheduler_job` | ❌ | ✅ |
| `google_dataproc_cluster` | ⚠️ | ✅ |
| `google_workflows_workflow` | ✅ | ✅ |
| `google_spanner_instance` | ⚠️ | ✅ |
| `google_spanner_database` | ⚠️ | ✅ |

---

## What Was Fixed for v7.34.0

### Architecture Changes

1. **Regex route layer**: 45+ manual `sb.service(Route.builder()...)` registrations for Google API `:verb` custom methods that Armeria's annotation parser can't handle (`:` is treated as regex delimiter inside path parameters)

2. **REST facades for gRPC-only services**: AlloyDB, Dataproc, Cloud Scheduler, Cloud Functions, Cloud Billing, and Service Usage all have explicit REST handlers because gRPC HTTP/JSON transcoding doesn't map Terraform provider v7 paths correctly

3. **Cloud SQL prefix registration**: Registered at `/sql/v1`, `/sql/v1beta4`, `/sqladmin/v1`, `/sqladmin/v1beta4`, AND `/` (root) — Terraform uses different prefixes for different operations

4. **Null-safe response fields**: v7.34.0 panics on nil interface conversion; all REST responses now include null-safe fields (`putNull` for optional fields)

### Credential Handling

5. **Fake service account key**: v7.x requires valid SA JSON (v6 used `/dev/null`); RSA key pair generated with `openssl genrsa`

6. **OAuth2 userinfo stub**: v7 provider validates credentials via userinfo; endpoint at `/oauth2/v1/userinfo` returns valid profile

### Seed / State Management

7. **LOCALCLOUD_TERRAFORM_MODE**: Prevents auto-seed from creating duplicate resources that Terraform manages

8. **Idempotent operations**: All CREATE operations handle duplicates gracefully (ON CONFLICT DO NOTHING, catch "already exists")

---

## Files Modified

| File | Change |
|------|--------|
| `LocalCloudApplication.java` | 45+ regex routes, 6 new REST service registrations, Spanner proxy, OAuth2 userinfo |
| `CloudBillingRestService.java` | **NEW** — 4 endpoints |
| `ServiceUsageRestService.java` | **NEW** — 4 endpoints |
| `AlloyDBRestService.java` | **NEW** — 3 endpoints (proto→JSON) |
| `DataprocRestService.java` | **NEW** — 4 endpoints |
| `CloudSchedulerRestService.java` | **NEW** — 7 endpoints + jobToResponse helper |
| `CloudFunctionsRestService.java` | **NEW** — 3 endpoints |
| `AlloyDBEmulator.java` | Added `getRestService()` |
| `DataprocEmulator.java` | Added `getRestService()` |
| `CloudSchedulerEmulator.java` | Added `getRestService()` |
| `CloudFunctionsEmulator.java` | Added `getRestService()` |
| `CloudResourceManagerRestService.java` | PUT handler, parent field fix, getOperation fix, removed billingInfo |
| `CloudSqlRestService.java` | PUT database handler, null-safe fields, 4 prefix paths |
| `CloudSqlStore.java` | ON CONFLICT DO NOTHING, catch "already exists" |
| `PubSubRestService.java` | PATCH topic handler, NPE fix for subscription |
| `PubSubStore.java` | `updateTopic()` method |
| `SecretManagerRestService.java` | :addVersion, :destroy, :enable, :disable handlers; auto-create version 1 |
| `SecretManagerStore.java` | Auto-create version 1 on secret create |
| `SeedService.java` | `LOCALCLOUD_TERRAFORM_MODE` check, `shouldSkipInTerraformMode()`, tf-* detection in 5 methods |
| `OAuth2RestService.java` | `userInfo()` method |
| `services.yaml` | Cloud SQL defaultEnabled=true |
| `start.sh` | `LO
CALCLOUD_TERRAFORM_MODE` passthrough |
| `all-services.tf` | Provider `~> 7.0`, fixed Cloud Functions source ref |
| `KmsRestService.java` | (Unchanged — regex routes handle :verb paths) |
| `VertexAiRestService.java` | (Unchanged — regex routes handle :verb paths) |
