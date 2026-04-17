package com.localcloud.emulators.workflows;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.localcloud.emulators.workflows.engine.*;
import com.localcloud.emulators.workflows.stdlib.StdlibRegistry;
import com.localcloud.emulators.workflows.stdlib.EventsFunctions;
import com.localcloud.emulators.workflows.connector.ConnectorRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.localcloud.emulators.workflows.stdlib.SysFunctions;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.*;

/**
 * REST service implementation for Cloud Workflows management and execution APIs.
 * Handles workflow CRUD and execution lifecycle.
 */
public class WorkflowsServiceImpl {
    private static final Logger logger = LoggerFactory.getLogger(WorkflowsServiceImpl.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    private final WorkflowsStore store;
    private final StdlibRegistry stdlib;
    private final ConnectorRegistry connectorRegistry;
    private final CallbackManager callbackManager;
    private final ExecutorService executionPool;
    private WorkflowEnvVarsRepository envVarsRepository;

    public WorkflowsServiceImpl(WorkflowsStore store) {
        this.store = store;
        this.stdlib = new StdlibRegistry();
        this.connectorRegistry = new ConnectorRegistry();
        this.callbackManager = new CallbackManager();
        this.executionPool = Executors.newVirtualThreadPerTaskExecutor();

        // Wire callback manager to stdlib
        EventsFunctions.register(this.stdlib, this.callbackManager, "http://localhost:8080/_localcloud/workflows/callbacks");
    }

    public WorkflowsStore getStore() { return store; }
    public CallbackManager getCallbackManager() { return callbackManager; }

    public void setEnvVarsRepository(WorkflowEnvVarsRepository repo) {
        this.envVarsRepository = repo;
    }

    public void shutdown() {
        executionPool.shutdown();
        callbackManager.shutdown();
    }

    // --- Workflow Management ---

    public Map<String, Object> createWorkflow(String projectId, String locationId, String workflowId,
                                               String sourceContents, String labelsJson, String serviceAccount) throws SQLException {
        // Validate YAML parses
        try {
            WorkflowParser.parse(sourceContents);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid workflow YAML: " + e.getMessage());
        }

        store.createWorkflow(projectId, locationId, workflowId, sourceContents, labelsJson, serviceAccount);
        Map<String, Object> workflow = store.getWorkflow(projectId, locationId, workflowId);

        // Wrap in Operation response
        Map<String, Object> operation = new LinkedHashMap<>();
        operation.put("name", "projects/" + projectId + "/locations/" + locationId + "/operations/create-" + workflowId);
        operation.put("done", true);
        operation.put("response", formatWorkflow(workflow, projectId, locationId));
        return operation;
    }

    public Map<String, Object> getWorkflow(String projectId, String locationId, String workflowId) throws SQLException {
        Map<String, Object> workflow = store.getWorkflow(projectId, locationId, workflowId);
        if (workflow == null) return null;
        return formatWorkflow(workflow, projectId, locationId);
    }

    public Map<String, Object> updateWorkflow(String projectId, String locationId, String workflowId,
                                               String sourceContents) throws SQLException {
        if (sourceContents != null) {
            try { WorkflowParser.parse(sourceContents); }
            catch (Exception e) { throw new IllegalArgumentException("Invalid workflow YAML: " + e.getMessage()); }
        }

        store.updateWorkflow(projectId, locationId, workflowId, sourceContents);
        Map<String, Object> workflow = store.getWorkflow(projectId, locationId, workflowId);

        Map<String, Object> operation = new LinkedHashMap<>();
        operation.put("name", "projects/" + projectId + "/locations/" + locationId + "/operations/update-" + workflowId);
        operation.put("done", true);
        operation.put("response", formatWorkflow(workflow, projectId, locationId));
        return operation;
    }

    public Map<String, Object> deleteWorkflow(String projectId, String locationId, String workflowId) throws SQLException {
        store.deleteWorkflow(projectId, locationId, workflowId);

        Map<String, Object> operation = new LinkedHashMap<>();
        operation.put("name", "projects/" + projectId + "/locations/" + locationId + "/operations/delete-" + workflowId);
        operation.put("done", true);
        return operation;
    }

    public List<Map<String, Object>> listWorkflows(String projectId, String locationId, int pageSize) throws SQLException {
        List<Map<String, Object>> workflows = store.listWorkflows(projectId, locationId, pageSize);
        List<Map<String, Object>> formatted = new ArrayList<>();
        for (Map<String, Object> w : workflows) {
            formatted.add(formatWorkflow(w, projectId, locationId));
        }
        return formatted;
    }

    public List<Map<String, Object>> listWorkflowRevisions(String projectId, String locationId, String workflowId) throws SQLException {
        Map<String, Object> workflow = store.getWorkflow(projectId, locationId, workflowId);
        if (workflow == null) return Collections.emptyList();
        // MVP: no separate revision storage — return current state as the only revision
        Map<String, Object> revision = formatWorkflow(workflow, projectId, locationId);
        revision.put("revisionId", String.valueOf(workflow.getOrDefault("revision_id", 1)));
        return List.of(revision);
    }

    // --- Execution Management ---

    public Map<String, Object> createExecution(String projectId, String locationId, String workflowId,
                                                String argument) throws SQLException {
        Map<String, Object> workflow = store.getWorkflow(projectId, locationId, workflowId);
        if (workflow == null) {
            throw new IllegalArgumentException("Workflow not found: " + workflowId);
        }

        String revisionId = String.valueOf(workflow.get("revision_id"));
        String executionId = store.createExecution(workflowId, projectId, locationId, argument, revisionId);
        String sourceContents = (String) workflow.get("source_contents");

        // Run execution asynchronously
        executionPool.submit(() -> runExecution(executionId, sourceContents, argument));

        Map<String, Object> execution = store.getExecution(projectId, locationId, workflowId, executionId);
        return formatExecution(execution, projectId, locationId, workflowId);
    }

    public Map<String, Object> getExecution(String projectId, String locationId,
                                             String workflowId, String executionId) throws SQLException {
        Map<String, Object> execution = store.getExecution(projectId, locationId, workflowId, executionId);
        if (execution == null) return null;
        return formatExecution(execution, projectId, locationId, workflowId);
    }

    public List<Map<String, Object>> listExecutions(String projectId, String locationId,
                                                     String workflowId, int pageSize) throws SQLException {
        List<Map<String, Object>> executions = store.listExecutions(projectId, locationId, workflowId, pageSize);
        List<Map<String, Object>> formatted = new ArrayList<>();
        for (Map<String, Object> e : executions) {
            formatted.add(formatExecution(e, projectId, locationId, workflowId));
        }
        return formatted;
    }

    public Map<String, Object> cancelExecution(String projectId, String locationId,
                                                String workflowId, String executionId) throws SQLException {
        Map<String, Object> execution = store.getExecution(projectId, locationId, workflowId, executionId);
        if (execution == null) throw new IllegalArgumentException("Execution not found: " + executionId);

        String state = String.valueOf(execution.get("state"));
        if ("SUCCEEDED".equals(state) || "FAILED".equals(state) || "CANCELLED".equals(state)) {
            throw new IllegalStateException("Cannot cancel execution in terminal state: " + state);
        }

        store.updateExecutionState(executionId, "CANCELLED", null, null);
        execution = store.getExecution(projectId, locationId, workflowId, executionId);
        return formatExecution(execution, projectId, locationId, workflowId);
    }

    // --- Execution runner ---

    private void runExecution(String executionId, String sourceContents, String argument) {
        try {
            // Transition to ACTIVE
            store.updateExecutionState(executionId, "ACTIVE", null, null);

            // Parse workflow
            WorkflowDefinition definition = WorkflowParser.parse(sourceContents);

            // Inject workflow env vars into context and SysFunctions
            Map<String, Object> initialVars = new LinkedHashMap<>();
            if (envVarsRepository != null) {
                try {
                    String projectId = store.getProjectIdForExecution(executionId);
                    if (projectId == null) projectId = "local-project";
                    String activePreset = envVarsRepository.getActivePreset(projectId);
                    Map<String, String> envVars = envVarsRepository.getEnvVarsForPreset(projectId, activePreset);
                    SysFunctions.setWorkflowEnvVars(envVars);
                    // Also inject as initial variables for ${VAR} template resolution
                    initialVars.putAll(envVars);
                } catch (Exception e) {
                    logger.warn("Failed to load workflow env vars, continuing without them: {}", e.getMessage());
                }
            }

            // Set up execution context with argument
            if (argument != null && !argument.isBlank() && !"null".equals(argument)) {
                try {
                    Object parsed = mapper.readValue(argument, Object.class);
                    if (parsed instanceof Map) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> argMap = (Map<String, Object>) parsed;
                        initialVars.putAll(argMap);
                    }
                    initialVars.put("args", parsed);
                } catch (Exception e) {
                    initialVars.put("args", argument);
                }
            }

            ExecutionContext context = new ExecutionContext(initialVars);

            // Register connector calls as stdlib functions
            for (String connectorPath : List.of(
                    "googleapis.storage.v1.objects.list", "googleapis.storage.v1.buckets.insert",
                    "googleapis.bigquery.v2.jobs.query", "googleapis.bigquery.v2.datasets.list",
                    "googleapis.pubsub.v1.projects.topics.publish",
                    "googleapis.secretmanager.v1.projects.secrets.list")) {
                if (connectorRegistry.has(connectorPath)) {
                    final String path = connectorPath;
                    stdlib.register(path, args -> {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> callArgs = args.isEmpty() ? Map.of() :
                                (args.get(0) instanceof Map ? (Map<String, Object>) args.get(0) : Map.of());
                        return connectorRegistry.execute(path, callArgs);
                    });
                }
            }

            // Execute
            WorkflowExecutor executor = new WorkflowExecutor(definition, context, stdlib);
            Object result = executor.execute();

            // Transition to SUCCEEDED
            String resultJson = result != null ? mapper.writeValueAsString(result) : "null";
            store.updateExecutionState(executionId, "SUCCEEDED", resultJson, null);
            logger.info("Workflow execution {} completed successfully", executionId);

        } catch (WorkflowException e) {
            try {
                String errorJson = mapper.writeValueAsString(e.toErrorMap());
                store.updateExecutionState(executionId, "FAILED", null, errorJson);
            } catch (Exception ex) {
                logger.error("Failed to update execution state for {}", executionId, ex);
            }
            logger.warn("Workflow execution {} failed: {}", executionId, e.getMessage());
        } catch (Exception e) {
            try {
                Map<String, Object> error = Map.of("code", "RuntimeError", "message", e.getMessage() != null ? e.getMessage() : "Unknown error");
                store.updateExecutionState(executionId, "FAILED", null, mapper.writeValueAsString(error));
            } catch (Exception ex) {
                logger.error("Failed to update execution state for {}", executionId, ex);
            }
            logger.error("Workflow execution {} crashed", executionId, e);
        }
    }

