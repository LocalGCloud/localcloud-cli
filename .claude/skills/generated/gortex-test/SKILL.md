---
name: gortex-test
description: "Work in the test area — 30 symbols across 8 files (59% cohesion)"
---

# test

30 symbols | 8 files | 59% cohesion

## When to Use

Use this skill when working on files in:
- `localcloud-server/src/main/java/com/localcloud/admin/CredentialBroker.java`
- `localcloud-server/src/main/java/com/localcloud/admin/bigtablesql/SqlTokenizer.java`
- `localcloud-server/src/main/java/com/localcloud/emulators/workflows/stdlib/StdlibRegistry.java`
- `localcloud-server/src/main/java/com/localcloud/sync/adapters/FirestoreSyncAdapter.java`
- `localcloud-server/src/main/java/com/localcloud/sync/adapters/RetryableHttpClient.java`
- `localcloud-server/src/test/java/com/localcloud/emulators/secretmanager/SecretManagerRestServiceTest.java`
- `localcloud-server/src/test/java/com/localcloud/emulators/workflows/connector/ConnectorRegistryTest.java`
- `localcloud-server/src/test/java/com/localcloud/emulators/workflows/stdlib/StdlibRegistryTest.java`

## Key Files

| File | Symbols |
|------|---------|
| `localcloud-server/src/main/java/com/localcloud/admin/CredentialBroker.java` | validateCredentialFile, getAccessToken |
| `localcloud-server/src/main/java/com/localcloud/admin/bigtablesql/SqlTokenizer.java` | readString |
| `localcloud-server/src/main/java/com/localcloud/emulators/workflows/stdlib/StdlibRegistry.java` | has |
| `localcloud-server/src/main/java/com/localcloud/sync/adapters/FirestoreSyncAdapter.java` | extractFirestoreValue |
| `localcloud-server/src/main/java/com/localcloud/sync/adapters/RetryableHttpClient.java` | get |
| `localcloud-server/src/test/java/com/localcloud/emulators/secretmanager/SecretManagerRestServiceTest.java` | secretResponseFormat_matchesGoogleApi |
| `localcloud-server/src/test/java/com/localcloud/emulators/workflows/connector/ConnectorRegistryTest.java` | testCloudTasksRegistered, testBigQueryQueryRegistered, testGcsInsertRegistered, testFirestoreRegistered, testSecretManagerRegistered, ... |
| `localcloud-server/src/test/java/com/localcloud/emulators/workflows/stdlib/StdlibRegistryTest.java` | testRegistryHasMathAbs, testRegistryHasJsonDecode, testProductionParityHelpersRegistered, testRegistryHasTextToUpper, testRegistryMissing, ... |

## Connected Communities

- **admin** (6 cross-edges)
- **examples/python-sdk-demo/src** (4 cross-edges)
- **build** (2 cross-edges)
- **handle** (2 cross-edges)
- **get** (2 cross-edges)
- **adapters** (1 cross-edges)
- **workflows** (1 cross-edges)

## How to Explore

```
get_communities with id: "community-145"
smart_context with task: "understand test", format: "gcx"
```

_`format: "gcx"` returns the [GCX1 compact wire format](../../docs/wire-format.md) — round-trippable, ~27% fewer tokens than JSON. Drop it for JSON output; agents using `@gortex/wire` or the Go `github.com/gortexhq/gcx-go` package decode either._
