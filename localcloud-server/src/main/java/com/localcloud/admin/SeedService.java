package com.localcloud.admin;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.linecorp.armeria.common.AggregatedHttpRequest;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.QueryParams;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.annotation.Post;
import com.localcloud.config.LocalCloudConfig;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Armeria annotated service for seeding and resetting emulator data.
 * Seeds data by calling external emulator REST APIs (GCS, Pub/Sub, BigQuery)
 * and in-process stores (Secret Manager). Registered at the
 * {@code /_localcloud} path prefix.
 */
public class SeedService {

    private static final Logger logger = LoggerFactory.getLogger(SeedService.class);

    private static final String GCS_BASE = "http://localhost:4443";
    private static final String PUBSUB_BASE = "http://localhost:8085";
    private static final String BIGQUERY_BASE = "http://localhost:9050";

    private final LocalCloudConfig config;
    private final ObjectMapper jsonMapper;
    private final YAMLMapper yamlMapper;
    private final HttpClient httpClient;

    /** Stores the last loaded seed YAML so it can be restored on reset. */
    private volatile String lastSeedYaml;

    public SeedService(LocalCloudConfig config) {
        this.config = config;
        this.jsonMapper = new ObjectMapper();
        this.jsonMapper.registerModule(new JavaTimeModule());
        this.jsonMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        this.yamlMapper = new YAMLMapper();
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    /**
     * Accept YAML seed data and load it into emulators via their REST APIs.
     * <pre>
     * gcs:
     *   buckets:
     *     - name: my-bucket
     *       location: US
     * pubsub:
     *   topics:
     *     - project: local-project
     *       name: my-topic
     *   subscriptions:
     *     - project: local-project
     *       name: my-sub
     *       topic: projects/local-project/topics/my-topic
     * bigquery:
     *   datasets:
     *     - name: my_dataset
     *   tables:
     *     - dataset: my_dataset
     *       name: my_table
     *       schema:
     *         fields:
     *           - name: id
     *             type: INTEGER
     * </pre>
     */
    @Post("/seed")
    public com.linecorp.armeria.common.HttpResponse seed(AggregatedHttpRequest request) {
        try {
            String yamlContent = request.contentUtf8();
            if (yamlContent == null || yamlContent.isBlank()) {
                return errorResponse(HttpStatus.BAD_REQUEST, "Seed data is required (YAML format)");
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> seedData = yamlMapper.readValue(yamlContent, Map.class);

            int totalSeeded = 0;
            Map<String, Object> results = new LinkedHashMap<>();

            // Process each service section
            if (seedData.containsKey("gcs")) {
                int count = seedGcs(seedData.get("gcs"));
                results.put("gcs", count);
                totalSeeded += count;
            }
            if (seedData.containsKey("pubsub")) {
                int count = seedPubSub(seedData.get("pubsub"));
                results.put("pubsub", count);
                totalSeeded += count;
            }
            if (seedData.containsKey("bigquery")) {
                int count = seedBigQuery(seedData.get("bigquery"));
                results.put("bigquery", count);
                totalSeeded += count;
            }
            if (seedData.containsKey("secretmanager")) {
                int count = seedSecretManager(seedData.get("secretmanager"));
                results.put("secretmanager", count);
                totalSeeded += count;
            }

            // Store seed for potential restore
            lastSeedYaml = yamlContent;

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("status", "seeded");
            response.put("total_records", totalSeeded);
            response.put("services", results);

            return jsonResponse(HttpStatus.OK, response);
        } catch (Exception e) {
            logger.error("Error processing seed data", e);
            return errorResponse(HttpStatus.INTERNAL_SERVER_ERROR, "Seed failed: " + e.getMessage());
        }
    }

    /**
     * Reset emulator data. If {@code restore_seed=true} is passed,
     * re-apply the last seed after clearing.
     */
    @Post("/reset")
    public com.linecorp.armeria.common.HttpResponse reset(ServiceRequestContext ctx) {
        try {
            QueryParams params = ctx.queryParams();
            boolean restoreSeed = "true".equalsIgnoreCase(params.get("restore_seed"));

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("status", "reset");
            response.put("note", "External emulators manage their own data; reset applies to seed re-application only");

            if (restoreSeed && lastSeedYaml != null) {
                // Re-apply seed
                @SuppressWarnings("unchecked")
                Map<String, Object> seedData = yamlMapper.readValue(lastSeedYaml, Map.class);
                int totalSeeded = 0;
                if (seedData.containsKey("gcs")) totalSeeded += seedGcs(seedData.get("gcs"));
                if (seedData.containsKey("pubsub")) totalSeeded += seedPubSub(seedData.get("pubsub"));
                if (seedData.containsKey("bigquery")) totalSeeded += seedBigQuery(seedData.get("bigquery"));
                if (seedData.containsKey("secretmanager")) totalSeeded += seedSecretManager(seedData.get("secretmanager"));

                response.put("seed_restored", true);
                response.put("records_restored", totalSeeded);
            } else {
                response.put("seed_restored", false);
            }

            return jsonResponse(HttpStatus.OK, response);
        } catch (Exception e) {
            logger.error("Error resetting data", e);
            return errorResponse(HttpStatus.INTERNAL_SERVER_ERROR, "Reset failed: " + e.getMessage());
        }
    }

    // ========== Seed processors (call external emulator REST APIs) ==========

    @SuppressWarnings("unchecked")
    private int seedGcs(Object gcsData) {
        if (!(gcsData instanceof Map)) return 0;
        Map<String, Object> gcs = (Map<String, Object>) gcsData;
        int count = 0;
        String projectId = config.getProjectId();

        List<Map<String, Object>> buckets = (List<Map<String, Object>>) gcs.get("buckets");
        if (buckets != null) {
            for (Map<String, Object> bucket : buckets) {
                try {
                    String name = (String) bucket.get("name");
                    Map<String, Object> body = new LinkedHashMap<>();
                    body.put("name", name);
                    if (bucket.containsKey("location")) body.put("location", bucket.get("location"));
                    if (bucket.containsKey("storage_class")) body.put("storageClass", bucket.get("storage_class"));

                    String url = GCS_BASE + "/storage/v1/b?project=" + projectId;
                    httpPut(url, jsonMapper.writeValueAsString(body));
                    count++;
                    logger.debug("Seeded GCS bucket: {}", name);
                } catch (Exception e) {
                    logger.warn("Failed to seed GCS bucket: {}", e.getMessage());
                }
            }
        }

        // Seed objects if present
        List<Map<String, Object>> objects = (List<Map<String, Object>>) gcs.get("objects");
        if (objects != null) {
            for (Map<String, Object> obj : objects) {
                try {
                    String bucketName = (String) obj.get("bucket");
                    String key = (String) obj.get("name");
                    String content = (String) obj.getOrDefault("content", "");

                    String url = GCS_BASE + "/upload/storage/v1/b/" + bucketName
                            + "/o?name=" + key + "&uploadType=media";
                    httpPost(url, content, "application/octet-stream");
                    count++;
                    logger.debug("Seeded GCS object: {}/{}", bucketName, key);
                } catch (Exception e) {
                    logger.warn("Failed to seed GCS object: {}", e.getMessage());
                }
            }
        }
        return count;
    }

    @SuppressWarnings("unchecked")
    private int seedPubSub(Object pubsubData) {
        if (!(pubsubData instanceof Map)) return 0;
        Map<String, Object> pubsub = (Map<String, Object>) pubsubData;
        int count = 0;

        List<Map<String, Object>> topics = (List<Map<String, Object>>) pubsub.get("topics");
        if (topics != null) {
            for (Map<String, Object> topic : topics) {
                try {
                    String project = (String) topic.getOrDefault("project", config.getProjectId());
                    String name = (String) topic.get("name");

                    String url = PUBSUB_BASE + "/v1/projects/" + project + "/topics/" + name;
                    httpPut(url, "{}");
                    count++;
                    logger.debug("Seeded Pub/Sub topic: {}", name);
                } catch (Exception e) {
                    logger.warn("Failed to seed Pub/Sub topic: {}", e.getMessage());
                }
            }
        }

        List<Map<String, Object>> subscriptions = (List<Map<String, Object>>) pubsub.get("subscriptions");
        if (subscriptions != null) {
            for (Map<String, Object> sub : subscriptions) {
                try {
                    String project = (String) sub.getOrDefault("project", config.getProjectId());
                    String name = (String) sub.get("name");
                    String topic = (String) sub.get("topic");
                    int ackDeadline = sub.containsKey("ack_deadline_seconds")
                            ? ((Number) sub.get("ack_deadline_seconds")).intValue() : 10;

                    Map<String, Object> body = new LinkedHashMap<>();
                    body.put("topic", topic);
                    body.put("ackDeadlineSeconds", ackDeadline);

                    String url = PUBSUB_BASE + "/v1/projects/" + project + "/subscriptions/" + name;
                    httpPut(url, jsonMapper.writeValueAsString(body));
                    count++;
                    logger.debug("Seeded Pub/Sub subscription: {}", name);
                } catch (Exception e) {
                    logger.warn("Failed to seed Pub/Sub subscription: {}", e.getMessage());
                }
            }
        }
        return count;
    }

    @SuppressWarnings("unchecked")
    private int seedBigQuery(Object bqData) {
        if (!(bqData instanceof Map)) return 0;
        Map<String, Object> bq = (Map<String, Object>) bqData;
        int count = 0;
        String projectId = config.getProjectId();

        List<Map<String, Object>> datasets = (List<Map<String, Object>>) bq.get("datasets");
        if (datasets != null) {
            for (Map<String, Object> dataset : datasets) {
                try {
                    String name = (String) dataset.get("name");
                    Map<String, Object> datasetRef = new LinkedHashMap<>();
                    datasetRef.put("datasetId", name);
                    datasetRef.put("projectId", projectId);

                    Map<String, Object> body = new LinkedHashMap<>();
                    body.put("datasetReference", datasetRef);
                    if (dataset.containsKey("location")) body.put("location", dataset.get("location"));

                    String url = BIGQUERY_BASE + "/bigquery/v2/projects/" + projectId + "/datasets";
                    httpPost(url, jsonMapper.writeValueAsString(body), "application/json");
                    count++;
                    logger.debug("Seeded BigQuery dataset: {}", name);
                } catch (Exception e) {
                    logger.warn("Failed to seed BigQuery dataset: {}", e.getMessage());
                }
            }
        }

        List<Map<String, Object>> tables = (List<Map<String, Object>>) bq.get("tables");
        if (tables != null) {
            for (Map<String, Object> table : tables) {
                try {
                    String datasetName = (String) table.get("dataset");
                    String tableName = (String) table.get("name");

                    Map<String, Object> tableRef = new LinkedHashMap<>();
                    tableRef.put("projectId", projectId);
                    tableRef.put("datasetId", datasetName);
                    tableRef.put("tableId", tableName);

                    Map<String, Object> body = new LinkedHashMap<>();
                    body.put("tableReference", tableRef);
                    if (table.containsKey("schema")) body.put("schema", table.get("schema"));

                    String url = BIGQUERY_BASE + "/bigquery/v2/projects/" + projectId
                            + "/datasets/" + datasetName + "/tables";
                    httpPost(url, jsonMapper.writeValueAsString(body), "application/json");
                    count++;
                    logger.debug("Seeded BigQuery table: {}.{}", datasetName, tableName);
                } catch (Exception e) {
                    logger.warn("Failed to seed BigQuery table: {}", e.getMessage());
                }
            }
        }
        return count;
    }

    @SuppressWarnings("unchecked")
    private int seedSecretManager(Object smData) {
        if (!(smData instanceof Map)) return 0;
        Map<String, Object> sm = (Map<String, Object>) smData;
        int count = 0;
        String projectId = config.getProjectId();

        // Secret Manager runs in-process on the gateway port (8080).
        // We call our own gRPC service via its REST transcoding endpoint.
        String smBase = "http://localhost:" + config.getGatewayPort();

        List<Map<String, Object>> secrets = (List<Map<String, Object>>) sm.get("secrets");
        if (secrets != null) {
            for (Map<String, Object> secret : secrets) {
                try {
                    String secretId = (String) secret.get("name");

                    // Create the secret
                    Map<String, Object> createBody = new LinkedHashMap<>();
                    createBody.put("replication", Map.of("automatic", Map.of()));

                    String createUrl = smBase + "/v1/projects/" + projectId
                            + "/secrets?secretId=" + secretId;
                    httpPost(createUrl, jsonMapper.writeValueAsString(createBody), "application/json");

                    // Add a version if value is provided
                    String value = (String) secret.get("value");
                    if (value != null) {
                        String encoded = java.util.Base64.getEncoder()
                                .encodeToString(value.getBytes(StandardCharsets.UTF_8));
                        Map<String, Object> versionBody = new LinkedHashMap<>();
                        versionBody.put("payload", Map.of("data", encoded));

                        String versionUrl = smBase + "/v1/projects/" + projectId
                                + "/secrets/" + secretId + ":addVersion";
                        httpPost(versionUrl, jsonMapper.writeValueAsString(versionBody), "application/json");
                    }

                    count++;
                    logger.debug("Seeded Secret Manager secret: {}", secretId);
                } catch (Exception e) {
                    logger.warn("Failed to seed secret: {}", e.getMessage());
                }
            }
        }
        return count;
    }

    // ========== HTTP helpers ==========

    private void httpPut(String url, String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .PUT(HttpRequest.BodyPublishers.ofString(body))
                .header("Content-Type", "application/json")
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 400) {
            logger.warn("PUT {} returned {}: {}", url, response.statusCode(), response.body());
        }
    }

    private void httpPost(String url, String body, String contentType) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .header("Content-Type", contentType)
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 400) {
            logger.warn("POST {} returned {}: {}", url, response.statusCode(), response.body());
        }
    }

    // ========== Response helpers ==========

    private com.linecorp.armeria.common.HttpResponse jsonResponse(HttpStatus status, Object body) {
        try {
            String json = jsonMapper.writerWithDefaultPrettyPrinter().writeValueAsString(body);
            return com.linecorp.armeria.common.HttpResponse.of(status, MediaType.JSON, json);
        } catch (Exception e) {
            return com.linecorp.armeria.common.HttpResponse.of(HttpStatus.INTERNAL_SERVER_ERROR,
                    MediaType.PLAIN_TEXT_UTF_8, "JSON serialization error");
        }
    }

    private com.linecorp.armeria.common.HttpResponse errorResponse(HttpStatus status, String message) {
        try {
            Map<String, Object> error = Map.of(
                    "error", true,
                    "message", message
            );
            return com.linecorp.armeria.common.HttpResponse.of(status,
                    MediaType.JSON, jsonMapper.writeValueAsString(error));
        } catch (Exception e) {
            return com.linecorp.armeria.common.HttpResponse.of(status,
                    MediaType.PLAIN_TEXT_UTF_8, message);
        }
    }
}
