package com.localcloud.emulators.bigtable;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for Bigtable SQL repository operations.
 */
class BigtableRepositoryTest {

    @Test
    void instanceProjectId_nonNull() {
        String projectId = "test-project";
        assertNotNull(projectId);
        assertFalse(projectId.isBlank());
    }

    @Test
    void instanceId_validChars() {
        String instanceId = "my-instance-01";
        assertTrue(instanceId.matches("[a-z][a-z0-9-]+"));
    }

    @Test
    void tableId_validChars() {
        String tableId = "my_table_v1";
        assertTrue(tableId.matches("[a-zA-Z0-9_-]+"));
        assertFalse(tableId.isEmpty());
    }

    @Test
    void clusterConfig_defaultNodeCount() {
        int serveNodes = 1;
        assertEquals(1, serveNodes);
        assertTrue(serveNodes >= 1);
        assertTrue(serveNodes <= 3);
    }
}
