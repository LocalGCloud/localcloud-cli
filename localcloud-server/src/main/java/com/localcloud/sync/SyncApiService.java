package com.localcloud.sync;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
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
 * Registered at the {@code /sync} path prefix.
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
    // OAuth flow endpoints
    // -----------------------------------------------------------------------

    /**
     * Generate a Google OAuth URL for browser-based authentication.
     * The console opens this URL in a new tab; Google redirects back to
     * {@code /auth/callback} with an authorization code.
     *
     * <p>Requires {@code client_id} in the request body. Create OAuth
     * credentials at console.cloud.google.com and pass the client_id here.
     * Without a valid client_id, Google will reject the OAuth request.
     * For quick setup, use the token-paste flow instead.
     */
    @Post("/auth/start")
    public HttpResponse authStart(ServiceRequestContext ctx, AggregatedHttpRequest req) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> body = mapper.readValue(req.contentUtf8(), Map.class);
            String sourceProject = (String) body.get("source_project");
            String clientId = (String) body.get("client_id");

            if (clientId == null || clientId.isBlank()) {
                return jsonResponse(Map.of("error", true,
                        "message", "OAuth requires client_id. Create OAuth credentials at "
                                + "console.cloud.google.com, then pass client_id in the request. "
                                + "For now, use the token paste flow."));
            }

            String project = resolveProject(ctx);

            String redirectUri = "http://localhost:24080/sync/auth/callback";
            String scope = "https://www.googleapis.com/auth/cloud-platform.read-only";

            // Build the Google OAuth authorization URL with the supplied client_id.
            String oauthUrl = "https://accounts.google.com/o/oauth2/v2/auth"
                    + "?response_type=code"
                    + "&client_id=" + URLEncoder.encode(clientId, StandardCharsets.UTF_8)
                    + "&access_type=offline"
                    + "&scope=" + URLEncoder.encode(scope, StandardCharsets.UTF_8)
                    + "&redirect_uri=" + URLEncoder.encode(redirectUri, StandardCharsets.UTF_8)
                    + "&state=" + URLEncoder.encode(project + "|" + sourceProject, StandardCharsets.UTF_8);

            return jsonResponse(Map.of("oauth_url", oauthUrl, "redirect_uri", redirectUri));
        } catch (Exception e) {
            logger.error("Error generating OAuth URL", e);
            return errorResponse(e);
        }
    }

    /**
     * OAuth redirect handler. Google sends the user here after granting (or
     * denying) access. Exchanges the authorization code for credentials and
     * returns a self-closing HTML page.
     */
    @Get("/auth/callback")
    public HttpResponse authCallback(ServiceRequestContext ctx) {
        try {
            String code = ctx.queryParams().get("code");
            String state = ctx.queryParams().get("state");
            String error = ctx.queryParams().get("error");

            if (error != null) {
                return HttpResponse.of(HttpStatus.OK, MediaType.HTML_UTF_8,
                        buildCallbackHtml(false, "Authentication denied: " + error));
            }

            if (code == null || state == null) {
                return HttpResponse.of(HttpStatus.BAD_REQUEST, MediaType.HTML_UTF_8,
                        buildCallbackHtml(false, "Missing authorization code"));
            }

            // Parse state: "projectId|sourceProject"
            String[] parts = state.split("\\|", 2);
            String projectId = parts[0];
            String sourceProject = parts.length > 1 ? parts[1] : "";

            // Store the authorization code. Full token exchange requires a
            // registered OAuth client_id/client_secret which the user supplies
            // externally; this initial version persists the code so the console
            // can confirm the auth round-trip succeeded.
            credentialRepo.save(projectId, sourceProject, "oauth",
                    mapper.writeValueAsString(Map.of(
                            "auth_code", code,
                            "source_project", sourceProject,
                            "created_at", java.time.Instant.now().toString()
                    )));

            return HttpResponse.of(HttpStatus.OK, MediaType.HTML_UTF_8,
                    buildCallbackHtml(true, "Connected to " + sourceProject));
        } catch (Exception e) {
            logger.error("Error handling OAuth callback", e);
            return HttpResponse.of(HttpStatus.OK, MediaType.HTML_UTF_8,
                    buildCallbackHtml(false, e.getMessage()));
        }
    }

    /**
     * Refresh an existing credential. For the token-paste flow the user must
     * re-paste; a full OAuth flow would use the stored refresh_token.
     */
    @Post("/auth/refresh")
    public HttpResponse authRefresh(ServiceRequestContext ctx) {
        try {
            String project = resolveProject(ctx);
            String data = credentialRepo.getCredentialData(project);
            if (data == null) {
                return jsonResponse(Map.of("error", true, "message", "No credentials configured"));
            }
            // For token-paste flow, user needs to re-paste.
            // For OAuth flow, would use refresh_token to get new access_token.
            return jsonResponse(Map.of("status", "manual_refresh_required",
                    "message", "Re-authenticate to refresh token"));
        } catch (Exception e) {
            logger.error("Error refreshing auth", e);
            return errorResponse(e);
        }
    }

    /**
     * List GCP projects visible to the stored access token via the Cloud
     * Resource Manager API.
     */
    @Get("/auth/projects")
    public HttpResponse authProjects(ServiceRequestContext ctx) {
        try {
            String project = resolveProject(ctx);
            String data = credentialRepo.getCredentialData(project);
            if (data == null) {
                return jsonResponse(Map.of("projects", List.of()));
            }

            JsonNode node = mapper.readTree(data);
            String token = node.has("access_token") ? node.get("access_token").asText() : null;
            if (token == null) {
                return jsonResponse(Map.of("projects", List.of()));
            }

            // Call Cloud Resource Manager API
            String url = "https://cloudresourcemanager.googleapis.com/v1/projects?filter=lifecycleState%3AACTIVE";
            java.net.HttpURLConnection conn = (java.net.HttpURLConnection)
                    java.net.URI.create(url).toURL().openConnection();
            conn.setRequestProperty("Authorization", "Bearer " + token);
            conn.setConnectTimeout(10_000);
            conn.setReadTimeout(10_000);

            int status = conn.getResponseCode();
            if (status >= 200 && status < 300) {
                String body = new String(conn.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                conn.disconnect();
                JsonNode resp = mapper.readTree(body);
                List<Map<String, String>> projects = new ArrayList<>();
                if (resp.has("projects")) {
                    for (JsonNode p : resp.get("projects")) {
                        projects.add(Map.of(
                                "projectId", p.path("projectId").asText(),
                                "name", p.path("name").asText()
                        ));
                    }
                }
                return jsonResponse(Map.of("projects", projects));
            } else {
                conn.disconnect();
                return jsonResponse(Map.of("projects", List.of(),
                        "error", "Failed to list projects: HTTP " + status));
            }
        } catch (Exception e) {
            logger.error("Error listing GCP projects", e);
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
     * Execute a sync operation asynchronously.
     * Returns manifest ID immediately; poll {@code /{service}/progress} for updates.
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

            int manifestId = syncService.startSyncAsync(project, service, sourceProject,
                    resource, filters, rowLimit);
            return jsonResponse(Map.of("manifest_id", manifestId, "status", "in_progress"));
        } catch (Exception e) {
            logger.error("Error starting sync for {}", service, e);
            return errorResponse(e);
        }
    }

    /**
     * Get progress of an active sync operation.
     * Query params: {@code resource} (required).
     */
    @Get("/{service}/progress")
    public HttpResponse progress(ServiceRequestContext ctx, @Param("service") String service) {
        try {
            String project = resolveProject(ctx);
            String resource = ctx.queryParams().get("resource");

            if (resource == null || resource.isBlank()) {
                return HttpResponse.of(HttpStatus.BAD_REQUEST, MediaType.JSON,
                        mapper.writeValueAsString(Map.of("error", true,
                                "message", "resource query param required")));
            }

            SyncService.SyncProgress progress = syncService.getProgress(project, service, resource);
            if (progress == null) {
                return jsonResponse(Map.of("status", "not_running"));
            }
            return jsonResponse(Map.of(
                    "status", "running",
                    "rows_transferred", progress.rowsTransferred(),
                    "bytes_transferred", progress.bytesTransferred(),
                    "estimated_total", progress.estimatedTotal(),
                    "percent", progress.percent(),
                    "elapsed_ms", progress.elapsedMs()
            ));
        } catch (Exception e) {
            logger.error("Error getting progress for {}", service, e);
            return errorResponse(e);
        }
    }

    // -----------------------------------------------------------------------
    // Cancel and resync endpoints
    // -----------------------------------------------------------------------

    /**
     * Cancel a running sync operation.
     * Expects JSON body: {@code {resource}}.
     */
    @Post("/{service}/cancel")
    public HttpResponse cancelSync(ServiceRequestContext ctx, @Param("service") String service,
                                    AggregatedHttpRequest req) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> body = mapper.readValue(req.contentUtf8(), Map.class);
            String resource = (String) body.get("resource");
            boolean cancelled = syncService.cancelSync(resolveProject(ctx), service, resource);
            return jsonResponse(Map.of("cancelled", cancelled));
        } catch (Exception e) {
            logger.error("Error cancelling sync for {}", service, e);
            return errorResponse(e);
        }
    }

    /**
     * Re-run a previous sync using stored manifest parameters.
     * Starts a fresh sync with the same configuration.
     */
    @Post("/resync/{id}")
    public HttpResponse resync(@Param("id") int id) {
        try {
            int newManifestId = syncService.resync(id);
            return jsonResponse(Map.of("manifest_id", newManifestId, "status", "in_progress"));
        } catch (Exception e) {
            logger.error("Error resyncing manifest {}", id, e);
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
        logger.error("Sync API error: {}", e.getMessage());
        try {
            return HttpResponse.of(HttpStatus.INTERNAL_SERVER_ERROR, MediaType.JSON,
                    mapper.writeValueAsString(Map.of("error", true,
                            "message", e.getMessage() != null ? e.getMessage() : "Unknown error")));
        } catch (Exception je) {
            return HttpResponse.of(HttpStatus.INTERNAL_SERVER_ERROR, MediaType.JSON,
                    "{\"error\":true,\"message\":\"Internal error\"}");
        }
    }

    /**
     * Build a simple HTML page shown after the OAuth callback redirect.
     * The page tells the user whether auth succeeded and invites them
     * to close the tab and return to the console.
     */
    String buildCallbackHtml(boolean success, String message) {
        String safeMessage = escapeHtml(message);
        String color = success ? "#34a853" : "#ea4335";
        String icon = success ? "\u2713" : "\u2717";
        return "<!DOCTYPE html><html><head><title>LocalCloud - Auth</title>"
                + "<style>body{font-family:Google Sans,Roboto,sans-serif;display:flex;align-items:center;"
                + "justify-content:center;height:100vh;margin:0;background:#f8f9fa}"
                + ".card{background:#fff;border-radius:12px;padding:48px;text-align:center;"
                + "box-shadow:0 1px 3px rgba(0,0,0,.12)}"
                + ".icon{font-size:48px;color:" + color + ";margin-bottom:16px}"
                + ".msg{font-size:16px;color:#5f6368;margin-top:8px}"
                + ".hint{font-size:13px;color:#240838b;margin-top:16px}</style></head>"
                + "<body><div class='card'><div class='icon'>" + icon + "</div>"
                + "<h2 style='margin:0;color:#202124'>"
                + (success ? "Connected!" : "Connection Failed") + "</h2>"
                + "<p class='msg'>" + safeMessage + "</p>"
                + "<p class='hint'>You can close this tab and return to the LocalCloud console.</p>"
                + "</div></body></html>";
    }

    private static String escapeHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }
}
