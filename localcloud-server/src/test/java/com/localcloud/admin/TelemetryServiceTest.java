package com.localcloud.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.localcloud.config.LocalCloudConfig;
import com.localcloud.config.ServiceRegistry;
import com.localcloud.config.ServiceRegistry.ServiceDefinition;
import com.localcloud.gateway.ProcessHealthChecker;
import com.localcloud.persistence.PostgresDataSource;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class TelemetryServiceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void trySendAcceptsAnySuccessful2xxCaptureResponse() throws Exception {
        try (CapturingPostHogServer posthog = new CapturingPostHogServer()) {
            posthog.respondWith(202);
            TelemetryService service = newTelemetryService(posthog.captureUrl(), telemetryDataSource("two_xx"));

            Map<String, Object> props = new LinkedHashMap<>();
            props.put("version", "test");
            String json = service.buildEventJson("heartbeat", props);

            assertTrue(service.trySend(json));

            CapturedRequest request = posthog.takeRequest();
            assertEquals("/i/v0/e/", request.path());
            assertEquals("POST", request.method());
            assertPostHogPayload(request.body(), "heartbeat");
        }
    }

    @Test
    void trySendRejectsNon2xxCaptureResponse() throws Exception {
        try (CapturingPostHogServer posthog = new CapturingPostHogServer()) {
            posthog.respondWith(500);
            TelemetryService service = newTelemetryService(posthog.captureUrl(), telemetryDataSource("non_two_xx"));

            Map<String, Object> props = new LinkedHashMap<>();
            props.put("version", "test");

            assertFalse(service.trySend(service.buildEventJson("heartbeat", props)));
        }
    }

    @Test
    void heartbeatCyclePostsExpectedPostHogCapturePayload() throws Exception {
        try (CapturingPostHogServer posthog = new CapturingPostHogServer()) {
            TelemetryService service = newTelemetryService(posthog.captureUrl(), telemetryDataSource("heartbeat_payload"));

            service.heartbeatCycle();

            CapturedRequest request = posthog.takeRequest();
            assertEquals("/i/v0/e/", request.path());
            assertEquals("POST", request.method());
            assertPostHogPayload(request.body(), "heartbeat");

            JsonNode payload = MAPPER.readTree(request.body());
            JsonNode properties = payload.path("properties");
            assertEquals(1, properties.path("services_enabled_count").asInt());
            assertEquals(2, properties.path("services_total").asInt());
            assertEquals(0, properties.path("telemetry_stats_errors_count").asInt());
        }
    }

    @Test
    void heartbeatCycleRetriesQueuedHeartbeatAndReportsDeliveryFailure() throws Exception {
        try (CapturingPostHogServer posthog = new CapturingPostHogServer()) {
            posthog.respondWith(500, 200, 200, 200);
            TelemetryDataSource telemetryDb = telemetryDataSource("heartbeat_retry");
            TelemetryService service = newTelemetryService(posthog.captureUrl(), telemetryDb);

            service.heartbeatCycle();

            CapturedRequest failedHeartbeat = posthog.takeRequest();
            assertPostHogPayload(failedHeartbeat.body(), "heartbeat");
            assertEquals(2, telemetryDb.queueDepth(), "failed heartbeat plus delivery error should be queued");

            service.heartbeatCycle();

            CapturedRequest retriedHeartbeat = posthog.takeRequest();
            CapturedRequest deliveryError = posthog.takeRequest();
            CapturedRequest currentHeartbeat = posthog.takeRequest();

            assertPostHogPayload(retriedHeartbeat.body(), "heartbeat");
            assertPostHogPayload(deliveryError.body(), "telemetry_delivery_error");
            assertPostHogPayload(currentHeartbeat.body(), "heartbeat");

            JsonNode failureProps = MAPPER.readTree(deliveryError.body()).path("properties");
            assertEquals("heartbeat", failureProps.path("operation").asText());
            assertEquals("heartbeat", failureProps.path("failed_event").asText());
            assertEquals(500, failureProps.path("status_code").asInt());
        }
    }

    @Test
    void heartbeatPayloadReportsStatsCollectionErrors() throws Exception {
        try (CapturingPostHogServer posthog = new CapturingPostHogServer()) {
            UsageMetricsRepository usageMetrics = mock(UsageMetricsRepository.class);
            when(usageMetrics.getGlobalCounts()).thenThrow(new RuntimeException("metrics unavailable"));
            TelemetryService service = newTelemetryService(
                    posthog.captureUrl(),
                    telemetryDataSource("stats_errors"),
                    usageMetrics);

            service.heartbeatCycle();

            JsonNode properties = MAPPER.readTree(posthog.takeRequest().body()).path("properties");
            assertEquals(1, properties.path("telemetry_stats_errors_count").asInt());
            assertTrue(properties.path("telemetry_stats_errors").get(0).asText().startsWith("request_counts:RuntimeException"));
        }
    }

    private static void assertPostHogPayload(String body, String eventName) throws Exception {
        JsonNode payload = MAPPER.readTree(body);
        assertEquals("test-key", payload.path("api_key").asText());
        assertEquals(eventName, payload.path("event").asText());
        assertEquals("lc_test", payload.path("distinct_id").asText());
        assertTrue(payload.hasNonNull("timestamp"));

        JsonNode properties = payload.path("properties");
        assertEquals("lc_test", properties.path("distinct_id").asText());
        assertEquals("test-key", properties.path("token").asText());
        assertFalse(properties.path("$process_person_profile").asBoolean());
        assertEquals("localcloud-java", properties.path("$lib").asText());
        assertEquals("1.0.0", properties.path("$lib_version").asText());
    }

    private static TelemetryService newTelemetryService(String posthogUrl, TelemetryDataSource telemetryDb) throws Exception {
        return newTelemetryService(posthogUrl, telemetryDb, usageMetrics(Map.of("gcs", 3L)));
    }

    private static TelemetryService newTelemetryService(String posthogUrl,
                                                        TelemetryDataSource telemetryDb,
                                                        UsageMetricsRepository usageMetrics) throws Exception {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "telemetry-test");
            t.setDaemon(true);
            return t;
        });
        return new TelemetryService(
                localCloudConfig(),
                usageMetrics,
                healthChecker(),
                projectService(),
                telemetryDb.dataSource(),
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build(),
                new ObjectMapper(),
                scheduler,
                "lc_test",
                "test-key",
                posthogUrl,
                Instant.parse("2026-06-28T00:00:00Z"));
    }

    private static LocalCloudConfig localCloudConfig() {
        LocalCloudConfig config = mock(LocalCloudConfig.class);
        ServiceRegistry registry = mock(ServiceRegistry.class);
        ServiceDefinition gcs = mock(ServiceDefinition.class);
        ServiceDefinition pubsub = mock(ServiceDefinition.class);
        when(registry.getAllServices()).thenReturn(Map.of("gcs", gcs, "pubsub", pubsub));
        when(config.getServiceRegistry()).thenReturn(registry);
        when(config.isServiceDynamicallyEnabled(anyString())).thenAnswer(invocation -> "gcs".equals(invocation.getArgument(0)));
        when(config.getGcpCredentialSource()).thenReturn("test");
        return config;
    }

    private static UsageMetricsRepository usageMetrics(Map<String, Long> counts) throws Exception {
        UsageMetricsRepository usageMetrics = mock(UsageMetricsRepository.class);
        when(usageMetrics.getGlobalCounts()).thenReturn(counts);
        return usageMetrics;
    }

    private static ProcessHealthChecker healthChecker() {
        ProcessHealthChecker healthChecker = mock(ProcessHealthChecker.class);
        when(healthChecker.getAllStatuses()).thenReturn(Map.of("gcs", "healthy", "pubsub", "stopped"));
        return healthChecker;
    }

    private static ProjectService projectService() throws Exception {
        ProjectService projectService = mock(ProjectService.class);
        when(projectService.listProjects()).thenReturn(List.of());
        return projectService;
    }

    private static TelemetryDataSource telemetryDataSource(String name) throws Exception {
        String jdbcUrl = "jdbc:h2:mem:" + name + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1";
        try (Connection conn = DriverManager.getConnection(jdbcUrl)) {
            conn.createStatement().execute("""
                    CREATE TABLE telemetry_queue (
                      id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
                      event_json CLOB NOT NULL,
                      created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                    )
                    """);
        }
        PostgresDataSource dataSource = mock(PostgresDataSource.class);
        when(dataSource.getConnection()).thenAnswer(invocation -> DriverManager.getConnection(jdbcUrl));
        return new TelemetryDataSource(jdbcUrl, dataSource);
    }

    private record TelemetryDataSource(String jdbcUrl, PostgresDataSource dataSource) {
        int queueDepth() throws Exception {
            try (Connection conn = DriverManager.getConnection(jdbcUrl);
                 var rs = conn.createStatement().executeQuery("SELECT COUNT(*) FROM telemetry_queue")) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private record CapturedRequest(String method, String path, String body) {}

    private static final class CapturingPostHogServer implements AutoCloseable {
        private final HttpServer server;
        private final LinkedBlockingQueue<CapturedRequest> requests = new LinkedBlockingQueue<>();
        private final Queue<Integer> statuses = new ConcurrentLinkedQueue<>();

        private CapturingPostHogServer() throws IOException {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            server.createContext("/i/v0/e/", exchange -> {
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                requests.add(new CapturedRequest(
                        exchange.getRequestMethod(),
                        exchange.getRequestURI().getPath(),
                        body));
                int status = statuses.isEmpty() ? 200 : statuses.remove();
                byte[] response = "{}".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(status, response.length);
                exchange.getResponseBody().write(response);
                exchange.close();
            });
            server.start();
        }

        private String captureUrl() {
            return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/i/v0/e/").toString();
        }

        private void respondWith(int... statusCodes) {
            for (int statusCode : statusCodes) {
                statuses.add(statusCode);
            }
        }

        private CapturedRequest takeRequest() throws InterruptedException {
            CapturedRequest request = requests.poll(2, TimeUnit.SECONDS);
            assertNotNull(request, "expected PostHog capture request");
            return request;
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
