# Workflows Gap Closure Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Close 9 feature gaps between LocalCloud's Cloud Workflows emulator and gcw-emulator.

**Architecture:** All changes are in-process additions to the existing Java workflows emulator. New stdlib modules follow the existing `register(StdlibRegistry)` pattern. Engine gaps (for-range, parallel shared vars, stack traces) modify `WorkflowExecutor` and `ExecutionContext`. gRPC bindings delegate to existing `WorkflowsServiceImpl`. Hot-reload uses `java.nio.file.WatchService` on a daemon thread.

**Tech Stack:** Java 21, JUnit 5, Armeria, gRPC-Java 1.68.2, proto-google-cloud-workflows-v1 2.64.0

---

## Task 1: `for` range syntax

**Files:**
- Modify: `localcloud-server/src/main/java/com/localcloud/emulators/workflows/engine/WorkflowExecutor.java:238-268`
- Test: `localcloud-server/src/test/java/com/localcloud/emulators/workflows/engine/WorkflowExecutorTest.java`

**Step 1: Write the failing test**

Add to `WorkflowExecutorTest.java`:

```java
@Test
void testForRange() {
    String yaml = """
        main:
          steps:
            - init:
                assign:
                  - total: 0
            - loop:
                for:
                  value: n
                  range: [1, 5]
                  steps:
                    - add:
                        assign:
                          - total: ${total + n}
            - done:
                return: ${total}
        """;
    assertEquals(15, runWorkflow(yaml));
}

@Test
void testForRangeWithIndex() {
    String yaml = """
        main:
          steps:
            - init:
                assign:
                  - last_idx: 0
            - loop:
                for:
                  value: n
                  index: i
                  range: [10, 12]
                  steps:
                    - track:
                        assign:
                          - last_idx: ${i}
            - done:
                return: ${last_idx}
        """;
    assertEquals(2, runWorkflow(yaml));
}
```

**Step 2: Run test to verify it fails**

Run: `cd localcloud-server && ./gradlew test --tests '*WorkflowExecutorTest.testForRange'`
Expected: FAIL — `TypeError: for 'in' must be a list`

**Step 3: Write minimal implementation**

In `WorkflowExecutor.java`, modify `executeFor()` — after line 241 (indexVar), before existing `inObj` evaluation:

```java
@SuppressWarnings("unchecked")
private void executeFor(WorkflowDefinition.StepDef step) {
    Map<String, Object> forConfig = (Map<String, Object>) step.get("for");
    String valueVar = String.valueOf(forConfig.get("value"));
    String indexVar = forConfig.containsKey("index") ? String.valueOf(forConfig.get("index")) : null;

    List<?> items;
    // Range-based iteration: range: [start, end] (inclusive)
    if (forConfig.containsKey("range")) {
        Object rangeObj = evaluateValue(forConfig.get("range"));
        if (rangeObj instanceof List<?> rangeList && rangeList.size() == 2) {
            int start = ((Number) rangeList.get(0)).intValue();
            int end = ((Number) rangeList.get(1)).intValue();
            List<Integer> rangeItems = new ArrayList<>();
            for (int r = start; r <= end; r++) rangeItems.add(r);
            items = rangeItems;
        } else {
            throw new WorkflowException("TypeError", "for 'range' must be a list of [start, end]");
        }
    } else {
        Object inObj = evaluateValue(forConfig.get("in"));
        if (inObj instanceof List<?> list) {
            items = list;
        } else {
            throw new WorkflowException("TypeError", "for 'in' must be a list, got " + (inObj == null ? "null" : inObj.getClass().getSimpleName()));
        }
    }

    List<WorkflowDefinition.StepDef> bodySteps = Collections.emptyList();
    if (forConfig.get("steps") instanceof List<?> stepsList) {
        bodySteps = parseInlineSteps(stepsList);
    }

    for (int idx = 0; idx < items.size(); idx++) {
        if (context.isCancelled()) throw new WorkflowException("Cancelled", "Execution was cancelled");
        context.setVariable(valueVar, items.get(idx));
        if (indexVar != null) context.setVariable(indexVar, idx);
        try {
            executeSteps(bodySteps);
        } catch (NextStepException e) {
            if ("break".equals(e.getTargetStep())) break;
            if ("continue".equals(e.getTargetStep())) continue;
            throw e;
        }
    }
}
```

**Step 4: Run test to verify it passes**

Run: `cd localcloud-server && ./gradlew test --tests '*WorkflowExecutorTest.testForRange*'`
Expected: PASS

**Step 5: Commit**

```bash
git add localcloud-server/src/main/java/com/localcloud/emulators/workflows/engine/WorkflowExecutor.java \
       localcloud-server/src/test/java/com/localcloud/emulators/workflows/engine/WorkflowExecutorTest.java
git commit -m "feat(workflows): add for-range syntax support"
```

---

## Task 2: `hash` module

**Files:**
- Create: `localcloud-server/src/main/java/com/localcloud/emulators/workflows/stdlib/HashFunctions.java`
- Modify: `localcloud-server/src/main/java/com/localcloud/emulators/workflows/stdlib/StdlibRegistry.java:13-24`
- Test: `localcloud-server/src/test/java/com/localcloud/emulators/workflows/stdlib/StdlibRegistryTest.java`

**Step 1: Write the failing tests**

Add to `StdlibRegistryTest.java`:

```java
// --- hash ---

@Test
void testHashComputeChecksumSha256() {
    String result = (String) call("hash.compute_checksum", "hello", "SHA-256");
    assertEquals("2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824", result);
}

@Test
void testHashComputeChecksumMd5() {
    String result = (String) call("hash.compute_checksum", "hello", "MD5");
    assertEquals("5d41402abc4b2a76b9719d911017c592", result);
}

@Test
void testHashComputeHmacSha256() {
    String result = (String) call("hash.compute_hmac", "hello", "secret", "SHA-256");
    assertNotNull(result);
    assertEquals(64, result.length()); // SHA-256 HMAC = 32 bytes = 64 hex chars
}

@Test
void testHashComputeChecksumUnsupportedAlgorithm() {
    assertThrows(RuntimeException.class, () -> call("hash.compute_checksum", "hello", "UNSUPPORTED"));
}
```

