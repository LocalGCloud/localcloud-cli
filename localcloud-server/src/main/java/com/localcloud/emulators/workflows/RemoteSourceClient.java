package com.localcloud.emulators.workflows;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;

/**
 * HTTP client for the remote workflow source API.
 * Discovers workflows, fetches source YAML, lists environments and services.
 */
public class RemoteSourceClient {
    private static final Logger logger = LoggerFactory.getLogger(RemoteSourceClient.class);
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private final String baseUrl;
    private final String username;

    public RemoteSourceClient(String baseUrl, String username) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.username = username;
    }

    /**
     * Validate connection by listing environments.
     * @return number of environments found
     */
    public int validateConnection() throws Exception {
        String url = baseUrl + "/api/list";
        List<?> envs = getJson(url, List.class);
        return envs != null ? envs.size() : 0;
    }

    /**
     * List workflows available for the configured user.
     */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> listWorkflows() throws Exception {
        String url = baseUrl + "/api/workflows/list?user=" + username;
        List<Map<String, Object>> result = getJson(url, List.class);
        return result != null ? result : List.of();
    }

    /**
     * Fetch workflow YAML source for a specific workflow.
     */
    @SuppressWarnings("unchecked")
    public String getWorkflowSource(String workflowName) throws Exception {
        String url = baseUrl + "/api/workflows/source?user=" + username + "&workflow=" + workflowName;
        Map<String, Object> result = getJson(url, Map.class);
        if (result == null || !result.containsKey("source")) {
            throw new RuntimeException("Workflow not found: " + workflowName);
        }
        return (String) result.get("source");
    }

    /**
     * List all environments from the remote source.
     */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> listEnvironments() throws Exception {
        String url = baseUrl + "/api/list";
        List<Map<String, Object>> result = getJson(url, List.class);
        return result != null ? result : List.of();
    }

    /**
     * Get service endpoints for a specific environment.
     */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> getServiceEndpoints(String envId) throws Exception {
        String url = baseUrl + "/api/status/" + envId;
        Map<String, Object> result = getJson(url, Map.class);
        if (result == null) return List.of();
        List<Map<String, Object>> services = (List<Map<String, Object>>) result.get("services");
        return services != null ? services : List.of();
    }

    /**
     * Get services for the user's environments.
     */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> getServicesForUser() throws Exception {
        List<Map<String, Object>> envs = listEnvironments();
        List<Map<String, Object>> allServices = new ArrayList<>();

        for (Map<String, Object> env : envs) {
            String owner = (String) env.get("owner");
            if (username.equals(owner)) {
                Object envIdObj = env.get("id");
                String envId = envIdObj != null ? String.valueOf(envIdObj) : null;
                if (envId != null) {
                    try {
                        List<Map<String, Object>> services = getServiceEndpoints(envId);
                        for (Map<String, Object> svc : services) {
                            svc.put("envId", envId);
                            allServices.add(svc);
                        }
                    } catch (Exception e) {
                        logger.warn("Failed to get services for env {}: {}", envId, e.getMessage());
                    }
                }
            }
        }
        return allServices;
    }

    private <T> T getJson(String url, Class<T> type) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(15))
                .GET()
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 400) {
            throw new RuntimeException("Remote API error: " + response.statusCode() + " " + response.body());
        }
        return mapper.readValue(response.body(), type);
    }
}
