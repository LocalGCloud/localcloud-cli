---
name: gortex-gateway
description: "Work in the gateway area — 25 symbols across 4 files (89% cohesion)"
---

# gateway

25 symbols | 4 files | 89% cohesion

## When to Use

Use this skill when working on files in:
- `localcloud-server/src/main/java/com/localcloud/config/LocalCloudConfig.java`
- `localcloud-server/src/main/java/com/localcloud/gateway/IamMiddleware.java`
- `localcloud-server/src/main/java/com/localcloud/gateway/ServiceGatingDecorator.java`
- `localcloud-server/src/test/java/com/localcloud/gateway/IamMiddlewareTest.java`

## Key Files

| File | Symbols |
|------|---------|
| `localcloud-server/src/main/java/com/localcloud/config/LocalCloudConfig.java` | getIamPolicyFile |
| `localcloud-server/src/main/java/com/localcloud/gateway/IamMiddleware.java` | getMode, serve |
| `localcloud-server/src/main/java/com/localcloud/gateway/ServiceGatingDecorator.java` | serve |
| `localcloud-server/src/test/java/com/localcloud/gateway/IamMiddlewareTest.java` | permissiveModeGetMode, emptyPolicyFileResultsInNoBindings, unknownModeStillReportsItsMode, strictModeGetMode, mockConfig, ... |

## Connected Communities

- **config** (3 cross-edges)
- **gateway** (2 cross-edges)
- **config** (1 cross-edges)
- **gateway** (1 cross-edges)

## How to Explore

```
get_communities with id: "community-122"
smart_context with task: "understand gateway", format: "gcx"
```

_`format: "gcx"` returns the [GCX1 compact wire format](../../docs/wire-format.md) — round-trippable, ~27% fewer tokens than JSON. Drop it for JSON output; agents using `@gortex/wire` or the Go `github.com/zzet/gortex/pkg/wire` package decode either._
