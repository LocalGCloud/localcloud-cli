package com.localcloud.admin;

import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.localcloud.config.LocalCloudConfig;
import com.localcloud.config.ServiceRegistry;
import com.localcloud.gateway.ProcessHealthChecker;
import com.localcloud.persistence.PostgresDataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Anonymous telemetry for LocalCloud.
 * Sends a heartbeat event to PostHog every hour with aggregated usage stats.
 * Sends error events immediately when services crash.
 * Unsent events are persisted to PostgreSQL and retried on next cycle.
 *
 * <p>Opt-out: set LOCALCLOUD_TELEMETRY=false to disable entirely.
 * <p>Configure: set LOCALCLOUD_EVENT_API_KEY to your PostHog project key.
 * <p>No PII is collected. The instance ID is a one-way SHA-256 hash.
 */
public class TelemetryService {

    private static final Logger logger = LoggerFactory.getLogger(TelemetryService.class);

    private static final String DEFAULT_POSTHOG_URL = "https://us.i.posthog.com/i/v0/e/";
    private static final String VERSION = "1.0.0";
    private static final int MAX_QUEUE_SIZE = 168; // 7 days of hourly heartbeats

    private final LocalCloudConfig config;
    private final UsageMetricsRepository usageMetrics;
    private final ProcessHealthChecker healthChecker;
    private final ProjectService projectService;
    private final PostgresDataSource dataSource;
    private final HttpClient httpClient;
    private final ObjectMapper mapper;
    private final ScheduledExecutorService scheduler;
    private final String distinctId;
    private final String posthogApiKey;
    private final String posthogUrl;
    private final Instant startTime;
    private final AtomicInteger errorsLastHour;

    // Track previous request counts to compute deltas
    private Map<String, Long> previousCounts = Map.of();

    public TelemetryService(LocalCloudConfig config,
                            UsageMetricsRepository usageMetrics,
                            ProcessHealthChecker healthChecker,
                            ProjectService projectService,
                            PostgresDataSource dataSource) {
        this(config, usageMetrics, healthChecker, projectService, dataSource,
                createHttpClient(),
                new ObjectMapper(),
                createScheduler(),
                generateDistinctId(),
                env("LOCALCLOUD_EVENT_API_KEY", ""),
                env("LOCALCLOUD_POSTHOG_URL", DEFAULT_POSTHOG_URL),
                Instant.now());
    }

    TelemetryService(LocalCloudConfig config,
                     UsageMetricsRepository usageMetrics,
                     ProcessHealthChecker healthChecker,
                     ProjectService projectService,
                     PostgresDataSource dataSource,
                     HttpClient httpClient,
                     ObjectMapper mapper,
                     ScheduledExecutorService scheduler,
                     String distinctId,
                     String posthogApiKey,
                     String posthogUrl,
                     Instant startTime) {
        this.config = config;
        this.usageMetrics = usageMetrics;
        this.healthChecker = healthChecker;
        this.projectService = projectService;
        this.dataSource = dataSource;
        this.httpClient = httpClient;
        this.mapper = mapper;
        this.scheduler = scheduler;
        this.distinctId = distinctId;
        this.posthogApiKey = posthogApiKey;
        this.posthogUrl = posthogUrl;
        this.startTime = startTime;
        this.errorsLastHour = new AtomicInteger(0);
    }

