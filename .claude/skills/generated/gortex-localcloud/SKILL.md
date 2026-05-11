---
name: gortex-localcloud
description: "Work in the localcloud area — 27 symbols across 1 files (93% cohesion)"
---

# localcloud

27 symbols | 1 files | 93% cohesion

## When to Use

Use this skill when working on files in:
- `localcloud-server/src/main/java/com/localcloud/LocalCloudApplication.java`

## Key Files

| File | Symbols |
|------|---------|
| `localcloud-server/src/main/java/com/localcloud/LocalCloudApplication.java` | LocalCloudApplication, requestLogger, schemaManager, adminApiService, browseService, ... |

## How to Explore

```
get_communities with id: "community-14"
smart_context with task: "understand localcloud", format: "gcx"
```

_`format: "gcx"` returns the [GCX1 compact wire format](../../docs/wire-format.md) — round-trippable, ~27% fewer tokens than JSON. Drop it for JSON output; agents using `@gortex/wire` or the Go `github.com/zzet/gortex/pkg/wire` package decode either._
