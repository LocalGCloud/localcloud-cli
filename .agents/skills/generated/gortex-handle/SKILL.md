---
name: gortex-handle
description: "Work in the handle area — 186 symbols across 44 files (68% cohesion)"
---

# handle

186 symbols | 44 files | 68% cohesion

## When to Use

Use this skill when working on files in:
- `examples/python-sdk-demo/services/compute_demo.py`
- `examples/python-sdk-demo/services/firestore_demo.py`
- `localcloud-server/src/main/java/com/localcloud/admin/ServiceConfigRepository.java`
- `localcloud-server/src/main/java/com/localcloud/admin/bigtablesql/BigtableSqlExecutor.java`
- `localcloud-server/src/main/java/com/localcloud/config/ServiceRegistry.java`
- `localcloud-server/src/main/java/com/localcloud/emulators/AbstractEmulator.java`
- `localcloud-server/src/main/java/com/localcloud/emulators/cloudrun/CloudRunEmulator.java`
- `localcloud-server/src/main/java/com/localcloud/emulators/cloudtasks/CloudTasksEmulator.java`
- `localcloud-server/src/main/java/com/localcloud/emulators/cloudtasks/CloudTasksStore.java`
- `localcloud-server/src/main/java/com/localcloud/emulators/memorystore/MemorystoreEmulator.java`
- `localcloud-server/src/main/java/com/localcloud/emulators/memorystore/MemorystoreStore.java`
- `localcloud-server/src/main/java/com/localcloud/emulators/memorystore/RedisCommandHandler.java`
- `localcloud-server/src/main/java/com/localcloud/emulators/secretmanager/SecretManagerEmulator.java`
- `localcloud-server/src/main/java/com/localcloud/emulators/workflows/ExecutionsGrpcServiceImpl.java`
- `localcloud-server/src/main/java/com/localcloud/emulators/workflows/RemoteSourceClient.java`
- `localcloud-server/src/main/java/com/localcloud/emulators/workflows/WorkflowConnectorService.java`
- `localcloud-server/src/main/java/com/localcloud/emulators/workflows/WorkflowsGrpcServiceImpl.java`
- `localcloud-server/src/main/java/com/localcloud/emulators/workflows/WorkflowsServiceImpl.java`
- `localcloud-server/src/main/java/com/localcloud/emulators/workflows/connector/ConnectorRegistry.java`
- `localcloud-server/src/main/java/com/localcloud/emulators/workflows/engine/ExecutionContext.java`
- `localcloud-server/src/main/java/com/localcloud/emulators/workflows/stdlib/EventsFunctions.java`
- `localcloud-server/src/main/java/com/localcloud/emulators/workflows/stdlib/HashFunctions.java`
- `localcloud-server/src/main/java/com/localcloud/emulators/workflows/stdlib/HttpFunctions.java`
- `localcloud-server/src/main/java/com/localcloud/emulators/workflows/stdlib/StdlibRegistry.java`
- `localcloud-server/src/main/java/com/localcloud/sync/SyncAdapter.java`
- `localcloud-server/src/main/java/com/localcloud/sync/SyncApiService.java`
- `localcloud-server/src/main/java/com/localcloud/sync/SyncService.java`
- `localcloud-server/src/test/java/com/localcloud/admin/AdminApiServiceTerraformTest.java`
- `localcloud-server/src/test/java/com/localcloud/admin/ServiceRoutingRepositoryTest.java`
- `localcloud-server/src/test/java/com/localcloud/emulators/cloudtasks/CloudTasksRestServiceTest.java`
- `localcloud-server/src/test/java/com/localcloud/emulators/gke/K3dManagerTest.java`
- `localcloud-server/src/test/java/com/localcloud/emulators/secretmanager/SecretManagerRestServiceTest.java`
- `localcloud-server/src/test/java/com/localcloud/emulators/workflows/BugfixRegressionTest.java`
- `localcloud-server/src/test/java/com/localcloud/emulators/workflows/WorkflowUrlRewriterTest.java`
- `localcloud-server/src/test/java/com/localcloud/emulators/workflows/WorkflowsServiceImplTest.java`
- `localcloud-server/src/test/java/com/localcloud/emulators/workflows/WorkflowsStoreTest.java`
- `localcloud-server/src/test/java/com/localcloud/emulators/workflows/engine/WorkflowExecutorTest.java`
- `localcloud-server/src/test/java/com/localcloud/emulators/workflows/stdlib/StdlibRegistryTest.java`
- `localcloud-server/src/test/java/com/localcloud/sync/SyncApiServiceTest.java`
- `localcloud-server/src/test/java/com/localcloud/sync/SyncCredentialRepositoryTest.java`
- `localcloud-server/src/test/java/com/localcloud/sync/SyncManifestRepositoryTest.java`
- `localcloud-server/src/test/java/com/localcloud/sync/adapters/BigtableSyncAdapterTest.java`
- `localcloud-server/src/test/java/com/localcloud/sync/adapters/FirestoreSyncAdapterTest.java`
- `localcloud-server/src/test/java/com/localcloud/sync/adapters/RetryableHttpClientTest.java`

