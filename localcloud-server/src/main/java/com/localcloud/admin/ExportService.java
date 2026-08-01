package com.localcloud.admin;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse.BodyHandlers;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.ResultSet;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.TreeMap;

import com.fasterxml.jackson.databind.ObjectMapper;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.params.ScanParams;
import redis.clients.jedis.resps.ScanResult;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.server.ServiceRequestContext;
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
 * Registered at the root path prefix.
 */
public class ExportService {

    private static final Logger logger = LoggerFactory.getLogger(ExportService.class);

    private final LocalCloudConfig config;
    private final PostgresDataSource dataSource;
    private final ServiceRegistry registry;
    private final ProjectService projectService;
    private final HttpClient httpClient;
    private final ObjectMapper mapper;
    private final YAMLMapper yamlMapper;

    // Base URLs computed from registry
    private final String gcsBase;
    private final String pubsubBase;
    private final String bigqueryBase;
    private final String spannerBase;

    public ExportService(LocalCloudConfig config, PostgresDataSource dataSource, ServiceRegistry registry,
                         ProjectService projectService) {
        this.config = config;
        this.dataSource = dataSource;
        this.registry = registry;
        this.projectService = projectService;
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
                ? spannerDef.additionalPorts().get("rest") : 24086;
        this.spannerBase = "http://localhost:" + spannerRestPort;
    }

    private static String baseUrl(ServiceDefinition def) {
        if (def == null) return "http://localhost:0";
        return "http://localhost:" + def.port();
    }

