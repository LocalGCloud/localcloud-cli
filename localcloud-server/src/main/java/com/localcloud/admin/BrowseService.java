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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.annotation.Get;
import com.linecorp.armeria.server.annotation.Param;
import com.localcloud.config.LocalCloudConfig;
import com.localcloud.config.ServiceRegistry;
import com.localcloud.config.ServiceRegistry.ServiceDefinition;
import com.localcloud.persistence.PostgresDataSource;

import redis.clients.jedis.Jedis;
import redis.clients.jedis.params.ScanParams;
import redis.clients.jedis.resps.ScanResult;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Browse service for the LocalCloud dashboard. Proxies read-only data
 * requests to external emulators (GCS, Pub/Sub, BigQuery) and queries
 * the local PostgreSQL database for in-process facade data (Secret Manager,
 * Cloud Tasks, Logging, Monitoring).
 * <p>
 * Registered at the {@code /_localcloud/browse} path prefix.
 */
public class BrowseService {

    private static final Logger logger = LoggerFactory.getLogger(BrowseService.class);

    private final LocalCloudConfig config;
    private final PostgresDataSource dataSource;
    private final ServiceRegistry registry;
    private final UsageMetricsRepository usageMetrics;
    private final HttpClient httpClient;
    private final ObjectMapper mapper;

    // Base URLs computed from registry
    private final String gcsBase;
    private final String pubsubBase;
    private final String bigqueryBase;
    private final String spannerBase;
    private final int bigtablePort;
    private final int firestorePort;

    public BrowseService(LocalCloudConfig config, PostgresDataSource dataSource,
                         ServiceRegistry registry, UsageMetricsRepository usageMetrics) {
        this(config, dataSource, registry, usageMetrics, null);
    }

    BrowseService(LocalCloudConfig config, PostgresDataSource dataSource,
                  ServiceRegistry registry, UsageMetricsRepository usageMetrics,
                  String bigqueryBaseOverride) {
        this.config = config;
        this.dataSource = dataSource;
        this.registry = registry;
        this.usageMetrics = usageMetrics;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        this.mapper = new ObjectMapper();

        // Compute base URLs from registry definitions
        this.gcsBase = baseUrl(registry.getService("gcs"));
        this.pubsubBase = baseUrl(registry.getService("pubsub"));
        this.bigqueryBase = bigqueryBaseOverride != null
                ? bigqueryBaseOverride
                : baseUrl(registry.getService("bigquery"));

        ServiceDefinition spannerDef = registry.getService("spanner");
        int spannerRestPort = spannerDef != null && spannerDef.additionalPorts().containsKey("rest")
                ? spannerDef.additionalPorts().get("rest") : 9020;
        this.spannerBase = "http://localhost:" + spannerRestPort;

        ServiceDefinition bigtableDef = registry.getService("bigtable");
        this.bigtablePort = bigtableDef != null ? bigtableDef.port() : 8087;

        ServiceDefinition firestoreDef = registry.getService("firestore");
        this.firestorePort = firestoreDef != null ? firestoreDef.port() : 8086;
    }

    private static String baseUrl(ServiceDefinition def) {
        if (def == null) return "http://localhost:0";
        // Always use http:// for internal service-to-service calls
        return "http://localhost:" + def.port();
    }

    @Get("/{service}")
    public HttpResponse browse(ServiceRequestContext ctx, @Param("service") String service) {
        return browseService(service, null, null, resolveProject(ctx));
    }

    @Get("/{service}/{resourceType}")
    public HttpResponse browseResource(ServiceRequestContext ctx,
                                       @Param("service") String service,
                                       @Param("resourceType") String resourceType) {
        return browseService(service, resourceType, null, resolveProject(ctx));
    }

    @Get("/{service}/{resourceType}/{resourceId}")
    public HttpResponse browseResourceById(ServiceRequestContext ctx,
                                           @Param("service") String service,
                                           @Param("resourceType") String resourceType,
                                           @Param("resourceId") String resourceId) {
        return browseService(service, resourceType, resourceId, resolveProject(ctx));
    }

    @Get("/{service}/{a}/{b}/{c}")
    public HttpResponse browse4(ServiceRequestContext ctx,
                                @Param("service") String service,
                                @Param("a") String a,
                                @Param("b") String b,
                                @Param("c") String c) {
        return browseService4(service, a, b, c, resolveProject(ctx));
    }

    @Get("/{service}/{a}/{b}/{c}/{d}")
    public HttpResponse browse5(ServiceRequestContext ctx,
                                @Param("service") String service,
                                @Param("a") String a,
                                @Param("b") String b,
                                @Param("c") String c,
                                @Param("d") String d) {
        return browseService5(service, a, b, c, d, resolveProject(ctx));
    }

    @Get("/{service}/{a}/{b}/{c}/{d}/{e}")
    public HttpResponse browse6(ServiceRequestContext ctx,
                                @Param("service") String service,
                                @Param("a") String a,
                                @Param("b") String b,
                                @Param("c") String c,
                                @Param("d") String d,
                                @Param("e") String e) {
        return browseService6(service, a, b, c, d, e, resolveProject(ctx));
    }

    /**
     * Resolve the project ID from the {@code ?project=} query parameter,
     * falling back to the configured default project.
     */
    private String resolveProject(ServiceRequestContext ctx) {
        String project = ctx.queryParams().get("project");
        return (project != null && !project.isBlank()) ? project : config.getProjectId();
    }

    private HttpResponse browseService6(String service, String a, String b, String c, String d, String e, String projectId) {
        try {
            usageMetrics.incrementCount(projectId, service, 1);
            String json = switch (service) {
                case "spanner" -> browseSpannerTableData(a, b, c, d, e, projectId);
                case "bigquery" -> browseBigQueryTableData(a, b, c, d, e, projectId);
                default -> mapper.writeValueAsString(Map.of(
                        "error", true,
                        "message", "Unsupported 6-segment browse for service: " + service));
            };
            return HttpResponse.of(HttpStatus.OK, MediaType.JSON, json);
        } catch (Exception e1) {
            logger.warn("Browse6 error for {}: {}", service, e1.getMessage());
            try {
                return HttpResponse.of(HttpStatus.INTERNAL_SERVER_ERROR, MediaType.JSON,
                        mapper.writeValueAsString(Map.of("error", true, "message", e1.getMessage())));
            } catch (Exception ex) {
                return HttpResponse.of(HttpStatus.INTERNAL_SERVER_ERROR,
                        MediaType.PLAIN_TEXT_UTF_8, "Browse error");
            }
        }
    }

    private HttpResponse browseService4(String service, String a, String b, String c, String projectId) {
        try {
            usageMetrics.incrementCount(projectId, service, 1);
            String json = switch (service) {
                case "spanner" -> browseSpannerDatabase(a, b, c, projectId);
                case "bigquery" -> browseBigQueryTables(a, b, c, projectId);
                case "bigtable" -> browseBigtable(a, b + "/" + c, projectId);
                case "memorystore" -> {
                    // memorystore/db/{index}/keys?prefix=...
                    if ("db".equals(a) && "keys".equals(c)) {
                        int redisPort = config.getServiceRegistry().getService("memorystore") != null
                                ? config.getServiceRegistry().getService("memorystore").port() : 6379;
                        yield browseMemorystoreKeys(redisPort, Integer.parseInt(b), null);
                    }
                    yield mapper.writeValueAsString(Map.of("error", true,
                            "message", "Invalid Memorystore browse path"));
                }
                default -> mapper.writeValueAsString(Map.of(
                        "error", true,
                        "message", "Unsupported 4-segment browse for service: " + service));
            };
            return HttpResponse.of(HttpStatus.OK, MediaType.JSON, json);
        } catch (Exception e) {
            logger.warn("Browse4 error for {}: {}", service, e.getMessage());
            try {
                return HttpResponse.of(HttpStatus.INTERNAL_SERVER_ERROR, MediaType.JSON,
                        mapper.writeValueAsString(Map.of("error", true, "message", e.getMessage())));
            } catch (Exception ex) {
                return HttpResponse.of(HttpStatus.INTERNAL_SERVER_ERROR,
                        MediaType.PLAIN_TEXT_UTF_8, "Browse error");
            }
        }
    }

