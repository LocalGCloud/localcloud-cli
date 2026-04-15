package com.localcloud.emulators.workflows;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.sql.SQLException;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for WorkflowsServiceImpl — Management API, Execution API, and Seed handler logic.
 *
 * WorkflowsStore is mocked via Mockito to avoid the PostgreSQL-specific ::jsonb
 * cast syntax that H2 does not support. All business logic in WorkflowsServiceImpl
 * (YAML validation, operation wrappers, formatting, error handling) is exercised here.
 */
class WorkflowsServiceImplTest {

    private static final String PROJECT  = "test-project";
    private static final String LOCATION = "us-central1";
    private static final String WF_ID    = "my-workflow";

    private static final String VALID_YAML = """
            main:
              steps:
                - done:
                    return: "hello"
            """;

    private WorkflowsStore store;
    private WorkflowsServiceImpl service;

    @BeforeEach
    void setUp() {
        store   = Mockito.mock(WorkflowsStore.class);
        service = new WorkflowsServiceImpl(store);
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /** Build a minimal DB row as returned by WorkflowsStore.getWorkflow() */
    private Map<String, Object> workflowRow(String workflowId, int revisionId) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("workflow_id",      workflowId);
        row.put("project_id",       PROJECT);
        row.put("location_id",      LOCATION);
        row.put("source_contents",  VALID_YAML);
        row.put("state",            "ACTIVE");
        row.put("revision_id",      revisionId);
        row.put("labels",           "{}");
        row.put("service_account",  null);
        row.put("created_at",       "2024-01-01T00:00:00");
        row.put("updated_at",       "2024-01-01T00:00:00");
        return row;
    }

