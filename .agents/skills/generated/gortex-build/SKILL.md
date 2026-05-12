---
name: gortex-build
description: "Work in the build area — 138 symbols across 45 files (58% cohesion)"
---

# build

138 symbols | 45 files | 58% cohesion

## When to Use

Use this skill when working on files in:
- `examples/python-sdk-demo/src/pages/Dashboard.jsx`
- `localcloud-console/src/api.js`
- `localcloud-server/src/main/java/com/localcloud/admin/AdminApiService.java`
- `localcloud-server/src/main/java/com/localcloud/admin/BigtableGrpcClient.java`
- `localcloud-server/src/main/java/com/localcloud/admin/BrowseService.java`
- `localcloud-server/src/main/java/com/localcloud/admin/CredentialBroker.java`
- `localcloud-server/src/main/java/com/localcloud/admin/ExportService.java`
- `localcloud-server/src/main/java/com/localcloud/admin/MutateService.java`
- `localcloud-server/src/main/java/com/localcloud/admin/ProjectService.java`
- `localcloud-server/src/main/java/com/localcloud/admin/SupervisorClient.java`
- `localcloud-server/src/main/java/com/localcloud/admin/TelemetryService.java`
- `localcloud-server/src/main/java/com/localcloud/admin/UsageMetricsRepository.java`
- `localcloud-server/src/main/java/com/localcloud/admin/bigtablesql/SqlFunctions.java`
- `localcloud-server/src/main/java/com/localcloud/config/LocalCloudConfig.java`
- `localcloud-server/src/main/java/com/localcloud/config/ServiceRegistry.java`
- `localcloud-server/src/main/java/com/localcloud/emulators/cloudtasks/CloudTasksStore.java`
- `localcloud-server/src/main/java/com/localcloud/emulators/compute/ComputeRestService.java`
- `localcloud-server/src/main/java/com/localcloud/emulators/secretmanager/SecretManagerRestService.java`
- `localcloud-server/src/main/java/com/localcloud/emulators/secretmanager/SecretManagerStore.java`
- `localcloud-server/src/main/java/com/localcloud/emulators/workflows/WorkflowConnectorService.java`
- `localcloud-server/src/main/java/com/localcloud/emulators/workflows/WorkflowsServiceImpl.java`
- `localcloud-server/src/main/java/com/localcloud/emulators/workflows/engine/ExecutionContext.java`
- `localcloud-server/src/main/java/com/localcloud/emulators/workflows/engine/WorkflowException.java`
- `localcloud-server/src/main/java/com/localcloud/gateway/HealthCheckService.java`
- `localcloud-server/src/main/java/com/localcloud/gateway/ProcessHealthChecker.java`
- `localcloud-server/src/main/java/com/localcloud/sync/SyncAdapter.java`
- `localcloud-server/src/main/java/com/localcloud/sync/SyncApiService.java`
- `localcloud-server/src/main/java/com/localcloud/sync/SyncCredentialRepository.java`
- `localcloud-server/src/main/java/com/localcloud/sync/SyncManifestRepository.java`
- `localcloud-server/src/main/java/com/localcloud/sync/SyncProgressCallback.java`
- `localcloud-server/src/main/java/com/localcloud/sync/SyncService.java`
- `localcloud-server/src/main/java/com/localcloud/sync/adapters/BigQuerySyncAdapter.java`
- `localcloud-server/src/main/java/com/localcloud/sync/adapters/BigtableSyncAdapter.java`
- `localcloud-server/src/main/java/com/localcloud/sync/adapters/FirestoreSyncAdapter.java`
- `localcloud-server/src/main/java/com/localcloud/sync/adapters/GcsSyncAdapter.java`
- `localcloud-server/src/main/java/com/localcloud/sync/adapters/SpannerSyncAdapter.java`
- `localcloud-server/src/test/java/com/localcloud/emulators/secretmanager/SecretManagerRestServiceTest.java`
- `localcloud-server/src/test/java/com/localcloud/emulators/workflows/WorkflowsServiceImplTest.java`
- `localcloud-server/src/test/java/com/localcloud/emulators/workflows/expression/ExpressionEvaluatorTest.java`
- `localcloud-server/src/test/java/com/localcloud/sync/SyncCancelResumeTest.java`
- `localcloud-server/src/test/java/com/localcloud/sync/SyncIntegrationTest.java`
- `localcloud-server/src/test/java/com/localcloud/sync/SyncManifestRepositoryTest.java`
- `localcloud-server/src/test/java/com/localcloud/sync/SyncServiceTest.java`
- `localcloud-server/src/test/java/com/localcloud/sync/adapters/BigtableSyncAdapterTest.java`
- `localcloud-server/src/test/java/com/localcloud/sync/adapters/FirestoreSyncAdapterTest.java`

## Key Files