    private HttpResponse browseService5(String service, String a, String b, String c, String d, String projectId) {
        try {
            usageMetrics.incrementCount(projectId, service, 1);
            String json = switch (service) {
                case "spanner" -> browseSpannerStats(a, b, c, d, projectId);
                default -> mapper.writeValueAsString(Map.of(
                        "error", true,
                        "message", "Unsupported 5-segment browse for service: " + service));
            };
            return HttpResponse.of(HttpStatus.OK, MediaType.JSON, json);
        } catch (Exception e) {
            logger.warn("Browse5 error for {}: {}", service, e.getMessage());
            try {
                return HttpResponse.of(HttpStatus.INTERNAL_SERVER_ERROR, MediaType.JSON,
                        mapper.writeValueAsString(Map.of("error", true, "message", e.getMessage())));
            } catch (Exception ex) {
                return HttpResponse.of(HttpStatus.INTERNAL_SERVER_ERROR,
                        MediaType.PLAIN_TEXT_UTF_8, "Browse error");
            }
        }
    }

    private HttpResponse browseService(String service, String resourceType, String resourceId, String projectId) {
        try {
            usageMetrics.incrementCount(projectId, service, 1);
            String json = switch (service) {
                case "gcs" -> browseGcs(resourceType, resourceId, projectId);
                case "pubsub" -> browsePubSub(resourceType, resourceId, projectId);
                case "bigquery" -> browseBigQuery(resourceType, resourceId, projectId);
                case "secretmanager" -> browseSecretManager(resourceType, resourceId, projectId);
                case "cloudtasks" -> browseCloudTasks(resourceType, resourceId, projectId);
                case "logging" -> browseLogging(resourceType, resourceId, projectId);
                case "monitoring" -> browseMonitoring(resourceType, resourceId, projectId);
                case "memorystore" -> browseMemorystore(resourceType, resourceId, projectId);
                case "spanner" -> browseSpanner(resourceType, resourceId, projectId);
                case "firestore" -> browseFirestore(resourceType, resourceId, projectId);
                case "bigtable" -> browseBigtable(resourceType, resourceId, projectId);
                case "workflows" -> browseWorkflows(resourceType, resourceId, projectId);
                default -> mapper.writeValueAsString(Map.of(
                        "error", true,
                        "message", "Unknown service: " + service));
            };
            return HttpResponse.of(HttpStatus.OK, MediaType.JSON, json);
        } catch (Exception e) {
            logger.warn("Browse error for {}: {}", service, e.getMessage());
            try {
                return HttpResponse.of(HttpStatus.INTERNAL_SERVER_ERROR, MediaType.JSON,
                        mapper.writeValueAsString(Map.of("error", true, "message", e.getMessage())));
            } catch (Exception ex) {
                return HttpResponse.of(HttpStatus.INTERNAL_SERVER_ERROR,
                        MediaType.PLAIN_TEXT_UTF_8, "Browse error");
            }
        }
    }

    // ========== GCS ==========

    @SuppressWarnings("unchecked")
    private String browseGcs(String resourceType, String resourceId, String projectId) throws Exception {
        if (resourceType == null || "buckets".equals(resourceType) && resourceId == null) {
            // List buckets — filter by project ownership from gcs_bucket_projects table
            // (fake-gcs-server returns all buckets regardless of ?project= param)
            String url = gcsBase + "/storage/v1/b?project=" + projectId;
            String raw = proxyGet(url);
            Map<String, Object> resp = mapper.readValue(raw, Map.class);
            List<Map<String, Object>> items = (List<Map<String, Object>>) resp.get("items");

            // Get ALL tracked bucket names to find untracked ones
            java.util.Set<String> allTrackedBuckets = getAllTrackedBuckets();

            // Auto-register any untracked buckets to the default project
            // (buckets created before ownership tracking was added)
            if (items != null) {
                String defaultProject = config.getProjectId();
                for (Map<String, Object> item : items) {
                    String name = (String) item.get("name");
                    if (!allTrackedBuckets.contains(name)) {
                        registerBucketOwnership(name, defaultProject);
                        allTrackedBuckets.add(name);
                    }
                }
            }

            // Get project-owned bucket names for filtering
            java.util.Set<String> ownedBuckets = getProjectBuckets(projectId);

            List<Map<String, Object>> buckets = new ArrayList<>();
            if (items != null) {
                for (Map<String, Object> item : items) {
                    String name = (String) item.get("name");
                    if (!ownedBuckets.contains(name)) {
                        continue;
                    }
                    Map<String, Object> bucket = new LinkedHashMap<>();
                    bucket.put("name", name);
                    bucket.put("timeCreated", item.get("timeCreated"));
                    bucket.put("location", item.get("location"));
                    buckets.add(bucket);
                }
            }
            return mapper.writeValueAsString(Map.of("buckets", buckets));
        }
        // List objects in bucket — resourceType is either "buckets" with resourceId as bucket name,
        // or resourceType is the bucket name directly (from console UI: browse/gcs/{bucketName})
        String bucketName = "buckets".equals(resourceType) ? resourceId : resourceType;
        if (bucketName != null) {
            String url = gcsBase + "/storage/v1/b/" + bucketName + "/o";
            String raw = proxyGet(url);
            Map<String, Object> resp = mapper.readValue(raw, Map.class);
            List<Map<String, Object>> items = (List<Map<String, Object>>) resp.get("items");
            List<Map<String, Object>> objects = new ArrayList<>();
            if (items != null) {
                for (Map<String, Object> item : items) {
                    Map<String, Object> obj = new LinkedHashMap<>();
                    obj.put("name", item.get("name"));
                    obj.put("size", item.get("size"));
                    obj.put("contentType", item.get("contentType"));
                    obj.put("updated", item.get("updated"));
                    objects.add(obj);
                }
            }
            return mapper.writeValueAsString(Map.of("objects", objects));
        }
        return mapper.writeValueAsString(Map.of("error", true, "message", "Invalid GCS browse path"));
    }

    // ========== Pub/Sub ==========

    private String browsePubSub(String resourceType, String resourceId, String projectId) throws Exception {
        if (resourceType == null || "topics".equals(resourceType) && resourceId == null) {
            // List topics
            String url = pubsubBase + "/v1/projects/" + projectId + "/topics";
            return proxyGet(url);
        }
        if ("subscriptions".equals(resourceType) && resourceId == null) {
            // List subscriptions
            String url = pubsubBase + "/v1/projects/" + projectId + "/subscriptions";
            return proxyGet(url);
        }
        if ("topics".equals(resourceType) && resourceId != null) {
            // Get topic
            String url = pubsubBase + "/v1/projects/" + projectId + "/topics/" + resourceId;
            return proxyGet(url);
        }
        if ("messages".equals(resourceType) && resourceId != null) {
            // Pull messages from subscription without acknowledging
            return pullPubSubMessages(resourceId, projectId);
        }
        if ("topics".equals(resourceType) && resourceId != null && resourceId.endsWith("/messages")) {
            // Browse messages in a topic by creating a temporary subscription, pulling, then deleting
            String topicName = resourceId.replace("/messages", "");
            return browseTopicMessages(topicName, projectId);
        }
        return mapper.writeValueAsString(Map.of("error", true, "message", "Invalid Pub/Sub browse path"));
    }

