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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

    public WorkflowsRestService(WorkflowsServiceImpl service, WorkflowsEmulator emulator) {
        this.service = service;
        this.emulator = emulator;
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
