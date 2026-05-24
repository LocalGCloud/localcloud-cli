# Workflows Cancellation & Project-Awareness Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Make workflow cancellation interrupt blocking steps (sleep, callbacks, HTTP) end-to-end, route all cancel/execute paths through WorkflowsServiceImpl, and thread project context through console and mutate API.

**Architecture:** Store the executing Thread reference in ExecutionContext. On cancel, set state + interrupt thread. Blocking steps (sleep, callback future, HTTP) respond to interrupts or check isCancelled() post-call. MutateService delegates to service layer. Console uses api.mutate() with activeProject.

**Tech Stack:** Java 21 (virtual threads, CompletableFuture), Solid.js (console), JUnit 5 + Mockito (tests)

---

### Task 1: ExecutionContext — Add Thread Tracking and cancelAndInterrupt()

**Files:**
- Modify: `localcloud-server/src/main/java/com/localcloud/emulators/workflows/engine/ExecutionContext.java:14-20`
- Test: `localcloud-server/src/test/java/com/localcloud/emulators/workflows/engine/ExecutionContextTest.java` (create)

**Step 1: Write the failing test**

Create `localcloud-server/src/test/java/com/localcloud/emulators/workflows/engine/ExecutionContextTest.java`:

```java
package com.localcloud.emulators.workflows.engine;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ExecutionContextTest {

    @Test
    void cancelAndInterrupt_setsStateToCancelled() {
        ExecutionContext ctx = new ExecutionContext();
        ctx.setExecutingThread(Thread.currentThread());

        ctx.cancelAndInterrupt();

        assertTrue(ctx.isCancelled());
        assertEquals("CANCELLED", ctx.getState());
        // Clear interrupt flag so it doesn't affect other tests
        Thread.interrupted();
    }

    @Test
    void cancelAndInterrupt_interruptsThread() {
        ExecutionContext ctx = new ExecutionContext();
        ctx.setExecutingThread(Thread.currentThread());

        ctx.cancelAndInterrupt();

        assertTrue(Thread.interrupted(), "Thread should be interrupted");
    }

    @Test
    void cancelAndInterrupt_withNullThread_doesNotThrow() {
        ExecutionContext ctx = new ExecutionContext();
        // No thread set
        assertDoesNotThrow(() -> ctx.cancelAndInterrupt());
        assertTrue(ctx.isCancelled());
    }

    @Test
    void executingThread_getterAndSetter() {
        ExecutionContext ctx = new ExecutionContext();
        assertNull(ctx.getExecutingThread());
        ctx.setExecutingThread(Thread.currentThread());
        assertEquals(Thread.currentThread(), ctx.getExecutingThread());
    }
}
```

**Step 2: Run test to verify it fails**

Run: `cd localcloud-server && ./gradlew test --tests "*.ExecutionContextTest" --info`
Expected: FAIL — `setExecutingThread` and `cancelAndInterrupt` don't exist yet

**Step 3: Write minimal implementation**

In `ExecutionContext.java`, add after line 20 (`private ReentrantLock sharedLock;`):

```java
private volatile Thread executingThread;
```

Add after line 148 (`public boolean isCancelled() { ... }`):

```java
public Thread getExecutingThread() { return executingThread; }
public void setExecutingThread(Thread t) { this.executingThread = t; }

public void cancelAndInterrupt() {
    this.state = "CANCELLED";
    Thread t = this.executingThread;
    if (t != null) {
        t.interrupt();
    }
}
```

**Step 4: Run test to verify it passes**

Run: `cd localcloud-server && ./gradlew test --tests "*.ExecutionContextTest" --info`
Expected: PASS (all 4 tests)

**Step 5: Commit**

```bash
git add localcloud-server/src/main/java/com/localcloud/emulators/workflows/engine/ExecutionContext.java \
       localcloud-server/src/test/java/com/localcloud/emulators/workflows/engine/ExecutionContextTest.java
git commit -m "feat(workflows): add thread tracking and cancelAndInterrupt to ExecutionContext"
```

---

### Task 2: SysFunctions — Throw on InterruptedException Instead of Swallowing

**Files:**
- Modify: `localcloud-server/src/main/java/com/localcloud/emulators/workflows/stdlib/SysFunctions.java:50-56`
- Test: `localcloud-server/src/test/java/com/localcloud/emulators/workflows/stdlib/SysFunctionsTest.java` (create)

**Step 1: Write the failing test**

Create `localcloud-server/src/test/java/com/localcloud/emulators/workflows/stdlib/SysFunctionsTest.java`:

