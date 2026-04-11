package com.localcloud.admin;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse.BodyHandlers;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.server.annotation.Get;
import com.localcloud.config.LocalCloudConfig;
import com.localcloud.config.ServiceRegistry;
import com.localcloud.config.ServiceRegistry.ServiceDefinition;
import com.localcloud.persistence.PostgresDataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Exports current emulator state as a seed-compatible YAML file.
 * This is the inverse of {@link SeedService} — it reads data from
 * each service and assembles a YAML structure that can be re-imported.
 * Registered at the {@code /_localcloud} path prefix.
 */
public class ExportService {

    private static final Logger logger = LoggerFactory.getLogger(ExportService.class);

    private final LocalCloudConfig config;
    private final PostgresDataSource dataSource;
    private final ServiceRegistry registry;
    private final HttpClient httpClient;
    private final ObjectMapper mapper;
    private final YAMLMapper yamlMapper;

    // Base URLs computed from registry
    private final String gcsBase;
    private final String pubsubBase;
    private final String bigqueryBase;
    private final String spannerBase;

    public ExportService(LocalCloudConfig config, PostgresDataSource dataSource, ServiceRegistry registry) {
        this.config = config;
        this.dataSource = dataSource;
        this.registry = registry;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        this.mapper = new ObjectMapper();
        this.yamlMapper = new YAMLMapper();

        // Compute base URLs from registry definitions
        this.gcsBase = baseUrl(registry.getService("gcs"));
        this.pubsubBase = baseUrl(registry.getService("pubsub"));
        this.bigqueryBase = baseUrl(registry.getService("bigquery"));

        ServiceDefinition spannerDef = registry.getService("spanner");
        int spannerRestPort = spannerDef != null && spannerDef.additionalPorts().containsKey("rest")
                ? spannerDef.additionalPorts().get("rest") : 9020;
        this.spannerBase = "http://localhost:" + spannerRestPort;
    }

    private static String baseUrl(ServiceDefinition def) {
        if (def == null) return "http://localhost:0";
        return "http://localhost:" + def.port();
    }

    /**
     * Export current state of all services as a seed-compatible YAML file.
     */
    @Get("/export")
    public HttpResponse export() {
        try {
            Map<String, Object> seedData = new LinkedHashMap<>();
            seedData.put("version", "1.0");
            seedData.put("project", config.getProjectId());

            Map<String, Object> services = new LinkedHashMap<>();

            // GCS: list buckets and object keys (no content)
            try {
                Map<String, Object> gcs = exportGcs();
                if (gcs != null && !gcs.isEmpty()) {
                    services.put("gcs", gcs);
                }
            } catch (Exception e) {
                logger.warn("Failed to export GCS: {}", e.getMessage());
            }

            // Pub/Sub: topics and subscriptions
            try {
                Map<String, Object> pubsub = exportPubSub();
                if (pubsub != null && !pubsub.isEmpty()) {
                    services.put("pubsub", pubsub);
                }
            } catch (Exception e) {
                logger.warn("Failed to export Pub/Sub: {}", e.getMessage());
            }

            // BigQuery: datasets and tables (schema only, no row data)
            try {
                Map<String, Object> bigquery = exportBigQuery();
                if (bigquery != null && !bigquery.isEmpty()) {
                    services.put("bigquery", bigquery);
                }
            } catch (Exception e) {
                logger.warn("Failed to export BigQuery: {}", e.getMessage());
            }

            // Secret Manager: secret names only (no values)
            try {
                Map<String, Object> secretmanager = exportSecretManager();
                if (secretmanager != null && !secretmanager.isEmpty()) {
                    services.put("secretmanager", secretmanager);
                }
            } catch (Exception e) {
                logger.warn("Failed to export Secret Manager: {}", e.getMessage());
            }

            // Spanner: instances, databases, DDL
            try {
                Map<String, Object> spanner = exportSpanner();
                if (spanner != null && !spanner.isEmpty()) {
                    services.put("spanner", spanner);
                }
            } catch (Exception e) {
                logger.warn("Failed to export Spanner: {}", e.getMessage());
            }

            // Memorystore: all keys
            try {
                Map<String, Object> memorystore = exportMemorystore();
                if (memorystore != null && !memorystore.isEmpty()) {
                    services.put("memorystore", memorystore);
                }
            } catch (Exception e) {
                logger.warn("Failed to export Memorystore: {}", e.getMessage());
            }

            // Cloud Tasks: queues
            try {
                Map<String, Object> cloudtasks = exportCloudTasks();
                if (cloudtasks != null && !cloudtasks.isEmpty()) {
                    services.put("cloudtasks", cloudtasks);
                }
            } catch (Exception e) {
                logger.warn("Failed to export Cloud Tasks: {}", e.getMessage());
            }

            seedData.put("services", services);

            String yaml = yamlMapper.writerWithDefaultPrettyPrinter().writeValueAsString(seedData);

            return HttpResponse.of(HttpStatus.OK, MediaType.parse("application/yaml"), yaml);
        } catch (Exception e) {
            logger.error("Export failed: {}", e.getMessage(), e);
            try {
                String error = mapper.writeValueAsString(Map.of("error", true, "message", e.getMessage()));
                return HttpResponse.of(HttpStatus.INTERNAL_SERVER_ERROR, MediaType.JSON, error);
            } catch (Exception ex) {
                return HttpResponse.of(HttpStatus.INTERNAL_SERVER_ERROR,
                        MediaType.PLAIN_TEXT_UTF_8, "Export failed");
            }
        }
    }