**Step 2: Run test to verify it fails**

Run: `cd localcloud-server && ./gradlew test --tests '*StdlibRegistryTest.testHashCompute*'`
Expected: FAIL — `Function not found: hash.compute_checksum`

**Step 3: Write implementation**

Create `HashFunctions.java`:

```java
package com.localcloud.emulators.workflows.stdlib;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

public class HashFunctions {
    private static final Map<String, String> ALGORITHM_MAP = Map.of(
        "SHA-256", "SHA-256", "SHA-384", "SHA-384", "SHA-512", "SHA-512",
        "MD5", "MD5", "SHA-1", "SHA-1"
    );
    private static final Map<String, String> HMAC_ALGORITHM_MAP = Map.of(
        "SHA-256", "HmacSHA256", "SHA-384", "HmacSHA384", "SHA-512", "HmacSHA512",
        "MD5", "HmacMD5", "SHA-1", "HmacSHA1"
    );

    public static void register(StdlibRegistry registry) {
        registry.register("hash.compute_checksum", HashFunctions::computeChecksum);
        registry.register("hash.compute_hmac", HashFunctions::computeHmac);
    }

    private static Object computeChecksum(List<Object> args) {
        if (args.size() < 2) throw new RuntimeException("hash.compute_checksum requires (data, algorithm)");
        String data = String.valueOf(args.get(0));
        String algorithm = String.valueOf(args.get(1));
        String javaAlg = ALGORITHM_MAP.get(algorithm);
        if (javaAlg == null) throw new RuntimeException("Unsupported hash algorithm: " + algorithm);
        try {
            MessageDigest md = MessageDigest.getInstance(javaAlg);
            byte[] hash = md.digest(data.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            throw new RuntimeException("hash.compute_checksum failed: " + e.getMessage(), e);
        }
    }

    private static Object computeHmac(List<Object> args) {
        if (args.size() < 3) throw new RuntimeException("hash.compute_hmac requires (data, key, algorithm)");
        String data = String.valueOf(args.get(0));
        String key = String.valueOf(args.get(1));
        String algorithm = String.valueOf(args.get(2));
        String javaAlg = HMAC_ALGORITHM_MAP.get(algorithm);
        if (javaAlg == null) throw new RuntimeException("Unsupported HMAC algorithm: " + algorithm);
        try {
            Mac mac = Mac.getInstance(javaAlg);
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), javaAlg));
            byte[] hash = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            throw new RuntimeException("hash.compute_hmac failed: " + e.getMessage(), e);
        }
    }
}
```

Register in `StdlibRegistry.java` — add after `EventsFunctions.register(this);`:

```java
HashFunctions.register(this);
```

**Step 4: Run test to verify it passes**

Run: `cd localcloud-server && ./gradlew test --tests '*StdlibRegistryTest.testHash*'`
Expected: PASS

**Step 5: Commit**

```bash
git add localcloud-server/src/main/java/com/localcloud/emulators/workflows/stdlib/HashFunctions.java \
       localcloud-server/src/main/java/com/localcloud/emulators/workflows/stdlib/StdlibRegistry.java \
       localcloud-server/src/test/java/com/localcloud/emulators/workflows/stdlib/StdlibRegistryTest.java
git commit -m "feat(workflows): add hash.compute_checksum and hash.compute_hmac"
```

---

## Task 3: `time` module

**Files:**
- Create: `localcloud-server/src/main/java/com/localcloud/emulators/workflows/stdlib/TimeFunctions.java`
- Modify: `localcloud-server/src/main/java/com/localcloud/emulators/workflows/stdlib/StdlibRegistry.java`
- Test: `localcloud-server/src/test/java/com/localcloud/emulators/workflows/stdlib/StdlibRegistryTest.java`

**Step 1: Write the failing tests**

Add to `StdlibRegistryTest.java`:

```java
// --- time ---

@Test
void testTimeFormatDefault() {
    // Unix epoch 0 in UTC = 1970-01-01T00:00:00Z
    String result = (String) call("time.format", 0.0);
    assertTrue(result.startsWith("1970-01-01T00:00:00"));
}

@Test
void testTimeFormatWithTimezone() {
    String result = (String) call("time.format", 0.0, "America/New_York");
    assertTrue(result.contains("1969-12-31") || result.contains("1970-01-01"));
}

@Test
void testTimeParse() {
    Object result = call("time.parse", "2026-04-22T12:00:00Z");
    assertTrue(result instanceof Double || result instanceof Number);
    double epoch = ((Number) result).doubleValue();
    assertTrue(epoch > 1_700_000_000); // sanity check: after 2023
}

@Test
void testTimeRoundTrip() {
    double now = System.currentTimeMillis() / 1000.0;
    String formatted = (String) call("time.format", now);
    double parsed = ((Number) call("time.parse", formatted)).doubleValue();
    assertEquals(now, parsed, 1.0); // within 1 second
}
```

**Step 2: Run test to verify it fails**

Run: `cd localcloud-server && ./gradlew test --tests '*StdlibRegistryTest.testTime*'`
Expected: FAIL — `Function not found: time.format`

**Step 3: Write implementation**

Create `TimeFunctions.java`:

```java
package com.localcloud.emulators.workflows.stdlib;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.List;

public class TimeFunctions {
    private static final DateTimeFormatter RFC3339 = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXXX");

    public static void register(StdlibRegistry registry) {
        registry.register("time.format", TimeFunctions::format);
        registry.register("time.parse", TimeFunctions::parse);
    }

    private static Object format(List<Object> args) {
        if (args.isEmpty()) throw new RuntimeException("time.format requires (timestamp[, timezone])");
        double epochSeconds = ((Number) args.get(0)).doubleValue();
        long epochMillis = (long) (epochSeconds * 1000);
        Instant instant = Instant.ofEpochMilli(epochMillis);
        ZoneId zone = ZoneId.of("UTC");
        if (args.size() >= 2 && args.get(1) != null) {
            zone = ZoneId.of(String.valueOf(args.get(1)));
        }
        return ZonedDateTime.ofInstant(instant, zone).format(RFC3339);
    }

    private static Object parse(List<Object> args) {
        if (args.isEmpty()) throw new RuntimeException("time.parse requires (string)");
        String s = String.valueOf(args.get(0));
        try {
            ZonedDateTime zdt = ZonedDateTime.parse(s, DateTimeFormatter.ISO_DATE_TIME);
            return zdt.toInstant().toEpochMilli() / 1000.0;
        } catch (Exception e) {
            try {
                Instant instant = Instant.parse(s);
                return instant.toEpochMilli() / 1000.0;
            } catch (Exception e2) {
                throw new RuntimeException("time.parse failed to parse: " + s);
            }
        }
    }
}
```

