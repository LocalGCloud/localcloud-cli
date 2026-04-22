package com.localcloud.emulators.workflows.engine;

import com.localcloud.emulators.workflows.expression.ExpressionEvaluator;
import com.localcloud.emulators.workflows.stdlib.StdlibRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.Function;

/**
 * Executes a parsed workflow definition step by step.
 */
public class WorkflowExecutor {
    private static final Logger logger = LoggerFactory.getLogger(WorkflowExecutor.class);

    private final WorkflowDefinition definition;
    private final ExecutionContext context;
    private final StdlibRegistry stdlib;

    public WorkflowExecutor(WorkflowDefinition definition, ExecutionContext context, StdlibRegistry stdlib) {
        this.definition = definition;
        this.context = context;
        this.stdlib = stdlib;
    }

    /**
     * Execute the main workflow entry point.
     * @return the workflow result (from a return step, or null)
     */
    public Object execute() {
        try {
            return executeSubworkflow("main", Collections.emptyMap());
        } catch (ReturnException e) {
            return e.getValue();
        } catch (WorkflowException e) {
            throw e;
        } catch (Exception e) {
            throw new WorkflowException("Workflow execution failed: " + e.getMessage(), e);
        }
    }

    /**
     * Execute a named subworkflow with the given parameters.
     */
    public Object executeSubworkflow(String name, Map<String, Object> params) {
        WorkflowDefinition.SubworkflowDef sub = definition.getSubworkflow(name);
        if (sub == null) {
            throw new WorkflowException("NotFound", "Subworkflow not found: " + name);
        }

        boolean isMain = "main".equals(name);
        if (!isMain) {
            context.pushScope(params);
        } else if (params != null && !params.isEmpty()) {
            // For main, set params as variables in current scope
            for (var entry : params.entrySet()) {
                context.setVariable(entry.getKey(), entry.getValue());
            }
        }

        try {
            return executeSteps(sub.getSteps());
        } catch (ReturnException e) {
            return e.getValue();
        } finally {
            if (!isMain) {
                context.popScope();
            }
        }
    }

    /**
     * Execute a list of steps sequentially. Handles 'next' jumps.
     * Package-private to allow child executors (used in parallel execution) to call this.
     */
    Object executeSteps(List<WorkflowDefinition.StepDef> steps) {
        int i = 0;
        while (i < steps.size()) {
            if (context.isCancelled()) {
                throw new WorkflowException("Cancelled", "Execution was cancelled");
            }

            WorkflowDefinition.StepDef step = steps.get(i);
            long startTime = System.currentTimeMillis();

            try {
                executeStep(step);
                context.recordStep(step.getName(), step.getType(), System.currentTimeMillis() - startTime);
                i++;
            } catch (NextStepException e) {
                context.recordStep(step.getName(), step.getType(), System.currentTimeMillis() - startTime);
                // Find target step
                int targetIdx = -1;
                for (int j = 0; j < steps.size(); j++) {
                    if (steps.get(j).getName().equals(e.getTargetStep())) {
                        targetIdx = j;
                        break;
                    }
                }
                if (targetIdx < 0) {
                    throw new WorkflowException("NotFound", "Step not found: " + e.getTargetStep());
                }
                i = targetIdx;
            } catch (ReturnException e) {
                context.recordStep(step.getName(), step.getType(), System.currentTimeMillis() - startTime);
                throw e;
            }
        }
        return null;
    }

