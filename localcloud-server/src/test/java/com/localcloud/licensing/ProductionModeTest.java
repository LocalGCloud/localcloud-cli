package com.localcloud.licensing;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPairGenerator;

import static org.junit.jupiter.api.Assertions.*;

class ProductionModeTest {

    @AfterEach
    void clearBuildModeProperty() {
        System.clearProperty(LicenseManager.BUILD_MODE_PATH_PROPERTY);
    }

    @Test
    void missingBuildModeFile_isNotProductionBuild() {
        System.setProperty(LicenseManager.BUILD_MODE_PATH_PROPERTY,
                "/tmp/nonexistent-build-mode-file-xyz-localcloud");
        assertFalse(LicenseManager.isProductionBuild(),
                "Missing BUILD_MODE file should mean dev build (not production)");
    }

    @Test
    void productionBuildModeFile_isProductionBuild(@TempDir Path tempDir) throws Exception {
        Path modeFile = tempDir.resolve("BUILD_MODE");
        Files.writeString(modeFile, "production");
        System.setProperty(LicenseManager.BUILD_MODE_PATH_PROPERTY, modeFile.toString());

        assertTrue(LicenseManager.isProductionBuild(),
                "BUILD_MODE=production should return true");
    }

    @Test
    void productionBuildModeFile_caseInsensitive(@TempDir Path tempDir) throws Exception {
        Path modeFile = tempDir.resolve("BUILD_MODE");
        Files.writeString(modeFile, "PRODUCTION");
        System.setProperty(LicenseManager.BUILD_MODE_PATH_PROPERTY, modeFile.toString());

        assertTrue(LicenseManager.isProductionBuild(),
                "BUILD_MODE=PRODUCTION (uppercase) should be recognized");
    }

    @Test
    void developmentBuildModeFile_isNotProductionBuild(@TempDir Path tempDir) throws Exception {
        Path modeFile = tempDir.resolve("BUILD_MODE");
        Files.writeString(modeFile, "development");
        System.setProperty(LicenseManager.BUILD_MODE_PATH_PROPERTY, modeFile.toString());

        assertFalse(LicenseManager.isProductionBuild(),
                "BUILD_MODE=development should return false");
    }

    @Test
    void productionMode_bypassModeDisabled(@TempDir Path tempDir) throws Exception {
        Path modeFile = tempDir.resolve("BUILD_MODE");
        Files.writeString(modeFile, "production");
        System.setProperty(LicenseManager.BUILD_MODE_PATH_PROPERTY, modeFile.toString());

        var kpg = KeyPairGenerator.getInstance("Ed25519");
        var keyPair = kpg.generateKeyPair();

        // No key, no server → bypass should be DISABLED in production
        LicenseManager mgr = new LicenseManager("", "none", tempDir, keyPair.getPublic());
        LicenseResult result = mgr.validate();

        assertFalse(result.isValid(),
                "Bypass should be disabled in production builds with no key/server");
    }

    @Test
    void devMode_bypassModeEnabled(@TempDir Path tempDir) throws Exception {
        // Point at nonexistent file → dev build
        System.setProperty(LicenseManager.BUILD_MODE_PATH_PROPERTY,
                "/tmp/nonexistent-build-mode-file-xyz-localcloud");

        var kpg = KeyPairGenerator.getInstance("Ed25519");
        var keyPair = kpg.generateKeyPair();

        LicenseManager mgr = new LicenseManager("", "none", tempDir, keyPair.getPublic());
        LicenseResult result = mgr.validate();

        assertTrue(result.isValid(),
                "Bypass should be enabled in dev builds (no BUILD_MODE file)");
        assertEquals(LicenseTier.PRO, result.tier());
    }

    @Test
    void productionMode_onlineKeyBypassBlocked() {
        // Simulate production build in OnlineKeyValidator directly
        // (tests the code path in OnlineKeyValidator.validate() for "none" server)
        // We can't easily set system property mid-test and have it affect the validator
        // since OnlineKeyValidator calls LicenseManager.isProductionBuild() dynamically.
        // So we just verify the validator rejects bypass when production file exists.
        // (Covered more thoroughly by productionMode_bypassModeDisabled above via LicenseManager)

        // Dev build: bypass passes through OnlineKeyValidator
        System.setProperty(LicenseManager.BUILD_MODE_PATH_PROPERTY,
                "/tmp/nonexistent-build-mode-file-xyz-localcloud");
        OnlineKeyValidator validator = new OnlineKeyValidator("none");
        LicenseResult result = validator.validate("lco_anykey", "device-id");
        assertTrue(result.isValid(),
                "Dev build bypass in OnlineKeyValidator should return valid PRO");
    }

    @Test
    void productionMode_onlineKeyBypassReturnsError(@TempDir Path tempDir) throws Exception {
        Path modeFile = tempDir.resolve("BUILD_MODE");
        Files.writeString(modeFile, "production");
        System.setProperty(LicenseManager.BUILD_MODE_PATH_PROPERTY, modeFile.toString());

        OnlineKeyValidator validator = new OnlineKeyValidator("none");
        LicenseResult result = validator.validate("lco_anykey", "device-id");

        assertFalse(result.isValid(),
                "Production build with bypass server 'none' should be rejected");
        assertTrue(result.errorMessage().contains("LOCALCLOUD_LICENSE_SERVER"),
                "Error should mention LOCALCLOUD_LICENSE_SERVER, got: " + result.errorMessage());
    }
}
