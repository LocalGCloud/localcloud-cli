# Terraform Compatibility Gap Analysis & Plan

## Summary

Reviewed all 35 Terraform resources in `terraform/examples/all-services.tf` against the localcloud emulator APIs. Of these, 19 were successfully created in an end-to-end test. Multiple bugs were found and fixed during the review. Two critical issues remain.

## Changes Made (Fixes Applied)

### 1. Workflows: `user_env_vars` PGobject Serialization Bug
- **File**: `WorkflowsServiceImpl.java`
- **Problem**: PostgreSQL JSONB columns (`user_env_vars`, `labels`, `tags`) returned as `PGobject` from JDBC. Jackson serialized PGobject's `isNull()` getter as `"null": false`, causing Terraform provider to fail parsing `map(string)`.
- **Fix**: Added `jsonbToMap()` helper that extracts JSON string from PGobject and parses to proper `Map<String, Object>`. Applied to all three JSONB fields in `formatWorkflow()` and `formatExecution()`.

### 2. Workflows: Missing Operations Polling Endpoint
- **File**: `WorkflowsRestService.java`
- **Problem**: Terraform provider creates workflow, gets back operation, then polls `GET /v1/projects/{project}/locations/{location}/operations/{operation}`. This endpoint didn't exist, causing infinite retries.
- **Fix**: Added `@Get("/projects/{project}/locations/{location}/operations/{operation}")` that returns `done: true` with the workflow response.

### 3. Cloud Resource Manager: Service Not Enabled
- **Files**: `start.sh`, `docker-entrypoint.sh`
- **Problem**: `cloudresourcemanager` was not in `LOCALCLOUD_SERVICES` list. Entrypoint script had no mapping for it in the case statement.
- **Fix**: Added `cloudresourcemanager` to both `LOCALCLOUD_SERVICES` in start.sh and the case statement in docker-entrypoint.sh (with `LOCALCLOUD_ENABLE_CLOUDRESOURCEMANAGER` flag).

### 4. Cloud Resource Manager: Wrong Env Var Name
- **File**: `services.yaml`
- **Problem**: `terraformEnvVar` was `GOOGLE_CLOUD_RESOURCE_MANAGER_CUSTOM_ENDPOINT` but Terraform Google provider uses `GOOGLE_RESOURCE_MANAGER_CUSTOM_ENDPOINT` (without `CLOUD_`).
- **Fix**: Corrected the env var name in services.yaml.

### 5. Cloud Resource Manager: Double `/v1/` Prefix in Custom Endpoint
- **File**: `AdminApiService.java`
- **Problem**: The env var generation added `/v1/` suffix for all facade services. CRM's Go client already includes `v1/` in its request paths, causing `/v1/v1/projects` double prefix.
- **Fix**: Added special case for `cloudresourcemanager` in the version prefix switch to return empty string.

### 6. Cloud Resource Manager: Missing Response Fields
- **File**: `CloudResourceManagerRestService.java`
- **Problem**: Project response was missing `projectNumber`, `updateTime`, and `@type` annotation, causing "inconsistent result" error.
- **Fix**: Added these fields to `toGoogleProject()`. Generates deterministic project number from project ID hash.

### 7. Cloud Resource Manager: Missing Operations GET Endpoint
- **File**: `CloudResourceManagerRestService.java`
- **Problem**: Provider polls `GET /v1/operations/{name}` after project creation. This endpoint didn't exist.
- **Fix**: Added `@Get("/operations/{operation}")` returning a done operation response.

## Verified Working Resources

