package com.localcloud.emulators.workflows;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linecorp.armeria.common.AggregatedHttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.QueryParams;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.annotation.*;
import com.localcloud.config.LocalCloudConfig;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * REST endpoints for workflow environment variables and presets.
 * Registered at /_localcloud/workflows/env
 */
public class WorkflowEnvVarsService {
    private static final Logger logger = LoggerFactory.getLogger(WorkflowEnvVarsService.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    private final LocalCloudConfig config;
    private final WorkflowEnvVarsRepository repo;

    public WorkflowEnvVarsService(LocalCloudConfig config, WorkflowEnvVarsRepository repo) {
        this.config = config;
        this.repo = repo;
    }

    private String getProjectId(ServiceRequestContext ctx) {
        QueryParams params = ctx.queryParams();
        String p = params.get("project");
        return p != null ? p : config.getProjectId();
    }

    @Get("")
    public HttpResponse listEnvVars(ServiceRequestContext ctx) {
        try {
            String projectId = getProjectId(ctx);
            QueryParams params = ctx.queryParams();
            String preset = params.get("preset");
            boolean all = "true".equals(params.get("all"));

            if (all) {
                preset = null; // return all presets
            } else if (preset == null) {
                preset = repo.getActivePreset(projectId);
            }

            List<Map<String, Object>> vars = repo.listEnvVars(projectId, preset);
            return HttpResponse.of(HttpStatus.OK, MediaType.JSON, mapper.writeValueAsString(vars));
        } catch (Exception e) {
            logger.error("Failed to list env vars", e);
            return HttpResponse.of(HttpStatus.INTERNAL_SERVER_ERROR, MediaType.JSON,
                "{\"error\":\"" + e.getMessage() + "\"}");
        }
    }

    @Post("")
    public HttpResponse createEnvVar(ServiceRequestContext ctx, AggregatedHttpRequest request) {
        try {
            String projectId = getProjectId(ctx);
            @SuppressWarnings("unchecked")
            Map<String, Object> body = mapper.readValue(request.contentUtf8(), Map.class);
            String varName = (String) body.get("varName");
            String varValue = (String) body.getOrDefault("varValue", "");
            String preset = (String) body.getOrDefault("preset", repo.getActivePreset(projectId));

            if (varName == null || varName.isBlank()) {
                return HttpResponse.of(HttpStatus.BAD_REQUEST, MediaType.JSON,
                    "{\"error\":\"varName is required\"}");
            }

            Map<String, Object> created = repo.createEnvVar(projectId, varName, varValue, preset);
            return HttpResponse.of(HttpStatus.CREATED, MediaType.JSON, mapper.writeValueAsString(created));
        } catch (java.sql.SQLException e) {
            if (e.getMessage() != null && e.getMessage().contains("duplicate key")) {
                return HttpResponse.of(HttpStatus.CONFLICT, MediaType.JSON,
                    "{\"error\":\"Variable already exists for this preset. Use PUT to update.\"}");
            }
            logger.error("Failed to create env var", e);
            return HttpResponse.of(HttpStatus.INTERNAL_SERVER_ERROR, MediaType.JSON,
                "{\"error\":\"" + e.getMessage() + "\"}");
        } catch (Exception e) {
            logger.error("Failed to create env var", e);
            return HttpResponse.of(HttpStatus.INTERNAL_SERVER_ERROR, MediaType.JSON,
                "{\"error\":\"" + e.getMessage() + "\"}");
        }
    }

    @Put("/:varName")
    public HttpResponse updateEnvVar(ServiceRequestContext ctx, @Param("varName") String varName,
                                      AggregatedHttpRequest request) {
        try {
            String projectId = getProjectId(ctx);
            @SuppressWarnings("unchecked")
            Map<String, Object> body = mapper.readValue(request.contentUtf8(), Map.class);
            String varValue = (String) body.getOrDefault("varValue", "");
            String preset = (String) body.getOrDefault("preset", repo.getActivePreset(projectId));

            boolean updated = repo.updateEnvVar(projectId, varName, varValue, preset);
            if (!updated) {
                return HttpResponse.of(HttpStatus.NOT_FOUND, MediaType.JSON,
                    "{\"error\":\"Variable " + varName + " not found for preset " + preset + "\"}");
            }
            return HttpResponse.of(HttpStatus.OK, MediaType.JSON,
                mapper.writeValueAsString(Map.of("varName", varName, "varValue", varValue, "preset", preset)));
        } catch (Exception e) {
            logger.error("Failed to update env var", e);
            return HttpResponse.of(HttpStatus.INTERNAL_SERVER_ERROR, MediaType.JSON,
                "{\"error\":\"" + e.getMessage() + "\"}");
        }
    }

    @Delete("/:varName")
    public HttpResponse deleteEnvVar(ServiceRequestContext ctx, @Param("varName") String varName) {
        try {
            String projectId = getProjectId(ctx);
            QueryParams params = ctx.queryParams();
            String preset = params.get("preset");
            if (preset == null) preset = repo.getActivePreset(projectId);

            boolean deleted = repo.deleteEnvVar(projectId, varName, preset);
            if (!deleted) {
                return HttpResponse.of(HttpStatus.NOT_FOUND, MediaType.JSON,
                    "{\"error\":\"Variable " + varName + " not found for preset " + preset + "\"}");
            }
            return HttpResponse.of(HttpStatus.NO_CONTENT);
        } catch (Exception e) {
            logger.error("Failed to delete env var", e);
            return HttpResponse.of(HttpStatus.INTERNAL_SERVER_ERROR, MediaType.JSON,
                "{\"error\":\"" + e.getMessage() + "\"}");
        }
    }

    @Post("/bulk")
    public HttpResponse bulkUpsert(ServiceRequestContext ctx, AggregatedHttpRequest request) {
        try {
            String projectId = getProjectId(ctx);
            @SuppressWarnings("unchecked")
            List<Map<String, String>> vars = mapper.readValue(request.contentUtf8(), List.class);
            int count = repo.bulkUpsert(projectId, vars);
            return HttpResponse.of(HttpStatus.OK, MediaType.JSON,
                mapper.writeValueAsString(Map.of("upserted", count)));
        } catch (Exception e) {
            logger.error("Failed to bulk upsert env vars", e);
            return HttpResponse.of(HttpStatus.INTERNAL_SERVER_ERROR, MediaType.JSON,
                "{\"error\":\"" + e.getMessage() + "\"}");
        }
    }

    @Get("/presets")
    public HttpResponse listPresets(ServiceRequestContext ctx) {
        try {
            String projectId = getProjectId(ctx);
            List<Map<String, Object>> presets = repo.listPresets(projectId);
            String activePreset = repo.getActivePreset(projectId);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("activePreset", activePreset);
            result.put("presets", presets);
            return HttpResponse.of(HttpStatus.OK, MediaType.JSON, mapper.writeValueAsString(result));
        } catch (Exception e) {
            logger.error("Failed to list presets", e);
            return HttpResponse.of(HttpStatus.INTERNAL_SERVER_ERROR, MediaType.JSON,
                "{\"error\":\"" + e.getMessage() + "\"}");
        }
    }

    @Post("/presets/activate")
    public HttpResponse activatePreset(ServiceRequestContext ctx, AggregatedHttpRequest request) {
        try {
            String projectId = getProjectId(ctx);
            @SuppressWarnings("unchecked")
            Map<String, Object> body = mapper.readValue(request.contentUtf8(), Map.class);
            String preset = (String) body.get("preset");
            if (preset == null || preset.isBlank()) {
                return HttpResponse.of(HttpStatus.BAD_REQUEST, MediaType.JSON,
                    "{\"error\":\"preset is required\"}");
            }

            repo.setActivePreset(projectId, preset);
            return HttpResponse.of(HttpStatus.OK, MediaType.JSON,
                mapper.writeValueAsString(Map.of("activePreset", preset)));
        } catch (Exception e) {
            logger.error("Failed to activate preset", e);
            return HttpResponse.of(HttpStatus.INTERNAL_SERVER_ERROR, MediaType.JSON,
                "{\"error\":\"" + e.getMessage() + "\"}");
        }
    }
}
