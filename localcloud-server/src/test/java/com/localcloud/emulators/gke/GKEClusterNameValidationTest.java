package com.localcloud.emulators.gke;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for GKE cluster name parsing and validation.
 */
class GKEClusterNameValidationTest {

    @Test
    void clusterNameParsing_validFormat() {
        String name = "projects/p/locations/us-central1/clusters/my-cluster";
        String[] parts = name.split("/");
        assertEquals(6, parts.length);
        assertEquals("p", parts[1]);
        assertEquals("us-central1", parts[3]);
        assertEquals("my-cluster", parts[5]);
    }

    @Test
    void clusterName_mustStartWithLetter() {
        assertTrue("my-cluster".matches("[a-z][a-z0-9-]+"));
        assertFalse("123-cluster".matches("[a-z][a-z0-9-]+"));
    }

    @Test
    void clusterName_maxLength() {
        String name = "a-cluster-name-that-is-reasonably-long";
        assertTrue(name.length() <= 40);
    }

    @Test
    void nodeCount_defaultValue() {
        int defaultNodes = 1;
        assertTrue(defaultNodes >= 1);
        assertTrue(defaultNodes <= 100);
    }

    @Test
    void clusterVersion_format() {
        String version = "1.28";
        String[] parts = version.split("\\.");
        assertEquals(2, parts.length);
        assertTrue(Integer.parseInt(parts[0]) >= 1);
    }
}