| File | Symbols |
|------|---------|
| `examples/python-sdk-demo/src/pages/Dashboard.jsx` | Dashboard, getTotalRequests, formatUptime |
| `localcloud-console/src/api.js` | put |
| `localcloud-server/src/main/java/com/localcloud/admin/AdminApiService.java` | routing, getServiceConfig, requests |
| `localcloud-server/src/main/java/com/localcloud/admin/BigtableGrpcClient.java` | listInstancesWithDetails |
| `localcloud-server/src/main/java/com/localcloud/admin/BrowseService.java` | browseLogging, browseMemorystore, browseWorkflows, browseCloudTasks, browseSecretManager, ... |
| `localcloud-server/src/main/java/com/localcloud/admin/CredentialBroker.java` | getStatus |
| `localcloud-server/src/main/java/com/localcloud/admin/ExportService.java` | exportMemorystore, export, exportCloudTasks |
| `localcloud-server/src/main/java/com/localcloud/admin/MutateService.java` | toFirestoreValue |
| `localcloud-server/src/main/java/com/localcloud/admin/ProjectService.java` | listProjects |
| `localcloud-server/src/main/java/com/localcloud/admin/SupervisorClient.java` | getProcessStatus, extractXmlValue |
| `localcloud-server/src/main/java/com/localcloud/admin/TelemetryService.java` | recordServiceError, collectStats, start, trySend, buildEventJson, ... |
| `localcloud-server/src/main/java/com/localcloud/admin/UsageMetricsRepository.java` | getGlobalCounts |
| `localcloud-server/src/main/java/com/localcloud/admin/bigtablesql/SqlFunctions.java` | register |
| `localcloud-server/src/main/java/com/localcloud/config/LocalCloudConfig.java` | isPersistenceEnabled, getGcpCredentialSource |
| `localcloud-server/src/main/java/com/localcloud/config/ServiceRegistry.java` | isExternal, isFacade, getAllServices |
| `localcloud-server/src/main/java/com/localcloud/emulators/cloudtasks/CloudTasksStore.java` | extractQueueRow |
| `localcloud-server/src/main/java/com/localcloud/emulators/compute/ComputeRestService.java` | instanceToJson, operationJson |
| `localcloud-server/src/main/java/com/localcloud/emulators/secretmanager/SecretManagerRestService.java` | listSecrets |
| `localcloud-server/src/main/java/com/localcloud/emulators/secretmanager/SecretManagerStore.java` | listSecretVersions, getSecretVersion, listSecrets |
| `localcloud-server/src/main/java/com/localcloud/emulators/workflows/WorkflowConnectorService.java` | importWorkflow |
| `localcloud-server/src/main/java/com/localcloud/emulators/workflows/WorkflowsServiceImpl.java` | exportExecutionData, formatStepEntry, deleteExecutionHistory, createWorkflow, formatWorkflow, ... |
| `localcloud-server/src/main/java/com/localcloud/emulators/workflows/engine/ExecutionContext.java` | recordStep |
| `localcloud-server/src/main/java/com/localcloud/emulators/workflows/engine/WorkflowException.java` | toErrorMap |
| `localcloud-server/src/main/java/com/localcloud/gateway/HealthCheckService.java` | health, services, usage, serviceHealth |
| `localcloud-server/src/main/java/com/localcloud/gateway/ProcessHealthChecker.java` | checkTcp, getAllStatuses, checkAll, checkHttp |
| `localcloud-server/src/main/java/com/localcloud/sync/SyncAdapter.java` | sync |
| `localcloud-server/src/main/java/com/localcloud/sync/SyncApiService.java` | startSync |
| `localcloud-server/src/main/java/com/localcloud/sync/SyncCredentialRepository.java` | getCredentialData |
| `localcloud-server/src/main/java/com/localcloud/sync/SyncManifestRepository.java` | getById, updateProgress, resultSetRowToMap |
| `localcloud-server/src/main/java/com/localcloud/sync/SyncProgressCallback.java` | onProgress |
| `localcloud-server/src/main/java/com/localcloud/sync/SyncService.java` | startSyncAsync, filtersToJson, getAccessToken |
| `localcloud-server/src/main/java/com/localcloud/sync/adapters/BigQuerySyncAdapter.java` | ensureLocalDataset, deleteLocal, ensureLocalTable, browseRemote, gcpGet |
| `localcloud-server/src/main/java/com/localcloud/sync/adapters/BigtableSyncAdapter.java` | extractChunks, buildReadRowsRequest, browseRemote, sync, gcpGet |
| `localcloud-server/src/main/java/com/localcloud/sync/adapters/FirestoreSyncAdapter.java` | buildRunQuery, buildFirestoreValue, previewRemote, browseRemote, buildFieldFilter, ... |
| `localcloud-server/src/main/java/com/localcloud/sync/adapters/GcsSyncAdapter.java` | browseRemote, gcpGet, previewRemote |
| `localcloud-server/src/main/java/com/localcloud/sync/adapters/SpannerSyncAdapter.java` | browseRemote, gcpGet |
| `localcloud-server/src/test/java/com/localcloud/emulators/secretmanager/SecretManagerRestServiceTest.java` | errorResponseFormat_matchesGoogleApi |
| `localcloud-server/src/test/java/com/localcloud/emulators/workflows/WorkflowsServiceImplTest.java` | stepEntryRow, workflowRow, executionRow, createWorkflow_withProductionMetadata_passesToStoreAndFormats, cancelExecution_queuedExecution_canBeCancelled, ... |
| `localcloud-server/src/test/java/com/localcloud/emulators/workflows/expression/ExpressionEvaluatorTest.java` | setUp |
| `localcloud-server/src/test/java/com/localcloud/sync/SyncCancelResumeTest.java` | resync_reusesManifestParams, resync_usesExactOriginalRowCount |
| `localcloud-server/src/test/java/com/localcloud/sync/SyncIntegrationTest.java` | syncFlow_progressCallback_fires, syncFlow_adapterFails_manifestMarkedFailed, fullSyncFlow_estimate_then_sync, syncFlow_withFilters, syncFlow_costCeilingBlocks |
| `localcloud-server/src/test/java/com/localcloud/sync/SyncManifestRepositoryTest.java` | getById_returnsNullWhenNotFound |
| `localcloud-server/src/test/java/com/localcloud/sync/SyncServiceTest.java` | deleteManifest_adapterFailure_stillDeletesManifest, deleteManifest_callsAdapterDeleteLocal, startSync_tracksProgress, startSync_adapterFails_updatesManifestToFailed, startSync_savesManifest, ... |
| `localcloud-server/src/test/java/com/localcloud/sync/adapters/BigtableSyncAdapterTest.java` | buildReadRowsRequest_withLimit, buildReadRowsRequest_withColumnFamily, buildReadRowsRequest_empty, buildReadRowsRequest_allOptions |
| `localcloud-server/src/test/java/com/localcloud/sync/adapters/FirestoreSyncAdapterTest.java` | buildRunQuery_withLimit, buildRunQuery_withFilters, buildRunQuery_withMultipleFilters, buildWhereClause_boolFilter, buildWhereClause_singleFilter_fieldFilter |

