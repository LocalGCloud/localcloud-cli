package com.localcloud.admin;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse.BodyHandlers;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linecorp.armeria.common.AggregatedHttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.annotation.Get;
import com.linecorp.armeria.server.annotation.Param;
import com.linecorp.armeria.server.annotation.Post;
import com.localcloud.config.LocalCloudConfig;
import com.localcloud.config.ServiceRegistry;
import com.localcloud.config.ServiceRegistry.ServiceDefinition;
import com.localcloud.persistence.PostgresDataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Query execution service for the LocalCloud SQL Editor.
 * Executes SQL queries against PostgreSQL-backed services directly,
 * and proxies queries to BigQuery and Spanner emulators.
 * <p>
 * Registered at the {@code /_localcloud} path prefix.
 */
public class QueryService {

    private static final Logger logger = LoggerFactory.getLogger(QueryService.class);

    /** Services whose data lives in the internal PostgreSQL database. */
    private static final Set<String> POSTGRES_SERVICES = Set.of(
            "secretmanager", "cloudtasks", "logging", "monitoring",
            "bigtable", "compute", "cloudrun", "gke", "memorystore", "workflows"
    );

    private final LocalCloudConfig config;
    private final PostgresDataSource dataSource;
    private final UsageMetricsRepository usageMetrics;
    private final HttpClient httpClient;
    private final ObjectMapper mapper;

    private final String bigqueryBase;
    private final String spannerBase;

    public QueryService(LocalCloudConfig config, PostgresDataSource dataSource,
                        ServiceRegistry registry, UsageMetricsRepository usageMetrics) {
        this.config = config;
        this.dataSource = dataSource;
        this.usageMetrics = usageMetrics;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        this.mapper = new ObjectMapper();

        ServiceDefinition bqDef = registry.getService("bigquery");
        this.bigqueryBase = "http://localhost:" + (bqDef != null ? bqDef.port() : 9050);

        ServiceDefinition spannerDef = registry.getService("spanner");
        int spannerRestPort = spannerDef != null && spannerDef.additionalPorts().containsKey("rest")
                ? spannerDef.additionalPorts().get("rest") : 9020;
        this.spannerBase = "http://localhost:" + spannerRestPort;
    }

    /**
     * Execute a SQL query against the specified service.
     * <p>
     * Request body:
     * <pre>
     * {
     *   "service": "bigquery|spanner|secretmanager|...",
     *   "sql": "SELECT * FROM table LIMIT 10",
     *   "instance": "my-instance",   // Spanner only
     *   "database": "my-database"    // Spanner only
     * }
     * </pre>
     */
    @Post("/query")
    public HttpResponse query(ServiceRequestContext ctx, AggregatedHttpRequest httpRequest) {
        try {
            String body = httpRequest.contentUtf8();
            @SuppressWarnings("unchecked")
            Map<String, Object> request = mapper.readValue(body, Map.class);

            String service = (String) request.get("service");
            String sql = (String) request.get("sql");

            if (service == null || service.isBlank()) {
                return errorResponse("Missing required field: service");
            }
            if (sql == null || sql.isBlank()) {
                return errorResponse("Missing required field: sql");
            }

            String project = ctx.queryParams().get("project");
            String projectId = (project != null && !project.isBlank()) ? project : config.getProjectId();

            usageMetrics.incrementCount(projectId, service, 1);
            long startTime = System.currentTimeMillis();

            if (POSTGRES_SERVICES.contains(service)) {
                return executePostgresQuery(sql, startTime);
            } else if ("bigquery".equals(service)) {
                return executeBigQueryQuery(sql, projectId, startTime);
            } else if ("spanner".equals(service)) {
                String instance = (String) request.get("instance");
                String database = (String) request.get("database");
                return executeSpannerQuery(sql, projectId, instance, database, startTime);
            } else if ("pubsub".equals(service)) {
                return executePubSubQuery(sql, projectId, startTime);
            } else {
                return errorResponse("Service '" + service + "' does not support SQL queries");
            }

        } catch (Exception e) {
            logger.error("Query execution failed", e);
            return errorResponse(e.getMessage() != null ? e.getMessage() : "Query execution failed");
        }
    }

    // ─── PostgreSQL Direct Query ───────────────────────────────────────

    private HttpResponse executePostgresQuery(String sql, long startTime) {
        // Safety: only allow SELECT and EXPLAIN
        String trimmed = sql.trim().toUpperCase();
        if (!trimmed.startsWith("SELECT") && !trimmed.startsWith("EXPLAIN") && !trimmed.startsWith("WITH")) {
            return errorResponse("Only SELECT, EXPLAIN, and WITH (CTE) queries are allowed");
        }

        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {

            // Set a query timeout to prevent runaway queries
            stmt.setQueryTimeout(30);

            ResultSet rs = stmt.executeQuery(sql);
            ResultSetMetaData meta = rs.getMetaData();
            int colCount = meta.getColumnCount();

            // Extract column names
            List<String> columns = new ArrayList<>(colCount);
            for (int i = 1; i <= colCount; i++) {
                columns.add(meta.getColumnLabel(i));
            }

            // Extract rows as arrays of values
            List<List<Object>> rows = new ArrayList<>();
            while (rs.next()) {
                List<Object> row = new ArrayList<>(colCount);
                for (int i = 1; i <= colCount; i++) {
                    Object val = rs.getObject(i);
                    // Convert PostgreSQL-specific types to JSON-safe types
                    if (val instanceof org.postgresql.util.PGobject) {
                        row.add(((org.postgresql.util.PGobject) val).getValue());
                    } else {
                        row.add(val);
                    }
                }
                rows.add(row);
            }

            long elapsed = System.currentTimeMillis() - startTime;
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("columns", columns);
            result.put("rows", rows);
            result.put("row_count", rows.size());
            result.put("execution_time_ms", elapsed);

            return HttpResponse.of(HttpStatus.OK, MediaType.JSON,
                    mapper.writerWithDefaultPrettyPrinter().writeValueAsString(result));

        } catch (Exception e) {
            logger.warn("PostgreSQL query failed: {}", e.getMessage());
            return errorResponse(e.getMessage());
        }
    }

