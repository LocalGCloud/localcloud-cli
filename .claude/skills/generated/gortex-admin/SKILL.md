---
name: gortex-admin
description: "Work in the admin area — 30 symbols across 3 files (80% cohesion)"
---

# admin

30 symbols | 3 files | 80% cohesion

## When to Use

Use this skill when working on files in:
- `localcloud-console/src/pages/DataBrowser.jsx`
- `localcloud-server/src/main/java/com/localcloud/admin/BrowseService.java`
- `localcloud-server/src/main/java/com/localcloud/admin/MutateService.java`

## Key Files

| File | Symbols |
|------|---------|
| `localcloud-console/src/pages/DataBrowser.jsx` | PubSubView, loadMessages, d, MonitoringView, CloudTasksView, ... |
| `localcloud-server/src/main/java/com/localcloud/admin/BrowseService.java` | browse |
| `localcloud-server/src/main/java/com/localcloud/admin/MutateService.java` | mutate, errorResponse |

## Entry Points

- `localcloud-console/src/pages/DataBrowser.jsx::SpannerView`
- `localcloud-console/src/pages/DataBrowser.jsx::BigQueryView`

## Connected Communities

- **admin** (6 cross-edges)
- **get** (4 cross-edges)
- **localcloud-console/src/components** (3 cross-edges)
- **pages** (3 cross-edges)
- **get** (2 cross-edges)
- **gateway** (1 cross-edges)
- **handle** (1 cross-edges)
- **expression** (1 cross-edges)
- **admin** (1 cross-edges)

## How to Explore

```
get_communities with id: "community-205"
smart_context with task: "understand admin", format: "gcx"
find_usages with id: "localcloud-console/src/pages/DataBrowser.jsx::SpannerView", format: "gcx"
```

_`format: "gcx"` returns the [GCX1 compact wire format](../../docs/wire-format.md) — round-trippable, ~27% fewer tokens than JSON. Drop it for JSON output; agents using `@gortex/wire` or the Go `github.com/zzet/gortex/pkg/wire` package decode either._
