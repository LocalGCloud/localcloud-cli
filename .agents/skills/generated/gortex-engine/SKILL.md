---
name: gortex-engine
description: "Work in the engine area — 27 symbols across 1 files (94% cohesion)"
---

# engine

27 symbols | 1 files | 94% cohesion

## When to Use

Use this skill when working on files in:
- `localcloud-server/src/test/java/com/localcloud/emulators/workflows/engine/WorkflowExecutorTest.java`

## Key Files

| File | Symbols |
|------|---------|
| `localcloud-server/src/test/java/com/localcloud/emulators/workflows/engine/WorkflowExecutorTest.java` | runWorkflow, testForLoopBreakAndContinue, testAssignMax50, testSwitchDefault, testForLoop, ... |

## Connected Communities

- **admin** (1 cross-edges)
- **build** (1 cross-edges)
- **handle** (1 cross-edges)

## How to Explore

```
get_communities with id: "community-261"
smart_context with task: "understand engine", format: "gcx"
```

_`format: "gcx"` returns the [GCX1 compact wire format](../../docs/wire-format.md) — round-trippable, ~27% fewer tokens than JSON. Drop it for JSON output; agents using `@gortex/wire` or the Go `github.com/gortexhq/gcx-go` package decode either._
