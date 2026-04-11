package com.localcloud.gateway;

import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.localcloud.config.LocalCloudConfig;
import com.localcloud.config.ServiceRegistry;
import com.localcloud.config.ServiceRegistry.HealthCheckDef;
import com.localcloud.config.ServiceRegistry.ServiceDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Polls external emulator processes to check if they're healthy.
 * Uses the {@link ServiceRegistry} to determine health check strategy
 * for each service (TCP vs HTTP, ports, paths).
 *
 * <p>External emulators are checked via HTTP GET or TCP connect based on
 * their health check definition. In-process facades are always reported
 * as "healthy" when the server is running.</p>
 */
public class ProcessHealthChecker implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(ProcessHealthChecker.class);

    private static final String HEALTHY = "healthy";
    private static final String UNHEALTHY = "unhealthy";
    private static final String DISABLED = "disabled";

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(2);

    private final LocalCloudConfig config;
    private final ServiceRegistry registry;
    private final ConcurrentHashMap<String, String> statuses = new ConcurrentHashMap<>();
    private final HttpClient httpClient;

    public ProcessHealthChecker(LocalCloudConfig config, ServiceRegistry registry) {
        this.config = config;
        this.registry = registry;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .build();
    }

    /**
     * Polls all configured emulators and updates the internal status map.
     */
    public void checkAll() {
        for (Map.Entry<String, ServiceDefinition> entry : registry.getAllServices().entrySet()) {
            String serviceName = entry.getKey();
            ServiceDefinition def = entry.getValue();

            if (!config.isServiceEnabled(serviceName)) {
                statuses.put(serviceName, DISABLED);
                continue;
            }

            if (def.isFacade()) {
                // In-process facades are always healthy when the server is running
                statuses.put(serviceName, HEALTHY);
            } else if (def.isExternal()) {
                // External services: use health check definition
                HealthCheckDef hc = def.healthCheck();
                if (hc == null) {
                    // No health check defined, try TCP on the service port
                    checkTcp(serviceName, "localhost", def.port());
                } else {
                    int checkPort = hc.port() != null ? hc.port() : def.port();
                    if ("http".equals(hc.type())) {
                        String path = hc.path() != null
                                ? hc.path().replace("{projectId}", config.getProjectId())
                                : "";
                        String url = "http://localhost:" + checkPort + path;
                        checkHttp(serviceName, url, false);
                    } else {
                        // tcp (default)
                        checkTcp(serviceName, "localhost", checkPort);
                    }
                }
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

    @Override
    public void close() {
        httpClient.close();
    }
}
