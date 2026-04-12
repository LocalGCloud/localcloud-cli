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
            "bigtable", "compute", "cloudrun", "gke", "memorystore"
    );

    private final LocalCloudConfig config;
    private final PostgresDataSource dataSource;
    private final HttpClient httpClient;
    private final ObjectMapper mapper;

    private final String bigqueryBase;
    private final String spannerBase;

    public QueryService(LocalCloudConfig config, PostgresDataSource dataSource, ServiceRegistry registry) {
        this.config = config;
        this.dataSource = dataSource;
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

            long startTime = System.currentTimeMillis();

            if (POSTGRES_SERVICES.contains(service)) {
                return executePostgresQuery(sql, startTime);
            } else if ("bigquery".equals(service)) {
                return executeBigQueryQuery(sql, projectId, startTime);
            } else if ("spanner".equals(service)) {
                String instance = (String) request.get("instance");
                String database = (String) request.get("database");
                return executeSpannerQuery(sql, projectId, instance, database, startTime);
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
        if (instance == null || instance.isBlank()) {
            return errorResponse("Spanner queries require 'instance' parameter");
        }
        if (database == null || database.isBlank()) {
            return errorResponse("Spanner queries require 'database' parameter");
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

            // 2. Execute SQL
            String sqlUrl = spannerBase + "/v1/" + sessionName + ":executeSql";
            String sqlPayload = mapper.writeValueAsString(Map.of("sql", sql));
            String sqlBody = proxyPost(sqlUrl, sqlPayload);
            @SuppressWarnings("unchecked")
            Map<String, Object> sqlResp = mapper.readValue(sqlBody, Map.class);

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

            if (service == null || !POSTGRES_SERVICES.contains(service)) {
                return HttpResponse.of(HttpStatus.OK, MediaType.JSON,
                        mapper.writeValueAsString(Map.of("tables", List.of())));
            }

            // Query PostgreSQL information_schema for table/column metadata
            List<Map<String, Object>> tables = new ArrayList<>();
            try (Connection conn = dataSource.getConnection();
                 Statement stmt = conn.createStatement()) {

                ResultSet rs = stmt.executeQuery(
                        "SELECT table_name, column_name, data_type " +
                        "FROM information_schema.columns " +
                        "WHERE table_schema = 'public' " +
                        "ORDER BY table_name, ordinal_position");

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
