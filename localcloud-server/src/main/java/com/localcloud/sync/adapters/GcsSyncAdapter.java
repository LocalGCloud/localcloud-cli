package com.localcloud.sync.adapters;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
 * Sync adapter for Google Cloud Storage.
 *
 * <p>Pulls objects from a real GCP Cloud Storage bucket into the local
 * fake-gcs-server emulator (port 4443). This lets developers work with
 * production data locally without repeated downloads.
 *
 * <p>GCP REST API docs:
 * <ul>
 *   <li>List buckets: GET /storage/v1/b?project={p}
 *   <li>List objects: GET /storage/v1/b/{bucket}/o?prefix=...
 *   <li>Get object:   GET /storage/v1/b/{bucket}/o/{name}?alt=media
 *   <li>Upload:       POST /upload/storage/v1/b/{bucket}/o?uploadType=media&name={name}
 * </ul>
 */
public class GcsSyncAdapter implements SyncAdapter {

    private static final Logger logger = LoggerFactory.getLogger(GcsSyncAdapter.class);

    private static final String GCP_GCS_BASE = "https://storage.googleapis.com";
    private static final int TIMEOUT_MS = 120_000;
    private static final int PAGE_SIZE = 1000;

    /** Maximum object size to sync (100 MB). Objects larger than this are skipped. */
    static final long MAX_OBJECT_SIZE = 100L * 1024 * 1024;

    /** GCS charges ~$0.004 per 10K Class A operations + egress ($0.12/GB). */
    private static final double COST_PER_10K_OPS = 0.004;
    private static final double EGRESS_PER_GB = 0.12;

    private final String localEmulatorBase;
    private final ObjectMapper mapper;
    private final RetryableHttpClient httpClient;

