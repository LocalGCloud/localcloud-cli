---
name: gortex-expression
description: "Work in the expression area — 29 symbols across 1 files (100% cohesion)"
---

# expression

29 symbols | 1 files | 100% cohesion

## When to Use

Use this skill when working on files in:
- `localcloud-server/src/main/java/com/localcloud/emulators/workflows/expression/Token.java`

## Key Files

| File | Symbols |
|------|---------|
| `localcloud-server/src/main/java/com/localcloud/emulators/workflows/expression/Token.java` | TokenType, GTE, GT, AND, DOT, ... |

## How to Explore

```
get_communities with id: "community-271"
smart_context with task: "understand expression", format: "gcx"
```

_`format: "gcx"` returns the [GCX1 compact wire format](../../docs/wire-format.md) — round-trippable, ~27% fewer tokens than JSON. Drop it for JSON output; agents using `@gortex/wire` or the Go `github.com/zzet/gortex/pkg/wire` package decode either._
