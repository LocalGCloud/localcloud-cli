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
 * Sync adapter for Google BigQuery.
 *
 * <p>Pulls data from a real GCP BigQuery project into the local DuckDB-based
 * BigQuery emulator (port 9050). This lets researchers pull filtered subsets of
 * production data for $0 repeated querying locally.
 *
 * <p>GCP REST API docs:
 * <ul>
 *   <li>Datasets: GET /bigquery/v2/projects/{p}/datasets
 *   <li>Tables:   GET /bigquery/v2/projects/{p}/datasets/{d}/tables
 *   <li>Schema:   GET /bigquery/v2/projects/{p}/datasets/{d}/tables/{t}
 *   <li>Query:    POST /bigquery/v2/projects/{p}/queries
 *   <li>InsertAll: POST /bigquery/v2/projects/{p}/datasets/{d}/tables/{t}/insertAll
 * </ul>
 */
public class BigQuerySyncAdapter implements SyncAdapter {

    private static final Logger logger = LoggerFactory.getLogger(BigQuerySyncAdapter.class);

    private static final String GCP_BQ_BASE = "https://bigquery.googleapis.com";
    private static final int TIMEOUT_MS = 60_000;
    private static final double COST_PER_TB_USD = 5.0;
    private static final long BYTES_PER_TB = 1024L * 1024 * 1024 * 1024;
    private static final int PAGE_SIZE = 10_000;

    /** Numeric types that should not be quoted in SQL. */
    private static final Set<String> NUMERIC_TYPES = Set.of(
            "INT64", "FLOAT64", "NUMERIC", "INTEGER", "FLOAT", "BIGNUMERIC"
    );

    private final String localEmulatorBase;
    private final ObjectMapper mapper;

    public BigQuerySyncAdapter(String localEmulatorBase, ObjectMapper mapper) {
        this.localEmulatorBase = localEmulatorBase;
        this.mapper = mapper;
    }

    // -----------------------------------------------------------------------
    // SyncAdapter interface
    // -----------------------------------------------------------------------

    @Override
    public BrowseResult browseRemote(String project, String accessToken) {
        List<Map<String, Object>> nodes = new ArrayList<>();
        try {
            // List datasets
            JsonNode datasetsResp = gcpGet(
                    "/bigquery/v2/projects/" + project + "/datasets", accessToken);
            JsonNode datasets = datasetsResp.path("datasets");

            for (JsonNode ds : datasets) {
                String datasetId = ds.path("datasetReference").path("datasetId").asText();
                Map<String, Object> datasetNode = new LinkedHashMap<>();
                datasetNode.put("id", datasetId);
                datasetNode.put("type", "dataset");

                // List tables in dataset
                List<Map<String, Object>> tableNodes = new ArrayList<>();
                JsonNode tablesResp = gcpGet(
                        "/bigquery/v2/projects/" + project + "/datasets/" + datasetId + "/tables",
                        accessToken);
                JsonNode tables = tablesResp.path("tables");

                for (JsonNode tbl : tables) {
                    String tableId = tbl.path("tableReference").path("tableId").asText();
                    String tableType = tbl.path("type").asText("TABLE");

                    // Get table schema
                    JsonNode tableDetail = gcpGet(
                            "/bigquery/v2/projects/" + project + "/datasets/" + datasetId
                                    + "/tables/" + tableId,
                            accessToken);

                    Map<String, Object> tableNode = new LinkedHashMap<>();
                    tableNode.put("id", datasetId + "." + tableId);
                    tableNode.put("name", tableId);
                    tableNode.put("type", tableType);
                    tableNode.put("numRows", tableDetail.path("numRows").asText("0"));
                    tableNode.put("numBytes", tableDetail.path("numBytes").asText("0"));

                    // Extract schema fields
                    List<Map<String, String>> columns = new ArrayList<>();
                    JsonNode fields = tableDetail.path("schema").path("fields");
                    for (JsonNode field : fields) {
                        Map<String, String> col = new LinkedHashMap<>();
                        col.put("name", field.path("name").asText());
                        col.put("type", field.path("type").asText());
                        col.put("mode", field.path("mode").asText("NULLABLE"));
                        columns.add(col);
                    }
                    tableNode.put("columns", columns);
                    tableNodes.add(tableNode);
                }

                datasetNode.put("tables", tableNodes);
                nodes.add(datasetNode);
            }
        } catch (Exception e) {
            logger.error("browseRemote failed for project {}: {}", project, e.getMessage());
            throw new RuntimeException("Failed to browse BigQuery: " + e.getMessage(), e);
        }
        return new BrowseResult(nodes);
    }

