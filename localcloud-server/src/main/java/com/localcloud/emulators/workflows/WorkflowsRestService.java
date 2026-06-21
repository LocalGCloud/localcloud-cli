package com.localcloud.emulators.workflows;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.annotation.Delete;
import com.linecorp.armeria.server.annotation.Get;
import com.linecorp.armeria.server.annotation.Param;
import com.linecorp.armeria.server.annotation.Post;
import com.localcloud.emulators.iam.IAMPolicyRestHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * REST endpoints for Workflows operations that are not present in the bundled
 * gRPC proto release or need explicit emulator behavior.
 */
public class WorkflowsRestService {
    private static final Logger logger = LoggerFactory.getLogger(WorkflowsRestService.class);

    private final WorkflowsServiceImpl service;
    private final WorkflowsEmulator emulator;
    private final ObjectMapper mapper = new ObjectMapper();
    private final IAMPolicyRestHandler iamHandler;

    public WorkflowsRestService(WorkflowsServiceImpl service, WorkflowsEmulator emulator) {
        this(service, emulator, null);
    }

    public WorkflowsRestService(WorkflowsServiceImpl service, WorkflowsEmulator emulator, IAMPolicyRestHandler iamHandler) {
        this.service = service;
        this.emulator = emulator;
        this.iamHandler = iamHandler;
    }

    @Get("/projects/{project}/locations/{location}/workflows/{workflow}/revisions")
    public HttpResponse listWorkflowRevisions(@Param String project,
                                              @Param String location,
                                              @Param String workflow) {
        emulator.incrementRequestCount();
        try {
            List<Map<String, Object>> revisions = service.listWorkflowRevisions(project, location, workflow);
            return jsonResponse(HttpStatus.OK, Map.of("workflows", revisions));
        } catch (Exception e) {
            logger.error("Failed to list workflow revisions", e);
            return errorResponse(500, e.getMessage());
        }
    }

    @Get("/projects/{project}/locations/{location}/workflows/{workflow}/executions/{execution}/stepEntries")
    public HttpResponse listStepEntries(ServiceRequestContext ctx,
                                        @Param String project,
                                        @Param String location,
                                        @Param String workflow,
                                        @Param String execution) {
        emulator.incrementRequestCount();
        try {
            int pageSize = parsePageSize(ctx.queryParams().get("pageSize"));
            List<Map<String, Object>> stepEntries =
                    service.listStepEntries(project, location, workflow, execution, pageSize);
            return jsonResponse(HttpStatus.OK, Map.of("stepEntries", stepEntries));
        } catch (Exception e) {
            logger.error("Failed to list step entries", e);
            return errorResponse(500, e.getMessage());
        }
    }

    @Get("/projects/{project}/locations/{location}/workflows/{workflow}/executions/{execution}/stepEntries/{stepEntry}")
    public HttpResponse getStepEntry(@Param String project,
                                     @Param String location,
                                     @Param String workflow,
                                     @Param String execution,
                                     @Param String stepEntry) {
        emulator.incrementRequestCount();
        try {
            Map<String, Object> result = service.getStepEntry(project, location, workflow, execution,
                    Long.parseLong(stepEntry));
            if (result == null) return errorResponse(404, "Step entry not found: " + stepEntry);
            return jsonResponse(HttpStatus.OK, result);
        } catch (NumberFormatException e) {
            return errorResponse(400, "Invalid step entry id: " + stepEntry);
        } catch (Exception e) {
            logger.error("Failed to get step entry", e);
            return errorResponse(500, e.getMessage());
        }
    }

    @Delete("/projects/{project}/locations/{location}/workflows/{workflow}/executions/{execution}/executionHistory")
    public HttpResponse deleteExecutionHistory(@Param String project,
                                               @Param String location,
                                               @Param String workflow,
                                               @Param String execution) {
        emulator.incrementRequestCount();
        try {
            return jsonResponse(HttpStatus.OK,
                    service.deleteExecutionHistory(project, location, workflow, execution));
        } catch (IllegalArgumentException e) {
            return errorResponse(404, e.getMessage());
        } catch (Exception e) {
            logger.error("Failed to delete execution history", e);
            return errorResponse(500, e.getMessage());
        }
    }

    @Get("regex:^/projects/(?<project>[^/]+)/locations/(?<location>[^/]+)/workflows/(?<workflow>[^/]+)/executions/(?<execution>[^/]+):exportData$")
    public HttpResponse exportExecutionData(@Param String project,
                                            @Param String location,
                                            @Param String workflow,
                                            @Param String execution) {
        emulator.incrementRequestCount();
        try {
            Map<String, Object> result = service.exportExecutionData(project, location, workflow, execution);
            if (result == null) return errorResponse(404, "Execution not found: " + execution);
            return jsonResponse(HttpStatus.OK, result);
        } catch (Exception e) {
            logger.error("Failed to export execution data", e);
            return errorResponse(500, e.getMessage());
        }
    }