Register in `StdlibRegistry.java`:

```java
TimeFunctions.register(this);
```

**Step 4: Run test to verify it passes**

Run: `cd localcloud-server && ./gradlew test --tests '*StdlibRegistryTest.testTime*'`
Expected: PASS

**Step 5: Commit**

```bash
git add localcloud-server/src/main/java/com/localcloud/emulators/workflows/stdlib/TimeFunctions.java \
       localcloud-server/src/main/java/com/localcloud/emulators/workflows/stdlib/StdlibRegistry.java \
       localcloud-server/src/test/java/com/localcloud/emulators/workflows/stdlib/StdlibRegistryTest.java
git commit -m "feat(workflows): add time.format and time.parse stdlib functions"
```

---

## Task 4: Error stack traces

**Files:**
- Modify: `localcloud-server/src/main/java/com/localcloud/emulators/workflows/engine/ExecutionContext.java`
- Modify: `localcloud-server/src/main/java/com/localcloud/emulators/workflows/engine/WorkflowException.java`
- Modify: `localcloud-server/src/main/java/com/localcloud/emulators/workflows/engine/WorkflowExecutor.java`
- Test: `localcloud-server/src/test/java/com/localcloud/emulators/workflows/engine/WorkflowExecutorTest.java`

**Step 1: Write the failing test**

Add to `WorkflowExecutorTest.java`:

```java
@Test
void testErrorStackTrace() {
    String yaml = """
        main:
          steps:
            - call_sub:
                call: inner
                args: {}
                result: r
        inner:
          params: []
          steps:
            - fail:
                raise:
                  code: "TestError"
                  message: "deliberate"
        """;
    WorkflowException ex = assertThrows(WorkflowException.class, () -> runWorkflow(yaml));
    Map<String, Object> error = ex.toErrorMap();
    assertTrue(error.containsKey("stack_trace"), "Error should contain stack_trace");
    @SuppressWarnings("unchecked")
    List<String> stack = (List<String>) error.get("stack_trace");
    assertFalse(stack.isEmpty());
}
```

**Step 2: Run test to verify it fails**

Run: `cd localcloud-server && ./gradlew test --tests '*WorkflowExecutorTest.testErrorStackTrace'`
Expected: FAIL — `stack_trace` key not present

**Step 3: Write implementation**

**ExecutionContext.java** — add step chain tracking:

Add fields after line 15:

```java
private final Deque<String> stepChain = new ArrayDeque<>();
```

Add methods after `getStepHistory()`:

```java
public synchronized void pushStepChain(String entry) {
    stepChain.push(entry);
}

public synchronized void popStepChain() {
    if (!stepChain.isEmpty()) stepChain.pop();
}

public synchronized List<String> getStepChain() {
    return new ArrayList<>(stepChain);
}
```

Also add to `createChildContext()` — pass step chain. Update the private constructor to accept and copy the step chain:

```java
private ExecutionContext(Map<String, Object> parentVars, List<Map<String, Object>> sharedStepHistory,
                         String state, int callDepth, Deque<String> parentStepChain) {
    // ... existing code ...
    this.stepChain.addAll(parentStepChain);
}

public ExecutionContext createChildContext(Map<String, Object> additionalVars) {
    Map<String, Object> snapshot = getAllVariables();
    if (additionalVars != null) snapshot.putAll(additionalVars);
    return new ExecutionContext(snapshot, this.stepHistory, this.state, this.callDepth, this.stepChain);
}
```

**WorkflowException.java** — add stack trace field:

Add field: `private List<String> stackTrace;`

Add setter/getter:
```java
public void setStackTrace(List<String> stackTrace) { this.stackTrace = stackTrace; }
public List<String> getWorkflowStackTrace() { return stackTrace; }
```

Update `toErrorMap()`:
```java
public Map<String, Object> toErrorMap() {
    Map<String, Object> error = new java.util.LinkedHashMap<>();
    error.put("code", code);
    error.put("message", getMessage());
    if (tags != null) error.put("tags", tags);
    if (stackTrace != null && !stackTrace.isEmpty()) error.put("stack_trace", stackTrace);
    return error;
}
```

**WorkflowExecutor.java** — push/pop step chain in `executeStep()` and `executeSubworkflow()`:

In `executeSubworkflow()`, after line 53:
```java
context.pushStepChain(name);
```
In the `finally` block, before `popScope()`:
```java
context.popStepChain();
```

In `executeStep()`, wrap the switch body:
```java
private void executeStep(WorkflowDefinition.StepDef step) {
    context.pushStepChain(step.getName());
    try {
        switch (step.getType()) {
            // ... existing cases ...
        }
    } catch (WorkflowException e) {
        if (e.getWorkflowStackTrace() == null) {
            e.setStackTrace(context.getStepChain());
        }
        throw e;
    } finally {
        context.popStepChain();
    }
}
```

**Step 4: Run test to verify it passes**

Run: `cd localcloud-server && ./gradlew test --tests '*WorkflowExecutorTest.testErrorStackTrace'`
Expected: PASS

**Step 5: Commit**

```bash
git add localcloud-server/src/main/java/com/localcloud/emulators/workflows/engine/ExecutionContext.java \
       localcloud-server/src/main/java/com/localcloud/emulators/workflows/engine/WorkflowException.java \
       localcloud-server/src/main/java/com/localcloud/emulators/workflows/engine/WorkflowExecutor.java \
       localcloud-server/src/test/java/com/localcloud/emulators/workflows/engine/WorkflowExecutorTest.java
git commit -m "feat(workflows): add stack_trace to workflow error output"
```