## Key Files

| File | Symbols |
|------|---------|
| `examples/python-sdk-demo/services/compute_demo.py` | run |
| `examples/python-sdk-demo/services/firestore_demo.py` | run, increment_counter |
| `localcloud-server/src/main/java/com/localcloud/admin/ServiceConfigRepository.java` | upsert |
| `localcloud-server/src/main/java/com/localcloud/admin/bigtablesql/BigtableSqlExecutor.java` | flattenRows, executeShowTables |
| `localcloud-server/src/main/java/com/localcloud/config/ServiceRegistry.java` | load, parseServiceDef, findServicesYaml |
| `localcloud-server/src/main/java/com/localcloud/emulators/AbstractEmulator.java` | toString |
| `localcloud-server/src/main/java/com/localcloud/emulators/cloudrun/CloudRunEmulator.java` | doStart |
| `localcloud-server/src/main/java/com/localcloud/emulators/cloudtasks/CloudTasksEmulator.java` | listQueues, buildQueue, mapQueueState |
| `localcloud-server/src/main/java/com/localcloud/emulators/cloudtasks/CloudTasksStore.java` | listTasks, getTask, deleteTask |
| `localcloud-server/src/main/java/com/localcloud/emulators/memorystore/MemorystoreEmulator.java` | doReset |
| `localcloud-server/src/main/java/com/localcloud/emulators/memorystore/MemorystoreStore.java` | getSortedSet, getSetMembers, listIndex, getHash, setHashFields, ... |
| `localcloud-server/src/main/java/com/localcloud/emulators/memorystore/RedisCommandHandler.java` | handleSdiff, handleZrange, handleAppend, handleHexists, handleZcount, ... |
| `localcloud-server/src/main/java/com/localcloud/emulators/secretmanager/SecretManagerEmulator.java` | buildSecret, getSecretVersion, accessSecretVersion, listSecretVersions |
| `localcloud-server/src/main/java/com/localcloud/emulators/workflows/ExecutionsGrpcServiceImpl.java` | mapToExecutionProto, toTimestamp, parseExecutionCallLogLevel |
| `localcloud-server/src/main/java/com/localcloud/emulators/workflows/RemoteSourceClient.java` | getServicesForUser, getServiceEndpoints |
| `localcloud-server/src/main/java/com/localcloud/emulators/workflows/WorkflowConnectorService.java` | connect |
| `localcloud-server/src/main/java/com/localcloud/emulators/workflows/WorkflowsGrpcServiceImpl.java` | mapToWorkflowProto, updateWorkflow, toTimestamp, parseExecutionHistoryLevel, parseWorkflowCallLogLevel, ... |
| `localcloud-server/src/main/java/com/localcloud/emulators/workflows/WorkflowsServiceImpl.java` | formatExecution, createExecution, createExecution |
| `localcloud-server/src/main/java/com/localcloud/emulators/workflows/connector/ConnectorRegistry.java` | executeUnknownConnector |
| `localcloud-server/src/main/java/com/localcloud/emulators/workflows/engine/ExecutionContext.java` | setState, getStepHistory |
| `localcloud-server/src/main/java/com/localcloud/emulators/workflows/stdlib/EventsFunctions.java` | register, register, EventsFunctions, currentExecutionId |
| `localcloud-server/src/main/java/com/localcloud/emulators/workflows/stdlib/HashFunctions.java` | computeHmac, computeChecksum |
| `localcloud-server/src/main/java/com/localcloud/emulators/workflows/stdlib/HttpFunctions.java` | httpCall |
| `localcloud-server/src/main/java/com/localcloud/emulators/workflows/stdlib/StdlibRegistry.java` | get |
| `localcloud-server/src/main/java/com/localcloud/sync/SyncAdapter.java` | deleteLocal |
| `localcloud-server/src/main/java/com/localcloud/sync/SyncApiService.java` | estimate, parseFilters |
| `localcloud-server/src/main/java/com/localcloud/sync/SyncService.java` | resync, startSync, cancelSync, deleteManifest, progressKey, ... |
| `localcloud-server/src/test/java/com/localcloud/admin/AdminApiServiceTerraformTest.java` | spannerRestPort_usedForTerraform |
| `localcloud-server/src/test/java/com/localcloud/admin/ServiceRoutingRepositoryTest.java` | upsertAndGet, defaultModeIsLocal, upsertUpdatesExisting, getReturnsNullWhenNoConfig, ServiceRoutingRepositoryTest, ... |
| `localcloud-server/src/test/java/com/localcloud/emulators/cloudtasks/CloudTasksRestServiceTest.java` | queueResponseFormat_matchesGoogleApi, errorResponseFormat_matchesGoogleApi |
| `localcloud-server/src/test/java/com/localcloud/emulators/gke/K3dManagerTest.java` | clusterPrefix_isLc, safeNamePattern_rejectsLeadingSpecialChars |
| `localcloud-server/src/test/java/com/localcloud/emulators/secretmanager/SecretManagerRestServiceTest.java` | parseCreateBody_multipleLabels |
| `localcloud-server/src/test/java/com/localcloud/emulators/workflows/BugfixRegressionTest.java` | testEventsFunctionsIsolation |
| `localcloud-server/src/test/java/com/localcloud/emulators/workflows/WorkflowUrlRewriterTest.java` | generateEnvVarEntries |
| `localcloud-server/src/test/java/com/localcloud/emulators/workflows/WorkflowsServiceImplTest.java` | createWorkflow_responseContainsWorkflowName, createWorkflow_responseContainsSourceContents, listStepEntries_formatsNames, updateWorkflow_validYaml_returnsOperation, createWorkflow_responseContainsRevisionId, ... |
| `localcloud-server/src/test/java/com/localcloud/emulators/workflows/WorkflowsStoreTest.java` | testSeedEntryFormatIsValid |
| `localcloud-server/src/test/java/com/localcloud/emulators/workflows/engine/WorkflowExecutorTest.java` | testFailedStepIsRecordedInHistory |
| `localcloud-server/src/test/java/com/localcloud/emulators/workflows/stdlib/StdlibRegistryTest.java` | testRegistryGetNull, testMapMerge |
| `localcloud-server/src/test/java/com/localcloud/sync/SyncApiServiceTest.java` | parseFilters_missingColumnType_defaultsToString, parseFilters_validList_returnsFilters |
| `localcloud-server/src/test/java/com/localcloud/sync/SyncCredentialRepositoryTest.java` | getStatus_neverReturnsCredentialData |
| `localcloud-server/src/test/java/com/localcloud/sync/SyncManifestRepositoryTest.java` | getById_returnsManifest, getAll_returnsAllForProject |
| `localcloud-server/src/test/java/com/localcloud/sync/adapters/BigtableSyncAdapterTest.java` | buildReadRowsRequest_withRowKeyPrefix |
| `localcloud-server/src/test/java/com/localcloud/sync/adapters/FirestoreSyncAdapterTest.java` | buildRunQuery_noFilters_noLimit |
| `localcloud-server/src/test/java/com/localcloud/sync/adapters/RetryableHttpClientTest.java` | invalidUrl_throwsIOException |