| Resource | Status | Notes |
|----------|--------|-------|
| `google_project` | ⚠️ Inconsistent result | All API fixes applied, still needs root cause investigation |
| `google_storage_bucket` | ✅ Working | Confirmed in full test; transient 404 in isolated test |
| `google_storage_bucket_object` | ✅ Working | Depends on bucket |
| `google_pubsub_topic` | ✅ Working | Confirmed in full test |
| `google_pubsub_subscription` | ✅ Working | Depends on topic |
| `google_bigquery_dataset` | ✅ Working | Confirmed in full test |
| `google_bigquery_table` | Not tested | Depends on dataset |
| `google_spanner_instance` | ✅ Working | Confirmed in full test |
| `google_spanner_database` | Not tested | Depends on instance |
| `google_secret_manager_secret` | ✅ Working | Confirmed in full + isolated test |
| `google_secret_manager_secret_version` | Not tested | Depends on secret |
| `google_cloud_tasks_queue` | ✅ Working | Confirmed in full test |
| `google_redis_instance` | ✅ Working | Confirmed in full test |
| `google_sql_database_instance` | ✅ Working | Confirmed in full test |
| `google_sql_database` | Not tested | Depends on instance |
| `google_sql_user` | Not tested | Depends on instance |
| `google_alloydb_cluster` | ✅ Working | Confirmed in full test |
| `google_alloydb_instance` | Not tested | Depends on cluster |
| `google_bigtable_instance` | ✅ Working | Confirmed in full test |
| `google_bigtable_table` | Not tested | Depends on instance |
| `google_cloudfunctions2_function` | Not tested | Depends on bucket |
| `google_cloud_scheduler_job` | ✅ Working | Confirmed in full test |
| `google_dataproc_cluster` | ✅ Working | Confirmed in full test |
| `google_workflows_workflow` | ✅ Working | Both create + operations (FIXED) |

## Remaining Issues

### Issue 1: `google_project` — "Root object was present, but now absent"
- **Symptom**: `terraform apply` creates project (200 OK), reads it back (200 OK), but reports "Provider produced inconsistent result after apply: Root object was present, but now absent."
- **Analysis**: The CRM API create and read both work correctly (verified via curl). The provider's `resourceGoogleProjectRead` is called after create and returns nil/empty state. This may be caused by:
  - The provider's `Projects.Get()` not using the correct API path for the read operation
  - Missing `parent` field in project response (provider v6 requires `org_id` or `folder_id`)
  - The provider using CRM v3 for read while create used v1
- **Next Step**: Need to add DEBUG-level provider tracing and inspect the exact HTTP request/response for the read operation. May need to implement CRM v3 API separately or add `parent` field.

### Issue 2: Transient 404s for GCS and PubSub
- **Symptom**: Isolated `-target` tests for GCS bucket and PubSub topic show 404 errors
- **Likely Cause**: These are external emulators (fake-gcs-server on 4443, Pub/Sub emulator on 8085). The 404s are from create operations, possibly due to:
  - Bucket name format mismatch
  - Authentication token not being forwarded
  - Emulator not fully initialized at time of test
- **Note**: Both services succeeded in the full 19-resource test, suggesting the emulators are functional but may have startup race conditions.

## Key Learnings for Future Resource Addition

1. **JSONB columns in PostgreSQL**: Always use a helper to deserialize PGobject values. Don't rely on Jackson's bean serialization.
2. **Operations/LRO polling**: Every service that returns an Operation must also have a GET endpoint for `/operations/{name}`.
3. **Env var naming**: Must match exactly what the Terraform Google provider expects. Check `config.go` in the provider source.
4. **Version prefixes in custom endpoints**: For facade services, determine whether the Go client strips or includes the version prefix by checking debug logs (`TF_LOG=DEBUG`).
5. **Response format completeness**: Include all fields the Terraform provider schema expects (`@type`, timestamps, identifiers). Generate fake but deterministic values for IDs/numbers.

## Build & Deploy Commands

```bash
# Rebuild server + Docker + restart (after any code change)
cd localcloud-server && ./gradlew shadowJar
cd .. && docker build -t localcloud/localcloud:latest .
./start.sh

# Or use the all-in-one dev script
./dev.sh
```
