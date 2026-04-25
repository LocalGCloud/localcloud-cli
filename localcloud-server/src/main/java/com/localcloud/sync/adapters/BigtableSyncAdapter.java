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
 * Sync adapter for Google Cloud Bigtable.
 *
 * <p>Pulls data from a real GCP Bigtable instance into the local Bigtable
 * emulator (port 8087). This lets developers work with filtered production
 * data locally for development and testing.
 *
 * <p>GCP REST API docs:
 * <ul>
 *   <li>List tables:     GET /v2/projects/{p}/instances/{i}/tables
 *   <li>Get table:       GET /v2/projects/{p}/instances/{i}/tables/{t}
 *   <li>Read rows:       POST /v2/projects/{p}/instances/{i}/tables/{t}:readRows
 *   <li>Mutate rows:     POST /v2/projects/{p}/instances/{i}/tables/{t}:mutateRows
 * </ul>
 */
public class BigtableSyncAdapter implements SyncAdapter {

    private static final Logger logger = LoggerFactory.getLogger(BigtableSyncAdapter.class);

    private static final String GCP_BIGTABLE_BASE = "https://bigtable.googleapis.com";
    private static final int TIMEOUT_MS = 60_000;
    private static final int PAGE_SIZE = 1000;

    /** Bigtable charges ~$0.26 per million read operations. */
    private static final double COST_PER_MILLION_READS = 0.26;

    private final String localEmulatorHost;
    private final int localEmulatorPort;
    private final ObjectMapper mapper;

    public BigtableSyncAdapter(String localEmulatorHost, int localEmulatorPort, ObjectMapper mapper) {
        this.localEmulatorHost = localEmulatorHost;
        this.localEmulatorPort = localEmulatorPort;
        this.mapper = mapper;
    }

    // -----------------------------------------------------------------------
    // SyncAdapter interface
    // -----------------------------------------------------------------------

    @Override
    public BrowseResult browseRemote(String project, String accessToken) {
        List<Map<String, Object>> nodes = new ArrayList<>();
        try {
            // We need instance IDs — list instances via admin API
            JsonNode instancesResp = gcpGet(
                    "/v2/projects/" + project + "/instances", accessToken);
            JsonNode instances = instancesResp.path("instances");

            for (JsonNode inst : instances) {
                String instanceName = inst.path("name").asText();
                String instanceId = instanceName.substring(instanceName.lastIndexOf('/') + 1);

                Map<String, Object> instanceNode = new LinkedHashMap<>();
                instanceNode.put("id", instanceId);
                instanceNode.put("type", "instance");

                // List tables in instance
                JsonNode tablesResp = gcpGet(
                        "/v2/projects/" + project + "/instances/" + instanceId + "/tables",
                        accessToken);
                JsonNode tables = tablesResp.path("tables");

                List<Map<String, Object>> tableNodes = new ArrayList<>();
                for (JsonNode tbl : tables) {
                    String tableName = tbl.path("name").asText();
                    String tableId = tableName.substring(tableName.lastIndexOf('/') + 1);

                    Map<String, Object> tableNode = new LinkedHashMap<>();
                    tableNode.put("id", instanceId + "/" + tableId);
                    tableNode.put("name", tableId);
                    tableNode.put("type", "table");

                    // Get column families
                    JsonNode columnFamilies = tbl.path("columnFamilies");
                    List<String> cfNames = new ArrayList<>();
                    if (columnFamilies.isObject()) {
                        columnFamilies.fieldNames().forEachRemaining(cfNames::add);
                    }
                    tableNode.put("columnFamilies", cfNames);

                    tableNodes.add(tableNode);
                }

                instanceNode.put("tables", tableNodes);
                nodes.add(instanceNode);
            }
        } catch (Exception e) {
            logger.error("browseRemote failed for project {}: {}", project, e.getMessage());
            throw new RuntimeException("Failed to browse Bigtable: " + e.getMessage(), e);
        }
        return new BrowseResult(nodes);
    }