    /**
     * Execute a single step based on its type.
     */
    private void executeStep(WorkflowDefinition.StepDef step) {
        int steps = context.incrementAndGetStepCount();
        if (steps > WorkflowLimits.MAX_STEPS_PER_EXECUTION) {
            throw new WorkflowException("StepLimitExceeded",
                "Execution exceeded maximum step limit of " + WorkflowLimits.MAX_STEPS_PER_EXECUTION);
        }
        context.pushStepChain(step.getName());
        try {
            switch (step.getType()) {
                case "assign" -> executeAssign(step);
                case "call" -> executeCall(step);
                case "switch" -> executeSwitch(step);
                case "for" -> executeFor(step);
                case "parallel" -> executeParallel(step);
                case "try" -> executeTry(step);
                case "raise" -> executeRaise(step);
                case "return" -> executeReturn(step);
                case "next" -> executeNext(step);
                case "steps" -> {
                    Object stepsObj = step.get("steps");
                    if (stepsObj instanceof List<?> list) {
                        executeSteps(parseInlineSteps(list));
                    }
                }
                default -> logger.warn("Unknown step type: {} in step {}", step.getType(), step.getName());
            }
        } catch (WorkflowException e) {
            if (e.getWorkflowStackTrace() == null) {
                e.setWorkflowStackTrace(context.getStepChain());
            }
            throw e;
        } finally {
            context.popStepChain();
        }
    }

    // --- Step executors ---

