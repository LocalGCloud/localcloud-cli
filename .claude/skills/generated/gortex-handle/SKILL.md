---
name: gortex-handle
description: "Work in the handle area — 177 symbols across 40 files (68% cohesion)"
---

# handle

177 symbols | 40 files | 68% cohesion

## When to Use

Use this skill when working on files in:
- `examples/python-sdk-demo/services/compute_demo.py`
- `examples/python-sdk-demo/services/firestore_demo.py`
- `examples/python-sdk-demo/src/api.js`
- `localcloud-server/src/main/java/com/localcloud/config/ServiceRegistry.java`
- `localcloud-server/src/main/java/com/localcloud/emulators/cloudtasks/CloudTasksEmulator.java`
- `localcloud-server/src/main/java/com/localcloud/emulators/memorystore/MemorystoreEmulator.java`
- `localcloud-server/src/main/java/com/localcloud/emulators/memorystore/MemorystoreStore.java`
- `localcloud-server/src/main/java/com/localcloud/emulators/memorystore/RedisCommandHandler.java`
- `localcloud-server/src/main/java/com/localcloud/emulators/secretmanager/SecretManagerEmulator.java`
- `localcloud-server/src/main/java/com/localcloud/emulators/secretmanager/SecretManagerRestService.java`
- `localcloud-server/src/main/java/com/localcloud/emulators/workflows/RemoteSourceClient.java`
- `localcloud-server/src/main/java/com/localcloud/emulators/workflows/WorkflowEnvVarsService.java`
- `localcloud-server/src/main/java/com/localcloud/emulators/workflows/WorkflowsGrpcServiceImpl.java`
- `localcloud-server/src/main/java/com/localcloud/emulators/workflows/connector/ConnectorRegistry.java`
- `localcloud-server/src/main/java/com/localcloud/emulators/workflows/stdlib/EventsFunctions.java`
- `localcloud-server/src/main/java/com/localcloud/emulators/workflows/stdlib/HashFunctions.java`
- `localcloud-server/src/main/java/com/localcloud/emulators/workflows/stdlib/HttpFunctions.java`
- `localcloud-server/src/main/java/com/localcloud/emulators/workflows/stdlib/StdlibRegistry.java`
- `localcloud-server/src/main/java/com/localcloud/gateway/IamMiddleware.java`
- `localcloud-server/src/main/java/com/localcloud/sync/SyncAdapter.java`
- `localcloud-server/src/main/java/com/localcloud/sync/SyncApiService.java`
- `localcloud-server/src/main/java/com/localcloud/sync/SyncService.java`
- `localcloud-server/src/test/java/com/localcloud/admin/AdminApiServiceTerraformTest.java`
- `localcloud-server/src/test/java/com/localcloud/admin/ServiceRoutingRepositoryTest.java`
- `localcloud-server/src/test/java/com/localcloud/emulators/cloudtasks/CloudTasksRestServiceTest.java`
- `localcloud-server/src/test/java/com/localcloud/emulators/gke/K3dManagerTest.java`
- `localcloud-server/src/test/java/com/localcloud/emulators/secretmanager/SecretManagerRestServiceTest.java`
- `localcloud-server/src/test/java/com/localcloud/emulators/workflows/BugfixRegressionTest.java`
- `localcloud-server/src/test/java/com/localcloud/emulators/workflows/SysFunctionsEnvVarsTest.java`
- `localcloud-server/src/test/java/com/localcloud/emulators/workflows/WorkflowUrlRewriterTest.java`
- `localcloud-server/src/test/java/com/localcloud/emulators/workflows/WorkflowsServiceImplTest.java`
- `localcloud-server/src/test/java/com/localcloud/emulators/workflows/WorkflowsStoreTest.java`
- `localcloud-server/src/test/java/com/localcloud/emulators/workflows/stdlib/StdlibRegistryTest.java`
- `localcloud-server/src/test/java/com/localcloud/emulators/workflows/stdlib/SysFunctionsTest.java`
- `localcloud-server/src/test/java/com/localcloud/events/EventBusTest.java`
- `localcloud-server/src/test/java/com/localcloud/sync/SyncApiServiceTest.java`
- `localcloud-server/src/test/java/com/localcloud/sync/SyncCancelResumeTest.java`
- `localcloud-server/src/test/java/com/localcloud/sync/SyncCredentialRepositoryTest.java`
- `localcloud-server/src/test/java/com/localcloud/sync/SyncManifestRepositoryTest.java`
- `localcloud-server/src/test/java/com/localcloud/sync/adapters/BigtableSyncAdapterTest.java`