    // ─── BigQuery Proxy Query ──────────────────────────────────────────

    private HttpResponse executeBigQueryQuery(String sql, String projectId, long startTime) {
        try {
            String queryUrl = bigqueryBase + "/bigquery/v2/projects/" + projectId + "/queries";
            String queryPayload = mapper.writeValueAsString(Map.of(
                    "query", sql,
                    "useLegacySql", false));
            String responseBody = proxyPost(queryUrl, queryPayload);

            @SuppressWarnings("unchecked")
            Map<String, Object> queryResp = mapper.readValue(responseBody, Map.class);

            // Check for errors from BigQuery
            @SuppressWarnings("unchecked")
            Map<String, Object> error = (Map<String, Object>) queryResp.get("error");
            if (error != null) {
                return errorResponse("BigQuery error: " + error.get("message"));
            }

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

            // Parse rows (BigQuery returns {f: [{v: value}, ...]})
            List<List<Object>> rows = new ArrayList<>();
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> rawRows = (List<Map<String, Object>>) queryResp.get("rows");
            if (rawRows != null) {
                for (Map<String, Object> rawRow : rawRows) {
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> cells = (List<Map<String, Object>>) rawRow.get("f");
                    List<Object> row = new ArrayList<>();
                    if (cells != null) {
                        for (Map<String, Object> cell : cells) {
                            row.add(cell.get("v"));
                        }
                    }
                    rows.add(row);
                }
            }

            long elapsed = System.currentTimeMillis() - startTime;
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("columns", columns);
            result.put("rows", rows);
            result.put("row_count", rows.size());
            result.put("execution_time_ms", elapsed);

            return HttpResponse.of(HttpStatus.OK, MediaType.JSON,
                    mapper.writerWithDefaultPrettyPrinter().writeValueAsString(result));

        } catch (Exception e) {
            logger.warn("BigQuery query failed: {}", e.getMessage());
            return errorResponse("BigQuery query failed: " + e.getMessage());
        }
    }

    // ─── Spanner Proxy Query ───────────────────────────────────────────

