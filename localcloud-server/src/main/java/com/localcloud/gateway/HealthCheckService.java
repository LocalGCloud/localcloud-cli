package com.localcloud.gateway;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.QueryParams;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.annotation.Get;
import com.linecorp.armeria.server.annotation.Param;
import com.localcloud.admin.SupervisorClient;
import com.localcloud.admin.UsageMetricsRepository;
import com.localcloud.config.LocalCloudConfig;
import com.localcloud.config.ServiceRegistry;
import com.localcloud.config.ServiceRegistry.HealthCheckDef;
import com.localcloud.config.ServiceRegistry.ServiceDefinition;

/**
 * REST endpoint handler for the LocalCloud admin health check.
 * Provides aggregated status information about all services, including
 * external emulators (checked via ProcessHealthChecker) and in-process
 * facade services.
 */
public class HealthCheckService {

    private static final org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(HealthCheckService.class);

    private final LocalCloudConfig config;
    private final ApiGateway gateway;
    private final ProcessHealthChecker processHealthChecker;
    private final UsageMetricsRepository usageMetrics;
    private final Instant startTime;
    private final ObjectMapper mapper;
    private final ServiceRegistry registry;
    private final ScheduledExecutorService flushScheduler;
    private final SupervisorClient supervisorClient = new SupervisorClient();
    private volatile String spaHtml;

    // Map service IDs to supervisord program names (external services only)
    private static final Map<String, String> SERVICE_TO_PROGRAM = Map.ofEntries(
        Map.entry("gcs", "fake-gcs-server"),
        Map.entry("pubsub", "pubsub-emulator"),
        Map.entry("firestore", "firestore-emulator"),
        Map.entry("bigtable", "bigtable-emulator"),
        Map.entry("spanner", "spanner-emulator"),
        Map.entry("bigquery", "bigquery-emulator"),
        Map.entry("memorystore", "valkey")
    );

