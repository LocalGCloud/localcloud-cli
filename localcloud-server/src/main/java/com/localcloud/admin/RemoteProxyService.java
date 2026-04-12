package com.localcloud.admin;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.Map;

/**
 * Proxies requests to real GCP APIs when a service is configured in "remote" mode.
 * Uses the {@link CredentialBroker} for authentication and {@link ServiceRoutingRepository}
 * to look up per-service routing configuration.
 */
public class RemoteProxyService {

    private static final Logger logger = LoggerFactory.getLogger(RemoteProxyService.class);
    private static final int TIMEOUT_MS = 30_000;

    /**
     * Response from a proxied request.
     */
    public record ProxyResponse(int statusCode, String body) {}

    private static final Map<String, String> GCP_BASE_URLS = Map.ofEntries(
            Map.entry("gcs", "https://storage.googleapis.com"),
            Map.entry("bigquery", "https://bigquery.googleapis.com"),
            Map.entry("pubsub", "https://pubsub.googleapis.com"),
            Map.entry("firestore", "https://firestore.googleapis.com"),
            Map.entry("spanner", "https://spanner.googleapis.com"),
            Map.entry("secretmanager", "https://secretmanager.googleapis.com"),
            Map.entry("cloudtasks", "https://cloudtasks.googleapis.com")
    );

    private final CredentialBroker credentialBroker;
    private final ServiceRoutingRepository routingRepository;

    public RemoteProxyService(CredentialBroker credentialBroker, ServiceRoutingRepository routingRepository) {
        this.credentialBroker = credentialBroker;
        this.routingRepository = routingRepository;
    }

    /**
     * Proxy a request to the real GCP API for a given service.
     *
     * @param serviceId the service identifier (e.g. "gcs", "bigquery")
     * @param method    HTTP method (GET, POST, PUT, DELETE, PATCH)
     * @param path      the request path (e.g. "/v1/projects/my-project/topics")
     * @param body      request body (may be null for GET/DELETE)
     * @param projectId the local project ID for routing lookup
     * @return a {@link ProxyResponse} with status code and body, or null if the service is not in remote mode
     * @throws IllegalStateException if credentials are not valid
     */
    public ProxyResponse proxyRequest(String serviceId, String method, String path,
                                       String body, String projectId) {
        // Look up routing config
        Map<String, String> routingConfig;
        try {
            routingConfig = routingRepository.get(projectId, serviceId);
        } catch (SQLException e) {
            logger.error("Failed to look up routing config for {}/{}: {}", projectId, serviceId, e.getMessage());
            throw new RuntimeException("Failed to look up routing configuration", e);
        }

        // If no routing config or mode is not "remote", return null (not proxied)
        if (routingConfig == null || !"remote".equals(routingConfig.get("mode"))) {
            return null;
        }

        // Validate credentials
        if (!credentialBroker.isValid()) {
            throw new IllegalStateException("Cannot proxy to remote GCP: credentials are not valid. "
                    + "Configure LOCALCLOUD_GCP_CREDENTIAL_SOURCE to enable remote mode.");
        }

        String accessToken = credentialBroker.getAccessToken();
        if (accessToken == null) {
            throw new IllegalStateException("Cannot proxy to remote GCP: unable to obtain access token. "
                    + "Ensure ADC credentials with an access_token are configured.");
        }

        // Determine base URL
        String baseUrl = GCP_BASE_URLS.get(serviceId);
        if (baseUrl == null) {
            logger.warn("No GCP base URL mapped for service: {}", serviceId);
            throw new IllegalArgumentException("Unsupported service for remote proxy: " + serviceId);
        }

        // Rewrite project ID in path if remote_project is set
        // Only replace in the /projects/{id}/ segment to avoid corrupting resource names
        String remoteProject = routingConfig.get("remote_project");
        String effectivePath = path;
        if (remoteProject != null && !remoteProject.isBlank()) {
            effectivePath = path.replaceFirst(
                "/projects/" + java.util.regex.Pattern.quote(projectId) + "/",
                "/projects/" + remoteProject + "/");
        }

        String gcpEndpoint = baseUrl + effectivePath;

        try {
            HttpURLConnection conn = (HttpURLConnection) URI.create(gcpEndpoint).toURL().openConnection();
            conn.setRequestMethod(method);
            conn.setRequestProperty("Authorization", "Bearer " + accessToken);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);

            // Send body for methods that support it
            if (body != null && !body.isBlank()
                    && ("POST".equals(method) || "PUT".equals(method) || "PATCH".equals(method))) {
                conn.setDoOutput(true);
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(body.getBytes(StandardCharsets.UTF_8));
                }
            }

            int statusCode = conn.getResponseCode();
            InputStream is = (statusCode >= 200 && statusCode < 300)
                    ? conn.getInputStream()
                    : conn.getErrorStream();

            String responseBody = "";
            if (is != null) {
                responseBody = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            }

            logger.info("[REMOTE] {} {} -> {} ({})", method, path, gcpEndpoint, statusCode);

            conn.disconnect();
            return new ProxyResponse(statusCode, responseBody);

        } catch (IOException e) {
            logger.error("[REMOTE] {} {} -> {} FAILED: {}", method, path, gcpEndpoint, e.getMessage());
            throw new RuntimeException("Remote proxy request failed: " + e.getMessage(), e);
        }
    }
}
