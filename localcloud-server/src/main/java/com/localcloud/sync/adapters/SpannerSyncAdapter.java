package com.localcloud.sync.adapters;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.localcloud.sync.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Sync adapter for Google Cloud Spanner.
 *
 * <p>Pulls data from a real GCP Spanner instance/database into the local
 * Spanner emulator (port 9010). This lets developers work with filtered
 * production data locally.
 *
 * <p>GCP REST API docs:
 * <ul>
 *   <li>List instances:  GET /v1/projects/{p}/instances
 *   <li>List databases:  GET /v1/projects/{p}/instances/{i}/databases
 *   <li>Get DDL:         GET /v1/projects/{p}/instances/{i}/databases/{d}/ddl
 *   <li>Create session:  POST /v1/projects/{p}/instances/{i}/databases/{d}/sessions
 *   <li>Execute SQL:     POST /v1/projects/{p}/instances/{i}/databases/{d}/sessions/{s}:executeSql
 * </ul>
 */
public class SpannerSyncAdapter implements SyncAdapter {

    private static final Logger logger = LoggerFactory.getLogger(SpannerSyncAdapter.class);

    private static final String GCP_SPANNER_BASE = "https://spanner.googleapis.com";
    private static final int TIMEOUT_MS = 60_000;
    private static final int PAGE_SIZE = 10_000;

    /** Spanner charges ~$0.65 per million read operations. */
    private static final double COST_PER_MILLION_READS = 0.65;

    /** Numeric types that should not be quoted in SQL. */
    private static final Set<String> NUMERIC_TYPES = Set.of(
            "INT64", "FLOAT64", "NUMERIC", "FLOAT32"
    );

    private final String localEmulatorHost;
    private final int localEmulatorPort;
    private final ObjectMapper mapper;
    private final RetryableHttpClient httpClient;

    public SpannerSyncAdapter(String localEmulatorHost, int localEmulatorPort, ObjectMapper mapper) {
        this.localEmulatorHost = localEmulatorHost;
        this.localEmulatorPort = localEmulatorPort;
        this.mapper = mapper;
        this.httpClient = new RetryableHttpClient();
    }

    // -----------------------------------------------------------------------
    // SyncAdapter interface
    // -----------------------------------------------------------------------

    @Override
    public BrowseResult browseRemote(String project, String accessToken) {
        List<Map<String, Object>> nodes = new ArrayList<>();
        try {
            // List instances
            JsonNode instancesResp = gcpGet(
                    "/v1/projects/" + project + "/instances", accessToken);
            JsonNode instances = instancesResp.path("instances");

            for (JsonNode inst : instances) {
                String instanceName = inst.path("name").asText();
                String instanceId = instanceName.substring(instanceName.lastIndexOf('/') + 1);

                Map<String, Object> instanceNode = new LinkedHashMap<>();
                instanceNode.put("id", instanceId);
                instanceNode.put("type", "instance");

                // List databases in instance
                JsonNode dbsResp = gcpGet(
                        "/v1/projects/" + project + "/instances/" + instanceId + "/databases",
                        accessToken);
                JsonNode databases = dbsResp.path("databases");

                List<Map<String, Object>> dbNodes = new ArrayList<>();
                for (JsonNode db : databases) {
                    String dbName = db.path("name").asText();
                    String dbId = dbName.substring(dbName.lastIndexOf('/') + 1);

                    Map<String, Object> dbNode = new LinkedHashMap<>();
                    dbNode.put("id", instanceId + "/" + dbId);
                    dbNode.put("name", dbId);
                    dbNode.put("type", "database");

                    // Get DDL to find tables
                    JsonNode ddlResp = gcpGet(
                            "/v1/projects/" + project + "/instances/" + instanceId
                                    + "/databases/" + dbId + "/ddl",
                            accessToken);
                    JsonNode statements = ddlResp.path("statements");

                    List<String> tableNames = new ArrayList<>();
                    for (JsonNode stmt : statements) {
                        String sql = stmt.asText();
                        // Parse CREATE TABLE statements
                        String upper = sql.toUpperCase().trim();
                        if (upper.startsWith("CREATE TABLE")) {
                            String tableName = extractTableName(sql);
                            if (tableName != null) {
                                tableNames.add(tableName);
                            }
                        }
                    }
                    dbNode.put("tables", tableNames);
                    dbNodes.add(dbNode);
                }

                instanceNode.put("databases", dbNodes);
                nodes.add(instanceNode);
            }
        } catch (Exception e) {
            logger.error("browseRemote failed for project {}: {}", project, e.getMessage());
            throw new RuntimeException("Failed to browse Spanner: " + e.getMessage(), e);
        }
        return new BrowseResult(nodes);
    }