    public HealthCheckService(LocalCloudConfig config, ApiGateway gateway,
                              ProcessHealthChecker processHealthChecker,
                              UsageMetricsRepository usageMetrics) {
        this.config = config;
        this.gateway = gateway;
        this.processHealthChecker = processHealthChecker;
        this.usageMetrics = usageMetrics;
        this.startTime = Instant.now();
        this.mapper = new ObjectMapper();
        this.mapper.registerModule(new JavaTimeModule());
        this.mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        this.registry = config.getServiceRegistry();

        // Flush in-memory request deltas to PostgreSQL every 30 seconds
        this.flushScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "usage-metrics-flush");
            t.setDaemon(true);
            return t;
        });
        this.flushScheduler.scheduleAtFixedRate(this::flushMetrics, 30, 30, TimeUnit.SECONDS);
    }

    /**
     * Sets the SPA HTML content, used to serve the console for browser GET requests
     * that would otherwise match API endpoints (e.g. /usage, /logs, /settings).
     */
    public void setSpaHtml(String html) {
        this.spaHtml = html;
    }

    /**
     * Flush in-memory request count deltas from all registered emulators
     * to the persistent usage_metrics table. Called periodically and on shutdown.
     */
    public void flushMetrics() {
        try {
            String projectId = config.getProjectId();
            Map<String, Long> deltas = new LinkedHashMap<>();
            for (var emulator : gateway.getEmulators()) {
                long delta = emulator.getAndResetRequestCount();
                if (delta > 0) {
                    deltas.put(emulator.getName(), delta);
                }
            }
            if (!deltas.isEmpty()) {
                usageMetrics.flushDeltas(projectId, deltas);
                logger.debug("Flushed usage deltas: {}", deltas);
            }
        } catch (Exception e) {
            logger.warn("Error flushing usage metrics: {}", e.getMessage());
        }
    }

    /**
     * Shut down the flush scheduler and perform a final flush.
     */
    public void shutdown() {
        flushScheduler.shutdown();
        flushMetrics(); // final flush
    }

    /**
     * Collect current JVM process CPU load and memory usage metrics.
     * Returns a map suitable for inclusion in the health response.
     */
    static Map<String, Object> collectSystemMetrics() {
        Map<String, Object> result = new LinkedHashMap<>();

        // CPU metrics via OperatingSystemMXBean (com.sun.management extension)
        try {
            var osBean = (com.sun.management.OperatingSystemMXBean)
                    ManagementFactory.getOperatingSystemMXBean();

            double processLoad = osBean.getProcessCpuLoad();
            double systemLoad = osBean.getCpuLoad();

            Map<String, Object> cpu = new LinkedHashMap<>();
            // getProcessCpuLoad returns -1 if not available (first call, etc.)
            cpu.put("process_load", processLoad >= 0 ? Math.round(processLoad * 1000.0) / 10.0 : 0.0);
            cpu.put("system_load", systemLoad >= 0 ? Math.round(systemLoad * 1000.0) / 10.0 : 0.0);
            cpu.put("available_processors", Runtime.getRuntime().availableProcessors());
            result.put("cpu", cpu);
        } catch (Exception e) {
            // Fallback: no CPU data available
        }

        // Memory metrics
        try {
            MemoryMXBean memBean = ManagementFactory.getMemoryMXBean();
            MemoryUsage heap = memBean.getHeapMemoryUsage();

            Runtime runtime = Runtime.getRuntime();
            long maxMem = runtime.maxMemory();
            long totalMem = runtime.totalMemory();
            long freeMem = runtime.freeMemory();
            long usedMem = totalMem - freeMem;

            // Try system-level physical memory from OS MXBean
            long totalPhysicalMb = 0;
            long freePhysicalMb = 0;
            try {
                var osBean = (com.sun.management.OperatingSystemMXBean)
                        ManagementFactory.getOperatingSystemMXBean();
                totalPhysicalMb = osBean.getTotalMemorySize() / (1024 * 1024);
                freePhysicalMb = osBean.getFreeMemorySize() / (1024 * 1024);
            } catch (Exception ignored) {}

            // Non-heap memory
            MemoryUsage nonHeap = memBean.getNonHeapMemoryUsage();

            Map<String, Object> memory = new LinkedHashMap<>();
            memory.put("heap_used_mb", usedMem / (1024 * 1024));
            memory.put("heap_max_mb", maxMem > 0 ? maxMem / (1024 * 1024) : 0);
            memory.put("heap_committed_mb", totalMem / (1024 * 1024));
            memory.put("heap_usage_pct", maxMem > 0
                    ? Math.round((double) usedMem / maxMem * 1000.0) / 10.0
                    : 0.0);
            memory.put("non_heap_used_mb", nonHeap.getUsed() / (1024 * 1024));
            memory.put("total_physical_mb", totalPhysicalMb);
            memory.put("free_physical_mb", freePhysicalMb);
            result.put("memory", memory);
        } catch (Exception e) {
            // Fallback: no memory data available
        }

        return result;
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

                // Version info
                try {
                    String versionFile = System.getenv("LOCALCLOUD_VERSION_FILE");
                    if (versionFile != null) {
                        String version = java.nio.file.Files.readString(java.nio.file.Path.of(versionFile)).trim();
                        response.put("version", version);
                    }
                    java.nio.file.Path displayFile = java.nio.file.Path.of("/opt/localcloud/VERSION_DISPLAY");
                    if (java.nio.file.Files.exists(displayFile)) {
                        response.put("version_display", java.nio.file.Files.readString(displayFile).trim());
                    }
                } catch (Exception ignored) {}

                // System metrics (CPU + memory)
                try {
                    response.putAll(collectSystemMetrics());
                } catch (Exception ignored) {}

                // Update availability (written by entrypoint background check)
                try {
                    java.nio.file.Path updateFile = java.nio.file.Path.of("/tmp/localcloud-update-available.json");
                    if (java.nio.file.Files.exists(updateFile)) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> updateInfo = mapper.readValue(java.nio.file.Files.readString(updateFile), Map.class);
                        response.put("update_available", updateInfo);
                    }
                } catch (Exception ignored) {}

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
     * Example: GET /health/gcs
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

    /**
     * Returns CI-friendly readiness for all enabled services or a selected
     * comma-separated service set via {@code ?services=gcs,pubsub}.
     */
    @Get("/readiness")
    public HttpResponse readiness(ServiceRequestContext ctx) {
        return HttpResponse.of(CompletableFuture.supplyAsync(() -> {
            try {
                QueryParams params = ctx.queryParams();
                List<String> requestedServices = parseRequestedServices(params.get("services"));

                processHealthChecker.checkAll();
                Map<String, String> statuses = processHealthChecker.getAllStatuses();
                Instant checkedAt = Instant.now();

                List<String> serviceIds = requestedServices.isEmpty()
                        ? enabledServiceIds()
                        : requestedServices;

                ReadinessResult result = buildReadinessResult(serviceIds, statuses, checkedAt);
                String json = mapper.writeValueAsString(result.response());
                return HttpResponse.of(result.ready() ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE,
                        MediaType.JSON, json);
            } catch (Exception e) {
                return HttpResponse.of(HttpStatus.INTERNAL_SERVER_ERROR,
                        MediaType.PLAIN_TEXT_UTF_8, "Internal server error");
            }
        }));
    }

    /**
     * Returns readiness for a single service. Disabled services return 503
     * with a remediation hint because an explicitly requested service is
     * considered required by the caller.
     */
    @Get("/readiness/{service}")
    public HttpResponse serviceReadiness(@Param("service") String service) {
        return HttpResponse.of(CompletableFuture.supplyAsync(() -> {
            try {
                if (registry.getService(service) == null) {
                    Map<String, Object> error = Map.of(
                            "error", true,
                            "message", "Unknown service: " + service
                    );
                    return HttpResponse.of(HttpStatus.NOT_FOUND, MediaType.JSON,
                            mapper.writeValueAsString(error));
                }

                processHealthChecker.checkAll();
                ReadinessResult result = buildReadinessResult(
                        List.of(service), processHealthChecker.getAllStatuses(), Instant.now());
                String json = mapper.writeValueAsString(result.response());
                return HttpResponse.of(result.ready() ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE,
                        MediaType.JSON, json);
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

                // Get persisted cumulative counts from DB
                Map<String, Long> persistedCounts = usageMetrics.getGlobalCounts();

                // Get all supervisor process PIDs for per-process memory
                Map<String, Map<String, String>> allProcs;
                try {
                    allProcs = supervisorClient.getAllProcesses();
                } catch (Exception e) {
                    logger.warn("Failed to query supervisor for process info: {}", e.getMessage());
                    allProcs = Map.of();
                }
                Map<String, Long> procMemory = new LinkedHashMap<>();
                for (var entry : allProcs.entrySet()) {
                    String pidStr = entry.getValue().getOrDefault("pid", "0");
                    try {
                        long pid = Long.parseLong(pidStr);
                        long memMb = SupervisorClient.getProcessMemoryMb(pid);
                        procMemory.put(entry.getKey(), memMb);
                        if (memMb > 0) {
                            logger.debug("Process {} (pid={}): {} MB", entry.getKey(), pid, memMb);
                        }
                    } catch (NumberFormatException e) {
                        procMemory.put(entry.getKey(), 0L);
                    }
                }
                logger.debug("Collected memory for {} supervisor processes", procMemory.size());

                List<Map<String, Object>> serviceList = new ArrayList<>();
                for (Map.Entry<String, ServiceDefinition> entry : registry.getAllServices().entrySet()) {
                    String serviceName = entry.getKey();
                    ServiceDefinition def = entry.getValue();

                    boolean enabled = config.isServiceDynamicallyEnabled(serviceName);
                    String status = enabled
                            ? statuses.getOrDefault(serviceName, "unknown")
                            : "disabled";

                    // Total = persisted cumulative + unflushed in-memory delta
                    long persisted = persistedCounts.getOrDefault(serviceName, 0L);
                    long unflushed = 0;
                    var emulator = gateway.getEmulator(serviceName);
                    if (emulator.isPresent()) {
                        unflushed = emulator.get().getRequestCount();
                    }
                    long totalCount = persisted + unflushed;

                    // Per-process memory: look up supervisor PID for external services
                    long memoryMb = 0;
                    String programName = SERVICE_TO_PROGRAM.get(serviceName);
                    if (programName != null) {
                        memoryMb = procMemory.getOrDefault(programName, 0L);
                        if (memoryMb > 0) {
                            logger.debug("Service {} (program={}): memory={} MB", serviceName, programName, memoryMb);
                        }
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
                    svc.put("request_count", totalCount);
                    svc.put("enabled", enabled);
                    svc.put("enabledSource", config.getConfigSource(serviceName));
                    svc.put("memory_mb", memoryMb);
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

    private List<String> enabledServiceIds() {
        List<String> serviceIds = new ArrayList<>();
        for (String serviceId : registry.getAllServices().keySet()) {
            if (config.isServiceEnabled(serviceId)) {
                serviceIds.add(serviceId);
            }
        }
        return serviceIds;
    }

    private static List<String> parseRequestedServices(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        return Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(service -> !service.isEmpty())
                .distinct()
                .toList();
    }

    private ReadinessResult buildReadinessResult(List<String> serviceIds,
                                                 Map<String, String> statuses,
                                                 Instant checkedAt) {
        boolean allReady = true;
        List<Map<String, Object>> services = new ArrayList<>();

        for (String serviceId : serviceIds) {
            ServiceDefinition def = registry.getService(serviceId);
            Map<String, Object> svc;
            if (def == null) {
                allReady = false;
                svc = unknownServiceReadiness(serviceId, checkedAt);
            } else {
                svc = serviceReadiness(serviceId, def, statuses, checkedAt);
                if (!Boolean.TRUE.equals(svc.get("ready"))) {
                    allReady = false;
                }
            }
            services.add(svc);
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("ready", allReady);
        response.put("status", allReady ? "ready" : "not_ready");
        response.put("checked_at", checkedAt.toString());
        response.put("project_id", config.getProjectId());
        response.put("services", services);
        return new ReadinessResult(allReady, response);
    }

    private Map<String, Object> unknownServiceReadiness(String serviceId, Instant checkedAt) {
        Map<String, Object> svc = new LinkedHashMap<>();
        svc.put("service_id", serviceId);
        svc.put("enabled", false);
        svc.put("ready", false);
        svc.put("status", "unknown");
        svc.put("last_checked_at", checkedAt.toString());
        svc.put("failure_reason", "unknown_service");
        svc.put("remediation_hint", "Use /services or /profiles to select a known service id.");
        return svc;
    }

    private Map<String, Object> serviceReadiness(String serviceId, ServiceDefinition def,
                                                 Map<String, String> statuses,
                                                 Instant checkedAt) {
        boolean enabled = config.isServiceEnabled(serviceId);
        String status = enabled ? statuses.getOrDefault(serviceId, "unknown") : "disabled";
        boolean ready = enabled && "healthy".equals(status);

        Map<String, Object> svc = new LinkedHashMap<>();
        svc.put("service_id", serviceId);
        svc.put("display_name", def.displayName());
        svc.put("enabled", enabled);
        svc.put("ready", ready);
        svc.put("status", status);
        svc.put("endpoint", def.envValue("localhost"));
        svc.put("protocol", def.protocol());
        svc.put("type", def.type());
        svc.put("port", def.port());
        svc.put("health_check", healthCheckMetadata(def));
        svc.put("last_checked_at", checkedAt.toString());
        svc.put("failure_reason", ready ? null : failureReason(enabled, status));
        svc.put("remediation_hint", ready ? null : remediationHint(serviceId, def, enabled, status));
        return svc;
    }

    private Map<String, Object> healthCheckMetadata(ServiceDefinition def) {
        Map<String, Object> healthCheck = new LinkedHashMap<>();
        if (def.isFacade()) {
            healthCheck.put("type", "facade");
            healthCheck.put("description", "In-process service is ready when the gateway is running and the service is enabled.");
            return healthCheck;
        }

        HealthCheckDef hc = def.healthCheck();
        healthCheck.put("type", hc != null ? hc.type() : "tcp");
        healthCheck.put("port", hc != null && hc.port() != null ? hc.port() : def.port());
        if (hc != null && hc.path() != null) {
            healthCheck.put("path", hc.path().replace("{projectId}", config.getProjectId()));
        }
        return healthCheck;
    }

    private static String failureReason(boolean enabled, String status) {
        if (!enabled) {
            return "service_disabled";
        }
        if ("unknown".equals(status)) {
            return "health_status_unknown";
        }
        return "health_check_failed";
    }

    private static String remediationHint(String serviceId, ServiceDefinition def,
                                          boolean enabled, String status) {
        if (!enabled) {
            return "Enable " + serviceId + " or remove it from the requested readiness service set.";
        }
        if ("unknown".equals(status)) {
            return "Health has not been observed yet; retry wait or inspect /health/" + serviceId + ".";
        }
        if (def.isExternal()) {
            return "Inspect service logs and confirm the local process is listening on port " + def.port() + ".";
        }
        return "Inspect gateway logs for the in-process facade.";
    }

    /**
     * Terraform-specific readiness check. Verifies that DNS redirect for
     * {@code serviceusage.googleapis.com} is configured and port 443 is
     * reachable. Returns actionable errors when prerequisites are missing.
     *
     * <p>Usage: {@code GET /terraform/readiness} — returns 200 if all
     * Terraform prerequisites are met, 503 with remediation hints otherwise.</p>
     */
    @Get("/terraform/readiness")
    public HttpResponse terraformReadiness() {
        return HttpResponse.of(CompletableFuture.supplyAsync(() -> {
            try {
                List<Map<String, Object>> checks = new ArrayList<>();
                boolean allPassed = true;

                // Check 1: DNS resolution for serviceusage.googleapis.com
                Map<String, Object> dnsCheck = new LinkedHashMap<>();
                dnsCheck.put("name", "dns_redirect");
                dnsCheck.put("description", "serviceusage.googleapis.com resolves to 127.0.0.1");
                try {
                    java.net.InetAddress addr = java.net.InetAddress.getByName("serviceusage.googleapis.com");
                    boolean resolvedToLocal = "127.0.0.1".equals(addr.getHostAddress());
                    dnsCheck.put("passed", resolvedToLocal);
                    dnsCheck.put("actual", addr.getHostAddress());
                    dnsCheck.put("expected", "127.0.0.1");
                    if (!resolvedToLocal) {
                        allPassed = false;
                        dnsCheck.put("remediation",
                            "Option A (recommended): echo 'nameserver 127.0.0.1' | sudo tee /etc/resolver/googleapis.com");
                        dnsCheck.put("remediation_alt",
                            "Option B: echo '127.0.0.1 serviceusage.googleapis.com' | sudo tee -a /etc/hosts");
                    }
                } catch (java.net.UnknownHostException e) {
                    dnsCheck.put("passed", false);
                    dnsCheck.put("error", e.getMessage());
                    allPassed = false;
                    dnsCheck.put("remediation",
                        "Option A (recommended): echo 'nameserver 127.0.0.1' | sudo tee /etc/resolver/googleapis.com");
                    dnsCheck.put("remediation_alt",
                        "Option B: echo '127.0.0.1 serviceusage.googleapis.com' | sudo tee -a /etc/hosts");
                }
                checks.add(dnsCheck);

                // Check 2: Port 443 reachable (gateway must be mapped -p 443:8080)
                Map<String, Object> portCheck = new LinkedHashMap<>();
                portCheck.put("name", "port_443");
                portCheck.put("description", "Port 443 reachable (requires -p 443:8080)");
                try (var socket = new java.net.Socket()) {
                    socket.connect(new java.net.InetSocketAddress("127.0.0.1", 443), 2000);
                    portCheck.put("passed", true);
                } catch (Exception e) {
                    portCheck.put("passed", false);
                    portCheck.put("error", e.getMessage());
                    allPassed = false;
                    portCheck.put("remediation",
                        "Restart container with: docker run -p 443:8080 ...");
                }
                checks.add(portCheck);

                // Check 3: Service Usage API responds
                Map<String, Object> apiCheck = new LinkedHashMap<>();
                apiCheck.put("name", "service_usage_api");
                apiCheck.put("description", "Service Usage API returns ENABLED");
                try {
                    var httpClient = java.net.http.HttpClient.newHttpClient();
                    var request = java.net.http.HttpRequest.newBuilder()
                        .uri(java.net.URI.create(
                            "http://localhost:" + config.getGatewayPort()
                            + "/v1/projects/" + config.getProjectId()
                            + "/services/storage.googleapis.com"))
                        .timeout(java.time.Duration.ofSeconds(3))
                        .GET()
                        .build();
                    var response = httpClient.send(request,
                        java.net.http.HttpResponse.BodyHandlers.ofString());
                    boolean apiOk = response.statusCode() == 200
                        && response.body().contains("ENABLED");
                    apiCheck.put("passed", apiOk);
                    apiCheck.put("status_code", response.statusCode());
                    if (!apiOk) {
                        allPassed = false;
                        apiCheck.put("remediation",
                            "Ensure serviceusage service is enabled in services.yaml");
                    }
                } catch (Exception e) {
                    apiCheck.put("passed", false);
                    apiCheck.put("error", e.getMessage());
                    allPassed = false;
                    apiCheck.put("remediation",
                        "Ensure gateway is running and serviceusage service is enabled");
                }
                checks.add(apiCheck);

                // Check 4: Billing API responds
                Map<String, Object> billingCheck = new LinkedHashMap<>();
                billingCheck.put("name", "billing_api");
                billingCheck.put("description", "Cloud Billing API returns billingEnabled=true");
                try {
                    var httpClient = java.net.http.HttpClient.newHttpClient();
                    var request = java.net.http.HttpRequest.newBuilder()
                        .uri(java.net.URI.create(
                            "http://localhost:" + config.getGatewayPort()
                            + "/v1/projects/" + config.getProjectId() + "/billingInfo"))
                        .timeout(java.time.Duration.ofSeconds(3))
                        .GET()
                        .build();
                    var response = httpClient.send(request,
                        java.net.http.HttpResponse.BodyHandlers.ofString());
                    boolean billingOk = response.statusCode() == 200
                        && response.body().contains("billingEnabled");
                    billingCheck.put("passed", billingOk);
                    billingCheck.put("status_code", response.statusCode());
                    if (!billingOk) {
                        allPassed = false;
                        billingCheck.put("remediation",
                            "Ensure cloudbilling service is enabled in services.yaml");
                    }
                } catch (Exception e) {
                    billingCheck.put("passed", false);
                    billingCheck.put("error", e.getMessage());
                    allPassed = false;
                    billingCheck.put("remediation",
                        "Ensure gateway is running and cloudbilling service is enabled");
                }
                checks.add(billingCheck);

                // Check 5: BigQuery DNS (provider ignores GOOGLE_BIGQUERY_CUSTOM_ENDPOINT)
                Map<String, Object> bqCheck = new LinkedHashMap<>();
                bqCheck.put("name", "bigquery_dns");
                bqCheck.put("description", "bigquery.googleapis.com resolves to 127.0.0.1 (required for BigQuery Terraform resources)");
                try {
                    java.net.InetAddress bqAddr = java.net.InetAddress.getByName("bigquery.googleapis.com");
                    boolean bqLocal = "127.0.0.1".equals(bqAddr.getHostAddress());
                    bqCheck.put("passed", bqLocal);
                    bqCheck.put("actual", bqAddr.getHostAddress());
                    bqCheck.put("expected", "127.0.0.1");
                    if (!bqLocal) {
                        bqCheck.put("remediation",
                            "If /etc/resolver/googleapis.com exists, this should work automatically. Otherwise add: echo '127.0.0.1 bigquery.googleapis.com' | sudo tee -a /etc/hosts");
                    }
                } catch (java.net.UnknownHostException e) {
                    bqCheck.put("passed", false);
                    bqCheck.put("error", e.getMessage());
                    bqCheck.put("remediation",
                        "If /etc/resolver/googleapis.com exists, this should work automatically. Otherwise add: echo '127.0.0.1 bigquery.googleapis.com' | sudo tee -a /etc/hosts");
                }
                checks.add(bqCheck);

                Map<String, Object> response = new LinkedHashMap<>();
                response.put("ready", allPassed);
                response.put("status", allPassed ? "ready" : "not_ready");
                response.put("project_id", config.getProjectId());
                response.put("gateway_port", config.getGatewayPort());
                response.put("checks", checks);
                response.put("required_dns_entry",
                    "127.0.0.1 serviceusage.googleapis.com");
                response.put("required_docker_port", "-p 443:8080");

                String json = mapper.writeValueAsString(response);
                return HttpResponse.of(
                    allPassed ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE,
                    MediaType.JSON, json);
            } catch (Exception e) {
                return HttpResponse.of(HttpStatus.INTERNAL_SERVER_ERROR,
                    MediaType.PLAIN_TEXT_UTF_8, "Internal server error");
            }
        }));
    }

    private record ReadinessResult(boolean ready, Map<String, Object> response) {}

    /**
     * Return cumulative usage metrics per service for the active project.
     * Includes persisted counts plus any unflushed in-memory deltas.
     * Supports {@code ?project=} query parameter for project-specific metrics.
     */
    @Get("/usage")
    public HttpResponse usage(ServiceRequestContext ctx) {
        // Serve SPA HTML for browser navigation (hard refresh of /usage)
        String spa = this.spaHtml;
        if (spa != null) {
            String acceptHeader = ctx.request().headers().get("accept");
            if (acceptHeader != null && acceptHeader.contains("text/html")) {
                ResponseHeaders headers = ResponseHeaders.builder(HttpStatus.OK)
                        .contentType(MediaType.HTML_UTF_8)
                        .add("Cache-Control", "no-cache")
                        .build();
                return HttpResponse.of(headers, HttpData.ofUtf8(spa));
            }
        }

        return HttpResponse.of(CompletableFuture.supplyAsync(() -> {
            try {
                String projectParam = ctx.queryParams().get("project");
                String projectId = (projectParam != null && !projectParam.isBlank())
                        ? projectParam : config.getProjectId();

                // Get persisted counts for this project
                Map<String, Long> persistedCounts = usageMetrics.getCountsByProject(projectId);

                // Add unflushed in-memory deltas (only for default project — facade emulators
                // currently don't track per-project, they use the default project)
                boolean isDefaultProject = projectId.equals(config.getProjectId());

                List<Map<String, Object>> usageList = new ArrayList<>();
                for (Map.Entry<String, ServiceDefinition> entry : registry.getAllServices().entrySet()) {
                    String serviceName = entry.getKey();
                    ServiceDefinition def = entry.getValue();

                    if (!config.isServiceEnabled(serviceName)) {
                        continue;
                    }

                    long persisted = persistedCounts.getOrDefault(serviceName, 0L);
                    long unflushed = 0;
                    if (isDefaultProject) {
                        var emulator = gateway.getEmulator(serviceName);
                        if (emulator.isPresent()) {
                            unflushed = emulator.get().getRequestCount();
                        }
                    }

                    Map<String, Object> svc = new LinkedHashMap<>();
                    svc.put("id", serviceName);
                    svc.put("name", def.displayName());
                    svc.put("request_count", persisted + unflushed);
                    usageList.add(svc);
                }

                Map<String, Object> response = new LinkedHashMap<>();
                response.put("project_id", projectId);
                response.put("services", usageList);

                String json = mapper.writeValueAsString(response);
                return HttpResponse.of(HttpStatus.OK, MediaType.JSON, json);
            } catch (Exception e) {
                logger.warn("Error fetching usage metrics: {}", e.getMessage());
                return HttpResponse.of(HttpStatus.INTERNAL_SERVER_ERROR,
                        MediaType.PLAIN_TEXT_UTF_8, "Internal server error");
            }
        }));
    }
}