---

## Task 5: Child workflow execution connector

**Files:**
- Modify: `localcloud-server/src/main/java/com/localcloud/emulators/workflows/connector/ConnectorRegistry.java`
- Modify: `localcloud-server/src/main/java/com/localcloud/emulators/workflows/WorkflowsServiceImpl.java`
- Test: `localcloud-server/src/test/java/com/localcloud/emulators/workflows/connector/ConnectorRegistryTest.java`

**Step 1: Write the failing test**

Add to `ConnectorRegistryTest.java`:

```java
@Test
void testHasChildWorkflowConnector() {
    ConnectorRegistry registry = new ConnectorRegistry();
    assertTrue(registry.has("googleapis.workflowexecutions.v1.projects.locations.workflows.executions.run"));
}
```

**Step 2: Run test to verify it fails**

Run: `cd localcloud-server && ./gradlew test --tests '*ConnectorRegistryTest.testHasChildWorkflowConnector'`
Expected: FAIL

**Step 3: Write implementation**

In `ConnectorRegistry.java`, add a `childWorkflowRunner` callback field:

```java
private java.util.function.BiFunction<String, Map<String, Object>, Object> childWorkflowRunner;

public void setChildWorkflowRunner(java.util.function.BiFunction<String, Map<String, Object>, Object> runner) {
    this.childWorkflowRunner = runner;
}
```

In `registerDefaults()`, add at the end:

```java
// Child workflow execution (handled specially in execute())
connectors.put("googleapis.workflowexecutions.v1.projects.locations.workflows.executions.run",
    new ConnectorDef("googleapis.workflowexecutions.v1.projects.locations.workflows.executions.run", "POST", "__CHILD_WORKFLOW__"));
```

In `execute()`, add before the existing `def == null` check:

```java
// Special handling for child workflow execution
if ("__CHILD_WORKFLOW__".equals(def.urlTemplate())) {
    if (childWorkflowRunner == null) {
        throw new RuntimeException("Child workflow execution not configured");
    }
    String workflowId = String.valueOf(args.getOrDefault("workflow_id",
        args.getOrDefault("workflowId", "")));
    @SuppressWarnings("unchecked")
    Map<String, Object> argument = args.get("argument") instanceof Map ?
        (Map<String, Object>) args.get("argument") : Map.of();
    return childWorkflowRunner.apply(workflowId, argument);
}
```

In `WorkflowsServiceImpl.java` constructor, wire it up after creating the ConnectorRegistry:

```java
this.connectorRegistry.setChildWorkflowRunner((workflowId, args) -> {
    try {
        Map<String, Object> workflow = store.getWorkflow(
            store.getProjectIdForExecution(null) != null ? store.getProjectIdForExecution(null) : "local-project",
            "us-central1", workflowId);
        if (workflow == null) throw new RuntimeException("Child workflow not found: " + workflowId);
        String source = (String) workflow.get("source_contents");
        WorkflowDefinition def = WorkflowParser.parse(source);
        ExecutionContext ctx = new ExecutionContext(args);
        WorkflowExecutor executor = new WorkflowExecutor(def, ctx, this.stdlib);
        return executor.execute();
    } catch (Exception e) {
        throw new RuntimeException("Child workflow execution failed: " + e.getMessage(), e);
    }
});
```

**Step 4: Run tests**

Run: `cd localcloud-server && ./gradlew test --tests '*ConnectorRegistryTest*'`
Expected: PASS

**Step 5: Commit**

```bash
git add localcloud-server/src/main/java/com/localcloud/emulators/workflows/connector/ConnectorRegistry.java \
       localcloud-server/src/main/java/com/localcloud/emulators/workflows/WorkflowsServiceImpl.java \
       localcloud-server/src/test/java/com/localcloud/emulators/workflows/connector/ConnectorRegistryTest.java
git commit -m "feat(workflows): add child workflow execution connector"
```

---

## Task 6: `parallel` shared variables

**Files:**
- Modify: `localcloud-server/src/main/java/com/localcloud/emulators/workflows/engine/WorkflowExecutor.java:271-353`
- Modify: `localcloud-server/src/main/java/com/localcloud/emulators/workflows/engine/ExecutionContext.java`
- Test: `localcloud-server/src/test/java/com/localcloud/emulators/workflows/engine/WorkflowExecutorTest.java`

**Step 1: Write the failing test**

Add to `WorkflowExecutorTest.java`:

```java
@Test
void testParallelSharedVariables() {
    String yaml = """
        main:
          steps:
            - init:
                assign:
                  - total: 0
                  - items: [10, 20, 30]
            - aggregate:
                parallel:
                  shared: [total]
                  for:
                    value: item
                    in: ${items}
                    steps:
                      - add:
                          assign:
                            - total: ${total + item}
            - done:
                return: ${total}
        """;
    Object result = runWorkflow(yaml);
    // Order may vary with parallelism, but total should be 60
    assertEquals(60, result);
}
```

**Step 2: Run test to verify it fails**

Run: `cd localcloud-server && ./gradlew test --tests '*WorkflowExecutorTest.testParallelSharedVariables'`
Expected: FAIL — `total` stays 0 because child contexts are fully isolated

**Step 3: Write implementation**

**ExecutionContext.java** — add shared variable support:

Add field:
```java
private final Map<String, Object> sharedVars; // null for non-parallel contexts
private final java.util.concurrent.locks.ReentrantLock sharedLock;
```

Initialize to `null` in constructors. Add a new method for parallel contexts:

```java
public ExecutionContext createChildContextWithShared(Map<String, Object> additionalVars,
                                                      Map<String, Object> sharedVars,
                                                      java.util.concurrent.locks.ReentrantLock sharedLock) {
    ExecutionContext child = createChildContext(additionalVars);
    child.sharedVars = sharedVars;
    child.sharedLock = sharedLock;
    return child;
}
```

Override `setVariable` and `getVariable` to check shared vars first:

```java
public synchronized void setVariable(String name, Object value) {
    if (sharedVars != null && sharedVars.containsKey(name)) {
        sharedLock.lock();
        try { sharedVars.put(name, value); } finally { sharedLock.unlock(); }
        return;
    }
    scopeStack.peek().put(name, value);
}

public synchronized Object getVariable(String name) {
    if (sharedVars != null && sharedVars.containsKey(name)) {
        sharedLock.lock();
        try { return sharedVars.get(name); } finally { sharedLock.unlock(); }
    }
    for (Map<String, Object> scope : scopeStack) {
        if (scope.containsKey(name)) return scope.get(name);
    }
    return null;
}
```

**WorkflowExecutor.java** — modify `executeParallel()`:

At the top of the method, extract shared vars list:

```java
@SuppressWarnings("unchecked")
List<String> sharedVarNames = parallelConfig.containsKey("shared")
    ? ((List<?>) parallelConfig.get("shared")).stream().map(String::valueOf).toList()
    : Collections.emptyList();

// Build shared var map from parent context
Map<String, Object> sharedVars = sharedVarNames.isEmpty() ? null : new java.util.concurrent.ConcurrentHashMap<>();
java.util.concurrent.locks.ReentrantLock sharedLock = sharedVarNames.isEmpty() ? null : new java.util.concurrent.locks.ReentrantLock();
if (sharedVars != null) {
    for (String name : sharedVarNames) {
        sharedVars.put(name, context.getVariable(name));
    }
}
```

Replace `context.createChildContext(...)` calls with:

```java
ExecutionContext childCtx = sharedVars != null
    ? context.createChildContextWithShared(Map.of(valueVar, item), sharedVars, sharedLock)
    : context.createChildContext(Map.of(valueVar, item));
```

After all futures complete, merge shared vars back to parent:

```java
if (sharedVars != null) {
    for (Map.Entry<String, Object> entry : sharedVars.entrySet()) {
        context.setVariable(entry.getKey(), entry.getValue());
    }
}
```

Apply the same pattern for both parallel-for and parallel-branches blocks.

**Step 4: Run test to verify it passes**

Run: `cd localcloud-server && ./gradlew test --tests '*WorkflowExecutorTest.testParallelShared*'`
Expected: PASS

**Step 5: Commit**

```bash
git add localcloud-server/src/main/java/com/localcloud/emulators/workflows/engine/WorkflowExecutor.java \
       localcloud-server/src/main/java/com/localcloud/emulators/workflows/engine/ExecutionContext.java \
       localcloud-server/src/test/java/com/localcloud/emulators/workflows/engine/WorkflowExecutorTest.java
git commit -m "feat(workflows): add parallel shared variables support"
```

---

## Task 7: Production-parity limits

**Files:**
- Create: `localcloud-server/src/main/java/com/localcloud/emulators/workflows/engine/WorkflowLimits.java`
- Modify: `localcloud-server/src/main/java/com/localcloud/emulators/workflows/engine/WorkflowExecutor.java`
- Modify: `localcloud-server/src/main/java/com/localcloud/emulators/workflows/WorkflowsServiceImpl.java`
- Modify: `localcloud-server/src/main/java/com/localcloud/emulators/workflows/stdlib/HttpFunctions.java`
- Test: `localcloud-server/src/test/java/com/localcloud/emulators/workflows/engine/WorkflowExecutorTest.java`

**Step 1: Write the failing test**

Add to `WorkflowExecutorTest.java`:

```java
@Test
void testStepLimitExceeded() {
    // Build a workflow that loops more than 100,000 times
    String yaml = """
        main:
          steps:
            - init:
                assign:
                  - count: 0
            - loop:
                for:
                  value: n
                  range: [1, 100001]
                  steps:
                    - inc:
                        assign:
                          - count: ${count + 1}
        """;
    WorkflowException ex = assertThrows(WorkflowException.class, () -> runWorkflow(yaml));
    assertTrue(ex.getMessage().contains("step limit") || ex.getMessage().contains("100000"));
}
```

**Step 2: Run test to verify it fails**

Run: `cd localcloud-server && ./gradlew test --tests '*WorkflowExecutorTest.testStepLimitExceeded'`
Expected: FAIL — runs to completion (no limit enforced)

**Step 3: Write implementation**

Create `WorkflowLimits.java`:

```java
package com.localcloud.emulators.workflows.engine;

public final class WorkflowLimits {
    private WorkflowLimits() {}
    public static final int MAX_STEPS_PER_EXECUTION = 100_000;
    public static final int MAX_VARIABLE_MEMORY_BYTES = 512 * 1024;
    public static final int MAX_STRING_LENGTH = 256 * 1024;
    public static final int MAX_HTTP_RESPONSE_BYTES = 2 * 1024 * 1024;
    public static final int MAX_WORKFLOW_SOURCE_BYTES = 128 * 1024;
    public static final int MAX_EXECUTION_ARGUMENT_BYTES = 32 * 1024;
    public static final int MAX_CONTINUE_ALL_EXCEPTIONS = 100;
}
```

Add step counter to `ExecutionContext.java`:

```java
private int stepCount = 0;

public int incrementAndGetStepCount() {
    return ++stepCount;
}
```

In `WorkflowExecutor.java`, add to `executeStep()` at the top:

```java
int steps = context.incrementAndGetStepCount();
if (steps > WorkflowLimits.MAX_STEPS_PER_EXECUTION) {
    throw new WorkflowException("StepLimitExceeded",
        "Execution exceeded maximum step limit of " + WorkflowLimits.MAX_STEPS_PER_EXECUTION);
}
```

In `WorkflowsServiceImpl.createWorkflow()`, add source size check:

```java
if (sourceContents.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > WorkflowLimits.MAX_WORKFLOW_SOURCE_BYTES) {
    throw new IllegalArgumentException("Workflow source exceeds maximum size of 128 KB");
}
```

In `WorkflowsServiceImpl.createExecution()`, add argument size check:

```java
if (argument != null && argument.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > WorkflowLimits.MAX_EXECUTION_ARGUMENT_BYTES) {
    throw new IllegalArgumentException("Execution argument exceeds maximum size of 32 KB");
}
```

In `HttpFunctions.java`, after receiving response body, add size check:

```java
if (response.body().length() > WorkflowLimits.MAX_HTTP_RESPONSE_BYTES) {
    throw new RuntimeException("HttpError: Response body exceeds 2 MB limit");
}
```