```java
package com.localcloud.emulators.workflows.stdlib;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SysFunctionsTest {

    private StdlibRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new StdlibRegistry();
        SysFunctions.register(registry);
    }

    @Test
    void sysSleep_interrupted_throwsRuntimeException() {
        Thread.currentThread().interrupt();

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> registry.get("sys.sleep").apply(List.of(10)));

        assertTrue(ex.getMessage().contains("Cancelled"),
                "Should mention cancellation, got: " + ex.getMessage());
        // Clear interrupt flag
        Thread.interrupted();
    }

    @Test
    void sysSleep_normal_completesWithoutError() {
        // Sleep 0.01 seconds — should complete quickly
        assertDoesNotThrow(() -> registry.get("sys.sleep").apply(List.of(0.01)));
    }
}
```

**Step 2: Run test to verify it fails**

Run: `cd localcloud-server && ./gradlew test --tests "*.SysFunctionsTest" --info`
Expected: `sysSleep_interrupted_throwsRuntimeException` FAILS — current code swallows InterruptedException

**Step 3: Write minimal implementation**

In `SysFunctions.java`, replace lines 50-56:

```java
        registry.register("sys.sleep", args -> {
            if (args.isEmpty()) throw new RuntimeException("sys.sleep requires seconds");
            double seconds = ((Number) args.get(0)).doubleValue();
            long millis = (long) (Math.min(seconds, 60) * 1000); // Cap at 60s in emulator
            try {
                Thread.sleep(millis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Cancelled: execution was cancelled during sleep");
            }
            return null;
        });
```

**Step 4: Run test to verify it passes**

Run: `cd localcloud-server && ./gradlew test --tests "*.SysFunctionsTest" --info`
Expected: PASS (both tests)

**Step 5: Commit**

```bash
git add localcloud-server/src/main/java/com/localcloud/emulators/workflows/stdlib/SysFunctions.java \
       localcloud-server/src/test/java/com/localcloud/emulators/workflows/stdlib/SysFunctionsTest.java
git commit -m "fix(workflows): sys.sleep throws on interrupt instead of swallowing"
```

---

### Task 3: CallbackManager — Add Execution Tracking and Cancellation

**Files:**
- Modify: `localcloud-server/src/main/java/com/localcloud/emulators/workflows/engine/CallbackManager.java`
- Test: `localcloud-server/src/test/java/com/localcloud/emulators/workflows/engine/CallbackManagerTest.java` (create)

**Step 1: Write the failing test**

Create `localcloud-server/src/test/java/com/localcloud/emulators/workflows/engine/CallbackManagerTest.java`:

```java
package com.localcloud.emulators.workflows.engine;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

class CallbackManagerTest {

    private CallbackManager manager;

    @BeforeEach
    void setUp() {
        manager = new CallbackManager();
    }

    @AfterEach
    void tearDown() {
        manager.shutdown();
    }

    @Test
    void createCallback_withExecutionId_tracksMapping() {
        String callbackId = manager.createCallback("exec-1");
        assertNotNull(callbackId);
        assertTrue(manager.isPending(callbackId));
    }

    @Test
    void cancelCallbacksForExecution_unblocksPendingWait() throws Exception {
        String callbackId = manager.createCallback("exec-cancel");

        // Start a thread that awaits the callback
        ExecutorService exec = Executors.newSingleThreadExecutor();
        Future<?> future = exec.submit(() -> {
            assertThrows(WorkflowException.class,
                    () -> manager.awaitCallback(callbackId, 30));
        });

        // Give thread time to start waiting
        Thread.sleep(100);

        // Cancel all callbacks for this execution
        manager.cancelCallbacksForExecution("exec-cancel");

        // Should unblock within 1 second
        assertDoesNotThrow(() -> future.get(2, TimeUnit.SECONDS));
        exec.shutdown();
    }

    @Test
    void cancelCallbacksForExecution_nonExistentExecution_doesNotThrow() {
        assertDoesNotThrow(() -> manager.cancelCallbacksForExecution("no-such-execution"));
    }

    @Test
    void createCallback_noExecutionId_backwardCompatible() {
        String callbackId = manager.createCallback();
        assertNotNull(callbackId);
        assertTrue(manager.isPending(callbackId));
    }

    @Test
    void getExecutionIdForCallback_returnsCorrectMapping() {
        String callbackId = manager.createCallback("exec-lookup");
        assertEquals("exec-lookup", manager.getExecutionIdForCallback(callbackId));
    }

    @Test
    void getExecutionIdForCallback_noExecutionId_returnsNull() {
        String callbackId = manager.createCallback();
        assertNull(manager.getExecutionIdForCallback(callbackId));
    }
}
```

