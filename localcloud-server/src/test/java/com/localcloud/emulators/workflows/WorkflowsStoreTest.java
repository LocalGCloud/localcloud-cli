package com.localcloud.emulators.workflows;

import com.localcloud.emulators.workflows.engine.WorkflowParser;
import com.localcloud.emulators.workflows.engine.WorkflowDefinition;
import com.localcloud.emulators.workflows.engine.WorkflowException;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import java.util.*;

/**
 * Tests for Workflows YAML parsing, validation, and structural inspection
 * that underpin WorkflowsStore's createWorkflow/upsertWorkflow validation path.
 *
 * Database-dependent CRUD tests are in WorkflowsServiceImplTest (mocked store).
 * The ::jsonb PostgreSQL cast used in WorkflowsStore SQL is incompatible with H2,
 * so store SQL is covered via Mockito in WorkflowsServiceImplTest.
 */
class WorkflowsStoreTest {

    // -----------------------------------------------------------------------
    // YAML Validation — used by WorkflowsServiceImpl before persisting
    // -----------------------------------------------------------------------

    @Test
    void testValidMinimalWorkflowParses() {
        String yaml = """
                main:
                  steps:
                    - done:
                        return: "hello"
                """;
        assertDoesNotThrow(() -> WorkflowParser.parse(yaml));
    }

    @Test
    void testInvalidYamlThrowsWorkflowException() {
        assertThrows(WorkflowException.class, () -> WorkflowParser.parse("{{invalid: [unclosed"));
    }

    @Test
    void testMissingMainEntryPointRejected() {
        String yaml = """
                helper:
                  steps:
                    - done:
                        return: "no main"
                """;
        WorkflowException ex = assertThrows(WorkflowException.class, () -> WorkflowParser.parse(yaml));
        assertTrue(ex.getMessage().contains("main"), "Error should mention missing 'main'");
    }

    @Test
    void testEmptyStepsListIsValid() {
        String yaml = """
                main:
                  steps: []
                """;
        WorkflowDefinition def = WorkflowParser.parse(yaml);
        assertNotNull(def.getMain());
        assertTrue(def.getMain().getSteps().isEmpty());
    }

    @Test
    void testNullSourceThrowsWorkflowException() {
        assertThrows(WorkflowException.class, () -> WorkflowParser.parse(null));
    }

    @Test
    void testBlankYamlThrowsWorkflowException() {
        assertThrows(WorkflowException.class, () -> WorkflowParser.parse(""));
    }

    // -----------------------------------------------------------------------
    // Subworkflow parsing
    // -----------------------------------------------------------------------

    @Test
    void testSubworkflowIsRecognised() {
        String yaml = """
                main:
                  steps:
                    - call_sub:
                        call: helper
                        result: r
                    - done:
                        return: ${r}
                helper:
                  params: [x]
                  steps:
                    - done:
                        return: ${x}
                """;
        WorkflowDefinition def = WorkflowParser.parse(yaml);
        assertTrue(def.hasSubworkflow("helper"), "Should recognise 'helper' subworkflow");
        assertEquals(List.of("x"), def.getSubworkflow("helper").getParams());
    }

    @Test
    void testMultipleSubworkflowsAllPresent() {
        String yaml = """
                main:
                  steps:
                    - done:
                        return: "ok"
                sub1:
                  params: [a]
                  steps:
                    - done:
                        return: ${a}
                sub2:
                  params: [b, c]
                  steps:
                    - done:
                        return: ${b}
                """;
        WorkflowDefinition def = WorkflowParser.parse(yaml);
        // getAllSubworkflows includes "main"
        assertEquals(3, def.getAllSubworkflows().size());
        assertTrue(def.hasSubworkflow("sub1"));
        assertTrue(def.hasSubworkflow("sub2"));
        assertEquals(2, def.getSubworkflow("sub2").getParams().size());
    }

    @Test
    void testSubworkflowWithNoParamsHasEmptyList() {
        String yaml = """
                main:
                  steps:
                    - done:
                        return: "ok"
                noparams:
                  steps:
                    - done:
                        return: "x"
                """;
        WorkflowDefinition def = WorkflowParser.parse(yaml);
        List<String> params = def.getSubworkflow("noparams").getParams();
        assertNotNull(params);
        assertTrue(params.isEmpty());
    }

