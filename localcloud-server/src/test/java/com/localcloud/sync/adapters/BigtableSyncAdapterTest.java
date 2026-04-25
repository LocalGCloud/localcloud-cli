package com.localcloud.sync.adapters;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.localcloud.sync.SyncFilter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link BigtableSyncAdapter}.
 *
 * <p>Tests cover internal helper methods (resource parsing, cost estimation,
 * filter extraction, read request building) without making any HTTP calls.
 * The helpers are package-visible for testability.
 */
class BigtableSyncAdapterTest {

    private BigtableSyncAdapter adapter;
    private ObjectMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper();
        adapter = new BigtableSyncAdapter("localhost", 8087, mapper);
    }

    // -----------------------------------------------------------------------
    // parseResource — "instance/table" format
    // -----------------------------------------------------------------------

    @Test
    void parseResource_valid() {
        String[] parts = adapter.parseResource("my-instance/my-table");
        assertEquals(2, parts.length);
        assertEquals("my-instance", parts[0]);
        assertEquals("my-table", parts[1]);
    }

    @Test
    void parseResource_valid_withHyphens() {
        String[] parts = adapter.parseResource("prod-instance/user-events");
        assertEquals("prod-instance", parts[0]);
        assertEquals("user-events", parts[1]);
    }

    @Test
    void parseResource_invalid_singlePart() {
        assertThrows(IllegalArgumentException.class,
                () -> adapter.parseResource("just-a-table"));
    }

    @Test
    void parseResource_invalid_tooManyParts() {
        assertThrows(IllegalArgumentException.class,
                () -> adapter.parseResource("a/b/c"));
    }

    @Test
    void parseResource_invalid_emptyParts() {
        assertThrows(IllegalArgumentException.class,
                () -> adapter.parseResource("/table"));
    }

    @Test
    void parseResource_invalid_null() {
        assertThrows(IllegalArgumentException.class,
                () -> adapter.parseResource(null));
    }

    @Test
    void parseResource_invalid_empty() {
        assertThrows(IllegalArgumentException.class,
                () -> adapter.parseResource(""));
    }

    // -----------------------------------------------------------------------
    // estimateReadCost — $0.26 per million reads
    // -----------------------------------------------------------------------

    @Test
    void estimateReadCost_correctCalculation() {
        // 1M rows -> $0.26
        double cost = adapter.estimateReadCost(1_000_000);
        assertEquals(0.26, cost, 0.001);
    }

    @Test
    void estimateReadCost_zeroRows() {
        double cost = adapter.estimateReadCost(0);
        assertEquals(0.0, cost, 0.001);
    }

    @Test
    void estimateReadCost_smallCount() {
        // 10K rows -> $0.0026
        double cost = adapter.estimateReadCost(10_000);
        assertEquals(0.26 * 10_000.0 / 1_000_000, cost, 0.00001);
    }

    @Test
    void estimateReadCost_halfMillion() {
        // 500K rows -> $0.13
        double cost = adapter.estimateReadCost(500_000);
        assertEquals(0.13, cost, 0.001);
    }

    // -----------------------------------------------------------------------
    // extractRowKeyPrefix
    // -----------------------------------------------------------------------

    @Test
    void extractRowKeyPrefix_found() {
        List<SyncFilter> filters = List.of(
                new SyncFilter("rowKey", "=", "user#123", "STRING")
        );
        assertEquals("user#123", adapter.extractRowKeyPrefix(filters));
    }

    @Test
    void extractRowKeyPrefix_alternativeName() {
        List<SyncFilter> filters = List.of(
                new SyncFilter("row_key_prefix", "=", "event#2024", "STRING")
        );
        assertEquals("event#2024", adapter.extractRowKeyPrefix(filters));
    }

    @Test
    void extractRowKeyPrefix_notFound() {
        List<SyncFilter> filters = List.of(
                new SyncFilter("status", "=", "active", "STRING")
        );
        assertNull(adapter.extractRowKeyPrefix(filters));
    }

    @Test
    void extractRowKeyPrefix_nullFilters() {
        assertNull(adapter.extractRowKeyPrefix(null));
    }

    // -----------------------------------------------------------------------
    // extractColumnFamily
    // -----------------------------------------------------------------------

    @Test
    void extractColumnFamily_found() {
        List<SyncFilter> filters = List.of(
                new SyncFilter("columnFamily", "=", "cf1", "STRING")
        );
        assertEquals("cf1", adapter.extractColumnFamily(filters));
    }

    @Test
    void extractColumnFamily_alternativeName() {
        List<SyncFilter> filters = List.of(
                new SyncFilter("column_family", "=", "data", "STRING")
        );
        assertEquals("data", adapter.extractColumnFamily(filters));
    }

    @Test
    void extractColumnFamily_notFound() {
        List<SyncFilter> filters = List.of(
                new SyncFilter("rowKey", "=", "key1", "STRING")
        );
        assertNull(adapter.extractColumnFamily(filters));
    }

    @Test
    void extractColumnFamily_nullFilters() {
        assertNull(adapter.extractColumnFamily(null));
    }

    // -----------------------------------------------------------------------
    // buildReadRowsRequest
    // -----------------------------------------------------------------------

    @Test
    void buildReadRowsRequest_empty() {
        ObjectNode request = adapter.buildReadRowsRequest(null, null, 0);
        assertTrue(request.path("rows").isMissingNode());
        assertTrue(request.path("filter").isMissingNode());
        assertTrue(request.path("rowsLimit").isMissingNode());
    }

    @Test
    void buildReadRowsRequest_withLimit() {
        ObjectNode request = adapter.buildReadRowsRequest(null, null, 100);
        assertEquals(100, request.path("rowsLimit").asInt());
    }

    @Test
    void buildReadRowsRequest_withRowKeyPrefix() {
        ObjectNode request = adapter.buildReadRowsRequest("user#", null, 0);
        JsonNode rows = request.path("rows");
        assertFalse(rows.isMissingNode());
        JsonNode rowRanges = rows.path("rowRanges");
        assertTrue(rowRanges.isArray());
        assertEquals(1, rowRanges.size());

        JsonNode range = rowRanges.get(0);
        assertFalse(range.path("startKeyClosed").isMissingNode());
        assertFalse(range.path("endKeyOpen").isMissingNode());
    }

    @Test
    void buildReadRowsRequest_withColumnFamily() {
        ObjectNode request = adapter.buildReadRowsRequest(null, "cf1", 0);
        JsonNode filter = request.path("filter");
        assertFalse(filter.isMissingNode());
        assertEquals("cf1", filter.path("familyNameRegexFilter").asText());
    }

    @Test
    void buildReadRowsRequest_allOptions() {
        ObjectNode request = adapter.buildReadRowsRequest("event#", "metrics", 500);
        assertFalse(request.path("rows").isMissingNode());
        assertEquals("metrics", request.path("filter").path("familyNameRegexFilter").asText());
        assertEquals(500, request.path("rowsLimit").asInt());
    }
}
