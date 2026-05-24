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
import java.util.regex.Pattern;

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
 * Registered at the root path prefix.
 */
public class QueryService {

    private static final Logger logger = LoggerFactory.getLogger(QueryService.class);

    /** Services whose data lives in the internal PostgreSQL database. */
    private static final Set<String> POSTGRES_SERVICES = Set.of(
            "secretmanager", "cloudtasks", "logging", "monitoring",
            "bigtable", "compute", "cloudrun", "gke", "memorystore", "workflows",
            "vertexai", "kms", "cloudsql"
    );

    private final LocalCloudConfig config;
    private final PostgresDataSource dataSource;
    private final UsageMetricsRepository usageMetrics;
    private final QueryHistoryRepository queryHistoryRepository;
    private final HttpClient httpClient;
    private final ObjectMapper mapper;

    private final String bigqueryBase;
    private final String spannerBase;
    private final int spannerGrpcPort;
    private final int bigtablePort;

    public QueryService(LocalCloudConfig config, PostgresDataSource dataSource,
                        ServiceRegistry registry, UsageMetricsRepository usageMetrics,
                        QueryHistoryRepository queryHistoryRepository) {
        this.config = config;
        this.dataSource = dataSource;
        this.usageMetrics = usageMetrics;
        this.queryHistoryRepository = queryHistoryRepository;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .version(HttpClient.Version.HTTP_1_1)
                .build();
        this.mapper = new ObjectMapper();

        ServiceDefinition bqDef = registry.getService("bigquery");
        this.bigqueryBase = "http://localhost:" + (bqDef != null ? bqDef.port() : 9050);

        ServiceDefinition spannerDef = registry.getService("spanner");
        int spannerRestPort = spannerDef != null && spannerDef.additionalPorts().containsKey("rest")
                ? spannerDef.additionalPorts().get("rest") : 9020;
        this.spannerBase = "http://localhost:" + spannerRestPort;
        this.spannerGrpcPort = spannerDef != null ? spannerDef.port() : 9010;

        ServiceDefinition bigtableDef = registry.getService("bigtable");
        this.bigtablePort = bigtableDef != null ? bigtableDef.port() : 8087;
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

            if ("bigtable".equals(service)) {
                return executeBigtableQuery(sql, projectId, startTime);
            } else if (POSTGRES_SERVICES.contains(service)) {
                return executePostgresQuery(sql, projectId, startTime);
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

    /**
     * Dry-run a BigQuery SQL query to estimate bytes processed and cost.
     * <p>
     * Request body:
     * <pre>
     * {
     *   "sql": "SELECT * FROM `dataset.table` LIMIT 10"
     * }
     * </pre>
     * Response:
     * <pre>
     * {
     *   "totalBytesProcessed": 1234567,
     *   "estimatedCostUsd": 0.0000,
     *   "valid": true
     * }
     * </pre>
     */
    @Post("/query/dryrun")
    public HttpResponse dryRun(ServiceRequestContext ctx, AggregatedHttpRequest httpRequest) {
        try {
            String body = httpRequest.contentUtf8();
            @SuppressWarnings("unchecked")
            Map<String, Object> request = mapper.readValue(body, Map.class);

            String sql = (String) request.get("sql");
            if (sql == null || sql.isBlank()) {
                return errorResponse("Missing required field: sql");
            }

            String project = ctx.queryParams().get("project");
            String projectId = (project != null && !project.isBlank()) ? project : config.getProjectId();

            String queryUrl = bigqueryBase + "/bigquery/v2/projects/" + projectId + "/queries";
            String queryPayload = mapper.writeValueAsString(Map.of(
                    "query", sql,
                    "useLegacySql", false,
                    "dryRun", true));
            String responseBody = proxyPost(queryUrl, queryPayload);

            @SuppressWarnings("unchecked")
            Map<String, Object> queryResp = mapper.readValue(responseBody, Map.class);

            @SuppressWarnings("unchecked")
            Map<String, Object> error = (Map<String, Object>) queryResp.get("error");
            if (error != null) {
                return HttpResponse.of(HttpStatus.OK, MediaType.JSON,
                        mapper.writeValueAsString(Map.of(
                                "valid", false,
                                "error", String.valueOf(error.get("message")),
                                "totalBytesProcessed", 0L,
                                "estimatedCostUsd", 0.0)));
            }

            long totalBytes = 0L;
            Object bytesObj = queryResp.get("totalBytesProcessed");
            if (bytesObj instanceof Number) {
                totalBytes = ((Number) bytesObj).longValue();
            } else if (bytesObj instanceof String) {
                try { totalBytes = Long.parseLong((String) bytesObj); } catch (NumberFormatException nfe) { totalBytes = 0L; }
            }
            double costPerTb = 5.0;
            double estimatedCost = costPerTb * totalBytes / (1024.0 * 1024.0 * 1024.0 * 1024.0);

            return HttpResponse.of(HttpStatus.OK, MediaType.JSON,
                    mapper.writeValueAsString(Map.of(
                            "valid", true,
                            "totalBytesProcessed", totalBytes,
                            "estimatedCostUsd", estimatedCost)));

        } catch (Exception e) {
            logger.warn("Dry-run failed: {}", e.getMessage());
            return errorResponse("Dry-run failed: " + e.getMessage());
        }
    }

    // ─── Spanner Batch DML (CSV import) ────────────────────────────────

    /**
     * Execute multiple DML statements against Spanner in a single session.
     * Uses one session with per-statement mini-transactions for per-row error tracking.
     * <p>
     * Request body:
     * <pre>
     * {
     *   "service": "spanner",
     *   "statements": ["INSERT OR UPDATE INTO ...", "INSERT OR UPDATE INTO ...", ...],
     *   "instance": "my-instance",
     *   "database": "my-database"
     * }
     * </pre>
     */
    @Post("/query/batch")
    public HttpResponse queryBatch(ServiceRequestContext ctx, AggregatedHttpRequest httpRequest) {
        try {
            String body = httpRequest.contentUtf8();
            @SuppressWarnings("unchecked")
            Map<String, Object> request = mapper.readValue(body, Map.class);

            String service = (String) request.get("service");
            @SuppressWarnings("unchecked")
            List<String> statements = (List<String>) request.get("statements");

            if (!"spanner".equals(service)) {
                return errorResponse("Batch query only supports Spanner");
            }
            if (statements == null || statements.isEmpty()) {
                return errorResponse("Missing required field: statements");
            }

            String project = ctx.queryParams().get("project");
            String projectId = (project != null && !project.isBlank()) ? project : config.getProjectId();
            String instance = (String) request.get("instance");
            String database = (String) request.get("database");

            usageMetrics.incrementCount(projectId, service, statements.size());
            long startTime = System.currentTimeMillis();

            return executeSpannerBatch(statements, projectId, instance, database, startTime);

        } catch (Exception e) {
            logger.error("Batch query failed", e);
            return errorResponse(e.getMessage() != null ? e.getMessage() : "Batch query failed");
        }
    }

    private HttpResponse executeSpannerBatch(List<String> statements, String projectId,
                                              String instance, String database, long startTime) {
        // Auto-resolve instance if not provided (same logic as single query, but done ONCE)
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
                String bestInst = null;
                int bestDbCount = -1;
                for (Map<String, Object> inst : instances) {
                    String instFullName = (String) inst.get("name");
                    String instName = instFullName.substring(instFullName.lastIndexOf('/') + 1);
                    try {
                        String dbsUrl = spannerBase + "/v1/projects/" + projectId + "/instances/" + instName + "/databases";
                        String dbsBody = proxyGet(dbsUrl);
                        @SuppressWarnings("unchecked")
                        Map<String, Object> dbsResp = mapper.readValue(dbsBody, Map.class);
                        @SuppressWarnings("unchecked")
                        List<?> dbs = (List<?>) dbsResp.getOrDefault("databases", List.of());
                        if (dbs.size() > bestDbCount) {
                            bestDbCount = dbs.size();
                            bestInst = instName;
                        }
                    } catch (Exception e) {
                        if (bestInst == null) bestInst = instName;
                    }
                }
                instance = bestInst;
            } catch (Exception e) {
                return errorResponse("Failed to auto-resolve Spanner instance: " + e.getMessage());
            }
        }

        // Auto-resolve database if not provided (done ONCE for all statements)
        if (database == null || database.isBlank()) {
            try {
                String url = spannerBase + "/v1/projects/" + projectId + "/instances/" + instance + "/databases";
                String body = proxyGet(url);
                @SuppressWarnings("unchecked")
                Map<String, Object> resp = mapper.readValue(body, Map.class);
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> databases = (List<Map<String, Object>>) resp.getOrDefault("databases", List.of());
                if (databases.isEmpty()) {
                    return errorResponse("No databases in Spanner instance '" + instance + "'.");
                }
                // Use first table match from the first statement
                String firstSql = statements.get(0).replaceAll("/\\*.*?\\*/", " ").replaceAll("--[^\n]*", " ");
                java.util.regex.Matcher tableMatcher = java.util.regex.Pattern.compile(
                        "(?i)(?:FROM|INTO|UPDATE|JOIN)\\s+([A-Za-z_][A-Za-z0-9_]*)").matcher(firstSql);
                Set<String> referencedTables = new java.util.LinkedHashSet<>();
                while (tableMatcher.find()) {
                    referencedTables.add(tableMatcher.group(1).toUpperCase());
                }
                String bestDb = null;
                int bestMatch = 0;
                for (Map<String, Object> db : databases) {
                    String dbFullName = (String) db.get("name");
                    String dbName = dbFullName.substring(dbFullName.lastIndexOf('/') + 1);
                    try {
                        String ddlUrl = spannerBase + "/v1/projects/" + projectId + "/instances/" + instance
                                + "/databases/" + dbName + "/ddl";
                        String ddlBody = proxyGet(ddlUrl);
                        @SuppressWarnings("unchecked")
                        Map<String, Object> ddlResp = mapper.readValue(ddlBody, Map.class);
                        @SuppressWarnings("unchecked")
                        List<String> ddlStmts = (List<String>) ddlResp.getOrDefault("statements", List.of());
                        Set<String> dbTables = new java.util.HashSet<>();
                        for (String stmt : ddlStmts) {
                            java.util.regex.Matcher cm = java.util.regex.Pattern.compile(
                                    "(?i)CREATE\\s+TABLE\\s+([A-Za-z_][A-Za-z0-9_]*)").matcher(stmt);
                            if (cm.find()) dbTables.add(cm.group(1).toUpperCase());
                        }
                        int matchCount = 0;
                        for (String ref : referencedTables) {
                            if (dbTables.contains(ref)) matchCount++;
                        }
                        if (matchCount > bestMatch) {
                            bestMatch = matchCount;
                            bestDb = dbName;
                        }
                    } catch (Exception ignored) {}
                }
                if (bestDb != null) {
                    database = bestDb;
                } else {
                    String dbFullName = (String) databases.get(0).get("name");
                    database = dbFullName.substring(dbFullName.lastIndexOf('/') + 1);
                }
            } catch (Exception e) {
                return errorResponse("Failed to auto-resolve Spanner database: " + e.getMessage());
            }
        }

        // Direct gRPC to Spanner emulator on port 9010 — bypasses the broken REST gateway.
        // The REST gateway (port 9020) corrupts its gRPC connection after commit operations,
        // returning EOF/code-14 errors and failing to persist data.
        // Per-statement isolation: each row gets its own session+transaction so one bad row
        // doesn't block others.
        String dbPath = "projects/" + projectId + "/instances/" + instance + "/databases/" + database;

        io.grpc.ManagedChannel channel = io.grpc.ManagedChannelBuilder
                .forAddress("localhost", spannerGrpcPort)
                .usePlaintext()
                .build();

        try {
            com.google.spanner.v1.SpannerGrpc.SpannerBlockingStub stub =
                    com.google.spanner.v1.SpannerGrpc.newBlockingStub(channel)
                            .withDeadlineAfter(30, java.util.concurrent.TimeUnit.SECONDS);

            List<Map<String, Object>> results = new ArrayList<>();
            int succeeded = 0, failed = 0;

            for (int i = 0; i < statements.size(); i++) {
                String sql = statements.get(i);
                String sessionName = null;
                try {
                    // 1. Create session
                    com.google.spanner.v1.Session session = stub.createSession(
                            com.google.spanner.v1.CreateSessionRequest.newBuilder()
                                    .setDatabase(dbPath)
                                    .build());
                    sessionName = session.getName();

                    // 2. ExecuteSql with inline transaction begin
                    com.google.spanner.v1.ResultSet execResult = stub.executeSql(
                            com.google.spanner.v1.ExecuteSqlRequest.newBuilder()
                                    .setSession(sessionName)
                                    .setSql(sql)
                                    .setTransaction(com.google.spanner.v1.TransactionSelector.newBuilder()
                                            .setBegin(com.google.spanner.v1.TransactionOptions.newBuilder()
                                                    .setReadWrite(com.google.spanner.v1.TransactionOptions.ReadWrite
                                                            .getDefaultInstance())))
                                    .setSeqno(i + 1)
                                    .build());

                    // 3. Extract transaction ID and commit
                    com.google.protobuf.ByteString txnId = execResult.getMetadata()
                            .getTransaction().getId();
                    stub.commit(com.google.spanner.v1.CommitRequest.newBuilder()
                            .setSession(sessionName)
                            .setTransactionId(txnId)
                            .build());

                    results.add(Map.of("success", true));
                    succeeded++;

                } catch (io.grpc.StatusRuntimeException e) {
                    String errMsg = e.getStatus().getDescription();
                    if (errMsg == null || errMsg.isBlank()) {
                        errMsg = e.getStatus().getCode().name();
                    }
                    // Make error messages more user-friendly
                    if (errMsg.contains("failed to marshal")) {
                        errMsg = "Constraint violation (NOT NULL, duplicate key, or type mismatch)";
                    }
                    results.add(Map.of("success", false, "error", enrichErrorMessage("spanner", errMsg)));
                    failed++;
                    logger.debug("Spanner batch row {} failed: {}", i + 1, errMsg);
                } catch (Exception e) {
                    String errMsg = e.getMessage() != null ? e.getMessage() : "Unknown error";
                    results.add(Map.of("success", false, "error", enrichErrorMessage("spanner", errMsg)));
                    failed++;
                    logger.debug("Spanner batch row {} failed: {}", i + 1, errMsg);
                } finally {
                    // Clean up session
                    if (sessionName != null) {
                        try {
                            stub.deleteSession(com.google.spanner.v1.DeleteSessionRequest.newBuilder()
                                    .setName(sessionName).build());
                        } catch (Exception ignored) {}
                    }
                }
            }

            long elapsed = System.currentTimeMillis() - startTime;
            String batchLabel = "Batch: " + statements.size() + " statements";
            queryHistoryRepository.record(projectId, "spanner", batchLabel, instance, database,
                    elapsed, statements.size(), failed == 0,
                    failed > 0 ? failed + " of " + statements.size() + " failed" : null);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("results", results);
            result.put("succeeded", succeeded);
            result.put("failed", failed);
            result.put("total", statements.size());
            result.put("execution_time_ms", elapsed);
            return HttpResponse.of(HttpStatus.OK, MediaType.JSON,
                    mapper.writerWithDefaultPrettyPrinter().writeValueAsString(result));

        } catch (Exception e) {
            String errMsg = e.getMessage() != null ? e.getMessage() : "Batch execution failed";
            queryHistoryRepository.record(projectId, "spanner",
                    "Batch: " + statements.size() + " statements", instance, database,
                    System.currentTimeMillis() - startTime, 0, false, errMsg);
            logger.error("Spanner batch failed: {}", errMsg, e);
            return errorResponse(enrichErrorMessage("spanner", errMsg));
        } finally {
            channel.shutdownNow();
        }
    }

