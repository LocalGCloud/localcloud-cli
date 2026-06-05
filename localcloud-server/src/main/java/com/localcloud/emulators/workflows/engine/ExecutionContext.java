package com.localcloud.emulators.workflows.engine;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Manages variable scopes (stack-based for subworkflows) and execution state.
 * Thread-safe for use with parallel step execution.
 */
public class ExecutionContext {
    private final Deque<Map<String, Object>> scopeStack;
    private final List<Map<String, Object>> stepHistory;
    private volatile String state = "ACTIVE";
    private volatile int callDepth = 0;
    private static final int MAX_CALL_DEPTH = 20;
    private final Deque<String> stepChain = new ArrayDeque<>();
    private String currentRoutine = "main";
    private int stepCount = 0;
    private Map<String, Object> sharedVars;
    private java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.locks.ReentrantLock> sharedLocks;
    private volatile Thread executingThread;
    private volatile ExecutionContext parentContext;

    public ExecutionContext() {
        this.scopeStack = new ArrayDeque<>();
        this.scopeStack.push(Collections.synchronizedMap(new LinkedHashMap<>()));
        this.stepHistory = new CopyOnWriteArrayList<>();
    }

    public ExecutionContext(Map<String, Object> initialVars) {
        this.scopeStack = new ArrayDeque<>();
        Map<String, Object> scope = Collections.synchronizedMap(new LinkedHashMap<>());
        if (initialVars != null) scope.putAll(initialVars);
        this.scopeStack.push(scope);
        this.stepHistory = new CopyOnWriteArrayList<>();
    }

    // Private constructor for child contexts
    private ExecutionContext(Map<String, Object> parentVars, List<Map<String, Object>> sharedStepHistory,
                             String state, int callDepth, Deque<String> parentStepChain,
                             ExecutionContext parent) {
        this.scopeStack = new ArrayDeque<>();
        Map<String, Object> scope = Collections.synchronizedMap(new LinkedHashMap<>());
        if (parentVars != null) scope.putAll(parentVars);
        this.scopeStack.push(scope);
        this.stepHistory = sharedStepHistory; // Share step history across parallel tasks
        this.state = state;
        this.callDepth = callDepth;
        this.stepChain.addAll(parentStepChain);
        this.parentContext = parent;
        this.currentRoutine = parent.currentRoutine; // Propagate routine for correct error traces
    }

    /**
     * Create an isolated child context for parallel execution.
     * Gets a snapshot of current variables but its own scope stack.
     * Shares stepHistory (thread-safe CopyOnWriteArrayList) and state with parent.
     */
    public ExecutionContext createChildContext(Map<String, Object> additionalVars) {
        Map<String, Object> snapshot = getAllVariables();
        if (additionalVars != null) snapshot.putAll(additionalVars);
        return new ExecutionContext(snapshot, this.stepHistory, this.state, this.callDepth, this.stepChain, this);
    }

    /**
     * Create a child context that shares specified variables with other children via a shared map.
     * Uses per-variable locking so independent shared variables don't block each other.
     */
    public ExecutionContext createChildContextWithShared(Map<String, Object> additionalVars,
                                                         Map<String, Object> sharedVars,
                                                         java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.locks.ReentrantLock> sharedLocks) {
        ExecutionContext child = createChildContext(additionalVars);
        child.sharedVars = sharedVars;
        child.sharedLocks = sharedLocks;
        return child;
    }

    // --- Variable management ---

    public synchronized void setVariable(String name, Object value) {
        if (sharedVars != null && sharedVars.containsKey(name)) {
            java.util.concurrent.locks.ReentrantLock lock = getOrCreateSharedLock(name);
            lock.lock();
            try { sharedVars.put(name, value); } finally { lock.unlock(); }
            return;
        }
        scopeStack.peek().put(name, value);
    }

    public synchronized Object getVariable(String name) {
        if (sharedVars != null && sharedVars.containsKey(name)) {
            java.util.concurrent.locks.ReentrantLock lock = getOrCreateSharedLock(name);
            lock.lock();
            try { return sharedVars.get(name); } finally { lock.unlock(); }
        }
        for (Map<String, Object> scope : scopeStack) {
            if (scope.containsKey(name)) return scope.get(name);
        }
        return null;
    }

    public synchronized boolean hasVariable(String name) {
        if (sharedVars != null && sharedVars.containsKey(name)) {
            return true;
        }
        for (Map<String, Object> scope : scopeStack) {
            if (scope.containsKey(name)) return true;
        }
        return false;
    }

