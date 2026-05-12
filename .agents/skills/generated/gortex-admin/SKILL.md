---
name: gortex-admin
description: "Work in the admin area — 136 symbols across 14 files (73% cohesion)"
---

# admin

136 symbols | 14 files | 73% cohesion

## When to Use

Use this skill when working on files in:
- `examples/python-sdk-demo/services/bigquery_demo.py`
- `examples/python-sdk-demo/services/gcs_demo.py`
- `localcloud-server/src/main/java/com/localcloud/admin/AdminApiService.java`
- `localcloud-server/src/main/java/com/localcloud/admin/BrowseService.java`
- `localcloud-server/src/main/java/com/localcloud/admin/CredentialBroker.java`
- `localcloud-server/src/main/java/com/localcloud/admin/ExportService.java`
- `localcloud-server/src/main/java/com/localcloud/admin/MutateService.java`
- `localcloud-server/src/main/java/com/localcloud/admin/QueryService.java`
- `localcloud-server/src/main/java/com/localcloud/admin/RemoteProxyService.java`
- `localcloud-server/src/main/java/com/localcloud/admin/SeedService.java`
- `localcloud-server/src/main/java/com/localcloud/admin/ServiceRoutingRepository.java`
- `localcloud-server/src/main/java/com/localcloud/admin/SupervisorClient.java`
- `localcloud-server/src/main/java/com/localcloud/config/LocalCloudConfig.java`
- `localcloud-server/src/main/java/com/localcloud/emulators/workflows/WorkflowConnectorService.java`

## Key Files

| File | Symbols |
|------|---------|
| `examples/python-sdk-demo/services/bigquery_demo.py` | run |
| `examples/python-sdk-demo/services/gcs_demo.py` | send, _NoVerifyAdapter |
| `localcloud-server/src/main/java/com/localcloud/admin/AdminApiService.java` | enableService, disableService, errorResponse, setRouting, updateServiceConfig |
| `localcloud-server/src/main/java/com/localcloud/admin/BrowseService.java` | browseSpannerTableData, browseGcs, browsePubSub, browseFirestore, browseBigQueryTableData, ... |
| `localcloud-server/src/main/java/com/localcloud/admin/CredentialBroker.java` | isValid |
| `localcloud-server/src/main/java/com/localcloud/admin/ExportService.java` | exportBigQuery, exportGcs, proxyGet, exportPubSub, exportSpanner |
| `localcloud-server/src/main/java/com/localcloud/admin/MutateService.java` | mutateWorkflows, mutateBigQuery, mutatePubSub, mutateBigtable, mutateWithSubOp, ... |
| `localcloud-server/src/main/java/com/localcloud/admin/QueryService.java` | gcsFileSchema, executeSpannerQuery, schemaBigQuery, executeBigQueryQuery, query, ... |
| `localcloud-server/src/main/java/com/localcloud/admin/RemoteProxyService.java` | proxyRequest |
| `localcloud-server/src/main/java/com/localcloud/admin/SeedService.java` | seedFirestore, seedCloudTasks, reset, resetSpanner, resetService, ... |
| `localcloud-server/src/main/java/com/localcloud/admin/ServiceRoutingRepository.java` | get |
| `localcloud-server/src/main/java/com/localcloud/admin/SupervisorClient.java` | stopProcess, startProcess, escapeXml, callXmlRpc, SupervisorClient, ... |
| `localcloud-server/src/main/java/com/localcloud/config/LocalCloudConfig.java` | setServiceEnabled |
| `localcloud-server/src/main/java/com/localcloud/emulators/workflows/WorkflowConnectorService.java` | getProjectId |

## Entry Points

- `examples/python-sdk-demo/services/bigquery_demo.py::run`

## Connected Communities

- **build** (180 cross-edges)
- **get** (61 cross-edges)
- **examples/python-sdk-demo/services** (23 cross-edges)
- **src** (11 cross-edges)
- **adapters** (9 cross-edges)
- **admin** (9 cross-edges)
- **handle** (5 cross-edges)
- **bigtablesql** (5 cross-edges)
- **config** (4 cross-edges)
- **admin** (3 cross-edges)
- **get** (3 cross-edges)
- **config** (3 cross-edges)
- **test** (2 cross-edges)
- **test** (2 cross-edges)
- **adapters** (2 cross-edges)
- **workflows** (1 cross-edges)
- **admin** (1 cross-edges)
- **get** (1 cross-edges)
- **pages** (1 cross-edges)
- **workflows** (1 cross-edges)
- **admin** (1 cross-edges)

## How to Explore

```
get_communities with id: "community-126"
smart_context with task: "understand admin", format: "gcx"
find_usages with id: "examples/python-sdk-demo/services/bigquery_demo.py::run", format: "gcx"
```

_`format: "gcx"` returns the [GCX1 compact wire format](../../docs/wire-format.md) — round-trippable, ~27% fewer tokens than JSON. Drop it for JSON output; agents using `@gortex/wire` or the Go `github.com/gortexhq/gcx-go` package decode either._