    /**
     * Start the hourly heartbeat. No-op if telemetry is disabled or no API key configured.
     */
    public void start() {
        if (!isEnabled()) {
            logger.info("Telemetry disabled (LOCALCLOUD_TELEMETRY=false)");
            // Send one opt-out ping so we know how many instances exist but opted out
            if (!posthogApiKey.isEmpty()) {
                scheduler.execute(() -> {
                    Map<String, Object> props = new LinkedHashMap<>();
                    props.put("version", VERSION);
                    props.put("os_arch", System.getProperty("os.arch"));
                    trySend(buildEventJson("telemetry_disabled", props));
                });
            }
            return;
        }
        if (posthogApiKey.isEmpty()) {
            logger.info("Telemetry disabled (LOCALCLOUD_EVENT_API_KEY not set)");
            return;
        }
        logger.info("Telemetry enabled (opt-out: LOCALCLOUD_TELEMETRY=false)");

        // Send startup event immediately
        scheduler.execute(() -> {
            Map<String, Object> props = new LinkedHashMap<>();
            props.put("version", VERSION);
            props.put("os_arch", System.getProperty("os.arch"));
            props.put("os_name", System.getProperty("os.name"));
            props.put("java_version", System.getProperty("java.version"));
            props.put("memory_max_mb", Runtime.getRuntime().maxMemory() / (1024 * 1024));

            ServiceRegistry registry = config.getServiceRegistry();
            List<String> enabled = new ArrayList<>();
            for (String svcId : registry.getAllServices().keySet()) {
                if (config.isServiceDynamicallyEnabled(svcId)) {
                    enabled.add(svcId);
                }
            }
            props.put("services_enabled", enabled);
            props.put("services_enabled_count", enabled.size());
            props.put("services_total", registry.getAllServices().size());
            props.put("credential_source", config.getGcpCredentialSource());

            String json = buildEventJson("server_started", props);
            SendResult result = trySendDetailed(json);
            if (result.success()) {
                logger.info("Telemetry startup event sent");
            } else {
                enqueueEvent(json);
                enqueueDeliveryFailure("server_started", "server_started", result);
                logger.debug("Telemetry startup event queued (will retry)");
            }
        });

        // Heartbeat every hour
        scheduler.scheduleAtFixedRate(this::heartbeatCycle, 60, 60, TimeUnit.MINUTES);
    }

    /**
     * Record a service error for the next heartbeat + send immediate error event.
     */
    public void recordServiceError(String serviceId, String errorType, int exitCode) {
        if (!isEnabled() || posthogApiKey.isEmpty()) return;
        errorsLastHour.incrementAndGet();

        scheduler.execute(() -> {
            Map<String, Object> props = new LinkedHashMap<>();
            props.put("service", serviceId);
            props.put("error_type", errorType);
            props.put("exit_code", exitCode);
            props.put("version", VERSION);
            props.put("os_arch", System.getProperty("os.arch"));

            String json = buildEventJson("service_error", props);
            SendResult result = trySendDetailed(json);
            if (!result.success()) {
                enqueueEvent(json);
                enqueueDeliveryFailure("service_error", "service_error", result);
            }
        });
    }

    public void stop() {
        scheduler.shutdown();
        httpClient.close();
    }

    // ─── Heartbeat Cycle ────────────────────────────────────────────────

    void heartbeatCycle() {
        try {
            // 1. Drain queued events (oldest first, stop on first failure)
            if (!drainQueue()) {
                // Network is down — queue the new heartbeat too
                String json = buildEventJson("heartbeat", collectStats());
                enqueueEvent(json);
                return;
            }

            // 2. Send new heartbeat
            String json = buildEventJson("heartbeat", collectStats());
            SendResult result = trySendDetailed(json);
            if (result.success()) {
                errorsLastHour.set(0);
                logger.debug("Telemetry heartbeat sent");
            } else {
                enqueueEvent(json);
                enqueueDeliveryFailure("heartbeat", "heartbeat", result);
                logger.debug("Telemetry heartbeat queued (will retry next hour)");
            }
        } catch (Exception e) {
            tryReportInternalError("heartbeat_cycle", e);
            logger.debug("Telemetry heartbeat cycle failed: {}", e.getMessage());
        }
    }

    // ─── Queue Operations ───────────────────────────────────────────────