    // ========== GCS ==========

    @SuppressWarnings("unchecked")
    private Map<String, Object> exportGcs() throws Exception {
        String projectId = config.getProjectId();
        String url = gcsBase + "/storage/v1/b?project=" + projectId;
        String response = proxyGet(url);
        Map<String, Object> data = mapper.readValue(response, Map.class);

        List<Map<String, Object>> items = (List<Map<String, Object>>) data.get("items");
        if (items == null || items.isEmpty()) {
            return Map.of();
        }

        List<Map<String, Object>> buckets = new ArrayList<>();
        for (Map<String, Object> item : items) {
            Map<String, Object> bucket = new LinkedHashMap<>();
            bucket.put("name", item.get("name"));
            if (item.get("location") != null) {
                bucket.put("location", item.get("location"));
            }

            // List objects in this bucket (keys only, no content)
            try {
                String objUrl = gcsBase + "/storage/v1/b/" + item.get("name") + "/o";
                String objResponse = proxyGet(objUrl);
                Map<String, Object> objData = mapper.readValue(objResponse, Map.class);
                List<Map<String, Object>> objItems = (List<Map<String, Object>>) objData.get("items");
                if (objItems != null && !objItems.isEmpty()) {
                    List<Map<String, Object>> objects = new ArrayList<>();
                    for (Map<String, Object> obj : objItems) {
                        Map<String, Object> o = new LinkedHashMap<>();
                        o.put("key", obj.get("name"));
                        if (obj.get("contentType") != null) {
                            o.put("contentType", obj.get("contentType"));
                        }
                        objects.add(o);
                    }
                    bucket.put("objects", objects);
                }
            } catch (Exception e) {
                logger.debug("Failed to list objects in bucket {}: {}", item.get("name"), e.getMessage());
            }

            buckets.add(bucket);
        }

        return Map.of("buckets", buckets);
    }

    // ========== Pub/Sub ==========

    @SuppressWarnings("unchecked")
    private Map<String, Object> exportPubSub() throws Exception {
        String projectId = config.getProjectId();
        Map<String, Object> result = new LinkedHashMap<>();

        // List topics
        String topicUrl = pubsubBase + "/v1/projects/" + projectId + "/topics";
        String topicResponse = proxyGet(topicUrl);
        Map<String, Object> topicData = mapper.readValue(topicResponse, Map.class);
        List<Map<String, Object>> rawTopics = (List<Map<String, Object>>) topicData.get("topics");

        List<Map<String, Object>> topics = new ArrayList<>();
        if (rawTopics != null) {
            for (Map<String, Object> t : rawTopics) {
                String fullName = (String) t.get("name");
                // Extract short name from projects/{project}/topics/{name}
                String shortName = fullName != null && fullName.contains("/")
                        ? fullName.substring(fullName.lastIndexOf('/') + 1) : fullName;
                Map<String, Object> topic = new LinkedHashMap<>();
                topic.put("name", shortName);
                topic.put("project", projectId);
                topics.add(topic);
            }
        }
        if (!topics.isEmpty()) {
            result.put("topics", topics);
        }

        // List subscriptions
        String subUrl = pubsubBase + "/v1/projects/" + projectId + "/subscriptions";
        String subResponse = proxyGet(subUrl);
        Map<String, Object> subData = mapper.readValue(subResponse, Map.class);
        List<Map<String, Object>> rawSubs = (List<Map<String, Object>>) subData.get("subscriptions");

        List<Map<String, Object>> subscriptions = new ArrayList<>();
        if (rawSubs != null) {
            for (Map<String, Object> s : rawSubs) {
                String fullName = (String) s.get("name");
                String shortName = fullName != null && fullName.contains("/")
                        ? fullName.substring(fullName.lastIndexOf('/') + 1) : fullName;
                String topicFull = (String) s.get("topic");
                String topicShort = topicFull != null && topicFull.contains("/")
                        ? topicFull.substring(topicFull.lastIndexOf('/') + 1) : topicFull;
                Map<String, Object> sub = new LinkedHashMap<>();
                sub.put("name", shortName);
                sub.put("topic", topicShort);
                sub.put("project", projectId);
                subscriptions.add(sub);
            }
        }
        if (!subscriptions.isEmpty()) {
            result.put("subscriptions", subscriptions);
        }

        return result;
    }