## Key Files

| File | Symbols |
|------|---------|
| `examples/python-sdk-demo/services/compute_demo.py` | run |
| `examples/python-sdk-demo/services/firestore_demo.py` | run, increment_counter |
| `examples/python-sdk-demo/src/api.js` | loadSeed, fetchMetrics, fetchLogs |
| `localcloud-server/src/main/java/com/localcloud/config/ServiceRegistry.java` | load, findServicesYaml, parseServiceDef |
| `localcloud-server/src/main/java/com/localcloud/emulators/cloudtasks/CloudTasksEmulator.java` | listQueues |
| `localcloud-server/src/main/java/com/localcloud/emulators/memorystore/MemorystoreEmulator.java` | doReset |
| `localcloud-server/src/main/java/com/localcloud/emulators/memorystore/MemorystoreStore.java` | getSortedSet, type, getSetMembers, getHash, flushAll |
| `localcloud-server/src/main/java/com/localcloud/emulators/memorystore/RedisCommandHandler.java` | handleSismember, handleHexists, handleLtrim, handleLrange, handleZadd, ... |
| `localcloud-server/src/main/java/com/localcloud/emulators/secretmanager/SecretManagerEmulator.java` | getSecretVersion, listSecretVersions, accessSecretVersion, buildSecret, listSecrets |
| `localcloud-server/src/main/java/com/localcloud/emulators/secretmanager/SecretManagerRestService.java` | listSecrets |
| `localcloud-server/src/main/java/com/localcloud/emulators/workflows/RemoteSourceClient.java` | getServicesForUser, getServiceEndpoints |
| `localcloud-server/src/main/java/com/localcloud/emulators/workflows/WorkflowEnvVarsService.java` | listEnvVars, activatePreset, createEnvVar, getProjectId, deleteEnvVar, ... |
| `localcloud-server/src/main/java/com/localcloud/emulators/workflows/WorkflowsGrpcServiceImpl.java` | createWorkflow, deleteWorkflow, updateWorkflow, mapToWorkflowProto, WorkflowsGrpcServiceImpl, ... |
| `localcloud-server/src/main/java/com/localcloud/emulators/workflows/connector/ConnectorRegistry.java` | executeUnknownConnector |
| `localcloud-server/src/main/java/com/localcloud/emulators/workflows/stdlib/EventsFunctions.java` | register, register, EventsFunctions, currentExecutionId |
| `localcloud-server/src/main/java/com/localcloud/emulators/workflows/stdlib/HashFunctions.java` | computeChecksum, computeHmac |
| `localcloud-server/src/main/java/com/localcloud/emulators/workflows/stdlib/HttpFunctions.java` | httpCall |
| `localcloud-server/src/main/java/com/localcloud/emulators/workflows/stdlib/StdlibRegistry.java` | get |
| `localcloud-server/src/main/java/com/localcloud/gateway/IamMiddleware.java` | loadLocalPolicies |
| `localcloud-server/src/main/java/com/localcloud/sync/SyncAdapter.java` | deleteLocal, sync |
| `localcloud-server/src/main/java/com/localcloud/sync/SyncApiService.java` | estimate, startSync, parseFilters |
| `localcloud-server/src/main/java/com/localcloud/sync/SyncService.java` | startSync, deleteManifest, cancelSync, resync, progressKey, ... |
| `localcloud-server/src/test/java/com/localcloud/admin/AdminApiServiceTerraformTest.java` | spannerRestPort_usedForTerraform |
| `localcloud-server/src/test/java/com/localcloud/admin/ServiceRoutingRepositoryTest.java` | upsertAndGet |
| `localcloud-server/src/test/java/com/localcloud/emulators/cloudtasks/CloudTasksRestServiceTest.java` | errorResponseFormat_matchesGoogleApi, queueResponseFormat_matchesGoogleApi |
| `localcloud-server/src/test/java/com/localcloud/emulators/gke/K3dManagerTest.java` | safeNamePattern_rejectsLeadingSpecialChars, clusterPrefix_isLc |
| `localcloud-server/src/test/java/com/localcloud/emulators/secretmanager/SecretManagerRestServiceTest.java` | parseCreateBody_multipleLabels |
| `localcloud-server/src/test/java/com/localcloud/emulators/workflows/BugfixRegressionTest.java` | testRegexLengthGuard, testEventsFunctionsIsolation, testInvalidRegexHandled |
| `localcloud-server/src/test/java/com/localcloud/emulators/workflows/SysFunctionsEnvVarsTest.java` | getEnvRequiresArgument |
| `localcloud-server/src/test/java/com/localcloud/emulators/workflows/WorkflowUrlRewriterTest.java` | generateEnvVarEntries |
| `localcloud-server/src/test/java/com/localcloud/emulators/workflows/WorkflowsServiceImplTest.java` | deleteWorkflow_returnsOperation |
| `localcloud-server/src/test/java/com/localcloud/emulators/workflows/WorkflowsStoreTest.java` | testSeedEntryFormatIsValid, testStepConfigAccessible |
| `localcloud-server/src/test/java/com/localcloud/emulators/workflows/stdlib/StdlibRegistryTest.java` | testMapMerge, testRegistryGetNull |
| `localcloud-server/src/test/java/com/localcloud/emulators/workflows/stdlib/SysFunctionsTest.java` | sysSleep_interrupted_throwsRuntimeException, sysSleep_normal_completesWithoutError |
| `localcloud-server/src/test/java/com/localcloud/events/EventBusTest.java` | eventRecordFieldsAccessible |
| `localcloud-server/src/test/java/com/localcloud/sync/SyncApiServiceTest.java` | parseFilters_missingColumnType_defaultsToString, parseFilters_validList_returnsFilters |
| `localcloud-server/src/test/java/com/localcloud/sync/SyncCancelResumeTest.java` | resync_manifestNotFound_throws, cancelSync_noRunningSync_returnsFalse |
| `localcloud-server/src/test/java/com/localcloud/sync/SyncCredentialRepositoryTest.java` | getStatus_neverReturnsCredentialData |
| `localcloud-server/src/test/java/com/localcloud/sync/SyncManifestRepositoryTest.java` | getAll_returnsAllForProject, getById_returnsManifest |
| `localcloud-server/src/test/java/com/localcloud/sync/adapters/BigtableSyncAdapterTest.java` | buildReadRowsRequest_withRowKeyPrefix |

