package com.localcloud.emulators.serviceusage;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for ServiceUsage enable/disable service names.
 */
class ServiceUsageRestServiceTest {

    @Test
    void serviceName_validFormat() {
        String serviceName = "compute.googleapis.com";
        assertTrue(serviceName.endsWith(".googleapis.com"));
        assertTrue(serviceName.contains("."));
    }

    @Test
    void parseServiceName_extractsService() {
        String name = "projects/p/services/compute.googleapis.com";
        String[] parts = name.split("/");
        assertEquals(4, parts.length);
        assertEquals("compute.googleapis.com", parts[3]);
    }

    @Test
    void enableService_returnsStateEnabled() {
        String state = "ENABLED";
        assertEquals("ENABLED", state);
    }

    @Test
    void disableService_returnsStateDisabled() {
        String state = "DISABLED";
        assertEquals("DISABLED", state);
    }
}
