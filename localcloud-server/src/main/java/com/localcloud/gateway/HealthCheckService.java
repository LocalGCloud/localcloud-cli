package com.localcloud.gateway;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.server.annotation.Get;
import com.localcloud.config.LocalCloudConfig;

/**
 * REST endpoint handler for the LocalCloud admin health check.
 * Provides aggregated status information about all services, including
 * external emulators (checked via ProcessHealthChecker) and in-process
 * facade services.
 */
public class HealthCheckService {

    private final LocalCloudConfig config;
    private final ApiGateway gateway;
    private final ProcessHealthChecker processHealthChecker;
    private final Instant startTime;
    private final ObjectMapper mapper;

    /** Service definitions: name -> {port, envVar} */
    private static final Map<String, ServiceDef> SERVICE_DEFS = new LinkedHashMap<>();

    static {
        SERVICE_DEFS.put("gcs",            new ServiceDef(4443,  "STORAGE_EMULATOR_HOST"));
        SERVICE_DEFS.put("pubsub",         new ServiceDef(8085,  "PUBSUB_EMULATOR_HOST"));
        SERVICE_DEFS.put("firestore",      new ServiceDef(8086,  "FIRESTORE_EMULATOR_HOST"));
        SERVICE_DEFS.put("bigtable",       new ServiceDef(8087,  "BIGTABLE_EMULATOR_HOST"));
        SERVICE_DEFS.put("spanner",        new ServiceDef(9010,  "SPANNER_EMULATOR_HOST"));
        SERVICE_DEFS.put("bigquery",       new ServiceDef(9050,  "BIGQUERY_EMULATOR_HOST"));
        SERVICE_DEFS.put("secretmanager",  new ServiceDef(8080,  "SECRET_MANAGER_EMULATOR_HOST"));
        SERVICE_DEFS.put("cloudtasks",     new ServiceDef(8080,  "CLOUD_TASKS_EMULATOR_HOST"));
        SERVICE_DEFS.put("logging",        new ServiceDef(8080,  "CLOUD_LOGGING_EMULATOR_HOST"));
        SERVICE_DEFS.put("monitoring",     new ServiceDef(8080,  "CLOUD_MONITORING_EMULATOR_HOST"));
        SERVICE_DEFS.put("gke",            new ServiceDef(8080,  "GKE_EMULATOR_HOST"));
        SERVICE_DEFS.put("compute",        new ServiceDef(8080,  "COMPUTE_EMULATOR_HOST"));
        SERVICE_DEFS.put("cloudrun",       new ServiceDef(8080,  "CLOUD_RUN_EMULATOR_HOST"));
    }

    private record ServiceDef(int port, String envVar) {}

    public HealthCheckService(LocalCloudConfig config, ApiGateway gateway,
                              ProcessHealthChecker processHealthChecker) {
        this.config = config;
        this.gateway = gateway;
        this.processHealthChecker = processHealthChecker;
        this.startTime = Instant.now();
        this.mapper = new ObjectMapper();
        this.mapper.registerModule(new JavaTimeModule());
        this.mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    @Get("/health")
    public HttpResponse health() {
        try {
            // Poll all external emulators and update statuses
            processHealthChecker.checkAll();
            Map<String, String> statuses = processHealthChecker.getAllStatuses();

            // Determine overall health
            boolean allHealthy = true;
            Map<String, Object> services = new LinkedHashMap<>();

            for (Map.Entry<String, ServiceDef> entry : SERVICE_DEFS.entrySet()) {
                String serviceName = entry.getKey();
                ServiceDef def = entry.getValue();

                if (!config.isServiceEnabled(serviceName)) {
                    continue;
                }

                String status = statuses.getOrDefault(serviceName, "unknown");
                Map<String, Object> svc = new LinkedHashMap<>();
                svc.put("status", status);
                svc.put("port", def.port());
                svc.put("env_var", def.envVar());
                services.put(serviceName, svc);

                if (!"healthy".equals(status)) {
                    allHealthy = false;
                }
            }

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("status", allHealthy ? "healthy" : "degraded");
            response.put("uptime_seconds", Duration.between(startTime, Instant.now()).getSeconds());
            response.put("services", services);
            response.put("project_id", config.getProjectId());
            response.put("persistence", config.isPersistenceEnabled());
            response.put("data_dir", config.getDataDir().toString());

            String json = mapper.writeValueAsString(response);
            return HttpResponse.of(HttpStatus.OK, MediaType.JSON, json);
        } catch (Exception e) {
            Map<String, Object> error = Map.of(
                    "status", "error",
                    "message", e.getMessage() != null ? e.getMessage() : "Unknown error"
            );
            try {
                return HttpResponse.of(HttpStatus.INTERNAL_SERVER_ERROR,
                        MediaType.JSON, mapper.writeValueAsString(error));
            } catch (Exception ex) {
                return HttpResponse.of(HttpStatus.INTERNAL_SERVER_ERROR,
                        MediaType.PLAIN_TEXT_UTF_8, "Internal server error");
            }
        }
    }

    @Get("/services")
    public HttpResponse services() {
        try {
            // Get latest statuses
            processHealthChecker.checkAll();
            Map<String, String> statuses = processHealthChecker.getAllStatuses();

            Map<String, Object> response = new LinkedHashMap<>();
            for (Map.Entry<String, ServiceDef> entry : SERVICE_DEFS.entrySet()) {
                String serviceName = entry.getKey();
                ServiceDef def = entry.getValue();

                boolean enabled = config.isServiceEnabled(serviceName);
                String status = enabled
                        ? statuses.getOrDefault(serviceName, "unknown")
                        : "disabled";

                Map<String, Object> svc = new LinkedHashMap<>();
                svc.put("enabled", enabled);
                svc.put("status", status);
                svc.put("port", def.port());
                svc.put("env_var", def.envVar());
                response.put(serviceName, svc);
            }

            String json = mapper.writeValueAsString(response);
            return HttpResponse.of(HttpStatus.OK, MediaType.JSON, json);
        } catch (Exception e) {
            return HttpResponse.of(HttpStatus.INTERNAL_SERVER_ERROR,
                    MediaType.PLAIN_TEXT_UTF_8, "Internal server error");
        }
    }
}