## Entry Points

- `examples/python-sdk-demo/services/firestore_demo.py::run`
- `examples/python-sdk-demo/services/compute_demo.py::run`

## Connected Communities

- **get** (64 cross-edges)
- **get** (55 cross-edges)
- **memorystore** (17 cross-edges)
- **emulators** (7 cross-edges)
- **sync** (6 cross-edges)
- **workflows** (5 cross-edges)
- **engine** (5 cross-edges)
- **engine** (5 cross-edges)
- **workflows** (4 cross-edges)
- **admin** (4 cross-edges)
- **components** (3 cross-edges)
- **engine** (3 cross-edges)
- **examples/python-sdk-demo/services** (3 cross-edges)
- **engine** (2 cross-edges)
- **workflows** (2 cross-edges)
- **secretmanager** (2 cross-edges)
- **compute** (2 cross-edges)
- **secretmanager** (2 cross-edges)
- **secretmanager** (2 cross-edges)
- **sync** (2 cross-edges)
- **stdlib** (2 cross-edges)
- **engine** (2 cross-edges)
- **workflows** (2 cross-edges)
- **cloudtasks** (1 cross-edges)
- **engine** (1 cross-edges)
- **dashboard** (1 cross-edges)
- **memorystore** (1 cross-edges)
- **secretmanager** (1 cross-edges)
- **stdlib** (1 cross-edges)
- **cloudtasks** (1 cross-edges)
- **expression** (1 cross-edges)
- **sync** (1 cross-edges)
- **adapters** (1 cross-edges)
- **adapters** (1 cross-edges)
- **secretmanager** (1 cross-edges)
- **localcloud-server/src/main/java/com/localcloud/emulators/cloudrun** (1 cross-edges)
- **sync** (1 cross-edges)
- **secretmanager** (1 cross-edges)
- **workflows** (1 cross-edges)
- **engine** (1 cross-edges)
- **localcloud-console/src/components** (1 cross-edges)
- **gateway** (1 cross-edges)
- **secretmanager** (1 cross-edges)

## How to Explore

```
get_communities with id: "community-54"
smart_context with task: "understand handle", format: "gcx"
find_usages with id: "examples/python-sdk-demo/services/firestore_demo.py::run", format: "gcx"
```

_`format: "gcx"` returns the [GCX1 compact wire format](../../docs/wire-format.md) — round-trippable, ~27% fewer tokens than JSON. Drop it for JSON output; agents using `@gortex/wire` or the Go `github.com/zzet/gortex/pkg/wire` package decode either._
