package com.localcloud.gateway;

import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.localcloud.config.LocalCloudConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Polls external emulator processes to check if they're healthy.
 * Used by HealthCheckService to provide aggregated health status.
 *
 * <p>External emulators (gcs, pubsub, firestore, bigtable, spanner, bigquery)
 * are checked via HTTP GET or TCP connect. In-process facades (secretmanager,
 * cloudtasks, logging, monitoring) are always reported as "healthy" when the
 * server is running.</p>
 */
public class ProcessHealthChecker {

    private static final Logger logger = LoggerFactory.getLogger(ProcessHealthChecker.class);

    private static final String HEALTHY = "healthy";
    private static final String UNHEALTHY = "unhealthy";
    private static final String DISABLED = "disabled";

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(2);

    /** Services that run in-process and are always healthy if the server is up. */
    private static final Set<String> IN_PROCESS_SERVICES = Set.of(
            "secretmanager", "cloudtasks", "logging", "monitoring"
    );

    private final LocalCloudConfig config;
    private final ConcurrentHashMap<String, String> statuses = new ConcurrentHashMap<>();
    private final HttpClient httpClient;

    public ProcessHealthChecker(LocalCloudConfig config) {
        this.config = config;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .build();
    }

    /**
     * Polls all configured emulators and updates the internal status map.
     */
    public void checkAll() {
        // fake-gcs-server uses HTTPS with self-signed cert; use TCP check instead
        checkTcp("gcs", "localhost", 4443);
        checkHttp("pubsub", "http://localhost:8085", false);
        checkHttp("firestore", "http://localhost:8086", false);
        checkTcp("bigtable", "localhost", 8087);
        checkHttp("spanner", "http://localhost:9020/v1/projects/test/instances", false);
        checkHttp("bigquery", "http://localhost:9050/bigquery/v2/projects/test/datasets", false);

        // In-process facades are always healthy when the server is running
        for (String service : IN_PROCESS_SERVICES) {
            if (config.isServiceEnabled(service)) {
                statuses.put(service, HEALTHY);
            } else {
                statuses.put(service, DISABLED);
            }
        }
    }

    /**
     * Returns the health status of a specific service.
     *
     * @param service the service name
     * @return "healthy", "unhealthy", or "disabled"
     */
    public String getStatus(String service) {
        if (!config.isServiceEnabled(service)) {
            return DISABLED;
        }
        return statuses.getOrDefault(service, UNHEALTHY);
    }

    /**
     * Returns an unmodifiable map of all service statuses.
     *
     * @return map of service name to status string
     */
    public Map<String, String> getAllStatuses() {
        return Collections.unmodifiableMap(statuses);
    }

    // ---- Internal health-check methods ----

    private void checkHttp(String service, String url, boolean requireOk) {
        if (!config.isServiceEnabled(service)) {
            statuses.put(service, DISABLED);
            return;
        }

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(REQUEST_TIMEOUT)
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString());

            if (requireOk) {
                statuses.put(service, response.statusCode() == 200 ? HEALTHY : UNHEALTHY);
            } else {
                // Any non-error response (< 500) counts as healthy
                statuses.put(service, response.statusCode() < 500 ? HEALTHY : UNHEALTHY);
            }
        } catch (Exception e) {
            logger.debug("Health check failed for {}: {}", service, e.getMessage());
            statuses.put(service, UNHEALTHY);
        }
    }

    private void checkTcp(String service, String host, int port) {
        if (!config.isServiceEnabled(service)) {
            statuses.put(service, DISABLED);
            return;
        }

        try (Socket socket = new Socket()) {
            socket.connect(new java.net.InetSocketAddress(host, port),
                    (int) CONNECT_TIMEOUT.toMillis());
            statuses.put(service, HEALTHY);
        } catch (Exception e) {
            logger.debug("Health check failed for {}: {}", service, e.getMessage());
            statuses.put(service, UNHEALTHY);
        }
    }
}