    public synchronized Map<String, Object> getAllVariables() {
        Map<String, Object> merged = new LinkedHashMap<>();
        List<Map<String, Object>> scopes = new ArrayList<>(scopeStack);
        Collections.reverse(scopes);
        for (Map<String, Object> scope : scopes) {
            merged.putAll(scope);
        }
        if (sharedVars != null) {
            // Snapshot shared vars with per-variable locking
            for (String name : sharedVars.keySet()) {
                java.util.concurrent.locks.ReentrantLock lock = getOrCreateSharedLock(name);
                lock.lock();
                try { merged.put(name, sharedVars.get(name)); } finally { lock.unlock(); }
            }
        }
        return merged;
    }

    private java.util.concurrent.locks.ReentrantLock getOrCreateSharedLock(String name) {
        return sharedLocks.computeIfAbsent(name, k -> new java.util.concurrent.locks.ReentrantLock());
    }

    // --- Scope management (for subworkflows) ---

    public synchronized void pushScope(Map<String, Object> params) {
        if (callDepth >= MAX_CALL_DEPTH) {
            throw new WorkflowException("Maximum call depth (" + MAX_CALL_DEPTH + ") exceeded");
        }
        callDepth++;
        Map<String, Object> scope = Collections.synchronizedMap(new LinkedHashMap<>());
        if (params != null) scope.putAll(params);
        scopeStack.push(scope);
    }

    public synchronized void popScope() {
        if (scopeStack.size() > 1) {
            scopeStack.pop();
            callDepth--;
        }
    }

    public int getCallDepth() { return callDepth; }

    public int incrementAndGetStepCount() {
        return ++stepCount;
    }

    // --- Execution state ---

    public String getState() { return state; }
    public void setState(String state) { this.state = state; }
    public boolean isCancelled() {
        if ("CANCELLED".equals(state)) return true;
        return parentContext != null && parentContext.isCancelled();
    }

    public Thread getExecutingThread() { return executingThread; }
    public void setExecutingThread(Thread t) { this.executingThread = t; }

    public void cancelAndInterrupt() {
        this.state = "CANCELLED";
        Thread t = this.executingThread;
        if (t != null) {
            t.interrupt();
        }
    }

    // --- Step history (thread-safe via CopyOnWriteArrayList) ---

    public void recordStep(String stepName, String stepType, long durationMs) {
        recordStep(stepName, stepType, durationMs, "SUCCEEDED", null);
    }

    public void recordStep(String stepName, String stepType, long durationMs, String state, Object error) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("step", stepName);
        entry.put("type", stepType);
        entry.put("state", state);
        entry.put("duration_ms", durationMs);
        entry.put("timestamp", System.currentTimeMillis());
        if (error != null) entry.put("error", error);
        // Include snapshot of current variables for debugging (limited to top-level keys)
        Map<String, Object> allVars = getAllVariables();
        if (!allVars.isEmpty()) {
            Map<String, Object> debug = new LinkedHashMap<>();
            for (Map.Entry<String, Object> var : allVars.entrySet()) {
                Object value = var.getValue();
                if (value instanceof String || value instanceof Number || value instanceof Boolean || value == null) {
                    debug.put(var.getKey(), value);
                } else if (value instanceof List<?> l) {
                    debug.put(var.getKey(), "[List size=" + l.size() + "]");
                } else if (value instanceof Map<?, ?> m) {
                    debug.put(var.getKey(), "[Map size=" + m.size() + "]");
                } else {
                    debug.put(var.getKey(), String.valueOf(value));
                }
            }
            entry.put("variables", debug);
        }
        stepHistory.add(entry);
    }

    public List<Map<String, Object>> getStepHistory() {
        return Collections.unmodifiableList(stepHistory);
    }

    public synchronized void pushStepChain(String entry) {
        stepChain.push(entry);
    }

    public synchronized void popStepChain() {
        if (!stepChain.isEmpty()) stepChain.pop();
    }

    public synchronized List<String> getStepChain() {
        List<String> result = new ArrayList<>(stepChain);
        Collections.reverse(result);
        return result;
    }

    public synchronized List<Map<String, String>> getStructuredStepChain() {
        List<String> reversed = new ArrayList<>(stepChain);
        Collections.reverse(reversed);
        // The routine for each step is inferred: steps in the main body use "main",
        // but since we don't track per-step routine, we return the current chain
        // with context from the call stack.
        List<Map<String, String>> result = new ArrayList<>();
        for (String step : reversed) {
            Map<String, String> entry = new java.util.LinkedHashMap<>();
            entry.put("step", step);
            entry.put("routine", currentRoutine);
            result.add(entry);
        }
        return result;
    }

    public void setCurrentRoutine(String routine) { this.currentRoutine = routine; }
    public String getCurrentRoutine() { return currentRoutine; }
}