    // ========== BigQuery ==========

    @SuppressWarnings("unchecked")
    private Map<String, Object> exportBigQuery() throws Exception {
        String projectId = config.getProjectId();
        Map<String, Object> result = new LinkedHashMap<>();

        // List datasets
        String dsUrl = bigqueryBase + "/bigquery/v2/projects/" + projectId + "/datasets";
        String dsResponse = proxyGet(dsUrl);
        Map<String, Object> dsData = mapper.readValue(dsResponse, Map.class);
        List<Map<String, Object>> rawDatasets = (List<Map<String, Object>>) dsData.get("datasets");

        if (rawDatasets == null || rawDatasets.isEmpty()) {
            return Map.of();
        }

        List<Map<String, Object>> datasets = new ArrayList<>();
        List<Map<String, Object>> tables = new ArrayList<>();

        for (Map<String, Object> ds : rawDatasets) {
            Map<String, Object> ref = (Map<String, Object>) ds.get("datasetReference");
            String datasetId = ref != null ? (String) ref.get("datasetId") : null;
            if (datasetId == null) continue;

            datasets.add(Map.of("name", datasetId));

            // List tables in this dataset
            try {
                String tablesUrl = bigqueryBase + "/bigquery/v2/projects/" + projectId
                        + "/datasets/" + datasetId + "/tables";
                String tablesResponse = proxyGet(tablesUrl);
                Map<String, Object> tablesData = mapper.readValue(tablesResponse, Map.class);
                List<Map<String, Object>> rawTables = (List<Map<String, Object>>) tablesData.get("tables");
                if (rawTables != null) {
                    for (Map<String, Object> tbl : rawTables) {
                        Map<String, Object> tblRef = (Map<String, Object>) tbl.get("tableReference");
                        String tableId = tblRef != null ? (String) tblRef.get("tableId") : null;
                        if (tableId != null) {
                            Map<String, Object> table = new LinkedHashMap<>();
                            table.put("dataset", datasetId);
                            table.put("name", tableId);
                            // Schema is available at table detail level but omitted for brevity
                            tables.add(table);
                        }
                    }
                }
            } catch (Exception e) {
                logger.debug("Failed to list tables in dataset {}: {}", datasetId, e.getMessage());
            }
        }

        if (!datasets.isEmpty()) {
            result.put("datasets", datasets);
        }
        if (!tables.isEmpty()) {
            result.put("tables", tables);
        }

        return result;
    }

    // ========== Secret Manager ==========