    @Override
    public PreviewResult previewRemote(String project, String resource,
                                        String accessToken, int limit) {
        String[] parts = parseResource(resource);
        String instance = parts[0];
        String table = parts[1];

        try {
            // ReadRows with limit
            ObjectNode readRequest = buildReadRowsRequest(null, null, limit);
            String tablePath = "projects/" + project + "/instances/" + instance + "/tables/" + table;

            JsonNode result = gcpPost(
                    "/v2/" + tablePath + ":readRows",
                    mapper.writeValueAsString(readRequest), accessToken);

            List<String> columns = List.of("rowKey", "columnFamily", "qualifier", "value", "timestamp");
            List<Map<String, Object>> rows = extractBigtableRows(result);

            return new PreviewResult(columns, rows, rows.size(), 0);
        } catch (Exception e) {
            logger.error("previewRemote failed for {}: {}", resource, e.getMessage());
            throw new RuntimeException("Failed to preview Bigtable table: " + e.getMessage(), e);
        }
    }

    @Override
    public CostEstimate estimate(String project, String resource, List<SyncFilter> filters,
                                  int rowLimit, String accessToken) {
        String[] parts = parseResource(resource);
        String instance = parts[0];
        String table = parts[1];

        try {
            // Read a sample to estimate row count
            String rowKeyPrefix = extractRowKeyPrefix(filters);
            ObjectNode readRequest = buildReadRowsRequest(rowKeyPrefix, null, PAGE_SIZE);
            String tablePath = "projects/" + project + "/instances/" + instance + "/tables/" + table;

            JsonNode result = gcpPost(
                    "/v2/" + tablePath + ":readRows",
                    mapper.writeValueAsString(readRequest), accessToken);

            List<Map<String, Object>> sampleRows = extractBigtableRows(result);
            long estimatedRows = sampleRows.size();

            if (rowLimit > 0 && estimatedRows > rowLimit) {
                estimatedRows = rowLimit;
            }

            double cost = estimateReadCost(estimatedRows);
            String details = String.format(
                    "Table: %s/%s | Row key prefix: %s | Estimated rows: %d | $%.4f (at $0.26/million reads)",
                    instance, table,
                    rowKeyPrefix != null ? rowKeyPrefix : "(none)",
                    estimatedRows, cost);

            return new CostEstimate(estimatedRows, 0, cost, details);
        } catch (Exception e) {
            logger.error("estimate failed for {}: {}", resource, e.getMessage());
            throw new RuntimeException("Failed to estimate Bigtable cost: " + e.getMessage(), e);
        }
    }

    @Override
    public SyncResult sync(String project, String resource, List<SyncFilter> filters,
                            int rowLimit, String accessToken, String localProject,
                            SyncProgressCallback progress) {
        String[] parts = parseResource(resource);
        String instance = parts[0];
        String table = parts[1];

        long totalRowsSynced = 0;

        try {
            String rowKeyPrefix = extractRowKeyPrefix(filters);
            String columnFamily = extractColumnFamily(filters);

            // Read rows from remote
            ObjectNode readRequest = buildReadRowsRequest(rowKeyPrefix, columnFamily, rowLimit);
            String tablePath = "projects/" + project + "/instances/" + instance + "/tables/" + table;

            JsonNode result = gcpPost(
                    "/v2/" + tablePath + ":readRows",
                    mapper.writeValueAsString(readRequest), accessToken);

            List<Map<String, Object>> rows = extractBigtableRows(result);

            // Write to local emulator
            if (!rows.isEmpty()) {
                String localTablePath = "projects/" + localProject + "/instances/" + instance
                        + "/tables/" + table;
                List<ObjectNode> mutations = buildMutateRowsEntries(rows);

                // Batch mutations
                for (int i = 0; i < mutations.size(); i += PAGE_SIZE) {
                    int end = Math.min(i + PAGE_SIZE, mutations.size());
                    List<ObjectNode> batch = mutations.subList(i, end);

                    ObjectNode mutateRequest = mapper.createObjectNode();
                    ArrayNode entries = mapper.createArrayNode();
                    batch.forEach(entries::add);
                    mutateRequest.set("entries", entries);

                    localPost("/v2/" + localTablePath + ":mutateRows",
                            mapper.writeValueAsString(mutateRequest));

                    totalRowsSynced += batch.size();
                    if (progress != null) {
                        progress.onProgress(totalRowsSynced, 0, rows.size());
                    }
                }
            }

            double cost = estimateReadCost(totalRowsSynced);
            logger.info("Sync complete: {} rows from {}/{} -> local {}",
                    totalRowsSynced, instance, table, localProject);

            return new SyncResult(0, totalRowsSynced, 0, cost, "completed", null);

        } catch (Exception e) {
            logger.error("sync failed for {}: {}", resource, e.getMessage(), e);
            double cost = estimateReadCost(totalRowsSynced);
            return new SyncResult(0, totalRowsSynced, 0, cost, "failed", e.getMessage());
        }
    }

