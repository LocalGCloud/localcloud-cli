---
name: gortex-adapters
description: "Work in the adapters area — 24 symbols across 2 files (83% cohesion)"
---

# adapters

24 symbols | 2 files | 83% cohesion

## When to Use

Use this skill when working on files in:
- `localcloud-server/src/main/java/com/localcloud/sync/adapters/BigQuerySyncAdapter.java`
- `localcloud-server/src/test/java/com/localcloud/sync/adapters/BigQuerySyncAdapterTest.java`

## Key Files

| File | Symbols |
|------|---------|
| `localcloud-server/src/main/java/com/localcloud/sync/adapters/BigQuerySyncAdapter.java` | buildSyncQuery, escapeSql, isNumericType |
| `localcloud-server/src/test/java/com/localcloud/sync/adapters/BigQuerySyncAdapterTest.java` | buildQuery_inOperator_numericValues, buildQuery_emptyFilterList_noWhereClause, BigQuerySyncAdapterTest, buildQuery_numericTypeFilter_noQuotes, buildQuery_floatTypeFilter_noQuotes, ... |

## Connected Communities

- **get** (1 cross-edges)
- **adapters** (1 cross-edges)

## How to Explore

```
get_communities with id: "community-273"
smart_context with task: "understand adapters", format: "gcx"
```

_`format: "gcx"` returns the [GCX1 compact wire format](../../docs/wire-format.md) — round-trippable, ~27% fewer tokens than JSON. Drop it for JSON output; agents using `@gortex/wire` or the Go `github.com/zzet/gortex/pkg/wire` package decode either._
