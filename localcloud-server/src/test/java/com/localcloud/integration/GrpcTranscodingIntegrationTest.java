package com.localcloud.integration;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.grpc.GrpcService;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;
import com.localcloud.emulators.cloudtasks.CloudTasksEmulator;
import com.localcloud.emulators.logging.LoggingEmulator;
import com.localcloud.emulators.monitoring.MonitoringEmulator;
import com.localcloud.emulators.secretmanager.SecretManagerEmulator;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.RegisterExtension;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies that gRPC-JSON transcoding is active for all facade services.
 * Each test makes an HTTP REST call to the transcoded endpoint and asserts
 * it does NOT return 501 (which would mean the endpoint is not registered).
 *
 * <p>This test class registers all four always-enabled gRPC facades
 * (Secret Manager, Cloud Tasks, Logging, Monitoring) and proves their
 * REST paths are reachable.
 */
class GrpcTranscodingIntegrationTest {

    private static TestDataSource testDs;

    @RegisterExtension
    static ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            testDs = TestDataSource.create("transcoding_test");
            var ds = testDs.getDataSource();

            var secretManager = new SecretManagerEmulator(ds);
            secretManager.start();
            var cloudTasks = new CloudTasksEmulator(ds);
            cloudTasks.start();
            var logging = new LoggingEmulator(ds);
            logging.start();
            var monitoring = new MonitoringEmulator(ds);
            monitoring.start();

            sb.service(GrpcService.builder()
                    .addService(secretManager.getServiceImpl())
                    .addService(cloudTasks.getServiceImpl())
                    .addService(logging.getLoggingService())
                    .addService(monitoring.getMonitoringService())
                    .enableHttpJsonTranscoding(true)
                    .build());
        }
    };

    private HttpClient httpClient;

    @BeforeEach
    void setUp() {
        httpClient = HttpClient.newHttpClient();
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + server.httpPort();
    }

    private HttpResponse<String> get(String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl() + path))
                .GET()
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path, String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl() + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    // -----------------------------------------------------------------------
    // Secret Manager REST endpoints are reachable
    // -----------------------------------------------------------------------

    @Test
    void secretManager_listSecrets_responds() throws Exception {
        HttpResponse<String> response = get("/v1/projects/test/secrets");
        assertNotEquals(501, response.statusCode(),
                "Secret Manager REST should be transcoded, not 501");
        assertEquals(200, response.statusCode());
    }

    // -----------------------------------------------------------------------
    // Cloud Tasks REST endpoints are reachable
    // -----------------------------------------------------------------------

    @Test
    void cloudTasks_listQueues_responds() throws Exception {
        HttpResponse<String> response = get("/v2/projects/test/locations/us-central1/queues");
        assertNotEquals(501, response.statusCode(),
                "Cloud Tasks REST should be transcoded, not 501");
        assertEquals(200, response.statusCode());
    }

    // -----------------------------------------------------------------------
    // Logging REST endpoints are reachable
    // -----------------------------------------------------------------------

    @Test
    void logging_listEntries_responds() throws Exception {
        HttpResponse<String> response = post("/v2/entries:list",
                "{\"resourceNames\":[\"projects/test\"]}");
        assertNotEquals(501, response.statusCode(),
                "Logging REST should be transcoded, not 501");
        assertEquals(200, response.statusCode());
    }

    // -----------------------------------------------------------------------
    // Monitoring REST endpoints are reachable
    // -----------------------------------------------------------------------

    @Test
    void monitoring_listTimeSeries_responds() throws Exception {
        HttpResponse<String> response = get("/v3/projects/test/timeSeries");
        assertNotEquals(501, response.statusCode(),
                "Monitoring REST should be transcoded, not 501");
        // May return 200 or 400 depending on required query params
        assertTrue(response.statusCode() < 500,
                "Should not be a server error: " + response.statusCode());
    }

    @Test
    void monitoring_listMetricDescriptors_responds() throws Exception {
        HttpResponse<String> response = get("/v3/projects/test/metricDescriptors");
        assertNotEquals(501, response.statusCode(),
                "Monitoring REST should be transcoded, not 501");
        assertEquals(200, response.statusCode());
    }
}
