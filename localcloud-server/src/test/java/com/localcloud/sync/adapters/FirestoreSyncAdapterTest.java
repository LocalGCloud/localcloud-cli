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
 * Unit tests for {@link FirestoreSyncAdapter}.
 *
 * <p>Tests cover internal helper methods (resource parsing, cost estimation,
 * filter/query building) without making any HTTP calls. The helpers are
 * package-visible for testability.
 */
class FirestoreSyncAdapterTest {

    private FirestoreSyncAdapter adapter;
    private ObjectMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper();
        adapter = new FirestoreSyncAdapter("localhost", 24083, mapper);
    }

    // -----------------------------------------------------------------------
    // parseResource
    // -----------------------------------------------------------------------

    @Test
    void parseResource_valid_simpleCollection() {
        String result = adapter.parseResource("users");
        assertEquals("users", result);
    }

    @Test
    void parseResource_valid_withSubcollection() {
        String result = adapter.parseResource("users/documents/orders");
        assertEquals("users/documents/orders", result);
    }

    @Test
    void parseResource_invalid_null() {
        assertThrows(IllegalArgumentException.class, () -> adapter.parseResource(null));
    }

    @Test
    void parseResource_invalid_empty() {
        assertThrows(IllegalArgumentException.class, () -> adapter.parseResource(""));
    }

    // -----------------------------------------------------------------------
    // estimateReadCost — $0.06 per 100K reads
    // -----------------------------------------------------------------------

    @Test
    void estimateReadCost_correctCalculation() {
        // 100K documents -> $0.06
        double cost = adapter.estimateReadCost(100_000);
        assertEquals(0.06, cost, 0.0001);
    }

    @Test
    void estimateReadCost_zeroDocs() {
        double cost = adapter.estimateReadCost(0);
        assertEquals(0.0, cost, 0.0001);
    }

    @Test
    void estimateReadCost_smallCount() {
        // 1000 documents -> $0.0006
        double cost = adapter.estimateReadCost(1000);
        assertEquals(0.06 * 1000.0 / 100_000, cost, 0.00001);
    }

    @Test
    void estimateReadCost_millionDocs() {
        // 1M documents -> $0.60
        double cost = adapter.estimateReadCost(1_000_000);
        assertEquals(0.60, cost, 0.001);
    }

    // -----------------------------------------------------------------------
    // buildRunQuery — basic structure
    // -----------------------------------------------------------------------

    @Test
    void buildRunQuery_noFilters_noLimit() {
        ObjectNode query = adapter.buildRunQuery("users", null, 0);
        JsonNode structuredQuery = query.path("structuredQuery");

        // from clause
        JsonNode from = structuredQuery.path("from");
        assertTrue(from.isArray());
        assertEquals("users", from.get(0).path("collectionId").asText());

        // no where clause
        assertTrue(structuredQuery.path("where").isMissingNode());

        // no limit
        assertTrue(structuredQuery.path("limit").isMissingNode());
    }

    @Test
    void buildRunQuery_withLimit() {
        ObjectNode query = adapter.buildRunQuery("orders", null, 50);
        JsonNode structuredQuery = query.path("structuredQuery");

        assertEquals(50, structuredQuery.path("limit").asInt());
    }

    @Test
    void buildRunQuery_withFilters() {
        List<SyncFilter> filters = List.of(
                new SyncFilter("status", "=", "active", "STRING")
        );
        ObjectNode query = adapter.buildRunQuery("users", filters, 0);
        JsonNode structuredQuery = query.path("structuredQuery");

        JsonNode where = structuredQuery.path("where");
        assertFalse(where.isMissingNode());

        // Single filter -> fieldFilter (not compositeFilter)
        JsonNode fieldFilter = where.path("fieldFilter");
        assertFalse(fieldFilter.isMissingNode());
        assertEquals("status", fieldFilter.path("field").path("fieldPath").asText());
        assertEquals("EQUAL", fieldFilter.path("op").asText());
        assertEquals("active", fieldFilter.path("value").path("stringValue").asText());
    }

    @Test
    void buildRunQuery_withMultipleFilters() {
        List<SyncFilter> filters = List.of(
                new SyncFilter("status", "=", "active", "STRING"),
                new SyncFilter("age", ">", "18", "INT64")
        );
        ObjectNode query = adapter.buildRunQuery("users", filters, 100);
        JsonNode structuredQuery = query.path("structuredQuery");

        JsonNode where = structuredQuery.path("where");
        // Multiple filters -> compositeFilter
        JsonNode composite = where.path("compositeFilter");
        assertFalse(composite.isMissingNode());
        assertEquals("AND", composite.path("op").asText());

        JsonNode filtersList = composite.path("filters");
        assertEquals(2, filtersList.size());

        // Verify limit
        assertEquals(100, structuredQuery.path("limit").asInt());
    }

    // -----------------------------------------------------------------------
    // buildWhereClause — operator mapping
    // -----------------------------------------------------------------------

    @Test
    void buildWhereClause_singleFilter_fieldFilter() {
        List<SyncFilter> filters = List.of(
                new SyncFilter("price", ">=", "10.5", "FLOAT64")
        );
        ObjectNode where = adapter.buildWhereClause(filters);

        JsonNode fieldFilter = where.path("fieldFilter");
        assertEquals("price", fieldFilter.path("field").path("fieldPath").asText());
        assertEquals("GREATER_THAN_OR_EQUAL", fieldFilter.path("op").asText());
        assertEquals(10.5, fieldFilter.path("value").path("doubleValue").asDouble(), 0.001);
    }

    @Test
    void buildWhereClause_boolFilter() {
        List<SyncFilter> filters = List.of(
                new SyncFilter("active", "=", "true", "BOOL")
        );
        ObjectNode where = adapter.buildWhereClause(filters);

        JsonNode fieldFilter = where.path("fieldFilter");
        assertTrue(fieldFilter.path("value").path("booleanValue").asBoolean());
    }

    // -----------------------------------------------------------------------
    // mapFilterOperator
    // -----------------------------------------------------------------------

    @Test
    void mapFilterOperator_allStandardOperators() {
        assertEquals("EQUAL", adapter.mapFilterOperator("="));
        assertEquals("NOT_EQUAL", adapter.mapFilterOperator("!="));
        assertEquals("LESS_THAN", adapter.mapFilterOperator("<"));
        assertEquals("LESS_THAN_OR_EQUAL", adapter.mapFilterOperator("<="));
        assertEquals("GREATER_THAN", adapter.mapFilterOperator(">"));
        assertEquals("GREATER_THAN_OR_EQUAL", adapter.mapFilterOperator(">="));
    }

    @Test
    void mapFilterOperator_unknownPassthrough() {
        // Unknown operators pass through unchanged
        assertEquals("ARRAY_CONTAINS", adapter.mapFilterOperator("ARRAY_CONTAINS"));
    }
}
