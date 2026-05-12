---
name: gortex-bigtablesql
description: "Work in the bigtablesql area — 60 symbols across 4 files (88% cohesion)"
---

# bigtablesql

60 symbols | 4 files | 88% cohesion

## When to Use

Use this skill when working on files in:
- `localcloud-server/src/main/java/com/localcloud/admin/bigtablesql/SqlParser.java`
- `localcloud-server/src/main/java/com/localcloud/emulators/memorystore/MemorystoreStore.java`
- `localcloud-server/src/test/java/com/localcloud/admin/bigtablesql/SqlTokenizerTest.java`
- `localcloud-server/src/test/java/com/localcloud/events/EventBusTest.java`

## Key Files

| File | Symbols |
|------|---------|
| `localcloud-server/src/main/java/com/localcloud/admin/bigtablesql/SqlParser.java` | parseComparison, parseAdditive, parseMultiplicative, peek, parseAnd, ... |
| `localcloud-server/src/main/java/com/localcloud/emulators/memorystore/MemorystoreStore.java` | type |
| `localcloud-server/src/test/java/com/localcloud/admin/bigtablesql/SqlTokenizerTest.java` | operators, betweenAndLike, createTable, commentSkipped, insertKeywords, ... |
| `localcloud-server/src/test/java/com/localcloud/events/EventBusTest.java` | eventRecordFieldsAccessible |

## Connected Communities

- **handle** (39 cross-edges)
- **src** (9 cross-edges)
- **get** (4 cross-edges)
- **get** (1 cross-edges)
- **build** (1 cross-edges)

## How to Explore

```
get_communities with id: "community-39"
smart_context with task: "understand bigtablesql", format: "gcx"
```

_`format: "gcx"` returns the [GCX1 compact wire format](../../docs/wire-format.md) — round-trippable, ~27% fewer tokens than JSON. Drop it for JSON output; agents using `@gortex/wire` or the Go `github.com/gortexhq/gcx-go` package decode either._