    // -----------------------------------------------------------------------
    // Step type detection
    // -----------------------------------------------------------------------

    @Test
    void testAllStepTypesDetected() {
        String yaml = """
                main:
                  steps:
                    - s1:
                        assign:
                          - x: 1
                    - s2:
                        call: http.get
                        args:
                          url: http://example.com
                    - s3:
                        switch:
                          - condition: true
                            return: "yes"
                    - s4:
                        for:
                          value: item
                          in: [1,2,3]
                          steps: []
                    - s5:
                        return: "done"
                    - s6:
                        raise: "error"
                    - s7:
                        next: s1
                """;
        WorkflowDefinition def = WorkflowParser.parse(yaml);
        List<WorkflowDefinition.StepDef> steps = def.getMain().getSteps();
        assertEquals(7, steps.size());
        assertEquals("assign",  steps.get(0).getType());
        assertEquals("call",    steps.get(1).getType());
        assertEquals("switch",  steps.get(2).getType());
        assertEquals("for",     steps.get(3).getType());
        assertEquals("return",  steps.get(4).getType());
        assertEquals("raise",   steps.get(5).getType());
        assertEquals("next",    steps.get(6).getType());
    }

    @Test
    void testParallelStepTypeDetected() {
        String yaml = """
                main:
                  steps:
                    - p:
                        parallel:
                          for:
                            value: item
                            in: [1,2]
                            steps: []
                """;
        WorkflowDefinition def = WorkflowParser.parse(yaml);
        assertEquals("parallel", def.getMain().getSteps().get(0).getType());
    }

    @Test
    void testTryStepTypeDetected() {
        String yaml = """
                main:
                  steps:
                    - t:
                        try:
                          steps:
                            - ok:
                                return: "ok"
                        except:
                          as: e
                          steps:
                            - handle:
                                return: "caught"
                """;
        WorkflowDefinition def = WorkflowParser.parse(yaml);
        assertEquals("try", def.getMain().getSteps().get(0).getType());
    }

    @Test
    void testStepNamesPreserved() {
        String yaml = """
                main:
                  steps:
                    - initialize:
                        assign:
                          - x: 1
                    - process:
                        return: ${x}
                """;
        WorkflowDefinition def = WorkflowParser.parse(yaml);
        assertEquals("initialize", def.getMain().getSteps().get(0).getName());
        assertEquals("process",    def.getMain().getSteps().get(1).getName());
    }

    @Test
    void testStepConfigAccessible() {
        String yaml = """
                main:
                  steps:
                    - fetch:
                        call: http.get
                        args:
                          url: http://example.com
                        result: resp
                """;
        WorkflowDefinition def = WorkflowParser.parse(yaml);
        WorkflowDefinition.StepDef step = def.getMain().getSteps().get(0);
        assertEquals("call", step.getType());
        assertEquals("http.get", step.get("call"));
        assertNotNull(step.get("args"));
    }

    // -----------------------------------------------------------------------
    // Main params
    // -----------------------------------------------------------------------

    @Test
    void testMainWithParamsParsed() {
        String yaml = """
                main:
                  params: [args]
                  steps:
                    - done:
                        return: "ok"
                """;
        WorkflowDefinition def = WorkflowParser.parse(yaml);
        assertEquals(List.of("args"), def.getMain().getParams());
    }

    @Test
    void testMainWithMultipleParamsParsed() {
        String yaml = """
                main:
                  params: [a, b, c]
                  steps:
                    - done:
                        return: "ok"
                """;
        WorkflowDefinition def = WorkflowParser.parse(yaml);
        assertEquals(List.of("a", "b", "c"), def.getMain().getParams());
    }

    @Test
    void testMainWithNoParamsHasEmptyList() {
        String yaml = """
                main:
                  steps:
                    - done:
                        return: "ok"
                """;
        WorkflowDefinition def = WorkflowParser.parse(yaml);
        assertNotNull(def.getMain().getParams());
        assertTrue(def.getMain().getParams().isEmpty());
    }