    private HttpResponse executeSpannerQuery(String sql, String projectId,
                                              String instance, String database, long startTime) {
        // Auto-resolve instance if not provided — use the first (usually only) instance
        if (instance == null || instance.isBlank()) {
            try {
                String url = spannerBase + "/v1/projects/" + projectId + "/instances";
                String body = proxyGet(url);
                @SuppressWarnings("unchecked")
                Map<String, Object> resp = mapper.readValue(body, Map.class);
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> instances = (List<Map<String, Object>>) resp.getOrDefault("instances", List.of());
                if (instances.isEmpty()) {
                    return errorResponse("No Spanner instances found. Create one first.");
                }
                String name = (String) instances.get(0).get("name");
                instance = name.substring(name.lastIndexOf('/') + 1);
            } catch (Exception e) {
                return errorResponse("Failed to auto-resolve Spanner instance: " + e.getMessage());
            }
        }

        // Auto-resolve database if not provided — use the first database in the instance
        if (database == null || database.isBlank()) {
            try {
                String url = spannerBase + "/v1/projects/" + projectId + "/instances/" + instance + "/databases";
                String body = proxyGet(url);
                @SuppressWarnings("unchecked")
                Map<String, Object> resp = mapper.readValue(body, Map.class);
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> databases = (List<Map<String, Object>>) resp.getOrDefault("databases", List.of());
                if (databases.isEmpty()) {
                    return errorResponse("No databases in Spanner instance '" + instance + "'. Create one first.");
                }
                String name = (String) databases.get(0).get("name");
                database = name.substring(name.lastIndexOf('/') + 1);
                logger.info("Auto-resolved Spanner database: {}/{}", instance, database);
            } catch (Exception e) {
                return errorResponse("Failed to auto-resolve Spanner database: " + e.getMessage());
            }
        }

        // Spanner SQL doesn't support database.table syntax.
        // If the SQL contains "database_name.TableName", extract the database and strip the prefix.
        // This lets users copy table names from the schema tree (which shows "orders_db.Products").
        try {
            String url = spannerBase + "/v1/projects/" + projectId + "/instances/" + instance + "/databases";
            String body = proxyGet(url);
            @SuppressWarnings("unchecked")
            Map<String, Object> resp = mapper.readValue(body, Map.class);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> allDbs = (List<Map<String, Object>>) resp.getOrDefault("databases", List.of());
            for (Map<String, Object> db : allDbs) {
                String dbFullName = (String) db.get("name");
                String dbName = dbFullName.substring(dbFullName.lastIndexOf('/') + 1);
                // Check if SQL references this database as a prefix (e.g., "orders_db.Products")
                if (sql.contains(dbName + ".")) {
                    database = dbName;
                    sql = sql.replace(dbName + ".", "");
                    logger.info("Spanner SQL rewrite: stripped '{}.' prefix, using database '{}'", dbName, database);
                    break;
                }
            }
        } catch (Exception e) {
            logger.debug("Failed to check database prefixes in SQL: {}", e.getMessage());
        }

        // DDL statements (CREATE TABLE, DROP TABLE, ALTER TABLE, CREATE INDEX, DROP INDEX)
        // must go through the DDL update API, not executeSql
        String trimmedUpper = sql.trim().toUpperCase();
        if (trimmedUpper.startsWith("CREATE ") || trimmedUpper.startsWith("DROP ") || trimmedUpper.startsWith("ALTER ")) {
            return executeSpannerDdl(sql, projectId, instance, database, startTime);
        }

        String sessionName = null;
        try {
            // 1. Create session
            String sessionUrl = spannerBase + "/v1/projects/" + projectId + "/instances/" + instance
                    + "/databases/" + database + "/sessions";
            String sessionBody = proxyPost(sessionUrl, "{}");
            @SuppressWarnings("unchecked")
            Map<String, Object> sessionResp = mapper.readValue(sessionBody, Map.class);
            sessionName = (String) sessionResp.get("name");

            // 2. Execute SQL (DML only — SELECT, INSERT, UPDATE, DELETE)
            String sqlUrl = spannerBase + "/v1/" + sessionName + ":executeSql";
            String sqlPayload = mapper.writeValueAsString(Map.of("sql", sql));
            String sqlBody = proxyPost(sqlUrl, sqlPayload);
            @SuppressWarnings("unchecked")
            Map<String, Object> sqlResp = mapper.readValue(sqlBody, Map.class);

            // Check for Spanner error response (e.g., {"code":5, "message":"Not Found"})
            if (sqlResp.containsKey("code")) {
                int code = ((Number) sqlResp.get("code")).intValue();
                String message = sqlResp.containsKey("message") ? String.valueOf(sqlResp.get("message")) : "Unknown error";
                if (code != 0) {
                    // Extract table name from SQL for a helpful error message
                    String tableName = sql.replaceAll("(?i).*?FROM\\s+(\\S+).*", "$1").trim();
                    String errorMsg;
                    if (code == 5 || message.contains("Not Found") || message.contains("not found")) {
                        errorMsg = "Table '" + tableName + "' not found in database '" + database + "'";
                    } else if (code == 13 || message.contains("marshal")) {
                        // Spanner emulator wraps NOT_FOUND as INTERNAL with "failed to marshal"
                        errorMsg = "Table '" + tableName + "' does not exist in database '" + database + "'. "
                                + "Check that the table name and database are correct.";
                    } else {
                        errorMsg = "Spanner error: " + message;
                    }
                    return errorResponse(errorMsg);
                }
            }

            // 3. Parse columns from metadata
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

            // 4. Parse rows
            @SuppressWarnings("unchecked")
            List<List<Object>> rawRows = (List<List<Object>>) sqlResp.get("rows");
            List<List<Object>> rows = new ArrayList<>();
            if (rawRows != null) {
                rows.addAll(rawRows);
            }

            long elapsed = System.currentTimeMillis() - startTime;
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("columns", columns);
            result.put("rows", rows);
            result.put("row_count", rows.size());
            result.put("execution_time_ms", elapsed);

            return HttpResponse.of(HttpStatus.OK, MediaType.JSON,
                    mapper.writerWithDefaultPrettyPrinter().writeValueAsString(result));

        } catch (Exception e) {
            logger.warn("Spanner query failed: {}", e.getMessage());
            return errorResponse("Spanner query failed: " + e.getMessage());
        } finally {
            if (sessionName != null) {
                try {
                    proxyDelete(spannerBase + "/v1/" + sessionName);
                } catch (Exception ignored) {
                    logger.debug("Failed to delete Spanner session: {}", ignored.getMessage());
                }
            }
        }
    }

    /**
     * Execute a Spanner DDL statement (CREATE TABLE, DROP TABLE, ALTER TABLE, CREATE INDEX, etc.)
     * via the database DDL update API (PATCH).
     */
    private HttpResponse executeSpannerDdl(String sql, String projectId,
                                            String instance, String database, long startTime) {
        try {
            // Spanner DDL API does not accept trailing semicolons — strip them
            String cleanSql = sql.trim();
            while (cleanSql.endsWith(";")) {
                cleanSql = cleanSql.substring(0, cleanSql.length() - 1).trim();
            }

            String url = spannerBase + "/v1/projects/" + projectId + "/instances/" + instance
                    + "/databases/" + database + "/ddl";
            String payload = mapper.writeValueAsString(Map.of("statements", List.of(cleanSql)));

            var request = java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create(url))
                    .method("PATCH", java.net.http.HttpRequest.BodyPublishers.ofString(payload))
                    .header("Content-Type", "application/json")
                    .timeout(java.time.Duration.ofSeconds(30))
                    .build();
            var response = httpClient.send(request, java.net.http.HttpResponse.BodyHandlers.ofString());

            long elapsed = System.currentTimeMillis() - startTime;

            if (response.statusCode() >= 400) {
                String errorBody = response.body();
                // Parse error message — Spanner returns either {"error":{"message":"..."}} or {"code":N,"message":"..."}
                try {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> errorResp = mapper.readValue(errorBody, Map.class);
                    // Try nested {"error":{"message":"..."}}
                    Object errorObj = errorResp.get("error");
                    if (errorObj instanceof Map) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> err = (Map<String, Object>) errorObj;
                        return errorResponse(String.valueOf(err.getOrDefault("message", errorBody)));
                    }
                    // Try flat {"code":N,"message":"..."}
                    if (errorResp.containsKey("message")) {
                        return errorResponse(String.valueOf(errorResp.get("message")));
                    }
                } catch (Exception ignored) {}
                return errorResponse(errorBody);
            }

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("columns", List.of("result"));
            result.put("rows", List.of(List.of("DDL statement executed successfully")));
            result.put("row_count", 1);
            result.put("execution_time_ms", elapsed);