    // -----------------------------------------------------------------------
    // Package-visible helpers (tested directly)
    // -----------------------------------------------------------------------

    /**
     * Parse an "instance/table" resource string into [instance, table].
     *
     * @throws IllegalArgumentException if the resource is not in the expected format
     */
    String[] parseResource(String resource) {
        if (resource == null || resource.isEmpty()) {
            throw new IllegalArgumentException(
                    "Resource must be in 'instance/table' format, got: " + resource);
        }
        String[] parts = resource.split("/");
        if (parts.length != 2) {
            throw new IllegalArgumentException(
                    "Resource must be in 'instance/table' format (exactly one slash), got: " + resource);
        }
        if (parts[0].isEmpty() || parts[1].isEmpty()) {
            throw new IllegalArgumentException(
                    "Resource must have non-empty instance and table names, got: " + resource);
        }
        return parts;
    }

    /**
     * Calculate the cost in USD for a given number of rows read.
     * Bigtable charges ~$0.26 per million read operations.
     */
    double estimateReadCost(long rowCount) {
        if (rowCount <= 0) return 0.0;
        return COST_PER_MILLION_READS * rowCount / 1_000_000.0;
    }

    /**
     * Extract row key prefix from filters. Looks for a filter with column="rowKey"
     * or column="row_key_prefix".
     */
    String extractRowKeyPrefix(List<SyncFilter> filters) {
        if (filters == null) return null;
        for (SyncFilter f : filters) {
            if ("rowKey".equalsIgnoreCase(f.column())
                    || "row_key_prefix".equalsIgnoreCase(f.column())) {
                return f.value();
            }
        }
        return null;
    }

    /**
     * Extract column family from filters. Looks for a filter with column="columnFamily"
     * or column="column_family".
     */
    String extractColumnFamily(List<SyncFilter> filters) {
        if (filters == null) return null;
        for (SyncFilter f : filters) {
            if ("columnFamily".equalsIgnoreCase(f.column())
                    || "column_family".equalsIgnoreCase(f.column())) {
                return f.value();
            }
        }
        return null;
    }

    /**
     * Build a Bigtable ReadRows request with optional row key prefix, column family filter,
     * and row limit.
     */
    ObjectNode buildReadRowsRequest(String rowKeyPrefix, String columnFamily, int limit) {
        ObjectNode request = mapper.createObjectNode();

        // Row key prefix filter
        if (rowKeyPrefix != null && !rowKeyPrefix.isEmpty()) {
            ObjectNode rows = mapper.createObjectNode();
            ArrayNode rowRanges = mapper.createArrayNode();
            ObjectNode range = mapper.createObjectNode();
            String encodedPrefix = Base64.getEncoder().encodeToString(
                    rowKeyPrefix.getBytes(StandardCharsets.UTF_8));
            range.put("startKeyClosed", encodedPrefix);
            // End key is prefix + 1 (increment last byte)
            byte[] prefixBytes = rowKeyPrefix.getBytes(StandardCharsets.UTF_8);
            byte[] endBytes = Arrays.copyOf(prefixBytes, prefixBytes.length);
            endBytes[endBytes.length - 1]++;
            range.put("endKeyOpen", Base64.getEncoder().encodeToString(endBytes));
            rowRanges.add(range);
            rows.set("rowRanges", rowRanges);
            request.set("rows", rows);
        }

        // Column family filter
        if (columnFamily != null && !columnFamily.isEmpty()) {
            ObjectNode filter = mapper.createObjectNode();
            filter.put("familyNameRegexFilter", columnFamily);
            request.set("filter", filter);
        }

        // Row limit
        if (limit > 0) {
            request.put("rowsLimit", limit);
        }

        return request;
    }

    // -----------------------------------------------------------------------
    // Private helpers — Bigtable row extraction
    // -----------------------------------------------------------------------

    private List<Map<String, Object>> extractBigtableRows(JsonNode result) {
        List<Map<String, Object>> rows = new ArrayList<>();

        // Bigtable ReadRows returns chunks
        JsonNode chunks = result.path("chunks");
        if (chunks.isMissingNode()) {
            // May be wrapped in array of responses
            if (result.isArray()) {
                for (JsonNode resp : result) {
                    chunks = resp.path("chunks");
                    extractChunks(chunks, rows);
                }
            }
            return rows;
        }

        extractChunks(chunks, rows);
        return rows;
    }