**Step 2: Run test to verify it fails**

Run: `cd localcloud-server && ./gradlew test --tests "*.CallbackManagerTest" --info`
Expected: FAIL — `createCallback(String)`, `cancelCallbacksForExecution`, `getExecutionIdForCallback` don't exist

**Step 3: Write minimal implementation**

In `CallbackManager.java`, add after line 17 (`private final ConcurrentHashMap<String, CompletableFuture<Object>> pendingCallbacks`):

```java
    private final ConcurrentHashMap<String, String> callbackToExecution = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Set<String>> executionToCallbacks = new ConcurrentHashMap<>();
```

Add new overload after the existing `createCallback()` method (after line 41):

```java
    /**
     * Create a callback tracked to a specific execution.
     */
    public String createCallback(String executionId) {
        String callbackId = createCallback();
        if (executionId != null) {
            callbackToExecution.put(callbackId, executionId);
            executionToCallbacks.computeIfAbsent(executionId, k -> ConcurrentHashMap.newKeySet()).add(callbackId);
        }
        return callbackId;
    }

    /**
     * Cancel all pending callbacks for an execution (used when execution is cancelled).
     */
    public void cancelCallbacksForExecution(String executionId) {
        Set<String> callbackIds = executionToCallbacks.remove(executionId);
        if (callbackIds == null) return;
        for (String callbackId : callbackIds) {
            CompletableFuture<Object> future = pendingCallbacks.remove(callbackId);
            if (future != null && !future.isDone()) {
                future.completeExceptionally(new WorkflowException("Cancelled", "Execution was cancelled"));
            }
            callbackToExecution.remove(callbackId);
        }
    }

    /**
     * Look up which execution owns a callback.
     */
    public String getExecutionIdForCallback(String callbackId) {
        return callbackToExecution.get(callbackId);
    }
```

Also update `awaitCallback` cleanup (in the existing method, after `pendingCallbacks.remove(callbackId)` on lines 58, 61, 64) to also clean up tracking maps. Add a private helper:

```java
    private void cleanupCallback(String callbackId) {
        pendingCallbacks.remove(callbackId);
        String execId = callbackToExecution.remove(callbackId);
        if (execId != null) {
            Set<String> set = executionToCallbacks.get(execId);
            if (set != null) {
                set.remove(callbackId);
                if (set.isEmpty()) executionToCallbacks.remove(execId);
            }
        }
    }
```

Replace `pendingCallbacks.remove(callbackId)` calls in `awaitCallback()` (lines 58, 61, 64) with `cleanupCallback(callbackId)`.

Also add `import java.util.Set;` to imports.

**Step 4: Run test to verify it passes**

Run: `cd localcloud-server && ./gradlew test --tests "*.CallbackManagerTest" --info`
Expected: PASS (all 6 tests)

**Step 5: Commit**

```bash
git add localcloud-server/src/main/java/com/localcloud/emulators/workflows/engine/CallbackManager.java \
       localcloud-server/src/test/java/com/localcloud/emulators/workflows/engine/CallbackManagerTest.java
git commit -m "feat(workflows): add execution-tracked callbacks with cancellation support"
```

---

### Task 4: ConnectorRegistry — Add ThreadLocal Cancellation Check

**Files:**
- Modify: `localcloud-server/src/main/java/com/localcloud/emulators/workflows/connector/ConnectorRegistry.java:19-24,134-155`

**Step 1: Write the failing test**

Add to a new test file `localcloud-server/src/test/java/com/localcloud/emulators/workflows/connector/ConnectorRegistryTest.java`:

```java
package com.localcloud.emulators.workflows.connector;

import com.localcloud.emulators.workflows.engine.ExecutionContext;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ConnectorRegistryTest {

    @Test
    void currentContext_setAndGet_roundTrips() {
        ExecutionContext ctx = new ExecutionContext();
        ConnectorRegistry.setCurrentContext(ctx);
        assertSame(ctx, ConnectorRegistry.getCurrentContext());
        ConnectorRegistry.clearCurrentContext();
        assertNull(ConnectorRegistry.getCurrentContext());
    }

    @Test
    void currentContext_defaultIsNull() {
        ConnectorRegistry.clearCurrentContext();
        assertNull(ConnectorRegistry.getCurrentContext());
    }
}
```

**Step 2: Run test to verify it fails**

Run: `cd localcloud-server && ./gradlew test --tests "*.ConnectorRegistryTest" --info`
Expected: FAIL — `setCurrentContext`, `getCurrentContext`, `clearCurrentContext` don't exist