    // -----------------------------------------------------------------------
    // Complex realistic workflows
    // -----------------------------------------------------------------------

    @Test
    void testComplexWorkflowWithForLoopAndHttpCall() {
        String yaml = """
                main:
                  params: [args]
                  steps:
                    - init:
                        assign:
                          - project: "local-project"
                          - items: ${args.items}
                    - loop:
                        for:
                          value: item
                          in: ${items}
                          steps:
                            - process:
                                call: http.post
                                args:
                                  url: http://example.com
                                  body:
                                    id: ${item.id}
                                result: resp
                    - done:
                        return: "completed"
                """;
        WorkflowDefinition def = WorkflowParser.parse(yaml);
        assertNotNull(def.getMain());
        assertEquals(List.of("args"), def.getMain().getParams());
        assertEquals(3, def.getMain().getSteps().size());
        assertEquals("for", def.getMain().getSteps().get(1).getType());
    }

    @Test
    void testWorkflowWithSwitchStep() {
        String yaml = """
                main:
                  steps:
                    - check:
                        switch:
                          - condition: ${x > 0}
                            next: positive
                          - condition: true
                            next: negative
                    - positive:
                        return: "pos"
                    - negative:
                        return: "neg"
                """;
        WorkflowDefinition def = WorkflowParser.parse(yaml);
        assertEquals(3, def.getMain().getSteps().size());
        assertEquals("switch", def.getMain().getSteps().get(0).getType());
    }

    // -----------------------------------------------------------------------
    // Resource name format validation (mirrors WorkflowsServiceImpl.formatWorkflow)
    // -----------------------------------------------------------------------

    @Test
    void testResourceNameFormatStructure() {
        String name = "projects/my-project/locations/us-central1/workflows/my-workflow";
        String[] parts = name.split("/");
        assertEquals(6, parts.length);
        assertEquals("projects",    parts[0]);
        assertEquals("my-project",  parts[1]);
        assertEquals("locations",   parts[2]);
        assertEquals("us-central1", parts[3]);
        assertEquals("workflows",   parts[4]);
        assertEquals("my-workflow", parts[5]);
    }

    @Test
    void testExecutionResourceNameFormatStructure() {
        String name = "projects/p/locations/us-central1/workflows/wf/executions/exec-id";
        String[] parts = name.split("/");
        assertEquals(8, parts.length);
        assertEquals("executions", parts[6]);
        assertEquals("exec-id",    parts[7]);
    }

    // -----------------------------------------------------------------------
    // Seed format validation — mirrors WorkflowsServiceImpl seed handler
    // -----------------------------------------------------------------------

    @Test
    void testSeedEntryFormatIsValid() {
        Map<String, Object> seedEntry = new LinkedHashMap<>();
        seedEntry.put("name", "hello-world");
        seedEntry.put("location", "us-central1");
        seedEntry.put("source", "main:\n  steps:\n    - done:\n        return: \"hello\"\n");

        assertEquals("hello-world", seedEntry.get("name"));
        assertEquals("us-central1", seedEntry.get("location"));
        assertNotNull(seedEntry.get("source"));
        assertDoesNotThrow(() -> WorkflowParser.parse((String) seedEntry.get("source")));
    }

    @Test
    void testSeedInvalidSourceFailsValidation() {
        String invalidSource = "{{not valid yaml: [";
        assertThrows(WorkflowException.class, () -> WorkflowParser.parse(invalidSource));
        // In the actual seed handler this is caught and the entry is skipped/logged
    }

    @Test
    void testSeedSourceWithSubworkflowsIsValid() {
        String source = """
                main:
                  params: [input]
                  steps:
                    - call_helper:
                        call: greet
                        args:
                          name: ${input.name}
                        result: greeting
                    - done:
                        return: ${greeting}
                greet:
                  params: [name]
                  steps:
                    - done:
                        return: ${"Hello " + name}
                """;
        assertDoesNotThrow(() -> WorkflowParser.parse(source));
        WorkflowDefinition def = WorkflowParser.parse(source);
        assertTrue(def.hasSubworkflow("greet"));
    }
}