    public GcsSyncAdapter(String localEmulatorBase, ObjectMapper mapper) {
        this.localEmulatorBase = localEmulatorBase;
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
            // List buckets
            JsonNode bucketsResp = gcpGet(
                    "/storage/v1/b?project=" + project, accessToken);
            JsonNode items = bucketsResp.path("items");

            for (JsonNode bucket : items) {
                String bucketName = bucket.path("name").asText();
                String storageClass = bucket.path("storageClass").asText("STANDARD");
                String location = bucket.path("location").asText("");

                Map<String, Object> bucketNode = new LinkedHashMap<>();
                bucketNode.put("id", bucketName);
                bucketNode.put("type", "bucket");
                bucketNode.put("storageClass", storageClass);
                bucketNode.put("location", location);

                // List top-level objects (just prefixes/delimited)
                JsonNode objectsResp = gcpGet(
                        "/storage/v1/b/" + bucketName + "/o?delimiter=/&maxResults=100",
                        accessToken);

                List<String> prefixes = new ArrayList<>();
                JsonNode prefixesNode = objectsResp.path("prefixes");
                if (prefixesNode.isArray()) {
                    for (JsonNode p : prefixesNode) {
                        prefixes.add(p.asText());
                    }
                }
                bucketNode.put("prefixes", prefixes);

                int objectCount = 0;
                JsonNode objItems = objectsResp.path("items");
                if (objItems.isArray()) {
                    objectCount = objItems.size();
                }
                bucketNode.put("topLevelObjects", objectCount);

                nodes.add(bucketNode);
            }
        } catch (Exception e) {
            logger.error("browseRemote failed for project {}: {}", project, e.getMessage());
            throw new RuntimeException("Failed to browse GCS: " + e.getMessage(), e);
        }
        return new BrowseResult(nodes);
    }

    @Override
    public PreviewResult previewRemote(String project, String resource,
                                        String accessToken, int limit) {
        String[] parts = parseResource(resource);
        String bucket = parts[0];
        String prefix = parts[1];

        try {
            // List objects with prefix
            String url = "/storage/v1/b/" + bucket + "/o?maxResults=" + limit;
            if (prefix != null && !prefix.isEmpty()) {
                url += "&prefix=" + prefix;
            }
            JsonNode result = gcpGet(url, accessToken);

            List<String> columns = List.of("name", "size", "contentType", "updated", "storageClass");
            List<Map<String, Object>> rows = new ArrayList<>();

            JsonNode items = result.path("items");
            for (JsonNode obj : items) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("name", obj.path("name").asText());
                row.put("size", obj.path("size").asText("0"));
                row.put("contentType", obj.path("contentType").asText(""));
                row.put("updated", obj.path("updated").asText(""));
                row.put("storageClass", obj.path("storageClass").asText(""));
                rows.add(row);
            }

            long totalObjects = rows.size();
            long totalSize = rows.stream()
                    .mapToLong(r -> Long.parseLong(r.getOrDefault("size", "0").toString()))
                    .sum();

            return new PreviewResult(columns, rows, totalObjects, totalSize);
        } catch (Exception e) {
            logger.error("previewRemote failed for {}: {}", resource, e.getMessage());
            throw new RuntimeException("Failed to preview GCS objects: " + e.getMessage(), e);
        }
    }

    @Override
    public CostEstimate estimate(String project, String resource, List<SyncFilter> filters,
                                  int rowLimit, String accessToken) {
        String[] parts = parseResource(resource);
        String bucket = parts[0];
        String prefix = parts[1];

        try {
            // Count objects and total size
            long objectCount = 0;
            long totalSize = 0;
            String pageToken = null;

            do {
                String url = "/storage/v1/b/" + bucket + "/o?maxResults=" + PAGE_SIZE;
                if (prefix != null && !prefix.isEmpty()) {
                    url += "&prefix=" + prefix;
                }
                if (pageToken != null) {
                    url += "&pageToken=" + pageToken;
                }

                JsonNode result = gcpGet(url, accessToken);
                JsonNode items = result.path("items");
                for (JsonNode obj : items) {
                    objectCount++;
                    totalSize += obj.path("size").asLong(0);
                }
                pageToken = result.path("nextPageToken").asText(null);
            } while (pageToken != null && !pageToken.isEmpty());

            if (rowLimit > 0 && objectCount > rowLimit) {
                // Scale down size estimate proportionally
                totalSize = (long) ((double) totalSize / objectCount * rowLimit);
                objectCount = rowLimit;
            }

            double cost = estimateCost(objectCount, totalSize);
            String details = String.format(
                    "Bucket: %s | Prefix: %s | Objects: %d | Size: %s | $%.4f (ops + egress)",
                    bucket, prefix != null ? prefix : "(none)",
                    objectCount, formatBytes(totalSize), cost);

            return new CostEstimate(objectCount, totalSize, cost, details);
        } catch (Exception e) {
            logger.error("estimate failed for {}: {}", resource, e.getMessage());
            throw new RuntimeException("Failed to estimate GCS cost: " + e.getMessage(), e);
        }
    }

    @Override
    public SyncResult sync(String project, String resource, List<SyncFilter> filters,
                            int rowLimit, String accessToken, String localProject,
                            SyncProgressCallback progress) {
        String[] parts = parseResource(resource);
        String bucket = parts[0];
        String prefix = parts[1];

        long totalObjectsSynced = 0;
        long totalBytesSynced = 0;
        int skippedCount = 0;

        try {
            // Ensure local bucket exists
            ensureLocalBucket(bucket);

            // List remote objects
            String pageToken = null;
            boolean limitReached = false;

            do {
                String url = "/storage/v1/b/" + bucket + "/o?maxResults=" + PAGE_SIZE;
                if (prefix != null && !prefix.isEmpty()) {
                    url += "&prefix=" + prefix;
                }
                if (pageToken != null) {
                    url += "&pageToken=" + pageToken;
                }

                JsonNode result = gcpGet(url, accessToken);
                JsonNode items = result.path("items");

                for (JsonNode obj : items) {
                    if (rowLimit > 0 && totalObjectsSynced >= rowLimit) {
                        limitReached = true;
                        break;
                    }

                    String objectName = obj.path("name").asText();
                    long objectSize = obj.path("size").asLong(0);
                    String contentType = obj.path("contentType").asText("application/octet-stream");

                    // Skip objects exceeding the max size limit
                    if (objectSize > MAX_OBJECT_SIZE) {
                        logger.warn("Skipping oversized object: {} ({} bytes, max {})",
                                objectName, objectSize, MAX_OBJECT_SIZE);
                        skippedCount++;
                        continue;
                    }

                    // Download from remote
                    byte[] data = downloadObject(bucket, objectName, accessToken);

                    // Upload to local emulator
                    uploadToLocal(bucket, objectName, data, contentType);

                    totalObjectsSynced++;
                    totalBytesSynced += objectSize;

                    if (progress != null) {
                        progress.onProgress(totalObjectsSynced, totalBytesSynced, 0);
                    }
                }

                if (limitReached) break;
                pageToken = result.path("nextPageToken").asText(null);
            } while (pageToken != null && !pageToken.isEmpty());

            double cost = estimateCost(totalObjectsSynced, totalBytesSynced);
            String errorDetail = null;
            if (skippedCount > 0) {
                errorDetail = skippedCount + " object(s) skipped (exceeded "
                        + MAX_OBJECT_SIZE + " byte max size)";
                logger.info("Sync complete with skips: {} objects, {} bytes, {} skipped from {}/{} -> local",
                        totalObjectsSynced, totalBytesSynced, skippedCount, bucket, prefix);
            } else {
                logger.info("Sync complete: {} objects, {} bytes from {}/{} -> local",
                        totalObjectsSynced, totalBytesSynced, bucket, prefix);
            }

            return new SyncResult(0, totalObjectsSynced, totalBytesSynced, cost,
                    "completed", errorDetail);

        } catch (Exception e) {
            logger.error("sync failed for {}: {}", resource, e.getMessage(), e);
            double cost = estimateCost(totalObjectsSynced, totalBytesSynced);
            return new SyncResult(0, totalObjectsSynced, totalBytesSynced, cost,
                    "failed", e.getMessage());
        }
    }

    // -----------------------------------------------------------------------
    // Package-visible helpers (tested directly)
    // -----------------------------------------------------------------------

    /**
     * Parse a "bucket/prefix" resource string into [bucket, prefix].
     * If no slash is present, the entire string is the bucket with no prefix.
     *
     * @throws IllegalArgumentException if the resource is null or empty
     */
    String[] parseResource(String resource) {
        if (resource == null || resource.isEmpty()) {
            throw new IllegalArgumentException(
                    "Resource must be in 'bucket' or 'bucket/prefix' format, got: " + resource);
        }
        int slashIndex = resource.indexOf('/');
        if (slashIndex < 0) {
            // Bucket only, no prefix
            return new String[]{resource, null};
        }
        String bucket = resource.substring(0, slashIndex);
        if (bucket.isEmpty()) {
            throw new IllegalArgumentException(
                    "Resource must have a non-empty bucket name, got: " + resource);
        }
        String prefix = resource.substring(slashIndex + 1);
        return new String[]{bucket, prefix.isEmpty() ? null : prefix};
    }

    /**
     * Calculate the cost in USD for object operations + egress.
     * GCS charges ~$0.004 per 10K Class A operations + $0.12/GB egress.
     */
    double estimateCost(long objectCount, long totalBytes) {
        if (objectCount <= 0 && totalBytes <= 0) return 0.0;
        double opsCost = COST_PER_10K_OPS * objectCount / 10_000.0;
        double egressCost = EGRESS_PER_GB * totalBytes / (1024.0 * 1024 * 1024);
        return opsCost + egressCost;
    }

    // -----------------------------------------------------------------------
    // Private helpers — GCP HTTP (delegated to RetryableHttpClient)
    // -----------------------------------------------------------------------

    private JsonNode gcpGet(String path, String accessToken) throws IOException {
        String url = GCP_GCS_BASE + path;
        String body = httpClient.get(url, accessToken).body();
        return mapper.readTree(body);
    }

    private byte[] downloadObject(String bucket, String objectName,
                                   String accessToken) throws IOException {
        String encodedName = objectName.replace("/", "%2F");
        String url = GCP_GCS_BASE + "/storage/v1/b/" + bucket + "/o/" + encodedName + "?alt=media";
        // Download uses get() with retry; body is returned as String, convert to bytes
        String body = httpClient.get(url, accessToken).body();
        return body.getBytes(StandardCharsets.UTF_8);
    }

    // -----------------------------------------------------------------------
    // Private helpers — Local emulator HTTP
    // -----------------------------------------------------------------------

    private void ensureLocalBucket(String bucket) throws IOException {
        String url = localEmulatorBase + "/storage/v1/b";
        HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setConnectTimeout(TIMEOUT_MS);
        conn.setReadTimeout(TIMEOUT_MS);
        conn.setDoOutput(true);

        String body = "{\"name\":\"" + bucket + "\"}";
        try (OutputStream os = conn.getOutputStream()) {
            os.write(body.getBytes(StandardCharsets.UTF_8));
        }

        int statusCode = conn.getResponseCode();
        InputStream is = (statusCode >= 200 && statusCode < 300)
                ? conn.getInputStream()
                : conn.getErrorStream();
        if (is != null) is.readAllBytes(); // drain
        conn.disconnect();

        // Ignore 409 Conflict (bucket already exists)
        if (statusCode >= 300 && statusCode != 409) {
            throw new IOException("Failed to create local bucket: HTTP " + statusCode);
        }
    }

    private void uploadToLocal(String bucket, String objectName, byte[] data,
                                String contentType) throws IOException {
        String encodedName = objectName.replace("/", "%2F");
        String url = localEmulatorBase + "/upload/storage/v1/b/" + bucket
                + "/o?uploadType=media&name=" + encodedName;
        HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", contentType);
        conn.setConnectTimeout(TIMEOUT_MS);
        conn.setReadTimeout(TIMEOUT_MS);
        conn.setDoOutput(true);

        try (OutputStream os = conn.getOutputStream()) {
            os.write(data);
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
            throw new IOException("Local GCS upload returned " + statusCode + ": " + responseBody);
        }
    }

    // -----------------------------------------------------------------------
    // Private helpers — formatting
    // -----------------------------------------------------------------------

    private String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
        if (bytes < 1024L * 1024 * 1024 * 1024) return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
        return String.format("%.2f TB", bytes / (1024.0 * 1024 * 1024 * 1024));
    }
}