**Step 3: Write minimal implementation**

In `ConnectorRegistry.java`, add after line 26 (`private java.util.function.BiFunction<...> childWorkflowRunner;`):

```java
    private static final ThreadLocal<com.localcloud.emulators.workflows.engine.ExecutionContext> currentContext = new ThreadLocal<>();

    public static void setCurrentContext(com.localcloud.emulators.workflows.engine.ExecutionContext ctx) {
        currentContext.set(ctx);
    }

    public static com.localcloud.emulators.workflows.engine.ExecutionContext getCurrentContext() {
        return currentContext.get();
    }

    public static void clearCurrentContext() {
        currentContext.remove();
    }
```

In the `execute()` method, after `HttpResponse<String> response = httpClient.send(...)` (line 134), add a cancellation check:

```java
            HttpResponse<String> response = httpClient.send(reqBuilder.build(), HttpResponse.BodyHandlers.ofString());

            // Check if execution was cancelled while HTTP call was in flight
            var ctx = currentContext.get();
            if (ctx != null && ctx.isCancelled()) {
                throw new RuntimeException("Cancelled: execution was cancelled during connector call");
            }
```

**Step 4: Run test to verify it passes**

Run: `cd localcloud-server && ./gradlew test --tests "*.ConnectorRegistryTest" --info`
Expected: PASS (both tests)

**Step 5: Commit**

```bash
git add localcloud-server/src/main/java/com/localcloud/emulators/workflows/connector/ConnectorRegistry.java \
       localcloud-server/src/test/java/com/localcloud/emulators/workflows/connector/ConnectorRegistryTest.java
git commit -m "feat(workflows): add ThreadLocal context and post-call cancellation check to ConnectorRegistry"
```

---

### Task 5: WorkflowsServiceImpl — Wire Thread Tracking and Cancellation Propagation

**Files:**
- Modify: `localcloud-server/src/main/java/com/localcloud/emulators/workflows/WorkflowsServiceImpl.java:189-209,213-295`
- Modify: `localcloud-server/src/test/java/com/localcloud/emulators/workflows/WorkflowsServiceImplTest.java`

**Step 1: Write the failing test**

Add to `WorkflowsServiceImplTest.java` after the existing cancel tests (after line 551):

```java
    @Test
    void cancelExecution_activeExecution_usesCancelAndInterrupt() throws SQLException {
        // First call returns ACTIVE, second call (after cancel) returns CANCELLED
        when(store.getExecution(PROJECT, LOCATION, WF_ID, "exec-interrupt"))
                .thenReturn(executionRow("exec-interrupt", "ACTIVE"))
                .thenReturn(executionRow("exec-interrupt", "CANCELLED"));

        Map<String, Object> result = service.cancelExecution(PROJECT, LOCATION, WF_ID, "exec-interrupt");

        assertEquals("CANCELLED", result.get("state"));
        verify(store).updateExecutionState("exec-interrupt", "CANCELLED", null, null);
    }
```

**Step 2: Run test to verify it passes (this test already works with current code)**

Run: `cd localcloud-server && ./gradlew test --tests "*.WorkflowsServiceImplTest.cancelExecution_activeExecution_usesCancelAndInterrupt" --info`
Expected: PASS (the interface-level behavior is the same)

**Step 3: Write implementation — wire cancelAndInterrupt and thread tracking**

In `WorkflowsServiceImpl.java`:

**a) cancelExecution() (line 204):** Replace `ctx.setState("CANCELLED")` with `ctx.cancelAndInterrupt()`:

```java
    // Propagate cancellation to running execution context
    ExecutionContext ctx = activeExecutions.get(executionId);
    if (ctx != null) {
        ctx.cancelAndInterrupt();
    }
```

**b) runExecution() (line 252-253):** Add thread tracking after creating ExecutionContext:

```java
            ExecutionContext context = new ExecutionContext(initialVars);
            context.setExecutingThread(Thread.currentThread());
            activeExecutions.put(executionId, context);
```

**c) runExecution() — set ConnectorRegistry ThreadLocal (line 256-264):** Add before connector registration loop:

```java
            // Set execution context for connector cancellation checks
            ConnectorRegistry.setCurrentContext(context);
```

Add import at top: `import com.localcloud.emulators.workflows.connector.ConnectorRegistry;`

**d) runExecution() finally block (line 291-294):** Clear thread ref and connector context:

```java
        } finally {
            activeExecutions.remove(executionId);
            ConnectorRegistry.clearCurrentContext();
            SysFunctions.clearWorkflowEnvVars();
        }
```

**e) runExecution() — handle cancellation as distinct from failure.** After the executor.execute() call (line 268), add a check before setting SUCCEEDED:

```java
            // Execute
            WorkflowExecutor executor = new WorkflowExecutor(definition, context, stdlib);
            Object result = executor.execute();

            // If cancelled during execution, don't overwrite with SUCCEEDED
            if (context.isCancelled()) {
                logger.info("Workflow execution {} was cancelled", executionId);
                return;
            }
```

**Step 4: Run all existing tests to verify nothing broke**

Run: `cd localcloud-server && ./gradlew test --tests "*.WorkflowsServiceImplTest" --info`
Expected: PASS (all existing + new test)

**Step 5: Commit**

```bash
git add localcloud-server/src/main/java/com/localcloud/emulators/workflows/WorkflowsServiceImpl.java \
       localcloud-server/src/test/java/com/localcloud/emulators/workflows/WorkflowsServiceImplTest.java
git commit -m "feat(workflows): wire thread tracking and cancelAndInterrupt in execution lifecycle"
```

---

### Task 6: WorkflowExecutor — Set ConnectorRegistry Context for Parallel Branches

**Files:**
- Modify: `localcloud-server/src/main/java/com/localcloud/emulators/workflows/engine/WorkflowExecutor.java:344,350-358,403`

**Step 1: Identify the gap**

WorkflowsServiceImpl sets `ConnectorRegistry.setCurrentContext(context)` on the main execution thread. But parallel branches (`executeParallel`) spawn new virtual threads that won't inherit the ThreadLocal. Each parallel child thread must also set the context.

**Step 2: Write the fix**

In `WorkflowExecutor.java`, in `executeParallel()`, inside both parallel-for and parallel-branches thread submissions, set the context ThreadLocal:

In the parallel-for loop (around line 350), wrap the executor submit body:

```java
                    futures.add(executor.submit(() -> {
                        ConnectorRegistry.setCurrentContext(context);
                        try {
                            if (finalSharedLock != null) {
                                finalSharedLock.lock();
                                try { childExecutor.executeSteps(finalBodySteps); } finally { finalSharedLock.unlock(); }
                            } else {
                                childExecutor.executeSteps(finalBodySteps);
                            }
                        } finally {
                            ConnectorRegistry.clearCurrentContext();
                            semaphore.release();
                        }
                    }));
```

Same pattern for the parallel-branches block (around line 403):

```java
                    futures.add(executor.submit(() -> {
                        ConnectorRegistry.setCurrentContext(context);
                        try {
                            if (finalSharedLock2 != null) {
                                finalSharedLock2.lock();
                                try { childExecutor.executeSteps(branchSteps); } finally { finalSharedLock2.unlock(); }
                            } else {
                                childExecutor.executeSteps(branchSteps);
                            }
                        } finally {
                            ConnectorRegistry.clearCurrentContext();
                        }
                    }));
```

Add import: `import com.localcloud.emulators.workflows.connector.ConnectorRegistry;`

**Step 3: Run all workflow executor tests**

Run: `cd localcloud-server && ./gradlew test --tests "*.WorkflowExecutorTest" --info`
Expected: PASS (no behavioral change, just threading context propagation)

**Step 4: Also add the retry sleep cancellation awareness**

In `executeTry()` (line 467), the retry delay also uses `Thread.sleep`. Make it cancellation-aware:

```java
                    double delay = Math.min(initialDelay * Math.pow(multiplier, attempt - 1), maxDelay);
                    try {
                        Thread.sleep((long) (delay * 1000));
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new WorkflowException("Cancelled", "Execution was cancelled during retry delay");
                    }
```

**Step 5: Commit**

```bash
git add localcloud-server/src/main/java/com/localcloud/emulators/workflows/engine/WorkflowExecutor.java
git commit -m "fix(workflows): propagate execution context to parallel threads, handle cancel in retry"
```

---

### Task 7: WorkflowsStore — Add getExecutionById()

**Files:**
- Modify: `localcloud-server/src/main/java/com/localcloud/emulators/workflows/WorkflowsStore.java`
- Modify: `localcloud-server/src/test/java/com/localcloud/emulators/workflows/WorkflowsStoreTest.java`

**Step 1: Write the failing test**

Add to `WorkflowsStoreTest.java` (check existing test patterns first — this file likely uses mock or H2):