Add `import com.localcloud.emulators.workflows.engine.WorkflowLimits;` where needed.

**Step 4: Run test to verify it passes**

Run: `cd localcloud-server && ./gradlew test --tests '*WorkflowExecutorTest.testStepLimit*'`
Expected: PASS

**Step 5: Commit**

```bash
git add localcloud-server/src/main/java/com/localcloud/emulators/workflows/engine/WorkflowLimits.java \
       localcloud-server/src/main/java/com/localcloud/emulators/workflows/engine/WorkflowExecutor.java \
       localcloud-server/src/main/java/com/localcloud/emulators/workflows/engine/ExecutionContext.java \
       localcloud-server/src/main/java/com/localcloud/emulators/workflows/WorkflowsServiceImpl.java \
       localcloud-server/src/main/java/com/localcloud/emulators/workflows/stdlib/HttpFunctions.java \
       localcloud-server/src/test/java/com/localcloud/emulators/workflows/engine/WorkflowExecutorTest.java
git commit -m "feat(workflows): enforce production-parity execution limits"
```

---

## Task 8: gRPC API binding

**Files:**
- Create: `localcloud-server/src/main/java/com/localcloud/emulators/workflows/WorkflowsGrpcServiceImpl.java`
- Create: `localcloud-server/src/main/java/com/localcloud/emulators/workflows/ExecutionsGrpcServiceImpl.java`
- Modify: `localcloud-server/src/main/java/com/localcloud/LocalCloudApplication.java:280-301`
- Test: `localcloud-server/src/test/java/com/localcloud/emulators/workflows/WorkflowsServiceImplTest.java` (add gRPC tests)

**Step 1: Write the failing test**

Add to `WorkflowsServiceImplTest.java`:

```java
@Test
void testGrpcServiceImplExists() {
    // Verify the gRPC service class exists and is instantiable
    assertDoesNotThrow(() -> {
        Class.forName("com.localcloud.emulators.workflows.WorkflowsGrpcServiceImpl");
    });
}
```

**Step 2: Run test to verify it fails**

Run: `cd localcloud-server && ./gradlew test --tests '*WorkflowsServiceImplTest.testGrpcServiceImplExists'`
Expected: FAIL — `ClassNotFoundException`

**Step 3: Write implementation**

Create `WorkflowsGrpcServiceImpl.java`:

```java
package com.localcloud.emulators.workflows;

import com.google.cloud.workflows.v1.*;
import com.google.longrunning.Operation;
import com.google.protobuf.Any;
import com.google.protobuf.Timestamp;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;

import java.sql.SQLException;
import java.time.Instant;
import java.util.Map;

public class WorkflowsGrpcServiceImpl extends WorkflowsGrpc.WorkflowsImplBase {
    private final WorkflowsServiceImpl service;

    public WorkflowsGrpcServiceImpl(WorkflowsServiceImpl service) {
        this.service = service;
    }

    @Override
    public void createWorkflow(CreateWorkflowRequest request, StreamObserver<Operation> responseObserver) {
        try {
            Workflow wf = request.getWorkflow();
            Map<String, Object> result = service.createWorkflow(
                extractProject(request.getParent()), extractLocation(request.getParent()),
                request.getWorkflowId(), wf.getSourceContents(), "{}", wf.getServiceAccount());
            Workflow built = buildWorkflowProto(result);
            Operation op = Operation.newBuilder()
                .setName(String.valueOf(result.get("name")))
                .setDone(true)
                .setResponse(Any.pack(built))
                .build();
            responseObserver.onNext(op);
            responseObserver.onCompleted();
        } catch (Exception e) {
            responseObserver.onError(Status.INTERNAL.withDescription(e.getMessage()).asException());
        }
    }

    @Override
    public void getWorkflow(GetWorkflowRequest request, StreamObserver<Workflow> responseObserver) {
        try {
            String name = request.getName();
            String[] parts = name.split("/");
            Map<String, Object> result = service.getWorkflow(parts[1], parts[3], parts[5]);
            if (result == null) {
                responseObserver.onError(Status.NOT_FOUND.withDescription("Workflow not found").asException());
                return;
            }
            responseObserver.onNext(buildWorkflowProto(result));
            responseObserver.onCompleted();
        } catch (Exception e) {
            responseObserver.onError(Status.INTERNAL.withDescription(e.getMessage()).asException());
        }
    }

    @Override
    public void deleteWorkflow(DeleteWorkflowRequest request, StreamObserver<Operation> responseObserver) {
        try {
            String name = request.getName();
            String[] parts = name.split("/");
            Map<String, Object> result = service.deleteWorkflow(parts[1], parts[3], parts[5]);
            Operation op = Operation.newBuilder()
                .setName(String.valueOf(result.get("name")))
                .setDone(true)
                .build();
            responseObserver.onNext(op);
            responseObserver.onCompleted();
        } catch (Exception e) {
            responseObserver.onError(Status.INTERNAL.withDescription(e.getMessage()).asException());
        }
    }

    @Override
    public void listWorkflows(ListWorkflowsRequest request, StreamObserver<ListWorkflowsResponse> responseObserver) {
        try {
            String[] parts = request.getParent().split("/");
            var workflows = service.listWorkflows(parts[1], parts[3], request.getPageSize() > 0 ? request.getPageSize() : 100);
            var builder = ListWorkflowsResponse.newBuilder();
            for (var wf : workflows) {
                builder.addWorkflows(buildWorkflowProto(wf));
            }
            responseObserver.onNext(builder.build());
            responseObserver.onCompleted();
        } catch (Exception e) {
            responseObserver.onError(Status.INTERNAL.withDescription(e.getMessage()).asException());
        }
    }

    private Workflow buildWorkflowProto(Map<String, Object> m) {
        Workflow.Builder b = Workflow.newBuilder()
            .setName(String.valueOf(m.getOrDefault("name", "")))
            .setState(Workflow.State.valueOf(String.valueOf(m.getOrDefault("state", "ACTIVE"))))
            .setRevisionId(String.valueOf(m.getOrDefault("revisionId", "1")));
        if (m.get("sourceContents") != null) b.setSourceContents(String.valueOf(m.get("sourceContents")));
        if (m.get("serviceAccount") != null) b.setServiceAccount(String.valueOf(m.get("serviceAccount")));
        return b.build();
    }

    private String extractProject(String parent) {
        String[] parts = parent.split("/");
        return parts.length > 1 ? parts[1] : "local-project";
    }

    private String extractLocation(String parent) {
        String[] parts = parent.split("/");
        return parts.length > 3 ? parts[3] : "us-central1";
    }
}
```

