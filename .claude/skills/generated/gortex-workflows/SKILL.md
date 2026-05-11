---
name: gortex-workflows
description: "Work in the workflows area — 61 symbols across 4 files (68% cohesion)"
---

# workflows

61 symbols | 4 files | 68% cohesion

## When to Use

Use this skill when working on files in:
- `localcloud-server/src/main/java/com/localcloud/emulators/workflows/ExecutionsGrpcServiceImpl.java`
- `localcloud-server/src/main/java/com/localcloud/emulators/workflows/WorkflowsServiceImpl.java`
- `localcloud-server/src/main/java/com/localcloud/emulators/workflows/WorkflowsStore.java`
- `localcloud-server/src/test/java/com/localcloud/emulators/workflows/WorkflowsServiceImplTest.java`

## Key Files

| File | Symbols |
|------|---------|
| `localcloud-server/src/main/java/com/localcloud/emulators/workflows/ExecutionsGrpcServiceImpl.java` | createExecution, getExecution |
| `localcloud-server/src/main/java/com/localcloud/emulators/workflows/WorkflowsServiceImpl.java` | cancelExecution, getExecution, createExecution, getWorkflow, createWorkflow, ... |
| `localcloud-server/src/main/java/com/localcloud/emulators/workflows/WorkflowsStore.java` | getExecution, getWorkflow |
| `localcloud-server/src/test/java/com/localcloud/emulators/workflows/WorkflowsServiceImplTest.java` | cancelExecution_alreadyFailed_throwsIllegalState, cancelExecution_alreadySucceeded_throwsIllegalState, cancelExecution_alreadyCancelled_throwsIllegalState, WorkflowsServiceImplTest, LOCATION, ... |

## Connected Communities

- **get** (44 cross-edges)
- **handle** (44 cross-edges)
- **get** (29 cross-edges)
- **workflows** (2 cross-edges)
- **engine** (2 cross-edges)
- **workflows** (2 cross-edges)
- **stdlib** (1 cross-edges)
- **engine** (1 cross-edges)
- **engine** (1 cross-edges)
- **engine** (1 cross-edges)

## How to Explore

```
get_communities with id: "community-238"
smart_context with task: "understand workflows", format: "gcx"
```

_`format: "gcx"` returns the [GCX1 compact wire format](../../docs/wire-format.md) — round-trippable, ~27% fewer tokens than JSON. Drop it for JSON output; agents using `@gortex/wire` or the Go `github.com/zzet/gortex/pkg/wire` package decode either._