    // --- Response formatting ---

    private Map<String, Object> formatWorkflow(Map<String, Object> row, String projectId, String locationId) {
        Map<String, Object> result = new LinkedHashMap<>();
        String wfId = String.valueOf(row.get("workflow_id"));
        result.put("name", "projects/" + projectId + "/locations/" + locationId + "/workflows/" + wfId);
        result.put("state", row.getOrDefault("state", "ACTIVE"));
        result.put("revisionId", String.valueOf(row.getOrDefault("revision_id", 1)));
        result.put("sourceContents", row.get("source_contents"));
        if (row.get("service_account") != null) result.put("serviceAccount", row.get("service_account"));
        if (row.get("labels") != null) result.put("labels", row.get("labels"));
        if (row.get("created_at") != null) result.put("createTime", String.valueOf(row.get("created_at")));
        if (row.get("updated_at") != null) result.put("updateTime", String.valueOf(row.get("updated_at")));
        return result;
    }

    private Map<String, Object> formatExecution(Map<String, Object> row, String projectId, String locationId, String workflowId) {
        Map<String, Object> result = new LinkedHashMap<>();
        String execId = String.valueOf(row.get("execution_id"));
        result.put("name", "projects/" + projectId + "/locations/" + locationId + "/workflows/" + workflowId + "/executions/" + execId);
        result.put("state", row.getOrDefault("state", "QUEUED"));
        if (row.get("argument") != null) result.put("argument", row.get("argument"));
        if (row.get("result") != null) result.put("result", row.get("result"));
        if (row.get("error") != null) result.put("error", row.get("error"));
        if (row.get("start_time") != null) result.put("startTime", String.valueOf(row.get("start_time")));
        if (row.get("end_time") != null) result.put("endTime", String.valueOf(row.get("end_time")));
        if (row.get("workflow_revision_id") != null) result.put("workflowRevisionId", String.valueOf(row.get("workflow_revision_id")));
        return result;
    }
}
