package com.localcloud.sync.adapters;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.localcloud.sync.SyncFilter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link BigQuerySyncAdapter}.
 *
 * <p>Tests cover internal helper methods (query building, resource parsing,
 * cost estimation) without making any HTTP calls. The helpers are
 * package-visible for testability.
 */
class BigQuerySyncAdapterTest {

    private BigQuerySyncAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new BigQuerySyncAdapter("http://localhost:9050", new ObjectMapper());
    }

    // -----------------------------------------------------------------------
    // buildSyncQuery — no filters
    // -----------------------------------------------------------------------

    @Test
    void buildQuery_noFilters_selectAll() {
        String sql = adapter.buildSyncQuery("my-dataset", "my-table", List.of(), 0);
        assertEquals("SELECT * FROM `my-dataset.my-table`", sql);
    }

    @Test
    void buildQuery_noFilters_withLimit() {
        String sql = adapter.buildSyncQuery("ds", "tbl", List.of(), 100);
        assertEquals("SELECT * FROM `ds.tbl` LIMIT 100", sql);
    }

    // -----------------------------------------------------------------------
    // buildSyncQuery — with filters
    // -----------------------------------------------------------------------

    @Test
    void buildQuery_withFilters_addsWhereClause() {
        List<SyncFilter> filters = List.of(
                new SyncFilter("status", "=", "active", "STRING")
        );
        String sql = adapter.buildSyncQuery("ds", "tbl", filters, 0);
        assertEquals("SELECT * FROM `ds.tbl` WHERE status = 'active'", sql);
    }

    @Test
    void buildQuery_multipleFilters_andJoined() {
        List<SyncFilter> filters = List.of(
                new SyncFilter("status", "=", "active", "STRING"),
                new SyncFilter("age", ">", "18", "INT64")
        );
        String sql = adapter.buildSyncQuery("ds", "tbl", filters, 50);
        assertEquals("SELECT * FROM `ds.tbl` WHERE status = 'active' AND age > 18 LIMIT 50", sql);
    }

    // -----------------------------------------------------------------------
    // buildSyncQuery — IN operator
    // -----------------------------------------------------------------------

    @Test
    void buildQuery_inOperator_handledCorrectly() {
        List<SyncFilter> filters = List.of(
                new SyncFilter("region", "IN", "us-east1,eu-west1,asia-south1", "STRING")
        );
        String sql = adapter.buildSyncQuery("ds", "tbl", filters, 0);
        assertEquals("SELECT * FROM `ds.tbl` WHERE region IN ('us-east1', 'eu-west1', 'asia-south1')", sql);
    }

    @Test
    void buildQuery_inOperator_numericValues() {
        List<SyncFilter> filters = List.of(
                new SyncFilter("id", "IN", "1,2,3", "INT64")
        );
        String sql = adapter.buildSyncQuery("ds", "tbl", filters, 0);
        assertEquals("SELECT * FROM `ds.tbl` WHERE id IN (1, 2, 3)", sql);
    }

    // -----------------------------------------------------------------------
    // buildSyncQuery — numeric filter (no quotes)
    // -----------------------------------------------------------------------

    @Test
    void buildQuery_numericFilter_noQuotes() {
        List<SyncFilter> filters = List.of(
                new SyncFilter("count", ">=", "42", "INT64")
        );
        String sql = adapter.buildSyncQuery("ds", "tbl", filters, 0);
        assertEquals("SELECT * FROM `ds.tbl` WHERE count >= 42", sql);
    }

    @Test
    void buildQuery_float64Filter_noQuotes() {
        List<SyncFilter> filters = List.of(
                new SyncFilter("score", "<", "3.14", "FLOAT64")
        );
        String sql = adapter.buildSyncQuery("ds", "tbl", filters, 0);
        assertEquals("SELECT * FROM `ds.tbl` WHERE score < 3.14", sql);
    }

    @Test
    void buildQuery_numericTypeFilter_noQuotes() {
        List<SyncFilter> filters = List.of(
                new SyncFilter("amount", "=", "99.99", "NUMERIC")
        );
        String sql = adapter.buildSyncQuery("ds", "tbl", filters, 0);
        assertEquals("SELECT * FROM `ds.tbl` WHERE amount = 99.99", sql);
    }

    @Test
    void buildQuery_integerTypeFilter_noQuotes() {
        List<SyncFilter> filters = List.of(
                new SyncFilter("qty", "=", "7", "INTEGER")
        );
        String sql = adapter.buildSyncQuery("ds", "tbl", filters, 0);
        assertEquals("SELECT * FROM `ds.tbl` WHERE qty = 7", sql);
    }

    @Test
    void buildQuery_floatTypeFilter_noQuotes() {
        List<SyncFilter> filters = List.of(
                new SyncFilter("rate", "<=", "0.05", "FLOAT")
        );
        String sql = adapter.buildSyncQuery("ds", "tbl", filters, 0);
        assertEquals("SELECT * FROM `ds.tbl` WHERE rate <= 0.05", sql);
    }

    // -----------------------------------------------------------------------
    // buildSyncQuery — BOOL filter (no quotes)
    // -----------------------------------------------------------------------

    @Test
    void buildQuery_boolFilter_noQuotes() {
        List<SyncFilter> filters = List.of(
                new SyncFilter("active", "=", "true", "BOOL")
        );
        String sql = adapter.buildSyncQuery("ds", "tbl", filters, 0);
        assertEquals("SELECT * FROM `ds.tbl` WHERE active = true", sql);
    }

    // -----------------------------------------------------------------------
    // buildSyncQuery — TIMESTAMP / DATE filters (quoted)
    // -----------------------------------------------------------------------

    @Test
    void buildQuery_timestampFilter_quoted() {
        List<SyncFilter> filters = List.of(
                new SyncFilter("created_at", ">=", "2024-01-01T00:00:00Z", "TIMESTAMP")
        );
        String sql = adapter.buildSyncQuery("ds", "tbl", filters, 0);
        assertEquals("SELECT * FROM `ds.tbl` WHERE created_at >= '2024-01-01T00:00:00Z'", sql);
    }

    @Test
    void buildQuery_dateFilter_quoted() {
        List<SyncFilter> filters = List.of(
                new SyncFilter("birth_date", "=", "1990-05-15", "DATE")
        );
        String sql = adapter.buildSyncQuery("ds", "tbl", filters, 0);
        assertEquals("SELECT * FROM `ds.tbl` WHERE birth_date = '1990-05-15'", sql);
    }

    // -----------------------------------------------------------------------
    // parseResource
    // -----------------------------------------------------------------------

    @Test
    void parseResource_dotSeparated() {
        String[] parts = adapter.parseResource("my_dataset.my_table");
        assertEquals(2, parts.length);
        assertEquals("my_dataset", parts[0]);
        assertEquals("my_table", parts[1]);
    }

    @Test
    void parseResource_valid_withHyphens() {
        String[] parts = adapter.parseResource("analytics-prod.user-events");
        assertEquals("analytics-prod", parts[0]);
        assertEquals("user-events", parts[1]);
    }

    @Test
    void parseResource_invalid_throws() {
        assertThrows(IllegalArgumentException.class, () -> adapter.parseResource("just-a-table"));
    }

    @Test
    void parseResource_invalid_tooManyParts() {
        assertThrows(IllegalArgumentException.class, () -> adapter.parseResource("a.b.c"));
    }

    @Test
    void parseResource_invalid_emptyParts() {
        assertThrows(IllegalArgumentException.class, () -> adapter.parseResource(".table"));
    }

    @Test
    void parseResource_invalid_empty() {
        assertThrows(IllegalArgumentException.class, () -> adapter.parseResource(""));
    }

    @Test
    void parseResource_invalid_null() {
        assertThrows(IllegalArgumentException.class, () -> adapter.parseResource(null));
    }

    // -----------------------------------------------------------------------
    // estimateCost — $5 per TB
    // -----------------------------------------------------------------------

    @Test
    void estimateCost_correctCalculation() {
        // 1 TB = 1,099,511,627,776 bytes -> $5.00
        double cost = adapter.estimateCost(1_099_511_627_776L);
        assertEquals(5.0, cost, 0.001);
    }

    @Test
    void estimateCost_zeroBytes() {
        double cost = adapter.estimateCost(0);
        assertEquals(0.0, cost, 0.001);
    }

    @Test
    void estimateCost_smallScan() {
        // 10 GB = 10,737,418,240 bytes -> $0.04883
        double cost = adapter.estimateCost(10_737_418_240L);
        assertEquals(5.0 * 10_737_418_240L / (1024.0 * 1024 * 1024 * 1024), cost, 0.001);
    }

    @Test
    void estimateCost_exactlyHalfTB() {
        long halfTB = 1_099_511_627_776L / 2;
        double cost = adapter.estimateCost(halfTB);
        assertEquals(2.5, cost, 0.001);
    }

    // -----------------------------------------------------------------------
    // buildSyncQuery — edge cases
    // -----------------------------------------------------------------------

    @Test
    void buildQuery_stringFilter_singleQuotesEscaped() {
        List<SyncFilter> filters = List.of(
                new SyncFilter("name", "=", "O'Brien", "STRING")
        );
        String sql = adapter.buildSyncQuery("ds", "tbl", filters, 0);
        assertEquals("SELECT * FROM `ds.tbl` WHERE name = 'O''Brien'", sql);
    }

    @Test
    void buildQuery_emptyFilterList_noWhereClause() {
        String sql = adapter.buildSyncQuery("ds", "tbl", List.of(), 1000);
        assertEquals("SELECT * FROM `ds.tbl` LIMIT 1000", sql);
    }
}
