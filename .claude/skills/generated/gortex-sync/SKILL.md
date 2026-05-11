---
name: gortex-sync
description: "Work in the sync area — 25 symbols across 3 files (65% cohesion)"
---

# sync

25 symbols | 3 files | 65% cohesion

## When to Use

Use this skill when working on files in:
- `localcloud-server/src/main/java/com/localcloud/sync/SyncApiService.java`
- `localcloud-server/src/main/java/com/localcloud/sync/SyncService.java`
- `localcloud-server/src/test/java/com/localcloud/sync/SyncServiceTest.java`

## Key Files

| File | Symbols |
|------|---------|
| `localcloud-server/src/main/java/com/localcloud/sync/SyncApiService.java` | authProjects, jsonResponse, authStart, authRefresh, progress, ... |
| `localcloud-server/src/main/java/com/localcloud/sync/SyncService.java` | getManifests |
| `localcloud-server/src/test/java/com/localcloud/sync/SyncServiceTest.java` | getManifests_byProjectAndService_delegatesToRepo |

## Connected Communities

- **handle** (29 cross-edges)
- **get** (7 cross-edges)
- **get** (3 cross-edges)
- **examples/python-sdk-demo/services** (2 cross-edges)
- **adapters** (2 cross-edges)
- **adapters** (1 cross-edges)
- **sync** (1 cross-edges)
- **admin** (1 cross-edges)

## How to Explore

```
get_communities with id: "community-279"
smart_context with task: "understand sync", format: "gcx"
```

_`format: "gcx"` returns the [GCX1 compact wire format](../../docs/wire-format.md) — round-trippable, ~27% fewer tokens than JSON. Drop it for JSON output; agents using `@gortex/wire` or the Go `github.com/zzet/gortex/pkg/wire` package decode either._