    @Override
    public PreviewResult previewRemote(String project, String resource,
                                        String accessToken, int limit) {
        String[] parts = parseResource(resource);
        String instance = parts[0];
        String database = parts[1];
        String table = parts[2];

        String sql = "SELECT * FROM " + table + " LIMIT " + limit;

        try {
            // Create session
            String sessionName = createSession(project, instance, database, accessToken);

            // Execute SQL
            JsonNode result = executeSql(sessionName, sql, accessToken);

            // Extract column names from metadata
            List<String> columns = new ArrayList<>();
            JsonNode metadata = result.path("metadata").path("rowType").path("fields");
            for (JsonNode field : metadata) {
                columns.add(field.path("name").asText());
            }

            // Extract rows
            List<Map<String, Object>> rows = extractSpannerRows(result, columns);
            long totalRows = rows.size();

            return new PreviewResult(columns, rows, totalRows, 0);
        } catch (Exception e) {
            logger.error("previewRemote failed for {}: {}", resource, e.getMessage());
            throw new RuntimeException("Failed to preview Spanner table: " + e.getMessage(), e);
        }
    }

    @Override
    public CostEstimate estimate(String project, String resource, List<SyncFilter> filters,
                                  int rowLimit, String accessToken) {
        String[] parts = parseResource(resource);
        String instance = parts[0];
        String database = parts[1];
        String table = parts[2];

        try {
            // Create session
            String sessionName = createSession(project, instance, database, accessToken);

            // Count rows with filters
            String countSql = buildCountQuery(table, filters);
            JsonNode result = executeSql(sessionName, countSql, accessToken);

            long estimatedRows = 0;
            JsonNode rows = result.path("rows");
            if (rows.isArray() && rows.size() > 0) {
                estimatedRows = rows.get(0).get(0).asLong(0);
            }

            if (rowLimit > 0 && estimatedRows > rowLimit) {
                estimatedRows = rowLimit;
            }

            double cost = estimateReadCost(estimatedRows);
            String details = String.format(
                    "Table: %s/%s/%s | Estimated rows: %d | $%.4f (at $0.65/million reads)",
                    instance, database, table, estimatedRows, cost);

            return new CostEstimate(estimatedRows, 0, cost, details);
        } catch (Exception e) {
            logger.error("estimate failed for {}: {}", resource, e.getMessage());
            throw new RuntimeException("Failed to estimate Spanner cost: " + e.getMessage(), e);
        }
    }

    @Override
    public SyncResult sync(String project, String resource, List<SyncFilter> filters,
                            int rowLimit, String accessToken, String localProject,
                            SyncProgressCallback progress) {
        String[] parts = parseResource(resource);
        String instance = parts[0];
        String database = parts[1];
        String table = parts[2];
        String sql = buildSyncQuery(table, filters, rowLimit);

        long totalRowsSynced = 0;

        try {
            // Create remote session
            String sessionName = createSession(project, instance, database, accessToken);

            // Execute query
            JsonNode result = executeSql(sessionName, sql, accessToken);

            // Extract columns
            List<String> columns = new ArrayList<>();
            JsonNode metadata = result.path("metadata").path("rowType").path("fields");
            for (JsonNode field : metadata) {
                columns.add(field.path("name").asText());
            }

            // Extract rows
            List<Map<String, Object>> rows = extractSpannerRows(result, columns);

            // Create local session and insert data
            if (!rows.isEmpty()) {
                String localSessionName = createLocalSession(localProject, instance, database);

                // Insert in batches
                for (int i = 0; i < rows.size(); i += PAGE_SIZE) {
                    int end = Math.min(i + PAGE_SIZE, rows.size());
                    List<Map<String, Object>> batch = rows.subList(i, end);

                    insertIntoLocal(localSessionName, table, columns, batch);
                    totalRowsSynced += batch.size();

                    if (progress != null) {
                        progress.onProgress(totalRowsSynced, 0, rows.size());
                    }
                }
            }

            double cost = estimateReadCost(totalRowsSynced);
            logger.info("Sync complete: {} rows from {}/{}/{} -> local {}",
                    totalRowsSynced, instance, database, table, localProject);

            return new SyncResult(0, totalRowsSynced, 0, cost, "completed", null);

        } catch (Exception e) {
            logger.error("sync failed for {}: {}", resource, e.getMessage(), e);
            double cost = estimateReadCost(totalRowsSynced);
            return new SyncResult(0, totalRowsSynced, 0, cost, "failed", e.getMessage());
        }
    }

