---
name: gortex-get
description: "Work in the get area — 36 symbols across 23 files (73% cohesion)"
---

# get

36 symbols | 23 files | 73% cohesion

## When to Use

Use this skill when working on files in:
- `localcloud-server/src/main/java/com/localcloud/LocalCloudApplication.java`
- `localcloud-server/src/main/java/com/localcloud/admin/BrowseService.java`
- `localcloud-server/src/main/java/com/localcloud/admin/ExportService.java`
- `localcloud-server/src/main/java/com/localcloud/admin/MutateService.java`
- `localcloud-server/src/main/java/com/localcloud/admin/QueryService.java`
- `localcloud-server/src/main/java/com/localcloud/admin/SeedService.java`
- `localcloud-server/src/main/java/com/localcloud/config/LocalCloudConfig.java`
- `localcloud-server/src/main/java/com/localcloud/config/ServiceRegistry.java`
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
- `localcloud-server/src/main/java/com/localcloud/persistence/PostgresDataSource.java`
- `localcloud-server/src/main/java/com/localcloud/sync/SyncService.java`
- `localcloud-server/src/test/java/com/localcloud/config/LocalCloudConfigTest.java`
- `localcloud-server/src/test/java/com/localcloud/sync/SyncServiceTest.java`

## Key Files

| File | Symbols |
|------|---------|
| `localcloud-server/src/main/java/com/localcloud/LocalCloudApplication.java` | start |
| `localcloud-server/src/main/java/com/localcloud/admin/BrowseService.java` | BrowseService.<init>, baseUrl |
| `localcloud-server/src/main/java/com/localcloud/admin/ExportService.java` | ExportService.<init>, baseUrl |
| `localcloud-server/src/main/java/com/localcloud/admin/MutateService.java` | setWorkflowsService, MutateService.<init>, baseUrl |
| `localcloud-server/src/main/java/com/localcloud/admin/QueryService.java` | QueryService.<init> |
| `localcloud-server/src/main/java/com/localcloud/admin/SeedService.java` | SeedService.<init>, baseUrl |
| `localcloud-server/src/main/java/com/localcloud/config/LocalCloudConfig.java` | getDataDir, getGatewayPort, isServiceEnabled |
| `localcloud-server/src/main/java/com/localcloud/config/ServiceRegistry.java` | getService |
| `localcloud-server/src/main/java/com/localcloud/emulators/cloudrun/CloudRunEmulator.java` | getRevisionsService, getServicesService |
| `localcloud-server/src/main/java/com/localcloud/emulators/cloudtasks/CloudTasksEmulator.java` | getServiceImpl, getStore |
| `localcloud-server/src/main/java/com/localcloud/emulators/compute/ComputeEmulator.java` | getRestService |
| `localcloud-server/src/main/java/com/localcloud/emulators/gke/GkeEmulator.java` | getClusterManagerService |
| `localcloud-server/src/main/java/com/localcloud/emulators/logging/LoggingEmulator.java` | getLoggingService |
| `localcloud-server/src/main/java/com/localcloud/emulators/monitoring/MonitoringEmulator.java` | getMonitoringService |
| `localcloud-server/src/main/java/com/localcloud/emulators/secretmanager/SecretManagerEmulator.java` | getServiceImpl, getStore |
| `localcloud-server/src/main/java/com/localcloud/emulators/workflows/WorkflowsEmulator.java` | getWorkflowsService |
| `localcloud-server/src/main/java/com/localcloud/emulators/workflows/WorkflowsServiceImpl.java` | setEnvVarsRepository |
| `localcloud-server/src/main/java/com/localcloud/gateway/ApiGateway.java` | registerGrpcEmulator |
| `localcloud-server/src/main/java/com/localcloud/gateway/ProcessHealthChecker.java` | getStatus |
| `localcloud-server/src/main/java/com/localcloud/persistence/PostgresDataSource.java` | getDataSource |
| `localcloud-server/src/main/java/com/localcloud/sync/SyncService.java` | registerAdapter |
| `localcloud-server/src/test/java/com/localcloud/config/LocalCloudConfigTest.java` | isServiceEnabledIsCaseSensitive, isServiceEnabledReturnsFalseForDisabledService, isServiceEnabledReflectsRuntimeToggle, isServiceEnabledReturnsTrueForEnabledService |
| `localcloud-server/src/test/java/com/localcloud/sync/SyncServiceTest.java` | setUp |

## Connected Communities

- **admin** (9 cross-edges)
- **get** (9 cross-edges)
- **config** (4 cross-edges)
- **config** (3 cross-edges)
- **gateway** (2 cross-edges)
- **config** (2 cross-edges)
- **workflows** (1 cross-edges)
- **config** (1 cross-edges)
- **pages** (1 cross-edges)
- **config** (1 cross-edges)
- **engine** (1 cross-edges)
- **localcloud-server/src/main/java/com/localcloud/emulators/cloudrun** (1 cross-edges)
- **get** (1 cross-edges)
- **sync** (1 cross-edges)
- **compute** (1 cross-edges)
- **handle** (1 cross-edges)

## How to Explore

```
get_communities with id: "community-64"
smart_context with task: "understand get", format: "gcx"
```

_`format: "gcx"` returns the [GCX1 compact wire format](../../docs/wire-format.md) — round-trippable, ~27% fewer tokens than JSON. Drop it for JSON output; agents using `@gortex/wire` or the Go `github.com/zzet/gortex/pkg/wire` package decode either._
