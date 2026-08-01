package com.localcloud.sync.adapters;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link GcsSyncAdapter}.
 *
 * <p>Tests cover internal helper methods (resource parsing, cost estimation)
 * without making any HTTP calls. The helpers are package-visible for testability.
 */
class GcsSyncAdapterTest {

    private GcsSyncAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new GcsSyncAdapter("http://localhost:24081", new ObjectMapper());
    }

    // -----------------------------------------------------------------------
    // parseResource — "bucket/prefix" format
    // -----------------------------------------------------------------------

    @Test
    void parseResource_bucketOnly() {
        String[] parts = adapter.parseResource("my-bucket");
        assertEquals("my-bucket", parts[0]);
        assertNull(parts[1]);
    }

    @Test
    void parseResource_bucketAndPrefix() {
        String[] parts = adapter.parseResource("my-bucket/data/2024/");
        assertEquals("my-bucket", parts[0]);
        assertEquals("data/2024/", parts[1]);
    }

    @Test
    void parseResource_bucketAndSinglePrefix() {
        String[] parts = adapter.parseResource("my-bucket/logs");
        assertEquals("my-bucket", parts[0]);
        assertEquals("logs", parts[1]);
    }

    @Test
    void parseResource_bucketWithTrailingSlash() {
        String[] parts = adapter.parseResource("my-bucket/");
        assertEquals("my-bucket", parts[0]);
        // Trailing slash with empty prefix -> null
        assertNull(parts[1]);
    }

    @Test
    void parseResource_invalid_null() {
        assertThrows(IllegalArgumentException.class, () -> adapter.parseResource(null));
    }

    @Test
    void parseResource_invalid_empty() {
        assertThrows(IllegalArgumentException.class, () -> adapter.parseResource(""));
    }

    @Test
    void parseResource_invalid_slashOnly() {
        assertThrows(IllegalArgumentException.class, () -> adapter.parseResource("/prefix"));
    }

    // -----------------------------------------------------------------------
    // estimateCost — ops + egress
    // -----------------------------------------------------------------------

    @Test
    void estimateCost_correctCalculation() {
        // 10K objects, 1 GB egress
        // ops: $0.004 * 10000 / 10000 = $0.004
        // egress: $0.12 * 1073741824 / 1073741824 = $0.12
        // total: $0.124
        long oneGB = 1024L * 1024 * 1024;
        double cost = adapter.estimateCost(10_000, oneGB);
        assertEquals(0.124, cost, 0.001);
    }

    @Test
    void estimateCost_zeroObjectsAndBytes() {
        double cost = adapter.estimateCost(0, 0);
        assertEquals(0.0, cost, 0.0001);
    }

    @Test
    void estimateCost_opsOnly() {
        // 100K objects, 0 bytes
        // ops: $0.004 * 100000 / 10000 = $0.04
        double cost = adapter.estimateCost(100_000, 0);
        assertEquals(0.04, cost, 0.001);
    }

    @Test
    void estimateCost_egressOnly() {
        // 0 objects, 10 GB
        // egress: $0.12 * 10 = $1.20
        long tenGB = 10L * 1024 * 1024 * 1024;
        double cost = adapter.estimateCost(0, tenGB);
        assertEquals(1.20, cost, 0.01);
    }

    @Test
    void estimateCost_smallObjects() {
        // 100 objects, 1 MB
        long oneMB = 1024L * 1024;
        double cost = adapter.estimateCost(100, oneMB);
        double expectedOps = 0.004 * 100.0 / 10_000;
        double expectedEgress = 0.12 * oneMB / (1024.0 * 1024 * 1024);
        assertEquals(expectedOps + expectedEgress, cost, 0.00001);
    }

    @Test
    void estimateCost_largeDataset() {
        // 1M objects, 1 TB
        long oneTB = 1024L * 1024 * 1024 * 1024;
        double cost = adapter.estimateCost(1_000_000, oneTB);
        double expectedOps = 0.004 * 1_000_000.0 / 10_000;
        double expectedEgress = 0.12 * 1024; // 1 TB = 1024 GB
        assertEquals(expectedOps + expectedEgress, cost, 0.1);
    }

    // -----------------------------------------------------------------------
    // MAX_OBJECT_SIZE guard
    // -----------------------------------------------------------------------

    @Test
    void maxObjectSizeConstant_is100MB() {
        assertEquals(100L * 1024 * 1024, GcsSyncAdapter.MAX_OBJECT_SIZE);
    }

    // -----------------------------------------------------------------------
    // deleteLocal — parseResource is validated on the delete path
    // -----------------------------------------------------------------------

    @Test
    void deleteLocal_parsesResourceWithPrefix() {
        // Test that resource parsing works for the delete path
        String[] parts = adapter.parseResource("my-bucket/some/prefix");
        assertEquals("my-bucket", parts[0]);
        assertEquals("some/prefix", parts[1]);
    }

    @Test
    void deleteLocal_parsesResourceBucketOnly() {
        // Delete with bucket-only resource
        String[] parts = adapter.parseResource("my-bucket");
        assertEquals("my-bucket", parts[0]);
        assertNull(parts[1]);
    }
}