    /**
     * Pull messages from a subscription without acknowledging them.
     */
    private String pullPubSubMessages(String subscriptionId, String projectId) throws Exception {
        String pullUrl = pubsubBase + "/v1/projects/" + projectId + "/subscriptions/" + subscriptionId + ":pull";
        String pullBody = "{\"maxMessages\": 100, \"returnImmediately\": true}";
        try {
            String response = proxyPost(pullUrl, pullBody);
            @SuppressWarnings("unchecked")
            Map<String, Object> pullResp = mapper.readValue(response, Map.class);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> receivedMessages = (List<Map<String, Object>>) pullResp.get("receivedMessages");

            List<Map<String, Object>> messages = new ArrayList<>();
            if (receivedMessages != null) {
                for (Map<String, Object> rm : receivedMessages) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> msg = (Map<String, Object>) rm.get("message");
                    if (msg != null) {
                        Map<String, Object> decoded = new LinkedHashMap<>();
                        decoded.put("messageId", msg.get("messageId"));
                        decoded.put("publishTime", msg.get("publishTime"));
                        // Decode base64 data
                        String data = (String) msg.get("data");
                        if (data != null) {
                            try {
                                decoded.put("data", new String(java.util.Base64.getDecoder().decode(data), java.nio.charset.StandardCharsets.UTF_8));
                            } catch (Exception e) {
                                decoded.put("data", data);
                            }
                        }
                        decoded.put("attributes", msg.get("attributes"));
                        messages.add(decoded);
                    }
                }
            }
            return mapper.writeValueAsString(Map.of("messages", messages, "subscription", subscriptionId));
        } catch (Exception e) {
            logger.warn("Failed to pull Pub/Sub messages: {}", e.getMessage());
            return mapper.writeValueAsString(Map.of("messages", List.of(), "subscription", subscriptionId));
        }
    }

    /**
     * Browse messages in a topic by creating a temporary subscription, pulling messages, then cleaning up.
     * This allows browsing messages without requiring a pre-existing subscription.
     */
    private String browseTopicMessages(String topicName, String projectId) throws Exception {
        String tempSubId = "_temp_browse_" + System.currentTimeMillis();
        String topicPath = "projects/" + projectId + "/topics/" + topicName;
        String subPath = pubsubBase + "/v1/projects/" + projectId + "/subscriptions/" + tempSubId;

        try {
            // 1. Create temporary subscription
            String subBody = mapper.writeValueAsString(Map.of(
                "topic", topicPath,
                "ackDeadlineSeconds", 10
            ));
            try {
                proxyPut(subPath, subBody);
            } catch (Exception e) {
                // Subscription may already exist — continue anyway
                logger.debug("Temporary subscription may already exist: {}", e.getMessage());
            }

            // 2. Pull messages
            return pullPubSubMessages(tempSubId, projectId);
        } finally {
            // 3. Clean up temporary subscription
            try {
                proxyDelete(subPath);
            } catch (Exception e) {
                logger.debug("Failed to clean up temporary subscription: {}", e.getMessage());
            }
        }
    }

    // ========== BigQuery ==========

    private String browseBigQuery(String resourceType, String resourceId, String projectId) throws Exception {
        if (resourceType == null || "datasets".equals(resourceType) && resourceId == null) {
            String url = bigqueryBase + "/bigquery/v2/projects/" + projectId + "/datasets";
            return proxyGet(url);
        }
        if ("datasets".equals(resourceType) && resourceId != null) {
            // List tables in dataset
            String url = bigqueryBase + "/bigquery/v2/projects/" + projectId
                    + "/datasets/" + resourceId + "/tables";
            return proxyGet(url);
        }
        if ("information_schema".equals(resourceType)) {
            return browseBigQueryInformationSchema(resourceId, projectId);
        }
        return mapper.writeValueAsString(Map.of("error", true, "message", "Invalid BigQuery browse path"));
    }

    /**
     * Browse BigQuery INFORMATION_SCHEMA views by asking the BigQuery emulator
     * to execute DuckDB-backed INFORMATION_SCHEMA SQL. LocalCloud keeps only
     * control-plane data in PostgreSQL; BigQuery metadata stays in the emulator.
     */
    String browseBigQueryInformationSchema(String viewType, String projectId) throws Exception {
        String view = viewType != null ? viewType : "tables";
        String sqlView = bigQueryInformationSchemaSqlView(view);
        if (sqlView == null) {
            return mapper.writeValueAsString(Map.of(
                    "error", true,
                    "message", "Unknown INFORMATION_SCHEMA view: " + view));
        }

        List<String> datasets = listBigQueryDatasetIds(projectId);
        if (datasets.isEmpty()) {
            List<String> columns = fallbackInfoSchemaColumns(sqlView);
            return mapper.writeValueAsString(Map.of(
                    "columns", columns,
                    "rows", List.of(),
                    "rowCount", 0));
        }

        List<String> columns = new ArrayList<>();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (String datasetId : datasets) {
            try {
                Map<String, Object> queryResp = queryBigQueryInformationSchema(projectId, datasetId, sqlView);
                if (columns.isEmpty()) {
                    columns.addAll(extractBigQueryColumns(queryResp));
                }
                rows.addAll(extractBigQueryRows(queryResp, columns));
            } catch (Exception e) {
                logger.warn("BigQuery INFORMATION_SCHEMA query failed for dataset {} (view={}): {}",
                        datasetId, sqlView, e.getMessage());
            }
        }

        if (columns.isEmpty()) {
            columns.addAll(fallbackInfoSchemaColumns(sqlView));
        }

        return mapper.writeValueAsString(Map.of(
            "columns", columns,
            "rows", rows, "rowCount", rows.size()));
    }

    private String bigQueryInformationSchemaSqlView(String view) {
        return switch (view) {
            case "tables" -> "TABLES";
            case "columns" -> "COLUMNS";
            case "schemata" -> "SCHEMATA";
            case "views" -> "VIEWS";
            case "routines" -> "ROUTINES";
            case "partitions" -> "PARTITIONS";
            case "table_storage" -> "TABLE_STORAGE";
            default -> null;
        };
    }

    private List<String> listBigQueryDatasetIds(String projectId) throws Exception {
        String url = bigqueryBase + "/bigquery/v2/projects/" + projectId + "/datasets";
        @SuppressWarnings("unchecked")
        Map<String, Object> resp = mapper.readValue(proxyGet(url), Map.class);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> datasets = (List<Map<String, Object>>) resp.getOrDefault("datasets", List.of());
        List<String> datasetIds = new ArrayList<>();
        for (Map<String, Object> dataset : datasets) {
            @SuppressWarnings("unchecked")
            Map<String, Object> ref = (Map<String, Object>) dataset.get("datasetReference");
            Object id = ref != null ? ref.get("datasetId") : dataset.get("id");
            if (id == null) {
                continue;
            }
            String datasetId = String.valueOf(id);
            int projectSeparator = datasetId.indexOf(':');
            if (projectSeparator >= 0 && projectSeparator + 1 < datasetId.length()) {
                datasetId = datasetId.substring(projectSeparator + 1);
            }
            if (!datasetId.isBlank()) {
                datasetIds.add(datasetId);
            }
        }
        return datasetIds;
    }

    private Map<String, Object> queryBigQueryInformationSchema(
            String projectId, String datasetId, String sqlView) throws Exception {
        String queryUrl = bigqueryBase + "/bigquery/v2/projects/" + projectId + "/queries";
        String queryPayload = mapper.writeValueAsString(Map.of(
                "query", "SELECT * FROM INFORMATION_SCHEMA." + sqlView,
                "useLegacySql", false,
                "defaultDataset", Map.of(
                        "projectId", projectId,
                        "datasetId", datasetId)));
        @SuppressWarnings("unchecked")
        Map<String, Object> queryResp = mapper.readValue(proxyPost(queryUrl, queryPayload), Map.class);
        if (queryResp.containsKey("error")) {
            throw new IllegalStateException("BigQuery INFORMATION_SCHEMA query failed for dataset "
                    + datasetId + ": " + queryResp.get("error"));
        }
        return queryResp;
    }

    private List<String> extractBigQueryColumns(Map<String, Object> queryResp) {
        List<String> columns = new ArrayList<>();
        @SuppressWarnings("unchecked")
        Map<String, Object> schema = (Map<String, Object>) queryResp.get("schema");
        if (schema == null) {
            return columns;
        }
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> fields = (List<Map<String, Object>>) schema.get("fields");
        if (fields == null) {
            return columns;
        }
        for (Map<String, Object> field : fields) {
            Object name = field.get("name");
            if (name != null) {
                columns.add(String.valueOf(name));
            }
        }
        return columns;
    }

    private List<Map<String, Object>> extractBigQueryRows(Map<String, Object> queryResp, List<String> columns) {
        List<Map<String, Object>> rows = new ArrayList<>();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rawRows = (List<Map<String, Object>>) queryResp.get("rows");
        if (rawRows == null) {
            return rows;
        }
        for (Map<String, Object> rawRow : rawRows) {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> cells = (List<Map<String, Object>>) rawRow.get("f");
            Map<String, Object> row = new LinkedHashMap<>();
            if (cells != null) {
                for (int i = 0; i < columns.size() && i < cells.size(); i++) {
                    row.put(columns.get(i), cells.get(i).get("v"));
                }
            }
            rows.add(row);
        }
        return rows;
    }

    private List<String> fallbackInfoSchemaColumns(String sqlView) {
        return switch (sqlView) {
            case "TABLES" -> List.of("table_catalog", "table_schema", "table_name", "table_type",
                    "creation_time", "ddl", "row_count", "size_bytes");
            case "COLUMNS" -> List.of("table_catalog", "table_schema", "table_name", "column_name",
                    "ordinal_position", "data_type", "is_nullable", "is_partitioning_column",
                    "clustering_ordinal_position");
            case "SCHEMATA" -> List.of("catalog_name", "schema_name", "schema_owner",
                    "creation_time", "last_modified_time", "location");
            case "VIEWS" -> List.of("table_catalog", "table_schema", "table_name",
                    "view_definition", "check_option", "use_standard_sql");
            case "ROUTINES" -> List.of("routine_name", "routine_catalog", "routine_schema",
                    "routine_type", "routine_definition", "created", "last_altered");
            case "PARTITIONS" -> List.of("table_catalog", "table_schema", "table_name",
                    "partition_id", "last_modified_time", "total_rows", "total_logical_bytes",
                    "total_billable_bytes");
            case "TABLE_STORAGE" -> List.of("table_catalog", "table_schema", "table_name",
                    "total_rows", "total_logical_bytes", "active_logical_bytes",
                    "total_physical_bytes", "last_modified_time");
            default -> List.of();
        };
    }

    /**
     * Browse BigQuery tables in a dataset (4-segment: browse/bigquery/datasets/{datasetId}).
     */
    private String browseBigQueryTables(String a, String b, String c, String projectId) throws Exception {
        // Expected path: datasets/{datasetId}/{operation} where operation is optional
        if (!"datasets".equals(a)) {
            return mapper.writeValueAsString(Map.of("error", true, "message", "Invalid BigQuery browse path. Expected: datasets/{datasetId}"));
        }
        String datasetId = b;
        // List tables in dataset
        String url = bigqueryBase + "/bigquery/v2/projects/" + projectId + "/datasets/" + datasetId + "/tables";
        return proxyGet(url);
    }

    // ========== Secret Manager (in-process, query PostgreSQL) ==========

    private String browseSecretManager(String resourceType, String resourceId, String projectId) throws Exception {
        if (!config.isPersistenceEnabled()) {
            return mapper.writeValueAsString(Map.of("message", "Persistence disabled"));
        }

        if (resourceType == null || "secrets".equals(resourceType) && resourceId == null) {
            List<Map<String, Object>> secrets = new ArrayList<>();
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement(
                     "SELECT secret_id, labels, created_at FROM secrets WHERE project_id = ?")) {
                ps.setString(1, projectId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        Map<String, Object> s = new LinkedHashMap<>();
                        s.put("name", rs.getString("secret_id"));
                        s.put("labels", rs.getString("labels"));
                        s.put("created_at", rs.getTimestamp("created_at").toString());
                        secrets.add(s);
                    }
                }
            }
            return mapper.writeValueAsString(Map.of("secrets", secrets));
        }
        return mapper.writeValueAsString(Map.of("error", true, "message", "Invalid browse path"));
    }

    // ========== Cloud Tasks (in-process, query PostgreSQL) ==========

    private String browseCloudTasks(String resourceType, String resourceId, String projectId) throws Exception {
        if (!config.isPersistenceEnabled()) {
            return mapper.writeValueAsString(Map.of("message", "Persistence disabled"));
        }

        if (resourceType == null || "queues".equals(resourceType) && resourceId == null) {
            List<Map<String, Object>> queues = new ArrayList<>();
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement(
                     "SELECT queue_id, location_id, state, max_attempts, created_at FROM task_queues WHERE project_id = ?")) {
                ps.setString(1, projectId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        Map<String, Object> q = new LinkedHashMap<>();
                        q.put("name", rs.getString("queue_id"));
                        q.put("location", rs.getString("location_id"));
                        q.put("state", rs.getString("state"));
                        q.put("max_attempts", rs.getInt("max_attempts"));
                        q.put("created_at", rs.getTimestamp("created_at").toString());
                        queues.add(q);
                    }
                }
            }
            return mapper.writeValueAsString(Map.of("queues", queues));
        }
        return mapper.writeValueAsString(Map.of("error", true, "message", "Invalid browse path"));
    }

    // ========== Logging (in-process, query PostgreSQL) ==========

    private String browseLogging(String resourceType, String resourceId, String projectId) throws Exception {
        if (!config.isPersistenceEnabled()) {
            return mapper.writeValueAsString(Map.of("message", "Persistence disabled"));
        }

        if (resourceType == null || "entries".equals(resourceType)) {
            List<Map<String, Object>> entries = new ArrayList<>();
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement(
                     "SELECT id, log_name, severity, text_payload, timestamp FROM log_entries WHERE project_id = ? ORDER BY timestamp DESC LIMIT 100")) {
                ps.setString(1, projectId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        Map<String, Object> e = new LinkedHashMap<>();
                        e.put("id", rs.getString("id"));
                        e.put("log_name", rs.getString("log_name"));
                        e.put("severity", rs.getString("severity"));
                        e.put("text_payload", rs.getString("text_payload"));
                        e.put("timestamp", rs.getLong("timestamp"));
                        entries.add(e);
                    }
                }
            }
            return mapper.writeValueAsString(Map.of("entries", entries));
        }
        return mapper.writeValueAsString(Map.of("error", true, "message", "Invalid browse path"));
    }

    // ========== Monitoring (in-process, query PostgreSQL) ==========

    private String browseMonitoring(String resourceType, String resourceId, String projectId) throws Exception {
        if (!config.isPersistenceEnabled()) {
            return mapper.writeValueAsString(Map.of("message", "Persistence disabled"));
        }

        if (resourceType == null || "timeseries".equals(resourceType)) {
            List<Map<String, Object>> series = new ArrayList<>();
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement(
                     "SELECT id, metric_type, metric_labels, resource_type FROM time_series WHERE project_name = ? LIMIT 100")) {
                ps.setString(1, "projects/" + projectId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        Map<String, Object> ts = new LinkedHashMap<>();
                        ts.put("id", rs.getString("id"));
                        ts.put("metric_type", rs.getString("metric_type"));
                        ts.put("metric_labels", rs.getString("metric_labels"));
                        ts.put("resource_type", rs.getString("resource_type"));
                        series.add(ts);
                    }
                }
            }
            return mapper.writeValueAsString(Map.of("time_series", series));
        }
        return mapper.writeValueAsString(Map.of("error", true, "message", "Invalid browse path"));
    }

    // ========== Memorystore (Redis/Valkey) ==========

    private String browseMemorystore(String resourceType, String resourceId, String projectId) throws Exception {
        int redisPort = config.getServiceRegistry().getService("memorystore") != null
                ? config.getServiceRegistry().getService("memorystore").port() : 6379;

        // Browse path: null -> list databases, "db" + resourceId -> browse keys in that database
        if ("db".equals(resourceType) && resourceId != null) {
            return browseMemorystoreKeys(redisPort, Integer.parseInt(resourceId), null);
        }

        // Default: list all 16 databases with key counts
        return browseMemorystoreDatabases(redisPort);
    }

    private String browseMemorystoreDatabases(int redisPort) throws Exception {
        List<Map<String, Object>> databases = new ArrayList<>();
        try (Jedis jedis = new Jedis("localhost", redisPort)) {
            // Valkey supports 16 databases by default (configurable)
            int dbCount = 16;
            try {
                Map<String, String> dbConfig = jedis.configGet("databases");
                String val = dbConfig.get("databases");
                if (val != null) {
                    dbCount = Integer.parseInt(val);
                }
            } catch (Exception ignored) {}

            for (int i = 0; i < dbCount; i++) {
                jedis.select(i);
                long keyCount = jedis.dbSize();
                Map<String, Object> db = new LinkedHashMap<>();
                db.put("index", i);
                db.put("name", "db" + i);
                db.put("keyCount", keyCount);
                databases.add(db);
            }
        } catch (Exception e) {
            logger.warn("Failed to list Memorystore databases: {}", e.getMessage());
            return mapper.writeValueAsString(Map.of("databases", List.of(),
                    "error", "Cannot connect to Valkey on port " + redisPort + ": " + e.getMessage()));
        }
        return mapper.writeValueAsString(Map.of("databases", databases));
    }

    private String browseMemorystoreKeys(int redisPort, int dbIndex, String prefixFilter) throws Exception {
        List<Map<String, Object>> keys = new ArrayList<>();
        Set<String> namespaces = new LinkedHashSet<>();
        try (Jedis jedis = new Jedis("localhost", redisPort)) {
            jedis.select(dbIndex);
            String matchPattern = (prefixFilter != null && !prefixFilter.isEmpty())
                    ? prefixFilter + "*" : "*";
            ScanParams scanParams = new ScanParams().match(matchPattern).count(200);
            String cursor = "0";
            do {
                ScanResult<String> result = jedis.scan(cursor, scanParams);
                for (String key : result.getResult()) {
                    Map<String, Object> k = new LinkedHashMap<>();
                    k.put("key", key);
                    String type = jedis.type(key);
                    k.put("type", type);

                    Object value = switch (type) {
                        case "string" -> jedis.get(key);
                        case "hash" -> jedis.hgetAll(key);
                        case "list" -> jedis.lrange(key, 0, 99);
                        case "set" -> jedis.smembers(key);
                        case "zset" -> jedis.zrangeWithScores(key, 0, 99);
                        case "stream" -> "stream (use XRANGE to view)";
                        default -> "(unknown type: " + type + ")";
                    };
                    k.put("value", value);

                    long ttl = jedis.ttl(key);
                    k.put("ttl", ttl > 0 ? ttl : null);

                    // Extract namespace prefix (first segment before ':')
                    int colonIdx = key.indexOf(':');
                    if (colonIdx > 0) {
                        namespaces.add(key.substring(0, colonIdx));
                    }

                    keys.add(k);
                    if (keys.size() >= 200) break;
                }
                cursor = result.getCursor();
            } while (!"0".equals(cursor) && keys.size() < 200);
        } catch (Exception e) {
            logger.warn("Failed to browse Memorystore db{}: {}", dbIndex, e.getMessage());
            return mapper.writeValueAsString(Map.of("keys", List.of(),
                    "error", "Cannot connect to Valkey on port " + redisPort + ": " + e.getMessage()));
        }
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("database", dbIndex);
        response.put("keys", keys);
        response.put("total", keys.size());
        response.put("namespaces", new ArrayList<>(namespaces));
        return mapper.writeValueAsString(response);
    }

    // ========== Spanner (proxy to Spanner REST API) ==========

    private String browseSpanner(String resourceType, String resourceId, String projectId) throws Exception {
        if (resourceType == null || "instances".equals(resourceType) && resourceId == null) {
            // List instances
            String url = spannerBase + "/v1/projects/" + projectId + "/instances";
            return proxyGet(url);
        }
        if ("instances".equals(resourceType) && resourceId != null) {
            // List databases in instance
            String url = spannerBase + "/v1/projects/" + projectId + "/instances/" + resourceId + "/databases";
            return proxyGet(url);
        }
        return mapper.writeValueAsString(Map.of("error", true, "message", "Invalid Spanner browse path"));
    }

    /**
     * Browse a specific Spanner database: returns DDL (table schemas).
     * Path: spanner/instances/{instance}/{database}
     * @param a should be "instances"
     * @param b instance name
     * @param c database name
     */
    private String browseSpannerDatabase(String a, String b, String c, String projectId) throws Exception {
        if (!"instances".equals(a)) {
            return mapper.writeValueAsString(Map.of("error", true, "message", "Invalid Spanner browse path"));
        }
        String url = spannerBase + "/v1/projects/" + projectId + "/instances/" + b + "/databases/" + c + "/ddl";
        return proxyGet(url);
    }

    /**
     * Compute database statistics from DDL for the System Insights panel.
     * Path: spanner/instances/{instance}/{database}/stats
     * Returns table count, index count, search/vector index count, and per-table breakdown.
     */
    @SuppressWarnings("unchecked")
    private String browseSpannerStats(String a, String b, String c, String d, String projectId) throws Exception {
        if (!"instances".equals(a) || !"stats".equals(d)) {
            return mapper.writeValueAsString(Map.of("error", true, "message", "Invalid stats path. Expected: /browse/spanner/instances/{instance}/{database}/stats"));
        }
        String instance = b;
        String database = c;
        if (instance == null || instance.isBlank() || database == null || database.isBlank()) {
            return mapper.writeValueAsString(Map.of("error", true, "message", "Instance and database name are required"));
        }

        String url = spannerBase + "/v1/projects/" + projectId + "/instances/" + instance
                + "/databases/" + database + "/ddl";
        String ddlBody = proxyGet(url);
        Map<String, Object> ddlResp = mapper.readValue(ddlBody, Map.class);
        List<String> statements = (List<String>) ddlResp.getOrDefault("statements", List.of());

        int tableCount = 0;
        int indexCount = 0;
        int searchIndexCount = 0;
        int vectorIndexCount = 0;
        List<Map<String, Object>> tableDetails = new ArrayList<>();

        for (String stmt : statements) {
            String trimmed = stmt.trim();
            String upper = trimmed.toUpperCase();
            if (upper.startsWith("CREATE TABLE")) {
                tableCount++;
                Map<String, Object> detail = new LinkedHashMap<>();
                detail.put("type", "TABLE");
                // Extract table name
                java.util.regex.Matcher nameM = java.util.regex.Pattern.compile(
                        "(?i)CREATE\\s+TABLE\\s+(\\S+)").matcher(trimmed);
                if (nameM.find()) detail.put("name", nameM.group(1));
                // Count columns
                int colStart = trimmed.indexOf('(');
                if (colStart >= 0) {
                    int depth = 0, colEnd = -1;
                    for (int i = colStart; i < trimmed.length(); i++) {
                        char ch = trimmed.charAt(i);
                        if (ch == '(') depth++;
                        else if (ch == ')') { depth--; if (depth == 0) { colEnd = i; break; } }
                    }
                    if (colEnd > colStart) {
                        String cols = trimmed.substring(colStart + 1, colEnd);
                        List<String> parts = splitTopLevel(cols);
                        int realCols = 0;
                        for (String col : parts) {
                            if (col.isEmpty()) continue;
                            if (col.toUpperCase().startsWith("INTERLEAVE") ||
                                col.toUpperCase().startsWith("CONSTRAINT") ||
                                col.toUpperCase().startsWith("PRIMARY KEY")) continue;
                            realCols++;
                        }
                        detail.put("columnCount", realCols);
                    }
                }
                detail.put("hasInterleaved", upper.contains("INTERLEAVE IN PARENT"));
                tableDetails.add(detail);
            } else if (upper.startsWith("CREATE INDEX") && !upper.contains("SEARCH") && !upper.contains("VECTOR")) {
                indexCount++;
                java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                        "(?i)CREATE(?:\\s+UNIQUE)?\\s+INDEX\\s+(\\S+)").matcher(trimmed);
                if (m.find()) tableDetails.add(Map.of("type", "INDEX", "name", m.group(1)));
            } else if (upper.startsWith("CREATE SEARCH INDEX")) {
                searchIndexCount++;
                java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                        "(?i)CREATE\\s+SEARCH\\s+INDEX\\s+(\\S+)").matcher(trimmed);
                if (m.find()) tableDetails.add(Map.of("type", "SEARCH_INDEX", "name", m.group(1)));
            } else if (upper.startsWith("CREATE VECTOR INDEX")) {
                vectorIndexCount++;
                java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                        "(?i)CREATE\\s+VECTOR\\s+INDEX\\s+(\\S+)").matcher(trimmed);
                if (m.find()) tableDetails.add(Map.of("type", "VECTOR_INDEX", "name", m.group(1)));
            }
        }

        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("database", database);
        stats.put("instance", instance);
        stats.put("tableCount", tableCount);
        stats.put("indexCount", indexCount);
        stats.put("searchIndexCount", searchIndexCount);
        stats.put("vectorIndexCount", vectorIndexCount);
        stats.put("totalObjects", tableCount + indexCount + searchIndexCount + vectorIndexCount);
        stats.put("details", tableDetails);

        return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(stats);
    }

    // ========== Spanner table data (proxy to Spanner REST API) ==========

    /**
     * Query data from a specific Spanner table.
     * Path: spanner/instances/{instance}/databases/{db}/tables/{table}
     * @param a should be "instances"
     * @param b instance name
     * @param c database name
     * @param d should be "tables"
     * @param e table name
     */
    private String browseSpannerTableData(String a, String b, String c, String d, String e, String projectId) throws Exception {
        if (!"instances".equals(a) || !"tables".equals(d)) {
            return mapper.writeValueAsString(Map.of("error", true, "message", "Invalid Spanner table data browse path"));
        }
        String instance = b;
        String database = c;
        String table = e;

        // 1. Create session
        String sessionUrl = spannerBase + "/v1/projects/" + projectId + "/instances/" + instance
                + "/databases/" + database + "/sessions";
        String sessionBody = proxyPost(sessionUrl, "{}");
        @SuppressWarnings("unchecked")
        Map<String, Object> sessionResp = mapper.readValue(sessionBody, Map.class);
        String sessionName = (String) sessionResp.get("name");

        try {
            // 2. Execute SQL
            String sqlUrl = spannerBase + "/v1/" + sessionName + ":executeSql";
            String sqlPayload = mapper.writeValueAsString(Map.of("sql", "SELECT * FROM " + table + " LIMIT 50"));
            String sqlBody = proxyPost(sqlUrl, sqlPayload);
            @SuppressWarnings("unchecked")
            Map<String, Object> sqlResp = mapper.readValue(sqlBody, Map.class);

            // 3. Parse result into columns and rows
            List<String> columns = new ArrayList<>();
            @SuppressWarnings("unchecked")
            Map<String, Object> metadata = (Map<String, Object>) sqlResp.get("metadata");
            if (metadata != null) {
                @SuppressWarnings("unchecked")
                Map<String, Object> rowType = (Map<String, Object>) metadata.get("rowType");
                if (rowType != null) {
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> fields = (List<Map<String, Object>>) rowType.get("fields");
                    if (fields != null) {
                        for (Map<String, Object> field : fields) {
                            columns.add((String) field.get("name"));
                        }
                    }
                }
            }

            @SuppressWarnings("unchecked")
            List<List<Object>> rawRows = (List<List<Object>>) sqlResp.get("rows");
            List<Map<String, Object>> rows = new ArrayList<>();
            if (rawRows != null) {
                for (List<Object> rawRow : rawRows) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (int i = 0; i < columns.size() && i < rawRow.size(); i++) {
                        row.put(columns.get(i), rawRow.get(i));
                    }
                    rows.add(row);
                }
            }

            return mapper.writeValueAsString(Map.of("columns", columns, "rows", rows));
        } finally {
            // 3. Delete session
            try {
                proxyDelete(spannerBase + "/v1/" + sessionName);
            } catch (Exception ignored) {
                logger.debug("Failed to delete Spanner session: {}", ignored.getMessage());
            }
        }
    }

    // ========== BigQuery table data (proxy to BigQuery REST API) ==========

    /**
     * Query data from a specific BigQuery table.
     * Path: bigquery/datasets/{dataset}/tables/{table}/data
     * @param a should be "datasets"
     * @param b dataset ID
     * @param c should be "tables"
     * @param d table ID
     * @param e should be "data"
     */
    private String browseBigQueryTableData(String a, String b, String c, String d, String e, String projectId) throws Exception {
        if (!"datasets".equals(a) || !"tables".equals(c) || !"data".equals(e)) {
            return mapper.writeValueAsString(Map.of("error", true, "message", "Invalid BigQuery table data browse path"));
        }
        String dataset = b;
        String tableId = d;

        // Execute query via jobs.query
        String queryUrl = bigqueryBase + "/bigquery/v2/projects/" + projectId + "/queries";
        String queryPayload = mapper.writeValueAsString(Map.of(
                "query", "SELECT * FROM `" + dataset + "." + tableId + "` LIMIT 50",
                "useLegacySql", false));
        String queryBody = proxyPost(queryUrl, queryPayload);
        @SuppressWarnings("unchecked")
        Map<String, Object> queryResp = mapper.readValue(queryBody, Map.class);

        // Parse schema fields into columns
        List<String> columns = new ArrayList<>();
        @SuppressWarnings("unchecked")
        Map<String, Object> schema = (Map<String, Object>) queryResp.get("schema");
        if (schema != null) {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> fields = (List<Map<String, Object>>) schema.get("fields");
            if (fields != null) {
                for (Map<String, Object> field : fields) {
                    columns.add((String) field.get("name"));
                }
            }
        }

        // Parse rows (BigQuery returns rows as {f: [{v: value}, ...]})
        List<Map<String, Object>> rows = new ArrayList<>();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rawRows = (List<Map<String, Object>>) queryResp.get("rows");
        if (rawRows != null) {
            for (Map<String, Object> rawRow : rawRows) {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> cells = (List<Map<String, Object>>) rawRow.get("f");
                Map<String, Object> row = new LinkedHashMap<>();
                if (cells != null) {
                    for (int i = 0; i < columns.size() && i < cells.size(); i++) {
                        row.put(columns.get(i), cells.get(i).get("v"));
                    }
                }
                rows.add(row);
            }
        }

        return mapper.writeValueAsString(Map.of("columns", columns, "rows", rows));
    }

    // ========== Firestore (proxy to Firestore REST API) ==========

    private String browseFirestore(String resourceType, String resourceId, String projectId) throws Exception {
        String firestoreBase = "http://localhost:" + firestorePort;

        if (resourceType == null) {
            // List root collections - Firestore REST API doesn't have a direct "list collections" endpoint
            // We use the document listing with a shallow query
            String url = firestoreBase + "/v1/projects/" + projectId + "/databases/(default)/documents";
            try {
                String response = proxyGet(url);
                @SuppressWarnings("unchecked")
                Map<String, Object> resp = mapper.readValue(response, Map.class);
                // Extract collection names from document paths
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> documents = (List<Map<String, Object>>) resp.get("documents");
                java.util.Set<String> collections = new java.util.LinkedHashSet<>();
                if (documents != null) {
                    for (Map<String, Object> doc : documents) {
                        String name = (String) doc.get("name");
                        if (name != null) {
                            // Extract collection name from path like projects/x/databases/(default)/documents/collection/docId
                            String[] parts = name.split("/");
                            if (parts.length >= 7) {
                                collections.add(parts[parts.length - 2]);
                            }
                        }
                    }
                }
                return mapper.writeValueAsString(Map.of("collections", collections));
            } catch (Exception e) {
                logger.warn("Failed to list Firestore collections: {}", e.getMessage());
                return mapper.writeValueAsString(Map.of("collections", List.of()));
            }
        }

        if (resourceType != null && resourceId == null) {
            // List documents in collection
            String url = firestoreBase + "/v1/projects/" + projectId + "/databases/(default)/documents/" + resourceType;
            String response = proxyGet(url);
            @SuppressWarnings("unchecked")
            Map<String, Object> resp = mapper.readValue(response, Map.class);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> documents = (List<Map<String, Object>>) resp.get("documents");
            List<Map<String, Object>> result = new ArrayList<>();
            if (documents != null) {
                for (Map<String, Object> doc : documents) {
                    Map<String, Object> simplified = new LinkedHashMap<>();
                    String name = (String) doc.get("name");
                    if (name != null) {
                        simplified.put("id", name.substring(name.lastIndexOf('/') + 1));
                    }
                    // Convert Firestore field format to simple key-value
                    @SuppressWarnings("unchecked")
                    Map<String, Object> fields = (Map<String, Object>) doc.get("fields");
                    if (fields != null) {
                        Map<String, Object> simpleFields = new LinkedHashMap<>();
                        for (Map.Entry<String, Object> entry : fields.entrySet()) {
                            simpleFields.put(entry.getKey(), extractFirestoreValue(entry.getValue()));
                        }
                        simplified.put("fields", simpleFields);
                    }
                    simplified.put("createTime", doc.get("createTime"));
                    simplified.put("updateTime", doc.get("updateTime"));
                    result.add(simplified);
                }
            }
            return mapper.writeValueAsString(Map.of("documents", result, "collection", resourceType));
        }

        if (resourceType != null && resourceId != null) {
            // Get specific document
            String url = firestoreBase + "/v1/projects/" + projectId + "/databases/(default)/documents/" + resourceType + "/" + resourceId;
            String response = proxyGet(url);
            @SuppressWarnings("unchecked")
            Map<String, Object> doc = mapper.readValue(response, Map.class);
            Map<String, Object> simplified = new LinkedHashMap<>();
            simplified.put("id", resourceId);
            @SuppressWarnings("unchecked")
            Map<String, Object> fields = (Map<String, Object>) doc.get("fields");
            if (fields != null) {
                Map<String, Object> simpleFields = new LinkedHashMap<>();
                for (Map.Entry<String, Object> entry : fields.entrySet()) {
                    simpleFields.put(entry.getKey(), extractFirestoreValue(entry.getValue()));
                }
                simplified.put("fields", simpleFields);
            }
            return mapper.writeValueAsString(simplified);
        }

        return mapper.writeValueAsString(Map.of("error", true, "message", "Invalid Firestore browse path"));
    }

    @SuppressWarnings("unchecked")
    private Object extractFirestoreValue(Object firestoreValue) {
        if (!(firestoreValue instanceof Map)) return firestoreValue;
        Map<String, Object> val = (Map<String, Object>) firestoreValue;
        if (val.containsKey("stringValue")) return val.get("stringValue");
        if (val.containsKey("integerValue")) return val.get("integerValue");
        if (val.containsKey("doubleValue")) return val.get("doubleValue");
        if (val.containsKey("booleanValue")) return val.get("booleanValue");
        if (val.containsKey("nullValue")) return null;
        if (val.containsKey("mapValue")) {
            Map<String, Object> mapVal = (Map<String, Object>) val.get("mapValue");
            Map<String, Object> fields = (Map<String, Object>) mapVal.get("fields");
            if (fields != null) {
                Map<String, Object> result = new LinkedHashMap<>();
                for (Map.Entry<String, Object> entry : fields.entrySet()) {
                    result.put(entry.getKey(), extractFirestoreValue(entry.getValue()));
                }
                return result;
            }
        }
        if (val.containsKey("arrayValue")) {
            Map<String, Object> arrVal = (Map<String, Object>) val.get("arrayValue");
            List<Object> values = (List<Object>) arrVal.get("values");
            if (values != null) {
                List<Object> result = new ArrayList<>();
                for (Object v : values) {
                    result.add(extractFirestoreValue(v));
                }
                return result;
            }
        }
        return firestoreValue;
    }

    // ========== Bigtable (proxy to emulator gRPC) ==========

    private String browseBigtable(String resourceType, String resourceId, String projectId) throws Exception {
        try (BigtableGrpcClient client = new BigtableGrpcClient(bigtablePort)) {
            // Top-level: return instances with tables and column families
            if (resourceType == null) {
                return mapper.writeValueAsString(client.listInstancesWithDetails(projectId));
            }

            // Flat table list (legacy)
            if ("tables".equals(resourceType) && resourceId == null) {
                return mapper.writeValueAsString(Map.of("tables", client.listTables(projectId)));
            }

            // Read rows from a specific table
            if ("tables".equals(resourceType) && resourceId != null) {
                String instanceId = "local-instance";
                String tableId = resourceId;
                int slash = resourceId.indexOf('/');
                if (slash > 0) {
                    instanceId = resourceId.substring(0, slash);
                    tableId = resourceId.substring(slash + 1);
                }
                return mapper.writeValueAsString(Map.of(
                        "rows", client.readRows(projectId, instanceId, tableId, 50),
                        "table", tableId,
                        "instance", instanceId));
            }
        }

        return mapper.writeValueAsString(Map.of("error", true, "message", "Invalid Bigtable browse path"));
    }

    // ========== Workflows (in-process, query PostgreSQL) ==========

    private String browseWorkflows(String resourceType, String resourceId, String projectId) throws Exception {
        if (!config.isPersistenceEnabled()) {
            return mapper.writeValueAsString(Map.of("message", "Persistence disabled"));
        }

        // browse/workflows — list all workflows
        if (resourceType == null) {
            List<Map<String, Object>> workflows = new ArrayList<>();
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement(
                     "SELECT workflow_id, location_id, state, revision_id, created_at, updated_at " +
                     "FROM workflows WHERE project_id = ? AND state != 'DELETED' ORDER BY created_at DESC LIMIT 100")) {
                ps.setString(1, projectId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        Map<String, Object> w = new LinkedHashMap<>();
                        w.put("name", rs.getString("workflow_id"));
                        w.put("location", rs.getString("location_id"));
                        w.put("state", rs.getString("state"));
                        w.put("revision_id", rs.getInt("revision_id"));
                        if (rs.getTimestamp("created_at") != null)
                            w.put("created_at", rs.getTimestamp("created_at").toString());
                        if (rs.getTimestamp("updated_at") != null)
                            w.put("updated_at", rs.getTimestamp("updated_at").toString());
                        workflows.add(w);
                    }
                }
            }
            return mapper.writeValueAsString(Map.of("workflows", workflows));
        }

        // browse/workflows/{workflowId} — single workflow detail
        if (resourceId == null) {
            String workflowId = resourceType;
            Map<String, Object> result = new LinkedHashMap<>();
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement(
                     "SELECT * FROM workflows WHERE project_id = ? AND workflow_id = ? AND state != 'DELETED'")) {
                ps.setString(1, projectId);
                ps.setString(2, workflowId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        result.put("workflow_id", rs.getString("workflow_id"));
                        result.put("location_id", rs.getString("location_id"));
                        result.put("state", rs.getString("state"));
                        result.put("revision_id", rs.getInt("revision_id"));
                        result.put("source_contents", rs.getString("source_contents"));
                        if (rs.getTimestamp("created_at") != null)
                            result.put("created_at", rs.getTimestamp("created_at").toString());
                        if (rs.getTimestamp("updated_at") != null)
                            result.put("updated_at", rs.getTimestamp("updated_at").toString());
                    } else {
                        return mapper.writeValueAsString(Map.of("error", true, "message", "Workflow not found: " + workflowId));
                    }
                }
            }
            return mapper.writeValueAsString(result);
        }

        // browse/workflows/{workflowId}/executions — list executions for a workflow
        if ("executions".equals(resourceId)) {
            String workflowId = resourceType;
            List<Map<String, Object>> executions = new ArrayList<>();
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement(
                     "SELECT execution_id, state, start_time, end_time, workflow_revision_id " +
                     "FROM workflow_executions WHERE project_id = ? AND workflow_id = ? ORDER BY start_time DESC LIMIT 100")) {
                ps.setString(1, projectId);
                ps.setString(2, workflowId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        Map<String, Object> e = new LinkedHashMap<>();
                        e.put("execution_id", rs.getString("execution_id"));
                        e.put("state", rs.getString("state"));
                        if (rs.getTimestamp("start_time") != null)
                            e.put("start_time", rs.getTimestamp("start_time").toString());
                        if (rs.getTimestamp("end_time") != null)
                            e.put("end_time", rs.getTimestamp("end_time").toString());
                        e.put("workflow_revision_id", rs.getString("workflow_revision_id"));
                        executions.add(e);
                    }
                }
            }
            return mapper.writeValueAsString(Map.of("executions", executions));
        }

        // browse/workflows/{workflowId}/revisions — list revisions for a workflow
        if ("revisions".equals(resourceId)) {
            String workflowId = resourceType;
            List<Map<String, Object>> revisions = new ArrayList<>();
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement(
                     "SELECT workflow_id, location_id, state, revision_id, source_contents, created_at, updated_at " +
                     "FROM workflows WHERE project_id = ? AND workflow_id = ? AND state != 'DELETED'")) {
                ps.setString(1, projectId);
                ps.setString(2, workflowId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        Map<String, Object> rev = new LinkedHashMap<>();
                        String loc = rs.getString("location_id");
                        rev.put("name", "projects/" + projectId + "/locations/" + loc + "/workflows/" + workflowId);
                        rev.put("state", rs.getString("state"));
                        rev.put("revisionId", String.valueOf(rs.getInt("revision_id")));
                        rev.put("sourceContents", rs.getString("source_contents"));
                        if (rs.getTimestamp("created_at") != null)
                            rev.put("createTime", rs.getTimestamp("created_at").toString());
                        if (rs.getTimestamp("updated_at") != null)
                            rev.put("updateTime", rs.getTimestamp("updated_at").toString());
                        revisions.add(rev);
                    }
                }
            }
            return mapper.writeValueAsString(Map.of("revisions", revisions));
        }

        return mapper.writeValueAsString(Map.of("error", true, "message", "Invalid Workflows browse path"));
    }

    // ========== GCS bucket ownership helpers ==========

    /**
     * Get ALL tracked bucket names across all projects.
     */
    private java.util.Set<String> getAllTrackedBuckets() {
        java.util.Set<String> buckets = new java.util.HashSet<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT bucket_name FROM gcs_bucket_projects")) {
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    buckets.add(rs.getString("bucket_name"));
                }
            }
        } catch (Exception e) {
            logger.debug("Could not query all GCS bucket ownership: {}", e.getMessage());
        }
        return buckets;
    }

    /**
     * Register bucket→project ownership. No-op if already tracked.
     */
    private void registerBucketOwnership(String bucketName, String projectId) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "INSERT INTO gcs_bucket_projects (bucket_name, project_id) VALUES (?, ?) " +
                 "ON CONFLICT (bucket_name) DO NOTHING")) {
            ps.setString(1, bucketName);
            ps.setString(2, projectId);
            ps.executeUpdate();
        } catch (Exception e) {
            logger.debug("Could not register GCS bucket ownership for {}: {}", bucketName, e.getMessage());
        }
    }

    /**
     * Get the set of bucket names owned by a project.
     */
    private java.util.Set<String> getProjectBuckets(String projectId) {
        java.util.Set<String> buckets = new java.util.HashSet<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT bucket_name FROM gcs_bucket_projects WHERE project_id = ?")) {
            ps.setString(1, projectId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    buckets.add(rs.getString("bucket_name"));
                }
            }
        } catch (Exception e) {
            logger.debug("Could not query GCS bucket ownership: {}", e.getMessage());
        }
        return buckets;
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

    private String proxyPost(String url, String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        java.net.http.HttpResponse<String> response = httpClient.send(request, BodyHandlers.ofString());
        return response.body();
    }

    private void proxyPut(String url, String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(body))
                .build();

        java.net.http.HttpResponse<String> response = httpClient.send(request, BodyHandlers.ofString());
        if (response.statusCode() >= 400) {
            throw new RuntimeException("HTTP PUT " + url + " failed with status " + response.statusCode());
        }
    }

    private void proxyDelete(String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .DELETE()
                .build();

        httpClient.send(request, BodyHandlers.ofString());
    }

    private List<String> splitTopLevel(String s) {
        List<String> parts = new ArrayList<>();
        int depth = 0, start = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '(') depth++;
            else if (c == ')') depth--;
            else if (c == ',' && depth == 0) {
                parts.add(s.substring(start, i).trim());
                start = i + 1;
            }
        }
        if (start < s.length()) {
            String last = s.substring(start).trim();
            if (!last.isEmpty()) parts.add(last);
        }
        return parts;
    }

}