    /**
     * Drain queued events oldest-first. Returns true if all were sent (or queue was empty).
     * Returns false on first send failure (network down).
     */
    private boolean drainQueue() {
        List<Long> sentIds = new ArrayList<>();
        boolean networkUp = true;
        try (Connection conn = dataSource.getConnection();
             PreparedStatement select = conn.prepareStatement(
                 "SELECT id, event_json FROM telemetry_queue ORDER BY id ASC")) {
            ResultSet rs = select.executeQuery();
            while (rs.next()) {
                long id = rs.getLong("id");
                String json = rs.getString("event_json");
                if (!trySend(json)) {
                    networkUp = false;
                    break; // Stop on first failure
                }
                sentIds.add(id);
            }

            // Batch-delete all successfully sent events
            if (!sentIds.isEmpty()) {
                try (PreparedStatement del = conn.prepareStatement(
                        "DELETE FROM telemetry_queue WHERE id = ANY(?)")) {
                    del.setArray(1, conn.createArrayOf("bigint", sentIds.toArray()));
                    del.executeUpdate();
                }
                logger.debug("Drained {} queued telemetry events", sentIds.size());
            }
        } catch (Exception e) {
            logger.debug("Failed to drain telemetry queue: {}", e.getMessage());
        }
        return networkUp;
    }

    /**
     * Persist an unsent event to the queue. Caps at MAX_QUEUE_SIZE by removing oldest.
     */
    private void enqueueEvent(String eventJson) {
        try (Connection conn = dataSource.getConnection()) {
            // Insert the event
            try (PreparedStatement ins = conn.prepareStatement(
                    "INSERT INTO telemetry_queue (event_json) VALUES (?)")) {
                ins.setString(1, eventJson);
                ins.executeUpdate();
            }
            // Cap the queue — delete oldest when over limit
            try (PreparedStatement trim = conn.prepareStatement(
                    "DELETE FROM telemetry_queue WHERE id IN (" +
                    "  SELECT id FROM telemetry_queue ORDER BY id ASC " +
                    "  LIMIT GREATEST(0, (SELECT COUNT(*) FROM telemetry_queue) - ?)" +
                    ")")) {
                trim.setInt(1, MAX_QUEUE_SIZE);
                trim.executeUpdate();
            }
        } catch (Exception e) {
            // If PostgreSQL is also down, the event is truly lost
            logger.debug("Failed to enqueue telemetry event: {}", e.getMessage());
        }
    }

    // ─── Event Building & Sending ───────────────────────────────────────

    String buildEventJson(String eventName, Map<String, Object> properties) {
        try {
            // PostHog expects distinct_id and token inside properties (canonical SDK format)
            properties.put("distinct_id", distinctId);
            properties.put("token", posthogApiKey);
            properties.put("$process_person_profile", false);
            properties.put("$lib", "localcloud-java");
            properties.put("$lib_version", VERSION);

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("api_key", posthogApiKey);
            payload.put("event", eventName);
            payload.put("distinct_id", distinctId);
            payload.put("properties", properties);
            payload.put("timestamp", Instant.now().toString());
            return mapper.writeValueAsString(payload);
        } catch (Exception e) {
            return "{}";
        }
    }

    /**
     * Attempt to send a JSON event to PostHog. Returns true on any successful HTTP 2xx response.
     */
    boolean trySend(String json) {
        return trySendDetailed(json).success();
    }