    @Override
    public PreviewResult previewRemote(String project, String resource,
                                        String accessToken, int limit) {
        String[] parts = parseResource(resource);
        String dataset = parts[0];
        String table = parts[1];

        String sql = "SELECT * FROM `" + dataset + "." + table + "` LIMIT " + limit;
        try {
            JsonNode result = executeQuery(project, sql, accessToken, false);

            List<String> columns = new ArrayList<>();
            JsonNode schemaFields = result.path("schema").path("fields");
            for (JsonNode f : schemaFields) {
                columns.add(f.path("name").asText());
            }

            List<Map<String, Object>> rows = extractRows(result, columns);
            long totalRows = result.path("totalRows").asLong(rows.size());
            long totalBytes = result.path("totalBytesProcessed").asLong(0);

            return new PreviewResult(columns, rows, totalRows, totalBytes);
        } catch (Exception e) {
            logger.error("previewRemote failed for {}: {}", resource, e.getMessage());
            throw new RuntimeException("Failed to preview BigQuery table: " + e.getMessage(), e);
        }
    }

    @Override
    public CostEstimate estimate(String project, String resource, List<SyncFilter> filters,
                                  int rowLimit, String accessToken) {
        String[] parts = parseResource(resource);
        String sql = buildSyncQuery(parts[0], parts[1], filters, rowLimit);

        try {
            // Dry-run query to get estimated bytes
            JsonNode result = executeQuery(project, sql, accessToken, true);
            long totalBytes = result.path("totalBytesProcessed").asLong(0);
            long estimatedRows = result.path("totalRows").asLong(0);
            double cost = estimateCost(totalBytes);

            String details = String.format(
                    "Query: %s | Estimated scan: %s | $%.4f (at $5/TB)",
                    sql, formatBytes(totalBytes), cost);

            return new CostEstimate(estimatedRows, totalBytes, cost, details);
        } catch (Exception e) {
            logger.error("estimate failed for {}: {}", resource, e.getMessage());
            throw new RuntimeException("Failed to estimate BigQuery cost: " + e.getMessage(), e);
        }
    }