    @SuppressWarnings("unchecked")
    private void executeAssign(WorkflowDefinition.StepDef step) {
        Object assignObj = step.get("assign");
        if (assignObj instanceof List<?> assignments) {
            if (assignments.size() > 50) {
                throw new WorkflowException("TooManyAssignments", "Maximum 50 assignments per step");
            }
            for (Object item : assignments) {
                if (item instanceof Map<?, ?> map) {
                    for (Map.Entry<?, ?> entry : map.entrySet()) {
                        String varName = String.valueOf(entry.getKey());
                        Object value = evaluateValue(entry.getValue());
                        context.setVariable(varName, value);
                    }
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void executeCall(WorkflowDefinition.StepDef step) {
        String target = String.valueOf(step.get("call"));
        Map<String, Object> args = step.get("args") instanceof Map ? (Map<String, Object>) step.get("args") : Collections.emptyMap();
        String resultVar = step.get("result") != null ? String.valueOf(step.get("result")) : null;

        // Evaluate args
        Map<String, Object> evaluatedArgs = new LinkedHashMap<>();
        for (var entry : args.entrySet()) {
            evaluatedArgs.put(entry.getKey(), evaluateValue(entry.getValue()));
        }

        Object result;

        // Check if it's a stdlib/http function
        if (stdlib.has(target)) {
            Function<List<Object>, Object> fn = stdlib.get(target);
            // For call steps, stdlib functions receive the args map as a single argument
            result = fn.apply(List.of((Object) evaluatedArgs));
        }
        // Check if it's a subworkflow
        else if (definition.hasSubworkflow(target)) {
            result = executeSubworkflow(target, evaluatedArgs);
        }
        // Unknown target
        else {
            throw new WorkflowException("NotFound", "Unknown call target: " + target);
        }

        if (resultVar != null) {
            context.setVariable(resultVar, result);
        }
    }

    @SuppressWarnings("unchecked")
    private void executeSwitch(WorkflowDefinition.StepDef step) {
        Object switchObj = step.get("switch");
        if (switchObj instanceof List<?> branches) {
            for (Object branch : branches) {
                if (branch instanceof Map<?, ?> branchMap) {
                    Object condition = branchMap.get("condition");
                    if (condition == null) {
                        // Default branch (no condition)
                        executeBranchAction((Map<String, Object>) branchMap);
                        return;
                    }
                    Object condResult = evaluateValue(condition);
                    if (isTruthy(condResult)) {
                        executeBranchAction((Map<String, Object>) branchMap);
                        return;
                    }
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void executeBranchAction(Map<String, Object> branch) {
        if (branch.containsKey("return")) {
            Object val = evaluateValue(branch.get("return"));
            throw new ReturnException(val);
        }
        if (branch.containsKey("next")) {
            throw new NextStepException(String.valueOf(branch.get("next")));
        }
        if (branch.containsKey("steps")) {
            Object stepsObj = branch.get("steps");
            if (stepsObj instanceof List<?> list) {
                executeSteps(parseInlineSteps(list));
            }
        }
        if (branch.containsKey("assign")) {
            WorkflowDefinition.StepDef assignStep = new WorkflowDefinition.StepDef("switch_assign", "assign",
                    Map.of("assign", branch.get("assign")));
            executeAssign(assignStep);
        }
    }

    @SuppressWarnings("unchecked")
    private void executeFor(WorkflowDefinition.StepDef step) {
        Map<String, Object> forConfig = (Map<String, Object>) step.get("for");
        String valueVar = String.valueOf(forConfig.get("value"));
        String indexVar = forConfig.containsKey("index") ? String.valueOf(forConfig.get("index")) : null;

        List<?> items;
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

    @SuppressWarnings("unchecked")
    private void executeParallel(WorkflowDefinition.StepDef step) {
        Map<String, Object> parallelConfig = (Map<String, Object>) step.get("parallel");
        int concurrencyLimit = 5;
        if (parallelConfig.containsKey("concurrency_limit")) {
            concurrencyLimit = ((Number) parallelConfig.get("concurrency_limit")).intValue();
        }
        concurrencyLimit = Math.min(concurrencyLimit, 10);

        // Parse shared variable names
        List<String> sharedVarNames = parallelConfig.containsKey("shared")
            ? ((List<?>) parallelConfig.get("shared")).stream().map(String::valueOf).toList()
            : Collections.emptyList();

        Map<String, Object> sharedVars = null;
        java.util.concurrent.locks.ReentrantLock sharedLock = null;
        if (!sharedVarNames.isEmpty()) {
            sharedVars = new java.util.concurrent.ConcurrentHashMap<>();
            sharedLock = new java.util.concurrent.locks.ReentrantLock();
            for (String name : sharedVarNames) {
                Object val = context.getVariable(name);
                if (val != null) sharedVars.put(name, val);
                else sharedVars.put(name, 0); // default to 0 for unset shared vars
            }
        }

        // Parallel for loop
        if (parallelConfig.containsKey("for")) {
            Map<String, Object> forConfig = (Map<String, Object>) parallelConfig.get("for");
            String valueVar = String.valueOf(forConfig.get("value"));
            Object inObj = evaluateValue(forConfig.get("in"));
            List<?> items = inObj instanceof List<?> list ? list : List.of();

            List<WorkflowDefinition.StepDef> bodySteps = Collections.emptyList();
            if (forConfig.get("steps") instanceof List<?> stepsList) {
                bodySteps = parseInlineSteps(stepsList);
            }

            ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
            try {
                List<Future<?>> futures = new ArrayList<>();
                Semaphore semaphore = new Semaphore(concurrencyLimit);
                final List<WorkflowDefinition.StepDef> finalBodySteps = bodySteps;

                final var finalSharedLock = sharedLock;
                for (Object item : items) {
                    try { semaphore.acquire(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
                    // Each parallel task gets its own child context (with shared vars if declared)
                    ExecutionContext childCtx = sharedVars != null
                        ? context.createChildContextWithShared(Map.of(valueVar, item), sharedVars, sharedLock)
                        : context.createChildContext(Map.of(valueVar, item));
                    WorkflowExecutor childExecutor = new WorkflowExecutor(definition, childCtx, stdlib);
                    futures.add(executor.submit(() -> {
                        try {
                            if (finalSharedLock != null) {
                                // Serialize execution of steps that access shared variables
                                // to prevent read-modify-write races on shared state
                                finalSharedLock.lock();
                                try { childExecutor.executeSteps(finalBodySteps); } finally { finalSharedLock.unlock(); }
                            } else {
                                childExecutor.executeSteps(finalBodySteps);
                            }
                        } finally {
                            semaphore.release();
                        }
                    }));
                }

                // Wait for all
                for (Future<?> f : futures) {
                    try { f.get(); } catch (ExecutionException e) {
                        if (e.getCause() instanceof WorkflowException we) throw we;
                        if (e.getCause() instanceof ReturnException re) throw re;
                        throw new WorkflowException("ParallelError", e.getCause().getMessage());
                    } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                }

                // Merge shared vars back to parent context
                if (sharedVars != null) {
                    for (Map.Entry<String, Object> entry : sharedVars.entrySet()) {
                        context.setVariable(entry.getKey(), entry.getValue());
                    }
                }
            } finally {
                executor.shutdown();
            }
        }
        // Parallel branches
        else if (parallelConfig.containsKey("branches")) {
            List<?> branches = (List<?>) parallelConfig.get("branches");
            ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
            try {
                List<Future<?>> futures = new ArrayList<>();

                final var finalSharedLock2 = sharedLock;
                for (Object branch : branches) {
                    if (branch instanceof Map<?, ?> branchMap) {
                        Object stepsObj = ((Map<?, ?>) branchMap).values().iterator().next();
                        if (stepsObj instanceof Map<?, ?> branchBody && branchBody.containsKey("steps")) {
                            List<WorkflowDefinition.StepDef> branchSteps = parseInlineSteps((List<?>) branchBody.get("steps"));
                            // Each branch gets its own child context (with shared vars if declared)
                            ExecutionContext childCtx = sharedVars != null
                                ? context.createChildContextWithShared(Map.of(), sharedVars, sharedLock)
                                : context.createChildContext(Map.of());
                            WorkflowExecutor childExecutor = new WorkflowExecutor(definition, childCtx, stdlib);
                            futures.add(executor.submit(() -> {
                                if (finalSharedLock2 != null) {
                                    finalSharedLock2.lock();
                                    try { childExecutor.executeSteps(branchSteps); } finally { finalSharedLock2.unlock(); }
                                } else {
                                    childExecutor.executeSteps(branchSteps);
                                }
                            }));
                        }
                    }
                }

                for (Future<?> f : futures) {
                    try { f.get(); } catch (ExecutionException e) {
                        if (e.getCause() instanceof WorkflowException we) throw we;
                        throw new WorkflowException("ParallelError", e.getCause().getMessage());
                    } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                }

                // Merge shared vars back to parent context
                if (sharedVars != null) {
                    for (Map.Entry<String, Object> entry : sharedVars.entrySet()) {
                        context.setVariable(entry.getKey(), entry.getValue());
                    }
                }
            } finally {
                executor.shutdown();
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void executeTry(WorkflowDefinition.StepDef step) {
        Object tryObj = step.get("try");
        Object retryObj = step.get("retry");
        Object exceptObj = step.get("except");

        List<WorkflowDefinition.StepDef> trySteps = Collections.emptyList();
        if (tryObj instanceof Map<?, ?> tryMap && tryMap.containsKey("steps")) {
            trySteps = parseInlineSteps((List<?>) tryMap.get("steps"));
        }

        // Parse retry config
        int maxRetries = 0;
        double initialDelay = 1.0, maxDelay = 60.0, multiplier = 2.0;
        if (retryObj instanceof Map<?, ?> retryMap) {
            if (retryMap.containsKey("max_retries")) maxRetries = ((Number) retryMap.get("max_retries")).intValue();
            Map<String, Object> backoff = retryMap.get("backoff") instanceof Map ? (Map<String, Object>) retryMap.get("backoff") : Collections.emptyMap();
            if (backoff.containsKey("initial_delay")) initialDelay = ((Number) backoff.get("initial_delay")).doubleValue();
            if (backoff.containsKey("max_delay")) maxDelay = ((Number) backoff.get("max_delay")).doubleValue();
            if (backoff.containsKey("multiplier")) multiplier = ((Number) backoff.get("multiplier")).doubleValue();
        }

        int attempt = 0;
        while (true) {
            try {
                executeSteps(trySteps);
                return; // Success
            } catch (ReturnException e) {
                throw e; // Don't catch returns
            } catch (RuntimeException e) {
                attempt++;
                if (attempt <= maxRetries) {
                    double delay = Math.min(initialDelay * Math.pow(multiplier, attempt - 1), maxDelay);
                    try { Thread.sleep((long) (delay * 1000)); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                    logger.debug("Retry attempt {} for step {} after {}s", attempt, step.getName(), delay);
                    continue;
                }

                // No more retries — run except block
                if (exceptObj instanceof Map<?, ?> exceptMap) {
                    String asVar = exceptMap.containsKey("as") ? String.valueOf(exceptMap.get("as")) : "e";
                    Map<String, Object> errorMap;
                    if (e instanceof WorkflowException we) {
                        errorMap = we.toErrorMap();
                    } else {
                        errorMap = new LinkedHashMap<>();
                        errorMap.put("code", "RuntimeError");
                        errorMap.put("message", e.getMessage());
                    }
                    context.setVariable(asVar, errorMap);

                    if (exceptMap.containsKey("steps") && exceptMap.get("steps") instanceof List<?> exceptStepsList) {
                        executeSteps(parseInlineSteps(exceptStepsList));
                    }
                    return;
                }
                // No except block — re-throw
                throw e;
            }
        }
    }

    private void executeRaise(WorkflowDefinition.StepDef step) {
        Object raiseObj = evaluateValue(step.get("raise"));
        if (raiseObj instanceof String msg) {
            throw new WorkflowException(msg);
        }
        if (raiseObj instanceof Map<?, ?> map) {
            String code = map.containsKey("code") ? String.valueOf(map.get("code")) : "RuntimeError";
            String message = map.containsKey("message") ? String.valueOf(map.get("message")) : "Unknown error";
            throw new WorkflowException(code, message);
        }
        throw new WorkflowException(String.valueOf(raiseObj));
    }

    private void executeReturn(WorkflowDefinition.StepDef step) {
        Object value = evaluateValue(step.get("return"));
        throw new ReturnException(value);
    }

    private void executeNext(WorkflowDefinition.StepDef step) {
        String target = String.valueOf(step.get("next"));
        throw new NextStepException(target);
    }

    // --- Helpers ---

    /**
     * Evaluate a value that may be a ${} expression string, a literal, or a nested structure.
     */
    @SuppressWarnings("unchecked")
    private Object evaluateValue(Object value) {
        if (value == null) return null;
        if (value instanceof String s) {
            if (s.contains("${")) {
                ExpressionEvaluator eval = createEvaluator();
                return eval.evaluateTemplate(s);
            }
            return s;
        }
        if (value instanceof Number || value instanceof Boolean) return value;
        if (value instanceof List<?> list) {
            List<Object> result = new ArrayList<>();
            for (Object item : list) result.add(evaluateValue(item));
            return result;
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                result.put(String.valueOf(entry.getKey()), evaluateValue(entry.getValue()));
            }
            return result;
        }
        return value;
    }

    private ExpressionEvaluator createEvaluator() {
        return new ExpressionEvaluator(context.getAllVariables(), stdlib.getAll());
    }

    private boolean isTruthy(Object val) {
        if (val instanceof Boolean b) return b;
        if (val == null) return false;
        if (val instanceof Number n) return n.doubleValue() != 0;
        if (val instanceof String s) return !s.isEmpty();
        return true;
    }

    @SuppressWarnings("unchecked")
    private List<WorkflowDefinition.StepDef> parseInlineSteps(List<?> stepsList) {
        List<WorkflowDefinition.StepDef> steps = new ArrayList<>();
        for (Object item : stepsList) {
            if (item instanceof Map<?, ?> stepMap) {
                for (Map.Entry<?, ?> entry : stepMap.entrySet()) {
                    String stepName = String.valueOf(entry.getKey());
                    if (entry.getValue() instanceof Map<?, ?> bodyMap) {
                        Map<String, Object> config = (Map<String, Object>) bodyMap;
                        String type = WorkflowParser.detectStepType(config);
                        steps.add(new WorkflowDefinition.StepDef(stepName, type, config));
                    }
                }
            }
        }
        return steps;
    }
}