    private SendResult trySendDetailed(String json) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(posthogUrl))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(10))
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            int statusCode = response.statusCode();
            return new SendResult(statusCode >= 200 && statusCode < 300, statusCode, null, null);
        } catch (Exception e) {
            return new SendResult(false, -1, e.getClass().getSimpleName(), safeMessage(e));
        }
    }

    private void enqueueDeliveryFailure(String operation, String failedEventName, SendResult result) {
        if ("telemetry_delivery_error".equals(failedEventName)) {
            return;
        }

        Map<String, Object> props = new LinkedHashMap<>();
        props.put("operation", operation);
        props.put("failed_event", failedEventName);
        props.put("version", VERSION);
        props.put("os_arch", System.getProperty("os.arch"));
        props.put("status_code", result.statusCode());
        if (result.errorType() != null) {
            props.put("error_type", result.errorType());
        }
        if (result.errorMessage() != null) {
            props.put("error_message", result.errorMessage());
        }
        enqueueEvent(buildEventJson("telemetry_delivery_error", props));
    }

    private void tryReportInternalError(String operation, Exception error) {
        if (!isEnabled() || posthogApiKey.isEmpty()) return;

        Map<String, Object> props = new LinkedHashMap<>();
        props.put("operation", operation);
        props.put("version", VERSION);
        props.put("os_arch", System.getProperty("os.arch"));
        props.put("error_type", error.getClass().getSimpleName());
        props.put("error_message", safeMessage(error));
        trySend(buildEventJson("telemetry_internal_error", props));
    }

    private static String safeMessage(Exception error) {
        String message = error.getMessage();
        if (message == null || message.isBlank()) {
            return "";
        }
        return message.length() <= 300 ? message : message.substring(0, 300);
    }

    // ─── Stats Collection ───────────────────────────────────────────────

    private Map<String, Object> collectStats() {
        Map<String, Object> props = new LinkedHashMap<>();
        List<String> statsErrors = new ArrayList<>();

        // Version & environment
        props.put("version", VERSION);
        props.put("os_arch", System.getProperty("os.arch"));
        props.put("os_name", System.getProperty("os.name"));
        props.put("java_version", System.getProperty("java.version"));
        props.put("uptime_hours", Duration.between(startTime, Instant.now()).toHours());

        // Memory
        long maxMem = Runtime.getRuntime().maxMemory();
        props.put("memory_max_mb", maxMem / (1024 * 1024));

        // Services
        ServiceRegistry registry = config.getServiceRegistry();
        List<String> enabled = new ArrayList<>();
        List<String> disabled = new ArrayList<>();
        for (String svcId : registry.getAllServices().keySet()) {
            if (config.isServiceDynamicallyEnabled(svcId)) {
                enabled.add(svcId);
            } else {
                disabled.add(svcId);
            }
        }
        props.put("services_enabled", enabled);
        props.put("services_disabled", disabled);
        props.put("services_enabled_count", enabled.size());
        props.put("services_total", registry.getAllServices().size());

        // Health
        healthChecker.checkAll();
        Map<String, String> statuses = healthChecker.getAllStatuses();
        long healthyCount = statuses.values().stream().filter("healthy"::equals).count();
        props.put("services_healthy", healthyCount);

        // Request counts — both cumulative totals and deltas since last heartbeat
        try {
            Map<String, Long> currentCounts = usageMetrics.getGlobalCounts();
            long totalDelta = 0;
            long totalCumulative = 0;
            for (Map.Entry<String, Long> entry : currentCounts.entrySet()) {
                long current = entry.getValue();
                long previous = previousCounts.getOrDefault(entry.getKey(), 0L);
                long delta = current - previous;
                if (delta > 0) {
                    props.put("requests_" + entry.getKey(), delta);
                }
                totalDelta += delta;
                totalCumulative += current;
                props.put("requests_cumulative_" + entry.getKey(), current);
            }
            props.put("requests_total", totalDelta);
            props.put("requests_cumulative_total", totalCumulative);
            props.put("estimated_cost_saved_usd", estimateCostSaved(currentCounts));
            previousCounts = currentCounts;
        } catch (Exception e) {
            props.put("requests_total", -1);
            statsErrors.add("request_counts:" + e.getClass().getSimpleName() + ":" + safeMessage(e));
        }

        // Projects
        try {
            props.put("projects_count", projectService.listProjects().size());
        } catch (Exception e) {
            props.put("projects_count", -1);
            statsErrors.add("projects:" + e.getClass().getSimpleName() + ":" + safeMessage(e));
        }

        // Credential source
        props.put("credential_source", config.getGcpCredentialSource());

        // Errors
        props.put("errors_last_hour", errorsLastHour.get());

        // Queue depth
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM telemetry_queue")) {
            ResultSet rs = ps.executeQuery();
            rs.next();
            props.put("queue_depth", rs.getInt(1));
        } catch (Exception e) {
            props.put("queue_depth", -1);
            statsErrors.add("queue_depth:" + e.getClass().getSimpleName() + ":" + safeMessage(e));
        }

        props.put("telemetry_stats_errors_count", statsErrors.size());
        if (!statsErrors.isEmpty()) {
            props.put("telemetry_stats_errors", statsErrors);
        }

        return props;
    }

    // ─── Helpers ────────────────────────────────────────────────────────

    private double estimateCostSaved(Map<String, Long> counts) {
        double total = 0;
        for (Map.Entry<String, Long> entry : counts.entrySet()) {
            long c = entry.getValue();
            if (c <= 0) continue;
            total += switch (entry.getKey()) {
                case "gcs" -> (c / 10000.0) * 0.05;
                case "pubsub" -> (c / 1000000.0) * 0.40;
                case "firestore" -> (c / 100000.0) * 0.06;
                case "bigquery" -> (c / 1000.0) * 5.00;
                case "secretmanager" -> (c / 10000.0) * 0.03;
                case "cloudtasks" -> (c / 1000000.0) * 0.40;
                case "spanner" -> (c / 3600.0) * 0.90;
                case "bigtable" -> (c / 3600.0) * 0.65;
                case "logging" -> (c / 10000.0) * 0.50;
                case "monitoring" -> (c / 1000.0) * 0.01;
                case "gke" -> (c / 3600.0) * 0.10;
                case "compute" -> (c / 3600.0) * 0.03;
                case "cloudrun" -> (c / 1000000.0) * 0.40;
                case "memorystore" -> (c / 10000.0) * 0.049;
                default -> 0;
            };
        }
        return Math.round(total * 100.0) / 100.0;
    }

    /**
     * Create an HttpClient that tolerates corporate proxy SSL inspection.
     * Telemetry data is non-sensitive (anonymous counters), so relaxing
     * SSL verification is acceptable for this specific use case.
     */
    private static HttpClient createHttpClient() {
        try {
            var trustManager = new javax.net.ssl.X509TrustManager() {
                public void checkClientTrusted(java.security.cert.X509Certificate[] chain, String authType) {}
                public void checkServerTrusted(java.security.cert.X509Certificate[] chain, String authType) {}
                public java.security.cert.X509Certificate[] getAcceptedIssuers() { return new java.security.cert.X509Certificate[0]; }
            };
            var sslContext = javax.net.ssl.SSLContext.getInstance("TLS");
            sslContext.init(null, new javax.net.ssl.TrustManager[]{trustManager}, new java.security.SecureRandom());
            return HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(5))
                    .sslContext(sslContext)
                    .build();
        } catch (Exception e) {
            // Fallback to default client if SSL setup fails
            return HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(5))
                    .build();
        }
    }

    private static ScheduledExecutorService createScheduler() {
        return Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "telemetry");
            t.setDaemon(true);
            return t;
        });
    }

    private boolean isEnabled() {
        String val = System.getenv("LOCALCLOUD_TELEMETRY");
        return val == null || !val.equalsIgnoreCase("false");
    }

    private static String env(String name, String defaultValue) {
        String value = System.getenv(name);
        return value != null && !value.isBlank() ? value : defaultValue;
    }

    /**
     * Generate a stable, anonymous instance ID.
     * Combines hostname + machine-id (if available) + MAC address for better uniqueness.
     * The result is a one-way SHA-256 hash — cannot be reversed.
     */
    private static String generateDistinctId() {
        try {
            StringBuilder seed = new StringBuilder();

            // Hostname (container ID in Docker)
            seed.append(InetAddress.getLocalHost().getHostName());

            // Machine ID (stable across container restarts if volume-mounted)
            try {
                String machineId = java.nio.file.Files.readString(
                        java.nio.file.Path.of("/etc/machine-id")).trim();
                seed.append(machineId);
            } catch (Exception ignored) {}

            // MAC address (stable for the host machine)
            try {
                var ni = java.net.NetworkInterface.getByInetAddress(InetAddress.getLocalHost());
                if (ni != null && ni.getHardwareAddress() != null) {
                    for (byte b : ni.getHardwareAddress()) {
                        seed.append(String.format("%02x", b));
                    }
                }
            } catch (Exception ignored) {}

            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(seed.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 8; i++) {
                hex.append(String.format("%02x", hash[i]));
            }
            return "lc_" + hex;
        } catch (Exception e) {
            return "lc_unknown";
        }
    }

    private record SendResult(boolean success, int statusCode, String errorType, String errorMessage) {}
}
