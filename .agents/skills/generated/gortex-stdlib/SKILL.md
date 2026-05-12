---
name: gortex-stdlib
description: "Work in the stdlib area — 80 symbols across 1 files (96% cohesion)"
---

# stdlib

80 symbols | 1 files | 96% cohesion

## When to Use

Use this skill when working on files in:
- `localcloud-server/src/test/java/com/localcloud/emulators/workflows/stdlib/StdlibRegistryTest.java`

## Key Files

| File | Symbols |
|------|---------|
| `localcloud-server/src/test/java/com/localcloud/emulators/workflows/stdlib/StdlibRegistryTest.java` | testUuidAndRetryFunctions, call, testMapGetMissingNoDefault, testTextReplaceAll, testMathFloorNegative, ... |

## Connected Communities

- **handle** (5 cross-edges)
- **workflows** (1 cross-edges)

## How to Explore

```
get_communities with id: "community-97"
smart_context with task: "understand stdlib", format: "gcx"
```

_`format: "gcx"` returns the [GCX1 compact wire format](../../docs/wire-format.md) — round-trippable, ~27% fewer tokens than JSON. Drop it for JSON output; agents using `@gortex/wire` or the Go `github.com/gortexhq/gcx-go` package decode either._
