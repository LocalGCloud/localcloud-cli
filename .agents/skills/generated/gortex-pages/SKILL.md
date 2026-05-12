---
name: gortex-pages
description: "Work in the pages area — 27 symbols across 3 files (81% cohesion)"
---

# pages

27 symbols | 3 files | 81% cohesion

## When to Use

Use this skill when working on files in:
- `localcloud-console/src/pages/ServiceExplorer.jsx`
- `localcloud-server/src/main/java/com/localcloud/emulators/AbstractEmulator.java`
- `localcloud-server/src/main/java/com/localcloud/gateway/RequestLogger.java`

## Key Files

| File | Symbols |
|------|---------|
| `localcloud-console/src/pages/ServiceExplorer.jsx` | SQLEditor, handleFileExpand, formatDuration, isGcsMode, loadGcsFiles, ... |
| `localcloud-server/src/main/java/com/localcloud/emulators/AbstractEmulator.java` | setRunning |
| `localcloud-server/src/main/java/com/localcloud/gateway/RequestLogger.java` | getEntries |

## Entry Points

- `localcloud-console/src/pages/ServiceExplorer.jsx::SQLEditor`

## Connected Communities

- **src** (7 cross-edges)
- **test** (4 cross-edges)
- **localcloud-console/src/components** (3 cross-edges)
- **pages** (3 cross-edges)
- **pages** (2 cross-edges)
- **pages** (2 cross-edges)
- **admin** (2 cross-edges)
- **gateway** (2 cross-edges)

## How to Explore

```
get_communities with id: "community-267"
smart_context with task: "understand pages", format: "gcx"
find_usages with id: "localcloud-console/src/pages/ServiceExplorer.jsx::SQLEditor", format: "gcx"
```

_`format: "gcx"` returns the [GCX1 compact wire format](../../docs/wire-format.md) — round-trippable, ~27% fewer tokens than JSON. Drop it for JSON output; agents using `@gortex/wire` or the Go `github.com/gortexhq/gcx-go` package decode either._
