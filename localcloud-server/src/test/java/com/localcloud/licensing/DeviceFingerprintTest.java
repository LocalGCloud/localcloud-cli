package com.localcloud.licensing;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DeviceFingerprintTest {

    @Test
    void computeReturnsConsistentHash() {
        String fp1 = DeviceFingerprint.compute();
        String fp2 = DeviceFingerprint.compute();
        assertNotNull(fp1);
        assertFalse(fp1.isBlank());
        assertEquals(fp1, fp2, "Fingerprint must be deterministic");
    }

    @Test
    void computeReturnsHexSha256() {
        String fp = DeviceFingerprint.compute();
        assertEquals(64, fp.length(), "SHA-256 hex should be 64 chars");
        assertTrue(fp.matches("[0-9a-f]{64}"), "Should be lowercase hex");
    }

    @Test
    void fromRawComponentsProducesDeterministicHash() {
        String fp1 = DeviceFingerprint.fromComponents("TestCPU", 8, 16384, "aa:bb:cc:dd:ee:ff", "SERIAL1", "6.5.0-generic");
        String fp2 = DeviceFingerprint.fromComponents("TestCPU", 8, 16384, "aa:bb:cc:dd:ee:ff", "SERIAL1", "6.5.0-generic");
        assertEquals(fp1, fp2);
    }

    @Test
    void differentComponentsProduceDifferentHash() {
        String fp1 = DeviceFingerprint.fromComponents("TestCPU", 8, 16384, "aa:bb:cc:dd:ee:ff", "SERIAL1", "6.5.0");
        String fp2 = DeviceFingerprint.fromComponents("TestCPU", 16, 16384, "aa:bb:cc:dd:ee:ff", "SERIAL1", "6.5.0");
        assertNotEquals(fp1, fp2, "Different core count should produce different fingerprint");
    }
}
