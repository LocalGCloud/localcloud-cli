package com.localcloud.sync.adapters;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.localcloud.sync.SyncFilter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link SpannerSyncAdapter}.
 *
 * <p>Tests cover internal helper methods (resource parsing, query building,
 * cost estimation, DDL parsing) without making any HTTP calls. The helpers
 * are package-visible for testability.
 */
class SpannerSyncAdapterTest {

    private SpannerSyncAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new SpannerSyncAdapter("localhost", 24085, new ObjectMapper());
    }

    // -----------------------------------------------------------------------
    // parseResource — "instance/database/table" format
    // -----------------------------------------------------------------------

    @Test
    void parseResource_valid() {
        String[] parts = adapter.parseResource("my-instance/my-db/my-table");
        assertEquals(3, parts.length);
        assertEquals("my-instance", parts[0]);
        assertEquals("my-db", parts[1]);
        assertEquals("my-table", parts[2]);
    }

    @Test
    void parseResource_valid_withHyphens() {
        String[] parts = adapter.parseResource("prod-instance/analytics-db/user-events");
        assertEquals("prod-instance", parts[0]);
        assertEquals("analytics-db", parts[1]);
        assertEquals("user-events", parts[2]);
    }

    @Test
    void parseResource_invalid_tooFewParts() {
        assertThrows(IllegalArgumentException.class,
                () -> adapter.parseResource("instance/database"));
    }

    @Test
    void parseResource_invalid_tooManyParts() {
        assertThrows(IllegalArgumentException.class,
                () -> adapter.parseResource("a/b/c/d"));
    }

    @Test
    void parseResource_invalid_emptyParts() {
        assertThrows(IllegalArgumentException.class,
                () -> adapter.parseResource("/db/table"));
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
    // buildSyncQuery
    // -----------------------------------------------------------------------

    @Test
    void buildSyncQuery_noFilters_noLimit() {
        String sql = adapter.buildSyncQuery("Users", List.of(), 0);
        assertEquals("SELECT * FROM Users", sql);
    }

    @Test
    void buildSyncQuery_noFilters_withLimit() {
        String sql = adapter.buildSyncQuery("Users", List.of(), 100);
        assertEquals("SELECT * FROM Users LIMIT 100", sql);
    }

    @Test
    void buildSyncQuery_withStringFilter() {
        List<SyncFilter> filters = List.of(
                new SyncFilter("status", "=", "active", "STRING")
        );
        String sql = adapter.buildSyncQuery("Users", filters, 0);
        assertEquals("SELECT * FROM Users WHERE status = 'active'", sql);
    }

    @Test
    void buildSyncQuery_withNumericFilter() {
        List<SyncFilter> filters = List.of(
                new SyncFilter("age", ">", "18", "INT64")
        );
        String sql = adapter.buildSyncQuery("Users", filters, 0);
        assertEquals("SELECT * FROM Users WHERE age > 18", sql);
    }

    @Test
    void buildSyncQuery_multipleFilters() {
        List<SyncFilter> filters = List.of(
                new SyncFilter("status", "=", "active", "STRING"),
                new SyncFilter("age", ">", "18", "INT64")
        );
        String sql = adapter.buildSyncQuery("Users", filters, 50);
        assertEquals("SELECT * FROM Users WHERE status = 'active' AND age > 18 LIMIT 50", sql);
    }

    @Test
    void buildSyncQuery_boolFilter_noQuotes() {
        List<SyncFilter> filters = List.of(
                new SyncFilter("active", "=", "true", "BOOL")
        );
        String sql = adapter.buildSyncQuery("Users", filters, 0);
        assertEquals("SELECT * FROM Users WHERE active = true", sql);
    }

    @Test
    void buildSyncQuery_singleQuotesEscaped() {
        List<SyncFilter> filters = List.of(
                new SyncFilter("name", "=", "O'Brien", "STRING")
        );
        String sql = adapter.buildSyncQuery("Users", filters, 0);
        assertEquals("SELECT * FROM Users WHERE name = 'O''Brien'", sql);
    }

    // -----------------------------------------------------------------------
    // buildCountQuery
    // -----------------------------------------------------------------------

    @Test
    void buildCountQuery_noFilters() {
        String sql = adapter.buildCountQuery("Users", List.of());
        assertEquals("SELECT COUNT(*) FROM Users", sql);
    }

    @Test
    void buildCountQuery_withFilters() {
        List<SyncFilter> filters = List.of(
                new SyncFilter("region", "=", "us-east1", "STRING")
        );
        String sql = adapter.buildCountQuery("Users", filters);
        assertEquals("SELECT COUNT(*) FROM Users WHERE region = 'us-east1'", sql);
    }

    // -----------------------------------------------------------------------
    // estimateReadCost — $0.65 per million reads
    // -----------------------------------------------------------------------

    @Test
    void estimateReadCost_correctCalculation() {
        // 1M rows -> $0.65
        double cost = adapter.estimateReadCost(1_000_000);
        assertEquals(0.65, cost, 0.001);
    }

    @Test
    void estimateReadCost_zeroRows() {
        double cost = adapter.estimateReadCost(0);
        assertEquals(0.0, cost, 0.001);
    }

    @Test
    void estimateReadCost_smallCount() {
        // 10K rows -> $0.0065
        double cost = adapter.estimateReadCost(10_000);
        assertEquals(0.65 * 10_000.0 / 1_000_000, cost, 0.00001);
    }

    @Test
    void estimateReadCost_halfMillion() {
        // 500K rows -> $0.325
        double cost = adapter.estimateReadCost(500_000);
        assertEquals(0.325, cost, 0.001);
    }

    // -----------------------------------------------------------------------
    // extractTableName — DDL parsing
    // -----------------------------------------------------------------------

    @Test
    void extractTableName_simple() {
        String name = adapter.extractTableName("CREATE TABLE Users (id INT64)");
        assertEquals("Users", name);
    }

    @Test
    void extractTableName_withBackticks() {
        String name = adapter.extractTableName("CREATE TABLE `UserEvents` (id INT64)");
        assertEquals("UserEvents", name);
    }

    @Test
    void extractTableName_withQuotes() {
        String name = adapter.extractTableName("CREATE TABLE \"my_table\" (id INT64)");
        assertEquals("my_table", name);
    }

    @Test
    void extractTableName_ifNotExists() {
        String name = adapter.extractTableName("CREATE TABLE IF NOT EXISTS Orders (id INT64)");
        assertEquals("Orders", name);
    }

    @Test
    void extractTableName_notCreateTable() {
        String name = adapter.extractTableName("CREATE INDEX idx ON Users (name)");
        assertNull(name);
    }
}