Create `ExecutionsGrpcServiceImpl.java`:

```java
package com.localcloud.emulators.workflows;

import com.google.cloud.workflows.executions.v1.*;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;

import java.util.Map;

public class ExecutionsGrpcServiceImpl extends ExecutionsGrpc.ExecutionsImplBase {
    private final WorkflowsServiceImpl service;

    public ExecutionsGrpcServiceImpl(WorkflowsServiceImpl service) {
        this.service = service;
    }

    @Override
    public void createExecution(CreateExecutionRequest request, StreamObserver<Execution> responseObserver) {
        try {
            String[] parts = request.getParent().split("/");
            String projectId = parts[1], locationId = parts[3], workflowId = parts[5];
            String argument = request.getExecution().getArgument();
            Map<String, Object> result = service.createExecution(projectId, locationId, workflowId, argument);
            responseObserver.onNext(buildExecutionProto(result));
            responseObserver.onCompleted();
        } catch (Exception e) {
            responseObserver.onError(Status.INTERNAL.withDescription(e.getMessage()).asException());
        }
    }

    @Override
    public void getExecution(GetExecutionRequest request, StreamObserver<Execution> responseObserver) {
        try {
            String[] parts = request.getName().split("/");
            String projectId = parts[1], locationId = parts[3], workflowId = parts[5], execId = parts[7];
            Map<String, Object> result = service.getExecution(projectId, locationId, workflowId, execId);
            if (result == null) {
                responseObserver.onError(Status.NOT_FOUND.withDescription("Execution not found").asException());
                return;
            }
            responseObserver.onNext(buildExecutionProto(result));
            responseObserver.onCompleted();
        } catch (Exception e) {
            responseObserver.onError(Status.INTERNAL.withDescription(e.getMessage()).asException());
        }
    }

    @Override
    public void cancelExecution(CancelExecutionRequest request, StreamObserver<Execution> responseObserver) {
        try {
            String[] parts = request.getName().split("/");
            String projectId = parts[1], locationId = parts[3], workflowId = parts[5], execId = parts[7];
            Map<String, Object> result = service.cancelExecution(projectId, locationId, workflowId, execId);
            responseObserver.onNext(buildExecutionProto(result));
            responseObserver.onCompleted();
        } catch (Exception e) {
            responseObserver.onError(Status.INTERNAL.withDescription(e.getMessage()).asException());
        }
    }

    @Override
    public void listExecutions(ListExecutionsRequest request, StreamObserver<ListExecutionsResponse> responseObserver) {
        try {
            String[] parts = request.getParent().split("/");
            String projectId = parts[1], locationId = parts[3], workflowId = parts[5];
            var executions = service.listExecutions(projectId, locationId, workflowId,
                request.getPageSize() > 0 ? request.getPageSize() : 100);
            var builder = ListExecutionsResponse.newBuilder();
            for (var exec : executions) {
                builder.addExecutions(buildExecutionProto(exec));
            }
            responseObserver.onNext(builder.build());
            responseObserver.onCompleted();
        } catch (Exception e) {
            responseObserver.onError(Status.INTERNAL.withDescription(e.getMessage()).asException());
        }
    }

    private Execution buildExecutionProto(Map<String, Object> m) {
        Execution.Builder b = Execution.newBuilder()
            .setName(String.valueOf(m.getOrDefault("name", "")))
            .setState(Execution.State.valueOf(String.valueOf(m.getOrDefault("state", "QUEUED"))));
        if (m.get("argument") != null) b.setArgument(String.valueOf(m.get("argument")));
        if (m.get("result") != null) b.setResult(String.valueOf(m.get("result")));
        return b.build();
    }
}
```

In `LocalCloudApplication.java`, replace line 283:

```java
// Before:
gateway.registerGrpcEmulator(workflowsEmulator, new io.grpc.BindableService[0]);

// After:
WorkflowsGrpcServiceImpl workflowsGrpc = new WorkflowsGrpcServiceImpl(workflowsEmulator.getWorkflowsService());
ExecutionsGrpcServiceImpl executionsGrpc = new ExecutionsGrpcServiceImpl(workflowsEmulator.getWorkflowsService());
grpcBuilder.addService(workflowsGrpc);
grpcBuilder.addService(executionsGrpc);
gateway.registerGrpcEmulator(workflowsEmulator, workflowsGrpc, executionsGrpc);
```

**Step 4: Run tests**

Run: `cd localcloud-server && ./gradlew test`
Expected: All tests PASS (compilation verifies gRPC bindings)

**Step 5: Commit**

```bash
git add localcloud-server/src/main/java/com/localcloud/emulators/workflows/WorkflowsGrpcServiceImpl.java \
       localcloud-server/src/main/java/com/localcloud/emulators/workflows/ExecutionsGrpcServiceImpl.java \
       localcloud-server/src/main/java/com/localcloud/LocalCloudApplication.java
git commit -m "feat(workflows): add gRPC API bindings for Workflows and Executions"
```

---

## Task 9: Hot-reload from filesystem

**Files:**
- Create: `localcloud-server/src/main/java/com/localcloud/emulators/workflows/WorkflowFileWatcher.java`
- Modify: `localcloud-server/src/main/java/com/localcloud/emulators/workflows/WorkflowsEmulator.java`
- Test: manual (filesystem watching is hard to unit-test reliably)

**Step 1: Write implementation**

Create `WorkflowFileWatcher.java`:

```java
package com.localcloud.emulators.workflows;

import com.localcloud.emulators.workflows.engine.WorkflowParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.*;
import java.sql.SQLException;

public class WorkflowFileWatcher implements Runnable {
    private static final Logger logger = LoggerFactory.getLogger(WorkflowFileWatcher.class);
    private final Path directory;
    private final WorkflowsStore store;
    private final String projectId;
    private final String locationId;
    private volatile boolean running = true;

    public WorkflowFileWatcher(Path directory, WorkflowsStore store, String projectId, String locationId) {
        this.directory = directory;
        this.store = store;
        this.projectId = projectId;
        this.locationId = locationId;
    }

    public void stop() { running = false; }

    @Override
    public void run() {
        // Initial scan — load all existing files
        try (var stream = Files.list(directory)) {
            stream.filter(this::isWorkflowFile).forEach(this::loadFile);
        } catch (IOException e) {
            logger.warn("Failed to scan workflows directory {}: {}", directory, e.getMessage());
        }

        // Watch for changes
        try (WatchService watcher = FileSystems.getDefault().newWatchService()) {
            directory.register(watcher, StandardWatchEventKinds.ENTRY_CREATE,
                StandardWatchEventKinds.ENTRY_MODIFY, StandardWatchEventKinds.ENTRY_DELETE);
            logger.info("Watching {} for workflow file changes", directory);

            while (running) {
                WatchKey key;
                try {
                    key = watcher.poll(2, java.util.concurrent.TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
                if (key == null) continue;

                for (WatchEvent<?> event : key.pollEvents()) {
                    Path changed = directory.resolve((Path) event.context());
                    if (!isWorkflowFile(changed)) continue;

                    if (event.kind() == StandardWatchEventKinds.ENTRY_DELETE) {
                        deleteFile(changed);
                    } else {
                        loadFile(changed);
                    }
                }
                key.reset();
            }
        } catch (IOException e) {
            logger.error("Workflow file watcher failed for {}: {}", directory, e.getMessage());
        }
    }

    private boolean isWorkflowFile(Path path) {
        String name = path.getFileName().toString().toLowerCase();
        return name.endsWith(".yaml") || name.endsWith(".yml") || name.endsWith(".json");
    }

    private void loadFile(Path path) {
        try {
            String source = Files.readString(path);
            WorkflowParser.parse(source); // Validate
            String workflowId = path.getFileName().toString().replaceFirst("\\.[^.]+$", "");
            store.upsertWorkflow(projectId, locationId, workflowId, source);
            logger.info("Loaded workflow '{}' from {}", workflowId, path.getFileName());
        } catch (Exception e) {
            logger.warn("Failed to load workflow from {}: {}", path.getFileName(), e.getMessage());
        }
    }

    private void deleteFile(Path path) {
        try {
            String workflowId = path.getFileName().toString().replaceFirst("\\.[^.]+$", "");
            store.deleteWorkflow(projectId, locationId, workflowId);
            logger.info("Deleted workflow '{}' (file removed: {})", workflowId, path.getFileName());
        } catch (Exception e) {
            logger.warn("Failed to delete workflow for {}: {}", path.getFileName(), e.getMessage());
        }
    }
}
```

**Step 2: Wire into WorkflowsEmulator**

Modify `WorkflowsEmulator.java`:

```java
package com.localcloud.emulators.workflows;

import com.localcloud.emulators.AbstractEmulator;
import com.localcloud.persistence.PostgresDataSource;

import java.nio.file.Files;
import java.nio.file.Path;

public class WorkflowsEmulator extends AbstractEmulator {
    private final WorkflowsStore store;
    private final WorkflowsServiceImpl workflowsService;
    private final ExecutionsServiceImpl executionsService;
    private WorkflowFileWatcher fileWatcher;

    public WorkflowsEmulator(PostgresDataSource dataSource) {
        super("workflows", "Cloud Workflows", 8080, "grpc", "WORKFLOWS_EMULATOR_HOST");
        this.store = new WorkflowsStore(dataSource);
        this.workflowsService = new WorkflowsServiceImpl(store);
        this.executionsService = new ExecutionsServiceImpl(store);
    }

    public WorkflowsServiceImpl getWorkflowsService() { return workflowsService; }
    public ExecutionsServiceImpl getExecutionsService() { return executionsService; }
    public WorkflowsStore getStore() { return store; }

    @Override protected void doStart() throws Exception {
        logger.info("Workflows emulator initialized");

        // Hot-reload: watch directory if LOCALCLOUD_WORKFLOWS_DIR is set
        String workflowsDir = System.getenv("LOCALCLOUD_WORKFLOWS_DIR");
        if (workflowsDir != null && !workflowsDir.isBlank()) {
            Path dir = Path.of(workflowsDir);
            if (Files.isDirectory(dir)) {
                String projectId = System.getenv().getOrDefault("LOCALCLOUD_PROJECT", "local-project");
                fileWatcher = new WorkflowFileWatcher(dir, store, projectId, "us-central1");
                Thread watchThread = new Thread(fileWatcher, "workflow-file-watcher");
                watchThread.setDaemon(true);
                watchThread.start();
                logger.info("Workflow hot-reload enabled for directory: {}", dir);
            } else {
                logger.warn("LOCALCLOUD_WORKFLOWS_DIR={} is not a valid directory, hot-reload disabled", workflowsDir);
            }
        }
    }

    @Override protected void doStop() {
        if (fileWatcher != null) fileWatcher.stop();
        workflowsService.shutdown();
    }

    @Override protected void doReset() {
        store.resetAll();
    }
}
```

**Step 3: Run full test suite**

Run: `cd localcloud-server && ./gradlew test`
Expected: All tests PASS

**Step 4: Commit**

```bash
git add localcloud-server/src/main/java/com/localcloud/emulators/workflows/WorkflowFileWatcher.java \
       localcloud-server/src/main/java/com/localcloud/emulators/workflows/WorkflowsEmulator.java
git commit -m "feat(workflows): add filesystem hot-reload for workflow definitions"
```

---

## Final Verification

After all 9 tasks:

```bash
# Full test suite
cd localcloud-server && ./gradlew test

# Build shadow JAR and Docker image
./gradlew shadowJar
cd .. && docker compose build

# Smoke test
docker compose up -d
curl http://localhost:8080/_localcloud/health
curl http://localhost:8080/_localcloud/services | jq '.[] | select(.name == "Cloud Workflows")'
```