    private int parsePageSize(String pageSize) {
        if (pageSize == null || pageSize.isBlank()) return 100;
        try {
            return Math.max(1, Math.min(1000, Integer.parseInt(pageSize)));
        } catch (NumberFormatException e) {
            return 100;
        }
    }

    private HttpResponse jsonResponse(HttpStatus status, Object value) {
        try {
            return HttpResponse.of(status, MediaType.JSON, mapper.writeValueAsString(value));
        } catch (Exception e) {
            return HttpResponse.of(HttpStatus.INTERNAL_SERVER_ERROR, MediaType.PLAIN_TEXT_UTF_8, e.getMessage());
        }
    }

    // IAM Policy endpoints are handled by the generic catch-all in LocalCloudApplication.

    // ── Workflow CRUD endpoints ─────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    @Post("/projects/{project}/locations/{location}/workflows")
    public HttpResponse createWorkflow(ServiceRequestContext ctx,
                                       @Param String project,
                                       @Param String location,
                                       String body) {
        emulator.incrementRequestCount();
        try {
            String workflowId = ctx.queryParams().get("workflowId");
            if (workflowId == null || workflowId.isBlank()) {
                logger.error("workflowId parameter is missing or blank. Query params: {}", ctx.queryParams());
                return errorResponse(400, "workflowId query parameter is required");
            }
            logger.info("Creating workflow with ID: {}, project: {}, location: {}", workflowId, project, location);

            Map<String, Object> workflow = mapper.readValue(body, Map.class);
            
            String sourceContents = (String) workflow.get("sourceContents");
            if (sourceContents == null || sourceContents.isBlank()) {
                return errorResponse(400, "sourceContents is required");
            }
            
            String description = (String) workflow.getOrDefault("description", "");
            String serviceAccount = (String) workflow.getOrDefault("serviceAccount", "");
            String callLogLevel = (String) workflow.getOrDefault("callLogLevel", "CALL_LOG_LEVEL_UNSPECIFIED");
            String executionHistoryLevel = (String) workflow.getOrDefault("executionHistoryLevel", "EXECUTION_HISTORY_LEVEL_UNSPECIFIED");
            String cryptoKeyName = (String) workflow.getOrDefault("cryptoKeyName", "");
            String labelsJson = mapper.writeValueAsString(workflow.getOrDefault("labels", Map.of()));
            String userEnvVarsJson = mapper.writeValueAsString(workflow.getOrDefault("userEnvVars", Map.of()));
            String tagsJson = mapper.writeValueAsString(workflow.getOrDefault("tags", Map.of()));
            
            Map<String, Object> result = service.createWorkflow(project, location, workflowId,
                    sourceContents, labelsJson, serviceAccount, description, callLogLevel,
                    executionHistoryLevel, cryptoKeyName, userEnvVarsJson, tagsJson);
            return jsonResponse(HttpStatus.OK, result);
        } catch (Exception e) {
            logger.error("Failed to create workflow", e);
            if (e.getMessage() != null && e.getMessage().contains("duplicate")) {
                return errorResponse(409, "Workflow already exists: " + e.getMessage());
            }
            return errorResponse(500, e.getMessage());
        }
    }

    @Get("/projects/{project}/locations/{location}/workflows/{workflow}")
    public HttpResponse getWorkflow(@Param String project,
                                    @Param String location,
                                    @Param String workflow) {
        emulator.incrementRequestCount();
        try {
            Map<String, Object> result = service.getWorkflow(project, location, workflow);
            if (result == null) {
                return errorResponse(404, "Workflow not found: " + workflow);
            }
            return jsonResponse(HttpStatus.OK, result);
        } catch (Exception e) {
            logger.error("Failed to get workflow", e);
            return errorResponse(500, e.getMessage());
        }
    }