```java
    @Test
    void getExecutionById_delegatesToQuery() throws SQLException {
        // getExecutionById looks up by execution_id only, returning full row
        // This test verifies the method exists and returns expected shape
        Map<String, Object> result = store.getExecutionById("nonexistent-id");
        assertNull(result);
    }
```

**Step 2: Run test to verify it fails**

Run: `cd localcloud-server && ./gradlew test --tests "*.WorkflowsStoreTest.getExecutionById*" --info`
Expected: FAIL — method doesn't exist

**Step 3: Write minimal implementation**

In `WorkflowsStore.java`, add after `getProjectIdForExecution()` (after line 179):

```java
    public Map<String, Object> getExecutionById(String executionId) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                "SELECT * FROM workflow_executions WHERE execution_id = ?")) {
            ps.setString(1, executionId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rowToMap(rs);
                return null;
            }
        }
    }
```

**Step 4: Run test to verify it passes**

Run: `cd localcloud-server && ./gradlew test --tests "*.WorkflowsStoreTest" --info`
Expected: PASS

**Step 5: Commit**

```bash
git add localcloud-server/src/main/java/com/localcloud/emulators/workflows/WorkflowsStore.java \
       localcloud-server/src/test/java/com/localcloud/emulators/workflows/WorkflowsStoreTest.java
git commit -m "feat(workflows): add getExecutionById for cancel-by-execution-id lookup"
```

---

### Task 8: MutateService — Route Cancel Through Service, Project-Aware

**Files:**
- Modify: `localcloud-server/src/main/java/com/localcloud/admin/MutateService.java:974-1047`

**Step 1: Identify the changes**

Two changes in `mutateWorkflows()`:

a) Line 975: Read project from request body with fallback
b) Lines 1028-1044: Replace direct SQL with `workflowsService.cancelExecution()`

**Step 2: Write the implementation**

**a) Project-aware (line 975):**

```java
    private String mutateWorkflows(String operation, String subOp, Map<String, Object> body) throws Exception {
        String projectId = body.containsKey("project_id")
                ? (String) body.get("project_id")
                : config.getProjectId();
        String locationId = (String) body.getOrDefault("location", "us-central1");
```

**b) Cancel routing (replace lines 1028-1044):**

```java
        // POST /mutate/workflows/cancel — cancel an execution
        if ("cancel".equals(operation)) {
            String executionId = (String) body.get("execution_id");
            if (executionId == null) return mapper.writeValueAsString(Map.of("error", true, "message", "execution_id is required"));

            if (workflowsService == null) {
                return mapper.writeValueAsString(Map.of("error", true, "message", "Workflows service not initialized"));
            }

            try {
                // Look up execution to find workflowId
                Map<String, Object> execRow = workflowsService.getStore().getExecutionById(executionId);
                if (execRow == null) {
                    return mapper.writeValueAsString(Map.of("error", true, "message", "Execution not found: " + executionId));
                }
                String workflowId = (String) execRow.get("workflow_id");

                Map<String, Object> result = workflowsService.cancelExecution(projectId, locationId, workflowId, executionId);
                return mapper.writeValueAsString(Map.of("status", "cancelled", "execution_id", executionId));
            } catch (IllegalStateException e) {
                return mapper.writeValueAsString(Map.of("error", true, "message", e.getMessage()));
            } catch (IllegalArgumentException e) {
                return mapper.writeValueAsString(Map.of("error", true, "message", e.getMessage()));
            }
        }
```

**Step 3: Run all tests**

Run: `cd localcloud-server && ./gradlew test --info`
Expected: PASS

**Step 4: Commit**

```bash
git add localcloud-server/src/main/java/com/localcloud/admin/MutateService.java
git commit -m "fix(workflows): route mutate cancel through WorkflowsServiceImpl, read project from body"
```

---

### Task 9: WorkflowsCallbackService — Check Execution State Before Delivery

**Files:**
- Modify: `localcloud-server/src/main/java/com/localcloud/emulators/workflows/WorkflowsCallbackService.java:26-85`

**Step 1: Write the implementation**

The CallbackManager now tracks callback→execution mappings. WorkflowsCallbackService can check if the owning execution is cancelled before delivering.

In `WorkflowsCallbackService.java`, update constructor and deliverCallback:

```java
public class WorkflowsCallbackService {

    private static final Logger logger = LoggerFactory.getLogger(WorkflowsCallbackService.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    private final CallbackManager callbackManager;

    public WorkflowsCallbackService(CallbackManager callbackManager) {
        this.callbackManager = callbackManager;
    }

    @Post("/callbacks/{callbackId}")
    public HttpResponse deliverCallback(@Param("callbackId") String callbackId,
                                        AggregatedHttpRequest request) {
        try {
            // Check if the callback's execution has been cancelled
            String executionId = callbackManager.getExecutionIdForCallback(callbackId);
            if (executionId != null && !callbackManager.isPending(callbackId)) {
                String json = mapper.writeValueAsString(Map.of(
                        "error", true,
                        "message", "Callback expired or execution was cancelled: " + callbackId));
                return HttpResponse.of(HttpStatus.GONE, MediaType.JSON, json);
            }

            // Parse request body as generic JSON payload
            Object payload = null;
            String body = request.contentUtf8();
            // ... rest stays the same
```

Actually, simpler approach — just let the existing flow handle it. If execution was cancelled, `cancelCallbacksForExecution` already removed the future from `pendingCallbacks`, so `deliverCallback` returns false → 404. The 404 vs 410 distinction is nice-to-have but not critical.

**Revised approach:** Keep it simple. The cancellation already removes callbacks from the map. The existing 404 response is sufficient. No code change needed here beyond what CallbackManager already handles.

**Step 2: Skip — no change needed**

The existing flow already handles this:
1. Execution cancelled → `cancelCallbacksForExecution()` removes futures
2. Late callback delivery → `deliverCallback()` returns false → 404

**Step 3: Commit (skip — no changes)**

---

### Task 10: Workflows.jsx — Use api.mutate(), Add Cancel Button, Pass Project

**Files:**
- Modify: `localcloud-console/src/pages/Workflows.jsx:635-653,656-716`

**Step 1: Replace raw fetch with api.mutate (lines 635-653)**

```jsx
    const handleCreateExecution = async () => {
        setCreating(true);
        try {
            const p = typeof props.activeProject === 'function' ? props.activeProject() : props.activeProject;
            await api.mutate('workflows', 'execute', {
                workflow_id: selectedWorkflow(),
                argument: execArgument(),
                project_id: p
            });
            setShowCreateExec(false);
            setExecArgument('{}');
            setActiveTab('executions');
            const execs = await api.browse('workflows/' + selectedWorkflow() + '/executions');
            setExecutions(Array.isArray(execs) ? execs : (execs.executions || []));
        } catch (err) {
            setError('Failed to create execution: ' + err.message);
        } finally {
            setCreating(false);
        }
    };
```

**Step 2: Add cancel handler after handleCreateExecution**

```jsx
    const handleCancelExecution = async (executionId) => {
        try {
            const p = typeof props.activeProject === 'function' ? props.activeProject() : props.activeProject;
            await api.mutate('workflows', 'cancel', {
                execution_id: executionId,
                project_id: p
            });
            // Refresh executions list
            const execs = await api.browse('workflows/' + selectedWorkflow() + '/executions');
            setExecutions(Array.isArray(execs) ? execs : (execs.executions || []));
            // Refresh selected execution if viewing
            if (selectedExecution() && (selectedExecution().execution_id === executionId || selectedExecution().name?.endsWith(executionId))) {
                const updated = (Array.isArray(execs) ? execs : (execs.executions || [])).find(e =>
                    e.execution_id === executionId || e.name?.endsWith(executionId));
                if (updated) setSelectedExecution(updated);
            }
        } catch (err) {
            setError('Failed to cancel execution: ' + err.message);
        }
    };
```

**Step 3: Add cancel button in execution detail view (after StateBadge, around line 670)**

Replace the header div (lines 668-671):

```jsx
                <div style={{ display: 'flex', 'align-items': 'center', gap: '12px', 'margin-bottom': '20px' }}>
                    <h2 style={{ margin: 0, 'font-size': '18px' }}>Execution {execId}</h2>
                    <StateBadge state={exec.state} />
                    <Show when={exec.state === 'ACTIVE' || exec.state === 'QUEUED'}>
                        <button
                            onClick={() => handleCancelExecution(exec.execution_id || exec.name?.split('/').pop())}
                            style={{
                                padding: '4px 12px',
                                border: '1px solid var(--error, #d93025)',
                                'border-radius': '4px',
                                background: 'transparent',
                                color: 'var(--error, #d93025)',
                                cursor: 'pointer',
                                'font-size': '12px',
                                'font-weight': '500'
                            }}
                        >
                            Cancel
                        </button>
                    </Show>
                </div>
```

**Step 4: Build console**

Run: `cd localcloud-console && npm run build`
Expected: Build succeeds with no errors

**Step 5: Commit**

```bash
git add localcloud-console/src/pages/Workflows.jsx
git commit -m "feat(console): use api.mutate for workflow execution, add cancel button"
```

