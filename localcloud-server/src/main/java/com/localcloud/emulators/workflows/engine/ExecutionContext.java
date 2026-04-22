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
    private Map<String, Object> sharedVars;
    private ReentrantLock sharedLock;

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
                             String state, int callDepth, Deque<String> parentStepChain) {
        this.scopeStack = new ArrayDeque<>();
        Map<String, Object> scope = Collections.synchronizedMap(new LinkedHashMap<>());
        if (parentVars != null) scope.putAll(parentVars);
        this.scopeStack.push(scope);
        this.stepHistory = sharedStepHistory; // Share step history across parallel tasks
        this.state = state;
        this.callDepth = callDepth;
        this.stepChain.addAll(parentStepChain);
    }

    /**
     * Create an isolated child context for parallel execution.
     * Gets a snapshot of current variables but its own scope stack.
     * Shares stepHistory (thread-safe CopyOnWriteArrayList) and state with parent.
     */
    public ExecutionContext createChildContext(Map<String, Object> additionalVars) {
        Map<String, Object> snapshot = getAllVariables();
        if (additionalVars != null) snapshot.putAll(additionalVars);
        return new ExecutionContext(snapshot, this.stepHistory, this.state, this.callDepth, this.stepChain);
    }

    /**
     * Create a child context that shares specified variables with other children via a shared map.
     * Reads and writes to shared variables are synchronized via the provided lock.
     */
    public ExecutionContext createChildContextWithShared(Map<String, Object> additionalVars,
                                                         Map<String, Object> sharedVars,
                                                         ReentrantLock sharedLock) {
        ExecutionContext child = createChildContext(additionalVars);
        child.sharedVars = sharedVars;
        child.sharedLock = sharedLock;
        return child;
    }

    // --- Variable management ---

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
            sharedLock.lock();
            try { merged.putAll(sharedVars); } finally { sharedLock.unlock(); }
        }
        return merged;
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

    // --- Execution state ---

    public String getState() { return state; }
    public void setState(String state) { this.state = state; }
    public boolean isCancelled() { return "CANCELLED".equals(state); }

    // --- Step history (thread-safe via CopyOnWriteArrayList) ---

    public void recordStep(String stepName, String stepType, long durationMs) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("step", stepName);
        entry.put("type", stepType);
        entry.put("duration_ms", durationMs);
        entry.put("timestamp", System.currentTimeMillis());
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
}
