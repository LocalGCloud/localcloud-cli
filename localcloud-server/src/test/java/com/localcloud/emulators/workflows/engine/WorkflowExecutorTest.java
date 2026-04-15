package com.localcloud.emulators.workflows.engine;

import com.localcloud.emulators.workflows.stdlib.StdlibRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import static org.junit.jupiter.api.Assertions.*;

import java.util.*;

class WorkflowExecutorTest {
    private StdlibRegistry stdlib;

    @BeforeEach
    void setUp() {
        stdlib = new StdlibRegistry();
    }

    private Object runWorkflow(String yaml) {
        return runWorkflow(yaml, Collections.emptyMap());
    }

    private Object runWorkflow(String yaml, Map<String, Object> args) {
        WorkflowDefinition def = WorkflowParser.parse(yaml);
        ExecutionContext ctx = new ExecutionContext(args);
        WorkflowExecutor executor = new WorkflowExecutor(def, ctx, stdlib);
        return executor.execute();
    }

    // --- Basic steps ---

    @Test
    void testReturnLiteral() {
        String yaml = """
            main:
              steps:
                - done:
                    return: "hello"
            """;
        assertEquals("hello", runWorkflow(yaml));
    }

    @Test
    void testAssignAndReturn() {
        String yaml = """
            main:
              steps:
                - init:
                    assign:
                      - x: 42
                - done:
                    return: ${x}
            """;
        assertEquals(42, runWorkflow(yaml));
    }

    @Test
    void testAssignExpression() {
        String yaml = """
            main:
              steps:
                - init:
                    assign:
                      - a: 10
                      - b: 20
                      - sum: ${a + b}
                - done:
                    return: ${sum}
            """;
        assertEquals(30, runWorkflow(yaml));
    }

    @Test
    void testAssignMax50() {
        // Should throw when > 50 assignments
        StringBuilder assignments = new StringBuilder();
        for (int i = 0; i < 51; i++) {
            assignments.append("      - var").append(i).append(": ").append(i).append("\n");
        }
        String yaml = "main:\n  steps:\n    - init:\n        assign:\n" + assignments;
        assertThrows(WorkflowException.class, () -> runWorkflow(yaml));
    }

    // --- Switch ---

    @Test
    void testSwitchTrueBranch() {
        String yaml = """
            main:
              steps:
                - init:
                    assign:
                      - x: 10
                - check:
                    switch:
                      - condition: ${x > 5}
                        return: "big"
                      - condition: true
                        return: "small"
            """;
        assertEquals("big", runWorkflow(yaml));
    }

    @Test
    void testSwitchDefault() {
        String yaml = """
            main:
              steps:
                - init:
                    assign:
                      - x: 1
                - check:
                    switch:
                      - condition: ${x > 100}
                        return: "big"
                      - condition: true
                        return: "default"
            """;
        assertEquals("default", runWorkflow(yaml));
    }

    // --- For loop ---

    @Test
    void testForLoop() {
        String yaml = """
            main:
              steps:
                - init:
                    assign:
                      - total: 0
                      - nums: [1, 2, 3, 4, 5]
                - loop:
                    for:
                      value: n
                      in: ${nums}
                      steps:
                        - add:
                            assign:
                              - total: ${total + n}
                - done:
                    return: ${total}
            """;
        assertEquals(15, runWorkflow(yaml));
    }

    // --- Next (goto) ---

    @Test
    void testNextStep() {
        String yaml = """
            main:
              steps:
                - step1:
                    next: step3
                - step2:
                    return: "wrong"
                - step3:
                    return: "correct"
            """;
        assertEquals("correct", runWorkflow(yaml));
    }

    @Test
    void testNextStepNotFound() {
        String yaml = """
            main:
              steps:
                - step1:
                    next: nonexistent
            """;
        assertThrows(WorkflowException.class, () -> runWorkflow(yaml));
    }

    // --- Raise ---

    @Test
    void testRaiseString() {
        String yaml = """
            main:
              steps:
                - fail:
                    raise: "something went wrong"
            """;
        WorkflowException ex = assertThrows(WorkflowException.class, () -> runWorkflow(yaml));
        assertTrue(ex.getMessage().contains("something went wrong"));
    }

    // --- Try/Except ---

    @Test
    void testTryCatchesError() {
        String yaml = """
            main:
              steps:
                - attempt:
                    try:
                      steps:
                        - fail:
                            raise: "error!"
                    except:
                      as: e
                      steps:
                        - handle:
                            return: "caught"
            """;
        assertEquals("caught", runWorkflow(yaml));
    }

    @Test
    void testTrySuccessNoExcept() {
        String yaml = """
            main:
              steps:
                - attempt:
                    try:
                      steps:
                        - ok:
                            assign:
                              - result: "success"
                    except:
                      as: e
                      steps:
                        - handle:
                            return: "caught"
                - done:
                    return: ${result}
            """;
        assertEquals("success", runWorkflow(yaml));
    }

    // --- Subworkflows ---

    @Test
    void testSubworkflowCall() {
        String yaml = """
            main:
              steps:
                - call_sub:
                    call: greet
                    args:
                      who: "World"
                    result: greeting
                - done:
                    return: ${greeting}
            greet:
              params: [who]
              steps:
                - build:
                    return: ${"Hello " + who}
            """;
        assertEquals("Hello World", runWorkflow(yaml));
    }

    @Test
    void testSubworkflowMaxDepth() {
        String yaml = """
            main:
              steps:
                - start:
                    call: recurse
                    args:
                      n: 25
                    result: r
                - done:
                    return: ${r}
            recurse:
              params: [n]
              steps:
                - check:
                    switch:
                      - condition: ${n <= 0}
                        return: 0
                - go:
                    call: recurse
                    args:
                      n: ${n - 1}
                    result: r
                - done:
                    return: ${r + 1}
            """;
        assertThrows(WorkflowException.class, () -> runWorkflow(yaml));
    }

    // --- YAML parsing ---

    @Test
    void testMissingMain() {
        String yaml = """
            not_main:
              steps:
                - done:
                    return: "oops"
            """;
        assertThrows(WorkflowException.class, () -> WorkflowParser.parse(yaml));
    }

    @Test
    void testInvalidYaml() {
        assertThrows(WorkflowException.class, () -> WorkflowParser.parse("{{invalid yaml"));
    }

    @Test
    void testEmptyWorkflow() {
        String yaml = """
            main:
              steps: []
            """;
        // Should not crash, just return null
        assertNull(runWorkflow(yaml));
    }

    // --- String concatenation in expressions ---

    @Test
    void testStringConcatInReturn() {
        String yaml = """
            main:
              steps:
                - init:
                    assign:
                      - name: "Cloud"
                      - version: 2
                - done:
                    return: ${name + " v" + version}
            """;
        assertEquals("Cloud v2", runWorkflow(yaml));
    }

    // --- Params passing ---

    @Test
    void testMainParams() {
        String yaml = """
            main:
              params: [args]
              steps:
                - done:
                    return: ${args}
            """;
        Map<String, Object> input = Map.of("args", Map.of("key", "value"));
        assertEquals(Map.of("key", "value"), runWorkflow(yaml, input));
    }
}
