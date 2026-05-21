package com.localcloud.admin;

import com.localcloud.integration.TestDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class QueryHistoryRepositoryTest {

    private TestDataSource testDs;
    private QueryHistoryRepository repo;

    @BeforeEach
    void setUp() {
        testDs = TestDataSource.create("query_history_test_" + System.nanoTime());
        repo = new QueryHistoryRepository(testDs.getDataSource());
    }

    @AfterEach
    void tearDown() {
        testDs.close();
    }

    @Test
    void recordAndList() {
        repo.record("proj-1", "spanner", "SELECT * FROM Users", "my-instance", "my-db",
                150L, 10, true, null);
        repo.record("proj-1", "spanner", "SELECT * FROM Orders", "my-instance", "my-db",
                200L, 5, true, null);
        repo.record("proj-1", "bigquery", "SELECT * FROM dataset.table", null, null,
                300L, 100, false, "Syntax error");

        var entries = repo.list("proj-1", null, 10, 0);
        assertEquals(3, entries.size());
        assertEquals("bigquery", entries.get(0).get("service"));
        assertEquals(300L, entries.get(0).get("duration_ms"));
        assertEquals(false, entries.get(0).get("success"));
        assertEquals("Syntax error", entries.get(0).get("error_message"));
    }

    @Test
    void listFiltersByService() {
        repo.record("proj-1", "spanner", "SELECT 1", null, null, 10L, 1, true, null);
        repo.record("proj-1", "bigquery", "SELECT 2", null, null, 20L, 2, true, null);

        var spannerEntries = repo.list("proj-1", "spanner", 10, 0);
        assertEquals(1, spannerEntries.size());
        assertEquals("spanner", spannerEntries.get(0).get("service"));
    }

    @Test
    void listRespectsPagination() {
        for (int i = 0; i < 10; i++) {
            repo.record("proj-1", "spanner", "SELECT " + i, null, null, (long) i, i, true, null);
        }

        var firstPage = repo.list("proj-1", null, 3, 0);
        assertEquals(3, firstPage.size());
        assertEquals("SELECT 9", firstPage.get(0).get("sql"));

        var secondPage = repo.list("proj-1", null, 3, 3);
        assertEquals(3, secondPage.size());
        assertEquals("SELECT 6", secondPage.get(0).get("sql"));
    }

    @Test
    void listReturnsEmptyForUnknownProject() {
        var entries = repo.list("nonexistent", null, 10, 0);
        assertTrue(entries.isEmpty());
    }

    @Test
    void recordHandlesNullOptionalFields() {
        repo.record("proj-1", "spanner", "SELECT * FROM Test", null, null,
                50L, 0, true, null);
        var entries = repo.list("proj-1", null, 10, 0);
        assertEquals(1, entries.size());
        assertNull(entries.get(0).get("instance"));
        assertNull(entries.get(0).get("database"));
    }

    @Test
    void recordHandlesFailedQuery() {
        repo.record("proj-1", "spanner", "SELECT * FROM MissingTable", "inst", "db",
                5L, 0, false, "Table not found");

        var entries = repo.list("proj-1", null, 10, 0);
        assertEquals(1, entries.size());
        assertFalse((Boolean) entries.get(0).get("success"));
        assertEquals("Table not found", entries.get(0).get("error_message"));
    }

    @Test
    void countReturnsTotalMatchingRecords() {
        repo.record("p", "spanner", "SELECT 1", null, null, 10, 1, true, null);
        repo.record("p", "spanner", "SELECT 2", null, null, 20, 2, true, null);
        repo.record("p", "bigquery", "SELECT 3", null, null, 30, 3, true, null);

        assertEquals(3, repo.count("p", null));
        assertEquals(2, repo.count("p", "spanner"));
        assertEquals(1, repo.count("p", "bigquery"));
        assertEquals(0, repo.count("unknown", null));
    }


}
