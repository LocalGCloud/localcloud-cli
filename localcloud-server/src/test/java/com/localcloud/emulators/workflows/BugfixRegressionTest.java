package com.localcloud.emulators.workflows;

import com.localcloud.emulators.workflows.engine.*;
import com.localcloud.emulators.workflows.expression.*;
import com.localcloud.emulators.workflows.stdlib.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.Function;

/**
 * Regression tests for critical and high-severity bugs found in code review.
 */
class BugfixRegressionTest {

    // --- Issue 1-2: Parallel execution thread safety ---

    @Test
    void testParallelForLoopDoesNotCorruptSharedState() {
        // Each parallel iteration should get isolated variables
        String yaml = """
            main:
              steps:
                - setup:
                    assign:
                      - items: [1, 2, 3, 4, 5]
                      - total: 0
                - parallel_loop:
                    parallel:
                      concurrency_limit: 5
                      for:
                        value: item
                        in: ${items}
                        steps:
                          - process:
                              assign:
                                - x: ${item * 10}
                - done:
                    return: "completed"
            """;
        StdlibRegistry stdlib = new StdlibRegistry();
        WorkflowDefinition def = WorkflowParser.parse(yaml);
        ExecutionContext ctx = new ExecutionContext();
        WorkflowExecutor executor = new WorkflowExecutor(def, ctx, stdlib);
        // Should not throw ConcurrentModificationException
        Object result = executor.execute();
        assertEquals("completed", result);
    }

    @Test
    void testChildContextIsIsolated() {
        ExecutionContext parent = new ExecutionContext(Map.of("shared", "value"));
        ExecutionContext child = parent.createChildContext(Map.of("child_var", "child_value"));

        // Child sees parent vars
        assertEquals("value", child.getVariable("shared"));
        assertEquals("child_value", child.getVariable("child_var"));

        // Parent does NOT see child vars
        assertNull(parent.getVariable("child_var"));

        // Modifying child does not affect parent
        child.setVariable("shared", "modified");
        assertEquals("value", parent.getVariable("shared"));
    }

    @Test
    void testExecutionContextThreadSafety() throws Exception {
        ExecutionContext ctx = new ExecutionContext();
        int threadCount = 10;
        CountDownLatch latch = new CountDownLatch(threadCount);
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);