---

### Task 11: EventsFunctions — Pass Execution ID to Callback Creation

**Files:**
- Modify: `localcloud-server/src/main/java/com/localcloud/emulators/workflows/stdlib/EventsFunctions.java:14-21`
- Modify: `localcloud-server/src/main/java/com/localcloud/emulators/workflows/WorkflowsServiceImpl.java:54-55`

**Step 1: Update EventsFunctions to pass executionId from context**

The EventsFunctions.register needs access to the execution context to pass executionId to createCallback. Simplest approach: use a ThreadLocal (same pattern as SysFunctions.workflowEnvVars).

In `EventsFunctions.java`, add a ThreadLocal for executionId:

```java
public class EventsFunctions {

    private static final ThreadLocal<String> currentExecutionId = new ThreadLocal<>();

    public static void setCurrentExecutionId(String executionId) {
        currentExecutionId.set(executionId);
    }

    public static void clearCurrentExecutionId() {
        currentExecutionId.remove();
    }

    public static void register(StdlibRegistry registry, CallbackManager callbackManager, String callbackBaseUrl) {
        registry.register("events.create_callback_endpoint", args -> {
            if (callbackManager == null) throw new RuntimeException("Callback manager not initialized");
            String execId = currentExecutionId.get();
            String callbackId = (execId != null) ? callbackManager.createCallback(execId) : callbackManager.createCallback();
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("url", callbackBaseUrl + "/" + callbackId);
            return result;
        });
        // ... rest unchanged
```

**Step 2: Set executionId in WorkflowsServiceImpl.runExecution()**

In `runExecution()`, after setting SysFunctions env vars (around line 229), add:

```java
            EventsFunctions.setCurrentExecutionId(executionId);
```

In the finally block, add:

```java
            EventsFunctions.clearCurrentExecutionId();
```

Also in `cancelExecution()`, after calling `ctx.cancelAndInterrupt()`, cancel callbacks:

```java
        ExecutionContext ctx = activeExecutions.get(executionId);
        if (ctx != null) {
            ctx.cancelAndInterrupt();
        }
        // Cancel any pending callbacks for this execution
        callbackManager.cancelCallbacksForExecution(executionId);
```

**Step 3: Run all tests**

Run: `cd localcloud-server && ./gradlew test --info`
Expected: PASS

**Step 4: Commit**

```bash
git add localcloud-server/src/main/java/com/localcloud/emulators/workflows/stdlib/EventsFunctions.java \
       localcloud-server/src/main/java/com/localcloud/emulators/workflows/WorkflowsServiceImpl.java
git commit -m "feat(workflows): track executionId in callbacks, cancel pending callbacks on execution cancel"
```

---

### Task 12: Full Build and Integration Verification

**Step 1: Run full Java test suite**

Run: `cd localcloud-server && ./gradlew test --info`
Expected: All 187+ tests pass (plus new tests)

**Step 2: Build console**

Run: `cd localcloud-console && npm run build`
Expected: Build succeeds

**Step 3: Build shadow JAR and Docker image**

Run: `cd localcloud-server && ./gradlew shadowJar`
Expected: JAR builds successfully

**Step 4: Final commit if any fixups needed**

---

## Task Summary

| Task | Description | Files | Tests |
|------|-------------|-------|-------|
| 1 | ExecutionContext thread tracking | ExecutionContext.java | ExecutionContextTest.java (4 tests) |
| 2 | sys.sleep throws on interrupt | SysFunctions.java | SysFunctionsTest.java (2 tests) |
| 3 | CallbackManager execution tracking | CallbackManager.java | CallbackManagerTest.java (6 tests) |
| 4 | ConnectorRegistry ThreadLocal check | ConnectorRegistry.java | ConnectorRegistryTest.java (2 tests) |
| 5 | WorkflowsServiceImpl wiring | WorkflowsServiceImpl.java | WorkflowsServiceImplTest.java (1 test) |
| 6 | WorkflowExecutor parallel + retry | WorkflowExecutor.java | existing tests |
| 7 | WorkflowsStore getExecutionById | WorkflowsStore.java | WorkflowsStoreTest.java (1 test) |
| 8 | MutateService cancel routing | MutateService.java | existing tests |
| 9 | WorkflowsCallbackService (skip) | — | — |
| 10 | Workflows.jsx console UI | Workflows.jsx | manual |
| 11 | EventsFunctions execution tracking | EventsFunctions.java, WorkflowsServiceImpl.java | existing tests |
| 12 | Full build verification | all | all |