## Entry Points

- `localcloud-server/src/test/java/com/localcloud/sync/SyncServiceTest.java::SyncServiceTest.startSync_savesManifest`
- `localcloud-server/src/test/java/com/localcloud/sync/SyncServiceTest.java::SyncServiceTest.startSync_tracksProgress`
- `localcloud-server/src/main/java/com/localcloud/admin/TelemetryService.java::TelemetryService.start`
- `localcloud-server/src/test/java/com/localcloud/sync/SyncServiceTest.java::SyncServiceTest.startSync_adapterFails_updatesManifestToFailed`
- `localcloud-server/src/main/java/com/localcloud/admin/ExportService.java::ExportService.export`

## Connected Communities

- **get** (103 cross-edges)
- **get** (52 cross-edges)
- **handle** (41 cross-edges)
- **admin** (32 cross-edges)
- **src** (20 cross-edges)
- **examples/python-sdk-demo/src** (18 cross-edges)
- **test** (13 cross-edges)
- **get** (11 cross-edges)
- **admin** (8 cross-edges)
- **config** (7 cross-edges)
- **examples/python-sdk-demo/services** (6 cross-edges)
- **adapters** (6 cross-edges)
- **config** (4 cross-edges)
- **engine** (4 cross-edges)
- **workflows** (4 cross-edges)
- **admin** (4 cross-edges)
- **engine** (4 cross-edges)
- **gateway** (4 cross-edges)
- **admin** (4 cross-edges)
- **sync** (3 cross-edges)
- **emulators** (3 cross-edges)
- **test** (3 cross-edges)
- **workflows** (2 cross-edges)
- **sync** (2 cross-edges)
- **gateway** (2 cross-edges)
- **adapters** (2 cross-edges)
- **workflows** (2 cross-edges)
- **bigtablesql** (2 cross-edges)
- **adapters** (2 cross-edges)
- **workflows** (2 cross-edges)
- **workflows** (2 cross-edges)
- **engine** (2 cross-edges)
- **sync** (2 cross-edges)
- **admin** (1 cross-edges)
- **adapters** (1 cross-edges)
- **adapters** (1 cross-edges)
- **workflows** (1 cross-edges)
- **adapters** (1 cross-edges)
- **pages** (1 cross-edges)
- **sync** (1 cross-edges)
- **src** (1 cross-edges)
- **secretmanager** (1 cross-edges)
- **workflows** (1 cross-edges)
- **sync** (1 cross-edges)
- **adapters** (1 cross-edges)
- **pages** (1 cross-edges)
- **admin** (1 cross-edges)
- **config** (1 cross-edges)
- **adapters** (1 cross-edges)
- **adapters** (1 cross-edges)
- **workflows** (1 cross-edges)
- **adapters** (1 cross-edges)
- **workflows** (1 cross-edges)

## How to Explore

```
get_communities with id: "community-196"
smart_context with task: "understand build", format: "gcx"
find_usages with id: "localcloud-server/src/test/java/com/localcloud/sync/SyncServiceTest.java::SyncServiceTest.startSync_savesManifest", format: "gcx"
```

_`format: "gcx"` returns the [GCX1 compact wire format](../../docs/wire-format.md) — round-trippable, ~27% fewer tokens than JSON. Drop it for JSON output; agents using `@gortex/wire` or the Go `github.com/gortexhq/gcx-go` package decode either._
