package com.localcloud.admin;

import java.time.Instant;
import java.util.ArrayList;
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
import com.linecorp.armeria.common.QueryParams;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.annotation.Delete;
import com.linecorp.armeria.server.annotation.Get;
import com.linecorp.armeria.server.annotation.Param;
import com.linecorp.armeria.server.annotation.Post;
import com.linecorp.armeria.server.annotation.Put;
import com.localcloud.config.LocalCloudConfig;
import com.localcloud.config.ServiceRegistry;
import com.localcloud.config.ServiceRegistry.ServiceDefinition;
import com.localcloud.gateway.RequestLogger;
import com.localcloud.gateway.RequestLogger.RequestLogEntry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Armeria annotated service providing admin API endpoints for the LocalCloud
 * server. Registered at the {@code /_localcloud} path prefix alongside the
 * health check service.
 */
public class AdminApiService {

    private static final Logger logger = LoggerFactory.getLogger(AdminApiService.class);
    private static final int DEFAULT_REQUEST_LIMIT = 100;
    private static final int MAX_REQUEST_LIMIT = 1000;

    private final LocalCloudConfig config;
    private final RequestLogger requestLogger;
    private final ProjectService projectService;
    private final ServiceRoutingRepository routingRepository;
    private final CredentialBroker credentialBroker;
    private final ServiceConfigRepository serviceConfigRepository;
    private final SupervisorClient supervisorClient;
    private final ObjectMapper mapper;