    @Override
    public SyncResult sync(String project, String resource, List<SyncFilter> filters,
                            int rowLimit, String accessToken, String localProject,
                            SyncProgressCallback progress) {
        String[] parts = parseResource(resource);
        String dataset = parts[0];
        String table = parts[1];
        String sql = buildSyncQuery(dataset, table, filters, rowLimit);

        long totalRowsSynced = 0;
        long totalBytesSynced = 0;

        try {
            // Ensure local dataset exists
            ensureLocalDataset(localProject, dataset);

            // Execute remote query
            JsonNode queryResult = executeQuery(project, sql, accessToken, false);
            long totalBytes = queryResult.path("totalBytesProcessed").asLong(0);
            long estimatedTotalRows = queryResult.path("totalRows").asLong(0);

            // Extract schema for local table creation
            JsonNode schemaFields = queryResult.path("schema").path("fields");
            List<String> columns = new ArrayList<>();
            for (JsonNode f : schemaFields) {
                columns.add(f.path("name").asText());
            }

            // Ensure local table exists with correct schema
            ensureLocalTable(localProject, dataset, table, schemaFields);

            // Process first page
            List<Map<String, Object>> rows = extractRows(queryResult, columns);
            if (!rows.isEmpty()) {
                insertIntoLocal(localProject, dataset, table, rows);
                totalRowsSynced += rows.size();
                if (progress != null) {
                    progress.onProgress(totalRowsSynced, totalBytes, estimatedTotalRows);
                }
            }

            // Handle pagination via pageToken
            String pageToken = queryResult.path("pageToken").asText(null);
            String jobId = queryResult.path("jobReference").path("jobId").asText(null);

            while (pageToken != null && !pageToken.isEmpty()) {
                JsonNode pageResult = getQueryResults(project, jobId, pageToken, accessToken);
                rows = extractRows(pageResult, columns);
                if (!rows.isEmpty()) {
                    insertIntoLocal(localProject, dataset, table, rows);
                    totalRowsSynced += rows.size();
                    if (progress != null) {
                        progress.onProgress(totalRowsSynced, totalBytes, estimatedTotalRows);
                    }
                }
                pageToken = pageResult.path("pageToken").asText(null);
            }

            totalBytesSynced = totalBytes;
            double cost = estimateCost(totalBytesSynced);

            logger.info("Sync complete: {} rows, {} bytes, ${} from {}.{} -> local {}",
                    totalRowsSynced, totalBytesSynced, cost, dataset, table, localProject);

            return new SyncResult(0, totalRowsSynced, totalBytesSynced, cost,
                    "completed", null);

        } catch (Exception e) {
            logger.error("sync failed for {}: {}", resource, e.getMessage(), e);
            double cost = estimateCost(totalBytesSynced);
            return new SyncResult(0, totalRowsSynced, totalBytesSynced, cost,
                    "failed", e.getMessage());
        }
    }

    // -----------------------------------------------------------------------
    // Package-visible helpers (tested directly)
    // -----------------------------------------------------------------------

    /**
     * Build a BigQuery SQL query with optional filters and row limit.
     */
    String buildSyncQuery(String dataset, String table, List<SyncFilter> filters, int rowLimit) {
        // Validate all filters before building query to prevent SQL injection
        if (filters != null) {
            filters.forEach(SyncFilterValidator::validate);
        }

        StringBuilder sb = new StringBuilder();
        sb.append("SELECT * FROM `").append(dataset).append('.').append(table).append('`');

        if (filters != null && !filters.isEmpty()) {
            sb.append(" WHERE ");
            for (int i = 0; i < filters.size(); i++) {
                if (i > 0) {
                    sb.append(" AND ");
                }
                SyncFilter f = filters.get(i);
                sb.append(f.column()).append(' ');

                if ("IN".equalsIgnoreCase(f.operator())) {
                    sb.append("IN (");
                    String[] values = f.value().split(",");
                    for (int j = 0; j < values.length; j++) {
                        if (j > 0) sb.append(", ");
                        String val = values[j].trim();
                        if (isNumericType(f.columnType())) {
                            sb.append(val);
                        } else {
                            sb.append('\'').append(escapeSql(val)).append('\'');
                        }
                    }
                    sb.append(')');
                } else {
                    sb.append(f.operator()).append(' ');
                    if (isNumericType(f.columnType()) || "BOOL".equalsIgnoreCase(f.columnType())) {
                        sb.append(f.value());
                    } else {
                        sb.append('\'').append(escapeSql(f.value())).append('\'');
                    }
                }
            }
        }

        if (rowLimit > 0) {
            sb.append(" LIMIT ").append(rowLimit);
        }

        return sb.toString();
    }

    /**
     * Parse a "dataset.table" resource string into [dataset, table].
     *
     * @throws IllegalArgumentException if the resource is null, empty, or not in "dataset.table" format
     */
    String[] parseResource(String resource) {
        if (resource == null || resource.isEmpty()) {
            throw new IllegalArgumentException("Resource must be in 'dataset.table' format, got: " + resource);
        }
        String[] parts = resource.split("\\.");
        if (parts.length != 2) {
            throw new IllegalArgumentException(
                    "Resource must be in 'dataset.table' format (exactly one dot), got: " + resource);
        }
        if (parts[0].isEmpty() || parts[1].isEmpty()) {
            throw new IllegalArgumentException(
                    "Resource must have non-empty dataset and table names, got: " + resource);
        }
        return parts;
    }

