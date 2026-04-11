package com.localcloud.admin;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
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
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.annotation.Param;
import com.linecorp.armeria.server.annotation.Post;
import com.localcloud.config.LocalCloudConfig;
import com.localcloud.config.ServiceRegistry;
import com.localcloud.config.ServiceRegistry.ServiceDefinition;
import com.localcloud.persistence.PostgresDataSource;

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

    private final LocalCloudConfig config;
    private final PostgresDataSource dataSource;
    private final ServiceRegistry registry;
    private final ObjectMapper jsonMapper;
    private final YAMLMapper yamlMapper;
    private final HttpClient httpClient;

    // Base URLs computed from registry
    private final String gcsBase;
    private final String pubsubBase;
    private final String bigqueryBase;
    private final int spannerRestPort;
    private final int firestorePort;

    /** Stores the last loaded seed YAML so it can be restored on reset. */
    private volatile String lastSeedYaml;

    public SeedService(LocalCloudConfig config, PostgresDataSource dataSource, ServiceRegistry registry) {
        this.config = config;
        this.dataSource = dataSource;
        this.registry = registry;
        this.jsonMapper = new ObjectMapper();
        this.jsonMapper.registerModule(new JavaTimeModule());
        this.jsonMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        this.yamlMapper = new YAMLMapper();
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();

        // Compute base URLs from registry definitions
        this.gcsBase = baseUrl(registry.getService("gcs"));
        this.pubsubBase = baseUrl(registry.getService("pubsub"));
        this.bigqueryBase = baseUrl(registry.getService("bigquery"));

        ServiceDefinition spannerDef = registry.getService("spanner");
        this.spannerRestPort = spannerDef != null && spannerDef.additionalPorts().containsKey("rest")
                ? spannerDef.additionalPorts().get("rest") : 9020;

        ServiceDefinition firestoreDef = registry.getService("firestore");
        this.firestorePort = firestoreDef != null ? firestoreDef.port() : 8086;
    }

    private static String baseUrl(ServiceDefinition def) {
        if (def == null) return "http://localhost:0";
        return "http://localhost:" + def.port();
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
            Map<String, Object> rawData = yamlMapper.readValue(yamlContent, Map.class);

            // Multi-project format: projects: { dev: { gcs: ... }, staging: { gcs: ... } }
            // Single-project format: services: { gcs: ... } or flat { gcs: ... }
            int totalSeeded = 0;
            Map<String, Object> results = new LinkedHashMap<>();

            if (rawData.containsKey("projects") && rawData.get("projects") instanceof Map) {
                // Multi-project seed
                @SuppressWarnings("unchecked")
                Map<String, Object> projectsMap = (Map<String, Object>) rawData.get("projects");
                for (Map.Entry<String, Object> entry : projectsMap.entrySet()) {
                    String projectId = entry.getKey();
                    if (!(entry.getValue() instanceof Map)) continue;
                    @SuppressWarnings("unchecked")
                    Map<String, Object> projectServices = (Map<String, Object>) entry.getValue();
                    // Unwrap nested services: key if present
                    @SuppressWarnings("unchecked")
                    Map<String, Object> seedData = projectServices.containsKey("services") && projectServices.get("services") instanceof Map
                            ? (Map<String, Object>) projectServices.get("services")
                            : projectServices;
                    int count = seedServicesForProject(seedData, projectId, results);
                    totalSeeded += count;
                }
            } else {
                // Single-project seed (backward compatible)
                @SuppressWarnings("unchecked")
                Map<String, Object> seedData = rawData.containsKey("services") && rawData.get("services") instanceof Map
                        ? (Map<String, Object>) rawData.get("services")
                        : rawData;
                totalSeeded = seedServicesForProject(seedData, config.getProjectId(), results);
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
     * Seed all services for a specific project.
     * Note: For multi-project, this is called once per project.
     * The individual seed methods use config.getProjectId() internally,
     * so for now multi-project seeding seeds under the default project.
     * Full per-project seeding would require passing projectId through
     * each seed method — marked as a future enhancement.
     */
    private int seedServicesForProject(Map<String, Object> seedData, String projectId,
                                        Map<String, Object> results) {
        int totalSeeded = 0;
        if (seedData.containsKey("gcs")) {
            int count = seedGcs(seedData.get("gcs"));
            results.put("gcs", results.containsKey("gcs") ? ((int) results.get("gcs")) + count : count);
            totalSeeded += count;
        }
        if (seedData.containsKey("pubsub")) {
            int count = seedPubSub(seedData.get("pubsub"));
            results.put("pubsub", results.containsKey("pubsub") ? ((int) results.get("pubsub")) + count : count);
            totalSeeded += count;
        }
        if (seedData.containsKey("bigquery")) {
            int count = seedBigQuery(seedData.get("bigquery"));
            results.put("bigquery", results.containsKey("bigquery") ? ((int) results.get("bigquery")) + count : count);
            totalSeeded += count;
        }
        if (seedData.containsKey("secretmanager")) {
            int count = seedSecretManager(seedData.get("secretmanager"));
            results.put("secretmanager", results.containsKey("secretmanager") ? ((int) results.get("secretmanager")) + count : count);
            totalSeeded += count;
        }
        if (seedData.containsKey("memorystore")) {
            int count = seedMemorystore(seedData.get("memorystore"));
            results.put("memorystore", results.containsKey("memorystore") ? ((int) results.get("memorystore")) + count : count);
            totalSeeded += count;
        }
        if (seedData.containsKey("spanner")) {
            int count = seedSpanner(seedData.get("spanner"));
            results.put("spanner", results.containsKey("spanner") ? ((int) results.get("spanner")) + count : count);
            totalSeeded += count;
        }
        if (seedData.containsKey("firestore")) {
            int count = seedFirestore(seedData.get("firestore"));
            results.put("firestore", results.containsKey("firestore") ? ((int) results.get("firestore")) + count : count);
            totalSeeded += count;
        }
        if (seedData.containsKey("bigtable")) {
            int count = seedBigtable(seedData.get("bigtable"));
            results.put("bigtable", results.containsKey("bigtable") ? ((int) results.get("bigtable")) + count : count);
            totalSeeded += count;
        }
        if (seedData.containsKey("cloudtasks")) {
            int count = seedCloudTasks(seedData.get("cloudtasks"));
            results.put("cloudtasks", results.containsKey("cloudtasks") ? ((int) results.get("cloudtasks")) + count : count);
            totalSeeded += count;
        }
        return totalSeeded;
    }

    /**
     * Reset emulator data. Accepts a JSON body with an optional
     * {@code restore_seed} boolean field. If true, re-applies
     * the last loaded seed after clearing.
     */
    @Post("/reset")
    public com.linecorp.armeria.common.HttpResponse reset(ServiceRequestContext ctx, AggregatedHttpRequest request) {
        try {
            boolean restoreSeed = false;
            String body = request.contentUtf8();
            if (body != null && !body.isBlank()) {
                try {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> jsonBody = jsonMapper.readValue(body, Map.class);
                    Object val = jsonBody.get("restore_seed");
                    restoreSeed = Boolean.TRUE.equals(val) || "true".equals(String.valueOf(val));
                } catch (Exception ignored) {
                    // fall through with restoreSeed = false
                }
            }

            // Resolve project ID: use ?project= query param if provided, else config default
            String projectParam = ctx.queryParams().get("project");
            String projectId = (projectParam != null && !projectParam.isBlank())
                    ? projectParam : config.getProjectId();

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("status", "success");

            if (restoreSeed && lastSeedYaml != null) {
                // Re-apply seed
                @SuppressWarnings("unchecked")
                Map<String, Object> rawData = yamlMapper.readValue(lastSeedYaml, Map.class);
                @SuppressWarnings("unchecked")
                Map<String, Object> seedData = rawData.containsKey("services") && rawData.get("services") instanceof Map
                        ? (Map<String, Object>) rawData.get("services")
                        : rawData;
                int totalSeeded = 0;
                if (seedData.containsKey("gcs")) totalSeeded += seedGcs(seedData.get("gcs"));
                if (seedData.containsKey("pubsub")) totalSeeded += seedPubSub(seedData.get("pubsub"));
                if (seedData.containsKey("bigquery")) totalSeeded += seedBigQuery(seedData.get("bigquery"));
                if (seedData.containsKey("secretmanager")) totalSeeded += seedSecretManager(seedData.get("secretmanager"));
                if (seedData.containsKey("memorystore")) totalSeeded += seedMemorystore(seedData.get("memorystore"));
                if (seedData.containsKey("spanner")) totalSeeded += seedSpanner(seedData.get("spanner"));
                if (seedData.containsKey("firestore")) totalSeeded += seedFirestore(seedData.get("firestore"));
                if (seedData.containsKey("bigtable")) totalSeeded += seedBigtable(seedData.get("bigtable"));
                if (seedData.containsKey("cloudtasks")) totalSeeded += seedCloudTasks(seedData.get("cloudtasks"));

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

    /**
     * Reset data for a single service. Accepts a JSON body with an optional
     * {@code restore_seed} boolean field. If true, re-applies the last loaded
     * seed data for that specific service after clearing.
     *
     * <p>PostgreSQL-backed services have their tables truncated via parameterized DELETE.
     * External emulators have their resources deleted via REST API calls.</p>
     */
    @Post("/reset/{service}")
    public com.linecorp.armeria.common.HttpResponse resetService(
            ServiceRequestContext ctx, @Param("service") String service, AggregatedHttpRequest request) {
        try {
            // Validate service name
            ServiceDefinition def = registry.getService(service);
            if (def == null) {
                return errorResponse(HttpStatus.NOT_FOUND, "Unknown service: " + service);
            }

            // Parse restore_seed from JSON body
            boolean restoreSeed = false;
            String body = request.contentUtf8();
            if (body != null && !body.isBlank()) {
                try {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> jsonBody = jsonMapper.readValue(body, Map.class);
                    Object val = jsonBody.get("restore_seed");
                    restoreSeed = Boolean.TRUE.equals(val) || "true".equals(String.valueOf(val));
                } catch (Exception ignored) {
                    // fall through with restoreSeed = false
                }
            }

            // Resolve project ID: use ?project= query param if provided, else config default
            String projectParam = ctx.queryParams().get("project");
            String projectId = (projectParam != null && !projectParam.isBlank())
                    ? projectParam : config.getProjectId();

            // Dispatch to per-service reset logic
            int deletedCount = switch (service) {
                case "gcs" -> resetGcs(projectId);
                case "pubsub" -> resetPubSub(projectId);
                case "firestore" -> resetFirestore(projectId);
                case "bigquery" -> resetBigQuery(projectId);
                case "spanner" -> resetSpanner(projectId);
                case "secretmanager" -> resetSecretManager(projectId);
                case "cloudtasks" -> resetCloudTasks(projectId);
                case "logging" -> resetLogging(projectId);
                case "monitoring" -> resetMonitoring(projectId);
                case "memorystore" -> resetMemorystore(projectId);
                case "bigtable" -> resetBigtable(projectId);
                case "compute" -> resetCompute(projectId);
                case "cloudrun" -> resetCloudRun(projectId);
                case "gke" -> resetGke(projectId);
                default -> {
                    logger.warn("No reset logic for service: {}", service);
                    yield 0;
                }
            };

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("status", "success");
            response.put("service", service);
            response.put("deleted_count", deletedCount);

            // Optionally re-seed this service
            if (restoreSeed && lastSeedYaml != null) {
                try {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> rawData = yamlMapper.readValue(lastSeedYaml, Map.class);
                    @SuppressWarnings("unchecked")
                    Map<String, Object> seedData = rawData.containsKey("services") && rawData.get("services") instanceof Map
                            ? (Map<String, Object>) rawData.get("services")
                            : rawData;

                    int seededCount = 0;
                    if (seedData.containsKey(service)) {
                        seededCount = switch (service) {
                            case "gcs" -> seedGcs(seedData.get("gcs"));
                            case "pubsub" -> seedPubSub(seedData.get("pubsub"));
                            case "bigquery" -> seedBigQuery(seedData.get("bigquery"));
                            case "secretmanager" -> seedSecretManager(seedData.get("secretmanager"));
                            case "memorystore" -> seedMemorystore(seedData.get("memorystore"));
                            case "spanner" -> seedSpanner(seedData.get("spanner"));
                            case "firestore" -> seedFirestore(seedData.get("firestore"));
                            case "bigtable" -> seedBigtable(seedData.get("bigtable"));
                            case "cloudtasks" -> seedCloudTasks(seedData.get("cloudtasks"));
                            default -> 0;
                        };
                    }
                    response.put("seed_restored", true);
                    response.put("records_restored", seededCount);
                } catch (Exception e) {
                    logger.warn("Failed to restore seed for {}: {}", service, e.getMessage());
                    response.put("seed_restored", false);
                    response.put("seed_error", e.getMessage());
                }
            } else {
                response.put("seed_restored", false);
            }

            return jsonResponse(HttpStatus.OK, response);
        } catch (Exception e) {
            logger.error("Error resetting service {}", service, e);
            return errorResponse(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Reset failed for " + service + ": " + e.getMessage());
        }
    }

    // ========== Per-service reset methods ==========

    @SuppressWarnings("unchecked")
    private int resetGcs(String projectId) {
        int count = 0;
        try {
            // List all buckets
            String listUrl = gcsBase + "/storage/v1/b?project=" + projectId;
            String resp = httpGet(listUrl);
            Map<String, Object> parsed = jsonMapper.readValue(resp, Map.class);
            List<Map<String, Object>> items = (List<Map<String, Object>>) parsed.get("items");
            if (items != null) {
                for (Map<String, Object> bucket : items) {
                    String bucketName = (String) bucket.get("name");
                    // List and delete all objects in the bucket
                    try {
                        String objListUrl = gcsBase + "/storage/v1/b/" + bucketName + "/o";
                        String objResp = httpGet(objListUrl);
                        Map<String, Object> objParsed = jsonMapper.readValue(objResp, Map.class);
                        List<Map<String, Object>> objects = (List<Map<String, Object>>) objParsed.get("items");
                        if (objects != null) {
                            for (Map<String, Object> obj : objects) {
                                String objName = (String) obj.get("name");
                                try {
                                    String encodedName = java.net.URLEncoder.encode(objName, StandardCharsets.UTF_8);
                                    httpDelete(gcsBase + "/storage/v1/b/" + bucketName + "/o/" + encodedName);
                                    count++;
                                } catch (Exception e) {
                                    logger.debug("Failed to delete GCS object {}/{}: {}", bucketName, objName, e.getMessage());
                                }
                            }
                        }
                    } catch (Exception e) {
                        logger.debug("Failed to list objects in bucket {}: {}", bucketName, e.getMessage());
                    }
                    // Delete the bucket
                    try {
                        httpDelete(gcsBase + "/storage/v1/b/" + bucketName);
                        count++;
                    } catch (Exception e) {
                        logger.debug("Failed to delete GCS bucket {}: {}", bucketName, e.getMessage());
                    }
                }
            }
        } catch (Exception e) {
            logger.warn("Failed to reset GCS: {}", e.getMessage());
        }
        logger.info("Reset GCS: deleted {} resources", count);
        return count;
    }

    @SuppressWarnings("unchecked")
    private int resetPubSub(String projectId) {
        int count = 0;
        try {
            // List and delete subscriptions first (they reference topics)
            try {
                String subListUrl = pubsubBase + "/v1/projects/" + projectId + "/subscriptions";
                String subResp = httpGet(subListUrl);
                Map<String, Object> subParsed = jsonMapper.readValue(subResp, Map.class);
                List<String> subscriptions = (List<String>) subParsed.get("subscriptions");
                if (subscriptions != null) {
                    for (String sub : subscriptions) {
                        try {
                            httpDelete(pubsubBase + "/v1/" + sub);
                            count++;
                        } catch (Exception e) {
                            logger.debug("Failed to delete Pub/Sub subscription {}: {}", sub, e.getMessage());
                        }
                    }
                }
            } catch (Exception e) {
                logger.debug("Failed to list Pub/Sub subscriptions: {}", e.getMessage());
            }

            // List and delete topics
            try {
                String topicListUrl = pubsubBase + "/v1/projects/" + projectId + "/topics";
                String topicResp = httpGet(topicListUrl);
                Map<String, Object> topicParsed = jsonMapper.readValue(topicResp, Map.class);
                List<String> topics = (List<String>) topicParsed.get("topics");
                if (topics != null) {
                    for (String topic : topics) {
                        try {
                            httpDelete(pubsubBase + "/v1/" + topic);
                            count++;
                        } catch (Exception e) {
                            logger.debug("Failed to delete Pub/Sub topic {}: {}", topic, e.getMessage());
                        }
                    }
                }
            } catch (Exception e) {
                logger.debug("Failed to list Pub/Sub topics: {}", e.getMessage());
            }
        } catch (Exception e) {
            logger.warn("Failed to reset Pub/Sub: {}", e.getMessage());
        }
        logger.info("Reset Pub/Sub: deleted {} resources", count);
        return count;
    }

    private int resetFirestore(String projectId) {
        int count = 0;
        String firestoreBase = "http://localhost:" + firestorePort;
        try {
            // The Firestore emulator supports a reset endpoint
            String resetUrl = firestoreBase + "/emulator/v1/projects/" + projectId + "/databases/(default)/documents";
            httpDelete(resetUrl);
            count++;
            logger.info("Reset Firestore: cleared all documents");
        } catch (Exception e) {
            logger.warn("Failed to reset Firestore: {}", e.getMessage());
        }
        return count;
    }

    @SuppressWarnings("unchecked")
    private int resetBigQuery(String projectId) {
        int count = 0;
        try {
            // List datasets
            String dsListUrl = bigqueryBase + "/bigquery/v2/projects/" + projectId + "/datasets";
            String dsResp = httpGet(dsListUrl);
            Map<String, Object> dsParsed = jsonMapper.readValue(dsResp, Map.class);
            List<Map<String, Object>> datasets = (List<Map<String, Object>>) dsParsed.get("datasets");
            if (datasets != null) {
                for (Map<String, Object> ds : datasets) {
                    Map<String, Object> ref = (Map<String, Object>) ds.get("datasetReference");
                    if (ref != null) {
                        String datasetId = (String) ref.get("datasetId");
                        try {
                            // deleteContents=true removes all tables in the dataset
                            httpDelete(bigqueryBase + "/bigquery/v2/projects/" + projectId
                                    + "/datasets/" + datasetId + "?deleteContents=true");
                            count++;
                        } catch (Exception e) {
                            logger.debug("Failed to delete BigQuery dataset {}: {}", datasetId, e.getMessage());
                        }
                    }
                }
            }
        } catch (Exception e) {
            logger.warn("Failed to reset BigQuery: {}", e.getMessage());
        }
        logger.info("Reset BigQuery: deleted {} datasets", count);
        return count;
    }

    @SuppressWarnings("unchecked")
    private int resetSpanner(String projectId) {
        int count = 0;
        String spannerBase = "http://localhost:" + spannerRestPort;
        try {
            // List instances
            String instanceListUrl = spannerBase + "/v1/projects/" + projectId + "/instances";
            String instanceResp = httpGet(instanceListUrl);
            Map<String, Object> instanceParsed = jsonMapper.readValue(instanceResp, Map.class);
            List<Map<String, Object>> instances = (List<Map<String, Object>>) instanceParsed.get("instances");
            if (instances != null) {
                for (Map<String, Object> instance : instances) {
                    String instanceName = (String) instance.get("name");
                    if (instanceName != null) {
                        // List databases in this instance
                        try {
                            String dbListUrl = spannerBase + "/v1/" + instanceName + "/databases";
                            String dbResp = httpGet(dbListUrl);
                            Map<String, Object> dbParsed = jsonMapper.readValue(dbResp, Map.class);
                            List<Map<String, Object>> databases = (List<Map<String, Object>>) dbParsed.get("databases");
                            if (databases != null) {
                                for (Map<String, Object> db : databases) {
                                    String dbName = (String) db.get("name");
                                    if (dbName != null) {
                                        try {
                                            httpDelete(spannerBase + "/v1/" + dbName);
                                            count++;
                                        } catch (Exception e) {
                                            logger.debug("Failed to delete Spanner database {}: {}", dbName, e.getMessage());
                                        }
                                    }
                                }
                            }
                        } catch (Exception e) {
                            logger.debug("Failed to list Spanner databases for {}: {}", instanceName, e.getMessage());
                        }
                        // Delete the instance
                        try {
                            httpDelete(spannerBase + "/v1/" + instanceName);
                            count++;
                        } catch (Exception e) {
                            logger.debug("Failed to delete Spanner instance {}: {}", instanceName, e.getMessage());
                        }
                    }
                }
            }
        } catch (Exception e) {
            logger.warn("Failed to reset Spanner: {}", e.getMessage());
        }
        logger.info("Reset Spanner: deleted {} resources", count);
        return count;
    }

    private int resetSecretManager(String projectId) {
        int count = 0;
        try (var conn = dataSource.getConnection()) {
            try (var ps = conn.prepareStatement("DELETE FROM secret_versions WHERE project_id = ?")) {
                ps.setString(1, projectId);
                count += ps.executeUpdate();
            }
            try (var ps = conn.prepareStatement("DELETE FROM secrets WHERE project_id = ?")) {
                ps.setString(1, projectId);
                count += ps.executeUpdate();
            }
        } catch (Exception e) {
            logger.warn("Failed to reset Secret Manager: {}", e.getMessage());
        }
        logger.info("Reset Secret Manager: deleted {} rows", count);
        return count;
    }

    private int resetCloudTasks(String projectId) {
        int count = 0;
        try (var conn = dataSource.getConnection()) {
            // Delete tasks first (FK dependency on task_queues)
            try (var ps = conn.prepareStatement("DELETE FROM cloud_tasks WHERE project_id = ?")) {
                ps.setString(1, projectId);
                count += ps.executeUpdate();
            }
            try (var ps = conn.prepareStatement("DELETE FROM task_queues WHERE project_id = ?")) {
                ps.setString(1, projectId);
                count += ps.executeUpdate();
            }
        } catch (Exception e) {
            logger.warn("Failed to reset Cloud Tasks: {}", e.getMessage());
        }
        logger.info("Reset Cloud Tasks: deleted {} rows", count);
        return count;
    }

    private int resetLogging(String projectId) {
        int count = 0;
        try (var conn = dataSource.getConnection();
             var ps = conn.prepareStatement("DELETE FROM log_entries WHERE project_id = ?")) {
            ps.setString(1, projectId);
            count = ps.executeUpdate();
        } catch (Exception e) {
            logger.warn("Failed to reset Logging: {}", e.getMessage());
        }
        logger.info("Reset Logging: deleted {} rows", count);
        return count;
    }

    private int resetMonitoring(String projectId) {
        int count = 0;
        String projectName = "projects/" + projectId;
        try (var conn = dataSource.getConnection()) {
            // Delete metric points for series belonging to this project
            try (var ps = conn.prepareStatement(
                    "DELETE FROM metric_points WHERE series_id IN " +
                    "(SELECT id FROM time_series WHERE project_name = ?)")) {
                ps.setString(1, projectName);
                count += ps.executeUpdate();
            }
            try (var ps = conn.prepareStatement("DELETE FROM time_series WHERE project_name = ?")) {
                ps.setString(1, projectName);
                count += ps.executeUpdate();
            }
        } catch (Exception e) {
            logger.warn("Failed to reset Monitoring: {}", e.getMessage());
        }
        logger.info("Reset Monitoring: deleted {} rows", count);
        return count;
    }

    private int resetMemorystore(String projectId) {
        int count = 0;
        try (var conn = dataSource.getConnection();
             var ps = conn.prepareStatement("DELETE FROM redis_data WHERE project_id = ?")) {
            ps.setString(1, projectId);
            count = ps.executeUpdate();
        } catch (Exception e) {
            logger.warn("Failed to reset Memorystore: {}", e.getMessage());
        }
        logger.info("Reset Memorystore: deleted {} rows", count);
        return count;
    }

    private int resetBigtable(String projectId) {
        int count = 0;
        try (var conn = dataSource.getConnection();
             var ps = conn.prepareStatement("DELETE FROM bigtable_data WHERE project_id = ?")) {
            ps.setString(1, projectId);
            count = ps.executeUpdate();
        } catch (Exception e) {
            logger.warn("Failed to reset Bigtable: {}", e.getMessage());
        }
        logger.info("Reset Bigtable: deleted {} rows", count);
        return count;
    }

    private int resetCompute(String projectId) {
        int count = 0;
        try (var conn = dataSource.getConnection();
             var ps = conn.prepareStatement("DELETE FROM compute_instances WHERE project_id = ?")) {
            ps.setString(1, projectId);
            count = ps.executeUpdate();
        } catch (Exception e) {
            logger.warn("Failed to reset Compute Engine: {}", e.getMessage());
        }
        logger.info("Reset Compute Engine: deleted {} rows", count);
        return count;
    }

    private int resetCloudRun(String projectId) {
        int count = 0;
        try (var conn = dataSource.getConnection()) {
            try (var ps = conn.prepareStatement("DELETE FROM cloudrun_revisions WHERE project_id = ?")) {
                ps.setString(1, projectId);
                count += ps.executeUpdate();
            }
            try (var ps = conn.prepareStatement("DELETE FROM cloudrun_services WHERE project_id = ?")) {
                ps.setString(1, projectId);
                count += ps.executeUpdate();
            }
        } catch (Exception e) {
            logger.warn("Failed to reset Cloud Run: {}", e.getMessage());
        }
        logger.info("Reset Cloud Run: deleted {} rows", count);
        return count;
    }

    private int resetGke(String projectId) {
        int count = 0;
        try (var conn = dataSource.getConnection();
             var ps = conn.prepareStatement("DELETE FROM gke_clusters WHERE project_id = ?")) {
            ps.setString(1, projectId);
            count = ps.executeUpdate();
        } catch (Exception e) {
            logger.warn("Failed to reset GKE: {}", e.getMessage());
        }
        logger.info("Reset GKE: deleted {} rows", count);
        return count;
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

                    String url = gcsBase + "/storage/v1/b?project=" + projectId;
                    try {
                        httpPost(url, jsonMapper.writeValueAsString(body), "application/json");
                        count++;
                        logger.debug("Seeded GCS bucket: {}", name);
                    } catch (Exception bucketErr) {
                        // 409 = bucket already exists, that's fine — continue to upload objects
                        if (!bucketErr.getMessage().contains("409")) {
                            throw bucketErr;
                        }
                        logger.debug("GCS bucket already exists: {}", name);
                    }

                    // Upload objects nested in this bucket
                    List<Map<String, Object>> bucketObjects = (List<Map<String, Object>>) bucket.get("objects");
                    if (bucketObjects != null) {
                        for (Map<String, Object> obj : bucketObjects) {
                            try {
                                String key = (String) obj.get("key");
                                String content = (String) obj.getOrDefault("content", "");
                                String contentType = (String) obj.getOrDefault("contentType", "application/octet-stream");

                                String objUrl = gcsBase + "/upload/storage/v1/b/" + name
                                        + "/o?name=" + java.net.URLEncoder.encode(key, StandardCharsets.UTF_8)
                                        + "&uploadType=media";
                                try {
                                    httpPost(objUrl, content, contentType);
                                    count++;
                                    logger.debug("Seeded GCS object: {}/{}", name, key);
                                } catch (Exception objErr) {
                                    // Object may already exist — overwrite by ignoring 409
                                    if (objErr.getMessage() != null && objErr.getMessage().contains("409")) {
                                        count++;
                                        logger.debug("GCS object already exists, skipped: {}/{}", name, key);
                                    } else {
                                        logger.warn("Failed to seed GCS object {}/{}: {}", name, obj.get("key"), objErr.getMessage());
                                    }
                                }
                            } catch (Exception e) {
                                logger.warn("Failed to seed GCS object {}/{}: {}", name, obj.get("key"), e.getMessage());
                            }
                        }
                    }
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

                    String url = gcsBase + "/upload/storage/v1/b/" + bucketName
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

                    String url = pubsubBase + "/v1/projects/" + project + "/topics/" + name;
                    boolean topicCreated = false;
                    try {
                        httpPut(url, "{}");
                        topicCreated = true;
                        count++;
                        logger.debug("Seeded Pub/Sub topic: {}", name);
                    } catch (Exception topicErr) {
                        if (topicErr.getMessage() != null && topicErr.getMessage().contains("409")) {
                            logger.debug("Pub/Sub topic already exists: {}", name);
                        } else {
                            logger.warn("Failed to seed Pub/Sub topic: {}", topicErr.getMessage());
                        }
                    }

                    // Create nested subscriptions for this topic
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> nestedSubs = (List<Map<String, Object>>) topic.get("subscriptions");
                    if (nestedSubs != null) {
                        for (Map<String, Object> sub : nestedSubs) {
                            try {
                                String subName = (String) sub.get("name");
                                int ackDeadline = sub.containsKey("ackDeadlineSeconds")
                                        ? ((Number) sub.get("ackDeadlineSeconds")).intValue()
                                        : sub.containsKey("ack_deadline_seconds")
                                        ? ((Number) sub.get("ack_deadline_seconds")).intValue() : 10;

                                Map<String, Object> subBody = new LinkedHashMap<>();
                                subBody.put("topic", "projects/" + project + "/topics/" + name);
                                subBody.put("ackDeadlineSeconds", ackDeadline);

                                String subUrl = pubsubBase + "/v1/projects/" + project + "/subscriptions/" + subName;
                                try {
                                    httpPut(subUrl, jsonMapper.writeValueAsString(subBody));
                                    count++;
                                    logger.debug("Seeded Pub/Sub subscription: {} (nested under topic {})", subName, name);
                                } catch (Exception subErr) {
                                    if (subErr.getMessage() != null && subErr.getMessage().contains("409")) {
                                        logger.debug("Pub/Sub subscription already exists: {}", subName);
                                    } else {
                                        logger.warn("Failed to seed Pub/Sub subscription {}: {}", subName, subErr.getMessage());
                                    }
                                }
                            } catch (Exception e) {
                                logger.warn("Failed to seed nested Pub/Sub subscription: {}", e.getMessage());
                            }
                        }
                    }

                    // Only publish seed messages for newly created topics
                    if (topicCreated) {
                        @SuppressWarnings("unchecked")
                        List<Map<String, Object>> messages = (List<Map<String, Object>>) topic.get("messages");
                        if (messages != null) {
                            for (Map<String, Object> msg : messages) {
                                try {
                                    String data = (String) msg.get("data");
                                    if (data != null) {
                                        String encodedData = java.util.Base64.getEncoder().encodeToString(
                                                data.getBytes(StandardCharsets.UTF_8));

                                        Map<String, Object> pubMsg = new LinkedHashMap<>();
                                        pubMsg.put("data", encodedData);
                                        Object attrs = msg.get("attributes");
                                        if (attrs != null) {
                                            pubMsg.put("attributes", attrs);
                                        }

                                        Map<String, Object> publishBody = Map.of("messages", List.of(pubMsg));
                                        String publishUrl = pubsubBase + "/v1/projects/" + project + "/topics/" + name + ":publish";
                                        httpPost(publishUrl, jsonMapper.writeValueAsString(publishBody), "application/json");
                                        count++;
                                    }
                                } catch (Exception me) {
                                    logger.warn("Failed to publish Pub/Sub message to {}: {}", name, me.getMessage());
                                }
                            }
                        }
                    }
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

                    String url = pubsubBase + "/v1/projects/" + project + "/subscriptions/" + name;
                    try {
                        httpPut(url, jsonMapper.writeValueAsString(body));
                        count++;
                        logger.debug("Seeded Pub/Sub subscription: {}", name);
                    } catch (Exception subErr) {
                        if (subErr.getMessage() != null && subErr.getMessage().contains("409")) {
                            logger.debug("Pub/Sub subscription already exists: {}", name);
                        } else {
                            logger.warn("Failed to seed Pub/Sub subscription {}: {}", name, subErr.getMessage());
                        }
                    }
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

                    String url = bigqueryBase + "/bigquery/v2/projects/" + projectId + "/datasets";
                    try {
                        httpPost(url, jsonMapper.writeValueAsString(body), "application/json");
                        count++;
                        logger.debug("Seeded BigQuery dataset: {}", name);
                    } catch (Exception dsErr) {
                        if (dsErr.getMessage() != null && dsErr.getMessage().contains("409")) {
                            logger.debug("BigQuery dataset already exists: {}", name);
                            count++;
                        } else if (dsErr.getMessage() != null && dsErr.getMessage().contains("UNIQUE constraint")) {
                            // Emulator has stale SQLite state — reset and retry
                            logger.info("BigQuery stale state detected for dataset {}, resetting", name);
                            try {
                                httpDelete(bigqueryBase + "/bigquery/v2/projects/" + projectId
                                        + "/datasets/" + name + "?deleteContents=true");
                            } catch (Exception ignored) {}
                            try {
                                httpPost(url, jsonMapper.writeValueAsString(body), "application/json");
                                count++;
                            } catch (Exception retryErr) {
                                logger.warn("Failed to seed BigQuery dataset {} after reset: {}", name, retryErr.getMessage());
                            }
                        } else {
                            logger.warn("Failed to seed BigQuery dataset {}: {}", name, dsErr.getMessage());
                        }
                    }
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

                    String url = bigqueryBase + "/bigquery/v2/projects/" + projectId
                            + "/datasets/" + datasetName + "/tables";
                    try {
                        httpPost(url, jsonMapper.writeValueAsString(body), "application/json");
                        count++;
                        logger.debug("Seeded BigQuery table: {}.{}", datasetName, tableName);
                    } catch (Exception tblErr) {
                        if (tblErr.getMessage() != null &&
                                (tblErr.getMessage().contains("409") || tblErr.getMessage().contains("UNIQUE constraint"))) {
                            logger.debug("BigQuery table already exists: {}.{}", datasetName, tableName);
                            count++;
                        } else {
                            logger.warn("Failed to seed BigQuery table {}.{}: {}", datasetName, tableName, tblErr.getMessage());
                        }
                    }

                    // Insert rows if present
                    List<Map<String, Object>> rows = (List<Map<String, Object>>) table.get("rows");
                    if (rows != null && !rows.isEmpty()) {
                        try {
                            List<Map<String, Object>> insertRows = new ArrayList<>();
                            int idx = 0;
                            for (Map<String, Object> row : rows) {
                                Map<String, Object> insertRow = new LinkedHashMap<>();
                                insertRow.put("insertId", "seed-" + tableName + "-" + idx++);
                                insertRow.put("json", row);
                                insertRows.add(insertRow);
                            }
                            Map<String, Object> insertBody = new LinkedHashMap<>();
                            insertBody.put("rows", insertRows);

                            String insertUrl = bigqueryBase + "/bigquery/v2/projects/" + projectId
                                    + "/datasets/" + datasetName + "/tables/" + tableName + "/insertAll";
                            httpPost(insertUrl, jsonMapper.writeValueAsString(insertBody), "application/json");
                            count += rows.size();
                            logger.debug("Seeded {} rows into {}.{}", rows.size(), datasetName, tableName);
                        } catch (Exception e) {
                            logger.warn("Failed to insert rows into {}.{}: {}", datasetName, tableName, e.getMessage());
                        }
                    }
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

        List<Map<String, Object>> secrets = (List<Map<String, Object>>) sm.get("secrets");
        if (secrets != null) {
            for (Map<String, Object> secret : secrets) {
                try {
                    String secretId = (String) secret.get("name");

                    // Insert secret directly into PostgreSQL
                    try (var conn = dataSource.getConnection();
                         var ps = conn.prepareStatement(
                                 "INSERT INTO secrets (project_id, secret_id, labels) VALUES (?, ?, '{}') " +
                                 "ON CONFLICT (project_id, secret_id) DO NOTHING")) {
                        ps.setString(1, projectId);
                        ps.setString(2, secretId);
                        ps.executeUpdate();
                    }

                    // Add versions if present
                    List<Map<String, Object>> versions = (List<Map<String, Object>>) secret.get("versions");
                    if (versions != null) {
                        int versionNum = 1;
                        for (Map<String, Object> ver : versions) {
                            String data = (String) ver.get("data");
                            String state = (String) ver.getOrDefault("state", "ENABLED");
                            if (data != null) {
                                try (var conn = dataSource.getConnection();
                                     var ps = conn.prepareStatement(
                                             "INSERT INTO secret_versions (project_id, secret_id, version_number, payload, state) " +
                                             "VALUES (?, ?, ?, ?, ?) ON CONFLICT (project_id, secret_id, version_number) DO NOTHING")) {
                                    ps.setString(1, projectId);
                                    ps.setString(2, secretId);
                                    ps.setInt(3, versionNum++);
                                    ps.setBytes(4, data.getBytes(StandardCharsets.UTF_8));
                                    ps.setString(5, state);
                                    ps.executeUpdate();
                                }
                            }
                        }
                    } else {
                        // Legacy format: single "value" field
                        String value = (String) secret.get("value");
                        if (value != null) {
                            try (var conn = dataSource.getConnection();
                                 var ps = conn.prepareStatement(
                                         "INSERT INTO secret_versions (project_id, secret_id, version_number, payload, state) " +
                                         "VALUES (?, ?, 1, ?, 'ENABLED') ON CONFLICT (project_id, secret_id, version_number) DO NOTHING")) {
                                ps.setString(1, projectId);
                                ps.setString(2, secretId);
                                ps.setBytes(3, value.getBytes(StandardCharsets.UTF_8));
                                ps.executeUpdate();
                            }
                        }
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

    // ========== Memorystore seed ==========

    @SuppressWarnings("unchecked")
    private int seedMemorystore(Object msData) {
        if (!(msData instanceof Map)) return 0;
        Map<String, Object> ms = (Map<String, Object>) msData;
        int count = 0;

        // Seed string keys
        List<Map<String, Object>> keys = (List<Map<String, Object>>) ms.get("keys");
        if (keys != null) {
            for (Map<String, Object> entry : keys) {
                try {
                    String key = (String) entry.get("key");
                    String value = (String) entry.get("value");
                    if (key != null && value != null) {
                        try (var conn = dataSource.getConnection();
                             var ps = conn.prepareStatement(
                                     "INSERT INTO redis_data (project_id, db_number, key_name, data_type, value) " +
                                     "VALUES (?, 0, ?, 'string', to_jsonb(?::text)) " +
                                     "ON CONFLICT (project_id, db_number, key_name) DO UPDATE SET value = to_jsonb(?::text), data_type = 'string'")) {
                            ps.setString(1, config.getProjectId());
                            ps.setString(2, key);
                            ps.setString(3, value);
                            ps.setString(4, value);
                            ps.executeUpdate();
                        }
                        count++;
                    }
                } catch (Exception e) {
                    logger.warn("Failed to seed memorystore key: {}", e.getMessage());
                }
            }
        }

        // Seed hashes
        List<Map<String, Object>> hashes = (List<Map<String, Object>>) ms.get("hashes");
        if (hashes != null) {
            for (Map<String, Object> entry : hashes) {
                try {
                    String key = (String) entry.get("key");
                    Map<String, Object> fields = (Map<String, Object>) entry.get("fields");
                    if (key != null && fields != null) {
                        String json = jsonMapper.writeValueAsString(fields);
                        try (var conn = dataSource.getConnection();
                             var ps = conn.prepareStatement(
                                     "INSERT INTO redis_data (project_id, db_number, key_name, data_type, value) " +
                                     "VALUES (?, 0, ?, 'hash', ?::jsonb) " +
                                     "ON CONFLICT (project_id, db_number, key_name) DO UPDATE SET value = ?::jsonb, data_type = 'hash'")) {
                            ps.setString(1, config.getProjectId());
                            ps.setString(2, key);
                            ps.setString(3, json);
                            ps.setString(4, json);
                            ps.executeUpdate();
                        }
                        count++;
                    }
                } catch (Exception e) {
                    logger.warn("Failed to seed memorystore hash: {}", e.getMessage());
                }
            }
        }
        return count;
    }

    // ========== Spanner seed ==========

    @SuppressWarnings("unchecked")
    private int seedSpanner(Object spannerData) {
        if (!(spannerData instanceof Map)) return 0;
        Map<String, Object> spanner = (Map<String, Object>) spannerData;
        int count = 0;
        String projectId = config.getProjectId();
        String spannerBase = "http://localhost:" + spannerRestPort;

        // Wait for Spanner emulator to be ready (it starts slower than the gateway)
        boolean spannerReady = false;
        for (int attempt = 0; attempt < 15; attempt++) {
            try {
                String checkUrl = spannerBase + "/v1/projects/" + projectId + "/instances";
                httpPostAndReturn(checkUrl.replace("/instances", "/instances"), "{}", "application/json");
                spannerReady = true;
                break;
            } catch (Exception e) {
                // Also try a simple GET to check if the REST port is up
                try {
                    java.net.http.HttpRequest req = java.net.http.HttpRequest.newBuilder()
                            .uri(URI.create(spannerBase + "/v1/projects/" + projectId + "/instances"))
                            .timeout(Duration.ofSeconds(3))
                            .GET().build();
                    java.net.http.HttpResponse<String> resp = httpClient.send(req, java.net.http.HttpResponse.BodyHandlers.ofString());
                    if (resp.statusCode() < 500) {
                        spannerReady = true;
                        break;
                    }
                } catch (Exception ignored) {}
                logger.info("Waiting for Spanner emulator to be ready (attempt {}/15)...", attempt + 1);
                try { Thread.sleep(2000); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); return 0; }
            }
        }
        if (!spannerReady) {
            logger.warn("Spanner emulator not ready after 30s, skipping seed");
            return 0;
        }

        List<Map<String, Object>> instances = (List<Map<String, Object>>) spanner.get("instances");
        if (instances != null) {
            for (Map<String, Object> instance : instances) {
                try {
                    String instanceName = (String) instance.get("name");

                    // Create instance (ignore 409 if already exists)
                    Map<String, Object> instanceConfig = new LinkedHashMap<>();
                    instanceConfig.put("config", "emulator-config");
                    instanceConfig.put("displayName", instanceName);
                    instanceConfig.put("nodeCount", 1);

                    Map<String, Object> createBody = new LinkedHashMap<>();
                    createBody.put("instanceId", instanceName);
                    createBody.put("instance", instanceConfig);

                    String instanceUrl = spannerBase + "/v1/projects/" + projectId + "/instances";
                    try {
                        httpPost(instanceUrl, jsonMapper.writeValueAsString(createBody), "application/json");
                    } catch (Exception e) {
                        // 409 Conflict is expected if instance already exists
                        if (!e.getMessage().contains("409")) {
                            logger.warn("Failed to create Spanner instance {}: {}", instanceName, e.getMessage());
                        }
                    }
                    count++;
                    logger.debug("Seeded Spanner instance: {}", instanceName);

                    // Create databases for this instance
                    List<Map<String, Object>> databases = (List<Map<String, Object>>) instance.get("databases");
                    if (databases != null) {
                        for (Map<String, Object> db : databases) {
                            try {
                                String dbName = (String) db.get("name");
                                List<String> ddlStatements = (List<String>) db.get("ddl");

                                Map<String, Object> dbBody = new LinkedHashMap<>();
                                dbBody.put("createStatement", "CREATE DATABASE " + dbName);
                                if (ddlStatements != null && !ddlStatements.isEmpty()) {
                                    dbBody.put("extraStatements", ddlStatements);
                                }

                                String dbUrl = spannerBase + "/v1/projects/" + projectId
                                        + "/instances/" + instanceName + "/databases";
                                try {
                                    httpPost(dbUrl, jsonMapper.writeValueAsString(dbBody), "application/json");
                                } catch (Exception e) {
                                    if (!e.getMessage().contains("409")) {
                                        logger.warn("Failed to create Spanner database {}: {}", dbName, e.getMessage());
                                    }
                                }
                                count++;
                                logger.debug("Seeded Spanner database: {}/{}", instanceName, dbName);

                                // Insert row data if present
                                List<Map<String, Object>> dataEntries = (List<Map<String, Object>>) db.get("data");
                                if (dataEntries != null && !dataEntries.isEmpty()) {
                                    count += seedSpannerRows(spannerBase, projectId, instanceName, dbName, dataEntries);
                                }
                            } catch (Exception e) {
                                logger.warn("Failed to seed Spanner database: {}", e.getMessage());
                            }
                        }
                    }
                } catch (Exception e) {
                    logger.warn("Failed to seed Spanner instance: {}", e.getMessage());
                }
            }
        }
        return count;
    }

    /**
     * Insert rows into Spanner tables via the REST commit API.
     * Uses insertOrUpdate mutations for idempotent re-seeding.
     */
    @SuppressWarnings("unchecked")
    private int seedSpannerRows(String spannerBase, String projectId, String instanceName,
                                String dbName, List<Map<String, Object>> dataEntries) {
        int count = 0;
        String sessionName = null;
        String dbPath = "projects/" + projectId + "/instances/" + instanceName + "/databases/" + dbName;

        try {
            // 1. Create a session
            String sessionUrl = spannerBase + "/v1/" + dbPath + "/sessions";
            String sessionResp = httpPostAndReturn(sessionUrl, "{}", "application/json");
            Map<String, Object> sessionObj = jsonMapper.readValue(sessionResp, Map.class);
            sessionName = (String) sessionObj.get("name");

            if (sessionName == null) {
                logger.warn("Failed to create Spanner session for {}/{}", instanceName, dbName);
                return 0;
            }

            // 2. Build mutations from data entries
            List<Map<String, Object>> mutations = new ArrayList<>();
            for (Map<String, Object> entry : dataEntries) {
                String table = (String) entry.get("table");
                List<String> columns = (List<String>) entry.get("columns");
                List<List<String>> rows = (List<List<String>>) entry.get("rows");

                if (table != null && columns != null && rows != null) {
                    // Convert rows to list of string lists for Spanner values format
                    List<List<String>> values = new ArrayList<>();
                    for (List<String> row : rows) {
                        List<String> rowValues = new ArrayList<>();
                        for (Object val : row) {
                            rowValues.add(val != null ? String.valueOf(val) : null);
                        }
                        values.add(rowValues);
                    }

                    Map<String, Object> insertOrUpdate = new LinkedHashMap<>();
                    insertOrUpdate.put("table", table);
                    insertOrUpdate.put("columns", columns);
                    insertOrUpdate.put("values", values);

                    Map<String, Object> mutation = new LinkedHashMap<>();
                    mutation.put("insertOrUpdate", insertOrUpdate);
                    mutations.add(mutation);

                    count += rows.size();
                }
            }

            if (!mutations.isEmpty()) {
                // 3. Commit the mutations
                Map<String, Object> commitBody = new LinkedHashMap<>();
                Map<String, Object> txn = new LinkedHashMap<>();
                txn.put("readWrite", Map.of());
                commitBody.put("singleUseTransaction", txn);
                commitBody.put("mutations", mutations);

                String commitUrl = spannerBase + "/v1/" + sessionName + ":commit";
                httpPost(commitUrl, jsonMapper.writeValueAsString(commitBody), "application/json");
                logger.debug("Seeded {} rows into Spanner {}/{}", count, instanceName, dbName);
            }
        } catch (Exception e) {
            logger.warn("Failed to seed Spanner rows for {}/{}: {}", instanceName, dbName, e.getMessage());
        } finally {
            // 4. Delete the session
            if (sessionName != null) {
                try {
                    httpDelete(spannerBase + "/v1/" + sessionName);
                } catch (Exception e) {
                    logger.debug("Failed to delete Spanner session: {}", e.getMessage());
                }
            }
        }
        return count;
    }

    // ========== Firestore seed ==========

    @SuppressWarnings("unchecked")
    private int seedFirestore(Object fsData) {
        if (!(fsData instanceof Map)) return 0;
        Map<String, Object> fs = (Map<String, Object>) fsData;
        int count = 0;
        String projectId = config.getProjectId();
        String firestoreBase = "http://localhost:" + firestorePort;

        List<Map<String, Object>> collections = (List<Map<String, Object>>) fs.get("collections");
        if (collections != null) {
            for (Map<String, Object> coll : collections) {
                String collName = (String) coll.get("name");
                List<Map<String, Object>> documents = (List<Map<String, Object>>) coll.get("documents");
                if (documents != null) {
                    for (Map<String, Object> doc : documents) {
                        try {
                            String docId = (String) doc.get("id");
                            Map<String, Object> fields = (Map<String, Object>) doc.get("fields");
                            if (docId != null && fields != null) {
                                // Convert fields to Firestore value format
                                Map<String, Object> firestoreFields = new LinkedHashMap<>();
                                for (Map.Entry<String, Object> entry : fields.entrySet()) {
                                    firestoreFields.put(entry.getKey(), toFirestoreValue(entry.getValue()));
                                }
                                Map<String, Object> body = Map.of("fields", firestoreFields);

                                String url = firestoreBase + "/v1/projects/" + projectId
                                    + "/databases/(default)/documents/" + collName + "/" + docId;
                                // Use PATCH to create or update (upsert) with retry for emulator startup
                                String bodyJson = jsonMapper.writeValueAsString(body);
                                int maxRetries = 3;
                                for (int attempt = 1; attempt <= maxRetries; attempt++) {
                                    try {
                                        httpPatch(url, bodyJson);
                                        break;
                                    } catch (Exception retryEx) {
                                        if (attempt == maxRetries) throw retryEx;
                                        logger.debug("Firestore seed attempt {}/{} failed, retrying: {}", attempt, maxRetries, retryEx.getMessage());
                                        Thread.sleep(1000L * attempt);
                                    }
                                }
                                count++;
                                logger.debug("Seeded Firestore document: {}/{}", collName, docId);
                            }
                        } catch (Exception e) {
                            logger.warn("Failed to seed Firestore document: {}", e.getMessage());
                        }
                    }
                }
            }
        }
        return count;
    }

    private Object toFirestoreValue(Object value) {
        if (value == null) {
            Map<String, Object> nullMap = new LinkedHashMap<>();
            nullMap.put("nullValue", null);
            return nullMap;
        }
        if (value instanceof String) return Map.of("stringValue", value);
        if (value instanceof Integer || value instanceof Long) return Map.of("integerValue", String.valueOf(value));
        if (value instanceof Double || value instanceof Float) return Map.of("doubleValue", value);
        if (value instanceof Boolean) return Map.of("booleanValue", value);
        if (value instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> map = (Map<String, Object>) value;
            Map<String, Object> mapFields = new LinkedHashMap<>();
            for (Map.Entry<String, Object> entry : map.entrySet()) {
                mapFields.put(entry.getKey(), toFirestoreValue(entry.getValue()));
            }
            return Map.of("mapValue", Map.of("fields", mapFields));
        }
        if (value instanceof List) {
            @SuppressWarnings("unchecked")
            List<Object> list = (List<Object>) value;
            List<Object> values = new ArrayList<>();
            for (Object item : list) {
                values.add(toFirestoreValue(item));
            }
            return Map.of("arrayValue", Map.of("values", values));
        }
        return Map.of("stringValue", String.valueOf(value));
    }

    // ========== Bigtable seed ==========

    @SuppressWarnings("unchecked")
    private int seedBigtable(Object btData) {
        if (!(btData instanceof Map)) return 0;
        Map<String, Object> bt = (Map<String, Object>) btData;
        int count = 0;

        List<Map<String, Object>> instances = (List<Map<String, Object>>) bt.get("instances");
        if (instances != null) {
            for (Map<String, Object> instance : instances) {
                String instanceName = (String) instance.get("name");
                List<Map<String, Object>> tables = (List<Map<String, Object>>) instance.get("tables");
                if (tables != null) {
                    for (Map<String, Object> table : tables) {
                        try {
                            String tableName = (String) table.get("name");
                            List<String> columnFamilies = (List<String>) table.get("columnFamilies");
                            List<Map<String, Object>> rows = (List<Map<String, Object>>) table.get("rows");

                            if (rows != null) {
                                for (Map<String, Object> row : rows) {
                                    String rowKey = (String) row.get("key");
                                    Map<String, Object> cells = (Map<String, Object>) row.get("cells");
                                    if (rowKey != null && cells != null) {
                                        String cellsJson = jsonMapper.writeValueAsString(cells);
                                        try (var conn = dataSource.getConnection();
                                             var ps = conn.prepareStatement(
                                                 "INSERT INTO bigtable_data (project_id, instance_id, table_name, row_key, cells) " +
                                                 "VALUES (?, ?, ?, ?, ?::jsonb) " +
                                                 "ON CONFLICT (project_id, instance_id, table_name, row_key) DO UPDATE SET cells = ?::jsonb")) {
                                            ps.setString(1, config.getProjectId());
                                            ps.setString(2, instanceName);
                                            ps.setString(3, tableName);
                                            ps.setString(4, rowKey);
                                            ps.setString(5, cellsJson);
                                            ps.setString(6, cellsJson);
                                            ps.executeUpdate();
                                        }
                                        count++;
                                    }
                                }
                            }
                            logger.debug("Seeded Bigtable table: {}/{} ({} rows)", instanceName, tableName, rows != null ? rows.size() : 0);
                        } catch (Exception e) {
                            logger.warn("Failed to seed Bigtable table: {}", e.getMessage());
                        }
                    }
                }
            }
        }
        return count;
    }

    // ========== Cloud Tasks seed ==========

    @SuppressWarnings("unchecked")
    private int seedCloudTasks(Object ctData) {
        if (!(ctData instanceof Map)) return 0;
        Map<String, Object> ct = (Map<String, Object>) ctData;
        int count = 0;
        String projectId = config.getProjectId();

        List<Map<String, Object>> queues = (List<Map<String, Object>>) ct.get("queues");
        if (queues != null) {
            for (Map<String, Object> queue : queues) {
                try {
                    String queueId = (String) queue.get("name");
                    String locationId = (String) queue.getOrDefault("location", "us-central1");
                    String state = (String) queue.getOrDefault("state", "RUNNING");
                    int maxAttempts = queue.containsKey("maxAttempts")
                        ? ((Number) queue.get("maxAttempts")).intValue()
                        : queue.containsKey("max_attempts")
                        ? ((Number) queue.get("max_attempts")).intValue() : 5;

                    try (var conn = dataSource.getConnection();
                         var ps = conn.prepareStatement(
                             "INSERT INTO task_queues (project_id, queue_id, location_id, state, max_attempts) " +
                             "VALUES (?, ?, ?, ?, ?) ON CONFLICT (project_id, queue_id) DO NOTHING")) {
                        ps.setString(1, projectId);
                        ps.setString(2, queueId);
                        ps.setString(3, locationId);
                        ps.setString(4, state);
                        ps.setInt(5, maxAttempts);
                        ps.executeUpdate();
                    }
                    count++;
                    logger.debug("Seeded Cloud Tasks queue: {}", queueId);
                } catch (Exception e) {
                    logger.warn("Failed to seed Cloud Tasks queue: {}", e.getMessage());
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
            String errorBody = response.body();
            logger.warn("HTTP PUT {} failed ({}): {}", url, response.statusCode(), errorBody);
            throw new RuntimeException(String.format("HTTP PUT %s failed with status %d: %s",
                    url, response.statusCode(), errorBody));
        }
    }

    private void httpPatch(String url, String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .method("PATCH", HttpRequest.BodyPublishers.ofString(body))
                .header("Content-Type", "application/json")
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 400) {
            logger.warn("HTTP PATCH {} failed ({}): {}", url, response.statusCode(), response.body());
            throw new RuntimeException(String.format("HTTP PATCH %s failed with status %d", url, response.statusCode()));
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
            String errorBody = response.body();
            logger.warn("HTTP POST {} failed ({}): {}", url, response.statusCode(), errorBody);
            throw new RuntimeException(String.format("HTTP POST %s failed with status %d: %s",
                    url, response.statusCode(), errorBody));
        }
    }

    private String httpPostAndReturn(String url, String body, String contentType) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .header("Content-Type", contentType)
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 400) {
            String errorBody = response.body();
            logger.warn("HTTP POST {} failed ({}): {}", url, response.statusCode(), errorBody);
            throw new RuntimeException(String.format("HTTP POST %s failed with status %d: %s",
                    url, response.statusCode(), errorBody));
        }
        return response.body();
    }

    private String httpGet(String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 400) {
            String errorBody = response.body();
            logger.debug("HTTP GET {} failed ({}): {}", url, response.statusCode(), errorBody);
            throw new RuntimeException(String.format("HTTP GET %s failed with status %d: %s",
                    url, response.statusCode(), errorBody));
        }
        return response.body();
    }

    private void httpDelete(String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .DELETE()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 400) {
            logger.debug("HTTP DELETE {} failed ({})", url, response.statusCode());
        }
    }

    /**
     * Close the underlying HTTP client to release resources.
     */
    public void close() {
        httpClient.close();
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
