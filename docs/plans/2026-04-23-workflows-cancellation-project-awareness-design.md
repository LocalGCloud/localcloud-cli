# Workflows Cancellation & Project-Awareness Completion

**Date:** 2026-04-23
**Status:** Approved
**Context:** Findings 2 & 3 from workflows gap closure review — cancellation doesn't wake blocking steps, mutate cancel bypasses service layer, project threading incomplete.

## Problem

1. Cancellation marks ExecutionContext as CANCELLED but doesn't interrupt blocking work (sys.sleep, events.await_callback, connector HTTP calls). Execution lingers after cancel.
2. MutateService cancel path uses direct SQL, bypassing WorkflowsServiceImpl.cancelExecution() — no ExecutionContext propagation.
3. MutateService uses config.getProjectId() instead of request-provided project. Console uses raw fetch instead of project-aware API helper.

## Design

### 1. Cancellation Propagation

**ExecutionContext** — add thread tracking:
- `private volatile Thread executingThread`
- `setExecutingThread(Thread)` / `getExecutingThread()`
- `cancelAndInterrupt()` — sets state CANCELLED + calls `executingThread.interrupt()` if non-null

**WorkflowsServiceImpl.cancelExecution():**
- Replace `ctx.setState("CANCELLED")` with `ctx.cancelAndInterrupt()`

**WorkflowsServiceImpl.runExecution():**
- Set `ctx.setExecutingThread(Thread.currentThread())` before execution
- Clear in finally block + remove from activeExecutions

**WorkflowExecutor.executeStep():**
- Add `if (ctx.isCancelled()) throw WorkflowException("Cancelled", ...)` at top before any work

### 2. Blocking Step Cancellation

**sys.sleep (SysFunctions.java:54):**
- On InterruptedException: throw RuntimeException("Cancelled: execution was cancelled during sleep") instead of silently re-interrupting

**events.await_callback (CallbackManager.java):**
- InterruptedException handler already throws WorkflowException("Cancelled") — works as-is
- Add `cancelCallbacksForExecution(executionId)` — completes futures exceptionally
- Add `executionCallbacks` map: executionId → Set<callbackId> for tracking
- `createCallback()` accepts optional executionId param

**ConnectorRegistry.execute():**
- After httpClient.send() returns, check cancellation via ThreadLocal<ExecutionContext>
- Add static ThreadLocal set by WorkflowExecutor before connector calls
- If cancelled, throw WorkflowException("Cancelled") instead of returning result

### 3. MutateService Cancel Routing

**Cancel path (line 1028):**
- Replace direct SQL UPDATE with `workflowsService.cancelExecution(projectId, locationId, workflowId, executionId)`
- Add `WorkflowsStore.getExecutionById(executionId)` to look up workflowId from execution_id (no API change needed)

### 4. Project-Aware Console Execution

**MutateService.java (line 975):**
- Read projectId from `body.getOrDefault("project_id", config.getProjectId())`

**Workflows.jsx (line 638):**
- Replace raw fetch with `api.mutate('workflows', 'execute', { workflow_id, argument, project_id: activeProject() })`
- Add cancel button to execution detail view for ACTIVE/QUEUED states
- Cancel handler: `api.mutate('workflows', 'cancel', { execution_id, workflow_id, project_id: activeProject() })`

### 5. Callback Contract Tightening

**WorkflowsCallbackService.java:**
- On delivery, check if execution still active via executionId→state lookup
- If cancelled, return 410 Gone with message "Execution was cancelled"
- Requires callback→execution mapping (from Section 2's executionCallbacks map, inverted)

### 6. ConnectorRegistry Post-Call Check

- After HTTP response, check ThreadLocal<ExecutionContext>.isCancelled()
- Throw before returning result to caller

## Files Changed

| File | Change |
|------|--------|
| ExecutionContext.java | Add executingThread field, cancelAndInterrupt() |
| WorkflowsServiceImpl.java | Store thread ref, use cancelAndInterrupt(), cancel callbacks |
| WorkflowExecutor.java | Add isCancelled() gate in executeStep(), set ThreadLocal |
| SysFunctions.java | Throw on InterruptedException instead of swallow |
| CallbackManager.java | Add execution tracking, cancelCallbacksForExecution() |
| ConnectorRegistry.java | Add ThreadLocal<ExecutionContext> check after HTTP call |
| MutateService.java | Route cancel through service, read project from body |
| WorkflowsStore.java | Add getExecutionById() |
| WorkflowsCallbackService.java | Check execution state before delivery |
| Workflows.jsx | Use api.mutate(), add cancel button, pass project_id |

## Testing

| Test | Verifies |
|------|----------|
| Cancel active execution → CANCELLED state | Basic cancel |
| Cancel during sys.sleep → unblocks < 1s | Thread interrupt |
| Cancel during await_callback → unblocks immediately | Future cancellation |
| Cancel during connector call → exits after call returns | Post-call check |
| Cancel via mutate API → same behavior | Routing |
| Cancel terminal execution → error | Idempotency |
| Execute with non-default project → correct in DB | Project threading |
| Cancel with non-default project → works | Project cancel |
| Deliver callback to cancelled execution → 410 | Contract |

## Non-Goals

- Async HttpClient refactor for ConnectorRegistry (overkill for emulator)
- Cancel propagation to child workflow executions (future work)
- Callback timeout configuration per-workflow (use 30min default)
