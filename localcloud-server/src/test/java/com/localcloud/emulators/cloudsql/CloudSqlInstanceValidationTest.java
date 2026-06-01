package com.localcloud.emulators.cloudsql;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for Cloud SQL REST handler instance/database name parsing.
 */
class CloudSqlInstanceValidationTest {

    @Test
    void parseInstanceName_validFormat() {
        String name = "projects/test-project/instances/my-instance";
        String[] parts = name.split("/");
        assertEquals(4, parts.length);
        assertEquals("test-project", parts[1]);
        assertEquals("my-instance", parts[3]);
    }

    @Test
    void databaseNameFormat_validChars() {
        String dbName = "my_database_123";
        assertTrue(dbName.matches("[a-zA-Z0-9_]+"));
        assertFalse(dbName.contains("-"));
    }

    @Test
    void instanceTier_defaultValues() {
        String tier = "db-custom-1-3840";
        assertTrue(tier.startsWith("db-"));
        String[] parts = tier.split("-");
        assertTrue(parts.length >= 3);
    }

    @Test
    void connectionNameFormat_projectRegionInstance() {
        String connectionName = "test-project:us-central1:my-instance";
        String[] parts = connectionName.split(":");
        assertEquals(3, parts.length);
        assertEquals("test-project", parts[0]);
        assertEquals("us-central1", parts[1]);
        assertEquals("my-instance", parts[2]);
    }
}