## Entry Points

- `examples/python-sdk-demo/services/firestore_demo.py::run`
- `examples/python-sdk-demo/services/compute_demo.py::run`

## Connected Communities

- **get** (71 cross-edges)
- **build** (61 cross-edges)
- **bigtablesql** (43 cross-edges)
- **get** (15 cross-edges)
- **src** (9 cross-edges)
- **workflows** (8 cross-edges)
- **admin** (8 cross-edges)
- **workflows** (5 cross-edges)
- **emulators** (5 cross-edges)
- **engine** (4 cross-edges)
- **engine** (4 cross-edges)
- **examples/python-sdk-demo/services** (4 cross-edges)
- **sync** (3 cross-edges)
- **components** (3 cross-edges)
- **admin** (3 cross-edges)
- **engine** (3 cross-edges)
- **workflows** (3 cross-edges)
- **examples/python-sdk-demo/src** (3 cross-edges)
- **secretmanager** (2 cross-edges)
- **engine** (2 cross-edges)
- **workflows** (2 cross-edges)
- **workflows** (2 cross-edges)
- **secretmanager** (2 cross-edges)
- **sync** (2 cross-edges)
- **test** (1 cross-edges)
- **engine** (1 cross-edges)
- **test** (1 cross-edges)
- **dashboard** (1 cross-edges)
- **secretmanager** (1 cross-edges)
- **secretmanager** (1 cross-edges)
- **localcloud-console** (1 cross-edges)
- **workflows** (1 cross-edges)
- **cloudtasks** (1 cross-edges)
- **workflows** (1 cross-edges)
- **workflows** (1 cross-edges)
- **bigtablesql** (1 cross-edges)
- **stdlib** (1 cross-edges)
- **localcloud-console/src/components** (1 cross-edges)
- **engine** (1 cross-edges)

## How to Explore

```
get_communities with id: "community-144"
smart_context with task: "understand handle", format: "gcx"
find_usages with id: "examples/python-sdk-demo/services/firestore_demo.py::run", format: "gcx"
```

_`format: "gcx"` returns the [GCX1 compact wire format](../../docs/wire-format.md) — round-trippable, ~27% fewer tokens than JSON. Drop it for JSON output; agents using `@gortex/wire` or the Go `github.com/gortexhq/gcx-go` package decode either._
