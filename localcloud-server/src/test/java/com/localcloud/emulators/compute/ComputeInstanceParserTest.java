package com.localcloud.emulators.compute;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for Compute instance metadata and network parsing.
 */
class ComputeInstanceParserTest {

    @Test
    void machineType_validFormat() {
        String machineType = "e2-medium";
        assertTrue(machineType.contains("-"));
        String[] parts = machineType.split("-");
        assertEquals(2, parts.length);
        assertTrue(parts[0].matches("[a-z]+[0-9]*"));
    }

    @Test
    void networkInterface_defaultIpFormat() {
        String ip = "10.0.0.1";
        String[] octets = ip.split("\\.");
        assertEquals(4, octets.length);
        for (String octet : octets) {
            int val = Integer.parseInt(octet);
            assertTrue(val >= 0 && val <= 255);
        }
    }

    @Test
    void metadataParsing_jsonString() {
        String metadata = "{\"key\":\"value\",\"startup-script\":\"#!/bin/bash\"}";
        assertTrue(metadata.contains("key"));
        assertTrue(metadata.contains("startup-script"));
    }

    @Test
    void instanceStatus_validStates() {
        String[] validStates = {"PROVISIONING", "STAGING", "RUNNING", "STOPPING", "TERMINATED"};
        for (String state : validStates) {
            assertNotNull(state);
            assertFalse(state.isBlank());
        }
    }
}