    /**
     * Calculate the cost in USD for a given number of bytes processed.
     * BigQuery charges $5 per TB scanned.
     */
    double estimateCost(long totalBytesProcessed) {
        if (totalBytesProcessed <= 0) return 0.0;
        return COST_PER_TB_USD * totalBytesProcessed / BYTES_PER_TB;
    }

    // -----------------------------------------------------------------------
    // Private helpers -- GCP HTTP
    // -----------------------------------------------------------------------

    private JsonNode gcpGet(String path, String accessToken) throws IOException {
        HttpURLConnection conn = openGcpConnection(path, "GET", accessToken);
        return readResponse(conn);
    }

    private JsonNode gcpPost(String path, String body, String accessToken) throws IOException {
        HttpURLConnection conn = openGcpConnection(path, "POST", accessToken);
        conn.setDoOutput(true);
        try (OutputStream os = conn.getOutputStream()) {
            os.write(body.getBytes(StandardCharsets.UTF_8));
        }
        return readResponse(conn);
    }

    private HttpURLConnection openGcpConnection(String path, String method,
                                                  String accessToken) throws IOException {
        String url = GCP_BQ_BASE + path;
        HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
        conn.setRequestMethod(method);
        conn.setRequestProperty("Authorization", "Bearer " + accessToken);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setConnectTimeout(TIMEOUT_MS);
        conn.setReadTimeout(TIMEOUT_MS);
        return conn;
    }