    @Override
    public void deleteLocal(String localProject, String resource) {
        String[] parts = parseResource(resource);
        String instance = parts[0];
        String database = parts[1];
        String table = parts[2];
        // Truncate table data via DELETE DML on the local Spanner emulator.
        // Full table drop would require DDL, but DELETE removes all synced rows.
        try {
            String sessionName = createLocalSession(localProject, instance, database);
            String deleteSql = "DELETE FROM " + table + " WHERE true";
            ObjectNode body = mapper.createObjectNode();
            body.put("sql", deleteSql);
            String url = "http://" + localEmulatorHost + ":" + localEmulatorPort
                    + "/v1/" + sessionName + ":executeSql";
            localPost(url, mapper.writeValueAsString(body));
            logger.info("Truncated local Spanner table {}/{}/{}", instance, database, table);
        } catch (Exception e) {
            logger.warn("Failed to delete local Spanner table data for {}: {}", resource, e.getMessage());
        }
    }

    // -----------------------------------------------------------------------
    // Package-visible helpers (tested directly)
    // -----------------------------------------------------------------------

    /**
     * Parse a "instance/database/table" resource string into [instance, database, table].
     *
     * @throws IllegalArgumentException if the resource is not in the expected format
     */
    String[] parseResource(String resource) {
        if (resource == null || resource.isEmpty()) {
            throw new IllegalArgumentException(
                    "Resource must be in 'instance/database/table' format, got: " + resource);
        }
        String[] parts = resource.split("/");
        if (parts.length != 3) {
            throw new IllegalArgumentException(
                    "Resource must be in 'instance/database/table' format (exactly two slashes), got: " + resource);
        }
        if (parts[0].isEmpty() || parts[1].isEmpty() || parts[2].isEmpty()) {
            throw new IllegalArgumentException(
                    "Resource must have non-empty instance, database, and table names, got: " + resource);
        }
        return parts;
    }

    /**
     * Build a Spanner SQL query with optional filters and row limit.
     */
    String buildSyncQuery(String table, List<SyncFilter> filters, int rowLimit) {
        // Validate all filters before building query to prevent SQL injection
        if (filters != null) {
            filters.forEach(SyncFilterValidator::validate);
        }

        StringBuilder sb = new StringBuilder();
        sb.append("SELECT * FROM ").append(table);

        if (filters != null && !filters.isEmpty()) {
            sb.append(" WHERE ");
            for (int i = 0; i < filters.size(); i++) {
                if (i > 0) sb.append(" AND ");
                SyncFilter f = filters.get(i);
                sb.append(f.column()).append(' ').append(f.operator()).append(' ');
                if (isNumericType(f.columnType()) || "BOOL".equalsIgnoreCase(f.columnType())) {
                    sb.append(f.value());
                } else {
                    sb.append('\'').append(escapeSql(f.value())).append('\'');
                }
            }
        }

        if (rowLimit > 0) {
            sb.append(" LIMIT ").append(rowLimit);
        }

        return sb.toString();
    }

    /**
     * Build a COUNT query for estimating row count.
     */
    String buildCountQuery(String table, List<SyncFilter> filters) {
        // Validate all filters before building query to prevent SQL injection
        if (filters != null) {
            filters.forEach(SyncFilterValidator::validate);
        }

        StringBuilder sb = new StringBuilder();
        sb.append("SELECT COUNT(*) FROM ").append(table);

        if (filters != null && !filters.isEmpty()) {
            sb.append(" WHERE ");
            for (int i = 0; i < filters.size(); i++) {
                if (i > 0) sb.append(" AND ");
                SyncFilter f = filters.get(i);
                sb.append(f.column()).append(' ').append(f.operator()).append(' ');
                if (isNumericType(f.columnType()) || "BOOL".equalsIgnoreCase(f.columnType())) {
                    sb.append(f.value());
                } else {
                    sb.append('\'').append(escapeSql(f.value())).append('\'');
                }
            }
        }

        return sb.toString();
    }

    /**
     * Calculate the cost in USD for a given number of rows read.
     * Spanner charges ~$0.65 per million read operations.
     */
    double estimateReadCost(long rowCount) {
        if (rowCount <= 0) return 0.0;
        return COST_PER_MILLION_READS * rowCount / 1_000_000.0;
    }

    /**
     * Extract a table name from a CREATE TABLE DDL statement.
     */
    String extractTableName(String ddl) {
        // Pattern: CREATE TABLE <name> (
        String trimmed = ddl.trim();
        int tableIdx = trimmed.toUpperCase().indexOf("CREATE TABLE");
        if (tableIdx < 0) return null;

        String afterCreate = trimmed.substring(tableIdx + "CREATE TABLE".length()).trim();
        // Handle optional IF NOT EXISTS
        if (afterCreate.toUpperCase().startsWith("IF NOT EXISTS")) {
            afterCreate = afterCreate.substring("IF NOT EXISTS".length()).trim();
        }

        // Table name ends at ( or whitespace
        int end = afterCreate.indexOf('(');
        if (end < 0) end = afterCreate.indexOf(' ');
        if (end < 0) end = afterCreate.length();

        String name = afterCreate.substring(0, end).trim();
        // Remove backticks or quotes if present
        if ((name.startsWith("`") && name.endsWith("`"))
                || (name.startsWith("\"") && name.endsWith("\""))) {
            name = name.substring(1, name.length() - 1);
        }
        return name.isEmpty() ? null : name;
    }