    @Get("/projects/{project}/locations/{location}/operations/{operation}")
    public HttpResponse getOperation(@Param String project,
                                     @Param String location,
                                     @Param String operation) {
        emulator.incrementRequestCount();
        // Operation names are of form "create-{workflowId}", "update-{workflowId}", or "delete-{workflowId}"
        // The provider polls this after create/update/delete — return done with the workflow response
        try {
            Map<String, Object> doneOp = new LinkedHashMap<>();
            doneOp.put("name", "projects/" + project + "/locations/" + location + "/operations/" + operation);
            doneOp.put("done", true);

            // Include proper metadata with createTime and target
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("@type", "type.googleapis.com/google.cloud.workflows.v1.OperationMetadata");
            metadata.put("createTime", java.time.Instant.now().toString());
            metadata.put("apiVersion", "v1");

            if (operation.startsWith("create-")) {
                String workflowId = operation.substring("create-".length());
                metadata.put("target", "projects/" + project + "/locations/" + location + "/workflows/" + workflowId);
                metadata.put("verb", "create");
                doneOp.put("metadata", metadata);
                Map<String, Object> workflow = service.getWorkflow(project, location, workflowId);
                if (workflow != null) {
                    doneOp.put("response", workflow);
                }
            } else if (operation.startsWith("update-")) {
                String workflowId = operation.substring("update-".length());
                metadata.put("target", "projects/" + project + "/locations/" + location + "/workflows/" + workflowId);
                metadata.put("verb", "update");
                doneOp.put("metadata", metadata);
                Map<String, Object> workflow = service.getWorkflow(project, location, workflowId);
                if (workflow != null) {
                    doneOp.put("response", workflow);
                }
            } else if (operation.startsWith("delete-")) {
                String workflowId = operation.substring("delete-".length());
                metadata.put("target", "projects/" + project + "/locations/" + location + "/workflows/" + workflowId);
                metadata.put("verb", "delete");
                doneOp.put("metadata", metadata);
                doneOp.put("response", Map.of());
            } else {
                metadata.put("target", "projects/" + project + "/locations/" + location);
                doneOp.put("metadata", metadata);
            }
            return jsonResponse(HttpStatus.OK, doneOp);
        } catch (Exception e) {
            logger.error("Failed to get operation", e);
            return errorResponse(500, e.getMessage());
        }
    }

    @Delete("/projects/{project}/locations/{location}/workflows/{workflow}")
    public HttpResponse deleteWorkflow(@Param String project,
                                       @Param String location,
                                       @Param String workflow) {
        emulator.incrementRequestCount();
        try {
            Map<String, Object> result = service.deleteWorkflow(project, location, workflow);
            return jsonResponse(HttpStatus.OK, result);
        } catch (IllegalArgumentException e) {
            return errorResponse(404, e.getMessage());
        } catch (Exception e) {
            logger.error("Failed to delete workflow", e);
            return errorResponse(500, e.getMessage());
        }
    }

    @Post("regex:^/projects/(?<project>[^/]+)/locations/(?<location>[^/]+)/workflows/(?<workflow>[^/]+):undelete$")
    public HttpResponse undeleteWorkflow(@Param String project,
                                          @Param String location,
                                          @Param String workflow) {
        emulator.incrementRequestCount();
        try {
            Map<String, Object> result = service.undeleteWorkflow(project, location, workflow);
            return jsonResponse(HttpStatus.OK, result);
        } catch (IllegalArgumentException e) {
            return errorResponse(404, e.getMessage());
        } catch (Exception e) {
            logger.error("Failed to undelete workflow", e);
            return errorResponse(500, e.getMessage());
        }
    }

    @Get("/projects/{project}/locations/{location}/workflows")
    public HttpResponse listWorkflows(ServiceRequestContext ctx,
                                      @Param String project,
                                      @Param String location) {
        emulator.incrementRequestCount();
        try {
            String pageSizeParam = ctx.queryParams().get("pageSize");
            int pageSize = 100; // Default page size
            if (pageSizeParam != null && !pageSizeParam.isBlank()) {
                try {
                    pageSize = Integer.parseInt(pageSizeParam);
                } catch (NumberFormatException e) {
                    // Use default
                }
            }
            
            List<Map<String, Object>> workflows = service.listWorkflows(project, location, pageSize);
            return jsonResponse(HttpStatus.OK, Map.of("workflows", workflows));
        } catch (Exception e) {
            logger.error("Failed to list workflows", e);
            return errorResponse(500, e.getMessage());
        }
    }

    private HttpResponse errorResponse(int code, String message) {
        try {
            ObjectNode error = mapper.createObjectNode();
            ObjectNode inner = mapper.createObjectNode();
            inner.put("code", code);
            inner.put("message", message != null ? message : "Internal error");
            error.set("error", inner);
            return HttpResponse.of(HttpStatus.valueOf(code), MediaType.JSON, mapper.writeValueAsString(error));
        } catch (Exception e) {
            return HttpResponse.of(HttpStatus.INTERNAL_SERVER_ERROR, MediaType.PLAIN_TEXT_UTF_8,
                    message != null ? message : "Internal error");
        }
    }
}