            return HttpResponse.of(HttpStatus.OK, MediaType.JSON,
                    mapper.writerWithDefaultPrettyPrinter().writeValueAsString(result));

        } catch (Exception e) {
            logger.warn("Spanner DDL failed: {}", e.getMessage());
            return errorResponse("Spanner DDL failed: " + e.getMessage());
        }
    }

    // ─── Pub/Sub Query ─────────────────────────────────────────────────

    /**
     * Execute a pseudo-SQL query against Pub/Sub topics.
     * Supports: SELECT * FROM <topic_name> [LIMIT n]
     * Pulls messages from the topic's first subscription, decodes base64 data,
     * and returns messages as rows.
     */
    private HttpResponse executePubSubQuery(String sql, String projectId, long startTime) {
        try {
            String pubsubBase = "http://localhost:8085";

            // Parse topic name and limit from SQL
            String trimmed = sql.trim();
            java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                    "(?i)SELECT\\s+\\*\\s+FROM\\s+(\\S+)(?:\\s+LIMIT\\s+(\\d+))?").matcher(trimmed);
            if (!m.find()) {
                return errorResponse("Pub/Sub SQL syntax: SELECT * FROM <topic_name> [LIMIT n]");
            }
            String topicName = m.group(1).replace("`", "").replace("\"", "");
            int limit = m.group(2) != null ? Integer.parseInt(m.group(2)) : 100;

            // Find subscription for this topic
            String subsUrl = pubsubBase + "/v1/projects/" + projectId + "/subscriptions";
            String subsBody = proxyGet(subsUrl);
            @SuppressWarnings("unchecked")
            Map<String, Object> subsResp = mapper.readValue(subsBody, Map.class);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> subscriptions = (List<Map<String, Object>>) subsResp.getOrDefault("subscriptions", List.of());

            String subscriptionName = null;
            String fullTopicName = "projects/" + projectId + "/topics/" + topicName;
            for (Map<String, Object> sub : subscriptions) {
                if (fullTopicName.equals(sub.get("topic"))) {
                    subscriptionName = (String) sub.get("name");
                    break;
                }
            }

            if (subscriptionName == null) {
                return errorResponse("No subscription found for topic '" + topicName + "'. Create a subscription first.");
            }

            // Pull messages (peek — don't ack)
            String shortSubName = subscriptionName.substring(subscriptionName.lastIndexOf('/') + 1);
            String pullUrl = pubsubBase + "/v1/projects/" + projectId + "/subscriptions/" + shortSubName + ":pull";
            String pullPayload = mapper.writeValueAsString(Map.of("maxMessages", limit));
            String pullBody = proxyPost(pullUrl, pullPayload);
            @SuppressWarnings("unchecked")
            Map<String, Object> pullResp = mapper.readValue(pullBody, Map.class);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> receivedMessages = (List<Map<String, Object>>) pullResp.getOrDefault("receivedMessages", List.of());

            // Build result rows
            List<String> columns = List.of("messageId", "data", "attributes", "publishTime");
            List<List<Object>> rows = new ArrayList<>();
            for (Map<String, Object> rm : receivedMessages) {
                @SuppressWarnings("unchecked")
                Map<String, Object> msg = (Map<String, Object>) rm.get("message");
                if (msg == null) continue;

                String messageId = (String) msg.getOrDefault("messageId", "");
                String dataBase64 = (String) msg.getOrDefault("data", "");
                String decodedData;
                try {
                    decodedData = new String(java.util.Base64.getDecoder().decode(dataBase64), java.nio.charset.StandardCharsets.UTF_8);
                } catch (Exception e) {
                    decodedData = dataBase64; // fallback to raw
                }
                Object attributes = msg.getOrDefault("attributes", Map.of());
                String publishTime = (String) msg.getOrDefault("publishTime", "");

                rows.add(List.of(messageId, decodedData, mapper.writeValueAsString(attributes), publishTime));
            }

            long elapsed = System.currentTimeMillis() - startTime;
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("columns", columns);
            result.put("rows", rows);
            result.put("row_count", rows.size());
            result.put("execution_time_ms", elapsed);

            return HttpResponse.of(HttpStatus.OK, MediaType.JSON,
                    mapper.writerWithDefaultPrettyPrinter().writeValueAsString(result));

        } catch (Exception e) {
            logger.warn("Pub/Sub query failed: {}", e.getMessage());
            return errorResponse("Pub/Sub query failed: " + e.getMessage());
        }
    }

    /**
     * Return Pub/Sub schema — lists topics as "tables" with message columns.
     */
    private HttpResponse schemaPubSub(String projectId) {
        try {
            String pubsubBase = "http://localhost:8085";
            String topicsUrl = pubsubBase + "/v1/projects/" + projectId + "/topics";
            String topicsBody = proxyGet(topicsUrl);
            @SuppressWarnings("unchecked")
            Map<String, Object> topicsResp = mapper.readValue(topicsBody, Map.class);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> topics = (List<Map<String, Object>>) topicsResp.getOrDefault("topics", List.of());

            List<Map<String, Object>> tables = new ArrayList<>();
            List<Map<String, String>> msgColumns = List.of(
                Map.of("name", "messageId", "type", "STRING"),
                Map.of("name", "data", "type", "JSON"),
                Map.of("name", "attributes", "type", "JSON"),
                Map.of("name", "publishTime", "type", "TIMESTAMP")
            );

            for (Map<String, Object> topic : topics) {
                String name = (String) topic.get("name");
                String shortName = name != null ? name.substring(name.lastIndexOf('/') + 1) : "unknown";
                tables.add(Map.of("name", shortName, "columns", msgColumns));
            }

            return HttpResponse.of(HttpStatus.OK, MediaType.JSON,
                    mapper.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of("tables", tables)));

        } catch (Exception e) {
            logger.warn("Pub/Sub schema fetch failed: {}", e.getMessage());
            return HttpResponse.of(HttpStatus.OK, MediaType.JSON, "{\"tables\": []}");
        }
    }

    // ─── GCS File Schema Detection ──────────────────────────────────────

    /** Allowlist pattern for bucket/object names — rejects path traversal and SQL injection. */
    private static final java.util.regex.Pattern SAFE_GCS_PATH =
            java.util.regex.Pattern.compile("^[a-zA-Z0-9._\\-/]+$");

    /** Supported queryable file extensions and their DuckDB reader functions. */
    private static final Map<String, String> FORMAT_READERS = Map.of(
            ".parquet", "read_parquet",
            ".csv", "read_csv",
            ".json", "read_json",
            ".jsonl", "read_json",
            ".ndjson", "read_json"
    );

    /**
     * Detect schema of a GCS file by querying the BigQuery emulator with LIMIT 0.
     * Returns { columns: [{ name, type }] }.
     */
    @Get("/gcs/file-schema")
    public HttpResponse gcsFileSchema(ServiceRequestContext ctx) {
        try {
            String bucket = ctx.queryParams().get("bucket");
            String object = ctx.queryParams().get("object");
            String project = ctx.queryParams().get("project");
            String projectId = (project != null && !project.isBlank()) ? project : config.getProjectId();

            if (bucket == null || bucket.isBlank() || object == null || object.isBlank()) {
                return HttpResponse.of(HttpStatus.BAD_REQUEST, MediaType.JSON,
                        mapper.writeValueAsString(Map.of("error", "Missing required params: bucket, object")));
            }

            // Reject path traversal and SQL injection characters
            if (!SAFE_GCS_PATH.matcher(bucket).matches() || !SAFE_GCS_PATH.matcher(object).matches()
                    || bucket.contains("..") || object.contains("..")) {
                return HttpResponse.of(HttpStatus.BAD_REQUEST, MediaType.JSON,
                        mapper.writeValueAsString(Map.of("error", "Invalid bucket or object name")));
            }

            // Detect format from extension
            String ext = object.contains(".") ? object.substring(object.lastIndexOf('.')).toLowerCase() : "";
            String readerFn = FORMAT_READERS.get(ext);
            if (readerFn == null) {
                return HttpResponse.of(HttpStatus.BAD_REQUEST, MediaType.JSON,
                        mapper.writeValueAsString(Map.of("error",
                                "Unsupported file format: " + ext + ". Supported: .parquet, .csv, .json, .jsonl, .ndjson")));
            }

            // Build a LIMIT 0 query to detect schema without reading data
            // Use local filesystem path (DuckDB reads directly inside container)
            String gcsDataDir = System.getenv("GCS_DATA_DIR");
            if (gcsDataDir == null || gcsDataDir.isBlank()) gcsDataDir = "/var/lib/localcloud/gcs-data";
            String filePath = gcsDataDir + "/" + bucket + "/" + object;
            String sql;
            if ("read_csv".equals(readerFn)) {
                sql = "SELECT * FROM " + readerFn + "('" + filePath + "', auto_detect=true, header=true) LIMIT 0";
            } else if ("read_json".equals(readerFn)) {
                sql = "SELECT * FROM " + readerFn + "('" + filePath + "', auto_detect=true) LIMIT 0";
            } else {
                sql = "SELECT * FROM " + readerFn + "('" + filePath + "') LIMIT 0";
            }

            // Execute via BigQuery emulator
            String queryUrl = bigqueryBase + "/bigquery/v2/projects/" + projectId + "/queries";
            String queryPayload = mapper.writeValueAsString(Map.of("query", sql, "useLegacySql", false));
            String responseBody = proxyPost(queryUrl, queryPayload);

            @SuppressWarnings("unchecked")
            Map<String, Object> queryResp = mapper.readValue(responseBody, Map.class);

            // Check for errors
            @SuppressWarnings("unchecked")
            Map<String, Object> bqError = (Map<String, Object>) queryResp.get("error");
            if (bqError != null) {
                String errMsg = (String) bqError.get("message");
                int status = errMsg != null && errMsg.contains("No such file") ? 404 : 500;
                return HttpResponse.of(HttpStatus.valueOf(status), MediaType.JSON,
                        mapper.writeValueAsString(Map.of("error", "Schema detection failed: " + errMsg)));
            }

            // Parse schema from response
            List<Map<String, String>> columns = new ArrayList<>();
            @SuppressWarnings("unchecked")
            Map<String, Object> schema = (Map<String, Object>) queryResp.get("schema");
            if (schema != null) {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> fields = (List<Map<String, Object>>) schema.get("fields");
                if (fields != null) {
                    for (Map<String, Object> field : fields) {
                        columns.add(Map.of(
                                "name", (String) field.get("name"),
                                "type", (String) field.getOrDefault("type", "STRING")
                        ));
                    }
                }
            }

            return HttpResponse.of(HttpStatus.OK, MediaType.JSON,
                    mapper.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of("columns", columns)));

        } catch (Exception e) {
            String msg = e.getMessage() != null ? e.getMessage() : "Unknown error";
            logger.warn("GCS file schema detection failed: {}", msg);
            try {
                return HttpResponse.of(HttpStatus.INTERNAL_SERVER_ERROR, MediaType.JSON,
                        mapper.writeValueAsString(Map.of("error", "Schema detection failed: " + msg)));
            } catch (Exception je) {
                return HttpResponse.of(HttpStatus.INTERNAL_SERVER_ERROR, MediaType.PLAIN_TEXT_UTF_8,
                        "Schema detection failed");
            }
        }
    }

    // ─── Schema endpoint ───────────────────────────────────────────────

    /**
     * Return table schema for a service (used by the SQL Editor for autocomplete).
     */
    @Get("/schema/{service}")
    public HttpResponse schema(ServiceRequestContext ctx, @Param("service") String service) {
        try {
            String project = ctx.queryParams().get("project");
            String projectId = (project != null && !project.isBlank()) ? project : config.getProjectId();

            if ("bigquery".equals(service)) {
                return schemaBigQuery(projectId);
            }

            if ("spanner".equals(service)) {
                String instance = ctx.queryParams().get("instance");
                String database = ctx.queryParams().get("database");
                return schemaSpanner(projectId, instance, database);
            }

            if ("pubsub".equals(service)) {
                return schemaPubSub(projectId);
            }

            if (service == null || !POSTGRES_SERVICES.contains(service)) {
                return HttpResponse.of(HttpStatus.OK, MediaType.JSON,
                        mapper.writeValueAsString(Map.of("tables", List.of())));
            }

            // Filter tables by service — each service owns specific PostgreSQL tables
            Map<String, List<String>> serviceTables = Map.ofEntries(
                Map.entry("secretmanager", List.of("secrets", "secret_versions")),
                Map.entry("cloudtasks", List.of("task_queues", "cloud_tasks")),
                Map.entry("logging", List.of("log_entries")),
                Map.entry("monitoring", List.of("time_series", "metric_points")),
                Map.entry("bigtable", List.of("bigtable_data")),
                Map.entry("compute", List.of("compute_instances")),
                Map.entry("cloudrun", List.of("cloudrun_services", "cloudrun_revisions")),
                Map.entry("gke", List.of("gke_clusters")),
                Map.entry("memorystore", List.of("redis_data")),
                Map.entry("workflows", List.of("workflows", "workflow_executions"))
            );

            List<String> allowedTables = serviceTables.getOrDefault(service, List.of());
            String tableFilter = "";
            if (!allowedTables.isEmpty()) {
                tableFilter = " AND table_name IN (" +
                    allowedTables.stream().map(t -> "'" + t + "'").collect(java.util.stream.Collectors.joining(",")) + ")";
            }

            // Query PostgreSQL information_schema for table/column metadata
            List<Map<String, Object>> tables = new ArrayList<>();
            try (Connection conn = dataSource.getConnection();
                 Statement stmt = conn.createStatement()) {

                ResultSet rs = stmt.executeQuery(
                        "SELECT table_name, column_name, data_type " +
                        "FROM information_schema.columns " +
                        "WHERE table_schema = 'public'" + tableFilter +
                        " ORDER BY table_name, ordinal_position");

                String currentTable = null;
                List<Map<String, String>> currentColumns = null;

                while (rs.next()) {
                    String tableName = rs.getString("table_name");
                    if (!tableName.equals(currentTable)) {
                        if (currentTable != null) {
                            tables.add(Map.of("name", currentTable, "columns", currentColumns));
                        }
                        currentTable = tableName;
                        currentColumns = new ArrayList<>();
                    }
                    currentColumns.add(Map.of(
                            "name", rs.getString("column_name"),
                            "type", rs.getString("data_type").toUpperCase()
                    ));
                }
                if (currentTable != null) {
                    tables.add(Map.of("name", currentTable, "columns", currentColumns));
                }
            }

            return HttpResponse.of(HttpStatus.OK, MediaType.JSON,
                    mapper.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of("tables", tables)));

        } catch (Exception e) {
            logger.warn("Schema fetch failed: {}", e.getMessage());
            return errorResponse("Schema fetch failed: " + e.getMessage());
        }
    }

    // ─── BigQuery Schema (proxy to emulator) ─────────────────────────

    private HttpResponse schemaBigQuery(String projectId) {
        try {
            // 1. List datasets
            String datasetsUrl = bigqueryBase + "/bigquery/v2/projects/" + projectId + "/datasets";
            String datasetsBody = proxyGet(datasetsUrl);
            @SuppressWarnings("unchecked")
            Map<String, Object> datasetsResp = mapper.readValue(datasetsBody, Map.class);

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> datasets = (List<Map<String, Object>>) datasetsResp.get("datasets");
            if (datasets == null || datasets.isEmpty()) {
                return HttpResponse.of(HttpStatus.OK, MediaType.JSON,
                        mapper.writeValueAsString(Map.of("tables", List.of())));
            }

            // 2. For each dataset, list tables and get their schema
            List<Map<String, Object>> allTables = new ArrayList<>();
            for (Map<String, Object> ds : datasets) {
                @SuppressWarnings("unchecked")
                Map<String, Object> dsRef = (Map<String, Object>) ds.get("datasetReference");
                String datasetId = dsRef != null ? (String) dsRef.get("datasetId") : (String) ds.get("id");
                if (datasetId == null) continue;

                // List tables in dataset
                String tablesUrl = bigqueryBase + "/bigquery/v2/projects/" + projectId + "/datasets/" + datasetId + "/tables";
                String tablesBody = proxyGet(tablesUrl);
                @SuppressWarnings("unchecked")
                Map<String, Object> tablesResp = mapper.readValue(tablesBody, Map.class);
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> tableList = (List<Map<String, Object>>) tablesResp.get("tables");
                if (tableList == null) continue;

                for (Map<String, Object> tbl : tableList) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> tblRef = (Map<String, Object>) tbl.get("tableReference");
                    String tableId = tblRef != null ? (String) tblRef.get("tableId") : null;
                    if (tableId == null) continue;

                    // Get table schema
                    String schemaUrl = bigqueryBase + "/bigquery/v2/projects/" + projectId
                            + "/datasets/" + datasetId + "/tables/" + tableId;
                    try {
                        String schemaBody = proxyGet(schemaUrl);
                        @SuppressWarnings("unchecked")
                        Map<String, Object> schemaResp = mapper.readValue(schemaBody, Map.class);
                        @SuppressWarnings("unchecked")
                        Map<String, Object> schema = (Map<String, Object>) schemaResp.get("schema");

                        List<Map<String, String>> columns = new ArrayList<>();
                        if (schema != null) {
                            @SuppressWarnings("unchecked")
                            List<Map<String, Object>> fields = (List<Map<String, Object>>) schema.get("fields");
                            if (fields != null) {
                                for (Map<String, Object> field : fields) {
                                    columns.add(Map.of(
                                            "name", (String) field.get("name"),
                                            "type", (String) field.getOrDefault("type", "STRING")
                                    ));
                                }
                            }
                        }
                        String qualifiedName = datasetId + "." + tableId;
                        allTables.add(Map.of("name", qualifiedName, "columns", columns));
                    } catch (Exception e) {
                        // Skip tables we can't get schema for
                        allTables.add(Map.of("name", datasetId + "." + tableId, "columns", List.of()));
                    }
                }
            }

            return HttpResponse.of(HttpStatus.OK, MediaType.JSON,
                    mapper.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of("tables", allTables)));

        } catch (Exception e) {
            logger.warn("BigQuery schema fetch failed: {}", e.getMessage());
            return HttpResponse.of(HttpStatus.OK, MediaType.JSON,
                    "{\"tables\":[]}");
        }
    }

    // ─── Spanner Schema (proxy to emulator) ──────────────────────────

    private HttpResponse schemaSpanner(String projectId, String instance, String database) {
        try {
            // 1. List instances
            String instancesUrl = spannerBase + "/v1/projects/" + projectId + "/instances";
            String instancesBody = proxyGet(instancesUrl);
            @SuppressWarnings("unchecked")
            Map<String, Object> instancesResp = mapper.readValue(instancesBody, Map.class);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> instances = (List<Map<String, Object>>) instancesResp.getOrDefault("instances", List.of());

            List<String> instanceNames = new ArrayList<>();
            for (Map<String, Object> inst : instances) {
                String name = (String) inst.get("name");
                if (name != null) instanceNames.add(name.substring(name.lastIndexOf('/') + 1));
            }

            if (instanceNames.isEmpty()) {
                return HttpResponse.of(HttpStatus.OK, MediaType.JSON,
                    mapper.writeValueAsString(Map.of("tables", List.of(), "instances", List.of(), "databases", List.of())));
            }

            // Use provided instance or first one
            String selectedInstance = (instance != null && !instance.isBlank()) ? instance : instanceNames.get(0);

            // 2. List databases
            String dbsUrl = spannerBase + "/v1/projects/" + projectId + "/instances/" + selectedInstance + "/databases";
            String dbsBody = proxyGet(dbsUrl);
            @SuppressWarnings("unchecked")
            Map<String, Object> dbsResp = mapper.readValue(dbsBody, Map.class);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> databases = (List<Map<String, Object>>) dbsResp.getOrDefault("databases", List.of());

            List<String> databaseNames = new ArrayList<>();
            for (Map<String, Object> db : databases) {
                String name = (String) db.get("name");
                if (name != null) databaseNames.add(name.substring(name.lastIndexOf('/') + 1));
            }

            if (databaseNames.isEmpty()) {
                return HttpResponse.of(HttpStatus.OK, MediaType.JSON,
                    mapper.writeValueAsString(Map.of("tables", List.of(), "instances", instanceNames, "databases", List.of())));
            }

            // 3. Get DDL for ALL databases — prefix table names with database name
            List<Map<String, Object>> tables = new ArrayList<>();
            for (String dbName : databaseNames) {
                try {
                    String ddlUrl = spannerBase + "/v1/projects/" + projectId + "/instances/" + selectedInstance
                            + "/databases/" + dbName + "/ddl";
                    String ddlBody = proxyGet(ddlUrl);
                    @SuppressWarnings("unchecked")
                    Map<String, Object> ddlResp = mapper.readValue(ddlBody, Map.class);
                    @SuppressWarnings("unchecked")
                    List<String> statements = (List<String>) ddlResp.getOrDefault("statements", List.of());

                    for (String stmt : statements) {
                        if (stmt.trim().toUpperCase().startsWith("CREATE TABLE")) {
                            Map<String, Object> table = parseCreateTable(stmt);
                            if (table != null) {
                                // Prefix with database name so frontend can group by database
                                Map<String, Object> prefixed = new LinkedHashMap<>(table);
                                prefixed.put("name", dbName + "." + table.get("name"));
                                prefixed.put("database", dbName);
                                tables.add(prefixed);
                            }
                        }
                    }
                } catch (Exception e) {
                    logger.debug("Failed to fetch DDL for database {}: {}", dbName, e.getMessage());
                }
            }

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("tables", tables);
            result.put("instances", instanceNames);
            result.put("databases", databaseNames);
            result.put("selectedInstance", selectedInstance);

            return HttpResponse.of(HttpStatus.OK, MediaType.JSON,
                mapper.writerWithDefaultPrettyPrinter().writeValueAsString(result));

        } catch (Exception e) {
            logger.warn("Spanner schema fetch failed: {}", e.getMessage());
            return HttpResponse.of(HttpStatus.OK, MediaType.JSON,
                "{\"tables\": [], \"instances\": [], \"databases\": []}");
        }
    }

    /**
     * Parse a Spanner CREATE TABLE statement into table name and columns.
     * Example: CREATE TABLE Persons (Id STRING(36) NOT NULL, Name STRING(100)) PRIMARY KEY (Id)
     */
    private Map<String, Object> parseCreateTable(String ddl) {
        try {
            // Extract table name
            String upper = ddl.trim();
            int tableIdx = upper.toUpperCase().indexOf("CREATE TABLE");
            if (tableIdx < 0) return null;

            String afterCreate = upper.substring(tableIdx + 12).trim();
            int parenIdx = afterCreate.indexOf('(');
            if (parenIdx < 0) return null;

            String tableName = afterCreate.substring(0, parenIdx).trim();

            // Extract columns — content between first ( and matching )
            // Find the matching closing paren before PRIMARY KEY
            String columnSection = afterCreate.substring(parenIdx + 1);
            int pkIdx = columnSection.toUpperCase().indexOf(") PRIMARY KEY");
            if (pkIdx < 0) {
                // Try just finding the last )
                pkIdx = columnSection.lastIndexOf(')');
                if (pkIdx < 0) return null;
            }
            columnSection = columnSection.substring(0, pkIdx).trim();

            // Split by comma, parse each column
            List<Map<String, String>> columns = new ArrayList<>();
            for (String col : columnSection.split(",")) {
                String trimmed = col.trim();
                if (trimmed.isEmpty()) continue;
                // Skip INTERLEAVE and other non-column definitions
                if (trimmed.toUpperCase().startsWith("INTERLEAVE")) continue;
                if (trimmed.toUpperCase().startsWith("CONSTRAINT")) continue;

                String[] parts = trimmed.split("\\s+", 3);
                if (parts.length >= 2) {
                    columns.add(Map.of("name", parts[0], "type", parts[1]));
                }
            }

            return Map.of("name", tableName, "columns", columns);
        } catch (Exception e) {
            logger.debug("Failed to parse DDL: {}", ddl, e);
            return null;
        }
    }

    // ─── Helpers ───────────────────────────────────────────────────────

    private HttpResponse errorResponse(String message) {
        try {
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("error", message);
            error.put("columns", List.of());
            error.put("rows", List.of());
            error.put("row_count", 0);
            return HttpResponse.of(HttpStatus.OK, MediaType.JSON,
                    mapper.writerWithDefaultPrettyPrinter().writeValueAsString(error));
        } catch (Exception e) {
            return HttpResponse.of(HttpStatus.INTERNAL_SERVER_ERROR, MediaType.PLAIN_TEXT_UTF_8,
                    "Internal error: " + e.getMessage());
        }
    }

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
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        java.net.http.HttpResponse<String> response = httpClient.send(request, BodyHandlers.ofString());
        return response.body();
    }

    private void proxyDelete(String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .DELETE()
                .build();
        httpClient.send(request, BodyHandlers.ofString());
    }
}