    // ─── PostgreSQL Direct Query ───────────────────────────────────────

    private HttpResponse executePostgresQuery(String sql, String projectId, long startTime) {
        // Safety: only allow SELECT and EXPLAIN
        String trimmed = sql.trim().toUpperCase();
        if (!trimmed.startsWith("SELECT") && !trimmed.startsWith("EXPLAIN") && !trimmed.startsWith("WITH")) {
            return errorResponse("Only SELECT, EXPLAIN, and WITH (CTE) queries are allowed");
        }

        try (Connection conn = dataSource.getConnection()) {
            // Use read-only transaction to prevent DML inside CTEs
            // (e.g., "WITH d AS (DELETE FROM t RETURNING *) SELECT * FROM d")
            conn.setAutoCommit(false);
            conn.setReadOnly(true);

            try (Statement stmt = conn.createStatement()) {

            // Set a query timeout to prevent runaway queries
            stmt.setQueryTimeout(30);

            // Set project context as a session variable so queries can be filtered.
            stmt.execute("SET localcloud.project_id = " + quoteStringLiteral(projectId));

            // Auto-inject project_id filter for tables that have the column.
            String filteredSql = wrapWithProjectFilter(sql, projectId);

            ResultSet rs = stmt.executeQuery(filteredSql);
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

            } finally {
                conn.rollback();
                conn.setReadOnly(false);
                conn.setAutoCommit(true);
            }
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
                return errorResponse(enrichErrorMessage("bigquery", String.valueOf(error.get("message"))));
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
            return errorResponse(enrichErrorMessage("bigquery", e.getMessage()));
        }
    }

