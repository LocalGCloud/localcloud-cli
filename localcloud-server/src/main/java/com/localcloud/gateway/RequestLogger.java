package com.localcloud.gateway;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * Thread-safe ring buffer that stores recent API requests for debugging
 * and the admin dashboard. Uses a ConcurrentLinkedDeque with a capacity
 * limit; oldest entries are evicted when the buffer is full.
 */
public class RequestLogger {

    /**
     * Immutable record representing a single logged API request.
     */
    public record RequestLogEntry(
            String id,
            Instant timestamp,
            String service,
            String method,
            String path,
            int statusCode,
            long durationMs,
            long requestSize,
            long responseSize
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
                    service,
                    method,
                    path,
                    statusCode,
                    durationMs,
                    requestSize,
                    responseSize
            );
        }
    }

    private static final int DEFAULT_CAPACITY = 1000;

    private final int maxSize;
    private final ConcurrentLinkedDeque<RequestLogEntry> entries;

    public RequestLogger() {
        this(DEFAULT_CAPACITY);
    }

    public RequestLogger(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("Capacity must be positive, got: " + capacity);
        }
        this.maxSize = capacity;
        this.entries = new ConcurrentLinkedDeque<>();
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
}
