---
name: gortex-memorystore
description: "Work in the memorystore area — 25 symbols across 1 files (61% cohesion)"
---

# memorystore

25 symbols | 1 files | 61% cohesion

## When to Use

Use this skill when working on files in:
- `localcloud-server/src/main/java/com/localcloud/emulators/memorystore/MemorystoreStore.java`

## Key Files

| File | Symbols |
|------|---------|
| `localcloud-server/src/main/java/com/localcloud/emulators/memorystore/MemorystoreStore.java` | upsertRaw, listSet, ttlExpr, MemorystoreStore, removeSortedSetMembers, ... |

## Connected Communities

- **handle** (12 cross-edges)
- **get** (11 cross-edges)
- **get** (5 cross-edges)
- **engine** (3 cross-edges)

## How to Explore

```
get_communities with id: "community-34"
smart_context with task: "understand memorystore", format: "gcx"
```

_`format: "gcx"` returns the [GCX1 compact wire format](../../docs/wire-format.md) — round-trippable, ~27% fewer tokens than JSON. Drop it for JSON output; agents using `@gortex/wire` or the Go `github.com/zzet/gortex/pkg/wire` package decode either._
