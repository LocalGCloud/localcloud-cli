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
 * Sync adapter for Google Cloud Firestore.
 *
 * <p>Pulls documents from a real GCP Firestore project into the local Firestore
 * emulator (port 8086). This lets developers pull filtered subsets of production
 * data for local development and testing.
 *
 * <p>GCP REST API docs:
 * <ul>
 *   <li>List collections: GET /v1/projects/{p}/databases/(default)/documents
 *   <li>List documents:   GET /v1/projects/{p}/databases/(default)/documents/{collectionId}
 *   <li>Run query:        POST /v1/projects/{p}/databases/(default)/documents:runQuery
 *   <li>Create document:  POST /v1/projects/{p}/databases/(default)/documents/{collectionId}
 * </ul>
 */
public class FirestoreSyncAdapter implements SyncAdapter {

    private static final Logger logger = LoggerFactory.getLogger(FirestoreSyncAdapter.class);

    private static final String GCP_FIRESTORE_BASE = "https://firestore.googleapis.com";
    private static final int TIMEOUT_MS = 60_000;
    private static final int PAGE_SIZE = 300;

    /** Firestore charges ~$0.06 per 100K document reads. */
    private static final double COST_PER_100K_READS = 0.06;

    private final String localEmulatorHost;
    private final int localEmulatorPort;
    private final ObjectMapper mapper;

