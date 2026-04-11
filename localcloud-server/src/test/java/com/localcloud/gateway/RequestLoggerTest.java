package com.localcloud.gateway;

import com.localcloud.gateway.RequestLogger.RequestLogEntry;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link RequestLogger}.
 * All tests exercise pure in-memory logic with no database or I/O dependencies.
 */
class RequestLoggerTest {

    private RequestLogger logger;

    @BeforeEach
    void setUp() {
        logger = new RequestLogger();
    }

    private RequestLogEntry entry(String service) {
        return RequestLogEntry.create(service, "GET", "/test", 200, 5, 100, 200);
    }

    private RequestLogEntry entry(String service, String method, String path) {
        return RequestLogEntry.create(service, method, path, 200, 10, 50, 100);
    }

    // -----------------------------------------------------------------------
    // Basic operations
    // -----------------------------------------------------------------------

    @Test
    void newLoggerHasSizeZero() {
        assertEquals(0, logger.getSize());
    }

    @Test
    void defaultCapacityIs1000() {
        assertEquals(1000, logger.getCapacity());
    }

    @Test
    void customCapacity() {
        RequestLogger custom = new RequestLogger(50);
        assertEquals(50, custom.getCapacity());
    }

    @Test
    void invalidCapacityThrows() {
        assertThrows(IllegalArgumentException.class, () -> new RequestLogger(0));
        assertThrows(IllegalArgumentException.class, () -> new RequestLogger(-1));
    }

    @Test
    void logEntryAndRetrieve() {
        RequestLogEntry e = entry("gcs");
        logger.log(e);

        assertEquals(1, logger.getSize());
        List<RequestLogEntry> entries = logger.getEntries(null, 10);
        assertEquals(1, entries.size());
        assertEquals("gcs", entries.get(0).service());
        assertEquals("GET", entries.get(0).method());
    }

    @Test
    void entriesAreReturnedNewestFirst() {
        logger.log(entry("gcs", "GET", "/first"));
        logger.log(entry("pubsub", "POST", "/second"));

        List<RequestLogEntry> entries = logger.getEntries(null, 10);
        assertEquals(2, entries.size());
        assertEquals("/second", entries.get(0).path());
        assertEquals("/first", entries.get(1).path());
    }

    // -----------------------------------------------------------------------
    // Ring buffer eviction
    // -----------------------------------------------------------------------

    @Test
    void ringBufferEvictsOldestWhenFull() {
        RequestLogger small = new RequestLogger(5);
        for (int i = 0; i < 8; i++) {
            small.log(entry("svc" + i));
        }
        assertEquals(5, small.getSize());
    }

    @Test
    void ringBufferEvictsAt1000DefaultCapacity() {
        for (int i = 0; i < 1001; i++) {
            logger.log(entry("svc"));
        }
        // Size should be capped at 1000
        assertTrue(logger.getSize() <= 1000);
    }

    // -----------------------------------------------------------------------
    // Filtering
    // -----------------------------------------------------------------------

    @Test
    void getEntriesWithServiceFilter() {
        logger.log(entry("gcs"));
        logger.log(entry("pubsub"));
        logger.log(entry("gcs"));
        logger.log(entry("firestore"));

        List<RequestLogEntry> gcsEntries = logger.getEntries("gcs", 100);
        assertEquals(2, gcsEntries.size());
        assertTrue(gcsEntries.stream().allMatch(e -> "gcs".equals(e.service())));
    }

    @Test
    void getEntriesWithNullServiceReturnsAll() {
        logger.log(entry("gcs"));
        logger.log(entry("pubsub"));
        logger.log(entry("firestore"));

        List<RequestLogEntry> all = logger.getEntries(null, 100);
        assertEquals(3, all.size());
    }

    @Test
    void getEntriesWithServiceFilterNoMatches() {
        logger.log(entry("gcs"));
        logger.log(entry("pubsub"));

        List<RequestLogEntry> entries = logger.getEntries("nonexistent", 100);
        assertTrue(entries.isEmpty());
    }

    // -----------------------------------------------------------------------
    // Limit
    // -----------------------------------------------------------------------

    @Test
    void getEntriesWithLimitReturnsCorrectCount() {
        for (int i = 0; i < 20; i++) {
            logger.log(entry("gcs"));
        }

        List<RequestLogEntry> entries = logger.getEntries(null, 5);
        assertEquals(5, entries.size());
    }

    @Test
    void getEntriesWithLimitLargerThanSizeReturnsAll() {
        logger.log(entry("gcs"));
        logger.log(entry("pubsub"));

        List<RequestLogEntry> entries = logger.getEntries(null, 100);
        assertEquals(2, entries.size());
    }

    // -----------------------------------------------------------------------
    // Clear
    // -----------------------------------------------------------------------

    @Test
    void clearResetsToEmpty() {
        logger.log(entry("gcs"));
        logger.log(entry("pubsub"));
        assertEquals(2, logger.getSize());

        logger.clear();
        assertEquals(0, logger.getSize());
        assertTrue(logger.getEntries(null, 100).isEmpty());
    }

    // -----------------------------------------------------------------------
    // Returned list is unmodifiable
    // -----------------------------------------------------------------------

    @Test
    void getEntriesReturnsUnmodifiableList() {
        logger.log(entry("gcs"));
        List<RequestLogEntry> entries = logger.getEntries(null, 10);
        assertThrows(UnsupportedOperationException.class,
                () -> entries.add(entry("pubsub")));
    }

    // -----------------------------------------------------------------------
    // RequestLogEntry.create() auto-generates ID and timestamp
    // -----------------------------------------------------------------------

    @Test
    void entryCreateGeneratesUniqueIds() {
        RequestLogEntry e1 = RequestLogEntry.create("gcs", "GET", "/a", 200, 1, 10, 20);
        RequestLogEntry e2 = RequestLogEntry.create("gcs", "GET", "/b", 200, 1, 10, 20);
        assertNotEquals(e1.id(), e2.id());
    }

    @Test
    void entryCreateSetsTimestamp() {
        RequestLogEntry e = RequestLogEntry.create("gcs", "GET", "/a", 200, 1, 10, 20);
        assertNotNull(e.timestamp());
    }

    // -----------------------------------------------------------------------
    // Concurrency
    // -----------------------------------------------------------------------

    @Test
    void concurrentLoggingDoesNotCrash() throws Exception {
        int threadCount = 10;
        int entriesPerThread = 100;
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);

        for (int t = 0; t < threadCount; t++) {
            final int threadId = t;
            executor.submit(() -> {
                try {
                    startLatch.await();
                    for (int i = 0; i < entriesPerThread; i++) {
                        logger.log(entry("thread-" + threadId));
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        assertTrue(doneLatch.await(10, TimeUnit.SECONDS), "Threads did not finish in time");
        executor.shutdown();

        // All entries logged, but capped at 1000
        int totalLogged = threadCount * entriesPerThread;
        int expectedSize = Math.min(totalLogged, logger.getCapacity());
        assertTrue(logger.getSize() <= expectedSize,
                "Size " + logger.getSize() + " exceeds expected max " + expectedSize);
        assertTrue(logger.getSize() > 0, "Should have some entries");
    }
}