    // -----------------------------------------------------------------------
    // Private helpers — SQL
    // -----------------------------------------------------------------------

    private boolean isNumericType(String columnType) {
        return columnType != null && NUMERIC_TYPES.contains(columnType.toUpperCase());
    }

    private String escapeSql(String value) {
        return value.replace("'", "''");
    }

    // -----------------------------------------------------------------------
    // Private helpers — Spanner row extraction
    // -----------------------------------------------------------------------

    private List<Map<String, Object>> extractSpannerRows(JsonNode result, List<String> columns) {
        List<Map<String, Object>> rows = new ArrayList<>();
        JsonNode rowsNode = result.path("rows");
        if (rowsNode.isMissingNode() || !rowsNode.isArray()) return rows;

        for (JsonNode row : rowsNode) {
            Map<String, Object> rowMap = new LinkedHashMap<>();
            for (int i = 0; i < columns.size() && i < row.size(); i++) {
                JsonNode val = row.get(i);
                rowMap.put(columns.get(i), val.isNull() ? null : val.asText());
            }
            rows.add(rowMap);
        }
        return rows;
    }

    // -----------------------------------------------------------------------
    // Private helpers — GCP HTTP (delegated to RetryableHttpClient)
    // -----------------------------------------------------------------------

    private JsonNode gcpGet(String path, String accessToken) throws IOException {
        String url = GCP_SPANNER_BASE + path;
        String body = httpClient.get(url, accessToken).body();
        return mapper.readTree(body);
    }

    private JsonNode gcpPost(String path, String body, String accessToken) throws IOException {
        String url = GCP_SPANNER_BASE + path;
        String responseBody = httpClient.post(url, body, accessToken).body();
        return mapper.readTree(responseBody);
    }

    // -----------------------------------------------------------------------
    // Private helpers — Session management
    // -----------------------------------------------------------------------

    private String createSession(String project, String instance, String database,
                                  String accessToken) throws IOException {
        String path = "/v1/projects/" + project + "/instances/" + instance
                + "/databases/" + database + "/sessions";
        JsonNode result = gcpPost(path, "{}", accessToken);
        return result.path("name").asText();
    }

    private String createLocalSession(String localProject, String instance,
                                       String database) throws IOException {
        String url = "http://" + localEmulatorHost + ":" + localEmulatorPort
                + "/v1/projects/" + localProject + "/instances/" + instance
                + "/databases/" + database + "/sessions";
        return localPost(url, "{}");
    }

    private JsonNode executeSql(String sessionName, String sql,
                                 String accessToken) throws IOException {
        ObjectNode body = mapper.createObjectNode();
        body.put("sql", sql);

        String path = "/v1/" + sessionName + ":executeSql";
        return gcpPost(path, mapper.writeValueAsString(body), accessToken);
    }

    // -----------------------------------------------------------------------
    // Private helpers — Local emulator HTTP
    // -----------------------------------------------------------------------

    private void insertIntoLocal(String sessionName, String table,
                                  List<String> columns, List<Map<String, Object>> rows)
            throws IOException {
        ObjectNode body = mapper.createObjectNode();
        body.put("table", table);

        ArrayNode columnsArray = mapper.createArrayNode();
        columns.forEach(columnsArray::add);
        body.set("columns", columnsArray);

        ArrayNode valuesArray = mapper.createArrayNode();
        for (Map<String, Object> row : rows) {
            ArrayNode rowValues = mapper.createArrayNode();
            for (String col : columns) {
                Object val = row.get(col);
                if (val == null) {
                    rowValues.addNull();
                } else {
                    rowValues.add(val.toString());
                }
            }
            valuesArray.add(rowValues);
        }
        body.set("values", valuesArray);

        String url = "http://" + localEmulatorHost + ":" + localEmulatorPort
                + "/v1/" + sessionName + ":commit";

        ObjectNode commitBody = mapper.createObjectNode();
        ArrayNode mutations = mapper.createArrayNode();
        ObjectNode mutation = mapper.createObjectNode();
        mutation.set("insert", body);
        mutations.add(mutation);
        commitBody.set("mutations", mutations);
        commitBody.put("singleUseTransaction", true);

        localPost(url, mapper.writeValueAsString(commitBody));
    }

    private String localPost(String url, String body) throws IOException {
        String responseBody = httpClient.localPost(url, body).body();

        // Return session name if present in response
        try {
            JsonNode resp = mapper.readTree(responseBody);
            if (resp.has("name")) {
                return resp.get("name").asText();
            }
        } catch (Exception ignored) {
            // Not JSON or no name field
        }
        return responseBody;
    }
}