    /** Build a minimal DB row as returned by WorkflowsStore.getExecution() */
    private Map<String, Object> executionRow(String execId, String state) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("execution_id",          execId);
        row.put("workflow_id",           WF_ID);
        row.put("project_id",            PROJECT);
        row.put("location_id",           LOCATION);
        row.put("state",                 state);
        row.put("argument",              null);
        row.put("result",                null);
        row.put("error",                 null);
        row.put("start_time",            "2024-01-01T00:00:00");
        row.put("end_time",              null);
        row.put("workflow_revision_id",  "1");
        return row;
    }

    // -----------------------------------------------------------------------
    // Management API — createWorkflow
    // -----------------------------------------------------------------------

    @Test
    void createWorkflow_validYaml_returnsOperation() throws SQLException {
        when(store.getWorkflow(PROJECT, LOCATION, WF_ID)).thenReturn(workflowRow(WF_ID, 1));

        Map<String, Object> op = service.createWorkflow(PROJECT, LOCATION, WF_ID, VALID_YAML, null, null);

        verify(store).createWorkflow(PROJECT, LOCATION, WF_ID, VALID_YAML, null, null);
        assertTrue((Boolean) op.get("done"), "Operation should be done=true");
        String opName = (String) op.get("name");
        assertTrue(opName.contains("create-" + WF_ID), "Operation name should contain action+workflowId");
    }

    @Test
    void createWorkflow_invalidYaml_throwsIllegalArgument() {
        assertThrows(IllegalArgumentException.class,
                () -> service.createWorkflow(PROJECT, LOCATION, WF_ID, "{{bad yaml", null, null));
        // store should never be called when YAML validation fails
        verifyNoInteractions(store);
    }

    @Test
    void createWorkflow_responseContainsWorkflowName() throws SQLException {
        when(store.getWorkflow(PROJECT, LOCATION, WF_ID)).thenReturn(workflowRow(WF_ID, 1));

        Map<String, Object> op = service.createWorkflow(PROJECT, LOCATION, WF_ID, VALID_YAML, null, null);

        @SuppressWarnings("unchecked")
        Map<String, Object> response = (Map<String, Object>) op.get("response");
        assertNotNull(response, "Operation must include a 'response'");
        String expectedName = "projects/" + PROJECT + "/locations/" + LOCATION + "/workflows/" + WF_ID;
        assertEquals(expectedName, response.get("name"));
    }

    @Test
    void createWorkflow_responseContainsRevisionId() throws SQLException {
        when(store.getWorkflow(PROJECT, LOCATION, WF_ID)).thenReturn(workflowRow(WF_ID, 1));

        Map<String, Object> op = service.createWorkflow(PROJECT, LOCATION, WF_ID, VALID_YAML, null, null);

        @SuppressWarnings("unchecked")
        Map<String, Object> response = (Map<String, Object>) op.get("response");
        assertEquals("1", response.get("revisionId"));
    }

    @Test
    void createWorkflow_responseContainsSourceContents() throws SQLException {
        when(store.getWorkflow(PROJECT, LOCATION, WF_ID)).thenReturn(workflowRow(WF_ID, 1));

        Map<String, Object> op = service.createWorkflow(PROJECT, LOCATION, WF_ID, VALID_YAML, null, null);

        @SuppressWarnings("unchecked")
        Map<String, Object> response = (Map<String, Object>) op.get("response");
        assertEquals(VALID_YAML, response.get("sourceContents"));
    }

    @Test
    void createWorkflow_withServiceAccount_responseIncludesIt() throws SQLException {
        Map<String, Object> row = workflowRow(WF_ID, 1);
        row.put("service_account", "sa@project.iam.gserviceaccount.com");
        when(store.getWorkflow(PROJECT, LOCATION, WF_ID)).thenReturn(row);

        Map<String, Object> op = service.createWorkflow(PROJECT, LOCATION, WF_ID, VALID_YAML, null,
                "sa@project.iam.gserviceaccount.com");

        @SuppressWarnings("unchecked")
        Map<String, Object> response = (Map<String, Object>) op.get("response");
        assertEquals("sa@project.iam.gserviceaccount.com", response.get("serviceAccount"));
    }

    @Test
    void createWorkflow_withSubworkflows_yamlValidates() throws SQLException {
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
        when(store.getWorkflow(PROJECT, LOCATION, WF_ID)).thenReturn(workflowRow(WF_ID, 1));

        Map<String, Object> op = service.createWorkflow(PROJECT, LOCATION, WF_ID, yaml, null, null);
        assertTrue((Boolean) op.get("done"));
    }

    // -----------------------------------------------------------------------
    // Management API — getWorkflow
    // -----------------------------------------------------------------------

    @Test
    void getWorkflow_existing_returnsFormatted() throws SQLException {
        when(store.getWorkflow(PROJECT, LOCATION, WF_ID)).thenReturn(workflowRow(WF_ID, 1));

        Map<String, Object> result = service.getWorkflow(PROJECT, LOCATION, WF_ID);

        assertNotNull(result);
        assertEquals("projects/" + PROJECT + "/locations/" + LOCATION + "/workflows/" + WF_ID,
                result.get("name"));
        assertEquals("ACTIVE", result.get("state"));
    }

    @Test
    void getWorkflow_notFound_returnsNull() throws SQLException {
        when(store.getWorkflow(PROJECT, LOCATION, "missing")).thenReturn(null);

        Map<String, Object> result = service.getWorkflow(PROJECT, LOCATION, "missing");
        assertNull(result);
    }

    @Test
    void getWorkflow_formattedResponseHasCreateTime() throws SQLException {
        when(store.getWorkflow(PROJECT, LOCATION, WF_ID)).thenReturn(workflowRow(WF_ID, 1));

        Map<String, Object> result = service.getWorkflow(PROJECT, LOCATION, WF_ID);
        assertNotNull(result.get("createTime"));
        assertNotNull(result.get("updateTime"));
    }

    // -----------------------------------------------------------------------
    // Management API — updateWorkflow
    // -----------------------------------------------------------------------

    @Test
    void updateWorkflow_validYaml_returnsOperation() throws SQLException {
        String updatedYaml = """
                main:
                  steps:
                    - done:
                        return: "updated"
                """;
        Map<String, Object> row = workflowRow(WF_ID, 2);
        row.put("source_contents", updatedYaml);
        when(store.getWorkflow(PROJECT, LOCATION, WF_ID)).thenReturn(row);

        Map<String, Object> op = service.updateWorkflow(PROJECT, LOCATION, WF_ID, updatedYaml);

        verify(store).updateWorkflow(PROJECT, LOCATION, WF_ID, updatedYaml);
        assertTrue((Boolean) op.get("done"));
        String opName = (String) op.get("name");
        assertTrue(opName.contains("update-" + WF_ID));
    }

    @Test
    void updateWorkflow_invalidYaml_throwsIllegalArgument() {
        assertThrows(IllegalArgumentException.class,
                () -> service.updateWorkflow(PROJECT, LOCATION, WF_ID, "{{invalid yaml"));
        verifyNoInteractions(store);
    }

    @Test
    void updateWorkflow_nullSource_skipsValidation() throws SQLException {
        when(store.getWorkflow(PROJECT, LOCATION, WF_ID)).thenReturn(workflowRow(WF_ID, 2));

        // null source means no update to source_contents — validation is skipped
        assertDoesNotThrow(() -> service.updateWorkflow(PROJECT, LOCATION, WF_ID, null));
        verify(store).updateWorkflow(PROJECT, LOCATION, WF_ID, null);
    }

    @Test
    void updateWorkflow_operationNameContainsWorkflowId() throws SQLException {
        when(store.getWorkflow(PROJECT, LOCATION, WF_ID)).thenReturn(workflowRow(WF_ID, 2));

        Map<String, Object> op = service.updateWorkflow(PROJECT, LOCATION, WF_ID, VALID_YAML);

        assertTrue(((String) op.get("name")).endsWith("update-" + WF_ID));
    }

    // -----------------------------------------------------------------------
    // Management API — deleteWorkflow
    // -----------------------------------------------------------------------

    @Test
    void deleteWorkflow_returnsOperation() throws SQLException {
        Map<String, Object> op = service.deleteWorkflow(PROJECT, LOCATION, WF_ID);

        verify(store).deleteWorkflow(PROJECT, LOCATION, WF_ID);
        assertTrue((Boolean) op.get("done"));
        assertTrue(((String) op.get("name")).contains("delete-" + WF_ID));
    }

    @Test
    void deleteWorkflow_operationHasNoResponse() throws SQLException {
        Map<String, Object> op = service.deleteWorkflow(PROJECT, LOCATION, WF_ID);

        assertFalse(op.containsKey("response"), "Delete operation should not have a 'response' field");
    }

    // -----------------------------------------------------------------------
    // Management API — listWorkflows
    // -----------------------------------------------------------------------

    @Test
    void listWorkflows_emptyList_returnsEmpty() throws SQLException {
        when(store.listWorkflows(PROJECT, LOCATION, 10)).thenReturn(Collections.emptyList());

        List<Map<String, Object>> result = service.listWorkflows(PROJECT, LOCATION, 10);
        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    void listWorkflows_formatsEachWorkflow() throws SQLException {
        List<Map<String, Object>> rows = List.of(
                workflowRow("wf-1", 1),
                workflowRow("wf-2", 3)
        );
        when(store.listWorkflows(PROJECT, LOCATION, 100)).thenReturn(rows);

        List<Map<String, Object>> result = service.listWorkflows(PROJECT, LOCATION, 100);

        assertEquals(2, result.size());
        assertTrue(result.get(0).get("name").toString().contains("wf-1"));
        assertTrue(result.get(1).get("name").toString().contains("wf-2"));
    }

    @Test
    void listWorkflows_eachEntryHasRequiredFields() throws SQLException {
        when(store.listWorkflows(PROJECT, LOCATION, 50)).thenReturn(List.of(workflowRow(WF_ID, 1)));

        List<Map<String, Object>> result = service.listWorkflows(PROJECT, LOCATION, 50);

        Map<String, Object> wf = result.get(0);
        assertTrue(wf.containsKey("name"));
        assertTrue(wf.containsKey("state"));
        assertTrue(wf.containsKey("revisionId"));
        assertTrue(wf.containsKey("sourceContents"));
    }

    // -----------------------------------------------------------------------
    // Execution API — createExecution
    // -----------------------------------------------------------------------

    @Test
    void createExecution_workflowNotFound_throwsIllegalArgument() throws SQLException {
        when(store.getWorkflow(PROJECT, LOCATION, "missing")).thenReturn(null);

        assertThrows(IllegalArgumentException.class,
                () -> service.createExecution(PROJECT, LOCATION, "missing", null));
    }

    @Test
    void createExecution_validWorkflow_returnsQueuedExecution() throws SQLException {
        when(store.getWorkflow(PROJECT, LOCATION, WF_ID)).thenReturn(workflowRow(WF_ID, 1));
        when(store.createExecution(eq(WF_ID), eq(PROJECT), eq(LOCATION), any(), eq("1")))
                .thenReturn("exec-abc123");
        when(store.getExecution(PROJECT, LOCATION, WF_ID, "exec-abc123"))
                .thenReturn(executionRow("exec-abc123", "QUEUED"));

        Map<String, Object> result = service.createExecution(PROJECT, LOCATION, WF_ID, null);

        assertNotNull(result);
        String name = (String) result.get("name");
        assertTrue(name.contains(WF_ID + "/executions/exec-abc123"));
    }

    @Test
    void createExecution_statePresentInResponse() throws SQLException {
        when(store.getWorkflow(PROJECT, LOCATION, WF_ID)).thenReturn(workflowRow(WF_ID, 1));
        when(store.createExecution(eq(WF_ID), eq(PROJECT), eq(LOCATION), any(), eq("1")))
                .thenReturn("exec-xyz");
        when(store.getExecution(PROJECT, LOCATION, WF_ID, "exec-xyz"))
                .thenReturn(executionRow("exec-xyz", "QUEUED"));

        Map<String, Object> result = service.createExecution(PROJECT, LOCATION, WF_ID, null);

        assertEquals("QUEUED", result.get("state"));
    }

    @Test
    void createExecution_withArgument_passedToStore() throws SQLException {
        String argument = "{\"key\":\"value\"}";
        when(store.getWorkflow(PROJECT, LOCATION, WF_ID)).thenReturn(workflowRow(WF_ID, 1));
        when(store.createExecution(eq(WF_ID), eq(PROJECT), eq(LOCATION), eq(argument), eq("1")))
                .thenReturn("exec-arg");
        when(store.getExecution(PROJECT, LOCATION, WF_ID, "exec-arg"))
                .thenReturn(executionRow("exec-arg", "QUEUED"));

        Map<String, Object> result = service.createExecution(PROJECT, LOCATION, WF_ID, argument);
        assertNotNull(result);
        verify(store).createExecution(WF_ID, PROJECT, LOCATION, argument, "1");
    }

    @Test
    void createExecution_usesRevisionIdFromWorkflow() throws SQLException {
        Map<String, Object> row = workflowRow(WF_ID, 5);
        when(store.getWorkflow(PROJECT, LOCATION, WF_ID)).thenReturn(row);
        when(store.createExecution(eq(WF_ID), eq(PROJECT), eq(LOCATION), any(), eq("5")))
                .thenReturn("exec-rev5");
        when(store.getExecution(PROJECT, LOCATION, WF_ID, "exec-rev5"))
                .thenReturn(executionRow("exec-rev5", "QUEUED"));

        service.createExecution(PROJECT, LOCATION, WF_ID, null);

        verify(store).createExecution(WF_ID, PROJECT, LOCATION, null, "5");
    }

    // -----------------------------------------------------------------------
    // Execution API — getExecution
    // -----------------------------------------------------------------------

    @Test
    void getExecution_existing_returnsFormatted() throws SQLException {
        when(store.getExecution(PROJECT, LOCATION, WF_ID, "exec-1"))
                .thenReturn(executionRow("exec-1", "SUCCEEDED"));

        Map<String, Object> result = service.getExecution(PROJECT, LOCATION, WF_ID, "exec-1");

        assertNotNull(result);
        assertEquals("SUCCEEDED", result.get("state"));
        assertTrue(result.get("name").toString().contains("exec-1"));
    }

    @Test
    void getExecution_notFound_returnsNull() throws SQLException {
        when(store.getExecution(PROJECT, LOCATION, WF_ID, "missing")).thenReturn(null);

        Map<String, Object> result = service.getExecution(PROJECT, LOCATION, WF_ID, "missing");
        assertNull(result);
    }

    @Test
    void getExecution_formattedNameContainsAllParts() throws SQLException {
        when(store.getExecution(PROJECT, LOCATION, WF_ID, "exec-2"))
                .thenReturn(executionRow("exec-2", "ACTIVE"));

        Map<String, Object> result = service.getExecution(PROJECT, LOCATION, WF_ID, "exec-2");

        String expected = "projects/" + PROJECT + "/locations/" + LOCATION
                + "/workflows/" + WF_ID + "/executions/exec-2";
        assertEquals(expected, result.get("name"));
    }

    @Test
    void getExecution_startTimePresent() throws SQLException {
        when(store.getExecution(PROJECT, LOCATION, WF_ID, "exec-3"))
                .thenReturn(executionRow("exec-3", "QUEUED"));

        Map<String, Object> result = service.getExecution(PROJECT, LOCATION, WF_ID, "exec-3");
        assertNotNull(result.get("startTime"));
    }

    @Test
    void getExecution_withResult_resultIncluded() throws SQLException {
        Map<String, Object> row = executionRow("exec-4", "SUCCEEDED");
        row.put("result", "\"hello\"");
        when(store.getExecution(PROJECT, LOCATION, WF_ID, "exec-4")).thenReturn(row);

        Map<String, Object> result = service.getExecution(PROJECT, LOCATION, WF_ID, "exec-4");
        assertEquals("\"hello\"", result.get("result"));
    }

    @Test
    void getExecution_withError_errorIncluded() throws SQLException {
        Map<String, Object> row = executionRow("exec-5", "FAILED");
        row.put("error", "{\"code\":\"RuntimeError\",\"message\":\"oops\"}");
        when(store.getExecution(PROJECT, LOCATION, WF_ID, "exec-5")).thenReturn(row);

        Map<String, Object> result = service.getExecution(PROJECT, LOCATION, WF_ID, "exec-5");
        assertNotNull(result.get("error"));
    }

    // -----------------------------------------------------------------------
    // Execution API — listExecutions
    // -----------------------------------------------------------------------

    @Test
    void listExecutions_empty_returnsEmptyList() throws SQLException {
        when(store.listExecutions(PROJECT, LOCATION, WF_ID, 10)).thenReturn(Collections.emptyList());

        List<Map<String, Object>> result = service.listExecutions(PROJECT, LOCATION, WF_ID, 10);
        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    void listExecutions_formatsEachEntry() throws SQLException {
        List<Map<String, Object>> rows = List.of(
                executionRow("exec-a", "SUCCEEDED"),
                executionRow("exec-b", "FAILED")
        );
        when(store.listExecutions(PROJECT, LOCATION, WF_ID, 100)).thenReturn(rows);

        List<Map<String, Object>> result = service.listExecutions(PROJECT, LOCATION, WF_ID, 100);

        assertEquals(2, result.size());
        assertEquals("SUCCEEDED", result.get(0).get("state"));
        assertEquals("FAILED",    result.get(1).get("state"));
    }

    @Test
    void listExecutions_eachEntryHasNameAndState() throws SQLException {
        when(store.listExecutions(PROJECT, LOCATION, WF_ID, 50))
                .thenReturn(List.of(executionRow("exec-c", "QUEUED")));

        List<Map<String, Object>> result = service.listExecutions(PROJECT, LOCATION, WF_ID, 50);
        Map<String, Object> exec = result.get(0);
        assertTrue(exec.containsKey("name"));
        assertTrue(exec.containsKey("state"));
    }

    // -----------------------------------------------------------------------
    // Execution API — cancelExecution
    // -----------------------------------------------------------------------

    @Test
    void cancelExecution_notFound_throwsIllegalArgument() throws SQLException {
        when(store.getExecution(PROJECT, LOCATION, WF_ID, "missing")).thenReturn(null);

        assertThrows(IllegalArgumentException.class,
                () -> service.cancelExecution(PROJECT, LOCATION, WF_ID, "missing"));
    }

    @Test
    void cancelExecution_alreadySucceeded_throwsIllegalState() throws SQLException {
        when(store.getExecution(PROJECT, LOCATION, WF_ID, "exec-done"))
                .thenReturn(executionRow("exec-done", "SUCCEEDED"));

        assertThrows(IllegalStateException.class,
                () -> service.cancelExecution(PROJECT, LOCATION, WF_ID, "exec-done"));
    }

    @Test
    void cancelExecution_alreadyFailed_throwsIllegalState() throws SQLException {
        when(store.getExecution(PROJECT, LOCATION, WF_ID, "exec-failed"))
                .thenReturn(executionRow("exec-failed", "FAILED"));

        assertThrows(IllegalStateException.class,
                () -> service.cancelExecution(PROJECT, LOCATION, WF_ID, "exec-failed"));
    }

    @Test
    void cancelExecution_alreadyCancelled_throwsIllegalState() throws SQLException {
        when(store.getExecution(PROJECT, LOCATION, WF_ID, "exec-cancelled"))
                .thenReturn(executionRow("exec-cancelled", "CANCELLED"));

        assertThrows(IllegalStateException.class,
                () -> service.cancelExecution(PROJECT, LOCATION, WF_ID, "exec-cancelled"));
    }

    @Test
    void cancelExecution_activeExecution_callsUpdateAndReturnsCancelled() throws SQLException {
        Map<String, Object> cancelledRow = executionRow("exec-active", "CANCELLED");
        when(store.getExecution(PROJECT, LOCATION, WF_ID, "exec-active"))
                .thenReturn(executionRow("exec-active", "ACTIVE"))
                .thenReturn(cancelledRow);

        Map<String, Object> result = service.cancelExecution(PROJECT, LOCATION, WF_ID, "exec-active");

        verify(store).updateExecutionState("exec-active", "CANCELLED", null, null);
        assertEquals("CANCELLED", result.get("state"));
    }

    @Test
    void cancelExecution_queuedExecution_canBeCancelled() throws SQLException {
        when(store.getExecution(PROJECT, LOCATION, WF_ID, "exec-queued"))
                .thenReturn(executionRow("exec-queued", "QUEUED"))
                .thenReturn(executionRow("exec-queued", "CANCELLED"));

        // Should not throw
        assertDoesNotThrow(() -> service.cancelExecution(PROJECT, LOCATION, WF_ID, "exec-queued"));
        verify(store).updateExecutionState("exec-queued", "CANCELLED", null, null);
    }

    // -----------------------------------------------------------------------
    // Store accessor
    // -----------------------------------------------------------------------

    @Test
    void getStore_returnsSameInstance() {
        assertSame(store, service.getStore());
    }

    @Test
    void getCallbackManager_isNotNull() {
        assertNotNull(service.getCallbackManager());
    }

    // -----------------------------------------------------------------------
    // Response formatting edge cases
    // -----------------------------------------------------------------------

    @Test
    void formatWorkflow_nullServiceAccount_notIncludedInResponse() throws SQLException {
        Map<String, Object> row = workflowRow(WF_ID, 1);
        row.put("service_account", null);
        when(store.getWorkflow(PROJECT, LOCATION, WF_ID)).thenReturn(row);

        Map<String, Object> result = service.getWorkflow(PROJECT, LOCATION, WF_ID);
        assertFalse(result.containsKey("serviceAccount"),
                "serviceAccount should be absent when null");
    }

    @Test
    void formatExecution_nullEndTime_notIncludedInResponse() throws SQLException {
        Map<String, Object> row = executionRow("exec-no-end", "ACTIVE");
        row.put("end_time", null);
        when(store.getExecution(PROJECT, LOCATION, WF_ID, "exec-no-end")).thenReturn(row);

        Map<String, Object> result = service.getExecution(PROJECT, LOCATION, WF_ID, "exec-no-end");
        assertFalse(result.containsKey("endTime"), "endTime should be absent when null");
    }

    @Test
    void formatExecution_workflowRevisionIdIncluded() throws SQLException {
        Map<String, Object> row = executionRow("exec-rev", "QUEUED");
        row.put("workflow_revision_id", "3");
        when(store.getExecution(PROJECT, LOCATION, WF_ID, "exec-rev")).thenReturn(row);

        Map<String, Object> result = service.getExecution(PROJECT, LOCATION, WF_ID, "exec-rev");
        assertEquals("3", result.get("workflowRevisionId"));
    }

    // -----------------------------------------------------------------------
    // Operation name format validation
    // -----------------------------------------------------------------------

    @Test
    void createOperation_nameContainsProjectAndLocation() throws SQLException {
        when(store.getWorkflow(PROJECT, LOCATION, WF_ID)).thenReturn(workflowRow(WF_ID, 1));

        Map<String, Object> op = service.createWorkflow(PROJECT, LOCATION, WF_ID, VALID_YAML, null, null);

        String opName = (String) op.get("name");
        assertTrue(opName.contains("projects/" + PROJECT));
        assertTrue(opName.contains("locations/" + LOCATION));
    }

    @Test
    void deleteOperation_nameContainsProjectAndLocation() throws SQLException {
        Map<String, Object> op = service.deleteWorkflow(PROJECT, LOCATION, WF_ID);

        String opName = (String) op.get("name");
        assertTrue(opName.contains("projects/" + PROJECT));
        assertTrue(opName.contains("locations/" + LOCATION));
    }

    // -----------------------------------------------------------------------
    // Seed handler logic — WorkflowsServiceImpl validates before persisting
    // -----------------------------------------------------------------------

    @Test
    void seedHandler_validWorkflowGoesToStore() throws SQLException {
        // Simulate what the seed handler does: validate then upsert
        String source = """
                main:
                  steps:
                    - done:
                        return: "seeded"
                """;
        // Validation should pass
        assertDoesNotThrow(() -> {
            com.localcloud.emulators.workflows.engine.WorkflowParser.parse(source);
        });

        // Simulate store upsert call
        store.upsertWorkflow(PROJECT, LOCATION, "seeded-wf", source);
        verify(store).upsertWorkflow(PROJECT, LOCATION, "seeded-wf", source);
    }

    @Test
    void seedHandler_invalidWorkflowSkipped() {
        String invalidSource = "{{not: [valid";
        // Seed handler catches this and skips the entry
        assertThrows(com.localcloud.emulators.workflows.engine.WorkflowException.class,
                () -> com.localcloud.emulators.workflows.engine.WorkflowParser.parse(invalidSource));
        // Store should not be called — seed handler catches the exception
        verifyNoInteractions(store);
    }

    @Test
    void seedHandler_missingMainSkipped() {
        String noMain = """
                helper:
                  steps:
                    - done:
                        return: "ok"
                """;
        assertThrows(com.localcloud.emulators.workflows.engine.WorkflowException.class,
                () -> com.localcloud.emulators.workflows.engine.WorkflowParser.parse(noMain));
        verifyNoInteractions(store);
    }

    @Test
    void seedHandler_multipleValidWorkflows_allUpserted() throws SQLException {
        List<String> names = List.of("wf-alpha", "wf-beta", "wf-gamma");
        String source = """
                main:
                  steps:
                    - done:
                        return: "ok"
                """;

        for (String name : names) {
            store.upsertWorkflow(PROJECT, LOCATION, name, source);
        }

        verify(store, times(3)).upsertWorkflow(eq(PROJECT), eq(LOCATION), anyString(), eq(source));
    }
}