    public AdminApiService(LocalCloudConfig config, RequestLogger requestLogger,
                           ProjectService projectService, ServiceRoutingRepository routingRepository,
                           CredentialBroker credentialBroker, ServiceConfigRepository serviceConfigRepository) {
        this.config = config;
        this.requestLogger = requestLogger;
        this.projectService = projectService;
        this.routingRepository = routingRepository;
        this.credentialBroker = credentialBroker;
        this.serviceConfigRepository = serviceConfigRepository;
        this.supervisorClient = new SupervisorClient();
        this.mapper = new ObjectMapper();
        this.mapper.registerModule(new JavaTimeModule());
        this.mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    /**
     * Return environment variables for all enabled emulator services.
     * Supports three output formats via the {@code format} query parameter:
     * <ul>
     *   <li>{@code shell} (default) - {@code export KEY=VALUE} lines</li>
     *   <li>{@code json} - JSON object mapping variable names to values</li>
     *   <li>{@code docker-compose} - YAML snippet suitable for a compose file</li>
     * </ul>
     */
    @Get("/env")
    public HttpResponse env(ServiceRequestContext ctx) {
        try {
            QueryParams params = ctx.queryParams();
            String format = params.get("format", "shell");

            // Build env vars from service registry, only for enabled services
            Map<String, String> envVars = new LinkedHashMap<>();
            ServiceRegistry registry = config.getServiceRegistry();
            for (Map.Entry<String, ServiceDefinition> entry : registry.getAllServices().entrySet()) {
                String service = entry.getKey();
                if (config.isServiceEnabled(service)) {
                    ServiceDefinition def = entry.getValue();
                    envVars.put(def.envVar(), def.envValue("localhost"));
                }
            }

            // Resolve project ID: use ?project= query param if provided, else config default
            String projectParam = params.get("project");
            String projectId = (projectParam != null && !projectParam.isBlank())
                    ? projectParam : config.getProjectId();

            // Always include the project ID (both standard names)
            envVars.put("GOOGLE_CLOUD_PROJECT", projectId);
            envVars.put("GCLOUD_PROJECT", projectId);

            // gcloud CLI support: add CLOUDSDK_* endpoint overrides for enabled services
            for (Map.Entry<String, ServiceDefinition> entry : registry.getAllServices().entrySet()) {
                String service = entry.getKey();
                if (config.isServiceEnabled(service)) {
                    ServiceDefinition def = entry.getValue();
                    String gcloudVar = def.gcloudEnvVar();
                    if (gcloudVar != null) {
                        envVars.put(gcloudVar, def.gcloudEndpoint("localhost"));
                    }
                }
            }
            envVars.put("CLOUDSDK_CORE_PROJECT", projectId);
            envVars.put("CLOUDSDK_AUTH_ACCESS_TOKEN", "localcloud-dev-token");

            return switch (format) {
                case "json" -> {
                    String json = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(envVars);
                    yield HttpResponse.of(HttpStatus.OK, MediaType.JSON, json);
                }
                case "docker-compose" -> {
                    StringBuilder sb = new StringBuilder();
                    sb.append("# docker-compose environment variables\n");
                    sb.append("environment:\n");
                    for (Map.Entry<String, String> e : envVars.entrySet()) {
                        sb.append("  ").append(e.getKey()).append(": \"")
                          .append(e.getValue()).append("\"\n");
                    }
                    yield HttpResponse.of(HttpStatus.OK, MediaType.PLAIN_TEXT_UTF_8, sb.toString());
                }
                default -> {
                    // shell format
                    StringBuilder sb = new StringBuilder();
                    for (Map.Entry<String, String> e : envVars.entrySet()) {
                        sb.append("export ").append(e.getKey()).append("=\"")
                          .append(e.getValue()).append("\"\n");
                    }
                    yield HttpResponse.of(HttpStatus.OK, MediaType.PLAIN_TEXT_UTF_8, sb.toString());
                }
            };
        } catch (Exception e) {
            logger.error("Error generating env output", e);
            return errorResponse(e);
        }
    }

    /**
     * Return recent request log entries. Supports optional query parameters:
     * <ul>
     *   <li>{@code service} - filter by emulator service name (e.g. {@code gcs})</li>
     *   <li>{@code limit} - max entries to return (default 100, max 1000)</li>
     *   <li>{@code since} - ISO-8601 timestamp; only return entries at or after this time</li>
     * </ul>
     */
    @Get("/requests")
    public HttpResponse requests(ServiceRequestContext ctx) {
        try {
            QueryParams params = ctx.queryParams();
            String service = params.get("service");
            int limit = Math.min(
                    params.getInt("limit", DEFAULT_REQUEST_LIMIT),
                    MAX_REQUEST_LIMIT
            );
            String sinceParam = params.get("since");

            List<RequestLogEntry> entries;
            if (sinceParam != null && !sinceParam.isEmpty()) {
                Instant since = Instant.parse(sinceParam);
                entries = requestLogger.getEntries(service, since, limit);
            } else {
                entries = requestLogger.getEntries(service, limit);
            }

            // Build response
            Map<String, Object> response = new LinkedHashMap<>();
            List<Map<String, Object>> requestList = new ArrayList<>();
            for (RequestLogEntry entry : entries) {
                Map<String, Object> req = new LinkedHashMap<>();
                req.put("id", entry.id());
                req.put("timestamp", entry.timestamp().toString());
                req.put("service", entry.service());
                req.put("method", entry.method());
                req.put("path", entry.path());
                req.put("status_code", entry.statusCode());
                req.put("duration_ms", entry.durationMs());
                req.put("request_size", entry.requestSize());
                req.put("response_size", entry.responseSize());
                requestList.add(req);
            }

            response.put("requests", requestList);
            response.put("total", requestLogger.getSize());
            response.put("has_more", entries.size() == limit && requestLogger.getSize() > limit);

            String json = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(response);
            return HttpResponse.of(HttpStatus.OK, MediaType.JSON, json);
        } catch (Exception e) {
            logger.error("Error retrieving request log", e);
            return errorResponse(e);
        }
    }

    /**
     * List all projects.
     */
    @Get("/projects")
    public HttpResponse listProjects() {
        try {
            var projects = projectService.listProjects();
            String json = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(projects);
            return HttpResponse.of(HttpStatus.OK, MediaType.JSON, json);
        } catch (Exception e) {
            logger.error("Error listing projects", e);
            return errorResponse(e);
        }
    }

    /**
     * Create a new project. Expects JSON body with {@code project_id} and
     * optional {@code display_name}.
     */
    @Post("/projects")
    public HttpResponse createProject(AggregatedHttpRequest request) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> body = mapper.readValue(
                    request.contentUtf8(), Map.class);
            String projectId = (String) body.get("project_id");
            String displayName = (String) body.getOrDefault("display_name", projectId);

            if (projectId == null || projectId.isBlank()) {
                Map<String, Object> error = Map.of(
                        "error", true,
                        "message", "project_id is required"
                );
                return HttpResponse.of(HttpStatus.BAD_REQUEST,
                        MediaType.JSON, mapper.writeValueAsString(error));
            }

            var project = projectService.createProject(projectId, displayName);
            String json = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(project);
            return HttpResponse.of(HttpStatus.CREATED, MediaType.JSON, json);
        } catch (Exception e) {
            logger.error("Error creating project", e);
            return errorResponse(e);
        }
    }

    /**
     * Delete a project and all associated data. The default project cannot be deleted.
     */
    @Delete("/projects/{id}")
    public HttpResponse deleteProject(@Param("id") String projectId) {
        try {
            projectService.deleteProject(projectId, config.getProjectId());
            Map<String, Object> result = Map.of(
                    "deleted", true,
                    "project_id", projectId
            );
            String json = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(result);
            return HttpResponse.of(HttpStatus.OK, MediaType.JSON, json);
        } catch (IllegalArgumentException e) {
            try {
                Map<String, Object> error = Map.of(
                        "error", true,
                        "message", e.getMessage()
                );
                return HttpResponse.of(HttpStatus.BAD_REQUEST,
                        MediaType.JSON, mapper.writeValueAsString(error));
            } catch (Exception ex) {
                return HttpResponse.of(HttpStatus.BAD_REQUEST,
                        MediaType.PLAIN_TEXT_UTF_8, e.getMessage());
            }
        } catch (Exception e) {
            logger.error("Error deleting project '{}'", projectId, e);
            return errorResponse(e);
        }
    }

    // --- Routing API ---

    /**
     * Return per-service routing status: auto-detected routing, configured mode,
     * port, and env var information.
     */
    @Get("/routing")
    public HttpResponse routing(ServiceRequestContext ctx) {
        try {
            QueryParams params = ctx.queryParams();
            String projectParam = params.get("project");
            String projectId = (projectParam != null && !projectParam.isBlank())
                    ? projectParam : config.getProjectId();

            Map<String, Map<String, String>> persisted = routingRepository.getAll(projectId);
            ServiceRegistry registry = config.getServiceRegistry();

            Map<String, Object> response = new LinkedHashMap<>();
            for (Map.Entry<String, ServiceDefinition> entry : registry.getAllServices().entrySet()) {
                String serviceId = entry.getKey();
                ServiceDefinition def = entry.getValue();
                boolean enabled = config.isServiceEnabled(serviceId);

                // Auto-detect routing based on whether service is enabled
                String routing;
                boolean emulatorRunning;
                boolean healthy;
                if (!enabled) {
                    routing = "cloud";
                    emulatorRunning = false;
                    healthy = false;
                } else {
                    // If enabled, assume running and healthy (detailed supervisord check deferred)
                    emulatorRunning = true;
                    healthy = true;
                    routing = "local";
                }

                // Get configured mode from DB (default to "local")
                Map<String, String> routingConfig = persisted.get(serviceId);
                String mode = routingConfig != null ? routingConfig.get("mode") : "local";

                Map<String, Object> serviceRouting = new LinkedHashMap<>();
                serviceRouting.put("emulatorRunning", emulatorRunning);
                serviceRouting.put("healthy", healthy);
                serviceRouting.put("routing", routing);
                serviceRouting.put("mode", mode);
                serviceRouting.put("port", def.port());
                serviceRouting.put("envVar", def.envVar());
                if ("remote".equals(mode) && routingConfig != null) {
                    serviceRouting.put("remote_project", routingConfig.get("remote_project"));
                    serviceRouting.put("remote_region", routingConfig.get("remote_region"));
                }
                response.put(serviceId, serviceRouting);
            }

            String json = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(response);
            return HttpResponse.of(HttpStatus.OK, MediaType.JSON, json);
        } catch (Exception e) {
            logger.error("Error getting routing status", e);
            return errorResponse(e);
        }
    }

    /**
     * Set routing mode for a specific service.
     */
    @Put("/routing/{service}")
    public HttpResponse setRouting(@Param("service") String serviceId, AggregatedHttpRequest request) {
        try {
            // Validate service exists
            ServiceRegistry registry = config.getServiceRegistry();
            if (!registry.getAllServices().containsKey(serviceId)) {
                Map<String, Object> error = Map.of("error", true, "message", "Unknown service: " + serviceId);
                return HttpResponse.of(HttpStatus.NOT_FOUND, MediaType.JSON, mapper.writeValueAsString(error));
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> body = mapper.readValue(request.contentUtf8(), Map.class);
            String mode = (String) body.get("mode");
            if (mode == null || (!mode.equals("local") && !mode.equals("remote"))) {
                Map<String, Object> error = Map.of("error", true, "message", "mode must be 'local' or 'remote'");
                return HttpResponse.of(HttpStatus.BAD_REQUEST, MediaType.JSON, mapper.writeValueAsString(error));
            }

            String remoteProject = (String) body.get("remote_project");
            String remoteRegion = (String) body.get("remote_region");
            String projectId = config.getProjectId();

            routingRepository.upsert(projectId, serviceId, mode, remoteProject, remoteRegion);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("service", serviceId);
            result.put("mode", mode);
            result.put("remote_project", remoteProject);
            result.put("remote_region", remoteRegion);
            return HttpResponse.of(HttpStatus.OK, MediaType.JSON,
                    mapper.writerWithDefaultPrettyPrinter().writeValueAsString(result));
        } catch (Exception e) {
            logger.error("Error setting routing for service '{}'", serviceId, e);
            return errorResponse(e);
        }
    }

    // --- Credentials API ---

    /**
     * Return the current GCP credential status.
     */
    @Get("/credentials")
    public HttpResponse credentials() {
        try {
            String json = mapper.writerWithDefaultPrettyPrinter()
                    .writeValueAsString(credentialBroker.getStatus());
            return HttpResponse.of(HttpStatus.OK, MediaType.JSON, json);
        } catch (Exception e) {
            logger.error("Error getting credential status", e);
            return errorResponse(e);
        }
    }

    // --- Service Enable/Disable API ---

    // Map service IDs to supervisord program names (external services only)
    private static final Map<String, String> SUPERVISOR_PROGRAM_NAMES = Map.of(
        "gcs", "fake-gcs-server",
        "pubsub", "pubsub-emulator",
        "firestore", "firestore-emulator",
        "bigtable", "bigtable-emulator",
        "spanner", "spanner-emulator",
        "bigquery", "bigquery-emulator"
    );

    /**
     * Enable an emulator service. External services are started via supervisord;
     * facade services are toggled in-memory.
     */
    @Post("/services/{id}/enable")
    public HttpResponse enableService(@Param("id") String serviceId) {
        try {
            ServiceRegistry registry = config.getServiceRegistry();
            ServiceDefinition def = registry.getAllServices().get(serviceId);
            if (def == null) {
                Map<String, Object> error = Map.of("error", true, "message", "Unknown service: " + serviceId);
                return HttpResponse.of(HttpStatus.NOT_FOUND, MediaType.JSON, mapper.writeValueAsString(error));
            }

            if (config.isServiceDynamicallyEnabled(serviceId)) {
                Map<String, Object> result = Map.of("service", serviceId, "status", "already_enabled");
                return HttpResponse.of(HttpStatus.OK, MediaType.JSON, mapper.writeValueAsString(result));
            }

            if ("external".equals(def.type())) {
                String programName = SUPERVISOR_PROGRAM_NAMES.get(serviceId);
                if (programName != null) {
                    boolean started = supervisorClient.startProcess(programName);
                    if (!started) {
                        logger.warn("Supervisor failed to start process '{}' for service '{}'", programName, serviceId);
                    }
                }
            }
            config.setServiceEnabled(serviceId, true);

            // Persist toggle state (best-effort)
            try {
                serviceConfigRepository.upsert(serviceId, true);
            } catch (Exception pe) {
                logger.warn("Failed to persist enable state for '{}': {}", serviceId, pe.getMessage());
            }

            Map<String, Object> result = Map.of("service", serviceId, "status", "enabled");
            return HttpResponse.of(HttpStatus.OK, MediaType.JSON,
                    mapper.writerWithDefaultPrettyPrinter().writeValueAsString(result));
        } catch (Exception e) {
            logger.error("Error enabling service '{}'", serviceId, e);
            return errorResponse(e);
        }
    }

    /**
     * Disable an emulator service. External services are stopped via supervisord;
     * facade services are toggled in-memory (new requests get 503).
     */
    @Post("/services/{id}/disable")
    public HttpResponse disableService(@Param("id") String serviceId) {
        try {
            ServiceRegistry registry = config.getServiceRegistry();
            ServiceDefinition def = registry.getAllServices().get(serviceId);
            if (def == null) {
                Map<String, Object> error = Map.of("error", true, "message", "Unknown service: " + serviceId);
                return HttpResponse.of(HttpStatus.NOT_FOUND, MediaType.JSON, mapper.writeValueAsString(error));
            }

            if (!config.isServiceDynamicallyEnabled(serviceId)) {
                Map<String, Object> result = Map.of("service", serviceId, "status", "already_disabled");
                return HttpResponse.of(HttpStatus.OK, MediaType.JSON, mapper.writeValueAsString(result));
            }

            if ("external".equals(def.type())) {
                String programName = SUPERVISOR_PROGRAM_NAMES.get(serviceId);
                if (programName != null) {
                    boolean stopped = supervisorClient.stopProcess(programName);
                    if (!stopped) {
                        logger.warn("Supervisor failed to stop process '{}' for service '{}'", programName, serviceId);
                    }
                }
            }
            config.setServiceEnabled(serviceId, false);

            // Persist toggle state (best-effort)
            try {
                serviceConfigRepository.upsert(serviceId, false);
            } catch (Exception pe) {
                logger.warn("Failed to persist disable state for '{}': {}", serviceId, pe.getMessage());
            }

            Map<String, Object> result = Map.of("service", serviceId, "status", "disabled");
            return HttpResponse.of(HttpStatus.OK, MediaType.JSON,
                    mapper.writerWithDefaultPrettyPrinter().writeValueAsString(result));
        } catch (Exception e) {
            logger.error("Error disabling service '{}'", serviceId, e);
            return errorResponse(e);
        }
    }

    /**
     * Return persisted service configuration with source information.
     */
    @Get("/config/services")
    public HttpResponse getServiceConfig() {
        try {
            Map<String, Object> result = new LinkedHashMap<>();
            for (String serviceId : config.getServiceRegistry().getAllServices().keySet()) {
                Map<String, Object> svcConfig = new LinkedHashMap<>();
                svcConfig.put("enabled", config.isServiceDynamicallyEnabled(serviceId));
                svcConfig.put("source", config.getConfigSource(serviceId));
                svcConfig.put("locked", "env".equals(config.getConfigSource(serviceId)));
                result.put(serviceId, svcConfig);
            }
            return HttpResponse.of(HttpStatus.OK, MediaType.JSON,
                    mapper.writerWithDefaultPrettyPrinter().writeValueAsString(result));
        } catch (Exception e) {
            logger.error("Error getting service config", e);
            return errorResponse(e);
        }
    }

    /**
     * Update persisted service configuration. Only affects services not locked by env vars.
     */
    @Put("/config/services")
    public HttpResponse updateServiceConfig(String body) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> updates = mapper.readValue(body, Map.class);
            for (Map.Entry<String, Object> entry : updates.entrySet()) {
                String serviceId = entry.getKey();
                Object val = entry.getValue();
                boolean enabled = val instanceof Boolean ? (Boolean) val : "true".equalsIgnoreCase(String.valueOf(val));

                if ("env".equals(config.getConfigSource(serviceId))) {
                    continue; // Skip env-locked services
                }

                config.setServiceEnabled(serviceId, enabled);
                serviceConfigRepository.upsert(serviceId, enabled);

                // Start/stop external services via supervisord
                ServiceDefinition def = config.getServiceRegistry().getAllServices().get(serviceId);
                if (def != null && "external".equals(def.type())) {
                    String programName = SUPERVISOR_PROGRAM_NAMES.get(serviceId);
                    if (programName != null) {
                        try {
                            if (enabled) {
                                supervisorClient.startProcess(programName);
                            } else {
                                supervisorClient.stopProcess(programName);
                            }
                        } catch (Exception se) {
                            logger.warn("Failed to {} supervisor process '{}': {}",
                                    enabled ? "start" : "stop", programName, se.getMessage());
                        }
                    }
                }
            }
            return getServiceConfig(); // Return updated state
        } catch (Exception e) {
            logger.error("Error updating service config", e);
            return errorResponse(e);
        }
    }

    private HttpResponse errorResponse(Exception e) {
        try {
            Map<String, Object> error = Map.of(
                    "error", true,
                    "message", e.getMessage() != null ? e.getMessage() : "Unknown error"
            );
            return HttpResponse.of(HttpStatus.INTERNAL_SERVER_ERROR,
                    MediaType.JSON, mapper.writeValueAsString(error));
        } catch (Exception ex) {
            return HttpResponse.of(HttpStatus.INTERNAL_SERVER_ERROR,
                    MediaType.PLAIN_TEXT_UTF_8, "Internal server error");
        }
    }
}
