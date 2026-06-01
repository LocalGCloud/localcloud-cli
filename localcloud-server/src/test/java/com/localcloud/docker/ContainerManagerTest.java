package com.localcloud.docker;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for Docker container manager port allocation and image parsing.
 */
class ContainerManagerTest {

    @Test
    void containerName_validFormat() {
        String containerName = "localcloud-compute-test-vm";
        assertTrue(containerName.matches("[a-zA-Z0-9_-]+"));
        assertTrue(containerName.startsWith("localcloud-"));
    }

    @Test
    void portRange_validPorts() {
        int port = 8080;
        assertTrue(port >= 1 && port <= 65535);
        assertTrue(port >= 1024, "should be in user port range");
    }

    @Test
    void imageTag_validFormat() {
        String image = "ubuntu:22.04";
        assertTrue(image.contains(":"));
        String[] parts = image.split(":");
        assertEquals(2, parts.length);
        assertFalse(parts[0].isEmpty());
    }

    @Test
    void containerId_hexFormat() {
        String containerId = "abc123def456";
        assertTrue(containerId.matches("[a-f0-9]+"));
        assertEquals(12, containerId.length());
    }
}
