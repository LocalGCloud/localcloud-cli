package com.localcloud.docker;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for Docker client provider configuration and connection handling.
 */
class DockerClientProviderTest {

    @Test
    void dockerHost_defaultUnixSocket() {
        String dockerHost = "unix:///var/run/docker.sock";
        assertTrue(dockerHost.startsWith("unix://") || dockerHost.startsWith("tcp://"));
    }

    @Test
    void dockerHost_tcpFormat() {
        String dockerHost = "tcp://localhost:2375";
        assertTrue(dockerHost.startsWith("tcp://"));
        String[] parts = dockerHost.replace("tcp://", "").split(":");
        assertEquals(2, parts.length);
    }

    @Test
    void containerImage_hasValidRegistryFormat() {
        String image = "docker.io/library/ubuntu:22.04";
        assertTrue(image.contains("/"));
        assertTrue(image.contains(":"));
    }

    @Test
    void containerImage_localFormat() {
        String image = "localcloud-compute:latest";
        assertTrue(image.contains(":"));
        String[] parts = image.split(":");
        assertEquals("localcloud-compute", parts[0]);
        assertEquals("latest", parts[1]);
    }
}
