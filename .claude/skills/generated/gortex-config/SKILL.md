---
name: gortex-config
description: "Work in the config area — 24 symbols across 2 files (63% cohesion)"
---

# config

24 symbols | 2 files | 63% cohesion

## When to Use

Use this skill when working on files in:
- `localcloud-server/src/main/java/com/localcloud/config/LocalCloudConfig.java`
- `localcloud-server/src/test/java/com/localcloud/config/LocalCloudConfigTest.java`

## Key Files

| File | Symbols |
|------|---------|
| `localcloud-server/src/main/java/com/localcloud/config/LocalCloudConfig.java` | getEnabledServices, isServiceDynamicallyEnabled, getConfigSource |
| `localcloud-server/src/test/java/com/localcloud/config/LocalCloudConfigTest.java` | customPostgresSettings, setProperty, LocalCloudConfigTest, localcloudServicesOverridesIndividualFlags, persistenceCanBeDisabled, ... |

## Connected Communities

- **config** (17 cross-edges)
- **get** (3 cross-edges)
- **get** (2 cross-edges)
- **config** (1 cross-edges)
- **config** (1 cross-edges)
- **config** (1 cross-edges)
- **config** (1 cross-edges)
- **gateway** (1 cross-edges)
- **config** (1 cross-edges)
- **admin** (1 cross-edges)

## How to Explore

```
get_communities with id: "community-121"
smart_context with task: "understand config", format: "gcx"
```

_`format: "gcx"` returns the [GCX1 compact wire format](../../docs/wire-format.md) — round-trippable, ~27% fewer tokens than JSON. Drop it for JSON output; agents using `@gortex/wire` or the Go `github.com/zzet/gortex/pkg/wire` package decode either._
