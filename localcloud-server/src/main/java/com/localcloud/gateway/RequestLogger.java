package com.localcloud.gateway;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Thread-safe ring buffer that stores recent API requests for debugging
 * and the admin dashboard. Uses a ConcurrentLinkedDeque with a capacity
 * limit; oldest entries are evicted when the buffer is full.
 *
 * <p>Supports optional request/response body capture (enabled by default)
 * with a configurable max body size (default 100KB). Bodies exceeding the
 * limit are truncated with a "...truncated" suffix.
 */
public class RequestLogger {

    /**
     * Immutable record representing a single logged API request.
     */
    public record RequestLogEntry(
            String id,
            Instant timestamp,
            String traceId,
            String service,
            String method,
            String path,
            int statusCode,
            long durationMs,
            long requestSize,
            long responseSize,
            String requestBody,
            String responseBody
    ) {
        /**
         * Create a new entry with an auto-generated ID and current timestamp.
         */
        public static RequestLogEntry create(String service, String method, String path,
                                              int statusCode, long durationMs,
                                              long requestSize, long responseSize) {
            return new RequestLogEntry(
                    UUID.randomUUID().toString(),
                    Instant.now(),
                    null,
                    service,
                    method,
                    path,
                    statusCode,
                    durationMs,
                    requestSize,
                    responseSize,
                    null,
                    null
            );
        }

        /**
         * Create a new entry with body capture and trace ID.
         */
        public static RequestLogEntry create(String traceId, String service, String method, String path,
                                              int statusCode, long durationMs,
                                              long requestSize, long responseSize,
                                              String requestBody, String responseBody) {
            return new RequestLogEntry(
                    UUID.randomUUID().toString(),
                    Instant.now(),
                    traceId,
                    service,
                    method,
                    path,
                    statusCode,
                    durationMs,
                    requestSize,
                    responseSize,
                    requestBody,
                    responseBody
            );
        }
    }

    private static final int DEFAULT_CAPACITY = 1000;
    private static final int DEFAULT_MAX_BODY_SIZE = 100_000; // 100KB

    private final int maxSize;
    private final int maxBodySize;
    private final ConcurrentLinkedDeque<RequestLogEntry> entries;
    private final AtomicBoolean captureBodies = new AtomicBoolean(true);

    public RequestLogger() {
        this(DEFAULT_CAPACITY, DEFAULT_MAX_BODY_SIZE);
    }

    public RequestLogger(int capacity) {
        this(capacity, DEFAULT_MAX_BODY_SIZE);
    }

    public RequestLogger(int capacity, int maxBodySize) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("Capacity must be positive, got: " + capacity);
        }
        this.maxSize = capacity;
        this.maxBodySize = maxBodySize;
        this.entries = new ConcurrentLinkedDeque<>();
    }

    /**
     * Toggle body capture on/off at runtime.
     */
    public void setCaptureBodies(boolean enabled) {
        captureBodies.set(enabled);
    }

    /**
     * Check if body capture is enabled.
     */
    public boolean isCaptureBodies() {
        return captureBodies.get();
    }

    /**
     * Truncate a body string to the configured max size.
     */
    public String truncateBody(String body) {
        if (body == null || body.isEmpty()) return null;
        if (body.length() <= maxBodySize) return body;
        return body.substring(0, maxBodySize) + "...truncated";
    }

    /**
     * Safely convert bytes to string for body capture.
     */
    public String bytesToString(byte[] bytes) {
        if (bytes == null || bytes.length == 0) return null;
        try {
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "[binary content, " + bytes.length + " bytes]";
        }
    }

    /**
     * Log a new request entry. If the buffer is full, the oldest entry is evicted.
     */
    public void log(RequestLogEntry entry) {
        entries.addFirst(entry);
        // Trim to max size
        while (entries.size() > maxSize) {
            entries.pollLast();
        }
    }

    /**
     * Get recent entries, optionally filtered by service name.
     *
     * @param service service name filter, or null for all services
     * @param limit   maximum number of entries to return
     * @return list of matching entries, most recent first
     */
    public List<RequestLogEntry> getEntries(String service, int limit) {
        List<RequestLogEntry> result = new ArrayList<>();
        for (RequestLogEntry entry : entries) {
            if (result.size() >= limit) {
                break;
            }
            if (service == null || service.equals(entry.service())) {
                result.add(entry);
            }
        }
        return Collections.unmodifiableList(result);
    }

    /**
     * Get recent entries filtered by service and time range.
     *
     * @param service service name filter, or null for all services
     * @param since   only return entries at or after this instant
     * @param limit   maximum number of entries to return
     * @return list of matching entries, most recent first
     */
    public List<RequestLogEntry> getEntries(String service, Instant since, int limit) {
        List<RequestLogEntry> result = new ArrayList<>();
        for (RequestLogEntry entry : entries) {
            if (result.size() >= limit) {
                break;
            }
            if (entry.timestamp().isBefore(since)) {
                // Entries are ordered newest-first; once we pass 'since', all remaining are older
                break;
            }
            if (service == null || service.equals(entry.service())) {
                result.add(entry);
            }
        }
        return Collections.unmodifiableList(result);
    }

    /**
     * Get a single entry by trace ID.
     *
     * @param traceId the correlation ID to look up
     * @return the matching entry, or null if not found
     */
    public RequestLogEntry getByTraceId(String traceId) {
        if (traceId == null) return null;
        for (RequestLogEntry entry : entries) {
            if (traceId.equals(entry.traceId())) {
                return entry;
            }
        }
        return null;
    }

    /**
     * Get all entries sharing the same trace ID (for multi-hop tracing).
     *
     * @param traceId the correlation ID to look up
     * @return list of matching entries, most recent first
     */
    public List<RequestLogEntry> getByTraceIdAll(String traceId) {
        List<RequestLogEntry> result = new ArrayList<>();
        if (traceId == null) return result;
        for (RequestLogEntry entry : entries) {
            if (traceId.equals(entry.traceId())) {
                result.add(entry);
            }
        }
        return Collections.unmodifiableList(result);
    }

    /**
     * Clear all logged entries.
     */
    public void clear() {
        entries.clear();
    }

    /**
     * Current number of stored entries.
     */
    public int getSize() {
        return entries.size();
    }

    /**
     * Maximum capacity of this ring buffer.
     */
    public int getCapacity() {
        return maxSize;
    }

    /**
     * Maximum body size for captured request/response bodies.
     */
    public int getMaxBodySize() {
        return maxBodySize;
    }
}