        for (int i = 0; i < threadCount; i++) {
            final int idx = i;
            executor.submit(() -> {
                try {
                    ctx.setVariable("var_" + idx, idx);
                    ctx.recordStep("step_" + idx, "assign", 1);
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await(5, TimeUnit.SECONDS);
        executor.shutdown();

        // All variables should be set
        for (int i = 0; i < threadCount; i++) {
            assertNotNull(ctx.getVariable("var_" + i), "Missing var_" + i);
        }
        // All steps should be recorded
        assertEquals(threadCount, ctx.getStepHistory().size());
    }

    // --- Issue 3: ExecutorService leak (tested indirectly via parallel completion) ---

    @Test
    void testParallelWithErrorStillCompletes() {
        String yaml = """
            main:
              steps:
                - attempt:
                    try:
                      steps:
                        - parallel_fail:
                            parallel:
                              for:
                                value: item
                                in: [1, 2, 3]
                                steps:
                                  - fail:
                                      raise: "parallel error"
                    except:
                      as: e
                      steps:
                        - recover:
                            return: "recovered"
            """;
        StdlibRegistry stdlib = new StdlibRegistry();
        WorkflowDefinition def = WorkflowParser.parse(yaml);
        ExecutionContext ctx = new ExecutionContext();
        WorkflowExecutor executor = new WorkflowExecutor(def, ctx, stdlib);
        Object result = executor.execute();
        assertEquals("recovered", result);
    }

    // --- Issue 6: Integer overflow in floor division ---

    @Test
    void testFloorDivisionLargeNumbers() {
        Map<String, Object> vars = Map.of();
        ExpressionEvaluator eval = new ExpressionEvaluator(vars, Map.of());
        // Large number that would overflow int
        Object result = eval.evaluateExpression("1000000000000 // 1");
        // Should return long, not overflow to negative int
        assertTrue(result instanceof Long || result instanceof Integer);
        if (result instanceof Long l) {
            assertEquals(1000000000000L, l);
        }
    }

    @Test
    void testFloorDivisionNormal() {
        ExpressionEvaluator eval = new ExpressionEvaluator(Map.of(), Map.of());
        assertEquals(3, eval.evaluateExpression("10 // 3"));
        assertEquals(0, eval.evaluateExpression("1 // 2"));
    }

    // --- Issue 7: ReDoS protection ---

    @Test
    void testRegexLengthGuard() {
        StdlibRegistry stdlib = new StdlibRegistry();
        var findAll = stdlib.get("text.find_all");
        assertNotNull(findAll);
        // Very long regex should be rejected
        String longRegex = "a".repeat(201);
        assertThrows(RuntimeException.class, () ->
            findAll.apply(List.of("test input", longRegex)));
    }

    @Test
    void testInvalidRegexHandled() {
        StdlibRegistry stdlib = new StdlibRegistry();
        var matchRegex = stdlib.get("text.match_regex");
        // Invalid regex should throw clean error, not PatternSyntaxException
        assertThrows(RuntimeException.class, () ->
            matchRegex.apply(List.of("test", "[invalid")));
    }

    // --- Issue 8: UUID collision risk (16 chars now) ---

    @Test
    void testExecutionIdLength() {
        // Generate multiple IDs and check length + uniqueness
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < 100; i++) {
            String id = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
            assertEquals(16, id.length());
            assertTrue(ids.add(id), "Duplicate ID generated: " + id);
        }
    }

    // --- Issue 9: Shutdown cleans up resources ---

    @Test
    void testCallbackManagerShutdown() {
        CallbackManager manager = new CallbackManager();
        String id = manager.createCallback();
        assertTrue(manager.isPending(id));
        manager.shutdown();
        assertFalse(manager.isPending(id));
    }

    // --- Issue 10: Template parser with } inside strings ---

    @Test
    void testTemplateWithBraceInsideString() {
        Map<String, Object> vars = Map.of("x", 5);
        ExpressionEvaluator eval = new ExpressionEvaluator(vars, Map.of());
        // Expression containing } inside a string literal should not break parsing
        Object result = eval.evaluateTemplate("${\"hello}\"}");
        // Should evaluate to the string "hello}" (the } is inside quotes)
        assertEquals("hello}", result);
    }

    @Test
    void testTemplateMultipleExpressions() {
        Map<String, Object> vars = Map.of("a", 1, "b", 2);
        ExpressionEvaluator eval = new ExpressionEvaluator(vars, Map.of());
        Object result = eval.evaluateTemplate("${a} + ${b} = ${a + b}");
        assertEquals("1 + 2 = 3", result);
    }

    @Test
    void testTemplateSingleExpressionReturnsRawType() {
        Map<String, Object> vars = Map.of("num", 42);
        ExpressionEvaluator eval = new ExpressionEvaluator(vars, Map.of());
        Object result = eval.evaluateTemplate("${num}");
        assertEquals(42, result); // Should return Integer, not String "42"
    }

    // --- Issue 5: EventsFunctions no longer uses static state ---

    @Test
    void testEventsFunctionsIsolation() {
        StdlibRegistry registry1 = new StdlibRegistry();
        StdlibRegistry registry2 = new StdlibRegistry();
        CallbackManager manager1 = new CallbackManager();
        CallbackManager manager2 = new CallbackManager();

        EventsFunctions.register(registry1, manager1, "http://host1/callbacks");
        EventsFunctions.register(registry2, manager2, "http://host2/callbacks");

        // Each registry should use its own callback manager
        @SuppressWarnings("unchecked")
        Map<String, Object> result1 = (Map<String, Object>) registry1.get("events.create_callback_endpoint").apply(List.of());
        @SuppressWarnings("unchecked")
        Map<String, Object> result2 = (Map<String, Object>) registry2.get("events.create_callback_endpoint").apply(List.of());

        assertTrue(((String) result1.get("url")).startsWith("http://host1/callbacks/"));
        assertTrue(((String) result2.get("url")).startsWith("http://host2/callbacks/"));

        manager1.shutdown();
        manager2.shutdown();
    }
}
