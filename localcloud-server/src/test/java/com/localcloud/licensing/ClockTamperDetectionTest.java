package com.localcloud.licensing;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.security.KeyPairGenerator;

import static org.junit.jupiter.api.Assertions.*;

class ClockTamperDetectionTest {

    @TempDir
    Path tempDir;

    @Test
    void clockRollbackIsNotDetectedOnFreshCache() {
        LicenseCache cache = new LicenseCache(tempDir, "test-device");
        LicenseResult result = LicenseResult.valid(LicenseTier.PRO, "test@example.com", "dev", 9999999999L);
        cache.write(result);

        // Freshly written cache — current time >= last-seen, no rollback
        boolean tampered = cache.detectClockRollback();
        assertFalse(tampered, "Fresh cache should not trigger rollback detection");
    }

    @Test
    void noExistingCacheDoesNotTriggerRollback() {
        // No cache file at all — cannot detect rollback, returns false
        LicenseCache cache = new LicenseCache(tempDir, "no-cache-device");
        assertFalse(cache.detectClockRollback(), "No cache = no rollback detection");
    }

    @Test
    void buildTimestampFloorIsInThePast() {
        long floor = LicenseManager.getBuildTimestampFloor();
        assertTrue(floor > 0);
        assertTrue(floor < System.currentTimeMillis() / 1000,
                "Build floor must be in the past");
        assertTrue(floor > 1577836800L, "Floor should be after 2020-01-01");
    }
}
