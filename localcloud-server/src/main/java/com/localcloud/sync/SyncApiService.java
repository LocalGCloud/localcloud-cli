package com.localcloud.sync;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.linecorp.armeria.common.AggregatedHttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.annotation.Delete;
import com.linecorp.armeria.server.annotation.Get;
import com.linecorp.armeria.server.annotation.Param;
import com.linecorp.armeria.server.annotation.Post;
import com.localcloud.config.LocalCloudConfig;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Armeria annotated service providing Data Mirror sync REST endpoints.
 * Registered at the {@code /_localcloud/sync} path prefix.
 *
 * <p>Endpoints cover:
 * <ul>
 *   <li>Auth — connect/disconnect GCP credentials, check status</li>
 *   <li>Browse — list remote resources and preview rows</li>
 *   <li>Sync — estimate costs and execute data sync</li>
 *   <li>Manifests — view sync history and delete synced data</li>
 * </ul>
 */
public class SyncApiService {

    private static final Logger logger = LoggerFactory.getLogger(SyncApiService.class);

    private final SyncService syncService;
    private final SyncCredentialRepository credentialRepo;
    private final LocalCloudConfig config;
    private final ObjectMapper mapper;

    public SyncApiService(SyncService syncService, SyncCredentialRepository credentialRepo,
                          LocalCloudConfig config) {
        this.syncService = syncService;
        this.credentialRepo = credentialRepo;
        this.config = config;
        this.mapper = new ObjectMapper();
        this.mapper.registerModule(new JavaTimeModule());
        this.mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    // -----------------------------------------------------------------------
    // Auth endpoints
    // -----------------------------------------------------------------------

    /**
     * Get credential connection status (no secrets exposed).
     */
    @Get("/auth/status")
    public HttpResponse authStatus(ServiceRequestContext ctx) {
        try {
            String project = resolveProject(ctx);
            Map<String, String> status = credentialRepo.getStatus(project);
            if (status == null) {
                Map<String, Object> disconnected = new LinkedHashMap<>();
                disconnected.put("connected", false);
                return jsonResponse(disconnected);
            }
            return jsonResponse(status);
        } catch (Exception e) {
            logger.error("Error getting auth status", e);
            return errorResponse(e);
        }
    }

    /**
     * Save credentials to connect to a remote GCP project.
     * Expects JSON body: {@code {source_project, auth_method, credential_data}}.
     */
    @Post("/auth/connect")
    public HttpResponse authConnect(ServiceRequestContext ctx, AggregatedHttpRequest request) {
        try {
            String project = resolveProject(ctx);
            @SuppressWarnings("unchecked")
            Map<String, Object> body = mapper.readValue(request.contentUtf8(), Map.class);

            String sourceProject = (String) body.get("source_project");
            String authMethod = (String) body.getOrDefault("auth_method", "oauth");
            Object credentialDataObj = body.get("credential_data");
            String credentialData;
            if (credentialDataObj instanceof String s) {
                credentialData = s;
            } else {
                credentialData = mapper.writeValueAsString(credentialDataObj);
            }

            if (sourceProject == null || sourceProject.isBlank()) {
                Map<String, Object> error = Map.of("error", true, "message", "source_project is required");
                return HttpResponse.of(HttpStatus.BAD_REQUEST, MediaType.JSON,
                        mapper.writeValueAsString(error));
            }

            credentialRepo.save(project, sourceProject, authMethod, credentialData);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("connected", true);
            result.put("source_project", sourceProject);
            result.put("auth_method", authMethod);
            return jsonResponse(result);
        } catch (Exception e) {
            logger.error("Error connecting credentials", e);
            return errorResponse(e);
        }
    }

    /**
     * Clear credentials for the current project.
     */
    @Post("/auth/disconnect")
    public HttpResponse authDisconnect(ServiceRequestContext ctx) {
        try {
            String project = resolveProject(ctx);
            credentialRepo.delete(project);
            Map<String, Object> result = Map.of("connected", false, "disconnected", true);
            return jsonResponse(result);
        } catch (Exception e) {
            logger.error("Error disconnecting credentials", e);
            return errorResponse(e);
        }
    }

    // -----------------------------------------------------------------------
    // Browse remote endpoints
    // -----------------------------------------------------------------------

    /**
     * List remote resources for a service (e.g., BigQuery datasets/tables).
     */
    @Get("/{service}/browse")
    public HttpResponse browseRemote(ServiceRequestContext ctx, @Param("service") String service) {
        try {
            String project = resolveProject(ctx);
            BrowseResult result = syncService.browseRemote(project, service);
            return jsonResponse(result);
        } catch (Exception e) {
            logger.error("Error browsing remote {} resources", service, e);
            return errorResponse(e);
        }
    }

    /**
     * Preview rows from a remote resource.
     * Query params: {@code resource} (required), {@code limit} (default 10).
     */
    @Get("/{service}/preview")
    public HttpResponse previewRemote(ServiceRequestContext ctx, @Param("service") String service) {
        try {
            String project = resolveProject(ctx);
            String resource = ctx.queryParams().get("resource");
            int limit = ctx.queryParams().getInt("limit", 10);

            if (resource == null || resource.isBlank()) {
                Map<String, Object> error = Map.of("error", true, "message", "resource query param is required");
                return HttpResponse.of(HttpStatus.BAD_REQUEST, MediaType.JSON,
                        mapper.writeValueAsString(error));
            }

            PreviewResult result = syncService.previewRemote(project, service, resource, limit);
            return jsonResponse(result);
        } catch (Exception e) {
            logger.error("Error previewing remote {} resource", service, e);
            return errorResponse(e);
        }
    }

    // -----------------------------------------------------------------------
    // Sync operation endpoints
    // -----------------------------------------------------------------------

    /**
     * Dry-run cost estimate for a sync operation.
     * Expects JSON body: {@code {resource, source_project, filters[], row_limit}}.
     */
    @Post("/{service}/estimate")
    public HttpResponse estimate(ServiceRequestContext ctx, @Param("service") String service,
                                  AggregatedHttpRequest request) {
        try {
            String project = resolveProject(ctx);
            @SuppressWarnings("unchecked")
            Map<String, Object> body = mapper.readValue(request.contentUtf8(), Map.class);

            String resource = (String) body.get("resource");
            String sourceProject = (String) body.get("source_project");
            List<SyncFilter> filters = parseFilters(body.get("filters"));
            int rowLimit = body.containsKey("row_limit") ? ((Number) body.get("row_limit")).intValue() : 0;

            CostEstimate estimate = syncService.estimate(project, service, sourceProject,
                    resource, filters, rowLimit);
            return jsonResponse(estimate);
        } catch (Exception e) {
            logger.error("Error estimating sync for {}", service, e);
            return errorResponse(e);
        }
    }

    /**
     * Execute a sync operation.
     * Expects JSON body: {@code {resource, source_project, filters[], row_limit}}.
     */
    @Post("/{service}/start")
    public HttpResponse startSync(ServiceRequestContext ctx, @Param("service") String service,
                                   AggregatedHttpRequest request) {
        try {
            String project = resolveProject(ctx);
            @SuppressWarnings("unchecked")
            Map<String, Object> body = mapper.readValue(request.contentUtf8(), Map.class);

            String resource = (String) body.get("resource");
            String sourceProject = (String) body.get("source_project");
            List<SyncFilter> filters = parseFilters(body.get("filters"));
            int rowLimit = body.containsKey("row_limit") ? ((Number) body.get("row_limit")).intValue() : 0;

            SyncResult result = syncService.startSync(project, service, sourceProject,
                    resource, filters, rowLimit, null);
            return jsonResponse(result);
        } catch (Exception e) {
            logger.error("Error starting sync for {}", service, e);
            return errorResponse(e);
        }
    }

    // -----------------------------------------------------------------------
    // Manifest endpoints
    // -----------------------------------------------------------------------

    /**
     * Get all sync manifests (history) for the current project.
     */
    @Get("/manifests")
    public HttpResponse allManifests(ServiceRequestContext ctx) {
        try {
            String project = resolveProject(ctx);
            List<Map<String, Object>> manifests = syncService.getManifests(project);
            return jsonResponse(Map.of("manifests", manifests));
        } catch (Exception e) {
            logger.error("Error getting manifests", e);
            return errorResponse(e);
        }
    }

    /**
     * Get sync manifests for a specific service.
     */
    @Get("/{service}/manifests")
    public HttpResponse serviceManifests(ServiceRequestContext ctx, @Param("service") String service) {
        try {
            String project = resolveProject(ctx);
            List<Map<String, Object>> manifests = syncService.getManifests(project, service);
            return jsonResponse(Map.of("manifests", manifests));
        } catch (Exception e) {
            logger.error("Error getting manifests for {}", service, e);
            return errorResponse(e);
        }
    }

    /**
     * Delete a sync manifest (and optionally the synced data).
     */
    @Delete("/manifests/{id}")
    public HttpResponse deleteManifest(@Param("id") int id) {
        try {
            syncService.deleteManifest(id);
            return jsonResponse(Map.of("deleted", true, "id", id));
        } catch (Exception e) {
            logger.error("Error deleting manifest {}", id, e);
            return errorResponse(e);
        }
    }

    // -----------------------------------------------------------------------
    // Helpers (same patterns as AdminApiService)
    // -----------------------------------------------------------------------

    private String resolveProject(ServiceRequestContext ctx) {
        String project = ctx.queryParams().get("project");
        return (project != null && !project.isBlank()) ? project : config.getProjectId();
    }

    @SuppressWarnings("unchecked")
    private List<SyncFilter> parseFilters(Object filtersObj) {
        if (filtersObj == null) return List.of();
        List<Map<String, String>> raw = (List<Map<String, String>>) filtersObj;
        return raw.stream().map(f -> new SyncFilter(
                f.get("column"), f.get("operator"), f.get("value"),
                f.getOrDefault("columnType", "STRING")
        )).toList();
    }

    private HttpResponse jsonResponse(Object data) {
        try {
            return HttpResponse.of(HttpStatus.OK, MediaType.JSON,
                    mapper.writeValueAsString(data));
        } catch (Exception e) {
            return HttpResponse.of(HttpStatus.INTERNAL_SERVER_ERROR, MediaType.JSON,
                    "{\"error\":true,\"message\":\"Failed to serialize response\"}");
        }
    }

    private HttpResponse errorResponse(Exception e) {
        String message = e.getMessage() != null ? e.getMessage().replace("\"", "'") : "Unknown error";
        return HttpResponse.of(HttpStatus.INTERNAL_SERVER_ERROR, MediaType.JSON,
                "{\"error\":true,\"message\":\"" + message + "\"}");
    }
}
