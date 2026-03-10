package com.localcloud.admin;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.QueryParams;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.annotation.Get;
import com.localcloud.config.LocalCloudConfig;
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
    private final ObjectMapper mapper;

    /**
     * Hardcoded env var mappings for all emulator services.
     * Each entry: service name -> {envVarName, envVarValue}
     */
    private static final Map<String, String[]> ENV_MAPPINGS = new LinkedHashMap<>();

    static {
        ENV_MAPPINGS.put("gcs",            new String[]{"STORAGE_EMULATOR_HOST",         "http://localhost:4443"});
        ENV_MAPPINGS.put("pubsub",         new String[]{"PUBSUB_EMULATOR_HOST",          "localhost:8085"});
        ENV_MAPPINGS.put("firestore",      new String[]{"FIRESTORE_EMULATOR_HOST",       "localhost:8086"});
        ENV_MAPPINGS.put("bigtable",       new String[]{"BIGTABLE_EMULATOR_HOST",        "localhost:8087"});
        ENV_MAPPINGS.put("spanner",        new String[]{"SPANNER_EMULATOR_HOST",         "localhost:9010"});
        ENV_MAPPINGS.put("bigquery",       new String[]{"BIGQUERY_EMULATOR_HOST",        "http://localhost:9050"});
        ENV_MAPPINGS.put("secretmanager",  new String[]{"SECRET_MANAGER_EMULATOR_HOST",  "localhost:8080"});
        ENV_MAPPINGS.put("cloudtasks",     new String[]{"CLOUD_TASKS_EMULATOR_HOST",     "localhost:8080"});
    }

    public AdminApiService(LocalCloudConfig config, RequestLogger requestLogger) {
        this.config = config;
        this.requestLogger = requestLogger;
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

            // Build env vars from hardcoded mappings, only for enabled services
            Map<String, String> envVars = new LinkedHashMap<>();
            for (Map.Entry<String, String[]> entry : ENV_MAPPINGS.entrySet()) {
                String service = entry.getKey();
                if (config.isServiceEnabled(service)) {
                    String[] mapping = entry.getValue();
                    envVars.put(mapping[0], mapping[1]);
                }
            }

            // Always include the project ID
            envVars.put("GCLOUD_PROJECT", config.getProjectId());

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
                        sb.append("export ").append(e.getKey()).append("=")
                          .append(e.getValue()).append("\n");
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