    private JsonNode readResponse(HttpURLConnection conn) throws IOException {
        int statusCode = conn.getResponseCode();
        InputStream is = (statusCode >= 200 && statusCode < 300)
                ? conn.getInputStream()
                : conn.getErrorStream();

        String body = "";
        if (is != null) {
            body = new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
        conn.disconnect();

        if (statusCode < 200 || statusCode >= 300) {
            throw new IOException("BigQuery API returned " + statusCode + ": " + body);
        }

        return mapper.readTree(body);
    }

    // -----------------------------------------------------------------------
    // Private helpers -- Query execution
    // -----------------------------------------------------------------------

    private JsonNode executeQuery(String project, String sql, String accessToken,
                                   boolean dryRun) throws IOException {
        ObjectNode requestBody = mapper.createObjectNode();
        requestBody.put("query", sql);
        requestBody.put("useLegacySql", false);
        requestBody.put("maxResults", PAGE_SIZE);
        if (dryRun) {
            requestBody.put("dryRun", true);
        }

        String path = "/bigquery/v2/projects/" + project + "/queries";
        return gcpPost(path, mapper.writeValueAsString(requestBody), accessToken);
    }

    private JsonNode getQueryResults(String project, String jobId, String pageToken,
                                      String accessToken) throws IOException {
        String path = "/bigquery/v2/projects/" + project + "/queries/" + jobId
                + "?pageToken=" + pageToken + "&maxResults=" + PAGE_SIZE;
        return gcpGet(path, accessToken);
    }

    private List<Map<String, Object>> extractRows(JsonNode queryResult, List<String> columns) {
        List<Map<String, Object>> rows = new ArrayList<>();
        JsonNode rowsNode = queryResult.path("rows");
        if (rowsNode.isMissingNode() || !rowsNode.isArray()) {
            return rows;
        }

        for (JsonNode row : rowsNode) {
            JsonNode values = row.path("f");
            Map<String, Object> rowMap = new LinkedHashMap<>();
            for (int i = 0; i < columns.size() && i < values.size(); i++) {
                JsonNode v = values.get(i).path("v");
                rowMap.put(columns.get(i), v.isNull() ? null : v.asText());
            }
            rows.add(rowMap);
        }
        return rows;
    }

    // -----------------------------------------------------------------------
    // Private helpers -- Local emulator HTTP
    // -----------------------------------------------------------------------

    private void ensureLocalDataset(String localProject, String dataset) throws IOException {
        ObjectNode body = mapper.createObjectNode();
        ObjectNode ref = mapper.createObjectNode();
        ref.put("projectId", localProject);
        ref.put("datasetId", dataset);
        body.set("datasetReference", ref);

        String path = "/bigquery/v2/projects/" + localProject + "/datasets";
        try {
            localPost(path, mapper.writeValueAsString(body));
        } catch (IOException e) {
            // Dataset may already exist -- ignore 409 Conflict
            if (!e.getMessage().contains("409")) {
                throw e;
            }
        }
    }

    private void ensureLocalTable(String localProject, String dataset, String table,
                                   JsonNode schemaFields) throws IOException {
        ObjectNode body = mapper.createObjectNode();
        ObjectNode ref = mapper.createObjectNode();
        ref.put("projectId", localProject);
        ref.put("datasetId", dataset);
        ref.put("tableId", table);
        body.set("tableReference", ref);

        ObjectNode schema = mapper.createObjectNode();
        schema.set("fields", schemaFields);
        body.set("schema", schema);

        String path = "/bigquery/v2/projects/" + localProject + "/datasets/"
                + dataset + "/tables";
        try {
            localPost(path, mapper.writeValueAsString(body));
        } catch (IOException e) {
            // Table may already exist -- ignore 409 Conflict
            if (!e.getMessage().contains("409")) {
                throw e;
            }
        }
    }

    private void insertIntoLocal(String localProject, String dataset, String table,
                                  List<Map<String, Object>> rows) throws IOException {
        ObjectNode body = mapper.createObjectNode();
        ArrayNode rowsArray = mapper.createArrayNode();

        for (Map<String, Object> row : rows) {
            ObjectNode insertRow = mapper.createObjectNode();
            ObjectNode json = mapper.createObjectNode();
            for (Map.Entry<String, Object> entry : row.entrySet()) {
                if (entry.getValue() == null) {
                    json.putNull(entry.getKey());
                } else {
                    json.put(entry.getKey(), entry.getValue().toString());
                }
            }
            insertRow.set("json", json);
            rowsArray.add(insertRow);
        }
        body.set("rows", rowsArray);

        String path = "/bigquery/v2/projects/" + localProject + "/datasets/"
                + dataset + "/tables/" + table + "/insertAll";
        localPost(path, mapper.writeValueAsString(body));
    }

    private void localPost(String path, String body) throws IOException {
        String url = localEmulatorBase + path;
        HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setConnectTimeout(TIMEOUT_MS);
        conn.setReadTimeout(TIMEOUT_MS);
        conn.setDoOutput(true);

        try (OutputStream os = conn.getOutputStream()) {
            os.write(body.getBytes(StandardCharsets.UTF_8));
        }

        int statusCode = conn.getResponseCode();
        InputStream is = (statusCode >= 200 && statusCode < 300)
                ? conn.getInputStream()
                : conn.getErrorStream();

        String responseBody = "";
        if (is != null) {
            responseBody = new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
        conn.disconnect();

        if (statusCode < 200 || statusCode >= 300) {
            throw new IOException("Local emulator returned " + statusCode + ": " + responseBody);
        }
    }

    // -----------------------------------------------------------------------
    // Private helpers -- SQL / formatting
    // -----------------------------------------------------------------------

    private boolean isNumericType(String columnType) {
        return columnType != null && NUMERIC_TYPES.contains(columnType.toUpperCase());
    }

    private String escapeSql(String value) {
        return value.replace("'", "''");
    }

    private String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
        if (bytes < 1024L * 1024 * 1024 * 1024) return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
        return String.format("%.2f TB", bytes / (1024.0 * 1024 * 1024 * 1024));
    }
}
