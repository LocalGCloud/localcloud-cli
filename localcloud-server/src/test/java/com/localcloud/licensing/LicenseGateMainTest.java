package com.localcloud.licensing;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for LicenseGateMain.run() — no SecurityManager tricks needed because
 * the logic is extracted into a package-private run() that returns an int exit code.
 */
class LicenseGateMainTest {

    @TempDir
    Path tempDir;

    @Test
    void devBypass_noKeyNoServer_returnsZeroAndWritesTier() throws Exception {
        Path outFile = tempDir.resolve("tier.txt");

        // No --key, default --server is "none" → bypass mode → PRO tier
        int exitCode = LicenseGateMain.run(new String[]{
                "--out", outFile.toString()
        });

        assertEquals(0, exitCode, "Dev bypass should succeed with exit code 0");
        assertTrue(Files.exists(outFile), "Tier file should be written");
        String tier = Files.readString(outFile).strip();
        assertEquals("pro", tier, "Bypass mode returns PRO tier");
    }

    @Test
    void invalidKeyFormat_returnsOne() throws Exception {
        Path outFile = tempDir.resolve("tier.txt");

        // A key that doesn't start with lco_ or lck_ is an unknown format → invalid
        int exitCode = LicenseGateMain.run(new String[]{
                "--key", "bad_key_format",
                "--server", "none",
                "--out", outFile.toString()
        });

        assertEquals(1, exitCode, "Invalid key format should fail with exit code 1");
    }
}