    // ─── Spanner Proxy Query ───────────────────────────────────────────

    private HttpResponse executeSpannerQuery(String sql, String projectId,
                                              String instance, String database, long startTime) {
        // Auto-resolve instance if not provided — pick instance with most databases
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
                // Pick instance with the most databases (likely has actual data)
                String bestInst = null;
                int bestDbCount = -1;
                for (Map<String, Object> inst : instances) {
                    String instFullName = (String) inst.get("name");
                    String instName = instFullName.substring(instFullName.lastIndexOf('/') + 1);
                    try {
                        String dbsUrl = spannerBase + "/v1/projects/" + projectId + "/instances/" + instName + "/databases";
                        String dbsBody = proxyGet(dbsUrl);
                        @SuppressWarnings("unchecked")
                        Map<String, Object> dbsResp = mapper.readValue(dbsBody, Map.class);
                        @SuppressWarnings("unchecked")
                        List<?> dbs = (List<?>) dbsResp.getOrDefault("databases", List.of());
                        if (dbs.size() > bestDbCount) {
                            bestDbCount = dbs.size();
                            bestInst = instName;
                        }
                    } catch (Exception e) {
                        if (bestInst == null) bestInst = instName;
                    }
                }
                instance = bestInst;
                logger.info("Auto-resolved Spanner instance: {} ({} databases)", instance, bestDbCount);
            } catch (Exception e) {
                return errorResponse("Failed to auto-resolve Spanner instance: " + e.getMessage());
            }
        }

        // Auto-resolve database if not provided — find the database that contains the queried table
        boolean databaseAutoResolved = false;
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

                // Extract table names from SQL (FROM/INTO/UPDATE/JOIN clauses)
                // Strip comments and hints first for cleaner parsing
                String sqlForParsing = sql.replaceAll("/\\*.*?\\*/", " ").replaceAll("--[^\n]*", " ");
                Set<String> referencedTables = new java.util.LinkedHashSet<>();
                java.util.regex.Matcher tableMatcher = java.util.regex.Pattern.compile(
                        "(?i)(?:FROM|INTO|UPDATE|JOIN)\\s+([A-Za-z_][A-Za-z0-9_]*)").matcher(sqlForParsing);
                while (tableMatcher.find()) {
                    referencedTables.add(tableMatcher.group(1).toUpperCase());
                }

                // Try to find the database that contains the referenced tables
                if (!referencedTables.isEmpty()) {
                    String bestDb = null;
                    int bestMatchCount = 0;
                    for (Map<String, Object> db : databases) {
                        String dbFullName = (String) db.get("name");
                        String dbName = dbFullName.substring(dbFullName.lastIndexOf('/') + 1);
                        try {
                            String ddlUrl = spannerBase + "/v1/projects/" + projectId + "/instances/" + instance
                                    + "/databases/" + dbName + "/ddl";
                            String ddlBody = proxyGet(ddlUrl);
                            @SuppressWarnings("unchecked")
                            Map<String, Object> ddlResp = mapper.readValue(ddlBody, Map.class);
                            @SuppressWarnings("unchecked")
                            List<String> statements = (List<String>) ddlResp.getOrDefault("statements", List.of());
                            // Extract table names from DDL
                            Set<String> dbTables = new java.util.HashSet<>();
                            for (String stmt : statements) {
                                java.util.regex.Matcher createMatcher = java.util.regex.Pattern.compile(
                                        "(?i)CREATE\\s+TABLE\\s+([A-Za-z_][A-Za-z0-9_]*)").matcher(stmt);
                                if (createMatcher.find()) {
                                    dbTables.add(createMatcher.group(1).toUpperCase());
                                }
                            }
                            // Count how many referenced tables exist in this database
                            int matchCount = 0;
                            for (String refTable : referencedTables) {
                                if (dbTables.contains(refTable)) matchCount++;
                            }
                            if (matchCount > bestMatchCount) {
                                bestMatchCount = matchCount;
                                bestDb = dbName;
                            }
                        } catch (Exception e) {
                            logger.debug("Failed to fetch DDL for database {}: {}", dbName, e.getMessage());
                        }
                    }

                    if (bestDb != null && bestMatchCount > 0) {
                        database = bestDb;
                        databaseAutoResolved = true;
                        logger.info("Auto-resolved Spanner database by table match: {}/{} ({}/{} tables matched)",
                                instance, database, bestMatchCount, referencedTables.size());
                    }
                }

                // Fallback: pick database with most tables if no table match found
                if (database == null || database.isBlank()) {
                    String fallbackDb = null;
                    int mostTables = -1;
                    for (Map<String, Object> db : databases) {
                        String dbFullName = (String) db.get("name");
                        String dbName = dbFullName.substring(dbFullName.lastIndexOf('/') + 1);
                        try {
                            String ddlUrl = spannerBase + "/v1/projects/" + projectId + "/instances/" + instance
                                    + "/databases/" + dbName + "/ddl";
                            String ddlBody = proxyGet(ddlUrl);
                            @SuppressWarnings("unchecked")
                            Map<String, Object> ddlResp = mapper.readValue(ddlBody, Map.class);
                            int tableCount = ((List<?>) ddlResp.getOrDefault("statements", List.of())).size();
                            if (tableCount > mostTables) {
                                mostTables = tableCount;
                                fallbackDb = dbName;
                            }
                        } catch (Exception e) {
                            if (fallbackDb == null) fallbackDb = dbName;
                        }
                    }
                    database = fallbackDb != null ? fallbackDb : ((String) databases.get(0).get("name"))
                            .substring(((String) databases.get(0).get("name")).lastIndexOf('/') + 1);
                    databaseAutoResolved = true;
                    logger.info("Auto-resolved Spanner database (fallback, most tables): {}/{}", instance, database);
                }
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
                // Use word-boundary regex to avoid corrupting string literals or partial matches
                String dbPattern = "\\b" + Pattern.quote(dbName) + "\\.";
                if (sql.matches("(?s).*" + dbPattern + ".*")) {
                    // Skip rewrite if the match is inside a quoted string
                    int matchPos = sql.indexOf(dbName + ".");
                    boolean insideQuote = false;
                    for (int i = 0; i < matchPos && i < sql.length(); i++) {
                        if (sql.charAt(i) == '\'') insideQuote = !insideQuote;
                    }
                    if (!insideQuote) {
                        database = dbName;
                        sql = sql.replaceAll(dbPattern, "");
                        logger.info("Spanner SQL rewrite: stripped '{}.' prefix, using database '{}'", dbName, database);
                        break;
                    }
                }
            }
        } catch (Exception e) {
            logger.debug("Failed to check database prefixes in SQL: {}", e.getMessage());
        }

        // Strip statement-level optimizer hints (@{OPTIMIZER_VERSION=...}, @{USE_ADDITIONAL_PARALLELISM=...}, etc.)
        // The emulator doesn't support these and crashes with ZETASQL_RET_CHECK failure.
        // Table-level hints like Table@{FORCE_INDEX=idx} are supported and left intact.
        java.util.regex.Matcher hintMatcher = java.util.regex.Pattern.compile(
                "^\\s*@\\{[^}]+\\}\\s*", java.util.regex.Pattern.DOTALL).matcher(sql);
        if (hintMatcher.find()) {
            String stripped = hintMatcher.group().trim();
            sql = sql.substring(hintMatcher.end());
            logger.info("Stripped Spanner statement-level hint: {}", stripped);
        }

        // DDL statements (CREATE TABLE, DROP TABLE, ALTER TABLE, CREATE INDEX, DROP INDEX)
        // must go through the DDL update API, not executeSql
        String trimmedUpper = sql.trim().toUpperCase();
        if (trimmedUpper.startsWith("CREATE ") || trimmedUpper.startsWith("DROP ") || trimmedUpper.startsWith("ALTER ")) {
            return executeSpannerDdl(sql, projectId, instance, database, startTime);
        }

        // Rewrite TRUNCATE TABLE → DELETE FROM ... WHERE TRUE (Spanner doesn't support TRUNCATE)
        if (trimmedUpper.startsWith("TRUNCATE ")) {
            java.util.regex.Matcher truncMatch = java.util.regex.Pattern.compile(
                    "(?i)TRUNCATE\\s+TABLE\\s+([\\w.`]+)", java.util.regex.Pattern.CASE_INSENSITIVE)
                    .matcher(sql.trim());
            if (truncMatch.find()) {
                sql = "DELETE FROM " + truncMatch.group(1) + " WHERE TRUE";
                trimmedUpper = sql.trim().toUpperCase();
                logger.info("Rewrote TRUNCATE to: {}", sql);
            }
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

            // 2. Execute SQL — use read-write transaction for DML (INSERT, UPDATE, DELETE)
            boolean isDml = trimmedUpper.startsWith("INSERT ") || trimmedUpper.startsWith("UPDATE ") || trimmedUpper.startsWith("DELETE ");
            String sqlUrl = spannerBase + "/v1/" + sessionName + ":executeSql";
            Map<String, Object> sqlPayloadMap = new LinkedHashMap<>();
            sqlPayloadMap.put("sql", sql);
            if (isDml) {
                Map<String, Object> txn = new LinkedHashMap<>();
                txn.put("readWrite", Map.of());
                Map<String, Object> txnSelector = new LinkedHashMap<>();
                txnSelector.put("begin", txn);
                sqlPayloadMap.put("transaction", txnSelector);
            }
            String sqlPayload = mapper.writeValueAsString(sqlPayloadMap);
            String sqlBody = proxyPost(sqlUrl, sqlPayload);
            @SuppressWarnings("unchecked")
            Map<String, Object> sqlResp = mapper.readValue(sqlBody, Map.class);

            // Check for Spanner error response (e.g., {"code":5, "message":"Not Found"})
            if (sqlResp.containsKey("code")) {
                int code = ((Number) sqlResp.get("code")).intValue();
                String message = sqlResp.containsKey("message") ? String.valueOf(sqlResp.get("message")) : "Unknown error";
                if (code != 0) {
                    String codeName = switch (code) {
                        case 1 -> "CANCELLED";
                        case 2 -> "UNKNOWN";
                        case 3 -> "INVALID_ARGUMENT";
                        case 4 -> "DEADLINE_EXCEEDED";
                        case 5 -> "NOT_FOUND";
                        case 6 -> "ALREADY_EXISTS";
                        case 7 -> "PERMISSION_DENIED";
                        case 9 -> "FAILED_PRECONDITION";
                        case 10 -> "ABORTED";
                        case 13 -> "INTERNAL";
                        case 14 -> "UNAVAILABLE";
                        default -> "ERROR_" + code;
                    };

                    String errorMsg;
                    if (code == 5 || message.contains("Not Found") || message.contains("not found")) {
                        // Extract table name from SQL for a helpful error message
                        String tableName = sql.replaceAll("(?i).*?(?:FROM|INTO)\\s+(\\S+).*", "$1").trim();
                        tableName = tableName.replaceAll("[(`@\\{\\s].*", "");  // Also strip @{hint} syntax
                        errorMsg = "Table '" + tableName + "' not found in database '" + database + "'";
                    } else if (code == 13 && message.contains("marshal") && message.contains("not found")) {
                        String tableName = sql.replaceAll("(?i).*?(?:FROM|INTO)\\s+(\\S+).*", "$1").trim();
                        tableName = tableName.replaceAll("[(`@\\{\\s].*", "");
                        errorMsg = "Table '" + tableName + "' does not exist in database '" + database + "'. "
                                + "Check that the table name and database are correct.";
                    } else {
                        // Enrich the error message with code name and pass through enrichErrorMessage
                        errorMsg = enrichErrorMessage("spanner", message);
                        // Prepend code name if enrichment didn't already add context
                        if (errorMsg.equals(message)) {
                            errorMsg = "Spanner " + codeName + ": " + message;
                        }
                    }

                    // Also include details array if present (often contains root cause)
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> details = (List<Map<String, Object>>) sqlResp.get("details");
                    if (details != null && !details.isEmpty()) {
                        StringBuilder detailStr = new StringBuilder();
                        for (Map<String, Object> detail : details) {
                            if (detail.containsKey("message")) {
                                detailStr.append("\n  - ").append(detail.get("message"));
                            }
                        }
                        if (!detailStr.isEmpty()) {
                            errorMsg += detailStr;
                        }
                    }

                    return errorResponse(errorMsg);
                }
            }

            // Commit DML transaction if applicable
            if (isDml) {
                @SuppressWarnings("unchecked")
                Map<String, Object> txnMeta = (Map<String, Object>) sqlResp.get("metadata");
                String transactionId = null;
                if (txnMeta != null) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> txnObj = (Map<String, Object>) txnMeta.get("transaction");
                    if (txnObj != null) transactionId = (String) txnObj.get("id");
                }
                // Also check top-level transaction field
                if (transactionId == null) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> txnTop = (Map<String, Object>) sqlResp.get("transaction");
                    if (txnTop != null) transactionId = (String) txnTop.get("id");
                }
                if (transactionId != null) {
                    String commitUrl = spannerBase + "/v1/" + sessionName + ":commit";
                    proxyPost(commitUrl, mapper.writeValueAsString(Map.of("transactionId", transactionId, "mutations", List.of())));
                } else {
                    logger.warn("No transaction ID returned for DML statement — data may not be committed. SQL: {}",
                            sql.length() > 100 ? sql.substring(0, 100) + "..." : sql);
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
            queryHistoryRepository.record(projectId, "spanner", sql, instance, database,
                    elapsed, rows.size(), true, null);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("columns", columns);
            result.put("rows", rows);
            result.put("row_count", rows.size());
            result.put("execution_time_ms", elapsed);
            if (databaseAutoResolved) {
                result.put("note", "Query executed on auto-selected database: " + database
                        + " (instance: " + instance + ")");
            }

            return HttpResponse.of(HttpStatus.OK, MediaType.JSON,
                    mapper.writerWithDefaultPrettyPrinter().writeValueAsString(result));

        } catch (Exception e) {
            queryHistoryRepository.record(projectId, "spanner", sql, instance, database,
                    System.currentTimeMillis() - startTime, 0, false, e.getMessage());
            logger.warn("Spanner query failed: {}", e.getMessage());
            return errorResponse(enrichErrorMessage("spanner", e.getMessage()));
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
     * Execute one or more Spanner DDL statements (CREATE TABLE, DROP TABLE, ALTER TABLE, CREATE INDEX, etc.)
     * via the database DDL update API (PATCH).
     * Supports multi-statement input separated by semicolons.
     */
    private HttpResponse executeSpannerDdl(String sql, String projectId,
                                            String instance, String database, long startTime) {
        try {
            // Split multi-statement DDL by semicolons at top-level (respecting parens)
            List<String> statements = splitDdlStatements(sql);
            if (statements.isEmpty()) {
                return errorResponse("No valid DDL statements found");
            }

            String url = spannerBase + "/v1/projects/" + projectId + "/instances/" + instance
                    + "/databases/" + database + "/ddl";
            String payload = mapper.writeValueAsString(Map.of("statements", statements));

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
                        return errorResponse(MutateService.extractDdlError(String.valueOf(err.getOrDefault("message", errorBody))));
                    }
                    // Try flat {"code":N,"message":"..."}
                    if (errorResp.containsKey("message")) {
                        return errorResponse(MutateService.extractDdlError(String.valueOf(errorResp.get("message"))));
                    }
                } catch (Exception ignored) {}
                return errorResponse(MutateService.extractDdlError(errorBody));
            }

            String msg = statements.size() == 1
                    ? "DDL statement executed successfully"
                    : statements.size() + " DDL statements executed successfully";
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("columns", List.of("result"));
            result.put("rows", List.of(List.of(msg)));
            result.put("row_count", 1);
            result.put("execution_time_ms", elapsed);

            return HttpResponse.of(HttpStatus.OK, MediaType.JSON,
                    mapper.writerWithDefaultPrettyPrinter().writeValueAsString(result));

        } catch (Exception e) {
            logger.warn("Spanner DDL failed: {}", e.getMessage());
            return errorResponse("Spanner DDL failed: " + e.getMessage());
        }
    }

    /**
     * Split DDL input by semicolons at paren depth 0.
     * Strips trailing semicolons, skips empty statements.
     */
    private List<String> splitDdlStatements(String sql) {
        List<String> statements = new ArrayList<>();
        int depth = 0;
        int start = 0;
        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);
            if (c == '(') depth++;
            else if (c == ')') depth--;
            else if (c == ';' && depth == 0) {
                String stmt = sql.substring(start, i).trim();
                if (!stmt.isEmpty()) statements.add(stmt);
                start = i + 1;
            }
        }
        // Last statement (no trailing semicolon)
        String last = sql.substring(start).trim();
        if (!last.isEmpty()) statements.add(last);
        return statements;
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

    // ─── Bigtable Query (translate SQL to gRPC ReadRows) ──────────────

    private HttpResponse executeBigtableQuery(String sql, String projectId, long startTime) {
        try (BigtableGrpcClient client = new BigtableGrpcClient(bigtablePort)) {
            // Tokenize → Parse → Execute
            var tokens = new com.localcloud.admin.bigtablesql.SqlTokenizer(sql).tokenize();
            var ast = new com.localcloud.admin.bigtablesql.SqlParser(tokens).parseStatement();
            var executor = new com.localcloud.admin.bigtablesql.BigtableSqlExecutor(client, projectId);
            var result = executor.execute(ast);

            long elapsed = System.currentTimeMillis() - startTime;

            // Convert to frontend-expected format:
            // columns: array of strings, rows: array of arrays, snake_case keys
            List<String> colNames = result.columns().stream()
                    .map(c -> c.get("name"))
                    .toList();
            List<List<Object>> rowArrays = result.rows().stream()
                    .map(row -> colNames.stream()
                            .map(col -> row.get(col))
                            .collect(java.util.stream.Collectors.toList()))
                    .toList();

            return HttpResponse.of(HttpStatus.OK, MediaType.JSON,
                    mapper.writeValueAsString(Map.of(
                            "columns", colNames,
                            "rows", rowArrays,
                            "row_count", result.rowCount(),
                            "execution_time_ms", elapsed)));
        } catch (com.localcloud.admin.bigtablesql.BigtableSqlException e) {
            return errorResponse("SQL error: " + e.getMessage());
        } catch (Exception e) {
            logger.warn("Bigtable query failed: {}", e.getMessage());
            return errorResponse("Bigtable query failed: " + e.getMessage());
        }
    }

    // ─── Bigtable Schema (proxy to emulator gRPC) ─────────────────────

    @SuppressWarnings("unchecked")
    private HttpResponse schemaBigtable(String projectId) {
        try (BigtableGrpcClient client = new BigtableGrpcClient(bigtablePort)) {
            var instances = client.listInstancesWithDetails(projectId);
            List<Map<String, Object>> tables = new ArrayList<>();
            for (var inst : instances) {
                String instanceId = (String) inst.get("id");
                List<Map<String, Object>> instTables = (List<Map<String, Object>>) inst.get("tables");
                if (instTables != null) {
                    for (var tbl : instTables) {
                        String tableName = instanceId + "." + tbl.get("id");
                        List<String> cfs = (List<String>) tbl.get("columnFamilies");
                        List<Map<String, String>> columns = new ArrayList<>();
                        if (cfs != null) {
                            for (String cf : cfs) {
                                columns.add(Map.of("name", cf, "type", "COLUMN_FAMILY"));
                            }
                        }
                        tables.add(Map.of("name", tableName, "columns", columns));
                    }
                }
            }
            return HttpResponse.of(HttpStatus.OK, MediaType.JSON,
                    mapper.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of("tables", tables)));
        } catch (Exception e) {
            logger.warn("Bigtable schema fetch failed: {}", e.getMessage());
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
     * Return query execution history for the SQL Editor.
     * Supports optional service filter, pagination via limit/offset params.
     */
    @Get("/query-history")
    public HttpResponse queryHistory(ServiceRequestContext ctx) {
        try {
            String project = ctx.queryParams().get("project");
            String projectId = (project != null && !project.isBlank()) ? project : config.getProjectId();
            String service = ctx.queryParams().get("service");
            int limit = Math.min(500, Math.max(1, parseIntOrDefault(ctx.queryParams().get("limit"), 50)));
            int offset = Math.max(0, parseIntOrDefault(ctx.queryParams().get("offset"), 0));

            List<Map<String, Object>> entries = queryHistoryRepository.list(projectId, service, limit, offset);

            int total = queryHistoryRepository.count(projectId, service);
            boolean hasMore = (offset + limit) < total;
            return HttpResponse.of(HttpStatus.OK, MediaType.JSON,
                    mapper.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of(
                            "entries", entries, "total", total, "has_more", hasMore)));
        } catch (Exception e) {
            logger.warn("Failed to list query history: {}", e.getMessage());
            try {
                return HttpResponse.of(HttpStatus.INTERNAL_SERVER_ERROR, MediaType.JSON,
                        mapper.writeValueAsString(Map.of("error", "Failed to list query history: " + e.getMessage())));
            } catch (Exception je) {
                return HttpResponse.of(HttpStatus.INTERNAL_SERVER_ERROR, MediaType.PLAIN_TEXT_UTF_8,
                        "Failed to list query history");
            }
        }
    }

    private int parseIntOrDefault(String value, int defaultVal) {
        if (value == null || value.isBlank()) return defaultVal;
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return defaultVal;
        }
    }

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

            if ("bigtable".equals(service)) {
                return schemaBigtable(projectId);
            }

            if (service == null || !POSTGRES_SERVICES.contains(service)) {
                return HttpResponse.of(HttpStatus.OK, MediaType.JSON,
                        mapper.writeValueAsString(Map.of("tables", List.of())));
            }

            // Filter tables by service — only expose services that support SQL queries.
            // Services like secretmanager, cloudtasks, logging, monitoring use facade APIs
            // and their PostgreSQL tables are internal storage — not user-facing.
            Map<String, List<String>> serviceTables = Map.ofEntries(
                Map.entry("secretmanager", List.of()),
                Map.entry("cloudtasks", List.of()),
                Map.entry("logging", List.of()),
                Map.entry("monitoring", List.of()),
                Map.entry("bigtable", List.of()),
                Map.entry("compute", List.of()),
                Map.entry("cloudrun", List.of()),
                Map.entry("gke", List.of()),
                Map.entry("memorystore", List.of()),
                Map.entry("workflows", List.of("workflows", "workflow_executions", "workflow_step_entries"))
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

                    // Get table schema and metadata
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
                                            "type", (String) field.getOrDefault("type", "STRING"),
                                            "mode", (String) field.getOrDefault("mode", "NULLABLE")
                                    ));
                                }
                            }
                        }

                        // Extract table metadata
                        String tableType = (String) schemaResp.getOrDefault("type", "TABLE");
                        String description = (String) schemaResp.get("description");
                        String creationTime = (String) schemaResp.get("creationTime");
                        String lastModifiedTime = (String) schemaResp.get("lastModifiedTime");
                        @SuppressWarnings("unchecked")
                        Map<String, Object> tableLabels = (Map<String, Object>) schemaResp.get("labels");

                        // Row and byte counts
                        long numRows = 0;
                        long numBytes = 0;
                        Object numRowsObj = schemaResp.get("numRows");
                        Object numBytesObj = schemaResp.get("numBytes");
                        if (numRowsObj != null) numRows = ((Number) numRowsObj).longValue();
                        if (numBytesObj != null) numBytes = ((Number) numBytesObj).longValue();

                        // Partitioning info
                        @SuppressWarnings("unchecked")
                        Map<String, Object> timePartitioning = (Map<String, Object>) schemaResp.get("timePartitioning");
                        @SuppressWarnings("unchecked")
                        List<String> clustering = (List<String>) schemaResp.get("clustering");

                        Map<String, Object> tableMeta = new LinkedHashMap<>();
                        tableMeta.put("name", datasetId + "." + tableId);
                        tableMeta.put("columns", columns);
                        tableMeta.put("type", tableType);
                        tableMeta.put("numRows", numRows);
                        tableMeta.put("numBytes", numBytes);
                        tableMeta.put("description", description != null ? description : "");
                        tableMeta.put("creationTime", creationTime != null ? creationTime : "");
                        tableMeta.put("lastModifiedTime", lastModifiedTime != null ? lastModifiedTime : "");
                        tableMeta.put("labels", tableLabels != null ? tableLabels : Map.of());
                        if (timePartitioning != null) {
                            tableMeta.put("timePartitioning", timePartitioning);
                        }
                        if (clustering != null && !clustering.isEmpty()) {
                            tableMeta.put("clustering", clustering);
                        }
                        allTables.add(tableMeta);
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
            // 1. List all instances
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

            // If a specific instance is requested, scan only that one.
            // Otherwise, scan ALL instances and aggregate all tables.
            List<String> instancesToScan;
            if (instance != null && !instance.isBlank()) {
                instancesToScan = List.of(instance);
            } else {
                instancesToScan = instanceNames;
            }

            // 2. Scan instances, aggregate ALL tables across all instances
            List<Map<String, Object>> allTables = new ArrayList<>();
            List<String> allDatabaseNames = new ArrayList<>();
            // Track which instance has most tables for auto-selecting in query execution
            String bestInstance = instancesToScan.get(0);
            int bestTableCount = 0;

            for (String instName : instancesToScan) {
                try {
                    String dbsUrl = spannerBase + "/v1/projects/" + projectId + "/instances/" + instName + "/databases";
                    String dbsBody = proxyGet(dbsUrl);
                    @SuppressWarnings("unchecked")
                    Map<String, Object> dbsResp = mapper.readValue(dbsBody, Map.class);
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> dbs = (List<Map<String, Object>>) dbsResp.getOrDefault("databases", List.of());

                    int instanceTableCount = 0;
                    for (Map<String, Object> db : dbs) {
                        String fullDbName = (String) db.get("name");
                        if (fullDbName == null) continue;
                        String dbName = fullDbName.substring(fullDbName.lastIndexOf('/') + 1);
                        allDatabaseNames.add(dbName);

                        try {
                            String ddlUrl = spannerBase + "/v1/projects/" + projectId + "/instances/" + instName
                                    + "/databases/" + dbName + "/ddl";
                            String ddlBody = proxyGet(ddlUrl);
                            @SuppressWarnings("unchecked")
                            Map<String, Object> ddlResp = mapper.readValue(ddlBody, Map.class);
                            @SuppressWarnings("unchecked")
                            List<String> statements = (List<String>) ddlResp.getOrDefault("statements", List.of());

                            for (String stmt : statements) {
                                if (stmt.trim().toUpperCase().startsWith("CREATE TABLE")) {
                                    Map<String, Object> table = SpannerDdlParser.parse(stmt);
                                    if (table == null) {
                                        table = parseCreateTable(stmt);
                                    }
                                    if (table != null) {
                                        Map<String, Object> prefixed = new LinkedHashMap<>(table);
                                        // Include instance in the name: instance/database.TableName
                                        prefixed.put("name", dbName + "." + table.get("name"));
                                        prefixed.put("database", dbName);
                                        prefixed.put("instance", instName);
                                        allTables.add(prefixed);
                                        instanceTableCount++;
                                    }
                                }
                            }
                        } catch (Exception e) {
                            logger.debug("Failed to fetch DDL for {}/{}: {}", instName, dbName, e.getMessage());
                        }
                    }

                    if (instanceTableCount > bestTableCount) {
                        bestTableCount = instanceTableCount;
                        bestInstance = instName;
                    }
                } catch (Exception e) {
                    logger.debug("Failed to list databases for instance {}: {}", instName, e.getMessage());
                }
            }

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("tables", allTables);
            result.put("instances", instanceNames);
            result.put("databases", allDatabaseNames);
            result.put("selectedInstance", bestInstance);

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
     * Uses paren-depth-aware splitting to handle generated columns, COALESCE,
     * CASE expressions, TOKENIZE_* functions, and other nested constructs.
     */
    private Map<String, Object> parseCreateTable(String ddl) {
        try {
            // Extract table name
            String trimmed = ddl.trim();
            int tableIdx = trimmed.toUpperCase().indexOf("CREATE TABLE");
            if (tableIdx < 0) return null;

            String afterCreate = trimmed.substring(tableIdx + 12).trim();
            int firstParen = afterCreate.indexOf('(');
            if (firstParen < 0) return null;

            String tableName = afterCreate.substring(0, firstParen).trim();

            // Find the matching closing paren at depth 0 — this is the end of the column list.
            // Cannot use indexOf(") PRIMARY KEY") because nested parens produce false matches.
            int depth = 0;
            int columnEnd = -1;
            for (int i = firstParen; i < afterCreate.length(); i++) {
                char c = afterCreate.charAt(i);
                if (c == '(') depth++;
                else if (c == ')') {
                    depth--;
                    if (depth == 0) {
                        columnEnd = i;
                        break;
                    }
                }
            }
            if (columnEnd < 0) return null;

            String columnSection = afterCreate.substring(firstParen + 1, columnEnd).trim();

            // Split by commas at depth 0 only (top-level commas)
            List<String> columnDefs = splitAtTopLevelCommas(columnSection);

            List<Map<String, String>> columns = new ArrayList<>();
            for (String colDef : columnDefs) {
                String col = colDef.trim();
                if (col.isEmpty()) continue;
                String colUpper = col.toUpperCase();

                // Skip non-column definitions
                if (colUpper.startsWith("INTERLEAVE")) continue;
                if (colUpper.startsWith("CONSTRAINT")) continue;

                // Extract column name and type
                String[] tokens = col.split("\\s+", 3);
                if (tokens.length < 2) continue;

                String colName = tokens[0];
                String colType = tokens[1];

                // Skip TOKENLIST columns (full-text search, not real data)
                if (colType.equalsIgnoreCase("TOKENLIST")) continue;

                // Skip HIDDEN columns (typically TOKENLIST-related)
                if (colUpper.contains(" HIDDEN")) continue;

                // For generated columns (AS ...), mark the type from the base type or STORED expression
                // e.g. "Name STRING(MAX) AS (COALESCE(...)) STORED" → type is STRING(MAX)
                // The type token may include a paren like STRING(MAX) — keep it as-is
                columns.add(Map.of("name", colName, "type", colType));
            }

            return Map.of("name", tableName, "columns", columns);
        } catch (Exception e) {
            logger.debug("Failed to parse DDL: {}", ddl, e);
            return null;
        }
    }

    /**
     * Split a string by commas, but only at parenthesis depth 0.
     * Handles nested parens in generated columns, function calls, CASE expressions, etc.
     */
    private List<String> splitAtTopLevelCommas(String s) {
        List<String> parts = new ArrayList<>();
        int depth = 0;
        int start = 0;
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

    // ─── Helpers ───────────────────────────────────────────────────────

    /**
     * Auto-inject project_id filter into SELECT queries for tables that have a project_id column.
     * Adds "WHERE project_id = '...'" or "AND project_id = '...'" as appropriate.
     * Skips if the query already references project_id.
     */
    private String wrapWithProjectFilter(String sql, String projectId) {
        String upper = sql.trim().toUpperCase();

        // Don't filter EXPLAIN queries
        if (upper.startsWith("EXPLAIN")) {
            return sql;
        }

        // Don't filter CTEs or subqueries — too complex to inject safely
        if (upper.startsWith("WITH")) {
            return sql;
        }

        // If user already has a project_id filter in a WHERE clause, don't double-filter
        if (upper.matches("(?s).*\\bWHERE\\b.*\\bPROJECT_ID\\b.*")) {
            return sql;
        }

        // Known tables with project_id column
        String[] tablesWithProjectId = {
            "secrets", "secret_versions", "task_queues", "cloud_tasks",
            "log_entries", "time_series", "metric_points", "bigtable_data",
            "compute_instances", "cloudrun_services", "cloudrun_revisions",
            "gke_clusters", "redis_data", "workflows", "workflow_executions",
            "workflow_step_entries", "usage_metrics", "projects", "gcs_bucket_projects"
        };

        // Check if query references any of these tables (word-boundary match to avoid
        // false positives like "custom_tasks" matching "tasks")
        boolean referencesProjectTable = false;
        for (String table : tablesWithProjectId) {
            String pattern = "\\b" + table.toUpperCase() + "\\b";
            if (java.util.regex.Pattern.compile(pattern).matcher(upper).find()) {
                referencesProjectTable = true;
                break;
            }
        }

        if (!referencesProjectTable) {
            return sql;
        }

        // Strip trailing semicolons
        String cleaned = sql.replaceAll(";\\s*$", "").trim();
        String cleanedUpper = cleaned.toUpperCase();

        // Inject project filter before ORDER BY, GROUP BY, LIMIT, or at the end
        String projectFilter = "project_id = " + quoteStringLiteral(projectId);
        String connector = cleanedUpper.contains("WHERE") ? " AND " : " WHERE ";

        // Find insertion point — before ORDER BY, GROUP BY, HAVING, LIMIT, OFFSET
        String[] clauses = {"ORDER BY", "GROUP BY", "HAVING", "LIMIT", "OFFSET"};
        int insertPos = cleaned.length();
        for (String clause : clauses) {
            int pos = cleanedUpper.lastIndexOf(clause);
            if (pos > 0 && pos < insertPos) {
                insertPos = pos;
            }
        }

        return cleaned.substring(0, insertPos).trim() + connector + projectFilter + " " + cleaned.substring(insertPos);
    }

    /** Quote a string literal for PostgreSQL, preventing SQL injection. */
    private String quoteStringLiteral(String value) {
        return "'" + value.replace("'", "''") + "'";
    }

    /**
     * Enrich raw emulator error messages with user-friendly explanations.
     */
    private String enrichErrorMessage(String service, String rawError) {
        if (rawError == null) return "Unknown error";

        if ("bigquery".equals(service)) {
            // DuckDB: "Catalog Error: Scalar Function with name X does not exist"
            java.util.regex.Matcher fnMatch = java.util.regex.Pattern.compile(
                    "(?i)Scalar Function with name (\\w+) does not exist").matcher(rawError);
            if (fnMatch.find()) {
                String fn = fnMatch.group(1).toUpperCase();
                return "Function " + fn + " is not supported by the BigQuery emulator (DuckDB). " +
                        switch (fn) {
                            case "APPROX_COUNT_DISTINCT" -> "Use COUNT(DISTINCT ...) instead.";
                            case "SAFE_DIVIDE" -> "Use CASE WHEN divisor = 0 THEN NULL ELSE a/b END.";
                            case "SAFE_CAST" -> "Use TRY_CAST(...) instead.";
                            case "GENERATE_UUID" -> "Use gen_random_uuid() instead.";
                            default -> "Check DuckDB docs for alternatives.";
                        };
            }
            if (rawError.contains("GEOGRAPHY") || rawError.contains("geography")) {
                return "GEOGRAPHY type is not supported by the BigQuery emulator (DuckDB).";
            }
            if (rawError.contains("BIGNUMERIC") || rawError.contains("bignumeric")) {
                return "BIGNUMERIC type is not supported by the BigQuery emulator. Use DECIMAL or DOUBLE instead.";
            }
        }

        if ("spanner".equals(service)) {
            String upper = rawError.toUpperCase();

            if (upper.contains("MERGE") && !upper.contains("MARSHAL")) {
                return "MERGE is not supported by the Spanner emulator. Use separate INSERT/UPDATE/DELETE statements.";
            }

            // "failed to marshal error message" — emulator's cryptic wrapper for table/index not found
            if (upper.contains("FAILED TO MARSHAL")) {
                return "Spanner: Table or index not found in the selected database. "
                        + "Verify that the correct database is selected and that all referenced tables and indexes exist. "
                        + "Detail: " + rawError;
            }

            // Function not found — extract function name and suggest alternatives
            java.util.regex.Matcher fnNotFound = java.util.regex.Pattern.compile(
                    "(?i)Function not found:\\s*([\\w.]+)").matcher(rawError);
            if (fnNotFound.find()) {
                String fn = fnNotFound.group(1).toUpperCase();
                String hint = switch (fn) {
                    case "SOUNDEX" -> "SOUNDEX requires the search index feature flag to be enabled, or the emulator may need to be rebuilt with the latest upstream.";
                    case "NORMALIZE" -> "NORMALIZE(string, NFC|NFD|NFKC|NFKD) is a ZetaSQL built-in. Ensure the emulator is built from the latest source.";
                    case "SAFE_DIVIDE" -> "SAFE_DIVIDE is a ZetaSQL built-in. Try: CASE WHEN y = 0 THEN NULL ELSE x / y END as a workaround.";
                    case "SEARCH_NGRAMS" -> "SEARCH_NGRAMS requires the search index feature flag. Ensure enable_search_index=true in emulator config.";
                    case "SCORE_NGRAMS" -> "SCORE_NGRAMS requires the search index feature flag. Ensure enable_search_index=true in emulator config.";
                    case "TOKENIZE_NGRAMS" -> "TOKENIZE_NGRAMS requires the search index feature flag. Ensure enable_search_index=true in emulator config.";
                    case "TOKENIZE_FULLTEXT" -> "TOKENIZE_FULLTEXT requires the search index feature flag. Ensure enable_search_index=true in emulator config.";
                    default -> "This function may not be supported by the Spanner emulator.";
                };
                return "Spanner: Function '" + fn + "' not found. " + hint;
            }

            // No matching signature — wrong argument types or count
            java.util.regex.Matcher sigMatch = java.util.regex.Pattern.compile(
                    "(?i)No matching signature for (?:function )?([\\w.]+)").matcher(rawError);
            if (sigMatch.find()) {
                String fn = sigMatch.group(1).toUpperCase();
                // Extract the "Supported signature" lines if present
                java.util.regex.Matcher supported = java.util.regex.Pattern.compile(
                        "(?i)Supported signature[s]?:\\s*(.+?)(?:\\]|$)", java.util.regex.Pattern.DOTALL).matcher(rawError);
                String supportedSigs = supported.find() ? "\nSupported signatures: " + supported.group(1).trim() : "";
                return "Spanner: No matching signature for function '" + fn + "'. "
                        + "Check argument types and count." + supportedSigs;
            }

            // Syntax errors — make location clearer
            java.util.regex.Matcher syntaxMatch = java.util.regex.Pattern.compile(
                    "(?i)Syntax error:\\s*(.+?)(?:\\[at (\\d+):(\\d+)\\])?\\s*$").matcher(rawError);
            if (syntaxMatch.find()) {
                String detail = syntaxMatch.group(1).trim();
                String line = syntaxMatch.group(2);
                String col = syntaxMatch.group(3);
                String location = (line != null && col != null) ? " (line " + line + ", column " + col + ")" : "";
                return "Spanner SQL syntax error" + location + ": " + detail;
            }

            // Hint parsing errors — often from @{OPTIMIZER_VERSION=...} or @{FORCE_INDEX=...}
            if (upper.contains("HINT") || (upper.contains("UNEXPECTED") && upper.contains("@"))) {
                return "Spanner: Query hint parsing error. "
                        + "Statement-level hints like @{OPTIMIZER_VERSION=latest} go before SELECT. "
                        + "Table-level hints like @{FORCE_INDEX=idx} go after the table name (e.g., MyTable@{FORCE_INDEX=idx}). "
                        + "Detail: " + rawError;
            }

            // Column/table not found in schema
            if (upper.contains("COLUMN NOT FOUND") || upper.contains("NAME") && upper.contains("NOT FOUND IN")) {
                return "Spanner: " + rawError + "\nCheck that the column exists in the table schema. "
                        + "Columns in generated/HIDDEN columns (e.g., TOKENLIST) are not directly selectable.";
            }

            // TOKENLIST type errors
            if (upper.contains("TOKENLIST")) {
                return "Spanner: TOKENLIST type error. " + rawError
                        + "\nTOKENLIST columns are generated/HIDDEN and cannot be directly inserted or selected. "
                        + "Use SEARCH_NGRAMS/SCORE_NGRAMS to query them.";
            }

            // Unrecognized name (e.g., variable, CTE, table alias)
            java.util.regex.Matcher unrecognized = java.util.regex.Pattern.compile(
                    "(?i)Unrecognized name:\\s*(\\S+)").matcher(rawError);
            if (unrecognized.find()) {
                return "Spanner: Unrecognized name '" + unrecognized.group(1) + "'. "
                        + "Check table aliases, CTE names, and column references. Detail: " + rawError;
            }

            // gRPC status code enrichment — prepend human-readable code name
            java.util.regex.Matcher codeMatch = java.util.regex.Pattern.compile(
                    "^(\\d+):\\s*").matcher(rawError);
            if (codeMatch.find()) {
                int code = Integer.parseInt(codeMatch.group(1));
                String codeName = switch (code) {
                    case 1 -> "CANCELLED";
                    case 2 -> "UNKNOWN";
                    case 3 -> "INVALID_ARGUMENT";
                    case 4 -> "DEADLINE_EXCEEDED";
                    case 5 -> "NOT_FOUND";
                    case 6 -> "ALREADY_EXISTS";
                    case 7 -> "PERMISSION_DENIED";
                    case 9 -> "FAILED_PRECONDITION";
                    case 10 -> "ABORTED";
                    case 13 -> "INTERNAL";
                    case 14 -> "UNAVAILABLE";
                    default -> "ERROR_" + code;
                };
                return "Spanner " + codeName + ": " + rawError.substring(codeMatch.end());
            }
        }

        return rawError;
    }

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
        try {
            java.net.http.HttpResponse<String> response = httpClient.send(request, BodyHandlers.ofString());
            return response.body();
        } catch (java.io.IOException e) {
            String msg = e.getMessage();
            if (msg != null && (msg.contains("header parser received no bytes")
                    || msg.contains("Connection reset")
                    || msg.contains("Broken pipe"))) {
                logger.debug("Retrying GET after transient connection error: {}", msg);
                java.net.http.HttpResponse<String> response = httpClient.send(request, BodyHandlers.ofString());
                return response.body();
            }
            throw e;
        }
    }

    private String proxyPost(String url, String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        // Retry once on transient connection reset (stale keep-alive)
        try {
            java.net.http.HttpResponse<String> response = httpClient.send(request, BodyHandlers.ofString());
            return response.body();
        } catch (java.io.IOException e) {
            String msg = e.getMessage();
            if (msg != null && (msg.contains("header parser received no bytes")
                    || msg.contains("Connection reset")
                    || msg.contains("Broken pipe"))) {
                logger.debug("Retrying POST after transient connection error: {}", msg);
                java.net.http.HttpResponse<String> response = httpClient.send(request, BodyHandlers.ofString());
                return response.body();
            }
            throw e;
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
}
