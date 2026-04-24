package com.localcloud.emulators.workflows.connector;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.localcloud.emulators.workflows.engine.ExecutionContext;

/**
 * Maps googleapis.SERVICE.VERSION.RESOURCE.METHOD connector calls
 * to HTTP requests against LocalCloud emulators.
 */
public class ConnectorRegistry {
    private static final Logger logger = LoggerFactory.getLogger(ConnectorRegistry.class);
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10)).build();

    private final Map<String, ConnectorDef> connectors = new HashMap<>();
    private java.util.function.BiFunction<String, Map<String, Object>, Object> childWorkflowRunner;

    private static final ThreadLocal<ExecutionContext> currentContext = new ThreadLocal<>();

    public static void setCurrentContext(ExecutionContext ctx) {
        currentContext.set(ctx);
    }

    public static ExecutionContext getCurrentContext() {
        return currentContext.get();
    }

    public static void clearCurrentContext() {
        currentContext.remove();
    }

    public ConnectorRegistry() {
        registerDefaults();
    }

    public void setChildWorkflowRunner(java.util.function.BiFunction<String, Map<String, Object>, Object> runner) {
        this.childWorkflowRunner = runner;
    }

    private void registerDefaults() {
        // Cloud Storage
        register("googleapis.storage.v1.objects.list", "GET", "http://localhost:4443/storage/v1/b/{bucket}/o");
        register("googleapis.storage.v1.objects.get", "GET", "http://localhost:4443/storage/v1/b/{bucket}/o/{object}");
        register("googleapis.storage.v1.objects.insert", "POST", "http://localhost:4443/storage/v1/b/{bucket}/o");
        register("googleapis.storage.v1.objects.delete", "DELETE", "http://localhost:4443/storage/v1/b/{bucket}/o/{object}");
        register("googleapis.storage.v1.buckets.list", "GET", "http://localhost:4443/storage/v1/b?project={project}");
        register("googleapis.storage.v1.buckets.get", "GET", "http://localhost:4443/storage/v1/b/{bucket}");
        register("googleapis.storage.v1.buckets.insert", "POST", "http://localhost:4443/storage/v1/b?project={project}");

        // BigQuery
        register("googleapis.bigquery.v2.jobs.query", "POST", "http://localhost:9050/bigquery/v2/projects/{projectId}/queries");
        register("googleapis.bigquery.v2.jobs.insert", "POST", "http://localhost:9050/bigquery/v2/projects/{projectId}/jobs");
        register("googleapis.bigquery.v2.datasets.list", "GET", "http://localhost:9050/bigquery/v2/projects/{projectId}/datasets");
        register("googleapis.bigquery.v2.datasets.get", "GET", "http://localhost:9050/bigquery/v2/projects/{projectId}/datasets/{datasetId}");
        register("googleapis.bigquery.v2.tables.list", "GET", "http://localhost:9050/bigquery/v2/projects/{projectId}/datasets/{datasetId}/tables");

        // Pub/Sub (REST transcoding on gRPC emulator — limited)
        register("googleapis.pubsub.v1.projects.topics.create", "PUT", "http://localhost:8085/v1/projects/{project}/topics/{topic}");
        register("googleapis.pubsub.v1.projects.topics.publish", "POST", "http://localhost:8085/v1/projects/{project}/topics/{topic}:publish");
        register("googleapis.pubsub.v1.projects.topics.list", "GET", "http://localhost:8085/v1/projects/{project}/topics");

        // Secret Manager (via gateway)
        register("googleapis.secretmanager.v1.projects.secrets.list", "GET", "http://localhost:8080/v1/projects/{project}/secrets");
        register("googleapis.secretmanager.v1.projects.secrets.get", "GET", "http://localhost:8080/v1/projects/{project}/secrets/{secret}");
        register("googleapis.secretmanager.v1.projects.secrets.versions.access", "GET",
                "http://localhost:8080/v1/projects/{project}/secrets/{secret}/versions/{version}:access");

        // Cloud Tasks (via gateway)
        register("googleapis.cloudtasks.v2.projects.locations.queues.list", "GET",
                "http://localhost:8080/v2/projects/{project}/locations/{location}/queues");

        // Firestore
        register("googleapis.firestore.v1.projects.databases.documents.get", "GET",
                "http://localhost:8086/v1/projects/{project}/databases/{database}/documents/{document}");

        // Child workflow execution (handled specially in execute())
        connectors.put("googleapis.workflowexecutions.v1.projects.locations.workflows.executions.run",
            new ConnectorDef("googleapis.workflowexecutions.v1.projects.locations.workflows.executions.run", "POST", "__CHILD_WORKFLOW__"));
    }

    public void register(String connectorPath, String httpMethod, String urlTemplate) {
        connectors.put(connectorPath, new ConnectorDef(connectorPath, httpMethod, urlTemplate));
    }

    public boolean has(String connectorPath) {
        return connectors.containsKey(connectorPath);
    }

    public Set<String> getAllConnectorPaths() {
        return Collections.unmodifiableSet(connectors.keySet());
    }

    /**
     * Execute a connector call. Maps args to URL template vars and body.
     */
    @SuppressWarnings("unchecked")
    public Object execute(String connectorPath, Map<String, Object> args) {
        ConnectorDef def = connectors.get(connectorPath);
        if (def == null) {
            logger.warn("Unknown connector: {}. Attempting direct HTTP call.", connectorPath);
            return executeUnknownConnector(connectorPath, args);
        }

        // Special handling for child workflow execution
        if ("__CHILD_WORKFLOW__".equals(def.urlTemplate())) {
            if (childWorkflowRunner == null) {
                throw new RuntimeException("Child workflow execution not configured");
            }
            String workflowId = String.valueOf(args.getOrDefault("workflow_id",
                args.getOrDefault("workflowId", "")));
            @SuppressWarnings("unchecked")
            Map<String, Object> argument = args.get("argument") instanceof Map ?
                (Map<String, Object>) args.get("argument") : Map.of();
            return childWorkflowRunner.apply(workflowId, argument);
        }

        try {
            // Resolve URL template with args
            String url = def.urlTemplate();
            for (Map.Entry<String, Object> entry : args.entrySet()) {
                url = url.replace("{" + entry.getKey() + "}", String.valueOf(entry.getValue()));
            }

            HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(30));
            // No auth header — local emulator bypass

            Object body = args.get("body");
            if (body != null && ("POST".equals(def.httpMethod()) || "PUT".equals(def.httpMethod()) || "PATCH".equals(def.httpMethod()))) {
                String bodyStr = body instanceof String ? (String) body : mapper.writeValueAsString(body);
                reqBuilder.method(def.httpMethod(), HttpRequest.BodyPublishers.ofString(bodyStr));
                reqBuilder.header("Content-Type", "application/json");
            } else {
                reqBuilder.method(def.httpMethod(), HttpRequest.BodyPublishers.noBody());
            }

            HttpResponse<String> response = httpClient.send(reqBuilder.build(), HttpResponse.BodyHandlers.ofString());

            // Check if execution was cancelled while HTTP call was in flight
            var ctx = currentContext.get();
            if (ctx != null && ctx.isCancelled()) {
                throw new RuntimeException("Cancelled: execution was cancelled during connector call");
            }

            if (response.statusCode() >= 400) {
                Map<String, Object> error = new LinkedHashMap<>();
                error.put("code", response.statusCode());
                error.put("message", "HTTP " + response.statusCode() + " from connector " + connectorPath);
                error.put("body", response.body());
                throw new RuntimeException("HttpError: " + mapper.writeValueAsString(error));
            }

            // Parse response — return completed result (LRO unwrapping)
            Object result = response.body();
            try {
                result = mapper.readValue(response.body(), Object.class);
                // If it looks like an LRO (has "done" field), unwrap
                if (result instanceof Map<?, ?> resultMap && resultMap.containsKey("done")) {
                    Object innerResponse = ((Map<?, ?>) resultMap).get("response");
                    if (innerResponse != null) result = innerResponse;
                }
            } catch (Exception ignored) {}

            return result;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Connector " + connectorPath + " failed: " + e.getMessage(), e);
        }
    }

    private Object executeUnknownConnector(String connectorPath, Map<String, Object> args) {
        String[] parts = connectorPath.split("\\.");
        if (parts.length < 3 || !"googleapis".equals(parts[0])) {
            throw new RuntimeException("Unknown connector: " + connectorPath);
        }
        String service = parts[1];
        String version = parts[2];
        // Build path from remaining parts
        StringBuilder pathBuilder = new StringBuilder();
        for (int i = 3; i < parts.length; i++) {
            pathBuilder.append("/").append(parts[i]);
        }
        String url = "https://" + service + ".googleapis.com/" + version + pathBuilder;
        logger.warn("Attempting fallback HTTP call for unknown connector {} to {}", connectorPath, url);

        try {
            // Substitute any path variables from args
            for (Map.Entry<String, Object> entry : args.entrySet()) {
                url = url.replace("{" + entry.getKey() + "}", String.valueOf(entry.getValue()));
            }

            HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(30));

            Object body = args.get("body");
            if (body != null) {
                String bodyStr = body instanceof String ? (String) body : mapper.writeValueAsString(body);
                reqBuilder.method("POST", HttpRequest.BodyPublishers.ofString(bodyStr));
                reqBuilder.header("Content-Type", "application/json");
            } else {
                reqBuilder.GET();
            }

            HttpResponse<String> response = httpClient.send(reqBuilder.build(), HttpResponse.BodyHandlers.ofString());

            // Check if execution was cancelled during HTTP call
            var ctx = currentContext.get();
            if (ctx != null && ctx.isCancelled()) {
                throw new RuntimeException("Cancelled: execution was cancelled during connector call");
            }

            Object result = response.body();
            try {
                result = mapper.readValue(response.body(), Object.class);
            } catch (Exception ignored) {}
            return result;
        } catch (Exception e) {
            throw new RuntimeException("Connector " + connectorPath + " fallback failed: " + e.getMessage(), e);
        }
    }

    private record ConnectorDef(String path, String httpMethod, String urlTemplate) {}
}
