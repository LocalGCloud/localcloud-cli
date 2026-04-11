package com.localcloud.gateway;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.server.annotation.Get;
import com.linecorp.armeria.server.annotation.Param;
import com.localcloud.config.LocalCloudConfig;
import com.localcloud.config.ServiceRegistry;
import com.localcloud.config.ServiceRegistry.ServiceDefinition;

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
    private final ServiceRegistry registry;

    public HealthCheckService(LocalCloudConfig config, ApiGateway gateway,
                              ProcessHealthChecker processHealthChecker) {
        this.config = config;
        this.gateway = gateway;
        this.processHealthChecker = processHealthChecker;
        this.startTime = Instant.now();
        this.mapper = new ObjectMapper();
        this.mapper.registerModule(new JavaTimeModule());
        this.mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        this.registry = config.getServiceRegistry();
    }

    @Get("/health")
    public HttpResponse health() {
        return HttpResponse.of(CompletableFuture.supplyAsync(() -> {
            try {
                // Poll all external emulators and update statuses
                processHealthChecker.checkAll();
                Map<String, String> statuses = processHealthChecker.getAllStatuses();

                // Determine overall health
                boolean allHealthy = true;
                Map<String, Object> services = new LinkedHashMap<>();

                for (Map.Entry<String, ServiceDefinition> entry : registry.getAllServices().entrySet()) {
                    String serviceName = entry.getKey();
                    ServiceDefinition def = entry.getValue();

                    if (!config.isServiceEnabled(serviceName)) {
                        continue;
                    }

                    String status = statuses.getOrDefault(serviceName, "unknown");
                    Map<String, Object> svc = new LinkedHashMap<>();
                    svc.put("status", status);
                    svc.put("port", def.port());
                    svc.put("protocol", def.protocol());
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
                return HttpResponse.of(HttpStatus.INTERNAL_SERVER_ERROR,
                        MediaType.PLAIN_TEXT_UTF_8, "Internal server error");
            }
        }));
    }

    /**
     * Returns health status for a single service by name.
     * Example: GET /_localcloud/health/gcs
     */
    @Get("/health/{service}")
    public HttpResponse serviceHealth(@Param("service") String service) {
        return HttpResponse.of(CompletableFuture.supplyAsync(() -> {
            try {
                ServiceDefinition def = registry.getService(service);
                if (def == null) {
                    Map<String, Object> error = Map.of(
                            "error", true,
                            "message", "Unknown service: " + service
                    );
                    return HttpResponse.of(HttpStatus.NOT_FOUND, MediaType.JSON,
                            mapper.writeValueAsString(error));
                }

                if (!config.isServiceEnabled(service)) {
                    Map<String, Object> resp = new LinkedHashMap<>();
                    resp.put("service", service);
                    resp.put("status", "disabled");
                    resp.put("port", def.port());
                    resp.put("protocol", def.protocol());
                    return HttpResponse.of(HttpStatus.OK, MediaType.JSON,
                            mapper.writeValueAsString(resp));
                }

                // Check this specific service
                processHealthChecker.checkAll();
                String status = processHealthChecker.getStatus(service);

                Map<String, Object> resp = new LinkedHashMap<>();
                resp.put("service", service);
                resp.put("status", status);
                resp.put("port", def.port());
                resp.put("protocol", def.protocol());
                resp.put("type", def.type());
                if (def.additionalPorts() != null && !def.additionalPorts().isEmpty()) {
                    resp.put("additional_ports", def.additionalPorts());
                }

                HttpStatus httpStatus = "healthy".equals(status) ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE;
                return HttpResponse.of(httpStatus, MediaType.JSON,
                        mapper.writeValueAsString(resp));
            } catch (Exception e) {
                return HttpResponse.of(HttpStatus.INTERNAL_SERVER_ERROR,
                        MediaType.PLAIN_TEXT_UTF_8, "Internal server error");
            }
        }));
    }

    @Get("/services")
    public HttpResponse services() {
        return HttpResponse.of(CompletableFuture.supplyAsync(() -> {
            try {
                // Get latest statuses
                processHealthChecker.checkAll();
                Map<String, String> statuses = processHealthChecker.getAllStatuses();

                List<Map<String, Object>> serviceList = new ArrayList<>();
                for (Map.Entry<String, ServiceDefinition> entry : registry.getAllServices().entrySet()) {
                    String serviceName = entry.getKey();
                    ServiceDefinition def = entry.getValue();

                    if (!config.isServiceEnabled(serviceName)) {
                        continue;
                    }

                    String status = statuses.getOrDefault(serviceName, "unknown");

                    // Look up request count from the gateway's registered emulator
                    long requestCount = 0;
                    var emulator = gateway.getEmulator(serviceName);
                    if (emulator.isPresent()) {
                        requestCount = emulator.get().getRequestCount();
                    }

                    String envValue = def.envValue("localhost");
                    Map<String, Object> svc = new LinkedHashMap<>();
                    svc.put("id", serviceName);
                    svc.put("name", def.displayName());
                    svc.put("status", status);
                    svc.put("port", def.port());
                    svc.put("protocol", def.protocol());
                    svc.put("endpoint", envValue);
                    svc.put("env_var", def.envVar());
                    svc.put("env_value", envValue);
                    svc.put("request_count", requestCount);
                    serviceList.add(svc);
                }

                Map<String, Object> response = new LinkedHashMap<>();
                response.put("services", serviceList);

                String json = mapper.writeValueAsString(response);
                return HttpResponse.of(HttpStatus.OK, MediaType.JSON, json);
            } catch (Exception e) {
                return HttpResponse.of(HttpStatus.INTERNAL_SERVER_ERROR,
                        MediaType.PLAIN_TEXT_UTF_8, "Internal server error");
            }
        }));
    }
}