    private void extractChunks(JsonNode chunks, List<Map<String, Object>> rows) {
        if (chunks.isMissingNode() || !chunks.isArray()) return;

        Map<String, Object> currentRow = null;
        for (JsonNode chunk : chunks) {
            String rowKey = chunk.path("rowKey").asText(null);
            if (rowKey != null && !rowKey.isEmpty()) {
                if (currentRow != null) {
                    rows.add(currentRow);
                }
                currentRow = new LinkedHashMap<>();
                currentRow.put("rowKey", rowKey);
            }

            if (currentRow == null) {
                currentRow = new LinkedHashMap<>();
            }

            String family = chunk.path("familyName").path("value").asText(null);
            String qualifier = chunk.path("qualifier").path("value").asText(null);
            String value = chunk.path("value").asText(null);
            long timestamp = chunk.path("timestampMicros").asLong(0);

            if (family != null) currentRow.put("columnFamily", family);
            if (qualifier != null) currentRow.put("qualifier", qualifier);
            if (value != null) currentRow.put("value", value);
            if (timestamp > 0) currentRow.put("timestamp", timestamp);

            if (chunk.path("commitRow").asBoolean(false)) {
                rows.add(currentRow);
                currentRow = null;
            }
        }

        // Add last row if not committed
        if (currentRow != null && !currentRow.isEmpty()) {
            rows.add(currentRow);
        }
    }

    private List<ObjectNode> buildMutateRowsEntries(List<Map<String, Object>> rows) {
        List<ObjectNode> entries = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            ObjectNode entry = mapper.createObjectNode();

            String rowKey = row.getOrDefault("rowKey", "").toString();
            entry.put("rowKey", Base64.getEncoder().encodeToString(
                    rowKey.getBytes(StandardCharsets.UTF_8)));

            ArrayNode mutations = mapper.createArrayNode();
            ObjectNode mutation = mapper.createObjectNode();
            ObjectNode setCell = mapper.createObjectNode();

            setCell.put("familyName", row.getOrDefault("columnFamily", "cf").toString());
            setCell.put("columnQualifier", Base64.getEncoder().encodeToString(
                    row.getOrDefault("qualifier", "").toString().getBytes(StandardCharsets.UTF_8)));
            setCell.put("value", Base64.getEncoder().encodeToString(
                    row.getOrDefault("value", "").toString().getBytes(StandardCharsets.UTF_8)));
            setCell.put("timestampMicros",
                    Long.parseLong(row.getOrDefault("timestamp", "0").toString()));

            mutation.set("setCell", setCell);
            mutations.add(mutation);
            entry.set("mutations", mutations);

            entries.add(entry);
        }
        return entries;
    }

    // -----------------------------------------------------------------------
    // Private helpers — GCP HTTP
    // -----------------------------------------------------------------------

    private JsonNode gcpGet(String path, String accessToken) throws IOException {
        HttpURLConnection conn = openGcpConnection(path, "GET", accessToken);
        return readResponse(conn, "Bigtable");
    }

    private JsonNode gcpPost(String path, String body, String accessToken) throws IOException {
        HttpURLConnection conn = openGcpConnection(path, "POST", accessToken);
        conn.setDoOutput(true);
        try (OutputStream os = conn.getOutputStream()) {
            os.write(body.getBytes(StandardCharsets.UTF_8));
        }
        return readResponse(conn, "Bigtable");
    }

    private HttpURLConnection openGcpConnection(String path, String method,
                                                  String accessToken) throws IOException {
        String url = GCP_BIGTABLE_BASE + path;
        HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
        conn.setRequestMethod(method);
        conn.setRequestProperty("Authorization", "Bearer " + accessToken);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setConnectTimeout(TIMEOUT_MS);
        conn.setReadTimeout(TIMEOUT_MS);
        return conn;
    }

    private JsonNode readResponse(HttpURLConnection conn, String serviceName) throws IOException {
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
            throw new IOException(serviceName + " API returned " + statusCode + ": " + body);
        }

        return mapper.readTree(body);
    }

    // -----------------------------------------------------------------------
    // Private helpers — Local emulator HTTP
    // -----------------------------------------------------------------------

    private void localPost(String path, String body) throws IOException {
        String url = "http://" + localEmulatorHost + ":" + localEmulatorPort + path;
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
            throw new IOException("Local Bigtable emulator returned " + statusCode + ": " + responseBody);
        }
    }
}
