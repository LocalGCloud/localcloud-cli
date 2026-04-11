package com.localcloud.emulators;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for Compute Engine IP generation and instance ID logic.
 * Extracted from ComputeRestService -- tests the algorithmic logic
 * without requiring Armeria, Docker, or a database.
 */
class ComputeRestServiceTest {

    /**
     * Replicates the IP suffix generation logic from ComputeRestService.insertInstance:
     *   int ipSuffix = (name.hashCode() & 0xFF) + 2;
     *   String networkIp = "10.128.0." + Math.min(ipSuffix, 254);
     */
    private static String generateNetworkIp(String instanceName) {
        int ipSuffix = (instanceName.hashCode() & 0xFF) + 2;
        return "10.128.0." + Math.min(ipSuffix, 254);
    }

    /**
     * Replicates the instance ID generation from ComputeRestService.instanceToJson:
     *   inst.instanceName().hashCode() & 0x7FFFFFFF
     */
    private static int generateInstanceId(String instanceName) {
        return instanceName.hashCode() & 0x7FFFFFFF;
    }

    // --- IP generation ---

    @Test
    void generateNetworkIp_differentNamesProduceDifferentIps() {
        String ip1 = generateNetworkIp("web-server");
        String ip2 = generateNetworkIp("db-server");
        // While hash collisions are theoretically possible, these two
        // common strings should produce different results
        assertNotEquals(ip1, ip2,
                "Different instance names should generally produce different IPs");
    }

    @ParameterizedTest
    @ValueSource(strings = {"instance-1", "my-vm", "test", "web-server-prod",
            "a", "zzzzz", "instance-with-long-name-that-is-quite-verbose"})
    void generateNetworkIp_alwaysInValidRange(String name) {
        String ip = generateNetworkIp(name);
        assertTrue(ip.startsWith("10.128.0."), "IP should start with 10.128.0., got: " + ip);

        int lastOctet = Integer.parseInt(ip.substring("10.128.0.".length()));
        assertTrue(lastOctet >= 2, "Last octet must be >= 2, got: " + lastOctet);
        assertTrue(lastOctet <= 254, "Last octet must be <= 254, got: " + lastOctet);
    }

    @Test
    void generateNetworkIp_sameNameProducesSameIp() {
        String ip1 = generateNetworkIp("my-instance");
        String ip2 = generateNetworkIp("my-instance");
        assertEquals(ip1, ip2, "Same instance name should always produce the same IP");
    }

    @Test
    void generateNetworkIp_minimumSuffixIsTwo() {
        // hashCode() & 0xFF can be 0, so the minimum ipSuffix is 0 + 2 = 2
        // We verify this by testing many names and checking the range
        for (int i = 0; i < 100; i++) {
            String name = "test-instance-" + i;
            String ip = generateNetworkIp(name);
            int lastOctet = Integer.parseInt(ip.substring("10.128.0.".length()));
            assertTrue(lastOctet >= 2, "Minimum last octet should be 2, got: " + lastOctet);
        }
    }

    @Test
    void generateNetworkIp_maximumSuffixIsCapped() {
        // hashCode() & 0xFF can be up to 255, so ipSuffix can be up to 257
        // Math.min(ipSuffix, 254) caps it at 254
        // We verify the cap holds across many names
        for (int i = 0; i < 200; i++) {
            String name = "cap-test-" + i;
            String ip = generateNetworkIp(name);
            int lastOctet = Integer.parseInt(ip.substring("10.128.0.".length()));
            assertTrue(lastOctet <= 254, "Maximum last octet should be 254, got: " + lastOctet);
        }
    }

    // --- Instance ID generation ---

    @Test
    void generateInstanceId_alwaysNonNegative() {
        // The 0x7FFFFFFF mask clears the sign bit so the result is never negative
        String[] names = {"test", "instance-1", "", "a-very-long-instance-name",
                "negative-hash-check", "xyz", "123"};
        for (String name : names) {
            int id = generateInstanceId(name);
            assertTrue(id >= 0, "Instance ID must be non-negative for name '" + name + "', got: " + id);
        }
    }

    @Test
    void generateInstanceId_sameNameProducesSameId() {
        int id1 = generateInstanceId("my-instance");
        int id2 = generateInstanceId("my-instance");
        assertEquals(id1, id2);
    }

    @Test
    void generateInstanceId_differentNamesProduceDifferentIds() {
        int id1 = generateInstanceId("instance-a");
        int id2 = generateInstanceId("instance-b");
        assertNotEquals(id1, id2);
    }

    @Test
    void generateInstanceId_emptyNameIsNonNegative() {
        int id = generateInstanceId("");
        assertTrue(id >= 0);
    }

    @Test
    void generateInstanceId_nameWithNegativeHashCode() {
        // Find a string whose hashCode is negative and verify the mask fixes it
        // "AaBB" has a negative hashCode on most JVMs, but to be safe we
        // just test a batch and assert all are non-negative
        for (int i = 0; i < 500; i++) {
            String name = "neg-check-" + i;
            if (name.hashCode() < 0) {
                int id = generateInstanceId(name);
                assertTrue(id >= 0,
                        "Negative hashCode for '" + name + "' must be masked to non-negative, got: " + id);
            }
        }
    }
}