    public FirestoreSyncAdapter(String localEmulatorHost, int localEmulatorPort, ObjectMapper mapper) {
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
            // List root document collections
            JsonNode collectionsResp = gcpGet(
                    "/v1/projects/" + project + "/databases/(default)/documents",
                    accessToken);
            JsonNode documents = collectionsResp.path("documents");

            // Extract unique collection IDs from document names
            Set<String> collectionIds = new LinkedHashSet<>();
            for (JsonNode doc : documents) {
                String name = doc.path("name").asText();
                // name format: projects/{p}/databases/(default)/documents/{collection}/{docId}
                String[] pathParts = name.split("/");
                if (pathParts.length >= 6) {
                    collectionIds.add(pathParts[5]);
                }
            }

            for (String collectionId : collectionIds) {
                Map<String, Object> collectionNode = new LinkedHashMap<>();
                collectionNode.put("id", collectionId);
                collectionNode.put("type", "collection");

                // Count documents in collection
                JsonNode docsResp = gcpGet(
                        "/v1/projects/" + project + "/databases/(default)/documents/"
                                + collectionId + "?pageSize=" + PAGE_SIZE,
                        accessToken);
                JsonNode docs = docsResp.path("documents");
                int docCount = docs.isArray() ? docs.size() : 0;
                collectionNode.put("documentCount", docCount);

                // Extract field names from first document if available
                if (docCount > 0) {
                    JsonNode firstDoc = docs.get(0);
                    JsonNode fields = firstDoc.path("fields");
                    List<String> fieldNames = new ArrayList<>();
                    fields.fieldNames().forEachRemaining(fieldNames::add);
                    collectionNode.put("fields", fieldNames);
                }

                nodes.add(collectionNode);
            }
        } catch (Exception e) {
            logger.error("browseRemote failed for project {}: {}", project, e.getMessage());
            throw new RuntimeException("Failed to browse Firestore: " + e.getMessage(), e);
        }
        return new BrowseResult(nodes);
    }

    @Override
    public PreviewResult previewRemote(String project, String resource,
                                        String accessToken, int limit) {
        String collectionId = parseResource(resource);

        try {
            // Use runQuery to get documents with limit
            ObjectNode queryBody = buildRunQuery(collectionId, null, limit);
            JsonNode result = gcpPost(
                    "/v1/projects/" + project + "/databases/(default)/documents:runQuery",
                    mapper.writeValueAsString(queryBody), accessToken);

            List<String> columns = new ArrayList<>();
            List<Map<String, Object>> rows = new ArrayList<>();
            Set<String> seenColumns = new LinkedHashSet<>();

            if (result.isArray()) {
                for (JsonNode entry : result) {
                    JsonNode doc = entry.path("document");
                    if (doc.isMissingNode()) continue;

                    JsonNode fields = doc.path("fields");
                    Map<String, Object> row = new LinkedHashMap<>();

                    // Extract document ID from name
                    String docName = doc.path("name").asText();
                    String docId = docName.substring(docName.lastIndexOf('/') + 1);
                    row.put("__documentId__", docId);
                    seenColumns.add("__documentId__");

                    fields.fieldNames().forEachRemaining(fieldName -> {
                        seenColumns.add(fieldName);
                        row.put(fieldName, extractFirestoreValue(fields.get(fieldName)));
                    });
                    rows.add(row);
                }
            }

            columns.addAll(seenColumns);
            return new PreviewResult(columns, rows, rows.size(), 0);
        } catch (Exception e) {
            logger.error("previewRemote failed for {}: {}", resource, e.getMessage());
            throw new RuntimeException("Failed to preview Firestore collection: " + e.getMessage(), e);
        }
    }

    @Override
    public CostEstimate estimate(String project, String resource, List<SyncFilter> filters,
                                  int rowLimit, String accessToken) {
        String collectionId = parseResource(resource);

        try {
            // Run a count query to estimate document count
            ObjectNode countQuery = buildCountQuery(collectionId, filters);
            JsonNode result = gcpPost(
                    "/v1/projects/" + project + "/databases/(default)/documents:runQuery",
                    mapper.writeValueAsString(countQuery), accessToken);

            long estimatedDocs = 0;
            if (result.isArray() && result.size() > 0) {
                // Count from result documents
                for (JsonNode entry : result) {
                    if (!entry.path("document").isMissingNode()) {
                        estimatedDocs++;
                    }
                }
            }

            if (rowLimit > 0 && estimatedDocs > rowLimit) {
                estimatedDocs = rowLimit;
            }

            double cost = estimateReadCost(estimatedDocs);
            String details = String.format(
                    "Collection: %s | Estimated documents: %d | $%.4f (at $0.06/100K reads)",
                    collectionId, estimatedDocs, cost);

            return new CostEstimate(estimatedDocs, 0, cost, details);
        } catch (Exception e) {
            logger.error("estimate failed for {}: {}", resource, e.getMessage());
            throw new RuntimeException("Failed to estimate Firestore cost: " + e.getMessage(), e);
        }
    }

    @Override
    public SyncResult sync(String project, String resource, List<SyncFilter> filters,
                            int rowLimit, String accessToken, String localProject,
                            SyncProgressCallback progress) {
        String collectionId = parseResource(resource);

        long totalDocsSynced = 0;

        try {
            // Run query on remote
            ObjectNode queryBody = buildRunQuery(collectionId, filters, rowLimit);
            JsonNode result = gcpPost(
                    "/v1/projects/" + project + "/databases/(default)/documents:runQuery",
                    mapper.writeValueAsString(queryBody), accessToken);

            if (result.isArray()) {
                for (JsonNode entry : result) {
                    JsonNode doc = entry.path("document");
                    if (doc.isMissingNode()) continue;

                    // Write document to local emulator
                    String docName = doc.path("name").asText();
                    String docId = docName.substring(docName.lastIndexOf('/') + 1);

                    ObjectNode localDoc = mapper.createObjectNode();
                    localDoc.set("fields", doc.path("fields"));

                    String localPath = "/v1/projects/" + localProject
                            + "/databases/(default)/documents/" + collectionId + "/" + docId;
                    localPatch(localPath, mapper.writeValueAsString(localDoc));

                    totalDocsSynced++;
                    if (progress != null) {
                        progress.onProgress(totalDocsSynced, 0, 0);
                    }
                }
            }

            double cost = estimateReadCost(totalDocsSynced);
            logger.info("Sync complete: {} documents from {} -> local {}",
                    totalDocsSynced, collectionId, localProject);

            return new SyncResult(0, totalDocsSynced, 0, cost, "completed", null);

        } catch (Exception e) {
            logger.error("sync failed for {}: {}", resource, e.getMessage(), e);
            double cost = estimateReadCost(totalDocsSynced);
            return new SyncResult(0, totalDocsSynced, 0, cost, "failed", e.getMessage());
        }
    }

    // -----------------------------------------------------------------------
    // Package-visible helpers (tested directly)
    // -----------------------------------------------------------------------

    /**
     * Parse a Firestore resource string — expects a collection ID (no dots or slashes).
     *
     * @throws IllegalArgumentException if the resource is null or empty
     */
    String parseResource(String resource) {
        if (resource == null || resource.isEmpty()) {
            throw new IllegalArgumentException(
                    "Resource must be a collection ID, got: " + resource);
        }
        // A collection ID may contain subcollection paths separated by /
        // but must not be empty after trimming
        String trimmed = resource.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException(
                    "Resource must be a non-empty collection ID, got: " + resource);
        }
        return trimmed;
    }

    /**
     * Calculate the cost in USD for a given number of document reads.
     * Firestore charges ~$0.06 per 100K document reads.
     */
    double estimateReadCost(long documentCount) {
        if (documentCount <= 0) return 0.0;
        return COST_PER_100K_READS * documentCount / 100_000.0;
    }

    /**
     * Build a Firestore structured query with optional filters and limit.
     */
    ObjectNode buildRunQuery(String collectionId, List<SyncFilter> filters, int limit) {
        ObjectNode body = mapper.createObjectNode();
        ObjectNode structuredQuery = mapper.createObjectNode();

        // from clause
        ArrayNode from = mapper.createArrayNode();
        ObjectNode collRef = mapper.createObjectNode();
        collRef.put("collectionId", collectionId);
        from.add(collRef);
        structuredQuery.set("from", from);

        // where clause (filters)
        if (filters != null && !filters.isEmpty()) {
            ObjectNode where = buildWhereClause(filters);
            structuredQuery.set("where", where);
        }

        // limit
        if (limit > 0) {
            structuredQuery.put("limit", limit);
        }

        body.set("structuredQuery", structuredQuery);
        return body;
    }

    /**
     * Build a Firestore structured query for counting (used in estimate).
     */
    ObjectNode buildCountQuery(String collectionId, List<SyncFilter> filters) {
        // Use the same runQuery approach — we just count the results
        return buildRunQuery(collectionId, filters, 0);
    }

    /**
     * Build a Firestore where clause from sync filters.
     * Single filter: fieldFilter. Multiple filters: compositeFilter with AND.
     */
    ObjectNode buildWhereClause(List<SyncFilter> filters) {
        if (filters.size() == 1) {
            return buildFieldFilter(filters.get(0));
        }

        ObjectNode compositeFilter = mapper.createObjectNode();
        ObjectNode composite = mapper.createObjectNode();
        composite.put("op", "AND");
        ArrayNode filtersArray = mapper.createArrayNode();
        for (SyncFilter f : filters) {
            filtersArray.add(buildFieldFilter(f));
        }
        composite.set("filters", filtersArray);
        compositeFilter.set("compositeFilter", composite);
        return compositeFilter;
    }

    // -----------------------------------------------------------------------
    // Private helpers — filter building
    // -----------------------------------------------------------------------

    private ObjectNode buildFieldFilter(SyncFilter filter) {
        ObjectNode wrapper = mapper.createObjectNode();
        ObjectNode fieldFilter = mapper.createObjectNode();

        ObjectNode field = mapper.createObjectNode();
        field.put("fieldPath", filter.column());
        fieldFilter.set("field", field);

        fieldFilter.put("op", mapFilterOperator(filter.operator()));
        fieldFilter.set("value", buildFirestoreValue(filter.value(), filter.columnType()));

        wrapper.set("fieldFilter", fieldFilter);
        return wrapper;
    }

    /**
     * Map standard SQL operators to Firestore filter operators.
     */
    String mapFilterOperator(String operator) {
        return switch (operator.toUpperCase()) {
            case "=" -> "EQUAL";
            case "!=" -> "NOT_EQUAL";
            case "<" -> "LESS_THAN";
            case "<=" -> "LESS_THAN_OR_EQUAL";
            case ">" -> "GREATER_THAN";
            case ">=" -> "GREATER_THAN_OR_EQUAL";
            default -> operator;
        };
    }

    /**
     * Build a Firestore Value JSON node from a string value and column type.
     */
    private ObjectNode buildFirestoreValue(String value, String columnType) {
        ObjectNode valueNode = mapper.createObjectNode();
        if (columnType == null) {
            valueNode.put("stringValue", value);
            return valueNode;
        }
        switch (columnType.toUpperCase()) {
            case "INT64", "INTEGER" -> valueNode.put("integerValue", value);
            case "FLOAT64", "FLOAT", "NUMERIC", "DOUBLE" -> valueNode.put("doubleValue", Double.parseDouble(value));
            case "BOOL", "BOOLEAN" -> valueNode.put("booleanValue", Boolean.parseBoolean(value));
            default -> valueNode.put("stringValue", value);
        }
        return valueNode;
    }

    /**
     * Extract a plain Java value from a Firestore value node.
     */
    private Object extractFirestoreValue(JsonNode valueNode) {
        if (valueNode.has("stringValue")) return valueNode.get("stringValue").asText();
        if (valueNode.has("integerValue")) return valueNode.get("integerValue").asText();
        if (valueNode.has("doubleValue")) return valueNode.get("doubleValue").asDouble();
        if (valueNode.has("booleanValue")) return valueNode.get("booleanValue").asBoolean();
        if (valueNode.has("nullValue")) return null;
        if (valueNode.has("timestampValue")) return valueNode.get("timestampValue").asText();
        if (valueNode.has("mapValue")) return valueNode.get("mapValue").toString();
        if (valueNode.has("arrayValue")) return valueNode.get("arrayValue").toString();
        return valueNode.toString();
    }

    // -----------------------------------------------------------------------
    // Private helpers — GCP HTTP
    // -----------------------------------------------------------------------

    private JsonNode gcpGet(String path, String accessToken) throws IOException {
        HttpURLConnection conn = openGcpConnection(path, "GET", accessToken);
        return readResponse(conn, "Firestore");
    }

    private JsonNode gcpPost(String path, String body, String accessToken) throws IOException {
        HttpURLConnection conn = openGcpConnection(path, "POST", accessToken);
        conn.setDoOutput(true);
        try (OutputStream os = conn.getOutputStream()) {
            os.write(body.getBytes(StandardCharsets.UTF_8));
        }
        return readResponse(conn, "Firestore");
    }

    private HttpURLConnection openGcpConnection(String path, String method,
                                                  String accessToken) throws IOException {
        String url = GCP_FIRESTORE_BASE + path;
        HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
        conn.setRequestMethod(method);
        conn.setRequestProperty("Authorization", "Bearer " + accessToken);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setConnectTimeout(TIMEOUT_MS);
        conn.setReadTimeout(TIMEOUT_MS);
        return conn;
    }

    // -----------------------------------------------------------------------
    // Private helpers — Local emulator HTTP
    // -----------------------------------------------------------------------

    private void localPatch(String path, String body) throws IOException {
        String url = "http://" + localEmulatorHost + ":" + localEmulatorPort + path;
        HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
        conn.setRequestMethod("POST"); // PATCH via POST with override
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("X-HTTP-Method-Override", "PATCH");
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
    // Private helpers — response reading
    // -----------------------------------------------------------------------

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
}
