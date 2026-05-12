---
name: gortex-gateway
description: "Work in the gateway area — 27 symbols across 4 files (90% cohesion)"
---

# gateway

27 symbols | 4 files | 90% cohesion

## When to Use

Use this skill when working on files in:
- `localcloud-server/src/main/java/com/localcloud/config/LocalCloudConfig.java`
- `localcloud-server/src/main/java/com/localcloud/gateway/IamMiddleware.java`
- `localcloud-server/src/main/java/com/localcloud/gateway/ServiceGatingDecorator.java`
- `localcloud-server/src/test/java/com/localcloud/gateway/IamMiddlewareTest.java`

## Key Files

| File | Symbols |
|------|---------|
| `localcloud-server/src/main/java/com/localcloud/config/LocalCloudConfig.java` | getIamPolicyFile, getIamMode |
| `localcloud-server/src/main/java/com/localcloud/gateway/IamMiddleware.java` | getMode, IamMiddleware.<init>, loadLocalPolicies |
| `localcloud-server/src/main/java/com/localcloud/gateway/ServiceGatingDecorator.java` | serve |
| `localcloud-server/src/test/java/com/localcloud/gateway/IamMiddlewareTest.java` | permissiveModeAllowsRequestsWithNoAuth, adminEndpointBypassesIamInPermissiveMode, adminEndpointNestedPathBypassesIam, IamMiddlewareTest, adminEndpointBypassesIamInStrictMode, ... |

## Connected Communities

- **handle** (2 cross-edges)
- **test** (1 cross-edges)
- **build** (1 cross-edges)
- **src** (1 cross-edges)
- **gateway** (1 cross-edges)
- **config** (1 cross-edges)
- **get** (1 cross-edges)

## How to Explore

```
get_communities with id: "community-306"
smart_context with task: "understand gateway", format: "gcx"
```

_`format: "gcx"` returns the [GCX1 compact wire format](../../docs/wire-format.md) — round-trippable, ~27% fewer tokens than JSON. Drop it for JSON output; agents using `@gortex/wire` or the Go `github.com/gortexhq/gcx-go` package decode either._