    /**
     * Export current state as a seed-compatible YAML file.
     *
     * <p>Optional query parameters: {@code ?project=my-project} exports state
     * for that project, and {@code ?services=gcs,pubsub} limits the export to
     * selected service ids. Omitting either keeps the configured defaults.</p>
     */
    @Get("/export")
    public HttpResponse export(ServiceRequestContext ctx) {
        try {
            Set<String> selectedServices = parseSelectedServices(ctx.queryParams().get("services"));
            String yaml = exportYaml(selectedServices, ctx.queryParams().get("project"));

            return HttpResponse.of(HttpStatus.OK, MediaType.parse("application/yaml"), yaml);
        } catch (IllegalArgumentException e) {
            try {
                String error = mapper.writeValueAsString(Map.of("error", true, "message", e.getMessage()));
                return HttpResponse.of(HttpStatus.BAD_REQUEST, MediaType.JSON, error);
            } catch (Exception ex) {
                return HttpResponse.of(HttpStatus.BAD_REQUEST, MediaType.PLAIN_TEXT_UTF_8, e.getMessage());
            }
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

    public String exportYaml(Set<String> selectedServices) throws Exception {
        return exportYaml(selectedServices, config.getProjectId());
    }

    public String exportYaml(Set<String> selectedServices, String requestedProject) throws Exception {
        String projectId = (requestedProject != null && !requestedProject.isBlank())
                ? requestedProject : config.getProjectId();
        if (!projectService.projectExists(projectId)) {
            throw new IllegalArgumentException("Project not found: " + projectId);
        }
        return yamlMapper.writerWithDefaultPrettyPrinter()
                .writeValueAsString(exportSeedData(selectedServices, projectId));
    }

    public Map<String, Object> exportSeedData(Set<String> selectedServices) {
        return exportSeedData(selectedServices, config.getProjectId());
    }

    private Map<String, Object> exportSeedData(Set<String> selectedServices, String projectId) {
        Map<String, Object> seedData = new LinkedHashMap<>();
        seedData.put("version", "1.0");
        seedData.put("project", projectId);
        if (selectedServices != null && !selectedServices.isEmpty()) {
            seedData.put("selected_services", new ArrayList<>(selectedServices));
        }

        Set<String> requestedServices = selectedServices == null ? Set.of() : selectedServices;
        Map<String, Object> services = new LinkedHashMap<>();

        exportIfSelected(requestedServices, services, "gcs", () -> exportGcs(projectId));
        exportIfSelected(requestedServices, services, "pubsub", () -> exportPubSub(projectId));
        exportIfSelected(requestedServices, services, "bigquery", () -> exportBigQuery(projectId));
        exportIfSelected(requestedServices, services, "secretmanager", () -> exportSecretManager(projectId));
        exportIfSelected(requestedServices, services, "spanner", () -> exportSpanner(projectId));
        exportIfSelected(requestedServices, services, "memorystore", this::exportMemorystore);
        exportIfSelected(requestedServices, services, "cloudtasks", () -> exportCloudTasks(projectId));

        seedData.put("services", services);
        return seedData;
    }

    private void exportIfSelected(Set<String> selectedServices, Map<String, Object> services,
                                  String serviceId, ExportOperation operation) {
        if (!shouldExport(selectedServices, serviceId)) {
            return;
        }
        try {
            Map<String, Object> data = operation.export();
            if (data != null && !data.isEmpty()) {
                services.put(serviceId, data);
            }
        } catch (Exception e) {
            logger.warn("Failed to export {}: {}", serviceId, e.getMessage());
        }
    }

    @FunctionalInterface
    private interface ExportOperation {
        Map<String, Object> export() throws Exception;
    }

    private static Set<String> parseSelectedServices(String raw) {
        if (raw == null || raw.isBlank()) {
            return Set.of();
        }
        return new LinkedHashSet<>(Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(service -> !service.isEmpty())
                .toList());
    }

    private static boolean shouldExport(Set<String> selectedServices, String serviceId) {
        return selectedServices.isEmpty() || selectedServices.contains(serviceId);
    }

    // ========== GCS ==========

    @SuppressWarnings("unchecked")
    private Map<String, Object> exportGcs(String projectId) throws Exception {
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

            // Migration snapshots include exact object bytes so reset/restore is lossless.
            try {
                String objUrl = gcsBase + "/storage/v1/b/" + encode(String.valueOf(item.get("name"))) + "/o";
                String objResponse = proxyGet(objUrl);
                Map<String, Object> objData = mapper.readValue(objResponse, Map.class);
                List<Map<String, Object>> objItems = (List<Map<String, Object>>) objData.get("items");
                if (objItems != null && !objItems.isEmpty()) {
                    List<Map<String, Object>> objects = new ArrayList<>();
                    for (Map<String, Object> obj : objItems) {
                        Map<String, Object> object = new LinkedHashMap<>();
                        String objectName = String.valueOf(obj.get("name"));
                        object.put("key", objectName);
                        object.put("contentBase64", Base64.getEncoder().encodeToString(proxyGetBytes(
                                gcsBase + "/download/storage/v1/b/" + encode(String.valueOf(item.get("name")))
                                        + "/o/" + encode(objectName) + "?alt=media")));
                        if (obj.get("contentType") != null) object.put("contentType", obj.get("contentType"));
                        objects.add(object);
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
    private Map<String, Object> exportPubSub(String projectId) throws Exception {
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
    private Map<String, Object> exportBigQuery(String projectId) throws Exception {
        Map<String, Object> result = new LinkedHashMap<>();
        Map<String, Object> datasetResponse = mapper.readValue(proxyGet(bigqueryBase
                + "/bigquery/v2/projects/" + encode(projectId) + "/datasets"), Map.class);
        List<Map<String, Object>> rawDatasets =
                (List<Map<String, Object>>) datasetResponse.getOrDefault("datasets", List.of());
        if (rawDatasets.isEmpty()) return Map.of();

        List<Map<String, Object>> datasets = new ArrayList<>();
        List<Map<String, Object>> tables = new ArrayList<>();
        for (Map<String, Object> dataset : rawDatasets) {
            Map<String, Object> reference = (Map<String, Object>) dataset.get("datasetReference");
            String datasetId = reference == null ? null : String.valueOf(reference.get("datasetId"));
            if (datasetId == null || datasetId.isBlank()) continue;
            datasets.add(Map.of("name", datasetId));
            try {
                Map<String, Object> tableResponse = mapper.readValue(proxyGet(bigqueryBase
                        + "/bigquery/v2/projects/" + encode(projectId) + "/datasets/" + encode(datasetId)
                        + "/tables"), Map.class);
                List<Map<String, Object>> rawTables =
                        (List<Map<String, Object>>) tableResponse.getOrDefault("tables", List.of());
                for (Map<String, Object> rawTable : rawTables) {
                    Map<String, Object> tableReference =
                            (Map<String, Object>) rawTable.get("tableReference");
                    String tableId = tableReference == null ? null : String.valueOf(tableReference.get("tableId"));
                    if (tableId == null || tableId.isBlank()) continue;
                    Map<String, Object> table = new LinkedHashMap<>();
                    table.put("dataset", datasetId);
                    table.put("name", tableId);
                    Map<String, Object> detail = mapper.readValue(proxyGet(bigqueryBase
                            + "/bigquery/v2/projects/" + encode(projectId) + "/datasets/" + encode(datasetId)
                            + "/tables/" + encode(tableId)), Map.class);
                    Map<String, Object> schema = (Map<String, Object>) detail.get("schema");
                    if (schema != null) table.put("schema", schema);
                    Map<String, Object> data = mapper.readValue(proxyGet(bigqueryBase
                            + "/bigquery/v2/projects/" + encode(projectId) + "/datasets/" + encode(datasetId)
                            + "/tables/" + encode(tableId) + "/data?maxResults=100000"), Map.class);
                    List<Map<String, Object>> rows =
                            (List<Map<String, Object>>) data.getOrDefault("rows", List.of());
                    if (!rows.isEmpty()) table.put("rows", decodeBigQueryRows(rows, schema));
                    tables.add(table);
                }
            } catch (Exception e) {
                logger.debug("Failed to snapshot BigQuery dataset {}: {}", datasetId, e.getMessage());
            }
        }
        if (!datasets.isEmpty()) result.put("datasets", datasets);
        if (!tables.isEmpty()) result.put("tables", tables);
        return result;
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> decodeBigQueryRows(List<Map<String, Object>> rows,
                                                        Map<String, Object> schema) {
        List<Map<String, Object>> fields = schema == null
                ? List.of() : (List<Map<String, Object>>) schema.getOrDefault("fields", List.of());
        List<Map<String, Object>> decoded = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            if (!row.containsKey("f") || fields.isEmpty()) {
                decoded.add(row);
            } else {
                decoded.add(decodeBigQueryRecord((List<Map<String, Object>>) row.get("f"), fields));
            }
        }
        return List.copyOf(decoded);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> decodeBigQueryRecord(List<Map<String, Object>> cells,
                                                             List<Map<String, Object>> fields) {
        Map<String, Object> decoded = new LinkedHashMap<>();
        for (int index = 0; index < Math.min(cells.size(), fields.size()); index++) {
            Map<String, Object> field = fields.get(index);
            Object value = cells.get(index).get("v");
            String mode = String.valueOf(field.getOrDefault("mode", "NULLABLE"));
            if ("REPEATED".equals(mode) && value instanceof List<?> values) {
                value = values.stream().map(item -> item instanceof Map<?, ?> map ? map.get("v") : item).toList();
            } else if ("RECORD".equals(String.valueOf(field.get("type"))) && value instanceof Map<?, ?> record) {
                value = decodeBigQueryRecord((List<Map<String, Object>>) record.get("f"),
                        (List<Map<String, Object>>) field.getOrDefault("fields", List.of()));
            }
            decoded.put(String.valueOf(field.get("name")), value);
        }
        return java.util.Collections.unmodifiableMap(decoded);
    }

    // ========== Secret Manager ==========

    private Map<String, Object> exportSecretManager(String projectId) throws Exception {
        if (!config.isPersistenceEnabled()) {
            return Map.of();
        }

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
    private Map<String, Object> exportSpanner(String projectId) throws Exception {

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

                        // Reference DDL from the persisted Spanner data dir instead of inlining.
                        // DDL can be large with generated columns, indexes, etc.
                        // Include table count for quick reference.
                        try {
                            String ddlUrl = spannerBase + "/v1/projects/" + projectId
                                    + "/instances/" + instName + "/databases/" + dbName + "/ddl";
                            String ddlResponse = proxyGet(ddlUrl);
                            Map<String, Object> ddlData = mapper.readValue(ddlResponse, Map.class);
                            List<String> statements = (List<String>) ddlData.get("statements");
                            if (statements != null && !statements.isEmpty()) {
                                // Count tables only (not indexes)
                                long tableCount = statements.stream()
                                        .filter(s -> s.trim().toUpperCase().startsWith("CREATE TABLE"))
                                        .count();
                                database.put("tableCount", tableCount);
                                database.put("ddlStatements", statements.size());
                                database.put("ddlSource", "/var/lib/localcloud/spanner-data");
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
        Map<String, Object> result = new LinkedHashMap<>();
        List<Map<String, Object>> databases = new ArrayList<>();

        int redisPort = config.getServiceRegistry().getService("memorystore") != null
                ? config.getServiceRegistry().getService("memorystore").port() : 24089;

        try (Jedis jedis = new Jedis("localhost", redisPort)) {
            // Export all databases that have keys
            int dbCount = 16;
            try {
                Map<String, String> dbConfig = jedis.configGet("databases");
                String val = dbConfig.get("databases");
                if (val != null) dbCount = Integer.parseInt(val);
            } catch (Exception ignored) {}

            for (int db = 0; db < dbCount; db++) {
                jedis.select(db);
                if (jedis.dbSize() == 0) continue;

                Map<String, Object> dbEntry = new LinkedHashMap<>();
                dbEntry.put("database", db);
                List<Map<String, Object>> keys = new ArrayList<>();

                ScanParams scanParams = new ScanParams().count(100);
                String cursor = ScanParams.SCAN_POINTER_START;
                do {
                    ScanResult<String> scanResult = jedis.scan(cursor, scanParams);
                    for (String keyName : scanResult.getResult()) {
                        Map<String, Object> key = new LinkedHashMap<>();
                        key.put("key", keyName);
                        key.put("type", jedis.type(keyName));
                        keys.add(key);
                    }
                    cursor = scanResult.getCursor();
                } while (!"0".equals(cursor));

                keys.sort((a, b) -> ((String) a.get("key")).compareTo((String) b.get("key")));
                dbEntry.put("keys", keys);
                databases.add(dbEntry);
            }
        } catch (Exception e) {
            logger.warn("Failed to scan Memorystore keys: {}", e.getMessage());
            return Map.of();
        }

        if (!databases.isEmpty()) {
            result.put("databases", databases);
            result.put("_note", "Values omitted — data persists in mounted volume at /var/lib/localcloud");
        }

        return result;
    }

    // ========== Cloud Tasks ==========

    private Map<String, Object> exportCloudTasks(String projectId) throws Exception {
        if (!config.isPersistenceEnabled()) {
            return Map.of();
        }

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

    /**
     * Content-addressed, deterministic state used by migration comparisons.
     * Unlike seed export, this includes GCS object bytes and BigQuery rows.
     */
    @SuppressWarnings("unchecked")
    public Map<String, String> captureMigrationState() throws Exception {
        Map<String, String> manifest = new TreeMap<>();
        if (config.isServiceEnabled("gcs")) {
        Map<String, Object> bucketsResponse = mapper.readValue(
                proxyGet(gcsBase + "/storage/v1/b?project=" + encode(config.getProjectId())), Map.class);
        List<Map<String, Object>> buckets = (List<Map<String, Object>>) bucketsResponse.getOrDefault("items", List.of());
        buckets.stream().sorted(Comparator.comparing(item -> String.valueOf(item.get("name")))).forEach(bucket -> {
            String bucketName = String.valueOf(bucket.get("name"));
            try {
                Map<String, Object> objectsResponse = mapper.readValue(
                        proxyGet(gcsBase + "/storage/v1/b/" + encode(bucketName) + "/o"), Map.class);
                List<Map<String, Object>> objects = (List<Map<String, Object>>) objectsResponse.getOrDefault("items", List.of());
                for (Map<String, Object> object : objects.stream()
                        .sorted(Comparator.comparing(item -> String.valueOf(item.get("name")))).toList()) {
                    String objectName = String.valueOf(object.get("name"));
                    byte[] content = proxyGetBytes(gcsBase + "/download/storage/v1/b/" + encode(bucketName)
                            + "/o/" + encode(objectName) + "?alt=media");
                    manifest.put("gcs://" + bucketName + "/" + objectName, sha256(content));
                }
            } catch (Exception e) {
                throw new IllegalStateException("Unable to capture GCS bucket " + bucketName, e);
            }
        });
        }

        if (config.isServiceEnabled("bigquery")) {
        Map<String, Object> datasetsResponse = mapper.readValue(proxyGet(bigqueryBase
                + "/bigquery/v2/projects/" + encode(config.getProjectId()) + "/datasets"), Map.class);
        List<Map<String, Object>> datasets = (List<Map<String, Object>>) datasetsResponse.getOrDefault("datasets", List.of());
        for (Map<String, Object> dataset : datasets) {
            Map<String, Object> reference = (Map<String, Object>) dataset.get("datasetReference");
            if (reference == null) continue;
            String datasetId = String.valueOf(reference.get("datasetId"));
            Map<String, Object> tablesResponse = mapper.readValue(proxyGet(bigqueryBase + "/bigquery/v2/projects/"
                    + encode(config.getProjectId()) + "/datasets/" + encode(datasetId) + "/tables"), Map.class);
            List<Map<String, Object>> tables = (List<Map<String, Object>>) tablesResponse.getOrDefault("tables", List.of());
            for (Map<String, Object> table : tables) {
                Map<String, Object> tableReference = (Map<String, Object>) table.get("tableReference");
                if (tableReference == null) continue;
                String tableId = String.valueOf(tableReference.get("tableId"));
                Map<String, Object> detail = mapper.readValue(proxyGet(bigqueryBase + "/bigquery/v2/projects/"
                        + encode(config.getProjectId()) + "/datasets/" + encode(datasetId) + "/tables/" + encode(tableId)), Map.class);
                Map<String, Object> rows = mapper.readValue(proxyGet(bigqueryBase + "/bigquery/v2/projects/"
                        + encode(config.getProjectId()) + "/datasets/" + encode(datasetId) + "/tables/" + encode(tableId)
                        + "/data?maxResults=100000"), Map.class);
                List<String> normalizedRows = ((List<Object>) rows.getOrDefault("rows", List.of())).stream()
                        .map(value -> {
                            try { return mapper.writeValueAsString(value); }
                            catch (Exception e) { throw new IllegalStateException(e); }
                        }).sorted().toList();
                Map<String, Object> normalized = new TreeMap<>();
                normalized.put("schema", detail.getOrDefault("schema", Map.of()));
                normalized.put("rows", normalizedRows);
                manifest.put("bigquery://" + datasetId + "." + tableId,
                        sha256(mapper.writeValueAsBytes(normalized)));
            }
        }
        }
        return Map.copyOf(manifest);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String sha256(byte[] value) {
        try {
            return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private byte[] proxyGetBytes(String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder().uri(URI.create(url))
                .timeout(Duration.ofSeconds(10)).GET().build();
        java.net.http.HttpResponse<byte[]> response = httpClient.send(request, BodyHandlers.ofByteArray());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("GET " + url + " returned " + response.statusCode());
        }
        return response.body();
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
