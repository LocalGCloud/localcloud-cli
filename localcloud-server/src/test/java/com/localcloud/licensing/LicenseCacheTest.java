package com.localcloud.licensing;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class LicenseCacheTest {

    @TempDir
    Path tempDir;
    private LicenseCache cache;

    @BeforeEach
    void setUp() {
        cache = new LicenseCache(tempDir, "test-device-fingerprint");
    }

    @Test
    void writeAndReadRoundTrip() {
        LicenseResult original = LicenseResult.valid(LicenseTier.PRO, "test@example.com", "dev1", 9999999999L);
        cache.write(original);

        LicenseResult loaded = cache.read();
        assertNotNull(loaded);
        assertTrue(loaded.isValid());
        assertEquals(LicenseTier.PRO, loaded.tier());
        assertEquals("test@example.com", loaded.email());
    }

    @Test
    void tamperedFileReturnsNull() {
        LicenseResult original = LicenseResult.valid(LicenseTier.PRO, "test@example.com", "dev1", 9999999999L);
        cache.write(original);

        Path cacheFile = tempDir.resolve(".license").resolve("token.bin");
        try {
            byte[] bytes = Files.readAllBytes(cacheFile);
            bytes[10] ^= 0xFF; // flip some bits
            Files.write(cacheFile, bytes);
        } catch (Exception e) {
            fail("Could not tamper with file: " + e);
        }

        LicenseResult loaded = cache.read();
        assertNull(loaded, "Tampered cache should return null");
    }

    @Test
    void missingFileReturnsNull() {
        LicenseResult loaded = cache.read();
        assertNull(loaded);
    }

    @Test
    void differentDeviceFingerprintCannotReadCache() {
        LicenseResult original = LicenseResult.valid(LicenseTier.PRO, "test@example.com", "dev1", 9999999999L);
        cache.write(original);

        LicenseCache otherDeviceCache = new LicenseCache(tempDir, "different-device");
        LicenseResult loaded = otherDeviceCache.read();
        assertNull(loaded, "Different device fingerprint should not be able to read cache");
    }

    @Test
    void bootCounterIncrementsOnEachWrite() {
        LicenseResult result = LicenseResult.valid(LicenseTier.PRO, "test@example.com", "dev1", 9999999999L);
        cache.write(result);
        long count1 = cache.getBootCount();

        cache.write(result);
        long count2 = cache.getBootCount();

        assertEquals(count1 + 1, count2, "Boot counter should increment");
    }

    @Test
    void lastSeenTimestampIsRecorded() {
        LicenseResult result = LicenseResult.valid(LicenseTier.PRO, "test@example.com", "dev1", 9999999999L);
        long before = System.currentTimeMillis() / 1000;
        cache.write(result);
        long after = System.currentTimeMillis() / 1000;

        long lastSeen = cache.getLastSeenTimestamp();
        assertTrue(lastSeen >= before && lastSeen <= after, "Last seen should be within write window");
    }

    @Test
    void graceWindowCheck() {
        LicenseResult result = LicenseResult.valid(LicenseTier.PRO, "test@example.com", "dev1", 9999999999L);
        cache.write(result);
        assertTrue(cache.isWithinGracePeriod(72), "Freshly written cache should be within grace period");
    }
}
