# Workflows Emulator — Feature Gap Closure

**Date:** 2026-04-22
**Context:** Competitive analysis against [gcw-emulator](https://github.com/lemonberrylabs/gcw-emulator) identified 9 feature gaps in LocalCloud's Cloud Workflows emulator.

## Gaps to Close

| # | Gap | Effort | Files |
|---|-----|--------|-------|
| 1 | `for` range syntax | S | `WorkflowExecutor.java` |
| 2 | `hash` module | S | New `HashFunctions.java`, `StdlibRegistry.java` |
| 3 | `time` module | S | New `TimeFunctions.java`, `StdlibRegistry.java` |
| 4 | Error stack traces | S | `ExecutionContext.java`, `WorkflowException.java` |
| 5 | Child workflow execution connector | M | `ConnectorRegistry.java`, `WorkflowExecutor.java` |
| 6 | `parallel` shared variables | M | `WorkflowParser.java`, `WorkflowExecutor.java`, `ExecutionContext.java` |
| 7 | Production-parity limits | M | New `WorkflowLimits.java`, enforcement across engine/stdlib |
| 8 | gRPC API binding | L | New `WorkflowsGrpcServiceImpl.java`, `ExecutionsGrpcServiceImpl.java`, `LocalCloudApplication.java` |
| 9 | Hot-reload from filesystem | M | New `WorkflowFileWatcher.java`, `WorkflowsEmulator.java` |

## Design

### Gap 1: `for` range syntax

In `WorkflowExecutor.executeFor()`, detect `range` key in step definition. When `range: [start, end]` is specified (instead of `in: ${list}`), generate an inclusive integer list `[start, start+1, ..., end]` and iterate. The `value` binding receives each integer. This matches real GCW behavior.

### Gap 2: `hash` module

New `stdlib/HashFunctions.java`:
- `hash.compute_checksum(data, algorithm)` — `java.security.MessageDigest`, supports SHA-256/384/512, MD5, SHA-1. Returns hex string.
- `hash.compute_hmac(data, key, algorithm)` — `javax.crypto.Mac` with `HmacSHA256` etc. Returns hex string.

Register in `StdlibRegistry.java`.

### Gap 3: `time` module

New `stdlib/TimeFunctions.java`:
- `time.format(timestamp, timezone?)` — converts Unix epoch (double) to RFC 3339 string via `DateTimeFormatter`. Default: UTC.
- `time.parse(string)` — parses RFC 3339 to Unix epoch (double).

Register in `StdlibRegistry.java`.

### Gap 4: Error stack traces

Add `List<String> stepChain` to `ExecutionContext`. Push step name on entry (`"main.stepName"` or `"subworkflowName.stepName"`), pop on exit. When `WorkflowException` is created, capture chain snapshot. Add `stack_trace` field to `toErrorMap()`.

### Gap 5: Child workflow execution connector

Add to `ConnectorRegistry`:
```
googleapis.workflowexecutions.v1.projects.locations.workflows.executions.run
```

Unlike HTTP-routed connectors, this looks up the target workflow in `WorkflowsStore`, parses it, and executes synchronously via `WorkflowExecutor`. Args: `workflow_id` (or resource name) + `argument`. Inject store reference via `ConnectorRegistry` constructor or callback.

### Gap 6: `parallel` shared variables

Parse `shared: [var1, var2, ...]` in `WorkflowParser` for parallel steps. In `executeParallel()`:
1. Extract shared var initial values from parent context
2. Create `ConcurrentHashMap` for shared state, protected by `ReentrantLock`
3. Each branch's child context gets synchronized read/write to shared map
4. After all branches complete, merge shared vars back to parent

Only explicitly listed variables are shared — all others remain isolated.

### Gap 7: Production-parity limits

New `WorkflowLimits.java` constants class. Enforcement points:

| Limit | Value | Where |
|-------|-------|-------|
| Steps/execution | 100,000 | `executeStep()` counter |
| Variable memory | 512 KB | `setVariable()` size estimate |
| String length | 256 KB | `ExpressionEvaluator` string ops |
| HTTP response | 2 MB | `HttpFunctions` body check |
| Workflow source | 128 KB | `createWorkflow()` |
| Execution args | 32 KB | `createExecution()` |
| continueAll exceptions | 100 | `executeParallel()` |

### Gap 8: gRPC API binding

Two new service implementations:

`WorkflowsGrpcServiceImpl` (`WorkflowsGrpc.WorkflowsImplBase`):
- `createWorkflow`, `getWorkflow`, `updateWorkflow`, `deleteWorkflow`, `listWorkflows`
- Delegates to existing `WorkflowsServiceImpl` logic
- Create/Update/Delete return `Operation` with `done=true` (instant completion)

`ExecutionsGrpcServiceImpl` (`ExecutionsGrpc.ExecutionsImplBase`):
- `createExecution`, `getExecution`, `listExecutions`, `cancelExecution`
- Delegates to existing execution logic

Registration: `grpcBuilder.addService(...)` in `LocalCloudApplication.java`.

Requires proto stubs: `com.google.cloud:google-cloud-workflows` and `com.google.api.grpc:proto-google-cloud-workflows-v1` in `build.gradle`.

### Gap 9: Hot-reload from filesystem

New `WorkflowFileWatcher`:
- Configurable via `LOCALCLOUD_WORKFLOWS_DIR` env var (optional — inactive if unset)
- `java.nio.file.WatchService` monitors `.yaml`/`.json` files
- Create/modify: parse YAML, `store.upsertWorkflow()` with filename as workflow ID
- Delete: `store.deleteWorkflow()`
- Daemon thread, started during emulator init

## Verification

1. Add unit tests for each new stdlib module (`HashFunctionsTest`, `TimeFunctionsTest`)
2. Add `for` range tests in `WorkflowExecutorTest`
3. Add parallel shared variable tests
4. Add stack trace assertion in existing error-handling tests
5. Add integration test for child workflow execution
6. Run full test suite: `cd localcloud-server && ./gradlew test`
7. Smoke test: build shadow JAR, run Docker, execute sample workflows from `seed.yaml`
