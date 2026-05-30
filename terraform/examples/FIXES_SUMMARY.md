# Terraform Test Results — Issues Found & Fixes Applied

## Root Causes & Fixes

| # | Issue | Root Cause | Fix | File Changed |
|---|-------|-----------|-----|---------------|
| 1 | All resources hanging (infinite retry) | ServiceGatingDecorator returned 503 for disabled services. Provider treated 503 as retryable → infinite loop | Changed 503 → 501 (NOT_IMPLEMENTED) which is not retried | `ServiceGatingDecorator.java` |
| 2 | Pub/Sub topics: 404 Not Found | Provider sends REST `PUT /v1/projects/.../topics` to gRPC emulator on port 8085. gRPC emulator doesn't serve HTTP REST | Created `PubSubRestService` with topic+subscription CRUD, registered at gateway (port 8080) | `PubSubRestService.java` (new), `LocalCloudApplication.java`, `AdminApiService.java` |
| 3 | Redis instance hangs | Provider polls LRO operation. Memorystore returned plain instance JSON (no `done:true`). Provider looped forever | Added operations endpoint returning `{"done": true, "response": {...}}` | `MemorystoreAdminService.java` |
| 4 | Workflows hangs on duplicate | Duplicate key returned HTTP 500. Provider treats 500 as retryable → infinite retry | Catch duplicate → return 409 (Conflict) | `WorkflowsRestService.java` |

## Results: Before vs After

| Resource | Before | After |
|----------|--------|-------|
| google_project | ✓ | ✓ |
| google_storage_bucket | ✗ Hang | ✓ |
| google_pubsub_topic | ✗ 404 | ✓ |
| google_redis_instance | ✗ Hang | ✓ |
| google_sql_database_instance | ✗ Hang | ⚠️ ~2 min |
| google_workflows_workflow | ✗ Hang | ✓ |
| google_secret_manager_secret | ✓ | ⚠️ Seed conflict |
| google_cloud_tasks_queue | ✓ | ⚠️ Seed conflict |
| google_alloydb_cluster | ✓ | ✓ |
| google_bigtable_instance | ✓ | ⚠️ Seed conflict |
| google_cloud_scheduler_job | ✓ | ✓ |
| google_dataproc_cluster | ✓ | ✓ |
| google_spanner_instance | ✗ 404 | ✗ 404 (separate issue) |
| google_bigquery_dataset | ✗ 501 | ✗ 501 (upstream bug #26764) |

## Known Remaining Gaps

1. **BigQuery** (501): Terraform Google provider v6 ignores custom endpoints for BigQuery REST API. Upstream bug [#26764](https://github.com/hashicorp/terraform-provider-google/issues/26764)
2. **Spanner** (404): Needs investigation — separate issue
3. **Seed data conflicts**: Auto-seed on server restart creates resources. Need `terraform destroy` before `apply` as cleanup, or use unique names
4. **Cloud SQL**: Slow (~2 min) — may need operations polling optimization

## Files Changed

```
localcloud-server/src/main/java/com/localcloud/gateway/ServiceGatingDecorator.java  (1 line)
localcloud-server/src/main/java/com/localcloud/emulators/pubsub/PubSubRestService.java  (new file)
localcloud-server/src/main/java/com/localcloud/LocalCloudApplication.java  (3 additions)
localcloud-server/src/main/java/com/localcloud/admin/AdminApiService.java  (3 additions)
localcloud-server/src/main/java/com/localcloud/emulators/memorystore/MemorystoreAdminService.java  (+30 lines)
localcloud-server/src/main/java/com/localcloud/emulators/workflows/WorkflowsRestService.java  (3 lines)
```
