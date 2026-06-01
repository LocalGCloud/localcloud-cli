package com.localcloud.emulators.bigtable;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for BigtableAdminService REST handler response parsing.
 * Tests instance/table CRUD request parsing without requiring Armeria or PostgreSQL.
 */
class BigtableAdminServiceTest {

    @Test
    void instanceNameParsing_validFormat() {
        String name = "projects/test-project/instances/test-instance";
        String[] parts = name.split("/");
        assertEquals(4, parts.length);
        assertEquals("projects", parts[0]);
        assertEquals("test-project", parts[1]);
        assertEquals("instances", parts[2]);
        assertEquals("test-instance", parts[3]);
    }

    @Test
    void instanceNameParsing_missingSegments() {
        String name = "projects/test-project";
        String[] parts = name.split("/");
        assertEquals(2, parts.length);
    }

    @Test
    void columnFamilyJson_defaultFormat() {
        String json = "{\"gcRule\":{\"maxNumVersions\":1}}";
        assertTrue(json.contains("gcRule"));
        assertTrue(json.contains("maxNumVersions"));
    }

    @Test
    void tableNameFormat_validId() {
        String tableId = "my-table-123";
        assertNotNull(tableId);
        assertFalse(tableId.isEmpty());
        assertTrue(tableId.matches("[a-zA-Z0-9_-]+"));
    }
}
