---
name: gortex-get
description: "Work in the get area — 27 symbols across 20 files (64% cohesion)"
---

# get

27 symbols | 20 files | 64% cohesion

## When to Use

Use this skill when working on files in:
- `localcloud-server/src/main/java/com/localcloud/LocalCloudApplication.java`
- `localcloud-server/src/main/java/com/localcloud/admin/MutateService.java`
- `localcloud-server/src/main/java/com/localcloud/config/LocalCloudConfig.java`
- `localcloud-server/src/main/java/com/localcloud/docker/DockerClientProvider.java`
- `localcloud-server/src/main/java/com/localcloud/emulators/cloudrun/CloudRunEmulator.java`
- `localcloud-server/src/main/java/com/localcloud/emulators/cloudtasks/CloudTasksEmulator.java`
- `localcloud-server/src/main/java/com/localcloud/emulators/compute/ComputeEmulator.java`
- `localcloud-server/src/main/java/com/localcloud/emulators/gke/GkeEmulator.java`
- `localcloud-server/src/main/java/com/localcloud/emulators/logging/LoggingEmulator.java`
- `localcloud-server/src/main/java/com/localcloud/emulators/monitoring/MonitoringEmulator.java`
- `localcloud-server/src/main/java/com/localcloud/emulators/secretmanager/SecretManagerEmulator.java`
- `localcloud-server/src/main/java/com/localcloud/emulators/workflows/WorkflowsEmulator.java`
- `localcloud-server/src/main/java/com/localcloud/emulators/workflows/WorkflowsServiceImpl.java`
- `localcloud-server/src/main/java/com/localcloud/gateway/ApiGateway.java`
- `localcloud-server/src/main/java/com/localcloud/gateway/ProcessHealthChecker.java`
- `localcloud-server/src/main/java/com/localcloud/sync/SyncService.java`
- `localcloud-server/src/test/java/com/localcloud/config/LocalCloudConfigTest.java`
- `localcloud-server/src/test/java/com/localcloud/emulators/workflows/WorkflowsServiceImplTest.java`
- `localcloud-server/src/test/java/com/localcloud/sync/SyncIntegrationTest.java`
- `localcloud-server/src/test/java/com/localcloud/sync/SyncServiceTest.java`

## Key Files

| File | Symbols |
|------|---------|
| `localcloud-server/src/main/java/com/localcloud/LocalCloudApplication.java` | start, getDataSource |
| `localcloud-server/src/main/java/com/localcloud/admin/MutateService.java` | setWorkflowsService |
| `localcloud-server/src/main/java/com/localcloud/config/LocalCloudConfig.java` | getGatewayPort, getDataDir, isServiceEnabled |
| `localcloud-server/src/main/java/com/localcloud/docker/DockerClientProvider.java` | getClient |
| `localcloud-server/src/main/java/com/localcloud/emulators/cloudrun/CloudRunEmulator.java` | getServicesService, getRevisionsService |
| `localcloud-server/src/main/java/com/localcloud/emulators/cloudtasks/CloudTasksEmulator.java` | getStore |
| `localcloud-server/src/main/java/com/localcloud/emulators/compute/ComputeEmulator.java` | getRestService |
| `localcloud-server/src/main/java/com/localcloud/emulators/gke/GkeEmulator.java` | getClusterManagerService |
| `localcloud-server/src/main/java/com/localcloud/emulators/logging/LoggingEmulator.java` | getLoggingService |
| `localcloud-server/src/main/java/com/localcloud/emulators/monitoring/MonitoringEmulator.java` | getMonitoringService |
| `localcloud-server/src/main/java/com/localcloud/emulators/secretmanager/SecretManagerEmulator.java` | getServiceImpl |
| `localcloud-server/src/main/java/com/localcloud/emulators/workflows/WorkflowsEmulator.java` | getWorkflowsService |
| `localcloud-server/src/main/java/com/localcloud/emulators/workflows/WorkflowsServiceImpl.java` | setEnvVarsRepository |
| `localcloud-server/src/main/java/com/localcloud/gateway/ApiGateway.java` | registerGrpcEmulator |
| `localcloud-server/src/main/java/com/localcloud/gateway/ProcessHealthChecker.java` | getStatus |
| `localcloud-server/src/main/java/com/localcloud/sync/SyncService.java` | registerAdapter |
| `localcloud-server/src/test/java/com/localcloud/config/LocalCloudConfigTest.java` | isServiceEnabledReturnsFalseForDisabledService, isServiceEnabledReflectsRuntimeToggle, isServiceEnabledIsCaseSensitive, isServiceEnabledReturnsTrueForEnabledService |
| `localcloud-server/src/test/java/com/localcloud/emulators/workflows/WorkflowsServiceImplTest.java` | getStore_returnsSameInstance |
| `localcloud-server/src/test/java/com/localcloud/sync/SyncIntegrationTest.java` | setUp |
| `localcloud-server/src/test/java/com/localcloud/sync/SyncServiceTest.java` | setUp |

## Connected Communities

- **admin** (11 cross-edges)
- **build** (7 cross-edges)
- **config** (6 cross-edges)
- **admin** (4 cross-edges)
- **config** (4 cross-edges)
- **get** (2 cross-edges)
- **config** (2 cross-edges)
- **gateway** (2 cross-edges)
- **pages** (1 cross-edges)
- **get** (1 cross-edges)
- **examples/python-sdk-demo/src** (1 cross-edges)
- **workflows** (1 cross-edges)
- **localcloud-console** (1 cross-edges)
- **engine** (1 cross-edges)
- **sync** (1 cross-edges)
- **test** (1 cross-edges)

## How to Explore

```
get_communities with id: "community-285"
smart_context with task: "understand get", format: "gcx"
```

_`format: "gcx"` returns the [GCX1 compact wire format](../../docs/wire-format.md) — round-trippable, ~27% fewer tokens than JSON. Drop it for JSON output; agents using `@gortex/wire` or the Go `github.com/gortexhq/gcx-go` package decode either._
