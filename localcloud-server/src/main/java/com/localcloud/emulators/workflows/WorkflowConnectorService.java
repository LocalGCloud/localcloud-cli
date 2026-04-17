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
 * REST endpoints for the remote workflow source connector.
 * Handles connection, listing workflows, discovering services, and importing.
 * Registered at /_localcloud/workflow
 */
public class WorkflowConnectorService {
    private static final Logger logger = LoggerFactory.getLogger(WorkflowConnectorService.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    private final LocalCloudConfig config;
    private final WorkflowEnvVarsRepository envVarsRepo;
    private final WorkflowsStore workflowsStore;

    public WorkflowConnectorService(LocalCloudConfig config, WorkflowEnvVarsRepository envVarsRepo,
                                     WorkflowsStore workflowsStore) {
        this.config = config;
        this.envVarsRepo = envVarsRepo;
        this.workflowsStore = workflowsStore;
    }

    private String getProjectId(ServiceRequestContext ctx) {
        QueryParams params = ctx.queryParams();
        String p = params.get("project");
        return p != null ? p : config.getProjectId();
    }

    @Get("/connect")
    public HttpResponse getConnectionStatus(ServiceRequestContext ctx) {
        try {
            String projectId = getProjectId(ctx);
            String url = envVarsRepo.getConfig(projectId, "source_url");
            String username = envVarsRepo.getConfig(projectId, "source_username");
            if (url != null && username != null) {
                return HttpResponse.of(HttpStatus.OK, MediaType.JSON,
                    mapper.writeValueAsString(Map.of("connected", true, "url", url, "username", username)));
            }
            return HttpResponse.of(HttpStatus.OK, MediaType.JSON,
                mapper.writeValueAsString(Map.of("connected", false)));
        } catch (Exception e) {
            return HttpResponse.of(HttpStatus.INTERNAL_SERVER_ERROR, MediaType.JSON,
                "{\"error\":\"" + e.getMessage() + "\"}");
        }
    }

    @Post("/connect")
    public HttpResponse connect(ServiceRequestContext ctx, AggregatedHttpRequest request) {
        try {
            String projectId = getProjectId(ctx);
            @SuppressWarnings("unchecked")
            Map<String, Object> body = mapper.readValue(request.contentUtf8(), Map.class);
            String url = (String) body.get("url");
            String username = (String) body.get("username");

            if (url == null || url.isBlank() || username == null || username.isBlank()) {
                return HttpResponse.of(HttpStatus.BAD_REQUEST, MediaType.JSON,
                    "{\"error\":\"url and username are required\"}");
            }

            // Validate connection
            try {
                RemoteSourceClient client = new RemoteSourceClient(url, username);
                int envCount = client.validateConnection();
                // Store connection config
                envVarsRepo.setConfig(projectId, "source_url", url);
                envVarsRepo.setConfig(projectId, "source_username", username);

                return HttpResponse.of(HttpStatus.OK, MediaType.JSON,
                    mapper.writeValueAsString(Map.of("connected", true, "envCount", envCount)));
            } catch (Exception e) {
                return HttpResponse.of(HttpStatus.UNPROCESSABLE_ENTITY, MediaType.JSON,
                    mapper.writeValueAsString(Map.of("error",
                        "Cannot connect to remote source at " + url + ": " + e.getMessage())));
            }
        } catch (Exception e) {
            return HttpResponse.of(HttpStatus.INTERNAL_SERVER_ERROR, MediaType.JSON,
                "{\"error\":\"" + e.getMessage() + "\"}");
        }
    }

    @Get("/workflows")
    public HttpResponse listWorkflows(ServiceRequestContext ctx) {
        try {
            String projectId = getProjectId(ctx);
            String url = envVarsRepo.getConfig(projectId, "source_url");
            String username = envVarsRepo.getConfig(projectId, "source_username");

            if (url == null || username == null) {
                return HttpResponse.of(HttpStatus.CONFLICT, MediaType.JSON,
                    "{\"error\":\"No remote source connection configured. Call POST /_localcloud/workflow/connect first.\"}");
            }

            RemoteSourceClient client = new RemoteSourceClient(url, username);
            List<Map<String, Object>> workflows = client.listWorkflows();

            // Check which are already imported
            List<Map<String, Object>> existing = workflowsStore.listWorkflows(projectId, "us-central1", 1000);
            Set<String> importedNames = new HashSet<>();
            for (Map<String, Object> w : existing) {
                importedNames.add(String.valueOf(w.get("workflow_id")));
            }

            List<Map<String, Object>> result = new ArrayList<>();
            for (Map<String, Object> wf : workflows) {
                Map<String, Object> entry = new LinkedHashMap<>(wf);
                String name = (String) wf.get("name");
                entry.put("alreadyImported", importedNames.contains(name));
                result.add(entry);
            }

            return HttpResponse.of(HttpStatus.OK, MediaType.JSON, mapper.writeValueAsString(result));
        } catch (Exception e) {
            logger.error("Failed to list remote workflows", e);
            return HttpResponse.of(HttpStatus.BAD_GATEWAY, MediaType.JSON,
                "{\"error\":\"Remote API error: " + e.getMessage() + "\"}");
        }
    }

    @Get("/services")
    public HttpResponse listServices(ServiceRequestContext ctx) {
        try {
            String projectId = getProjectId(ctx);
            String url = envVarsRepo.getConfig(projectId, "source_url");
            String username = envVarsRepo.getConfig(projectId, "source_username");

            if (url == null || username == null) {
                return HttpResponse.of(HttpStatus.CONFLICT, MediaType.JSON,
                    "{\"error\":\"No remote source connection configured.\"}");
            }

            RemoteSourceClient client = new RemoteSourceClient(url, username);
            List<Map<String, Object>> services = client.getServicesForUser();
            return HttpResponse.of(HttpStatus.OK, MediaType.JSON, mapper.writeValueAsString(services));
        } catch (Exception e) {
            logger.error("Failed to list remote services", e);
            return HttpResponse.of(HttpStatus.BAD_GATEWAY, MediaType.JSON,
                "{\"error\":\"Remote API error: " + e.getMessage() + "\"}");
        }
    }

    @Post("/import")
    public HttpResponse importWorkflow(ServiceRequestContext ctx, AggregatedHttpRequest request) {
        try {
            String projectId = getProjectId(ctx);
            String sourceUrl = envVarsRepo.getConfig(projectId, "source_url");
            String username = envVarsRepo.getConfig(projectId, "source_username");

            if (sourceUrl == null || username == null) {
                return HttpResponse.of(HttpStatus.CONFLICT, MediaType.JSON,
                    "{\"error\":\"No remote source connection configured.\"}");
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> body = mapper.readValue(request.contentUtf8(), Map.class);
            String workflowName = (String) body.get("name");
            if (workflowName == null || workflowName.isBlank()) {
                return HttpResponse.of(HttpStatus.BAD_REQUEST, MediaType.JSON,
                    "{\"error\":\"name is required\"}");
            }

            // Fetch workflow source
            RemoteSourceClient client = new RemoteSourceClient(sourceUrl, username);
            String yaml = client.getWorkflowSource(workflowName);

            // Detect and rewrite URLs
            List<WorkflowUrlRewriter.UrlMatch> matches = WorkflowUrlRewriter.detect(yaml);
            String rewrittenYaml = WorkflowUrlRewriter.rewrite(yaml);

            // Generate env var entries
            List<Map<String, String>> envVarEntries = WorkflowUrlRewriter.generateEnvVarEntries(matches);

            // Store workflow
            workflowsStore.upsertWorkflow(projectId, "us-central1", workflowName, rewrittenYaml);

            // Upsert env vars
            envVarsRepo.bulkUpsert(projectId, envVarEntries);

            // Build rewrite summary
            List<Map<String, String>> rewrites = new ArrayList<>();
            for (WorkflowUrlRewriter.UrlMatch match : matches) {
                rewrites.add(Map.of(
                    "originalUrl", match.fullUrl,
                    "variableName", "${" + WorkflowUrlRewriter.toVarName(match.serviceName) + "}",
                    "pathSuffix", match.pathSuffix
                ));
            }

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("workflow", workflowName);
            result.put("imported", true);
            result.put("urlRewrites", rewrites);
            result.put("envVarsCreated", envVarEntries.size());

            return HttpResponse.of(HttpStatus.OK, MediaType.JSON, mapper.writeValueAsString(result));
        } catch (Exception e) {
            logger.error("Failed to import workflow", e);
            return HttpResponse.of(HttpStatus.INTERNAL_SERVER_ERROR, MediaType.JSON,
                "{\"error\":\"Import failed: " + e.getMessage() + "\"}");
        }
    }
}