    private Map<String, Object> exportSecretManager() throws Exception {
        if (!config.isPersistenceEnabled()) {
            return Map.of();
        }
        String projectId = config.getProjectId();

        List<Map<String, Object>> secrets = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT secret_id FROM secrets WHERE project_id = ?")) {
            ps.setString(1, projectId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    secrets.add(Map.of("name", rs.getString("secret_id")));
                }
            }
        }

        if (secrets.isEmpty()) {
            return Map.of();
        }
        return Map.of("secrets", secrets);
    }

    // ========== Spanner ==========

    @SuppressWarnings("unchecked")
    private Map<String, Object> exportSpanner() throws Exception {
        String projectId = config.getProjectId();

        // List instances
        String instUrl = spannerBase + "/v1/projects/" + projectId + "/instances";
        String instResponse = proxyGet(instUrl);
        Map<String, Object> instData = mapper.readValue(instResponse, Map.class);
        List<Map<String, Object>> rawInstances = (List<Map<String, Object>>) instData.get("instances");

        if (rawInstances == null || rawInstances.isEmpty()) {
            return Map.of();
        }

        List<Map<String, Object>> instances = new ArrayList<>();
        for (Map<String, Object> inst : rawInstances) {
            String fullName = (String) inst.get("name");
            // Extract instance name from projects/{project}/instances/{name}
            String instName = fullName != null && fullName.contains("/")
                    ? fullName.substring(fullName.lastIndexOf('/') + 1) : fullName;

            Map<String, Object> instance = new LinkedHashMap<>();
            instance.put("name", instName);

            // List databases in this instance
            try {
                String dbUrl = spannerBase + "/v1/projects/" + projectId
                        + "/instances/" + instName + "/databases";
                String dbResponse = proxyGet(dbUrl);
                Map<String, Object> dbData = mapper.readValue(dbResponse, Map.class);
                List<Map<String, Object>> rawDbs = (List<Map<String, Object>>) dbData.get("databases");

                List<Map<String, Object>> databases = new ArrayList<>();
                if (rawDbs != null) {
                    for (Map<String, Object> db : rawDbs) {
                        String dbFullName = (String) db.get("name");
                        String dbName = dbFullName != null && dbFullName.contains("/")
                                ? dbFullName.substring(dbFullName.lastIndexOf('/') + 1) : dbFullName;

                        Map<String, Object> database = new LinkedHashMap<>();
                        database.put("name", dbName);

                        // Get DDL for this database
                        try {
                            String ddlUrl = spannerBase + "/v1/projects/" + projectId
                                    + "/instances/" + instName + "/databases/" + dbName + "/ddl";
                            String ddlResponse = proxyGet(ddlUrl);
                            Map<String, Object> ddlData = mapper.readValue(ddlResponse, Map.class);
                            List<String> statements = (List<String>) ddlData.get("statements");
                            if (statements != null && !statements.isEmpty()) {
                                database.put("ddl", statements);
                            }
                        } catch (Exception e) {
                            logger.debug("Failed to get DDL for {}/{}: {}", instName, dbName, e.getMessage());
                        }

                        databases.add(database);
                    }
                }
                if (!databases.isEmpty()) {
                    instance.put("databases", databases);
                }
            } catch (Exception e) {
                logger.debug("Failed to list databases in instance {}: {}", instName, e.getMessage());
            }

            instances.add(instance);
        }

        return Map.of("instances", instances);
    }

    // ========== Memorystore ==========

    private Map<String, Object> exportMemorystore() throws Exception {
        if (!config.isPersistenceEnabled()) {
            return Map.of();
        }

        Map<String, Object> result = new LinkedHashMap<>();
        List<Map<String, Object>> keys = new ArrayList<>();
        List<Map<String, Object>> hashes = new ArrayList<>();

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT key_name, data_type, value FROM redis_data " +
                 "WHERE db_number = 0 AND (ttl_expires_at IS NULL OR ttl_expires_at > NOW()) " +
                 "ORDER BY key_name")) {
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String keyName = rs.getString("key_name");
                    String dataType = rs.getString("data_type");
                    String value = rs.getString("value");

                    if ("hash".equalsIgnoreCase(dataType)) {
                        Map<String, Object> hash = new LinkedHashMap<>();
                        hash.put("key", keyName);
                        // value is stored as JSONB
                        try {
                            @SuppressWarnings("unchecked")
                            Map<String, Object> fields = mapper.readValue(value, Map.class);
                            hash.put("fields", fields);
                        } catch (Exception e) {
                            hash.put("value", value);
                        }
                        hashes.add(hash);
                    } else {
                        Map<String, Object> key = new LinkedHashMap<>();
                        key.put("key", keyName);
                        key.put("value", value);
                        keys.add(key);
                    }
                }
            }
        }

        if (!keys.isEmpty()) {
            result.put("keys", keys);
        }
        if (!hashes.isEmpty()) {
            result.put("hashes", hashes);
        }

        return result;
    }

    // ========== Cloud Tasks ==========

    private Map<String, Object> exportCloudTasks() throws Exception {
        if (!config.isPersistenceEnabled()) {
            return Map.of();
        }
        String projectId = config.getProjectId();

        List<Map<String, Object>> queues = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT queue_id, location_id, max_attempts FROM task_queues WHERE project_id = ?")) {
            ps.setString(1, projectId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> queue = new LinkedHashMap<>();
                    queue.put("name", rs.getString("queue_id"));
                    queue.put("location", rs.getString("location_id"));
                    queue.put("maxAttempts", rs.getInt("max_attempts"));
                    queues.add(queue);
                }
            }
        }

        if (queues.isEmpty()) {
            return Map.of();
        }
        return Map.of("queues", queues);
    }

    // ========== HTTP proxy helper ==========

    private String proxyGet(String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();

        java.net.http.HttpResponse<String> response = httpClient.send(request, BodyHandlers.ofString());
        return response.body();
    }
}
