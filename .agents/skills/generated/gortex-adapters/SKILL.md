---
name: gortex-adapters
description: "Work in the adapters area — 29 symbols across 3 files (80% cohesion)"
---

# adapters

29 symbols | 3 files | 80% cohesion

## When to Use

Use this skill when working on files in:
- `localcloud-server/src/main/java/com/localcloud/sync/adapters/SpannerSyncAdapter.java`
- `localcloud-server/src/test/java/com/localcloud/sync/adapters/BigQuerySyncAdapterTest.java`
- `localcloud-server/src/test/java/com/localcloud/sync/adapters/SpannerSyncAdapterTest.java`

## Key Files

| File | Symbols |
|------|---------|
| `localcloud-server/src/main/java/com/localcloud/sync/adapters/SpannerSyncAdapter.java` | buildSyncQuery |
| `localcloud-server/src/test/java/com/localcloud/sync/adapters/BigQuerySyncAdapterTest.java` | buildQuery_inOperator_numericValues, buildQuery_withFilters_addsWhereClause, buildQuery_inOperator_handledCorrectly, buildQuery_invalidColumn_throws, buildQuery_floatTypeFilter_noQuotes, ... |
| `localcloud-server/src/test/java/com/localcloud/sync/adapters/SpannerSyncAdapterTest.java` | buildSyncQuery_multipleFilters, buildSyncQuery_withNumericFilter, buildSyncQuery_singleQuotesEscaped, buildSyncQuery_boolFilter_noQuotes, buildSyncQuery_noFilters_noLimit, ... |

## Connected Communities

- **adapters** (2 cross-edges)
- **test** (1 cross-edges)
- **examples/python-sdk-demo/src** (1 cross-edges)

## How to Explore

```
get_communities with id: "community-251"
smart_context with task: "understand adapters", format: "gcx"
```

_`format: "gcx"` returns the [GCX1 compact wire format](../../docs/wire-format.md) — round-trippable, ~27% fewer tokens than JSON. Drop it for JSON output; agents using `@gortex/wire` or the Go `github.com/gortexhq/gcx-go` package decode either._
